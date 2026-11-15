import type { Usage } from "../api";
import ErrorNotice from "./ErrorNotice";

interface Props {
  usage: Usage | null;
  loadError: unknown;
  onReload: () => void;
}

export default function UsageMeter({ usage, loadError, onReload }: Props) {
  return (
    <section className="card" aria-labelledby="usage-title">
      <h2 id="usage-title">Today's usage</h2>
      {loadError !== null && <ErrorNotice error={loadError} onRetry={onReload} />}
      {usage === null && loadError === null && <p className="muted">Loading…</p>}
      {usage && (
        <div className="stack">
          <label>
            Voice notes: {usage.today_requests} of {usage.limits.daily_requests}
            <progress value={Math.min(usage.today_requests, usage.limits.daily_requests)} max={usage.limits.daily_requests} />
          </label>
          <label>
            Audio: {Math.round(usage.today_audio_seconds)} of {Math.round(usage.limits.daily_audio_seconds)} seconds
            <progress
              value={Math.min(usage.today_audio_seconds, usage.limits.daily_audio_seconds)}
              max={usage.limits.daily_audio_seconds}
            />
          </label>
          <p className="muted">
            Up to {usage.limits.requests_per_minute} voice notes a minute, {Math.round(usage.limits.max_audio_seconds)} seconds
            each. Limits reset at midnight.
          </p>
        </div>
      )}
    </section>
  );
}
