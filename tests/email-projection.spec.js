// The `projection` the signup carries (2026-10-27): the feasible result exactly as the page showed
// it, so the personalised welcome email can print and chart what the visitor saw. The payload is
// built in index.html's render() from the reveal state (compute()'s date, growthSeries() on the
// same inputs, the comparison's number from the stakes ladder) and follows the last render; the
// no-date states (at target, no monthly amount) send no projection key at all.
//
// Reference numbers (frontend/assets/projection.js, checked in Node first):
//   defaults 30 / $2,000 / $7,500 / $0 at 10%: 53.49 -> 53, April 2050 from 2026-10, target $2,250,000,
//     $563,792 put in, 25 points, 65+ against 53 = 12 from the plan (tests/fixtures/projections/sample.json)
//   $150,000 at 12%: 46.3 -> 46, 65 - 46 = 19 from the plan (2026-10-29: "do nothing" is always 65+), the chart starts at $150,000
//   38 / $1,000 / $5,000: 64, 65 - 64 = 1 from the plan (the lever rung is gone, 2026-10-29)
//   42 / $500 / $4,000: 73, nothing to compare: hidden, 0 years (65 - 73 clamps to 0)
//   defaults with $3,000/mo: 49.89 -> 50, 15 from the plan, 21 points
const { test, expect } = require('@playwright/test');
const path = require('path');
const { installMocks, submitWaitlist } = require('./helpers');
const P = require(path.join(__dirname, '..', 'frontend', 'assets', 'projection.js'));
const SAMPLE = require('./fixtures/projections/sample.json');

const KEYS = [
  'currentAge', 'freedomAge', 'freedomMonth', 'freedomYear', 'monthlyInvest', 'targetIncome', 'existingBalance',
  'returnRate', 'scenario', 'stakeYears', 'stakeSource', 'totalContributed', 'portfolioTarget', 'series', 'contributions',
];
const DEFAULTS = { age: 30, invest: 2000, retire: 7500, existing: 0, ratePct: 10 };

// Same mapping as the page script and existing-invested.spec.js (copied, not imported across specs).
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
async function setExisting(page, dollars) {
  const tick = dollarsToTick(dollars);
  if (tickToDollars(tick) !== dollars) throw new Error(`$${dollars} is not on a slider tick`);
  await page.fill('#existing-range', String(tick));
}
async function setSliders(page, { age, invest, retire }) {
  await page.fill('#age-range', String(age));
  await page.fill('#invest-range', String(invest));
  await page.fill('#retire-range', String(retire));
}
async function reveal(page) {
  await page.click('#reveal-btn');
  await expect(page.locator('#capture-email')).toBeVisible();
}

// The calendar date the way compute() builds it: the first of the month, round(months) months from
// today, in the test host's local time (the same clock the browser runs on).
function freedomDate(inputs) {
  const res = P.project(inputs);
  const now = new Date();
  return new Date(now.getFullYear(), now.getMonth() + Math.round(res.months), 1);
}
const formatMonthYear = (d) => d.toLocaleDateString('en-US', { month: 'long', year: 'numeric' });

test('the defaults send the projection the page rendered, field for field the committed sample', async ({ page }) => {
  const calls = await installMocks(page);
  await page.goto('/index.html');
  await reveal(page);
  await submitWaitlist(page, 'projection@example.com');
  await expect(page.locator('#pending-block')).toBeVisible({ timeout: 10_000 });

  expect(calls.signup).toHaveLength(1);
  const body = calls.signup[0];
  // The rest of the payload is untouched.
  expect(body).toMatchObject({ email: 'projection@example.com', age: 30, investMonthly: 2000, retireMonthly: 7500, existingInvested: 0, returnAssumptionPct: 10, freedomAge: 53, portfolioTarget: 2250000, revealedBeforeSubmit: true });
  const p = body.projection;
  expect(Object.keys(p).sort()).toEqual([...KEYS].sort());
  expect(p).toMatchObject({ scenario: 'MODERATE', returnRate: 10, currentAge: 30, monthlyInvest: 2000, targetIncome: 7500, existingBalance: 0, stakeSource: 'plan', stakeYears: 12 });

  // The numbers on screen are the numbers in the payload.
  await expect(page.locator('#result-age')).toHaveText(String(Math.round(p.freedomAge)));
  const shownDate = await page.locator('#compare-right-date').textContent();
  expect(formatMonthYear(new Date(p.freedomYear, p.freedomMonth - 1, 1))).toBe(shownDate);
  await expect(page.locator('#result-target')).toContainText('$' + p.portfolioTarget.toLocaleString('en-US'));
  const stakesText = await page.locator('#stakes-line').textContent();
  expect(Number(stakesText.match(/\d+/)[0])).toBe(p.stakeYears);

  // The chart data.
  expect(p.series).toHaveLength(25);
  expect(p.contributions).toHaveLength(25);
  expect(p.series[0]).toEqual({ age: 30, value: 0 });
  expect(p.series[24].value).toBe(p.portfolioTarget);
  expect(p.series[24].age).toBe(p.freedomAge);
  expect(p.contributions[24].value).toBe(p.totalContributed);

  // Everything but the calendar date equals the fixture the backend renders in its tests; the date
  // depends on today, so it is computed here the way the page computes it.
  const expectedDate = freedomDate(DEFAULTS);
  const { freedomMonth, freedomYear, ...rest } = p;
  const { freedomMonth: _m, freedomYear: _y, ...sampleRest } = SAMPLE;
  expect(rest).toEqual(sampleRest);
  expect(freedomMonth).toBe(expectedDate.getMonth() + 1);
  expect(freedomYear).toBe(expectedDate.getFullYear());
});

test('$150,000 already invested at the Optimistic scenario: the chart starts at $150,000 at 12%', async ({ page }) => {
  const calls = await installMocks(page);
  await page.goto('/index.html');
  await setExisting(page, 150000);
  await page.click('#return-seg .seg-btn[data-return="optimistic"]');
  await reveal(page);
  await submitWaitlist(page, 'optimistic@example.com');
  await expect(page.locator('#pending-block')).toBeVisible({ timeout: 10_000 });

  const p = calls.signup[0].projection;
  expect(p).toMatchObject({ existingBalance: 150000, returnRate: 12, scenario: 'OPTIMISTIC', currentAge: 30, monthlyInvest: 2000, targetIncome: 7500, stakeSource: 'plan', stakeYears: 19 });
  expect(Math.round(p.freedomAge)).toBe(46);
  expect(p.series[0]).toEqual({ age: 30, value: 150000 });
  expect(p.contributions[0]).toEqual({ age: 30, value: 150000 });
  expect(p.series[p.series.length - 1].value).toBe(2250000);
  expect(calls.signup[0]).toMatchObject({ existingInvested: 150000, returnAssumptionPct: 12, freedomAge: Math.round(p.freedomAge) });
  await expect(page.locator('#result-age')).toHaveText(String(Math.round(p.freedomAge)));
});

test('a one-year gap: 38 / $1,000 / $5,000 sends stakeSource plan and 1 year (never lever)', async ({ page }) => {
  const calls = await installMocks(page);
  await page.goto('/index.html');
  await setSliders(page, { age: 38, invest: 1000, retire: 5000 });
  await reveal(page);
  await submitWaitlist(page, 'lever@example.com');
  await expect(page.locator('#pending-block')).toBeVisible({ timeout: 10_000 });

  const p = calls.signup[0].projection;
  expect(p).toMatchObject({ stakeSource: 'plan', stakeYears: 1, currentAge: 38, monthlyInvest: 1000, targetIncome: 5000, portfolioTarget: 1500000 });
  expect(Math.round(p.freedomAge)).toBe(64);
  expect(p.series[0]).toEqual({ age: 38, value: 0 });
});

test('the hidden rung: 42 / $500 / $4,000 sends stakeSource hidden and 0 years', async ({ page }) => {
  const calls = await installMocks(page);
  await page.goto('/index.html');
  await setSliders(page, { age: 42, invest: 500, retire: 4000 });
  await reveal(page);
  await expect(page.locator('#compare')).toBeHidden();
  await submitWaitlist(page, 'hidden@example.com');
  await expect(page.locator('#pending-block')).toBeVisible({ timeout: 10_000 });

  const p = calls.signup[0].projection;
  expect(p).toMatchObject({ stakeSource: 'hidden', stakeYears: 0, currentAge: 42, monthlyInvest: 500, targetIncome: 4000 });
  expect(Math.round(p.freedomAge)).toBe(73);
});

test('the at-target state (balance at the target) sends no projection key', async ({ page }) => {
  // $300,000 already invested against a $1,000/mo target ($300,000): at target.
  const atTarget = await installMocks(page);
  await page.goto('/index.html');
  await page.fill('#retire-range', '1000');
  await setExisting(page, 300000);
  await reveal(page);
  await expect(page.locator('#at-target-block')).toBeVisible();
  await submitWaitlist(page, 'at-target@example.com');
  await expect(page.locator('#pending-block')).toBeVisible({ timeout: 10_000 });
  expect(atTarget.signup).toHaveLength(1);
  expect(atTarget.signup[0]).not.toHaveProperty('projection');
  expect(atTarget.signup[0]).not.toHaveProperty('freedomAge');
  expect(atTarget.signup[0]).toMatchObject({ existingInvested: 300000, retireMonthly: 1000, portfolioTarget: 300000 });
});

test('$0 a month (no date) sends no projection key', async ({ page }) => {
  const calls = await installMocks(page);
  await page.goto('/index.html');
  await page.fill('#invest-range', '0');
  await reveal(page);
  await expect(page.locator('#infeasible-block')).toBeVisible();
  await submitWaitlist(page, 'no-invest@example.com');
  await expect(page.locator('#pending-block')).toBeVisible({ timeout: 10_000 });
  expect(calls.signup).toHaveLength(1);
  expect(calls.signup[0]).not.toHaveProperty('projection');
  expect(calls.signup[0]).not.toHaveProperty('freedomAge');
  expect(calls.signup[0]).toMatchObject({ investMonthly: 0 });
});

test('the payload follows the last render: a slider change after the reveal sends the new numbers', async ({ page }) => {
  const calls = await installMocks(page);
  await page.goto('/index.html');
  await reveal(page);
  await expect(page.locator('#result-age')).toHaveText('53');
  // Live update: the result re-renders on input, with no second reveal click.
  await page.fill('#invest-range', '3000');
  await expect(page.locator('#result-age')).toHaveText('50');
  await submitWaitlist(page, 'recalc@example.com');
  await expect(page.locator('#pending-block')).toBeVisible({ timeout: 10_000 });

  const p = calls.signup[0].projection;
  const res = P.project({ ...DEFAULTS, invest: 3000 });
  expect(p).toMatchObject({ monthlyInvest: 3000, currentAge: 30, targetIncome: 7500, existingBalance: 0, returnRate: 10, scenario: 'MODERATE' });
  expect(p.freedomAge).toBe(res.ageAtFreedom);
  expect(Math.round(p.freedomAge)).toBe(50);
  expect(p.series).toHaveLength(Math.floor(res.years) + 2);
  expect(p.series[p.series.length - 1].value).toBe(2250000);
  expect(p.contributions[p.contributions.length - 1].value).toBe(Math.round(3000 * res.months));
  expect(p.stakeYears).toBe(15);
  expect(calls.signup[0]).toMatchObject({ investMonthly: 3000, freedomAge: 50 });
  // A different number: the one the defaults would have sent is not what left the page.
  expect(p.totalContributed).not.toBe(SAMPLE.totalContributed);
});
