# Earshot bug log

Newest first. Each entry: what the user saw, the evidence, the cause (when known), and the state of the fix.

For the post-call huddle on 2026-10-24, start with **[Huddle summary](#huddle-summary-first-real-live-call-2026-10-24)** at the end of this file.

| # | Bug | Severity | Status |
|---|-----|----------|--------|
| B-14 | Remote speakers merged or switching numbers (a participant's question labelled as the instructor) | Medium | Open: needs a decision (Zoom name OCR or more voiceprints) |
| B-13 | Names and jargon misheard ("Aveena" for Abhinav, "Repito" for Rapido) | Medium | Open: needs a decision (vocabulary prompt) |
| B-12 | Text in other writing systems ("やあみし") | Low | **Fixed** 2026-10-24 |
| B-11 | Live transcription skipped the hallucination filters ("Thank you." / "Bye." in silence) | Medium | **Fixed** 2026-10-24 (partly: real "Okay."s score the same as invented ones) |
| B-10 | Gap-fill script: duplicated passages and wrong confidence values in meeting 20 | Low | **Fixed** 2026-10-24 |
| B-9 | Live audio stream reconnects every ~40 s, losing audio; live times drift behind the saved file | **High** | **Fixed** 2026-10-24 |
| B-8 | Some clean-up steps during the call had side effects on another project | Process | Noted |
| B-7 | Earshot needs more memory than a 16 GB Mac can spare | High | **Fixed** 2026-10-24: no local LLM live, native Postgres (no Docker VM) |
| B-6 | Live transcription stalls even after the leak was cleared | **Critical** | **Fixed** 2026-10-24: local LLM out of the live path; see the 60-min measurement |
| B-5 | Lag figure overstates the delay | Low | **Fixed** 2026-10-24 |
| B-4 | No warning when live transcription falls behind for good | Medium | **Fixed** 2026-10-24 |
| B-3 | Service stopped on its own and stayed down | Medium | **Fixed** 2026-10-24: launchd KeepAlive (cause of the stop still unknown) |
| B-2 | Stop throws away the untranscribed backlog | High | **Fixed** 2026-10-24 |
| B-1 | ML process memory grows without bound; live transcription crawls | High | **Fixed** 2026-10-24: cache capped, memory shown |

---

## B-14: Remote speakers merged or switching numbers

- **Seen:** quality review of meeting 20 (ISB class) from the user's ISB-CTO session, 2026-10-24.
  - Speaker numbers sometimes switch.
  - Some participants' speech is labelled as the instructor: Amit's question at 12:28:56, the investment-banking question around 12:19:55–12:20:14.
  - Some very long merged lines (11:28:52, 11:33:23).
- **Cause:** all remote voices arrive on one system channel. The instructor's voiceprint is strong, and short questions from others land near it.
- **Options (owner to decide):**
  - Read Zoom's active-speaker name from the screen. The review says this worked well for the 17 Oct recording.
  - Remember more voices: classmates named once are then recognised.
  - Cap line length at speaker changes inside long lines.

## B-13: Names and jargon misheard

- **Seen:** same review.
  - Names: Abhinav → "Aveena" / "Abhinah" / "Aminav"; Hardik → "Hathik"; Bharat → "Parath"; Sajal → "Sejal".
  - Companies: Zomato → "Zometo"; Rapido → "Repito"; Capgemini → "Capgevinan".
  - Other phrases: "cross-percussion" for cross-functional; "Millennial Gara" for the programme name.
- **Option (owner to decide):** a vocabulary list per meeting, or a global one in Settings, passed to Whisper as its initial prompt: names of people, companies and terms.
  - It helps names noticeably.
  - A long or off-topic prompt can make Whisper repeat it on silence, so it needs the same filters and a measured trial on the class audio.

## B-12: Text in other writing systems

- **Seen:** same review. "やあみし" (Japanese) at 12:19:07, in a meeting set to English + Hindi.
- **Fixed (2026-10-24):** with `en`, `hi` or `en+hi`, a line containing letters outside Latin (with accents) and Devanagari is dropped (`asr.foreign`). With `auto`, anything is kept.
- Devanagari for Hindi words ("इनोवेशन") is the intended en+hi behaviour (PRD §3). Writing Hindi in Latin script instead would be a new option, not a bug.

## B-11: Live transcription skipped the hallucination filters

- **Seen:** same review. "Thank you." / "Bye." / "To." / "Hello?" in silence, e.g. 11:02:39, 11:04:53, 12:33:02.
- **Cause:** the batch path applied Whisper's no-speech, log-probability and compression filters (PRD §5.2 step 8); the live path did not.
- **Fixed (2026-10-24):** the live worker applies the same `asr.keep` filters and logs what it drops (`event=live_dropped`).
- **Not fully solved:** in meeting 20 the instructor's real "Okay."s scored the same confidence (0.4–0.6) as the invented "Thank you."s, so a stricter filter would drop real speech too. Worth revisiting with the vocabulary prompt trial (B-13).

## B-10: Gap-fill script left duplicates and wrong confidence values in meeting 20

- **Seen:** same review. The instructor's closing (12:50:41–12:51:22) appeared twice, including the user's "Sir, this time there was no feedback". Part 1 also had a short overlap near 11:27.
- **Cause:** the one-off script that filled meeting 20's gaps started at the last live line's time. Because of B-9, live time was 7 s (part 1) and 27 s (part 2) behind the file at that point, so it re-transcribed audio that already had text. It also stored raw log-probabilities as confidence.
- **Fixed (2026-10-24):** the 18 duplicated lines were deleted and the confidence of the 194 filled lines corrected. The built-in catch-up after Stop also skips lines that repeat recent live text.

## B-9: Live audio stream reconnects every ~40 s, losing audio; live times drift

- **Seen:** found while explaining B-10. `capture.log` had 681 `ml_stream_retry … ConnectionClosedError`, about every 41 s even with nothing else running.
- **Cause:**
  - WebSocket keep-alive pings between capture and the ML process timed out (20 s interval + 20 s timeout), so the connection closed every ~40 s.
  - The frame in flight was lost at each reconnect. The ML process timed lines by the samples it had received, so live times fell further and further behind the saved file: 7 s after 28 min and 27 s after 62 min in meeting 20.
- **Effects:**
  - Clock times in live transcripts are early by up to half a minute late in a long meeting.
  - Lines are missing at each reconnect.
  - A catch-up that trusts live times re-transcribes audio (B-10).
- **Fixed (2026-10-24):**
  - Each frame now carries its sample index (`framing=seq`). The ML process turns lost audio into silence and skips repeated audio, so live times are the file's times.
  - The frame in flight is resent after a reconnect.
  - Keep-alive pings are off on this local socket, on both ends.
  - Test: `test_framed_stream_keeps_file_time`.
  - The capture process must be restarted to get this; the service re-attaches to an old one.

## B-8: Some clean-up steps during the call had side effects on another project

Logged so the huddle has the full picture. These were operator actions during the call, not Earshot bugs.

- To free memory, these mint processes were stopped on the owner's "kill mint":
  - two `listen` (Whisper) processes;
  - `mint/speech/server.py`;
  - mint's Java service (pid 12406).
- `launchctl bootout` was also run on `com.mint.speech-host`. The owner then said mint is meant to restart under launchd and should be left alone, so it was put back with `launchctl bootstrap` the same minute and its `listen` came back.
- Mint's Java service isn't run by launchd, so it stayed down. The owner said not to worry about it.
- The gain was small: about 3.6 GB freed, but mint's `listen` respawned at 1.6 GB.
- **Lesson:** another project's memory is not Earshot's to reclaim. Earshot has to fit in what it has (B-7).

## B-7: Earshot needs more memory than a 16 GB Mac can spare

- **Fixed (2026-10-24):** the local LLM never runs during a live meeting (owner decision D-2; it only runs when the user picks it for an insight, and then after Stop), and Postgres moved from Docker (8 GB VM) to native Homebrew Postgres 17 (D-5).

- **What runs during a live meeting (2026-10-24, 16 GB MacBook Pro):**

  | Process | Resident memory | Notes |
  |---|---|---|
  | Docker VM for Postgres | 8.2 GB reserved | the database itself uses ~225 MB |
  | Qwen2.5 7B (LM Studio `llama-server`) | 4.5 GB | running summary once a minute |
  | Whisper large-v3-turbo (Earshot ML) | 3.4 GB | |

  These add up to about 16 GB before Zoom, the window server or any other app.
- **GPU:** total allocated GPU system memory was 13.7 GB (`ioreg`, "Alloc system memory").
- **Options to decide in the huddle:**
  - Cap Docker Desktop's VM at 2 GB, or run Postgres natively (Homebrew) with no VM.
  - Make the running summary optional during live meetings, or let it unload the LLM between calls.
  - Use a smaller live model (Whisper small or distil) and keep large-v3-turbo for the after-meeting pass.
  - Add a pre-flight check that refuses to say "ready" when there isn't room for the live model.

## B-6: Live transcription stalls even after the leak was cleared

- **Fixed (2026-10-24):** transcription and diarization are the core; AI insights are optional and off by default, and a local model never runs while a meeting is live. Live semantic indexing with a local model is deferred to Stop too. Verified by a 66-minute replay of the class at real speed with Zoom open: lag median 3.2 s, p90 5.9 s, max 19.4 s; speed 0.50× real time (implementation log, 2026-10-24).

- **Seen:** 2026-10-24, meeting 20 part 2, after the ML process restart that fixed B-1.
  - Transcription ran at 2× real time at the start, then 5–109× (per-sentence spikes).
  - At 12:16, 27 min into part 2, only the first 6 min 50 s had been transcribed: about 20 minutes behind.
  - The live transcript, the Ask box and the live dump were all useless for the rest of the class.
- **Ruled out:**
  - **The B-1 leak:** the new ML process stayed at about 3.4 GB.
  - **The database:** it spiked once to 139% CPU, but there were no long queries and it was back to 1–2% seconds later.
  - **Chrome, VS Code, OneNote and mint's `listen`:** closing them moved free memory from 13% to only 16%, with no change in speed.
- **Strong lead:** Earshot's own local LLM shares the GPU and memory with Whisper.
  - The running summary calls Qwen 7B every minute. On 2026-10-24 there were 8 calls, median 22.8 s and up to 52 s each, and the GPU serves one job at a time.
  - Summaries stop once lag passes `live_lag_skip_summary_seconds` (the last one for meeting 20 was at 11:53), but the 4.5 GB model stays loaded.
  - Swap-ins stayed at about 25,000 a second, so something is evicting memory continuously.
- **Not yet measured (do first after the call):** time one 10 s utterance through Whisper:
  1. alone;
  2. with Qwen loaded but idle;
  3. with Qwen generating;
  4. with Zoom running.

  That tells memory pressure apart from GPU contention.
- **Also check:** whether `mx.set_cache_limit(1 GB)` makes MLX free and re-allocate buffers for every sentence, which could slow it under memory pressure.

## B-1: ML process memory grows without bound; live transcription crawls

- **Fixed (2026-10-24):** MLX cache capped at 1 GB, cleared after batch transcription; the speech engine's memory (resident + GPU active + cache) is in `/api/health` and the meeting status bar. Measured: 1.8–3.0 GB, with no growth, through a 66-minute replay.

- **Seen:** 2026-10-24, meeting 20 (ISB Class, online call, Jabra). Transcription speed shown as 32× real time at the start, then about 1.6× for the rest of the class. Lag reached about 18 minutes, so the live transcript (and the live dump an agent reads) was useless for asking questions during the class.
- **Evidence:**
  - The ML process (pid 54090) had been running for 40 hours and had a 19 GB footprint on a 16 GB Mac. `footprint` showed 16 GB of it as `IOAccelerator (graphics)` in 4,943 regions.
  - Swap: 34.5 of 35.8 GB used, with about 10,000 swap-ins per second during the class.
  - After the restart, the new ML process used 130 MB and swap fell to 23 GB.
- **Cause:** MLX keeps freed GPU buffers in a cache for reuse. Each utterance has a different length, so it needs buffers of new sizes, and with no cache limit the pile kept growing. Whisper large-v3-turbo itself needs about 1.6 GB.
- **Fix (applied 2026-10-24, uncommitted):** `mx.set_cache_limit(1 << 30)` in `ml/earshot_ml/asr.py`. The ML process was restarted with it after meeting 20 was stopped. Live speed with the cap in place has not been measured yet.
- **Still to do:**
  - Confirm the ML footprint stays flat (2–3 GB) over a day of use.
  - Also call `mx.clear_cache()` after batch jobs.
  - Report the ML process's memory in `/api/health` and the status bar, so growth is visible before it hurts.

## B-2: Stop throws away the untranscribed backlog

- **Fixed (2026-10-24):** Stop drops the ML queue at once, and the after-Stop job transcribes each channel after its last line from the saved audio, assigning lines to known voices (similarity ≥ 0.5) or new speakers; the meeting page shows "Catching up". Replay test: live had reached 124 s of 177.5 s at Stop; the catch-up added 16 lines up to 177.5 s, all to the right speaker. Meeting 20's gaps were filled by hand the same day (164 + 48 lines).

- **Seen:** 2026-10-24, meeting 20. The meeting was stopped while about 18 minutes of audio was still waiting to be transcribed. The meeting finished as "done", but its transcript stops at 28:10 (`max(end_s) = 1689.6`) of 45:50 (`duration_s = 2749.7`).
- **Cause:**
  - `MeetingService.stop()` tells the ML process to stop the live session and immediately closes the events WebSocket.
  - The ML worker does still drain its queue (`live.stop` puts `None` behind the queued work), but the lines it produces have nowhere to go.
  - `PostMeetingJob.finishFromLive` then runs straight away and marks the conversation `exported`/`done`. Nothing checks that the transcript covers the audio.
- **Impact:** text is lost for any meeting stopped while lagging. The audio is safe in `data/audio/meetings/20/`.
- **Fix (open):**
  - On Stop, if the lag is more than a few seconds, either:
    - keep the events socket open until the ML process reports it is idle, then finish; or
    - in `finishFromLive`, transcribe from the saved audio everything after the last live line (the batch path already exists).
  - The Live page should say "catching up: N min left" instead of just "finishing".
- **Repair for meeting 20:** transcribe 1689.6 s to the end from the saved channel files and add the result to conversation 56.

## B-3: Service stopped on its own and stayed down

- **Fixed (2026-10-24):** `scripts/earshot.sh install` runs the service as a launchd agent with KeepAlive; killing it brought it back in ~6 s. The cause of the 21 Oct stop is still unknown.

- **Seen:** 2026-10-21 21:40:00. `service.log` shows a clean graceful shutdown, which is what a SIGTERM produces. The ML process went down with the service; capture shut itself off at 22:09 when heartbeats stopped. The Mac had not rebooted (up since 2026-10-16), and an out-of-memory kill would not be graceful.
- **Cause:** unknown. Most likely the terminal or Claude session that launched `scripts/restart-service.sh` ended and took its process group with it.
- **Fix (open):** run the service under a launchd agent (`~/Library/LaunchAgents`) with `KeepAlive`, so it starts at login and comes back after any stop. Log the signal source if it happens again.

## B-4: No warning when live transcription falls behind for good

- **Fixed (2026-10-24):** past `live_lag_warn_seconds` (60) the meeting shows a banner: how far behind, whether it is catching up or falling behind (speed over the last minute), what helps, and that Stop loses nothing. The status bar shows speed over the last minute and the speech engine's memory.

- **Seen:** 2026-10-24, meeting 20. The orange "Lag" badge grew from 88 s to more than 1,000 s with no other sign that something was wrong. The user only found out by asking.
- **Fix (open):** when the transcription speed stays above 1.0× real time for more than a minute, show a clear banner ("Transcription can't keep up: N min behind") with the likely cause. Memory pressure and swap can be read from the health endpoint. The banner should offer the safe action: Stop, restart transcription, Resume.

## B-5: Lag figure overstates the delay

- **Fixed (2026-10-24):** `live.lag()`: the age of the oldest audio still waiting, or the time since the last line when nothing waits. Test `test_live_lag_is_age_of_oldest_waiting_audio`.

- **Seen:** 2026-10-24, the live dump header read "lags the room by about 1060 s; elapsed 1013 s". The lag can't be longer than the meeting.
- **Cause:** `ml/earshot_ml/live.py` computes `lag_s` as this utterance's wait in the queue plus the whole remaining backlog. The audio that utterance waited behind is counted twice.
- **Fix (open):** lag = time now minus the end time of the newest transcribed utterance (both measured from meeting start). That equals "how old is the newest line".

---

## Huddle summary: first real live call (2026-10-24)

**Verdict:** live transcription failed. Everything that depends on it (the live transcript, Ask, the live dump for an agent) was 13–20 minutes behind for most of the class. Audio capture worked throughout: both channels of both parts are on disk.

**What worked:**
- Device setup: Aggregate Jabra (mic on ch 0, BlackHole on ch 1–2) and Call Output Jabra in Zoom.
- Speaker naming: the instructor was recognised as "Mukesh Jain".
- English + Hindi language setting.
- Stop and Resume as parts.
- The live dump file (`scripts/live-dump.py` → `data/live/current.md`).

**Timeline (IST):**

| Time | Event |
|---|---|
| 10:58 | Meeting 20 started. ML process 40 h old with a 19 GB footprint (B-1). Lag was 88 s within 2 minutes. |
| 11:12 | Live dump started for agent use. Transcript ~13 min behind. |
| 11:44 | Stopped to restart. The backlog was dropped: part 1 transcript ends at 28:10 of 45:50 (B-2). |
| ~11:48 | Service and ML restarted with the MLX cache cap. Part 2 resumed. |
| 11:49–11:53 | Four running summaries (22–52 s each on the GPU). Lag grew from 56 s to 144 s, then to 468 s. |
| ~12:00 | mint processes stopped; Chrome, VS Code and OneNote closed. No real improvement (B-6, B-8). |
| 12:16 | Part 2 is 27 min in, with 6 min 50 s transcribed. |

**Decided (owner, 2026-10-24, during the call):** transcription and diarization are Earshot's core, and nothing may slow them down. AI insights (summaries, action items, Ask) stay as an *optional* layer, off by default, with a provider chosen per feature (local LLM, OpenAI or Anthropic). A local model never competes with live transcription. Outside agents can read the live transcript file. Full analysis and plan: `docs/analysis/2026-10-24-transcription-first.md`.

**Decisions needed:**
1. **Memory budget (B-7):**
   - What is allowed to run during a live meeting?
   - Docker VM size, or native Postgres?
   - Is the LLM summary on during live meetings?
   - Which Whisper model for live?
2. **Stop behaviour (B-2):** wait for the backlog to finish, or transcribe the rest from disk after Stop?
3. **Running as a service (B-3):** launchd with KeepAlive?
4. **Fallback:** when live can't keep up, should Earshot warn and offer a lighter mode (B-4)?

**After the call:**
- Run the B-6 measurements.
- Transcribe the gaps from the saved audio:
  - part 1: 28:10–45:50;
  - part 2: 6:50 to the end.
- Fix B-5.

**Uncommitted from today:**
- the MLX cache cap in `ml/earshot_ml/asr.py`;
- `scripts/live-dump.py`;
- this file.
