import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

// The backend owns these paths. In dev, Vite forwards them so the browser only ever talks to one origin
// (same as production, where FastAPI serves web/dist itself).
const API_PATHS = ["/auth", "/voice-notes", "/detect", "/reminders", "/usage", "/health", "/push", "/reports"];
const BACKEND = "http://127.0.0.1:8000";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: Object.fromEntries(API_PATHS.map((path) => [path, { target: BACKEND, changeOrigin: false }])),
  },
  build: { outDir: "dist", emptyOutDir: true },
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test-setup.ts"],
    restoreMocks: true,
    unstubGlobals: true,
  },
});
