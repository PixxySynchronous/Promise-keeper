"""Offline, free, deterministic STT for development, tests and dry-run demos.

The transcript comes from (1) the demo manifest, keyed by the audio's SHA-256, else (2) the file name:
`kal_5_baje_report_bhej_dunga.wav` -> "kal 5 baje report bhej dunga". No network, no credits.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

from . import Transcript

MANIFEST = Path(__file__).resolve().parents[2] / "demo_audio" / "manifest.json"


class MockProvider:
    name = "mock"
    billable = False

    def __init__(self, manifest: Path = MANIFEST):
        self._by_hash: dict[str, dict] = {}
        if manifest.exists():
            for item in json.loads(manifest.read_text(encoding="utf-8")):
                self._by_hash[item["sha256"]] = item

    def cache_identity(self) -> str:
        return "mock"

    def transcribe(self, audio: bytes, filename: str, request_id: str, language: str) -> Transcript:
        item = self._by_hash.get(hashlib.sha256(audio).hexdigest())
        if item:
            return Transcript(item["transcript"], item.get("language"), f"mock-{request_id}")
        stem = filename.rsplit("/", 1)[-1].rsplit(".", 1)[0]
        return Transcript(stem.replace("_", " ").replace("-", " ").strip(), None, f"mock-{request_id}")

