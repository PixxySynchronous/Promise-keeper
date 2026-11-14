"""Gnani client against a fake transport. No request leaves the machine (see conftest.no_network)."""
import json
import logging
from dataclasses import replace

import httpx
import pytest

from app.guard import provider_paused
from app.jobs import make_provider, process_next
from app.stt.gnani import GnaniProvider
from tests.conftest import login

KEY = "test-key-not-real"


def armed(settings, **kw):
    return replace(settings, stt_provider="gnani", gnani_live_calls_enabled=True, gnani_api_key=KEY, **kw)


class FakeGnani:
    """Replies with queued (status, body) pairs and records each request."""

    def __init__(self, *replies):
        self.replies = list(replies)
        self.requests: list[httpx.Request] = []

    def __call__(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        status, body = self.replies.pop(0) if self.replies else (200, OK)
        return httpx.Response(status, json=body)


OK = {"success": True, "request_id": "r-1", "transcript": "kal paanch baje report bhej dunga", "model": "prisma-v3.0"}


def run_one(make_client, settings, wav, fake, **overrides):
    s = armed(settings, **overrides)
    provider = GnaniProvider(s, transport=httpx.MockTransport(fake))
    client, app = make_client(provider=provider, **{k: getattr(s, k) for k in
                                                   ("stt_provider", "gnani_live_calls_enabled", "gnani_api_key", *overrides)})
    h = login(client)
    r = client.post("/voice-notes", headers=h, files={"audio_file": ("note.wav", wav(), "audio/wav")})
    assert r.status_code == 202, r.text
    process_next(app.state.db, app.state.settings, provider)
    return client, app, h, r.json()["id"]


def test_provider_needs_both_switches_and_a_key(settings):
    assert make_provider(replace(settings, stt_provider="gnani")) is None
    assert make_provider(replace(settings, stt_provider="gnani", gnani_live_calls_enabled=True)) is None
    assert make_provider(replace(settings, stt_provider="gnani", gnani_api_key=KEY)) is None
    with pytest.raises(RuntimeError):
        GnaniProvider(replace(settings, stt_provider="gnani", gnani_api_key=KEY))


def test_request_matches_the_documented_api(make_client, settings, wav):
    fake = FakeGnani((200, OK))
    client, app, h, job_id = run_one(make_client, settings, wav, fake)
    req = fake.requests[0]
    assert req.method == "POST" and str(req.url) == "https://api.vachana.ai/stt/v3"
    assert req.headers["X-API-Key-ID"] == KEY and req.headers["X-API-Request-ID"] == job_id
    body = req.content.decode("latin-1")
    for field, value in [("language_code", "indic-auto"), ("format", "transliterate"), ("model", "prisma-v3.0")]:
        assert f'name="{field}"\r\n\r\n{value}' in body
    assert f'name="audio_file"; filename="{job_id}.wav"' in body
    job = client.get(f"/voice-notes/{job_id}", headers=h).json()
    assert job["status"] == "done" and job["reminder"] is not None
    event = app.state.db.one("SELECT * FROM usage_events")
    assert event["billable"] == 1 and event["outcome"] == "ok" and event["upstream_request_id"] == "r-1"


def test_retries_only_retryable_errors_and_logs_each_attempt(make_client, settings, wav):
    busy = {"detail": {"error_code": "RATE_LIMITED", "message": "Only 1 active request", "status_code": 429}}
    fake = FakeGnani((429, busy), (200, OK))
    _, app, _, _ = run_one(make_client, settings, wav, fake)
    assert len(fake.requests) == 2
    outcomes = [r["outcome"] for r in app.state.db.all("SELECT outcome FROM usage_events ORDER BY id")]
    assert outcomes == ["error:RATE_LIMITED", "ok"]

    bad = {"success": False, "error": {"type": "UNSUPPORTED_AUDIO_FORMAT", "message": "nope"}}
    fake2 = FakeGnani((400, bad))
    client, app2, h, job_id = run_one(make_client, settings, wav, fake2)
    assert len(fake2.requests) == 1
    assert client.get(f"/voice-notes/{job_id}", headers=h).json()["error"] == "UNSUPPORTED_AUDIO_FORMAT"


@pytest.mark.parametrize("status", [401, 402, 403])
def test_auth_or_credit_errors_pause_the_provider(make_client, settings, wav, status):
    fake = FakeGnani((status, {"detail": {"error_code": "X", "message": "stop", "status_code": status}}))
    client, app, h, _ = run_one(make_client, settings, wav, fake)
    assert provider_paused(app.state.db)
    r = client.post("/voice-notes", headers=h, files={"audio_file": ("n.wav", wav(freq=300), "audio/wav")})
    assert r.status_code == 503 and r.json()["detail"]["code"] == "PROVIDER_PAUSED"


def test_budget_blocks_before_any_call(make_client, settings, wav):
    fake = FakeGnani((200, OK))
    client, app, h, _ = run_one(make_client, settings, wav, fake, budget_total_requests=1)
    assert len(fake.requests) == 1
    r = client.post("/voice-notes", headers=h, files={"audio_file": ("n.wav", wav(freq=300), "audio/wav")})
    assert r.status_code == 503 and r.json()["detail"]["code"] == "BUDGET_EXHAUSTED"
    assert len(fake.requests) == 1


def test_budget_lowered_after_queueing_stops_the_worker(make_client, settings, wav):
    s = armed(settings)
    fake = FakeGnani()
    provider = GnaniProvider(s, transport=httpx.MockTransport(fake))
    client, app = make_client(provider=provider, stt_provider="gnani", gnani_live_calls_enabled=True, gnani_api_key=KEY)
    h = login(client)
    job_id = client.post("/voice-notes", headers=h, files={"audio_file": ("n.wav", wav(), "audio/wav")}).json()["id"]
    process_next(app.state.db, replace(s, budget_total_audio_seconds=0.5), provider)
    assert fake.requests == []
    assert client.get(f"/voice-notes/{job_id}", headers=h).json()["error"] == "BUDGET_EXHAUSTED"


def test_key_never_appears_in_logs_or_responses(make_client, settings, wav, caplog):
    caplog.set_level(logging.DEBUG)
    fake = FakeGnani((500, {"success": False, "error": {"type": "TRANSCRIPTION_FAILED", "message": "x"}}),
                     (500, {"success": False, "error": {"type": "TRANSCRIPTION_FAILED", "message": "x"}}))
    client, app, h, job_id = run_one(make_client, settings, wav, fake)
    seen = caplog.text + json.dumps(client.get(f"/voice-notes/{job_id}", headers=h).json())
    seen += json.dumps(client.get("/usage/me", headers=h).json()) + json.dumps(client.get("/health").json())
    assert KEY not in seen
    assert KEY not in repr(app.state.settings)
