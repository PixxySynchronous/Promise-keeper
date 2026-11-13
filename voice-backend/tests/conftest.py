"""Shared fixtures. Every test is offline: outbound sockets are blocked, so no test can spend Gnani credits."""
from __future__ import annotations

import io
import math
import socket
import struct
import sys
import wave
from dataclasses import replace
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.config import Settings  # noqa: E402


@pytest.fixture(autouse=True)
def no_network(monkeypatch):
    real_connect = socket.socket.connect

    def guarded(self, address):
        host = address[0] if isinstance(address, tuple) else address
        if host not in ("127.0.0.1", "::1", "localhost") and not str(host).startswith("/"):
            raise RuntimeError(f"Network access blocked in tests (tried {host}). Use a mock transport.")
        return real_connect(self, address)

    monkeypatch.setattr(socket.socket, "connect", guarded)


@pytest.fixture(autouse=True)
def no_retry_sleep(monkeypatch):
    import app.jobs

    monkeypatch.setattr(app.jobs, "_sleep", lambda s: None)


@pytest.fixture
def settings(tmp_path) -> Settings:
    return Settings(database_path=str(tmp_path / "test.db"), worker_enabled=False)


def make_wav(seconds: float = 1.5, rate: int = 8000, freq: float = 440.0) -> bytes:
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(b"".join(struct.pack("<h", int(3000 * math.sin(2 * math.pi * freq * i / rate)))
                               for i in range(int(seconds * rate))))
    return buf.getvalue()


@pytest.fixture
def wav():
    return make_wav


@pytest.fixture
def make_client(settings, tmp_path):
    """make_client(provider=..., **setting_overrides) -> (TestClient, app). Worker is off; tests drive it."""
    from fastapi.testclient import TestClient

    from app.main import create_app

    clients = []

    def factory(provider=None, push_sender=None, llm=None, **overrides):
        overrides.setdefault("database_path", str(tmp_path / f"client{len(clients)}.db"))  # fresh DB per client
        s = replace(settings, **overrides)
        app = create_app(s, provider=provider, start_worker=False, push_sender=push_sender, llm=llm)
        client = TestClient(app)
        client.__enter__()
        clients.append(client)
        return client, app

    yield factory
    for c in clients:
        c.__exit__(None, None, None)


def login(client, email="a@example.com", password="password123") -> dict:
    client.post("/auth/register", json={"email": email, "password": password})
    token = client.post("/auth/login", json={"email": email, "password": password}).json()["token"]
    return {"Authorization": f"Bearer {token}"}
