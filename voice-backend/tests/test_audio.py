from dataclasses import replace

import pytest

from app.audio import AudioRejected, validate


def code_of(filename, data, settings):
    with pytest.raises(AudioRejected) as exc:
        validate(filename, data, settings)
    return exc.value.status, exc.value.code


def test_valid_wav(settings, wav):
    assert validate("note.wav", wav(2.0), settings).seconds == 2.0


def test_rejections(settings, wav):
    assert code_of("note.webm", wav(), settings) == (415, "UNSUPPORTED_FORMAT")
    assert code_of("note.wav", b"", settings) == (400, "EMPTY_AUDIO")
    assert code_of("note.wav", b"RIFF-not-really-audio", settings) == (400, "UNREADABLE_AUDIO")
    assert code_of("note.mp3", b"\x00" * 1000, settings) == (400, "UNREADABLE_AUDIO")
    assert code_of("note.wav", wav(0.2), settings) == (400, "AUDIO_TOO_SHORT")
    assert code_of("note.wav", wav(31), settings) == (413, "AUDIO_TOO_LONG")
    assert code_of("note.wav", wav(5), replace(settings, max_upload_bytes=1000)) == (413, "FILE_TOO_LARGE")


def test_duration_cap_can_never_exceed_gnani_limit(settings, wav):
    loose = replace(settings, max_audio_seconds=600, max_upload_bytes=10_000_000)
    assert loose.effective_max_audio_seconds == 30
    assert code_of("note.wav", wav(31), loose) == (413, "AUDIO_TOO_LONG")
