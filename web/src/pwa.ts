import { useEffect, useState } from "react";

/** True while the browser has a connection. Re-renders when that changes. */
export function useOnline(): boolean {
  const [online, setOnline] = useState(() => (typeof navigator === "undefined" ? true : navigator.onLine !== false));
  useEffect(() => {
    const up = () => setOnline(true);
    const down = () => setOnline(false);
    window.addEventListener("online", up);
    window.addEventListener("offline", down);
    return () => {
      window.removeEventListener("online", up);
      window.removeEventListener("offline", down);
    };
  }, []);
  return online;
}

interface InstallPromptEvent extends Event {
  prompt(): Promise<void>;
  userChoice: Promise<{ outcome: "accepted" | "dismissed" }>;
}

/** Chrome fires `beforeinstallprompt` when the app can be installed. Returns a function to show the prompt, or null. */
export function useInstallPrompt(): (() => void) | null {
  const [event, setEvent] = useState<InstallPromptEvent | null>(null);
  useEffect(() => {
    const ready = (e: Event) => {
      e.preventDefault(); // keep it for our own button instead of the browser's mini-bar
      setEvent(e as InstallPromptEvent);
    };
    const installed = () => setEvent(null);
    window.addEventListener("beforeinstallprompt", ready);
    window.addEventListener("appinstalled", installed);
    return () => {
      window.removeEventListener("beforeinstallprompt", ready);
      window.removeEventListener("appinstalled", installed);
    };
  }, []);
  if (!event) return null;
  return () => {
    void event.prompt().catch(() => undefined);
    setEvent(null); // a prompt can only be used once
  };
}
