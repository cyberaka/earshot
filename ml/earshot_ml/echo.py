"""Detects the call leaking into the mic (speakers instead of headphones), from the audio itself.

The system channel is what the call sounds like; if the mic channel's loudness rises and falls with
it a few ms later, the mic is hearing the call and its lines would be labelled as the user. Loudness
envelopes (10 ms log energy) are compared, not waveforms, so room echo and speaker colouring don't matter.
"""
from collections import deque

import numpy as np

from .config import SAMPLE_RATE

FRAME = SAMPLE_RATE // 100  # 10 ms
WINDOW = 400  # frames compared at a time (4 s)
EVERY = 100  # frames between checks (1 s)
LAGS = range(-10, 31)  # the mic may hear the call up to 300 ms after BlackHole gets it (or slightly before)
ACTIVE_RMS = 0.005  # call audio loud enough to judge
MIC_RMS = 0.004  # leakage quieter than this is below the mic's noise and never transcribed
CORRELATED = 0.5
KEEP = 10  # recent checks with call audio
LEAKING_AT = 4  # of KEEP, correlated: leaking


def _log_energy(x: np.ndarray) -> np.ndarray:
    n = len(x) // FRAME
    frames = x[: n * FRAME].reshape(n, FRAME)
    return np.log10(np.mean(frames ** 2, axis=1) + 1e-8)


class Detector:
    def __init__(self):
        self.mic: deque = deque(maxlen=WINDOW + max(LAGS) - min(LAGS) + 1)
        self.sys: deque = deque(maxlen=WINDOW + max(LAGS) - min(LAGS) + 1)
        self.mic_rest = np.zeros(0, dtype=np.float32)
        self.sys_rest = np.zeros(0, dtype=np.float32)
        self.since = 0
        self.recent: deque = deque(maxlen=KEEP)
        self.score = 0.0

    @property
    def leaking(self) -> bool:
        return sum(c >= CORRELATED for c in self.recent) >= LEAKING_AT

    def feed(self, mic: np.ndarray, system: np.ndarray) -> None:
        """Equal-length 16 kHz blocks of the two channels, recorded together."""
        self.mic_rest = np.concatenate([self.mic_rest, mic])
        self.sys_rest = np.concatenate([self.sys_rest, system])
        n = min(len(self.mic_rest), len(self.sys_rest)) // FRAME * FRAME
        if n == 0:
            return
        m, s = self.mic_rest[:n], self.sys_rest[:n]
        self.mic_rest, self.sys_rest = self.mic_rest[n:], self.sys_rest[n:]
        for e, r in zip(_log_energy(m), np.sqrt(np.mean(m.reshape(-1, FRAME) ** 2, axis=1))):
            self.mic.append((e, r))
        for e, r in zip(_log_energy(s), np.sqrt(np.mean(s.reshape(-1, FRAME) ** 2, axis=1))):
            self.sys.append((e, r))
        self.since += n // FRAME
        if self.since >= EVERY and len(self.mic) == self.mic.maxlen:
            self.since = 0
            self._check()

    def _check(self) -> None:
        mic = np.array(self.mic)
        sys = np.array(self.sys)
        pad = max(LAGS)
        # System window ends `pad` frames before the newest, so every lag has data on both sides.
        s_e, s_r = sys[-WINDOW - pad:-pad or None, 0], sys[-WINDOW - pad:-pad or None, 1]
        if np.sqrt(np.mean(s_r ** 2)) < ACTIVE_RMS:
            return  # nobody on the call is talking: nothing to judge
        best = 0.0
        for lag in LAGS:
            lo = len(mic) - WINDOW - pad + lag
            m_e, m_r = mic[lo:lo + WINDOW, 0], mic[lo:lo + WINDOW, 1]
            if np.sqrt(np.mean(m_r ** 2)) < MIC_RMS:
                continue
            c = np.corrcoef(m_e, s_e)[0, 1]
            if np.isfinite(c):
                best = max(best, float(c))
        self.recent.append(best)
        self.score = round(float(np.median(self.recent)), 2)
