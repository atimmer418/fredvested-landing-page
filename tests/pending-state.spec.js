// Proves the double opt-in pending state on index.html and about.html. A signup
// answered with requiresConfirmation:true hides the form and shows "One more step:
// check your email." with the address; the state survives a reload (7 days at most);
// "resend the email" posts exactly the address and shows the neutral sentence;
// "Use a different one" brings back a working form and forgets the state; a
// storage-blocked browser still gets both from the in-memory copy; and a signup
// that needs no confirmation (or an address already on the list) shows the success
// block and never the pending block. On about.html the nav CTA reads
// "Check your email" while pending and the price card CTA is left alone.
const { test, expect } = require('@playwright/test');
const {
  FRESH_SIGNUP, installMocks, seedStorage, blockStorage, submitWaitlist, revealIfNeeded, storage, events,
} = require('./helpers');

const DAY_MS = 24 * 60 * 60 * 1000;
const RESEND_SENTENCE = 'If that address is on our waitlist, a new confirmation link is on its way. Check your inbox.';
const PENDING_HEADLINE = 'One more step: check your email.';
const RESEND_LINE = 'Didn’t get it? Check your spam folder, or resend the email.';
const RESET_LINE = 'Wrong address? Use a different one.';
const NAV_DEFAULT = 'Get my Freedom Date';
const NAV_PENDING = 'Check your email';

const PAGES = [
  // On index.html the capture block sits inside the reveal zone (visibility:hidden
  // until "Reveal my Freedom Date"), so a saved state is only on screen after the
  // reveal click. about.html shows the form directly and has the nav CTA.
  { name: 'index.html', path: '/index.html', collapsed: true, hasNav: false },
  { name: 'about.html', path: '/about.html', collapsed: false, hasNav: true },
];

function sentLine(email) {
  return `We sent a confirmation link to ${email}. Click it to lock in your spot. The link expires in 7 days.`;
}

/** Opens the reveal zone on index.html without requiring the form to be visible (a no-op on about.html). */
async function openCaptureArea(page) {
  const reveal = page.locator('#reveal-btn');
  if (await reveal.count()) await reveal.click();
}

/** The pending block with its exact copy and the address, and no form or success block beside it. */
async function expectPendingBlock(page, email) {
  const block = page.locator('#pending-block');
  await expect(block).toBeVisible({ timeout: 10_000 });
  await expect(block.locator('p').first()).toHaveText(PENDING_HEADLINE);
  await expect(block.locator('p', { hasText: 'We sent a confirmation link' })).toHaveText(sentLine(email));
  await expect(page.locator('#pending-email')).toHaveText(email);
  await expect(block.locator('p', { hasText: 'get it?' })).toHaveText(RESEND_LINE);
  await expect(block.locator('p', { hasText: 'Wrong address?' })).toHaveText(RESET_LINE);
  await expect(page.locator('#pending-resend')).toHaveText('resend the email');
  await expect(page.locator('#pending-reset')).toHaveText('Use a different one');
  await expect(page.locator('#waitlist-form')).toBeHidden();
  await expect(page.locator('#capture-email')).toBeHidden();
  await expect(page.locator('#success-block')).toBeHidden();
}

/** The form back on screen and usable. */
async function expectWorkingForm(page) {
  await expect(page.locator('#waitlist-form')).toBeVisible();
  await expect(page.locator('#capture-email')).toBeVisible();
  await expect(page.locator('#capture-email')).toBeEnabled();
  await expect(page.locator('#join-waitlist-btn')).toBeEnabled();
  await expect(page.locator('#pending-block')).toBeHidden();
}

async function pendingRecord(page) {
  const raw = await storage(page, 'waitlist_pending');
  return raw === null ? null : JSON.parse(raw);
}

function pendingEntry(email, ageMs) {
  return JSON.stringify({ email, at: Date.now() - ageMs });
}

for (const p of PAGES) {
  test.describe(p.name, () => {
    test('a fresh signup hides the form and shows the pending block with the address', async ({ page }) => {
      const calls = await installMocks(page);
      await page.goto(p.path);
      const email = 'pending@example.com';
      const before = Date.now();
      await submitWaitlist(page, email);

      await expectPendingBlock(page, email);
      expect(calls.signup).toHaveLength(1);
      expect(calls.signup[0].email).toBe(email);

      expect(await storage(page, 'waitlist_status')).toBe('pending_confirmation');
      const saved = await pendingRecord(page);
      expect(saved.email).toBe(email);
      expect(typeof saved.at).toBe('number');
      expect(saved.at).toBeGreaterThanOrEqual(before);
      expect(saved.at).toBeLessThanOrEqual(Date.now());

      // Analytics is gated off on localhost (no forceAnalytics here): the pending
      // path must not have produced a single plausible() call.
      expect(await events(page)).toEqual([]);
    });

    test('a reload shows the pending block again with the address and no form', async ({ page }) => {
      const calls = await installMocks(page);
      await page.goto(p.path);
      const email = 'reload@example.com';
      await submitWaitlist(page, email);
      await expectPendingBlock(page, email);

      await page.reload();
      await expect(page.locator('#waitlist-form')).toBeHidden();
      if (p.collapsed) {
        // The saved state is restored on load but stays inside the collapsed reveal zone.
        await expect(page.locator('#pending-block')).toBeHidden();
        await openCaptureArea(page);
      }
      await expectPendingBlock(page, email);
      if (p.hasNav) await expect(page.locator('#nav-cta')).toHaveText(NAV_PENDING);
      // The reload made no second signup call.
      expect(calls.signup).toHaveLength(1);
      expect(await storage(page, 'waitlist_status')).toBe('pending_confirmation');
    });

    test('"resend the email" posts exactly the address and shows the neutral sentence', async ({ page }) => {
      const calls = await installMocks(page);
      await page.goto(p.path);
      const email = 'resend@example.com';
      await submitWaitlist(page, email);
      await expectPendingBlock(page, email);
      expect(calls.resend).toEqual([]);

      await page.locator('#pending-resend').click();
      await expect(page.locator('#pending-msg')).toHaveText(RESEND_SENTENCE);
      expect(calls.resend).toEqual([{ email }]);
      // The button is usable again once the request has completed.
      await expect(page.locator('#pending-resend')).toBeEnabled();
      // Still pending: the resend changes nothing about the state.
      await expect(page.locator('#pending-block')).toBeVisible();
      await expect(page.locator('#waitlist-form')).toBeHidden();
      expect(await storage(page, 'waitlist_status')).toBe('pending_confirmation');
    });

    test('"Use a different one" restores a working form and clears both storage keys', async ({ page }) => {
      const calls = await installMocks(page);
      await page.goto(p.path);
      const email = 'reset@example.com';
      await submitWaitlist(page, email);
      await expectPendingBlock(page, email);

      await page.locator('#pending-reset').click();
      await expectWorkingForm(page);
      await expect(page.locator('#success-block')).toBeHidden();
      expect(await storage(page, 'waitlist_status')).toBeNull();
      expect(await storage(page, 'waitlist_pending')).toBeNull();
      if (p.hasNav) await expect(page.locator('#nav-cta')).toHaveText(NAV_DEFAULT);
      // The reset is local: no resend and no second signup.
      expect(calls.resend).toEqual([]);
      expect(calls.signup).toHaveLength(1);

      // The cleared state does not come back on the next load.
      await page.reload();
      await expect(page.locator('#pending-block')).toBeHidden();
      await revealIfNeeded(page);
      await expectWorkingForm(page);
    });

    test('with storage blocked a signup still shows the pending block and resend uses the in-memory address', async ({ page }) => {
      await blockStorage(page);
      const calls = await installMocks(page);
      await page.goto(p.path);
      const email = 'nostorage@example.com';
      await submitWaitlist(page, email);

      await expectPendingBlock(page, email);
      // Nothing could be written, and the flow did not break on the failed writes.
      expect(await storage(page, 'waitlist_status')).toBeNull();
      expect(await storage(page, 'waitlist_pending')).toBeNull();

      await page.locator('#pending-resend').click();
      await expect(page.locator('#pending-msg')).toHaveText(RESEND_SENTENCE);
      expect(calls.resend).toEqual([{ email }]);
      await expect(page.locator('#pending-block')).toBeVisible();
      await expect(page.locator('#waitlist-form')).toBeHidden();
    });

    test('a saved pending state younger than 7 days shows the pending block on load', async ({ page }) => {
      const email = 'recent@example.com';
      await seedStorage(page, {
        waitlist_status: 'pending_confirmation',
        waitlist_pending: pendingEntry(email, 6 * DAY_MS),
      });
      const calls = await installMocks(page);
      await page.goto(p.path);

      await expect(page.locator('#waitlist-form')).toBeHidden();
      await openCaptureArea(page);
      await expectPendingBlock(page, email);
      if (p.hasNav) await expect(page.locator('#nav-cta')).toHaveText(NAV_PENDING);
      expect(await storage(page, 'waitlist_status')).toBe('pending_confirmation');
      expect((await pendingRecord(page)).email).toBe(email);
      expect(calls.signup).toEqual([]);
    });

    test('a saved pending state older than 7 days shows the form and clears the saved status', async ({ page }) => {
      await seedStorage(page, {
        waitlist_status: 'pending_confirmation',
        waitlist_pending: pendingEntry('stale@example.com', 8 * DAY_MS),
      });
      const calls = await installMocks(page);
      await page.goto(p.path);

      // The expired record reads as absent: cleared on load, together with the status.
      await expect(page.locator('#pending-block')).toBeHidden();
      await expect(page.locator('#success-block')).toBeHidden();
      expect(await storage(page, 'waitlist_status')).toBeNull();
      expect(await storage(page, 'waitlist_pending')).toBeNull();
      if (p.hasNav) await expect(page.locator('#nav-cta')).toHaveText(NAV_DEFAULT);

      await revealIfNeeded(page);
      await expectWorkingForm(page);
      expect(calls.signup).toEqual([]);
    });

    test('a signup that needs no confirmation shows the success block, not the pending block', async ({ page }) => {
      const calls = await installMocks(page, {
        signup: { ...FRESH_SIGNUP, status: 'WAITLISTFOUNDER', requiresConfirmation: false },
      });
      await page.goto(p.path);
      await submitWaitlist(page, 'founder@example.com');

      await expect(page.locator('#success-block')).toBeVisible({ timeout: 10_000 });
      await expect(page.locator('#success-note')).toHaveText('You’re in line for a Founder invite.');
      await expect(page.locator('#pending-block')).toBeHidden();
      await expect(page.locator('#waitlist-form')).toBeHidden();
      expect(calls.signup).toHaveLength(1);
      expect(await storage(page, 'waitlist_status')).toBe('WAITLISTFOUNDER');
      expect(await storage(page, 'waitlist_pending')).toBeNull();
      if (p.hasNav) {
        // A completed Founder signup is the one case that changes the price card CTA.
        await expect(page.locator('#founder-cta')).toContainText('Your spot in line is reserved');
      }
    });

    test('an address already on the list shows "already on the list", not the pending block', async ({ page }) => {
      // Mirrors WaitlistController for a confirmed duplicate: status already_joined,
      // the row's real status alongside, and no requiresConfirmation key at all.
      const { requiresConfirmation, ...stats } = FRESH_SIGNUP;
      const calls = await installMocks(page, {
        signup: { ...stats, status: 'already_joined', realStatus: 'WAITLISTNORMAL' },
      });
      await page.goto(p.path);
      await submitWaitlist(page, 'again@example.com');

      await expect(page.locator('#success-block')).toBeVisible({ timeout: 10_000 });
      await expect(page.locator('#success-note')).toHaveText('You’re already on the list.');
      await expect(page.locator('#pending-block')).toBeHidden();
      await expect(page.locator('#waitlist-form')).toBeHidden();
      expect(calls.signup).toHaveLength(1);
      // The real status is what a later visit shows, never "already_joined" itself.
      expect(await storage(page, 'waitlist_status')).toBe('WAITLISTNORMAL');
      expect(await storage(page, 'waitlist_pending')).toBeNull();
    });
  });
}

test.describe('about.html nav and price card', () => {
  test('while pending the nav CTA reads "Check your email" and the price card CTA is untouched', async ({ page }) => {
    await installMocks(page);
    await page.goto('/about.html');
    const nav = page.locator('#nav-cta');
    const founderCta = page.locator('#founder-cta');
    await expect(nav).toHaveText(NAV_DEFAULT);
    const initialText = (await founderCta.innerText()).trim();
    const initialClass = await founderCta.getAttribute('class');
    expect(initialText).toBe('Reserve my Founder spot');

    await submitWaitlist(page, 'navstate@example.com');
    await expectPendingBlock(page, 'navstate@example.com');
    await expect(nav).toHaveText(NAV_PENDING);
    await expect(nav).toHaveAttribute('href', '#join');
    // Nothing is reserved until the emailed link is clicked, so the card keeps its call to action.
    await expect(founderCta).toHaveText(initialText);
    await expect(founderCta).toHaveAttribute('class', initialClass);
    await expect(founderCta).toHaveAttribute('href', '#join');

    await page.locator('#pending-reset').click();
    await expect(nav).toHaveText(NAV_DEFAULT);
    await expect(founderCta).toHaveText(initialText);
  });
});
