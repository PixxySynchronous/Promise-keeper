"""Detection quality gates. The eval set is the Android app's own (app/src/test/resources/commitment_eval.json)."""
import json
from collections import Counter
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

import pytest

from app import detect, timeparse

EVAL = Path(__file__).resolve().parents[2] / "app/src/test/resources/commitment_eval.json"
NOW = datetime(2026, 11, 13, 15, 0, tzinfo=ZoneInfo("Asia/Kolkata"))


def run(text, hint=None):
    p = timeparse.parse(text, NOW)
    return detect.detect(text, hint, has_time=p is not None, time_used_default=p.used_default if p else None)


def test_phrases_are_read_from_the_android_source():
    phrases = detect.commitment_phrases()
    assert "bhej dunga" in phrases and "भेज दूंगा" in phrases
    assert len(phrases) > 150


def test_rules_match_the_app_on_its_eval_set():
    """Gate: same order of accuracy as the app's rules (README: rules alone catch 87%). Raise floors, never lower."""
    c = Counter()
    for case in json.loads(EVAL.read_text(encoding="utf-8"))["cases"]:
        got = run(case["text"]).is_promise
        c["pos" if case["label"] else "neg"] += 1
        c["tp" if case["label"] and got else "fp" if got and not case["label"] else "x"] += 1
    recall, fpr = c["tp"] / c["pos"], c["fp"] / c["neg"]
    print(f"recall={recall:.3f} false_positive_rate={fpr:.3f}")
    assert recall >= 0.86
    assert fpr <= 0.01


@pytest.mark.parametrize("text", [
    "kal paanch baje report bhej dunga",      # transliterate spells numbers out
    "I'll call mom tonight",
    "मैं कल फाइल भेज दूंगा",
    "main 10 min mein call karta hu",
])
def test_promises(text):
    assert run(text).is_promise


@pytest.mark.parametrize("text, reason", [
    ("kya tum kal aa rahe ho?", "question"),
    ("maine kal file bhej di thi", "past tense"),
    ("I will not do it", "question, request, maybe or refusal"),   # backend-only fix; the app still flags this
    ("main roz gym jaata hu", "habit"),
    ("Priya will send it tomorrow", "someone else's promise"),
])
def test_tense_and_intent_rejections(text, reason):
    d = run(text)
    assert not d.is_promise
    assert reason in d.reasons


# Starter rules for other Gnani languages. UNVERIFIED by native speakers: these only pin current behaviour.
@pytest.mark.parametrize("text, hint", [
    ("naan naalaikku anuppuven", "ta-IN"), ("நான் நாளை அனுப்புவேன்", None),
    ("ami kal pathabo", "bn-IN"), ("mi udya pathavin", "mr-IN"), ("hu kale moklish", "gu-IN"),
    ("main kal bhejanga", "pa-IN"), ("nenu repu pampistanu", "te-IN"), ("naanu naale kalistini", "kn-IN"),
    ("njan naale ayakkum", "ml-IN"),
])
def test_multilingual_future_is_a_promise(text, hint):
    d = run(text, hint)
    assert d.is_promise and d.tense == "future" and d.verified_rules is False


@pytest.mark.parametrize("text, hint", [
    ("naan anuppa maaten", "ta-IN"), ("ami korbo na", "bn-IN"), ("tumi kokhon pathabe", "bn-IN"),
    ("I will finish english homework", "gu-IN"),  # "-ish" English words must not count as Gujarati future
    ("naale ayakkum", "ml-IN"),                    # Malayalam -um needs "njan"
])
def test_multilingual_rejections(text, hint):
    assert not run(text, hint).is_promise


def test_latin_text_without_hint_uses_hinglish_rules():
    assert detect.resolve_language("kal bhej dunga", None) == "en-IN"
    assert detect.resolve_language("kal bhej dunga", "indic-auto") == "en-IN"
    assert detect.resolve_language("ami kal pathabo", "bn-IN") == "bn-IN"
    assert detect.resolve_language("मी उद्या पाठवीन", "mr-IN") == "mr-IN"
