"""SQLite persistence (stdlib only). Data survives restarts; queued jobs resume after a restart."""
from __future__ import annotations

import sqlite3
import threading
from pathlib import Path

SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY,
    email TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS sessions (
    token_hash TEXT PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at TEXT NOT NULL
);
-- One row per uploaded voice note. audio is cleared once the job finishes (privacy + disk).
CREATE TABLE IF NOT EXISTS jobs (
    id TEXT PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status TEXT NOT NULL,              -- queued | processing | done | failed
    filename TEXT NOT NULL,
    extension TEXT NOT NULL,
    audio BLOB,
    audio_sha256 TEXT NOT NULL,
    audio_seconds REAL NOT NULL,
    language TEXT NOT NULL,
    transcript TEXT,
    detected_language TEXT,
    cached INTEGER NOT NULL DEFAULT 0,
    error TEXT,
    created_at TEXT NOT NULL,
    finished_at TEXT,
    llm TEXT                           -- the LLM's verdict as JSON, if it was asked about this note
);
CREATE INDEX IF NOT EXISTS jobs_user_created ON jobs(user_id, created_at);
CREATE INDEX IF NOT EXISTS jobs_status ON jobs(status, created_at);
-- Cache: same audio + same provider settings -> reuse the transcript, spend no credits.
CREATE TABLE IF NOT EXISTS transcript_cache (
    cache_key TEXT PRIMARY KEY,
    transcript TEXT NOT NULL,
    detected_language TEXT,
    created_at TEXT NOT NULL
);
-- Cost log: one row per provider attempt. billable=1 means the request reached Gnani.
CREATE TABLE IF NOT EXISTS usage_events (
    id INTEGER PRIMARY KEY,
    job_id TEXT,
    user_id INTEGER,
    provider TEXT NOT NULL,
    audio_seconds REAL NOT NULL,
    billable INTEGER NOT NULL,
    outcome TEXT NOT NULL,             -- ok | cached | error:<code>
    upstream_request_id TEXT,
    latency_ms INTEGER,
    created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS usage_created ON usage_events(created_at);
CREATE TABLE IF NOT EXISTS reminders (
    id INTEGER PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    job_id TEXT REFERENCES jobs(id) ON DELETE SET NULL,
    text TEXT NOT NULL,
    matched_phrase TEXT,
    due_at TEXT NOT NULL,
    time_text TEXT,
    used_default_time INTEGER NOT NULL,
    created_at TEXT NOT NULL,
    done_at TEXT,                      -- set when the user ticks the reminder off; NULL = still open
    pushed_at TEXT                     -- set when the due-time notification was claimed for sending (once only)
);
-- One row per browser that asked for notifications. keys are the browser's public encryption keys.
CREATE TABLE IF NOT EXISTS push_subscriptions (
    id INTEGER PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    endpoint TEXT NOT NULL UNIQUE,
    p256dh TEXT NOT NULL,
    auth TEXT NOT NULL,
    created_at TEXT NOT NULL
);
-- LLM second opinions: same transcript + model -> stored verdict, no second call.
CREATE TABLE IF NOT EXISTS llm_cache (
    cache_key TEXT PRIMARY KEY,
    verdict TEXT NOT NULL,
    created_at TEXT NOT NULL
);
-- One row per LLM attempt. outcome: ok | cached | skipped:daily_cap | error:<code>
CREATE TABLE IF NOT EXISTS llm_events (
    id INTEGER PRIMARY KEY,
    job_id TEXT,
    user_id INTEGER,
    provider TEXT NOT NULL,
    outcome TEXT NOT NULL,
    prompt_tokens INTEGER,
    completion_tokens INTEGER,
    latency_ms INTEGER,
    created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS llm_created ON llm_events(created_at);
-- The latest weekly report per user. fingerprint = hash of the data it shows; a mismatch means rebuild.
CREATE TABLE IF NOT EXISTS report_cache (
    user_id INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    day TEXT NOT NULL,
    fingerprint TEXT NOT NULL,
    pdf BLOB NOT NULL,
    created_at TEXT NOT NULL
);
-- Server-wide switches, e.g. provider_paused after Gnani says 401/402.
CREATE TABLE IF NOT EXISTS flags (
    name TEXT PRIMARY KEY,
    value TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
"""


class Database:
    """One connection guarded by a lock: simple and safe for a single-process demo server."""

    def __init__(self, path: str):
        if path != ":memory:":
            Path(path).parent.mkdir(parents=True, exist_ok=True)
        self._conn = sqlite3.connect(path, check_same_thread=False, isolation_level=None)
        self._conn.row_factory = sqlite3.Row
        self._conn.execute("PRAGMA foreign_keys = ON")
        self.lock = threading.RLock()
        with self.lock:
            self._conn.executescript(SCHEMA)
            self._migrate()

    def _migrate(self) -> None:
        """Add columns that older database files don't have yet (CREATE TABLE IF NOT EXISTS won't)."""
        columns = {row["name"] for row in self._conn.execute("PRAGMA table_info(reminders)")}
        if "llm" not in {row["name"] for row in self._conn.execute("PRAGMA table_info(jobs)")}:
            self._conn.execute("ALTER TABLE jobs ADD COLUMN llm TEXT")
        for column in ("done_at", "pushed_at"):
            if column not in columns:
                self._conn.execute(f"ALTER TABLE reminders ADD COLUMN {column} TEXT")

    def execute(self, sql: str, params: tuple | dict = ()) -> sqlite3.Cursor:
        with self.lock:
            return self._conn.execute(sql, params)

    def one(self, sql: str, params: tuple | dict = ()) -> sqlite3.Row | None:
        with self.lock:
            return self._conn.execute(sql, params).fetchone()

    def all(self, sql: str, params: tuple | dict = ()) -> list[sqlite3.Row]:
        with self.lock:
            return self._conn.execute(sql, params).fetchall()

    def transaction(self):
        """Use as `with db.transaction():` for check-then-insert sequences (quota reservation)."""
        return _Tx(self)

    def close(self) -> None:
        with self.lock:
            self._conn.close()


class _Tx:
    def __init__(self, db: Database):
        self.db = db

    def __enter__(self):
        self.db.lock.acquire()
        self.db._conn.execute("BEGIN IMMEDIATE")
        return self.db

    def __exit__(self, exc_type, exc, tb):
        try:
            self.db._conn.execute("ROLLBACK" if exc_type else "COMMIT")
        finally:
            self.db.lock.release()
        return False
