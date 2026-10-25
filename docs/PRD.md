# PRD: Personal Conversation Recorder & Live Meeting Transcription

**Working name:** Earshot
**Owner:** Abhinav
**Status:** Draft v7 (r16, 2026-10-24: a click is honoured during a live meeting too; r15: Re-transcribe button, batch work yields to live meetings; r14: AI insights run only on the user's click; r13: transcription first, AI insights optional)
**Date:** 2026-10-19, revised 2026-10-24
**Build approach:** Implemented with Claude Code, milestone by milestone (see §12). Significant design decisions get a numbered ADR in `docs/adr/` before they are built.

---

## 1. Summary

Earshot is a local web app that runs entirely on a laptop. **Its job is transcription and diarization: who said what, and when** (owner decision, 2026-10-24). It has two modes:

1. **Daily Notes (batch).** Each evening, audio from a pocket voice recorder is imported. The app transcribes and diarizes it, labels the user's own voice, and produces a searchable, speaker-attributed record of the day's conversations.
2. **Live Meeting Transcription.** During a meeting (in-person or online), the app listens and transcribes in near real time, with speakers separated and named. The transcript is also written to a plain file as it happens, so any agent the user chooses (for example Claude Code) can read it during the meeting and answer questions or suggest what to ask.

What happens with the transcript, during the meeting and after it, is the user's choice. Earshot can add **optional AI insights** (§6.8): summaries, action items, Q&A and semantic search, each off by default, each with a provider the user picks (a local model, OpenAI or Anthropic), and each runs **only when the user clicks its button**. They never come at the cost of transcription: a local model never runs while a meeting is live.

Transcription and diarization run offline. No audio ever leaves the machine; transcript text leaves it only for an insight the user has pointed at a cloud provider. The user supplies the Whisper turbo model.

**Why r13:** in the first real live call (2026-10-24, a 2-hour class) the transcript fell 20 minutes behind, so the live features were useless. The local 7B model writing the running summary every minute shared the GPU and 4.5 GB of memory with Whisper on a 16 GB Mac, and the speech engine's GPU buffer cache had grown to 16 GB (`docs/BUGS.md`, `docs/analysis/2026-10-24-transcription-first.md`).

## 2. Goals & Non-Goals

### Goals
- G1: Turn a full day of pocket-recorder audio into speaker-attributed, timestamped notes with no manual effort beyond importing the files.
- G2: Reliably separate **the user ("Me")** from **others** in both modes.
- G3: A live transcript that keeps up with the room (§6.4) and that an outside agent can read while the meeting runs.
- G4: Transcription and diarization 100% offline on one laptop; nothing leaves it unless the user points an optional insight at a cloud provider.
- G5: Name recurring people from voiceprints over time.
- G6: Optional AI insights (summaries, Q&A, semantic search) from a provider of the user's choosing, never at the cost of transcription (§6.8).

### Non-Goals (v1)
- Native mobile apps. A phone can later reach the web app over LAN as a thin companion (Phase 3).
- Cloud sync, sharing, or multi-user collaboration.
- Real-time translation.
- Joining meetings as a bot or integrating with Teams/Zoom/Meet APIs.
- Speaking answers aloud. All assistant output is on-screen only.
- Perfect live diarization among multiple remote participants (see §6.3).
- Echo cancellation for online calls played through laptop speakers (v1 requires headphones; see §6.2).

## 3. Users & Context

- **Single user**, personal use only. The output is private notes.
- **Speech:** primarily Indian English. Hindi and code-mixed speech should transcribe at Whisper's native quality.
- **Language default:** `en+hi`, written as **Hinglish in Latin letters** (owner decision 2026-10-25: "I prefer Hinglish in Latin letters. Avoid Devanagari"). Each utterance is decoded once in English with a short romanized Hinglish example ahead of the names-and-terms prompt, so Hindi comes out as said ("Data engineering totally aapka tha"), English terms stay English and searchable, and pure English is unchanged; any Devanagari, and lines that only echo the example, are dropped. Without the example, English decoding translates Hindi ("Data Engineering was totally your own"). Replaces the 2026-10-20 rule (English, then a Hindi pass in Devanagari when Whisper was unsure of the English), which wrote Hinglish in Devanagari ("डेटा एंजिनरिंग") and translated what it did not switch. `hi` alone still writes Devanagari. Overridable per import, recording and meeting (`en+hi`, `en`, `hi`, `auto`).
- **Names and terms (owner request R-3, 2026-10-24):** a comma-separated list in Settings (`vocabulary`, for everything) plus a meeting's own list (at Start, or "Names & terms" during the meeting: the next sentence uses it) is given to Whisper as its initial prompt ("Names and terms: Abhinav, Rapido, …", ≤ ~400 characters). Lines made only of prompt words are dropped as Whisper echoing it. Trial on the 2026-10-24 class: fixed Parath → Bharat, Sejal → Sajal, Zometo → Zomato, Captiv9 → Capgemini, cross-percussion → cross-functional; "Aveena" (Abhinav) stayed; one clip lost "Hardik"; no more invented lines over silence than without. Hindi passes get no prompt (it pulls the decoder back to English).
- **Corrections by the user (owner request R-4, 2026-10-25; built the same day):** "now it's time to bring human in the loop… Some words which are frequently getting wrongly transcribed I can correct." Raised after reading the re-processed 23 Oct recorder day (Hinglish spelling such as "Taaf engineer dudo", repeats, a colleague's voice merged into the user's). Three parts:
  1. Edit a line's text in place; the original is kept and the line shows "edited"; search, exports and the live transcript file use the correction.
  2. Remembered corrections: an edit that swaps a word or phrase offers "Always change X → Y"; the list (Settings, removable) is applied to new transcripts, to existing ones on request, and feeds Names and terms.
  3. A run of lines given to one speaker in one go ("from this line to that line: Thanveer"), besides today's per-line "Who said this?".
  4. "Re-transcribe this line" on a badly transcribed line (owner, same day: "I want to fix it myself or propose a transcription again… using local model or a cloud model"). Click-only (D-10), one line at a time. Local: a different local model as a second opinion (re-running the same model on the same audio mostly returns the same text), optionally with current Names and terms and a little audio either side. Cloud: OpenAI transcription (Anthropic's API takes no audio); the button says before the click that the line's audio leaves the Mac, with its length and approximate cost. The result is shown as a proposal beside the current text (Use this / Keep current, editable first), never written over it; an accepted proposal counts as the user's correction (part 2).
  As built: each paragraph on a day's transcript has edit · redo · + note; editing or keeping a proposal makes the paragraph one line, keeps Earshot's text (`segment.original_text`, shown as "edited", with "Put back Earshot's text") and records who changed it (`edited_by`: user, correction, `local:whisper-large-v3`, `openai:gpt-4o-transcribe`). An edit that swaps up to four words offers "Always change X to Y"; corrections (Settings → Corrections, `correction` table) are applied to every new line (batch, after-Stop, Re-transcribe, live) and to existing lines on "Fix existing transcripts", and their right spelling joins Names and terms. "Select lines" (shift-click for a run) → "Who said these?" gives the lines to one person in one request. Second opinion: `mlx-community/whisper-large-v3-mlx`, loaded for the click and the everyday model loaded back (engine 1.9 GB after, 5.7 GB peak); cloud: OpenAI `gpt-4o-transcribe` (`OPENAI_TRANSCRIBE_MODEL`), a reply that only repeats the prompt counts as nothing heard. Tried on conversation 30 (23 Oct): stored "college paas pura meera tha, ham doonohi kaasun hai"; large-v3 "coding part pura mera tha, hum dono hi kaasun hai uske liye" (9.6 s); OpenAI "coding part mera tha" (2.4 s).
- **Settings:** office conversations, cafeteria, travel, in-person meeting rooms, and online calls on the laptop.

## 4. Constraints & Assumptions

| Area | Decision / Assumption |
|---|---|
| Platform | Local web app. Spring Boot backend (serves the UI) plus Python ML processes, all on one laptop, served on `localhost`. |
| Baseline hardware | Apple Silicon Mac, **16 GB unified memory**. Must work within this budget. |
| Disk security | FileVault is assumed on. Earshot does not add its own at-rest encryption in v1. |
| Network | No outbound network calls at runtime, except to a cloud provider the user chose for an optional insight (§6.8). Model downloads are a one-time setup step. |
| ASR | Whisper **large-v3-turbo**, provided by the user. |
| LLM | **Optional** (§6.8). Per feature: off (default), a local model behind an OpenAI-compatible endpoint (LM Studio or Ollama), OpenAI, or Anthropic. API keys come from the environment or `.env` only. |
| Embeddings | **Optional**, for semantic search: a local model through the OpenAI-compatible endpoint, or OpenAI. Keyword search needs none. |
| Storage | Native Postgres 17 + pgvector (Homebrew, `127.0.0.1:5435`), plus audio and note files on disk under a single data directory. No Docker (owner decision 2026-10-24: its VM reserved 8 GB of a 16 GB Mac). |
| Auth | No user login in v1. All services bind to `127.0.0.1`, with Host/Origin checks (§9.4). |
| Recorder | Generic voice recorder producing WAV or MP3. Its clock is **not** trusted blindly; see §5.4. |
| Online calls | Mic + System capture requires headphones in v1. |

## 5. Mode A: Daily Notes (Batch)

### 5.1 User flow
1. The user connects the recorder and drags the audio files into the web UI, or drops them into a watched `inbox/` folder.
2. The import screen shows the derived start time of each file (§5.4) and lets the user apply a clock offset to the whole import before processing.
3. The user clicks **Process**. Optionally, processing can run on a schedule (Phase 2).
4. The app processes the files in the background, with per-stage progress shown in the UI.
5. The user opens **Today**. It shows a timeline of conversations, each with speaker-attributed transcript (plus summary and action items when the user has clicked Summarize, §6.8). Ambient conversations (§5.2 step 7) are collapsed by default.
6. The user names unknown speakers ("Speaker 3 → Ravi"). The name appears everywhere immediately, including summaries and exports (§5.5), and future sessions apply it automatically.

### 5.2 Processing pipeline
1. **Ingest:** skip files already imported (matched by SHA-256 of the content). Copy the original file into `data/audio/raw/YYYY-MM-DD/`, then convert to a 16 kHz mono working copy (FLAC) in `data/audio/work/` with ffmpeg. The working copy is the reference for all offsets and playback.
2. **Denoise (optional, configurable):** DeepFilterNet, applied only to the audio fed to ASR. The working copy, VAD, diarization, and speaker embeddings use the un-denoised audio, because denoising distorts voice characteristics.
3. **VAD:** Silero VAD produces speech regions.
4. **Day timeline:** place all of the day's files on one wall-clock timeline (§5.4), including files from earlier imports. Segmentation runs over this timeline, so a conversation that spans a recorder file split stays one conversation. When a later import adds a file that touches an already-processed conversation (gap below `conversation_gap_seconds`), that conversation is re-segmented and reprocessed.
5. **Conversation segmentation:**
   - A gap of `≥ conversation_gap_seconds` (default 120) starts a new conversation.
   - A conversation longer than `max_conversation_minutes` (default 90) is split at its longest internal gap.
   - Drop conversations shorter than `min_conversation_seconds` (default 20).
6. **Diarization (per conversation, not per day):** pyannote **Community-1** in exclusive mode. Diarization runs before ASR so its result can decide whether ASR runs at all (the M-1 spike measures the relative cost of the two).
7. **Speaker identification:**
   - Compute an embedding for each diarized speaker cluster.
   - Match it to the **Me** voiceprints (cosine similarity ≥ `me_threshold`) and to the named-person library (≥ `person_threshold`). **More than one cluster may match Me** in a conversation (diarization often splits one voice).
   - Store unmatched clusters as **Unknown** with their embedding so they can be named later.
   - A conversation where Me speaks less than `me_min_speech_seconds` (default 10) is marked **ambient**: kept as audio, hidden by default, and not transcribed or enriched. The user can transcribe an ambient conversation on demand.
   - Until a Me voiceprint exists (§7), nothing is marked ambient. After the first enrollment, the app re-runs matching and ambient marking on everything processed so far.
   - A wrongly-ambient conversation is a silently lost note, so the ambient test errs toward transcribing: it uses a lower threshold (`me_ambient_threshold`, below `me_threshold`). The Today view shows ambient rows with their duration, and re-matching (A-12) transcribes any conversation that stops being ambient.
8. **ASR (per non-ambient conversation):** Whisper turbo with word timestamps, restricted to VAD speech regions. Discard segments that look hallucinated: `no_speech_prob` above threshold with low average log-probability, or compression ratio above threshold (all configurable).
9. **Word to speaker alignment:** assign each word to the speaker turn with maximum time overlap. Merge turns shorter than 1 s into the adjacent turn when the speaker is ambiguous.
10. **Enrichment (optional, §6.8; on the user's click only, never part of the pipeline):** Summarize on a conversation generates a title, a 3–5 bullet summary, action items with owners, and topics or tags. Speakers are referred to by stable tokens, never by display names (§5.5). A provider failure skips this step; the transcript is complete without it.
11. **Index:** the full-text index always (a generated column); embeddings for semantic search only when that option is on (§5.6).
12. **Export:** write `data/notes/YYYY-MM-DD.md`, a human-readable daily file (format in §9.3). Ambient conversations and recorder conversations linked to a meeting (A-13) are left out; meetings are included.

### 5.3 Functional requirements
- A-1: Import files by drag-and-drop and through a watched folder. A file in the watched folder is imported only after its size has been unchanged for 10 s, so a file still being copied is not picked up.
- A-2: Processing is resumable. Each (conversation, stage) is checkpointed; a crash or a pause resumes from the last completed stage.
- A-3: Per-stage progress shown in the UI.
- A-4: Conversation timeline with wall-clock times derived from the recording start time (§5.4) plus the offset.
- A-5: Transcript view with speaker labels, timestamps, and click-to-play audio for any segment.
- A-6: Rename a speaker in one conversation, with the option to "apply to voiceprint library".
- A-7: Search across all days: full text + fuzzy always, plus semantic when that option is on.
- A-8: Daily markdown export, regenerated when any of its content changes.
- A-9: Ask questions across a day or a date range (optional, §6.8; same Q&A engine as Mode B, different scope).
- A-10: Per-import clock offset, and a manual start-time override per recording.
- A-11: Show and un-hide ambient conversations, and transcribe one on demand.
- A-12: Re-run speaker matching over past conversations after a threshold or voiceprint change (cheap: embeddings are stored, no audio is reprocessed).
- A-13: Overlap with live meetings: a recorder conversation that overlaps a meeting recorded in Mode B by at least 50% of the shorter of the two (recorder clocks drift, so exact overlap is not required) is linked to that meeting and hidden by default, so the day's notes show it once. The meeting's record is primary. The user can unlink it.

### 5.4 Recording start time
Recorder clocks drift or reset, and copying files can rewrite file timestamps. `recording.started_at` is derived in this order:
1. A timestamp in the filename, parsed with the configurable `recorder_filename_pattern`.
2. File modification time minus duration (most recorders write mtime at the end of a recording).
3. File creation time.

Then the import's clock offset is applied. The source used is stored (`started_at_source`) and shown on the import screen.

Recorders store local time with no zone. Each import has a time zone (default: the laptop's current zone, editable on the import screen, because the user travels). `started_at` is stored as a UTC instant together with that zone. A conversation belongs to the date, in its recording's zone, on which it **starts**.

### 5.5 Speaker names in derived text
The user names speakers after enrichment has run, and later renames, merges, or deletes people. So derived text never stores display names:
- LLM prompts label speakers `S1`, `S2`, … and the stored summary, action items, and titles use tokens such as `{{spk:<cluster_id>}}`.
- Tokens are rendered to the current name (or "Speaker N" / "Unknown") at view and export time.
- Changing a name, merge, or delete re-renders the affected daily markdown files. LLM output is not regenerated.
- Search chunks are embedded without speaker names; speaker filtering uses the segment's cluster.
- When derived text or transcript is sent to the LLM for Q&A, tokens are rendered to current names, so "what did Ravi say about the budget?" works.
- In search and Q&A, a query term that matches a person's name or alias also becomes a speaker filter candidate: results spoken by that person rank higher.

### 5.6 Search
- **Full text:** Postgres `tsvector` with the `simple` configuration (works for code-mixed text).
- **Fuzzy:** `pg_trgm` similarity, for names and terms Whisper misspells.
- **Semantic (optional, §6.8):** pgvector cosine similarity.
- Results are merged with reciprocal-rank fusion. Q&A retrieval (§6.5) uses the same hybrid search.

## 6. Mode B: Live Meeting Transcription

### 6.1 User flow
1. The user clicks **Start Meeting**, picks an audio source (§6.2), and optionally adds a title (and, when an AI insight that uses it is on, pre-meeting context such as an agenda or notes, pasted or uploaded as `.md`/`.txt`/`.pdf`).
2. A live transcript scrolls in the main panel, with speakers separated and nameable; the user's notes sit beside it. The transcript is also written to `data/live/current.md` within about a second of each line (§6.4), for any agent the user runs.
3. With the optional summary on (§6.8), a side panel has a **Summarize** button: each click makes one summary of the meeting so far, with **topics covered**. With the optional Ask on, the user can type a question or click a quick action anytime:
   - **"Was X discussed?"**
   - **"Suggest a question I could ask"**
   - **"Summarize the last 5 minutes"**
   - **"What decisions / action items so far?"**
   - **"What's still open from the agenda?"** (only if an agenda was provided)
4. Answers stream into the side panel with clickable timestamp citations.
5. The user clicks **Stop**. The live transcript becomes the final one, and anything live transcription had not reached yet is transcribed from the saved audio (§6.6).

### 6.2 Audio sources
| Source | Captures | Speaker attribution while live |
|---|---|---|
| **Mic only** (in-person meeting) | Room audio through the laptop mic | Everything labelled "Room" while live; full diarization after the meeting |
| **Mic + System audio** (online call) | Mic channel = Me; system loopback (BlackHole) channel = others | **Me vs Others** from the channel, with no model needed |
| **System only** | Remote participants only | "Others" |

- **Hybrid meetings** (an online call joined from a room with other people present): the mic channel contains Me *and* the room. Live, the mic channel is labelled "Me / Room". The post-meeting pass diarizes the mic channel too (§6.6).
- Mic + System uses one **macOS Aggregate Device** (mic + BlackHole, drift correction on for BlackHole), captured as a single multi-channel stream. This keeps both channels on one clock for the whole meeting.
- **Headphones are required** for Mic + System. Without them the mic records the call from the speakers, and the remote voices land in the "Me" channel. The check is made on the audio itself, not the output setting (the call app picks its own speaker, which macOS doesn't report): while recording, the capture process compares the loudness of the mic channel with the system channel every second; if the mic rises and falls with the call (correlation ≥ 0.5 in 4 of the last 10 checks with call audio), the meeting shows "Your mic is picking up the call" with a fix hint. (Owner decision 2026-10-20: the earlier pre-Start check of the system output gave false alarms when only the call app used headphones.)
- **Silent system channel:** if the call app sends its audio straight to the headphones instead of the Multi-Output Device, BlackHole hears nothing. If the system channel stays silent for 60 s while the mic channel has speech, the UI shows "No call audio is being captured" with a fix hint.
- **Switching language mid-meeting (owner request, 2026-10-20):** the live meeting header shows the transcription language and can change it; the next sentence uses the new one, earlier lines stay as they were.
- **Switching input mid-meeting:** the live meeting has an Input control (e.g. Aggregate Mac → Aggregate Jabra when putting on a headset). Recording moves to the new device without stopping: same part, same timeline, about 0.1 s missed. A device that can't work is refused and recording continues on the old one.
- The setup guide covers installing BlackHole, creating the Multi-Output Device (so the user still hears the call) and the Aggregate Device.

### 6.3 Live speaker attribution scope
- **v1:** channel-based Me vs Others for online calls. Room audio is unattributed while live.
- **Owner request, 2026-10-19:** speakers are separated live (instant labels, regrouped every 30 s) so the user can name them during the meeting. Earlier plan: re-diarize a rolling window (e.g., the last 60–120 s) every 30 s with pyannote, and match the clusters to voiceprints. Labels may be revised as more audio arrives.
- Final attribution comes from the post-meeting pass (§6.6), which keeps the channel split where one exists.

### 6.4 Live transcription
- The capture process (§10) does two things with each channel: it writes 30-second FLAC chunk files for durability (a crash never leaves an unreadable file), and it streams the same frames to the ML process over a local socket for low latency. After an ML restart, the ML process catches up from the chunk files, then switches back to the stream.
- VAD cuts segments at natural pauses. If speech runs past `live_max_segment_seconds` (default 15), cut at the lowest-energy point in the last 3 s, keep 1 s of overlap with the next segment, and remove the duplicated words when stitching.
- Each channel is transcribed separately. Show text in the UI within **≤ 5 s** of the segment ending (target), and never more than 10 s.
- **Lag** is the age of the newest line: the age of the oldest audio still waiting, or, with nothing waiting, how far the room has moved on since the last line. (r13: the first formula added the queued audio to the wait and counted it twice; it once showed 1060 s of lag 1013 s into a meeting.)
- If the lag passes `live_lag_warn_seconds` (default 60), the meeting shows a plain-language banner: how far behind, whether it is catching up or falling further behind (transcription speed over the last minute above 1× real time), what helps, and that nothing is lost. Never drop audio: the audio is on disk regardless, and Stop covers any gap (§6.6).
- **Live transcript file (owner request, 2026-10-24):** `data/live/meeting-<id>.md` per meeting and `data/live/current.md` for the meeting being recorded, rewritten atomically within ~1 s of each new line, regrouping, rename or moved line (so names are right throughout), with a header saying whether it is recording and how far behind it is. Deleting the meeting deletes its file. This is how an outside agent reads the meeting live.
- The status bar shows transcription speed (over the last minute), the speech engine's memory (resident plus GPU buffers) and the transcript file.

### 6.5 Context building (optional insights only)
Only for Summarize and Ask clicks (§6.8). Nothing here calls a model on its own:

| Component | Update cadence | Purpose |
|---|---|---|
| Raw transcript | Per segment | Source of truth, citations |
| Search index (full text; vectors only when the user clicks "Prepare … for meaning search") | Per segment (full text) | Recall questions ("was X discussed?") |
| Meeting summary | Only when the user clicks Summarize: one request with the whole transcript so far (cloud only during a meeting, §6.8) | Compact meeting state that fits the LLM context |
| Topic list | With each Summarize click | "Topics covered" panel, agenda tracking |
| Decisions & action items | With each Summarize click; each item stores the offsets of the segments it came from | Quick action answers, cited by those offsets |
| Pre-meeting context | Once at start | Agenda coverage, question suggestions |

**Q&A prompt assembly**, capped at `qa_prompt_max_tokens` (default 3000), trimmed in this order (last item trimmed first):
`system instructions + user question + top-k retrieved segments (hybrid, §5.6) + last ~2 min of verbatim transcript + rolling summary + pre-meeting context`

**Day / range Q&A prompt assembly** (Mode A, A-9), same cap:
`system instructions + user question + top-k retrieved segments across the scope + summaries of the conversations those segments belong to + list of all conversation titles and times in the scope`

**Answer rules:**
- Answer only from the meeting content. If something wasn't discussed, say so plainly.
- Cite timestamps for factual claims. Only retrieved or verbatim transcript segments count as evidence. The rolling summary is a navigation aid and is never cited.
- "Was X discussed?": retrieve up to 10 candidates with hybrid search, then one LLM call lists which candidates are relevant and answers from those. The answer is "not discussed" when none is relevant. (Fused ranks have no absolute scale, so no fixed score threshold is used.)
- "Suggest a question" uses the summary, open agenda items, recent transcript, and gaps or ambiguities. Return 3 short candidate questions.

### 6.6 After Stop
**Owner decision, 2026-10-19:** no slow re-run after a meeting. On Stop:
- the ML process drops its queue at once (lines made after Stop would have nowhere to go, and would hold the GPU);
- **nothing is lost (owner decision D-7, 2026-10-24):** for each channel, the audio after its last line is transcribed from the saved file, and the lines go to the voices already heard in the meeting (voice similarity ≥ 0.5) or to new speakers. The meeting page says "Catching up" with how much is left. (r13: before this, Stop while lagging silently dropped the backlog, 18 minutes of one class.) Without a backlog this takes seconds;
- the live transcript plus the caught-up lines become the final transcript;
- a summary the user made during the meeting, if any, becomes the meeting's summary; no model runs at Stop (the user can click Summarize afterwards, §6.8);
- the per-channel audio is joined for playback, the day's markdown is exported and the transcript file is rewritten as stopped.

A full re-run (re-transcribe and re-diarize each channel, as Mode A steps 6–11) remains available as an explicit job (`post_meeting_full`) and is never started automatically.

**Re-transcribe (owner request, 2026-10-24):** a finished meeting has a **Re-transcribe** button: a fuller pass from the saved audio (diarization and Whisper over whole files, with more context than live). Only on the user's click, right after the meeting or at the end of the day; never automatic.
- Clicked while another meeting is live, it starts at once and shares the GPU with that meeting (the confirm says so; measured: a 2-min meeting re-done in 18 s, live lag 2.5 s). Otherwise it is a batch job: it waits while a meeting is live, and stops within seconds when one starts (the speech engine checks before every ~30 s chunk and hands the GPU back). It carries on after that meeting's Stop. Measured: 3.8 s from the next meeting's start to the GPU being free.
- The current transcript stays visible until the new one replaces it. Speaker names carry over by voice (library voices, and names given in this meeting at similarity ≥ 0.5); lines moved by hand are redone. Notes, screenshots and any summary stay.
- The button says what it costs (roughly half the meeting's length of CPU and GPU) and asks once more before it starts.

### 6.6a Resuming a meeting
**Owner request, 2026-10-19:** a stopped meeting can be resumed at any later time (minutes or days).
- Resume records a new part of the same meeting: its own recording and conversation, so it appears in the
  daily notes of **every day it has a part** (owner decision), marked "(continued)" after the first.
- The meeting page joins all parts, with a "Resumed … · after …" divider; notes, Q&A and the rolling summary
  cover all parts; Q&A citations stay exact per part.
- Speakers heard in earlier parts are carried into live speaker separation, so they keep their label or name;
  naming a speaker in any part names them in all parts.
- Resume defaults to the audio source the meeting last used; it can be changed.

### 6.7 Functional requirements
- B-1: Start or stop a meeting, and pick the audio source and devices.
- B-2: Live transcript with no more than 10 s lag under normal load.
- B-3: Optional (§6.8): summary and topic panel, made when the user clicks Summarize (never automatically).
- B-4: Optional (§6.8): free-text Q&A plus the quick-action buttons listed in §6.1.
- B-5: Optional (§6.8): streaming answers with clickable timestamp citations that scroll the transcript to the cited spot.
- B-6: Optional pre-meeting context upload (only shown when an insight uses it).
- B-7: Meeting audio saved to disk per channel for the post-meeting pass.
- B-8: Stop finishes the meeting from the live transcript within seconds; a full re-run only on demand.
- B-9: The UI stays responsive while the LLM is generating. Transcription never pauses for Q&A.
- B-10: A warning during a Mic + System meeting when the mic picks up the call (no headphones), and a warning when the system channel is silent (§6.2).
- B-11: The Mac does not idle-sleep during a meeting (§8).
- B-12: The live transcript file (§6.4), current within ~1 s, for outside agents.
- B-13: Stop never loses text: a backlog is transcribed from the saved audio (§6.6).
- B-14: A plain-language banner when transcription falls behind (§6.4).
- B-15: Screenshots in the transcript (§6.9).

### 6.9 Screenshots (owner request R-1, 2026-10-24)
The slide on screen at a moment of the meeting belongs next to what was said then. No AI is involved.
- **Adding:** on the meeting page (live or finished), paste an image (⌘V), drop image files anywhere on the page, or "Add screenshot". PNG, JPEG, GIF and WebP up to 25 MB, checked by content, not by name.
- **Placement:** a screenshot is often pasted a minute or two after it was taken. It goes at the time it was taken when that falls inside a part of the meeting (+60 s slack after the end): from a macOS / CleanShot file name ("Screenshot 2026-10-24 at 11.18.52"), else the file's modified time (sent by the browser; ignored when within 5 s of now, as for a paste). Otherwise at the time it is attached (live), or at the end of the last part (attached afterwards). The row says which.
- **Stored** under the part's recording folder, `data/audio/meetings/<id>[/part-N]/attachments/<HHMMSS>-<name>.png`, with a row in `attachment` (conversation, path, caption, taken_at, attached_at, anchor_s, placed_by). Deleted with the meeting.
- **Shown** among the transcript lines by time (meeting page and Today), with an optional one-line caption added or edited later. In the live transcript file and the daily notes as Markdown that renders inline: `[11:18:52] 📎 Screenshot: caption ![caption](../audio/meetings/20/attachments/111852-slide.png)`.
- **Later (not built):** a global hotkey capturing the screen straight into Earshot; other file types (PDF, chat export).

### 6.8 Optional AI insights (owner decision, 2026-10-24)
Earshot's core is transcription and diarization; it must work fully with every insight off, which is the default. Each insight has its own provider, chosen in Settings → Insights:

| Insight | Runs when the user clicks | Providers |
|---|---|---|
| Summary during a meeting | Summarize on the live meeting: summary, topics, decisions, agenda so far (§6.5) | Off · Local · OpenAI · Anthropic |
| Summary after a meeting | Summarize on a finished meeting or a daily-notes conversation: summary, topics, action items (§5.2 step 10) | Off · Local · OpenAI · Anthropic |
| Ask | Each question or quick action: one answer (§6.1, A-9) | Off · Local · OpenAI · Anthropic |
| Search by meaning | "Prepare … for meaning search" embeds new lines; the "by meaning" tick adds one call per search (§5.6) | Off · Local · OpenAI (Anthropic has no embeddings) |

- **Click only (owner rule D-10, 2026-10-24):** no insight ever runs on its own: no timer, no loop, nothing after Stop or an import. A local call costs GPU time, a cloud call costs money, so every call is the user's click. Choosing a provider in Settings only shows the buttons. Before a click, the button shows what it would send (lines, about how many tokens, to which provider).
- **One click, one request** to a cloud provider: the whole transcript goes in one request (up to ~100k tokens), not in pieces. A local model, with a smaller context, condenses long transcripts in parts.
- **Providers:** local = an OpenAI-compatible server (LM Studio, Ollama); OpenAI and Anthropic through their APIs. Model names are settings per provider.
- **A click is always honoured, during a meeting too (owner, 2026-10-24, D-12; replaces D-2's refusal):** "the user knows why", e.g. a 32 GB Mac has room. The button warns when the local model would share the GPU with live transcription, and the log records `event=local_model_during_live reason=user_click`. Earshot itself never starts anything during a meeting.
- **One request at a time per provider.** None blocks a transcription thread.
- **A provider failure never touches the transcript:** the click shows the provider's error; meetings, imports and exports never depend on a model.
- **Privacy:** with a cloud provider on, transcript text (never audio) goes to that company. Settings says so next to each such insight, and the header shows "Insights: OpenAI" (or Anthropic) while any is on.
- **Keys (D-3):** `OPENAI_API_KEY` and `ANTHROPIC_API_KEY` from the environment or `.env`, read at start; never stored in the database, shown, logged or exported. Settings shows only whether a key is set. `OPENAI_BASE_URL` and `ANTHROPIC_BASE_URL` override the endpoints.
- Without the insights, the meeting page shows transcript, speakers and notes; Today and Search show no Ask; existing summaries stay visible.

## 7. Speaker & Voiceprint Management

- **Me enrollment:**
  - Primary: after the first processed day, the user marks a few segments "this is me". Their embeddings become Me voiceprints. This matches the recorder's microphone.
  - Also: record 30–60 s in the UI through the laptop mic (needed for live mode), or upload a clip.
  - Voiceprints keep their `source` (recorder, laptop mic, upload, call). Matching uses the best score across all of a person's voiceprints.
- **Person library:** name, aliases, embeddings (multiple), first seen, last seen, and conversation count.
- **Naming flow:** clicking an unknown speaker shows "Name this speaker" with a suggested match if one is near threshold.
- **Remembered voices (owner request, 2026-10-20):** the naming panel has "Remember this voice" (on by default), which saves the speaker's embedding as a voiceprint of the chosen person, linked to that speaker's cluster. Later meetings and recordings name them automatically; such names show "by voice" with the match score, and one click confirms ("Yes, it's X", which adds this voice too) or corrects. Correcting a name replaces the voiceprint saved from that speaker, so a wrong name never lingers in the library. In a live meeting the saved voiceprint follows the speaker's cluster as more speech arrives.
- **Who said this line (owner request, 2026-10-20):** two similar voices (siblings on one phone over Zoom scored 0.57–0.67 against each other, versus 0.81–0.88 for one speaker with themself) can end up as one speaker. Clicking a line's speaker offers "This line only" or "Every line of <speaker>"; a line of an already-named speaker starts on "This line only". A moved line is pinned (live regrouping leaves it alone), its person's lines in that conversation form one speaker, and with "Remember this voice" that speaker's voiceprint is built from its lines. The speaker it left gets its voice rebuilt from the lines it keeps, so a remembered voice no longer carries the other person. During a live meeting the newest audio may not be on disk yet (chunks every 30 s): the voice follows at Stop.
- **Merge:** merge two people (e.g., duplicates created from different mic conditions).
- **Delete:** deleting a person removes their voiceprints and unlinks their clusters, which render as "Unknown" everywhere (§5.5).
- **Unknown voiceprint retention:** embeddings of clusters never linked to a person are deleted after `unknown_voiceprint_retention_days` (default 90). The transcript stays; the cluster can no longer be auto-matched.
- **Thresholds:** configurable. Defaults are set from the evaluation set (§13.1).

## 8. Resource Management (16 GB budget)

- Whisper, pyannote, and Silero VAD live in the ML process. Load them lazily; in batch mode, unload them when idle.
- **Live mode priority:** transcription owns the GPU during a meeting. **Why (owner, 2026-10-24):** Earshot's live transcript file, with screenshots, is what an external agent (Claude Code) reads every few seconds to give the user real-time pointers; losing real-time transcription loses the point of Earshot. Batch work (after-Stop catch-up, Re-transcribe, imports) stops when a meeting goes live and resumes after Stop. A local LLM never runs while a meeting is live (§6.8); cloud providers may. At most one request per provider is in flight, and Q&A prompts are capped (§6.5).
- **Speech engine memory (r13):** MLX keeps freed GPU buffers for reuse; with no cap the cache grew to 16 GB in 40 hours and the Mac swapped live transcription to 1.6–100× real time. The cache is capped at 1 GB and cleared after batch work, and the speech engine's memory is shown in the meeting status bar and `/api/health`.
- A local LLM runs out-of-process (LM Studio or Ollama). The app only calls its HTTP endpoint.
- **Provider unavailable:** transcription, diarization, and keyword search keep working; the insight is skipped and logged, and Ask shows the reason.
- **Meeting start:** batch-only models (pyannote, if not needed for live) are unloaded to free memory for the live model.
- **Sleep:** while a meeting is recording or a batch job is running, Earshot holds a macOS power assertion (like `caffeinate -i`) so the Mac does not idle-sleep. Closing the lid still sleeps the Mac unless an external display is attached; the setup guide and the Start Meeting screen say so. After a sleep, an interrupted batch job resumes from its checkpoint; a meeting's capture records a gap and the UI marks it.
- **Battery and heat:** on battery or when thermally throttled, ASR slows. The resources indicator shows power source, and the lag indicator (§6.4) makes the effect visible.
- Show a resources indicator in the UI: transcription speed over the last minute, speech engine memory, and lag.
- **Batch concurrency:** one ML job runs at a time. LLM stages (enrichment, indexing) of one conversation may run while the next conversation is in the ML stages.
- **One meeting at a time:** starting a second meeting is refused while one is active.
- **Batch vs live:** batch work never runs during a live meeting. Starting a meeting pauses a running batch job at its next checkpoint (A-2) and cancels the in-flight ML call; the job resumes after the meeting's post-meeting pass.

## 9. Data

### 9.1 Directory layout
```
data/
  audio/raw/YYYY-MM-DD/        # original recorder files (retention configurable)
  audio/work/<recording_id>.flac # 16 kHz mono working copy; reference for offsets and playback
  audio/meetings/<id>/<channel>/  # live meeting captures as 30 s FLAC chunks per channel (mic, system); joined into one file per channel after Stop
  audio/clips/                 # enrollment samples
  notes/YYYY-MM-DD.md          # daily markdown exports
  backups/                     # daily pg_dump files
  live/                        # meeting-<id>.md and current.md: transcripts for outside agents (§6.4)
  logs/                        # service.log, ml.log, capture.log
  models/                      # optional local model cache
```
Postgres data lives in Homebrew's data directory (`/opt/homebrew/var/postgresql@17`); `data/backups/` holds its dumps.

**Configuration** has two tiers:
- **Boot config:** `service/src/main/resources/application.yaml` with `${EARSHOT_*:default}` overrides from a gitignored `.env` (ports, paths, DB URL, local LLM base URL), plus the optional `OPENAI_API_KEY` / `ANTHROPIC_API_KEY`. Changing it needs a restart.
- **Runtime settings:** thresholds, intervals, insight providers and model names, and retention, stored in the `setting` table and edited in the Settings screen. Defaults come from `application.yaml`.

### 9.2 Core entities (Postgres, Flyway migrations)
- `recording` (id, import_id, source_type[recorder|meeting], path, work_path, sha256 unique, started_at (UTC), time_zone, started_at_source, clock_offset_s, duration_s, language, status)
- `import` (id, created_at, time_zone, clock_offset_s)
- `conversation` (id, recording_id, start_offset_s, end_offset_s, started_at, local_date, title, summary, is_ambient, duplicate_of_meeting_id nullable, status)
- `segment` (id, conversation_id, speaker_cluster_id nullable, channel[mixed|mic|system], start_s, end_s, text, words_json, confidence)
- `speaker_cluster` (id, conversation_id, label, embedding, person_id nullable, match_score)
- `person` (id, name, aliases, is_me, created_at)
- `voiceprint` (id, person_id, embedding, source[recorder|laptop_mic|upload], created_at)
- `meeting` (id, recording_id, title, pre_context, audio_mode, started_at, ended_at, rolling_summary, topics_json)
- `qa` (id, scope_type[meeting|day|range], scope_ref, question, answer, citations_json, created_at). Citations are `(recording_id, start_s, end_s)` offsets, never segment ids.
- `action_item` (id, conversation_id, text, owner_cluster_id nullable, done)
- `job` (id, type, recording_id, conversation_id nullable, stage, status, attempts, error, updated_at)
- `attachment` (id, conversation_id, path, original_name, content_type, size_bytes, caption, taken_at, attached_at, anchor_s, placed_by[taken|attached|end]) (§6.9). `meeting.vocabulary` holds a meeting's names and terms (§3).
- `setting` (key, value_json, updated_at)
- `segment_vec` (segment_id, model, embedding `vector`). The column is untyped so the embedding model can change; each model gets a partial HNSW index on `embedding::vector(<dim>)`. Changing the embedding model in Settings starts a re-index job, and search uses the old model until it finishes.
- Full-text and trigram indexes on `segment.text`.

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

### 9.4 Retention, privacy & local security
- **Storage budget:** a recorder running ~10 h a day produces ~1 GB/day of raw MP3/WAV and ~0.5 GB/day of FLAC working copies, i.e. hundreds of GB a year if everything is kept. Settings shows disk use per category, and the app warns when free disk drops below `min_free_disk_gb` (default 20); below 5 GB, imports and meeting starts are refused.
- Configurable `raw_audio_retention_days` (default 30; `0` = delete raw audio after successful processing). The working copy is the playback source, so raw audio is redundant once processing succeeds.
- Configurable `ambient_audio_retention_days` (default 30): the working audio of ambient conversations is deleted after this, leaving only the timeline row.
- The working copy of non-ambient conversations is kept as long as the conversation.
- One-click delete of a conversation, a day, or a person, which cascades to audio, vectors, and exports.
- **Backups:** `pg_dump` to `data/backups/` at startup if the last backup is older than 24 h, and every 24 h while running (a laptop is often asleep at night). Kept for `backup_retention_days` (default 7). A delete is therefore complete in backups after that window; the delete dialog says so.
- No telemetry. No outbound requests except to a cloud provider the user chose for an optional insight (§6.8). All services bind to `127.0.0.1`.
- Transcript text sent to an insight provider leaves `data/`: a local server's own logs (the setup guide covers turning off prompt logging), or OpenAI's / Anthropic's retention under the user's account terms. Full delete (NF-6) covers only what is on this Mac.
- The live transcript files in `data/live/` are plain text readable by any local program the user runs; that is their purpose.
- **Local web attack surface:** any website the user visits can send requests to localhost. So:
  - Spring Boot rejects requests whose `Host` is not `localhost`/`127.0.0.1` on its port (blocks DNS rebinding);
  - state-changing requests and WebSocket upgrades must carry an allowed `Origin`;
  - the ML processes require a shared secret header (generated into `.env` on first run) and accept calls only from Spring Boot (plus the capture → ML frame stream, §6.4).
- Voiceprints of other people are biometric data. Earshot keeps them only as long as needed (§7) and the user is responsible for consent (§14).

## 10. Architecture

```
┌──────────── Browser (React UI, localhost) ────────────┐
│ Today / Search / Meeting / People / Settings          │
└───────────────▲───────────── WebSocket + REST ────────┘
                │
┌───────────────┴─ Backend (Java 25, Spring Boot 4.1) ──┐
│ API layer  │ Job queue (batch) │ Live session manager │
│ Speaker ID matching │ Live transcript file │ Search   │
│ Optional insights (summaries, Q&A) │ Exporter         │
│ Process supervisor  │ UI bundle                       │
└──────▲─────────────────▲─────────────────────▲────────┘
       │ JDBC            │ HTTP + WebSocket    │ HTTPS/HTTP, optional, per insight
       │                 │ (127.0.0.1, secret) │
 Postgres 17 +   ┌───────┴──────────────────────────┐   local server (LM Studio / Ollama),
 pgvector        │ Capture process (Python)         │   OpenAI or Anthropic
 (Homebrew)      │  sounddevice → per-channel       │
                 │  30 s FLAC chunks on disk        │
                 ├─ frame stream; chunks on restart ┤
                 │ ML process (Python, FastAPI)     │
                 │  ffmpeg → VAD → Whisper →        │
                 │  pyannote → align → embeddings   │
                 └──────────────────────────────────┘
```

The capture process is deliberately small and separate from the ML process. If Whisper or pyannote crashes, recording continues; Spring Boot's supervisor restarts the ML process, which resumes from the audio on disk.

**Process lifecycle:**
- Spring Boot sends a heartbeat to both Python processes. If Spring Boot dies, the ML process exits after `heartbeat_timeout_seconds` (default 30). The capture process keeps recording a live meeting for up to `capture_orphan_minutes` (default 30) so a backend crash doesn't lose the meeting, then stops.
- On start, Spring Boot finds Python processes left from an earlier run (by PID file), re-attaches to a capture process that is still recording, and kills anything else.

### 10.1 Tech choices
Follows the existing stack used across the owner's other projects.

- **Backend:** Java 25, Spring Boot 4.1 (webmvc with virtual threads, websocket, jdbc via `JdbcClient`, no JPA), Maven. One package per feature under `com.earshot.api` (`recording`, `conversation`, `speaker`, `meeting`, `ask`, `search`, `export`, `llm`, `ml`, `settings`, `health`); `llm.Insights` decides which optional feature may use which provider. SLF4J `key=value` log lines. PDF agenda text extraction with PDFBox.
- **Database:** native Postgres 17 + pgvector and `pg_trgm` (Homebrew, `127.0.0.1:5435`, `scripts/setup-postgres.sh`). Flyway migrations in `service/src/main/resources/db/migration/V<n>__snake_case.sql`. (Was Postgres 16 in Docker until 2026-10-24; Homebrew's pgvector is built for 17 and 18.)
- **Job queue:** in-process worker in Spring Boot with the persisted `job` table. No Redis.
- **Python processes (`ml/`):** Python 3.11+, FastAPI, uvicorn, pydantic 2. Exactly pinned `requirements.txt`; pytest; ruff. Two entry points: `capture` and `ml`. Spring Boot is their only HTTP caller; the one other link is the capture → ML frame stream. All bind to `127.0.0.1` and require the shared secret.
  - **ASR:** behind an `ASREngine` interface. Default `mlx-whisper` on Apple Silicon, with `faster-whisper` as a fallback.
  - **Diarization and speaker embeddings:** `pyannote.audio` 4.x, Community-1, behind a `Diarizer` interface.
  - **VAD:** Silero VAD.
  - Runs with `HF_HUB_OFFLINE=1` so cached models never trigger network calls.
- **Optional insights:** called from Spring Boot through a `ChatClient` per provider: an OpenAI-compatible client (local server or OpenAI, by base URL and key) and an Anthropic Messages API client.
- **Frontend:** React 19, TypeScript (strict), Vite, react-router-dom 7, CSS Modules (no Tailwind or UI kit), CSS-variable theme tokens for light/dark. ESLint + `tsc`. Vite builds into `service/src/main/resources/static` so one jar serves the API and the UI; the dev server proxies `/api` and `/ws` to 8080. A typed fetch client in `web/src/api.ts`.
- **Repo layout:** `service/` (Spring Boot), `web/` (React), `ml/` (Python processes), `eval/` (evaluation set and scripts, §13.1), `docs/` (PRD, ADRs, analysis, bug log), `scripts/`, `earshot` start script.
- **Packaging:** `./earshot` runs it in the foreground (Postgres via `brew services`, then the jar); `scripts/earshot.sh install` runs it as a launchd login agent with KeepAlive, so it starts at login and comes back if it stops (owner decision D-8, 2026-10-24, after the service went down unnoticed on 2026-10-21). Spring Boot starts and supervises the Python processes. No Makefile.

### 10.2 Key interfaces (for swappability)
Python:
```python
class ASREngine:       def transcribe(wav_path|np.ndarray, language="en") -> list[Word]
class Diarizer:        def diarize(wav_path) -> list[Turn]          # exclusive turns
class SpeakerEmbedder: def embed(wav, turns) -> np.ndarray
```
Java:
```java
interface ChatClient { String chat(String model, List<Message> messages, boolean jsonMode); String chatStream(String model, List<Message> messages, Consumer<String> onToken); float[][] embed(String model, List<String> texts); }
interface MlClient  { BatchResult process(Path wav, String language); LiveSession startLive(AudioMode mode, String device); }
```

### 10.3 Internal Python API (127.0.0.1, shared secret, called only by Spring Boot)
- ML: `POST /ml/batch` (working copy path + language → words, turns, cluster embeddings; cancellable) · `POST /ml/embed-speaker` (clip or segment range → embedding) · `GET /ml/health`
- Capture: `POST /capture/start` (device, channels, meeting id) · `POST /capture/stop` · `GET /capture/devices` · `GET /capture/health`
- Live: `WS /ml/live/{id}` (transcript segments per channel, lag status)

### 10.4 Public API sketch (Spring Boot)
- `POST /api/import` (multipart) · `PATCH /api/imports/{id}` (clock offset) · `POST /api/process/{recording_id}` · `GET /api/jobs`
- `GET /api/days/{date}` · `GET /api/conversations/{id}` · `GET /api/audio/{recording_id}?start=&end=` (served from the working copy)
- `PATCH /api/speaker-clusters/{id}` (assign person) · `PATCH /api/segments/{id}/speaker` (one line) · `CRUD /api/people` · `POST /api/people/{id}/merge` · `POST /api/enroll`
- `POST /api/meetings` (start) · `POST /api/meetings/{id}/stop` · `POST /api/meetings/{id}/device` · `POST /api/meetings/{id}/language` · `POST /api/meetings/{id}/vocabulary` · `WS /ws/meetings/{id}` (transcript, summary, status events)
- `POST /api/meetings/{id}/attachments` (multipart image, `lastModified`, `caption`) · `PATCH /api/attachments/{id}` (caption) · `DELETE /api/attachments/{id}` · `GET /api/attachments/{id}/file`
- `POST /api/ask` (scope + question, streams the answer; 409 with the reason when Ask is off or local during a live meeting)
- `GET /api/health` (includes per-insight providers, whether keys are set, and the speech engine's memory)
- `GET /api/search?q=&from=&to=&speaker=`
- `GET/PUT /api/settings`
- `DELETE /api/conversations/{id}` · `DELETE /api/days/{date}` · `DELETE /api/people/{id}`

## 11. Non-Functional Requirements

| ID | Requirement |
|---|---|
| NF-1 | Offline: with every insight off or local (the default), zero outbound network calls at runtime (verified with a test that blocks all non-loopback sockets). |
| NF-2 | Batch: process 1 hour of speech in ≤ 30 min on the baseline hardware (target). A full day with ~4 h of speech therefore takes ~2 h; this is acceptable for overnight or evening runs. |
| NF-3 | Live: transcript lag ≤ 5 s target, ≤ 10 s max, sustained for 60+ minutes on the baseline Mac with the call app running (r13 measurement: `docs/implementation/2026-10-19-v1-build.md`). |
| NF-4 | Stability: a 2-hour live meeting runs without memory growth beyond a fixed budget; the speech engine stays within ~3 GB (MLX cache capped). |
| NF-5 | Robustness: an ML process crash loses no audio and at most the in-flight live segment's text (recovered by the post-meeting pass). Meeting audio is flushed to disk continuously by the capture process. |
| NF-6 | Privacy: every service (Spring Boot, Python processes, Postgres) bound to 127.0.0.1; Host/Origin checks (§9.4); files under one data directory; full delete is possible (backups expire per §9.4). |
| NF-7 | Configurability: boot settings in `application.yaml` (env-overridable); all thresholds, intervals, model names, and retention editable in Settings. |

## 12. Milestones (build order for Claude Code)

Each milestone is independently runnable and testable.

**M-1: Feasibility spike (before any app code)**
- Record one real day with the pocket recorder and one 30-minute online call. Run Whisper turbo, Silero VAD, and Community-1 over them with plain scripts in `eval/`.
- Hand-label the first evaluation recordings (§13.1) while doing it.
- *Done when:* word quality, Me-cluster accuracy, hallucination rate, and processing time are measured and written up. If Me accuracy is far below 90% or the audio is unusable, revise the PRD (mic placement, denoise default, scope) before M0.

**M0: Skeleton**
- Repo layout (`service/`, `web/`, `ml/`, `eval/`), Spring Boot with a health endpoint, Postgres + pgvector container, Flyway baseline migration, React shell built into the jar, Python processes started by Spring Boot with health endpoints, `application.yaml` and `setting` table, Host/Origin checks, shared secret for the Python processes, daily backup, process supervision with heartbeats.
- *Done when:* `./earshot` serves an empty UI on localhost and the health endpoint reports the DB and both Python processes as up.

**M1: Batch transcription (no diarization)**
- Import with duplicate detection, start-time derivation, time zone and clock offset, ffmpeg conversion, VAD, day timeline and conversation segmentation, Whisper with hallucination filters, segments stored, transcript view with audio playback.
- Low-disk guard and raw-audio retention (§9.4), since audio starts accumulating here.
- Without diarization, nothing is ambient and every conversation is transcribed.
- *Done when:* a recorder file becomes timestamped conversations in the UI.

**M2: Diarization & Me detection**
- pyannote exclusive diarization, word alignment, Me enrollment (from recorder segments and laptop mic), Me vs Speaker N labels, ambient marking.
- *Done when:* Me accuracy meets §13 on the evaluation set.

**M3: Person library**
- Unknown cluster storage, naming flow, auto-matching in later sessions, merge and delete, unknown voiceprint retention.

**M4: Enrichment, search & daily export**
- LLM summaries, action items, topics with speaker tokens; hybrid search; daily markdown export with re-rendering; day-scope Q&A.

**M5: Live meeting (core)**
- Audio source selection (mic / mic+system / system), Aggregate Device capture, headphone check, separate capture process, streaming transcription over WebSocket, segment stitching, channel-based Me vs Others, per-channel meeting audio.
- *Done when:* a 30-minute online call shows a live transcript with ≤ 10 s lag.

**M6: Live context & Q&A**
- Rolling summary, topics, decisions; hybrid retrieval; Q&A with offset citations; quick actions; pre-meeting context; LLM single-flight queue and prompt cap.

**M7: Post-meeting pass & polish**
- Automatic per-channel reprocessing after Stop, batch pause/resume around meetings, merge into daily notes, linking recorder conversations that overlap a meeting (A-13), resources indicator, ambient and voiceprint retention settings, setup guide (BlackHole, Multi-Output and Aggregate Devices, headphones, LM Studio).

**Phase 2 (later):** rolling-window live diarization; scheduled nightly processing (launchd); recorder auto-detect on mount; echo cancellation so online calls work without headphones.
**Phase 3 (later):** phone companion over LAN (needs auth); Obsidian vault export.

## 13. Acceptance Criteria (v1)

- [ ] A full day's recorder audio is imported and processed without manual intervention beyond clicking Process.
- [ ] The user's own speech is correctly labelled "Me" in ≥ 90% of segments on the recorder part of the evaluation set.
- [ ] A named person is automatically recognized in a later recording.
- [ ] During a live online call (with headphones), the transcript shows Me vs Others with ≤ 10 s lag.
- [ ] With Ask on (§6.8): "Was X discussed?" answers correctly with timestamp citations on ≥ 9 of 10 scripted questions per evaluation meeting, including the "not discussed" cases.
- [ ] With Ask on: "Suggest a question" returns 3 questions, none of which is already answered in the transcript, on at least 4 of 5 evaluation meetings (judged by the user).
- [ ] A 60-minute live call keeps the transcript within 10 s of the room, and Stop leaves no gap.
- [ ] The app works with Wi-Fi disabled.
- [ ] Deleting a day removes its audio, transcript, vectors, and markdown export (and is gone from backups after the retention window).

### 13.1 Evaluation set
Stored in `eval/` (gitignored audio, committed labels and scripts):
- At least 3 recorder recordings of about 30 minutes each, from different settings (office, cafeteria, meeting room), with hand-labelled Me / not-Me segments.
- At least 2 recorded meetings (one online, one in-person), each with 10 scripted questions and expected answers (some "not discussed").
- A script that runs the pipeline on the set and reports Me accuracy, word quality spot checks, and Q&A results. Thresholds (§7) are tuned on this set.

### 13.2 Testing
- **Spring Boot:** JUnit 5 with Testcontainers (`pgvector/pgvector:pg16`) for repository and migration tests; a fake `MlClient` and `LlmClient` for service tests.
- **Python:** pytest with short committed audio fixtures (a few seconds each: speech, silence, two speakers, noise) for VAD, ASR wrapper, hallucination filters, stitching, and alignment.
- **Pipeline:** an end-to-end test on a one-minute fixture with a stub LLM, run before each milestone is called done.
- **Quality:** the evaluation set (§13.1) for accuracy targets; it is run by hand, not on every change.
- **Offline:** the NF-1 socket-blocking test runs as part of the end-to-end test.

## 14. Risks & Mitigations

| Risk | Mitigation |
|---|---|
| Pocket or clothing noise degrades ASR | M-1 spike measures it first; recommend a collar clip or lavalier; optional DeepFilterNet; document this in the setup guide |
| Whisper hallucinates text on noise or silence | VAD gating and confidence/compression filters (§5.2 step 8) |
| 16 GB memory and GPU contention in live mode | No local LLM during live meetings (§6.8); MLX cache capped; no Docker VM; no batch work during meetings; lag banner (§6.4) |
| Online call audio leaks into the mic | Headphones required for Mic + System; headphone check; echo cancellation in Phase 2 |
| Live room diarization is weak | Channel split for online calls; accurate attribution deferred to the post-meeting pass |
| An LLM hallucinates in Q&A | Hybrid retrieval, mandatory citations from transcript only, explicit "not discussed" rule; Ask is optional |
| Transcript text sent to a cloud provider | Every insight off by default; per-insight choice; shown in Settings and the header; audio never sent |
| Short utterances misattributed | Merge sub-second turns; accept as a known limitation |
| Recorder clock is wrong | Start-time derivation order and per-import offset (§5.4) |
| Data loss in the Postgres data directory | Daily `pg_dump` to `data/backups/` |
| Disk fills up with audio | Storage budget, retention defaults, and low-disk guard (§9.4) |
| Mac sleeps during a meeting or job | Power assertion; resumable jobs; gap marking in meetings (§8) |
| pyannote model access needs Hugging Face acceptance | One-time setup step to download and cache; runtime stays offline with `HF_HUB_OFFLINE=1` |
| A website attacks the localhost API | Host/Origin checks, shared secret for the Python processes (§9.4) |
| Recording others, legal/policy (e.g., India's DPDP Act) | Personal-use only; voiceprints of unnamed people expire; easy delete; user is responsible for consent and workplace policy |

## 15. Open Questions

1. Recorder model and filename format. (Determines `recorder_filename_pattern`, §5.4.)
2. ~~Which local LLMs will be the default live and batch models?~~ **Resolved (2026-10-24):** insights are optional, provider per feature (§6.8); defaults are off.
3. ~~Is FastAPI + React acceptable, or should the app layer be Spring Boot with Python ML workers?~~ **Resolved (2026-10-19):** Spring Boot + React, with Python ML processes, matching the existing stack (§10.1).
4. Should the daily markdown be written straight into an Obsidian vault folder?
5. Raw-audio retention defaults to 30 days (§9.4). Confirm.
6. Hindi / code-mixed speech: resolved 2026-10-20 with `en+hi`; written in Latin letters since 2026-10-25 (§3).
