import { describe, expect, it } from "vitest";
import { formatDue, formatWait, isOverdue, languageName, toInputValue } from "./format";
import { encodeWav } from "./wav";

describe("encodeWav", () => {
  it("writes a 16-bit mono PCM WAV with the right sizes", async () => {
    const blob = encodeWav([new Float32Array([0, 1, -1]), new Float32Array([0.5, 2])], 16000);
    expect(blob.type).toBe("audio/wav");
    // jsdom's Blob has no arrayBuffer(), so read it the old way.
    const bytes = await new Promise<ArrayBuffer>((resolve) => {
      const reader = new FileReader();
      reader.onload = () => resolve(reader.result as ArrayBuffer);
      reader.readAsArrayBuffer(blob);
    });
    const view = new DataView(bytes);
    const tag = (o: number) => String.fromCharCode(...[0, 1, 2, 3].map((i) => view.getUint8(o + i)));
    expect([tag(0), tag(8), tag(12), tag(36)]).toEqual(["RIFF", "WAVE", "fmt ", "data"]);
    expect(view.byteLength).toBe(44 + 5 * 2);
    expect(view.getUint32(4, true)).toBe(36 + 10);
    expect(view.getUint16(20, true)).toBe(1); // PCM
    expect(view.getUint16(22, true)).toBe(1); // mono
    expect(view.getUint32(24, true)).toBe(16000);
    expect(view.getUint32(28, true)).toBe(32000);
    expect(view.getUint16(34, true)).toBe(16);
    expect(view.getUint32(40, true)).toBe(10);
    const samples = [0, 1, 2, 3, 4].map((i) => view.getInt16(44 + i * 2, true));
    expect(samples).toEqual([0, 32767, -32767, 16383, 32767]); // loud samples are clipped, not wrapped
  });
});

describe("format", () => {
  it("keeps the server's wall-clock time whatever the browser time zone", () => {
    expect(toInputValue("2026-11-14T17:00+05:30")).toBe("2026-11-14T17:00");
    expect(formatDue("2026-11-14T17:00+05:30")).toMatch(/14 Nov|Nov 14/);
    expect(formatDue("2026-11-14T17:00+05:30")).toMatch(/5:00/);
    expect(formatDue("garbage")).toBe("garbage");
  });

  it("knows overdue, waits and language names", () => {
    const now = new Date("2026-11-14T12:00:00Z");
    expect(isOverdue("2026-11-14T17:00+05:30", now)).toBe(true); // 11:30 UTC
    expect(isOverdue("2026-11-14T18:00+05:30", now)).toBe(false);
    expect([formatWait(0.2), formatWait(59), formatWait(75), formatWait(7500)]).toEqual(["1 s", "59 s", "1 min 15 s", "2 h 5 min"]);
    expect([languageName("ta-IN"), languageName("xx"), languageName(null)]).toEqual(["Tamil", "xx", "Unknown"]);
  });
});
