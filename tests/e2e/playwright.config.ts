import { defineConfig } from "@playwright/test";
import { env } from "./src/env";

export default defineConfig({
  testDir: "./src",
  workers: 1,
  fullyParallel: false,
  retries: 0,
  timeout: 15_000,
  expect: { timeout: 5_000 },
  reporter: "list",
  use: { baseURL: env.webBaseUrl, navigationTimeout: 5_000 },
  projects: [
    { name: "setup", testMatch: "global.setup.ts" },
    {
      name: "flow",
      testMatch: "signal-lab/*.spec.ts",
      dependencies: ["setup"],
    },
    {
      name: "resilience",
      testMatch: "signal-lab/resilience/*.spec.ts",
      dependencies: ["flow"],
    },
  ],
});
