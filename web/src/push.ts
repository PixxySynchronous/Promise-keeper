// Notifications when a reminder is due (Web Push). The browser gives us a subscription (an address at its
// push service plus encryption keys); the server stores it and pushes to it at the due time. The service
// worker (public/sw.js) shows the notification.
import { getPushConfig, pushSubscribe, pushUnsubscribe } from "./api";

export type PushState =
  | "unsupported" // this browser can't do Web Push (or, on iPhone, the app isn't installed to the Home Screen)
  | "no-worker" // no service worker (dev server, or it failed to register)
  | "not-configured" // the server has no VAPID keys
  | "blocked" // the user said no to notifications in the browser
  | "off"
  | "on";

export const pushSupported = () =>
  typeof navigator !== "undefined" &&
  "serviceWorker" in navigator &&
  typeof window !== "undefined" &&
  "PushManager" in window &&
  "Notification" in window;

/** VAPID public key (base64url) → the bytes pushManager.subscribe() wants. */
export function keyBytes(base64url: string): Uint8Array<ArrayBuffer> {
  const padded = base64url.replace(/-/g, "+").replace(/_/g, "/").padEnd(Math.ceil(base64url.length / 4) * 4, "=");
  const raw = atob(padded);
  const bytes = new Uint8Array(new ArrayBuffer(raw.length));
  for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
  return bytes;
}

async function registration(): Promise<ServiceWorkerRegistration | null> {
  // Not `serviceWorker.ready`: that never settles when no worker is registered (the dev server).
  return (await navigator.serviceWorker.getRegistration()) ?? null;
}

/**
 * Where things stand for this browser. If it is already subscribed, the subscription is sent to the server
 * again: the server may have lost it (a redeploy can reset its database) and re-sending is harmless.
 */
export async function pushStatus(token: string): Promise<PushState> {
  if (!pushSupported()) return "unsupported";
  const reg = await registration();
  if (!reg) return "no-worker";
  if (!(await getPushConfig()).enabled) return "not-configured";
  if (Notification.permission === "denied") return "blocked";
  const existing = await reg.pushManager.getSubscription();
  if (!existing || Notification.permission !== "granted") return "off";
  await pushSubscribe(token, existing.toJSON());
  return "on";
}

/** Asks for permission (must be called from a click), subscribes this browser and tells the server. */
export async function enablePush(token: string): Promise<PushState> {
  const reg = await registration();
  if (!pushSupported() || !reg) return "unsupported";
  const config = await getPushConfig();
  if (!config.enabled || !config.public_key) return "not-configured";
  const permission = await Notification.requestPermission();
  if (permission === "denied") return "blocked";
  if (permission !== "granted") return "off"; // the prompt was dismissed
  const options = { userVisibleOnly: true, applicationServerKey: keyBytes(config.public_key) };
  let subscription: PushSubscription;
  try {
    subscription = await reg.pushManager.subscribe(options);
  } catch {
    // An old subscription made with a different server key blocks a new one: drop it and try once more.
    await (await reg.pushManager.getSubscription())?.unsubscribe();
    subscription = await reg.pushManager.subscribe(options);
  }
  try {
    await pushSubscribe(token, subscription.toJSON());
  } catch (err) {
    await subscription.unsubscribe().catch(() => undefined); // don't leave a subscription the server doesn't know
    throw err;
  }
  return "on";
}

/** Stops notifications on this browser. Used by "Turn off" and by logout (so the next person isn't notified). */
export async function disablePush(token: string): Promise<void> {
  if (!pushSupported()) return;
  const subscription = await (await registration())?.pushManager.getSubscription();
  if (!subscription) return;
  const endpoint = subscription.endpoint;
  await subscription.unsubscribe();
  await pushUnsubscribe(token, endpoint);
}
