// Playwright configuration for the landing page end-to-end suite.
//
// The pages are served exactly as in development (frontend/serve.py on 5500,
// no-cache). Everything the pages would call over the network is intercepted
// in the browser by tests/helpers.js: the waitlist API, the resend endpoint,
// the Plausible script (/js/script.js, replaced by a recorder) and /api/event.
// The only real third-party request is Cloudflare Turnstile, which the
// third-party-hosts spec explicitly allows and every other spec inherits.
const { defineConfig, devices } = require('@playwright/test');

module.exports = defineConfig({
  testDir: './tests',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : [['list']],
  use: {
    baseURL: 'http://localhost:5500',
    trace: 'retain-on-failure',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
  ],
  webServer: {
    command: 'python3 serve.py',
    cwd: 'frontend',
    url: 'http://localhost:5500/index.html',
    reuseExistingServer: !process.env.CI,
    timeout: 30_000,
  },
});
