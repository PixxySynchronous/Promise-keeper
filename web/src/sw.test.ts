// @vitest-environment node
import { beforeEach, describe, expect, it } from "vitest";
import SOURCE from "../public/sw.js?raw";

// Runs the real public/sw.js against a fake browser, to pin down the privacy rule:
// the worker caches the app shell and static files only, never API responses or audio.

const ORIGIN = "https://app.test";
const SHELL_HTML = '<html><script src="/assets/index-abc.js"></script><link href="/assets/index-abc.css"></html>';

type Listener = (event: any) => void;

class FakeCache {
  store = new Map<string, Response>();
  private key = (k: string | { url: string }) => new URL(typeof k === "string" ? k : k.url, ORIGIN).pathname;
  async match(k: string | { url: string }) {
    return this.store.get(this.key(k))?.clone();
  }
  async put(k: string | { url: string }, res: Response) {
    this.store.set(this.key(k), res);
  }
  async keys() {
    return [...this.store.keys()].map((path) => ({ url: ORIGIN + path }));
  }
  async delete(k: string | { url: string }) {
    return this.store.delete(this.key(k));
  }
}

function boot(files: Record<string, () => Response>) {
  const listeners: Record<string, Listener> = {};
  const cache = new FakeCache();
  const state = { online: true, fetched: [] as string[] };
  const fetchFake = async (input: string | { url: string }) => {
    const path = new URL(typeof input === "string" ? input : input.url, ORIGIN).pathname;
    state.fetched.push(path);
    if (!state.online) throw new TypeError("Failed to fetch");
    return files[path]?.() ?? new Response("nope", { status: 404 });
  };
  const shown: Array<{ title: string; options: any }> = [];
  const windows: Array<{ url: string; focus: () => Promise<string>; postMessage: (m: unknown) => void; messages: unknown[] }> = [];
  const opened: string[] = [];
  const self = {
    location: { origin: ORIGIN },
    addEventListener: (name: string, fn: Listener) => (listeners[name] = fn),
    skipWaiting: async () => undefined,
    registration: { showNotification: async (title: string, options: any) => void shown.push({ title, options }) },
    clients: {
      claim: async () => undefined,
      matchAll: async () => windows,
      openWindow: async (url: string) => void opened.push(url),
    },
  };
  const addWindow = (url: string) => {
    const win = { url, messages: [] as unknown[], focus: async () => "focused", postMessage: (m: unknown) => void win.messages.push(m) };
    windows.push(win);
    return win;
  };
  async function fire(name: "push" | "notificationclick", event: Record<string, unknown>) {
    const pending: Promise<unknown>[] = [];
    listeners[name]({ ...event, waitUntil: (p: Promise<unknown>) => pending.push(p) });
    await Promise.all(pending);
  }
  const caches = { open: async () => cache, keys: async () => ["pk-shell-v1", "old-cache"], delete: async () => true };
  new Function("self", "caches", "fetch", SOURCE)(self, caches, fetchFake);

  async function lifecycle(name: "install" | "activate") {
    const pending: Promise<unknown>[] = [];
    listeners[name]({ waitUntil: (p: Promise<unknown>) => pending.push(p) });
    await Promise.all(pending);
  }
  /** Returns the worker's answer, or null when it left the request to the browser. */
  async function request(path: string, init: { method?: string; mode?: string } = {}) {
    let answer: Promise<Response> | null = null;
    const pending: Promise<unknown>[] = [];
    listeners.fetch({
      request: { url: (path.startsWith("http") ? "" : ORIGIN) + path, method: init.method ?? "GET", mode: init.mode ?? "cors" },
      respondWith: (p: Promise<Response>) => (answer = p),
      waitUntil: (p: Promise<unknown>) => pending.push(p),
    });
    const result = answer ? await (answer as Promise<Response>) : null;
    await Promise.all(pending);
    return result;
  }
  return { cache, state, lifecycle, request, fire, shown, opened, addWindow };
}

const html = (body = SHELL_HTML) => () => new Response(body, { headers: { "content-type": "text/html; charset=utf-8" } });
const file = (body: string) => () => new Response(body);
const FILES = {
  "/": html(),
  "/assets/index-abc.js": file("js"),
  "/assets/index-abc.css": file("css"),
  "/manifest.webmanifest": file("{}"),
  "/icon.svg": file("<svg/>"),
  "/icon-192.png": file("png"),
  "/icon-512.png": file("png"),
  "/reminders": () => new Response('[{"text":"private"}]', { headers: { "content-type": "application/json" } }),
};

describe("service worker", () => {
  let sw: ReturnType<typeof boot>;
  beforeEach(async () => {
    sw = boot(FILES);
    await sw.lifecycle("install");
    await sw.lifecycle("activate");
  });

  it("caches the shell and the files it names when installed", () => {
    expect([...sw.cache.store.keys()].sort()).toEqual(
      ["/", "/assets/index-abc.css", "/assets/index-abc.js", "/icon-192.png", "/icon-512.png", "/icon.svg", "/manifest.webmanifest"].sort(),
    );
  });

  it.each([
    ["/reminders", {}],
    ["/usage/me", {}],
    ["/voice-notes/abc", {}],
    ["/auth/login", { method: "POST" }],
    ["/voice-notes", { method: "POST" }],
    ["/health", {}],
    ["/reminders", { mode: "navigate" }],
    ["/legacy", { mode: "navigate" }],
    ["/docs", { mode: "navigate" }],
    ["https://elsewhere.test/assets/x.js", {}],
    ["/something.json", {}],
  ])("leaves %s %o to the browser and caches nothing", async (path, init) => {
    const before = [...sw.cache.store.keys()];
    expect(await sw.request(path, init)).toBeNull();
    expect([...sw.cache.store.keys()]).toEqual(before);
  });

  it("never stores API data, even after API calls and navigations", async () => {
    await sw.request("/reminders");
    await sw.request("/", { mode: "navigate" });
    for (const res of sw.cache.store.values()) expect(await res.clone().text()).not.toContain("private");
    expect([...sw.cache.store.keys()].some((k) => /reminders|voice-notes|usage|auth/.test(k))).toBe(false);
  });

  it("serves the cached shell for any app page when offline", async () => {
    sw.state.online = false;
    for (const path of ["/", "/some/client/route"]) {
      const res = await sw.request(path, { mode: "navigate" });
      expect(await res!.text()).toBe(SHELL_HTML);
    }
    expect(await (await sw.request("/assets/index-abc.js"))!.text()).toBe("js");
  });

  it("serves hashed assets from the cache without asking the network", async () => {
    sw.state.fetched.length = 0;
    expect(await (await sw.request("/assets/index-abc.css"))!.text()).toBe("css");
    expect(sw.state.fetched).toEqual([]);
  });

  it("picks up a new build online and drops the old build's files", async () => {
    const files = { ...FILES, "/": html('<script src="/assets/index-new.js"></script>'), "/assets/index-new.js": file("new js") };
    const fresh = boot(files);
    fresh.cache.store.set("/assets/index-abc.js", new Response("js")); // left over from the old build
    const res = await fresh.request("/", { mode: "navigate" });
    expect(await res!.text()).toContain("index-new.js");
    expect(fresh.cache.store.has("/assets/index-new.js")).toBe(true);
    expect(fresh.cache.store.has("/assets/index-abc.js")).toBe(false);
    fresh.state.online = false;
    expect(await (await fresh.request("/", { mode: "navigate" }))!.text()).toContain("index-new.js");
  });

  it("does not cache an error page as the shell", async () => {
    const broken = boot({ ...FILES, "/": () => new Response("Bad gateway", { status: 502, headers: { "content-type": "text/html" } }) });
    broken.cache.store.set("/", new Response(SHELL_HTML));
    const res = await broken.request("/", { mode: "navigate" });
    expect(res!.status).toBe(502);
    expect(await (await broken.cache.match("/"))!.text()).toBe(SHELL_HTML);
  });

  const pushData = (value: unknown) => ({ data: { json: () => value } });

  it("shows a notification for a pushed reminder and stores nothing", async () => {
    const before = [...sw.cache.store.keys()];
    await sw.fire("push", pushData({ title: "Promise due", body: "kal report bhej dunga", reminder_id: 7, url: "/?reminder=7" }));
    expect(sw.shown).toEqual([
      {
        title: "Promise due",
        options: expect.objectContaining({ body: "kal report bhej dunga", tag: "reminder-7", data: { id: 7, url: "/?reminder=7" } }),
      },
    ]);
    expect([...sw.cache.store.keys()]).toEqual(before);
  });

  it("always shows something, even for an empty or broken push, and ignores a URL in the payload", async () => {
    await sw.fire("push", { data: null });
    await sw.fire("push", { data: { json: () => { throw new SyntaxError("bad"); } } });
    await sw.fire("push", pushData({ title: 5, body: null, reminder_id: "x", url: "https://evil.example/phish" }));
    expect(sw.shown).toHaveLength(3);
    for (const n of sw.shown) {
      expect(n.title).toBe("Promise due");
      expect(n.options.body).toBe("You have a reminder.");
      expect(n.options.data).toEqual({ id: null, url: "/" });
    }
  });

  it("tapping opens the reminder in a new window when the app is closed", async () => {
    const close = () => undefined;
    await sw.fire("notificationclick", { notification: { close, data: { id: 7, url: "/?reminder=7" } } });
    expect(sw.opened).toEqual(["/?reminder=7"]);
  });

  it("tapping reuses an open app window and tells it which reminder", async () => {
    sw.addWindow("https://elsewhere.test/");
    const app = sw.addWindow(ORIGIN + "/");
    let closed = false;
    await sw.fire("notificationclick", { notification: { close: () => (closed = true), data: { id: 7, url: "/?reminder=7" } } });
    expect(closed).toBe(true);
    expect(app.messages).toEqual([{ type: "open-reminder", id: 7 }]);
    expect(sw.opened).toEqual([]);
  });
});
