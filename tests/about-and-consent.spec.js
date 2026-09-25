// Proves three things about the pages that carry a waitlist form, with the API
// mocked in the browser (tests/helpers.js) and no backend running:
//   1. The post-signup status wording. about.html and index.html read
//      localStorage.waitlist_status on load and, for a saved value, replace the
//      form with the success block. about.html also rewrites #nav-cta and the
//      price-card #founder-cta, and only a known WAITLISTFOUNDER may read as a
//      reserved Founder spot: WAITLISTNORMAL, already_joined and the bare
//      "confirmed" that confirmed.html stores when the tier is unknown all get
//      the priority-waitlist wording (FREDdocs/email-funnel.md, section 4).
//   2. The consent sentence. Both forms render CONSENT_SENTENCE_HTML from
//      assets/waitlist.js into #consent-note, so counsel's final wording lands in
//      one place: the two texts must be identical, ask for consent, link to the
//      privacy policy and carry no em dash.
//   3. about.html's live-only proof numbers. The stats endpoint is fetched on
//      load and the count tile and the spots-filled meter render only at or
//      above STAT_TILE_MIN_N (25); below it nothing renders at all.
const { test, expect } = require('@playwright/test');
const { STATS, installMocks, seedStorage, revealIfNeeded } = require('./helpers');

// The pages spell these with the typographic apostrophe (U+2019), exactly as
// STATUS_NOTES and showSuccess() do in about.html and index.html.
const NAV_CTA_DEFAULT = 'Get my Freedom Date';
const NAV_CTA_JOINED = 'You’re in ✓';
const FOUNDER_CTA_DEFAULT = 'Reserve my Founder spot';
const FOUNDER_CTA_RESERVED = 'Your spot in line is reserved';
const FOUNDER_CTA_PRIORITY = 'You’re on the priority waitlist';
const NOTE_FOUNDER = 'You’re in line for a Founder invite.';
const NOTE_NORMAL = 'You’re on the priority waitlist for a Founder spot.';
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

test('about.html with a saved WAITLISTFOUNDER status shows the success block, a joined nav CTA and a reserved founder CTA', async ({ page }) => {
  await seedStorage(page, { waitlist_status: 'WAITLISTFOUNDER' });
  await installMocks(page);
  await page.goto('/about.html');
  await expect(page.locator('#success-block')).toBeVisible();
  await expect(page.locator('#success-note')).toHaveText(NOTE_FOUNDER);
  await expect(page.locator('#waitlist-form')).toBeHidden();
  await expect(page.locator('#pending-block')).toBeHidden();
  await expect(page.locator('#nav-cta')).toHaveText(NAV_CTA_JOINED);
  await expect(page.locator('#nav-cta')).toHaveAttribute('href', '#join');
  await expect(page.locator('#founder-cta')).toContainText(FOUNDER_CTA_RESERVED);
  await expect(page.locator('#founder-cta')).not.toContainText(FOUNDER_CTA_PRIORITY);
  await expect(page.locator('#founder-cta')).toHaveAttribute('href', '#join');
});

// Everything that is not a known founder gets the priority-waitlist wording.
for (const { status, note } of [
  { status: 'WAITLISTNORMAL', note: NOTE_NORMAL },
  { status: 'confirmed', note: NOTE_CONFIRMED },
  { status: 'already_joined', note: NOTE_ALREADY_JOINED },
]) {
  test(`about.html with a saved ${status} status notes "${note}" and words the founder CTA as the priority waitlist, never reserved`, async ({ page }) => {
    await seedStorage(page, { waitlist_status: status });
    await installMocks(page);
    await page.goto('/about.html');
    await expect(page.locator('#success-block')).toBeVisible();
    await expect(page.locator('#success-note')).toHaveText(note);
    await expect(page.locator('#waitlist-form')).toBeHidden();
    await expect(page.locator('#nav-cta')).toHaveText(NAV_CTA_JOINED);
    const founderCta = page.locator('#founder-cta');
    // The positive check first, so the negative one runs against the rewritten
    // CTA and not against the page's initial "Reserve my Founder spot".
    await expect(founderCta).toContainText(FOUNDER_CTA_PRIORITY);
    await expect(founderCta).not.toContainText('reserved', { ignoreCase: true });
  });
}

test('about.html with a saved status and a saved freedom date shows the date in the hero instead of the calculator CTA', async ({ page }) => {
  await seedStorage(page, {
    waitlist_status: 'WAITLISTFOUNDER',
    waitlist_freedom: JSON.stringify({ age: 45, monthYear: 'March 2040' }),
  });
  await installMocks(page);
  await page.goto('/about.html');
  await expect(page.locator('#hero-freedom-display')).toBeVisible();
  await expect(page.locator('#hero-freedom-value')).toHaveText('Free at 45 · March 2040');
  await expect(page.locator('#hero-cta')).toBeHidden();
  await expect(page.locator('#hero-cta-note')).toBeHidden();
});

test('index.html with a saved WAITLISTNORMAL status shows the priority waitlist note in place of the form once revealed', async ({ page }) => {
  await seedStorage(page, { waitlist_status: 'WAITLISTNORMAL' });
  await installMocks(page);
  await page.goto('/index.html');
  // The capture block lives inside the reveal zone, which stays visibility:hidden
  // until the reveal button opens it (calculator first, by design).
  await expect(page.locator('#success-block')).toBeHidden();
  await page.click('#reveal-btn');
  await expect(page.locator('#success-block')).toBeVisible();
  await expect(page.locator('#success-note')).toHaveText(NOTE_NORMAL);
  await expect(page.locator('#waitlist-form')).toBeHidden();
  await expect(page.locator('#capture-cta-note')).toBeHidden();
  await expect(page.locator('#pending-block')).toBeHidden();
});

// --- 2. Consent sentence ----------------------------------------------------

/** Asserts the consent note on the current page and returns its text. */
async function expectConsentNote(page) {
  const note = page.locator('#consent-note');
  await expect(note).toBeVisible();
  await expect(note).toContainText('By joining the waitlist, you consent');
  await expect(note).toContainText('You can unsubscribe at any time.');
  const link = note.locator('a');
  await expect(link).toHaveCount(1);
  await expect(link).toHaveText('Privacy Policy');
  // The constant links to the clean /privacy path; on localhost devLinkMap
  // rewrites it to privacy.html because nothing maps clean URLs there.
  await expect(link).toHaveAttribute('href', /^(\/privacy|privacy\.html)$/);
  const text = await note.textContent();
  expect(text).not.toContain(EM_DASH);
  return text;
}

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
  // The source constant itself carries no em dash either (markup included).
  const constant = await page.evaluate(() => window.FredWaitlist.CONSENT_SENTENCE_HTML);
  expect(constant).not.toContain(EM_DASH);
});

// --- 3. Live-only proof numbers on about.html --------------------------------

test('about.html fetches the stats endpoint on load and shows the count tile at 40, at or above STAT_TILE_MIN_N', async ({ page }) => {
  const calls = await installMocks(page, { stats: { ...STATS, count: 40, founderCount: 12 } });
  await page.goto('/about.html');
  expect(await page.evaluate(() => window.FredWaitlist.STAT_TILE_MIN_N)).toBe(STAT_TILE_MIN_N);
  await expect(page.locator('#stat-count-tile')).toBeVisible();
  await expect(page.locator('#stat-count')).toHaveText('40');
  expect(calls.stats).toBe(1);
  // founderCount 12 is below the threshold: the spots-filled meter stays hidden.
  await expect(page.locator('#progress-block')).toBeHidden();
});

test('about.html with a count of 3 fetches the stats endpoint but keeps the count tile hidden', async ({ page }) => {
  const calls = await installMocks(page, { stats: { ...STATS, count: 3, founderCount: 3 } });
  const statsAnswered = page.waitForResponse((r) => /\/api\/waitlist\/stats/.test(r.url()));
  await page.goto('/about.html');
  await statsAnswered;
  // Let the page consume the response before asserting that nothing rendered.
  await page.waitForLoadState('networkidle');
  expect(calls.stats).toBe(1);
  await expect(page.locator('#stat-count-tile')).toBeHidden();
  await expect(page.locator('#stat-count')).toHaveText('');
  await expect(page.locator('#progress-block')).toBeHidden();
});

test('about.html shows the spots-filled meter once the founder count reaches STAT_TILE_MIN_N', async ({ page }) => {
  await installMocks(page, { stats: { ...STATS, count: 60, founderCount: 40 } });
  await page.goto('/about.html');
  await expect(page.locator('#progress-block')).toBeVisible();
  await expect(page.locator('#progress-count')).toHaveText('40 / 300');
  await expect(page.locator('#btn-text')).toHaveText('Join Waitlist');
});

test('about.html at 300 confirmed founders reads full and offers the priority waitlist instead', async ({ page }) => {
  await installMocks(page, { stats: { ...STATS, count: 320, founderCount: 300 } });
  await page.goto('/about.html');
  await expect(page.locator('#progress-count')).toHaveText('300 / 300 (Full)');
  await expect(page.locator('#waitlist-note')).toContainText('The private beta is currently full.');
  await expect(page.locator('#btn-text')).toHaveText('Join Priority Waitlist');
});
