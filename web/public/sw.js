// Service worker: caches the APP SHELL ONLY, so the app opens without a connection, and shows the
// notification when the server pushes a due reminder.
//
// Privacy rule: API responses (transcripts, reminders, usage, tokens) and audio are NEVER cached and never
// even pass through this file. We only answer (1) page navigations and (2) GET requests for the app's own
// static files. Everything else is left to the browser, untouched.

const CACHE = "pk-shell-v1";
const SHELL = "/";
const STATIC_FILES = ["/manifest.webmanifest", "/icon.svg", "/icon-192.png", "/icon-512.png"];
// First path segments that belong to the backend, not to the app. Keep in step with API_PREFIXES in
// voice-backend/app/main.py (plus "legacy", the old demo page).
const NOT_APP = ["auth", "voice-notes", "detect", "reminders", "usage", "health", "push", "reports", "docs", "redoc",
  "openapi.json", "legacy"];

const assetUrls = (html) => [...html.matchAll(/(?:src|href)="(\/assets\/[^"]+)"/g)].map((m) => m[1]);

function isStatic(url) {
  return url.pathname.startsWith("/assets/") || STATIC_FILES.includes(url.pathname);
}

/** Stores a fresh shell and the hashed files it names, then drops files from older builds. */
async function saveShell(response) {
  const cache = await caches.open(CACHE);
  const wanted = assetUrls(await response.clone().text());
  await cache.put(SHELL, response.clone());
  await Promise.all(
    wanted.map(async (url) => {
      if (await cache.match(url)) return;
      const res = await fetch(url);
      if (res.ok) await cache.put(url, res);
    }),
  );
  for (const request of await cache.keys()) {
    const path = new URL(request.url).pathname;
    if (path.startsWith("/assets/") && !wanted.includes(path)) await cache.delete(request);
  }
}

self.addEventListener("install", (event) => {
  event.waitUntil(
    (async () => {
      const shell = await fetch(SHELL, { cache: "no-store" });
      if (!shell.ok) throw new Error("app shell not available");
      await saveShell(shell);
      const cache = await caches.open(CACHE);
      await Promise.all(
        STATIC_FILES.map(async (url) => {
          const res = await fetch(url);
          if (res.ok) await cache.put(url, res);
        }),
      );
      await self.skipWaiting();
    })(),
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    (async () => {
      for (const name of await caches.keys()) if (name !== CACHE) await caches.delete(name);
      await self.clients.claim();
    })(),
  );
});

self.addEventListener("fetch", (event) => {
  const request = event.request;
  if (request.method !== "GET") return;
  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;
  if (NOT_APP.includes(url.pathname.split("/")[1])) return; // the API: never touched, never cached

  if (request.mode === "navigate") {
    // Network first, so a new deploy shows up at once; the cached shell is only for when that fails.
    event.respondWith(
      (async () => {
        try {
          const fresh = await fetch(request);
          if (fresh.ok && (fresh.headers.get("content-type") || "").includes("text/html")) {
            event.waitUntil(saveShell(fresh.clone()).catch(() => undefined));
          }
          return fresh;
        } catch (offline) {
          const shell = await (await caches.open(CACHE)).match(SHELL);
          if (shell) return shell;
          throw offline;
        }
      })(),
    );
    return;
  }

  if (isStatic(url)) {
    // File names under /assets/ contain a content hash, so a cached copy is always the right one.
    event.respondWith(
      (async () => {
        const cache = await caches.open(CACHE);
        const hit = await cache.match(url.pathname);
        if (hit) return hit;
        const res = await fetch(request);
        if (res.ok) event.waitUntil(cache.put(url.pathname, res.clone()));
        return res;
      })(),
    );
  }
});

// --- Push: a reminder is due -----------------------------------------------------------------------
// The server sends {title, body, reminder_id, url}. Nothing from it is stored here.
self.addEventListener("push", (event) => {
  let data = {};
  try {
    data = event.data ? event.data.json() : {};
  } catch {
    // Not JSON: fall through to the generic text below. A push must always show something.
  }
  const id = Number.isInteger(data.reminder_id) ? data.reminder_id : null;
  event.waitUntil(
    self.registration.showNotification(typeof data.title === "string" ? data.title : "Promise due", {
      body: typeof data.body === "string" ? data.body : "You have a reminder.",
      icon: "/icon-192.png",
      badge: "/icon-192.png",
      tag: id === null ? "reminder" : `reminder-${id}`, // the same reminder never stacks up twice
      data: { id, url: id === null ? "/" : `/?reminder=${id}` }, // built here: never trust a URL from the payload
    }),
  );
});

// Tapping the notification opens the app on that reminder: an open tab is reused, else a new one opens.
self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  const { id, url } = event.notification.data || { id: null, url: "/" };
  event.waitUntil(
    (async () => {
      const windows = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
      const open = windows.find((client) => new URL(client.url).origin === self.location.origin);
      if (open) {
        open.postMessage({ type: "open-reminder", id });
        return open.focus();
      }
      return self.clients.openWindow(url);
    })(),
  );
});
