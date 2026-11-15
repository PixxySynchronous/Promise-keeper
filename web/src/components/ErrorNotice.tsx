import { useEffect, useState } from "react";
import { ApiError } from "../api";
import { formatWait } from "../format";

interface Props {
  error: unknown;
  /** If given, a Retry button is shown; it stays disabled while a Retry-After countdown runs. */
  onRetry?: () => void;
}

export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  if (error instanceof Error && error.message) return error.message;
  return "Something went wrong.";
}

/** Shows the server's own message, and counts down when the server said how long to wait (429/503). */
export default function ErrorNotice({ error, onRetry }: Props) {
  const wait = error instanceof ApiError ? error.retryAfterSeconds : null;
  const [left, setLeft] = useState(wait ?? 0);

  useEffect(() => {
    if (!wait) {
      setLeft(0);
      return;
    }
    const until = Date.now() + wait * 1000;
    setLeft(wait);
    const timer = setInterval(() => {
      const remaining = Math.max(0, Math.ceil((until - Date.now()) / 1000));
      setLeft(remaining);
      if (remaining === 0) clearInterval(timer);
    }, 1000);
    return () => clearInterval(timer);
  }, [error, wait]);

  return (
    <div className="notice error" role="alert">
      <p>{errorMessage(error)}</p>
      {wait !== null && (
        <p className="muted">{left > 0 ? `You can try again in ${formatWait(left)}.` : "You can try again now."}</p>
      )}
      {onRetry && (
        <button type="button" onClick={onRetry} disabled={left > 0}>
          Retry
        </button>
      )}
    </div>
  );
}
