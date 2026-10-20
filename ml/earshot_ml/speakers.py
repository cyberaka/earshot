"""SpeakerEmbedder and Diarizer (PRD §10.2).

Community-1 is gated on Hugging Face. Until it is in the local cache, diarization falls back to
clustering WeSpeaker embeddings of short windows inside the VAD speech regions.
"""
import logging
import threading

import numpy as np
import torch
from scipy.cluster.hierarchy import fcluster, linkage

from .config import DIARIZATION_MODEL, EMBEDDING_MODEL, SAMPLE_RATE

log = logging.getLogger(__name__)
_lock = threading.RLock()
_embedder = None
_pipeline = None
_pipeline_tried = False


def _device():
    return torch.device("mps") if torch.backends.mps.is_available() else torch.device("cpu")


def embedder():
    global _embedder
    with _lock:
        if _embedder is None:
            from pyannote.audio import Inference, Model
            model = Model.from_pretrained(EMBEDDING_MODEL)
            _embedder = Inference(model, window="whole", device=_device())
        return _embedder


def embed(samples: np.ndarray) -> np.ndarray:
    """One L2-normalised embedding for a stretch of audio."""
    if len(samples) < SAMPLE_RATE // 2:
        samples = np.pad(samples, (0, SAMPLE_RATE // 2 - len(samples)))
    wave = torch.from_numpy(samples.astype(np.float32))[None, :]
    vec = np.asarray(embedder()({"waveform": wave, "sample_rate": SAMPLE_RATE}), dtype=np.float32).reshape(-1)
    return vec / (np.linalg.norm(vec) + 1e-9)


def embed_ranges(samples: np.ndarray, ranges: list[tuple[float, float]], offset: float = 0.0) -> np.ndarray:
    parts = [samples[int((s - offset) * SAMPLE_RATE):int((e - offset) * SAMPLE_RATE)] for s, e in ranges]
    parts = [p for p in parts if len(p) > 0]
    if not parts:
        raise ValueError("no audio in the given ranges")
    return embed(np.concatenate(parts))


def _community_pipeline():
    global _pipeline, _pipeline_tried
    with _lock:
        if not _pipeline_tried:
            _pipeline_tried = True
            try:
                from pyannote.audio import Pipeline
                _pipeline = Pipeline.from_pretrained(DIARIZATION_MODEL)
                _pipeline.to(_device())
                log.info("event=diarizer_loaded model=%s", DIARIZATION_MODEL)
            except Exception as e:  # not accepted or not cached yet
                log.warning("event=diarizer_fallback reason=%s", type(e).__name__)
        return _pipeline


def backend() -> str:
    return "community-1" if _community_pipeline() is not None else "embedding-clustering"


def diarize(samples: np.ndarray, regions: list[list[float]], offset: float, threshold: float = 0.68) -> dict:
    """Exclusive speaker turns plus one embedding per cluster.

    `regions` are VAD speech regions in file time; `offset` is the file time of samples[0].
    """
    pipe = _community_pipeline()
    if pipe is not None:
        turns = _diarize_pipeline(pipe, samples, offset)
    else:
        turns = _diarize_clustering(samples, regions, offset, threshold)
    clusters = []
    for label in sorted({t["speaker"] for t in turns}):
        mine = [(t["start"], t["end"]) for t in turns if t["speaker"] == label]
        speech = sum(e - s for s, e in mine)
        if speech < 0.5:
            continue
        clusters.append({"label": label, "speech_s": round(speech, 2),
                         "embedding": embed_ranges(samples, mine, offset).round(6).tolist()})
    keep = {c["label"] for c in clusters}
    return {"backend": backend(), "turns": [t for t in turns if t["speaker"] in keep], "clusters": clusters}


def _diarize_pipeline(pipe, samples, offset):
    wave = torch.from_numpy(samples.astype(np.float32))[None, :]
    out = pipe({"waveform": wave, "sample_rate": SAMPLE_RATE})
    ann = getattr(out, "exclusive_speaker_diarization", None) or getattr(out, "speaker_diarization", out)
    return [{"start": round(seg.start + offset, 3), "end": round(seg.end + offset, 3), "speaker": str(spk)}
            for seg, _, spk in ann.itertracks(yield_label=True)]


def _diarize_clustering(samples, regions, offset, threshold, win=3.0, hop=1.5, frame=0.25, min_cluster_s=3.0):
    """Average-linkage clustering of WeSpeaker embeddings over 3 s windows of speech.

    Measured 2026-10-19 on a real room recording (one YouTube narrator through laptop speakers, plus
    the owner) and on the synthetic set: with 1.5 s windows one voice in room audio scored a median
    0.33 against itself and split into 26 clusters; 3 s windows raise that to 0.49 while different
    voices stay near 0.1. Thresholds 0.65-0.70 got every case right; 0.68 is the middle.
    """
    end_s = offset + len(samples) / SAMPLE_RATE
    spans = [(max(rs, offset), min(re_, end_s)) for rs, re_ in regions]
    spans = [(s, e) for s, e in spans if e - s >= 0.4]
    windows = []
    for rs, re_ in spans:
        t = rs
        while True:
            windows.append((t, min(t + win, re_)))
            if t + win >= re_:
                break
            t += hop
    if not windows:
        return []
    vecs = np.stack([embed(samples[int((s - offset) * SAMPLE_RATE):int((e - offset) * SAMPLE_RATE)]) for s, e in windows])
    if len(windows) == 1:
        labels = np.array([1])
    else:
        labels = fcluster(linkage(vecs, method="average", metric="cosine"), t=threshold, criterion="distance")
    # Clusters covering under min_cluster_s are noise, not people: fold them into the nearest real one.
    cover = {lab: sum(e - s for (s, e), k in zip(windows, labels) if k == lab) for lab in set(labels)}
    big = [lab for lab, c in cover.items() if c >= min_cluster_s] or list(cover)
    centroids = {lab: vecs[labels == lab].mean(axis=0) for lab in big}
    labels = np.array([k if k in centroids else max(centroids, key=lambda b: float(vecs[i] @ centroids[b]))
                       for i, k in enumerate(labels)])
    centres = np.array([(s + e) / 2 for s, e in windows])
    # Each 250 ms frame of speech takes the label of the window centred nearest to it;
    # runs of one label become exclusive turns.
    turns: list[dict] = []
    for rs, re_ in spans:
        t = rs
        while t < re_:
            fe = min(t + frame, re_)
            label = f"SPEAKER_{labels[int(np.argmin(np.abs(centres - (t + fe) / 2)))]:02d}"
            if turns and turns[-1]["speaker"] == label and t - turns[-1]["end"] < 0.3:
                turns[-1]["end"] = round(fe, 3)
            else:
                turns.append({"start": round(t, 3), "end": round(fe, 3), "speaker": label})
            t = fe
    return turns


class LiveDiarizer:
    """Live speaker labels for one channel (PRD §6.3; owner request: name people during the meeting).

    Each utterance's 3 s windows get an instant label from the nearest speaker centroid (or start a
    new speaker). Every `recluster` call re-runs the offline clustering over all windows so far and
    maps the new clusters back onto the existing labels by overlap, so labels (and names the user
    gave them) stay stable while mistakes from the first seconds are corrected.

    Measured 2026-10-19: instant labels alone (running centroids) merged the two voices of a real
    12-minute podcast played through laptop speakers; periodic re-clustering is what separates them.
    """

    def __init__(self, threshold: float = 0.68, assign: float = 0.35, short_assign: float = 0.25,
                 win: float = 3.0, hop: float = 1.5, min_window: float = 1.2, min_cluster_s: float = 3.0,
                 voice_window: float = 2.0):
        self.threshold, self.assign, self.short_assign = threshold, assign, short_assign
        self.win, self.hop, self.min_window, self.min_cluster_s = win, hop, min_window, min_cluster_s
        # Only windows this long can found a voice: "Mm-hmm"s (1-2 s) sound unlike their speaker's talking
        # voice (measured 2026-10-20: 0.8-0.9 cosine apart on a headset) and would form a speaker of their own.
        self.voice_window = voice_window
        self.spans: list[tuple[float, float]] = []
        self.vecs: list[np.ndarray] = []
        self.labels: list[str] = []
        self.next_id = 1
        self.last: str | None = None

    def seed(self, label: str, vec: np.ndarray, weight_s: float = 30.0) -> None:
        """A speaker already known from an earlier part of the meeting: their voice is present from
        the start (as pseudo-windows far in the past), so they keep their label when heard again."""
        vec = np.asarray(vec, dtype=np.float32)
        vec = vec / (np.linalg.norm(vec) + 1e-9)
        base = -1e9 - len(self.spans) * 10
        for i in range(max(1, int(weight_s // self.win))):
            self.spans.append((base + i * self.win, base + (i + 1) * self.win))
            self.vecs.append(vec)
            self.labels.append(label)

    def _centroids(self) -> dict[str, np.ndarray]:
        out: dict[str, np.ndarray] = {}
        for lab in set(self.labels):
            idx = [i for i, x in enumerate(self.labels) if x == lab and self.spans[i][1] - self.spans[i][0] >= self.min_window]
            if not idx:
                idx = [i for i, x in enumerate(self.labels) if x == lab]
            v = np.mean([self.vecs[i] for i in idx], axis=0)
            out[lab] = v / (np.linalg.norm(v) + 1e-9)
        return out

    def _new_label(self) -> str:
        lab = f"L{self.next_id}"
        self.next_id += 1
        return lab

    def windows(self, n_samples: int) -> list[tuple[float, float]]:
        dur = n_samples / SAMPLE_RATE
        if dur <= self.win:
            return [(0.0, dur)]
        out, t = [], 0.0
        while t + self.win < dur:
            out.append((t, t + self.win))
            t += self.hop
        out.append((max(0.0, dur - self.win), dur))
        return out

    def add(self, samples: np.ndarray, start_s: float) -> list[tuple[float, float, str]]:
        """Instant labels for one utterance's windows, as (abs_start, abs_end, label)."""
        cents = self._centroids()
        out = []
        for ws, we in self.windows(len(samples)):
            vec = embed(samples[int(ws * SAMPLE_RATE):int(we * SAMPLE_RATE)])
            best, score = None, -1.0
            for lab, c in cents.items():
                sc = float(vec @ c)
                if sc > score:
                    best, score = lab, sc
            long_enough = we - ws >= self.min_window
            if best is not None and score >= (self.assign if long_enough else self.short_assign):
                lab = best
            elif we - ws < self.voice_window and (self.last or best):
                lab = self.last or best
            else:
                lab = self._new_label()
                cents[lab] = vec
            self.spans.append((start_s + ws, start_s + we))
            self.vecs.append(vec)
            self.labels.append(lab)
            self.last = lab
            out.append((start_s + ws, start_s + we, lab))
        return out

    def recluster(self) -> bool:
        """Re-clusters everything heard so far. Returns True if any window's label changed."""
        n = len(self.vecs)
        if n < 3:
            return False
        V = np.stack(self.vecs)
        # Voices come from the long windows; short ones join the nearest voice below.
        long = [i for i, (s, e) in enumerate(self.spans) if e - s >= self.voice_window]
        if len(long) < 3:
            long = list(range(n))
        raw = np.zeros(n, dtype=int)
        raw[long] = fcluster(linkage(V[long], method="average", metric="cosine"), t=self.threshold, criterion="distance")
        cover: dict[int, float] = {}
        for i in long:
            s, e = self.spans[i]
            cover[raw[i]] = cover.get(raw[i], 0.0) + (e - s)
        big = [k for k, c in cover.items() if c >= self.min_cluster_s] or list(cover)
        cent = {k: V[long][raw[long] == k].mean(axis=0) for k in big}
        raw = np.array([k if k in cent else max(cent, key=lambda b: float(V[i] @ cent[b])) for i, k in enumerate(raw)])
        # Map new clusters onto existing labels by overlap, largest overlaps first, so labels are stable.
        overlap: dict[tuple[int, str], float] = {}
        for (s, e), k, old in zip(self.spans, raw, self.labels):
            overlap[(k, old)] = overlap.get((k, old), 0.0) + (e - s)
        mapping: dict[int, str] = {}
        used: set[str] = set()
        for (k, old), _ in sorted(overlap.items(), key=lambda kv: -kv[1]):
            if k not in mapping and old not in used:
                mapping[k] = old
                used.add(old)
        for k in set(raw):
            if k not in mapping:
                mapping[k] = self._new_label()
        new = [mapping[k] for k in raw]
        changed = new != self.labels
        self.labels = new
        if self.last is not None and self.labels:
            self.last = self.labels[-1]
        return changed

    def label_of(self, start: float, end: float) -> str | None:
        """Majority label over [start, end] by overlap."""
        votes: dict[str, float] = {}
        for (s, e), lab in zip(self.spans, self.labels):
            ov = min(e, end) - max(s, start)
            if ov > 0:
                votes[lab] = votes.get(lab, 0.0) + ov
        return max(votes, key=votes.get) if votes else None

    def snapshot(self) -> list[dict]:
        speech: dict[str, float] = {}
        for (s, e), lab in zip(self.spans, self.labels):
            if s >= 0:  # seeds sit at negative times
                speech[lab] = speech.get(lab, 0.0) + (e - s)
        return [{"label": lab, "embedding": c.round(6).tolist(), "speech_s": round(speech[lab], 2)}
                for lab, c in self._centroids().items() if lab in speech]
