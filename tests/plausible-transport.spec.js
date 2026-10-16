// The real transport, end to end: with Plausible's actual script (not the recorder),
// a Waitlist Submitted on each form becomes a completed POST to /api/event on our
// own origin, and so do the pageview and Calc Engaged. This is the check that
// would catch an event that never leaves the browser (gate, queue, script,
// endpoint), which the recorder cannot see.
//
// Plausible's script drops everything under WebDriver unless window.__plausible is set,
// so the spec sets that before any script runs; the fixture's localhost ignore is removed
// (see its header) so it runs against the local server like every other spec.
const { test, expect } = require('@playwright/test');
const fs = require('fs');
const path = require('path');
const { installMocks, forceAnalytics, submitWaitlist, revealIfNeeded } = require('./helpers');

const SCRIPT = fs.readFileSync(path.join(__dirname, 'fixtures', 'plausible-script.js'), 'utf8');

/** Routes the real script and records every /api/event POST that completes. */
async function realTransport(page) {
  await page.addInitScript(() => { window.__plausible = true; });
  await forceAnalytics(page);
  const calls = await installMocks(page);
  await page.route('**/js/script.js', (route) => route.fulfill({ status: 200, contentType: 'application/javascript', body: SCRIPT }));
  const sent = [];
  await page.route('**/api/event', async (route) => {
    const body = JSON.parse(route.request().postData() || '{}');
    await route.fulfill({ status: 202, contentType: 'text/plain', body: 'ok' });
    sent.push(body);
  });
  return { calls, sent };
}

const names = (sent) => sent.map((e) => e.n);

test('index.html: pageview, Calc Engaged and Waitlist Submitted each complete as a POST to /api/event', async ({ page }) => {
  const { sent } = await realTransport(page);
  await page.goto('/index.html');
  await expect.poll(() => names(sent)).toContain('pageview');

  await page.fill('#age-range', '35');
  await expect.poll(() => names(sent)).toContain('Calc Engaged');

  await revealIfNeeded(page);
  await submitWaitlist(page, 'transport-home@example.com');
  await expect.poll(() => names(sent), { timeout: 10_000 }).toContain('Waitlist Submitted');

  const submitted = sent.find((e) => e.n === 'Waitlist Submitted');
  expect(submitted.d).toBe('fredvested.com');
  expect(submitted.u).toContain('/index.html');
  expect(submitted.p.source_page).toBe('home');
  expect(submitted.p.revealed).toBe('yes');
  expect(JSON.stringify(submitted)).not.toContain('@');
  // And the page did not move on: the pending block is on screen, same document.
  await expect(page.locator('#pending-block')).toBeVisible();
  expect(page.url()).toContain('/index.html');
});

test('about.html: pageview and Waitlist Submitted each complete as a POST to /api/event', async ({ page }) => {
  const { sent } = await realTransport(page);
  await page.goto('/about.html');
  await expect.poll(() => names(sent)).toContain('pageview');

  await submitWaitlist(page, 'transport-about@example.com');
  await expect.poll(() => names(sent), { timeout: 10_000 }).toContain('Waitlist Submitted');

  const submitted = sent.find((e) => e.n === 'Waitlist Submitted');
  expect(submitted.p.source_page).toBe('about');
  expect(submitted.p.revealed).toBe('no');
  expect(JSON.stringify(submitted)).not.toContain('@');
  await expect(page.locator('#pending-block')).toBeVisible();
  expect(page.url()).toContain('/about.html');
});

test('confirmed.html: Waitlist Confirmed completes as a POST to /api/event', async ({ page }) => {
  const { sent } = await realTransport(page);
  await page.goto('/confirmed.html?status=confirmed&hours=%3C1');
  await expect.poll(() => names(sent), { timeout: 10_000 }).toContain('Waitlist Confirmed');
  expect(sent.find((e) => e.n === 'Waitlist Confirmed').p.hours_to_confirm_band).toBe('<1');
});
