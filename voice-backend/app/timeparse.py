"""When should the reminder go off? A deliberately small subset of the app's TimeParser.kt.

Covers: "in 10 mins" / "10 min mein" / "2 ghante mein", today/tomorrow/day-after (English, Hinglish and the
"tomorrow"/"today" word of each Gnani language), clock times ("5 baje", "5 pm", "8:30", "at 5"), parts of day,
and "EOD". Anything else falls back to now + 2 hours, flagged with used_default=True.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import datetime, time, timedelta


@dataclass
class ParsedTime:
    when: datetime
    matched: str
    used_default: bool


# Native script plus the romanized spellings Gnani's transliterate format produces (UNVERIFIED spellings:
# extend from real transcripts).
DAY_WORDS = {
    0: ["today", "aaj", "aj", "aaje", "ajj", "indru", "inniki", "innaikku", "eeroju", "ivala", "ivattu", "innu",
        "आज", "আজ", "આજે", "ਅੱਜ", "இன்று", "ఈరోజు", "ಇಂದು", "ഇന്ന്"],
    1: ["tomorrow", "tmrw", "kal", "kl", "kaal", "kall", "kale", "kaale", "agamikal", "udya", "udyaa", "naalai",
        "nalaikku", "naalaikku", "repu", "naale", "nale",
        "कल", "उद्या", "আগামীকাল", "কাল", "કાલે", "ਕੱਲ੍ਹ", "நாளை", "రేపు", "ನಾಳೆ", "നാളെ"],
    2: ["day after tomorrow", "parso", "parson", "parva", "परसों", "परवा"],
}
# Transliterate spells numbers out ("paanch baje"). Only rewritten before a time unit, so Hindi "do" (two)
# vs English "do" never matters elsewhere.
NUMBER_WORDS = {
    "one": 1, "two": 2, "three": 3, "four": 4, "five": 5, "six": 6, "seven": 7, "eight": 8, "nine": 9, "ten": 10,
    "eleven": 11, "twelve": 12, "fifteen": 15, "twenty": 20, "thirty": 30,
    "ek": 1, "do": 2, "teen": 3, "tin": 3, "char": 4, "chaar": 4, "paanch": 5, "panch": 5, "chhe": 6, "che": 6,
    "chhah": 6, "saat": 7, "sat": 7, "aath": 8, "aat": 8, "nau": 9, "no": 9, "das": 10, "dus": 10, "gyarah": 11,
    "gyara": 11, "barah": 12, "bara": 12, "pandrah": 15, "bees": 20, "tees": 30,
}
_NUMBER_BEFORE_UNIT = re.compile(
    r"\b(" + "|".join(sorted(NUMBER_WORDS, key=len, reverse=True)) + r")\s+"
    r"(?=(baje|bje|o'?clock|am|pm|min|mins|minute|minutes|ghante|ghanta|hours?|hrs?)\b)",
    re.I,
)


def numbers_to_digits(text: str) -> str:
    return _NUMBER_BEFORE_UNIT.sub(lambda m: f"{NUMBER_WORDS[m.group(1).lower()]} ", text)
PART_OF_DAY = {
    "morning": time(9, 30), "subah": time(9, 30), "सुबह": time(9, 30),
    "afternoon": time(14, 0), "dopahar": time(14, 0), "दोपहर": time(14, 0),
    "evening": time(18, 0), "shaam": time(18, 0), "sham": time(18, 0), "शाम": time(18, 0),
    "tonight": time(21, 0), "night": time(21, 0), "raat": time(21, 0), "रात": time(21, 0),
    "eod": time(18, 0), "end of day": time(18, 0),
}
DEFAULT_DAY_TIME = time(10, 0)
NO_TIME_DELAY = timedelta(hours=2)

DURATION = re.compile(
    r"\b(?:in\s+)?(\d{1,3})\s*(min|mins|minute|minutes|ghante|ghanta|hr|hrs|hour|hours)\b(?:\s*(?:mein|me|mai|में))?",
    re.I,
)
CLOCK = re.compile(
    r"(?:\bat\s+)?\b(\d{1,2})(?:[:.](\d{2}))?\s*(am|pm|a\.m\.|p\.m\.|baje|bje|बजे|o'?clock)?(?=\W|$)", re.I
)
PAST_HINT = re.compile(r"\b(tha|thi|gaya|gayi|kiya|hua|was|were|did)\b|था|थी|गया", re.I)


def _contains_word(text: str, word: str) -> bool:
    if re.fullmatch(r"[a-z ]+", word):
        return re.search(rf"\b{re.escape(word)}\b", text) is not None
    return word in text  # Indic scripts: \b is unreliable around vowel signs


def _day_offset(text: str) -> tuple[int, str] | None:
    for offset in (2, 1, 0):
        for w in DAY_WORDS[offset]:
            if _contains_word(text, w):
                # Hindi "kal" also means yesterday; with past tense it is not a future day.
                if w in ("kal", "kl", "कल") and PAST_HINT.search(text):
                    continue
                return offset, w
    return None


def _clock(text: str) -> tuple[time, str, bool] | None:
    """Returns (time, matched text, has_explicit_meridiem_or_marker)."""
    for m in CLOCK.finditer(text):
        hour, minute, marker = int(m.group(1)), int(m.group(2) or 0), (m.group(3) or "").lower().replace(".", "")
        explicit_at = m.group(0).lower().lstrip().startswith("at ")
        if not marker and not m.group(2) and not explicit_at:
            continue  # a bare number ("2 files") is not a time
        if hour > 23 or minute > 59:
            continue
        if marker == "pm" and hour < 12:
            hour += 12
        elif marker == "am" and hour == 12:
            hour = 0
        return time(hour, minute), m.group(0).strip(), marker in ("am", "pm")
    return None


def parse(text: str, now: datetime) -> ParsedTime | None:
    lower = numbers_to_digits(text.lower())
    if m := DURATION.search(lower):
        amount, unit = int(m.group(1)), m.group(2).lower()
        delta = timedelta(minutes=amount) if unit.startswith("min") else timedelta(hours=amount)
        return ParsedTime(now + delta, m.group(0).strip(), False)

    day = _day_offset(lower)
    clock = _clock(lower)
    part = next(((w, t) for w, t in PART_OF_DAY.items() if _contains_word(lower, w)), None)
    if not day and not clock and not part:
        return None

    base = now.date() + timedelta(days=day[0] if day else 0)
    if clock:
        t, matched, has_meridiem = clock
        # "shaam 7 baje" -> 19:00. A bare 1-7 with no am/pm is usually afternoon/evening in chat.
        if not has_meridiem and t.hour < 12 and (part and part[1].hour >= 14 or 1 <= t.hour <= 7):
            t = time(t.hour + 12, t.minute)
        when, used_default = datetime.combine(base, t, now.tzinfo), False
    elif part:
        when, matched, used_default = datetime.combine(base, part[1], now.tzinfo), part[0], True
    else:
        when, matched, used_default = datetime.combine(base, DEFAULT_DAY_TIME, now.tzinfo), day[1], True
    if day is None and when <= now:
        when += timedelta(days=1)  # "5 pm" said at 6 pm means tomorrow
    if day and clock is None and part is None and day[0] == 0:
        when = now + NO_TIME_DELAY  # "aaj" alone: later today
    return ParsedTime(when, matched if not day or matched == day[1] else f"{day[1]} {matched}", used_default)


def due_time(text: str, now: datetime) -> ParsedTime:
    return parse(text, now) or ParsedTime(now + NO_TIME_DELAY, "", True)
