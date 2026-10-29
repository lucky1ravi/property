import { defineConfig, devices } from '@playwright/test';

const baseURL = process.env.E2E_BASE_URL || 'http://localhost:4173';

export default defineConfig({
  testDir: './tests',
  timeout: 90_000,
  expect: { timeout: 10_000 },
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: process.env.CI ? 3 : 4,
  reporter: [['list'], ['html', { open: 'never' }], ['junit', { outputFile: 'test-results/junit.xml' }]],
  use: { baseURL, trace: 'retain-on-failure', screenshot: 'only-on-failure', video: 'off', viewport: { width: 1366, height: 900 } },
  projects: [
    { name: 'api', testMatch: /tests\/api\/.*\.spec\.ts/ },
    { name: 'chromium', testMatch: /tests\/(ui|journeys)\/.*\.spec\.ts/, use: { ...devices['Desktop Chrome'], viewport: { width: 1366, height: 900 } } },
  ],
});
