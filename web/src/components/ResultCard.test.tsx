import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { ApiError } from "../api";
import { detection, reminder } from "../test-helpers";
import ResultCard, { type Result } from "./ResultCard";

const voice = (over: Partial<Result> = {}): Result => ({
  source: "voice",
  transcript: "kal 5 baje report bhej dunga",
  detection: detection(),
  reminder: reminder(),
  dueAt: null,
  ...over,
});

describe("ResultCard", () => {
  it("shows transcript, language, tense, matched phrase and due time for a promise", () => {
    render(<ResultCard result={voice()} onSaveTime={vi.fn()} />);
    expect(screen.getByRole("heading", { name: "Promise found" })).toBeInTheDocument();
    expect(screen.getByText("kal 5 baje report bhej dunga")).toBeInTheDocument();
    expect(screen.getByText("English")).toBeInTheDocument();
    expect(screen.getByText("Future")).toBeInTheDocument();
    expect(screen.getByText("“bhej dunga”")).toBeInTheDocument();
    expect(screen.getByText(/14 Nov|Nov 14/)).toBeInTheDocument();
    expect(screen.getByText(/5:00/)).toBeInTheDocument(); // wall-clock time as the server wrote it
    expect(screen.queryByText("Starter rules")).not.toBeInTheDocument();
    expect(screen.getByLabelText("Change the time")).toHaveValue("2026-11-14T17:00");
  });

  it("shows the starter-rules badge when the language rules are unverified", () => {
    render(<ResultCard result={voice({ detection: detection({ language: "ta-IN", verified_rules: false }) })} onSaveTime={vi.fn()} />);
    expect(screen.getByText("Tamil")).toBeInTheDocument();
    expect(screen.getByText("Starter rules")).toBeInTheDocument();
    expect(screen.getByText(/not been checked by a native speaker yet, so results may be wrong/)).toBeInTheDocument();
  });

  it("explains why something is not a promise, with no time editor", () => {
    const result = voice({
      transcript: "maine kal file bhej di thi",
      reminder: null,
      detection: detection({ is_promise: false, tense: "past", matched: null, reasons: ["past tense"] }),
    });
    render(<ResultCard result={result} onSaveTime={vi.fn()} />);
    expect(screen.getByRole("heading", { name: "No promise found" })).toBeInTheDocument();
    expect(screen.getByText("Why not")).toBeInTheDocument();
    expect(screen.getByText("past tense")).toBeInTheDocument();
    expect(screen.queryByLabelText("Change the time")).not.toBeInTheDocument();
    expect(screen.queryByText(/Due:/)).not.toBeInTheDocument();
  });

  it("says when the time is a guess and when the transcript came from the cache", () => {
    render(<ResultCard result={voice({ cached: true, reminder: reminder({ used_default_time: 1 }) })} onSaveTime={vi.fn()} />);
    expect(screen.getByText(/this is a guess/)).toBeInTheDocument();
    expect(screen.getByText(/no credits spent/)).toBeInTheDocument();
  });

  it("saves an edited time and confirms it", async () => {
    const onSaveTime = vi.fn().mockResolvedValue(undefined);
    const { rerender } = render(<ResultCard result={voice()} onSaveTime={onSaveTime} />);
    const save = screen.getByRole("button", { name: "Save time" });
    expect(save).toBeDisabled(); // nothing changed yet
    fireEvent.change(screen.getByLabelText("Change the time"), { target: { value: "2026-11-15T09:30" } });
    await userEvent.click(save);
    expect(onSaveTime).toHaveBeenCalledWith(7, "2026-11-15T09:30");
    expect(await screen.findByText("Time saved.")).toBeInTheDocument();
    // The parent then passes the updated reminder: the confirmation stays and the new time shows.
    rerender(<ResultCard result={voice({ reminder: reminder({ due_at: "2026-11-15T09:30+05:30" }) })} onSaveTime={onSaveTime} />);
    expect(screen.getByText("Time saved.")).toBeInTheDocument();
    expect(screen.getByText(/15 Nov|Nov 15/)).toBeInTheDocument();
    // A different voice note clears it.
    rerender(<ResultCard result={voice({ reminder: reminder({ id: 8 }) })} onSaveTime={onSaveTime} />);
    expect(screen.queryByText("Time saved.")).not.toBeInTheDocument();
  });

  it("shows the server's message when saving fails, and keeps the typed time", async () => {
    const onSaveTime = vi.fn().mockRejectedValue(new ApiError(422, "DUE_IN_PAST", "Pick a time in the future."));
    render(<ResultCard result={voice()} onSaveTime={onSaveTime} />);
    fireEvent.change(screen.getByLabelText("Change the time"), { target: { value: "2020-01-01T09:30" } });
    await userEvent.click(screen.getByRole("button", { name: "Save time" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Pick a time in the future.");
    expect(screen.getByLabelText("Change the time")).toHaveValue("2020-01-01T09:30");
    expect(screen.queryByText("Time saved.")).not.toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("button", { name: "Save time" })).toBeEnabled());
  });

  it("typed text: shows the due time but offers nothing to save", () => {
    const result: Result = { source: "text", transcript: "I'll call mom tonight", detection: detection(), reminder: null, dueAt: "2026-11-13T21:00+05:30" };
    render(<ResultCard result={result} onSaveTime={vi.fn()} />);
    expect(screen.getByRole("heading", { name: "Promise found" })).toBeInTheDocument();
    expect(screen.getByText(/13 Nov|Nov 13/)).toBeInTheDocument();
    expect(screen.getByText(/only checked, not saved/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Save time" })).not.toBeInTheDocument();
  });
});
