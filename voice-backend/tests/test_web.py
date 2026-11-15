"""Serving the built web app (web/dist) from FastAPI, with a fallback for client-side routes."""
import pytest


@pytest.fixture
def dist(tmp_path):
    d = tmp_path / "dist"
    (d / "assets").mkdir(parents=True)
    (d / "index.html").write_text("<!doctype html><title>React shell</title>")
    (d / "assets" / "index-abc123.js").write_text("console.log('app')")
    (d / "manifest.webmanifest").write_text("{}")
    (d / "sw.js").write_text("// worker")
    (tmp_path / "secret.txt").write_text("outside dist")
    return d


def test_old_page_is_served_when_there_is_no_build(make_client, tmp_path):
    for path in ("", str(tmp_path / "missing")):
        client, _ = make_client(web_dist_path=path)
        r = client.get("/")
        assert r.status_code == 200 and "React shell" not in r.text and "<html" in r.text.lower()
        assert client.get("/some/page").status_code == 404


def test_built_app_is_served_with_spa_fallback(make_client, dist):
    client, _ = make_client(web_dist_path=str(dist))
    root = client.get("/")
    assert root.status_code == 200 and "React shell" in root.text
    assert root.headers["cache-control"] == "no-cache"
    for route in ("/reminders-view", "/capture/new"):  # client-side routes get the shell
        r = client.get(route)
        assert r.status_code == 200 and "React shell" in r.text
    asset = client.get("/assets/index-abc123.js")
    assert asset.status_code == 200 and "console.log" in asset.text
    assert "immutable" in asset.headers["cache-control"]
    manifest = client.get("/manifest.webmanifest")
    assert manifest.headers["cache-control"] == "no-cache"
    assert manifest.headers["content-type"].startswith("application/manifest+json")
    worker = client.get("/sw.js")  # must be re-checked on every load, or an old worker could stick around
    assert worker.status_code == 200 and worker.headers["cache-control"] == "no-cache"
    assert "javascript" in worker.headers["content-type"]
    legacy = client.get("/legacy")  # the old demo page stays reachable
    assert legacy.status_code == 200 and "React shell" not in legacy.text


def test_fallback_never_hides_api_errors_or_missing_files(make_client, dist):
    client, _ = make_client(web_dist_path=str(dist))
    assert client.get("/health").json()["status"] == "ok"
    assert client.get("/reminders").status_code == 401  # still the API, not the shell
    for path in ("/auth/nope", "/voice-notes/x/y", "/usage", "/assets/missing.js", "/favicon.ico"):
        r = client.get(path)
        assert r.status_code == 404, path
        assert r.json()["detail"]["code"] == "NOT_FOUND"
    assert client.post("/some/page").status_code == 405


@pytest.mark.parametrize("path", ["/../secret.txt", "/%2e%2e/secret.txt", "/assets/%2e%2e/%2e%2e/secret.txt",
                                  "/..%2fsecret.txt", "/assets/..%2f..%2fsecret.txt"])
def test_cannot_read_files_outside_dist(make_client, dist, path):
    client, _ = make_client(web_dist_path=str(dist))
    r = client.get(path)
    assert "outside dist" not in r.text
    assert r.status_code == 404 or "React shell" in r.text
