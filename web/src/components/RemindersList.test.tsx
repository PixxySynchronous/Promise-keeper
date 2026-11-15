import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { ApiError } from "../api";
import { reminder } from "../test-helpers";
import RemindersList from "./RemindersList";

const rows = [
  reminder({ id: 1, text: "send report", due_at: "2999-01-01T10:00+05:30" }),
  reminder({ id: 2, text: "call mom", due_at: "2020-01-01T10:00+05:30" }),
  reminder({ id: 3, text: "pay rent", done_at: "2026-11-13T10:00:00+00:00" }),
];
const noop = { loadError: null, onReload: vi.fn(), onSetDone: vi.fn().mockResolvedValue(undefined), onDelete: vi.fn().mockResolvedValue(undefined) };

describe("RemindersList", () => {
  it("splits upcoming and done, and flags overdue ones", () => {
    render(<RemindersList reminders={rows} {...noop} />);
    const upcoming = screen.getByRole("list", { name: /Upcoming \(2\)/ });
    expect(within(upcoming).getByText("send report")).toBeInTheDocument();
    expect(within(upcoming).getAllByText("Overdue")).toHaveLength(1);
    expect(within(screen.getByRole("list", { name: /Done \(1\)/ })).getByText("pay rent")).toBeInTheDocument();
  });

  it("marks done, reopens and deletes through labelled buttons", async () => {
    const onSetDone = vi.fn().mockResolvedValue(undefined);
    const onDelete = vi.fn().mockResolvedValue(undefined);
    render(<RemindersList reminders={rows} {...noop} onSetDone={onSetDone} onDelete={onDelete} />);
    await userEvent.click(screen.getByRole("button", { name: "Mark done: send report" }));
    expect(onSetDone).toHaveBeenCalledWith(1, true);
    await userEvent.click(screen.getByRole("button", { name: "Reopen: pay rent" }));
    expect(onSetDone).toHaveBeenCalledWith(3, false);
    await userEvent.click(screen.getByRole("button", { name: "Delete: call mom" }));
    expect(onDelete).toHaveBeenCalledWith(2);
  });

  it("shows empty, loading, load-error and action-error states", async () => {
    const { rerender } = render(<RemindersList reminders={null} {...noop} />);
    expect(screen.getByText("Loading…")).toBeInTheDocument();
    rerender(<RemindersList reminders={[]} {...noop} />);
    expect(screen.getByText(/Nothing yet/)).toBeInTheDocument();
    const onReload = vi.fn();
    rerender(<RemindersList reminders={null} {...noop} loadError={new ApiError(503, "DOWN", "Back soon.")} onReload={onReload} />);
    expect(screen.getByRole("alert")).toHaveTextContent("Back soon.");
    await userEvent.click(screen.getByRole("button", { name: "Retry" }));
    expect(onReload).toHaveBeenCalled();
    rerender(<RemindersList reminders={rows} {...noop} onDelete={vi.fn().mockRejectedValue(new ApiError(404, "NOT_FOUND", "No such reminder."))} />);
    await userEvent.click(screen.getByRole("button", { name: "Delete: call mom" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("No such reminder.");
  });

  it("marks the reminder a notification was tapped for, or says it is gone", () => {
    const { rerender } = render(<RemindersList reminders={rows} {...noop} highlightId={2} />);
    expect(screen.getByText("call mom").closest("li")).toHaveAttribute("aria-current", "true");
    expect(screen.getByText("send report").closest("li")).not.toHaveAttribute("aria-current");
    rerender(<RemindersList reminders={rows} {...noop} highlightId={99} />);
    expect(screen.getByText("That reminder is no longer in your list.")).toBeInTheDocument();
  });
});
