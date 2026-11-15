import { type FormEvent, useState } from "react";
import { login, register } from "../api";
import ErrorNotice from "./ErrorNotice";

interface Props {
  onLoggedIn: (token: string) => void;
  /** Shown above the form, e.g. after the session expired. */
  notice?: string;
}

export default function AuthForm({ onLoggedIn, notice }: Props) {
  const [mode, setMode] = useState<"login" | "register">("login");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      if (mode === "register") await register(email.trim(), password, code.trim());
      const { token } = await login(email.trim(), password);
      onLoggedIn(token);
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  const registering = mode === "register";
  return (
    <section className="card" aria-labelledby="auth-title">
      <h2 id="auth-title">{registering ? "Create an account" : "Log in"}</h2>
      {notice && <p className="notice">{notice}</p>}
      <form onSubmit={submit} className="stack">
        <label>
          Email
          <input type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
        </label>
        <label>
          Password
          <input
            type="password"
            autoComplete={registering ? "new-password" : "current-password"}
            required
            minLength={8}
            maxLength={128}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            aria-describedby={registering ? "password-hint" : undefined}
          />
        </label>
        {registering && (
          <>
            <p id="password-hint" className="muted">
              At least 8 characters.
            </p>
            <label>
              Registration code (only if you were given one)
              <input type="text" autoComplete="off" value={code} onChange={(e) => setCode(e.target.value)} />
            </label>
          </>
        )}
        {error !== null && <ErrorNotice error={error} />}
        <button type="submit" className="primary" disabled={busy}>
          {busy ? "Please wait…" : registering ? "Create account" : "Log in"}
        </button>
      </form>
      <button
        type="button"
        className="link"
        onClick={() => {
          setMode(registering ? "login" : "register");
          setError(null);
        }}
      >
        {registering ? "I already have an account" : "Create an account"}
      </button>
    </section>
  );
}
