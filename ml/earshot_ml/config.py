"""Settings for the Python processes, from the same .env as the service (PRD §10.1)."""
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def _load_dotenv() -> None:
    env = ROOT / ".env"
    if not env.exists():
        return
    for line in env.read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            os.environ.setdefault(key.strip(), value.strip())


_load_dotenv()
# Cached models only: a runtime download would be an outbound call (NF-1).
os.environ.setdefault("HF_HUB_OFFLINE", "1")

SECRET = os.environ.get("EARSHOT_ML_SECRET", "")
ML_PORT = int(os.environ.get("EARSHOT_ML_PORT", "8091"))
CAPTURE_PORT = int(os.environ.get("EARSHOT_CAPTURE_PORT", "8092"))
WHISPER_MODEL = os.environ.get("EARSHOT_WHISPER_MODEL", "mlx-community/whisper-large-v3-turbo")
# A second opinion on one badly heard line (owner request R-4): the full large-v3, slower than turbo.
# Loaded only for that click, then the everyday model is loaded back.
SECOND_OPINION_MODEL = os.environ.get("EARSHOT_SECOND_OPINION_MODEL", "mlx-community/whisper-large-v3-mlx")
DIARIZATION_MODEL = os.environ.get("EARSHOT_DIARIZATION_MODEL", "pyannote/speaker-diarization-community-1")
EMBEDDING_MODEL = os.environ.get("EARSHOT_SPEAKER_EMBEDDING_MODEL", "pyannote/wespeaker-voxceleb-resnet34-LM")
SAMPLE_RATE = 16000
