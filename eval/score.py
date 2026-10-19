"""Me accuracy on the evaluation set (PRD §13, §13.1), read back through the running service.

    python3 eval/score.py 2026-10-20

Each labelled utterance is matched to the transcript segment it overlaps most; it counts as
correct when "is Me" agrees. Ambient conversations count too: their Me check is the ambient flag.
"""
import json
import sys
import urllib.request
from pathlib import Path

BASE = "http://127.0.0.1:8090"


def get(path):
    with urllib.request.urlopen(BASE + path) as r:
        return json.load(r)


def main(day: str):
    labels = json.loads((Path(__file__).parent / "audio" / "labels.json").read_text())["day"]
    segments = []
    for c in get(f"/api/days/{day}")["conversations"]:
        d = get(f"/api/conversations/{c['id']}")
        segments += [(s["startS"], s["endS"], s["speaker"]) for s in d["segments"]]
    total = correct = words_missing = 0
    for u in labels:
        best, overlap = None, 0.0
        for s, e, spk in segments:
            ov = min(e, u["end"]) - max(s, u["start"])
            if ov > overlap:
                best, overlap = spk, ov
        total += 1
        if best is None:
            words_missing += 1
            continue
        correct += (best == "Me") == (u["speaker"] == "Me")
    print(f"utterances={total} matched={total - words_missing} me_accuracy={correct / max(total, 1):.1%}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "2026-10-20")
