"""The ML process (PRD §10.3). Stateless HTTP for batch work; WebSockets for live sessions.

    .venv/bin/python -m earshot_ml.app
"""
import asyncio
import logging
import os
import subprocess
import time

import uvicorn
from fastapi import Depends, FastAPI, Header, HTTPException, WebSocket, WebSocketDisconnect
from pydantic import BaseModel

from . import asr, audio, live, speakers, vad
from .config import ML_PORT, SECRET, WHISPER_MODEL

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
log = logging.getLogger("earshot_ml")
app = FastAPI(title="earshot-ml")
LAST_HEARTBEAT = time.monotonic()


def check_secret(x_earshot_secret: str = Header(default="")):
    if SECRET and x_earshot_secret != SECRET:
        raise HTTPException(status_code=401)


def ws_allowed(ws: WebSocket) -> bool:
    return not SECRET or ws.headers.get("x-earshot-secret") == SECRET or ws.query_params.get("secret") == SECRET


class Prepare(BaseModel):
    src: str
    dst: str


class Span(BaseModel):
    path: str
    start_s: float = 0.0
    end_s: float | None = None
    regions: list[list[float]] = []


class Diarize(Span):
    threshold: float = 0.68


class Transcribe(Span):
    language: str | None = "en"
    vocabulary: list[str] = []  # names and terms for Whisper's prompt (R-3)
    no_speech_prob: float = 0.6
    avg_logprob: float = -1.0
    compression_ratio: float = 2.4
    min_speech_fraction: float = 0.3


class Embed(BaseModel):
    path: str
    ranges: list[list[float]]


class LiveStart(BaseModel):
    language: str | None = "en"
    vocabulary: list[str] = []
    max_segment_s: float = 15.0
    seeds: dict[str, list[dict]] = {}  # channel -> [{"label", "embedding"}] from earlier parts


def memory() -> dict:
    """This process's memory. The GPU numbers are MLX's: on 2026-10-24 its cache of freed buffers
    had grown to 16 GB unnoticed (BUGS.md B-1), so it is reported where the user can see it."""
    import mlx.core as mx
    try:
        rss_kb = int(subprocess.run(["ps", "-o", "rss=", "-p", str(os.getpid())], capture_output=True, text=True).stdout.strip() or 0)
    except Exception:
        rss_kb = 0
    mb = 1024 * 1024
    return {"rss_mb": rss_kb // 1024, "gpu_active_mb": mx.get_active_memory() // mb, "gpu_cache_mb": mx.get_cache_memory() // mb,
            "gpu_peak_mb": mx.get_peak_memory() // mb}


@app.get("/ml/health")
def health():
    return {"status": "up", "whisper_model": WHISPER_MODEL, "live_sessions": [k for k, v in live.SESSIONS.items() if not v.stopped],
            "memory": memory()}


def release_gpu_cache():
    """Batch work over: give MLX's spare buffers back unless a live meeting is reusing them."""
    if not any(not v.stopped for v in live.SESSIONS.values()):
        import mlx.core as mx
        mx.clear_cache()


@app.post("/ml/heartbeat", dependencies=[Depends(check_secret)])
def heartbeat():
    global LAST_HEARTBEAT
    LAST_HEARTBEAT = time.monotonic()
    return {"ok": True}


@app.post("/ml/prepare", dependencies=[Depends(check_secret)])
def prepare(req: Prepare):
    t = time.monotonic()
    duration = audio.to_working_copy(req.src, req.dst)
    regions = vad.speech_regions(req.dst)
    log.info("event=prepared src=%s duration_s=%.1f regions=%d took_s=%.1f", req.src, duration, len(regions), time.monotonic() - t)
    return {"duration_s": round(duration, 3), "regions": regions}


YIELDED = "paused: a live meeting is being transcribed"


def yield_to_live() -> None:
    """Batch work stops when a meeting goes live; the service puts the job back and resumes it after Stop."""
    if live.active():
        log.info("event=batch_yielded_to_live")
        raise HTTPException(status_code=409, detail=YIELDED)


@app.post("/ml/diarize", dependencies=[Depends(check_secret)])
def diarize(req: Diarize):
    yield_to_live()
    t = time.monotonic()
    samples = audio.read_span(req.path, req.start_s, req.end_s)
    regions = req.regions or [[req.start_s, req.end_s or req.start_s + len(samples) / 16000]]
    result = speakers.diarize(samples, regions, req.start_s, req.threshold)
    log.info("event=diarized path=%s span=%.1f-%s clusters=%d took_s=%.1f", req.path, req.start_s, req.end_s,
             len(result["clusters"]), time.monotonic() - t)
    return result


def speech_chunks(regions: list[list[float]], start: float, end: float, max_s: float = 28.0, join_gap: float = 1.5):
    """Groups VAD regions into chunks of at most ~max_s seconds, padded a little.

    Whisper on long stretches of silence drops punctuation and words, and invents text; chunks
    of real speech avoid both (PRD §5.2 step 8).
    """
    chunks: list[list[float]] = []
    for s, e in regions:
        s, e = max(s, start), min(e, end)
        if e - s < 0.2:
            continue
        if chunks and s - chunks[-1][1] < join_gap and e - chunks[-1][0] <= max_s:
            chunks[-1][1] = e
        else:
            chunks.append([s, e])
    out = []
    for s, e in chunks:
        while e - s > max_s:  # one very long region: hard split
            out.append([s, s + max_s])
            s += max_s
        out.append([s, e])
    return [[max(start, s - 0.3), min(end, e + 0.3)] for s, e in out]


@app.post("/ml/transcribe", dependencies=[Depends(check_secret)])
def transcribe(req: Transcribe):
    t = time.monotonic()
    end = req.end_s if req.end_s is not None else req.start_s + audio.read_span(req.path, req.start_s).shape[0] / 16000
    filters = asr.Filters(req.no_speech_prob, req.avg_logprob, req.compression_ratio, req.min_speech_fraction)
    chunks = speech_chunks(req.regions, req.start_s, end) if req.regions else [[req.start_s, end]]
    out, dropped, previous = [], 0, None
    vocab = asr.vocabulary_prompt(req.vocabulary)
    for cs, ce in chunks:
        yield_to_live()  # checked per ~30 s chunk, so a meeting that starts gets the GPU within seconds
        samples = audio.read_span(req.path, cs, ce)
        prompt = " ".join(p for p in (vocab, previous) if p) or None
        for seg in asr.engine().transcribe(samples, req.language, initial_prompt=prompt):
            start, stop = seg.start + cs, seg.end + cs
            frac = vad.speech_fraction(req.regions, start, stop) if req.regions else 1.0
            if not asr.keep(seg, filters, frac) or asr.echoes_prompt(seg, vocab):
                dropped += 1
                continue
            out.append({"start_s": round(start, 3), "end_s": round(stop, 3), "text": seg.text,
                        "words": [{**w, "s": round(w["s"] + cs, 3), "e": round(w["e"] + cs, 3)} for w in seg.words],
                        "avg_logprob": round(seg.avg_logprob, 3), "no_speech_prob": round(seg.no_speech_prob, 3)})
            previous = seg.text[-200:]
    release_gpu_cache()
    log.info("event=transcribed path=%s span=%.1f-%.1f chunks=%d segments=%d dropped=%d took_s=%.1f", req.path, req.start_s,
             end, len(chunks), len(out), dropped, time.monotonic() - t)
    return {"segments": out, "dropped": dropped}


@app.post("/ml/embed", dependencies=[Depends(check_secret)])
def embed(req: Embed):
    # During a live meeting the newest lines may not be written yet: use what is, or say so.
    have = audio.on_disk_s(req.path)
    ranges = [(s, min(e, have)) for s, e in req.ranges if s < have - 0.5]
    if not ranges:
        raise HTTPException(status_code=409, detail="That audio is not on disk yet.")
    req.ranges = [list(r) for r in ranges]
    end = max(e for _, e in req.ranges)
    start = min(s for s, _ in req.ranges)
    samples = audio.read_span(req.path, start, end)
    vec = speakers.embed_ranges(samples, [(s, e) for s, e in req.ranges], start)
    return {"embedding": vec.round(6).tolist()}


@app.post("/ml/live/{session_id}/start", dependencies=[Depends(check_secret)])
async def live_start(session_id: str, req: LiveStart):
    live.start(session_id, req.language or "en", req.max_segment_s, asyncio.get_running_loop(), req.seeds,
               asr.vocabulary_prompt(req.vocabulary))
    return {"ok": True}


class LiveLanguage(BaseModel):
    language: str


class LiveVocabulary(BaseModel):
    vocabulary: list[str] = []


@app.post("/ml/live/{session_id}/vocabulary", dependencies=[Depends(check_secret)])
def live_vocabulary(session_id: str, req: LiveVocabulary):
    """Names added mid-meeting: the next sentence uses them."""
    s = live.SESSIONS.get(session_id)
    if s is None or s.stopped:
        raise HTTPException(status_code=404, detail="No live session.")
    s.prompt = asr.vocabulary_prompt(req.vocabulary)
    log.info("event=live_vocabulary session=%s terms=%d", session_id, len(req.vocabulary))
    return {"ok": True}


@app.post("/ml/live/{session_id}/language", dependencies=[Depends(check_secret)])
def live_language(session_id: str, req: LiveLanguage):
    """A wrong language noticed mid-meeting: the next sentence uses the new one."""
    s = live.SESSIONS.get(session_id)
    if s is None or s.stopped:
        raise HTTPException(status_code=404, detail="No live session.")
    s.language = req.language
    log.info("event=live_language session=%s language=%s", session_id, req.language)
    return {"ok": True}


@app.post("/ml/live/{session_id}/stop", dependencies=[Depends(check_secret)])
def live_stop(session_id: str):
    live.stop(session_id)
    return {"ok": True}


@app.websocket("/ml/live/{session_id}/audio")
async def live_audio(ws: WebSocket, session_id: str, channel: str, framing: str | None = None):
    if not ws_allowed(ws):
        await ws.close(code=4401)
        return
    await ws.accept()
    s = live.SESSIONS.get(session_id) or live.start(session_id, "en", 15.0, asyncio.get_running_loop())
    # framing=seq: each frame starts with its sample index (current capture); without it, raw pcm.
    feed = live.feed_framed if framing == "seq" else live.feed
    try:
        while True:
            feed(s, channel, await ws.receive_bytes())
    except WebSocketDisconnect:
        pass


@app.websocket("/ml/live/{session_id}/events")
async def live_events(ws: WebSocket, session_id: str):
    if not ws_allowed(ws):
        await ws.close(code=4401)
        return
    await ws.accept()
    s = live.SESSIONS.get(session_id) or live.start(session_id, "en", 15.0, asyncio.get_running_loop())
    s.listeners.add(ws)
    try:
        while True:
            await ws.receive_text()
    except WebSocketDisconnect:
        s.listeners.discard(ws)


@app.on_event("startup")
async def watchdog():
    timeout = float(os.environ.get("EARSHOT_HEARTBEAT_TIMEOUT_SECONDS", "0"))
    if timeout <= 0:
        return

    async def loop():
        while True:
            await asyncio.sleep(5)
            if time.monotonic() - LAST_HEARTBEAT > timeout:
                log.warning("event=heartbeat_lost exiting=true")
                os._exit(0)
    asyncio.create_task(loop())


if __name__ == "__main__":
    # No keep-alive pings: the only WebSocket clients are local, and a ping missed while the GPU is
    # busy closed the audio stream every ~40 s (BUGS.md B-9).
    uvicorn.run(app, host="127.0.0.1", port=ML_PORT, log_level="info", ws_ping_interval=None, ws_ping_timeout=None)
