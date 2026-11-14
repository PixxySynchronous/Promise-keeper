import { useEffect, useState } from "react";
import { ApiError } from "../api";
import { disablePush, enablePush, type PushState, pushStatus } from "../push";
import ErrorNotice from "./ErrorNotice";

interface Props {
  token: string;
  onSessionExpired: () => void;
}

const EXPLAIN: Partial<Record<PushState, string>> = {
  unsupported: "This browser can't show notifications. On an iPhone, add the app to your Home Screen first.",
  "no-worker": "Notifications aren't available in this view of the app.",
  "not-configured": "Notifications aren't set up on this server yet.",
  blocked: "Notifications are blocked for this site. Allow them in your browser's site settings, then reload.",
};

/** Lets the user switch due-time notifications on or off for this browser. */
export default function PushToggle({ token, onSessionExpired }: Props) {
  const [state, setState] = useState<PushState | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  function fail(err: unknown) {
    if (err instanceof ApiError && err.status === 401) return onSessionExpired();
    setError(err);
  }

  useEffect(() => {
    let alive = true;
    pushStatus(token)
      .then((s) => alive && setState(s))
      .catch((err) => {
        if (!alive) return;
        setState("off");
        if (err instanceof ApiError && err.status === 401) onSessionExpired();
      });
    return () => {
      alive = false;
    };
  }, [token, onSessionExpired]);

  async function toggle() {
    setBusy(true);
    setError(null);
    try {
      if (state === "on") {
        await disablePush(token);
        setState("off");
      } else {
        setState(await enablePush(token));
      }
    } catch (err) {
      fail(err);
    } finally {
      setBusy(false);
    }
  }

  if (state === null) return null;
  const explain = EXPLAIN[state];
  return (
    <div className="push">
      {explain ? (
        <p className="muted">{explain}</p>
      ) : (
        <div className="row">
          <p className="muted" role="status">
            {state === "on"
              ? "Notifications are on for this device."
              : "Get a notification on this device when a reminder is due."}
          </p>
          <button type="button" onClick={() => void toggle()} disabled={busy}>
            {busy ? "Please wait…" : state === "on" ? "Turn off notifications" : "Turn on notifications"}
          </button>
        </div>
      )}
      {error !== null && <ErrorNotice error={error} />}
    </div>
  );
}
