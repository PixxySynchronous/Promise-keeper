// Records 16-bit mono WAV in the browser. MediaRecorder would give WebM/Opus, which Gnani rejects, so we
// collect raw samples and write the WAV header ourselves (same encoder as the old static demo page).

export const MAX_RECORD_SECONDS = 30; // Gnani's hard limit per request

export function encodeWav(chunks: Float32Array[], sampleRate: number): Blob {
  const length = chunks.reduce((n, c) => n + c.length, 0);
  const buffer = new ArrayBuffer(44 + length * 2);
  const view = new DataView(buffer);
  const ascii = (offset: number, text: string) => {
    for (let i = 0; i < text.length; i++) view.setUint8(offset + i, text.charCodeAt(i));
  };
  ascii(0, "RIFF");
  view.setUint32(4, 36 + length * 2, true);
  ascii(8, "WAVE");
  ascii(12, "fmt ");
  view.setUint32(16, 16, true); // fmt chunk size
  view.setUint16(20, 1, true); // PCM
  view.setUint16(22, 1, true); // mono
  view.setUint32(24, sampleRate, true);
  view.setUint32(28, sampleRate * 2, true); // bytes per second
  view.setUint16(32, 2, true); // bytes per sample frame
  view.setUint16(34, 16, true); // bits per sample
  ascii(36, "data");
  view.setUint32(40, length * 2, true);
  let offset = 44;
  for (const chunk of chunks) {
    for (const sample of chunk) {
      view.setInt16(offset, Math.max(-1, Math.min(1, sample)) * 0x7fff, true);
      offset += 2;
    }
  }
  return new Blob([buffer], { type: "audio/wav" });
}

// Microphones record at 44.1 or 48 kHz, which is about 96 KB a second as 16-bit WAV: a 30 s note would be
// 2.9 MB, over the server's 2 MB limit. Speech recognition needs no more than 16 kHz (32 KB a second).
export const TARGET_SAMPLE_RATE = 16000;

/** Joins the chunks and lowers the sample rate by averaging each group of input samples (never raises it). */
export function downsample(chunks: Float32Array[], fromRate: number, toRate: number = TARGET_SAMPLE_RATE): Float32Array {
  const length = chunks.reduce((n, c) => n + c.length, 0);
  const all = new Float32Array(length);
  let at = 0;
  for (const chunk of chunks) {
    all.set(chunk, at);
    at += chunk.length;
  }
  if (!(fromRate > toRate)) return all;
  const ratio = fromRate / toRate;
  const out = new Float32Array(Math.floor(length / ratio));
  for (let i = 0; i < out.length; i++) {
    const start = Math.floor(i * ratio);
    const end = Math.min(length, Math.max(start + 1, Math.floor((i + 1) * ratio)));
    let sum = 0;
    for (let j = start; j < end; j++) sum += all[j];
    out[i] = sum / (end - start); // averaging is a simple low-pass filter, enough for speech
  }
  return out;
}

export interface Recording {
  /** Stops the microphone and returns the WAV. Safe to call more than once. */
  stop(): Promise<Blob>;
}

export const canRecord = () =>
  typeof navigator !== "undefined" && !!navigator.mediaDevices?.getUserMedia && typeof AudioContext !== "undefined";

/** Starts recording. Samples past maxSeconds are dropped, so the file can never exceed the limit. */
export async function startRecording(maxSeconds: number = MAX_RECORD_SECONDS): Promise<Recording> {
  const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
  const ctx = new AudioContext();
  const source = ctx.createMediaStreamSource(stream);
  // ScriptProcessor is old but works everywhere without a separate worklet file.
  const node = ctx.createScriptProcessor(4096, 1, 1);
  const chunks: Float32Array[] = [];
  const maxSamples = Math.floor(maxSeconds * ctx.sampleRate);
  let samples = 0;
  node.onaudioprocess = (event) => {
    const room = maxSamples - samples;
    if (room <= 0) return;
    const data = event.inputBuffer.getChannelData(0);
    const part = new Float32Array(data.subarray(0, Math.min(room, data.length)));
    chunks.push(part);
    samples += part.length;
  };
  source.connect(node);
  node.connect(ctx.destination);
  let result: Promise<Blob> | null = null;
  return {
    stop() {
      result ??= (async () => {
        node.disconnect();
        source.disconnect();
        stream.getTracks().forEach((track) => track.stop());
        const rate = ctx.sampleRate;
        await ctx.close();
        return encodeWav([downsample(chunks, rate)], Math.min(rate, TARGET_SAMPLE_RATE));
      })();
      return result;
    },
  };
}
