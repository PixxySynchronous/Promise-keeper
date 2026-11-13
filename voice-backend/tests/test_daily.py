"""The 08:00 daily job: reports pre-built for active users, old jobs deleted, once per day even across restarts."""
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

from app import daily, reports
from app.auth import iso
from tests.conftest import login
from tests.test_api import CountingMock, drain, upload

IST = ZoneInfo("Asia/Kolkata")


def at(hour, minute=0, day=14):
    return datetime(2026, 11, day, hour, minute, tzinfo=IST)


def add_job(db, job_id, created, status="done", user_id=1):
    db.execute("INSERT INTO jobs (id, user_id, status, filename, extension, audio_sha256, audio_seconds, language, "
               "transcript, created_at) VALUES (?, ?, ?, 'a.wav', 'wav', 'x', 1.0, 'hi-IN', 't', ?)",
               (job_id, user_id, status, iso(created)))


def test_runs_once_a_day_at_or_after_eight(make_client):
    client, app = make_client()
    login(client)
    db, tick = app.state.db, app.state.scheduler.tick
    cached = lambda: db.one("SELECT COUNT(*) AS n FROM report_cache")["n"]  # noqa: E731
    tick(at(7, 59))
    assert cached() == 0
    tick(at(8, 0))
    assert cached() == 1
    db.execute("DELETE FROM report_cache")
    for when in (at(8, 1), at(12), at(23, 59), at(7, 59, day=15)):
        tick(when)
    assert cached() == 0  # already ran on the 14th; not yet 08:00 on the 15th
    tick(at(8, 0, day=15))
    assert cached() == 1


def test_a_server_that_was_asleep_at_eight_catches_up_and_a_restart_does_not_repeat(make_client, tmp_path):
    path = str(tmp_path / "shared.db")
    client, app = make_client(database_path=path)
    login(client)
    assert daily.run_if_due(app.state.db, at(15, 30)) is True  # woke up in the afternoon
    client.__exit__(None, None, None)
    client2, app2 = make_client(database_path=path)  # restart
    assert daily.run_if_due(app2.state.db, at(15, 31)) is False
    assert daily.run_if_due(app2.state.db, at(9, 0, day=15)) is True


def test_the_prebuilt_report_is_what_the_user_then_downloads(make_client, wav, monkeypatch):
    client, app = make_client(provider=CountingMock())
    h = login(client)
    upload(client, h, wav())
    drain(app)
    now = datetime.now(IST).replace(hour=9, minute=0)
    assert daily.run_if_due(app.state.db, now) is True
    monkeypatch.setattr(reports, "render", lambda data: (_ for _ in ()).throw(AssertionError("should be cached")))
    r = client.get("/reports/weekly.pdf", headers=h)
    assert r.status_code == 200 and r.headers["x-report-cache"] == "hit" and r.content.startswith(b"%PDF")


def test_only_users_with_a_live_session_get_a_report(make_client):
    client, app = make_client()
    a = login(client)
    login(client, "b@example.com")
    login(client, "c@example.com")
    db = app.state.db
    client.post("/auth/logout", headers=a)                                         # A logged out
    db.execute("UPDATE sessions SET expires_at = ? WHERE user_id = 2", (iso(at(7)),))  # B's session expired
    daily.run_if_due(db, at(8))
    assert [r["user_id"] for r in db.all("SELECT user_id FROM report_cache")] == [3]


def test_old_finished_jobs_are_deleted_but_reminders_and_the_cost_log_stay(make_client):
    client, app = make_client()
    login(client)
    db, now = app.state.db, at(8)
    add_job(db, "old-done", now - timedelta(days=31))
    add_job(db, "old-failed", now - timedelta(days=45), status="failed")
    add_job(db, "old-queued", now - timedelta(days=40), status="queued")  # never delete unfinished work
    add_job(db, "edge", now - timedelta(days=29, hours=23))
    add_job(db, "new", now - timedelta(days=1))
    db.execute("INSERT INTO reminders (user_id, job_id, text, due_at, used_default_time, created_at) "
               "VALUES (1, 'old-done', 'keep me', '2026-12-01T10:00+05:30', 0, ?)", (iso(now),))
    db.execute("INSERT INTO usage_events (job_id, user_id, provider, audio_seconds, billable, outcome, created_at) "
               "VALUES ('old-done', 1, 'gnani', 3.0, 1, 'ok', ?)", (iso(now - timedelta(days=31)),))
    assert daily.run_if_due(db, now) is True
    assert sorted(r["id"] for r in db.all("SELECT id FROM jobs")) == ["edge", "new", "old-queued"]
    rem = db.one("SELECT text, job_id FROM reminders")
    assert rem["text"] == "keep me" and rem["job_id"] is None
    assert db.one("SELECT COUNT(*) AS n FROM usage_events WHERE billable = 1")["n"] == 1  # budget maths unchanged


def test_one_broken_account_does_not_stop_the_rest(make_client, monkeypatch):
    client, app = make_client()
    login(client)
    login(client, "b@example.com")
    real = reports.weekly_report

    def flaky(db, user_id, now):
        if user_id == 1:
            raise RuntimeError("boom")
        return real(db, user_id, now)

    monkeypatch.setattr(reports, "weekly_report", flaky)
    app.state.scheduler.tick(at(8))  # must not raise
    assert [r["user_id"] for r in app.state.db.all("SELECT user_id FROM report_cache")] == [2]
