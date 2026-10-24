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

test('scenarios() returns both rows from the same spy, four projection calls in all', () => {
  const s = spy();
  const out = P.scenarios(CASES.A, s);
  assert.equal(s.calls.length, 4);
  assert.deepEqual(out, { lever: { step: 250, age: 52, shiftYears: 1 }, delay: { age: 54, months: 12 } });
  assert.deepEqual(P.scenarios(CASES.C), { lever: null, delay: { age: 47, months: 6 } });
});
