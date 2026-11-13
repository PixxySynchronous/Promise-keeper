import { vi } from "vitest";
import type { Detection, Reminder } from "./api";

export const jsonResponse = (body: unknown, status = 200, headers: Record<string, string> = {}) =>
  new Response(body === null ? null : JSON.stringify(body), { status, headers });

export const apiError = (status: number, code: string, message: string, retryAfter?: number) =>
  jsonResponse({ detail: { code, message } }, status, retryAfter ? { "Retry-After": String(retryAfter) } : {});

type Handler = (init: RequestInit | undefined) => Response | Promise<Response>;

/** Stubs fetch with a table of "METHOD /path" → response. Unknown calls fail the test loudly. */
export function mockApi(routes: Record<string, Handler | Response>) {
  const calls: Array<{ key: string; init?: RequestInit }> = [];
  const fetchMock = vi.fn(async (path: string, init?: RequestInit) => {
    const key = `${init?.method ?? "GET"} ${path}`;
    calls.push({ key, init });
    const route = routes[key];
    if (!route) throw new Error(`Unexpected request in test: ${key}`);
    return route instanceof Response ? route.clone() : route(init);
  });
  vi.stubGlobal("fetch", fetchMock);
  return { calls, keys: () => calls.map((c) => c.key) };
}

export const detection = (over: Partial<Detection> = {}): Detection => ({
  is_promise: true,
  language: "en-IN",
  tense: "future",
  matched: "bhej dunga",
  reasons: [],
  verified_rules: true,
  ...over,
});

export const reminder = (over: Partial<Reminder> = {}): Reminder => ({
  id: 7,
  text: "kal 5 baje report bhej dunga",
  due_at: "2026-11-14T17:00+05:30",
  time_text: "kal 5 baje",
  used_default_time: 0,
  done_at: null,
  ...over,
});

export const usage = (requests = 3) => ({
  today_requests: requests,
  today_audio_seconds: 42.4,
  limits: {
    provider: "mock",
    max_upload_bytes: 2_000_000,
    max_audio_seconds: 30,
    min_audio_seconds: 0.5,
    requests_per_minute: 3,
    daily_requests: 20,
    daily_audio_seconds: 300,
    max_pending_jobs: 2,
    formats: ["wav"],
  },
});
