"""The capture process (PRD §6.4, §10): small on purpose, so an ML crash never stops recording.

For each channel it writes 30 s FLAC chunks (durable even if killed) and streams 16 kHz PCM to
the ML process. A `file` source replays WAVs in real time, for tests and demos.

    .venv/bin/python -m earshot_ml.capture
"""
import asyncio
import logging
import os
import queue
import subprocess
import threading
import time
from pathlib import Path

import numpy as np
import soundfile as sf
import uvicorn
import websockets
from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel
from scipy.signal import resample_poly

from .config import CAPTURE_PORT, ML_PORT, SAMPLE_RATE, SECRET

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
log = logging.getLogger("earshot_capture")
app = FastAPI(title="earshot-capture")
CHUNK_S = 30
FRAME_S = 0.1
LAST_HEARTBEAT = time.monotonic()


def check_secret(x_earshot_secret: str = Header(default="")):
    if SECRET and x_earshot_secret != SECRET:
        raise HTTPException(status_code=401)


class Start(BaseModel):
    meeting_id: str
    out_dir: str
    source: str = "device"  # device | file
    device: str | None = None  # input device name (an Aggregate Device for mic + system)
    channels: dict[str, int] = {"mic": 0}  # channel name -> input channel index
    files: dict[str, str] = {}  # source=file: channel name -> wav path
    speed: float = 1.0  # source=file: 1.0 = real time


class Channel:
    def __init__(self, name: str, out_dir: Path):
        self.name, self.dir = name, out_dir / name
        self.dir.mkdir(parents=True, exist_ok=True)
        self.buf: list[np.ndarray] = []
        self.buffered = 0
        self.index = 0
        self.samples = 0
        self.silent_s = 0.0
        self.q: "queue.Queue[bytes | None]" = queue.Queue()

    def push(self, samples: np.ndarray) -> None:
        self.samples += len(samples)
        rms = float(np.sqrt(np.mean(samples ** 2))) if len(samples) else 0.0
        self.silent_s = self.silent_s + len(samples) / SAMPLE_RATE if rms < 0.003 else 0.0
        self.buf.append(samples)
        self.buffered += len(samples)
        if self.buffered >= CHUNK_S * SAMPLE_RATE:
            self.flush()
        self.q.put((np.clip(samples, -1, 1) * 32767).astype(np.int16).tobytes())

    def flush(self) -> None:
        if not self.buf:
            return
        self.index += 1
        sf.write(self.dir / f"chunk_{self.index:05d}.flac", np.concatenate(self.buf), SAMPLE_RATE)
        self.buf, self.buffered = [], 0

    def join(self) -> Path:
        """Joins chunks into one file per channel after Stop (PRD §9.1)."""
        self.flush()
        chunks = sorted(self.dir.glob("chunk_*.flac"))
        out = self.dir.parent / f"{self.name}.flac"
        if chunks:
            sf.write(out, np.concatenate([sf.read(c, dtype="float32")[0] for c in chunks]), SAMPLE_RATE)
        return out


class Recording:
    def __init__(self, req: Start):
        self.req = req
        self.out = Path(req.out_dir)
        self.channels = {name: Channel(name, self.out) for name in (req.files or req.channels)}
        self.stop_event = threading.Event()
        self.started = time.time()
        self.threads: list[threading.Thread] = []
        self.error: str | None = None

    def start(self) -> None:
        """Opens the source now, so a bad device or file fails the Start request, not a thread later."""
        if self.req.source == "file":
            missing = [p for p in self.req.files.values() if not Path(p).is_file()]
            if missing:
                raise ValueError(f"Audio file not found: {', '.join(missing)}")
            src = self._replay
        else:
            self._open_device()
            src = self._device
        for ch in self.channels.values():
            t = threading.Thread(target=self._stream, args=(ch,), daemon=True)
            t.start()
            self.threads.append(t)
        t = threading.Thread(target=src, daemon=True)
        t.start()
        self.threads.append(t)

    def _replay(self) -> None:
        data = {name: sf.read(path, dtype="float32", always_2d=True) for name, path in self.req.files.items()}
        mono = {}
        for name, (a, sr) in data.items():
            a = a.mean(axis=1)
            mono[name] = resample_poly(a, SAMPLE_RATE, sr).astype(np.float32) if sr != SAMPLE_RATE else a
        n = max(len(a) for a in mono.values())
        step = int(FRAME_S * SAMPLE_RATE)
        t0 = time.monotonic()
        for i in range(0, n, step):
            if self.stop_event.is_set():
                break
            for name, a in mono.items():
                frame = a[i:i + step]
                self.channels[name].push(frame if len(frame) == step else np.pad(frame, (0, step - len(frame))))
            time.sleep(max(0.0, t0 + (i + step) / SAMPLE_RATE / self.req.speed - time.monotonic()))
        log.info("event=replay_done meeting=%s", self.req.meeting_id)

    def _open_device(self) -> None:
        import sounddevice as sd
        try:
            info = sd.query_devices(self.req.device, "input") if self.req.device else sd.query_devices(kind="input")
        except Exception:
            raise ValueError(f"Input device not found: {self.req.device or 'system default'}") from None
        needed = max(self.req.channels.values()) + 1
        if info["max_input_channels"] < needed:
            if "system" in self.req.channels and len(self.req.channels) > 1:
                raise ValueError(
                    f"\"{info['name']}\" has {info['max_input_channels']} input channel(s); an online call needs {needed}: "
                    "your mic plus system audio. Pick the Aggregate Device (mic + BlackHole) from the setup guide, "
                    "or use In-person.")
            raise ValueError(f"\"{info['name']}\" has {info['max_input_channels']} input channel(s); channel {needed - 1} does not exist.")
        self.sr = int(info["default_samplerate"])
        self.frames: "queue.Queue[np.ndarray]" = queue.Queue()

        def callback(indata, _frames, _time, status):
            if status:
                log.warning("event=capture_status status=%s", status)
            self.frames.put(indata.copy())

        try:
            self.stream = sd.InputStream(device=info["index"], channels=needed, samplerate=self.sr, dtype="float32",
                                         blocksize=int(self.sr * FRAME_S), callback=callback)
            self.stream.start()
        except Exception as e:
            raise ValueError(f"Could not open \"{info['name']}\": {e}") from None

    def _device(self) -> None:
        sr = self.sr
        try:
            while not self.stop_event.is_set():
                try:
                    block = self.frames.get(timeout=0.5)
                except queue.Empty:
                    if not self.stream.active:
                        raise RuntimeError("the input device stopped (unplugged?)")
                    continue
                for name, idx in self.req.channels.items():
                    x = block[:, idx]
                    self.channels[name].push(resample_poly(x, SAMPLE_RATE, sr).astype(np.float32) if sr != SAMPLE_RATE else x)
        except Exception as e:
            self.error = str(e)
            log.exception("event=capture_failed meeting=%s", self.req.meeting_id)
        finally:
            self.stream.close()

    def _stream(self, ch: Channel) -> None:
        """Streams to the ML process; reconnects so an ML restart only pauses live text."""
        asyncio.run(self._stream_async(ch))

    async def _stream_async(self, ch: Channel) -> None:
        url = f"ws://127.0.0.1:{ML_PORT}/ml/live/{self.req.meeting_id}/audio?channel={ch.name}&secret={SECRET}"
        while not (self.stop_event.is_set() and ch.q.empty()):
            try:
                async with websockets.connect(url, max_size=None) as ws:
                    while True:
                        try:
                            pcm = ch.q.get(timeout=0.5)
                        except queue.Empty:
                            if self.stop_event.is_set():
                                return
                            continue
                        await ws.send(pcm)
            except Exception as e:
                log.warning("event=ml_stream_retry channel=%s reason=%s", ch.name, type(e).__name__)
                await asyncio.sleep(1)

    def stop(self) -> dict:
        self.stop_event.set()
        for t in self.threads:
            t.join(timeout=10)
        files = {name: str(ch.join()) for name, ch in self.channels.items()}
        return {"files": files, "duration_s": round(max(ch.samples for ch in self.channels.values()) / SAMPLE_RATE, 2)}

    def status(self) -> dict:
        return {"meeting_id": self.req.meeting_id, "elapsed_s": round(time.time() - self.started, 1), "error": self.error,
                "channels": {n: {"seconds": round(c.samples / SAMPLE_RATE, 1), "silent_s": round(c.silent_s, 1)}
                             for n, c in self.channels.items()}}


ACTIVE: dict[str, Recording] = {}


def _default_output() -> str:
    try:
        import sounddevice as sd
        return sd.query_devices(kind="output")["name"]
    except Exception:
        return ""


def _output_has_speakers(name: str) -> bool | None:
    """True if audio plays from built-in speakers; None if a Multi-Output Device hides it."""
    if "Multi-Output" in name:
        try:
            out = subprocess.run(["system_profiler", "SPAudioDataType"], capture_output=True, text=True, timeout=10).stdout
            return "Speakers" in out and "Multi-Output" in out and "Headphones" not in out
        except Exception:
            return None
    return "Speakers" in name


@app.get("/capture/health")
def health():
    return {"status": "up", "active": list(ACTIVE)}


@app.get("/capture/devices")
def devices():
    import sounddevice as sd
    inputs = [{"index": i, "name": d["name"], "channels": d["max_input_channels"], "sample_rate": d["default_samplerate"]}
              for i, d in enumerate(sd.query_devices()) if d["max_input_channels"] > 0]
    out = _default_output()
    try:
        default_input = sd.query_devices(kind="input")["name"]
    except Exception:
        default_input = None
    return {"inputs": inputs, "default_input": default_input, "output": out, "output_uses_speakers": _output_has_speakers(out)}


@app.post("/capture/heartbeat", dependencies=[Depends(check_secret)])
def heartbeat():
    global LAST_HEARTBEAT
    LAST_HEARTBEAT = time.monotonic()
    return {"ok": True}


@app.post("/capture/start", dependencies=[Depends(check_secret)])
def start(req: Start):
    if ACTIVE:
        raise HTTPException(status_code=409, detail="a meeting is already recording")
    rec = Recording(req)
    try:
        rec.start()
    except ValueError as e:
        log.warning("event=capture_refused meeting=%s reason=%s", req.meeting_id, e)
        raise HTTPException(status_code=400, detail=str(e)) from None
    ACTIVE[req.meeting_id] = rec
    log.info("event=capture_started meeting=%s source=%s channels=%s", req.meeting_id, req.source, list(rec.channels))
    return {"ok": True}


@app.get("/capture/{meeting_id}/status")
def status(meeting_id: str):
    rec = ACTIVE.get(meeting_id)
    if not rec:
        raise HTTPException(status_code=404)
    return rec.status()


@app.post("/capture/{meeting_id}/stop", dependencies=[Depends(check_secret)])
def stop(meeting_id: str):
    rec = ACTIVE.pop(meeting_id, None)
    if not rec:
        raise HTTPException(status_code=404)
    result = rec.stop()
    log.info("event=capture_stopped meeting=%s duration_s=%s", meeting_id, result["duration_s"])
    return result


@app.on_event("startup")
async def watchdog():
    # Keep recording through a backend crash, but not forever (PRD §10, process lifecycle).
    orphan_s = float(os.environ.get("EARSHOT_CAPTURE_ORPHAN_SECONDS", "0"))
    if orphan_s <= 0:
        return

    async def loop():
        while True:
            await asyncio.sleep(10)
            if time.monotonic() - LAST_HEARTBEAT > orphan_s:
                for mid in list(ACTIVE):
                    ACTIVE.pop(mid).stop()
                log.warning("event=heartbeat_lost exiting=true")
                os._exit(0)
    asyncio.create_task(loop())


if __name__ == "__main__":
    uvicorn.run(app, host="127.0.0.1", port=CAPTURE_PORT, log_level="info")
