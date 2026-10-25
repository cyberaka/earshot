"""End-to-end test with a real recorded call: the ISB class (Zoom recording plus Zoom's transcript).

Drives the running service the way the UI does, then checks the result against Zoom's transcript:

  1. Upload the recording (Replay audio files: ffmpeg turns the MP4 into FLAC on the service).
  2. Start a meeting that replays it in real time, as a live call would arrive.
  3. While it runs: lag (NF-3: <= 5 s target, never more than 10 s), speech engine memory,
     lines arriving, and the live transcript file the agent reads (meeting-<id>.md).
  4. Stop, wait for the after-Stop pass, score who-said-what against Zoom.
  5. Click Re-transcribe, wait, score again; the instructor must still be named (B-17).
  6. Delete the test meeting and the uploaded copy (--keep leaves them).

Speaker accuracy: Earshot's lines and Zoom's cues are matched by time overlap, each Zoom speaker
mapped to at most one Earshot speaker (Hungarian assignment). Word agreement with Zoom is shown
for information only: Zoom's words are machine transcription too, not ground truth.

    ml/.venv/bin/python eval/e2e_class.py RECORDING.mp4 ZOOM-TRANSCRIPT.txt            # whole class, ~2.5 h
    ml/.venv/bin/python eval/e2e_class.py RECORDING.mp4 ZOOM-TRANSCRIPT.txt --minutes 10

Pass bars below are regressions guards, set just under what this recording measured on 2026-10-24
(live 98.2%, Re-transcribe 98.5%). Exit code 0 when every check passes.
"""
import argparse
import difflib
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

import numpy as np
from scipy.optimize import linear_sum_assignment

BASE = "http://127.0.0.1:8090"
INSTRUCTOR = "Mukesh Jain"
VOCABULARY = "Abhinav, Mukesh Jain, Bharat, Sajal, Hardik, Amit, Zomato, Capgemini, Rapido"


def call(method, path, body=None, timeout=60):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(BASE + path, data=data, method=method, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        raise SystemExit(f"{method} {path}: HTTP {e.code} {e.read().decode(errors='replace')[:300]}") from None


def log(msg):
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def ts(t):
    h, m, s = t.split(":")
    return int(h) * 3600 + int(m) * 60 + float(s)


def zoom_cues(vtt, limit_s):
    cues = []
    for block in Path(vtt).read_text(encoding="utf-8").split("\n\n"):
        m = re.search(r"(\d\d:\d\d:\d\d\.\d+) --> (\d\d:\d\d:\d\d\.\d+)\n([^:\n]{1,60}): (.*)", block, re.S)
        if m and ts(m.group(2)) <= limit_s:
            cues.append((ts(m.group(1)), ts(m.group(2)), m.group(3).strip(), m.group(4).strip()))
    return cues


def speaker_score(cues, segments):
    """Accuracy (share of Zoom's speech time on the mapped Earshot speaker), students labelled as the
    instructor, the instructor's speech split off, and the name on the instructor's main speaker."""
    refs = sorted({c[2] for c in cues})
    hyps = sorted({s["speaker"] for s in segments})
    M = np.zeros((len(refs), len(hyps)))
    for a, b, who, _ in cues:
        for s in segments:
            o = min(b, s["endS"]) - max(a, s["startS"])
            if o > 0:
                M[refs.index(who), hyps.index(s["speaker"])] += o
    if M.sum() == 0:
        return {"accuracy": 0.0, "students_as_instructor": 1.0, "instructor_split": 1.0, "speakers": len(hyps), "instructor_named": None}
    r, c = linear_sum_assignment(-M)
    boss = refs.index(INSTRUCTOR)
    bh = int(np.argmax(M[boss]))
    others = M.sum() - M[boss].sum()
    return {
        "accuracy": M[r, c].sum() / M.sum(),
        "students_as_instructor": (M[:, bh].sum() - M[boss, bh]) / others if others else 0.0,
        "instructor_split": 1 - M[boss, bh] / M[boss].sum(),
        "speakers": len(hyps),
        "instructor_named": hyps[bh],
    }


def words(text):
    return re.findall(r"[a-z0-9']+", text.lower())


def word_agreement(cues, segments):
    a = [w for c in cues for w in words(c[3])]
    b = [w for s in segments for w in words(s["text"])]
    return difflib.SequenceMatcher(None, a, b, autojunk=False).ratio() if a and b else 0.0


def segments_of(meeting):
    out = []
    for p in call("GET", f"/api/meetings/{meeting}")["parts"]:
        out += call("GET", f"/api/conversations/{p['conversation_id']}")["segments"]
    return out


def wait_done(meeting, timeout_s, what):
    t0 = time.monotonic()
    last = None
    while time.monotonic() - t0 < timeout_s:
        v = call("GET", f"/api/meetings/{meeting}")
        if v["status"] in ("done", "failed"):
            return v["status"], time.monotonic() - t0
        if v.get("finishing") != last:
            last = v.get("finishing")
            log(f"{what}: {last or v['status']}")
        time.sleep(5)
    return "timeout", time.monotonic() - t0


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("recording")
    ap.add_argument("vtt")
    ap.add_argument("--minutes", type=float, default=0, help="replay only the first N minutes (0 = all)")
    ap.add_argument("--keep", action="store_true", help="keep the test meeting and the uploaded copy")
    ap.add_argument("--min-live-accuracy", type=float, default=0.95)
    ap.add_argument("--min-redo-accuracy", type=float, default=0.96)
    ap.add_argument("--max-students-as-instructor", type=float, default=0.03)
    ap.add_argument("--max-lag", type=float, default=10.0)
    args = ap.parse_args()

    pre = call("GET", "/api/meetings/preflight")
    if pre["activeMeetingId"] != "":
        raise SystemExit(f"Meeting {pre['activeMeetingId']} is live; this test needs Earshot to itself.")

    duration = float(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", args.recording],
                                    capture_output=True, text=True, check=True).stdout)
    length = min(duration, args.minutes * 60) if args.minutes else duration
    cues = zoom_cues(args.vtt, length)
    log(f"recording {duration / 60:.1f} min, replaying {length / 60:.1f} min; Zoom cues {len(cues)}, speakers {len({c[2] for c in cues})}")

    # 1. Upload, as a drop on "Replay audio files" does. A clip is cut first when --minutes is given.
    with tempfile.TemporaryDirectory() as tmp:
        src = args.recording
        if args.minutes:
            src = os.path.join(tmp, "earshot-e2e-class" + Path(args.recording).suffix)
            subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", args.recording, "-t", str(length), "-c", "copy", src], check=True)
        else:
            os.symlink(os.path.abspath(src), src := os.path.join(tmp, "earshot-e2e-class" + Path(args.recording).suffix))
        t0 = time.monotonic()
        up = subprocess.run(["curl", "-sf", "-F", f"file=@{src}", BASE + "/api/meetings/replay-file"], capture_output=True, text=True)
        if up.returncode != 0:
            raise SystemExit(f"upload failed: {up.stdout or up.stderr}")
        replay = json.loads(up.stdout)["path"]
    log(f"uploaded and converted in {time.monotonic() - t0:.0f} s -> {replay}")

    checks = []

    def check(name, ok, detail):
        checks.append((name, ok, detail))
        log(f"{'PASS' if ok else 'FAIL'}  {name}: {detail}")

    # 2. Start, as the Start page does for a replay (call audio only).
    meeting = call("POST", "/api/meetings", {"title": "E2E test – ISB class replay", "audioMode": "system", "source": "file",
                                             "files": {"system": replay}, "systemChannel": 0, "language": "en+hi",
                                             "vocabulary": VOCABULARY, "preContext": "", "speed": 1.0})["meetingId"]
    log(f"meeting {meeting} started; live transcript file {call('GET', f'/api/meetings/{meeting}')['transcriptFile']}")

    # 3. Watch it in real time. The replay ends after `length` s; a little more lets the last line land.
    lags, mem, started = [], [], time.monotonic()
    next_report = 60
    try:
        while (el := time.monotonic() - started) < length + 20:
            st = call("GET", f"/api/meetings/{meeting}").get("status_live") or {}
            if st.get("lagS") is not None:
                lags.append(float(st["lagS"]))
            if st.get("mlMemoryMb"):
                mem.append(float(st["mlMemoryMb"]))
            if el >= next_report:
                n = len(segments_of(meeting))
                log(f"{el / 60:5.1f} min  lag {lags[-1] if lags else float('nan'):.1f} s  max {max(lags, default=0):.1f} s  "
                    f"speed {st.get('asrRtfAvg') or 0:.2f}x  ml {mem[-1] if mem else 0:.0f} MB  lines {n}  {st.get('warning') or ''}")
                next_report += 300 if length > 1200 else 60
            time.sleep(5)
    except KeyboardInterrupt:
        log("interrupted: stopping the meeting")
    live_file = Path(call("GET", f"/api/meetings/{meeting}")["transcriptFile"])
    file_lines_live = sum(1 for _ in live_file.open(encoding="utf-8")) if live_file.exists() else 0
    live_segments_at_stop = len(segments_of(meeting))

    # 4. Stop, then the after-Stop pass.
    call("POST", f"/api/meetings/{meeting}/stop")
    status, took = wait_done(meeting, 3600, "after Stop")
    check("after-Stop pass finishes", status == "done", f"{status} in {took:.0f} s")
    if lags:
        p95 = float(np.percentile(lags, 95))
        check("NF-3 live lag never above max", max(lags) <= args.max_lag,
              f"max {max(lags):.1f} s, p95 {p95:.1f} s, median {np.median(lags):.1f} s (target <= 5 s) over {len(lags)} samples")
    else:
        check("NF-3 live lag never above max", False, "no lag readings")
    if mem:
        log(f"info  speech engine memory: max {max(mem):.0f} MB, last {mem[-1]:.0f} MB")
    check("live transcript file written", live_file.exists() and file_lines_live >= 0.9 * live_segments_at_stop > 0,
          f"{live_file.name}: {file_lines_live} lines while live, {live_segments_at_stop} lines in Earshot")

    live = segments_of(meeting)
    s = speaker_score(cues, live)
    check("live speaker accuracy", s["accuracy"] >= args.min_live_accuracy,
          f"{s['accuracy']:.1%} (bar {args.min_live_accuracy:.0%}), {s['speakers']} speakers (Zoom {len({c[2] for c in cues})}), {len(live)} lines")
    check("live: students labelled as the instructor", s["students_as_instructor"] <= args.max_students_as_instructor,
          f"{s['students_as_instructor']:.1%} (bar {args.max_students_as_instructor:.0%}); instructor split off {s['instructor_split']:.1%}")
    log(f"info  live word agreement with Zoom: {word_agreement(cues, live):.1%}; instructor's speaker shown as “{s['instructor_named']}”")

    # 5. Re-transcribe, as the button does.
    call("POST", f"/api/meetings/{meeting}/retranscribe")
    status, took = wait_done(meeting, 3 * 3600, "Re-transcribe")
    check("Re-transcribe finishes", status == "done", f"{status} in {took:.0f} s ({length / max(took, 1):.1f}x real time)")
    redo = segments_of(meeting)
    stale = sum(1 for x in redo if x.get("live"))
    check("Re-transcribe replaced the live lines", stale == 0 and len(redo) > 0, f"{len(redo)} lines, {stale} left from live")
    r = speaker_score(cues, redo)
    check("Re-transcribe speaker accuracy", r["accuracy"] >= args.min_redo_accuracy,
          f"{r['accuracy']:.1%} (bar {args.min_redo_accuracy:.0%}), {r['speakers']} speakers")
    check("Re-transcribe: students labelled as the instructor", r["students_as_instructor"] <= args.max_students_as_instructor,
          f"{r['students_as_instructor']:.1%}; instructor split off {r['instructor_split']:.1%}")
    if s["instructor_named"] == INSTRUCTOR:
        check("Re-transcribe keeps the instructor's name (B-17)", r["instructor_named"] == INSTRUCTOR, f"shown as “{r['instructor_named']}”")
    else:
        log(f"info  instructor was not named live (“{s['instructor_named']}”), so the B-17 name check is skipped")
    log(f"info  Re-transcribe word agreement with Zoom: {word_agreement(cues, redo):.1%}")
    redo_file = sum(1 for _ in live_file.open(encoding="utf-8")) if live_file.exists() else 0
    log(f"info  {live_file.name} after Re-transcribe: {redo_file} lines")

    # 6. Clean up.
    if args.keep:
        log(f"kept meeting {meeting} and {replay}")
    else:
        for p in call("GET", f"/api/meetings/{meeting}")["parts"]:
            call("DELETE", f"/api/conversations/{p['conversation_id']}")
        Path(replay).unlink(missing_ok=True)
        log(f"deleted meeting {meeting} and the uploaded copy")

    failed = [c for c in checks if not c[1]]
    print(f"\n{len(checks) - len(failed)}/{len(checks)} checks passed" + ("" if not failed else ": FAILED " + ", ".join(c[0] for c in failed)))
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
