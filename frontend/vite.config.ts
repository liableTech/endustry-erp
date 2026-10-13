import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// The API runs on :8080 in development; proxying keeps the browser on one origin (no CORS needed).
// The end-to-end tests point API_URL at their own throwaway server instead.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: { '/api': (globalThis as any).process?.env?.API_URL ?? 'http://localhost:8080' },
  },
  test: { environment: 'node' },
});
