// A returning visitor on index.html (decision 2026-09-26): someone who comes back
// after signing up must see "you got mail" or their confirmed status at once,
// not a calculator that looks like they never signed up. The reveal zone opens
// itself when a state is saved, the calculator stays visible above it, no reveal
// event fires for the automatic open, and a fresh visitor still gets the closed zone.
const { test, expect } = require('@playwright/test');
const { installMocks, forceAnalytics, seedStorage, eventNames, storage } = require('./helpers');

const PENDING = JSON.stringify({ email: 'back@example.com', at: Date.now() });

test('a fresh visitor still sees the calculator with the zone closed', async ({ page }) => {
  await installMocks(page);
  await page.goto('/index.html');
  await expect(page.locator('#age-range')).toBeVisible();
  await expect(page.locator('#reveal-zone')).not.toHaveClass(/open/);
  await expect(page.locator('#reveal-btn')).toHaveAttribute('aria-expanded', 'false');
  await expect(page.locator('#pending-block')).toBeHidden();
  await expect(page.locator('#success-block')).toBeHidden();
  await expect(page.locator('#capture-email')).toBeHidden();
});

test('a pending signup opens the zone on load and shows "you got mail" above nothing else', async ({ page }) => {
  await forceAnalytics(page);
  await seedStorage(page, { waitlist_status: 'pending_confirmation', waitlist_pending: PENDING });
  await installMocks(page);
  await page.goto('/index.html');

  await expect(page.locator('#reveal-zone')).toHaveClass(/open/);
  await expect(page.locator('#reveal-btn')).toHaveAttribute('aria-expanded', 'true');
  await expect(page.locator('#pending-block')).toBeVisible();
  await expect(page.locator('#pending-email')).toHaveText('back@example.com');
  await expect(page.locator('#waitlist-form')).toBeHidden();
  await expect(page.locator('#success-block')).toBeHidden();
  // The calculator is still there, above the status.
  await expect(page.locator('#age-range')).toBeVisible();
  await expect(page.locator('#reveal-btn')).toBeVisible();
  const zoneTop = await page.locator('#reveal-zone').evaluate((el) => el.getBoundingClientRect().top);
  const sliderTop = await page.locator('#age-range').evaluate((el) => el.getBoundingClientRect().top);
  expect(sliderTop).toBeLessThan(zoneTop);
  // The automatic open is not a reveal: no event fires for it.
  expect(await eventNames(page)).toEqual([]);
  expect(await storage(page, 'waitlist_status')).toBe('pending_confirmation');
});

test('a waitlisted visitor opens the zone on load and sees the on-the-list note', async ({ page }) => {
  await forceAnalytics(page);
  await seedStorage(page, { waitlist_status: 'WAITLISTED' });
  await installMocks(page);
  await page.goto('/index.html');

  await expect(page.locator('#reveal-zone')).toHaveClass(/open/);
  await expect(page.locator('#success-block')).toBeVisible();
  await expect(page.locator('#success-note')).toHaveText('You’re in line for a Founder invite.');
  await expect(page.locator('#pending-block')).toBeHidden();
  await expect(page.locator('#waitlist-form')).toBeHidden();
  await expect(page.locator('#age-range')).toBeVisible();
  expect(await eventNames(page)).toEqual([]);
});

test('after the automatic open, the first reveal click is this page load\'s Freedom Date Revealed', async ({ page }) => {
  await forceAnalytics(page);
  await seedStorage(page, { waitlist_status: 'WAITLISTED' });
  await installMocks(page);
  await page.goto('/index.html');
  await expect(page.locator('#reveal-zone')).toHaveClass(/open/);
  expect(await eventNames(page)).toEqual([]);
  await page.click('#reveal-btn');
  // The automatic open was not a reveal; the budget is per page load, so this click is one.
  await expect.poll(() => eventNames(page)).toEqual(['Freedom Date Revealed']);
  await page.click('#reveal-btn');
  await expect.poll(() => eventNames(page)).toEqual(['Freedom Date Revealed', 'Recalculated']);
});

test('after the automatic open, moving a slider updates the shown date live, without a reveal click', async ({ page }) => {
  await forceAnalytics(page);
  await seedStorage(page, { waitlist_status: 'WAITLISTED' });
  await installMocks(page);
  await page.goto('/index.html');
  await expect(page.locator('#reveal-zone')).toHaveClass(/open/);
  const before = await page.locator('#result-chip').textContent();
  // Same path a real drag takes: set the range's value and fire `input` on it.
  await page.locator('#invest-range').evaluate((el) => {
    el.value = el.max;
    el.dispatchEvent(new Event('input', { bubbles: true }));
  });
  await expect(page.locator('#result-chip')).not.toHaveText(before);
  // Still not a reveal: the first slider touch is Calc Engaged as always, nothing more.
  expect(await eventNames(page)).toEqual(['Calc Engaged']);
});

// "Recalculate anytime" on about.html is a plain link back here. The date the about hero
// shows comes from storage, written at signup; a recalculation by someone who already
// joined must overwrite it, or the about page keeps showing the signup-time date.
test('a joined visitor who recalculates overwrites the saved freedom date the about page shows', async ({ page }) => {
  // Seeded once, by hand: seedStorage() re-runs on every navigation and would re-plant the
  // old date on the about.html hop below.
  await installMocks(page);
  await page.goto('/index.html');
  await page.evaluate(() => {
    localStorage.setItem('waitlist_status', 'WAITLISTED');
    localStorage.setItem('waitlist_freedom', JSON.stringify({ age: 45, monthYear: 'March 2040' }));
  });
  await page.goto('/index.html');
  await expect(page.locator('#reveal-zone')).toHaveClass(/open/);
  // The automatic open renders the default sliders, not the visitor's numbers: it must not clobber the saved date.
  expect(JSON.parse(await storage(page, 'waitlist_freedom'))).toEqual({ age: 45, monthYear: 'March 2040' });

  await page.locator('#invest-range').evaluate((el) => {
    el.value = el.max;
    el.dispatchEvent(new Event('input', { bubbles: true }));
  });
  const age = Number(await page.locator('#result-age').textContent());
  const chip = await page.locator('#result-chip').textContent();
  const saved = JSON.parse(await storage(page, 'waitlist_freedom'));
  expect(saved.age).toBe(age);
  expect(chip).toContain(saved.monthYear);
  expect(saved).not.toEqual({ age: 45, monthYear: 'March 2040' });

  await page.goto('/about.html');
  await expect(page.locator('#hero-freedom-value')).toHaveText('Free at ' + age + ' · ' + saved.monthYear);
});

test('a visitor who has not joined never writes a freedom date by recalculating', async ({ page }) => {
  await installMocks(page);
  await page.goto('/index.html');
  await page.click('#reveal-btn');
  await page.locator('#invest-range').evaluate((el) => {
    el.value = el.max;
    el.dispatchEvent(new Event('input', { bubbles: true }));
  });
  await expect(page.locator('#result-age')).not.toHaveText('');
  expect(await storage(page, 'waitlist_freedom')).toBeNull();
});
