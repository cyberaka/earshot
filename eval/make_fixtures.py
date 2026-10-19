"""Synthetic test recordings built with macOS `say`, until real recorder audio exists (PRD M-1).

Each speaker is a different system voice; "Me" is Rishi (Indian English). Writes WAVs plus a
labels.json with the true speaker of every utterance, so Me accuracy can be measured.

    ml/.venv/bin/python eval/make_fixtures.py
"""
import json
import subprocess
import tempfile
from pathlib import Path

import numpy as np
import soundfile as sf

SR = 16000
OUT = Path(__file__).parent / "audio"
VOICES = {"Me": "Rishi", "Ravi": "Daniel", "Ruchi": "Samantha", "Kiran": "Karen", "Vendor": "Reed (English (US))"}
rng = np.random.default_rng(7)


def speak(who: str, text: str) -> np.ndarray:
    with tempfile.TemporaryDirectory() as tmp:
        aiff, wav = Path(tmp) / "u.aiff", Path(tmp) / "u.wav"
        subprocess.run(["say", "-v", VOICES[who], "-o", str(aiff), text], check=True)
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", str(aiff), "-ac", "1", "-ar", str(SR), str(wav)], check=True)
        audio, _ = sf.read(wav, dtype="float32")
    return audio


def noise(seconds: float, level: float = 0.002) -> np.ndarray:
    return (rng.standard_normal(int(seconds * SR)) * level).astype("float32")


def build(script, gap=0.6):
    """script: list of (who, text) or ("gap", seconds). Returns audio and labels."""
    parts, labels, t = [noise(1.0)], [], 1.0
    for who, text in script:
        if who == "gap":
            parts.append(noise(text))
            t += text
            continue
        a = speak(who, text)
        labels.append({"speaker": who, "start": round(t, 2), "end": round(t + len(a) / SR, 2), "text": text})
        parts += [a, noise(gap)]
        t += len(a) / SR + gap
    audio = np.concatenate(parts)
    return audio + noise(len(audio) / SR), labels


SPRINT = [
    ("Me", "Okay, let's start with payments. Where are we on the migration?"),
    ("Ravi", "The vendor says the sandbox is ready, but the production cut over needs one more sprint."),
    ("Ruchi", "The bigger issue is the integration tests. Three of them fail randomly on the build server."),
    ("Me", "Can we quarantine those this week and fix them after the code freeze?"),
    ("Ruchi", "Yes, I will quarantine the flaky tests today and open tickets for each one."),
    ("Ravi", "I will confirm the new migration date with the vendor and update the tracker."),
    ("Me", "Great. I will share the release checklist by Thursday. Let's freeze scope on Friday."),
    ("Ravi", "Agreed. Friday it is for the scope freeze."),
]
HIRING = [
    ("Me", "How did the system design round go with the backend candidate?"),
    ("Kiran", "Strong on databases, weaker on caching. I would say a lean hire."),
    ("Me", "Let's schedule one more round focused on caching before we decide."),
    ("Kiran", "I can set that up for next Tuesday and send the feedback form."),
    ("Me", "Also, please ask them about rate limiting. That came up in the last two rounds as a gap."),
    ("Kiran", "Good idea. I will add a rate limiting question and share the rubric with you tomorrow."),
    ("Me", "Thanks Kiran. Let's decide by Wednesday so we don't lose the candidate."),
]
AMBIENT = [
    ("Ravi", "Did you see the cricket match last night? What an ending."),
    ("Kiran", "I missed it. Was it the final over again?"),
    ("Ravi", "Yes, six runs needed off the last two balls."),
    ("Kiran", "Unbelievable. I need to watch the highlights."),
    ("Ravi", "The crowd went completely silent before the last ball. Then the stadium just exploded."),
    ("Kiran", "Are you going to the stadium for the next home game? Tickets sell out quickly."),
    ("Ravi", "Maybe. My brother is trying to get four tickets for the weekend match."),
]
CATCHUP = [
    ("Ruchi", "Quick update. Two of the flaky tests are quarantined now."),
    ("Me", "Nice. What about the reconciliation test?"),
    ("Ruchi", "That one is a real bug. I will pair with Ravi on it tomorrow morning."),
    ("Me", "Okay. If it needs a schema change, flag it before Friday because of the freeze."),
    ("Ruchi", "Will do. I think it is only the rounding in the currency conversion, so no schema change."),
    ("Me", "Perfect. Send me a note once it is merged so I can update the release checklist."),
]
MEETING = [
    ("Vendor", "The sandbox has been stable for two weeks, so we are comfortable with week forty six for production."),
    ("Me", "Okay. And the refund flow, is that the same integration?"),
    ("Vendor", "Same interface, yes. Refunds go through the payments endpoint with a negative amount."),
    ("Me", "Your test plan only lists full refunds though. Partial refunds are not in there."),
    ("Ruchi", "That is a fair point. We will add partial refunds to the test plan."),
    ("Vendor", "On monitoring, we expose a status webhook you can subscribe to."),
    ("Me", "That works. We will wire it into our alerting before the cut over."),
    ("Ruchi", "Next we should talk about pricing for the second region."),
]
ENROLL = ("Me", "This is my voice for enrollment. I am reading a few sentences so the app can learn how I "
          "sound. I usually talk about sprints, releases, payments and hiring. The weather today is pleasant "
          "and I had coffee with the team. Earshot should recognise me in every recording from now on.")


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    day, labels, t0 = [], [], 0.0
    for name, script, trailing_gap in [("sprint", SPRINT, 150), ("hiring", HIRING, 150),
                                       ("ambient", AMBIENT, 150), ("catchup", CATCHUP, 5)]:
        a, lab = build(script)
        for x in lab:
            labels.append({**x, "conversation": name, "start": round(x["start"] + t0, 2), "end": round(x["end"] + t0, 2)})
        day += [a, noise(trailing_gap)]
        t0 += len(a) / SR + trailing_gap
    sf.write(OUT / "REC_20261020_094000.wav", np.concatenate(day), SR)

    enroll, _ = build([ENROLL])
    sf.write(OUT / "enroll_me.wav", enroll, SR)

    # Meeting as two channels on one clock: mic carries Me only, system carries everyone else.
    full, mlab = build(MEETING)
    mic, system = np.zeros_like(full), np.zeros_like(full)
    for x in mlab:
        s, e = int(x["start"] * SR), int(x["end"] * SR)
        (mic if x["speaker"] == "Me" else system)[s:e] = full[s:e]
    sf.write(OUT / "meeting_mic.wav", mic + noise(len(mic) / SR), SR)
    sf.write(OUT / "meeting_system.wav", system + noise(len(system) / SR), SR)

    (OUT / "labels.json").write_text(json.dumps({"day": labels, "meeting": mlab}, indent=1))
    print(f"day {t0/60:.1f} min, {len(labels)} utterances; meeting {len(full)/SR:.0f} s")


if __name__ == "__main__":
    main()
