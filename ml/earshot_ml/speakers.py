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


def diarize(samples: np.ndarray, regions: list[list[float]], offset: float, threshold: float = 0.55) -> dict:
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


def _diarize_clustering(samples, regions, offset, threshold, win=1.5, hop=0.75, frame=0.25):
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
