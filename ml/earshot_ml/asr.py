"""ASREngine (PRD §10.2): mlx-whisper on Apple Silicon."""
import math
import threading
from dataclasses import dataclass, field

import mlx_whisper
import numpy as np

from .config import SAMPLE_RATE, WHISPER_MODEL

# MLX shares one GPU; live and batch transcription take turns (PRD §8).
GPU_LOCK = threading.Lock()


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
        with GPU_LOCK:
            result = mlx_whisper.transcribe(
                samples.astype(np.float32), path_or_hf_repo=self.repo, word_timestamps=True,
                language=None if language in (None, "auto") else language,
                condition_on_previous_text=False, initial_prompt=initial_prompt, verbose=None,
                hallucination_silence_threshold=2.0,
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
