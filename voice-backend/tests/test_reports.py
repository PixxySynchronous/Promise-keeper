"""Weekly PDF report: real PDF, only your own data, Indic text, and a cache that is never stale."""
import io
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

from pypdf import PdfReader

from app import reports
from app.auth import iso
from tests.conftest import login
from tests.test_api import CountingMock, drain, upload

IST = ZoneInfo("Asia/Kolkata")


def text_of(pdf: bytes) -> str:
    return "\n".join(page.extract_text() for page in PdfReader(io.BytesIO(pdf)).pages)


def note(client, app, headers, wav, transcript, freq=440.0):
    app.state.provider.text = transcript
    assert upload(client, headers, wav(freq=freq)).status_code == 202  # a new tone = new audio = no cache hit
    drain(app)


def test_report_is_a_pdf_with_your_reminders_and_notes(make_client, wav):
    client, app = make_client(provider=CountingMock())
    h = login(client)
    note(client, app, h, wav, "kal 5 baje report bhej dunga")
    note(client, app, h, wav, "maine kal file bhej di thi", freq=500.0)
    r = client.get("/reports/weekly.pdf", headers=h)
    assert r.status_code == 200 and r.headers["content-type"] == "application/pdf"
    assert r.content.startswith(b"%PDF") and r.content.rstrip().endswith(b"%%EOF")
    assert "attachment" in r.headers["content-disposition"] and ".pdf" in r.headers["content-disposition"]
    assert r.headers["cache-control"] == "private, no-store"
    text = text_of(r.content)
    assert "a@example.com" in text and "Next 7 days" in text
    assert text.count("kal 5 baje report bhej dunga") == 2  # once as a reminder, once as a note
    assert "17:00" in text and "reminder made" in text
    assert "maine kal file bhej di thi" in text and "no promise found" in text
    assert "1 due in the next 7 days" in text and "2 voice notes" in text


def test_report_needs_a_token(make_client):
    client, _ = make_client()
    assert client.get("/reports/weekly.pdf").status_code == 401


def test_another_users_data_is_absent(make_client, wav):
    client, app = make_client(provider=CountingMock(), user_requests_per_minute=10)
    a, b = login(client), login(client, "b@example.com")
    note(client, app, a, wav, "kal 5 baje alpha secret report bhej dunga")
    note(client, app, b, wav, "kal 6 baje bravo invoice bhej dunga", freq=500.0)
    mine, theirs = text_of(client.get("/reports/weekly.pdf", headers=a).content), text_of(
        client.get("/reports/weekly.pdf", headers=b).content)
    assert "alpha secret" in mine and "bravo" not in mine and "b@example.com" not in mine
    assert "bravo invoice" in theirs and "alpha" not in theirs and "a@example.com" not in theirs
    # The cache is per user too: asking again must not hand A's cached file to B.
    assert "bravo" not in text_of(client.get("/reports/weekly.pdf", headers=a).content)
    assert app.state.db.one("SELECT COUNT(*) AS n FROM report_cache")["n"] == 2


def test_an_empty_account_still_gets_a_report(make_client):
    client, _ = make_client()
    text = text_of(client.get("/reports/weekly.pdf", headers=login(client)).content)
    assert "Nothing due this week." in text and "No voice notes this week." in text


def test_second_download_comes_from_the_cache_and_changes_rebuild_it(make_client, wav, monkeypatch):
    client, app = make_client(provider=CountingMock(), user_requests_per_minute=10)
    h = login(client)
    note(client, app, h, wav, "kal 5 baje report bhej dunga")
    renders = []
    real = reports.render
    monkeypatch.setattr(reports, "render", lambda data: renders.append(1) or real(data))
    first = client.get("/reports/weekly.pdf", headers=h)
    second = client.get("/reports/weekly.pdf", headers=h)
    assert (first.headers["x-report-cache"], second.headers["x-report-cache"]) == ("miss", "hit")
    assert first.content == second.content and len(renders) == 1
    # Anything that changes what the report shows must rebuild it: a new note, a moved time, ticking one off.
    note(client, app, h, wav, "kal 7 baje call karunga", freq=600.0)
    third = client.get("/reports/weekly.pdf", headers=h)
    assert third.headers["x-report-cache"] == "miss" and "call karunga" in text_of(third.content)
    rem = client.get("/reminders", headers=h).json()[0]
    client.patch(f"/reminders/{rem['id']}", headers=h, json={"done": True})
    fourth = client.get("/reports/weekly.pdf", headers=h)
    assert fourth.headers["x-report-cache"] == "miss" and "1 done in the last 7 days" in text_of(fourth.content)
    assert client.get("/reports/weekly.pdf", headers=h).headers["x-report-cache"] == "hit"
    assert len(renders) == 3
    assert app.state.db.one("SELECT COUNT(*) AS n FROM report_cache")["n"] == 1  # one row per user, replaced


def test_the_cache_is_per_day(make_client, wav):
    client, app = make_client(provider=CountingMock())
    h = login(client)
    note(client, app, h, wav, "kal 5 baje report bhej dunga")
    db, now = app.state.db, datetime.now(IST)
    assert reports.weekly_report(db, 1, now)[1] is False
    assert reports.weekly_report(db, 1, now)[1] is True
    pdf, cached = reports.weekly_report(db, 1, now + timedelta(days=1))
    assert cached is False and (now + timedelta(days=1)).date().isoformat() in text_of(pdf)


def test_only_the_right_weeks_are_shown(make_client):
    client, app = make_client()
    login(client)
    db, now = app.state.db, datetime(2026, 11, 13, 12, 0, tzinfo=IST)

    def reminder(text, due, done=None):
        db.execute("INSERT INTO reminders (user_id, text, due_at, used_default_time, created_at, done_at) "
                   "VALUES (1, ?, ?, 0, ?, ?)", (text, due.isoformat(timespec="minutes"), iso(now), done and iso(done)))

    def job(n, transcript, created, status="done"):
        db.execute("INSERT INTO jobs (id, user_id, status, filename, extension, audio_sha256, audio_seconds, language, "
                   "transcript, created_at) VALUES (?, 1, ?, 'a.wav', 'wav', 'x', 1.0, 'hi-IN', ?, ?)",
                   (f"job{n}", status, transcript, iso(created)))

    reminder("in-two-days", now + timedelta(days=2))
    reminder("in-six-days-23h", now + timedelta(days=6, hours=23))
    reminder("in-eight-days", now + timedelta(days=8))
    reminder("was-yesterday", now - timedelta(days=1))
    reminder("finished-one", now + timedelta(days=1), done=now - timedelta(hours=3))
    reminder("finished-long-ago", now - timedelta(days=30), done=now - timedelta(days=20))
    job(1, "note-three-days-ago", now - timedelta(days=3))
    job(2, "note-nine-days-ago", now - timedelta(days=9))
    job(3, None, now - timedelta(hours=1), status="failed")
    data = reports.gather(db, 1, now)
    assert [r["text"] for r in data.upcoming] == ["in-two-days", "in-six-days-23h"]
    assert [r["text"] for r in data.overdue] == ["was-yesterday"]
    assert [n["text"] for n in data.notes] == ["", "note-three-days-ago"] and data.done_last_week == 1
    text = text_of(reports.render(data))
    for absent in ("in-eight-days", "finished-one", "finished-long-ago", "note-nine-days-ago"):
        assert absent not in text
    assert "Overdue" in text and "was-yesterday" in text and "could not be transcribed" in text
    assert "Sun 15 Nov, 12:00" in text  # times are shown in the server's zone


def test_indic_scripts_and_odd_text_render_without_missing_glyphs(make_client):
    """Every Gnani language's script must be drawn with a real font, not dropped or replaced."""
    from fontTools.ttLib import TTFont

    samples = {"Devanagari": "मैं कल फाइल भेज दूंगा", "Bengali": "আমি কাল পাঠাবো", "Gujarati": "હું કાલે મોકલીશ",
               "Gurmukhi": "ਮੈਂ ਕੱਲ੍ਹ ਭੇਜਾਂਗਾ", "Kannada": "ನಾನು ನಾಳೆ ಕಳುಹಿಸುತ್ತೇನೆ", "Malayalam": "ഞാൻ നാളെ അയയ്ക്കും",
               "Tamil": "நான் நாளை அனுப்புவேன்", "Telugu": "నేను రేపు పంపుతాను"}
    for script, sample in samples.items():
        cmap = TTFont(reports.FONT_DIR / f"NotoSans{script}-Regular.ttf").getBestCmap()
        missing = [ch for ch in sample if not ch.isspace() and ord(ch) not in cmap]
        assert not missing, f"{script} font lacks {missing}"
    now = datetime(2026, 11, 13, 12, 0, tzinfo=IST)
    rows = [{"when": now + timedelta(hours=i + 1), "text": t, "guessed": False} for i, t in enumerate(samples.values())]
    rows.append({"when": now + timedelta(days=1), "text": "emoji 😀, a very long word " + "x" * 400 + " and <b>tags</b>", "guessed": True})
    pdf = reports.render(reports.ReportData("a@example.com", "2026-11-13", rows, [], [], 0))
    assert pdf.startswith(b"%PDF")
    pages = PdfReader(io.BytesIO(pdf)).pages
    fonts = {page["/Resources"]["/Font"][k].get_object()["/BaseFont"] for page in pages for k in page["/Resources"]["/Font"]}
    for script in samples:
        assert any(script in name for name in fonts), f"{script} font not used; got {fonts}"
    text = text_of(pdf)
    assert "நான் நாளை" in text and "<b>tags</b>" in text and "(time guessed)" in text


def test_same_data_gives_the_same_bytes():
    now = datetime(2026, 11, 13, 12, 0, tzinfo=IST)
    data = reports.ReportData("a@example.com", "2026-11-13", [{"when": now, "text": "x", "guessed": False}], [], [], 0)
    assert reports.render(data) == reports.render(data)
