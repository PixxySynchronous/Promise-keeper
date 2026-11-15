// due_at arrives as server-local time with an offset ("2026-11-14T17:00+05:30"). We show and edit that
// wall-clock time as written, whatever the browser's own time zone is, so "5 pm" stays "5 pm".

/** "2026-11-14T17:00+05:30" → "2026-11-14T17:00", the value an <input type="datetime-local"> wants. */
export const toInputValue = (dueAt: string) => dueAt.slice(0, 16);

const wallClock = new Intl.DateTimeFormat(undefined, {
  weekday: "short",
  day: "numeric",
  month: "short",
  hour: "numeric",
  minute: "2-digit",
  timeZone: "UTC",
});

export function formatDue(dueAt: string): string {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(dueAt)) return dueAt;
  const asUtc = new Date(toInputValue(dueAt) + ":00Z");
  return Number.isNaN(asUtc.getTime()) ? dueAt : wallClock.format(asUtc);
}

export function isOverdue(dueAt: string, now: Date = new Date()): boolean {
  const when = new Date(dueAt);
  return !Number.isNaN(when.getTime()) && when.getTime() < now.getTime();
}

/** 75 → "1 min 15 s"; 7500 → "2 h 5 min". For Retry-After countdowns, which can run until midnight. */
export function formatWait(seconds: number): string {
  const s = Math.max(0, Math.ceil(seconds));
  if (s < 60) return `${s} s`;
  if (s < 3600) return `${Math.floor(s / 60)} min ${s % 60} s`;
  return `${Math.floor(s / 3600)} h ${Math.floor((s % 3600) / 60)} min`;
}

export const LANGUAGES: ReadonlyArray<readonly [code: string, name: string]> = [
  ["indic-auto", "Auto (Hindi, Hinglish, English)"],
  ["hi-IN", "Hindi"],
  ["en-IN", "English"],
  ["bn-IN", "Bengali"],
  ["gu-IN", "Gujarati"],
  ["kn-IN", "Kannada"],
  ["ml-IN", "Malayalam"],
  ["mr-IN", "Marathi"],
  ["pa-IN", "Punjabi"],
  ["ta-IN", "Tamil"],
  ["te-IN", "Telugu"],
];

export const languageName = (code: string | null | undefined) =>
  LANGUAGES.find(([c]) => c === code)?.[1] ?? code ?? "Unknown";
