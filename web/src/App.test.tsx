import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import App from "./App";
import { saveToken } from "./session";
import { apiError, jsonResponse, mockApi, reminder, usage } from "./test-helpers";

beforeEach(() => saveToken(null));

describe("App", () => {
  it("starts on the login screen and makes no API calls", () => {
    const api = mockApi({});
    render(<App />);
    expect(screen.getByRole("heading", { name: "Log in" })).toBeInTheDocument();
    expect(api.calls).toHaveLength(0);
  });

  it("after login shows reminders and today's usage against the limits", async () => {
    mockApi({
      "POST /auth/login": jsonResponse({ token: "tok" }),
      "GET /reminders": jsonResponse([reminder()]),
      "GET /usage/me": jsonResponse(usage(3)),
    });
    render(<App />);
    await userEvent.type(screen.getByLabelText("Email"), "a@example.com");
    await userEvent.type(screen.getByLabelText("Password"), "password123");
    await userEvent.click(screen.getByRole("button", { name: "Log in" }));
    expect(await screen.findByText("kal 5 baje report bhej dunga")).toBeInTheDocument();
    expect(await screen.findByLabelText(/Voice notes: 3 of 20/)).toHaveValue(3);
    expect(screen.getByLabelText(/Audio: 42 of 300 seconds/)).toBeInTheDocument();
    expect(sessionStorage.getItem("pk_token")).toBe("tok");
  });

  it("returns to login with a notice when the saved token is no longer valid", async () => {
    saveToken("stale");
    mockApi({
      "GET /reminders": apiError(401, "INVALID_TOKEN", "Log in again."),
      "GET /usage/me": apiError(401, "INVALID_TOKEN", "Log in again."),
    });
    render(<App />);
    expect(await screen.findByText("Your session ended. Log in again.")).toBeInTheDocument();
    expect(sessionStorage.getItem("pk_token")).toBeNull();
  });

  it("shows a load error with Retry instead of an empty list", async () => {
    saveToken("tok");
    let attempts = 0;
    mockApi({
      "GET /reminders": () => (++attempts === 1 ? apiError(503, "DOWN", "Back in a minute.") : jsonResponse([reminder()])),
      "GET /usage/me": jsonResponse(usage()),
    });
    render(<App />);
    expect(await screen.findByText("Back in a minute.")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Retry" }));
    expect(await screen.findByText("kal 5 baje report bhej dunga")).toBeInTheDocument();
  });

  it("logs out and forgets the token", async () => {
    saveToken("tok");
    const api = mockApi({
      "GET /reminders": jsonResponse([]),
      "GET /usage/me": jsonResponse(usage()),
      "POST /auth/logout": jsonResponse(null, 204),
    });
    render(<App />);
    await userEvent.click(await screen.findByRole("button", { name: "Log out" }));
    expect(screen.getByRole("heading", { name: "Log in" })).toBeInTheDocument();
    expect(sessionStorage.getItem("pk_token")).toBeNull();
    await waitFor(() => expect(api.keys()).toContain("POST /auth/logout"));
  });

  it("still works when sessionStorage is blocked", async () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new DOMException("blocked", "SecurityError");
    });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new DOMException("blocked", "SecurityError");
    });
    mockApi({
      "POST /auth/login": jsonResponse({ token: "tok" }),
      "GET /reminders": jsonResponse([]),
      "GET /usage/me": jsonResponse(usage()),
    });
    render(<App />);
    await userEvent.type(screen.getByLabelText("Email"), "a@example.com");
    await userEvent.type(screen.getByLabelText("Password"), "password123");
    await userEvent.click(screen.getByRole("button", { name: "Log in" }));
    expect(await screen.findByRole("heading", { name: "Say a promise" })).toBeInTheDocument();
  });

  it("says so when offline, and reloads when the connection returns", async () => {
    saveToken("tok");
    const online = vi.spyOn(navigator, "onLine", "get").mockReturnValue(false);
    const api = mockApi({ "GET /reminders": jsonResponse([reminder()]), "GET /usage/me": jsonResponse(usage()) });
    render(<App />);
    expect(screen.getByText(/You're offline/)).toBeInTheDocument();
    expect(api.calls).toHaveLength(0); // no pointless requests while offline
    online.mockReturnValue(true);
    act(() => void window.dispatchEvent(new Event("online")));
    expect(await screen.findByText("kal 5 baje report bhej dunga")).toBeInTheDocument();
    expect(screen.queryByText(/You're offline/)).not.toBeInTheDocument();
  });

  it("offers Install app only when the browser says it can be installed", async () => {
    mockApi({});
    render(<App />);
    expect(screen.queryByRole("button", { name: "Install app" })).not.toBeInTheDocument();
    const prompt = vi.fn().mockResolvedValue(undefined);
    act(() => void window.dispatchEvent(Object.assign(new Event("beforeinstallprompt", { cancelable: true }), { prompt })));
    await userEvent.click(await screen.findByRole("button", { name: "Install app" }));
    expect(prompt).toHaveBeenCalledOnce();
    expect(screen.queryByRole("button", { name: "Install app" })).not.toBeInTheDocument();
  });

  it("opens on the reminder from a tapped notification (/?reminder=7)", async () => {
    saveToken("tok");
    window.history.replaceState(null, "", "/?reminder=7");
    mockApi({ "GET /reminders": jsonResponse([reminder({ id: 7 }), reminder({ id: 8, text: "other" })]), "GET /usage/me": jsonResponse(usage()) });
    render(<App />);
    expect((await screen.findByText("kal 5 baje report bhej dunga")).closest("li")).toHaveAttribute("aria-current", "true");
    expect(screen.getByText("other").closest("li")).not.toHaveAttribute("aria-current");
    window.history.replaceState(null, "", "/");
  });
});
