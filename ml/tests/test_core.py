"""Pure-logic tests that need no models: filters, speech coverage, the stream segmenter's cap cut."""
import numpy as np

from earshot_ml import asr, vad
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
