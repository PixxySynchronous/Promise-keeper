# Promise Keeper Voice (backend)

Say a promise out loud, in your own language, and get a reminder. *"Kal paanch baje report bhej dunga"* becomes a reminder for tomorrow at 17:00.

Indian WhatsApp users speak Hindi, Tamil, Bengali and other languages, and type them in English letters. This service takes a voice note, transcribes it with **Gnani Prisma v3.0** in `transliterate` mode (Latin script for every language), checks whether it is the speaker's own promise (an explicit tense step: future vs past, habit, question, refusal, someone else's promise), works out the time, and stores a reminder.

**Where this fits:** the project's first half catches promises you *type* in WhatsApp, on the phone ([Promise Keeper Android app](../README.md), and its Evon-taught copy in [`evon-app/`](../evon-app)). This is the second half, for promises you *say*: it is built and running, and it is the base for the next stage, voice notes and calls. The overview of both halves is [Promise Keeper - Project Overview.docx](../Promise%20Keeper%20-%20Project%20Overview.docx).

It is the backend companion to the offline Android app. It reuses the app's phrase list directly (read from `CommitmentPhrases.kt`) and is evaluated on the app's 981-message test set. The app itself stays offline; nothing here changes it.

## Run it (mock speech-to-text, no key, no credits)
You need Python 3.12 or newer and Node 20 or newer. From the repository root:
```bash
cd web && npm ci && npm run build
```
```bash
cd ../voice-backend && python3 -m venv .venv && .venv/bin/pip install -r requirements-dev.txt && .venv/bin/python -m app.admin seed && .venv/bin/uvicorn app.main:app
```
Open http://127.0.0.1:8000 and log in as `demo@example.com` / `demo-password-123`. The first command builds the web app; if you skip it, the same address shows a plain demo page instead (always available at `/legacy`). The second installs the server, creates the demo user and the files in `demo_audio/`, and starts it. Stop it with Ctrl+C.

**Five-minute demo path**
1. Upload `demo_audio/kal_report_bhej_dunga.wav`. The card says *Promise found*, due tomorrow at 17:00, and the reminder joins the list.
2. Upload `demo_audio/past_not_a_promise.wav`. *No promise found*, with the reason "past tense".
3. Pick **Tamil** and upload `demo_audio/tamil_naalai_anuppuven.wav`. A promise is found, marked *Starter rules*.
4. Upload any file again straight away. The fourth upload inside one minute is refused: "Max 3 voice notes per minute", with a countdown.
5. When the countdown ends, upload the first file again. The card says the earlier transcript was reused and no credits were spent.
6. Change a reminder's time, tick one off, then press **Download weekly report**.
7. In a second terminal, in `voice-backend/`, run `.venv/bin/python -m app.admin usage` to see the cost log and budget.

With the mock provider, the transcript of a demo file comes from `demo_audio/manifest.json` and the transcript of any other file is its file name (`kal_5_baje_call_karunga.wav` → "kal 5 baje call karunga"). Record in the browser to test real audio once Gnani is enabled.

## Or run it with Docker
From the repository root, with Docker running:
```bash
docker compose up --build
```
Open http://127.0.0.1:8000 and log in as the demo user above. It builds the web app, starts the server with simulated speech-to-text (no key, no credits), creates the demo user, and writes the demo voice notes to `voice-backend/demo_audio/` on your computer so you can upload them. Reminders are kept in a Docker volume; `docker compose down -v` deletes them. The compose file never reads `.env`, so it cannot spend Gnani credits.

## Web app (`../web`, React + TypeScript)
`npm run build` (above) writes `web/dist`, which the backend serves at `/`. Rebuild after changing anything in `web/`; the server picks it up without a restart.

For live reload while developing, keep the backend running and, in a second terminal inside `web/`, run:
```bash
npm run dev
```
Then open http://localhost:5173. Vite forwards `/auth`, `/voice-notes`, `/detect`, `/reminders`, `/usage` and `/health` to the backend on `127.0.0.1:8000`. Tests: `npm test`.

The web app covers login and sign-up, recording (WAV, 30 s limit) or uploading a voice note, a free typed-text check, the result card (transcript, language, tense, matched phrase, reasons, editable due time), the reminders list (upcoming, done, delete) and today's usage against the limits. Log in with the demo user from the section above.

It is an installable PWA: Chrome offers "Install app" (also a button in the header), and the page opens without a connection and says it is offline. The service worker (`web/public/sw.js`) stores only the app's own files; reminders, transcripts and audio are never cached, so they need a connection.

## Notifications when a reminder is due (Web Push)
Off by default. To switch it on:
```bash
.venv/bin/python -m app.admin vapid
```
Paste the three printed lines into `.env` (the private key is a secret; set a real contact in `VAPID_SUBJECT`), load the file and restart the server. In the web app, press **Turn on notifications** in the Reminders card.

A scheduler thread checks once a minute and sends one push per due reminder; each reminder is marked before it is sent, so a restart never sends it twice. Tapping the notification opens the app on that reminder. Needs HTTPS (or `localhost`) and a browser with Web Push; on an iPhone the app must be added to the Home Screen first. If the host puts the server to sleep, pushes go out when it wakes (up to 12 hours late; older ones are skipped).

## Optional: a second opinion from Gnani Evon (self-hosted LLM)
Off by default, and the app works fully without it. Evon v3.3 (`gnani/gnani-evon-v3.3-30B-A3B`, a 30B-parameter text model with open weights) is not on Gnani's API, so Gnani credits don't cover it: you run it yourself behind an OpenAI-compatible server (vLLM, llama.cpp with a GGUF build, …), which needs a machine that can hold a 30B model, typically a GPU with 24 GB or more or a large-memory Mac. Then set in `.env`:
```
LLM_PROVIDER=openai_compat
EVON_BASE_URL=http://127.0.0.1:8001/v1
```
The rules stay in charge. The model is asked only when they are on weak ground: the language has starter rules, or a promise was found with no time we could read. It must answer with exactly `{"is_promise", "task", "time_text", "confidence"}`; anything else is discarded. It can change the yes/no only for starter-rule languages and only above `LLM_MIN_CONFIDENCE`; for a missing time it supplies the words, and our own parser turns them into a time. Each call has a timeout, there is a daily cap (`LLM_DAILY_REQUESTS`), identical sentences are answered from a cache, and `python -m app.admin usage` shows tokens and errors. If the model is slow, down or wrong-shaped, the note is processed by the rules as usual.

`LLM_PROVIDER=mock` runs the same path offline for a demo; it only repeats the rules' answer.

**Status:** this optional step is tested against a fake server only; it has not been run against a real Evon server yet. Evon itself has been run: a 4-bit copy under llama.cpp's `llama-server` on a 24 GB Mac labelled the training messages for the phone app (see [`evon-app/README.md`](../evon-app/README.md)), and that server speaks the same OpenAI-compatible protocol this step expects.

## Use Gnani (spends credits)
1. `cp .env.example .env` and set `STT_PROVIDER=gnani`, `GNANI_LIVE_CALLS_ENABLED=true`, `GNANI_API_KEY=…`, and **low budgets first** (`BUDGET_TOTAL_REQUESTS=5`).
2. Check the file before spending anything: `.venv/bin/python scripts/check_env.py` prints the settings that matter (never the key) and says whether it is safe to start.
3. `set -a; source .env; set +a` and start the server.
4. Check `python -m app.admin usage` against the dashboard at app.gnani.ai/voice.

All limits and how to tune them are in [docs/limits.md](docs/limits.md).

## API
| Method | Path | Auth | Notes |
|---|---|---|---|
| POST | `/auth/register` | none | `{email, password, registration_code?}` → 201 / 409 / 403 / 422 |
| POST | `/auth/login` | none | → `{token}`; 401 wrong password; 429 too many tries |
| POST | `/auth/logout` | bearer | 204 |
| POST | `/voice-notes` | bearer | multipart `audio_file` + optional `language` (`indic-auto`, `hi-IN`, `ta-IN`, …) → **202** + job id; 400/413/415/422 bad audio; 429 rate or quota; 503 budget, queue or provider off |
| GET | `/voice-notes/{id}` | bearer | status, transcript, detection (tense, matched, reasons), reminder |
| GET | `/voice-notes` | bearer | your last 50 |
| POST | `/detect` | bearer | free text check, no speech-to-text |
| GET | `/reminders` · DELETE `/reminders/{id}` | bearer | each reminder has `done_at` (null = open) |
| PATCH | `/reminders/{id}` | bearer | `{due_at?: "2026-11-14T17:00", done?: true}` → the updated reminder; 422 for a bad, past or far-off time; 404 if it isn't yours |
| GET | `/push/config` | none | `{enabled, public_key}` for the browser to subscribe |
| POST · DELETE | `/push/subscribe` | bearer | store / remove this browser's push subscription; 503 if the server has no VAPID keys; 422 unless the address is a known push service |
| GET | `/reports/weekly.pdf` | bearer | PDF: your open reminders for the next 7 days, overdue ones, and your voice notes from the last 7 days. `X-Report-Cache: hit` or `miss` |
| GET | `/usage/me` | bearer | today's usage + limits |
| GET | `/health` | none | provider, live or not |

Interactive docs: http://127.0.0.1:8000/docs

## Concepts implemented
| Concept | Where it lives |
|---|---|
| API endpoints | `app/main.py` |
| Database | `app/db.py` (SQLite; survives restarts; queued jobs resume) |
| Authentication | `app/auth.py` + `current_user` in `app/main.py` |
| Background jobs | `app/jobs.py` (queue + worker thread; upload returns 202) and `app/scheduler.py` with two timed tasks: `app/push.py` (every minute: push due reminders) and `app/daily.py` (08:00 daily: pre-build weekly reports, delete voice-note rows older than 30 days) |
| Caching | `transcript_cache` in `app/jobs.py`: identical audio is never paid for twice. `report_cache` in `app/reports.py`: the weekly PDF is built once per user per day and rebuilt only when its data changes |
| PDF / document generation | `app/reports.py`: weekly report with Noto fonts and text shaping, so all ten languages' scripts render |
| LLM integration *(optional, off by default)* | `app/llm.py`: a second opinion from Gnani Evon v3.3 on your own server, for starter-rule languages and missing times. Strict JSON only; timeout, daily cap, cache and a cost log |
| Rate limiting / quotas *(swap)* | `app/guard.py`. Speech-to-text credits are finite, so limits at the boundary are core here, not an extra. |
| Test suite *(stretch)* | `tests/`, one command, fully offline |


The overview and the measured numbers are in [Promise Keeper - Project Overview.docx](../Promise%20Keeper%20-%20Project%20Overview.docx). Reproduce the numbers with `.venv/bin/python scripts/measure.py` (offline).

**Non-goal:** no mobile app changes and no network in the Android app.

## Tests
```bash
.venv/bin/python -m pytest -q
```
Outbound network is blocked during tests, so they can never spend credits. Detection gate on the app's eval set: 393 of 453 promises caught (recall 0.868), 0 false alarms on 528 other messages. The web app has its own tests: `cd ../web && npm test`.

## Limits of the current version
- Rules for Bengali, Gujarati, Kannada, Malayalam, Marathi, Punjabi, Tamil and Telugu are **starter rules not yet checked by native speakers** (`verified_rules: false` in responses).
- Gnani says its `transliterate` accuracy is not yet measured. Five real transcripts (one speaker) are kept as a regression test in `tests/fixtures/transliterate_samples.json`; that is a smoke test, not an accuracy figure.
- Times: durations, today/tomorrow/day-after, clock times and parts of the day. No weekdays, dates or repeats yet.

## Credits
Android app and detection rules: Piyush S. Voice backend, web app and the Evon-taught copy of the app: Ishaan Rastogi. Speech-to-text: [Gnani.ai](https://docs.gnani.ai) Prisma v3.0.
