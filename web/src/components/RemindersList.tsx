import { type ReactNode, useEffect, useRef, useState } from "react";
import type { Reminder } from "../api";
import { formatDue, isOverdue } from "../format";
import ErrorNotice from "./ErrorNotice";

interface Props {
  reminders: Reminder[] | null;
  loadError: unknown;
  onReload: () => void;
  onSetDone: (id: number, done: boolean) => Promise<void>;
  onDelete: (id: number) => Promise<void>;
  /** The reminder a notification was tapped for: scrolled into view and marked. */
  highlightId?: number | null;
  /** Shown under the title (the notifications switch). */
  children?: ReactNode;
}

export default function RemindersList({ reminders, loadError, onReload, onSetDone, onDelete, highlightId, children }: Props) {
  const [busyId, setBusyId] = useState<number | null>(null);
  const [error, setError] = useState<unknown>(null);
  const highlighted = useRef<HTMLLIElement | null>(null);
  const found = highlightId != null && !!reminders?.some((r) => r.id === highlightId);

  useEffect(() => {
    if (found) highlighted.current?.scrollIntoView?.({ block: "center" });
  }, [found, highlightId]);

  async function act(id: number, action: () => Promise<void>) {
    setBusyId(id);
    setError(null);
    try {
      await action();
    } catch (err) {
      setError(err);
    } finally {
      setBusyId(null);
    }
  }

  const upcoming = (reminders ?? []).filter((r) => !r.done_at);
  const done = (reminders ?? []).filter((r) => r.done_at);

  const item = (r: Reminder) => (
    <li
      key={r.id}
      ref={r.id === highlightId ? highlighted : undefined}
      aria-current={r.id === highlightId ? "true" : undefined}
      className={["reminder", r.done_at ? "done" : "", r.id === highlightId ? "highlight" : ""].filter(Boolean).join(" ")}
    >
      <div>
        <p className="when">
          {formatDue(r.due_at)}
          {!r.done_at && isOverdue(r.due_at) && <span className="badge warn">Overdue</span>}
        </p>
        <p className="what">{r.text}</p>
      </div>
      <div className="actions">
        <button
          type="button"
          disabled={busyId === r.id}
          onClick={() => act(r.id, () => onSetDone(r.id, !r.done_at))}
          aria-label={`${r.done_at ? "Reopen" : "Mark done"}: ${r.text}`}
        >
          {r.done_at ? "Reopen" : "Done"}
        </button>
        <button
          type="button"
          className="danger"
          disabled={busyId === r.id}
          onClick={() => act(r.id, () => onDelete(r.id))}
          aria-label={`Delete: ${r.text}`}
        >
          Delete
        </button>
      </div>
    </li>
  );

  return (
    <section className="card" aria-labelledby="reminders-title">
      <h2 id="reminders-title">Reminders</h2>
      {children}
      {highlightId != null && reminders !== null && !found && (
        <p className="notice">That reminder is no longer in your list.</p>
      )}
      {loadError !== null && <ErrorNotice error={loadError} onRetry={onReload} />}
      {error !== null && <ErrorNotice error={error} />}
      {reminders === null && loadError === null && <p className="muted">Loading…</p>}
      {reminders !== null && (
        <>
          <h3 id="upcoming-title">Upcoming ({upcoming.length})</h3>
          {upcoming.length === 0 ? (
            <p className="muted">Nothing yet. Say a promise to add one.</p>
          ) : (
            <ul className="reminders" aria-labelledby="upcoming-title">
              {upcoming.map(item)}
            </ul>
          )}
          {done.length > 0 && (
            <>
              <h3 id="done-title">Done ({done.length})</h3>
              <ul className="reminders" aria-labelledby="done-title">
                {done.map(item)}
              </ul>
            </>
          )}
        </>
      )}
    </section>
  );
}
