import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './e2e',
  outputDir: '../test_run/browser-results',
  fullyParallel: false,
  workers: 1,
  reporter: [['list'], ['html', { outputFolder: '../test_run/reports/browser', open: 'never' }]],
  use: {
    baseURL: 'http://127.0.0.1:18081',
    viewport: { width: 1440, height: 960 },
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
    launchOptions: process.env.GB_TEST_BROWSER ? { executablePath: process.env.GB_TEST_BROWSER } : {},
  },
  webServer: {
    command: 'node e2e/support/web-fixture.cjs',
    url: 'http://127.0.0.1:18081',
    timeout: 60000,
    reuseExistingServer: false,
  },
})
