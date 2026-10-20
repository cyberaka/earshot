"""Pure-logic tests that need no models: filters, speech coverage, the stream segmenter's cap cut."""
import numpy as np

from earshot_ml import asr, echo, vad
from earshot_ml.config import SAMPLE_RATE


def seg(**kw):
    base = dict(start=0.0, end=2.0, text="hello", avg_logprob=-0.2, no_speech_prob=0.1, compression_ratio=1.2)
    base.update(kw)
    return asr.Segment(**base)


def test_filters_drop_hallucinations():
    f = asr.Filters()
    assert asr.keep(seg(), f, 1.0)
    assert not asr.keep(seg(no_speech_prob=0.9, avg_logprob=-1.5), f, 1.0)
    assert not asr.keep(seg(compression_ratio=3.0), f, 1.0)
    assert not asr.keep(seg(), f, 0.1)  # text over silence


def test_speech_fraction():
    regions = [[0.0, 1.0], [2.0, 3.0]]
    assert vad.speech_fraction(regions, 0.0, 4.0) == 0.5
    assert vad.speech_fraction(regions, 5.0, 6.0) == 0.0


def test_cap_cut_keeps_overlap():
    s = vad.StreamSegmenter(max_seconds=2.0, overlap_s=0.5)
    s.in_speech = True
    s.buf = [np.ones(int(2.5 * SAMPLE_RATE), dtype=np.float32) * 0.1]
    s.buf_start = 0
    start, audio, cap = s._flush(cap=True)
    assert cap and start == 0.0
    kept = sum(len(b) for b in s.buf)
    # The carried-forward audio starts before the cut, so a word split by it is heard whole once.
    assert s.buf_start + kept == int(2.5 * SAMPLE_RATE)
    assert s.buf_start < len(audio)


def test_nan_scores_become_finite(monkeypatch):
    import json
    import math

    fake = {"segments": [{"start": 0.0, "end": 1.0, "text": "hi", "avg_logprob": float("nan"), "no_speech_prob": float("nan"),
                          "compression_ratio": 1.0, "words": [{"word": "hi", "start": 0.0, "end": 0.5, "probability": float("nan")}]}]}
    monkeypatch.setattr(asr.mlx_whisper, "transcribe", lambda *a, **k: fake)
    seg = asr.MlxWhisper().transcribe(np.zeros(SAMPLE_RATE, dtype=np.float32))[0]
    assert math.isfinite(seg.avg_logprob) and math.isfinite(seg.no_speech_prob)
    json.dumps(seg.words, allow_nan=False)


def _speechy(seconds: float, seed: int) -> np.ndarray:
    """Noise switched on and off in syllable-sized bursts: a loudness envelope like speech."""
    rng = np.random.default_rng(seed)
    n = int(seconds * SAMPLE_RATE)
    bursts = n // 1600 + 1
    env = ((rng.random(bursts) > 0.4) * rng.uniform(0.3, 1.0, bursts)).repeat(1600)[:n]
    return (rng.standard_normal(n) * 0.1 * env).astype(np.float32)


def _run(mic: np.ndarray, system: np.ndarray):
    d = echo.Detector()
    step = SAMPLE_RATE // 10
    for i in range(0, len(mic), step):
        d.feed(mic[i:i + step], system[i:i + step])
    return d


def test_echo_detects_call_in_mic():
    call = _speechy(20, 1)
    delay = int(0.04 * SAMPLE_RATE)  # speaker-to-mic path plus device latency
    leaked = np.concatenate([np.zeros(delay, np.float32), call[:-delay]]) * 0.3
    room = np.random.default_rng(2).standard_normal(len(call)).astype(np.float32) * 0.001
    assert _run(leaked + room, call).leaking


def test_echo_ignores_own_voice_with_headphones():
    call, me = _speechy(20, 1), _speechy(20, 3)
    assert not _run(me, call).leaking
    # Silent call: nothing to judge, never a warning.
    assert not _run(me, np.zeros_like(me)).leaking


class _Fake(asr.MlxWhisper):
    """Scripted results per language, to test the mixed-language choice without a model."""
    def __init__(self, results):
        self.results, self.calls = results, []

    def _transcribe(self, samples, language, initial_prompt):
        self.calls.append(language)
        return self.results[language]


def test_mixed_language_keeps_confident_english():
    e = _Fake({"en": [seg(text="so if I speak in English", avg_logprob=-0.3)], "hi": [seg(text="x", avg_logprob=-0.3)]})
    assert e.transcribe(np.zeros(SAMPLE_RATE), "en+hi")[0].text == "so if I speak in English"
    assert e.calls == ["en"]  # confident: Hindi is never tried


def test_mixed_language_switches_to_hindi():
    e = _Fake({"en": [seg(text="How to eat the food.", avg_logprob=-0.96)], "hi": [seg(text="हाथी के खाने के", avg_logprob=-0.29)]})
    assert e.transcribe(np.zeros(SAMPLE_RATE), "en+hi")[0].text == "हाथी के खाने के"
    # Hindi noise words never win, however confident.
    e = _Fake({"en": [seg(text="you", avg_logprob=-0.95)], "hi": [seg(text="झाल झाल", avg_logprob=-0.42)]})
    assert e.transcribe(np.zeros(SAMPLE_RATE), "en+hi")[0].text == "you"
