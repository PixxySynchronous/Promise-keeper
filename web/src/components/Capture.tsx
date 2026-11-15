import { type FormEvent, useEffect, useRef, useState } from "react";
import { ApiError, detectText, getVoiceNote, uploadVoiceNote } from "../api";
import { LANGUAGES } from "../format";
import { canRecord, MAX_RECORD_SECONDS, type Recording, startRecording } from "../wav";
import ErrorNotice from "./ErrorNotice";
import type { Result } from "./ResultCard";

interface Props {
  token: string;
  onResult: (result: Result | null) => void;
  /** Called after anything that may have changed reminders or usage. */
  onChanged: () => void;
  onSessionExpired: () => void;
  /** Test seams; the defaults are the real microphone and a 1 s poll. */
  record?: typeof startRecording;
  pollMs?: number;
}

type Phase = "idle" | "recording" | "uploading" | "transcribing" | "checking";
const POLL_TRIES = 60;
const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

const STATUS: Record<Phase, string> = {
  idle: "",
  recording: "Recording…",
  uploading: "Uploading…",
  transcribing: "Transcribing…",
  checking: "Checking…",
};

export default function Capture({ token, onResult, onChanged, onSessionExpired, record = startRecording, pollMs = 1000 }: Props) {
  const [language, setLanguage] = useState("indic-auto");
  const [phase, setPhase] = useState<Phase>("idle");
  const [secondsLeft, setSecondsLeft] = useState(MAX_RECORD_SECONDS);
  const [file, setFile] = useState<File | null>(null);
  const [text, setText] = useState("");
  const [error, setError] = useState<unknown>(null);
  const [note, setNote] = useState("");
  const [retry, setRetry] = useState<(() => void) | null>(null);
  const recording = useRef<Recording | null>(null);
  const timer = useRef<ReturnType<typeof setInterval> | null>(null);
  const alive = useRef(true);

  useEffect(() => {
    alive.current = true;
    return () => {
      // Leaving the screen must release the microphone.
      alive.current = false;
      if (timer.current) clearInterval(timer.current);
      void recording.current?.stop();
    };
  }, []);

  function fail(err: unknown, again: () => void) {
    if (err instanceof ApiError && err.status === 401) return onSessionExpired();
    setError(err);
    setRetry(() => again);
  }

  function begin(next: Phase) {
    setPhase(next);
    setError(null);
    setRetry(null);
    setNote("");
  }

  async function send(audio: Blob, filename: string) {
    begin("uploading");
    onResult(null);
    try {
      const queued = await uploadVoiceNote(token, audio, filename, language);
      onChanged(); // the upload already counts against today's usage
      setPhase("transcribing");
      for (let i = 0; i < POLL_TRIES && alive.current; i++) {
        await sleep(pollMs);
        const job = await getVoiceNote(token, queued.id);
        if (job.status === "failed") {
          setError(new Error(job.error ? `Could not transcribe: ${job.error}` : "Could not transcribe this voice note."));
          return;
        }
        if (job.status === "done") {
          onResult({
            source: "voice",
            transcript: job.transcript ?? "",
            detection: job.detection ?? null,
            reminder: job.reminder,
            dueAt: null,
            cached: job.cached,
          });
          onChanged();
          return;
        }
      }
      if (alive.current) setNote("Still working. Your reminder will appear in the list when it's ready; press Refresh there.");
    } catch (err) {
      fail(err, () => void send(audio, filename));
    } finally {
      if (alive.current) setPhase("idle");
    }
  }

  async function stopAndSend() {
    if (timer.current) clearInterval(timer.current);
    timer.current = null;
    const current = recording.current;
    recording.current = null;
    if (!current) return;
    const wav = await current.stop();
    if (alive.current) await send(wav, "voice-note.wav");
  }

  async function startRec() {
    begin("recording");
    setSecondsLeft(MAX_RECORD_SECONDS);
    try {
      recording.current = await record(MAX_RECORD_SECONDS);
    } catch {
      setPhase("idle");
      setError(new Error("Can't use the microphone. Allow microphone access in your browser, or upload a file instead."));
      return;
    }
    if (!alive.current) return void recording.current.stop();
    const started = Date.now();
    timer.current = setInterval(() => {
      const left = MAX_RECORD_SECONDS - (Date.now() - started) / 1000;
      setSecondsLeft(Math.max(0, Math.ceil(left)));
      if (left <= 0) void stopAndSend(); // hard stop at the limit
    }, 250);
  }

  async function check(event: FormEvent) {
    event.preventDefault();
    const typed = text.trim();
    if (!typed) return;
    begin("checking");
    onResult(null);
    try {
      const found = await detectText(token, typed, language);
      const { due_at, ...detection } = found;
      onResult({ source: "text", transcript: typed, detection, reminder: null, dueAt: due_at });
    } catch (err) {
      fail(err, () => void check(event));
    } finally {
      if (alive.current) setPhase("idle");
    }
  }

  const busy = phase !== "idle";
  const isRecording = phase === "recording";
  return (
    <section className="card" aria-labelledby="capture-title">
      <h2 id="capture-title">Say a promise</h2>
      <p className="muted">For example: “kal paanch baje report bhej dunga”. Up to {MAX_RECORD_SECONDS} seconds.</p>

      <label>
        Language
        <select value={language} onChange={(e) => setLanguage(e.target.value)} disabled={busy}>
          {LANGUAGES.map(([code, name]) => (
            <option key={code} value={code}>
              {name}
            </option>
          ))}
        </select>
      </label>

      <div className="row">
        {isRecording ? (
          <button type="button" className="primary recording" onClick={() => void stopAndSend()}>
            Stop and send
          </button>
        ) : (
          <button type="button" className="primary" onClick={() => void startRec()} disabled={busy || !canRecord()}>
            Record
          </button>
        )}
        {isRecording && (
          <span role="timer" aria-label="Recording time left" className="countdown">
            {secondsLeft} s left
          </span>
        )}
      </div>
      {!canRecord() && <p className="muted">This browser can't record audio here. Upload a file instead.</p>}

      <form
        className="row"
        onSubmit={(e) => {
          e.preventDefault();
          if (file) void send(file, file.name);
        }}
      >
        <label>
          Or upload a file (WAV, MP3, OGG, FLAC, AAC, M4A)
          <input
            type="file"
            accept=".wav,.mp3,.ogg,.flac,.aac,.m4a,audio/*"
            disabled={busy}
            onChange={(e) => setFile(e.target.files?.[0] ?? null)}
          />
        </label>
        <button type="submit" disabled={busy || !file}>
          Upload
        </button>
      </form>

      <form className="stack" onSubmit={check}>
        <label>
          Or type it (free check, nothing is saved)
          <textarea rows={2} maxLength={1000} value={text} onChange={(e) => setText(e.target.value)} disabled={busy} />
        </label>
        <button type="submit" disabled={busy || !text.trim()}>
          Check text
        </button>
      </form>

      <p className="status" role="status" aria-live="polite">
        {STATUS[phase] || note}
      </p>
      {error !== null && <ErrorNotice error={error} onRetry={retry ?? undefined} />}
    </section>
  );
}
