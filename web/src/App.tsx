import { useCallback, useEffect, useState } from "react";
import {
  ApiError,
  deleteReminder,
  getUsage,
  listReminders,
  logout,
  patchReminder,
  type Reminder,
  type Usage,
} from "./api";
import AuthForm from "./components/AuthForm";
import Capture from "./components/Capture";
import PushToggle from "./components/PushToggle";
import RemindersList from "./components/RemindersList";
import ReportButton from "./components/ReportButton";
import ResultCard, { type Result } from "./components/ResultCard";
import UsageMeter from "./components/UsageMeter";
import { disablePush } from "./push";
import { useInstallPrompt, useOnline } from "./pwa";
import { loadToken, saveToken } from "./session";

/** `/?reminder=12` is where a tapped notification lands when the app was closed. */
function reminderFromUrl(): number | null {
  const id = Number(new URLSearchParams(window.location.search).get("reminder"));
  return Number.isInteger(id) && id > 0 ? id : null;
}

export default function App() {
  const [token, setToken] = useState<string | null>(loadToken);
  const [notice, setNotice] = useState("");
  const [result, setResult] = useState<Result | null>(null);
  const [reminders, setReminders] = useState<Reminder[] | null>(null);
  const [remindersError, setRemindersError] = useState<unknown>(null);
  const [usage, setUsage] = useState<Usage | null>(null);
  const [usageError, setUsageError] = useState<unknown>(null);
  const [highlightId, setHighlightId] = useState<number | null>(reminderFromUrl);
  const online = useOnline();
  const install = useInstallPrompt();

  const signOut = useCallback((message = "") => {
    saveToken(null);
    setToken(null);
    setNotice(message);
    setResult(null);
    setReminders(null);
    setUsage(null);
    setRemindersError(null);
    setUsageError(null);
  }, []);

  const expired = useCallback(() => signOut("Your session ended. Log in again."), [signOut]);

  /** A 401 on any call means the token is no longer valid: go back to the login screen. */
  const orExpire = useCallback(
    (err: unknown): never => {
      if (err instanceof ApiError && err.status === 401) expired();
      throw err;
    },
    [expired],
  );

  const refreshReminders = useCallback(() => {
    if (!token) return;
    listReminders(token)
      .then((rows) => {
        setReminders(rows);
        setRemindersError(null);
      })
      .catch((err) => (err instanceof ApiError && err.status === 401 ? expired() : setRemindersError(err)));
  }, [token, expired]);

  const refreshUsage = useCallback(() => {
    if (!token) return;
    getUsage(token)
      .then((u) => {
        setUsage(u);
        setUsageError(null);
      })
      .catch((err) => (err instanceof ApiError && err.status === 401 ? expired() : setUsageError(err)));
  }, [token, expired]);

  const refreshAll = useCallback(() => {
    refreshReminders();
    refreshUsage();
  }, [refreshReminders, refreshUsage]);

  // A notification tapped while the app is open: the service worker tells us which reminder.
  useEffect(() => {
    const worker = navigator.serviceWorker;
    if (!worker?.addEventListener) return;
    const onMessage = (event: MessageEvent) => {
      if (event.data?.type !== "open-reminder" || !Number.isInteger(event.data.id)) return;
      setHighlightId(event.data.id);
      refreshReminders();
    };
    worker.addEventListener("message", onMessage);
    return () => worker.removeEventListener("message", onMessage);
  }, [refreshReminders]);

  // Load on login, and again whenever the connection comes back.
  useEffect(() => {
    if (online) refreshAll();
  }, [refreshAll, online]);

  const offlineBanner = !online && (
    <p className="notice offline" role="status">
      You're offline. Your reminders and new voice notes need a connection; they'll load when you're back online.
    </p>
  );
  const installButton = install && (
    <button type="button" className="link" onClick={install}>
      Install app
    </button>
  );

  if (!token) {
    return (
      <main className="shell">
        <Header>{installButton}</Header>
        {offlineBanner}
        <AuthForm
          notice={notice}
          onLoggedIn={(t) => {
            saveToken(t);
            setNotice("");
            setToken(t);
          }}
        />
      </main>
    );
  }

  const saveTime = async (id: number, dueLocal: string) => {
    const updated = await patchReminder(token, id, { due_at: dueLocal }).catch(orExpire);
    setResult((r) => (r?.reminder?.id === id ? { ...r, reminder: updated } : r));
    refreshReminders();
  };
  const setDone = async (id: number, done: boolean) => {
    await patchReminder(token, id, { done }).catch(orExpire);
    refreshReminders();
  };
  const remove = async (id: number) => {
    await deleteReminder(token, id).catch(orExpire);
    setResult((r) => (r?.reminder?.id === id ? null : r));
    refreshReminders();
  };

  return (
    <main className="shell">
      <Header>
        {installButton}
        <button
          type="button"
          className="link"
          onClick={() => {
            // Best effort; the local token is dropped either way. Notifications stop first, so this device
            // doesn't keep announcing this account's reminders to whoever uses it next.
            void disablePush(token)
              .catch(() => undefined)
              .then(() => logout(token))
              .catch(() => undefined);
            signOut();
          }}
        >
          Log out
        </button>
      </Header>
      {offlineBanner}
      <Capture token={token} onResult={setResult} onChanged={refreshAll} onSessionExpired={expired} />
      {result && <ResultCard result={result} onSaveTime={saveTime} />}
      <RemindersList
        reminders={reminders}
        loadError={remindersError}
        onReload={refreshReminders}
        onSetDone={setDone}
        onDelete={remove}
        highlightId={highlightId}
      >
        <PushToggle token={token} onSessionExpired={expired} />
        <ReportButton token={token} onSessionExpired={expired} />
      </RemindersList>
      <UsageMeter usage={usage} loadError={usageError} onReload={refreshUsage} />
    </main>
  );
}

function Header({ children }: { children?: React.ReactNode }) {
  return (
    <header className="top">
      <div>
        <h1>Promise Keeper</h1>
        <p className="tagline">Say a promise out loud. Get a reminder.</p>
      </div>
      <div className="top-actions">{children}</div>
    </header>
  );
}
