# My 10x Solution: Promise Keeper Voice

**Ishaan Rastogi** · backend track · code in [`voice-backend/`](voice-backend/) and [`web/`](web/)

## The problem

People make small promises all day by voice: "kal paanch baje report bhej dunga", "I'll call mom tonight", "naan naalaikku anuppuven". Most are forgotten, because saving one means opening a reminder app, typing it out and picking a time. In India there is a second obstacle: people speak Hindi, Tamil, Bengali and other languages, often mixed with English, and reminder tools don't understand that.

## What I built

Say the promise once and it becomes a reminder at the right time.

1. You record or upload a voice note (up to 30 seconds) in the web app, or type the sentence.
2. The server transcribes it with **Gnani Prisma v3.0** in `transliterate` mode, so every language comes back in Latin letters, the way people type on WhatsApp.
3. Rules decide whether it is *your own promise about the future*. A question, a past event, a habit, a refusal or someone else's promise is rejected, and the app says why.
4. The time is worked out ("kal paanch baje" is tomorrow at 17:00) and a reminder is saved. You can change the time.
5. When it falls due you get a push notification, and each week you can download a PDF of what is coming up.

It works for English, Hinglish and Hindi with rules checked against a labelled test set. Eight more languages (Bengali, Gujarati, Kannada, Malayalam, Marathi, Punjabi, Tamil, Telugu) have starter rules that no native speaker has checked yet; the app labels those results "Starter rules".

## The 10x claim, and what is measured

The claim: **saving a promise takes one spoken sentence instead of open-app, type, pick-a-time**, and it works in the language you actually speak.

What I have measured (run `voice-backend/scripts/measure.py`; Apple M5, Python 3.14, mock speech-to-text):

| Measurement | Result |
|---|---|
| Promises caught, on the Android app's 981 labelled messages | **393 of 453 (86.8%)** |
| False alarms on the 528 messages that are not promises | **0** |
| Server time from upload to saved reminder, excluding speech-to-text (median of 50) | **2.8 ms** |
| Same audio uploaded again | served from the cache: **0 paid calls** (50 of 50) |
| Weekly PDF, first build vs cached (100 voice notes) | 268 ms vs 1.4 ms |
| First live Gnani run: 5 clips of my own voice (Hinglish, Hindi, English, Punjabi; 31 s of audio in total) | 5 of 5 transcribed and classified correctly; every clip came back with the words I said |
| Gnani response time on those 5 calls | median **0.7 s** (0.4 to 7.1 s) |
| Gnani cost of those 5 calls, from the Gnani usage page | 0.20 credits in total: **0.03 to 0.05 credits per voice note** of 5 to 8 seconds (about 0.39 credits per minute of audio) |

Those 5 clips also showed two gaps in the time rules ("four p m" and Punjabi "panj vaje" were not read as clock times). Both are fixed, and the transcripts are now a regression test.

What is **not measured yet**: the seconds saved against typing a reminder. Five clips from one speaker is a smoke test, not an accuracy figure; I would rather leave those blank than estimate them.

## How it works

```
Web app (React PWA)  ->  FastAPI  ->  job queue (SQLite)  ->  worker  ->  Gnani STT (or mock)
                            |                                    |
                       auth, credit guard              detect promise + parse time
                            |                                    |
                       reminders, usage, PDF  <-----------  reminder saved
                            |
                       scheduler: push due reminders every minute; daily job at 08:00
```

The upload returns at once with a job id; a single worker thread does the transcription, which also matches Gnani's limit of one request at a time. Audio is deleted as soon as it is processed. Every paid call passes a credit guard (per-user rate and daily limits, a lifetime budget, an automatic pause if the provider rejects the key), and identical audio is never paid for twice.

## Concepts used

| Concept | Where |
|---|---|
| API endpoints | `voice-backend/app/main.py` |
| Database | `voice-backend/app/db.py` (SQLite; survives restarts) |
| Authentication | `voice-backend/app/auth.py` (scrypt passwords, bearer tokens stored hashed) |
| Background jobs | `app/jobs.py` (queue + worker) and `app/scheduler.py` with `app/push.py` and `app/daily.py` (timed) |
| Reporting (PDF) | `app/reports.py`: weekly report that renders all ten languages' scripts |
| Caching | transcript cache in `app/jobs.py`; report cache in `app/reports.py` |
| LLM integration (optional, untested against the real model) | `app/llm.py`: a second opinion from Gnani Evon v3.3 for starter-rule languages and missing times; strict JSON, timeout, daily cap, cache, cost log |
| **Swap:** rate limiting and quotas | `app/guard.py`. *Reason: speech-to-text credits are finite, so limits at the boundary are core to this product, not an extra.* |
| Stretch: test suite | `voice-backend/tests/` and `web/src/**/*.test.*`; one command, fully offline |

LLM integration is built as an optional backup and is **off by default**: Gnani's Evon model is not on their API and has to be self-hosted, and I have not had a machine to run it on, so it is tested against a fake server only. I don't count it among the concepts above.

## Run it

No key and no credits needed; speech-to-text is simulated.

```bash
cd web && npm ci && npm run build
```

```bash
cd ../voice-backend && python3 -m venv .venv && .venv/bin/pip install -r requirements-dev.txt && .venv/bin/python -m app.admin seed && .venv/bin/uvicorn app.main:app
```

Open http://127.0.0.1:8000 and log in as `demo@example.com` / `demo-password-123`. Upload `voice-backend/demo_audio/kal_report_bhej_dunga.wav` and a reminder for tomorrow 17:00 appears. The five-minute demo path, the API table and how to switch on real Gnani calls are in [`voice-backend/README.md`](voice-backend/README.md). Needs Python 3.12+ and Node 20+.

Or, with Docker instead of Python and Node: `docker compose up --build` from the repository root, then open the same address.

Run the tests (all offline): `.venv/bin/python -m pytest -q` in `voice-backend/`, and `npm test` in `web/`.

## Non-goals

- **No mobile app.** The Android app in `app/` is my teammate Piyush S's offline WhatsApp helper. I read its phrase list and test set and changed nothing in it.
- No accuracy claim for the eight starter-rule languages.
- No weekdays, dates or repeating reminders yet; times cover durations, today, tomorrow, day after, clock times and parts of the day.
