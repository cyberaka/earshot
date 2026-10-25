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
    """Scripted decodes, to test what English + Hindi asks for and keeps without a model."""
    def __init__(self, segments):
        self.segments, self.calls = segments, []

    def _transcribe(self, samples, language, initial_prompt, **decode):
        self.calls.append((language, initial_prompt))
        return self.segments


def test_english_hindi_is_hinglish_in_latin_letters():
    # Owner, 2026-10-25: Hinglish in Latin letters, no Devanagari. One English decode with a romanized example.
    e = _Fake([seg(text="Data engineering totally aapka tha"), seg(text="डेटा एंजिनरिंग"), seg(text="Haan bhai, theek hai."),
               seg(text="So if I speak in English")])
    out = [g.text for g in e.transcribe(np.zeros(SAMPLE_RATE), "en+hi", initial_prompt="Names and terms: Thanveer.")]
    assert out == ["Data engineering totally aapka tha", "So if I speak in English"]  # Devanagari and the echoed example dropped
    assert e.calls == [("en", asr.HINGLISH_PROMPT + " Names and terms: Thanveer.")]


def test_hindi_alone_still_writes_devanagari():
    e = _Fake([seg(text="हाथी के खाने के")])
    assert [g.text for g in e.transcribe(np.zeros(SAMPLE_RATE), "hi")] == ["हाथी के खाने के"]
    assert e.calls == [("hi", None)]


def test_live_stop_drops_queued_audio():
    """Stop must not keep the GPU busy on lines nobody will receive (BUGS.md B-2)."""
    import queue as q
    from earshot_ml import live
    s = live.Session("t-stop")
    live.SESSIONS["t-stop"] = s
    for i in range(5):
        live._enqueue(s, "system", float(i * 10), np.zeros(SAMPLE_RATE, dtype=np.float32), False)
    live.stop("t-stop")
    assert s.stopped and s.backlog_s == 0.0
    assert s.work.get_nowait() is None  # only the end marker is left
    try:
        s.work.get_nowait()
        assert False, "queue should be empty"
    except q.Empty:
        pass
    del live.SESSIONS["t-stop"]


def test_live_lag_is_age_of_oldest_waiting_audio():
    """Lag counts each second once (BUGS.md B-5): 1060 s 'lag' after 1013 s of meeting can't happen."""
    from earshot_ml import live
    s = live.Session("t-lag")
    s.received_s = {"system": 100.0, "mic": 100.0}
    # Nothing waiting: how far the room moved on since the line just made.
    assert live.lag(s, "system", 97.0) == 3.0
    # Audio from 40 s and 70 s still queued: the oldest decides.
    live._enqueue(s, "system", 40.0, np.zeros(SAMPLE_RATE, dtype=np.float32), False)
    live._enqueue(s, "mic", 70.0, np.zeros(SAMPLE_RATE, dtype=np.float32), False)
    assert live.lag(s, "system", 39.0) == 60.0
    assert live.lag(s, "system", 39.0) <= max(s.received_s.values())


def test_framed_stream_keeps_file_time():
    """A lost frame becomes silence and a resent one is skipped (BUGS.md B-9)."""
    import struct
    from earshot_ml import live
    s = live.Session("t-frame")
    frame = np.zeros(1600, dtype=np.int16).tobytes()  # 0.1 s
    live.feed_framed(s, "mic", struct.pack("<q", 0) + frame)
    live.feed_framed(s, "mic", struct.pack("<q", 3200) + frame)   # 1600..3200 lost
    live.feed_framed(s, "mic", struct.pack("<q", 3200) + frame)   # sent twice
    assert abs(s.received_s["mic"] - 4800 / SAMPLE_RATE) < 1e-9
    # A stream that starts mid-recording keeps the recording's clock.
    live.feed_framed(s, "system", struct.pack("<q", 16000 * 60) + frame)
    assert abs(s.received_s["system"] - 60.1) < 1e-9 and s.segmenters["system"].pos >= 16000 * 60


def test_foreign_scripts_dropped_only_for_en_hi():
    assert asr.foreign(seg(text="やあみし"), "en+hi")
    assert asr.foreign(seg(text="Если"), "en")
    assert asr.foreign(seg(text="इनोवेशन is key"), "en+hi")  # Hinglish is written in Latin letters
    assert not asr.foreign(seg(text="इनोवेशन"), "hi")
    assert not asr.foreign(seg(text="café résumé"), "en")
    assert not asr.foreign(seg(text="やあみし"), "auto")


def test_vocabulary_prompt_and_its_echo():
    """R-3: names go to Whisper as a prompt; a line made only of prompt words is Whisper repeating it."""
    p = asr.vocabulary_prompt(["Abhinav", " Rapido ", "Abhinav", ""])
    assert p == "Names and terms: Abhinav, Rapido."
    assert asr.vocabulary_prompt([]) is None
    assert asr.echoes_prompt(seg(text="Abhinav, Rapido."), p)
    assert asr.echoes_prompt(seg(text="Names and terms"), p)
    assert not asr.echoes_prompt(seg(text="Thank you, Abhinav."), p)
    assert not asr.echoes_prompt(seg(text="Rapido"), p)  # one word: could be said
    assert not asr.echoes_prompt(seg(text="Abhinav, Rapido."), None)


def test_batch_work_yields_to_a_live_meeting(monkeypatch):
    # Back-to-back meetings (owner rule, 2026-10-24): batch work hands the GPU back once a meeting is live.
    import pytest
    from fastapi import HTTPException

    from earshot_ml import app, live

    monkeypatch.setattr(live, "SESSIONS", {})
    assert not live.active()
    app.yield_to_live()  # nothing live: batch work goes on
    live.SESSIONS["m1"] = live.Session("m1")
    assert live.active()
    with pytest.raises(HTTPException) as e:
        app.yield_to_live()
    assert e.value.status_code == 409 and "live meeting" in e.value.detail
    app.yield_to_live("1")  # the user clicked for this during the meeting: it runs anyway
    live.SESSIONS["m1"].stopped = True  # Stop: the next meeting's catch-up and re-runs may continue
    assert not live.active()
    app.yield_to_live()


def test_aggregate_parts_shape():
    # Core Audio may report nothing (no aggregates, or not macOS); whatever it reports is in channel order.
    from earshot_ml import coreaudio
    for name, items in coreaudio.parts().items():
        first = 0
        for it in items:
            assert it["first"] == first and it["channels"] > 0 and it["name"]
            first += it["channels"]


def test_live_speaker_threshold_reaches_the_diarizer():
    # Setting live_speaker_threshold (BUGS.md B-14): 0.60 by default, clamped to a sane range.
    from earshot_ml import live
    try:
        s = live.start("t-thr", "en", 15.0, None, speaker_threshold=0.6)
        live._speaker_windows(s, "system", np.zeros(SAMPLE_RATE * 2, dtype=np.float32), 0.0)
        assert s.diarizers["system"].threshold == 0.6
        assert live.start("t-thr2", "en", 15.0, None, speaker_threshold=5).speaker_threshold == 0.9
    finally:
        for k in ("t-thr", "t-thr2"):
            if k in live.SESSIONS:
                live.stop(k)
                del live.SESSIONS[k]


def test_long_speech_is_embedded_in_pieces():
    # 14 min joined into one embedding scored 0.23 against the speaker's own voiceprint (24 Oct class).
    from earshot_ml import speakers
    sr = 16000
    assert speakers.piece_bounds([sr * 100] * 9, sr * 300) == [(0, 3), (3, 6), (6, 9)]
    assert speakers.piece_bounds([sr * 10], sr * 300) == [(0, 1)]
    assert speakers.piece_bounds([sr * 400, sr * 10], sr * 300) == [(0, 1), (1, 2)]


def test_batch_diarizer_is_unloaded_after_use(monkeypatch):
    # Kept loaded, community-1 left the speech engine at 6 GB and the next live meeting swapping (25 Oct).
    from earshot_ml import speakers

    class Pipe:
        def to(self, _device):
            return self

    loads = []
    monkeypatch.setattr(speakers, "_pipeline", None)
    monkeypatch.setattr(speakers, "_pipeline_missing", False)
    monkeypatch.setattr("pyannote.audio.Pipeline.from_pretrained", lambda _name: loads.append(1) or Pipe())
    monkeypatch.setattr(speakers, "_diarize_pipeline", lambda _p, _s, off: [{"start": off, "end": off + 1.0, "speaker": "A"}])
    monkeypatch.setattr(speakers, "embed_ranges", lambda *_a: np.ones(4, dtype=np.float32))
    for _ in range(2):
        out = speakers.diarize(np.zeros(SAMPLE_RATE, dtype=np.float32), [[0.0, 1.0]], 0.0)
        assert out["backend"] == "community-1" and speakers._pipeline is None
    assert len(loads) == 2
