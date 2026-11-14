import { useState } from "react";
import { ApiError, getWeeklyReport } from "../api";
import ErrorNotice from "./ErrorNotice";

interface Props {
  token: string;
  onSessionExpired: () => void;
}

/** Saves a blob as a file by clicking a temporary link (the standard way to download fetched data). */
function saveFile(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10_000); // give the browser time to start the download
}

export default function ReportButton({ token, onSessionExpired }: Props) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  async function download() {
    setBusy(true);
    setError(null);
    try {
      const { blob, filename } = await getWeeklyReport(token);
      saveFile(blob, filename);
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) onSessionExpired();
      else setError(err);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="report">
      <button type="button" onClick={() => void download()} disabled={busy}>
        {busy ? "Preparing…" : "Download weekly report"}
      </button>
      {error !== null && <ErrorNotice error={error} onRetry={() => void download()} />}
    </div>
  );
}
