"""Live sessions (PRD §6.4): per-channel VAD segmentation, one ASR worker, events to listeners."""
import asyncio
import logging
import queue
import threading
import time
from dataclasses import dataclass, field

import numpy as np

from . import asr, speakers
from .config import SAMPLE_RATE
from .vad import StreamSegmenter

log = logging.getLogger(__name__)


REGROUP_EVERY_S = 30.0


@dataclass
class Session:
    id: str
    language: str = "en"
    max_segment_s: float = 15.0
    segmenters: dict[str, StreamSegmenter] = field(default_factory=dict)
    received_s: dict[str, float] = field(default_factory=dict)
    last_cut_end: dict[str, float] = field(default_factory=dict)
    listeners: set = field(default_factory=set)
    work: "queue.Queue" = field(default_factory=queue.Queue)
    loop: asyncio.AbstractEventLoop | None = None
    stopped: bool = False
    backlog_s: float = 0.0
    # Live speaker separation per channel; emitted segments by key, for relabelling after regrouping.
    diarizers: dict[str, "speakers.LiveDiarizer"] = field(default_factory=dict)
    emitted: dict[str, list] = field(default_factory=dict)
    next_regroup: dict[str, float] = field(default_factory=dict)
    seq: int = 0


SESSIONS: dict[str, Session] = {}


def start(session_id: str, language: str, max_segment_s: float, loop, seeds: dict | None = None) -> Session:
    s = SESSIONS.get(session_id)
    if s is None or s.stopped:
        s = Session(session_id, language, max_segment_s, loop=loop)
        SESSIONS[session_id] = s
        # Speakers from earlier parts of a resumed meeting.
        for channel, known in (seeds or {}).items():
            d = s.diarizers.setdefault(channel, speakers.LiveDiarizer())
            for k in known:
                d.seed(k["label"], np.array(k["embedding"], dtype=np.float32))
        threading.Thread(target=_worker, args=(s,), daemon=True, name=f"live-asr-{session_id}").start()
        # Load Whisper now, not on the first sentence: a cold model adds ~10 s to the first line.
        threading.Thread(target=lambda: asr.engine().transcribe(np.zeros(SAMPLE_RATE, dtype=np.float32)), daemon=True).start()
    return s


def stop(session_id: str) -> None:
    s = SESSIONS.get(session_id)
    if s:
        for channel, seg in s.segmenters.items():
            if seg.in_speech and seg.buf:
                start_s, audio, cap = seg._flush(cap=False)
                _enqueue(s, channel, start_s, audio, cap)
        s.work.put(None)
        s.stopped = True


def feed(s: Session, channel: str, pcm16: bytes) -> None:
    samples = np.frombuffer(pcm16, dtype=np.int16).astype(np.float32) / 32768.0
    seg = s.segmenters.get(channel)
    if seg is None:
        seg = s.segmenters[channel] = StreamSegmenter(max_seconds=s.max_segment_s)
    s.received_s[channel] = s.received_s.get(channel, 0.0) + len(samples) / SAMPLE_RATE
    for start_s, audio, cap in seg.feed(samples):
        _enqueue(s, channel, start_s, audio, cap)


def _enqueue(s: Session, channel: str, start_s: float, audio: np.ndarray, cap: bool) -> None:
    s.backlog_s += len(audio) / SAMPLE_RATE
    s.work.put((channel, start_s, audio, cap, time.monotonic()))


def _emit(s: Session, event: dict) -> None:
    if s.loop is None:
        return
    for ws in list(s.listeners):
        asyncio.run_coroutine_threadsafe(_send(s, ws, event), s.loop)


async def _send(s: Session, ws, event: dict) -> None:
    try:
        await ws.send_json(event)
    except Exception:
        s.listeners.discard(ws)


def _worker(s: Session) -> None:
    while True:
        item = s.work.get()
        if item is None:
            for channel in list(s.diarizers):
                _regroup(s, channel)
            _emit(s, {"type": "stopped"})
            return
        channel, start_s, audio, cap, queued_at = item
        dur = len(audio) / SAMPLE_RATE
        t0 = time.monotonic()
        try:
            segments = asr.engine().transcribe(audio, s.language)
        except Exception as e:
            log.exception("event=live_asr_failed session=%s", s.id)
            _emit(s, {"type": "error", "message": str(e)})
            segments = []
        s.backlog_s = max(0.0, s.backlog_s - dur)
        cut_end = s.last_cut_end.pop(channel, None)
        windows = _speaker_windows(s, channel, audio, start_s) if segments else []
        for seg in segments:
            words = [{**w, "s": round(w["s"] + start_s, 3), "e": round(w["e"] + start_s, 3)} for w in seg.words]
            if cut_end is not None:
                # Drop words heard again in the 1 s overlap after a cap cut.
                words = [w for w in words if w["s"] >= cut_end - 0.15]
                if not words:
                    continue
            confidence = round(float(np.exp(seg.avg_logprob)), 3)
            runs = _split_by_speaker(words, windows)
            whole = len(runs) == 1 and cut_end is None
            for label, run in runs:
                s.seq += 1
                key = f"{channel}-{s.seq}"
                text = seg.text if whole else " ".join(w["w"] for w in run)
                start = run[0]["s"] if run else seg.start + start_s
                end = run[-1]["e"] if run else seg.end + start_s
                s.emitted.setdefault(channel, []).append([key, start, end, label])
                _emit(s, {"type": "segment", "channel": channel, "key": key, "speaker": label,
                          "start_s": round(start, 3), "end_s": round(end, 3), "text": text, "words": run, "confidence": confidence})
        if windows:
            _emit(s, {"type": "speakers", "channel": channel, "speakers": s.diarizers[channel].snapshot()})
        if cap:
            s.last_cut_end[channel] = start_s + dur
        if start_s + dur >= s.next_regroup.get(channel, REGROUP_EVERY_S):
            s.next_regroup[channel] = start_s + dur + REGROUP_EVERY_S
            _regroup(s, channel)
        # Lag: how long after the segment ended (in wall time) its text was ready, plus queued audio.
        _emit(s, {"type": "status", "lag_s": round(time.monotonic() - queued_at + s.backlog_s, 2),
                  "asr_rtf": round((time.monotonic() - t0) / max(dur, 0.01), 3),
                  "received_s": {k: round(v, 1) for k, v in s.received_s.items()}})


def _speaker_windows(s: Session, channel: str, audio: np.ndarray, start_s: float) -> list[tuple[float, float, str]]:
    d = s.diarizers.get(channel)
    if d is None:
        d = s.diarizers[channel] = speakers.LiveDiarizer()
    try:
        return d.add(audio, start_s)
    except Exception:
        log.exception("event=live_diarize_failed session=%s channel=%s", s.id, channel)
        return []


def _split_by_speaker(words: list[dict], windows: list[tuple[float, float, str]]) -> list[tuple[str | None, list[dict]]]:
    """Consecutive words of one speaker form one line; a word takes the label of the nearest window."""
    if not windows:
        return [(None, words)]
    centres = [((a + b) / 2, lab) for a, b, lab in windows]
    runs: list[tuple[str | None, list[dict]]] = []
    for w in words:
        mid = (w["s"] + w["e"]) / 2
        lab = min(centres, key=lambda c: abs(c[0] - mid))[1]
        if runs and runs[-1][0] == lab:
            runs[-1][1].append(w)
        else:
            runs.append((lab, [w]))
    return runs or [(windows[0][2], words)]


def _regroup(s: Session, channel: str) -> None:
    """Re-clusters the channel's voices; lines whose speaker changed are relabelled in place."""
    d = s.diarizers.get(channel)
    if d is None:
        return
    try:
        changed = d.recluster()
    except Exception:
        log.exception("event=live_regroup_failed session=%s", s.id)
        return
    if changed:
        changes = []
        for seg in s.emitted.get(channel, []):
            new = d.label_of(seg[1], seg[2])
            if new and new != seg[3]:
                seg[3] = new
                changes.append({"key": seg[0], "speaker": new})
        if changes:
            _emit(s, {"type": "relabel", "channel": channel, "changes": changes})
            log.info("event=live_relabel session=%s channel=%s changes=%d", s.id, channel, len(changes))
    _emit(s, {"type": "speakers", "channel": channel, "speakers": d.snapshot()})
