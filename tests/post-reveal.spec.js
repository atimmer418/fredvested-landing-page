// Post-reveal conversion. 2026-10-25: the waitlist form moved into the reveal card itself, under a
// "do nothing vs this plan" comparison computed from the visitor's inputs. 2026-10-26: the
// "Freedom Date · Month Year" pill is back under "years old", the "what moves your date" card and
// its Lever Viewed event are gone, the capture copy changed, the submit button is label + arrow (pig 2026-10-26..29) and (2026-10-28)
// FRED the presenter stands beside the age.
// 2026-10-27: Andrew's wording of the stakes line and the headline ("That gap is N years of your
// life." / "FRED exists to close the gap.").
// Nothing of the comparison or the form exists in the DOM before the reveal; after it there is
// exactly one email input on the page. Copy is frozen (COPY in index.html) and checked verbatim
// here so a reworded sentence fails before counsel sees it.
//
// Reference numbers (frontend/assets/projection.js, checked in Node; moderate 10% unless said):
//   defaults 30 / $2,000 / $7,500 / $0:          53; do nothing: 65+; N 12
//   42 / $500 / $4,000 / $0:                      73; do nothing 65+ (N -8); +$100 -> 71 (N -6): no gap, "No date" left (2026-11-01)
//   30 / $2,000 / $7,500 / $250,000 (tick 46):    46; do nothing on the balance alone: 52; N 6
//   30 / $2,000 / $7,500 / $1,000,000 (tick 76):  37; N 28 (2026-10-29: "do nothing" is always 65+)
//   38 / $1,000 / $5,000 / $0:                    64; N 1, "1 year" (the lever rung of 2026-10-25 is gone)
//   41 / $2,000 / $7,500 / $0:                    64; N 1
//   35 / $1,000 / $7,500 / $0:                    65; N 0: no gap, "No date" left, "Hope isn't a plan. <month year> is." 
//   30 / $500 / $7,500 / $0 (recalculate):        67; do nothing 65+ (N -2); +$100 -> 65 (N 0): no gap, "No date" left
const { test, expect } = require('@playwright/test');
const { installMocks, forceAnalytics, events, eventNames, collectHosts, assertOnlyAllowedHosts, submitWaitlist, seedStorage } = require('./helpers');

const RSQUO = String.fromCharCode(0x2019);
const COPY = {
  YEARS_OLD: 'years old',
  COMPARE_LEFT_LABEL: 'Do nothing.',
  COMPARE_LEFT_NEVER: '65+',
  COMPARE_LEFT_CAPTION_NEVER: 'the usual retirement',
  COMPARE_RIGHT_LABEL: `On FRED${RSQUO}s route.`,
  HEADLINE: 'FRED exists to close the gap.',
  SUB: `Save your Freedom Date and we${RSQUO}ll help you turn it from a number into a plan the day we open.`,
  BUTTON: 'I want FRED\u2019s help',
  // 65 and later (2026-11-01): the comparison stays, "No date" on the left, and these three take the slots.
  COMPARE_LEFT_NO_DATE: 'No date',
  STAKES_LATE: (date) => `Hope isn${RSQUO}t a plan. ${date} is.`, // one line from 360px with the longest month
  HEADLINE_LATE: 'FRED exists to pull it closer.',
  SUB_LATE: `Save your Freedom Date and we${RSQUO}ll show you what brings it closer the day we open.`, // Andrew's edit, 2026-11-01
  BUTTON_SAVE: 'Save my Freedom Date', // the no-date states only
  // The at-target sub line (2026-10-30 trim of the 2026-10-22 attorney-pending sentence).
  AT_TARGET_BODY: 'Based on your inputs, you meet the target. Not an assessment of whether you can retire.',
  EMAIL_LABEL: 'Email',
  DISCLAIMER: 'Hypothetical illustration based on the assumptions shown, not a prediction or guarantee. Actual returns may vary.',
};
const stakes = (n) => (n === 1 ? 'That gap is 1 year of your life.' : `That gap is ${n} years of your life.`);
const NO_DATE_HEADING = 'Put your financial future on autopilot.';
const DATE = /^[A-Z][a-z]+ \d{4}$/;
const EM_DASH = String.fromCharCode(0x2014);
// Words the feature may never render (spec rule); "FRED gets you" and any percentage return included
// (the fine-print block carries the scenario's return and is checked separately).
const FORBIDDEN = [/guarantee/i, /will retire/i, /FRED gets you/i, /\d+\s*%/];
// Sentences of the 2026-10-24 / 2026-10-25 rounds that must not come back.
const GONE = ['This date is not fixed', 'Invest $', 'Start one year later', 'Freedom Date moves to', 'Freedom Date slips to', 'before the usual 65', 'on the table', 'Keep my', 'That is ', 'This plan + $', 'on what you already have', 'This date can still move', 'your Freedom Date\n'];
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
/** The button (2026-10-29): the label and a decorative arrow after it, no pig, no image. Returns the label text. */
async function expectArrow(page) {
  const btn = page.locator('#join-waitlist-btn');
  await expect(btn.locator('svg')).toHaveCount(0);
  await expect(btn.locator('img')).toHaveCount(0);
  const arrow = btn.locator('#btn-text > span[aria-hidden="true"]');
  await expect(arrow).toHaveCount(1);
  await expect(arrow).toHaveText('\u2192');
  // The arrow slides 4px on hover, and after the reveal click the mouse can be resting on the button
  // (it is at 1280, where the scroll lands the form under it): park the pointer and let the slide settle.
  // Tailwind v4's translate-x-1 is the CSS `translate` property (transform stays "none" throughout), so that is
  // the one to watch until the 150ms slide back has ended.
  await page.mouse.move(0, 0);
  await expect.poll(() => page.evaluate(() => { const s = getComputedStyle(document.querySelector('#btn-text > span[aria-hidden="true"]')); return s.translate + ' ' + s.transform; })).toBe('none none');
  const m = await page.evaluate(() => {
    const a = document.querySelector('#btn-text > span[aria-hidden="true"]');
    const l = document.getElementById('btn-label');
    const b = document.getElementById('join-waitlist-btn');
    const ar = a.getBoundingClientRect(); const lr = l.getBoundingClientRect(); const br = b.getBoundingClientRect();
    const cs = getComputedStyle(b);
    // Client rects, exact to the subpixel (offsetLeft / offsetWidth round and read 9 for an 8px gap next to a
    // fractional label width); the hover slide is settled above, so the rects are the layout positions.
    const gap = ar.left - lr.right;
    return {
      gap,
      rightOfText: gap >= 0,
      centred: Math.abs((ar.top + ar.bottom) / 2 - (br.top + br.bottom) / 2),
      labelLines: Math.round(lr.height / parseFloat(getComputedStyle(l).lineHeight)),
      // Label, gap and arrow inside the button's content box: nothing clipped by overflow-hidden.
      fits: ar.right - lr.left <= b.clientWidth - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight) + 0.5,
      label: l.textContent.trim(),
      name: b.getAttribute('aria-label') || b.innerText.trim(),
    };
  });
  expect(Math.round(m.gap)).toBe(8);
  expect(m.rightOfText).toBe(true);
  expect(m.centred).toBeLessThanOrEqual(2);
  expect(m.labelLines).toBe(1);
  expect(m.fits).toBe(true);
  return m.label;
}
async function expectComparison(page, { left, leftCaption, rightLabel, right, n }) {
  await expect(page.locator('#compare')).toBeVisible();
  await expect(page.locator('#compare-left-label')).toHaveText(COPY.COMPARE_LEFT_LABEL);
  await expect(page.locator('#compare-left-age')).toHaveText(left);
  await expect(page.locator('#compare-left-caption')).toHaveText(leftCaption);
  await expect(page.locator('#compare-right-label')).toHaveText(rightLabel);
  await expect(page.locator('#compare-right-age')).toHaveText(right);
  await expect(page.locator('#stakes-line')).toHaveText(stakes(n));
  await expect(page.locator('#capture-heading')).toHaveText(COPY.HEADLINE);
  await expect(page.locator('#capture-support')).toHaveText(COPY.SUB);
  await expect(page.locator('#btn-label')).toHaveText(COPY.BUTTON);
  expect(await expectArrow(page)).toBe(COPY.BUTTON);
}
/** The plan's age is 65 or later (2026-11-01): the same card as a dated reveal, with "No date" in the left cell,
 *  the plan's age and date on the right, no gap counted, and the late copy in the three slots. */
async function expectLate(page, age) {
  await expect(page.locator('#result-age')).toHaveText(String(age));
  await expect(page.locator('#compare')).toBeVisible();
  await expect(page.locator('#compare-left-label')).toHaveText(COPY.COMPARE_LEFT_LABEL);
  await expect(page.locator('#compare-left-age')).toHaveText(COPY.COMPARE_LEFT_NO_DATE);
  await expect(page.locator('#compare-left-caption')).toHaveText(COPY.COMPARE_LEFT_CAPTION_NEVER);
  await expect(page.locator('#compare-right-label')).toHaveText(COPY.COMPARE_RIGHT_LABEL);
  await expect(page.locator('#compare-right-age')).toHaveText(String(age));
  await expect(page.locator('#compare-right-date')).toHaveText(DATE);
  const date = await page.locator('#compare-right-date').textContent();
  await expect(page.locator('#stakes-line')).toBeVisible();
  await expect(page.locator('#stakes-line')).toHaveText(COPY.STAKES_LATE(date));
  await expect(page.locator('#capture-heading')).toHaveText(COPY.HEADLINE_LATE);
  await expect(page.locator('#capture-support')).toHaveText(COPY.SUB_LATE);
  await expect(page.locator('#btn-label')).toHaveText(COPY.BUTTON);
  expect(await expectArrow(page)).toBe(COPY.BUTTON);
  // The live region hears the cells and the line as rendered.
  expect(await page.locator('#result-sr').textContent()).toContain(`Do nothing: No date. On FRED${RSQUO}s route: ${age}, ${date}. ${COPY.STAKES_LATE(date)} `);
}
/** The month and year: in the comparison's right cell under the age (the plan's Freedom Date), bold primary;
 *  nowhere else on the card, always in #result-sr. No chip, no header row. */
async function expectDate(page) {
  await expect(page.locator('#result-date, #result-chip, #result-context, #compare-right-caption')).toHaveCount(0);
  await expect(page.locator('#result-years-old')).toHaveText(COPY.YEARS_OLD);
  const sr = await page.locator('#result-sr').textContent();
  const srDate = sr.match(/Freedom Date: ([A-Z][a-z]+ \d{4})\./);
  expect(srDate, 'month and year in the screen-reader summary').not.toBeNull();
  const cell = page.locator('#compare-right-date');
  if (await page.locator('#compare').isHidden()) {
    await expect(cell).toBeHidden();
    return;
  }
  await expect(cell).toBeVisible();
  await expect(cell).toHaveText(DATE);
  const m = await page.evaluate(() => {
    const d = document.getElementById('compare-right-date');
    const a = document.getElementById('compare-right-age');
    const l = document.getElementById('compare-right-label');
    const box = a.parentElement;
    const ds = getComputedStyle(d), dr = d.getBoundingClientRect(), ar = a.getBoundingClientRect(), br = box.getBoundingClientRect();
    return {
      label: l.textContent, text: d.textContent,
      underAge: dr.top >= ar.bottom, insideCell: dr.left >= br.left && dr.right <= br.right && dr.bottom <= br.bottom,
      centred: Math.abs((dr.left + dr.right) / 2 - (br.left + br.right) / 2),
      style: [ds.fontSize, ds.fontWeight, ds.color], lastInCell: box.lastElementChild === d,
    };
  });
  expect(m.underAge).toBe(true);
  expect(m.insideCell).toBe(true);
  expect(m.centred).toBeLessThanOrEqual(1);
  expect(m.lastInCell).toBe(true);
  expect(m.style).toEqual(['11.5px', '700', 'rgb(19, 91, 236)']);
  expect(m.label).toBe(COPY.COMPARE_RIGHT_LABEL);
  expect(m.text).toBe(srDate[1]); // the plan's own date
  expect(sr).toContain(m.label.replace(/\.$/, '') + ': ' + (await page.locator('#compare-right-age').textContent()) + ', ' + m.text + '.');
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
      // The month and year live in the comparison, which does not exist yet; no other date element does either.
      await expect(page.locator('#compare-right-date, #result-date, #result-chip')).toHaveCount(0);
    });

    test('defaults (53): "years old", 65+ against 53 with its month and year, twelve years, the form inside the reveal card, one email input, no lever card', async ({ page }) => {
      await installMocks(page);
      await page.goto('/index.html');
      await reveal(page);
      await expect(page.locator('#result-age')).toHaveText('53');
      await expectDate(page);
      await expectComparison(page, { left: '65+', leftCaption: COPY.COMPARE_LEFT_CAPTION_NEVER, rightLabel: COPY.COMPARE_RIGHT_LABEL, right: '53', n: 12 });
      await expect(page.locator('input[type="email"]')).toHaveCount(1);
      await expect(page.locator('#result-card #capture-email')).toHaveCount(1);
      await expect(page.locator('#result-card #join')).toHaveCount(1);
      await expect(page.locator('#result-fineprint')).toHaveText('Assumes a 10% avg annual return (Moderate scenario) & 4% withdrawal rate. Projected portfolio target: $2,250,000. ' + COPY.DISCLAIMER);
      await expect(page.locator('#levers-card')).toHaveCount(0);
      await expect(page.locator('#levers-heading, #lever-row, #delay-row, #lever-text, #delay-text')).toHaveCount(0);

      // Order top to bottom inside the card, then the accordion.
      const tops = await page.evaluate(() => ['result-eyebrow', 'result-age', 'result-years-old', 'compare', 'stakes-line', 'capture-heading', 'capture-support', 'capture-email', 'join-waitlist-btn', 'capture-count', 'consent-note', 'capture-cta-note', 'result-fineprint', 'calc-method']
        .map((id) => document.getElementById(id).getBoundingClientRect().top));
      for (let i = 1; i < tops.length; i++) expect(tops[i], `order at ${i}`).toBeGreaterThan(tops[i - 1]);
      const cardBottom = await page.evaluate(() => document.getElementById('result-card').getBoundingClientRect().bottom);
      expect(tops[12]).toBeLessThan(cardBottom); // fine print inside the card
      expect(tops[13]).toBeGreaterThanOrEqual(cardBottom); // the accordion directly below it
    });

    test('a large balance never changes the left cell: $250,000 is 65+ against 46, nineteen years (2026-10-29)', async ({ page }) => {
      await installMocks(page);
      await page.goto('/index.html');
      await page.fill('#existing-range', '46'); // $250,000
      await reveal(page);
      await expect(page.locator('#result-age')).toHaveText('46');
      await expectDate(page);
      await expectComparison(page, { left: '65+', leftCaption: COPY.COMPARE_LEFT_CAPTION_NEVER, rightLabel: COPY.COMPARE_RIGHT_LABEL, right: '46', n: 19 });
      await page.fill('#existing-range', '76'); // $1,000,000: 37, still 65+ on the left
      await expect(page.locator('#result-age')).toHaveText('37');
      await expectComparison(page, { left: '65+', leftCaption: COPY.COMPARE_LEFT_CAPTION_NEVER, rightLabel: COPY.COMPARE_RIGHT_LABEL, right: '37', n: 28 });
    });

    test('at 64 the comparison shows the plan itself with a one-year gap, singular; no "This plan + $250/mo"', async ({ page }) => {
      await installMocks(page);
      await page.goto('/index.html');
      await page.fill('#age-range', '38');
      await page.fill('#invest-range', '1000');
      await page.fill('#retire-range', '5000');
      await reveal(page);
      await expect(page.locator('#result-age')).toHaveText('64');
      await expectDate(page);
      await expectComparison(page, { left: '65+', leftCaption: COPY.COMPARE_LEFT_CAPTION_NEVER, rightLabel: COPY.COMPARE_RIGHT_LABEL, right: '64', n: 1 });
      await expect(page.locator('#stakes-line')).toHaveText('That gap is 1 year of your life.');
      expect(await revealText(page)).not.toMatch(/1 years\b/);
      // The two ages stay level (subgrid rows).
      const tops = await page.evaluate(() => ['compare-left-age', 'compare-right-age'].map((id) => Math.round(document.getElementById(id).getBoundingClientRect().top)));
      expect(tops[0]).toBe(tops[1]);
      // The live region hears the number with what it is measured against.
      expect(await page.locator('#result-sr').textContent()).toMatch(new RegExp('Do nothing: 65\\+\\. ' + COPY.COMPARE_RIGHT_LABEL.replace(/\.$/, '') + ': 64, [A-Z][a-z]+ \\d{4}\\. ' + stakes(1).replace('.', '\\.')));
    });

    test('at exactly 65 (35 / $1,000 / $7,500) the comparison stays with "No date" on the left and the late copy; at 73 too', async ({ page }) => {
      await installMocks(page);
      await page.goto('/index.html');
      await page.fill('#age-range', '35');
      await page.fill('#invest-range', '1000');
      await reveal(page);
      await expect(page.locator('#result-age')).toHaveText('65');
      await expectDate(page);
      await expectLate(page, 65);
      await page.fill('#age-range', '42');
      await page.fill('#invest-range', '500');
      await page.fill('#retire-range', '4000');
      await expect(page.locator('#result-age')).toHaveText('73');
      await expectLate(page, 73);
    });

    test('when the plan is past 65 (73) no gap is counted: "No date" against 73 and its date, "Hope isn\'t a plan. <date> is.", the dated button', async ({ page }) => {
      await installMocks(page);
      await page.goto('/index.html');
      await page.fill('#age-range', '42');
      await page.fill('#invest-range', '500');
      await page.fill('#retire-range', '4000');
      await reveal(page);
      await expect(page.locator('#result-age')).toHaveText('73');
      await expectDate(page);
      await expectLate(page, 73);
      expect(await argumentText(page)).not.toMatch(/gap|of your life/i);
    });

    test('recalculating rewrites every number and scrolls the card back to the top', async ({ page }) => {
      await installMocks(page);
      await page.goto('/index.html');
      await reveal(page);
      await expect(page.locator('#btn-label')).toHaveText(COPY.BUTTON);
      await expect.poll(async () => Math.round((await rects(page)).card.top)).toBe(8);
      const firstDate = (await page.locator('#result-sr').textContent()).match(/Freedom Date: ([^.]+)\./)[1];

      await page.evaluate(() => window.scrollTo(0, 0));
      await page.fill('#invest-range', '500');
      await page.fill('#retire-range', '7000'); // 30 / $500 / $7,000: 66, target $2.1M, no gap to count ("No date" left)
      await page.click('#reveal-btn'); // Recalculate
      await expect(page.locator('#result-age')).toHaveText('66');
      await expectDate(page);
      expect((await page.locator('#result-sr').textContent()).match(/Freedom Date: ([^.]+)\./)[1]).not.toBe(firstDate);
      await expectLate(page, 66);
      await expect(page.locator('#result-fineprint')).toContainText('Projected portfolio target: $2,100,000.');
      await expect.poll(async () => Math.round((await rects(page)).card.top), { timeout: 3000 }).toBe(8);

      // Back to a comparison: a one-year gap (41: 64).
      await page.fill('#invest-range', '2000');
      await page.fill('#retire-range', '7500');
      await page.fill('#age-range', '41');
      await page.click('#reveal-btn');
      await expect(page.locator('#result-age')).toHaveText('64');
      await expectComparison(page, { left: '65+', leftCaption: COPY.COMPARE_LEFT_CAPTION_NEVER, rightLabel: COPY.COMPARE_RIGHT_LABEL, right: '64', n: 1 });

      // Live slider edits keep everything current too (same path as a drag): $1,000,000, 65+ against 37.
      await page.fill('#age-range', '30');
      await page.locator('#existing-range').evaluate((el) => { el.value = '76'; el.dispatchEvent(new Event('input', { bubbles: true })); });
      await expect(page.locator('#result-age')).toHaveText('37');
      await expectComparison(page, { left: '65+', leftCaption: COPY.COMPARE_LEFT_CAPTION_NEVER, rightLabel: COPY.COMPARE_RIGHT_LABEL, right: '37', n: 28 });
      await expect(page.locator('input[type="email"]')).toHaveCount(1);
    });

    test('nothing scrolls sideways and every new element stays inside the viewport width', async ({ page }) => {
      await installMocks(page);
      await page.goto('/index.html');
      await reveal(page);
      const r = await page.evaluate(() => {
        const ids = ['result-card', 'compare-right-date', 'compare', 'stakes-line', 'capture-heading', 'capture-support', 'join', 'capture-email', 'join-waitlist-btn', 'btn-text', 'consent-note', 'capture-cta-note', 'result-fineprint'];
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
    await expectDate(page);
  });

  test('the same holds for the one-year case (65+ against 64, singular stakes line) and the 65-and-later case', async ({ page }) => {
    await installMocks(page);
    await page.goto('/index.html');
    await page.fill('#age-range', '38');
    await page.fill('#invest-range', '1000');
    await page.fill('#retire-range', '5000');
    await reveal(page);
    await expect(page.locator('#stakes-line')).toHaveText(stakes(1));
    await expect.poll(async () => Math.round((await rects(page)).card.top), { timeout: 3000 }).toBe(8);
    let r = await rects(page);
    expect(r.button.bottom).toBeLessThanOrEqual(r.vh);

    await page.evaluate(() => window.scrollTo(0, 0));
    await page.fill('#age-range', '42');
    await page.fill('#invest-range', '500');
    await page.fill('#retire-range', '4000');
    await page.click('#reveal-btn');
    await expectLate(page, 73);
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
        // The label, the gap and the arrow inside the button's content box (its padding untouched).
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
    expect(await expectArrow(page)).toBe(COPY.BUTTON);
    // The mocked stats answer 40, at or above STAT_TILE_MIN_N (25): the live count line shows.
    await expect(page.locator('#capture-count')).toBeVisible();
    await expect(page.locator('#capture-count')).toHaveText('40 people are already on the list.');
    await expect(page.locator('#consent-note')).toContainText('By signing up for our waitlist');
    // One paragraph since 2026-10-31 (Andrew merged the payment sentence and the iOS line).
    await expect(page.locator('#capture-cta-note p')).toHaveCount(1);
    await expect(page.locator('#capture-cta-note p')).toHaveText('No payment until launch. You only pay if invited and choose to claim your spot. The private beta is currently iOS only.');
    // A visible focus indicator on the white field: the primary border and ring, not white on white.
    await input.focus();
    await page.waitForTimeout(650); // the input's colour transition is 500 ms
    const focusBorder = await input.evaluate((el) => getComputedStyle(el).borderColor);
    expect(focusBorder).not.toBe('rgb(255, 255, 255)');
    expect(focusBorder).toBe('rgb(19, 91, 236)');
  });

  test('the button is label + arrow, no pig, in every state: plan, one-year plan, 65 and later, no date, at target', async ({ page }) => {
    await installMocks(page);
    await page.goto('/index.html');
    await reveal(page);
    expect(await expectArrow(page)).toBe(COPY.BUTTON); // plan
    await page.fill('#age-range', '38');
    await page.fill('#invest-range', '1000');
    await page.fill('#retire-range', '5000');
    await expect(page.locator('#stakes-line')).toHaveText(stakes(1));
    expect(await expectArrow(page)).toBe(COPY.BUTTON); // one year
    await page.fill('#age-range', '42');
    await page.fill('#invest-range', '500');
    await page.fill('#retire-range', '4000');
    await expectLate(page, 73); // 65 and later: the dated button too (2026-11-01)
    await page.fill('#invest-range', '0');
    await expect(page.locator('#infeasible-block')).toBeVisible();
    expect(await expectArrow(page)).toBe(COPY.BUTTON_SAVE); // no date
    await page.fill('#age-range', '30');
    await page.fill('#invest-range', '2000');
    await page.fill('#retire-range', '1000');
    await page.fill('#existing-range', '48'); // $300,000, the target
    await expect(page.locator('#at-target-block')).toBeVisible();
    expect(await expectArrow(page)).toBe(COPY.BUTTON); // at target: the same stakes, so the same button (2026-10-30)
    await expect(page.locator('input[type="email"]')).toHaveCount(1);
  });

  test('the label and the arrow fit inside the button at 320, 354, 355, 360, 375, 379, 380 and 390, in the plan state and with the longer "Save my Freedom Date"', async ({ page }) => {
    // The text is centred and the button clips (overflow-hidden), so a spill shows as a cut arrow and a cut first
    // letter. "Save my Freedom Date" + arrow is 221px at 16px against a 200px box at 320 with the old px-6 (and
    // the 2026-10-26..29 pig row, 266px, was clipped at 375 and 360). Now: 16px from 380px, 15px with px-4 from
    // 355px, 14px with px-2 under 355px (Tailwind's max-[355px] is width < 355px); the arrow is there at every width.
    await installMocks(page);
    for (const w of [320, 354, 355, 360, 375, 379, 380, 390]) {
      await page.setViewportSize({ width: w, height: 660 });
      await page.goto('/index.html');
      await reveal(page);
      await expect(page.locator('#btn-label')).toHaveText(COPY.BUTTON);
      expect(await expectArrow(page), `plan label fits at ${w}`).toBe(COPY.BUTTON);
      await page.fill('#invest-range', '0'); // no date: the longer save label
      await expect(page.locator('#btn-label')).toHaveText(COPY.BUTTON_SAVE);
      expect(await expectArrow(page), `save label fits at ${w}`).toBe(COPY.BUTTON_SAVE);
      const m = await page.evaluate(() => {
        const b = document.getElementById('join-waitlist-btn'); const cs = getComputedStyle(b);
        return { font: parseFloat(cs.fontSize), pad: parseFloat(cs.paddingLeft), height: b.getBoundingClientRect().height };
      });
      expect(m.font, `font at ${w}`).toBe(w >= 380 ? 16 : w >= 355 ? 15 : 14);
      expect(m.pad, `padding at ${w}`).toBe(w >= 355 ? 16 : 8);
      expect(m.height, `tap target at ${w}`).toBeGreaterThanOrEqual(48);
    }
  });

  test('FRED stands beside the age at phone and desktop widths: one inline SVG right of the digits, feet on the bottom of the "years old" line, as tall as the column, decorative, still during the count-up, and gone with the age', async ({ page }) => {
    await installMocks(page);
    await page.goto('/index.html');
    await page.click('#reveal-btn');
    // The pig must not move while the digits count up (the age keeps its final width for the animation):
    // sample its left edge every frame for the whole animation, from the click.
    const motion = await page.evaluate(() => new Promise((resolve) => {
      const lefts = []; const t0 = performance.now();
      (function sample() {
        const r = document.getElementById('result-pig').getBoundingClientRect();
        if (r.width > 0) lefts.push(r.left);
        if (performance.now() - t0 < 1300) requestAnimationFrame(sample); else resolve({ min: Math.min(...lefts), max: Math.max(...lefts), frames: lefts.length });
      })();
    }));
    expect(motion.frames).toBeGreaterThan(30);
    expect(motion.max - motion.min, 'pig held still during the count-up').toBeLessThanOrEqual(0.5);
    await expect(page.locator('#capture-email')).toBeVisible();
    // Measure only once the count-up has landed: countUp() removes aria-hidden in finish().
    await expect(page.locator('#result-age')).toHaveText('53');
    await expect(page.locator('#result-age')).not.toHaveAttribute('aria-hidden', 'true');
    const pig = page.locator('#feasible-block #result-age-row > #result-pig');
    await expect(pig).toHaveCount(1);
    await expect(pig).toHaveAttribute('aria-hidden', 'true');
    await expect(pig).toHaveAttribute('focusable', 'false');
    await expect(page.locator('#result-card img')).toHaveCount(0);
    await expect(page.locator('#result-card svg')).toHaveCount(2); // this one and the hidden at-target state's (none on the button since 2026-10-29)
    await expect(page.locator('#result-card svg:visible')).toHaveCount(1);
    for (const viewport of [PHONE, { width: 1280, height: 900 }]) {
      await page.setViewportSize(viewport);
      const m = await page.evaluate(() => {
        const g = (id) => document.getElementById(id).getBoundingClientRect();
        const a = g('result-age'), y = g('result-years-old'), p = g('result-pig'), e = g('result-eyebrow'), c = g('result-card');
        const col = document.getElementById('result-age').parentElement.getBoundingClientRect(); // the age + caption column
        const range = document.createRange(); range.selectNodeContents(document.getElementById('result-age'));
        const digits = range.getBoundingClientRect();
        return {
          gap: p.left - digits.right, feet: p.bottom - y.bottom, heightDiff: p.height - (y.bottom - a.top), belowEyebrow: p.top - e.bottom,
          insideCard: p.left >= c.left && p.right <= c.right, offCentre: (col.left - c.left) - (c.right - p.right),
          captionUnderDigits: (a.left + a.right) / 2 - (y.left + y.right) / 2, pigHeight: p.height,
        };
      });
      const w = viewport.width;
      expect(m.gap, `gap at ${w}`).toBeGreaterThanOrEqual(8);
      expect(m.gap, `gap at ${w}`).toBeLessThanOrEqual(20);
      expect(Math.abs(m.feet), `feet at ${w}`).toBeLessThanOrEqual(1); // on the bottom of the caption's line box
      expect(Math.abs(m.heightDiff), `height at ${w}`).toBeLessThanOrEqual(1); // exactly the column: adds no height
      expect(m.belowEyebrow, `eyebrow clearance at ${w}`).toBeGreaterThanOrEqual(4);
      expect(m.insideCard, `inside the card at ${w}`).toBe(true);
      expect(Math.abs(m.offCentre), `group centred at ${w}`).toBeLessThanOrEqual(2);
      expect(Math.abs(m.captionUnderDigits), `caption under the digits at ${w}`).toBeLessThanOrEqual(2);
      expect(m.pigHeight, `legible at ${w}`).toBeGreaterThanOrEqual(80);
    }
    await page.setViewportSize(PHONE);
    // The height is in rem: with a larger browser default font size the column grows and the pig grows with it.
    await page.addStyleTag({ content: 'html{font-size:20px}' });
    const big = await page.evaluate(() => { const g = (id) => document.getElementById(id).getBoundingClientRect(); const a = g('result-age'), y = g('result-years-old'), p = g('result-pig'); return { heightDiff: p.height - (y.bottom - a.top), feet: p.bottom - y.bottom }; });
    expect(Math.abs(big.heightDiff), 'as tall as the column at a 20px root font size').toBeLessThanOrEqual(1);
    expect(Math.abs(big.feet)).toBeLessThanOrEqual(1);
    await page.evaluate(() => document.querySelector('style:last-of-type').remove());
    // No date, no age, no pig beside it; the button is label + arrow.
    await page.fill('#invest-range', '0');
    await expect(page.locator('#infeasible-block')).toBeVisible();
    await expect(pig).toBeHidden();
    expect(await expectArrow(page)).toBe(COPY.BUTTON_SAVE);
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
    await expect(page.locator('#compare-right-date')).toBeHidden();
    await expect(page.locator('#compare')).toBeHidden();
    await expect(page.locator('#stakes-line')).toBeHidden();
    await expect(page.locator('#capture-heading')).toHaveText(NO_DATE_HEADING);
    await expect(page.locator('#capture-support')).toBeHidden();
    await expect(page.locator('#btn-label')).toHaveText(COPY.BUTTON_SAVE);
    expect(await expectArrow(page)).toBe(COPY.BUTTON_SAVE);
    await expect(page.locator('#result-assumptions')).toBeHidden();
    await expect(page.locator('#result-fineprint')).toHaveText(COPY.DISCLAIMER);
    await expect(page.locator('input[type="email"]')).toHaveCount(1);
    // Back to a date: the argument and the pill return.
    await page.fill('#invest-range', '2000');
    await expect(page.locator('#capture-heading')).toHaveText(COPY.HEADLINE);
    await expect(page.locator('#compare')).toBeVisible();
    await expectDate(page);
    await expect(page.locator('#result-fineprint')).toContainText('Assumes a 10%');
  });

  test('the at-target state is the dated layout: the comparison with "Today" and this month on FRED\'s side, the same stakes line (years to 65) and headline, the at-target sentence as a two-line sub, the same button, no pill', async ({ page }) => {
    await installMocks(page);
    await page.goto('/index.html');
    await page.fill('#retire-range', '1000');
    await page.fill('#existing-range', '48'); // $300,000, the target
    await reveal(page);
    await expect(page.locator('#at-target-block')).toBeVisible();
    await expect(page.locator('#at-target-age')).toHaveText('30');
    await expect(page.locator('#at-target-caption')).toHaveText(COPY.YEARS_OLD);
    // The same fine print as the dated result (2026-11-01): assumptions, the target the sub line names, the disclosure.
    await expect(page.locator('#result-fineprint')).toHaveText(`Assumes a 10% avg annual return (Moderate scenario) & 4% withdrawal rate. Projected portfolio target: $300,000. ${COPY.DISCLAIMER}`);
    const thisMonth = new Date().toLocaleDateString('en-US', { month: 'long', year: 'numeric' });
    await expect(page.locator('#compare')).toBeVisible();
    await expect(page.locator('#compare-left-label')).toHaveText(COPY.COMPARE_LEFT_LABEL);
    await expect(page.locator('#compare-left-age')).toHaveText(COPY.COMPARE_LEFT_NEVER);
    await expect(page.locator('#compare-left-caption')).toHaveText(COPY.COMPARE_LEFT_CAPTION_NEVER);
    await expect(page.locator('#compare-right-label')).toHaveText(COPY.COMPARE_RIGHT_LABEL);
    await expect(page.locator('#compare-right-age')).toHaveText('Today');
    await expect(page.locator('#compare-right-date')).toBeVisible();
    await expect(page.locator('#compare-right-date')).toHaveText(thisMonth);
    // "Today" in the age slot and the month in the date slot keep those cells' own styles, and fit.
    const cell = await page.evaluate(() => {
      const a = document.getElementById('compare-right-age'), d = document.getElementById('compare-right-date');
      const box = a.parentElement.getBoundingClientRect(); const cs = getComputedStyle(a.parentElement);
      const inner = box.width - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight);
      const range = document.createRange(); range.selectNodeContents(a); const w = range.getBoundingClientRect().width;
      return { ageStyle: [getComputedStyle(a).fontSize, getComputedStyle(a).fontWeight, getComputedStyle(a).color], dateStyle: [getComputedStyle(d).fontSize, getComputedStyle(d).fontWeight, getComputedStyle(d).color], fits: w <= inner, lines: Math.round(a.getBoundingClientRect().height / parseFloat(getComputedStyle(a).fontSize)) };
    });
    expect(cell.ageStyle).toEqual(['28px', '900', 'rgb(19, 91, 236)']);
    expect(cell.dateStyle).toEqual(['11.5px', '700', 'rgb(19, 91, 236)']);
    expect(cell.fits).toBe(true);
    expect(cell.lines).toBe(1);
    // The same stakes line and headline as the dated reveal (Andrew, 2026-10-30): N is 65 minus the age entered
    // (30 here), from the same stakes() in projection.js; the at-target sentence is the sub line; the same button.
    await expect(page.locator('#stakes-line')).toBeVisible();
    await expect(page.locator('#stakes-line')).toHaveText(stakes(35));
    await expect(page.locator('#capture-heading')).toBeVisible();
    await expect(page.locator('#capture-heading')).toHaveText(COPY.HEADLINE);
    await expect(page.locator('#capture-support')).toBeVisible();
    await expect(page.locator('#capture-support')).toHaveText(COPY.AT_TARGET_BODY);
    await expect(page.locator('#btn-label')).toHaveText(COPY.BUTTON);
    expect(await expectArrow(page)).toBe(COPY.BUTTON);
    await expect(page.locator('#capture-email')).toBeVisible();
    // Stacked as the dated reveal is: comparison, stakes line, headline, sub, form.
    const order = await page.evaluate(() => ['compare', 'stakes-line', 'capture-heading', 'capture-support', 'capture-email'].map((id) => document.getElementById(id).getBoundingClientRect().top));
    for (let i = 1; i < order.length; i++) expect(order[i - 1], `order at ${i}`).toBeLessThan(order[i]);
    // The sentence was trimmed to hold to two lines in the slot on a 390 phone, as SUB does (the 2026-10-22
    // sentence ran four); and the heading is one line there like the dated state's.
    await page.setViewportSize(PHONE);
    const lines = await page.evaluate(() => {
      const n = (id) => { const e = document.getElementById(id); return Math.round(e.getBoundingClientRect().height / parseFloat(getComputedStyle(e).lineHeight)); };
      return { support: n('capture-support'), heading: n('capture-heading') };
    });
    expect(lines.support).toBeLessThanOrEqual(2);
    expect(lines.heading).toBe(1);
    // Out of the state and back: the age and the plan's own N return, then "Today" and the years to 65 again.
    await page.fill('#retire-range', '1250');
    await expect(page.locator('#feasible-block')).toBeVisible();
    await expect(page.locator('#capture-heading')).toHaveText(COPY.HEADLINE);
    await expect(page.locator('#compare-right-age')).toHaveText('31');
    await expect(page.locator('#stakes-line')).toHaveText(stakes(34));
    await expect(page.locator('#capture-support')).toHaveText(COPY.SUB);
    await page.fill('#retire-range', '1000');
    await expect(page.locator('#at-target-block')).toBeVisible();
    await expect(page.locator('#compare-right-age')).toHaveText('Today');
    await expect(page.locator('#stakes-line')).toHaveText(stakes(35));
    await expect(page.locator('#capture-support')).toHaveText(COPY.AT_TARGET_BODY);
    // The years to 65 follow the age entered.
    await page.fill('#age-range', '41');
    await expect(page.locator('#at-target-age')).toHaveText('41');
    await expect(page.locator('#stakes-line')).toHaveText(stakes(24));
  });

  test('at the target the $100k-$1m FRED stands right of the current age exactly as the presenter does: as tall as the column, feet on the caption line, inside the card at every width, still during the count-up, the row the same height as the dated one, and gone with the state', async ({ page }) => {
    await installMocks(page);
    await page.goto('/index.html');
    await page.fill('#retire-range', '1000');
    await page.fill('#existing-range', '48'); // $300,000, the target
    await page.click('#reveal-btn');
    // The row must not shift while the digits count up: sample the pig's left edge every frame from the click.
    const motion = await page.evaluate(() => new Promise((resolve) => {
      const lefts = []; const t0 = performance.now();
      (function sample() {
        const r = document.getElementById('at-target-pig').getBoundingClientRect();
        if (r.width > 0) lefts.push(r.left);
        if (performance.now() - t0 < 1300) requestAnimationFrame(sample); else resolve({ min: Math.min(...lefts), max: Math.max(...lefts), frames: lefts.length });
      })();
    }));
    expect(motion.frames).toBeGreaterThan(30);
    expect(motion.max - motion.min, 'pig held still during the count-up').toBeLessThanOrEqual(0.5);
    await expect(page.locator('#capture-email')).toBeVisible();
    await expect(page.locator('#at-target-age')).toHaveText('30');
    await expect(page.locator('#at-target-age')).not.toHaveAttribute('aria-hidden', 'true');
    const pig = page.locator('#at-target-block #at-target-age-row > #at-target-pig');
    await expect(pig).toHaveCount(1);
    await expect(pig).toHaveAttribute('aria-hidden', 'true');
    await expect(pig).toHaveAttribute('focusable', 'false');
    await expect(page.locator('#result-pig')).toBeHidden(); // the presenter goes with the feasible block
    await expect(page.locator('#result-card img')).toHaveCount(0);
    await expect(page.locator('#result-card svg:visible')).toHaveCount(1); // this one
    for (const viewport of [{ width: 320, height: 660 }, PHONE, { width: 1280, height: 900 }]) {
      await page.setViewportSize(viewport);
      const m = await page.evaluate(() => {
        const g = (id) => document.getElementById(id).getBoundingClientRect();
        const p = g('at-target-pig'), a = g('at-target-age'), y = g('at-target-caption'), e = g('at-target-eyebrow'), c = g('result-card');
        const cs = getComputedStyle(document.getElementById('result-card'));
        const col = document.getElementById('at-target-age').parentElement.getBoundingClientRect(); // the age + caption column
        return {
          heightDiff: p.height - (y.bottom - a.top), feet: p.bottom - y.bottom, gap: p.left - col.right,
          belowEyebrow: Math.min(p.top, a.top) - e.bottom,
          insideContent: p.right <= c.right - parseFloat(cs.paddingRight) + 0.5 && col.left >= c.left + parseFloat(cs.paddingLeft) - 0.5,
          offCentre: (col.left - c.left) - (c.right - p.right), captionUnderDigits: (a.left + a.right) / 2 - (y.left + y.right) / 2, pigHeight: p.height,
          rowHeight: document.getElementById('at-target-age-row').getBoundingClientRect().height, compareTop: document.getElementById('compare').getBoundingClientRect().top - c.top,
          colLeft: col.left - c.left, pigWidth: p.width,
          today: (() => { const ra = document.getElementById('compare-right-age'); const box = ra.parentElement.getBoundingClientRect(); const pcs = getComputedStyle(ra.parentElement); const range = document.createRange(); range.selectNodeContents(ra); return { fits: range.getBoundingClientRect().width <= box.width - parseFloat(pcs.paddingLeft) - parseFloat(pcs.paddingRight), lines: range.getClientRects().length }; })(),
        };
      });
      const w = viewport.width;
      expect(Math.abs(m.heightDiff), `as tall as the column at ${w}`).toBeLessThanOrEqual(1); // like the presenter: adds no height
      expect(Math.abs(m.feet), `feet on the caption line at ${w}`).toBeLessThanOrEqual(1);
      expect(m.gap, `gap at ${w}`).toBeGreaterThanOrEqual(8);
      expect(m.gap, `gap at ${w}`).toBeLessThanOrEqual(20);
      expect(m.belowEyebrow, `eyebrow clearance at ${w}`).toBeGreaterThanOrEqual(4);
      expect(m.insideContent, `inside the card's content box at ${w}`).toBe(true);
      expect(Math.abs(m.offCentre), `group centred at ${w}`).toBeLessThanOrEqual(2);
      expect(Math.abs(m.captionUnderDigits), `caption under the digits at ${w}`).toBeLessThanOrEqual(2);
      expect(m.pigHeight, `legible at ${w}`).toBeGreaterThanOrEqual(80);
      expect(m.today.fits, `"Today" fits the right cell at ${w}`).toBe(true);
      expect(m.today.lines, `"Today" on one line at ${w}`).toBe(1);
      // Seamless across the boundary: one tick of income later the result is dated (31) and the presenter is
      // back, and the row, the digits and the comparison sit exactly where they were (this pig's box is the
      // presenter's: same height and the same width, so the centred group does not move).
      await page.fill('#retire-range', '1250');
      await expect(page.locator('#result-age')).toHaveText('31');
      const dated = await page.evaluate(() => {
        const c = document.getElementById('result-card').getBoundingClientRect();
        return { rowHeight: document.getElementById('result-age-row').getBoundingClientRect().height, compareTop: document.getElementById('compare').getBoundingClientRect().top - c.top, colLeft: document.getElementById('result-age').parentElement.getBoundingClientRect().left - c.left, pigWidth: document.getElementById('result-pig').getBoundingClientRect().width };
      });
      expect(Math.abs(dated.rowHeight - m.rowHeight), `row height across the boundary at ${w}`).toBeLessThanOrEqual(0.5);
      expect(Math.abs(dated.compareTop - m.compareTop), `comparison position across the boundary at ${w}`).toBeLessThanOrEqual(0.5);
      expect(Math.abs(dated.colLeft - m.colLeft), `digits in the same place across the boundary at ${w}`).toBeLessThanOrEqual(0.5);
      expect(Math.abs(dated.pigWidth - m.pigWidth), `the two pigs' boxes are the same width at ${w}`).toBeLessThanOrEqual(0.5);
      await page.fill('#retire-range', '1000');
      await expect(page.locator('#at-target-block')).toBeVisible();
    }
    await page.setViewportSize(PHONE);
    // Sliding the income out of reach brings the dated result and the presenter back; this pig goes.
    await page.fill('#retire-range', '1250');
    await expect(page.locator('#feasible-block')).toBeVisible();
    await expect(pig).toBeHidden();
    await expect(page.locator('#result-pig')).toBeVisible();
    await expect(page.locator('#result-card svg:visible')).toHaveCount(1);
  });

  test('"FRED exists to close the gap." is one line on every phone: the heading follows the card\'s width up to its 22px, 26px from sm', async ({ page }) => {
    await installMocks(page);
    await page.goto('/index.html');
    await reveal(page);
    await expect(page.locator('#capture-heading')).toHaveText(COPY.HEADLINE);
    for (const width of [320, 335, 360, 375, 390, 400, 412, 430, 639, 640, 768]) {
      await page.setViewportSize({ width, height: 800 });
      const m = await page.evaluate(() => {
        const h = document.getElementById('capture-heading'); const c = document.getElementById('result-card'); const cs = getComputedStyle(c);
        const inner = c.getBoundingClientRect().width - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight);
        const range = document.createRange(); range.selectNodeContents(h);
        return { lines: range.getClientRects().length, font: parseFloat(getComputedStyle(h).fontSize), textWidth: range.getBoundingClientRect().width, inner };
      });
      expect(m.lines, `one line at ${width}`).toBe(1);
      expect(m.textWidth, `inside the card at ${width}`).toBeLessThanOrEqual(m.inner);
      if (width >= 640) expect(m.font, `26px from sm at ${width}`).toBe(26);
      else {
        // min(6.7cqw, 22px): 6.7% of the card's content box, capped at 22px (the cap from 412 on in this list).
        expect(m.font, `min(6.7cqw, 22px) at ${width}`).toBeCloseTo(Math.min(0.067 * m.inner, 22), 1);
        expect(m.font).toBeGreaterThanOrEqual(16);
        if (width >= 412) expect(m.font, `22px on a wide phone at ${width}`).toBe(22);
      }
    }
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
    await expect(page.locator('#compare-right-date')).toBeVisible();
    await expect(page.locator('#capture-count')).toBeVisible();
    expect(calls.signup[0]).toMatchObject({ email: 'moved@example.com', freedomAge: 53, revealedBeforeSubmit: true });
    // "Use a different one" brings the form, the pitch, the pig and the note back.
    await page.click('#pending-reset');
    await expect(page.locator('#waitlist-form')).toBeVisible();
    await expect(page.locator('#capture-pitch')).toBeVisible();
    await expect(page.locator('#capture-heading')).toHaveText(COPY.HEADLINE);
    await expect(page.locator('#capture-cta-note')).toBeVisible();
    await expect(page.locator('#btn-loading')).toBeHidden();
    expect(await expectArrow(page)).toBe(COPY.BUTTON);
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
    await expectDate(page);
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
    // The label's apostrophe is the page's typographic one (COPY.COMPARE_RIGHT_LABEL), so the pattern is built from RSQUO.
    expect(sr).toMatch(new RegExp(`^You could be free at 53 years old\\. Freedom Date: [A-Z][a-z]+ \\d{4}\\. Do nothing: 65\\+\\. On FRED${RSQUO}s route: 53, [A-Z][a-z]+ \\d{4}\\. That gap is 12 years of your life\\. Projected portfolio target: \\$2,250,000\\. Assumes a 10% average annual return \\(Moderate scenario\\) and 4% withdrawal rate\\. Hypothetical illustration`));
    expect(sr).not.toContain('..');
    expect(sr).not.toContain('before the usual 65');
    // 69 on a large balance, no gap to count (2026-11-01: the comparison stays, "No date" on the left): the age,
    // the date, the two cells, the late stakes line and the fine print; no "of your life".
    await page.fill('#age-range', '60');
    await page.fill('#invest-range', '50');
    await page.fill('#existing-range', '72'); // $900,000
    await expectLate(page, 69);
    const sr2 = await page.locator('#result-sr').textContent();
    expect(sr2).toMatch(new RegExp(`^You could be free at 69 years old\\. Freedom Date: ([A-Z][a-z]+ \\d{4})\\. Do nothing: No date\\. On FRED${RSQUO}s route: 69, \\1\\. Hope isn${RSQUO}t a plan\\. \\1 is\\. Projected portfolio target: `));
    expect(sr2).not.toContain('of your life');
    expect(sr2).not.toContain('..');
  });

  test('at 64 nothing counts the years before 65 any more: "years old" and the pill, no context line', async ({ page }) => {
    await installMocks(page);
    await page.goto('/index.html');
    await page.fill('#age-range', '41');
    await reveal(page);
    await expect(page.locator('#result-age')).toHaveText('64');
    await expectDate(page);
    expect(await revealText(page)).toContain('That gap is 1 year of your life.');
    expect(await revealText(page)).not.toMatch(/1 years\b/);
  });
});

test.describe('analytics', () => {
  const STAKE_BANDS = ['hidden', '1-5', '6-10', '11+'];
  const STAKE_SOURCES = ['plan', 'hidden'];
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

  test('a one-year gap bands as 1-5 plan, a plan at 65 or later as hidden, the balance case as plan 11+ (never lever)', async ({ page }) => {
    await forceAnalytics(page);
    await installMocks(page);
    await page.goto('/index.html');
    // 38 / $1,000 / $5,000: 64, one year.
    await page.fill('#age-range', '38');
    await page.fill('#invest-range', '1000');
    await page.fill('#retire-range', '5000');
    await reveal(page);
    expect(named(await events(page), 'Freedom Date Revealed')[0].props).toMatchObject({ freedom_age_band: '60-64', stake_years_band: '1-5', stake_source: 'plan' });
    // 42 / $500 / $4,000: 73, no gap to count: the comparison shows "No date" against 73, both bands hidden.
    await page.fill('#age-range', '42');
    await page.fill('#invest-range', '500');
    await page.fill('#retire-range', '4000');
    await page.click('#reveal-btn');
    await expectLate(page, 73);
    // $250,000 at the defaults: 65+ against 46, nineteen years.
    await page.fill('#age-range', '30');
    await page.fill('#invest-range', '2000');
    await page.fill('#retire-range', '7500');
    await page.fill('#existing-range', '46');
    await page.click('#reveal-btn'); // third click: no Recalculated, context still recorded
    await expect(page.locator('#compare-left-age')).toHaveText('65+');
    await expect(page.locator('#compare-right-age')).toHaveText('46');
    await submitWaitlist(page, 'plan@example.com');
    await expect(page.locator('#pending-block')).toBeVisible({ timeout: 10_000 });
    const all = await events(page);
    expect(named(all, 'Recalculated')).toHaveLength(1);
    expect(named(all, 'Email Focused')[0].props).toEqual({ stake_years_band: '11+', stake_source: 'plan' });
    expect(named(all, 'Waitlist Submitted')[0].props).toMatchObject({ return_scenario: 'moderate', freedom_age_band: '45-49', stake_years_band: '11+', stake_source: 'plan' });
    expectNoLeverTraces(all);
    for (const e of all) for (const v of Object.values(e.props)) expect(v).not.toBe('lever');
    const raw = ['250000', '46', '47', '52', '19', '1', '62', '64'];
    for (const e of all) for (const v of Object.values(e.props)) expect(raw).not.toContain(v);
  });

  test('a plan at 65 or later (and no date at all) bands as hidden on all three events', async ({ page }) => {
    await forceAnalytics(page);
    await installMocks(page);
    await page.goto('/index.html');
    await page.fill('#age-range', '35');
    await page.fill('#invest-range', '1000'); // 35 / $1,000 / $7,500: exactly 65, no gap to count
    await reveal(page);
    await expectLate(page, 65);
    await submitWaitlist(page, 'hidden@example.com');
    await expect(page.locator('#pending-block')).toBeVisible({ timeout: 10_000 });
    const all = await events(page);
    expect(named(all, 'Freedom Date Revealed')[0].props).toMatchObject(HIDDEN_STAKE);
    expect(named(all, 'Email Focused')[0].props).toEqual(HIDDEN_STAKE);
    expect(named(all, 'Waitlist Submitted')[0].props).toMatchObject({ revealed: 'yes', freedom_age_band: '65+', ...HIDDEN_STAKE });
    expectNoLeverTraces(all);
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
