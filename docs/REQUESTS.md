# Earshot feature requests

Requests waiting for the owner's decision on scope and priority. Newest first. Bugs live in `BUGS.md`.

## R-3: Vocabulary prompt for names and jargon (from BUGS.md B-13): **built 2026-10-24**

Settings → Transcription → vocabulary (global) plus "Names and terms" per meeting (at Start, or mid-meeting from the header). PRD §3. Trial results are in the implementation log.

A list of people, companies and terms (per meeting, or global in Settings) passed to Whisper as its initial prompt, so "Abhinav" stops coming out as "Aveena" and "Rapido" as "Repito". Needs a measured trial on the ISB class audio: a long prompt can make Whisper repeat it over silence.

## R-2: Remote speaker names from Zoom (from BUGS.md B-14): **on hold** (owner unsure, 2026-10-24)

Read Zoom's active-speaker name from the screen during a call, and use it to label remote lines. The review says this worked well for the 17 Oct recording. The alternative is more remembered voices.

## R-1: Timestamped screenshot attachments (owner request via the ISB-CTO session, 2026-10-24): **built 2026-10-24**

Paste / drop / "Add screenshot" on the meeting page; placed at the time taken (file name or modified time) when inside the meeting; caption; in the live file and daily notes as inline Markdown images. PRD §6.9. The hotkey and other file types are still to come.

"Live transcription is good; screenshots attached as notes with a timestamp would be golden." No AI needed. During the class the owner took ~7 slide screenshots and another session had to match each one to transcript times by hand.

1. During a live meeting, paste (Cmd+V) or drag-drop an image into Earshot.
2. It is saved next to the meeting, e.g. `data/audio/meetings/<id>/attachments/<HHMMSS>-<name>.png`.
3. A timestamped line goes into the live transcript (the live file in `data/live/` too) and the final notes, rendering inline in Markdown:
   `[11:18:52] 📎 Screenshot: ![](attachments/111852-slide.png)`
4. Both the attach time and the file's creation time are kept. Screenshots are often pasted a minute or two after they are taken, so the line is placed at the creation time when that falls within the meeting.
5. An optional one-line caption, typed when attaching or edited later.

**Later:**
- A global hotkey that captures the screen straight into Earshot (macOS `screencapture`).
- Other file types (PDF, chat export) as general timestamped attachments.

**Start:** screenshots only.
