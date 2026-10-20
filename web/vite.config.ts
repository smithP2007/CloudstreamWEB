import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // Backend runs on 8080 (scripts/run.sh); API + stream proxying happen there.
    proxy: {
      "/api": "http://localhost:8080",
      "/stream": "http://localhost:8080",
    },
  },
  build: {
    outDir: "dist",
  },
});
