"""End to end through HTTP with the mock provider: auth, validation, jobs, cache, rate limits, quotas."""
from app.jobs import process_next
from app.stt import Transcript
from tests.conftest import login


class CountingMock:
    name, billable = "mock", False

    def __init__(self, text="kal 5 baje report bhej dunga"):
        self.calls, self.text = 0, text

    def cache_identity(self):
        return "counting"

    def transcribe(self, audio, filename, request_id, language):
        self.calls += 1
        return Transcript(self.text, None, f"mock-{request_id}")


def upload(client, headers, data, name="note.wav", **form):
    return client.post("/voice-notes", headers=headers, files={"audio_file": (name, data, "audio/wav")}, data=form)


def drain(app, settings_obj=None):
    while process_next(app.state.db, settings_obj or app.state.settings, app.state.provider):
        pass


def test_protected_routes_need_a_token(make_client):
    client, _ = make_client()
    for method, path in [("get", "/reminders"), ("get", "/usage/me"), ("post", "/voice-notes"), ("get", "/voice-notes/x")]:
        assert getattr(client, method)(path).status_code == 401
    assert client.get("/reminders", headers={"Authorization": "Bearer nope"}).status_code == 401


def test_register_login_errors(make_client):
    client, _ = make_client()
    assert client.post("/auth/register", json={"email": "a@example.com", "password": "password123"}).status_code == 201
    assert client.post("/auth/register", json={"email": "A@example.com", "password": "password123"}).status_code == 409
    assert client.post("/auth/register", json={"email": "bad", "password": "password123"}).status_code == 422
    assert client.post("/auth/register", json={"email": "b@example.com", "password": "short"}).status_code == 422
    assert client.post("/auth/login", json={"email": "a@example.com", "password": "wrongpass1"}).status_code == 401


def test_registration_code(make_client):
    client, _ = make_client(registration_code="letmein")
    assert client.post("/auth/register", json={"email": "a@example.com", "password": "password123"}).status_code == 403
    ok = client.post("/auth/register", json={"email": "a@example.com", "password": "password123",
                                             "registration_code": "letmein"})
    assert ok.status_code == 201


def test_login_is_rate_limited(make_client):
    client, _ = make_client(login_attempts_per_15_min=3)
    for _ in range(3):
        client.post("/auth/login", json={"email": "x@example.com", "password": "password123"})
    r = client.post("/auth/login", json={"email": "x@example.com", "password": "password123"})
    assert r.status_code == 429 and int(r.headers["Retry-After"]) > 0


def test_voice_note_becomes_a_reminder(make_client, wav):
    provider = CountingMock()
    client, app = make_client(provider=provider)
    h = login(client)
    r = upload(client, h, wav())
    assert r.status_code == 202
    job_id = r.json()["id"]
    assert client.get(f"/voice-notes/{job_id}", headers=h).json()["status"] == "queued"
    drain(app)
    job = client.get(f"/voice-notes/{job_id}", headers=h).json()
    assert job["status"] == "done" and job["transcript"] == provider.text
    assert "T17:00" in job["reminder"]["due_at"]
    assert len(client.get("/reminders", headers=h).json()) == 1
    # Audio is deleted once processed.
    assert app.state.db.one("SELECT audio FROM jobs WHERE id = ?", (job_id,))["audio"] is None


def test_not_a_promise_makes_no_reminder(make_client, wav):
    client, app = make_client(provider=CountingMock("maine kal file bhej di thi"))
    h = login(client)
    job_id = upload(client, h, wav()).json()["id"]
    drain(app)
    job = client.get(f"/voice-notes/{job_id}", headers=h).json()
    assert job["reminder"] is None and "past tense" in job["detection"]["reasons"]


def test_same_audio_is_served_from_cache(make_client, wav):
    provider = CountingMock()
    client, app = make_client(provider=provider)
    h = login(client)
    data = wav()
    first = upload(client, h, data).json()["id"]
    drain(app)
    second = upload(client, h, data).json()["id"]
    drain(app)
    assert provider.calls == 1
    assert client.get(f"/voice-notes/{second}", headers=h).json()["cached"] is True
    outcomes = [r["outcome"] for r in app.state.db.all("SELECT outcome FROM usage_events ORDER BY id")]
    assert outcomes == ["ok", "cached"] and first != second


def test_users_cannot_see_each_others_notes(make_client, wav):
    client, app = make_client(provider=CountingMock())
    a, b = login(client, "a@example.com"), login(client, "b@example.com")
    job_id = upload(client, a, wav()).json()["id"]
    assert client.get(f"/voice-notes/{job_id}", headers=b).status_code == 404


def test_upload_validation_status_codes(make_client, wav):
    client, _ = make_client(provider=CountingMock())
    h = login(client)
    assert upload(client, h, wav(), name="x.webm").status_code == 415
    assert upload(client, h, wav(31)).status_code == 413
    assert upload(client, h, wav(0.1)).status_code == 400
    assert upload(client, h, wav(), language="xx-IN").status_code == 422


def test_per_minute_rate_limit(make_client, wav):
    client, app = make_client(provider=CountingMock(), user_requests_per_minute=2, user_max_pending_jobs=10)
    h = login(client)
    assert upload(client, h, wav(freq=300)).status_code == 202
    assert upload(client, h, wav(freq=400)).status_code == 202
    r = upload(client, h, wav(freq=500))
    assert r.status_code == 429 and r.json()["detail"]["code"] == "RATE_LIMITED"
    assert 1 <= int(r.headers["Retry-After"]) <= 61


def test_pending_jobs_limit(make_client, wav):
    client, _ = make_client(provider=CountingMock(), user_max_pending_jobs=1)
    h = login(client)
    upload(client, h, wav())
    r = upload(client, h, wav(freq=300))
    assert r.status_code == 429 and r.json()["detail"]["code"] == "TOO_MANY_PENDING"


def test_daily_quotas(make_client, wav):
    client, app = make_client(provider=CountingMock(), user_daily_requests=1)
    h = login(client)
    upload(client, h, wav())
    drain(app)
    r = upload(client, h, wav(freq=300))
    assert r.status_code == 429 and r.json()["detail"]["code"] == "DAILY_QUOTA"

    client2, app2 = make_client(provider=CountingMock(), user_daily_audio_seconds=2.0)
    h2 = login(client2)
    upload(client2, h2, wav(1.5))
    drain(app2)
    r = upload(client2, h2, wav(1.5, freq=300))
    assert r.status_code == 429 and r.json()["detail"]["code"] == "DAILY_AUDIO_QUOTA"


def test_global_limits(make_client, wav):
    client, app = make_client(provider=CountingMock(), global_daily_requests=1)
    upload(client, login(client, "a@example.com"), wav())
    r = upload(client, login(client, "b@example.com"), wav(freq=300))
    assert r.status_code == 503 and r.json()["detail"]["code"] == "SERVER_DAILY_LIMIT"

    client2, _ = make_client(provider=CountingMock(), max_queue_depth=1)
    upload(client2, login(client2, "a@example.com"), wav())
    r = upload(client2, login(client2, "b@example.com"), wav(freq=300))
    assert r.status_code == 503 and r.json()["detail"]["code"] == "QUEUE_FULL"


def test_disabled_and_unarmed_gnani_refuse_uploads(make_client, wav):
    client, _ = make_client(stt_provider="disabled")
    r = upload(client, login(client), wav())
    assert r.status_code == 503 and r.json()["detail"]["code"] == "STT_DISABLED"

    # Gnani selected, but the second switch is off: refused, nothing queued.
    client2, app2 = make_client(stt_provider="gnani", gnani_api_key="k-test")
    r = upload(client2, login(client2), wav())
    assert r.status_code == 503 and r.json()["detail"]["code"] == "GNANI_NOT_ENABLED"
    assert app2.state.provider is None


def test_detect_endpoint_is_free(make_client):
    client, app = make_client(provider=CountingMock())
    r = client.post("/detect", headers=login(client), json={"text": "ami kal pathabo", "language": "bn-IN"})
    assert r.status_code == 200 and r.json()["is_promise"] and r.json()["verified_rules"] is False
    assert app.state.db.one("SELECT COUNT(*) AS n FROM usage_events")["n"] == 0


def test_usage_endpoint(make_client, wav):
    client, app = make_client(provider=CountingMock())
    h = login(client)
    upload(client, h, wav())
    body = client.get("/usage/me", headers=h).json()
    assert body["today_requests"] == 1 and body["limits"]["requests_per_minute"] == 3
    assert "gnani_api_key" not in str(body)
