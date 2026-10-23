"""Collects false alarms users shared through the "Share false alarms" GitHub form into tools/contributed/.

Each issue made from .github/ISSUE_TEMPLATE/false-alarms.yml holds an app export, pasted as text or attached as a
.json file. This saves each one as tools/contributed/github-issue-<number>.json (skipping issues already saved), so
the next run of tools/train_semantic_head.py learns from them. Only the message texts are kept.

Usage: python3 -I tools/fetch_contributed.py          (set GITHUB_TOKEN to avoid GitHub's hourly limit)
Then:  .venv/bin/python -I tools/train_semantic_head.py
"""
import json
import os
import re
import urllib.request
from pathlib import Path

REPO = "PixxySynchronous/Promise-keeper"
FORM_TITLE = "False alarms from the app"
FORMAT = "promise-keeper-false-alarms"
out = Path(__file__).resolve().parent / "contributed"


def get(url):
    headers = {"Accept": "application/vnd.github+json", "User-Agent": "promise-keeper-fetch"}
    if os.environ.get("GITHUB_TOKEN"):
        headers["Authorization"] = "Bearer " + os.environ["GITHUB_TOKEN"]
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=30) as response:
        return response.read().decode("utf-8")


def export_from(body):
    """The app export in an issue body: pasted JSON, or the first attached .json file."""
    start, end = body.find("{"), body.rfind("}")
    if 0 <= start < end:
        try:
            return json.loads(body[start:end + 1])
        except json.JSONDecodeError:
            pass
    for link in re.findall(r"https://github\.com/user-attachments/files/\d+/[^\s)\]]+\.json", body):
        try:
            return json.loads(get(link))
        except (OSError, json.JSONDecodeError):
            continue
    return None


def issues():
    page = 1
    while True:
        batch = json.loads(get(f"https://api.github.com/repos/{REPO}/issues?state=all&per_page=100&page={page}"))
        if not batch:
            return
        for issue in batch:
            labels = {label["name"] for label in issue.get("labels", [])}
            if "pull_request" not in issue and ("false-alarms" in labels or issue["title"].startswith(FORM_TITLE)):
                yield issue
        page += 1


saved = 0
for issue in issues():
    path = out / f"github-issue-{issue['number']}.json"
    if path.exists():
        continue
    data = export_from(issue.get("body") or "")
    if not isinstance(data, dict) or data.get("format") != FORMAT:
        print(f"#{issue['number']}: no app export found, skipped ({issue['html_url']})")
        continue
    # Keep only message texts; nothing else from the issue is trusted or needed.
    messages = [{"text": m["text"].strip()} for m in data.get("messages", []) if isinstance(m, dict) and str(m.get("text", "")).strip()]
    path.write_text(json.dumps({"format": FORMAT, "version": 1, "source": issue["html_url"], "label": "not_a_promise", "messages": messages},
                               ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"#{issue['number']}: saved {len(messages)} messages to {path.name}")
    saved += 1
print(f"{saved} new file(s). Review them for anything private, then retrain.")
