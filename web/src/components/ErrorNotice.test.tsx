import { act, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "../api";
import ErrorNotice from "./ErrorNotice";

afterEach(() => vi.useRealTimers());

describe("ErrorNotice", () => {
  it("shows the API message", () => {
    render(<ErrorNotice error={new ApiError(413, "TOO_LARGE", "The file is too big (max 2 MB).")} />);
    expect(screen.getByRole("alert")).toHaveTextContent("The file is too big (max 2 MB).");
    expect(screen.queryByText(/try again/)).not.toBeInTheDocument();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  it("counts down Retry-After on a 429 and only then enables Retry", () => {
    vi.useFakeTimers();
    const onRetry = vi.fn();
    render(<ErrorNotice error={new ApiError(429, "RATE_LIMITED", "Max 3 voice notes per minute.", 3)} onRetry={onRetry} />);
    const retry = screen.getByRole("button", { name: "Retry" });
    expect(screen.getByRole("alert")).toHaveTextContent("Max 3 voice notes per minute.");
    expect(screen.getByText("You can try again in 3 s.")).toBeInTheDocument();
    expect(retry).toBeDisabled();
    act(() => void vi.advanceTimersByTime(1000));
    expect(screen.getByText("You can try again in 2 s.")).toBeInTheDocument();
    act(() => void vi.advanceTimersByTime(2000));
    expect(screen.getByText("You can try again now.")).toBeInTheDocument();
    expect(retry).toBeEnabled();
    fireEvent.click(retry);
    expect(onRetry).toHaveBeenCalledOnce();
  });

  it("shows long waits in hours and minutes", () => {
    vi.useFakeTimers();
    render(<ErrorNotice error={new ApiError(429, "DAILY_QUOTA", "Daily limit of 20 voice notes reached.", 7500)} />);
    expect(screen.getByText("You can try again in 2 h 5 min.")).toBeInTheDocument();
  });

  it("copes with errors that are not from the API", () => {
    const { rerender } = render(<ErrorNotice error={new Error("Microphone blocked.")} />);
    expect(screen.getByRole("alert")).toHaveTextContent("Microphone blocked.");
    rerender(<ErrorNotice error={"weird"} />);
    expect(screen.getByRole("alert")).toHaveTextContent("Something went wrong.");
  });
});
