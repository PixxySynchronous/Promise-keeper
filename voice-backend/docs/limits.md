# Credit limits and safeguards

Gnani credits are finite and the credit unit is not documented, so every speech-to-text call is treated as money. This page lists each safeguard, its default, and the setting that changes it.

## Two switches before any real call
`STT_PROVIDER=gnani` **and** `GNANI_LIVE_CALLS_ENABLED=true` **and** a non-empty `GNANI_API_KEY`. With any one missing, uploads get `503 GNANI_NOT_ENABLED` and the client is never built (`GnaniProvider.__init__` raises).

## Safeguards (where, default, env var)
| Safeguard | Default | Env var | Code |
|---|---|---|---|
| Upload size | 2 MB | `MAX_UPLOAD_BYTES` | `audio.validate` (reads at most limit + 1 byte) |
| Duration, measured from the file, never trusted from the client | 0.5–30 s (30 is Gnani's hard max and can't be raised) | `MIN_AUDIO_SECONDS`, `MAX_AUDIO_SECONDS` | `audio.validate` |
| Formats | wav mp3 ogg flac aac m4a | none | `config.SUPPORTED_EXTENSIONS` |
| Per-user rate | 3 / minute | `USER_REQUESTS_PER_MINUTE` | `guard.check_enqueue` → 429 + Retry-After |
| Per-user pending jobs | 2 | `USER_MAX_PENDING_JOBS` | 429 |
| Per-user daily | 20 notes, 300 s audio | `USER_DAILY_REQUESTS`, `USER_DAILY_AUDIO_SECONDS` | 429 until local midnight |
| Server daily | 100 notes | `GLOBAL_DAILY_REQUESTS` | 503 |
| Queue depth | 20 | `MAX_QUEUE_DEPTH` | 503 |
| **Lifetime budget** | 300 billable calls **and** 3,600 billable seconds | `BUDGET_TOTAL_REQUESTS`, `BUDGET_TOTAL_AUDIO_SECONDS` | checked at enqueue (counting queued work) **and** again right before each call |
| Concurrency | 1 call in flight (Gnani's account limit) | none (single worker thread) | `jobs.Worker` |
| Retries | only 429/5xx/timeouts, at most 1 retry, backoff; every attempt is billed in the log | `GNANI_MAX_RETRIES` (0–3) | `jobs._transcribe_with_retries` |
| Auto-pause | 401/402/403 from Gnani pauses all calls | `python -m app.admin resume` | `guard.pause_provider` |
| Cache | same audio + model + format + language → no call | none | `transcript_cache` |
| Sign-ups | open locally; set a code before going public | `REGISTRATION_CODE` | `/auth/register` |
| Login guessing | 10 tries / 15 min per email | `LOGIN_ATTEMPTS_PER_15_MIN` | `auth.LoginLimiter` |
| Kill switch | none | `STT_PROVIDER=disabled` | 503 for every upload |
| Crash safety | jobs stuck in `processing` after a restart are failed, not retried (they may already be billed) | none | `jobs.recover_interrupted` |

## Monitoring
- `cd voice-backend && .venv/bin/python -m app.admin usage`: billable calls and seconds vs budget, cache hits, pause state, and the last 20 provider events.
- `GET /usage/me`: one user's usage today plus the limits.
- Compare `billable_requests_total` with the Gnani dashboard (app.gnani.ai/voice) after the first few live calls to learn what one call costs, then set the budgets so they stay well under the remaining credits.

## Suggested first live run
1. Ask Gnani or check the dashboard for the credit unit.
2. Set `BUDGET_TOTAL_REQUESTS=5` and make 3–5 real calls with your own voice. Compare the dashboard to the cost log.
3. Set real budgets with a 20% margin, and set `REGISTRATION_CODE` before sharing the URL.
