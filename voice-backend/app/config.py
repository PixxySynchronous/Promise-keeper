"""All settings come from environment variables. Defaults are conservative: mock STT, small quotas.

Nothing here is a secret except GNANI_API_KEY, which has no default and is never logged or returned.
"""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

# Gnani's REST endpoint rejects audio longer than 30 s, so no setting may raise the cap above it.
GNANI_HARD_MAX_SECONDS = 30.0
SUPPORTED_EXTENSIONS = ("wav", "mp3", "ogg", "flac", "aac", "m4a")
LANGUAGE_HINTS = ("indic-auto", "bn-IN", "en-IN", "gu-IN", "hi-IN", "kn-IN", "ml-IN", "mr-IN", "pa-IN", "ta-IN", "te-IN")


def _int(name: str, default: int) -> int:
    raw = os.environ.get(name)
    return default if raw in (None, "") else int(raw)


def _float(name: str, default: float) -> float:
    raw = os.environ.get(name)
    return default if raw in (None, "") else float(raw)


def _bool(name: str, default: bool) -> bool:
    raw = os.environ.get(name)
    return default if raw in (None, "") else raw.strip().lower() in ("1", "true", "yes", "on")


@dataclass(frozen=True)
class Settings:
    # --- Provider selection -------------------------------------------------------------------
    # mock: deterministic, offline, free. gnani: real API (needs the two switches below). disabled: refuse all.
    stt_provider: str = "mock"
    # Second switch: even with STT_PROVIDER=gnani, nothing is sent unless this is true.
    gnani_live_calls_enabled: bool = False
    gnani_api_key: str = field(default="", repr=False)
    gnani_url: str = "https://api.vachana.ai/stt/v3"
    gnani_model: str = "prisma-v3.0"
    # Default language hint when the upload gives none. With format=transliterate a list is ignored by
    # Gnani, so this is one code or "indic-auto".
    gnani_language: str = "indic-auto"
    # transliterate: everything in Latin letters ("kal paanch baje bhej dunga", "naan naalaikku anuppuven"),
    # how WhatsApp users actually type. Gnani says its accuracy is unmeasured: test with your own audio.
    gnani_format: str = "transliterate"
    gnani_timeout_seconds: float = 20.0
    # Only 429/5xx are retried, and every attempt counts against the budget.
    gnani_max_retries: int = 1

    # --- Upload validation --------------------------------------------------------------------
    max_upload_bytes: int = 2_000_000
    max_audio_seconds: float = 30.0
    min_audio_seconds: float = 0.5

    # --- Rate limits and quotas (per user) ----------------------------------------------------
    user_requests_per_minute: int = 3
    user_daily_requests: int = 20
    user_daily_audio_seconds: float = 300.0
    user_max_pending_jobs: int = 2

    # --- Global credit budget (whole server) --------------------------------------------------
    global_daily_requests: int = 100
    # Lifetime caps on calls that reach Gnani. Set them below your remaining credits
    # (check app.gnani.ai/voice); the credit unit is not documented, so both are tracked.
    budget_total_requests: int = 300
    budget_total_audio_seconds: float = 3600.0
    max_queue_depth: int = 20

    # --- Auth ---------------------------------------------------------------------------------
    # If set, /auth/register requires this code, so strangers cannot farm per-user quotas.
    registration_code: str = field(default="", repr=False)
    session_hours: int = 72
    login_attempts_per_15_min: int = 10

    # --- Runtime --------------------------------------------------------------------------------
    database_path: str = "data/promise_keeper.db"
    timezone: str = "Asia/Kolkata"
    worker_enabled: bool = True
    worker_poll_seconds: float = 1.0
    # Folder with the built web app (web/dist). Empty = serve only the old static demo page.
    web_dist_path: str = ""

    # --- Web Push (reminder notifications) ------------------------------------------------------
    # A VAPID key pair identifies this server to the browsers' push services. Make one with
    # `python -m app.admin vapid`. Both empty = push is off and the app says so.
    vapid_private_key: str = field(default="", repr=False)
    vapid_public_key: str = ""
    # Contact for the push services if something goes wrong: "mailto:you@example.com" or an https URL.
    vapid_subject: str = "mailto:admin@example.com"
    push_timeout_seconds: float = 10.0
    scheduler_interval_seconds: float = 60.0

    @property
    def effective_max_audio_seconds(self) -> float:
        return min(self.max_audio_seconds, GNANI_HARD_MAX_SECONDS)

    @property
    def live_gnani(self) -> bool:
        return self.stt_provider == "gnani" and self.gnani_live_calls_enabled and bool(self.gnani_api_key)

    # --- Optional LLM second opinion (Gnani Evon v3.3, self-hosted) ---------------------------------
    # none (default) | mock (offline, repeats the rules) | openai_compat (your own server at EVON_BASE_URL)
    llm_provider: str = "none"
    evon_base_url: str = ""
    evon_api_key: str = field(default="", repr=False)  # only if your server asks for one
    evon_model: str = "gnani/gnani-evon-v3.3-30B-A3B"
    llm_timeout_seconds: float = 15.0
    llm_daily_requests: int = 200
    llm_max_tokens: int = 200
    # Below this the model's yes/no is ignored and the rules' answer stands.
    llm_min_confidence: float = 0.6

    @property
    def push_enabled(self) -> bool:
        return bool(self.vapid_private_key and self.vapid_public_key)

    def public_limits(self) -> dict:
        """Limits safe to show users (no secrets)."""
        return {
            "provider": self.stt_provider,
            "max_upload_bytes": self.max_upload_bytes,
            "max_audio_seconds": self.effective_max_audio_seconds,
            "min_audio_seconds": self.min_audio_seconds,
            "requests_per_minute": self.user_requests_per_minute,
            "daily_requests": self.user_daily_requests,
            "daily_audio_seconds": self.user_daily_audio_seconds,
            "max_pending_jobs": self.user_max_pending_jobs,
            "formats": list(SUPPORTED_EXTENSIONS),
        }


def load_settings() -> Settings:
    provider = os.environ.get("STT_PROVIDER", "mock").strip().lower()
    if provider not in ("mock", "gnani", "disabled"):
        raise ValueError(f"STT_PROVIDER must be mock, gnani or disabled, not {provider!r}")
    llm = os.environ.get("LLM_PROVIDER", "none").strip().lower() or "none"
    if llm not in ("none", "mock", "openai_compat"):
        raise ValueError(f"LLM_PROVIDER must be none, mock or openai_compat, not {llm!r}")
    db = os.environ.get("DATABASE_PATH") or str(Path(__file__).resolve().parent.parent / "data" / "promise_keeper.db")
    web = os.environ.get("WEB_DIST_PATH") or str(Path(__file__).resolve().parents[2] / "web" / "dist")
    return Settings(
        stt_provider=provider,
        gnani_live_calls_enabled=_bool("GNANI_LIVE_CALLS_ENABLED", False),
        gnani_api_key=os.environ.get("GNANI_API_KEY", ""),
        gnani_url=os.environ.get("GNANI_URL", Settings.gnani_url),
        gnani_model=os.environ.get("GNANI_MODEL", Settings.gnani_model),
        gnani_language=os.environ.get("GNANI_LANGUAGE", Settings.gnani_language),
        gnani_format=os.environ.get("GNANI_FORMAT", Settings.gnani_format),
        gnani_timeout_seconds=_float("GNANI_TIMEOUT_SECONDS", Settings.gnani_timeout_seconds),
        gnani_max_retries=max(0, min(_int("GNANI_MAX_RETRIES", Settings.gnani_max_retries), 3)),
        max_upload_bytes=_int("MAX_UPLOAD_BYTES", Settings.max_upload_bytes),
        max_audio_seconds=_float("MAX_AUDIO_SECONDS", Settings.max_audio_seconds),
        min_audio_seconds=_float("MIN_AUDIO_SECONDS", Settings.min_audio_seconds),
        user_requests_per_minute=_int("USER_REQUESTS_PER_MINUTE", Settings.user_requests_per_minute),
        user_daily_requests=_int("USER_DAILY_REQUESTS", Settings.user_daily_requests),
        user_daily_audio_seconds=_float("USER_DAILY_AUDIO_SECONDS", Settings.user_daily_audio_seconds),
        user_max_pending_jobs=_int("USER_MAX_PENDING_JOBS", Settings.user_max_pending_jobs),
        global_daily_requests=_int("GLOBAL_DAILY_REQUESTS", Settings.global_daily_requests),
        budget_total_requests=_int("BUDGET_TOTAL_REQUESTS", Settings.budget_total_requests),
        budget_total_audio_seconds=_float("BUDGET_TOTAL_AUDIO_SECONDS", Settings.budget_total_audio_seconds),
        max_queue_depth=_int("MAX_QUEUE_DEPTH", Settings.max_queue_depth),
        registration_code=os.environ.get("REGISTRATION_CODE", ""),
        session_hours=_int("SESSION_HOURS", Settings.session_hours),
        login_attempts_per_15_min=_int("LOGIN_ATTEMPTS_PER_15_MIN", Settings.login_attempts_per_15_min),
        database_path=db,
        timezone=os.environ.get("APP_TIMEZONE", Settings.timezone),
        worker_enabled=_bool("WORKER_ENABLED", True),
        worker_poll_seconds=_float("WORKER_POLL_SECONDS", Settings.worker_poll_seconds),
        web_dist_path=web,
        llm_provider=llm,
        evon_base_url=os.environ.get("EVON_BASE_URL", "").strip(),
        evon_api_key=os.environ.get("EVON_API_KEY", "").strip(),
        evon_model=os.environ.get("EVON_MODEL", "").strip() or Settings.evon_model,
        llm_timeout_seconds=_float("LLM_TIMEOUT_SECONDS", Settings.llm_timeout_seconds),
        llm_daily_requests=_int("LLM_DAILY_REQUESTS", Settings.llm_daily_requests),
        llm_max_tokens=_int("LLM_MAX_TOKENS", Settings.llm_max_tokens),
        llm_min_confidence=_float("LLM_MIN_CONFIDENCE", Settings.llm_min_confidence),
        vapid_private_key=os.environ.get("VAPID_PRIVATE_KEY", "").strip(),
        vapid_public_key=os.environ.get("VAPID_PUBLIC_KEY", "").strip(),
        vapid_subject=os.environ.get("VAPID_SUBJECT", "").strip() or Settings.vapid_subject,
    )
