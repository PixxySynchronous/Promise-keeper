import { beforeEach, describe, expect, it, vi } from "vitest";
import { disablePush, enablePush, keyBytes, pushStatus } from "./push";
import { apiError, jsonResponse, mockApi } from "./test-helpers";

const ENDPOINT = "https://fcm.googleapis.com/fcm/send/abc";
const KEYS = { p256dh: "BPubKey", auth: "authKey" };

function fakeBrowser(opts: { permission?: string; answer?: string; subscribed?: boolean; registered?: boolean; staleKey?: boolean } = {}) {
  const subscription = {
    endpoint: ENDPOINT,
    toJSON: () => ({ endpoint: ENDPOINT, keys: KEYS }),
    unsubscribe: vi.fn(async () => {
      state.current = null;
      return true;
    }),
  };
  const state = { current: opts.subscribed ? subscription : null, subscribeCalls: 0 } as {
    current: typeof subscription | null;
    subscribeCalls: number;
  };
  const pushManager = {
    getSubscription: vi.fn(async () => state.current),
    subscribe: vi.fn(async (_options: PushSubscriptionOptionsInit) => {
      state.subscribeCalls++;
      if (opts.staleKey && state.subscribeCalls === 1) throw new DOMException("different key", "InvalidStateError");
      state.current = subscription;
      return subscription;
    }),
  };
  const notification = {
    permission: opts.permission ?? "default",
    requestPermission: vi.fn(async () => (notification.permission = opts.answer ?? "granted")),
  };
  vi.stubGlobal("Notification", notification);
  vi.stubGlobal("PushManager", class {});
  vi.stubGlobal("navigator", {
    serviceWorker: { getRegistration: async () => (opts.registered === false ? undefined : { pushManager }) },
  });
  return { subscription, pushManager, notification, state };
}

const config = (enabled = true) => jsonResponse({ enabled, public_key: enabled ? "AQID" : null });

describe("push", () => {
  beforeEach(() => vi.unstubAllGlobals());

  it("decodes the server key from base64url", () => {
    expect([...keyBytes("AQID")]).toEqual([1, 2, 3]);
    expect([...keyBytes("-_8")]).toEqual([251, 255]);
  });

  it("reports why notifications can't be used", async () => {
    mockApi({ "GET /push/config": config(false) });
    expect(await pushStatus("tok")).toBe("unsupported"); // jsdom: no service worker, no PushManager
    fakeBrowser({ registered: false });
    expect(await pushStatus("tok")).toBe("no-worker");
    fakeBrowser();
    expect(await pushStatus("tok")).toBe("not-configured");
    mockApi({ "GET /push/config": config() });
    fakeBrowser({ permission: "denied" });
    expect(await pushStatus("tok")).toBe("blocked");
    fakeBrowser();
    expect(await pushStatus("tok")).toBe("off");
  });

  it("re-sends an existing subscription to the server on load", async () => {
    const api = mockApi({ "GET /push/config": config(), "POST /push/subscribe": jsonResponse({ subscribed: true }, 201) });
    fakeBrowser({ permission: "granted", subscribed: true });
    expect(await pushStatus("tok")).toBe("on");
    expect(JSON.parse(api.calls[1].init!.body as string)).toEqual({ endpoint: ENDPOINT, keys: KEYS });
  });

  it("asks permission, subscribes with the server key and registers with the server", async () => {
    const api = mockApi({ "GET /push/config": config(), "POST /push/subscribe": jsonResponse({ subscribed: true }, 201) });
    const browser = fakeBrowser();
    expect(await enablePush("tok")).toBe("on");
    const options = browser.pushManager.subscribe.mock.calls[0][0];
    expect(options.userVisibleOnly).toBe(true);
    expect([...(options.applicationServerKey as Uint8Array)]).toEqual([1, 2, 3]);
    expect((api.calls[1].init!.headers as Headers).get("Authorization")).toBe("Bearer tok");
  });

  it("does not subscribe when the user says no or dismisses the prompt", async () => {
    const api = mockApi({ "GET /push/config": config() });
    let browser = fakeBrowser({ answer: "denied" });
    expect(await enablePush("tok")).toBe("blocked");
    browser = fakeBrowser({ answer: "default" });
    expect(await enablePush("tok")).toBe("off");
    expect(browser.pushManager.subscribe).not.toHaveBeenCalled();
    expect(api.keys()).not.toContain("POST /push/subscribe");
  });

  it("replaces a subscription made with an old server key", async () => {
    mockApi({ "GET /push/config": config(), "POST /push/subscribe": jsonResponse({ subscribed: true }, 201) });
    const browser = fakeBrowser({ subscribed: true, staleKey: true });
    expect(await enablePush("tok")).toBe("on");
    expect(browser.subscription.unsubscribe).toHaveBeenCalledOnce();
    expect(browser.pushManager.subscribe).toHaveBeenCalledTimes(2);
  });

  it("undoes the browser subscription if the server refuses it", async () => {
    mockApi({ "GET /push/config": config(), "POST /push/subscribe": apiError(422, "INVALID_PUSH_ENDPOINT", "Not supported.") });
    const browser = fakeBrowser();
    await expect(enablePush("tok")).rejects.toMatchObject({ code: "INVALID_PUSH_ENDPOINT" });
    expect(browser.state.current).toBeNull();
  });

  it("turning off unsubscribes the browser and tells the server", async () => {
    const api = mockApi({ "DELETE /push/subscribe": jsonResponse(null, 204) });
    const browser = fakeBrowser({ permission: "granted", subscribed: true });
    await disablePush("tok");
    expect(browser.state.current).toBeNull();
    expect(JSON.parse(api.calls[0].init!.body as string)).toEqual({ endpoint: ENDPOINT });
    await disablePush("tok"); // nothing left: no second request
    expect(api.calls).toHaveLength(1);
  });
});
