# Earshot

Offline daily notes from a pocket recorder, and a live meeting assistant. Runs entirely on one
Mac; no audio, transcript or derived data leaves it. Spec: [docs/PRD.md](docs/PRD.md).

## Run

```sh
./earshot            # http://localhost:8090
```

The script starts the Postgres container, builds the UI and the jar when their sources changed,
and runs the service, which starts and supervises the Python ML and capture processes.

### One-time setup

| Need | How |
|---|---|
| Java 25, Maven, Node 22+, Docker, ffmpeg | Homebrew / Docker Desktop |
| Python 3.12 env | `cd ml && uv venv -p python3.12 .venv && uv pip install --python .venv/bin/python -r requirements.txt` |
| Whisper turbo (MLX) | cached by `huggingface-cli download mlx-community/whisper-large-v3-turbo` |
| Speaker embeddings | `HF_HUB_OFFLINE=0 ml/.venv/bin/python -c "from pyannote.audio import Model; Model.from_pretrained('pyannote/wespeaker-voxceleb-resnet34-LM')"` |
| Diarization (optional, better) | accept the terms of `pyannote/speaker-diarization-community-1` on Hugging Face, `huggingface-cli login`, then download it once the same way. Without it, diarization clusters WeSpeaker embeddings. |
| LLM + embeddings | LM Studio on `127.0.0.1:1234` with `qwen2.5-7b-instruct` and `text-embedding-nomic-embed-text-v1.5` loaded (model names are Settings) |

At runtime the Python processes run with `HF_HUB_OFFLINE=1`.

### Online calls (Mic + System)

1. Install [BlackHole 2ch](https://existential.audio/blackhole/).
2. Audio MIDI Setup → **Multi-Output Device**: your headphones + BlackHole (primary = headphones,
   drift correction on BlackHole). Set the call app's speaker (or the system output) to it, so you
   still hear the call. One per output you use, e.g. "Call Output Jabra" and "Call Output Mac".
   In the call app the microphone stays your real mic, never the Aggregate Device.
3. Audio MIDI Setup → **Aggregate Device**: your mic + BlackHole (drift correction on BlackHole).
   Tick the mic first so it is channel 0 and BlackHole channels 1–2. Keep "Aggregate" in the name:
   Earshot preselects it for Online call. One per mic, e.g. "Aggregate Jabra" and "Aggregate Mac".
4. Wear headphones. Through speakers the mic hears the call and others are labelled as you;
   Earshot notices this from the audio within about 10 s of call speech and shows a warning.

Closing the lid sleeps the Mac and stops recording unless an external display is attached.

## Layout

| Path | What |
|---|---|
| `service/` | Spring Boot 4.1 / Java 25: API, job queue, live sessions, speaker matching, Q&A, search, exports |
| `web/` | React 19 + Vite + CSS Modules; builds into the service's static resources |
| `ml/` | Python: `earshot_ml.app` (VAD, Whisper, diarization, embeddings, live ASR) and `earshot_ml.capture` |
| `eval/` | Synthetic fixtures (`make_fixtures.py`) and `score.py` (Me accuracy) |
| `data/` | Everything the app stores (gitignored): audio, notes, backups, logs |

## Tests

```sh
cd service && mvn -q test                     # Java unit tests
cd ml && .venv/bin/python -m pytest -q tests  # Python unit tests
ml/.venv/bin/python eval/make_fixtures.py     # synthetic recorder day + meeting (macOS voices)
python3 eval/score.py 2026-10-20              # Me accuracy, after importing the fixture day
```

`scripts/restart-service.sh` rebuilds and restarts only Earshot's service (by port, never by jar name).
