"""Email + password accounts, opaque bearer tokens. Passwords use scrypt; tokens are stored only as SHA-256."""
from __future__ import annotations

import hashlib
import hmac
import secrets
import threading
import time
from collections import defaultdict, deque
from datetime import datetime, timedelta, timezone

from .db import Database

_SCRYPT = dict(n=2**14, r=8, p=1, dklen=32)


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


def iso(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).isoformat(timespec="seconds")


def hash_password(password: str) -> str:
    salt = secrets.token_bytes(16)
    digest = hashlib.scrypt(password.encode(), salt=salt, **_SCRYPT)
    return f"scrypt${salt.hex()}${digest.hex()}"


def verify_password(password: str, stored: str) -> bool:
    try:
        _, salt_hex, digest_hex = stored.split("$")
    except ValueError:
        return False
    digest = hashlib.scrypt(password.encode(), salt=bytes.fromhex(salt_hex), **_SCRYPT)
    return hmac.compare_digest(digest.hex(), digest_hex)


def _token_hash(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def create_user(db: Database, email: str, password: str) -> int | None:
    """Returns the new user id, or None if the email is taken."""
    existing = db.one("SELECT id FROM users WHERE email = ?", (email,))
    if existing:
        return None
    cur = db.execute(
        "INSERT INTO users (email, password_hash, created_at) VALUES (?, ?, ?)",
        (email, hash_password(password), iso(utcnow())),
    )
    return cur.lastrowid


def login(db: Database, email: str, password: str, session_hours: int) -> str | None:
    row = db.one("SELECT id, password_hash FROM users WHERE email = ?", (email,))
    if not row or not verify_password(password, row["password_hash"]):
        return None
    token = secrets.token_urlsafe(32)
    db.execute(
        "INSERT INTO sessions (token_hash, user_id, expires_at) VALUES (?, ?, ?)",
        (_token_hash(token), row["id"], iso(utcnow() + timedelta(hours=session_hours))),
    )
    return token


def user_for_token(db: Database, token: str) -> int | None:
    row = db.one("SELECT user_id, expires_at FROM sessions WHERE token_hash = ?", (_token_hash(token),))
    if not row or row["expires_at"] < iso(utcnow()):
        return None
    return row["user_id"]


def logout(db: Database, token: str) -> None:
    db.execute("DELETE FROM sessions WHERE token_hash = ?", (_token_hash(token),))


class LoginLimiter:
    """In-memory sliding window per email, against password guessing. Resets on restart, which is acceptable here."""

    def __init__(self, limit: int, window_seconds: int = 15 * 60):
        self.limit = limit
        self.window = window_seconds
        self._hits: dict[str, deque[float]] = defaultdict(deque)
        self._lock = threading.Lock()

    def allow(self, key: str) -> tuple[bool, int]:
        now = time.monotonic()
        with self._lock:
            hits = self._hits[key]
            while hits and now - hits[0] > self.window:
                hits.popleft()
            if len(hits) >= self.limit:
                return False, int(self.window - (now - hits[0])) + 1
            hits.append(now)
            return True, 0
