"""Real Gnani transcripts (text only) must keep producing the right reminder. Add a sample for every new live run."""
import json
from datetime import datetime, timedelta
from pathlib import Path

import pytest

from app import detect, timeparse

DATA = json.loads((Path(__file__).parent / "fixtures" / "transliterate_samples.json").read_text(encoding="utf-8"))
NOW = datetime.fromisoformat(DATA["recorded_at"])


@pytest.mark.parametrize("sample", DATA["samples"], ids=lambda s: f"{s['language']}: {s['transcript'][:32]}")
def test_real_transcript_gives_the_expected_reminder(sample):
    text = sample["transcript"]
    parsed = timeparse.parse(text, NOW)
    found = detect.detect(text, sample["language"], has_time=parsed is not None,
                          time_used_default=parsed.used_default if parsed else None)
    assert found.is_promise is sample["is_promise"], found.reasons
    if sample["due"]:
        days, clock = sample["due"]
        expected = datetime.combine(NOW.date() + timedelta(days=days), datetime.strptime(clock, "%H:%M").time(), NOW.tzinfo)
        assert parsed is not None and parsed.when == expected, (parsed and parsed.when, parsed and parsed.matched)
        assert parsed.used_default is False  # the spoken clock time was understood, not guessed


def test_time_fixes_do_not_touch_ordinary_words():
    now = NOW
    assert timeparse.parse("i am going to send it", now) is None            # "a m" must not become a time
    assert timeparse.parse("give me a moment", now) is None
    assert timeparse.numbers_to_digits("at four p.m. sharp") == "at 4 pm sharp"
    assert timeparse.numbers_to_digits("9 a m") == "9 am"
    assert timeparse.parse("subah saat vaje milange", now).when.hour == 7      # Punjabi morning stays morning
    assert timeparse.parse("panj kitaban leke aavanga", now) is None           # "panj" alone is just a number
    assert timeparse.parse("kal subah saat baje call karunga", now).when.hour == 7  # Hindi too: morning is not 19:00
    assert timeparse.parse("kal 7 baje call karunga", now).when.hour == 19          # no "morning": evening, as before
