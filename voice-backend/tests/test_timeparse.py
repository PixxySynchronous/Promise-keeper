from datetime import datetime
from zoneinfo import ZoneInfo

import pytest

from app.timeparse import due_time, parse

NOW = datetime(2026, 11, 13, 15, 0, tzinfo=ZoneInfo("Asia/Kolkata"))


@pytest.mark.parametrize("text, expected, default", [
    ("kal 5 baje bhej dunga", "2026-11-14T17:00", False),
    ("kal paanch baje bhej dunga", "2026-11-14T17:00", False),
    ("in 10 mins", "2026-11-13T15:10", False),
    ("do ghante mein karunga", "2026-11-13T17:00", False),
    ("I will call at 8:30 pm", "2026-11-13T20:30", False),
    ("shaam 7 baje", "2026-11-13T19:00", False),
    ("kal bhej dunga", "2026-11-14T10:00", True),
    ("parso pakka", "2026-11-15T10:00", True),
    ("naalaikku anuppuven", "2026-11-14T10:00", True),
    ("call at 2 pm", "2026-11-14T14:00", False),   # 2 pm already passed today -> tomorrow
])
def test_parse(text, expected, default):
    p = parse(text, NOW)
    assert p is not None
    assert p.when.isoformat(timespec="minutes").startswith(expected)
    assert p.used_default is default


@pytest.mark.parametrize("text", ["2 files bhej dunga", "maine kal bhej di thi", "ok"])
def test_no_time(text):
    assert parse(text, NOW) is None


def test_fallback_is_two_hours():
    assert due_time("bhej dunga", NOW).when.isoformat(timespec="minutes").startswith("2026-11-13T17:00")
