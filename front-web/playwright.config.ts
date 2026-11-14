import { defineConfig, devices } from '@playwright/test';

/**
 * End-to-end tests against the real local stack: Keycloak, the backend and the front end.
 *
 *   cd infra/compose && docker compose up -d --wait       # infrastructure
 *   cd backend/ebank-app && ../mvnw spring-boot:run       # backend, dev profile
 *   cd front-web && npm run e2e                           # starts `ng serve` if needed
 *
 * E2E_BASE_URL points the tests at a front end that is already running (for example the
 * front-web container on :4200). The tests create their own users, so they can run again.
 */
const baseURL = process.env['E2E_BASE_URL'] ?? 'http://localhost:4200';

export default defineConfig({
  testDir: './e2e',
  // One shared backend: tests run one after the other, so their checks don't race.
  fullyParallel: false,
  workers: 1,
  retries: process.env['CI'] ? 1 : 0,
  timeout: 90_000,
  expect: { timeout: 15_000 },
  reporter: process.env['CI']
    ? [['list'], ['junit', { outputFile: 'test-results/junit.xml' }], ['html', { open: 'never' }]]
    : 'list',
  globalSetup: './e2e/support/global-setup.ts',
  use: {
    baseURL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  // Keycloak only accepts http://localhost:4200 as the app's origin, so the dev server uses that port.
  webServer: process.env['E2E_BASE_URL']
    ? undefined
    : { command: 'npm start', url: baseURL, reuseExistingServer: true, timeout: 180_000 },
});
