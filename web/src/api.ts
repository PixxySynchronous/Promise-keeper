// The only place that talks to the backend. Paths are relative: same origin in production (FastAPI serves
// the built app) and proxied by Vite in dev. The browser never talks to Gnani and never sees its key.

/** A backend error: `{"detail": {"code": "UPPER_SNAKE", "message": "..."}}` plus Retry-After on 429/503. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly retryAfterSeconds: number | null = null,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

export interface Health {
  status: string;
  provider: string;
  live_gnani: boolean;
}

export interface Detection {
  is_promise: boolean;
  language: string;
  tense: string;
  matched: string | null;
  reasons: string[];
  verified_rules: boolean;
  /** The optional LLM's opinion, when the server asked it (starter-rule languages, or no time found). */
  llm?: { is_promise: boolean; task: string; time_text: string | null; confidence: number } | null;
}

export interface Reminder {
  id: number;
  job_id?: string | null;
  text: string;
  matched_phrase?: string | null;
  /** Server-local time with offset, e.g. 2026-11-14T17:00+05:30 */
  due_at: string;
  time_text: string | null;
  used_default_time: number;
  created_at?: string;
  done_at: string | null;
}

export interface VoiceNote {
  id: string;
  status: "queued" | "processing" | "done" | "failed";
  audio_seconds: number;
  language: string;
  transcript: string | null;
  detected_language: string | null;
  cached: boolean;
  error: string | null;
  reminder: Reminder | null;
  detection?: Detection;
}

export interface QueuedNote {
  id: string;
  status: string;
  audio_seconds: number;
  poll: string;
}

export interface TextCheck extends Detection {
  due_at: string | null;
}

export interface Usage {
  today_requests: number;
  today_audio_seconds: number;
  limits: {
    provider: string;
    max_upload_bytes: number;
    max_audio_seconds: number;
    min_audio_seconds: number;
    requests_per_minute: number;
    daily_requests: number;
    daily_audio_seconds: number;
    max_pending_jobs: number;
    formats: string[];
  };
}

async function toApiError(res: Response): Promise<ApiError> {
  let code = "HTTP_" + res.status;
  let message = `The server answered ${res.status}.`;
  try {
    const detail = (await res.json())?.detail;
    if (detail && typeof detail === "object" && !Array.isArray(detail)) {
      if (typeof detail.code === "string") code = detail.code;
      if (typeof detail.message === "string") message = detail.message;
    } else if (Array.isArray(detail) && typeof detail[0]?.msg === "string") {
      // FastAPI's own validation errors (422) use a list of {loc, msg}.
      code = "INVALID_INPUT";
      message = detail[0].msg;
    }
  } catch {
    // Not JSON (e.g. a proxy error page): keep the generic message.
  }
  const retry = Number(res.headers.get("Retry-After"));
  return new ApiError(res.status, code, message, Number.isFinite(retry) && retry > 0 ? retry : null);
}

/** Sends the request with the token and turns every failure into an ApiError. */
async function send(path: string, init: RequestInit = {}, token?: string | null): Promise<Response> {
  const headers = new Headers(init.headers);
  if (token) headers.set("Authorization", `Bearer ${token}`);
  let res: Response;
  try {
    res = await fetch(path, { ...init, headers });
  } catch {
    throw new ApiError(0, "NETWORK", "Can't reach the server. Check your connection and try again.");
  }
  if (!res.ok) throw await toApiError(res);
  return res;
}

export async function request<T>(path: string, init: RequestInit = {}, token?: string | null): Promise<T> {
  const res = await send(path, init, token);
  return res.status === 204 ? (undefined as T) : ((await res.json()) as T);
}

const json = (method: string, body: unknown): RequestInit => ({
  method,
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify(body),
});

export const getHealth = () => request<Health>("/health");

export function register(email: string, password: string, registrationCode: string) {
  const body = { email, password, ...(registrationCode ? { registration_code: registrationCode } : {}) };
  return request<{ id: number; email: string }>("/auth/register", json("POST", body));
}

export const login = (email: string, password: string) =>
  request<{ token: string }>("/auth/login", json("POST", { email, password }));

export const logout = (token: string) => request<void>("/auth/logout", { method: "POST" }, token);

export function uploadVoiceNote(token: string, audio: Blob, filename: string, language: string) {
  const form = new FormData();
  form.append("audio_file", audio, filename);
  form.append("language", language);
  return request<QueuedNote>("/voice-notes", { method: "POST", body: form }, token);
}

export const getVoiceNote = (token: string, id: string) =>
  request<VoiceNote>(`/voice-notes/${encodeURIComponent(id)}`, {}, token);

export const detectText = (token: string, text: string, language: string) =>
  request<TextCheck>("/detect", json("POST", { text, language }), token);

export const listReminders = (token: string) => request<Reminder[]>("/reminders", {}, token);

export const patchReminder = (token: string, id: number, change: { due_at?: string; done?: boolean }) =>
  request<Reminder>(`/reminders/${id}`, json("PATCH", change), token);

export const deleteReminder = (token: string, id: number) =>
  request<void>(`/reminders/${id}`, { method: "DELETE" }, token);

export const getUsage = (token: string) => request<Usage>("/usage/me", {}, token);

export interface PushConfig {
  enabled: boolean;
  public_key: string | null;
}

export const getPushConfig = () => request<PushConfig>("/push/config");

export const pushSubscribe = (token: string, subscription: PushSubscriptionJSON) =>
  request<{ subscribed: boolean }>(
    "/push/subscribe",
    json("POST", { endpoint: subscription.endpoint, keys: subscription.keys }),
    token,
  );

export const pushUnsubscribe = (token: string, endpoint: string) =>
  request<void>("/push/subscribe", json("DELETE", { endpoint }), token);

/** The weekly PDF. It needs the token, so it can't be a plain link: fetch it, then hand the file to the browser. */
export async function getWeeklyReport(token: string): Promise<{ blob: Blob; filename: string }> {
  const res = await send("/reports/weekly.pdf", {}, token);
  const named = /filename="([^"]+)"/.exec(res.headers.get("Content-Disposition") ?? "");
  return { blob: await res.blob(), filename: named?.[1] ?? "promise-keeper-weekly.pdf" };
}
