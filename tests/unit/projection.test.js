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

// --- "Do nothing vs this plan" (2026-10-25; 2026-10-29: "do nothing" is always 65+, no lever rung, N from 1) ---
// Reference numbers (same formula, same rounding):
//   A: 65 - 53 = 12.
//   B: 65 - 73 is -8: hidden.
//   C: $250,000 already invested: 46; 65 - 46 = 19 (the balance alone, 52, no longer appears).
//   D: 38 / $1,000 / $5,000 / $0: 64.2 -> 64; 65 - 64 = 1: shown (the lever rung, 62 with +$250, is gone).
//   E: $1,000,000 at the defaults: 37.4 -> 37; 65 - 37 = 28.
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
  test('is always the usual retirement, 65+, and never projects anything (2026-10-29)', () => {
    for (const inputs of [CASES.A, CASES.B, CASES.C, { ...CASES.A, existing: 100000 }, { ...CASES.A, existing: 1000000 }]) {
      const s = spy();
      assert.deepEqual(P.doNothingScenario(inputs, s), { age: 65, reaches: false });
      assert.equal(s.calls.length, 0);
    }
  });
});

test.describe('stakes (65+ against the plan)', () => {
  test('A: 65+ against 53 is 12 from the plan; the projection is called once, for the plan', () => {
    const s = spy();
    const out = P.stakes(CASES.A, s);
    assert.deepEqual(s.calls, [CASES.A]);
    assert.deepEqual(out, { left: { age: 65, reaches: false }, right: { age: 53, step: null }, years: 12, source: 'plan' });
  });

  test('C and E: the money already invested changes the plan age, never the left cell', () => {
    assert.deepEqual(P.stakes(CASES.C, spy()), { left: { age: 65, reaches: false }, right: { age: 46, step: null }, years: 19, source: 'plan' });
    assert.deepEqual(P.stakes(CASES2.E, spy()), { left: { age: 65, reaches: false }, right: { age: 37, step: null }, years: 28, source: 'plan' });
  });

  test('D: one year (65 - 64) is shown from the plan; nothing is projected with a raised amount', () => {
    const s = spy();
    const out = P.stakes(CASES2.D, s);
    assert.deepEqual(out, { left: { age: 65, reaches: false }, right: { age: 64, step: null }, years: 1, source: 'plan' });
    assert.deepEqual(s.calls, [CASES2.D]);
  });

  test('B: 73 is past 65, hidden; so is exactly 65', () => {
    assert.deepEqual(P.stakes(CASES.B, spy()), { left: { age: 65, reaches: false }, right: null, years: null, source: 'hidden' });
    // 35 / $1,000 / $7,500 / $0 at 10%: 64.96 -> 65, N 0.
    const at65 = P.stakes({ age: 35, invest: 1000, retire: 7500, existing: 0, ratePct: 10 }, spy());
    assert.equal(P.roundAge(P.project({ age: 35, invest: 1000, retire: 7500, existing: 0, ratePct: 10 }).ageAtFreedom), 65);
    assert.equal(at65.source, 'hidden');
  });

  test('no current date: null, after one projection call', () => {
    const s = spy();
    assert.equal(P.stakes({ ...CASES.A, invest: 0 }, s), null);
    assert.equal(s.calls.length, 1);
  });

  test('at the target: the plan age is the age entered, N the years to 65, after one projection call (2026-10-30)', () => {
    const s = spy();
    const inputs = { age: 30, invest: 2000, retire: 1000, existing: 300000, ratePct: 10 };
    assert.deepEqual(P.stakes(inputs, s), { left: { age: 65, reaches: false }, right: { age: 30, step: null }, years: 35, source: 'plan' });
    assert.deepEqual(s.calls, [inputs]);
    assert.deepEqual(P.stakes({ ...inputs, age: 41 }, spy()), { left: { age: 65, reaches: false }, right: { age: 41, step: null }, years: 24, source: 'plan' });
  });
});

test('scenarios() carries the comparison alongside the rows and projects five times in all', () => {
  const s = spy();
  const out = P.scenarios(CASES.A, s);
  // lever (2) + delay (2) + stakes (the plan; "do nothing" projects nothing) = 5
  assert.equal(s.calls.length, 5);
  assert.deepEqual(out.stakes, { left: { age: 65, reaches: false }, right: { age: 53, step: null }, years: 12, source: 'plan' });
  assert.deepEqual(P.scenarios(CASES.C).stakes.years, 19);
});

// --- growthSeries (the welcome email's chart, 2026-10-27) ---------------------------------
// The balance and the cumulative amount put in, year by year, from the same model project()
// inverts: balance(m) = existing * (1 + r)^m + invest * ((1 + r)^m - 1) / r, contributed(m) =
// existing + invest * m. One point per whole year from the current age to the freedom age, plus
// the final fractional point, so the last balance is the target itself (to the dollar).
test.describe('growthSeries', () => {
  const sample = require(path.join(__dirname, '..', 'fixtures', 'projections', 'sample.json'));

  test('null without a date, after one projection call', () => {
    const s = spy();
    assert.equal(P.growthSeries({ ...CASES.A, invest: 0 }, s), null);
    assert.equal(s.calls.length, 1);
    assert.equal(P.growthSeries({ age: 30, invest: 2000, retire: 1000, existing: 300000, ratePct: 10 }, spy()), null);
    assert.equal(P.growthSeries({ age: 45, invest: 50, retire: 30000, existing: 0, ratePct: 8 }, spy()), null);
  });

  test('A: 25 points, from {30, $0} to the target at the fractional freedom age, matching the committed fixture', () => {
    const g = P.growthSeries(CASES.A);
    const r = P.project(CASES.A);
    assert.equal(g.series.length, 25);
    assert.equal(g.contributions.length, 25);
    assert.deepEqual(g.series[0], { age: 30, value: 0 });
    assert.deepEqual(g.contributions[0], { age: 30, value: 0 });
    const last = g.series[g.series.length - 1];
    assert.equal(last.value, Math.round(r.target));
    assert.equal(last.age, r.ageAtFreedom);
    assert.equal(g.contributions[g.contributions.length - 1].age, r.ageAtFreedom);
    assert.equal(g.totalContributed, 563792);
    assert.deepEqual(g.series, sample.series);
    assert.deepEqual(g.contributions, sample.contributions);
    assert.equal(g.totalContributed, sample.totalContributed);
  });

  test('both lines start at the existing balance and the last balance is the target, across inputs', () => {
    const cases = [
      CASES.A, CASES.B, CASES.C, CASES2.D,
      { age: 30, invest: 2000, retire: 7500, existing: 100000, ratePct: 12 },
      { age: 18, invest: 170, retire: 3000, existing: 0, ratePct: 8 },
      { age: 30, invest: 9000, retire: 1200, existing: 0, ratePct: 10 },
      { age: 30, invest: 10000, retire: 20000, existing: 0, ratePct: 12 },
    ];
    for (const inputs of cases) {
      const g = P.growthSeries(inputs);
      const r = P.project(inputs);
      const label = JSON.stringify(inputs);
      assert.deepEqual(g.series[0], { age: inputs.age, value: inputs.existing }, label);
      assert.deepEqual(g.contributions[0], { age: inputs.age, value: inputs.existing }, label);
      assert.equal(g.series[g.series.length - 1].value, Math.round(r.target), label);
      assert.equal(g.series[g.series.length - 1].age, r.ageAtFreedom, label);
      // One point per whole year plus the fractional one (no horizon here lands on a whole year).
      assert.equal(g.series.length, Math.floor(r.years) + 1 + (r.years > Math.floor(r.years) ? 1 : 0), label);
      assert.equal(g.contributions.length, g.series.length, label);
      // Ages strictly increasing, values never falling, on both lines.
      for (const line of [g.series, g.contributions]) {
        for (let i = 1; i < line.length; i++) {
          assert.ok(line[i].age > line[i - 1].age, label + ' age at ' + i);
          assert.ok(line[i].value >= line[i - 1].value, label + ' value at ' + i);
        }
      }
      // The amount put in: the starting balance plus every monthly amount, rounded once.
      const contributed = Math.round(inputs.existing + inputs.invest * r.months);
      assert.equal(g.totalContributed, contributed, label);
      assert.equal(g.contributions[g.contributions.length - 1].value, contributed, label);
    }
  });

  test('existing $100,000 at 12%: the chart begins at $100k on both lines', () => {
    const inputs = { age: 30, invest: 2000, retire: 7500, existing: 100000, ratePct: 12 };
    const g = P.growthSeries(inputs);
    assert.equal(g.series[0].value, 100000);
    assert.equal(g.contributions[0].value, 100000);
    assert.equal(g.series[g.series.length - 1].value, 2250000);
    // Every balance point after the first is above the amount put in (the money is growing).
    for (let i = 1; i < g.series.length; i++) assert.ok(g.series[i].value > g.contributions[i].value);
  });

  test('a horizon of exactly whole years has no fractional point: years + 1 points, the last at the freedom age', () => {
    // project() never lands on a whole year by itself, so a fake projection stands in: 24 months.
    const fake = (inputs) => ({ feasible: true, target: 0, months: 24, years: 2, ageAtFreedom: inputs.age + 2 });
    const g = P.growthSeries(CASES.A, fake);
    assert.equal(g.series.length, 3);
    assert.deepEqual(g.series.map((p) => p.age), [30, 31, 32]);
    assert.deepEqual(g.contributions.map((p) => p.value), [0, 24000, 48000]);
    assert.equal(g.totalContributed, 48000);
  });

  test('calls the projectFn it is given once, with the inputs unchanged, and reads its months and age', () => {
    const s = spy();
    const g = P.growthSeries(CASES.C, s);
    assert.equal(s.calls.length, 1);
    assert.deepEqual(s.calls[0], CASES.C);
    assert.equal(g.series[0].value, 250000);
    assert.equal(g.series[g.series.length - 1].age, P.project(CASES.C).ageAtFreedom);
  });
});
