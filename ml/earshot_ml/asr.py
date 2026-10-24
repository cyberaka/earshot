"""ASREngine (PRD §10.2): mlx-whisper on Apple Silicon."""
import math
import re
import threading
from dataclasses import dataclass, field

import mlx.core as mx
import mlx_whisper
import numpy as np
from mlx_whisper import decoding
from mlx_whisper.audio import N_FRAMES, N_SAMPLES, log_mel_spectrogram, pad_or_trim
from mlx_whisper.transcribe import ModelHolder

from .config import SAMPLE_RATE, WHISPER_MODEL

# MLX shares one GPU; live and batch transcription take turns (PRD §8).
GPU_LOCK = threading.Lock()
# MLX keeps freed GPU buffers for reuse; every utterance length needs new sizes, so without a cap
# the pile grew to 16 GB in 40 hours and the Mac swapped live transcription to 1.6x real time
# (2026-10-24). 1 GB keeps reuse for the common sizes.
mx.set_cache_limit(1 << 30)

# Mixed languages ("en+hi"): each utterance is transcribed in the first language; unless Whisper is
# sure of it, the next is tried too, and kept only if it is clean, really in that language's script,
# and more confident. Measured 2026-10-20 on two real Hindi/English calls (115 utterances):
# - Hinglish ("sirf English words ko transcribe") scored -0.41 in English (a translation) and -0.28
#   in Hindi (right), so anything short of a sure English result is worth a Hindi pass.
# - Hindi forced onto English speech often loops ("इसी कि इसी कि ...") or drifts into other scripts,
#   and such loops score *better* (-0.2..-0.4) than the real English: the score alone can't decide.
# - English transliterated into Devanagari ("इस बेब तो लिसन इन") scores well below the English.
# - On muddy audio (a child on a phone) both passes are poor and Hindi "wins" with nonsense, so Hindi
#   must also be confident in itself; every real Hindi line seen scored -0.16..-0.47.
# The check is a text-only decode (~0.66 s: one encoder run, no word timing); only a Hindi win pays
# for the full pass with word timings.
TRY_NEXT_BELOW = -0.25
NEXT_MARGIN = 0.05
NEXT_AT_LEAST = -0.5
# Tokens per second of speech allowed in the check: room for fast Hindi, none for a long loop.
NEXT_TOKENS_PER_S = 15
SCRIPTS = {"hi": ("\u0900", "\u097f")}  # Devanagari
NOISE_WORDS = {"झाल"}


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
        if language and "+" in language:
            first, *others = language.split("+")
            best = self._transcribe(samples, first, initial_prompt)
            for other in others:
                if confidence(best) >= TRY_NEXT_BELOW and best:
                    break
                if self._better(self._check(samples, other), best, other):
                    # No prompt: text in the first language would pull the decoder back to it.
                    alt = self._transcribe(samples, other, None, temperature=0.0)
                    if self._better(alt, best, other):
                        best = alt
            return [g for g in best if not foreign(g, language)]
        segs = self._transcribe(samples, language, initial_prompt)
        return [g for g in segs if not foreign(g, language)]

    @staticmethod
    def _better(alt: list[Segment], best: list[Segment], language: str) -> bool:
        c = confidence(alt)
        return (c > confidence(best) + NEXT_MARGIN and c >= NEXT_AT_LEAST and in_script(alt, language)
                and not garbled(alt, language) and not only_noise(alt))

    def _check(self, samples: np.ndarray, language: str) -> list[Segment]:
        """Text and scores in another language from one quick decode: no word timing, no retries at
        higher temperature, and a length cap (a decoding loop is rejected, not decoded at length)."""
        if len(samples) > N_SAMPLES:
            return self._transcribe(samples, language, None, temperature=0.0)
        with GPU_LOCK:
            model = ModelHolder.get_model(self.repo, mx.float16)
            mel = pad_or_trim(log_mel_spectrogram(samples.astype(np.float32), n_mels=model.dims.n_mels), N_FRAMES, axis=-2)
            r = decoding.decode(model, mel.astype(mx.float16), decoding.DecodingOptions(
                language=language, without_timestamps=True, temperature=0.0,
                sample_len=min(224, int(NEXT_TOKENS_PER_S * len(samples) / SAMPLE_RATE) + 20)))
        text = r.text.strip()
        if not text:
            return []
        return [Segment(0.0, round(len(samples) / SAMPLE_RATE, 3), text, [], r.avg_logprob, r.no_speech_prob, r.compression_ratio)]

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


def garbled(segments: list[Segment], language: str) -> bool:
    """A decoding loop or a drift into other scripts (besides English and the language's own), not speech."""
    if any(s.compression_ratio > 2.0 for s in segments):
        return True
    text = " ".join(s.text for s in segments)
    lo, hi = SCRIPTS.get(language, ("", "\U0010ffff"))
    if any(c.isalpha() and not c.isascii() and not lo <= c <= hi for c in text):
        return True
    words = text.split()
    for n in (1, 2, 3):
        for i in range(len(words) - 4 * n + 1):
            if words[i:i + n] * 4 == words[i:i + 4 * n]:
                return True
    return False


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


def foreign(seg: Segment, language: str | None) -> bool:
    """Text in a writing system nobody was speaking: with English and/or Hindi set, Whisper sometimes
    writes noise as Japanese or Cyrillic ("やあみし", ISB class 2026-10-24). Latin with accents and
    Devanagari are fine; with `auto` anything goes."""
    if not language or language == "auto" or not set(language.split("+")) <= {"en", "hi"}:
        return False
    return any(c.isalpha() and not (c <= "\u024f" or "\u0900" <= c <= "\u097f") for c in seg.text)


def in_script(segments: list[Segment], language: str) -> bool:
    """Whisper in Hindi mode sometimes just writes the English it heard: then the English pass is better."""
    lo, hi = SCRIPTS.get(language, ("", "\U0010ffff"))
    return any(lo <= c <= hi for s in segments for c in s.text if c.isalpha())


def only_noise(segments: list[Segment]) -> bool:
    words = [w.strip(".,!?।") for s in segments for w in s.text.split()]
    return all(w in NOISE_WORDS for w in words)


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
