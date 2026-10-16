// track(name, ctx, done): the hook for a caller that is about to navigate. `done`
// runs once, when Plausible reports the event sent, after 500 ms if it never does
// (a blocked request must not trap the visitor), or at once when nothing is sent.
const { test, expect } = require('@playwright/test');
const { installMocks, forceAnalytics } = require('./helpers');

async function timeToDone(page, name, flags) {
  return page.evaluate(([n, f]) => new Promise((resolve) => {
    Object.assign(window, f || {});
    const t0 = performance.now();
    // done may run synchronously (gated, repeat), so resolve on the next tick.
    window.FredAnalytics.track(n, {}, () => { const ms = Math.round(performance.now() - t0); setTimeout(() => resolve({ ms }), 0); });
  }), [name, flags]);
}

test('done fires promptly when Plausible calls back', async ({ page }) => {
  await forceAnalytics(page);
  await installMocks(page);
  await page.goto('/about.html');
  const r = await timeToDone(page, 'Email Focused');
  expect(r.ms).toBeLessThan(400);
  expect((await page.evaluate(() => window.__plausible.map((e) => e.name)))).toEqual(['Email Focused']);
});

test('done fires after the 500 ms timeout when the request never completes', async ({ page }) => {
  await forceAnalytics(page);
  await installMocks(page);
  await page.goto('/about.html');
  const r = await timeToDone(page, 'Email Focused', { __plausibleNeverCallBack: true });
  expect(r.ms).toBeGreaterThanOrEqual(450);
  expect(r.ms).toBeLessThan(1500);
});

test('done fires at once when the event is gated off', async ({ page }) => {
  await installMocks(page); // no force flag: analytics disabled on localhost
  await page.goto('/about.html');
  const r = await timeToDone(page, 'Email Focused');
  expect(r.ms).toBeLessThan(100);
  expect(await page.evaluate(() => (window.__plausible || []).length)).toBe(0);
});

test('done fires at once for a repeat of a once-per-page event', async ({ page }) => {
  await forceAnalytics(page);
  await installMocks(page);
  await page.goto('/about.html');
  await timeToDone(page, 'Email Focused');
  const r = await timeToDone(page, 'Email Focused');
  expect(r.ms).toBeLessThan(100);
});
