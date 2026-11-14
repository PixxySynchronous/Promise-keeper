# My 10x Solution: Promise Keeper

**Team:** Ishaan Rastogi and Piyush S · code: [`evon-app/`](evon-app/) (phone), [`voice-backend/`](voice-backend/) and [`web/`](web/) (server and web app)

## The problem

People make small promises all day in chat: "kal bhej dunga", "I'll call you after lunch", "शाम तक बता दूंगी". Most are forgotten, because saving one means leaving the chat, opening a reminder app, typing it again and picking a time. In India there is a second obstacle: people write Hindi in Latin letters, mixed with English and full of short forms ("kr dunga kal tk"), and reminder tools don't understand that.

## The solution

**1. On the phone: promises caught as you send them.** An Android app watches the messages *you* send in WhatsApp. When one is a promise, a notification asks "keep this as a reminder?", with the time already worked out. One tap instead of open-app, type, pick-a-time. Everything runs on the phone; the app has no internet permission.

A message is judged in two steps: phrase and tense rules first (a first-person future is a promise; questions, requests, past tense, refusals and other people's promises are rejected), then a small language model for what the rules cannot decide.

**2. How the small model learns: a large model teaches it.** The phone's classifier is taught by **Gnani Evon v3.3**, a 30-billion-parameter model that is far too big for a phone. A 4-bit copy of Evon ran once, on a laptop, and labelled 976 chat messages. It is asked two narrow questions per message (who acts, when, and is the sender sure; then: is this a commitment, a request, a plan, a maybe, a statement or a question), and its confidence is read from its own token probabilities. Those labels, overruled by 52 real false alarms that people reported from the app, train the 769 numbers the phone runs. Evon never ships in the app.

**3. Next stage, already running: voice.** The same promise detection sits behind a server and a web app, so a spoken promise becomes a reminder too: record a sentence, **Gnani Prisma v3.0** speech-to-text returns it in Latin letters (`transliterate`), the rules find the promise and its time, and a reminder is saved, with a push notification when due and a weekly PDF. This is the base for the next step of the project: voice notes and calls.

## Gnani models used

| Model | How it is used |
|---|---|
| **Evon v3.3** (`gnani/gnani-evon-v3.3-30B-A3B`, open weights, 4-bit GGUF) | Teacher: labels training messages offline for the phone's classifier |
| **Prisma v3.0 speech-to-text** (REST, `format=transliterate`) | Transcribes voice notes in the server and web app |

## What is measured

| Measurement | Result |
|---|---|
| Evon's labels against 976 hand-labelled messages | agree on **95.1%** (the questions were tuned on these messages, so this is not an independent score) |
| Phone pipeline with the Evon-taught model, on messages the model had not seen | **94.0%** of promises caught, **0.4%** of other messages flagged |
| Same pipeline with the earlier hand-labelled model | 95.1% caught, 0.8% flagged |
| Voice: 5 live clips (Hinglish, Hindi, English, Punjabi) | 5 of 5 transcribed and classified correctly; median reply 0.7 s; about 0.04 credits per note |
| Voice: same audio uploaded twice | second time served from the cache, 0 paid calls |

So the Evon-taught model halves the false alarms and catches slightly fewer promises. Not measured: seconds saved against typing a reminder, and accuracy on speech beyond those five clips.

## Concepts implemented (server, in `voice-backend/app/`)

| Concept | Where |
|---|---|
| API endpoints | `main.py` |
| Database | `db.py` (SQLite; survives restarts) |
| Authentication | `auth.py` (scrypt passwords, bearer tokens stored hashed) |
| Background jobs | `jobs.py` (queue and worker), `scheduler.py` with `push.py` and `daily.py` (timed) |
| Reporting (PDF) | `reports.py`: weekly report, renders ten languages' scripts |
| Caching | transcript cache in `jobs.py`, report cache in `reports.py` |
| **Swap:** rate limiting and quotas | `guard.py`. *Reason: speech-to-text credits are finite, so limits at the boundary are core here; the LLM is used offline as a teacher, not behind an endpoint.* |
| Also | test suites (`voice-backend/tests/`, `web/src/`, `evon-app/keeper/src/test/`), Docker (`docker compose up --build`) |

## Run it

Server and web app (no key needed; speech-to-text is simulated). Needs Python 3.12+ and Node 20+:

```bash
cd web && npm ci && npm run build
```

```bash
cd ../voice-backend && python3 -m venv .venv && .venv/bin/pip install -r requirements-dev.txt && .venv/bin/python -m app.admin seed && .venv/bin/uvicorn app.main:app
```

Open http://127.0.0.1:8000, log in as `demo@example.com` / `demo-password-123`, and upload `voice-backend/demo_audio/kal_report_bhej_dunga.wav`: a reminder for tomorrow 17:00 appears. The five-minute demo path is in [`voice-backend/README.md`](voice-backend/README.md).

Phone app (JDK 17, Android SDK, Git LFS): `cd evon-app && ./gradlew :keeper:testDebugUnitTest :keeper:assembleDebug`. How the model is taught, step by step: [`evon-app/README.md`](evon-app/README.md).

## Who did what

- **Ishaan Rastogi:** the Evon teaching pipeline and the Evon-taught phone app (`evon-app/`), rules from real false alarms, the voice server and the web app.
- **Piyush S:** the original offline Android app (`app/`), its phrase list and the labelled example messages that both parts are tested on.

## Not built

- No cloud in the phone app, and no reading of received messages or other apps.
- No accuracy claim for the eight languages that only have starter rules in the voice part.
- Voice notes inside WhatsApp and phone calls are the next stage, not in this version.
