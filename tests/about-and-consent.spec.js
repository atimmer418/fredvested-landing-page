// Proves three things about the pages that carry a waitlist form, with the API
// mocked in the browser (tests/helpers.js) and no backend running:
//   1. The post-signup status wording. about.html and index.html read
//      localStorage.waitlist_status on load and, for a saved value, replace the
//      form with the success block. about.html also rewrites #nav-cta and the
//      price-card #founder-cta to "Your spot in line is reserved" for every
//      joined status: there is no tier at signup, Founder Access invites go out
//      in the order people joined (founder decision, 2026-10-10).
//   2. The consent sentence. Both forms render CONSENT_SENTENCE_HTML from
//      assets/waitlist.js into #consent-note, so counsel's final wording lands in
//      one place: the two texts must be identical, ask for consent, link to the
//      privacy policy and carry no em dash.
//   3. The live-only proof numbers. The stats endpoint is fetched on load and
//      about.html's count tile and index.html's list-size line render only at
//      or above STAT_TILE_MIN_N (25); below it nothing renders at all.
const { test, expect } = require('@playwright/test');
const { STATS, installMocks, seedStorage, revealIfNeeded } = require('./helpers');

// The pages spell these with the typographic apostrophe (U+2019), exactly as
// STATUS_NOTES and showSuccess() do in about.html and index.html.
const NAV_CTA_DEFAULT = 'Get my Freedom Date';
const NAV_CTA_JOINED = 'You’re in ✓';
const FOUNDER_CTA_DEFAULT = 'Reserve my Founder spot';
const FOUNDER_CTA_RESERVED = 'Your spot in line is reserved';
const NOTE_WAITLISTED = 'You’re in line for a Founder invite.';
const NOTE_ALREADY_JOINED = 'You’re already on the list.';
const NOTE_CONFIRMED = 'Your spot on the FRED waitlist is secured. We’ll email you when your wave opens.';

const STAT_TILE_MIN_N = 25;
// U+2014, spelled as a code point so this file itself never carries the character.
const EM_DASH = String.fromCharCode(0x2014);

// --- 1. Status wording ------------------------------------------------------

test('about.html with no saved status shows the form, the default nav CTA and the reserve founder CTA', async ({ page }) => {
  await installMocks(page);
  await page.goto('/about.html');
  await expect(page.locator('#waitlist-form')).toBeVisible();
  await expect(page.locator('#success-block')).toBeHidden();
  await expect(page.locator('#pending-block')).toBeHidden();
  await expect(page.locator('#nav-cta')).toHaveText(NAV_CTA_DEFAULT);
  await expect(page.locator('#founder-cta')).toHaveText(FOUNDER_CTA_DEFAULT);
  await expect(page.locator('#founder-cta')).toHaveAttribute('href', '#join');
});

// Every joined status gets the same reserved-place wording on the price card.
for (const { status, note } of [
  { status: 'WAITLISTED', note: NOTE_WAITLISTED },
  { status: 'confirmed', note: NOTE_CONFIRMED },
  { status: 'already_joined', note: NOTE_ALREADY_JOINED },
]) {
  test(`about.html with a saved ${status} status notes "${note}", joins the nav CTA and reserves the founder CTA`, async ({ page }) => {
    await seedStorage(page, { waitlist_status: status });
    await installMocks(page);
    await page.goto('/about.html');
    await expect(page.locator('#success-block')).toBeVisible();
    await expect(page.locator('#success-note')).toHaveText(note);
    await expect(page.locator('#waitlist-form')).toBeHidden();
    await expect(page.locator('#pending-block')).toBeHidden();
    await expect(page.locator('#nav-cta')).toHaveText(NAV_CTA_JOINED);
    await expect(page.locator('#nav-cta')).toHaveAttribute('href', '#join');
    const founderCta = page.locator('#founder-cta');
    await expect(founderCta).toContainText(FOUNDER_CTA_RESERVED);
    await expect(founderCta).not.toContainText('priority', { ignoreCase: true });
    await expect(founderCta).toHaveAttribute('href', '#join');
  });
}

test('about.html with a saved status and a saved freedom date shows the date in the hero instead of the calculator CTA', async ({ page }) => {
  await seedStorage(page, {
    waitlist_status: 'WAITLISTED',
    waitlist_freedom: JSON.stringify({ age: 45, monthYear: 'March 2040' }),
  });
  await installMocks(page);
  await page.goto('/about.html');
  await expect(page.locator('#hero-freedom-display')).toBeVisible();
  await expect(page.locator('#hero-freedom-value')).toHaveText('Free at 45 · March 2040');
  await expect(page.locator('#hero-cta')).toBeHidden();
  await expect(page.locator('#hero-cta-note')).toBeHidden();
});

test('index.html with a saved WAITLISTED status shows the on-the-list note in place of the form at once', async ({ page }) => {
  await seedStorage(page, { waitlist_status: 'WAITLISTED' });
  await installMocks(page);
  await page.goto('/index.html');
  // A saved state opens the reveal zone itself (2026-09-26): no reveal click needed,
  // and the calculator stays above it.
  await expect(page.locator('#reveal-zone')).toHaveClass(/open/);
  await expect(page.locator('#age-range')).toBeVisible();
  await expect(page.locator('#success-block')).toBeVisible();
  await expect(page.locator('#success-note')).toHaveText(NOTE_WAITLISTED);
  await expect(page.locator('#waitlist-form')).toBeHidden();
  await expect(page.locator('#capture-cta-note')).toBeHidden();
  await expect(page.locator('#pending-block')).toBeHidden();
});

// --- 2. Consent sentence ----------------------------------------------------

/** Asserts the consent note on the current page and returns its text. */
async function expectConsentNote(page) {
  const note = page.locator('#consent-note');
  await expect(note).toBeVisible();
  await expect(note).toContainText('By signing up for our waitlist, you recognize that your data');
  await expect(note).toContainText('you may do so through the unsubscribe link contained in our messages.');
  const link = note.locator('a');
  await expect(link).toHaveCount(1);
  await expect(link).toHaveText('privacy policy');
  // The constant links to the clean /privacy path; on localhost devLinkMap
  // rewrites it to privacy.html because nothing maps clean URLs there.
  await expect(link).toHaveAttribute('href', /^(\/privacy|privacy\.html)$/);
  const text = await note.textContent();
  expect(text).not.toContain(EM_DASH);
  return text;
}

const COUNSEL_SENTENCE = 'By signing up for our waitlist, you recognize that your data (including your email address, stored calculator inputs, and internet browsing activity) will be stored by us and handled in accord with our privacy policy and that we will contact you to market our services to you. If at any time you wish to unsubscribe from the waitlist, you may do so through the unsubscribe link contained in our messages.';

test('the index page consent sentence is counsel\'s exact wording and its link resolves to the privacy page', async ({ page }) => {
  await installMocks(page);
  await page.goto('/index.html');
  await revealIfNeeded(page);
  await expect(page.locator('#consent-note')).toHaveText(COUNSEL_SENTENCE);
  await expect(page.locator('#waitlist-form input[type="checkbox"]')).toHaveCount(0);
  const href = await page.locator('#consent-note a').evaluate((a) => a.href);
  const res = await page.request.get(href);
  expect(res.status()).toBe(200);
  expect(await res.text()).toMatch(/privacy/i);
});

test('the consent sentence under both waitlist forms is one identical text that asks for consent and links to the privacy policy', async ({ page }) => {
  await installMocks(page);
  await page.goto('/index.html');
  // On index.html the note sits inside the reveal zone, so it is visible only after the reveal.
  await expect(page.locator('#consent-note')).toBeHidden();
  await revealIfNeeded(page);
  const indexText = await expectConsentNote(page);

  await page.goto('/about.html');
  const aboutText = await expectConsentNote(page);

  expect(aboutText).toBe(indexText);
  expect(indexText).toBe(COUNSEL_SENTENCE);
  // The source constant itself carries no em dash either (markup included).
  const constant = await page.evaluate(() => window.FredWaitlist.CONSENT_SENTENCE_HTML);
  expect(constant).not.toContain(EM_DASH);
});

// --- 3. Live-only proof numbers on about.html --------------------------------

test('about.html fetches the stats endpoint on load and shows the count tile at 40, at or above STAT_TILE_MIN_N', async ({ page }) => {
  const calls = await installMocks(page, { stats: { ...STATS, count: 40 } });
  await page.goto('/about.html');
  expect(await page.evaluate(() => window.FredWaitlist.STAT_TILE_MIN_N)).toBe(STAT_TILE_MIN_N);
  await expect(page.locator('#stat-count-tile')).toBeVisible();
  await expect(page.locator('#stat-count')).toHaveText('40');
  expect(calls.stats).toBe(1);
  // No spots-filled meter any more: the join card carries only the note.
  await expect(page.locator('#progress-block')).toHaveCount(0);
  await expect(page.locator('#waitlist-note')).toBeVisible();
  await expect(page.locator('#btn-text')).toHaveText('Join Waitlist');
});

test('about.html with a count of 3 fetches the stats endpoint but keeps the count tile hidden', async ({ page }) => {
  const calls = await installMocks(page, { stats: { ...STATS, count: 3 } });
  const statsAnswered = page.waitForResponse((r) => /\/api\/waitlist\/stats/.test(r.url()));
  await page.goto('/about.html');
  await statsAnswered;
  // Let the page consume the response before asserting that nothing rendered.
  await page.waitForLoadState('networkidle');
  expect(calls.stats).toBe(1);
  await expect(page.locator('#stat-count-tile')).toBeHidden();
  await expect(page.locator('#stat-count')).toHaveText('');
});

test('about.html never words the join card as a full beta or a priority waitlist, whatever the count', async ({ page }) => {
  await installMocks(page, { stats: { ...STATS, count: 5000 } });
  await page.goto('/about.html');
  await expect(page.locator('#stat-count')).toHaveText('5,000');
  await expect(page.locator('#waitlist-note')).not.toContainText('full');
  await expect(page.locator('#waitlist-note')).not.toContainText('priority');
  await expect(page.locator('#btn-text')).toHaveText('Join Waitlist');
  await expect(page.locator('#join')).not.toContainText('priority waitlist', { ignoreCase: true });
});

// --- 3b. List-size line on index.html (same gate as about.html's count tile) ----

test('index.html keeps the list-size line hidden below STAT_TILE_MIN_N', async ({ page }) => {
  const calls = await installMocks(page, { stats: { ...STATS, count: 12 } });
  const statsAnswered = page.waitForResponse((r) => /\/api\/waitlist\/stats/.test(r.url()));
  await page.goto('/index.html');
  await statsAnswered;
  await page.waitForLoadState('networkidle');
  expect(calls.stats).toBe(1);
  await expect(page.locator('#capture-count')).toBeHidden();
  await expect(page.locator('#capture-count')).toHaveText('');
  await expect(page.locator('#capture-count-block')).toHaveCount(0);
  await expect(page.locator('#capture-cta-note')).toContainText('No payment until launch. You only pay if invited and choose to claim your spot.');
  await expect(page.locator('#capture-cta-note')).toContainText('The private beta is currently iOS only.');
});

test('index.html shows the list-size line from the live count at or above STAT_TILE_MIN_N', async ({ page }) => {
  await installMocks(page, { stats: { ...STATS, count: 120 } });
  await page.goto('/index.html');
  await revealIfNeeded(page);
  await expect(page.locator('#capture-count')).toBeVisible();
  await expect(page.locator('#capture-count')).toHaveText('120 people are already on the list.');
  await expect(page.locator('#capture-count b')).toHaveText('120');
  await expect(page.locator('#capture-scarcity-pill')).toBeVisible();
});

test('index.html keeps the scarcity pill and formats a large count, with no cap or "full" wording anywhere', async ({ page }) => {
  await installMocks(page, { stats: { ...STATS, count: 4200 } });
  await page.goto('/index.html');
  await revealIfNeeded(page);
  await expect(page.locator('#capture-count')).toHaveText('4,200 people are already on the list.');
  await expect(page.locator('#capture-scarcity-pill')).toBeVisible();
  await expect(page.locator('#capture-scarcity-pill')).toHaveText(/Founding member spots are limited/);
  await expect(page.locator('#capture-count')).not.toContainText('/');
});

test('index.html states that the private beta is iOS only under the payment sentence', async ({ page }) => {
  await installMocks(page);
  await page.goto('/index.html');
  await revealIfNeeded(page);
  const iosLine = page.locator('#capture-cta-note p').last();
  await expect(iosLine).toHaveText('The private beta is currently iOS only.');
  await expect(iosLine.locator('b')).toHaveText('iOS only');
});
