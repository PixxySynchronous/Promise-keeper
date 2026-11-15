"""Upload validation before anything is queued: extension, size, and a duration we can actually measure."""
from __future__ import annotations

import io
import wave
from dataclasses import dataclass

from .config import SUPPORTED_EXTENSIONS, Settings


@dataclass
class AudioCheck:
    extension: str
    seconds: float


class AudioRejected(Exception):
    def __init__(self, status: int, code: str, message: str):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message


def extension_of(filename: str) -> str:
    return filename.rsplit(".", 1)[-1].lower() if "." in filename else ""


def measure_seconds(data: bytes, extension: str) -> float | None:
    """Duration in seconds, or None if the file can't be parsed. Never trusts a client-declared length."""
    if extension == "wav":
        try:
            with wave.open(io.BytesIO(data)) as w:
                rate = w.getframerate()
                return w.getnframes() / rate if rate else None
        except (wave.Error, EOFError):
            return None
    try:
        import mutagen

        info = mutagen.File(io.BytesIO(data))
        length = getattr(getattr(info, "info", None), "length", None)
        return float(length) if length else None
    except Exception:  # mutagen raises many types on malformed input
        return None


def validate(filename: str, data: bytes, settings: Settings) -> AudioCheck:
    ext = extension_of(filename)
    if ext not in SUPPORTED_EXTENSIONS:
        raise AudioRejected(415, "UNSUPPORTED_FORMAT", f"Use one of: {', '.join(SUPPORTED_EXTENSIONS)}.")
    if not data:
        raise AudioRejected(400, "EMPTY_AUDIO", "The file is empty.")
    if len(data) > settings.max_upload_bytes:
        raise AudioRejected(413, "FILE_TOO_LARGE", f"Max upload size is {settings.max_upload_bytes} bytes.")
    seconds = measure_seconds(data, ext)
    if seconds is None:
        raise AudioRejected(400, "UNREADABLE_AUDIO", "Could not read the audio length; send a valid audio file.")
    if seconds < settings.min_audio_seconds:
        raise AudioRejected(400, "AUDIO_TOO_SHORT", f"Audio must be at least {settings.min_audio_seconds} s.")
    if seconds > settings.effective_max_audio_seconds:
        raise AudioRejected(413, "AUDIO_TOO_LONG", f"Audio must be at most {settings.effective_max_audio_seconds:g} s.")
    return AudioCheck(extension=ext, seconds=round(seconds, 2))
