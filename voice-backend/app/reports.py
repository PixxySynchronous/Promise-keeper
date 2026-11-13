"""Weekly PDF report: the reminders due in the next 7 days and the voice notes from the last 7.

Rendering is the slow part, so the finished PDF is cached per user per day (report_cache). The cache row also
stores a fingerprint of the data it was built from: if the user's reminders or notes changed since, the PDF
is rebuilt, so a cached report is never stale.
"""
from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path

from .auth import iso
from .db import Database

FONT_DIR = Path(__file__).resolve().parent / "fonts"
BASE_FONT = "NotoSans"
MAX_ROWS = 100  # per section; keeps the PDF small whatever the account holds
WEEK = timedelta(days=7)


@dataclass(frozen=True)
class ReportData:
    email: str
    day: str                 # local date the report is for, YYYY-MM-DD
    upcoming: list[dict]     # open reminders due within 7 days: {when: datetime, text, guessed}
    overdue: list[dict]      # open reminders already past due
    notes: list[dict]        # voice notes from the last 7 days: {when: datetime, status, text, reminder}
    done_last_week: int

    def fingerprint(self) -> str:
        raw = json.dumps([self.email, self.day, self.upcoming, self.overdue, self.notes, self.done_last_week],
                         default=str, ensure_ascii=False, sort_keys=True)
        return hashlib.sha256(raw.encode()).hexdigest()


def _parse(value: str, zone) -> datetime | None:
    try:
        when = datetime.fromisoformat(value)
    except (TypeError, ValueError):
        return None
    return (when.replace(tzinfo=zone) if when.tzinfo is None else when).astimezone(zone)


def gather(db: Database, user_id: int, now: datetime) -> ReportData:
    """Everything the report shows, for this user only. Every query filters on user_id."""
    zone = now.tzinfo
    user = db.one("SELECT email FROM users WHERE id = ?", (user_id,))
    upcoming, overdue = [], []
    for row in db.all("SELECT text, due_at, used_default_time FROM reminders WHERE user_id = ? AND done_at IS NULL "
                      "ORDER BY due_at", (user_id,)):
        when = _parse(row["due_at"], zone)
        if when is None:
            continue
        item = {"when": when, "text": row["text"], "guessed": bool(row["used_default_time"])}
        if when < now:
            overdue.append(item)
        elif when < now + WEEK:
            upcoming.append(item)
    since = iso(now - WEEK)  # jobs.created_at and reminders.done_at are UTC ISO strings, so text order = time order
    notes = []
    for row in db.all(
            "SELECT j.created_at, j.status, j.transcript, "
            "EXISTS (SELECT 1 FROM reminders r WHERE r.job_id = j.id AND r.user_id = j.user_id) AS reminder "
            "FROM jobs j WHERE j.user_id = ? AND j.created_at >= ? ORDER BY j.created_at DESC LIMIT ?",
            (user_id, since, MAX_ROWS)):
        when = _parse(row["created_at"], timezone.utc)
        notes.append({"when": when.astimezone(zone) if when else None, "status": row["status"],
                      "text": row["transcript"] or "", "reminder": bool(row["reminder"])})
    done = db.one("SELECT COUNT(*) AS n FROM reminders WHERE user_id = ? AND done_at >= ?", (user_id, since))["n"]
    return ReportData(user["email"] if user else "", now.date().isoformat(), upcoming[:MAX_ROWS],
                      overdue[-MAX_ROWS:], notes, done)


def _fmt(when: datetime | None) -> str:
    return when.strftime("%a %d %b, %H:%M") if when else "unknown time"


def render(data: ReportData) -> bytes:
    """ReportData -> PDF bytes. Noto fonts with text shaping, so Indic scripts join and stack correctly."""
    from fpdf import FPDF  # imported here: only this function needs it

    pdf = FPDF(format="A4")
    pdf.set_title("Promise Keeper weekly report")
    pdf.set_creator("Promise Keeper")
    # A fixed date keeps the bytes identical for identical data, which the cache and the tests rely on.
    pdf.set_creation_date(datetime.fromisoformat(data.day).replace(tzinfo=timezone.utc))
    fallbacks = []
    for path in sorted(FONT_DIR.glob("*-Regular.ttf")):
        name = path.stem.removesuffix("-Regular")
        pdf.add_font(name, fname=str(path))
        if name != BASE_FONT:
            fallbacks.append(name)
    pdf.set_font(BASE_FONT, size=11)
    pdf.set_fallback_fonts(fallbacks)  # each character uses the first font that has it
    pdf.set_text_shaping(True)
    pdf.set_auto_page_break(True, margin=15)
    pdf.add_page()

    def line(text: str, size: float = 11, gap: float = 0, grey: bool = False) -> None:
        pdf.set_font(BASE_FONT, size=size)
        pdf.set_text_color(*(110, 110, 110) if grey else (20, 20, 20))
        pdf.multi_cell(0, size * 0.52, text, new_x="LMARGIN", new_y="NEXT")
        if gap:
            pdf.ln(gap)

    def section(title: str, rows: list[str], empty: str) -> None:
        pdf.ln(3)
        line(title, size=14, gap=1)
        if not rows:
            line(empty, grey=True)
        for row in rows:
            line(row, gap=1.2)

    line("Promise Keeper: weekly report", size=18, gap=1)
    line(f"{data.email}  ·  made on {data.day}", grey=True)
    line(f"{len(data.upcoming)} due in the next 7 days  ·  {len(data.overdue)} overdue  ·  "
         f"{data.done_last_week} done in the last 7 days  ·  {len(data.notes)} voice notes", grey=True)

    section("Next 7 days", [f"{_fmt(r['when'])}{' (time guessed)' if r['guessed'] else ''}  -  {r['text']}"
                            for r in data.upcoming], "Nothing due this week.")
    if data.overdue:
        section("Overdue", [f"{_fmt(r['when'])}  -  {r['text']}" for r in data.overdue], "")
    notes = []
    for n in data.notes:
        if n["status"] == "failed":
            outcome, text = "could not be transcribed", ""
        elif n["status"] != "done":
            outcome, text = "still being transcribed", ""
        else:
            outcome, text = ("reminder made" if n["reminder"] else "no promise found"), n["text"]
        notes.append(f"{_fmt(n['when'])}  -  {outcome}" + (f"\n{text}" if text else ""))
    section("Voice notes from the last 7 days", notes, "No voice notes this week.")
    return bytes(pdf.output())


def weekly_report(db: Database, user_id: int, now: datetime) -> tuple[bytes, bool]:
    """(pdf, from_cache). One cached PDF per user; reused while the day and the underlying data are unchanged."""
    data = gather(db, user_id, now)
    fingerprint = data.fingerprint()
    row = db.one("SELECT pdf FROM report_cache WHERE user_id = ? AND day = ? AND fingerprint = ?",
                 (user_id, data.day, fingerprint))
    if row:
        return bytes(row["pdf"]), True
    pdf = render(data)
    db.execute("INSERT INTO report_cache (user_id, day, fingerprint, pdf, created_at) VALUES (?, ?, ?, ?, ?) "
               "ON CONFLICT(user_id) DO UPDATE SET day = excluded.day, fingerprint = excluded.fingerprint, "
               "pdf = excluded.pdf, created_at = excluded.created_at",
               (user_id, data.day, fingerprint, pdf, iso(now)))
    return pdf, False
