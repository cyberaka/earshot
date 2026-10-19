# PRD: Personal Conversation Recorder & Live Meeting Assistant

**Working name:** Earshot
**Owner:** Abhinav
**Status:** Draft v1
**Date:** 2026-10-19
**Build approach:** Implemented with Claude Code, milestone by milestone (see §12)

---

## 1. Summary

Earshot is a local web app that runs entirely on a laptop. It has two modes:

1. **Daily Notes (batch).** Each evening, audio from a pocket voice recorder is imported. The app transcribes and diarizes it, labels the user's own voice, and produces a searchable, speaker-attributed record of the day's conversations.
2. **Live Meeting Assistant.** During a meeting (in-person or online), the app listens, transcribes in near real time, and builds running context. The user can ask questions at any point, such as "Was X discussed?" or "What question should I ask now?", and get answers grounded in what was said.

Everything runs offline. No audio, transcript, or derived data leaves the machine. The user supplies the local LLM and the Whisper turbo model.

## 2. Goals & Non-Goals

### Goals
- G1: Turn a full day of pocket-recorder audio into speaker-attributed, timestamped notes with no manual effort beyond importing the files.
- G2: Reliably separate **the user ("Me")** from **others** in both modes.
- G3: Answer questions about a live meeting within seconds, citing transcript timestamps.
- G4: Run 100% offline on one laptop, with no cloud calls at runtime.
- G5: Name recurring people from voiceprints over time.

### Non-Goals (v1)
- Native mobile apps. A phone can later reach the web app over LAN as a thin companion (Phase 3).
- Cloud sync, sharing, or multi-user collaboration.
- Real-time translation.
- Joining meetings as a bot or integrating with Teams/Zoom/Meet APIs.
- Speaking answers aloud. All assistant output is on-screen only.
- Perfect live diarization among multiple remote participants (see §6.3).

## 3. Users & Context

- **Single user**, personal use only. The output is private notes.
- **Speech:** primarily Indian English. Hindi and code-mixed speech should transcribe at Whisper's native quality. No special handling is required in v1.
- **Settings:** office conversations, cafeteria, travel, in-person meeting rooms, and online calls on the laptop.

## 4. Constraints & Assumptions

| Area | Decision / Assumption |
|---|---|
| Platform | Local web app. Spring Boot backend (serves the UI) plus a Python ML sidecar, all on one laptop, served on `localhost`. |
| Baseline hardware | Apple Silicon Mac, **16 GB unified memory**. Must work within this budget. |
| Network | No outbound network calls at runtime. Model downloads are a one-time setup step. |
| ASR | Whisper **large-v3-turbo**, provided by the user. |
| LLM | User-provided local model behind an **OpenAI-compatible endpoint** (e.g., LM Studio or Ollama). Target size 3–8B. |
| Embeddings | Local embedding model through the same OpenAI-compatible endpoint (e.g., `nomic-embed-text`). |
| Storage | Postgres 16 + pgvector in a local Docker container (docker-compose, bound to `127.0.0.1`), plus audio and note files on disk under a single data directory. |
| Auth | None in v1. Bind to `127.0.0.1` only. |
| Recorder | Generic voice recorder producing WAV or MP3. The file's creation time is trusted as recording start. |

## 5. Mode A: Daily Notes (Batch)

### 5.1 User flow
1. The user connects the recorder and drags the audio files into the web UI, or drops them into a watched `inbox/` folder.
2. The user clicks **Process**. Optionally, processing can run on a schedule (Phase 2).
3. The app processes the files in the background, with progress shown in the UI.
4. The user opens **Today**. It shows a timeline of conversations, each with speaker-attributed transcript, summary and action items.
5. The user names unknown speakers ("Speaker 3 → Ravi"). Future sessions apply the name automatically.

### 5.2 Processing pipeline
1. **Ingest:** copy the original file into `data/audio/raw/YYYY-MM-DD/`, then convert to 16 kHz mono WAV with ffmpeg.
2. **Denoise (optional, configurable):** DeepFilterNet.
3. **VAD:** Silero VAD produces speech regions.
4. **Conversation segmentation:** a gap of `≥ conversation_gap_seconds` (default 120) starts a new conversation. Drop conversations shorter than `min_conversation_seconds` (default 20).
5. **ASR (per conversation):** Whisper turbo with word timestamps.
6. **Diarization (per conversation, not per day):** pyannote **Community-1** in exclusive mode.
7. **Word to speaker alignment:** assign each word to the speaker turn with maximum time overlap. Merge turns shorter than 1 s into the adjacent turn when the speaker is ambiguous.
8. **Speaker identification:**
   - Compute an embedding for each diarized speaker cluster.
   - Match it to the **Me** voiceprint (cosine similarity ≥ `me_threshold`) and to the named-person library (≥ `person_threshold`).
   - Store unmatched clusters as **Unknown** with their embedding so they can be named later.
9. **Enrichment (LLM):** per conversation, generate a title, a 3–5 bullet summary, action items, and topics or tags.
10. **Index:** chunk transcript segments, embed them, and store vectors for search and Q&A.
11. **Export:** write `data/notes/YYYY-MM-DD.md`, a human-readable daily file (format in §9.3).

### 5.3 Functional requirements
- A-1: Import files by drag-and-drop and through a watched folder.
- A-2: Processing is resumable. A crash mid-day resumes per conversation and does not restart the whole day.
- A-3: Per-stage progress shown in the UI.
- A-4: Conversation timeline with wall-clock times derived from the file start time plus the offset.
- A-5: Transcript view with speaker labels, timestamps, and click-to-play audio for any segment.
- A-6: Rename a speaker in one conversation, with the option to "apply to voiceprint library".
- A-7: Full-text search and semantic search across all days.
- A-8: Daily markdown export.
- A-9: Ask questions across a day or a date range (same Q&A engine as Mode B, different scope).

## 6. Mode B: Live Meeting Assistant

### 6.1 User flow
1. The user clicks **Start Meeting**, picks an audio source (§6.2), and optionally adds a title and pre-meeting context such as an agenda or notes (pasted text or uploaded `.md`/`.txt`/`.pdf`).
2. A live transcript scrolls in the main panel. A side panel shows the **running summary** and **topics covered**.
3. Anytime during the meeting, the user types a question or clicks a quick action:
   - **"Was X discussed?"**
   - **"Suggest a question I could ask"**
   - **"Summarize the last 5 minutes"**
   - **"What decisions / action items so far?"**
   - **"What's still open from the agenda?"** (only if an agenda was provided)
4. Answers stream into the side panel with clickable timestamp citations.
5. The user clicks **Stop**. A post-meeting pass (§6.6) produces the final, higher-quality record, stored alongside Daily Notes.

### 6.2 Audio sources
| Source | Captures | Speaker attribution while live |
|---|---|---|
| **Mic only** (in-person meeting) | Room audio through the laptop mic | Everything labelled "Room" while live; full diarization after the meeting |
| **Mic + System audio** (online call) | Mic = Me; system loopback (BlackHole) = others | **Me vs Others** from the channel, with no model needed |
| **System only** | Remote participants only | "Others" |

- Audio capture happens in the **Python ML sidecar** (e.g., `sounddevice`), not the browser. The sidecar streams transcript segments to Spring Boot, which relays them to the UI.
- The setup guide must cover installing BlackHole and creating a macOS Multi-Output Device, so the user still hears the call.

### 6.3 Live speaker attribution scope
- **v1:** channel-based Me vs Others for online calls. Room audio is unattributed while live.
- **v1 stretch / Phase 2:** re-diarize a rolling window (e.g., the last 60–120 s) every 30 s with pyannote, and match the clusters to voiceprints. Labels may be revised as more audio arrives.
- Final, accurate attribution always comes from the post-meeting pass.

### 6.4 Live transcription
- Stream audio in chunks. Use VAD to cut segments at natural pauses, with a maximum segment length of about 15 s.
- Transcribe each segment with Whisper turbo. Show partial text in the UI within **≤ 5 s** of speech ending (target), and never more than 10 s.
- If transcription falls behind real time, log it in the UI as "lagging Ns". Never drop audio silently.

### 6.5 Context building
These components are maintained continuously:

| Component | Update cadence | Purpose |
|---|---|---|
| Raw transcript | Per segment | Source of truth, citations |
| Vector index of segments | Per segment | Recall questions ("was X discussed?") |
| Rolling summary | Every `summary_interval` (default 3 min) or N new segments | Compact meeting state that fits the LLM context |
| Topic list | With each summary update | "Topics covered" panel, agenda tracking |
| Decisions & action items | With each summary update | Quick action answers |
| Pre-meeting context | Once at start | Agenda coverage, question suggestions |

**Q&A prompt assembly** (keep it within the model's context window):
`system instructions + pre-meeting context (truncated) + rolling summary + top-k retrieved segments + last ~2 min of verbatim transcript + user question`

**Answer rules:**
- Answer only from the meeting content. If something wasn't discussed, say so plainly.
- Cite timestamps for factual claims.
- "Suggest a question" uses the summary, open agenda items, recent transcript, and gaps or ambiguities. Return 3 short candidate questions.

### 6.6 Post-meeting pass
- Re-run the Mode A pipeline (steps 5–10) on the full recorded meeting audio for better transcription and diarization.
- Replace the live transcript with the final version, keeping a link to the live Q&A history.
- Add the meeting to that day's notes and markdown export.

### 6.7 Functional requirements
- B-1: Start or stop a meeting, and pick the audio source and devices.
- B-2: Live transcript with no more than 10 s lag under normal load.
- B-3: Rolling summary and topic panel, updating automatically.
- B-4: Free-text Q&A plus the quick-action buttons listed in §6.1.
- B-5: Streaming answers with clickable timestamp citations that scroll the transcript to the cited spot.
- B-6: Optional pre-meeting context upload.
- B-7: Raw meeting audio saved to disk for the post-meeting pass.
- B-8: Post-meeting pass runs automatically after Stop, or on demand.
- B-9: The UI stays responsive while the LLM is generating. Transcription never pauses for Q&A.

## 7. Speaker & Voiceprint Management

- **Me enrollment:** a one-time flow where the user records 30–60 s of speech in the UI, or uploads a clip. Store the embedding. Allow re-enrollment and multiple samples per person (average or keep the best).
- **Person library:** name, aliases, embeddings (multiple), first seen, last seen, and conversation count.
- **Naming flow:** clicking an unknown speaker shows "Name this speaker" with a suggested match if one is near threshold.
- **Merge:** merge two people (e.g., duplicates created from different mic conditions).
- **Delete:** deleting a person removes their embeddings. Past transcripts revert to "Unknown".
- **Thresholds:** configurable, with sensible defaults chosen by testing on real recordings.

## 8. Resource Management (16 GB budget)

- Whisper, pyannote, and Silero VAD live in the Python ML sidecar. Load them lazily; in batch mode, unload them when idle.
- **Live mode priority:** ASR first, then context updates, then Q&A. When resources are constrained, delay summary updates rather than ASR.
- The LLM runs out-of-process (LM Studio or Ollama). The app only calls its HTTP endpoint.
- Show a resources indicator in the UI: memory use, ASR real-time factor, and LLM queue depth.
- Batch mode must not run while a live meeting is active. Queue it instead.

## 9. Data

### 9.1 Directory layout
```
data/
  audio/raw/YYYY-MM-DD/      # original recorder files (retention configurable)
  audio/meetings/<id>.wav    # live meeting captures
  audio/clips/               # enrollment samples
  notes/YYYY-MM-DD.md        # daily markdown exports
  models/                    # optional local model cache
```
Postgres data lives in the `earshot-db` container's volume. Configuration is `service/src/main/resources/application.yaml` with `${EARSHOT_*:default}` overrides from a gitignored `.env`.

### 9.2 Core entities (Postgres, Flyway migrations)
- `recording` (id, source_type[recorder|meeting], path, started_at, duration_s, status)
- `conversation` (id, recording_id, start_offset_s, end_offset_s, started_at, title, summary, status)
- `segment` (id, conversation_id, speaker_id nullable, speaker_label, start_s, end_s, text, words_json, confidence)
- `speaker_cluster` (id, conversation_id, label, embedding, person_id nullable, match_score)
- `person` (id, name, aliases, is_me, created_at)
- `voiceprint` (id, person_id, embedding, source, created_at)
- `meeting` (id, recording_id, title, pre_context, audio_mode, started_at, ended_at, rolling_summary, topics_json)
- `qa` (id, scope_type[meeting|day|range], scope_ref, question, answer, citations_json, created_at)
- `action_item` (id, conversation_id, text, owner nullable, done)
- `segment_vec`: pgvector table (segment_id, embedding VECTOR(n)); the width is a Flyway placeholder tied to the embedding model

### 9.3 Daily markdown format
```markdown
# 2026-10-20 (Tue)

## 09:42 – 10:05 · Sprint sync with Ravi, Ruchi
**Summary**
- …
**Action items**
- [ ] …
**Transcript**
- **09:42:10 Me:** …
- **09:42:31 Ravi:** …
```

### 9.4 Retention & privacy
- Configurable `raw_audio_retention_days` (default: keep forever; `0` = delete raw audio after successful processing).
- One-click delete of a conversation, a day, or a person, which cascades to vectors and exports.
- No telemetry. No outbound requests. Bind to `127.0.0.1`.

## 10. Architecture

```
┌──────────── Browser (React UI, localhost) ────────────┐
│ Today / Search / Meeting / People / Settings          │
└───────────────▲───────────── WebSocket + REST ────────┘
                │
┌───────────────┴─ Backend (Java 25, Spring Boot 4.1) ──┐
│ API layer  │ Job queue (batch) │ Live session manager │
│ Speaker ID matching │ Context builder │ Q&A           │
│ Markdown exporter   │ Serves the built React bundle   │
└──────▲─────────────────▲─────────────────────▲────────┘
       │ JDBC            │ HTTP + WebSocket    │ HTTP (OpenAI-compatible)
       │                 │ (127.0.0.1 only)    │
 Postgres 16 +   ┌───────┴──── ML sidecar (Python, FastAPI) ───┐   LM Studio / Ollama
 pgvector        │ Audio capture (sounddevice, BlackHole)      │   (LLM + embeddings)
 (Docker)        │ ffmpeg → VAD → Whisper → pyannote → align   │
                 │ Speaker embeddings                          │
                 └─────────────────────────────────────────────┘
```

### 10.1 Tech choices
Follows the existing stack used across the owner's other projects.

- **Backend:** Java 25, Spring Boot 4.1 (webmvc with virtual threads, websocket, jdbc via `JdbcClient`, no JPA), Maven. One package per feature under `com.earshot.api` (`recording`, `conversation`, `speaker`, `meeting`, `ask`, `search`, `export`, `llm`, `ml`, `settings`, `health`). SLF4J `key=value` log lines.
- **Database:** Postgres 16 + pgvector (`pgvector/pgvector:pg16` image) in a local docker-compose container bound to `127.0.0.1`. Flyway migrations in `service/src/main/resources/db/migration/V<n>__snake_case.sql`.
- **Job queue:** in-process worker in Spring Boot with a persisted `job` table in Postgres. No Redis.
- **ML sidecar (`ml/`):** Python 3.11+, FastAPI, uvicorn, pydantic 2. Exactly pinned `requirements.txt`; pytest; ruff. Spring Boot is its only caller; it binds to `127.0.0.1`.
  - **ASR:** behind an `ASREngine` interface. Default `mlx-whisper` on Apple Silicon, with `faster-whisper` as a fallback.
  - **Diarization and speaker embeddings:** `pyannote.audio` 4.x, Community-1, behind a `Diarizer` interface.
  - **VAD:** Silero VAD.
  - Runs with `HF_HUB_OFFLINE=1` so cached models never trigger network calls.
- **LLM and embeddings:** called from Spring Boot through an OpenAI-compatible HTTP client with a configurable base URL and model names.
- **Frontend:** React 19, TypeScript (strict), Vite, react-router-dom 7, CSS Modules (no Tailwind or UI kit), CSS-variable theme tokens for light/dark. ESLint + `tsc`. Vite builds into `service/src/main/resources/static` so one jar serves the API and the UI; the dev server proxies `/api` and `/ws` to 8080. A typed fetch client in `web/src/api.ts`.
- **Config:** `application.yaml` with `${EARSHOT_*:default}` placeholders; gitignored `.env` for local overrides. The sidecar reads its settings from the same `.env`.
- **Repo layout:** `service/` (Spring Boot), `web/` (React), `ml/` (Python sidecar), `docs/` (PRD, ADRs), `docker-compose.yml` (Postgres only), `earshot` start script.
- **Packaging:** a single `./earshot` script starts the Postgres container, the ML sidecar, and the Spring Boot jar. No Makefile.

### 10.2 Key interfaces (for swappability)
ML sidecar (Python):
```python
class ASREngine:       def transcribe(wav_path|np.ndarray, language=None) -> list[Word]
class Diarizer:        def diarize(wav_path) -> list[Turn]          # exclusive turns
class SpeakerEmbedder: def embed(wav, turns) -> np.ndarray
```
Backend (Java):
```java
interface LlmClient { String chat(List<Message> messages); void chatStream(List<Message> messages, Consumer<String> onToken); float[][] embed(List<String> texts); }
interface MlClient  { BatchResult process(Path wav); LiveSession startLive(AudioMode mode, Devices devices); }
```

### 10.3 Internal ML sidecar API (127.0.0.1, called only by Spring Boot)
- `POST /ml/batch` (wav path → words, turns, cluster embeddings) · `POST /ml/embed-speaker` (enrollment clip → embedding)
- `POST /ml/live/start` · `POST /ml/live/{id}/stop` · `WS /ml/live/{id}` (transcript segments, lag status)
- `GET /ml/devices` · `GET /ml/health`

### 10.4 Public API sketch (Spring Boot)
- `POST /api/import` (multipart) · `POST /api/process/{recording_id}` · `GET /api/jobs`
- `GET /api/days/{date}` · `GET /api/conversations/{id}` · `GET /api/audio/{recording_id}?start=&end=`
- `PATCH /api/speaker-clusters/{id}` (assign person) · `CRUD /api/people` · `POST /api/enroll`
- `POST /api/meetings` (start) · `POST /api/meetings/{id}/stop` · `WS /ws/meetings/{id}` (transcript, summary, status events)
- `POST /api/ask` (scope + question, streams the answer)
- `GET /api/search?q=&mode=text|semantic&from=&to=`
- `GET/PUT /api/settings`

## 11. Non-Functional Requirements

| ID | Requirement |
|---|---|
| NF-1 | Offline: zero outbound network calls at runtime (verified with a test that blocks all non-loopback sockets). |
| NF-2 | Batch: process 1 hour of speech in ≤ 30 min on the baseline hardware (target). |
| NF-3 | Live: transcript lag ≤ 5 s target, ≤ 10 s max. First answer token ≤ 5 s for quick actions. |
| NF-4 | Stability: a 2-hour live meeting runs without memory growth beyond a fixed budget. |
| NF-5 | Robustness: a crash loses at most the current segment. Meeting audio is flushed to disk continuously. |
| NF-6 | Privacy: every service (Spring Boot, ML sidecar, Postgres) bound to 127.0.0.1; files under one data directory; full delete is possible. |
| NF-7 | Configurability: all thresholds, intervals, model names, and paths in `application.yaml` (env-overridable), editable in Settings. |

## 12. Milestones (build order for Claude Code)

Each milestone is independently runnable and testable.

**M0: Skeleton**
- Repo layout (`service/`, `web/`, `ml/`), Spring Boot with a health endpoint, Postgres + pgvector container, Flyway baseline migration, React shell built into the jar, ML sidecar with a health endpoint, `application.yaml` config.
- *Done when:* `./earshot` serves an empty UI on localhost and the health endpoint reports DB and sidecar as up.

**M1: Batch transcription (no diarization)**
- Import, ffmpeg conversion, VAD, conversation segmentation, Whisper, segments stored, transcript view with audio playback.
- *Done when:* a recorder file becomes timestamped conversations in the UI.

**M2: Diarization & Me detection**
- pyannote exclusive diarization, word alignment, Me enrollment flow, Me vs Speaker N labels.
- *Done when:* the user's speech is labelled "Me" with ≥ 90% segment accuracy on a test recording.

**M3: Person library**
- Unknown cluster storage, naming flow, auto-matching in later sessions, merge and delete.

**M4: Enrichment, search & daily export**
- LLM summaries, action items, topics; vector index; text and semantic search; daily markdown export; day-scope Q&A.

**M5: Live meeting (core)**
- Audio source selection (mic / mic+system / system), live capture, streaming transcription over WebSocket, channel-based Me vs Others, meeting audio saved.
- *Done when:* a 30-minute online call shows a live transcript with ≤ 10 s lag.

**M6: Live context & Q&A**
- Rolling summary, topics, decisions; retrieval; Q&A with citations; quick actions; pre-meeting context.

**M7: Post-meeting pass & polish**
- Automatic full reprocessing after Stop, merge into daily notes, resources indicator, retention settings, setup guide (BlackHole, LM Studio).

**Phase 2 (later):** rolling-window live diarization; scheduled nightly processing (launchd); recorder auto-detect on mount.
**Phase 3 (later):** phone companion over LAN (needs auth); Obsidian vault export.

## 13. Acceptance Criteria (v1)

- [ ] A full day's recorder audio is imported and processed without manual intervention beyond clicking Process.
- [ ] The user's own speech is correctly labelled "Me" in ≥ 90% of segments on clip-on recordings.
- [ ] A named person is automatically recognized in a later recording.
- [ ] During a live online call, the transcript shows Me vs Others with ≤ 10 s lag.
- [ ] "Was X discussed?" answers correctly with timestamp citations, and says "not discussed" when appropriate.
- [ ] "Suggest a question" returns 3 relevant, non-redundant questions grounded in the meeting.
- [ ] The app works with Wi-Fi disabled.
- [ ] Deleting a day removes its audio, transcript, vectors, and markdown export.

## 14. Risks & Mitigations

| Risk | Mitigation |
|---|---|
| Pocket or clothing noise degrades ASR | Recommend a collar clip or lavalier; optional DeepFilterNet; document this in the setup guide |
| 16 GB memory pressure in live mode | Small LLM (3–8B), lazy model loading, strict live-mode priority, no batch work during meetings |
| Live room diarization is weak | Channel split for online calls; accurate attribution deferred to the post-meeting pass |
| Small local LLM hallucinates in Q&A | Retrieval-grounded prompts, mandatory citations, explicit "not discussed" behavior |
| Short utterances misattributed | Merge sub-second turns; accept as a known limitation |
| pyannote model access needs Hugging Face acceptance | One-time setup step to download and cache; runtime stays offline |
| Recording others, legal/policy | Personal-use only; easy delete; user is responsible for consent and workplace policy |

## 15. Open Questions

1. Recorder model and file format. Is the file's timestamp reliable for wall-clock time?
2. Which local LLM and model size will be the default? (This affects the Q&A prompt budget.)
3. ~~Is FastAPI + React acceptable, or should the app layer be Spring Boot with Python ML workers?~~ **Resolved (2026-10-19):** Spring Boot + React, with a Python ML sidecar, matching the existing stack (§10.1).
4. Should the daily markdown be written straight into an Obsidian vault folder?
5. Default raw-audio retention: keep forever, or delete after N days?
6. Hindi / code-mixed speech: is auto language detection enough, or should the language be pinned per meeting?
