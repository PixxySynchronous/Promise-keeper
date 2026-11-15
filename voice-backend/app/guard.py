"""Credit guard: every check that stands between an upload and a paid Gnani call.

Checked at the API boundary (enqueue) so users get an honest 429/503 immediately, and again in the worker
right before the provider call, so nothing queued earlier can overspend a budget lowered since.
Quotas count *reserved* work (queued jobs), not just finished calls, so a burst cannot slip past a limit.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

from .auth import iso, utcnow
from .config import Settings
from .db import Database

PAUSE_FLAG = "provider_paused"


@dataclass
class Refusal(Exception):
    status: int        # 429 = this user should slow down; 503 = the service is out of capacity/budget
    code: str
    message: str
    retry_after: int | None = None


def local_day_start_utc(settings: Settings, now: datetime | None = None) -> datetime:
    now = now or utcnow()
    tz = ZoneInfo(settings.timezone)
    local = now.astimezone(tz)
    return local.replace(hour=0, minute=0, second=0, microsecond=0).astimezone(timezone.utc)


def seconds_until_local_midnight(settings: Settings, now: datetime | None = None) -> int:
    now = now or utcnow()
    return int((local_day_start_utc(settings, now) + timedelta(days=1) - now).total_seconds()) + 1


def provider_paused(db: Database) -> str | None:
    row = db.one("SELECT value FROM flags WHERE name = ?", (PAUSE_FLAG,))
    return row["value"] if row else None


def pause_provider(db: Database, reason: str) -> None:
    db.execute(
        "INSERT INTO flags (name, value, updated_at) VALUES (?, ?, ?) "
        "ON CONFLICT(name) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at",
        (PAUSE_FLAG, reason, iso(utcnow())),
    )


def resume_provider(db: Database) -> None:
    db.execute("DELETE FROM flags WHERE name = ?", (PAUSE_FLAG,))


def billable_totals(db: Database) -> tuple[int, float]:
    row = db.one("SELECT COUNT(*) AS n, COALESCE(SUM(audio_seconds), 0) AS s FROM usage_events WHERE billable = 1")
    return int(row["n"]), float(row["s"])


def check_provider_available(db: Database, settings: Settings) -> None:
    if settings.stt_provider == "disabled":
        raise Refusal(503, "STT_DISABLED", "Speech-to-text is switched off on this server.", 3600)
    if settings.stt_provider == "gnani" and not settings.live_gnani:
        raise Refusal(503, "GNANI_NOT_ENABLED",
                      "Gnani is selected but live calls are not enabled (GNANI_LIVE_CALLS_ENABLED / GNANI_API_KEY).", 3600)
    if reason := provider_paused(db):
        raise Refusal(503, "PROVIDER_PAUSED", f"Speech-to-text is paused: {reason}", 3600)


def check_budget(db: Database, settings: Settings, extra_requests: int, extra_seconds: float) -> None:
    """Lifetime budget for calls that reach Gnani. Mock calls are free and skip this."""
    if settings.stt_provider != "gnani":
        return
    used_n, used_s = billable_totals(db)
    if used_n + extra_requests > settings.budget_total_requests:
        raise Refusal(503, "BUDGET_EXHAUSTED", "The server's speech-to-text request budget is used up.", 3600)
    if used_s + extra_seconds > settings.budget_total_audio_seconds:
        raise Refusal(503, "BUDGET_EXHAUSTED", "The server's speech-to-text audio budget is used up.", 3600)


def check_enqueue(db: Database, settings: Settings, user_id: int, audio_seconds: float) -> None:
    """Raise Refusal if this upload must not be queued. Call inside db.transaction() with the insert."""
    check_provider_available(db, settings)
    now = utcnow()
    minute_ago = iso(now - timedelta(seconds=60))
    recent = db.all(
        "SELECT created_at FROM jobs WHERE user_id = ? AND created_at > ? ORDER BY created_at",
        (user_id, minute_ago),
    )
    if len(recent) >= settings.user_requests_per_minute:
        oldest = datetime.fromisoformat(recent[0]["created_at"])
        wait = max(1, int(60 - (now - oldest).total_seconds()) + 1)
        raise Refusal(429, "RATE_LIMITED", f"Max {settings.user_requests_per_minute} voice notes per minute.", wait)

    pending = db.one(
        "SELECT COUNT(*) AS n FROM jobs WHERE user_id = ? AND status IN ('queued', 'processing')", (user_id,)
    )["n"]
    if pending >= settings.user_max_pending_jobs:
        raise Refusal(429, "TOO_MANY_PENDING", "Wait for your earlier voice notes to finish.", 10)

    day_start = iso(local_day_start_utc(settings, now))
    until_midnight = seconds_until_local_midnight(settings, now)
    mine = db.one(
        "SELECT COUNT(*) AS n, COALESCE(SUM(audio_seconds), 0) AS s FROM jobs WHERE user_id = ? AND created_at >= ?",
        (user_id, day_start),
    )
    if mine["n"] >= settings.user_daily_requests:
        raise Refusal(429, "DAILY_QUOTA", f"Daily limit of {settings.user_daily_requests} voice notes reached.", until_midnight)
    if mine["s"] + audio_seconds > settings.user_daily_audio_seconds:
        raise Refusal(429, "DAILY_AUDIO_QUOTA",
                      f"Daily limit of {settings.user_daily_audio_seconds:g} s of audio reached.", until_midnight)

    everyone = db.one("SELECT COUNT(*) AS n FROM jobs WHERE created_at >= ?", (day_start,))["n"]
    if everyone >= settings.global_daily_requests:
        raise Refusal(503, "SERVER_DAILY_LIMIT", "The server's daily voice-note limit is reached.", until_midnight)

    queued = db.one(
        "SELECT COUNT(*) AS n, COALESCE(SUM(audio_seconds), 0) AS s FROM jobs WHERE status IN ('queued', 'processing')"
    )
    if queued["n"] >= settings.max_queue_depth:
        raise Refusal(503, "QUEUE_FULL", "Too many voice notes are waiting. Try again shortly.", 30)
    # Reserve budget for everything already queued plus this one (worst case: none hit the cache).
    check_budget(db, settings, queued["n"] + 1, queued["s"] + audio_seconds)


def usage_summary(db: Database, settings: Settings, user_id: int | None = None) -> dict:
    day_start = iso(local_day_start_utc(settings))
    where, params = ("AND user_id = ?", (day_start, user_id)) if user_id is not None else ("", (day_start,))
    today = db.one(
        f"SELECT COUNT(*) AS n, COALESCE(SUM(audio_seconds), 0) AS s FROM jobs WHERE created_at >= ? {where}", params
    )
    out = {"today_requests": today["n"], "today_audio_seconds": round(today["s"], 2), "limits": settings.public_limits()}
    if user_id is None:
        used_n, used_s = billable_totals(db)
        cached = db.one("SELECT COUNT(*) AS n FROM usage_events WHERE outcome = 'cached'")["n"]
        out.update({
            "billable_requests_total": used_n,
            "billable_audio_seconds_total": round(used_s, 2),
            "budget_total_requests": settings.budget_total_requests,
            "budget_total_audio_seconds": settings.budget_total_audio_seconds,
            "cache_hits_total": cached,
            "provider_paused": provider_paused(db),
        })
    return out
