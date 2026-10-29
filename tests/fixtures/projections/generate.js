// Builds the sample projection payloads the backend renders in its tests and in the dev email preview,
// from frontend/assets/projection.js itself (never from a second formula), shaped exactly as index.html
// sends `projection` with a signup. Run from the repo root:
//   node tests/fixtures/projections/generate.js
// writes tests/fixtures/projections/*.json, copies to backend/src/test/resources/projections/ and
// backend/src/main/resources/email/samples/ (the dev preview's inputs). The calendar date is fixed at
// 2026-10-27 so the files are reproducible.
const fs = require('fs');
const path = require('path');
const P = require('../../../frontend/assets/projection.js');

const SCENARIOS = { 8: 'CONSERVATIVE', 10: 'MODERATE', 12: 'OPTIMISTIC' };
const USUAL = P.USUAL_RETIREMENT_AGE;
const TODAY = new Date(2026, 9, 27);

// Mirrors index.html: compute() adds the first-of-month date round(months) months out; stakes() picks
// the comparison's number and source; the rest is the reveal state.
function build(inputs) {
  const res = P.project(inputs);
  if (!res.feasible) return null;
  const date = new Date(TODAY.getFullYear(), TODAY.getMonth() + Math.round(res.months), 1);
  const st = P.stakes(inputs);
  const g = P.growthSeries(inputs);
  const rounded = P.roundAge(res.ageAtFreedom);
  const stakeYears = st.source === 'hidden' ? Math.max(0, USUAL - rounded) : st.years;
  return {
    currentAge: inputs.age,
    freedomAge: res.ageAtFreedom,
    freedomMonth: date.getMonth() + 1,
    freedomYear: date.getFullYear(),
    monthlyInvest: inputs.invest,
    targetIncome: inputs.retire,
    existingBalance: inputs.existing || 0,
    returnRate: inputs.ratePct,
    scenario: SCENARIOS[inputs.ratePct],
    stakeYears: stakeYears,
    stakeSource: st.source,
    totalContributed: g.totalContributed,
    portfolioTarget: Math.round(res.target),
    series: g.series,
    contributions: g.contributions,
  };
}

const CASES = {
  // The brief's sample: age 30, $2,000/mo, $7,500/mo target, $0 existing, Moderate 10%.
  sample: { age: 30, invest: 2000, retire: 7500, existing: 0, ratePct: 10 },
  // Andrew's case: $100k already invested at 12%; the chart starts at $100k.
  'existing-100k-optimistic': { age: 30, invest: 2000, retire: 7500, existing: 100000, ratePct: 12 },
  // A three-year horizon.
  'short-horizon': { age: 30, invest: 9000, retire: 1200, existing: 0, ratePct: 10 },
  // A forty-five-year horizon at the conservative rate.
  'long-horizon': { age: 18, invest: 170, retire: 3000, existing: 0, ratePct: 8 },
  // A target under $1M ($100k gridlines).
  'target-under-1m': { age: 30, invest: 2000, retire: 2000, existing: 0, ratePct: 10 },
  // A target over $5M ($1M gridlines).
  'target-over-5m': { age: 30, invest: 10000, retire: 20000, existing: 0, ratePct: 12 },
  // The lever rung of the stakes ladder (38 / $1,000 / $5,000: 64, do nothing 65+, +$250 -> 62).
  'stake-lever': { age: 38, invest: 1000, retire: 5000, existing: 0, ratePct: 10 },
  // The hidden rung (41 / $500 / $7,500 -> 71; nothing the ladder can show): stakeYears from the formula.
  'stake-hidden': { age: 42, invest: 500, retire: 4000, existing: 0, ratePct: 10 },
  // The boundary of the email's 65-or-later variant (2026-10-28): a hidden rung whose date rounds to 64
  // (do nothing 65, nothing the ladder can show) keeps the no-date heading ...
  'hidden-under-65': { age: 45, invest: 2100, retire: 5000, existing: 0, ratePct: 10 },
  // ... and a lever rung whose own date rounds to 65 (40 / $700 / $3,000: 64.71; +$250 -> 63) gets it.
  'late-date-lever': { age: 40, invest: 700, retire: 3000, existing: 0, ratePct: 10 },
};

const here = __dirname;
const targets = [here, path.join(here, '../../../backend/src/test/resources/projections'), path.join(here, '../../../backend/src/main/resources/email/samples')];
for (const [name, inputs] of Object.entries(CASES)) {
  const p = build(inputs);
  if (!p) throw new Error(name + ': no date');
  const json = JSON.stringify(p, null, 1) + '\n';
  for (const dir of targets) fs.writeFileSync(path.join(dir, name + '.json'), json);
  console.log(name.padEnd(26), 'age', p.currentAge, '->', p.freedomAge.toFixed(2), 'points', p.series.length, 'target', p.portfolioTarget, 'contributed', p.totalContributed, 'stake', p.stakeYears, p.stakeSource);
}
