"""Silero VAD over whole files (in blocks) and over live streams."""
import threading

import numpy as np
import torch
from silero_vad import VADIterator, get_speech_timestamps, load_silero_vad

from . import audio
from .config import SAMPLE_RATE

_model = None
_lock = threading.RLock()


def model():
    global _model
    with _lock:
        if _model is None:
            _model = load_silero_vad()
        return _model


def speech_regions(path: str, min_silence_ms: int = 300) -> list[list[float]]:
    """Speech regions in seconds over the whole file, merged across block boundaries."""
    regions: list[list[float]] = []
    for offset, block in audio.blocks(path):
        with _lock:
            stamps = get_speech_timestamps(
                torch.from_numpy(block), model(), sampling_rate=SAMPLE_RATE,
                min_silence_duration_ms=min_silence_ms, return_seconds=True,
            )
        for s in stamps:
            start, end = offset + s["start"], offset + s["end"]
            if regions and start - regions[-1][1] < min_silence_ms / 1000:
                regions[-1][1] = end
            else:
                regions.append([round(start, 3), round(end, 3)])
    return regions


def speech_fraction(regions: list[list[float]], start: float, end: float) -> float:
    if end <= start:
        return 0.0
    covered = sum(max(0.0, min(e, end) - max(s, start)) for s, e in regions)
    return covered / (end - start)


class StreamSegmenter:
    """Cuts a live stream into utterances at pauses, capped at max_seconds (PRD §6.4).

    A cap cut keeps `overlap_s` of audio at the start of the next segment so a word split by
    the cut is heard whole once; the duplicated words are removed when stitching.
    """

    def __init__(self, max_seconds: float = 15.0, overlap_s: float = 1.0, min_silence_ms: int = 500):
        self.it = VADIterator(load_silero_vad(), sampling_rate=SAMPLE_RATE, min_silence_duration_ms=min_silence_ms)
        self.max_samples = int(max_seconds * SAMPLE_RATE)
        self.overlap = int(overlap_s * SAMPLE_RATE)
        self.pending = np.zeros(0, dtype=np.float32)
        self.buf: list[np.ndarray] = []
        self.buf_start = 0  # sample index of buf[0]
        self.pos = 0  # samples consumed so far
        self.in_speech = False
        self.preroll: list[np.ndarray] = []  # ~0.3 s before a detected start

    def feed(self, samples: np.ndarray) -> list[tuple[float, np.ndarray, bool]]:
        """Returns finished segments as (start_s, audio, was_cap_cut)."""
        out = []
        self.pending = np.concatenate([self.pending, samples])
        while len(self.pending) >= 512:
            frame, self.pending = self.pending[:512], self.pending[512:]
            event = self.it(torch.from_numpy(frame.copy()))
            if event and "start" in event and not self.in_speech:
                self.in_speech = True
                self.buf = self.preroll + [frame]
                self.buf_start = self.pos - 512 * len(self.preroll)
            elif self.in_speech:
                self.buf.append(frame)
            else:
                self.preroll = (self.preroll + [frame])[-10:]
            self.pos += 512
            if self.in_speech and event and "end" in event:
                out.append(self._flush(cap=False))
            elif self.in_speech and sum(len(b) for b in self.buf) >= self.max_samples:
                out.append(self._flush(cap=True))
        return out

    def _flush(self, cap: bool) -> tuple[float, np.ndarray, bool]:
        seg = np.concatenate(self.buf)
        start = self.buf_start / SAMPLE_RATE
        if cap:
            # Cut at the quietest 100 ms in the last 3 s, carry the tail plus overlap forward.
            tail = seg[-3 * SAMPLE_RATE:]
            win = SAMPLE_RATE // 10
            energy = [float(np.mean(tail[i:i + win] ** 2)) for i in range(0, max(1, len(tail) - win), win)]
            cut = len(seg) - len(tail) + int(np.argmin(energy)) * win + win // 2
            keep_from = max(0, cut - self.overlap)
            self.buf = [seg[keep_from:]]
            self.buf_start += keep_from
            return start, seg[:cut], True
        self.buf = []
        self.preroll = []
        self.in_speech = False
        return start, seg, False
