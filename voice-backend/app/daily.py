"""The once-a-day job (08:00 server time): pre-build each active user's weekly report and delete old jobs.

It is a scheduler task, so it is called every minute with the current time and decides for itself whether
today's run is due. The date of the last run is kept in the flags table, so a restart never repeats it and a
server that was asleep at 08:00 catches up when it wakes.
"""
from __future__ import annotations

import logging
from datetime import datetime, time, timedelta

from . import reports
from .auth import iso
from .db import Database

log = logging.getLogger("promise_keeper.daily")
RUN_AT = time(8, 0)
JOB_RETENTION = timedelta(days=30)
FLAG = "daily_job_last_run"


def active_users(db: Database, now: datetime) -> list[int]:
    """Users who can still log in without a password (a live session): the ones likely to open the report."""
    rows = db.all("SELECT DISTINCT user_id FROM sessions WHERE expires_at > ? ORDER BY user_id", (iso(now),))
    return [r["user_id"] for r in rows]


def delete_old_jobs(db: Database, now: datetime) -> int:
    """Finished voice-note rows older than 30 days. Reminders made from them stay (their job_id becomes NULL),
    and so does the cost log (usage_events)."""
    cur = db.execute("DELETE FROM jobs WHERE status IN ('done', 'failed') AND created_at < ?",
                     (iso(now - JOB_RETENTION),))
    return cur.rowcount


def run_if_due(db: Database, now: datetime) -> bool:
    """Runs today's job if it is 08:00 or later and it hasn't run today. Returns whether it ran."""
    today = now.date().isoformat()
    if now.time() < RUN_AT:
        return False
    with db.transaction():
        # Claim the day first, so two overlapping calls can't both run it.
        last = db.one("SELECT value FROM flags WHERE name = ?", (FLAG,))
        if last and last["value"] >= today:
            return False
        db.execute("INSERT INTO flags (name, value, updated_at) VALUES (?, ?, ?) "
                   "ON CONFLICT(name) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at",
                   (FLAG, today, iso(now)))
    deleted = delete_old_jobs(db, now)  # before the reports, so they are built from what remains
    built = 0
    for user_id in active_users(db, now):
        try:
            reports.weekly_report(db, user_id, now)
            built += 1
        except Exception:  # one broken account must not stop the others
            log.exception("weekly report for user %s failed", user_id)
    log.info("daily job: %s reports ready, %s old jobs deleted", built, deleted)
    return True
