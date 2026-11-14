import { defineConfig, devices } from "@playwright/test";

/**
 * End-to-end tests against the real local stack: Keycloak and Postgres from infra/docker-compose.yml and
 * the backend with profiles api,local (see README). Playwright starts the built Next.js app itself.
 */
export default defineConfig({
  testDir: "e2e",
  timeout: 90_000,
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : "list",
  use: {
    baseURL: "http://localhost:3000",
    trace: "off",
    launchOptions: process.env.PW_CHROMIUM_PATH ? { executablePath: process.env.PW_CHROMIUM_PATH } : {},
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: {
    command: "pnpm start",
    url: "http://localhost:3000/healthz",
    reuseExistingServer: !process.env.CI,
    timeout: 60_000,
    env: {
      APP_URL: "http://localhost:3000",
      BACKEND_URL: process.env.BACKEND_URL ?? "http://localhost:8080",
      OIDC_ISSUER: process.env.OIDC_ISSUER ?? "http://localhost:8180/realms/operator",
      OIDC_CLIENT_ID: "operator-web",
      OIDC_CLIENT_SECRET: process.env.OIDC_CLIENT_SECRET ?? "local-operator-web-secret",
      SESSION_SECRET: process.env.SESSION_SECRET ?? "e2e-session-secret-with-at-least-32-characters",
    },
  },
});
