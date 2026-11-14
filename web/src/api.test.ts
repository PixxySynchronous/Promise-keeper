import { describe, expect, it, vi } from "vitest";
import { ApiError, request } from "./api";

const json = (body: unknown, status: number, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), { status, headers });

describe("request", () => {
  it("sends the bearer token and returns JSON", async () => {
    const fetchMock = vi.fn().mockResolvedValue(json([{ id: 1 }], 200));
    vi.stubGlobal("fetch", fetchMock);
    await expect(request("/reminders", {}, "tok")).resolves.toEqual([{ id: 1 }]);
    expect((fetchMock.mock.calls[0][1].headers as Headers).get("Authorization")).toBe("Bearer tok");
  });

  it("turns a 429 into an ApiError with code, message and Retry-After", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(json({ detail: { code: "RATE_LIMITED", message: "Slow down." } }, 429, { "Retry-After": "17" })),
    );
    const err = await request("/voice-notes").catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err).toMatchObject({ status: 429, code: "RATE_LIMITED", message: "Slow down.", retryAfterSeconds: 17 });
  });

  it("reads FastAPI validation errors and survives non-JSON bodies", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json({ detail: [{ loc: ["body"], msg: "Field required" }] }, 422)));
    await expect(request("/detect")).rejects.toMatchObject({ code: "INVALID_INPUT", message: "Field required" });
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("<html>bad gateway</html>", { status: 502 })));
    await expect(request("/detect")).rejects.toMatchObject({ status: 502, code: "HTTP_502", retryAfterSeconds: null });
  });

  it("returns undefined for 204", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 204 })));
    await expect(request("/reminders/1", { method: "DELETE" })).resolves.toBeUndefined();
  });
});
