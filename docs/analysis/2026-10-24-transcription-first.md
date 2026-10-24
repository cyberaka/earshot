# Earshot: transcription first, AI insights optional (analysis, 2026-10-24)

**Status:** built the same day (steps 1–6 below; decisions in §5). What was done and measured: `docs/implementation/2026-10-19-v1-build.md`, section 2026-10-24. Not built: T-7's short-line filter (not decided in the huddle).

## The decision (owner, 2026-10-24)

1. **Earshot's core job is transcription and diarization:** who said what, and when, offline on the local GPU. The core must work fully with every AI feature switched off, and nothing may slow it down.
2. **The AI features stay, but as an option.** Summaries, action items, titles, Q&A and question suggestions become an *Insights* layer.
   - It is off unless the user turns it on.
   - The user picks the provider **per feature**: a local LLM (LM Studio / Ollama), OpenAI or Anthropic. For example, OpenAI writes the summaries and Anthropic answers questions.
3. **What happens to the transcript during and after a call is the user's choice.** Earshot also writes a live transcript file that any outside agent (for example Claude Code) can read, whether Insights is on or off.

**Why:** in the first real live call (meeting 20, ISB class) the transcript fell 20 minutes behind. The local Qwen 7B running summary shared the GPU and 4.5 GB of memory with Whisper on a 16 GB Mac (`docs/BUGS.md` B-6, B-7). The features weren't the problem; letting them compete with transcription was.

## 1. Inventory: what Earshot does today

| Area | What it does | Uses an LLM? | Verdict |
|---|---|---|---|
| Capture (`ml/earshot_ml/capture.py`, port 8092) | Records mic and system audio (BlackHole) to 30 s FLAC chunks | No | **Core** |
| VAD (Silero) | Finds speech, cuts utterances | No (local ML) | **Core** |
| ASR (MLX Whisper large-v3-turbo) | Live and batch transcription, English + Hindi rule | No (local GPU) | **Core** |
| Diarization (pyannote / WeSpeaker) | Live clustering per channel; batch diarization for imports | No (local ML) | **Core** |
| Speaker ID (voiceprints, `speaker/*`) | Me enrollment, remembered voices, line reassignment, rematch | No (local ML) | **Core** |
| Import / Inbox / batch pipeline | prepare → diarize → identify → transcribe → **enrich** → index → export | Only `enrich` | Core; `enrich` becomes optional |
| Notes | The user's own notes | No | **Core** |
| Keyword search (Postgres `tsvector`) | Words and names across days | No | **Core** |
| Daily markdown export (`MarkdownExporter`) | `data/notes/YYYY-MM-DD.md` | No | **Core**: transcript and notes always; Insights sections only when present |
| Live transcript file (`scripts/live-dump.py`, today's stopgap) | `data/live/current.md` for an outside agent | No | **Promote to core** (§3) |
| Live running summary (`MeetingService.summarize`, every minute) | Summary, topics, decisions, agenda status | Yes | **Insights:** live summary |
| After-meeting summary (`PostMeetingJob.finishFromLive`) | Final summary, action items, generated title | Yes | **Insights:** meeting summary |
| Enrichment (`pipeline/Enricher`) | Title, summary, topics and action items for imports and recorder days | Yes | **Insights:** meeting summary (same feature) |
| Ask (`ask/*`, `AskBox`, `/api/ask`, `qa`) | Q&A per meeting, day or range; quick actions incl. Suggest a question | Yes | **Insights:** Ask |
| Agenda / pre-context | Feeds the live summary and Ask | Feeds the LLM | **Insights**: shown only when live summary or Ask is on |
| Semantic search (`segment_vec`, `Indexer`) | Embeddings for hybrid ranking | Yes (embeddings) | **Insights:** semantic search; keyword search alone when off |
| Health, settings, badges | `llm` up/down, `llmQueue`, model names, summary timings | About the LLM | Become per-provider and per-feature (§2) |

The ML sidecar (`ml/`) has no LLM code. The LLM code in the service is about 1,000 lines. It already goes through one class, `LlmClient` (OpenAI-compatible: LM Studio, Ollama), which makes the split clean.

## 2. Design: the Insights layer

### 2.1 Features and providers

| Feature | When it runs | Providers |
|---|---|---|
| **Live summary** (running summary, topics, decisions, agenda) | During a meeting | Off · Local · OpenAI · Anthropic |
| **Meeting summary** (title, summary, topics, action items; after Stop and for imports) | After a meeting or import | Off · Local · OpenAI · Anthropic |
| **Ask** (meeting, day or range Q&A; quick actions; suggest a question) | On request | Off · Local · OpenAI · Anthropic |
| **Semantic search** (embeddings) | After transcription | Off · Local · OpenAI (Anthropic has no embeddings API) |

- **Every feature defaults to Off.** A fresh install is transcription and diarization only.
- **Providers are set up once, in Settings → Insights:**
  - Local: base URL and model name, as today.
  - OpenAI: API key and model.
  - Anthropic: API key and model.
- Each feature then picks a provider and, optionally, a model.

### 2.2 Code shape

- An `InsightsProvider` interface with `chat`, `chatStream` and `embed`. There are three implementations:
  - `OpenAiCompatible`: today's `LlmClient`, reused for local and for OpenAI with a different base URL and a key;
  - `Anthropic`: the Messages API with streaming;
  - `None`.
- An `Insights` service maps feature → provider. Every current call site asks `insights.enabled(Feature.X)` first, and does nothing when the feature is off.
- With everything off:
  - no LLM code path runs;
  - no LLM health check runs;
  - the UI hides the panels: the Meeting page shows transcript, speakers and notes only, with no Ask boxes and no summary sections.

### 2.3 Rules that protect transcription

1. **A local model never competes with live transcription.** While a meeting is live, local-provider work is held until Stop by default. A setting allows it for users with GPU to spare.
   - Cloud providers don't use the GPU, so a live summary from OpenAI or Anthropic can run during the call.
2. **One LLM request at a time per provider** (today's single slot), and none may block a transcription thread.
3. **An LLM failure never fails a meeting, import or export.** The feature shows "unavailable"; the transcript is complete regardless.

### 2.4 Privacy (Earshot's "offline" promise changes here)

- With a cloud provider on, **transcript text leaves the Mac** and goes to that company.
- The UI must say so where it happens:
  - Settings shows "Sends transcript text to OpenAI" next to each feature using a cloud provider.
  - The meeting header shows a small "Insights: OpenAI" marker while a cloud feature is active.
- Audio never leaves the Mac; only text is sent.
- API keys come from environment variables or `.env` (D-3). They are never logged, never stored in the database, and never exported.
- Earshot's "all local" badge becomes "Transcription local · Insights: OpenAI" when a cloud provider is on.

## 3. The core job: what has to be true

| # | Target | Today (meeting 20) | Work |
|---|---|---|---|
| T-1 | Live transcription keeps up: ≤ 0.8× real time sustained for 60+ minutes on this Mac with Zoom running | 2–109×, 20 min behind | Rule 2.3-1, then the B-6 measurements; a smaller live model if still short (D-4) |
| T-2 | Honest lag and a clear warning when it can't keep up | Lag overstated; no warning | B-5, B-4 |
| T-3 | Nothing lost at Stop | ~18 min of part 1 untranscribed | B-2: transcribe the rest from disk before "done" |
| T-4 | Transcript available to outside agents within ~2 s of each line | 5 s polling script started by hand | Live transcript file built into the service: written on each new line or relabel, true lag in the header, starts with Earshot. Later an MCP connector. |
| T-5 | Earshot stays up | Stopped on 21 Oct, unnoticed | B-3: launchd KeepAlive |
| T-6 | Memory budget fits 16 GB | Docker VM 8 GB + Qwen 4.5 GB + Whisper 3.4 GB | Local LLM only when chosen and never during live (2.3-1); native Postgres, no Docker VM (D-5); ML footprint shown in status |
| T-7 | Diarization holds up | Siblings merge; short junk lines on an unnamed speaker | Drop very short low-confidence lines; measure on the ISB recording |
| T-8 | Gaps in meeting 20 filled | Part 1: 28:10–45:50; part 2: 6:50 to end | Batch-transcribe from the saved channel files |

## 4. Plan

1. **Protect live transcription now (small):**
   - Hold local-provider work during live meetings (rule 2.3-1).
   - Fix B-2 (Stop keeps the backlog), B-5 (honest lag) and B-4 (warning).
   - Fill meeting 20's gaps.
2. **Live transcript file in the service (T-4)**, replacing `scripts/live-dump.py`.
3. **Insights layer:**
   - Provider interface with the OpenAI-compatible and Anthropic implementations.
   - Per-feature settings, all defaulting to Off, and existing call sites gated.
   - UI hides what's off.
   - Settings → Insights page with privacy labels.
4. **Data:** no deletions. Existing summaries, action items and Q&A stay and show when present. One small migration for the per-feature settings.
5. **Stability:** launchd KeepAlive (B-3, D-8) and native Postgres (D-5).
6. **Docs:**
   - PRD revision r13, with a backup first:
     - §1 and §2 restated as core + optional Insights;
     - §6 becomes Live Transcription, plus optional Live Insights;
     - §8 and §10 cover providers;
     - §9.4 covers privacy for cloud providers.
   - Update the implementation log.

**Verification:**
- With all Insights off, the service starts with LM Studio stopped, no LLM request is made in a replay meeting, and the UI shows no AI panels.
- With live summary on OpenAI, the summary updates during the replay while ASR speed is unchanged.
- With live summary on Local, nothing runs until Stop.
- Ask works on Anthropic, with streaming.
- The tests pass.

**Effort:** steps 1–2 about half a day; step 3 about a day; steps 4–6 a few hours.

## 5. Decisions (huddle, 2026-10-24)

| # | Question | Decision |
|---|---|---|
| D-1 | Earshot's scope | Transcription and diarization are the core. AI insights are optional, off by default, with a provider chosen per feature (local, OpenAI, Anthropic). |
| D-2 | Local LLM during a live meeting | **Never.** Local work waits until Stop. Cloud providers may run live. |
| D-3 | API keys | **Environment variables / `.env`** (`OPENAI_API_KEY`, `ANTHROPIC_API_KEY`), read at startup and edited by hand. Settings shows only whether a key is present, never the key. `.env` is already git-ignored. |
| D-4 | Live model | **Measured: keep large-v3-turbo** (66-min replay: lag median 3.2 s, p90 5.9 s; 0.50× real time). Was: **Measure first.** Keep large-v3-turbo. After the live-path fixes, run a 60-minute replay with Zoom open, and switch to small-live / large-after-Stop only if it can't hold ≤ 0.8× real time. |
| D-5 | Postgres | **Native Postgres 16 + pgvector (Homebrew).** Earshot no longer needs Docker. The data is moved with a dump and restore, and the Docker container is kept until the move is verified. |
| D-6 | Default language | **English + Hindi** stays the default. |
| D-7 | Stop while behind | **Finish from saved audio.** Stop returns at once; the untranscribed tail is transcribed from disk in the background, with "catching up: N min left" on the meeting page. |
| D-8 | Always on | **launchd agent with KeepAlive**: starts at login and restarts if it stops. |
| D-9 | Build order | **Live path first**, then the 60-min measurement, then the Insights layer, then native Postgres. |
| D-10 | When AI runs (owner rule, evening) | **Only on the user's click.** No timer, no loop, nothing after Stop or an import. A local call costs GPU, a cloud call costs money. Each button shows lines, ~tokens and provider before the click; a cloud summary is one request with the whole transcript. Enforced by `ClickOnlyTest`. |
| D-11 | Re-transcribe; back-to-back meetings (owner, evening) | A **Re-transcribe** button on finished meetings, click only. All batch work hands the GPU back within seconds when a meeting goes live and resumes after Stop. The live transcript file feeds an external agent in real time; that is Earshot's purpose. |
