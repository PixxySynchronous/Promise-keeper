import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiError, mockApi } from "../test-helpers";
import ReportButton from "./ReportButton";

const pdf = () =>
  new Response(new Blob(["%PDF-1.4 fake"], { type: "application/pdf" }), {
    headers: { "Content-Type": "application/pdf", "Content-Disposition": 'attachment; filename="promise-keeper-weekly-2026-11-13.pdf"' },
  });

describe("ReportButton", () => {
  let clicked: HTMLAnchorElement[];
  beforeEach(() => {
    clicked = [];
    // jsdom has neither object URLs nor downloads: record what would have been saved.
    URL.createObjectURL = vi.fn(() => "blob:fake");
    URL.revokeObjectURL = vi.fn();
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(function (this: HTMLAnchorElement) {
      clicked.push(this);
    });
  });
  afterEach(() => vi.restoreAllMocks());

  it("fetches the PDF with the token and saves it under the server's file name", async () => {
    const api = mockApi({ "GET /reports/weekly.pdf": pdf });
    render(<ReportButton token="tok" onSessionExpired={vi.fn()} />);
    await userEvent.click(screen.getByRole("button", { name: "Download weekly report" }));
    await waitFor(() => expect(clicked).toHaveLength(1));
    expect(clicked[0].download).toBe("promise-keeper-weekly-2026-11-13.pdf");
    expect(clicked[0].href).toBe("blob:fake");
    expect((api.calls[0].init!.headers as Headers).get("Authorization")).toBe("Bearer tok");
    expect(document.querySelector("a[download]")).toBeNull(); // the temporary link is removed
    expect(screen.getByRole("button", { name: "Download weekly report" })).toBeEnabled();
  });

  it("shows the server's message and lets the user retry", async () => {
    let calls = 0;
    mockApi({ "GET /reports/weekly.pdf": () => (++calls === 1 ? apiError(503, "DOWN", "Back in a minute.") : pdf()) });
    render(<ReportButton token="tok" onSessionExpired={vi.fn()} />);
    await userEvent.click(screen.getByRole("button", { name: "Download weekly report" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Back in a minute.");
    expect(clicked).toHaveLength(0);
    await userEvent.click(screen.getByRole("button", { name: "Retry" }));
    await waitFor(() => expect(clicked).toHaveLength(1));
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("goes back to login when the token is rejected", async () => {
    mockApi({ "GET /reports/weekly.pdf": apiError(401, "INVALID_TOKEN", "Log in again.") });
    const expired = vi.fn();
    render(<ReportButton token="tok" onSessionExpired={expired} />);
    await userEvent.click(screen.getByRole("button", { name: "Download weekly report" }));
    await waitFor(() => expect(expired).toHaveBeenCalled());
  });
});
