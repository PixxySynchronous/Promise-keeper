"""One background thread for timed work (due-reminder pushes now; the daily report job later).

Tasks take the current time as an argument, so tests call them directly with any "now" and no thread.
"""
from __future__ import annotations

import logging
import threading
from datetime import datetime
from typing import Callable
from zoneinfo import ZoneInfo

log = logging.getLogger("promise_keeper.scheduler")
Task = Callable[[datetime], object]


class Scheduler:
    def __init__(self, timezone: str, interval_seconds: float = 60.0):
        self._zone, self._interval = ZoneInfo(timezone), interval_seconds
        self._tasks: list[tuple[str, Task]] = []
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._run, name="scheduler", daemon=True)

    def add(self, name: str, task: Task) -> None:
        self._tasks.append((name, task))

    def tick(self, now: datetime | None = None) -> None:
        """Runs every task once. One failing task never stops the others or the loop."""
        now = now or datetime.now(self._zone)
        for name, task in self._tasks:
            try:
                task(now)
            except Exception:
                log.exception("scheduled task %s failed", name)

    def start(self) -> None:
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        self._thread.join(timeout=5)

    def _run(self) -> None:
        while not self._stop.is_set():
            self.tick()
            self._stop.wait(self._interval)
