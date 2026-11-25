import { defineConfig } from '@playwright/test'

/**
 * Optional browser smoke test (Step 7.14). It runs against an already running stack (Docker Compose, or the
 * dev server) given by E2E_BASE_URL, and is NOT part of `pnpm test`: local builds and the main CI jobs need
 * no browser. Run: `npx playwright install chromium` once, then `pnpm e2e`.
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:5173',
    viewport: { width: 1440, height: 900 },
    screenshot: 'off',
  },
})
