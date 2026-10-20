"""The capture process (PRD §6.4, §10): small on purpose, so an ML crash never stops recording.

For each channel it writes 30 s FLAC chunks (durable even if killed) and streams 16 kHz PCM to
the ML process. A `file` source replays WAVs in real time, for tests and demos.

    .venv/bin/python -m earshot_ml.capture
"""
import asyncio
import logging
import os
import queue
import threading
import time
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import soundfile as sf
import uvicorn
import websockets
from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel
from scipy.signal import resample_poly

from . import echo
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


@dataclass
class Source:
    name: str
    channels: dict[str, int]
    sr: int
    stream: object
    frames: "queue.Queue[np.ndarray]"


class Recording:
    def __init__(self, req: Start):
        self.req = req
        self.out = Path(req.out_dir)
        self.channels = {name: Channel(name, self.out) for name in (req.files or req.channels)}
        self.stop_event = threading.Event()
        self.started = time.time()
        self.threads: list[threading.Thread] = []
        self.error: str | None = None
        # Online call: watch for the call leaking into the mic (speakers instead of headphones).
        self.echo = echo.Detector() if {"mic", "system"} <= set(self.channels) else None

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
            frames = {}
            for name, a in mono.items():
                frame = a[i:i + step]
                frames[name] = frame if len(frame) == step else np.pad(frame, (0, step - len(frame)))
                self.channels[name].push(frames[name])
            self._check_echo(frames)
            time.sleep(max(0.0, t0 + (i + step) / SAMPLE_RATE / self.req.speed - time.monotonic()))
        log.info("event=replay_done meeting=%s", self.req.meeting_id)

    def _open_device(self) -> None:
        self.src = self._open(self.req.device, self.req.channels)
        self.src_lock = threading.Lock()

    def _open(self, device: str | None, channels: dict[str, int]) -> "Source":
        import sounddevice as sd
        try:
            info = sd.query_devices(device, "input") if device else sd.query_devices(kind="input")
        except Exception:
            raise ValueError(f"Input device not found: {device or 'system default'}") from None
        needed = max(channels.values()) + 1
        if info["max_input_channels"] < needed:
            if "system" in channels and len(channels) > 1:
                raise ValueError(
                    f"\"{info['name']}\" has {info['max_input_channels']} input channel(s); an online call needs {needed}: "
                    "your mic plus system audio. Pick the Aggregate Device (mic + BlackHole) from the setup guide, "
                    "or use In-person.")
            raise ValueError(f"\"{info['name']}\" has {info['max_input_channels']} input channel(s); channel {needed - 1} does not exist.")
        sr = int(info["default_samplerate"])
        frames: "queue.Queue[np.ndarray]" = queue.Queue()

        def callback(indata, _frames, _time, status):
            if status:
                log.warning("event=capture_status status=%s", status)
            frames.put(indata.copy())

        try:
            stream = sd.InputStream(device=info["index"], channels=needed, samplerate=sr, dtype="float32",
                                    blocksize=int(sr * FRAME_S), callback=callback)
            stream.start()
        except Exception as e:
            raise ValueError(f"Could not open \"{info['name']}\": {e}") from None
        return Source(info["name"], dict(channels), sr, stream, frames)

    def switch(self, device: str | None, channels: dict[str, int]) -> str:
        """Moves recording to another input mid-meeting (speakers -> headset). The new device opens
        before the old one closes, so the timeline, transcript and part carry on with ~0.1 s missed."""
        if self.req.source != "device":
            raise ValueError("A replayed meeting cannot switch devices.")
        if set(channels) != set(self.channels):
            raise ValueError(f"Channels must stay {sorted(self.channels)}.")
        with self.src_lock:
            new = self._open(device, channels)
            old, self.src = self.src, new
            if self.echo is not None:
                self.echo = echo.Detector()  # the old device's leak history says nothing about the new one
        old.stream.close()
        log.info("event=capture_switched meeting=%s from=%s to=%s", self.req.meeting_id, old.name, new.name)
        return new.name

    def _device(self) -> None:
        try:
            while not self.stop_event.is_set():
                src = self.src
                try:
                    block = src.frames.get(timeout=0.5)
                except queue.Empty:
                    if src is self.src and not src.stream.active:
                        raise RuntimeError("the input device stopped (unplugged?)")
                    continue
                if src is not self.src:
                    continue  # switched: the old device's last blocks are dropped
                frames = {}
                for name, idx in src.channels.items():
                    x = block[:, idx]
                    frames[name] = resample_poly(x, SAMPLE_RATE, src.sr).astype(np.float32) if src.sr != SAMPLE_RATE else x
                    self.channels[name].push(frames[name])
                self._check_echo(frames)
        except Exception as e:
            self.error = str(e)
            log.exception("event=capture_failed meeting=%s", self.req.meeting_id)
        finally:
            self.src.stream.close()

    def _check_echo(self, frames: dict[str, np.ndarray]) -> None:
        if self.echo is not None:
            try:
                self.echo.feed(frames["mic"], frames["system"])
            except Exception:
                log.exception("event=echo_check_failed meeting=%s", self.req.meeting_id)
                self.echo = None

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
                "device": self.src.name if getattr(self, "src", None) else None,
                "channels": {n: {"seconds": round(c.samples / SAMPLE_RATE, 1), "silent_s": round(c.silent_s, 1)}
                             for n, c in self.channels.items()},
                "echo": None if self.echo is None else {"leaking": self.echo.leaking, "score": self.echo.score}}


ACTIVE: dict[str, Recording] = {}


@app.get("/capture/health")
def health():
    return {"status": "up", "active": list(ACTIVE)}


def _rescan() -> None:
    """PortAudio lists devices once per process; re-read them so a new Aggregate Device or
    BlackHole shows up without a restart. Never while recording: it would close the stream."""
    if ACTIVE:
        return
    import sounddevice as sd
    sd._terminate()
    sd._initialize()


@app.get("/capture/devices")
def devices():
    import sounddevice as sd
    _rescan()
    inputs = [{"index": i, "name": d["name"], "channels": d["max_input_channels"], "sample_rate": d["default_samplerate"]}
              for i, d in enumerate(sd.query_devices()) if d["max_input_channels"] > 0]
    try:
        default_input = sd.query_devices(kind="input")["name"]
    except Exception:
        default_input = None
    return {"inputs": inputs, "default_input": default_input}


@app.post("/capture/heartbeat", dependencies=[Depends(check_secret)])
def heartbeat():
    global LAST_HEARTBEAT
    LAST_HEARTBEAT = time.monotonic()
    return {"ok": True}


@app.post("/capture/start", dependencies=[Depends(check_secret)])
def start(req: Start):
    if ACTIVE:
        raise HTTPException(status_code=409, detail="a meeting is already recording")
    _rescan()
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


class Switch(BaseModel):
    device: str | None = None
    channels: dict[str, int]


@app.post("/capture/{meeting_id}/device", dependencies=[Depends(check_secret)])
def switch(meeting_id: str, req: Switch):
    rec = ACTIVE.get(meeting_id)
    if not rec:
        raise HTTPException(status_code=404)
    try:
        return {"device": rec.switch(req.device, req.channels)}
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e)) from None


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
