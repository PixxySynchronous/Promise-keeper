"""Promise detection for transcripts, language-aware, with an explicit tense step.

Pipeline:  transcript -> language (Gnani's detected code, else script) -> tense/intent signals -> decision.

* English / Hinglish / Hindi: a port of the Android app's RuleBasedCommitmentDetector
  (app/src/main/java/com/satyam/whatsappbot/domain/CommitmentDetector.kt). The phrase list is read
  straight from CommitmentPhrases.kt so the app and the server never drift apart.
* Other Gnani languages (bn, gu, kn, ml, mr, pa, ta, te): starter tense rules — first-person future
  verb endings, negation and question markers. UNVERIFIED: they need native-speaker labelled examples
  (tests/fixtures/multilingual_eval.json) before anyone quotes accuracy for them.
"""
from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass, field
from functools import lru_cache
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
PHRASES_KT = REPO_ROOT / "app/src/main/java/com/satyam/whatsappbot/domain/CommitmentPhrases.kt"


@dataclass
class Detection:
    is_promise: bool
    language: str               # e.g. "hi-IN", "ta-IN", or "en-IN" for English/Hinglish
    tense: str                  # future | present | past | habit | none
    matched: str | None = None  # phrase or verb ending that decided it
    reasons: list[str] = field(default_factory=list)  # why it was rejected, for debugging and the demo
    verified_rules: bool = True  # False for the starter rules of languages without an eval set


@lru_cache(maxsize=1)
def commitment_phrases() -> tuple[str, ...]:
    """String literals inside `CommitmentPhrases.all`, skipping // comments."""
    text = PHRASES_KT.read_text(encoding="utf-8")
    body = text[text.index("listOf(") : text.rindex(")")]
    body = "\n".join(line.split("//", 1)[0] for line in body.splitlines())
    return tuple(re.findall(r'"([^"]+)"', body))


# ---------------------------------------------------------------------------------------------------------
# Script detection (fallback when the provider gives no language code)
# ---------------------------------------------------------------------------------------------------------
_SCRIPTS = {
    "hi-IN": (0x0900, 0x097F),  # Devanagari (also Marathi; Gnani's code disambiguates)
    "bn-IN": (0x0980, 0x09FF),
    "pa-IN": (0x0A00, 0x0A7F),
    "gu-IN": (0x0A80, 0x0AFF),
    "ta-IN": (0x0B80, 0x0BFF),
    "te-IN": (0x0C00, 0x0C7F),
    "kn-IN": (0x0C80, 0x0CFF),
    "ml-IN": (0x0D00, 0x0D7F),
}


def script_language(text: str) -> str:
    counts: dict[str, int] = {}
    for ch in text:
        cp = ord(ch)
        for lang, (lo, hi) in _SCRIPTS.items():
            if lo <= cp <= hi:
                counts[lang] = counts.get(lang, 0) + 1
    return max(counts, key=counts.get) if counts else "en-IN"


# ---------------------------------------------------------------------------------------------------------
# English / Hinglish / Hindi — port of CommitmentDetector.kt (keep the two in sync)
# ---------------------------------------------------------------------------------------------------------
def normalize(value: str) -> str:
    v = unicodedata.normalize("NFC", value).lower().replace("’", "'")
    v = re.sub(r"[^a-z0-9'ऀ-ॿ]+", " ", v)
    v = re.sub(r"\b(bhej|bhj|bata|bta|kar|kr|dekh|bol) (ta|ti|unga|ungi)\b", r"\1\2", v)
    v = re.sub(r"\b(bhej|bhj|bata|bta|kar|kr|de|rakh|sambhal)(dunga|dungi|doonga|doongi|lunga|lungi)\b", r"\1 \2", v)
    return v.strip()


R = re.compile
SELF_TODO = R(
    r"^((hi+|hey+|hello|ok|okay|ugh|oh|bro|bhai|yaar|yr|so|also|btw)\s+)*(need to|have to|must|gotta|got to)\b"
    r"|\bi (really |still |also |just )?(need to|have to|must|gotta|got to)\b|\bi'?ve (got )?to\b"
)
START_QUESTION_OR_UNCERTAINTY = R(r"^(can you|will you|could you|would you|maybe|i was going to)\b")
NEGATIVE_SIGNALS = [
    R(r"^(he|she|they|you) will\b"),
    R(r"\b(he|she|they) (said|told|promised)\b"),
    R(r"^(did|do|does|have|has) (you|u|he|she|they)\b"),
    R(r"^(please|pls|plz) (send|share|forward|call|reply|confirm|do|let)\b"),
    R(r"\b(when|what time|where|why|how) (will|would|can|do|did) (you|u)\b"),
    R(
        r"^((hi+|hey+|hello|helo|yo|bro|bhai|yaar|yr|dude|ok|okay|acha|achha|sir|maam|ma'am|good morning|gm)\s+)*"
        r"(is|are|am|was|were|has|what|when|where|why|how|which|who|whose|kya|kab|kahan|kaha|kyu|kyun|kyon|kaise|kitna|kitne|kitni|kaun|kidhar"
        r"|(do|does|did|can|could|would|should|shall) (you|u|we|i|he|she|they|it))\b"
    ),
    R(r"\balready\b"),
    R(r"\b(maybe|might|probably|shayad|shyd|not sure|no promises|pakka nahi|pata nahi|pta ni|try karunga|try karungi)\b"),
    R(r"\b(won't|wont|can't|cant|cannot|couldn't|should|would have) "),
    # Not in the app yet: explicit future negation ("I will not", "not going to", "kabhi nahi").
    R(r"\b(will not|shall not|not going to|not gonna|never going to|kabhi nahi|kabhi nhi)\b"),
    R(r"\b(nahi|nhi|ni) (ho|bhej|kar|kr|de|aa) ?(paunga|paungi|payega|payegi|pauga|sakta|sakti|saka)\b"),
    R(r"\b(nahi|nhi|ni) (\w+ )?\w*(unga|ungi|oonga|oongi)\b"),
    R(r"\b(soch ke|soch kar|sochta hu|sochti hu|agar|if i get time|if i find time)\b"),
    R(r"\b(think about it|think it over|think and tell|let me think|lemme think)\b"),
]
WORD_BEFORE_WILL = R(r"([a-z']+) +will\b")
SELF_OR_FILLER = set(
    "i we ok okay k kk sure yes yeah ya yup yep fine alright cool np bro bhai yaar yr brb haan han ha hn pakka and "
    "then also so definitely surely really just def defo totally promise worry tension".split()
)
HINGLISH_REQUEST = R(
    r"\b(le aana|le ana|le aao|le ao|lete aana|leke aana|lekar aana|bhej dena|bhej dijiye|kar dena|kr dena|"
    r"kar dijiye|de dena|de dijiye|bata dena|bta dena|bata do|bta do|yaad dila dena|dila dena|bhej do|bhj do|"
    r"kar do|kr do|de do|le lo|lao|laana|bhejo|bhejna|batao|btao|karo|kro|dedo|krdo|kardo|bhejdo|"
    r"aajao|aa jao|aaja|aa ja|aa jana|aajana|chale aao|chale aana|aao|jao|chale jao)\b"
)
HINGLISH_PAST = R(r"\b(tha|thi|thhe|hua|hui|huyi|huye|liya|liye|diya|diye|kiya|kiye|gaya|gayi|gaye|chuka|chuki|bachaliya)\b")
ENGLISH_PAST = R(
    r"\b(i|we)\s+((?:just|already|also|finally|actually|literally|totally|even|never|recently)\s+)?"
    r"(\w+ed|did|went|sent|got|saw|made|took|gave|told|bought|brought|paid|ate|came|left|wrote|forgot|thought|"
    r"met|had|was|were|found|kept|put|said|spoke|slept|won|lost|heard|understood|caught|began|drank|drove|felt|"
    r"knew|ran|sat|sold|spent|taught|woke|wore|binged)\b"
)
NOT_PAST_ED = set("need feed proceed exceed succeed bleed seed breed speed heed weed red bed shed embed".split())
DEVANAGARI_PAST = ["था", "थी", "हुआ", "हुई", "लिया", "दिया", "किया", "गया", "गई", "चुका", "चुकी"]
HINGLISH_TODO = R(r"\b\w+(na|ni)\s+(hai|h|he|hain)\b")
HINGLISH_TODO_NOT_MINE = R(
    r"\b(kya|kab|kaise|kahan|kaha|kyu|kyun|kitna|kitne|kaun|tumhe|tumhein|tujhe|aapko|apko|usko|use|unko|inko|isko|tumko|tuje)\b"
)
ENGLISH_OWN_FUTURE = R(r"\b(i'll|ill|i will|i wil|i'm going to|im going to|i am going to|i'm gonna|im gonna|we'll|we will)\b")
ENGLISH_FUTURE = R(r"\b(will|wil|i'll|ill|gonna|going to|i'm going to)\b")
DEVANAGARI_FUTURE = ["ूंगा", "ूंगी", "ूँगा", "ूँगी", "ऊंगा", "ऊंगी", "ऊँगा", "ऊँगी"]
FIRST_PERSON_PRESENT = R(r"\b\w+(ta|ti) (hu|hoon|hun)\b")
TIME_WORD = R(
    r"\b(kal|kl|tomorrow|tom|tmrw|aaj|abhi|parso|baad mein|baad me|baad mai|thodi der|thodi der mein|"
    r"monday|tuesday|wednesday|thursday|friday|saturday|sunday|\d+\s*(min|mins|minute|minutes|ghante|hours?|baje|bje))\b"
)
PART_OF_DAY = R(r"\b(subah|subha|shaam|sham|raat|dopahar|dopehar|morning|evening|tonight)\b")
REPEAT_OR_HINDI_DAY = R(
    r"\b(har|every|each|daily|weekly|monthly|yearly|roz|rozana|somvar|somwar|mangalvar|mangalwar|budhvar|budhwar|"
    r"guruvar|guruwar|shukravar|shukrawar|shanivar|shaniwar|ravivar|raviwar|itwar|itvar)\b"
)
ENGLISH_NEGATION = R(r"\b(don't|dont|do not|didn't|didnt|doesn't|doesnt|haven't|havent|hasn't|hasnt|never)\b")
HABIT = R(r"\b(roz|rozana|daily|har din|har roz|hamesha|humesha|usually|always|generally|aksar|jab bhi|every day|everyday|every morning|every night)\b")
HINGLISH_OWN_FUTURE = R(r"\b\w*(unga|ungi|oonga|oongi|ungaa)\b")
HINDI_NEGATIVE_SIGNALS = ["शायद", "नहीं", "पता नहीं", "सोच", "अगर", "मैंने", "सकते", "सकती"]


def _has_future(n: str) -> bool:
    return bool(HINGLISH_OWN_FUTURE.search(n) or ENGLISH_FUTURE.search(n)) or any(s in n for s in DEVANAGARI_FUTURE)


def _is_past(n: str) -> bool:
    if HINGLISH_PAST.search(n) or any(s in n for s in DEVANAGARI_PAST):
        return True
    m = ENGLISH_PAST.search(n)
    return bool(m and m.group(3) not in NOT_PAST_ED)


def _is_hinglish_todo(n: str) -> bool:
    return bool(HINGLISH_TODO.search(n)) and not HINGLISH_TODO_NOT_MINE.search(n)


def tense_of(n: str) -> str:
    """The explicit tense step. Order matters: a future anywhere wins (\"aaj nahi hua, kal bhej dunga\")."""
    if _has_future(n) or SELF_TODO.search(n) or _is_hinglish_todo(n):
        return "future"
    if HABIT.search(n):
        return "habit"
    if _is_past(n):
        return "past"
    if FIRST_PERSON_PRESENT.search(n):
        return "present"
    return "none"


def _rejections(message: str, n: str) -> list[str]:
    reasons = []
    if message.rstrip().endswith("?"):
        reasons.append("question")
    if any(p.search(n) for p in NEGATIVE_SIGNALS) or any(s in n for s in HINDI_NEGATIVE_SIGNALS):
        reasons.append("question, request, maybe or refusal")
    if any(m.group(1) not in SELF_OR_FILLER for m in WORD_BEFORE_WILL.finditer(message.lower().replace("’", "'"))):
        reasons.append("someone else's promise")
    if HINGLISH_REQUEST.search(n) and not HINGLISH_OWN_FUTURE.search(n):
        reasons.append("request to the other person")
    if _is_past(n) and not _has_future(n) and not FIRST_PERSON_PRESENT.search(n):
        reasons.append("past tense")
    if HABIT.search(n) and not _has_future(n) and not SELF_TODO.search(n) and not _is_hinglish_todo(n):
        reasons.append("habit")
    if ENGLISH_NEGATION.search(n) and not _has_future(n):
        reasons.append("negation")
    return reasons


def _detect_hi_en(message: str, has_time: bool, time_used_default: bool | None) -> Detection:
    n = normalize(message)
    tense = tense_of(n)
    phrases = commitment_phrases()
    reasons = _rejections(message, n)
    if START_QUESTION_OR_UNCERTAINTY.search(n) and not any(
        normalize(p) in n and n.index(normalize(p)) > 20 for p in phrases
    ):
        reasons.append("starts as a question or maybe")
    lang = "hi-IN" if script_language(message) == "hi-IN" else "en-IN"
    if reasons:
        return Detection(False, lang, tense, reasons=reasons)
    clock_or_date = bool(TIME_WORD.search(n)) or time_used_default is False
    any_time = has_time
    phrase = next((p for p in phrases if normalize(p) in n), None)
    if phrase is None and (m := SELF_TODO.search(n)):
        phrase = m.group(0).strip()
    if phrase is None and (m := FIRST_PERSON_PRESENT.search(n)) and clock_or_date:
        phrase = m.group(0)
    if phrase is None and (m := HINGLISH_OWN_FUTURE.search(n)) and (
        clock_or_date or any_time or PART_OF_DAY.search(n) or REPEAT_OR_HINDI_DAY.search(n)
    ):
        phrase = m.group(0)
    if phrase is None and (m := ENGLISH_OWN_FUTURE.search(n)) and (clock_or_date or any_time or REPEAT_OR_HINDI_DAY.search(n)):
        phrase = m.group(0)
    if phrase is None and (m := HINGLISH_TODO.search(n)) and _is_hinglish_todo(n) and (
        clock_or_date or any_time or REPEAT_OR_HINDI_DAY.search(n)
    ):
        phrase = m.group(0)
    if phrase is None:
        return Detection(False, lang, tense, reasons=["no commitment phrase or own-future verb"])
    return Detection(True, lang, tense, matched=phrase)


# ---------------------------------------------------------------------------------------------------------
# Other Gnani languages — starter tense rules (UNVERIFIED, see module docstring)
#
# Each language has two spellings of the same rules: native script (Gnani format=verbatim/codemix) and
# romanized (format=transliterate, the default here, because WhatsApp users type their language in Latin
# letters: "naan naalaikku anuppuven", "ami kal pathabo"). Transliterate returns no detected language, so
# the language comes from the upload's hint.
# ---------------------------------------------------------------------------------------------------------
@dataclass(frozen=True)
class TenseRules:
    future_endings: tuple[str, ...]   # verb endings meaning "I will ..."
    negation: tuple[str, ...]         # whole words, or substrings in native script
    question: tuple[str, ...]         # whole words, or substrings in native script
    first_person: tuple[str, ...] = ()  # required pronoun when the ending is not person-specific
    not_verbs: tuple[str, ...] = ()     # common words that happen to share a future ending
    min_word_len: int = 3


NATIVE_RULES: dict[str, TenseRules] = {
    "mr-IN": TenseRules(("ईन", "ेन", "णार"), ("नाही", "नको"), ("कधी", "काय")),
    "bn-IN": TenseRules(("ব", "বো"), ("না", "নয়"), ("কি", "কখন", "কেন"), not_verbs=("সব", "অভাব", "জবাব")),
    "gu-IN": TenseRules(("ીશ", "ીશું"), ("નહીં", "નથી"), ("શું", "ક્યારે")),
    "pa-IN": TenseRules(("ਾਂਗਾ", "ਾਂਗੀ", "ਵਾਂਗਾ", "ਵਾਂਗੀ"), ("ਨਹੀਂ",), ("ਕੀ", "ਕਦੋਂ")),
    "ta-IN": TenseRules(("வேன்", "பேன்"), ("மாட்டேன்", "இல்லை"), ("எப்போது", "என்ன")),
    "te-IN": TenseRules(("తాను", "స్తా"), ("లేను", "చేయను"), ("ఎప్పుడు", "ఏమి")),
    "kn-IN": TenseRules(("ತ್ತೇನೆ", "ತೀನಿ", "ತೇನೆ"), ("ಇಲ್ಲ", "ಲ್ಲ"), ("ಯಾವಾಗ", "ಏನು")),
    "ml-IN": TenseRules(("ും",), ("ഇല്ല", "ില്ല"), ("എപ്പോൾ", "എന്ത്"), first_person=("ഞാൻ",)),
}

ROMAN_RULES: dict[str, TenseRules] = {
    # karin, pathavin, yein, sangen, karnar
    "mr-IN": TenseRules(("vin", "rin", "ein", "gin", "den", "nar"), ("nahi", "naahi", "nako"), ("kadhi", "kay", "ka")),
    # korbo, pathabo, debo, dekhbo
    "bn-IN": TenseRules(("bo",), ("na", "nai", "noy"), ("ki", "kokhon", "keno")),
    # karish, moklish, aapish
    "gu-IN": TenseRules(("ish", "ishu", "ishun"), ("nahi", "nathi"), ("shu", "kyare"),
                        not_verbs=("wish", "finish", "english", "dish", "fish", "polish", "publish", "british")),
    # karanga, bhejangi, aavanga
    "pa-IN": TenseRules(("anga", "angi", "aanga", "aangi", "unga", "ungi"), ("nahi", "nai"), ("ki", "kado", "kadon")),
    # anuppuven, seiven, kodupen, paakaren? (no: present) — future only
    "ta-IN": TenseRules(("ven", "pen", "paen", "vaen"), ("maaten", "matten", "maatten", "illa"), ("enna", "eppo", "eppadi"),
                        not_verbs=("seven", "even", "given", "heaven", "oven", "driven", "eleven", "open", "happen", "pen")),
    # chestanu, pampistanu, chesta, istha
    "te-IN": TenseRules(("stanu", "sthanu", "sta", "stha", "tanu"), ("ledu", "cheyanu", "kaadu"), ("enti", "eppudu"),
                        not_verbs=("pasta", "costa", "vista", "insta")),
    # madtini, kalistini, maaduttene
    "kn-IN": TenseRules(("tini", "teeni", "thini", "ttene", "tene"), ("illa", "alla"), ("yenu", "yaavaga", "yavaga")),
    # njan ayakkum, cheyyum — "-um" is not person-specific, so "njan" is required
    "ml-IN": TenseRules(("um",), ("illa", "alla"), ("enthu", "eppol"), first_person=("njan", "njaan", "naan"),
                        not_verbs=("album", "forum", "premium", "minimum", "maximum", "sum", "gum", "room", "medium")),
}
SUPPORTED_RULE_LANGUAGES = tuple(NATIVE_RULES)


def _is_latin(text: str) -> bool:
    letters = [c for c in text if c.isalpha()]
    return bool(letters) and sum(c.isascii() for c in letters) / len(letters) > 0.8


def _detect_multilingual(message: str, lang: str) -> Detection:
    roman = _is_latin(message)
    rules = (ROMAN_RULES if roman else NATIVE_RULES)[lang]
    text = unicodedata.normalize("NFC", message).strip()
    lowered = text.lower()
    words = re.findall(r"[^\s.,!?।]+", lowered)

    def has(markers):  # whole words in Latin; substrings in native scripts (\b is unreliable around vowel signs)
        return any((m in words) if roman else (m in text) for m in markers)

    reasons = []
    if text.endswith("?") or has(rules.question):
        reasons.append("question")
    if has(rules.negation):
        reasons.append("negation")
    if rules.first_person and not has(rules.first_person):
        reasons.append("no first-person subject")
    ending = next(
        (e for w in words if w not in rules.not_verbs and len(w) >= rules.min_word_len
         for e in rules.future_endings if w.endswith(e) and len(w) > len(e)),
        None,
    )
    tense = "future" if ending else "none"
    if reasons or not ending:
        return Detection(False, lang, tense, reasons=reasons or ["no first-person future verb"], verified_rules=False)
    return Detection(True, lang, tense, matched=ending, verified_rules=False)


def resolve_language(text: str, hint: str | None) -> str:
    """Native script decides when present. For Latin text (transliterate), the upload's language hint decides;
    with no hint or indic-auto, Latin text goes to the English/Hinglish rules."""
    by_script = script_language(text)
    if by_script != "en-IN":
        if hint == "mr-IN" and by_script == "hi-IN":
            return "mr-IN"  # Marathi and Hindi share Devanagari
        return by_script
    if hint and hint in ROMAN_RULES:
        return hint
    return "en-IN"


def detect(message: str, language_hint: str | None = None, has_time: bool = False,
           time_used_default: bool | None = None) -> Detection:
    lang = resolve_language(message, language_hint)
    if lang in NATIVE_RULES:
        return _detect_multilingual(message, lang)
    return _detect_hi_en(message, has_time, time_used_default)
