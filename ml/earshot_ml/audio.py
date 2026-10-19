"""Audio files: conversion to the 16 kHz mono working copy, and reading spans of it."""
import json
import subprocess
from pathlib import Path

import numpy as np
import soundfile as sf

from .config import SAMPLE_RATE


def probe_duration(path: str) -> float:
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "json", path],
        check=True, capture_output=True, text=True,
    ).stdout
    return float(json.loads(out)["format"]["duration"])


def to_working_copy(src: str, dst: str) -> float:
    """Decode anything ffmpeg reads into 16 kHz mono FLAC. Returns the duration in seconds."""
    Path(dst).parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        ["ffmpeg", "-loglevel", "error", "-y", "-i", src, "-ac", "1", "-ar", str(SAMPLE_RATE), "-c:a", "flac", dst],
        check=True,
    )
    return sf.info(dst).duration


def read_span(path: str, start_s: float = 0.0, end_s: float | None = None) -> np.ndarray:
    start = int(start_s * SAMPLE_RATE)
    stop = None if end_s is None else int(end_s * SAMPLE_RATE)
    audio, sr = sf.read(path, start=start, stop=stop, dtype="float32", always_2d=False)
    if sr != SAMPLE_RATE:
        raise ValueError(f"{path} is {sr} Hz; expected the {SAMPLE_RATE} Hz working copy")
    return audio if audio.ndim == 1 else audio.mean(axis=1)


def blocks(path: str, seconds: float = 600.0):
    """Yield (offset_s, samples) blocks so a 10-hour day never sits in memory at once."""
    size = int(seconds * SAMPLE_RATE)
    offset = 0
    for block in sf.blocks(path, blocksize=size, dtype="float32", always_2d=False):
        yield offset / SAMPLE_RATE, block if block.ndim == 1 else block.mean(axis=1)
        offset += len(block)
