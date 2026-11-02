// The "I already have invested" input (2026-10-22): a graduated slider (tick index 0..112
// mapped to dollars, fine steps at the low end) with a display-only figure beside the label
// (Andrew, 2026-10-23: nothing is typed into any of the four fields; each slider is the single
// control); the amount is the projection's starting balance, growing at the selected scenario
// rate alongside the contributions; a balance at or past the target shows the at-target state
// (attorney review pending) instead of a date; the exact amount travels with the signup and
// only a band ever reaches analytics.
//
// Reference numbers (index.html's compute(), checked in Node first):
//   defaults (30, $2,000/mo, $7,500/mo) -> target $2.25M
//     8%:  free at 56.8 (57) from zero, 49.2 (49) with $250,000
//     10%: free at 53.5 (53) from zero, 46.3 (46) with $250,000
//     12%: free at 51.0 (51) from zero, 44.2 (44) with $250,000
//   $7,500/mo with $150,000 at 10%: 48.6 (49)
const { test, expect } = require('@playwright/test');
const { installMocks, forceAnalytics, events, submitWaitlist } = require('./helpers');

const LABEL = 'I already have invested';
const PARENTHETICAL = '(in personal/retirement accounts)';
// The at-target sentence awaits attorney approval (the 2026-10-22 sentence, trimmed by Andrew on
// 2026-10-30 to two lines in the sub line's slot), so the test checks its legal anchors rather than
// the exact sentence; pin the exact text here once counsel has signed it off.
const AT_TARGET_ANCHORS = [/^Based on your inputs/, /[Nn]ot an assessment of whether you can retire\./];

// Same mapping as the page script. Ticks 0..40 are $2,500 steps, 41..76 are $25,000 steps,
// 77..112 are $250,000 steps; dollarsToTick is the nearest tick, halfway rounding up.
function tickToDollars(t) {
  if (t <= 40) return t * 2500;
  if (t <= 76) return 100000 + (t - 40) * 25000;
  return 1000000 + (t - 76) * 250000;
}
function dollarsToTick(d) {
  if (d <= 100000) return Math.round(d / 2500);
  if (d <= 1000000) return 40 + Math.round((d - 100000) / 25000);
  return 76 + Math.round((d - 1000000) / 250000);
}

// The slider is the only way in, so every balance a test uses must sit on a tick.
async function setExisting(page, dollars) {
  const tick = dollarsToTick(dollars);
  if (tickToDollars(tick) !== dollars) throw new Error(`$${dollars} is not on a slider tick`);
  await page.fill('#existing-range', String(tick));
}
async function reveal(page) {
  await page.click('#reveal-btn');
  await expect(page.locator('#capture-email')).toBeVisible();
}
const named = (list, name) => list.filter((e) => e.name === name);

test.describe('the input row', () => {
  test('renders as the last slider, after the retire row, with the exact label, the parenthetical and a default of $0', async ({ page }) => {
    await installMocks(page);
    await page.goto('/index.html');

    const label = page.locator('label[for="existing-range"]');
    await expect(label).toHaveText(LABEL + PARENTHETICAL);
    await expect(label.locator('span.block')).toHaveText(PARENTHETICAL);
    await expect(page.locator('#existing-number')).toHaveText('0');
    await expect(page.locator('#existing-range')).toHaveValue('0');
    // No aria-valuemin/max/now: ARIA in HTML forbids them on a native range; the dollar text is the mechanism.
    await expect(page.locator('#existing-range')).not.toHaveAttribute('aria-valuemax', /.*/);
    await expect(page.locator('#existing-range')).toHaveAttribute('aria-valuetext', '$0');

    // Order on the page (Andrew, 2026-10-23): invest, retire, existing, then the return selector.
    const tops = await page.evaluate(() => ['invest-range', 'retire-range', 'existing-range', 'return-seg'].map((id) => document.getElementById(id).getBoundingClientRect().top));
    expect(tops[0]).toBeLessThan(tops[1]);
    expect(tops[1]).toBeLessThan(tops[2]);
  });

  test('the parenthetical sits on its own line under the label', async ({ page }) => {
    await installMocks(page);
    await page.goto('/index.html');
    const [labelTop, noteTop] = await page.evaluate(() => {
      const label = document.querySelector('label[for="existing-range"]');
      const note = label.querySelector('span.block');
      return [label.getBoundingClientRect().top, note.getBoundingClientRect().top];
    });
    expect(noteTop).toBeGreaterThan(labelTop + 10);
  });

  for (const tick of [10, 50, 112]) {
    test(`slider tick ${tick} writes $${tickToDollars(tick).toLocaleString('en-US')} into the field and the aria text`, async ({ page }) => {
      await installMocks(page);
      await page.goto('/index.html');
      await page.fill('#existing-range', String(tick));
      await expect(page.locator('#existing-number')).toHaveText(tickToDollars(tick).toLocaleString('en-US'));
      await expect(page.locator('#existing-range')).toHaveAttribute('aria-valuetext', '$' + tickToDollars(tick).toLocaleString('en-US'));
    });
  }
  test('the page switches step size exactly at ticks 40/41 and 76/77', async ({ page }) => {
    // Driven through the page, not the spec-local helpers: a one-off in index.html's own
    // tickToDollars at either boundary would otherwise pass unnoticed.
    await installMocks(page);
    await page.goto('/index.html');
    for (const [tick, dollars] of [[40, 100000], [41, 125000], [76, 1000000], [77, 1250000], [0, 0]]) {
      await page.fill('#existing-range', String(tick));
      await expect(page.locator('#existing-number')).toHaveText(dollars.toLocaleString('en-US'));
    }
    for (let t = 0; t <= 112; t++) expect(dollarsToTick(tickToDollars(t))).toBe(t);
  });

  test('nothing in the calculator card can be typed into: the four sliders are the only inputs and every figure is plain text', async ({ page }) => {
    // Andrew, 2026-10-23. The figures are aria-hidden so a screen reader hears each value once,
    // from its slider.
    await installMocks(page);
    await page.goto('/index.html');
    const card = page.locator('#existing-range').locator('xpath=ancestor::div[contains(@class,"rounded-[20px]")]');
    await expect(card.locator('input')).toHaveCount(4);
    await expect(card.locator('input:not([type="range"])')).toHaveCount(0);
    await expect(card.locator('textarea, [contenteditable]')).toHaveCount(0);
    for (const id of ['age-number', 'invest-number', 'retire-number', 'existing-number']) {
      const fig = page.locator('#' + id);
      expect(await fig.evaluate((e) => e.tagName)).toBe('SPAN');
      expect(await fig.evaluate((e) => e.closest('[aria-hidden="true"]') !== null)).toBe(true);
    }
    // Each label hands focus to its slider.
    for (const range of ['age-range', 'invest-range', 'retire-range', 'existing-range']) {
      await page.locator(`label[for="${range}"]`).click();
      await expect(page.locator('#' + range)).toBeFocused();
    }
  });

  test('the number field does not overflow its card at 360px wide with the maximum value', async ({ page }) => {
    await page.setViewportSize({ width: 360, height: 800 });
    await installMocks(page);
    await page.goto('/index.html');
    await setExisting(page, 10000000);
    const ok = await page.evaluate(() => {
      const num = document.getElementById('existing-number').getBoundingClientRect();
      const card = document.getElementById('existing-range').closest('div').parentElement.getBoundingClientRect();
      return num.right <= card.right && num.left >= card.left && document.documentElement.scrollWidth <= window.innerWidth;
    });
    expect(ok).toBe(true);
  });
  // Andrew, 2026-10-23: the figure never wraps under the label and the subtext stays on one
  // line. On a phone an eight digit value steps its font down to fit beside "I already have
  // invested"; smaller values keep full size.
  for (const width of [360, 390]) {
    test(`at ${width}px the maximum value stays on the label's line and neither the title nor the subtext wraps`, async ({ page }) => {
      await page.setViewportSize({ width, height: 800 });
      await installMocks(page);
      await page.goto('/index.html');
      await setExisting(page, 10000000);
      const measure = () => page.evaluate(() => {
        const title = document.getElementById('existing-title').getBoundingClientRect();
        const sub = document.querySelector('label[for="existing-range"] span.block').getBoundingClientRect();
        const num = document.getElementById('existing-number');
        const figure = num.parentElement.getBoundingClientRect();
        return { sameLine: figure.top < title.bottom && figure.bottom > title.top, titleLines: Math.round(title.height / 20), subLines: Math.round(sub.height / 14), fontPx: parseFloat(getComputedStyle(num).fontSize) };
      });
      const r = await measure();
      expect(r.sameLine).toBe(true);
      expect(r.titleLines).toBe(1);
      expect(r.subLines).toBe(1);
      expect(r.fontPx).toBeGreaterThanOrEqual(16);
      expect(r.fontPx).toBeLessThan(26);
      await setExisting(page, 2500);
      expect((await measure()).fontPx).toBe(26);
    });
  }
});

test.describe('projection', () => {
  test('defaults show 53; a $250,000 starting balance shows 46', async ({ page }) => {
    await installMocks(page);
    await page.goto('/index.html');
    await reveal(page);
    await expect(page.locator('#result-age')).toHaveText('53');
    await setExisting(page, 250000);
    await expect(page.locator('#result-age')).toHaveText('46');
    await expect(page.locator('#result-target')).toHaveText('Projected portfolio target: $2,250,000.');
  });

  for (const [scenario, fromZero, with250k] of [
    ['conservative', '57', '49'],
    ['moderate', '53', '46'],
    ['optimistic', '51', '44'],
  ]) {
    test(`${scenario}: existing 0 matches the page's own result and 250000 is lower (${fromZero} -> ${with250k})`, async ({ page }) => {
      await installMocks(page);
      await page.goto('/index.html');
      await page.click(`#return-seg [data-return="${scenario}"]`);
      await reveal(page);
      await expect(page.locator('#result-age')).toHaveText(fromZero);
      await setExisting(page, 250000);
      await expect(page.locator('#result-age')).toHaveText(with250k);
      expect(Number(with250k)).toBeLessThan(Number(fromZero));
      await setExisting(page, 0);
      await expect(page.locator('#result-age')).toHaveText(fromZero);
    });
  }

  test('a starting balance below the target never replaces the required monthly amount', async ({ page }) => {
    // Andrew, 2026-10-23: no growth-only date. $0 a month keeps the "add a monthly amount"
    // message whatever the balance, unless the balance already meets the target (at-target state).
    await installMocks(page);
    await page.goto('/index.html');
    await page.fill('#invest-range', '0');
    await reveal(page);
    await expect(page.locator('#infeasible-block')).toBeVisible();
    await setExisting(page, 250000);
    await expect(page.locator('#infeasible-block')).toBeVisible();
    await expect(page.locator('#infeasible-message')).toHaveText('Add a monthly investment amount to reveal your Freedom Date.');
    await expect(page.locator('#feasible-block')).toBeHidden();
    await expect(page.locator('#at-target-block')).toBeHidden();
    // The smallest target is $300,000 ($1,000 a month): a balance that meets it is the at-target state, not a projection.
    await page.fill('#retire-range', '1000');
    await setExisting(page, 300000);
    await expect(page.locator('#at-target-block')).toBeVisible();
    await expect(page.locator('#infeasible-block')).toBeHidden();
  });
});

test.describe('dollar figures show commas', () => {
  // Andrew, 2026-10-23: the three dollar figures display thousands separators and the money
  // sliders speak the same amount per month; the signup payload carries plain integers.
  test('defaults and slider moves render with commas, and the payload stays numeric', async ({ page }) => {
    const calls = await installMocks(page);
    await page.goto('/index.html');
    await expect(page.locator('#invest-number')).toHaveText('2,000');
    await expect(page.locator('#retire-number')).toHaveText('7,500');
    await expect(page.locator('#age-number')).toHaveText('30');
    await expect(page.locator('#invest-range')).toHaveAttribute('aria-valuetext', '$2,000 per month');

    await page.fill('#invest-range', '2500');
    await expect(page.locator('#invest-number')).toHaveText('2,500');

    await page.fill('#retire-range', '12250');
    await expect(page.locator('#retire-number')).toHaveText('12,250');
    await expect(page.locator('#retire-range')).toHaveAttribute('aria-valuetext', '$12,250 per month');

    await page.fill('#invest-range', '950');
    await expect(page.locator('#invest-number')).toHaveText('950');

    await submitWaitlist(page, 'commas@example.com');
    expect(calls.signup[0].investMonthly).toBe(950);
    expect(calls.signup[0].retireMonthly).toBe(12250);
  });
});

test.describe('at-target state', () => {
  test('a balance at the target shows the attorney-pending copy, keeps the disclosure and the capture, and recovers live', async ({ page }) => {
    await installMocks(page);
    await page.goto('/index.html');
    await page.fill('#retire-range', '1000'); // target $300,000
    await setExisting(page, 300000);
    await reveal(page);

    const block = page.locator('#at-target-block');
    await expect(block).toBeVisible();
    // Since 2026-10-29 the state is the dated reveal's layout: the approved kicker, the visitor's current age
    // (the default 30 here) counted up in the age's type, "years old", the comparison with "Today" and the
    // current month and year on FRED's side, then (2026-10-30) the same stakes line and headline as the dated
    // reveal, N being the years between the age entered and 65, and the at-target sentence as the sub line.
    await expect(page.locator('#at-target-eyebrow')).toHaveText('You could be free at');
    await expect(page.locator('#at-target-age')).toHaveText('30');
    await expect(page.locator('#at-target-age')).not.toHaveAttribute('aria-hidden', 'true'); // the count-up has landed
    await expect(page.locator('#at-target-caption')).toHaveText('years old');
    const thisMonth = new Date().toLocaleDateString('en-US', { month: 'long', year: 'numeric' });
    await expect(page.locator('#compare')).toBeVisible();
    await expect(page.locator('#compare-left-age')).toHaveText('65+');
    await expect(page.locator('#compare-right-age')).toHaveText('Today');
    await expect(page.locator('#compare-right-date')).toHaveText(thisMonth);
    const gap = (age) => `That gap is ${65 - age} years of your life.`;
    await expect(page.locator('#stakes-line')).toBeVisible();
    await expect(page.locator('#stakes-line')).toHaveText(gap(30));
    await expect(page.locator('#capture-heading')).toBeVisible();
    await expect(page.locator('#capture-heading')).toHaveText('FRED exists to close the gap.');
    const body = (await page.locator('#capture-support').textContent()).trim();
    for (const anchor of AT_TARGET_ANCHORS) expect(body).toMatch(anchor);
    expect(body).not.toContain('—');
    await expect(page.locator('#at-target-headline, #at-target-body')).toHaveCount(0); // "You're already there." is gone
    await expect(page.locator('#feasible-block')).toBeHidden();
    await expect(page.locator('#infeasible-block')).toBeHidden();
    // The same fine-print block as a dated result (Andrew, 2026-11-01; hidden here before): the assumptions, the
    // target the sub line names, the approved disclosure.
    await expect(page.locator('#result-fineprint')).toHaveText('Assumes a 10% avg annual return (Moderate scenario) & 4% withdrawal rate. Projected portfolio target: $300,000. Hypothetical illustration based on the assumptions shown, not a prediction or guarantee. Actual returns may vary.');
    await expect(page.locator('#capture-email')).toBeVisible();
    // The announcement ends as the dated reveal's does: the target, the assumptions and the disclosure sentence
    // (the trimmed sentence no longer says "hypothetical").
    const disclaimer = (await page.locator('#result-disclaimer').textContent()).trim();
    const sr = (age) => `You could be free at ${age} years old. Do nothing: 65+. On FRED\u2019s route: Today, ${thisMonth}. ${gap(age)} ${body} Projected portfolio target: $300,000. Assumes a 10% average annual return (Moderate scenario) and 4% withdrawal rate. ${disclaimer}`;
    await expect(page.locator('#result-sr')).toHaveText(sr(30));
    // The age is the slider's, live, and so is the gap.
    await page.fill('#age-range', '41');
    await expect(page.locator('#at-target-age')).toHaveText('41');
    await expect(page.locator('#stakes-line')).toHaveText(gap(41));
    await expect(page.locator('#result-sr')).toHaveText(sr(41));
    await page.fill('#age-range', '30');

    // Raising the income out of reach of the balance restores the dated result live.
    await page.fill('#retire-range', '1250'); // target $375,000
    await expect(block).toBeHidden();
    await expect(page.locator('#feasible-block')).toBeVisible();
    await expect(page.locator('#result-age')).toHaveText('31');
  });

  test('one tick short of the target is a dated result, not the at-target state', async ({ page }) => {
    await installMocks(page);
    await page.goto('/index.html');
    await page.fill('#retire-range', '1000');
    await setExisting(page, 275000); // tick 47, the last stop below the $300,000 target
    await reveal(page);
    await expect(page.locator('#at-target-block')).toBeHidden();
    await expect(page.locator('#feasible-block')).toBeVisible();
  });
});

test.describe('payload and analytics', () => {
  test('a signup after a reveal carries existingInvested as the exact integer alongside the other inputs', async ({ page }) => {
    const calls = await installMocks(page);
    await page.goto('/index.html');
    await setExisting(page, 150000); // tick 42
    await reveal(page);
    await submitWaitlist(page, 'existing@example.com');
    await expect(page.locator('#pending-block')).toBeVisible({ timeout: 10_000 });
    expect(calls.signup).toHaveLength(1);
    expect(calls.signup[0]).toMatchObject({ existingInvested: 150000, investMonthly: 2000, retireMonthly: 7500, freedomAge: 49, interacted: true });
  });

  test('an untouched amount is sent as existingInvested 0 with interacted false', async ({ page }) => {
    const calls = await installMocks(page);
    await page.goto('/index.html');
    await submitWaitlist(page, 'zero@example.com');
    await expect(page.locator('#pending-block')).toBeVisible({ timeout: 10_000 });
    expect(calls.signup[0]).toMatchObject({ existingInvested: 0, interacted: false, freedomAge: 53 });
  });

  test('the at-target signup carries existingInvested and the target but no freedomAge or freedomDate', async ({ page }) => {
    const calls = await installMocks(page);
    await page.goto('/index.html');
    await page.fill('#retire-range', '1000');
    await setExisting(page, 300000);
    await submitWaitlist(page, 'attarget@example.com');
    await expect(page.locator('#pending-block')).toBeVisible({ timeout: 10_000 });
    expect(calls.signup[0]).toMatchObject({ existingInvested: 300000, portfolioTarget: 300000 });
    expect(calls.signup[0]).not.toHaveProperty('freedomAge');
    expect(calls.signup[0]).not.toHaveProperty('freedomDate');
  });

  test('Freedom Date Revealed bands 150000 as 50k-250k and never carries the raw amount', async ({ page }) => {
    await forceAnalytics(page);
    await installMocks(page);
    await page.goto('/index.html');
    await setExisting(page, 150000);
    await reveal(page);
    const revealed = named(await events(page), 'Freedom Date Revealed');
    expect(revealed).toHaveLength(1);
    expect(revealed[0].props).toMatchObject({ existing_invested_band: '50k-250k', freedom_age_band: '45-49' });
    expect(JSON.stringify(await events(page))).not.toContain('150000');
  });

  test('an untouched amount bands as 0', async ({ page }) => {
    await forceAnalytics(page);
    await installMocks(page);
    await page.goto('/index.html');
    await reveal(page);
    const revealed = named(await events(page), 'Freedom Date Revealed');
    expect(revealed[0].props).toMatchObject({ existing_invested_band: '0', freedom_age_band: '50-54' });
  });

  test('the at-target state bands freedom_age_band as at_target on the reveal and the submit, with no raw numbers', async ({ page }) => {
    await forceAnalytics(page);
    await installMocks(page);
    await page.goto('/index.html');
    await page.fill('#retire-range', '1000');
    await setExisting(page, 300000);
    await reveal(page);
    await submitWaitlist(page, 'attarget@example.com');
    await expect(page.locator('#pending-block')).toBeVisible({ timeout: 10_000 });

    const all = await events(page);
    // The card shows the comparison and the stakes line in this state since 2026-10-30, but nothing is projected,
    // so the stake bands stay hidden on both events (FREDdocs/analytics-events.md).
    expect(named(all, 'Freedom Date Revealed')[0].props).toMatchObject({ existing_invested_band: '250k-1m', freedom_age_band: 'at_target', stake_years_band: 'hidden', stake_source: 'hidden' });
    expect(named(all, 'Waitlist Submitted')[0].props).toMatchObject({ revealed: 'yes', freedom_age_band: 'at_target', stake_years_band: 'hidden', stake_source: 'hidden' });
    const json = JSON.stringify(all);
    expect(json).not.toContain('300000');
  });

  test('touching the existing slider first fires Calc Engaged with first_field existing_invested', async ({ page }) => {
    await forceAnalytics(page);
    await installMocks(page);
    await page.goto('/index.html');
    await page.fill('#existing-range', '10');
    expect(await events(page)).toEqual([{ name: 'Calc Engaged', props: { first_field: 'existing_invested' }, url: 'http://localhost:5500/index.html' }]);
  });
});
