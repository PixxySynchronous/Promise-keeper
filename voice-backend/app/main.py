"""HTTP API. Run: uvicorn app.main:app  (from voice-backend/)."""
from __future__ import annotations

import hashlib
import json
import logging
import mimetypes
import re
import uuid
from contextlib import asynccontextmanager
from datetime import datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, Request, UploadFile
from fastapi.responses import FileResponse, JSONResponse, Response
from pydantic import BaseModel, Field

from . import auth, daily, detect, llm as llm_mod, push, reports, timeparse
from .audio import AudioRejected, validate
from .auth import iso, utcnow
from .config import LANGUAGE_HINTS, Settings, load_settings
from .db import Database
from .guard import Refusal, check_enqueue, usage_summary
from .jobs import Worker, make_provider, recover_interrupted
from .scheduler import Scheduler

# Python doesn't know this extension on every system; browsers want the manifest served with its real type.
mimetypes.add_type("application/manifest+json", ".webmanifest")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
STATIC = Path(__file__).resolve().parent.parent / "static"
EMAIL = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")
# First path segments owned by the API (now or soon). The SPA fallback never answers these with HTML,
# so a mistyped API URL stays an honest JSON 404.
API_PREFIXES = frozenset({"auth", "voice-notes", "detect", "reminders", "usage", "health", "push", "reports",
                          "docs", "redoc", "openapi.json"})


class Credentials(BaseModel):
    email: str = Field(max_length=254)
    password: str = Field(min_length=8, max_length=128)
    registration_code: str | None = None


class TextIn(BaseModel):
    text: str = Field(min_length=1, max_length=1000)
    language: str | None = None


class PushKeys(BaseModel):
    p256dh: str = Field(min_length=20, max_length=200, pattern=r"^[A-Za-z0-9_\-+/=]+$")
    auth: str = Field(min_length=8, max_length=100, pattern=r"^[A-Za-z0-9_\-+/=]+$")


class PushSubscription(BaseModel):
    """What the browser's PushSubscription.toJSON() gives (expirationTime is ignored)."""
    endpoint: str = Field(min_length=10, max_length=1000)
    keys: PushKeys


class PushEndpoint(BaseModel):
    endpoint: str = Field(min_length=10, max_length=1000)


class ReminderPatch(BaseModel):
    """Only the fields sent are changed."""
    due_at: str | None = Field(default=None, max_length=40)
    done: bool | None = None


# How far ahead a reminder may be moved. Catches typos like year 20226 without limiting real use.
MAX_REMINDER_AHEAD = timedelta(days=366 * 2)
REMINDER_COLUMNS = "id, job_id, text, matched_phrase, due_at, time_text, used_default_time, created_at, done_at"


def create_app(settings: Settings | None = None, provider=None, start_worker: bool | None = None,
               push_sender: push.Sender | None = None, llm=None) -> FastAPI:
    settings = settings or load_settings()

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        db = Database(settings.database_path)
        recover_interrupted(db)
        prov = provider if provider is not None else make_provider(settings)
        app.state.db, app.state.provider = db, prov
        app.state.llm = llm if llm is not None else llm_mod.make_llm(settings)
        app.state.push_sender = push_sender if push_sender is not None else push.make_sender(settings)
        # Tests call app.state.scheduler.tick(now) themselves; the thread only runs alongside the worker.
        scheduler = app.state.scheduler = Scheduler(settings.timezone, settings.scheduler_interval_seconds)
        scheduler.add("push-due-reminders", lambda now: push.send_due(db, app.state.push_sender, now))
        scheduler.add("daily-08:00", lambda now: daily.run_if_due(db, now))
        worker = None
        background = settings.worker_enabled if start_worker is None else start_worker
        if background:
            worker = Worker(db, settings, prov, app.state.llm)
            worker.start()
            scheduler.start()
        logging.getLogger("promise_keeper").info(
            "STT provider=%s live_gnani=%s llm=%s", settings.stt_provider, settings.live_gnani,
            app.state.llm.name if app.state.llm else "none")
        yield
        if worker:
            worker.stop()
            scheduler.stop()
        db.close()

    app = FastAPI(title="Promise Keeper Voice API", version="0.1.0", lifespan=lifespan)
    app.state.settings = settings
    login_limiter = auth.LoginLimiter(settings.login_attempts_per_15_min)

    def db_dep(request: Request) -> Database:
        return request.app.state.db

    def current_user(authorization: str | None = Header(default=None), db: Database = Depends(db_dep)) -> int:
        if not authorization or not authorization.lower().startswith("bearer "):
            raise HTTPException(401, {"code": "UNAUTHENTICATED", "message": "Send Authorization: Bearer <token>."})
        user = auth.user_for_token(db, authorization[7:].strip())
        if user is None:
            raise HTTPException(401, {"code": "INVALID_TOKEN", "message": "Log in again."})
        return user

    @app.exception_handler(Refusal)
    async def refusal_handler(_: Request, exc: Refusal):
        headers = {"Retry-After": str(exc.retry_after)} if exc.retry_after else None
        return JSONResponse({"detail": {"code": exc.code, "message": exc.message}}, exc.status, headers=headers)

    @app.get("/health")
    def health():
        return {"status": "ok", "provider": settings.stt_provider, "live_gnani": settings.live_gnani}

    def web_dist() -> Path | None:
        """The built web app, if there is one. Checked per request so `npm run build` needs no restart."""
        if not settings.web_dist_path:
            return None
        dist = Path(settings.web_dist_path).resolve()
        return dist if (dist / "index.html").is_file() else None

    def app_shell(dist: Path) -> FileResponse:
        # index.html names the hashed asset files, so it must be re-checked after every deploy.
        return FileResponse(dist / "index.html", headers={"Cache-Control": "no-cache"})

    @app.get("/", include_in_schema=False)
    def index():
        dist = web_dist()
        return app_shell(dist) if dist else FileResponse(STATIC / "index.html")

    @app.get("/legacy", include_in_schema=False)
    def legacy_page():
        """The plain demo page, still reachable once the web app is built (until the web app covers it all)."""
        return FileResponse(STATIC / "index.html")

    @app.post("/auth/register", status_code=201)
    def register(body: Credentials, db: Database = Depends(db_dep)):
        if settings.registration_code and body.registration_code != settings.registration_code:
            raise HTTPException(403, {"code": "REGISTRATION_CLOSED", "message": "A valid registration code is required."})
        email = body.email.strip().lower()
        if not EMAIL.match(email):
            raise HTTPException(422, {"code": "INVALID_EMAIL", "message": "Enter a valid email."})
        user_id = auth.create_user(db, email, body.password)
        if user_id is None:
            raise HTTPException(409, {"code": "EMAIL_TAKEN", "message": "That email is already registered."})
        return {"id": user_id, "email": email}

    @app.post("/auth/login")
    def login(body: Credentials, db: Database = Depends(db_dep)):
        email = body.email.strip().lower()
        ok, wait = login_limiter.allow(email)
        if not ok:
            raise Refusal(429, "TOO_MANY_LOGINS", "Too many login attempts. Try later.", wait)
        token = auth.login(db, email, body.password, settings.session_hours)
        if token is None:
            raise HTTPException(401, {"code": "BAD_CREDENTIALS", "message": "Wrong email or password."})
        return {"token": token, "token_type": "bearer", "expires_in_hours": settings.session_hours}

    @app.post("/auth/logout", status_code=204)
    def logout(authorization: str = Header(), db: Database = Depends(db_dep), _: int = Depends(current_user)):
        auth.logout(db, authorization[7:].strip())

    @app.post("/voice-notes", status_code=202)
    async def upload(audio_file: UploadFile = File(...), language: str | None = Form(default=None),
                     user_id: int = Depends(current_user), db: Database = Depends(db_dep)):
        # Read at most one byte past the limit, so a huge upload is never held in memory.
        data = await audio_file.read(settings.max_upload_bytes + 1)
        try:
            checked = validate(audio_file.filename or "", data, settings)
        except AudioRejected as rej:
            raise HTTPException(rej.status, {"code": rej.code, "message": rej.message})
        language = (language or settings.gnani_language).strip()
        if language not in LANGUAGE_HINTS:
            raise HTTPException(422, {"code": "INVALID_LANGUAGE", "message": f"language must be one of {', '.join(LANGUAGE_HINTS)}"})
        job_id = uuid.uuid4().hex
        with db.transaction():
            check_enqueue(db, settings, user_id, checked.seconds)
            db.execute(
                "INSERT INTO jobs (id, user_id, status, filename, extension, audio, audio_sha256, audio_seconds, "
                "language, created_at) VALUES (?, ?, 'queued', ?, ?, ?, ?, ?, ?, ?)",
                (job_id, user_id, (audio_file.filename or "audio")[:120], checked.extension, data,
                 hashlib.sha256(data).hexdigest(), checked.seconds, language, iso(utcnow())),
            )
        return {"id": job_id, "status": "queued", "audio_seconds": checked.seconds, "poll": f"/voice-notes/{job_id}"}

    @app.get("/voice-notes/{job_id}")
    def get_job(job_id: str, user_id: int = Depends(current_user), db: Database = Depends(db_dep)):
        row = db.one(
            "SELECT id, status, filename, audio_seconds, language, transcript, detected_language, cached, error, created_at, "
            "finished_at, llm FROM jobs WHERE id = ? AND user_id = ?", (job_id, user_id))
        if not row:
            raise HTTPException(404, {"code": "NOT_FOUND", "message": "No such voice note."})
        out = dict(row)
        verdict = out.pop("llm")
        out["cached"] = bool(out["cached"])
        rem = db.one("SELECT id, text, due_at, time_text, used_default_time, done_at FROM reminders WHERE job_id = ?",
                     (job_id,))
        out["reminder"] = dict(rem) if rem else None
        if row["transcript"]:
            d = detect.detect(row["transcript"], row["detected_language"] or row["language"])
            out["detection"] = {"is_promise": out["reminder"] is not None, "language": d.language, "tense": d.tense,
                                "matched": d.matched, "reasons": d.reasons, "verified_rules": d.verified_rules,
                                # The optional model's opinion, if it was asked (None otherwise).
                                "llm": json.loads(verdict) if verdict else None}
        return out

    @app.get("/voice-notes")
    def list_jobs(user_id: int = Depends(current_user), db: Database = Depends(db_dep)):
        rows = db.all("SELECT id, status, transcript, error, created_at FROM jobs WHERE user_id = ? "
                      "ORDER BY created_at DESC LIMIT 50", (user_id,))
        return [dict(r) for r in rows]

    @app.post("/detect")
    def detect_text(body: TextIn, _: int = Depends(current_user)):
        """Free: no STT involved. Useful to test the detection rules on typed text."""
        now = datetime.now(ZoneInfo(settings.timezone))
        parsed = timeparse.parse(body.text, now)
        d = detect.detect(body.text, body.language, has_time=parsed is not None,
                          time_used_default=parsed.used_default if parsed else None)
        return {"is_promise": d.is_promise, "language": d.language, "tense": d.tense, "matched": d.matched,
                "reasons": d.reasons, "verified_rules": d.verified_rules,
                "due_at": (parsed or timeparse.due_time(body.text, now)).when.isoformat(timespec="minutes")
                if d.is_promise else None}

    @app.get("/reminders")
    def reminders(user_id: int = Depends(current_user), db: Database = Depends(db_dep)):
        rows = db.all(f"SELECT {REMINDER_COLUMNS} FROM reminders WHERE user_id = ? ORDER BY due_at", (user_id,))
        return [dict(r) for r in rows]

    @app.patch("/reminders/{reminder_id}")
    def edit_reminder(reminder_id: int, body: ReminderPatch, user_id: int = Depends(current_user),
                      db: Database = Depends(db_dep)):
        """Move a reminder to another time and/or tick it off (done=true) or reopen it (done=false)."""
        if body.due_at is None and body.done is None:
            raise HTTPException(422, {"code": "NOTHING_TO_CHANGE", "message": "Send due_at and/or done."})
        sets, params = [], []
        if body.due_at is not None:
            zone = ZoneInfo(settings.timezone)
            try:
                when = datetime.fromisoformat(body.due_at.strip())
            except ValueError:
                raise HTTPException(422, {"code": "INVALID_DUE_AT",
                                          "message": "due_at must look like 2026-11-14T17:00."})
            # A time without an offset means the server's local time (what <input type=datetime-local> sends).
            when = (when.replace(tzinfo=zone) if when.tzinfo is None else when.astimezone(zone)).replace(
                second=0, microsecond=0)
            now = datetime.now(zone)
            if when <= now:
                raise HTTPException(422, {"code": "DUE_IN_PAST", "message": "Pick a time in the future."})
            if when > now + MAX_REMINDER_AHEAD:
                raise HTTPException(422, {"code": "DUE_TOO_FAR", "message": "Pick a time within the next two years."})
            # The user chose this time, so it is no longer a guessed default.
            # A new time also means a new notification, even if the old time was already announced.
            sets += ["due_at = ?", "used_default_time = 0", "pushed_at = NULL"]
            params.append(when.isoformat(timespec="minutes"))
        if body.done is not None:
            sets.append("done_at = ?")
            params.append(iso(utcnow()) if body.done else None)
        # user_id in the WHERE clause: someone else's reminder looks exactly like a missing one.
        cur = db.execute(f"UPDATE reminders SET {', '.join(sets)} WHERE id = ? AND user_id = ?",
                         (*params, reminder_id, user_id))
        if cur.rowcount == 0:
            raise HTTPException(404, {"code": "NOT_FOUND", "message": "No such reminder."})
        return dict(db.one(f"SELECT {REMINDER_COLUMNS} FROM reminders WHERE id = ?", (reminder_id,)))

    @app.delete("/reminders/{reminder_id}", status_code=204)
    def delete_reminder(reminder_id: int, user_id: int = Depends(current_user), db: Database = Depends(db_dep)):
        cur = db.execute("DELETE FROM reminders WHERE id = ? AND user_id = ?", (reminder_id, user_id))
        if cur.rowcount == 0:
            raise HTTPException(404, {"code": "NOT_FOUND", "message": "No such reminder."})

    @app.get("/push/config")
    def push_config():
        """The public half of the server's push key, which the browser needs to subscribe. Not a secret."""
        return {"enabled": settings.push_enabled, "public_key": settings.vapid_public_key if settings.push_enabled else None}

    @app.post("/push/subscribe", status_code=201)
    def push_subscribe(body: PushSubscription, user_id: int = Depends(current_user), db: Database = Depends(db_dep)):
        if not settings.push_enabled:
            raise HTTPException(503, {"code": "PUSH_NOT_CONFIGURED", "message": "Notifications are not set up on this server."})
        if not push.valid_endpoint(body.endpoint):
            raise HTTPException(422, {"code": "INVALID_PUSH_ENDPOINT",
                                      "message": "That is not a push address from a supported browser."})
        push.save_subscription(db, user_id, body.endpoint, body.keys.p256dh, body.keys.auth)
        return {"subscribed": True}

    @app.delete("/push/subscribe", status_code=204)
    def push_unsubscribe(body: PushEndpoint, user_id: int = Depends(current_user), db: Database = Depends(db_dep)):
        push.delete_subscription(db, user_id, body.endpoint)  # already gone is fine: the result is the same

    @app.get("/reports/weekly.pdf")
    def weekly_report(user_id: int = Depends(current_user), db: Database = Depends(db_dep)):
        """Your reminders for the next 7 days and your voice notes from the last 7, as a PDF."""
        now = datetime.now(ZoneInfo(settings.timezone))
        pdf, cached = reports.weekly_report(db, user_id, now)
        return Response(pdf, media_type="application/pdf", headers={
            "Content-Disposition": f'attachment; filename="promise-keeper-weekly-{now.date().isoformat()}.pdf"',
            "Cache-Control": "private, no-store",  # personal data: our own cache is the only one
            "X-Report-Cache": "hit" if cached else "miss",
        })

    @app.get("/usage/me")
    def my_usage(user_id: int = Depends(current_user), db: Database = Depends(db_dep)):
        return usage_summary(db, settings, user_id)

    # Registered last, so every real route wins. Serves the built web app's files and, for any other
    # page-like path, the app shell (the React app does its own routing).
    @app.get("/{path:path}", include_in_schema=False)
    def web_files(path: str):
        not_found = HTTPException(404, {"code": "NOT_FOUND", "message": "Nothing here."})
        dist = web_dist()
        if dist is None or path.split("/", 1)[0] in API_PREFIXES:
            raise not_found
        target = (dist / path).resolve()
        if not target.is_relative_to(dist):  # ../ tricks must never read files outside web/dist
            raise not_found
        if target.is_file():
            # Vite puts a content hash in every file name under assets/, so those can be cached for good.
            cache = "public, max-age=31536000, immutable" if path.startswith("assets/") else "no-cache"
            return FileResponse(target, headers={"Cache-Control": cache})
        if "." in path.rsplit("/", 1)[-1]:  # a missing file (app.js, icon.png) is a 404, not a page
            raise not_found
        return app_shell(dist)

    return app


app = create_app()
