import { type FormEvent, useEffect, useState } from "react";
import type { Detection, Reminder } from "../api";
import { formatDue, languageName, toInputValue } from "../format";
import ErrorNotice from "./ErrorNotice";

export interface Result {
  source: "voice" | "text";
  transcript: string;
  detection: Detection | null;
  /** Voice notes only: the reminder the server saved. */
  reminder: Reminder | null;
  /** Typed text only: the time the server would use. Nothing is saved for typed text. */
  dueAt: string | null;
  cached?: boolean;
}

interface Props {
  result: Result;
  /** Saves a new time (server-local "YYYY-MM-DDTHH:MM"). Rejects with an ApiError on failure. */
  onSaveTime: (reminderId: number, dueLocal: string) => Promise<void>;
}

const TENSES: Record<string, string> = {
  future: "Future",
  present: "Present",
  past: "Past",
  habit: "Habit",
  none: "Not clear",
};

export default function ResultCard({ result, onSaveTime }: Props) {
  const { detection, reminder } = result;
  const promise = result.source === "voice" ? reminder !== null : !!detection?.is_promise;
  const due = reminder?.due_at ?? result.dueAt;
  const [time, setTime] = useState(due ? toInputValue(due) : "");
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<unknown>(null);

  // Keep the editor in step with the saved time (it changes after a successful save).
  useEffect(() => setTime(due ? toInputValue(due) : ""), [due]);
  // A different result starts clean. Not tied to `due`, or "Time saved." would vanish as soon as it appears.
  useEffect(() => {
    setSaved(false);
    setError(null);
  }, [result.source, result.transcript, reminder?.id]);

  async function save(event: FormEvent) {
    event.preventDefault();
    if (!reminder || !time) return;
    setSaving(true);
    setSaved(false);
    setError(null);
    try {
      await onSaveTime(reminder.id, time);
      setSaved(true);
    } catch (err) {
      setError(err);
    } finally {
      setSaving(false);
    }
  }

  return (
    <section className="card result" aria-labelledby="result-title">
      <h2 id="result-title">{promise ? "Promise found" : "No promise found"}</h2>
      <blockquote lang="und">{result.transcript}</blockquote>

      {detection && (
        <dl className="facts">
          <div>
            <dt>Language</dt>
            <dd>
              {languageName(detection.language)}{" "}
              {!detection.verified_rules && (
                <span className="badge" title="These language rules have not been checked by a native speaker yet.">
                  Starter rules
                </span>
              )}
            </dd>
          </div>
          <div>
            <dt>Tense</dt>
            <dd>{TENSES[detection.tense] ?? detection.tense}</dd>
          </div>
          {detection.matched && (
            <div>
              <dt>Matched phrase</dt>
              <dd>“{detection.matched}”</dd>
            </div>
          )}
          {detection.llm && (
            <div>
              <dt>Second opinion</dt>
              <dd>
                Evon model: {detection.llm.is_promise ? "a promise" : "not a promise"} (
                {Math.round(detection.llm.confidence * 100)}% sure)
              </dd>
            </div>
          )}
          {detection.reasons.length > 0 && (
            <div>
              <dt>{promise ? "Notes" : "Why not"}</dt>
              <dd>
                <ul>
                  {detection.reasons.map((reason) => (
                    <li key={reason}>{reason}</li>
                  ))}
                </ul>
              </dd>
            </div>
          )}
        </dl>
      )}
      {!detection?.verified_rules && detection && (
        <p className="muted">Starter rules: this language has not been checked by a native speaker yet, so results may be wrong.</p>
      )}
      {result.cached && <p className="muted">Same audio as before: reused the earlier transcript, no credits spent.</p>}

      {promise && due && (
        <p>
          <strong>Due:</strong> {formatDue(due)}
          {reminder?.used_default_time ? <span className="muted"> (no clock time heard, so this is a guess)</span> : null}
        </p>
      )}

      {reminder && (
        <form onSubmit={save} className="row">
          <label>
            Change the time
            <input type="datetime-local" required value={time} onChange={(e) => setTime(e.target.value)} />
          </label>
          <button type="submit" disabled={saving || !time || time === toInputValue(reminder.due_at)}>
            {saving ? "Saving…" : "Save time"}
          </button>
        </form>
      )}
      {saved && (
        <p className="ok" role="status">
          Time saved.
        </p>
      )}
      {error !== null && <ErrorNotice error={error} />}
      {result.source === "text" && promise && (
        <p className="muted">Typed text is only checked, not saved. Record or upload a voice note to create a reminder.</p>
      )}
    </section>
  );
}
