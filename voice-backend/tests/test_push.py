"""Web Push at due time: subscribe/unsubscribe, one push per reminder (even after a restart), fake sender only."""
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

import pytest

from app import push
from app.admin import new_vapid_keys
from app.config import Settings
from app.db import Database
from app.scheduler import Scheduler
from tests.conftest import login
from tests.test_api import CountingMock, drain, upload

IST = ZoneInfo("Asia/Kolkata")
ENDPOINT = "https://fcm.googleapis.com/fcm/send/abc123"
SUB = {"endpoint": ENDPOINT, "keys": {"p256dh": "B" + "a" * 86, "auth": "c" * 22}}
KEYS = dict(vapid_private_key="test-private", vapid_public_key="test-public")


class FakeSender:
    def __init__(self, error=None):
        self.sent, self.error = [], error

    def send(self, subscription, payload):
        if self.error:
            raise self.error
        self.sent.append((subscription["endpoint"], payload))


@pytest.fixture
def ready(make_client, wav):
    """A user with one reminder (due tomorrow 17:00) and one push subscription."""
    sender = FakeSender()
    client, app = make_client(provider=CountingMock(), push_sender=sender, **KEYS)
    h = login(client)
    upload(client, h, wav())
    drain(app)
    assert client.post("/push/subscribe", headers=h, json=SUB).status_code == 201
    rem = client.get("/reminders", headers=h).json()[0]
    return client, app, h, sender, rem, datetime.fromisoformat(rem["due_at"])


def test_config_says_whether_push_is_on_and_never_leaks_the_private_key(make_client):
    off, _ = make_client()
    assert off.get("/push/config").json() == {"enabled": False, "public_key": None}
    on, _ = make_client(**KEYS)
    body = on.get("/push/config")
    assert body.json() == {"enabled": True, "public_key": "test-public"}
    assert "test-private" not in body.text and "test-private" not in repr(Settings(**KEYS))
    half, _ = make_client(vapid_public_key="only-public")
    assert half.get("/push/config").json()["enabled"] is False


def test_subscribe_needs_login_keys_and_a_configured_server(make_client):
    client, _ = make_client(**KEYS)
    assert client.post("/push/subscribe", json=SUB).status_code == 401
    h = login(client)
    assert client.post("/push/subscribe", headers=h, json={"endpoint": ENDPOINT}).status_code == 422
    assert client.post("/push/subscribe", headers=h, json={**SUB, "keys": {"p256dh": "x y z" * 10, "auth": "c" * 22}}).status_code == 422
    unset, _ = make_client()
    r = unset.post("/push/subscribe", headers=login(unset), json=SUB)
    assert r.status_code == 503 and r.json()["detail"]["code"] == "PUSH_NOT_CONFIGURED"


@pytest.mark.parametrize("endpoint", [
    "http://fcm.googleapis.com/fcm/send/abc",            # not https
    "https://127.0.0.1/internal",                        # the server itself
    "https://169.254.169.254/latest/meta-data",          # cloud metadata
    "https://evil.example.com/fcm.googleapis.com",       # push host only in the path
    "https://fcm.googleapis.com.evil.example/send",      # push host as a prefix
    "https://notfcm.googleapis.com.attacker.io/x",
    "https://user:pw@fcm.googleapis.com/fcm/send/abc",   # credentials in the URL
    "https://fcm.googleapis.com:8443/fcm/send/abc",      # odd port
    "https://[::1]/x",
    "not a url at all",
])
def test_only_real_push_services_are_accepted(make_client, endpoint):
    client, app = make_client(**KEYS)
    r = client.post("/push/subscribe", headers=login(client), json={**SUB, "endpoint": endpoint})
    assert r.status_code == 422 and r.json()["detail"]["code"] == "INVALID_PUSH_ENDPOINT"
    assert app.state.db.one("SELECT COUNT(*) AS n FROM push_subscriptions")["n"] == 0


@pytest.mark.parametrize("endpoint", [
    ENDPOINT,
    "https://updates.push.services.mozilla.com/wpush/v2/abc",
    "https://wns2-par02p.notify.windows.com/w/?token=abc",
    "https://web.push.apple.com/abc",
])
def test_known_push_services(endpoint):
    assert push.valid_endpoint(endpoint)


def test_subscribing_twice_keeps_one_row_and_old_devices_are_dropped(make_client):
    client, app = make_client(**KEYS)
    h = login(client)
    for _ in range(2):
        assert client.post("/push/subscribe", headers=h, json=SUB).status_code == 201
    count = lambda: app.state.db.one("SELECT COUNT(*) AS n FROM push_subscriptions")["n"]  # noqa: E731
    assert count() == 1
    for i in range(push.MAX_SUBSCRIPTIONS_PER_USER + 2):
        client.post("/push/subscribe", headers=h, json={**SUB, "endpoint": f"{ENDPOINT}-{i}"})
    assert count() == push.MAX_SUBSCRIPTIONS_PER_USER
    newest = app.state.db.one("SELECT endpoint FROM push_subscriptions ORDER BY id DESC LIMIT 1")["endpoint"]
    assert newest.endswith(f"-{push.MAX_SUBSCRIPTIONS_PER_USER + 1}")


def test_a_shared_browser_moves_to_whoever_subscribed_last(make_client):
    client, app = make_client(**KEYS)
    a, b = login(client), login(client, "b@example.com")
    client.post("/push/subscribe", headers=a, json=SUB)
    client.post("/push/subscribe", headers=b, json=SUB)
    rows = app.state.db.all("SELECT user_id FROM push_subscriptions")
    assert len(rows) == 1 and rows[0]["user_id"] == 2


def test_unsubscribe_only_removes_your_own(ready):
    client, app, h, *_ = ready
    other = login(client, "b@example.com")
    assert client.request("DELETE", "/push/subscribe", headers=other, json={"endpoint": ENDPOINT}).status_code == 204
    assert app.state.db.one("SELECT COUNT(*) AS n FROM push_subscriptions")["n"] == 1
    assert client.request("DELETE", "/push/subscribe", json={"endpoint": ENDPOINT}).status_code == 401
    assert client.request("DELETE", "/push/subscribe", headers=h, json={"endpoint": ENDPOINT}).status_code == 204
    assert app.state.db.one("SELECT COUNT(*) AS n FROM push_subscriptions")["n"] == 0
    assert client.request("DELETE", "/push/subscribe", headers=h, json={"endpoint": ENDPOINT}).status_code == 204  # again: fine


def test_nothing_is_sent_before_the_due_time_and_exactly_one_push_after(ready):
    client, app, h, sender, rem, due = ready
    app.state.scheduler.tick(due - timedelta(minutes=1))
    assert sender.sent == []
    for minutes in (0, 1, 2, 60):
        app.state.scheduler.tick(due + timedelta(minutes=minutes))
    assert len(sender.sent) == 1
    endpoint, payload = sender.sent[0]
    assert endpoint == ENDPOINT
    import json
    body = json.loads(payload)
    assert body == {"title": "Promise due", "body": rem["text"], "reminder_id": rem["id"], "url": f"/?reminder={rem['id']}"}


def test_no_duplicate_after_a_restart(make_client, wav, tmp_path):
    path = str(tmp_path / "shared.db")
    first = FakeSender()
    client, app = make_client(provider=CountingMock(), push_sender=first, database_path=path, **KEYS)
    h = login(client)
    upload(client, h, wav())
    drain(app)
    client.post("/push/subscribe", headers=h, json=SUB)
    due = datetime.fromisoformat(client.get("/reminders", headers=h).json()[0]["due_at"])
    app.state.scheduler.tick(due)
    assert len(first.sent) == 1
    client.__exit__(None, None, None)  # server stops

    second = FakeSender()
    client2, app2 = make_client(provider=CountingMock(), push_sender=second, database_path=path, **KEYS)
    app2.state.scheduler.tick(due + timedelta(minutes=5))
    assert second.sent == []


def test_a_crash_between_claim_and_send_never_sends_twice(ready):
    """The reminder is marked before sending. If the process dies in between, the push is lost, not repeated."""
    client, app, h, sender, rem, due = ready
    assert len(push._claim_due(app.state.db, due)) == 1  # claimed, then "crash": nothing sent
    app.state.scheduler.tick(due + timedelta(minutes=1))
    assert sender.sent == []


def test_done_deleted_and_stale_reminders_are_not_pushed(ready):
    client, app, h, sender, rem, due = ready
    client.patch(f"/reminders/{rem['id']}", headers=h, json={"done": True})
    app.state.scheduler.tick(due)
    assert sender.sent == []
    client.patch(f"/reminders/{rem['id']}", headers=h, json={"done": False})
    app.state.scheduler.tick(due + push.MAX_LATE + timedelta(minutes=1))  # server was asleep too long
    assert sender.sent == []
    app.state.scheduler.tick(due + push.MAX_LATE + timedelta(minutes=2))
    assert sender.sent == [] and app.state.db.one("SELECT pushed_at FROM reminders")["pushed_at"]


def test_moving_a_reminder_lets_it_notify_again_at_the_new_time(ready):
    client, app, h, sender, rem, due = ready
    app.state.scheduler.tick(due)
    assert len(sender.sent) == 1
    later = (datetime.now(IST) + timedelta(days=5)).replace(second=0, microsecond=0)
    client.patch(f"/reminders/{rem['id']}", headers=h, json={"due_at": later.replace(tzinfo=None).isoformat(timespec="minutes")})
    app.state.scheduler.tick(later - timedelta(minutes=1))
    assert len(sender.sent) == 1
    app.state.scheduler.tick(later)
    assert len(sender.sent) == 2


def test_subscribing_late_still_gets_a_recent_reminder_but_only_your_own(make_client, wav):
    sender = FakeSender()
    client, app = make_client(provider=CountingMock(), push_sender=sender, **KEYS)
    a, b = login(client), login(client, "b@example.com")
    upload(client, a, wav())
    drain(app)
    due = datetime.fromisoformat(client.get("/reminders", headers=a).json()[0]["due_at"])
    client.post("/push/subscribe", headers=b, json={**SUB, "endpoint": ENDPOINT + "-b"})
    app.state.scheduler.tick(due)  # A has no device yet; B must not get A's reminder
    assert sender.sent == []
    client.post("/push/subscribe", headers=a, json=SUB)
    app.state.scheduler.tick(due + timedelta(minutes=30))
    assert [e for e, _ in sender.sent] == [ENDPOINT]


def test_dead_subscriptions_are_removed_and_other_devices_still_get_it(ready):
    client, app, h, sender, rem, due = ready
    client.post("/push/subscribe", headers=h, json={**SUB, "endpoint": ENDPOINT + "-phone"})

    class OneGone(FakeSender):
        def send(self, subscription, payload):
            if subscription["endpoint"] == ENDPOINT:
                raise push.PushGone()
            super().send(subscription, payload)

    app.state.push_sender = gone = OneGone()
    app.state.scheduler.tick(due)
    assert [e for e, _ in gone.sent] == [ENDPOINT + "-phone"]
    assert [r["endpoint"] for r in app.state.db.all("SELECT endpoint FROM push_subscriptions")] == [ENDPOINT + "-phone"]


def test_a_passing_failure_is_retried_next_minute_without_duplicates(ready):
    client, app, h, sender, rem, due = ready
    app.state.push_sender = FakeSender(error=push.PushFailed("push service answered 503"))
    app.state.scheduler.tick(due)
    assert app.state.db.one("SELECT pushed_at FROM reminders")["pushed_at"] is None  # will retry
    app.state.push_sender = sender
    app.state.scheduler.tick(due + timedelta(minutes=1))
    app.state.scheduler.tick(due + timedelta(minutes=2))
    assert len(sender.sent) == 1


def test_an_unexpected_sender_crash_does_not_stop_the_scheduler(ready):
    client, app, h, sender, rem, due = ready
    app.state.push_sender = FakeSender(error=RuntimeError("boom"))
    app.state.scheduler.tick(due)  # must not raise
    ran = []
    s = Scheduler("Asia/Kolkata")
    s.add("bad", lambda now: 1 / 0)
    s.add("good", ran.append)
    s.tick(due)
    assert ran == [due]


def test_push_is_a_no_op_when_not_configured(make_client, wav):
    client, app = make_client(provider=CountingMock())
    h = login(client)
    upload(client, h, wav())
    drain(app)
    assert app.state.push_sender is None
    app.state.scheduler.tick(datetime.now(IST) + timedelta(days=30))
    assert app.state.db.one("SELECT pushed_at FROM reminders")["pushed_at"] is None


def test_long_text_is_cut_in_the_notification():
    payload = push._payload({"id": 1, "text": "क" * 500})
    import json
    assert len(json.loads(payload)["body"]) == push.BODY_CHARS


def test_real_sender_passes_keys_to_pywebpush_and_maps_errors(monkeypatch):
    import pywebpush

    private, public = new_vapid_keys()
    assert len(private) == 43 and len(public) == 87
    settings = Settings(vapid_private_key=private, vapid_public_key=public, vapid_subject="mailto:ops@example.com")
    calls = []
    monkeypatch.setattr(pywebpush, "webpush", lambda **kw: calls.append(kw))
    push.WebPushSender(settings).send(SUB, "{}")
    assert calls[0]["subscription_info"] == SUB and calls[0]["vapid_private_key"] == private
    assert calls[0]["vapid_claims"] == {"sub": "mailto:ops@example.com"} and calls[0]["timeout"] == 10.0

    class Resp:
        def __init__(self, status):
            self.status_code = status

    def fail(status):
        def raiser(**kw):
            raise pywebpush.WebPushException(f"Push failed: {status} secret-endpoint-echo", response=Resp(status))
        return raiser

    monkeypatch.setattr(pywebpush, "webpush", fail(410))
    with pytest.raises(push.PushGone):
        push.WebPushSender(settings).send(SUB, "{}")
    monkeypatch.setattr(pywebpush, "webpush", fail(500))
    with pytest.raises(push.PushFailed) as err:
        push.WebPushSender(settings).send(SUB, "{}")
    assert "secret-endpoint-echo" not in str(err.value)
    with pytest.raises(RuntimeError):
        push.WebPushSender(Settings())


def test_real_sender_encrypts_and_signs_offline(monkeypatch):
    """The whole pywebpush path with a real key pair, stopped at the HTTP call (no network in tests)."""
    import base64

    import requests
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import ec

    private, public = new_vapid_keys()
    browser = ec.generate_private_key(ec.SECP256R1()).public_key().public_bytes(
        serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint)
    b64 = lambda raw: base64.urlsafe_b64encode(raw).rstrip(b"=").decode()  # noqa: E731
    sub = {"endpoint": ENDPOINT, "keys": {"p256dh": b64(browser), "auth": b64(b"0123456789abcdef")}}
    seen = {}

    class Resp:
        status_code, reason, text, headers = 201, "Created", "", {}

    def fake_post(self, url, data=None, headers=None, timeout=None, **kw):
        seen.update(url=url, data=data, headers=headers, timeout=timeout)
        return Resp()

    monkeypatch.setattr(requests.Session, "post", fake_post, raising=False)
    monkeypatch.setattr(requests, "post", lambda url, **kw: fake_post(None, url, **kw))
    settings = Settings(vapid_private_key=private, vapid_public_key=public)
    push.WebPushSender(settings).send(sub, '{"title":"Promise due","body":"kal report"}')
    assert seen["url"] == ENDPOINT and b"kal report" not in seen["data"]  # encrypted on the wire
    auth_header = {k.lower(): v for k, v in seen["headers"].items()}["authorization"]
    assert auth_header.startswith("vapid ") and public in auth_header


def test_old_database_gets_the_pushed_column(tmp_path):
    import sqlite3
    path = str(tmp_path / "old.db")
    conn = sqlite3.connect(path)
    conn.execute("CREATE TABLE reminders (id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL, job_id TEXT, "
                 "text TEXT NOT NULL, matched_phrase TEXT, due_at TEXT NOT NULL, time_text TEXT, "
                 "used_default_time INTEGER NOT NULL, created_at TEXT NOT NULL, done_at TEXT)")
    conn.commit()
    conn.close()
    db = Database(path)
    assert {"done_at", "pushed_at"} <= {r["name"] for r in db.all("PRAGMA table_info(reminders)")}
    db.close()
