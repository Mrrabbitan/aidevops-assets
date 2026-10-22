import { defineConfig, devices } from "@playwright/test";
export default defineConfig({
  testDir: "../tests/e2e",
  timeout: 60000,
  expect: { timeout: 15000 },
  fullyParallel: false,
  workers: 1,
  reporter: [
    ["list"],
    ["html", { outputFolder: "../tests/e2e/report", open: "never" }],
  ],
  outputDir: "../tests/e2e/results",
  use: {
    baseURL: process.env.E2E_BASE_URL || "http://localhost:19385",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    video: "off",
  },
  projects: [
    {
      name: "chromium",
      use: {
        ...devices["Desktop Chrome"],
        channel: process.env.E2E_BROWSER_CHANNEL || "chromium",
      },
    },
  ],
});
