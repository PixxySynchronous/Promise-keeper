# Contributed false alarms

These files are messages that Promise Keeper wrongly flagged as promises, exported by users from the app. The training script (`tools/train_semantic_head.py`) adds every `.json` file in this folder as "not a promise" examples.

## How to contribute

1. In the app, open the **False alarms** tab and check the messages. **Delete any message that contains something private**, like a name, phone number or address. Whatever you post on GitHub is public.
2. Tap **Export → Copy & open GitHub form**. Your browser opens the [Share false alarms](https://github.com/PixxySynchronous/Promise-keeper/issues/new?template=false-alarms.yml) form (sign in to GitHub, or make a free account).
3. Long-press the big box, tap **Paste**, then **Submit new issue**. That's it.

Prefer a file? Tap **Export → Share file** instead and attach it to the same form, or, if you know git, add it to this folder in a pull request (name it like `false-alarms-<your-github-name>-<date>.json`).

## For the maintainer: collecting them

```sh
python3 -I tools/fetch_contributed.py                 # saves each form as github-issue-<number>.json here
.venv/bin/python -I tools/train_semantic_head.py      # retrains with them
```

The fetch script reads every issue made from the form (label `false-alarms`), takes the pasted or attached export, keeps only the message texts, and skips issues it already saved. Read the new files for anything private before committing them. Set `GITHUB_TOKEN` if GitHub's hourly limit for anonymous requests is reached.

The file contains only the message text and what flagged it (a phrase, or the AI model with its score). It never includes contact names.

## Format

```json
{
  "format": "promise-keeper-false-alarms",
  "version": 1,
  "app_version": "1.3",
  "exported_at": "2026-10-19T16:30:00",
  "label": "not_a_promise",
  "messages": [
    {"text": "ok fatty", "detected_by": "semantic AI (0.99)", "detected_at": "2026-10-19T16:01:12"}
  ]
}
```
