"""Gnani (Vachana) REST speech-to-text: POST https://api.vachana.ai/stt/v3, multipart/form-data.

Docs: https://docs.gnani.ai/api/STT/speech-to-text (captured in reference/SOURCES.md, which is gitignored).
The API key is sent only in the X-API-Key-ID header and is never logged, stored or returned.
"""
from __future__ import annotations

import httpx

from ..config import Settings
from . import SttError, Transcript

_MIME = {"wav": "audio/wav", "mp3": "audio/mpeg", "ogg": "audio/ogg", "flac": "audio/flac",
         "aac": "audio/aac", "m4a": "audio/mp4"}
# 401/402/403 will not fix themselves: pause the provider so queued jobs stop spending attempts.
_PAUSE = {401, 402, 403}
_RETRY = {429, 500, 502, 503, 504}


class GnaniProvider:
    name = "gnani"
    billable = True

    def __init__(self, settings: Settings, transport: httpx.BaseTransport | None = None):
        if not settings.live_gnani:
            # Defence in depth: the factory already checks, but never build a live client by accident.
            raise RuntimeError("Gnani provider needs STT_PROVIDER=gnani, GNANI_LIVE_CALLS_ENABLED=true and GNANI_API_KEY")
        self._s = settings
        self._client = httpx.Client(timeout=settings.gnani_timeout_seconds, transport=transport)

    def cache_identity(self) -> str:
        return f"gnani|{self._s.gnani_model}|{self._s.gnani_format}"

    def transcribe(self, audio: bytes, filename: str, request_id: str, language: str) -> Transcript:
        """One attempt. Retries live in jobs.py so every attempt is written to the cost log."""
        ext = filename.rsplit(".", 1)[-1].lower()
        data = {"language_code": language or self._s.gnani_language, "format": self._s.gnani_format}
        if self._s.gnani_model:
            data["model"] = self._s.gnani_model
        try:
            resp = self._client.post(
                self._s.gnani_url,
                headers={"X-API-Key-ID": self._s.gnani_api_key, "X-API-Request-ID": request_id},
                data=data,
                # Gnani detects the format from the file-name extension, so keep it.
                files={"audio_file": (f"{request_id}.{ext}", audio, _MIME.get(ext, "application/octet-stream"))},
            )
        except httpx.TimeoutException as exc:
            raise SttError("UPSTREAM_TIMEOUT", "Gnani did not answer in time.", retryable=True) from exc
        except httpx.HTTPError as exc:
            # Connection never established: almost certainly not billed.
            raise SttError("NETWORK_ERROR", type(exc).__name__, retryable=True, reached_upstream=False) from exc

        body = _json(resp)
        if resp.status_code == 200 and body.get("success") is not False:
            return Transcript(
                text=(body.get("transcript") or "").strip(),
                detected_language=body.get("detected_language_code"),
                upstream_request_id=body.get("request_id"),
                truncated=bool(body.get("truncated")),
            )
        code = _error_code(body) or f"HTTP_{resp.status_code}"
        raise SttError(code, _error_message(body) or f"Gnani returned HTTP {resp.status_code}",
                       retryable=resp.status_code in _RETRY, pause_provider=resp.status_code in _PAUSE)


def _json(resp: httpx.Response) -> dict:
    try:
        body = resp.json()
        return body if isinstance(body, dict) else {}
    except ValueError:
        return {}


def _error_code(body: dict) -> str | None:
    err = body.get("error")
    if isinstance(err, dict) and err.get("type"):
        return str(err["type"])
    detail = body.get("detail")
    if isinstance(detail, dict) and detail.get("error_code"):
        return str(detail["error_code"])
    return body.get("code")


def _error_message(body: dict) -> str | None:
    err = body.get("error")
    if isinstance(err, dict):
        return err.get("message")
    detail = body.get("detail")
    if isinstance(detail, dict):
        return detail.get("message")
    return body.get("message")
