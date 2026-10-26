// Unit tests for frontend/assets/projection.js (node --test, no browser). The two
// "what moves your date" scenarios must run the shared projection with modified inputs
// and never carry a formula of their own: every case below passes a spy in place of
// project() and asserts what it was called with, then checks the rounded ages against
// numbers computed independently with the same formula in Node.
//
// Reference numbers (4% withdrawal target, monthly compounding, Math.round on the age):
//   A. defaults 30 / $2,000 / $7,500 / $0 at 10%: 53.49 -> 53; +$250 -> 52.4 -> 52; a year later -> 54.49 -> 54, 12 months
//   B. 42 / $500 / $4,000 / $0 at 10%:            72.57 -> 73; +$100 -> 70.9 -> 71; a year later -> 74, 12 months
//   C. 30 / $2,000 / $7,500 / $250,000 at 10%:    46.32 -> 46; +$250 -> 45.6 -> 46 (hidden); a year later, balance grown -> 46.8 -> 47, 6 months
const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('path');

const P = require(path.join(__dirname, '..', '..', 'frontend', 'assets', 'projection.js'));

const CASES = {
  A: { age: 30, invest: 2000, retire: 7500, existing: 0, ratePct: 10 },
  B: { age: 42, invest: 500, retire: 4000, existing: 0, ratePct: 10 },
  C: { age: 30, invest: 2000, retire: 7500, existing: 250000, ratePct: 10 },
};

// A spy that records every call and delegates to the real projection.
function spy() {
  const calls = [];
  const fn = (inputs) => { calls.push({ ...inputs }); return P.project(inputs); };
  fn.calls = calls;
  return fn;
}

const nearly = (a, b, eps = 1e-9) => Math.abs(a - b) < eps;

test('project() reproduces the calculator on the defaults and keeps the result states', () => {
  const r = P.project(CASES.A);
  assert.equal(r.feasible, true);
  assert.equal(r.target, 2250000);
  assert.ok(nearly(Math.round(r.months * 10) / 10, 281.9), `months ${r.months}`);
  assert.equal(P.roundAge(r.ageAtFreedom), 53);

  assert.deepEqual(P.project({ ...CASES.A, invest: 0 }), { feasible: false, target: 2250000, reason: 'no-investment' });
  assert.deepEqual(P.project({ age: 30, invest: 2000, retire: 1000, existing: 300000, ratePct: 10 }), { feasible: false, atTarget: true, target: 300000 });
  assert.equal(P.project({ age: 45, invest: 50, retire: 30000, existing: 0, ratePct: 8 }).reason, 'horizon');
});

test('roundAge is Math.round on the projected age, the same rule as the main result', () => {
  assert.equal(P.roundAge(53.49), 53);
  assert.equal(P.roundAge(53.5), 54);
  assert.equal(P.roundAge(72.57), 73);
});

test('leverStep is $100 under $1,000 a month and $250 from there', () => {
  assert.equal(P.leverStep(0), 100);
  assert.equal(P.leverStep(999), 100);
  assert.equal(P.leverStep(1000), 250);
  assert.equal(P.leverStep(10000), 250);
});

test.describe('leverScenario', () => {
  test('A: calls the projection twice, the second time with invest + $250, and lands on 52', () => {
    const s = spy();
    const out = P.leverScenario(CASES.A, s);
    assert.equal(s.calls.length, 2);
    assert.deepEqual(s.calls[0], CASES.A);
    assert.deepEqual(s.calls[1], { ...CASES.A, invest: 2250 });
    assert.deepEqual(out, { step: 250, age: 52, shiftYears: 1 });
  });

  test('B: uses the $100 step under $1,000 a month and moves 73 to 71', () => {
    const s = spy();
    const out = P.leverScenario(CASES.B, s);
    assert.deepEqual(s.calls[1], { ...CASES.B, invest: 600 });
    assert.deepEqual(out, { step: 100, age: 71, shiftYears: 2 });
  });

  test('C: hidden (null) when the raised amount rounds to the same age as the result', () => {
    const s = spy();
    assert.equal(P.leverScenario(CASES.C, s), null);
    assert.equal(s.calls.length, 2); // it did run the projection, and found no whole year
    assert.equal(P.roundAge(P.project(CASES.C).ageAtFreedom), 46);
    assert.equal(P.roundAge(P.project({ ...CASES.C, invest: 2250 }).ageAtFreedom), 46);
  });

  test('hidden when there is no current date', () => {
    const s = spy();
    assert.equal(P.leverScenario({ ...CASES.A, invest: 0 }, s), null);
    assert.equal(s.calls.length, 1);
    assert.equal(P.leverScenario({ age: 30, invest: 2000, retire: 1000, existing: 300000, ratePct: 10 }, s), null);
  });
});

test.describe('delayScenario', () => {
  test('A: calls the projection twice, the second time a year older with the same (zero) balance, and slips 12 months to 54', () => {
    const s = spy();
    const out = P.delayScenario(CASES.A, s);
    assert.equal(s.calls.length, 2);
    assert.deepEqual(s.calls[0], CASES.A);
    assert.deepEqual(s.calls[1], { ...CASES.A, age: 31, existing: 0 });
    assert.deepEqual(out, { age: 54, months: 12 });
  });

  test('B: 73 slips to 74, 12 months', () => {
    const s = spy();
    assert.deepEqual(P.delayScenario(CASES.B, s), { age: 74, months: 12 });
    assert.deepEqual(s.calls[1], { ...CASES.B, age: 43, existing: 0 });
  });

  test('C: the existing balance keeps compounding for the 12 months and the slip is 6 months, 46 to 47', () => {
    const s = spy();
    const out = P.delayScenario(CASES.C, s);
    const grown = 250000 * Math.pow(1 + 0.10 / 12, 12);
    assert.equal(s.calls.length, 2);
    assert.equal(s.calls[1].age, 31);
    assert.ok(nearly(s.calls[1].existing, grown), `grown balance ${s.calls[1].existing}`);
    assert.equal(s.calls[1].invest, 2000);
    assert.equal(s.calls[1].retire, 7500);
    assert.equal(s.calls[1].ratePct, 10);
    assert.deepEqual(out, { age: 47, months: 6 });
  });

  test('hidden under 3 months, hidden when the delayed age rounds to the result, hidden when the grown balance meets the target', () => {
    // $1,000,000 already invested: the slip is 2 months.
    assert.equal(P.delayScenario({ ...CASES.A, existing: 1000000 }, spy()), null);
    // $2,000,000 with $5,000 a month: 3 months, but 31 slips to 31.
    assert.equal(P.delayScenario({ age: 30, invest: 5000, retire: 7500, existing: 2000000, ratePct: 10 }, spy()), null);
    // $275,000 against a $300,000 target: a year of growth meets the target, no delayed date.
    assert.equal(P.delayScenario({ age: 30, invest: 2000, retire: 1000, existing: 275000, ratePct: 10 }, spy()), null);
    // No current date at all.
    assert.equal(P.delayScenario({ ...CASES.A, invest: 0 }, spy()), null);
  });
});

test('scenarios() returns both rows from the same spy', () => {
  const s = spy();
  const out = P.scenarios(CASES.A, s);
  assert.deepEqual(out.lever, { step: 250, age: 52, shiftYears: 1 });
  assert.deepEqual(out.delay, { age: 54, months: 12 });
  assert.deepEqual(P.scenarios(CASES.C).lever, null);
  assert.deepEqual(P.scenarios(CASES.C).delay, { age: 47, months: 6 });
});

// --- "Do nothing vs this plan" (2026-10-25) ---------------------------------------------
// Reference numbers (same formula, same rounding):
//   A: $0 alone never reaches the target -> 65+; 65 - 53 = 12, from the plan.
//   B: 65+ against 73 is -8 and against the lever's 71 is -6: hidden.
//   C: $250,000 alone at 10%: ln(9) / ln(1 + 0.10/12) = 264.8 months -> 52.07 -> 52; 52 - 46 = 6, from the plan.
//   D: 38 / $1,000 / $5,000 / $0: 64.2 -> 64; 65 - 64 = 1 (under 2); +$250 -> 62.3 -> 62; 65 - 62 = 3, from the lever.
//   E: $1,000,000 at the defaults: 37.4 -> 37; alone 38.1 -> 38; 1 year, no lever row: hidden.
const CASES2 = {
  D: { age: 38, invest: 1000, retire: 5000, existing: 0, ratePct: 10 },
  E: { age: 30, invest: 2000, retire: 7500, existing: 1000000, ratePct: 10 },
};

test('project() with growthOnly projects the balance alone; without it a $0 amount is still no-investment', () => {
  assert.deepEqual(P.project({ ...CASES.C, invest: 0 }), { feasible: false, target: 2250000, reason: 'no-investment' });
  const alone = P.project({ ...CASES.C, invest: 0, growthOnly: true });
  assert.equal(alone.feasible, true);
  assert.ok(nearly(Math.round(alone.months * 10) / 10, 264.8), `months ${alone.months}`);
  assert.equal(P.roundAge(alone.ageAtFreedom), 52);
  // Nothing invested and nothing added: no date, and no division by zero.
  assert.equal(P.project({ ...CASES.A, invest: 0, growthOnly: true }).reason, 'horizon');
});

test.describe('doNothingScenario', () => {
  test('A: calls the projection once, with invest 0 and growthOnly on every other input unchanged, and reads 65+', () => {
    const s = spy();
    const out = P.doNothingScenario(CASES.A, s);
    assert.equal(s.calls.length, 1);
    assert.deepEqual(s.calls[0], { ...CASES.A, invest: 0, growthOnly: true });
    assert.deepEqual(out, { age: 65, reaches: false });
  });

  test('C: the balance alone reaches the target at 52, before 65', () => {
    const s = spy();
    const out = P.doNothingScenario(CASES.C, s);
    assert.deepEqual(s.calls[0], { ...CASES.C, invest: 0, growthOnly: true });
    assert.deepEqual(out, { age: 52, reaches: true });
  });

  test('a balance that reaches the target only at 65 or later is 65+ (the cutoff is a rounded age under 65)', () => {
    // $100,000 at the defaults: alone, ln(22.5) / ln(1 + 0.10/12) = 374.6 months -> 61.2: reaches.
    assert.deepEqual(P.doNothingScenario({ ...CASES.A, existing: 100000 }, spy()), { age: 61, reaches: true });
    // $50,000: 458.2 months -> 68.2: not before 65.
    assert.deepEqual(P.doNothingScenario({ ...CASES.A, existing: 50000 }, spy()), { age: 65, reaches: false });
  });
});

test.describe('stakes (the N ladder)', () => {
  test('A: 65+ against 53 is 12 from the plan; the projection is called for the plan and for doing nothing', () => {
    const s = spy();
    const out = P.stakes(CASES.A, s, null);
    assert.deepEqual(s.calls[0], CASES.A);
    assert.deepEqual(s.calls[1], { ...CASES.A, invest: 0, growthOnly: true });
    assert.equal(s.calls.length, 2);
    assert.deepEqual(out, { left: { age: 65, reaches: false }, right: { age: 53, step: null }, years: 12, source: 'plan' });
  });

  test('C: 52 against 46 is 6 from the plan, with the balance case on the left', () => {
    assert.deepEqual(P.stakes(CASES.C, spy()), { left: { age: 52, reaches: true }, right: { age: 46, step: null }, years: 6, source: 'plan' });
  });

  test('D: under 2 from the plan (65 - 64), so the lever age 62 goes on the right and N is 3', () => {
    const s = spy();
    const out = P.stakes(CASES2.D, s);
    assert.deepEqual(out, { left: { age: 65, reaches: false }, right: { age: 62, step: 250 }, years: 3, source: 'lever' });
    // The lever was projected through the same spy (raised amount), not a formula of its own.
    assert.ok(s.calls.some((c) => c.invest === 1250 && c.age === 38));
    // When the page passes the lever row it already computed, it is not projected again.
    const s2 = spy();
    assert.deepEqual(P.stakes(CASES2.D, s2, { step: 250, age: 62, shiftYears: 2 }).years, 3);
    assert.equal(s2.calls.length, 2);
  });

  test('B and E: still under 2 after the lever, or no lever row at all: hidden', () => {
    assert.deepEqual(P.stakes(CASES.B, spy()), { left: { age: 65, reaches: false }, right: null, years: null, source: 'hidden' });
    assert.deepEqual(P.stakes(CASES2.E, spy()), { left: { age: 38, reaches: true }, right: null, years: null, source: 'hidden' });
    // A hidden lever row passed in explicitly is honoured as hidden.
    assert.equal(P.stakes(CASES2.D, spy(), null).source, 'hidden');
  });

  test('no current date: null, after one projection call', () => {
    const s = spy();
    assert.equal(P.stakes({ ...CASES.A, invest: 0 }, s), null);
    assert.equal(s.calls.length, 1);
    assert.equal(P.stakes({ age: 30, invest: 2000, retire: 1000, existing: 300000, ratePct: 10 }, spy()), null);
  });
});

test('scenarios() carries the comparison alongside the rows and projects six times in all', () => {
  const s = spy();
  const out = P.scenarios(CASES.A, s);
  // lever (2) + delay (2) + stakes (plan, do nothing; the lever row is reused) = 6
  assert.equal(s.calls.length, 6);
  assert.deepEqual(out.stakes, { left: { age: 65, reaches: false }, right: { age: 53, step: null }, years: 12, source: 'plan' });
  assert.deepEqual(P.scenarios(CASES.C).stakes.years, 6);
});
