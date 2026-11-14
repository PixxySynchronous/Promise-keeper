"""Prints the numbers quoted in the overview doc. Offline: mock speech-to-text, a temporary database.

    cd voice-backend && .venv/bin/python scripts/measure.py
"""
from __future__ import annotations

import io
import json
import math
import statistics
import struct
import sys
import tempfile
import time
import wave
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from fastapi.testclient import TestClient  # noqa: E402

from app import detect, timeparse  # noqa: E402
from app.config import Settings  # noqa: E402
from app.jobs import process_next  # noqa: E402
from app.main import create_app  # noqa: E402
from app.stt import Transcript  # noqa: E402

EVAL = ROOT.parent / "app/src/test/resources/commitment_eval.json"
RUNS = 50


class FixedTranscript:
    """Stands in for speech-to-text and answers at once, so only our own code is timed."""
    name, billable = "mock", False

    def cache_identity(self):
        return "measure"

    def transcribe(self, audio, filename, request_id, language):
        return Transcript("kal 5 baje report bhej dunga", None, request_id)


def tone(freq: float) -> bytes:
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1), w.setsampwidth(2), w.setframerate(8000)
        w.writeframes(b"".join(struct.pack("<h", int(3000 * math.sin(2 * math.pi * freq * i / 8000))) for i in range(40000)))
    return buf.getvalue()


def detection() -> dict:
    """Same method as the gate in tests/test_detect.py, on the Android app's own labelled messages."""
    now = datetime(2026, 11, 13, 15, 0, tzinfo=ZoneInfo("Asia/Kolkata"))
    cases = json.loads(EVAL.read_text(encoding="utf-8"))["cases"]
    tp = fp = pos = neg = 0
    for case in cases:
        parsed = timeparse.parse(case["text"], now)
        found = detect.detect(case["text"], None, has_time=parsed is not None,
                              time_used_default=parsed.used_default if parsed else None).is_promise
        if case["label"]:
            pos += 1
            tp += found
        else:
            neg += 1
            fp += found
    return {"messages": len(cases), "promises": pos, "caught": tp, "recall": round(tp / pos, 3),
            "others": neg, "false_alarms": fp}


def latency() -> dict:
    with tempfile.TemporaryDirectory() as tmp:
        # Limits raised only inside this throwaway database, so 50 uploads in a row are allowed.
        settings = Settings(database_path=f"{tmp}/m.db", worker_enabled=False, user_requests_per_minute=10_000,
                            user_daily_requests=10_000, user_daily_audio_seconds=1e9, global_daily_requests=10_000)
        app = create_app(settings, provider=FixedTranscript(), start_worker=False)
        with TestClient(app) as client:
            cred = {"email": "m@example.com", "password": "password123"}
            client.post("/auth/register", json=cred)
            headers = {"Authorization": "Bearer " + client.post("/auth/login", json=cred).json()["token"]}
            fresh, repeat = [], []
            for i in range(RUNS):
                audio = tone(300 + i)  # 5 s, different each time: no cache hit
                for bucket in (fresh, repeat):  # second pass = same audio = transcript cache hit
                    start = time.perf_counter()
                    job = client.post("/voice-notes", headers=headers, files={"audio_file": ("n.wav", audio, "audio/wav")}).json()
                    process_next(app.state.db, settings, app.state.provider)
                    done = client.get(f"/voice-notes/{job['id']}", headers=headers).json()
                    bucket.append((time.perf_counter() - start) * 1000)
                    assert done["status"] == "done" and done["reminder"], done
            start = time.perf_counter()
            pdf = client.get("/reports/weekly.pdf", headers=headers)
            first_pdf = (time.perf_counter() - start) * 1000
            start = time.perf_counter()
            again = client.get("/reports/weekly.pdf", headers=headers)
            cached_pdf = (time.perf_counter() - start) * 1000
            assert pdf.headers["x-report-cache"] == "miss" and again.headers["x-report-cache"] == "hit"
            calls = app.state.db.one("SELECT SUM(outcome = 'ok') AS paid, SUM(outcome = 'cached') AS cached FROM usage_events")
    med = lambda xs: round(statistics.median(xs), 1)  # noqa: E731
    return {"runs": RUNS, "upload_to_reminder_ms_median": med(fresh), "upload_to_reminder_ms_max": round(max(fresh), 1),
            "repeat_upload_ms_median": med(repeat), "provider_calls": calls["paid"], "cache_hits": calls["cached"],
            "weekly_pdf_first_ms": round(first_pdf, 1), "weekly_pdf_cached_ms": round(cached_pdf, 1)}


if __name__ == "__main__":
    print(json.dumps({"detection": detection(), "server": latency()}, indent=2))
