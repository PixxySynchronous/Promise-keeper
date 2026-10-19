# Contributed false alarms

These files are messages that Promise Keeper wrongly flagged as promises, exported by users from the app. The training script (`tools/train_semantic_head.py`) adds every `.json` file in this folder as "not a promise" examples.

## How to contribute

1. In the app, open the **False alarms** tab and check the messages. **Delete any message that contains something private**, like a name, phone number or address. You can also edit the file after exporting.
2. Tap **Export** and save or share the file.
3. Either:
   - **Pull request:** add the file to this folder. Name it something like `false-alarms-<your-github-name>-<date>.json`.
   - **Issue:** open an issue on the repository and attach the file.
   - **Direct:** send it to the maintainer.

The file contains only the message text and what flagged it (a phrase, or the AI model with its score). It never includes contact names.

## Format

```json
{
  "format": "promise-keeper-false-alarms",
  "version": 1,
  "app_version": "1.1",
  "exported_at": "2026-10-19T16:30:00",
  "label": "not_a_promise",
  "messages": [
    {"text": "ok fatty", "detected_by": "semantic AI (0.99)", "detected_at": "2026-10-19T16:01:12"}
  ]
}
```
