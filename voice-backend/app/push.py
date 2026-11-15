"""Web Push: a notification on the user's device when a reminder falls due.

The scheduler calls send_due() once a minute. Each reminder is claimed in the database (pushed_at) BEFORE
anything is sent, so a crash or restart can never send it twice. Notification text is end-to-end encrypted
for the browser by the Web Push protocol; it is never logged here.
"""
from __future__ import annotations

import json
import logging
from datetime import datetime, timedelta
from typing import Protocol
from urllib.parse import urlsplit

from .auth import iso, utcnow
from .config import Settings
from .db import Database

log = logging.getLogger("promise_keeper.push")

# The server POSTs to whatever endpoint a browser hands us, so only real push services are accepted.
# Without this list a user could make the server call any URL (server-side request forgery).
PUSH_HOST_SUFFIXES = ("fcm.googleapis.com", ".push.services.mozilla.com", ".notify.windows.com", ".push.apple.com")
MAX_SUBSCRIPTIONS_PER_USER = 5
# A reminder this late is still worth a push (the server may have been asleep). Older ones are skipped, so
# switching notifications on never dumps a pile of stale reminders on the user.
MAX_LATE = timedelta(hours=12)
BATCH = 50
BODY_CHARS = 140


class PushGone(Exception):
    """The push service says this subscription no longer exists (404/410): delete it."""


class PushFailed(Exception):
    """Could not deliver right now (network, 5xx, bad VAPID key)."""


class Sender(Protocol):
    def send(self, subscription: dict, payload: str) -> None: ...


class WebPushSender:
    """The real sender. pywebpush is imported here so the app starts even where push is not configured."""

    def __init__(self, settings: Settings):
        if not settings.push_enabled:
            raise RuntimeError("VAPID keys are not set")
        self._key, self._subject = settings.vapid_private_key, settings.vapid_subject
        self._timeout = settings.push_timeout_seconds

    def send(self, subscription: dict, payload: str) -> None:
        from pywebpush import WebPushException, webpush

        try:
            # A new claims dict each time: webpush() writes the audience and expiry into it.
            webpush(subscription_info=subscription, data=payload, vapid_private_key=self._key,
                    vapid_claims={"sub": self._subject}, ttl=3600, timeout=self._timeout)
        except WebPushException as exc:
            status = getattr(exc.response, "status_code", None)
            if status in (404, 410):
                raise PushGone() from None
            raise PushFailed(f"push service answered {status}") from None  # no body: it may echo the endpoint
        except Exception as exc:  # timeouts, DNS, TLS
            raise PushFailed(type(exc).__name__) from None


def make_sender(settings: Settings) -> Sender | None:
    return WebPushSender(settings) if settings.push_enabled else None


def valid_endpoint(endpoint: str) -> bool:
    try:
        url = urlsplit(endpoint)
    except ValueError:
        return False
    host = (url.hostname or "").lower()
    if url.scheme != "https" or url.username or url.password or url.port not in (None, 443):
        return False
    return any(host == s or host.endswith(s if s.startswith(".") else "." + s) for s in PUSH_HOST_SUFFIXES)


def save_subscription(db: Database, user_id: int, endpoint: str, p256dh: str, auth_key: str) -> None:
    with db.transaction():
        # An endpoint belongs to one browser profile. If another account logs in there, it takes it over.
        db.execute("DELETE FROM push_subscriptions WHERE endpoint = ?", (endpoint,))
        db.execute("INSERT INTO push_subscriptions (user_id, endpoint, p256dh, auth, created_at) VALUES (?, ?, ?, ?, ?)",
                   (user_id, endpoint, p256dh, auth_key, iso(utcnow())))
        db.execute(
            "DELETE FROM push_subscriptions WHERE user_id = ? AND id NOT IN "
            "(SELECT id FROM push_subscriptions WHERE user_id = ? ORDER BY id DESC LIMIT ?)",
            (user_id, user_id, MAX_SUBSCRIPTIONS_PER_USER))


def delete_subscription(db: Database, user_id: int, endpoint: str) -> bool:
    return db.execute("DELETE FROM push_subscriptions WHERE user_id = ? AND endpoint = ?", (user_id, endpoint)).rowcount > 0


def _payload(reminder) -> str:
    text = reminder["text"]
    body = text if len(text) <= BODY_CHARS else text[: BODY_CHARS - 1] + "…"
    return json.dumps({"title": "Promise due", "body": body, "reminder_id": reminder["id"],
                       "url": f"/?reminder={reminder['id']}"}, ensure_ascii=False)


def _claim_due(db: Database, now: datetime) -> list:
    """Marks due reminders as pushed and returns the ones to send. Too-old ones are marked but not returned."""
    claimed = []
    with db.transaction():
        rows = db.all("SELECT id, user_id, text, due_at FROM reminders WHERE pushed_at IS NULL AND done_at IS NULL "
                      "ORDER BY due_at")
        for row in rows:
            try:
                due = datetime.fromisoformat(row["due_at"])
            except ValueError:
                continue
            if due.tzinfo is None:
                due = due.replace(tzinfo=now.tzinfo)
            if due > now:
                continue
            fresh = now - due <= MAX_LATE
            has_subscription = db.one("SELECT 1 FROM push_subscriptions WHERE user_id = ? LIMIT 1", (row["user_id"],))
            if fresh and not has_subscription:
                continue  # may still subscribe within the window
            db.execute("UPDATE reminders SET pushed_at = ? WHERE id = ? AND pushed_at IS NULL", (iso(now), row["id"]))
            if fresh:
                claimed.append(row)
                if len(claimed) >= BATCH:
                    break
    return claimed


def send_due(db: Database, sender: Sender | None, now: datetime) -> int:
    """Sends a push for every reminder that is due and not yet pushed. Returns how many reminders were delivered
    to at least one device. Safe to call any number of times: a reminder is only ever claimed once."""
    if sender is None:
        return 0
    delivered = 0
    for reminder in _claim_due(db, now):
        payload = _payload(reminder)
        ok = failed = 0
        for sub in db.all("SELECT id, endpoint, p256dh, auth FROM push_subscriptions WHERE user_id = ?",
                          (reminder["user_id"],)):
            try:
                sender.send({"endpoint": sub["endpoint"], "keys": {"p256dh": sub["p256dh"], "auth": sub["auth"]}}, payload)
                ok += 1
            except PushGone:
                db.execute("DELETE FROM push_subscriptions WHERE id = ?", (sub["id"],))
            except PushFailed as exc:
                failed += 1
                log.warning("push for reminder %s failed: %s", reminder["id"], exc)
            except Exception:
                failed += 1
                log.exception("push for reminder %s failed", reminder["id"])
        if ok:
            delivered += 1
        elif failed:
            # Nobody got it and the failure may pass (network, push service down): let the next tick retry.
            # Never done after a success, so a retry cannot duplicate a delivered push.
            db.execute("UPDATE reminders SET pushed_at = NULL WHERE id = ?", (reminder["id"],))
    return delivered
