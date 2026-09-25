// confirmed.html is the landing target of every emailed link (confirm, expired,
// invalid, unsubscribe). This spec proves, for each status in the query string,
// which state block shows, whether the resend form is offered, and, for the
// backend's confirmation redirect, that exactly one Waitlist Confirmed event
// fires with only the banded hours, that the browser is marked as a member
// (waitlist_status written, waitlist_pending cleared), and that hours and tier
// are stripped from the address bar so a reload, a shared link or a retyped URL
// can neither fire the event again nor mark another browser. It also proves the
// resend form gives one identical answer for any completed request, and that a
// founder confirmation turns index.html's pending block into the Founder success
// state. The API is mocked in the browser; no backend runs.
const { test, expect } = require('@playwright/test');
const {
  installMocks, forceAnalytics, seedStorage, events, eventNames, submitWaitlist, storage,
} = require('./helpers');

const FOUNDER_URL = '/confirmed.html?status=confirmed&hours=%3C1&tier=founder';
const STATES = ['confirmed', 'expired', 'invalid', 'unsubscribed', 'default'];
const CONFIRM_BANDS = ['<1', '1-6', '6-24', '24-72', '72+'];
const RESEND_SENTENCE = 'If that address is on our waitlist, a new confirmation link is on its way. Check your inbox.';
const OFFLINE_SENTENCE = 'Could not reach FRED. Please check your connection and try again.';

function pendingJson(email) {
  return JSON.stringify({ email, at: Date.now() });
}

/** Exactly one state block is shown; the other four stay hidden. */
async function expectOnlyState(page, shown) {
  for (const state of STATES) {
    const block = page.locator(`#s-${state}`);
    if (state === shown) await expect(block).toBeVisible();
    else await expect(block).toBeHidden();
  }
}

async function search(page) {
  return page.evaluate(() => location.search);
}

// --- the backend's confirmation redirect -----------------------------------

test('a founder confirmation shows the confirmed state, fires one Waitlist Confirmed and marks the browser', async ({ page }) => {
  const seeded = pendingJson('pending@example.com');
  await forceAnalytics(page);
  await seedStorage(page, { waitlist_pending: seeded });
  const calls = await installMocks(page);

  await page.goto(FOUNDER_URL);

  await expectOnlyState(page, 'confirmed');
  await expect(page.locator('#s-confirmed h1')).toHaveText('You’re confirmed.');
  await expect(page.locator('#c-confirmed')).toBeVisible();
  await expect(page.locator('#c-resend')).toBeHidden();
  await expect(page.locator('#resend-form')).toBeHidden();

  const fired = await events(page);
  expect(fired).toHaveLength(1);
  expect(fired[0].name).toBe('Waitlist Confirmed');
  // Only the band goes to Plausible: no tier, no address, no raw hours.
  expect(fired[0].props).toEqual({ hours_to_confirm_band: '<1' });
  // The strip runs before Plausible's deferred script replays the queued call,
  // so even the first load's event URL carries only the status (analytics-qa B6).
  expect(fired[0].url).toBe('http://localhost:5500/confirmed.html?status=confirmed');

  expect(await storage(page, 'waitlist_status')).toBe('WAITLISTFOUNDER');
  expect(await storage(page, 'waitlist_pending')).toBeNull();
  expect(await search(page)).toBe('?status=confirmed');

  // The page reads nothing from the API on load.
  expect(calls.stats).toBe(0);
  expect(calls.signup).toEqual([]);
  expect(calls.resend).toEqual([]);
});

for (const band of CONFIRM_BANDS) {
  test(`hours=${band} is forwarded exactly as hours_to_confirm_band`, async ({ page }) => {
    await forceAnalytics(page);
    await installMocks(page);
    await page.goto(`/confirmed.html?status=confirmed&hours=${encodeURIComponent(band)}&tier=normal`);
    await expectOnlyState(page, 'confirmed');
    const fired = await events(page);
    expect(fired).toHaveLength(1);
    expect(fired[0].name).toBe('Waitlist Confirmed');
    expect(fired[0].props).toEqual({ hours_to_confirm_band: band });
    expect(await search(page)).toBe('?status=confirmed');
  });
}

test('tier=normal stores WAITLISTNORMAL', async ({ page }) => {
  await forceAnalytics(page);
  await installMocks(page);
  await page.goto('/confirmed.html?status=confirmed&hours=1-6&tier=normal');
  await expectOnlyState(page, 'confirmed');
  expect(await storage(page, 'waitlist_status')).toBe('WAITLISTNORMAL');
  expect(await eventNames(page)).toEqual(['Waitlist Confirmed']);
  expect(await search(page)).toBe('?status=confirmed');
});

test('a redirect without tier (an already invited, claimed or declined row) stores "confirmed"', async ({ page }) => {
  await forceAnalytics(page);
  await installMocks(page);
  await page.goto('/confirmed.html?status=confirmed&hours=6-24');
  await expectOnlyState(page, 'confirmed');
  expect(await storage(page, 'waitlist_status')).toBe('confirmed');
  expect(await eventNames(page)).toEqual(['Waitlist Confirmed']);
  expect(await search(page)).toBe('?status=confirmed');
});

test('a reload of the stripped URL keeps the confirmed state and fires no second event', async ({ page }) => {
  await forceAnalytics(page);
  await installMocks(page);
  await page.goto(FOUNDER_URL);
  expect(await eventNames(page)).toEqual(['Waitlist Confirmed']);
  expect(await search(page)).toBe('?status=confirmed');

  // The address bar now holds only ?status=confirmed; this is what a reload or an
  // "open in browser" from a mail app loads.
  await page.reload();

  await expectOnlyState(page, 'confirmed');
  expect(await events(page)).toEqual([]);
  expect(await storage(page, 'waitlist_status')).toBe('WAITLISTFOUNDER');
  expect(await search(page)).toBe('?status=confirmed');
});

test('a shared or retyped status=confirmed link without hours shows the copy but stores nothing and fires nothing', async ({ page }) => {
  const seeded = pendingJson('pending@example.com');
  await forceAnalytics(page);
  await seedStorage(page, { waitlist_pending: seeded });
  await installMocks(page);

  await page.goto('/confirmed.html?status=confirmed');

  await expectOnlyState(page, 'confirmed');
  await expect(page.locator('#c-confirmed')).toBeVisible();
  expect(await events(page)).toEqual([]);
  expect(await storage(page, 'waitlist_status')).toBeNull();
  expect(await storage(page, 'waitlist_pending')).toBe(seeded);
  expect(await search(page)).toBe('?status=confirmed');
});

test('tier without hours is ignored: nothing stored, nothing fired, URL untouched', async ({ page }) => {
  const seeded = pendingJson('pending@example.com');
  await forceAnalytics(page);
  await seedStorage(page, { waitlist_pending: seeded });
  await installMocks(page);

  await page.goto('/confirmed.html?status=confirmed&tier=founder');

  await expectOnlyState(page, 'confirmed');
  expect(await events(page)).toEqual([]);
  expect(await storage(page, 'waitlist_status')).toBeNull();
  expect(await storage(page, 'waitlist_pending')).toBe(seeded);
  expect(await search(page)).toBe('?status=confirmed&tier=founder');
});

test('an unknown band (hours=99) shows the confirmed state and fires nothing', async ({ page }) => {
  await forceAnalytics(page);
  await installMocks(page);
  await page.goto('/confirmed.html?status=confirmed&hours=99&tier=founder');
  await expectOnlyState(page, 'confirmed');
  await expect(page.locator('#c-confirmed')).toBeVisible();
  expect(await events(page)).toEqual([]);
  // The redirect still carried hours, so the browser is marked and the URL stripped;
  // only the event is dropped (analytics.js CONFIRM_BANDS).
  expect(await storage(page, 'waitlist_status')).toBe('WAITLISTFOUNDER');
  expect(await search(page)).toBe('?status=confirmed');
});

test('without the escape hatch the confirmed page fires nothing but still marks the browser', async ({ page }) => {
  await installMocks(page);
  await page.goto(FOUNDER_URL);
  await expectOnlyState(page, 'confirmed');
  expect(await events(page)).toEqual([]);
  // The hostname gate switches Plausible's own pageview off too (analytics-qa B3).
  expect(await storage(page, 'plausible_ignore')).toBe('true');
  expect(await storage(page, 'waitlist_status')).toBe('WAITLISTFOUNDER');
  expect(await search(page)).toBe('?status=confirmed');
});

// --- expired / invalid: the resend form ------------------------------------

test('status=expired shows the expired state with the resend form', async ({ page }) => {
  await forceAnalytics(page);
  await installMocks(page);
  await page.goto('/confirmed.html?status=expired');
  await expectOnlyState(page, 'expired');
  await expect(page.locator('#s-expired h1')).toHaveText('That link has expired.');
  await expect(page.locator('#c-resend')).toBeVisible();
  await expect(page.locator('#resend-form')).toBeVisible();
  await expect(page.locator('#resend-email')).toBeVisible();
  await expect(page.locator('#resend-btn')).toBeEnabled();
  await expect(page.locator('#c-confirmed')).toBeHidden();
  expect(await events(page)).toEqual([]);
  expect(await storage(page, 'waitlist_status')).toBeNull();
});

test('status=invalid shows the invalid state with the resend form', async ({ page }) => {
  await forceAnalytics(page);
  await installMocks(page);
  await page.goto('/confirmed.html?status=invalid');
  await expectOnlyState(page, 'invalid');
  await expect(page.locator('#s-invalid h1')).toHaveText('That link isn’t valid.');
  await expect(page.locator('#c-resend')).toBeVisible();
  await expect(page.locator('#resend-form')).toBeVisible();
  await expect(page.locator('#c-confirmed')).toBeHidden();
  expect(await events(page)).toEqual([]);
  expect(await storage(page, 'waitlist_status')).toBeNull();
});

test('the resend form calls the endpoint and answers two different addresses with the same sentence', async ({ page }) => {
  const calls = await installMocks(page);
  await page.goto('/confirmed.html?status=expired');

  const isResend = (r) => r.url().includes('/api/waitlist/resend-confirmation') && r.request().method() === 'POST';

  // Mixed case and padding: the page normalises the address before sending it.
  await page.fill('#resend-email', '  First@Example.COM ');
  let done = page.waitForResponse(isResend);
  await page.click('#resend-btn');
  await done;
  await expect(page.locator('#resend-msg')).toContainText(RESEND_SENTENCE);
  await expect(page.locator('#resend-btn')).toBeEnabled();
  const firstAnswer = await page.locator('#resend-msg').innerHTML();

  await page.fill('#resend-email', 'second@example.com');
  done = page.waitForResponse(isResend);
  await page.click('#resend-btn');
  await done;
  await expect(page.locator('#resend-msg')).toContainText(RESEND_SENTENCE);
  await expect(page.locator('#resend-btn')).toBeEnabled();
  const secondAnswer = await page.locator('#resend-msg').innerHTML();

  expect(calls.resend).toEqual([{ email: 'first@example.com' }, { email: 'second@example.com' }]);
  expect(secondAnswer).toBe(firstAnswer);
  expect(calls.signup).toEqual([]);
});

test('the resend form on the invalid state calls the endpoint and shows the same sentence', async ({ page }) => {
  const calls = await installMocks(page);
  await page.goto('/confirmed.html?status=invalid');
  const done = page.waitForResponse((r) => r.url().includes('/api/waitlist/resend-confirmation'));
  await page.fill('#resend-email', 'invalid-state@example.com');
  await page.click('#resend-btn');
  await done;
  await expect(page.locator('#resend-msg')).toContainText(RESEND_SENTENCE);
  expect(calls.resend).toEqual([{ email: 'invalid-state@example.com' }]);
});

test('a rate-limited resend (429) still shows the same sentence: the page is not an oracle', async ({ page }) => {
  const calls = await installMocks(page);
  // Registered after installMocks, so this route wins for the resend endpoint.
  await page.route('**/api/waitlist/resend-confirmation', (route) => {
    calls.resend.push(JSON.parse(route.request().postData() || '{}'));
    route.fulfill({
      status: 429,
      contentType: 'application/json',
      body: JSON.stringify({ code: 'rate_limited', message: 'Too many requests. Please try again later.' }),
    });
  });
  await page.goto('/confirmed.html?status=expired');
  const done = page.waitForResponse((r) => r.url().includes('/api/waitlist/resend-confirmation'));
  await page.fill('#resend-email', 'limited@example.com');
  await page.click('#resend-btn');
  await done;
  await expect(page.locator('#resend-msg')).toContainText(RESEND_SENTENCE);
  await expect(page.locator('#resend-msg')).not.toContainText('Too many requests');
  await expect(page.locator('#resend-btn')).toBeEnabled();
  expect(calls.resend).toEqual([{ email: 'limited@example.com' }]);
});

test('a resend that never reaches the API shows the connection message and re-enables the button', async ({ page }) => {
  await installMocks(page);
  await page.route('**/api/waitlist/resend-confirmation', (route) => route.abort('failed'));
  await page.goto('/confirmed.html?status=expired');
  await page.fill('#resend-email', 'offline@example.com');
  await page.click('#resend-btn');
  await expect(page.locator('#resend-msg')).toContainText(OFFLINE_SENTENCE);
  await expect(page.locator('#resend-msg')).not.toContainText(RESEND_SENTENCE);
  await expect(page.locator('#resend-btn')).toBeEnabled();
});

test('the resend form rejects an invalid address without calling the endpoint', async ({ page }) => {
  const calls = await installMocks(page);
  await page.goto('/confirmed.html?status=expired');
  await page.fill('#resend-email', 'not-an-email');
  await page.click('#resend-btn');
  await expect(page.locator('#resend-msg')).toContainText('Please enter a valid email address.');
  await expect(page.locator('#resend-btn')).toBeEnabled();
  expect(calls.resend).toEqual([]);
});

test('the resend form rejects an empty address without calling the endpoint', async ({ page }) => {
  const calls = await installMocks(page);
  await page.goto('/confirmed.html?status=invalid');
  await page.click('#resend-btn');
  await expect(page.locator('#resend-msg')).toContainText('Please enter a valid email address.');
  expect(calls.resend).toEqual([]);
});

// --- unsubscribed / default ------------------------------------------------

test('status=unsubscribed shows the unsubscribed state with no resend form and fires nothing', async ({ page }) => {
  await forceAnalytics(page);
  await installMocks(page);
  await page.goto('/confirmed.html?status=unsubscribed');
  await expectOnlyState(page, 'unsubscribed');
  await expect(page.locator('#s-unsubscribed h1')).toHaveText('You’ve been unsubscribed.');
  await expect(page.locator('#c-resend')).toBeHidden();
  await expect(page.locator('#resend-form')).toBeHidden();
  await expect(page.locator('#c-confirmed')).toBeHidden();
  expect(await events(page)).toEqual([]);
  expect(await storage(page, 'waitlist_status')).toBeNull();
});

test('no status shows the default state with the resend form', async ({ page }) => {
  await forceAnalytics(page);
  await installMocks(page);
  await page.goto('/confirmed.html');
  await expectOnlyState(page, 'default');
  await expect(page.locator('#s-default h1')).toHaveText('Confirm your email.');
  await expect(page.locator('#c-resend')).toBeVisible();
  await expect(page.locator('#resend-form')).toBeVisible();
  await expect(page.locator('#c-confirmed')).toBeHidden();
  expect(await events(page)).toEqual([]);
  expect(await storage(page, 'waitlist_status')).toBeNull();
});

test('an unknown status shows the default state', async ({ page }) => {
  await forceAnalytics(page);
  await installMocks(page);
  await page.goto('/confirmed.html?status=banana&hours=%3C1&tier=founder');
  await expectOnlyState(page, 'default');
  await expect(page.locator('#resend-form')).toBeVisible();
  // hours and tier mean nothing outside status=confirmed.
  expect(await events(page)).toEqual([]);
  expect(await storage(page, 'waitlist_status')).toBeNull();
});

// --- the handoff back to index.html ------------------------------------------

test('a founder confirmation turns the pending block on index.html into the Founder success state', async ({ page }) => {
  await installMocks(page);

  // 1. A fresh signup on index.html: double opt-in leaves the page in the pending state.
  await page.goto('/index.html');
  await submitWaitlist(page, 'founder@example.com');
  await expect(page.locator('#pending-block')).toBeVisible();
  await expect(page.locator('#pending-email')).toHaveText('founder@example.com');
  await expect(page.locator('#success-block')).toBeHidden();
  expect(await storage(page, 'waitlist_status')).toBe('pending_confirmation');
  expect(JSON.parse(await storage(page, 'waitlist_pending')).email).toBe('founder@example.com');

  // 2. The emailed link lands on the backend's redirect.
  await page.goto(FOUNDER_URL);
  await expectOnlyState(page, 'confirmed');
  expect(await storage(page, 'waitlist_status')).toBe('WAITLISTFOUNDER');
  expect(await storage(page, 'waitlist_pending')).toBeNull();

  // 3. Back on index.html the capture block (inside the reveal zone) shows the decided
  //    status instead of the form or the pending block.
  await page.goto('/index.html');
  await page.click('#reveal-btn');
  await expect(page.locator('#success-block')).toBeVisible();
  await expect(page.locator('#success-note')).toHaveText('You’re in line for a Founder invite.');
  await expect(page.locator('#waitlist-form')).toBeHidden();
  await expect(page.locator('#capture-email')).toBeHidden();
  await expect(page.locator('#pending-block')).toBeHidden();
  await expect(page.locator('#capture-cta-note')).toBeHidden();
  // Let Turnstile finish loading before teardown: a request still in flight when the
  // context closes trips the retain-on-failure trace writer (same quiesce as the
  // third-party-hosts signup test).
  await page.waitForLoadState('networkidle');
});
