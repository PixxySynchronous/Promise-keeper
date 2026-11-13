"""Background transcription: upload returns 202 at once; one worker thread drains the queue.

One worker == at most one provider call in flight, which is also Gnani's documented account limit.
"""
from __future__ import annotations

import hashlib
import logging
import threading
import time
from datetime import datetime
from zoneinfo import ZoneInfo

from . import detect, llm as llm_mod, timeparse
from .auth import iso, utcnow
from .config import Settings
from .db import Database
from .guard import Refusal, check_budget, check_provider_available, pause_provider
from .stt import SttError, SttProvider, Transcript

log = logging.getLogger("promise_keeper.jobs")
_sleep = time.sleep  # patched in tests


def make_provider(settings: Settings) -> SttProvider | None:
    if settings.stt_provider == "mock":
        from .stt.mock import MockProvider

        return MockProvider()
    if settings.stt_provider == "gnani" and settings.live_gnani:
        from .stt.gnani import GnaniProvider

        return GnaniProvider(settings)
    return None  # disabled, or gnani without both switches: the guard refuses uploads


def cache_key(audio_sha256: str, provider: SttProvider, language: str) -> str:
    return hashlib.sha256(f"{audio_sha256}|{provider.cache_identity()}|{language}".encode()).hexdigest()


def recover_interrupted(db: Database) -> int:
    """Jobs left 'processing' by a crash may already have been billed; fail them rather than pay twice."""
    cur = db.execute(
        "UPDATE jobs SET status = 'failed', error = 'INTERRUPTED', audio = NULL, finished_at = ? WHERE status = 'processing'",
        (iso(utcnow()),),
    )
    return cur.rowcount


def _claim(db: Database):
    with db.transaction():
        row = db.one("SELECT * FROM jobs WHERE status = 'queued' ORDER BY created_at LIMIT 1")
        if row:
            db.execute("UPDATE jobs SET status = 'processing' WHERE id = ?", (row["id"],))
        return row


def _log_usage(db, job, provider, outcome, billable, upstream_id=None, latency_ms=None):
    db.execute(
        "INSERT INTO usage_events (job_id, user_id, provider, audio_seconds, billable, outcome, upstream_request_id, "
        "latency_ms, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        (job["id"], job["user_id"], provider.name, job["audio_seconds"], int(billable), outcome, upstream_id,
         latency_ms, iso(utcnow())),
    )


def _finish(db, job, status, *, transcript=None, language=None, cached=False, error=None):
    db.execute(
        "UPDATE jobs SET status = ?, transcript = ?, detected_language = ?, cached = ?, error = ?, audio = NULL, "
        "finished_at = ? WHERE id = ?",
        (status, transcript, language, int(cached), error, iso(utcnow()), job["id"]),
    )


def _transcribe_with_retries(db, settings, provider, job) -> Transcript:
    attempts = 0
    while True:
        # Re-check right before spending: budget may have shrunk or the provider been paused meanwhile.
        check_provider_available(db, settings)
        check_budget(db, settings, 1, job["audio_seconds"])
        started = time.monotonic()
        try:
            result = provider.transcribe(job["audio"], job["filename"], job["id"], job["language"])
            _log_usage(db, job, provider, "ok", provider.billable, result.upstream_request_id,
                       int((time.monotonic() - started) * 1000))
            return result
        except SttError as err:
            _log_usage(db, job, provider, f"error:{err.code}", provider.billable and err.reached_upstream,
                       latency_ms=int((time.monotonic() - started) * 1000))
            if err.pause_provider:
                pause_provider(db, f"{err.code} from {provider.name}")
            if not err.retryable or attempts >= settings.gnani_max_retries:
                raise
            attempts += 1
            _sleep(min(2.0 * attempts, 8.0))


def _make_reminder(db, settings, job, transcript: str, language: str | None, llm=None) -> None:
    now = datetime.now(ZoneInfo(settings.timezone))
    parsed = timeparse.parse(transcript, now)
    found = detect.detect(transcript, language, has_time=parsed is not None,
                          time_used_default=parsed.used_default if parsed else None)
    is_promise = found.is_promise
    # The optional model is asked only where the rules are weakest: unverified languages, or a promise with
    # no time we could parse. Any failure gives None and the rules' answer stands.
    if llm is not None and (not found.verified_rules or (is_promise and parsed is None)):
        verdict = llm_mod.consult(db, settings, llm, job, transcript, language)
        if verdict is not None:
            db.execute("UPDATE jobs SET llm = ? WHERE id = ?", (verdict.model_dump_json(), job["id"]))
            if not found.verified_rules and verdict.confidence >= settings.llm_min_confidence:
                is_promise = verdict.is_promise  # checked rules are never overruled, only starter rules
            if is_promise and parsed is None and verdict.time_text:
                # The model only points at the words; our own parser turns them into a time.
                parsed = timeparse.parse(verdict.time_text, now)
    if not is_promise:
        return
    due = parsed or timeparse.due_time(transcript, now)
    db.execute(
        "INSERT INTO reminders (user_id, job_id, text, matched_phrase, due_at, time_text, used_default_time, created_at) "
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        (job["user_id"], job["id"], transcript, found.matched, due.when.isoformat(timespec="minutes"),
         due.matched, int(due.used_default), iso(utcnow())),
    )


def process_next(db: Database, settings: Settings, provider: SttProvider | None, llm=None) -> str | None:
    """Process one queued job. Returns its id, or None if the queue is empty. Safe to call from tests."""
    job = _claim(db)
    if job is None:
        return None
    if provider is None:
        _finish(db, job, "failed", error="STT_UNAVAILABLE")
        return job["id"]
    key = cache_key(job["audio_sha256"], provider, job["language"])
    try:
        hit = db.one("SELECT transcript, detected_language FROM transcript_cache WHERE cache_key = ?", (key,))
        if hit:
            _log_usage(db, job, provider, "cached", False)
            transcript, language, cached = hit["transcript"], hit["detected_language"], True
        else:
            result = _transcribe_with_retries(db, settings, provider, job)
            transcript, language, cached = result.text, result.detected_language, False
            if transcript and not result.truncated:
                db.execute(
                    "INSERT OR REPLACE INTO transcript_cache (cache_key, transcript, detected_language, created_at) "
                    "VALUES (?, ?, ?, ?)", (key, transcript, language, iso(utcnow())),
                )
        if transcript:
            _make_reminder(db, settings, job, transcript, language or job["language"], llm)
        _finish(db, job, "done", transcript=transcript, language=language, cached=cached)
    except Refusal as ref:
        _finish(db, job, "failed", error=ref.code)
    except SttError as err:
        log.warning("job %s failed: %s", job["id"], err.code)  # never log audio, transcript or keys
        _finish(db, job, "failed", error=err.code)
    except Exception:
        log.exception("job %s crashed", job["id"])
        _finish(db, job, "failed", error="INTERNAL_ERROR")
    return job["id"]


class Worker:
    def __init__(self, db: Database, settings: Settings, provider: SttProvider | None, llm=None):
        self.db, self.settings, self.provider, self.llm = db, settings, provider, llm
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._run, name="stt-worker", daemon=True)

    def start(self) -> None:
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        self._thread.join(timeout=5)

    def _run(self) -> None:
        while not self._stop.is_set():
            try:
                if process_next(self.db, self.settings, self.provider, self.llm) is None:
                    self._stop.wait(self.settings.worker_poll_seconds)
            except Exception:
                log.exception("worker loop error")
                self._stop.wait(self.settings.worker_poll_seconds)
