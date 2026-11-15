"""Operator commands (run from voice-backend/ with the same env as the server).

    python -m app.admin usage      # credit usage, budget left, cache hits, last 20 provider calls
    python -m app.admin resume     # clear provider_paused after fixing a 401/402/403 from Gnani
    python -m app.admin seed       # demo user + synthetic demo audio for the mock provider
    python -m app.admin vapid      # print a new Web Push key pair to paste into .env (prints a secret!)
"""
from __future__ import annotations

import hashlib
import json
import math
import os
import struct
import sys
import wave
from pathlib import Path

from . import auth
from .config import load_settings
from .db import Database
from .guard import resume_provider, usage_summary

DEMO_DIR = Path(__file__).resolve().parent.parent / "demo_audio"
# Synthetic tones stand in for speech: the mock provider maps each file's hash to this transcript.
DEMO_NOTES = [
    ("kal_report_bhej_dunga.wav", "kal 5 baje report bhej dunga", "hi-IN"),
    ("call_mom_tonight.wav", "I'll call mom tonight", "en-IN"),
    ("hindi_kal_bhejunga.wav", "मैं कल फाइल भेज दूंगा", "hi-IN"),
    ("tamil_naalai_anuppuven.wav", "நான் நாளை அனுப்புவேன்", "ta-IN"),
    ("not_a_promise_question.wav", "kya tum kal aa rahe ho?", "hi-IN"),
    ("past_not_a_promise.wav", "maine kal file bhej di thi", "hi-IN"),
]


def _tone(path: Path, freq: float, seconds: float = 1.5, rate: int = 8000) -> bytes:
    frames = b"".join(
        struct.pack("<h", int(8000 * math.sin(2 * math.pi * freq * i / rate))) for i in range(int(seconds * rate))
    )
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(frames)
    return path.read_bytes()


def seed() -> None:
    settings = load_settings()
    db = Database(settings.database_path)
    email = os.environ.get("DEMO_EMAIL", "demo@example.com")
    password = os.environ.get("DEMO_PASSWORD", "demo-password-123")
    created = auth.create_user(db, email, password)
    print(f"demo user {email}: {'created' if created else 'already exists'} (password from DEMO_PASSWORD or .env.example)")
    DEMO_DIR.mkdir(exist_ok=True)
    manifest = []
    for i, (name, transcript, lang) in enumerate(DEMO_NOTES):
        data = _tone(DEMO_DIR / name, 220 + 110 * i)
        manifest.append({"file": name, "sha256": hashlib.sha256(data).hexdigest(), "transcript": transcript,
                         "language": lang})
    (DEMO_DIR / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"wrote {len(manifest)} demo voice notes to {DEMO_DIR}")


def usage() -> None:
    settings = load_settings()
    db = Database(settings.database_path)
    print(json.dumps(usage_summary(db, settings), indent=2))
    from . import llm

    print("\nLLM (optional second opinion):")
    print(json.dumps(llm.usage_summary(db, settings), indent=2))
    print("\nlast 20 provider events:")
    for r in db.all("SELECT created_at, provider, outcome, billable, audio_seconds, latency_ms FROM usage_events "
                    "ORDER BY id DESC LIMIT 20"):
        print(dict(r))


def new_vapid_keys() -> tuple[str, str]:
    """(private, public) as unpadded base64url: the raw 32-byte key and the 65-byte uncompressed point."""
    import base64

    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import ec

    key = ec.generate_private_key(ec.SECP256R1())
    private = key.private_numbers().private_value.to_bytes(32, "big")
    public = key.public_key().public_bytes(serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint)
    b64 = lambda raw: base64.urlsafe_b64encode(raw).rstrip(b"=").decode()  # noqa: E731
    return b64(private), b64(public)


def main(argv: list[str]) -> int:
    cmd = argv[1] if len(argv) > 1 else ""
    if cmd == "seed":
        seed()
    elif cmd == "usage":
        usage()
    elif cmd == "vapid":
        private, public = new_vapid_keys()
        print("# Paste into voice-backend/.env (never commit). The private key is a secret.")
        print(f"VAPID_PRIVATE_KEY={private}\nVAPID_PUBLIC_KEY={public}\nVAPID_SUBJECT=mailto:you@example.com")
    elif cmd == "resume":
        resume_provider(Database(load_settings().database_path))
        print("provider resumed")
    else:
        print(__doc__)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
