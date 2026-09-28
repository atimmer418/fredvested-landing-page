// A returning visitor on index.html (decision 2026-09-26): someone who comes back
// after signing up must see "check your email" or their confirmed status at once,
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

test('a pending signup opens the zone on load and shows "check your email" above nothing else', async ({ page }) => {
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

test('a confirmed founder opens the zone on load and sees the founder note', async ({ page }) => {
  await forceAnalytics(page);
  await seedStorage(page, { waitlist_status: 'WAITLISTFOUNDER' });
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
  await seedStorage(page, { waitlist_status: 'WAITLISTNORMAL' });
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
