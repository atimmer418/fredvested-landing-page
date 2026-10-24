// Post-reveal conversion (2026-10-24): the result context line, the "what moves your date" card,
// the waitlist form moved up into a dark capture card directly under it, and the Lever Viewed
// goal. Nothing of this exists in the DOM before the reveal; after it there is exactly one email
// input on the page. Copy is frozen (COPY in index.html) and checked verbatim here so a reworded
// sentence fails before counsel sees it.
//
// Reference numbers (frontend/assets/projection.js, checked in Node; moderate 10% unless said):
//   defaults 30 / $2,000 / $7,500 / $0:          53; +$250 -> 52; a year later -> 54, 12 months; 12 years before 65
//   42 / $500 / $4,000 / $0:                      73 (65+); +$100 -> 71; a year later -> 74, 12 months
//   30 / $2,000 / $7,500 / $250,000 (tick 46):    46; +$250 rounds to 46 (row 1 hidden); a year later -> 47, 6 months
//   30 / $2,000 / $7,500 / $1,000,000 (tick 76):  37; both rows hidden (lever flat, slip 2 months)
//   30 / $500 / $7,500 / $0 (recalculate):        67; +$100 -> 65; a year later -> 68, 12 months
//   41 / $2,000 / $7,500 / $0:                    64; "1 year before the usual 65"
const { test, expect } = require('@playwright/test');
const { installMocks, forceAnalytics, events, eventNames, collectHosts, assertOnlyAllowedHosts, submitWaitlist, seedStorage } = require('./helpers');

const COPY = {
  CONTEXT_65_PLUS: 'Here is what moves this date.',
  LEVERS_HEADING: 'This date is not fixed. Here is what moves it.',
  DELAY_TITLE: 'Start one year later',
  CAPTURE_HEADING_SAVE: 'Save this Freedom Date.',
  CAPTURE_SUPPORT: 'We will save this Freedom Date and email you the exact steps when FRED opens. No app needed yet.',
  EMAIL_LABEL: 'Email',
  BUTTON: 'Save my Freedom Date',
};
const NO_DATE_HEADING = 'Put your financial future on autopilot.';
const EM_DASH = String.fromCharCode(0x2014);
// Words the feature may never render (spec rule); "FRED gets you" and any percentage return included.
const FORBIDDEN = [/guarantee/i, /will retire/i, /FRED gets you/i, /\d+\s*%/];

const VIEWPORTS = [
  { name: '375px', width: 375, height: 667 },
  { name: '1280px', width: 1280, height: 800 },
];

const named = (list, name) => list.filter((e) => e.name === name);

async function reveal(page) {
  await page.click('#reveal-btn');
  await expect(page.locator('#capture-email')).toBeVisible();
}
async function scrollTo(page, selector) {
  await page.locator(selector).scrollIntoViewIfNeeded();
}
/** Text of the whole post-reveal section (lever card + capture card), for the copy sweeps. */
async function postRevealText(page) {
  return page.locator('#post-reveal').innerText();
}

for (const vp of VIEWPORTS) {
  test.describe(`at ${vp.name}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height } });

    test('before the reveal there is no lever card, no capture card and no email input in the DOM', async ({ page }) => {
      await installMocks(page);
      await page.goto('/index.html');
      await expect(page.locator('#levers-card')).toHaveCount(0);
      await expect(page.locator('#join')).toHaveCount(0);
      await expect(page.locator('input[type="email"]')).toHaveCount(0);
      await expect(page.locator('#waitlist-form')).toHaveCount(0);
      await expect(page.locator('#post-reveal')).toBeEmpty();
      // The template holds the markup but is not part of the rendered document.
      await expect(page.locator('#post-reveal-template')).toHaveCount(1);
    });

    test('defaults (53): context line, both rows, lever heading, one email input, capture card right under the result', async ({ page }) => {
      await installMocks(page);
      await page.goto('/index.html');
      await reveal(page);
      await expect(page.locator('#result-age')).toHaveText('53');
      await expect(page.locator('#result-context')).toHaveText('12 years before the usual 65');

      const card = page.locator('#levers-card');
      await expect(card).toBeVisible();
      await expect(page.locator('#levers-heading')).toHaveText(COPY.LEVERS_HEADING);
      await expect(page.locator('#lever-row')).toBeVisible();
      await expect(page.locator('#lever-title')).toHaveText('Invest $250 more each month');
      await expect(page.locator('#lever-text')).toHaveText('Freedom Date moves to 52');
      await expect(page.locator('#delay-row')).toBeVisible();
      await expect(page.locator('#delay-title')).toHaveText(COPY.DELAY_TITLE);
      await expect(page.locator('#delay-text')).toHaveText('Freedom Date slips to 54. Waiting costs about 12 months per year.');
      // Each row carries one inline icon (the page's SVG treatment, no icon font).
      await expect(page.locator('#lever-row svg')).toHaveCount(1);
      await expect(page.locator('#delay-row svg')).toHaveCount(1);

      await expect(page.locator('#capture-heading')).toHaveText('Get the plan that gets you to 52.');
      await expect(page.locator('#capture-support')).toHaveText(COPY.CAPTURE_SUPPORT);
      await expect(page.locator('input[type="email"]')).toHaveCount(1);
      await expect(page.locator('#btn-label')).toHaveText(COPY.BUTTON);
      await expect(page.locator('#join-waitlist-btn')).toContainText(COPY.BUTTON);

      // Order: result card, lever card, capture card, then the methodology accordion.
      const tops = await page.evaluate(() => ['result-card', 'levers-card', 'join', 'calc-method'].map((id) => document.getElementById(id).getBoundingClientRect().top));
      expect(tops[0]).toBeLessThan(tops[1]);
      expect(tops[1]).toBeLessThan(tops[2]);
      expect(tops[2]).toBeLessThan(tops[3]);
      if (vp.width === 375) {
        // The form is within 1.5 viewport heights of the result.
        expect(tops[2] - tops[0]).toBeLessThan(1.5 * vp.height);
      }
    });

    test('an age of 65 or more reads "Here is what moves this date." with no negative framing', async ({ page }) => {
      await installMocks(page);
      await page.goto('/index.html');
      await page.fill('#age-range', '42');
      await page.fill('#invest-range', '500');
      await page.fill('#retire-range', '4000');
      await reveal(page);
      await expect(page.locator('#result-age')).toHaveText('73');
      await expect(page.locator('#result-context')).toHaveText(COPY.CONTEXT_65_PLUS);
      await expect(page.locator('#lever-title')).toHaveText('Invest $100 more each month');
      await expect(page.locator('#lever-text')).toHaveText('Freedom Date moves to 71');
      await expect(page.locator('#delay-text')).toHaveText('Freedom Date slips to 74. Waiting costs about 12 months per year.');
      await expect(page.locator('#capture-heading')).toHaveText('Get the plan that gets you to 71.');
      expect(await postRevealText(page)).not.toMatch(/behind/i);
    });

    test('when the lever cannot move the date a whole year, row 1 is hidden and the heading reads "Save this Freedom Date."', async ({ page }) => {
      await installMocks(page);
      await page.goto('/index.html');
      await page.fill('#existing-range', '46'); // $250,000
      await reveal(page);
      await expect(page.locator('#result-age')).toHaveText('46');
      await expect(page.locator('#levers-card')).toBeVisible();
      await expect(page.locator('#lever-row')).toBeHidden();
      await expect(page.locator('#delay-row')).toBeVisible();
      await expect(page.locator('#delay-text')).toHaveText('Freedom Date slips to 47. Waiting costs about 6 months per year.');
      await expect(page.locator('#capture-heading')).toHaveText(COPY.CAPTURE_HEADING_SAVE);
      // Never "moves to 46" under a 46.
      expect(await postRevealText(page)).not.toContain('moves to 46');
    });

    test('recalculating rewrites the context line, both rows and the heading', async ({ page }) => {
      await installMocks(page);
      await page.goto('/index.html');
      await reveal(page);
      await expect(page.locator('#capture-heading')).toHaveText('Get the plan that gets you to 52.');

      await page.fill('#invest-range', '500');
      await page.click('#reveal-btn'); // Recalculate
      await expect(page.locator('#result-age')).toHaveText('67');
      await expect(page.locator('#result-context')).toHaveText(COPY.CONTEXT_65_PLUS);
      await expect(page.locator('#lever-title')).toHaveText('Invest $100 more each month');
      await expect(page.locator('#lever-text')).toHaveText('Freedom Date moves to 65');
      await expect(page.locator('#delay-text')).toHaveText('Freedom Date slips to 68. Waiting costs about 12 months per year.');
      await expect(page.locator('#capture-heading')).toHaveText('Get the plan that gets you to 65.');

      // Live slider edits keep the rows current too (same path as a drag).
      await page.locator('#existing-range').evaluate((el) => { el.value = '76'; el.dispatchEvent(new Event('input', { bubbles: true })); }); // $1,000,000
      await page.fill('#invest-range', '2000');
      await expect(page.locator('#result-age')).toHaveText('37');
      await expect(page.locator('#levers-card')).toBeHidden();
      await expect(page.locator('#capture-heading')).toHaveText(COPY.CAPTURE_HEADING_SAVE);
      await expect(page.locator('input[type="email"]')).toHaveCount(1);
    });

    test('nothing scrolls sideways and the whole post-reveal section stays inside the viewport width', async ({ page }) => {
      await installMocks(page);
      await page.goto('/index.html');
      await reveal(page);
      await scrollTo(page, '#join');
      const r = await page.evaluate(() => {
        const ids = ['levers-card', 'join', 'capture-email', 'join-waitlist-btn'];
        const boxes = ids.map((id) => document.getElementById(id).getBoundingClientRect());
        return {
          scrollWidth: document.documentElement.scrollWidth,
          innerWidth: window.innerWidth,
          maxRight: Math.max(...boxes.map((b) => b.right)),
          minLeft: Math.min(...boxes.map((b) => b.left)),
        };
      });
      expect(r.scrollWidth).toBeLessThanOrEqual(r.innerWidth);
      expect(r.maxRight).toBeLessThanOrEqual(r.innerWidth);
      expect(r.minLeft).toBeGreaterThanOrEqual(0);
    });
  });
}

test.describe('the capture card', () => {
  test.use({ viewport: { width: 375, height: 667 } });

  test('email input and button meet the one-tap spec; the button is the only one in the card; consent sits under it', async ({ page }) => {
    await installMocks(page);
    await page.goto('/index.html');
    await reveal(page);
    const input = page.locator('#capture-email');
    await expect(input).toHaveAttribute('type', 'email');
    await expect(input).toHaveAttribute('inputmode', 'email');
    await expect(input).toHaveAttribute('autocomplete', 'email');
    await expect(input).toHaveAttribute('enterkeyhint', 'go');
    await expect(page.locator('label[for="capture-email"]')).toHaveText(COPY.EMAIL_LABEL);
    const m = await page.evaluate(() => {
      const i = document.getElementById('capture-email');
      const b = document.getElementById('join-waitlist-btn');
      const card = document.getElementById('join');
      const consent = document.getElementById('consent-note');
      return {
        inputHeight: i.getBoundingClientRect().height,
        inputFont: parseFloat(getComputedStyle(i).fontSize),
        btnHeight: b.getBoundingClientRect().height,
        btnWidth: b.getBoundingClientRect().width,
        formWidth: document.getElementById('waitlist-form').getBoundingClientRect().width,
        buttons: card.querySelectorAll('button:not([hidden])').length,
        visibleButtons: [...card.querySelectorAll('button')].filter((x) => x.offsetParent !== null).length,
        consentBelowButton: consent.getBoundingClientRect().top >= b.getBoundingClientRect().bottom,
        cardBg: getComputedStyle(card).backgroundColor,
      };
    });
    expect(m.inputHeight).toBeGreaterThanOrEqual(48);
    expect(m.inputFont).toBeGreaterThanOrEqual(16);
    expect(m.btnHeight).toBeGreaterThanOrEqual(48);
    expect(Math.round(m.btnWidth)).toBe(Math.round(m.formWidth));
    expect(m.visibleButtons).toBe(1);
    expect(m.consentBelowButton).toBe(true);
    // The navy text token (#0F172A) as the dark surface.
    expect(m.cardBg).toBe('rgb(15, 23, 42)');
    await expect(page.locator('#consent-note')).toContainText('By signing up for our waitlist');
  });

  test('anchor links to #join land on the capture card', async ({ page }) => {
    await installMocks(page);
    await page.goto('/index.html');
    await reveal(page);
    await expect(page.locator('#join #waitlist-form')).toHaveCount(1);
    // Let the reveal's own smooth scroll to the result settle first; a hash set while it is in
    // flight is overridden by it (the reveal scrolls 150 ms after the click).
    await page.waitForTimeout(1200);
    await page.evaluate(() => { location.hash = '#join'; });
    await expect.poll(() => page.evaluate(() => {
      const r = document.getElementById('join').getBoundingClientRect();
      return r.top >= -2 && r.top < window.innerHeight;
    })).toBe(true);
  });

  test('a no-date result keeps the form, falls back to the approved capture heading and shows no lever card', async ({ page }) => {
    await installMocks(page);
    await page.goto('/index.html');
    await page.fill('#invest-range', '0');
    await reveal(page);
    await expect(page.locator('#infeasible-block')).toBeVisible();
    await expect(page.locator('#result-context')).toBeHidden();
    await expect(page.locator('#levers-card')).toBeHidden();
    await expect(page.locator('#capture-heading')).toHaveText(NO_DATE_HEADING);
    await expect(page.locator('#capture-support')).toBeHidden();
    await expect(page.locator('input[type="email"]')).toHaveCount(1);
    // Back to a date: the lever copy returns.
    await page.fill('#invest-range', '2000');
    await expect(page.locator('#capture-heading')).toHaveText('Get the plan that gets you to 52.');
    await expect(page.locator('#capture-support')).toBeVisible();
  });

  test('the at-target state keeps the form with the approved heading and no lever card', async ({ page }) => {
    await installMocks(page);
    await page.goto('/index.html');
    await page.fill('#retire-range', '1000');
    await page.fill('#existing-range', '48'); // $300,000, the target
    await reveal(page);
    await expect(page.locator('#at-target-block')).toBeVisible();
    await expect(page.locator('#levers-card')).toBeHidden();
    await expect(page.locator('#capture-heading')).toHaveText(NO_DATE_HEADING);
    await expect(page.locator('#capture-email')).toBeVisible();
  });

  test('the submit flow is unchanged: the pending block replaces the form inside the capture card', async ({ page }) => {
    const calls = await installMocks(page);
    await page.goto('/index.html');
    await submitWaitlist(page, 'moved@example.com');
    await expect(page.locator('#join #pending-block')).toBeVisible({ timeout: 10_000 });
    await expect(page.locator('#waitlist-form')).toBeHidden();
    await expect(page.locator('#capture-heading')).toHaveText('Get the plan that gets you to 52.');
    expect(calls.signup[0]).toMatchObject({ email: 'moved@example.com', freedomAge: 53, revealedBeforeSubmit: true });
  });

  test('a returning visitor gets the capture card mounted on load with the success state and the lever card for the current inputs', async ({ page }) => {
    await seedStorage(page, { waitlist_status: 'WAITLISTED' });
    await installMocks(page);
    await page.goto('/index.html');
    await expect(page.locator('#reveal-zone')).toHaveClass(/open/);
    await expect(page.locator('#success-block')).toBeVisible();
    await expect(page.locator('#waitlist-form')).toBeHidden();
    await expect(page.locator('#levers-card')).toBeVisible();
    await expect(page.locator('#lever-text')).toHaveText('Freedom Date moves to 52');
    await expect(page.locator('input[type="email"]')).toHaveCount(1);
  });
});

test.describe('copy', () => {
  test('the exact frozen sentences, no em dash, no forbidden words, and a single disclaimer under the result', async ({ page }) => {
    await installMocks(page);
    await page.goto('/index.html');
    await reveal(page);
    const text = await postRevealText(page);
    for (const re of FORBIDDEN) expect(text).not.toMatch(re);
    expect(text).not.toContain(EM_DASH);
    expect(await page.locator('#result-context').innerText()).not.toContain(EM_DASH);
    // One disclosure near the output (the attorney-approved sentence in the result card); the lever and
    // capture cards add none, so there are never two.
    const zone = await page.locator('#reveal-zone').innerText();
    const accordion = await page.locator('#calc-method').innerText();
    const outsideAccordion = zone.replace(accordion, '');
    expect(outsideAccordion.match(/Hypothetical illustration/g) || []).toHaveLength(1);
    await expect(page.locator('#result-card')).toContainText('Hypothetical illustration based on the assumptions shown, not a prediction or guarantee.');
  });

  test('at 65 or more with no card to point at, the context line is not shown and the live region has no double stop', async ({ page }) => {
    await installMocks(page);
    await page.goto('/index.html');
    await page.fill('#age-range', '60');
    await page.fill('#invest-range', '50');
    await page.fill('#existing-range', '72'); // $900,000: 69, lever flat, slip rounds to 0
    await reveal(page);
    await expect(page.locator('#result-age')).toHaveText('69');
    await expect(page.locator('#levers-card')).toBeHidden();
    await expect(page.locator('#result-context')).toBeHidden();
    await expect(page.locator('#result-context')).toHaveText('');
    expect(await page.locator('#result-sr').textContent()).not.toContain('..');
    // With a card, the sentence is there and the announcement still reads with one stop.
    await page.fill('#existing-range', '0');
    await page.fill('#invest-range', '500');
    await page.fill('#age-range', '42');
    await page.fill('#retire-range', '4000');
    await expect(page.locator('#result-context')).toHaveText(COPY.CONTEXT_65_PLUS);
    const sr = await page.locator('#result-sr').textContent();
    expect(sr).toContain('Here is what moves this date. Freedom Date:');
    expect(sr).not.toContain('..');
  });

  test('"1 year before the usual 65" is singular at 64', async ({ page }) => {
    await installMocks(page);
    await page.goto('/index.html');
    await page.fill('#age-range', '41');
    await reveal(page);
    await expect(page.locator('#result-age')).toHaveText('64');
    await expect(page.locator('#result-context')).toHaveText('1 year before the usual 65');
  });
});

test.describe('analytics', () => {
  const LEVER_KEYS = ['lever_shift_band', 'delay_months_band', 'freedom_age_band'];
  const SHIFT_BANDS = ['hidden', '0', '1-2', '3-5', '6+'];
  const DELAY_BANDS = ['hidden', '3-6', '7-11', '12+'];

  test('Lever Viewed fires once per reveal when the card is in view, with the three banded properties and no raw number', async ({ page }) => {
    await forceAnalytics(page);
    await installMocks(page);
    await page.setViewportSize({ width: 375, height: 667 });
    await page.goto('/index.html');
    await reveal(page);
    await scrollTo(page, '#levers-card');
    await expect.poll(() => eventNames(page)).toContain('Lever Viewed');
    let viewed = named(await events(page), 'Lever Viewed');
    expect(viewed).toHaveLength(1);
    expect(Object.keys(viewed[0].props).sort()).toEqual([...LEVER_KEYS].sort());
    expect(viewed[0].props).toEqual({ lever_shift_band: '1-2', delay_months_band: '12+', freedom_age_band: '50-54' });
    for (const v of Object.values(viewed[0].props)) expect(/^\d+(\.\d+)?$/.test(v)).toBe(false);

    // Scrolling away and back adds nothing within the same reveal.
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.waitForTimeout(300);
    await scrollTo(page, '#levers-card');
    await page.waitForTimeout(300);
    expect(named(await events(page), 'Lever Viewed')).toHaveLength(1);

    // A second reveal click re-arms it: once more, with the new bands.
    await page.fill('#invest-range', '500');
    await page.click('#reveal-btn');
    await scrollTo(page, '#levers-card');
    await expect.poll(async () => named(await events(page), 'Lever Viewed').length).toBe(2);
    viewed = named(await events(page), 'Lever Viewed');
    expect(viewed[1].props).toEqual({ lever_shift_band: '1-2', delay_months_band: '12+', freedom_age_band: '65+' });
    expect(SHIFT_BANDS).toContain(viewed[1].props.lever_shift_band);
    expect(DELAY_BANDS).toContain(viewed[1].props.delay_months_band);
    // Nothing else new was invented: the only names are the documented ones.
    expect(new Set(await eventNames(page))).toEqual(new Set(['Freedom Date Revealed', 'Lever Viewed', 'Calc Engaged', 'Recalculated']));
  });

  test('Lever Viewed never fires when the card is hidden, and Email Focused / Waitlist Submitted band both rows as hidden', async ({ page }) => {
    await forceAnalytics(page);
    await installMocks(page);
    await page.goto('/index.html');
    await page.fill('#existing-range', '76'); // $1,000,000: lever flat, slip 2 months
    await reveal(page);
    await expect(page.locator('#levers-card')).toBeHidden();
    await submitWaitlist(page, 'hidden@example.com');
    await expect(page.locator('#pending-block')).toBeVisible({ timeout: 10_000 });
    const all = await events(page);
    expect(named(all, 'Lever Viewed')).toEqual([]);
    expect(named(all, 'Email Focused')[0].props).toEqual({ lever_shift_band: 'hidden', delay_months_band: 'hidden' });
    expect(named(all, 'Waitlist Submitted')[0].props).toMatchObject({ revealed: 'yes', freedom_age_band: '<45', lever_shift_band: 'hidden', delay_months_band: 'hidden' });
    expect(JSON.stringify(all)).not.toContain('1000000');
  });

  test('Email Focused and Waitlist Submitted carry the lever bands of the last reveal click', async ({ page }) => {
    await forceAnalytics(page);
    await installMocks(page);
    await page.goto('/index.html');
    await page.fill('#existing-range', '46'); // $250,000: row 1 hidden, slip 6 months
    await reveal(page);
    await submitWaitlist(page, 'bands@example.com');
    await expect(page.locator('#pending-block')).toBeVisible({ timeout: 10_000 });
    const all = await events(page);
    expect(named(all, 'Email Focused')[0].props).toEqual({ lever_shift_band: 'hidden', delay_months_band: '3-6' });
    expect(named(all, 'Waitlist Submitted')[0].props).toMatchObject({ lever_shift_band: 'hidden', delay_months_band: '3-6', freedom_age_band: '45-49' });
    const raw = ['250000', '46', '47', '6'];
    for (const e of all) for (const v of Object.values(e.props)) expect(raw).not.toContain(v);
  });

  test('a full post-reveal journey talks only to our origin and Turnstile', async ({ page }) => {
    await forceAnalytics(page);
    await installMocks(page);
    const seen = collectHosts(page);
    await page.goto('/index.html');
    await reveal(page);
    await scrollTo(page, '#levers-card');
    await submitWaitlist(page, 'hosts@example.com');
    await expect(page.locator('#pending-block')).toBeVisible({ timeout: 10_000 });
    await page.waitForLoadState('networkidle');
    assertOnlyAllowedHosts(seen);
  });

  test('index.html reaches the API only on submit: no stats call', async ({ page }) => {
    const calls = await installMocks(page);
    await page.goto('/index.html');
    await reveal(page);
    await page.waitForLoadState('networkidle');
    expect(calls.stats).toBe(0);
    await submitWaitlist(page, 'only-submit@example.com');
    await expect(page.locator('#pending-block')).toBeVisible({ timeout: 10_000 });
    expect(calls.stats).toBe(0);
    expect(calls.signup).toHaveLength(1);
  });

  test('a live edit after the click never fires Lever Viewed with the click\'s stale bands', async ({ page }) => {
    await forceAnalytics(page);
    await installMocks(page);
    await page.setViewportSize({ width: 375, height: 667 });
    await page.goto('/index.html');
    // Click with no date: no card, nothing armed. A live edit then shows the card: still nothing.
    await page.fill('#invest-range', '0');
    await reveal(page);
    await expect(page.locator('#levers-card')).toBeHidden();
    await page.fill('#invest-range', '2000');
    await expect(page.locator('#levers-card')).toBeVisible();
    await scrollTo(page, '#levers-card');
    await page.waitForTimeout(400);
    expect(named(await events(page), 'Lever Viewed')).toEqual([]);
    // The next click records this card and fires once.
    await page.click('#reveal-btn');
    await scrollTo(page, '#levers-card');
    await expect.poll(async () => named(await events(page), 'Lever Viewed').length).toBe(1);
    expect(named(await events(page), 'Lever Viewed')[0].props).toEqual({ lever_shift_band: '1-2', delay_months_band: '12+', freedom_age_band: '50-54' });
    // A click whose card is hidden, then a live edit that shows it: no event either.
    await page.fill('#existing-range', '76'); // $1,000,000
    await page.click('#reveal-btn');
    await expect(page.locator('#levers-card')).toBeHidden();
    await page.fill('#existing-range', '0');
    await expect(page.locator('#levers-card')).toBeVisible();
    await scrollTo(page, '#levers-card');
    await page.waitForTimeout(400);
    expect(named(await events(page), 'Lever Viewed')).toHaveLength(1);
  });

  test('a third reveal click (no Recalculated event) still bands its own card on Lever Viewed, Email Focused and Waitlist Submitted', async ({ page }) => {
    await forceAnalytics(page);
    await installMocks(page);
    await page.goto('/index.html');
    // Each click's event is awaited before the next edit: a slider move right after a click
    // disarms the observer (the card on screen is no longer the one the click recorded).
    const viewedCount = async () => named(await events(page), 'Lever Viewed').length;
    await reveal(page);
    await scrollTo(page, '#levers-card');
    await expect.poll(viewedCount).toBe(1);
    await page.fill('#invest-range', '500');
    await page.click('#reveal-btn'); // Recalculated: 67, 1-2 / 12+
    await scrollTo(page, '#levers-card');
    await expect.poll(viewedCount).toBe(2);
    await page.fill('#invest-range', '2000');
    await page.fill('#existing-range', '46'); // $250,000: 46, row 1 hidden, slip 6 months
    await page.click('#reveal-btn'); // third click: no event, context still recorded
    await scrollTo(page, '#levers-card');
    await expect.poll(viewedCount).toBe(3);
    const third = named(await events(page), 'Lever Viewed')[2].props;
    expect(third).toEqual({ lever_shift_band: 'hidden', delay_months_band: '3-6', freedom_age_band: '45-49' });
    await submitWaitlist(page, 'third@example.com');
    await expect(page.locator('#pending-block')).toBeVisible({ timeout: 10_000 });
    const all = await events(page);
    expect(named(all, 'Recalculated')).toHaveLength(1);
    expect(named(all, 'Email Focused')[0].props).toEqual({ lever_shift_band: 'hidden', delay_months_band: '3-6' });
    expect(named(all, 'Waitlist Submitted')[0].props).toMatchObject({ return_scenario: 'moderate', freedom_age_band: '45-49', lever_shift_band: 'hidden', delay_months_band: '3-6' });
  });
});
