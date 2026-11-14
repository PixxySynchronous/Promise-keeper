import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { apiError, jsonResponse, mockApi } from "../test-helpers";
import AuthForm from "./AuthForm";

async function fill(email: string, password: string) {
  await userEvent.type(screen.getByLabelText("Email"), email);
  await userEvent.type(screen.getByLabelText("Password"), password);
}

describe("AuthForm", () => {
  it("logs in and hands back the token", async () => {
    const api = mockApi({ "POST /auth/login": jsonResponse({ token: "tok-1" }) });
    const onLoggedIn = vi.fn();
    render(<AuthForm onLoggedIn={onLoggedIn} />);
    await fill("a@example.com", "password123");
    await userEvent.click(screen.getByRole("button", { name: "Log in" }));
    expect(onLoggedIn).toHaveBeenCalledWith("tok-1");
    expect(JSON.parse(api.calls[0].init!.body as string)).toEqual({ email: "a@example.com", password: "password123" });
  });

  it("shows the server's message for a wrong password", async () => {
    mockApi({ "POST /auth/login": apiError(401, "BAD_CREDENTIALS", "Wrong email or password.") });
    const onLoggedIn = vi.fn();
    render(<AuthForm onLoggedIn={onLoggedIn} />);
    await fill("a@example.com", "wrong-password");
    await userEvent.click(screen.getByRole("button", { name: "Log in" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Wrong email or password.");
    expect(onLoggedIn).not.toHaveBeenCalled();
  });

  it("shows a countdown when logins are rate limited", async () => {
    mockApi({ "POST /auth/login": apiError(429, "TOO_MANY_LOGINS", "Too many login attempts. Try later.", 600) });
    render(<AuthForm onLoggedIn={vi.fn()} />);
    await fill("a@example.com", "password123");
    await userEvent.click(screen.getByRole("button", { name: "Log in" }));
    expect(await screen.findByText(/You can try again in 10 min 0 s/)).toBeInTheDocument();
  });

  it("registers with a code, then logs in", async () => {
    const api = mockApi({
      "POST /auth/register": jsonResponse({ id: 1, email: "b@example.com" }, 201),
      "POST /auth/login": jsonResponse({ token: "tok-2" }),
    });
    const onLoggedIn = vi.fn();
    render(<AuthForm onLoggedIn={onLoggedIn} />);
    await userEvent.click(screen.getByRole("button", { name: "Create an account" }));
    await fill("b@example.com", "password123");
    await userEvent.type(screen.getByLabelText(/Registration code/), "letmein");
    await userEvent.click(screen.getByRole("button", { name: "Create account" }));
    expect(onLoggedIn).toHaveBeenCalledWith("tok-2");
    expect(api.keys()).toEqual(["POST /auth/register", "POST /auth/login"]);
    expect(JSON.parse(api.calls[0].init!.body as string).registration_code).toBe("letmein");
  });

  it("does not try to log in when registration is refused", async () => {
    const api = mockApi({ "POST /auth/register": apiError(409, "EMAIL_TAKEN", "That email is already registered.") });
    render(<AuthForm onLoggedIn={vi.fn()} notice="Your session ended. Log in again." />);
    expect(screen.getByText("Your session ended. Log in again.")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Create an account" }));
    await fill("b@example.com", "password123");
    await userEvent.click(screen.getByRole("button", { name: "Create account" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("That email is already registered.");
    expect(api.keys()).toEqual(["POST /auth/register"]);
  });
});
