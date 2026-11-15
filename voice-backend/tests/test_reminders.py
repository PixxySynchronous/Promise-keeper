"""Editing reminders: move the time, tick off, reopen. Each user can only touch their own."""
import sqlite3
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

import pytest

from app.db import Database
from tests.conftest import login
from tests.test_api import CountingMock, drain, upload

IST = ZoneInfo("Asia/Kolkata")


@pytest.fixture
def one_reminder(make_client, wav):
    client, app = make_client(provider=CountingMock())
    h = login(client)
    upload(client, h, wav())
    drain(app)
    return client, h, client.get("/reminders", headers=h).json()[0]


def local(dt: datetime) -> str:
    return dt.astimezone(IST).replace(tzinfo=None).isoformat(timespec="minutes")


def test_move_reminder_to_another_time(one_reminder):
    client, h, rem = one_reminder
    assert rem["done_at"] is None and rem["used_default_time"] == 0
    target = datetime.now(IST) + timedelta(days=3, hours=2)
    r = client.patch(f"/reminders/{rem['id']}", headers=h, json={"due_at": local(target)})
    assert r.status_code == 200
    body = r.json()
    assert body["due_at"] == target.replace(second=0, microsecond=0).isoformat(timespec="minutes")
    assert body["due_at"].endswith("+05:30") and body["text"] == rem["text"]
    assert client.get("/reminders", headers=h).json()[0]["due_at"] == body["due_at"]


def test_time_with_another_offset_is_converted_to_server_time(one_reminder):
    client, h, rem = one_reminder
    target = (datetime.now(IST) + timedelta(days=1)).replace(second=0, microsecond=0)
    as_utc = target.astimezone(ZoneInfo("UTC")).isoformat(timespec="minutes")
    r = client.patch(f"/reminders/{rem['id']}", headers=h, json={"due_at": as_utc})
    assert r.json()["due_at"] == target.isoformat(timespec="minutes")


def test_editing_clears_the_guessed_time_flag(make_client, wav):
    client, app = make_client(provider=CountingMock("main report bhej dunga"))  # no time said: default used
    h = login(client)
    upload(client, h, wav())
    drain(app)
    rem = client.get("/reminders", headers=h).json()[0]
    assert rem["used_default_time"] == 1
    r = client.patch(f"/reminders/{rem['id']}", headers=h,
                     json={"due_at": local(datetime.now(IST) + timedelta(hours=5))})
    assert r.json()["used_default_time"] == 0


def test_tick_off_and_reopen(one_reminder):
    client, h, rem = one_reminder
    done = client.patch(f"/reminders/{rem['id']}", headers=h, json={"done": True}).json()
    assert done["done_at"] and done["due_at"] == rem["due_at"]
    assert client.get("/reminders", headers=h).json()[0]["done_at"] == done["done_at"]
    reopened = client.patch(f"/reminders/{rem['id']}", headers=h, json={"done": False}).json()
    assert reopened["done_at"] is None


@pytest.mark.parametrize("body, code", [
    ({}, "NOTHING_TO_CHANGE"),
    ({"due_at": "tomorrow at five"}, "INVALID_DUE_AT"),
    ({"due_at": ""}, "INVALID_DUE_AT"),
    ({"due_at": "2020-01-01T10:00"}, "DUE_IN_PAST"),
    ({"due_at": "9999-01-01T10:00"}, "DUE_TOO_FAR"),
])
def test_bad_edits_are_refused_and_change_nothing(one_reminder, body, code):
    client, h, rem = one_reminder
    r = client.patch(f"/reminders/{rem['id']}", headers=h, json=body)
    assert r.status_code == 422 and r.json()["detail"]["code"] == code
    assert client.get("/reminders", headers=h).json()[0] == rem


def test_wrong_types_and_missing_token(one_reminder):
    client, h, rem = one_reminder
    assert client.patch(f"/reminders/{rem['id']}", headers=h, json={"done": "maybe"}).status_code == 422
    assert client.patch(f"/reminders/{rem['id']}", json={"done": True}).status_code == 401
    assert client.patch("/reminders/999999", headers=h, json={"done": True}).status_code == 404


def test_cannot_edit_someone_elses_reminder(one_reminder):
    client, h, rem = one_reminder
    other = login(client, "b@example.com")
    r = client.patch(f"/reminders/{rem['id']}", headers=other, json={"done": True})
    assert r.status_code == 404
    assert client.get("/reminders", headers=h).json()[0]["done_at"] is None


def test_old_database_gets_the_done_column(tmp_path):
    path = str(tmp_path / "old.db")
    conn = sqlite3.connect(path)
    conn.execute("CREATE TABLE reminders (id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL, job_id TEXT, "
                 "text TEXT NOT NULL, matched_phrase TEXT, due_at TEXT NOT NULL, time_text TEXT, "
                 "used_default_time INTEGER NOT NULL, created_at TEXT NOT NULL)")
    conn.execute("INSERT INTO reminders VALUES (1, 1, NULL, 'x', NULL, '2026-11-14T17:00+05:30', NULL, 0, 'now')")
    conn.commit()
    conn.close()
    for _ in range(2):  # opening twice must be safe
        db = Database(path)
        row = db.one("SELECT text, done_at FROM reminders WHERE id = 1")
        assert row["text"] == "x" and row["done_at"] is None
        db.close()
