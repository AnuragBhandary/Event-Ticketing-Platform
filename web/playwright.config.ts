import { defineConfig, devices } from "@playwright/test";

// End-to-end tests run against the real stack: `docker compose up -d --build --wait` from the
// repository root (nginx on :8090, two API instances, PostgreSQL), then `npm run e2e`.
export default defineConfig({
  testDir: "e2e",
  timeout: 180_000,
  expect: { timeout: 30_000 },
  workers: 1,
  reporter: [["list"], ["html", { open: "never" }]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:8090",
    trace: "retain-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
});
