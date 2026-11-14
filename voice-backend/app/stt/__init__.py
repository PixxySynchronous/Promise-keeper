"""Speech-to-text providers behind one small interface, so mock and Gnani are swappable by env var."""
from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol


@dataclass
class Transcript:
    text: str
    detected_language: str | None
    upstream_request_id: str | None = None
    truncated: bool = False


class SttError(Exception):
    """retryable: worth one more attempt (429/5xx). pause_provider: stop all calls until an operator acts (401/402/403)."""

    def __init__(self, code: str, message: str, *, retryable: bool = False, pause_provider: bool = False,
                 reached_upstream: bool = True):
        super().__init__(message)
        self.code = code
        self.message = message
        self.retryable = retryable
        self.pause_provider = pause_provider
        self.reached_upstream = reached_upstream


class SttProvider(Protocol):
    name: str
    billable: bool

    def cache_identity(self) -> str:
        """Settings that change the transcript; part of the cache key (with the language hint)."""

    def transcribe(self, audio: bytes, filename: str, request_id: str, language: str) -> Transcript: ...
