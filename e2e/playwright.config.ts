import { defineConfig, devices } from '@playwright/test';

// The tests run against their own copy of the app (API on 18080, web app on 15173, fresh demo
// database), never the one you start with run-backend.cmd / run-frontend.cmd.
const API_PORT = process.env.E2E_API_PORT ?? '18080';
const WEB_PORT = process.env.E2E_WEB_PORT ?? '15173';
const BASE_URL = `http://localhost:${WEB_PORT}`;

export default defineConfig({
  testDir: './tests',
  // One shared database, so tests run one at a time in a fixed order.
  workers: 1,
  fullyParallel: false,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  retries: 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: BASE_URL,
    screenshot: 'on',
    trace: 'retain-on-failure',
    viewport: { width: 1440, height: 900 },
    launchOptions: process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : undefined,
  },
  projects: [
    { name: 'setup', testMatch: /auth\.setup\.ts/ },
    { name: 'chromium', use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } }, dependencies: ['setup'] },
  ],
  webServer: [
    {
      command: 'node scripts/start-api.mjs',
      url: `http://localhost:${API_PORT}/api/public/i18n/languages`,
      env: { E2E_API_PORT: API_PORT },
      timeout: 600_000,
      reuseExistingServer: !!process.env.E2E_REUSE,
      stdout: 'ignore',
      stderr: 'pipe',
    },
    {
      command: `npm --prefix ../frontend run dev -- --port ${WEB_PORT} --strictPort`,
      url: BASE_URL,
      env: { API_URL: `http://localhost:${API_PORT}` },
      timeout: 120_000,
      reuseExistingServer: !!process.env.E2E_REUSE,
    },
  ],
});
