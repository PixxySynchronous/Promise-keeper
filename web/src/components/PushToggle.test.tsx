import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "../api";
import * as push from "../push";
import PushToggle from "./PushToggle";

vi.mock("../push");
const mocked = vi.mocked(push);
const show = (onSessionExpired = vi.fn()) => render(<PushToggle token="tok" onSessionExpired={onSessionExpired} />);

describe("PushToggle", () => {
  beforeEach(() => vi.resetAllMocks());

  it("turns notifications on, then off", async () => {
    mocked.pushStatus.mockResolvedValue("off");
    mocked.enablePush.mockResolvedValue("on");
    mocked.disablePush.mockResolvedValue();
    show();
    await userEvent.click(await screen.findByRole("button", { name: "Turn on notifications" }));
    expect(mocked.enablePush).toHaveBeenCalledWith("tok");
    expect(await screen.findByText("Notifications are on for this device.")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Turn off notifications" }));
    expect(mocked.disablePush).toHaveBeenCalledWith("tok");
    expect(await screen.findByRole("button", { name: "Turn on notifications" })).toBeInTheDocument();
  });

  it.each([
    ["unsupported", /can't show notifications/],
    ["not-configured", /aren't set up on this server/],
    ["blocked", /blocked for this site/],
    ["no-worker", /aren't available in this view/],
  ] as const)("explains %s instead of offering a button", async (state, text) => {
    mocked.pushStatus.mockResolvedValue(state);
    show();
    expect(await screen.findByText(text)).toBeInTheDocument();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  it("explains when the browser blocks the permission prompt", async () => {
    mocked.pushStatus.mockResolvedValue("off");
    mocked.enablePush.mockResolvedValue("blocked");
    show();
    await userEvent.click(await screen.findByRole("button", { name: "Turn on notifications" }));
    expect(await screen.findByText(/blocked for this site/)).toBeInTheDocument();
  });

  it("shows the server's message when subscribing fails, and still offers the button", async () => {
    mocked.pushStatus.mockResolvedValue("off");
    mocked.enablePush.mockRejectedValue(new ApiError(503, "PUSH_NOT_CONFIGURED", "Notifications are not set up on this server."));
    show();
    await userEvent.click(await screen.findByRole("button", { name: "Turn on notifications" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Notifications are not set up on this server.");
    expect(screen.getByRole("button", { name: "Turn on notifications" })).toBeEnabled();
  });

  it("goes back to login when the token is rejected", async () => {
    mocked.pushStatus.mockResolvedValue("off");
    mocked.enablePush.mockRejectedValue(new ApiError(401, "INVALID_TOKEN", "Log in again."));
    const expired = vi.fn();
    show(expired);
    await userEvent.click(await screen.findByRole("button", { name: "Turn on notifications" }));
    expect(expired).toHaveBeenCalled();
  });
});
