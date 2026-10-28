// Post-reveal conversion. 2026-10-25: the waitlist form moved into the reveal card itself, under a
// "do nothing vs this plan" comparison computed from the visitor's inputs. 2026-10-26: the
// "Freedom Date · Month Year" pill is back under "years old", the "what moves your date" card and
// its Lever Viewed event are gone, the capture copy changed and the pig sits on the submit button.
// 2026-10-27: Andrew's wording of the stakes line and the headline ("That gap is N years of your
// life." / "FRED exists to close the gap.").
// Nothing of the comparison or the form exists in the DOM before the reveal; after it there is
// exactly one email input on the page. Copy is frozen (COPY in index.html) and checked verbatim
// here so a reworded sentence fails before counsel sees it.
//
// Reference numbers (frontend/assets/projection.js, checked in Node; moderate 10% unless said):
//   defaults 30 / $2,000 / $7,500 / $0:          53; do nothing: 65+; N 12
//   42 / $500 / $4,000 / $0:                      73; do nothing 65+ (N -8); +$100 -> 71 (N -6): comparison hidden
//   30 / $2,000 / $7,500 / $250,000 (tick 46):    46; do nothing on the balance alone: 52; N 6
//   30 / $2,000 / $7,500 / $1,000,000 (tick 76):  37; do nothing: 38 (N 1), no lever step: comparison hidden
//   38 / $1,000 / $5,000 / $0:                    64; do nothing 65+ (N 1); +$250 -> 62: right cell is the lever, N 3
//   41 / $2,000 / $7,500 / $0:                    64; +$250 -> 63: lever, N 2
//   30 / $500 / $7,500 / $0 (recalculate):        67; do nothing 65+ (N -2); +$100 -> 65 (N 0): comparison hidden
const { test, expect } = require('@playwright/test');
const { installMocks, forceAnalytics, events, eventNames, collectHosts, assertOnlyAllowedHosts, submitWaitlist, seedStorage } = require('./helpers');

const RSQUO = String.fromCharCode(0x2019);
const COPY = {
  YEARS_OLD: 'years old',
  COMPARE_LEFT_LABEL: 'Do nothing.',
  COMPARE_LEFT_NEVER: '65+',
  COMPARE_LEFT_CAPTION_NEVER: 'the usual retirement',
  COMPARE_LEFT_CAPTION_EXISTING: 'on what you already have',
  COMPARE_RIGHT_LABEL: 'Stick to this plan.',
  COMPARE_RIGHT_CAPTION: 'your Freedom Date',
  HEADLINE: 'FRED exists to close the gap.',
  SUB: `Save your Freedom Date and we${RSQUO}ll help you turn it from a number into a plan the day we open.`,
  BUTTON: 'Let FRED help me get there',
  HEADLINE_STILL: 'This date can still move.',
  SUB_STILL: `Save it and we${RSQUO}ll show you what moves it the day FRED opens.`,
  BUTTON_SAVE: 'Save my Freedom Date',
  EMAIL_LABEL: 'Email',
  DISCLAIMER: 'Hypothetical illustration based on the assumptions shown, not a prediction or guarantee. Actual returns may vary.',
};
const stakes = (n) => `That gap is ${n} years of your life.`;
const NO_DATE_HEADING = 'Put your financial future on autopilot.';
const PILL = /^Freedom Date [A-Z][a-z]+ \d{4}$/;
const EM_DASH = String.fromCharCode(0x2014);
// Words the feature may never render (spec rule); "FRED gets you" and any percentage return included
// (the fine-print block carries the scenario's return and is checked separately).
const FORBIDDEN = [/guarantee/i, /will retire/i, /FRED gets you/i, /\d+\s*%/];
// Sentences of the 2026-10-24 / 2026-10-25 rounds that must not come back.
const GONE = ['This date is not fixed', 'Invest $', 'Start one year later', 'Freedom Date moves to', 'Freedom Date slips to', 'before the usual 65', 'on the table', 'Keep my', 'That is '];
const LEVER_KEYS = ['lever_shift_band', 'delay_months_band'];

const VIEWPORTS = [
  { name: '390px', width: 390, height: 660 },
  { name: '1280px', width: 1280, height: 800 },
];
const PHONE = { width: 390, height: 660 };

const named = (list, name) => list.filter((e) => e.name === name);

async function reveal(page) {
  await page.click('#reveal-btn');
  await expect(page.locator('#capture-email')).toBeVisible();
}
/** Text of everything the reveal renders (the result card holds all of it now), for the copy sweeps. */
async function revealText(page) {
  return page.locator('#result-card').innerText();
}
/** Text of the reveal minus the fine-print block (which legitimately carries the scenario's return). */
async function argumentText(page) {
  const all = await revealText(page);
  const fine = await page.locator('#result-fineprint').innerText();
  return all.replace(fine, '');
}
/** getBoundingClientRect of the card and the capture controls, in viewport coordinates. */
async function rects(page) {
  return page.evaluate(() => {
    const r = (id) => { const b = document.getElementById(id).getBoundingClientRect(); return { top: b.top, bottom: b.bottom, left: b.left, right: b.right, height: b.height }; };
    return { card: r('result-card'), input: r('capture-email'), button: r('join-waitlist-btn'), vh: window.innerHeight, vw: window.innerWidth };
  });
}
/** The pig: one inline SVG inside the button, left of the label, 22px tall, hidden from assistive tech. */
async function expectPig(page) {
  const btn = page.locator('#join-waitlist-btn');
  await expect(btn.locator('svg')).toHaveCount(1);
  await expect(btn.locator('img')).toHaveCount(0);
  const svg = btn.locator('#btn-text > svg');
  await expect(svg).toHaveCount(1);
  await expect(svg).toHaveAttribute('aria-hidden', 'true');
  await expect(svg).toHaveAttribute('focusable', 'false');
  const m = await page.evaluate(() => {
    const s = document.querySelector('#btn-text > svg');
    const l = document.getElementById('btn-label');
    const b = document.getElementById('join-waitlist-btn');
    const sr = s.getBoundingClientRect(); const lr = l.getBoundingClientRect(); const br = b.getBoundingClientRect();
    return {
      height: sr.height, width: sr.width, gap: lr.left - sr.right,
      leftOfText: sr.right <= lr.left,
      centred: Math.abs((sr.top + sr.bottom) / 2 - (br.top + br.bottom) / 2),
      ids: s.querySelectorAll('[id]').length, styles: s.querySelectorAll('style').length,
      currentColor: s.outerHTML.includes('currentColor'), scripts: s.querySelectorAll('script, foreignObject').length,
      labelLines: Math.round(lr.height / parseFloat(getComputedStyle(l).lineHeight)),
      text: b.innerText.trim(),
    };
  });
  expect(Math.round(m.height)).toBe(22);
  expect(Math.round(m.width)).toBe(22);
  expect(Math.round(m.gap)).toBe(8);
  expect(m.leftOfText).toBe(true);
  expect(m.centred).toBeLessThanOrEqual(1);
  expect(m.ids).toBe(0);
  expect(m.styles).toBe(0);
  expect(m.scripts).toBe(0);
  expect(m.currentColor).toBe(false);
  expect(m.labelLines).toBe(1);
  expect(m.text).not.toContain('→');
  return m.text;
}
async function expectComparison(page, { left, leftCaption, rightLabel, right, n }) {
  await expect(page.locator('#compare')).toBeVisible();
  await expect(page.locator('#compare-left-label')).toHaveText(COPY.COMPARE_LEFT_LABEL);
  await expect(page.locator('#compare-left-age')).toHaveText(left);
  await expect(page.locator('#compare-left-caption')).toHaveText(leftCaption);
  await expect(page.locator('#compare-right-label')).toHaveText(rightLabel);
  await expect(page.locator('#compare-right-age')).toHaveText(right);
  await expect(page.locator('#compare-right-caption')).toHaveText(COPY.COMPARE_RIGHT_CAPTION);
  await expect(page.locator('#stakes-line')).toHaveText(stakes(n));
  await expect(page.locator('#capture-heading')).toHaveText(COPY.HEADLINE);
  await expect(page.locator('#capture-support')).toHaveText(COPY.SUB);
  await expect(page.locator('#btn-label')).toHaveText(COPY.BUTTON);
  expect(await expectPig(page)).toBe(COPY.BUTTON);
}
async function expectCollapsed(page) {
  await expect(page.locator('#compare')).toBeHidden();
  await expect(page.locator('#stakes-line')).toBeHidden();
  await expect(page.locator('#capture-heading')).toHaveText(COPY.HEADLINE_STILL);
  await expect(page.locator('#capture-support')).toHaveText(COPY.SUB_STILL);
  await expect(page.locator('#btn-label')).toHaveText(COPY.BUTTON_SAVE);
  expect(await expectPig(page)).toBe(COPY.BUTTON_SAVE);
}
/** The Freedom Date header row (2026-10-27): label flush left, month and year flush right, hairline under, no chip. */
async function expectPill(page) {
  const line = page.locator('#result-date');
  await expect(line).toBeVisible();
  await expect(line).toHaveText(PILL);
  await expect(page.locator('#result-date-dot')).toHaveCount(0);
  await expect(page.locator('#result-chip')).toHaveCount(0);
  await expect(page.locator('#result-years-old')).toHaveText(COPY.YEARS_OLD);
  await expect(page.locator('#result-context')).toHaveCount(0);
  const m = await page.evaluate(() => {
    const p = document.getElementById('result-date');
    const v = document.getElementById('result-date-value');
    const y = document.getElementById('result-years-old');
    const e = document.getElementById('result-eyebrow');
    const card = document.getElementById('result-card');
    const cs = getComputedStyle(p), vs = getComputedStyle(v), ys = getComputedStyle(y);
    const pr = p.getBoundingClientRect(), er = e.getBoundingClientRect(), cr = card.getBoundingClientRect();
    const l = document.getElementById('result-date-label');
    const lr = l.getBoundingClientRect(), vr = v.getBoundingClientRect();
    const ls = getComputedStyle(l);
    return {
      value: v.textContent, label: l.textContent,
      // Label flush left and date flush right on the row, which spans the card's content box.
      labelFlushLeft: Math.abs(lr.left - pr.left), valueFlushRight: Math.abs(vr.right - pr.right),
      rowSpansCard: Math.abs(pr.width - (cr.width - parseFloat(getComputedStyle(card).paddingLeft) - parseFloat(getComputedStyle(card).paddingRight))),
      baseline: cs.alignItems, // 11px and 16px boxes have different bottoms even on one baseline
      labelStyle: [ls.fontSize, ls.fontWeight, ls.textTransform, ls.color, ls.letterSpacing],
      eyebrowStyle: (() => { const es = getComputedStyle(e); return [es.fontSize, es.fontWeight, es.textTransform, es.color, es.letterSpacing]; })(),
      valueFont: vs.fontSize, valueFamily: vs.fontFamily,
      hairline: [cs.borderBottomWidth, cs.borderBottomStyle, cs.borderTopWidth, cs.borderLeftWidth, cs.borderRightWidth],
      finePrintRule: getComputedStyle(document.querySelector('#result-card .border-t')).borderTopColor, hairlineColor: cs.borderBottomColor,
      aboveEyebrow: pr.bottom <= er.top, gapToEyebrow: er.top - pr.bottom,
      firstInCard: p === document.getElementById('feasible-block').firstElementChild,
      background: cs.backgroundColor, radius: cs.borderRadius,
      valueWeight: vs.fontWeight, valueColor: vs.color,
      yearsOldToCompare: document.getElementById('compare').getBoundingClientRect().top - y.getBoundingClientRect().bottom,
    };
  });
  expect(m.label).toBe('Freedom Date');
  expect(m.value).toMatch(/^[A-Z][a-z]+ \d{4}$/);
  expect(m.labelFlushLeft).toBeLessThanOrEqual(1);
  expect(m.valueFlushRight).toBeLessThanOrEqual(1);
  expect(m.rowSpansCard).toBeLessThanOrEqual(1);
  expect(m.baseline).toBe('baseline');
  expect(m.labelStyle).toEqual(m.eyebrowStyle); // the kicker's style, as approved
  expect(m.valueFont).toBe('16px');
  expect(m.valueFamily).toMatch(/Montserrat/);
  expect(m.hairline).toEqual(['1px', 'solid', '0px', '0px', '0px']);
  expect(m.hairlineColor).toBe(m.finePrintRule); // the same rule as above the fine print
  expect(m.aboveEyebrow).toBe(true);
  expect(m.firstInCard).toBe(true);
  expect(Math.round(m.gapToEyebrow)).toBe(12); // mt-3, the same token as "years old" to the comparison
  expect(m.background).toBe('rgba(0, 0, 0, 0)');
  expect(m.radius).toBe('0px');
  expect(m.valueWeight).toBe('700');
  expect(m.valueColor).toBe('rgb(255, 255, 255)');
  // Same month and year as the screen-reader summary.
  const sr = await page.locator('#result-sr').textContent();
  expect(sr).toContain((await line.textContent()).replace('Freedom Date ', 'Freedom Date: ') + '.');
}

for (const vp of VIEWPORTS) {
  test.describe(`at ${vp.name}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height } });

    test('before the reveal there is no comparison block, no form, no lever card and no email input in the DOM', async ({ page }) => {
      await installMocks(page);
      await page.goto('/index.html');
      await expect(page.locator('#compare')).toHaveCount(0);
      await expect(page.locator('#stakes-line')).toHaveCount(0);
      await expect(page.locator('#capture')).toHaveCount(0);
      await expect(page.locator('#levers-card')).toHaveCount(0);
      await expect(page.locator('#join')).toHaveCount(0);
      await expect(page.locator('input[type="email"]')).toHaveCount(0);
      await expect(page.locator('#waitlist-form')).toHaveCount(0);
      await expect(page.locator('#compare-host')).toBeEmpty();
      await expect(page.locator('#capture-host')).toBeEmpty();
      await expect(page.locator('#post-reveal')).toHaveCount(0);
      // The template holds the markup but is not part of the rendered document.
      await expect(page.locator('#post-reveal-template')).toHaveCount(1);
      // The date line is part of the result card's own markup, inside the closed zone.
      await expect(page.locator('#result-date')).toHaveCount(1);
      await expect(page.locator('#result-date')).toBeHidden();
    });

    test('defaults (53): "years old", the date line, 65+ against 53, twelve years, the form inside the reveal card, one email input, no lever card', async ({ page }) => {
      await installMocks(page);
      await page.goto('/index.html');
      await reveal(page);
      await expect(page.locator('#result-age')).toHaveText('53');
      await expectPill(page);
      await expectComparison(page, { left: '65+', leftCaption: COPY.COMPARE_LEFT_CAPTION_NEVER, rightLabel: COPY.COMPARE_RIGHT_LABEL, right: '53', n: 12 });
      await expect(page.locator('input[type="email"]')).toHaveCount(1);
      await expect(page.locator('#result-card #capture-email')).toHaveCount(1);
      await expect(page.locator('#result-card #join')).toHaveCount(1);
      await expect(page.locator('#result-fineprint')).toHaveText('Assumes a 10% avg annual return (Moderate scenario) & 4% withdrawal rate. Projected portfolio target: $2,250,000. ' + COPY.DISCLAIMER);
      await expect(page.locator('#levers-card')).toHaveCount(0);
      await expect(page.locator('#levers-heading, #lever-row, #delay-row, #lever-text, #delay-text')).toHaveCount(0);

      // Order top to bottom inside the card, then the accordion.
      const tops = await page.evaluate(() => ['result-date', 'result-eyebrow', 'result-age', 'result-years-old', 'compare', 'stakes-line', 'capture-heading', 'capture-support', 'capture-email', 'join-waitlist-btn', 'capture-count', 'consent-note', 'capture-cta-note', 'result-fineprint', 'calc-method']
        .map((id) => document.getElementById(id).getBoundingClientRect().top));
      for (let i = 1; i < tops.length; i++) expect(tops[i], `order at ${i}`).toBeGreaterThan(tops[i - 1]);
      const cardBottom = await page.evaluate(() => document.getElementById('result-card').getBoundingClientRect().bottom);
      expect(tops[13]).toBeLessThan(cardBottom); // fine print inside the card
      expect(tops[14]).toBeGreaterThanOrEqual(cardBottom); // the accordion directly below it
    });

    test('a large balance that reaches the target alone before 65 shows that age on the left, "on what you already have"', async ({ page }) => {
      await installMocks(page);
      await page.goto('/index.html');
      await page.fill('#existing-range', '46'); // $250,000
      await reveal(page);
      await expect(page.locator('#result-age')).toHaveText('46');
      await expectPill(page);
      await expectComparison(page, { left: '52', leftCaption: COPY.COMPARE_LEFT_CAPTION_EXISTING, rightLabel: COPY.COMPARE_RIGHT_LABEL, right: '46', n: 6 });
    });

    test('at 64 with a lever step available the right cell becomes "This plan + $250/mo" and N comes from the lever age', async ({ page }) => {
      await installMocks(page);
      await page.goto('/index.html');
      await page.fill('#age-range', '38');
      await page.fill('#invest-range', '1000');
      await page.fill('#retire-range', '5000');
      await reveal(page);
      await expect(page.locator('#result-age')).toHaveText('64');
      await expectPill(page);
      await expectComparison(page, { left: '65+', leftCaption: COPY.COMPARE_LEFT_CAPTION_NEVER, rightLabel: 'This plan + $250/mo', right: '62', n: 3 });
      // The two ages stay level even when the right label wraps (subgrid rows).
      const tops = await page.evaluate(() => ['compare-left-age', 'compare-right-age'].map((id) => Math.round(document.getElementById(id).getBoundingClientRect().top)));
      expect(tops[0]).toBe(tops[1]);
      // The live region hears the number with what it is measured against.
      expect(await page.locator('#result-sr').textContent()).toContain('Do nothing: 65+. This plan + $250/mo: 62. ' + stakes(3));
    });

    test('when both paths collapse (73, lever 71) the comparison and the stakes line are hidden and the still-moves copy shows', async ({ page }) => {
      await installMocks(page);
      await page.goto('/index.html');
      await page.fill('#age-range', '42');
      await page.fill('#invest-range', '500');
      await page.fill('#retire-range', '4000');
      await reveal(page);
      await expect(page.locator('#result-age')).toHaveText('73');
      await expectPill(page);
      await expectCollapsed(page);
      expect(await argumentText(page)).not.toMatch(/gap|of your life/i);
    });

    test('recalculating rewrites every number and scrolls the card back to the top', async ({ page }) => {
      await installMocks(page);
      await page.goto('/index.html');
      await reveal(page);
      await expect(page.locator('#btn-label')).toHaveText(COPY.BUTTON);
      await expect.poll(async () => Math.round((await rects(page)).card.top)).toBe(8);
      const firstPill = await page.locator('#result-date').textContent();

      await page.evaluate(() => window.scrollTo(0, 0));
      await page.fill('#invest-range', '500');
      await page.fill('#retire-range', '7000'); // 30 / $500 / $7,000: 66, target $2.1M, +$100 -> 64, nothing to compare
      await page.click('#reveal-btn'); // Recalculate
      await expect(page.locator('#result-age')).toHaveText('66');
      await expectPill(page);
      expect(await page.locator('#result-date').textContent()).not.toBe(firstPill);
      await expectCollapsed(page);
      await expect(page.locator('#result-fineprint')).toContainText('Projected portfolio target: $2,100,000.');
      await expect.poll(async () => Math.round((await rects(page)).card.top), { timeout: 3000 }).toBe(8);

      // Back to a comparison: the lever path this time (41: 64, +$250 -> 63, two years).
      await page.fill('#invest-range', '2000');
      await page.fill('#retire-range', '7500');
      await page.fill('#age-range', '41');
      await page.click('#reveal-btn');
      await expect(page.locator('#result-age')).toHaveText('64');
      await expectComparison(page, { left: '65+', leftCaption: COPY.COMPARE_LEFT_CAPTION_NEVER, rightLabel: 'This plan + $250/mo', right: '63', n: 2 });

      // Live slider edits keep everything current too (same path as a drag): $1,000,000, 37 vs 38, hidden.
      await page.fill('#age-range', '30');
      await page.locator('#existing-range').evaluate((el) => { el.value = '76'; el.dispatchEvent(new Event('input', { bubbles: true })); });
      await expect(page.locator('#result-age')).toHaveText('37');
      await expectCollapsed(page);
      await expect(page.locator('input[type="email"]')).toHaveCount(1);
    });

    test('nothing scrolls sideways and every new element stays inside the viewport width', async ({ page }) => {
      await installMocks(page);
      await page.goto('/index.html');
      await reveal(page);
      const r = await page.evaluate(() => {
        const ids = ['result-card', 'result-date', 'compare', 'stakes-line', 'capture-heading', 'capture-support', 'join', 'capture-email', 'join-waitlist-btn', 'btn-text', 'consent-note', 'capture-cta-note', 'result-fineprint'];
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

test.describe('the fit at 390 x 660 (iPhone with the Instagram in-app browser bar)', () => {
  test.use({ viewport: PHONE });

  test('after the reveal and its own scroll, the card top sits 8px down and the email input and button are fully in view with no scroll by the test', async ({ page }) => {
    await installMocks(page);
    await page.goto('/index.html');
    await reveal(page);
    // Only the page scrolls: wait for its smooth scroll to settle (150 ms delay plus the motion).
    await expect.poll(async () => Math.round((await rects(page)).card.top), { timeout: 3000 }).toBe(8);
    await page.waitForTimeout(300);
    const r = await rects(page);
    expect(Math.round(r.card.top)).toBe(8);
    for (const box of [r.input, r.button]) {
      expect(box.top).toBeGreaterThanOrEqual(0);
      expect(box.bottom).toBeLessThanOrEqual(r.vh);
      expect(box.left).toBeGreaterThanOrEqual(0);
      expect(box.right).toBeLessThanOrEqual(r.vw);
      expect(box.height).toBeGreaterThanOrEqual(48);
    }
    expect(r.input.bottom).toBeLessThan(r.button.top);
    await expectPill(page);
  });

  test('the same holds for the longest copy case (65+ against a two-digit age, lever label) and the collapsed case', async ({ page }) => {
    await installMocks(page);
    await page.goto('/index.html');
    await page.fill('#age-range', '38');
    await page.fill('#invest-range', '1000');
    await page.fill('#retire-range', '5000');
    await reveal(page);
    await expect(page.locator('#compare-right-label')).toHaveText('This plan + $250/mo');
    await expect.poll(async () => Math.round((await rects(page)).card.top), { timeout: 3000 }).toBe(8);
    let r = await rects(page);
    expect(r.button.bottom).toBeLessThanOrEqual(r.vh);

    await page.evaluate(() => window.scrollTo(0, 0));
    await page.fill('#age-range', '42');
    await page.fill('#invest-range', '500');
    await page.fill('#retire-range', '4000');
    await page.click('#reveal-btn');
    await expectCollapsed(page);
    await expect.poll(async () => Math.round((await rects(page)).card.top), { timeout: 3000 }).toBe(8);
    r = await rects(page);
    expect(r.button.bottom).toBeLessThanOrEqual(r.vh);
  });

  test('with reduced motion the scroll is instant and lands in the same place', async ({ page }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await installMocks(page);
    await page.goto('/index.html');
    await page.click('#reveal-btn');
    // 300 ms after the click the smooth scroll would still be hundreds of pixels away; instant is already there.
    await page.waitForTimeout(300);
    const r = await rects(page);
    expect(Math.round(r.card.top)).toBe(8);
    expect(r.button.bottom).toBeLessThanOrEqual(r.vh);
    // And the age did not count up.
    await expect(page.locator('#result-age')).not.toHaveAttribute('aria-hidden', 'true');
  });

  test('the page re-runs its scroll only when the first one was clamped: a visitor who scrolls after it is not pulled back', async ({ page }) => {
    await installMocks(page);
    await page.goto('/index.html');
    await reveal(page);
    await expect.poll(async () => Math.round((await rects(page)).card.top), { timeout: 3000 }).toBe(8);
    // Recalculate: the document is already full height, so the first scroll lands and the retry must not fire.
    await page.click('#reveal-btn');
    await expect.poll(async () => Math.round((await rects(page)).card.top), { timeout: 3000 }).toBe(8);
    await page.evaluate(() => window.scrollBy(0, 300)); // the page is shorter without the lever card: stay inside its scroll range
    await page.waitForTimeout(900);
    expect(Math.round((await rects(page)).card.top)).toBe(8 - 300);
  });
});

test.describe('the capture section', () => {
  test.use({ viewport: PHONE });

  test('email input and button meet the one-tap spec; one visible button; the label fits the button on one line; count line, consent and note follow in order', async ({ page }) => {
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
      const t = document.getElementById('btn-text');
      const card = document.getElementById('result-card');
      const consent = document.getElementById('consent-note');
      const count = document.getElementById('capture-count');
      const note = document.getElementById('capture-cta-note');
      const top = (el) => el.getBoundingClientRect().top;
      const bottom = (el) => el.getBoundingClientRect().bottom;
      const cs = getComputedStyle(b);
      return {
        inputHeight: i.getBoundingClientRect().height,
        inputFont: parseFloat(getComputedStyle(i).fontSize),
        btnHeight: b.getBoundingClientRect().height,
        btnWidth: b.getBoundingClientRect().width,
        // The pig, the gap and the label inside the button's content box (its padding untouched).
        // Measured from the row's children: scrollWidth of a centred flex row is its own width when
        // the content fits and only the right-hand half of the spill when it does not.
        btnContentWidth: b.clientWidth - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight),
        textWidth: (() => { const k = [...t.children].map((c) => c.getBoundingClientRect()); return Math.max(...k.map((r) => r.right)) - Math.min(...k.map((r) => r.left)); })(),
        formWidth: document.getElementById('waitlist-form').getBoundingClientRect().width,
        visibleButtons: [...card.querySelectorAll('button')].filter((x) => x.offsetParent !== null).length,
        // Order (Andrew's follow-up, 2026-10-25): button, count line, consent sentence, note; the note is the
        // capture section's last line and the fine print follows it.
        countBelowButton: top(count) >= bottom(b),
        consentBelowCount: top(consent) >= bottom(count),
        noteBelowConsent: top(note) >= bottom(consent),
        noteIsLast: document.getElementById('capture').lastElementChild === note,
        consentFont: parseFloat(getComputedStyle(consent).fontSize),
        noteStyle: [getComputedStyle(note).fontSize, getComputedStyle(note).color],
        consentStyle: [getComputedStyle(consent).fontSize, getComputedStyle(consent).color],
        consentAlign: getComputedStyle(consent).textAlign,
      };
    });
    expect(m.inputHeight).toBeGreaterThanOrEqual(48);
    expect(m.inputFont).toBeGreaterThanOrEqual(16);
    expect(m.btnHeight).toBeGreaterThanOrEqual(48);
    expect(Math.round(m.btnWidth)).toBe(Math.round(m.formWidth));
    expect(m.textWidth).toBeLessThanOrEqual(Math.ceil(m.btnContentWidth));
    expect(m.visibleButtons).toBe(1);
    expect(m.countBelowButton).toBe(true);
    expect(m.consentBelowCount).toBe(true);
    expect(m.noteBelowConsent).toBe(true);
    expect(m.noteIsLast).toBe(true);
    expect(m.noteStyle).toEqual(m.consentStyle);
    expect(m.consentFont).toBe(11); // never below its size
    expect(m.consentAlign).toBe('left');
    expect(await expectPig(page)).toBe(COPY.BUTTON);
    // The mocked stats answer 40, at or above STAT_TILE_MIN_N (25): the live count line shows.
    await expect(page.locator('#capture-count')).toBeVisible();
    await expect(page.locator('#capture-count')).toHaveText('40 people are already on the list.');
    await expect(page.locator('#consent-note')).toContainText('By signing up for our waitlist');
    await expect(page.locator('#capture-cta-note p').nth(0)).toHaveText('No payment until launch. You only pay if invited and choose to claim your spot.');
    await expect(page.locator('#capture-cta-note p').nth(1)).toHaveText('The private beta is currently iOS only.');
    // A visible focus indicator on the white field: the primary border and ring, not white on white.
    await input.focus();
    await page.waitForTimeout(650); // the input's colour transition is 500 ms
    const focusBorder = await input.evaluate((el) => getComputedStyle(el).borderColor);
    expect(focusBorder).not.toBe('rgb(255, 255, 255)');
    expect(focusBorder).toBe('rgb(19, 91, 236)');
  });

  test('the pig is on the button in every state: plan, lever, still-moves, no date, at target', async ({ page }) => {
    await installMocks(page);
    await page.goto('/index.html');
    await reveal(page);
    expect(await expectPig(page)).toBe(COPY.BUTTON); // plan
    await page.fill('#age-range', '38');
    await page.fill('#invest-range', '1000');
    await page.fill('#retire-range', '5000');
    await expect(page.locator('#compare-right-label')).toHaveText('This plan + $250/mo');
    expect(await expectPig(page)).toBe(COPY.BUTTON); // lever
    await page.fill('#age-range', '42');
    await page.fill('#invest-range', '500');
    await page.fill('#retire-range', '4000');
    await expectCollapsed(page); // still-moves, Save my Freedom Date
    await page.fill('#invest-range', '0');
    await expect(page.locator('#infeasible-block')).toBeVisible();
    expect(await expectPig(page)).toBe(COPY.BUTTON_SAVE); // no date
    await page.fill('#age-range', '30');
    await page.fill('#invest-range', '2000');
    await page.fill('#retire-range', '1000');
    await page.fill('#existing-range', '48'); // $300,000, the target
    await expect(page.locator('#at-target-block')).toBeVisible();
    expect(await expectPig(page)).toBe(COPY.BUTTON_SAVE); // at target
    await expect(page.locator('input[type="email"]')).toHaveCount(1);
  });

  test('anchor links to #join land on the form', async ({ page }) => {
    await installMocks(page);
    await page.goto('/index.html');
    await reveal(page);
    await expect(page.locator('#join #waitlist-form')).toHaveCount(1);
    // Let the reveal's own smooth scroll settle first; a hash set while it is in flight is
    // overridden by it.
    await page.waitForTimeout(1200);
    await page.evaluate(() => { window.scrollTo(0, 0); location.hash = '#join'; });
    await expect.poll(() => page.evaluate(() => {
      const r = document.getElementById('join').getBoundingClientRect();
      return r.top >= -2 && r.top < window.innerHeight;
    })).toBe(true);
  });

  test('a no-date result keeps the form, hides the comparison and the pill, and falls back to the approved capture heading and the save button', async ({ page }) => {
    await installMocks(page);
    await page.goto('/index.html');
    await page.fill('#invest-range', '0');
    await reveal(page);
    await expect(page.locator('#infeasible-block')).toBeVisible();
    await expect(page.locator('#result-date')).toBeHidden();
    await expect(page.locator('#compare')).toBeHidden();
    await expect(page.locator('#stakes-line')).toBeHidden();
    await expect(page.locator('#capture-heading')).toHaveText(NO_DATE_HEADING);
    await expect(page.locator('#capture-support')).toBeHidden();
    await expect(page.locator('#btn-label')).toHaveText(COPY.BUTTON_SAVE);
    expect(await expectPig(page)).toBe(COPY.BUTTON_SAVE);
    await expect(page.locator('#result-assumptions')).toBeHidden();
    await expect(page.locator('#result-fineprint')).toHaveText(COPY.DISCLAIMER);
    await expect(page.locator('input[type="email"]')).toHaveCount(1);
    // Back to a date: the argument and the pill return.
    await page.fill('#invest-range', '2000');
    await expect(page.locator('#capture-heading')).toHaveText(COPY.HEADLINE);
    await expect(page.locator('#compare')).toBeVisible();
    await expectPill(page);
    await expect(page.locator('#result-fineprint')).toContainText('Assumes a 10%');
  });

  test('the at-target state keeps the form with the approved heading, no comparison and no pill', async ({ page }) => {
    await installMocks(page);
    await page.goto('/index.html');
    await page.fill('#retire-range', '1000');
    await page.fill('#existing-range', '48'); // $300,000, the target
    await reveal(page);
    await expect(page.locator('#at-target-block')).toBeVisible();
    await expect(page.locator('#compare')).toBeHidden();
    await expect(page.locator('#result-date')).toBeHidden();
    await expect(page.locator('#capture-heading')).toHaveText(NO_DATE_HEADING);
    await expect(page.locator('#btn-label')).toHaveText(COPY.BUTTON_SAVE);
    await expect(page.locator('#capture-email')).toBeVisible();
  });

  test('the submit flow is unchanged: the pending block replaces the form inside the reveal card and the note goes with the form', async ({ page }) => {
    const calls = await installMocks(page);
    await page.goto('/index.html');
    await submitWaitlist(page, 'moved@example.com');
    await expect(page.locator('#result-card #join #pending-block')).toBeVisible({ timeout: 10_000 });
    await expect(page.locator('#waitlist-form')).toBeHidden();
    await expect(page.locator('#capture-cta-note')).toBeHidden();
    // The pitch goes with the form: no "FRED exists to close the gap." above "you got mail".
    await expect(page.locator('#capture-pitch')).toBeHidden();
    await expect(page.locator('#capture-heading')).toBeHidden();
    await expect(page.locator('#compare')).toBeVisible();
    await expect(page.locator('#result-date')).toBeVisible();
    await expect(page.locator('#capture-count')).toBeVisible();
    expect(calls.signup[0]).toMatchObject({ email: 'moved@example.com', freedomAge: 53, revealedBeforeSubmit: true });
    // "Use a different one" brings the form, the pitch, the pig and the note back.
    await page.click('#pending-reset');
    await expect(page.locator('#waitlist-form')).toBeVisible();
    await expect(page.locator('#capture-pitch')).toBeVisible();
    await expect(page.locator('#capture-heading')).toHaveText(COPY.HEADLINE);
    await expect(page.locator('#capture-cta-note')).toBeVisible();
    await expect(page.locator('#btn-loading')).toBeHidden();
    expect(await expectPig(page)).toBe(COPY.BUTTON);
  });

  test('a returning visitor gets the pill, the comparison and the success state mounted on load for the current inputs', async ({ page }) => {
    await seedStorage(page, { waitlist_status: 'WAITLISTED' });
    await installMocks(page);
    await page.goto('/index.html');
    await expect(page.locator('#reveal-zone')).toHaveClass(/open/);
    await expect(page.locator('#success-block')).toBeVisible();
    await expect(page.locator('#waitlist-form')).toBeHidden();
    await expect(page.locator('#capture-pitch')).toBeHidden();
    await expect(page.locator('#compare')).toBeVisible();
    await expect(page.locator('#compare-right-age')).toHaveText('53');
    await expectPill(page);
    await expect(page.locator('#levers-card')).toHaveCount(0);
    await expect(page.locator('input[type="email"]')).toHaveCount(1);
  });
});

test.describe('copy', () => {
  test('the exact frozen sentences, no em dash, no forbidden words, nothing of the removed card, and a single disclaimer under the result', async ({ page }) => {
    await installMocks(page);
    await page.goto('/index.html');
    await reveal(page);
    const argument = await argumentText(page);
    for (const re of FORBIDDEN) expect(argument).not.toMatch(re);
    const whole = await page.locator('#reveal-zone').innerText();
    expect(whole).not.toContain(EM_DASH);
    for (const s of GONE) expect(whole, s).not.toContain(s);
    // One disclosure near the output (the attorney-approved sentence, in the merged fine-print
    // block); nothing else in the zone outside the accordion repeats it.
    const accordion = await page.locator('#calc-method').innerText();
    const outsideAccordion = whole.replace(accordion, '');
    expect(outsideAccordion.match(/Hypothetical illustration/g) || []).toHaveLength(1);
    await expect(page.locator('#result-fineprint')).toContainText(COPY.DISCLAIMER);
    // The fine print carries the scenario's return once, with the word "guarantee" only inside the approved sentence.
    const fine = await page.locator('#result-fineprint').innerText();
    expect(fine.match(/\d+\s*%/g)).toEqual(['10%', '4%']);
    expect(fine.match(/guarantee/g)).toHaveLength(1);
  });

  test('the screen-reader summary keeps the month and year and the stakes sentence, with no double stop', async ({ page }) => {
    await installMocks(page);
    await page.goto('/index.html');
    await reveal(page);
    const sr = await page.locator('#result-sr').textContent();
    expect(sr).toMatch(/^You could be free at 53 years old\. Freedom Date: [A-Z][a-z]+ \d{4}\. Do nothing: 65\+\. Stick to this plan: 53\. That gap is 12 years of your life\. Projected portfolio target: \$2,250,000\. Assumes a 10% average annual return \(Moderate scenario\) and 4% withdrawal rate\. Hypothetical illustration/);
    expect(sr).not.toContain('..');
    expect(sr).not.toContain('before the usual 65');
    // 69 on a large balance, nothing to compare: just the age, the date and the fine print.
    await page.fill('#age-range', '60');
    await page.fill('#invest-range', '50');
    await page.fill('#existing-range', '72'); // $900,000
    await expect(page.locator('#result-age')).toHaveText('69');
    await expectPill(page);
    await expect(page.locator('#compare')).toBeHidden();
    const sr2 = await page.locator('#result-sr').textContent();
    expect(sr2).toMatch(/^You could be free at 69 years old\. Freedom Date: /);
    expect(sr2).not.toContain('of your life');
    expect(sr2).not.toContain('..');
  });

  test('at 64 nothing counts the years before 65 any more: "years old" and the pill, no context line', async ({ page }) => {
    await installMocks(page);
    await page.goto('/index.html');
    await page.fill('#age-range', '41');
    await reveal(page);
    await expect(page.locator('#result-age')).toHaveText('64');
    await expectPill(page);
    expect(await revealText(page)).not.toMatch(/1 year\b/);
  });
});

test.describe('analytics', () => {
  const STAKE_BANDS = ['hidden', '2-5', '6-10', '11+'];
  const STAKE_SOURCES = ['plan', 'lever', 'hidden'];
  const DEFAULT_STAKE = { stake_years_band: '11+', stake_source: 'plan' };
  const HIDDEN_STAKE = { stake_years_band: 'hidden', stake_source: 'hidden' };
  const expectNoLeverTraces = (all) => {
    expect(named(all, 'Lever Viewed')).toEqual([]);
    for (const e of all) for (const k of LEVER_KEYS) expect(e.props, `${e.name}.${k}`).not.toHaveProperty(k);
  };

  test('stake_years_band and stake_source ride on Freedom Date Revealed, Email Focused and Waitlist Submitted, banded, and on nothing else; no lever bands anywhere', async ({ page }) => {
    await forceAnalytics(page);
    await installMocks(page);
    await page.goto('/index.html');
    await reveal(page);
    await submitWaitlist(page, 'stake@example.com');
    await expect(page.locator('#pending-block')).toBeVisible({ timeout: 10_000 });
    const all = await events(page);
    expect(named(all, 'Freedom Date Revealed')[0].props).toMatchObject({ freedom_age_band: '50-54', ...DEFAULT_STAKE });
    expect(named(all, 'Email Focused')[0].props).toEqual(DEFAULT_STAKE);
    expect(named(all, 'Waitlist Submitted')[0].props).toMatchObject({ revealed: 'yes', ...DEFAULT_STAKE });
    // No new goals (untouched sliders: no Calc Engaged), and no Lever Viewed.
    expect(new Set(all.map((e) => e.name))).toEqual(new Set(['Freedom Date Revealed', 'Email Focused', 'Waitlist Submitted']));
    for (const e of all) {
      if (['Freedom Date Revealed', 'Email Focused', 'Waitlist Submitted'].includes(e.name)) {
        expect(STAKE_BANDS).toContain(e.props.stake_years_band);
        expect(STAKE_SOURCES).toContain(e.props.stake_source);
      } else {
        expect(e.props).not.toHaveProperty('stake_years_band');
        expect(e.props).not.toHaveProperty('stake_source');
      }
    }
    expectNoLeverTraces(all);
    // The twelve years never travel as a bare number.
    expect(JSON.stringify(all)).not.toContain('"12"');
    // And the banded view the page exposes has no lever keys either.
    const ctx = await page.evaluate(() => window.FredAnalytics.getCalcContext());
    expect(Object.keys(ctx).sort()).toEqual(['freedom_age_band', 'input_edits_band', 'return_scenario', 'revealed', 'stake_source', 'stake_years_band']);
  });

  test('the lever path is reported as stake_source lever, the hidden comparison as hidden, the balance case as plan 6-10', async ({ page }) => {
    await forceAnalytics(page);
    await installMocks(page);
    await page.goto('/index.html');
    // 38 / $1,000 / $5,000: 64, lever 62, three years from the lever.
    await page.fill('#age-range', '38');
    await page.fill('#invest-range', '1000');
    await page.fill('#retire-range', '5000');
    await reveal(page);
    expect(named(await events(page), 'Freedom Date Revealed')[0].props).toMatchObject({ freedom_age_band: '60-64', stake_years_band: '2-5', stake_source: 'lever' });
    // 42 / $500 / $4,000: 73, nothing to compare.
    await page.fill('#age-range', '42');
    await page.fill('#invest-range', '500');
    await page.fill('#retire-range', '4000');
    await page.click('#reveal-btn');
    await expectCollapsed(page);
    // $250,000 at the defaults: 52 against 46 from the plan itself.
    await page.fill('#age-range', '30');
    await page.fill('#invest-range', '2000');
    await page.fill('#retire-range', '7500');
    await page.fill('#existing-range', '46');
    await page.click('#reveal-btn'); // third click: no Recalculated, context still recorded
    await expect(page.locator('#compare-left-age')).toHaveText('52');
    await submitWaitlist(page, 'plan@example.com');
    await expect(page.locator('#pending-block')).toBeVisible({ timeout: 10_000 });
    const all = await events(page);
    expect(named(all, 'Recalculated')).toHaveLength(1);
    expect(named(all, 'Email Focused')[0].props).toEqual({ stake_years_band: '6-10', stake_source: 'plan' });
    expect(named(all, 'Waitlist Submitted')[0].props).toMatchObject({ return_scenario: 'moderate', freedom_age_band: '45-49', stake_years_band: '6-10', stake_source: 'plan' });
    expectNoLeverTraces(all);
    const raw = ['250000', '46', '47', '52', '6', '3', '62', '64'];
    for (const e of all) for (const v of Object.values(e.props)) expect(raw).not.toContain(v);
  });

  test('a hidden comparison (and no date at all) bands as hidden on all three events', async ({ page }) => {
    await forceAnalytics(page);
    await installMocks(page);
    await page.goto('/index.html');
    await page.fill('#existing-range', '76'); // $1,000,000: 37 against 38, no lever step
    await reveal(page);
    await expectCollapsed(page);
    await submitWaitlist(page, 'hidden@example.com');
    await expect(page.locator('#pending-block')).toBeVisible({ timeout: 10_000 });
    const all = await events(page);
    expect(named(all, 'Freedom Date Revealed')[0].props).toMatchObject(HIDDEN_STAKE);
    expect(named(all, 'Email Focused')[0].props).toEqual(HIDDEN_STAKE);
    expect(named(all, 'Waitlist Submitted')[0].props).toMatchObject({ revealed: 'yes', freedom_age_band: '<45', ...HIDDEN_STAKE });
    expectNoLeverTraces(all);
    expect(JSON.stringify(all)).not.toContain('1000000');
  });

  test('scrolling the whole page after a reveal, a recalculate and live edits fires no Lever Viewed and no lever band', async ({ page }) => {
    await forceAnalytics(page);
    await installMocks(page);
    await page.setViewportSize(PHONE);
    await page.goto('/index.html');
    await reveal(page);
    await page.waitForFunction(() => document.getElementById('reveal-zone').style.maxHeight === 'none');
    await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
    await page.waitForTimeout(400);
    await page.fill('#invest-range', '500');
    await page.click('#reveal-btn');
    await page.waitForTimeout(800);
    await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
    await page.fill('#existing-range', '46');
    await page.waitForTimeout(400);
    await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
    await page.waitForTimeout(400);
    const all = await events(page);
    expect(new Set(all.map((e) => e.name))).toEqual(new Set(['Freedom Date Revealed', 'Calc Engaged', 'Recalculated']));
    expectNoLeverTraces(all);
  });

  test('a full post-reveal journey talks only to our origin and Turnstile, and never requests the pig file', async ({ page }) => {
    await forceAnalytics(page);
    await installMocks(page);
    const seen = collectHosts(page);
    const svgRequests = [];
    page.on('request', (r) => { if (/\.svg(\?|$)/i.test(r.url()) || /pig/i.test(r.url())) svgRequests.push(r.url()); });
    await page.goto('/index.html');
    await reveal(page);
    await page.waitForFunction(() => document.getElementById('reveal-zone').style.maxHeight === 'none');
    await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
    await submitWaitlist(page, 'hosts@example.com');
    await expect(page.locator('#pending-block')).toBeVisible({ timeout: 10_000 });
    await page.waitForLoadState('networkidle');
    assertOnlyAllowedHosts(seen);
    expect(svgRequests).toEqual([]);
  });

  // The count line's traffic is exactly what it was before 2026-10-24 (restored 2026-10-25): one
  // stats call on load, one more after a successful signup, and nothing else before submit.
  test('index.html calls the stats endpoint once on load and once after a signup, and nothing else before submit', async ({ page }) => {
    const calls = await installMocks(page);
    const statsAnswered = page.waitForResponse((r) => /\/api\/waitlist\/stats/.test(r.url()));
    await page.goto('/index.html');
    await statsAnswered;
    await reveal(page);
    await page.waitForLoadState('networkidle');
    expect(calls.stats).toBe(1);
    expect(calls.signup).toHaveLength(0);
    await submitWaitlist(page, 'only-submit@example.com');
    await expect(page.locator('#pending-block')).toBeVisible({ timeout: 10_000 });
    await expect.poll(() => calls.stats).toBe(2);
    expect(calls.signup).toHaveLength(1);
  });
});
