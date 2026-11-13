"""Checks a .env file before a live Gnani run and prints a verdict. It never prints the key or any other secret.

    cd voice-backend && .venv/bin/python scripts/check_env.py            # checks ./.env
    .venv/bin/python scripts/check_env.py path/to/file --max-calls 5
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app.config import Settings  # noqa: E402  (the one place the real Gnani address is written down)

SECRET = ("GNANI_API_KEY", "EVON_API_KEY", "VAPID_PRIVATE_KEY", "REGISTRATION_CODE", "DEMO_PASSWORD")
TRUE = ("1", "true", "yes", "on")


def parse(path: Path) -> tuple[dict[str, str], list[str]]:
    values, problems, seen = {}, [], {}
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            problems.append(f"line {number}: not NAME=value (the server's loader may fail on it)")
            continue
        name, value = line.split("=", 1)
        name, value = name.strip(), value.strip()
        if name.startswith("export "):
            name = name[7:].strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        elif " #" in value:
            value = value.split(" #", 1)[0].strip()  # the shell treats this as a comment too
        if name in seen:
            problems.append(f"{name} is set twice (lines {seen[name]} and {number}); the LATER one wins")
        seen[name] = number
        values[name] = value
    return values, problems


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("path", nargs="?", default=".env")
    ap.add_argument("--max-calls", type=int, default=5, help="largest BUDGET_TOTAL_REQUESTS allowed for this run")
    args = ap.parse_args()
    path = Path(args.path)
    if not path.is_file():
        print(f"STOP: {path} does not exist. Run: cp .env.example .env")
        return 2
    env, stop = parse(path)
    warn: list[str] = []
    get = lambda name, default="": env.get(name, default)  # noqa: E731

    provider = get("STT_PROVIDER", "mock").lower()
    live = get("GNANI_LIVE_CALLS_ENABLED", "false").lower() in TRUE
    key = get("GNANI_API_KEY")
    will_call = provider == "gnani" and live and bool(key)

    print(f"File: {path}")
    print(f"  STT_PROVIDER              = {provider}")
    print(f"  GNANI_LIVE_CALLS_ENABLED  = {str(live).lower()}")
    print(f"  GNANI_API_KEY             = {'set, ' + str(len(key)) + ' characters' if key else 'EMPTY'}")
    for name in ("BUDGET_TOTAL_REQUESTS", "BUDGET_TOTAL_AUDIO_SECONDS", "GNANI_MAX_RETRIES", "GNANI_MODEL", "GNANI_FORMAT",
                 "MAX_AUDIO_SECONDS", "USER_REQUESTS_PER_MINUTE", "USER_DAILY_REQUESTS", "LLM_PROVIDER"):
        print(f"  {name:<25} = {get(name, '(not set: default)')}")
    print(f"  => Real Gnani calls will be made: {'YES' if will_call else 'NO'}")

    if provider not in ("mock", "gnani", "disabled"):
        stop.append(f"STT_PROVIDER must be mock, gnani or disabled, not {provider!r}: the server will not start")
    if key and (key.startswith("<") or "your key" in key.lower() or len(key) < 16 or " " in key):
        stop.append("GNANI_API_KEY does not look like a real key (placeholder, too short, or contains a space)")
    if provider == "gnani" and live and not key:
        stop.append("Gnani is selected but GNANI_API_KEY is empty: every upload will be refused")
    if provider == "gnani" and not live:
        warn.append("STT_PROVIDER=gnani but GNANI_LIVE_CALLS_ENABLED is not true: uploads will be refused (no credits spent)")

    def number(name: str, default: float) -> float | None:
        raw = get(name)
        if raw == "":
            return default
        try:
            return float(raw)
        except ValueError:
            stop.append(f"{name}={raw!r} is not a number: the server will not start")
            return None

    budget = number("BUDGET_TOTAL_REQUESTS", 300)
    retries = number("GNANI_MAX_RETRIES", 1)
    seconds = number("BUDGET_TOTAL_AUDIO_SECONDS", 3600)
    for name, default in (("MAX_AUDIO_SECONDS", 30), ("MAX_UPLOAD_BYTES", 2_000_000), ("USER_REQUESTS_PER_MINUTE", 3),
                          ("USER_DAILY_REQUESTS", 20), ("GLOBAL_DAILY_REQUESTS", 100), ("GNANI_TIMEOUT_SECONDS", 20)):
        number(name, default)
    if will_call:
        if budget is not None and budget > args.max_calls:
            stop.append(f"BUDGET_TOTAL_REQUESTS={budget:g} allows more than {args.max_calls} paid calls. Set it to {args.max_calls}.")
        if budget is not None and budget <= 0:
            stop.append("BUDGET_TOTAL_REQUESTS is 0 or less: every upload will be refused")
        if retries:
            warn.append(f"GNANI_MAX_RETRIES={retries:g}: a failed call is retried and billed again (still inside the budget). 0 is safer for a first run.")
        if seconds is not None and seconds < 15 * args.max_calls:
            warn.append(f"BUDGET_TOTAL_AUDIO_SECONDS={seconds:g} may run out before {args.max_calls} clips of 15 s")
        if get("GNANI_FORMAT", "transliterate") != "transliterate":
            warn.append(f"GNANI_FORMAT={get('GNANI_FORMAT')}: the detection rules expect transliterate")
        if get("GNANI_MODEL", "prisma-v3.0") != "prisma-v3.0":
            warn.append(f"GNANI_MODEL={get('GNANI_MODEL')}: expected prisma-v3.0")
        if get("GNANI_URL") and get("GNANI_URL").rstrip("/") != Settings.gnani_url.rstrip("/"):
            stop.append("GNANI_URL points somewhere other than Gnani: your key and audio would be sent there")
    if get("LLM_PROVIDER", "none").lower() not in ("none", "mock", "openai_compat"):
        stop.append("LLM_PROVIDER must be none, mock or openai_compat: the server will not start")
    elif get("LLM_PROVIDER", "none").lower() != "none":
        warn.append(f"LLM_PROVIDER={get('LLM_PROVIDER')}: keep it none for a first Gnani run, so only Gnani is measured")
    if bool(get("VAPID_PRIVATE_KEY")) != bool(get("VAPID_PUBLIC_KEY")):
        warn.append("only one of VAPID_PRIVATE_KEY / VAPID_PUBLIC_KEY is set: notifications stay off")
    for name, value in env.items():
        if value.startswith(("<", "…")) or value in ("...", "changeme"):
            warn.append(f"{name} still holds a placeholder")
        if name not in SECRET and len(value) > 60:
            warn.append(f"{name} has an unusually long value; check it isn't a key pasted on the wrong line")

    print()
    for line in stop:
        print("STOP:", line)
    for line in warn:
        print("WARN:", line)
    if stop:
        print("\nVerdict: DO NOT START the server yet. Fix the STOP lines.")
        return 1
    print("\nVerdict:", f"OK to start. At most {budget:g} paid Gnani calls can be made." if will_call
          else "OK to start. No Gnani calls will be made (offline or refused).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
