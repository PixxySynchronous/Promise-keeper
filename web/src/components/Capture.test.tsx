import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { apiError, detection, jsonResponse, mockApi, reminder } from "../test-helpers";
import Capture from "./Capture";

const queued = jsonResponse({ id: "job1", status: "queued", audio_seconds: 1.5, poll: "/voice-notes/job1" }, 202);
const doneJob = {
  id: "job1", status: "done", audio_seconds: 1.5, language: "indic-auto", transcript: "kal 5 baje report bhej dunga",
  detected_language: null, cached: false, error: null, reminder: reminder(), detection: detection(),
};
const wavFile = () => new File([new Uint8Array(64)], "note.wav", { type: "audio/wav" });

function setup(extra: Partial<React.ComponentProps<typeof Capture>> = {}) {
  const props = { onResult: vi.fn(), onChanged: vi.fn(), onSessionExpired: vi.fn() };
  render(<Capture token="tok" pollMs={1} {...props} {...extra} />);
  return props;
}

async function uploadFile() {
  await userEvent.upload(screen.getByLabelText(/Or upload a file/), wavFile());
  await userEvent.click(screen.getByRole("button", { name: "Upload" }));
}

describe("Capture", () => {
  it("uploads a file with the chosen language, polls, and reports the result", async () => {
    let polls = 0;
    const api = mockApi({
      "POST /voice-notes": queued,
      "GET /voice-notes/job1": () => jsonResponse(++polls < 2 ? { ...doneJob, status: "processing", reminder: null } : doneJob),
    });
    const props = setup();
    await userEvent.selectOptions(screen.getByLabelText("Language"), "ta-IN");
    await uploadFile();
    await waitFor(() => expect(props.onResult).toHaveBeenLastCalledWith(expect.objectContaining({ source: "voice", transcript: doneJob.transcript })));
    const form = api.calls[0].init!.body as FormData;
    expect(form.get("language")).toBe("ta-IN");
    expect((form.get("audio_file") as File).name).toBe("note.wav");
    expect((api.calls[0].init!.headers as Headers).get("Authorization")).toBe("Bearer tok");
    expect(polls).toBe(2);
    expect(props.onChanged).toHaveBeenCalled();
  });

  it("shows the rate-limit message with a countdown and a Retry that waits", async () => {
    mockApi({ "POST /voice-notes": apiError(429, "RATE_LIMITED", "Max 3 voice notes per minute.", 40) });
    const props = setup();
    await uploadFile();
    expect(await screen.findByRole("alert")).toHaveTextContent("Max 3 voice notes per minute.");
    expect(screen.getByText("You can try again in 40 s.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Retry" })).toBeDisabled();
    expect(props.onResult).not.toHaveBeenCalledWith(expect.objectContaining({ source: "voice" }));
  });

  it("shows upload rejections and failed transcriptions", async () => {
    mockApi({ "POST /voice-notes": apiError(413, "TOO_LARGE", "The file is too big.") });
    const first = render(<Capture token="tok" pollMs={1} onResult={vi.fn()} onChanged={vi.fn()} onSessionExpired={vi.fn()} />);
    await uploadFile();
    expect(await screen.findByRole("alert")).toHaveTextContent("The file is too big.");
    first.unmount();

    mockApi({ "POST /voice-notes": queued, "GET /voice-notes/job1": jsonResponse({ ...doneJob, status: "failed", error: "provider timeout", reminder: null }) });
    setup();
    await uploadFile();
    expect(await screen.findByRole("alert")).toHaveTextContent("Could not transcribe: provider timeout");
  });

  it("sends the user back to login when the token is rejected", async () => {
    mockApi({ "POST /voice-notes": apiError(401, "INVALID_TOKEN", "Log in again.") });
    const props = setup();
    await uploadFile();
    await waitFor(() => expect(props.onSessionExpired).toHaveBeenCalled());
  });

  it("checks typed text for free", async () => {
    const api = mockApi({ "POST /detect": jsonResponse({ ...detection(), due_at: "2026-11-13T21:00+05:30" }) });
    const props = setup();
    await userEvent.type(screen.getByLabelText(/Or type it/), "I'll call mom tonight");
    await userEvent.click(screen.getByRole("button", { name: "Check text" }));
    await waitFor(() =>
      expect(props.onResult).toHaveBeenLastCalledWith(expect.objectContaining({ source: "text", reminder: null, dueAt: "2026-11-13T21:00+05:30" })),
    );
    expect(JSON.parse(api.calls[0].init!.body as string)).toEqual({ text: "I'll call mom tonight", language: "indic-auto" });
  });

  it("records with a countdown, then sends the WAV on stop", async () => {
    vi.stubGlobal("AudioContext", class {});
    vi.stubGlobal("navigator", { ...navigator, mediaDevices: { getUserMedia: vi.fn() } });
    const api = mockApi({ "POST /voice-notes": queued, "GET /voice-notes/job1": jsonResponse(doneJob) });
    const stop = vi.fn().mockResolvedValue(new Blob([new Uint8Array(100)], { type: "audio/wav" }));
    const record = vi.fn().mockResolvedValue({ stop });
    const props = setup({ record });
    await userEvent.click(screen.getByRole("button", { name: "Record" }));
    expect(await screen.findByRole("timer")).toHaveTextContent("30 s left");
    expect(record).toHaveBeenCalledWith(30);
    await userEvent.click(screen.getByRole("button", { name: "Stop and send" }));
    await waitFor(() => expect(props.onResult).toHaveBeenLastCalledWith(expect.objectContaining({ source: "voice" })));
    expect(stop).toHaveBeenCalled();
    expect(((api.calls[0].init!.body as FormData).get("audio_file") as File).name).toBe("voice-note.wav");
  });

  it("explains a blocked microphone, and a browser that cannot record", async () => {
    setup();
    expect(screen.getByRole("button", { name: "Record" })).toBeDisabled(); // jsdom has no microphone
    expect(screen.getByText(/can't record audio here/)).toBeInTheDocument();
  });

  it("explains a refused microphone permission", async () => {
    vi.stubGlobal("AudioContext", class {});
    vi.stubGlobal("navigator", { ...navigator, mediaDevices: { getUserMedia: vi.fn() } });
    setup({ record: vi.fn().mockRejectedValue(new DOMException("denied", "NotAllowedError")) });
    await userEvent.click(screen.getByRole("button", { name: "Record" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(/Allow microphone access/);
    expect(screen.getByRole("button", { name: "Record" })).toBeEnabled();
  });
});
