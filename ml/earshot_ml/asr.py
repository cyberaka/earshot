"""ASREngine (PRD §10.2): mlx-whisper on Apple Silicon."""
import math
import re
import threading
from dataclasses import dataclass, field

import mlx.core as mx
import mlx_whisper
import numpy as np

from .config import SAMPLE_RATE, SECOND_OPINION_MODEL, WHISPER_MODEL

# MLX shares one GPU; live and batch transcription take turns (PRD §8).
GPU_LOCK = threading.Lock()
# MLX keeps freed GPU buffers for reuse; every utterance length needs new sizes, so without a cap
# the pile grew to 16 GB in 40 hours and the Mac swapped live transcription to 1.6x real time
# (2026-10-24). 1 GB keeps reuse for the common sizes.
mx.set_cache_limit(1 << 30)

# English + Hindi is written as Hinglish in Latin letters (owner, 2026-10-25: "I prefer Hinglish in
# Latin letters. Avoid Devanagari"): one English decode, steered by an example of romanized Hinglish.
# Without it, Hindi speech decoded as English comes out translated ("Data Engineering was totally
# your own"); with it, as said ("Data engineering totally aapka tha"), English terms kept in English.
# Measured on the 23 Oct recorder day, conversation 30.
HINGLISH_PROMPT = "Haan bhai, theek hai. Mujhe lagta hai ki yeh kaam aaj karna hai, data ka issue dekh lo."


@dataclass
class Filters:
    """Hallucination filters (PRD §5.2 step 8)."""
    no_speech_prob: float = 0.6
    avg_logprob: float = -1.0
    compression_ratio: float = 2.4
    min_speech_fraction: float = 0.3


@dataclass
class Segment:
    start: float
    end: float
    text: str
    words: list[dict] = field(default_factory=list)
    avg_logprob: float = 0.0
    no_speech_prob: float = 0.0
    compression_ratio: float = 0.0


class ASREngine:
    def transcribe(self, samples: np.ndarray, language: str | None = "en") -> list[Segment]:
        raise NotImplementedError


class MlxWhisper(ASREngine):
    def __init__(self, repo: str = WHISPER_MODEL):
        self.repo = repo

    def transcribe(self, samples: np.ndarray, language: str | None = "en", initial_prompt: str | None = None) -> list[Segment]:
        if len(samples) < SAMPLE_RATE // 4:
            return []
        if hinglish(language):
            prompt = " ".join(p for p in (HINGLISH_PROMPT, initial_prompt) if p)
            segs = self._transcribe(samples, "en", prompt)
            return [g for g in segs if g.text.strip() and not foreign(g, "en") and not echoes_prompt(g, HINGLISH_PROMPT)]
        segs = self._transcribe(samples, language, initial_prompt)
        return [g for g in segs if not foreign(g, language)]

    def _transcribe(self, samples: np.ndarray, language: str | None, initial_prompt: str | None, **decode) -> list[Segment]:
        with GPU_LOCK:
            result = mlx_whisper.transcribe(
                samples.astype(np.float32), path_or_hf_repo=self.repo, word_timestamps=True,
                language=None if language in (None, "auto") else language,
                condition_on_previous_text=False, initial_prompt=initial_prompt, verbose=None,
                hallucination_silence_threshold=2.0, **decode,
            )
        segments = []

        def num(v, default):
            # Whisper occasionally yields NaN scores; JSON cannot carry them (a 500 after a long run).
            return default if v is None or not math.isfinite(v) else v

        for s in result.get("segments", []):
            text = s["text"].strip()
            if not text:
                continue
            words = [{"w": w["word"].strip(), "s": round(num(w["start"], s["start"]), 3), "e": round(num(w["end"], s["end"]), 3),
                      "p": round(num(w.get("probability"), 0.0), 3)} for w in s.get("words", []) if w["word"].strip()]
            segments.append(Segment(round(s["start"], 3), round(s["end"], 3), text, words,
                                    num(s.get("avg_logprob"), -1.0), num(s.get("no_speech_prob"), 0.0),
                                    num(s.get("compression_ratio"), 0.0)))
        return segments


def confidence(segments: list[Segment]) -> float:
    """Duration-weighted mean log probability; nothing heard counts as unsure."""
    total = sum(max(s.end - s.start, 0.01) for s in segments)
    return sum(s.avg_logprob * max(s.end - s.start, 0.01) for s in segments) / total if segments else -1.0


def vocabulary_prompt(terms: list[str] | None) -> str | None:
    """Whisper's initial prompt from the user's names and terms (R-3): it biases spelling towards
    them ("Abhinav", not "Aveena"). Kept short: the prompt shares Whisper's 224-token context."""
    terms = [t.strip() for t in (terms or []) if t and t.strip()]
    if not terms:
        return None
    out, size = [], 0
    for t in dict.fromkeys(terms):
        if size + len(t) > 400:
            break
        out.append(t)
        size += len(t) + 2
    return "Names and terms: " + ", ".join(out) + "."


def echoes_prompt(seg: Segment, prompt: str | None) -> bool:
    """Whisper sometimes "hears" its own prompt in silence: a line made only of prompt words is that."""
    if not prompt:
        return False
    norm = lambda x: [w for w in re.sub(r"[^\w\s]", " ", x.lower()).split() if w]
    vocab, words = set(norm(prompt)), norm(seg.text)
    return len(words) >= 2 and all(w in vocab for w in words) or "names and terms" in seg.text.lower()


def hinglish(language: str | None) -> bool:
    return bool(language) and set(language.split("+")) == {"en", "hi"}


def foreign(seg: Segment, language: str | None) -> bool:
    """Text in a writing system nobody was speaking: with English and/or Hindi set, Whisper sometimes
    writes noise as Japanese or Cyrillic ("やあみし", ISB class 2026-10-24). Latin with accents and
    Devanagari are fine; with `auto` anything goes."""
    if not language or language == "auto" or not set(language.split("+")) <= {"en", "hi"}:
        return False
    devanagari_ok = "hi" in language.split("+") and not hinglish(language)
    return any(c.isalpha() and not (c <= "\u024f" or devanagari_ok and "\u0900" <= c <= "\u097f") for c in seg.text)


def keep(seg: Segment, f: Filters, speech_fraction: float) -> bool:
    if seg.no_speech_prob > f.no_speech_prob and seg.avg_logprob < f.avg_logprob:
        return False
    if seg.compression_ratio > f.compression_ratio:
        return False
    return speech_fraction >= f.min_speech_fraction


_engine: ASREngine | None = None


def engine() -> ASREngine:
    global _engine
    if _engine is None:
        _engine = MlxWhisper()
    return _engine


def second_opinion(samples: np.ndarray, language: str | None, initial_prompt: str | None) -> list[Segment]:
    """One clicked line through the full large-v3 (R-4): mlx-whisper holds one model at a time, so the
    everyday model is loaded back straight after, before live or batch work needs it."""
    from mlx_whisper.transcribe import ModelHolder
    try:
        return MlxWhisper(SECOND_OPINION_MODEL).transcribe(samples, language, initial_prompt)
    finally:
        with GPU_LOCK:
            ModelHolder.get_model(WHISPER_MODEL, mx.float16)
        mx.clear_cache()
