// The privacy policy names only our own vendors. This spec FAILS on any request
// to a host other than our origin and challenges.cloudflare.com (Turnstile), on
// every page, through a full signup and the confirmed page. A new font, icon
// CDN, tracking pixel or analytics vendor shows up here before it ships.
const { test, expect } = require('@playwright/test');
const { installMocks, forceAnalytics, collectHosts, submitWaitlist, assertOnlyAllowedHosts } = require('./helpers');

const PAGES = ['/index.html', '/about.html', '/privacy.html', '/terms.html', '/confirmed.html?status=confirmed&hours=%3C1&tier=founder'];

for (const path of PAGES) {
  test(`${path} talks only to our origin and Turnstile`, async ({ page }) => {
    await forceAnalytics(page);
    await installMocks(page);
    const seen = collectHosts(page);
    await page.goto(path);
    await page.waitForLoadState('networkidle');
    // Scroll to the bottom so lazy resources (if any) load too.
    await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
    await page.waitForTimeout(500);
    assertOnlyAllowedHosts(seen);
    expect(seen.get('localhost') || seen.get('127.0.0.1')).toBeGreaterThan(0);
  });
}

test('a full signup on index.html stays inside the allowed hosts', async ({ page }) => {
  await forceAnalytics(page);
  await installMocks(page);
  const seen = collectHosts(page);
  await page.goto('/index.html#join');
  await submitWaitlist(page, 'hosts@example.com');
  await expect(page.locator('#pending-block')).toBeVisible();
  await page.waitForLoadState('networkidle');
  assertOnlyAllowedHosts(seen);
});
