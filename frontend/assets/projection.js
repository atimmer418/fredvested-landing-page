// The calculator's projection, as a pure function, plus the two "what moves your date"
// scenarios built on it. index.html's compute() wraps project() and adds the calendar
// date; nothing else on the page does arithmetic on the inputs. The backend mirrors
// project() in FreedomCalculator.java (one scenario rate on everything, monthly
// compounding, 4% withdrawal target); change both or neither.
//
// Loads as a classic script (window.FredProjection) and as a CommonJS module for the
// unit tests in tests/unit/, which also pass a spy in place of project() to prove the
// scenarios call it rather than carrying a formula of their own.
(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  root.FredProjection = api;
})(typeof window !== 'undefined' ? window : globalThis, function () {
  const WITHDRAWAL_RATE = 0.04;

  // The lever: how much more a month the first row proposes. $100 under $1,000 a
  // month, $250 from there.
  const LEVER_STEP_SMALL = 100;
  const LEVER_STEP_LARGE = 250;
  const LEVER_STEP_THRESHOLD = 1000;
  // The cost of waiting: contributions start this many months later.
  const DELAY_MONTHS = 12;
  // A slip under this many months is hidden: a tiny number undercuts the point.
  const DELAY_MIN_MONTHS = 3;
  // The "do nothing vs this plan" comparison (2026-10-25). "Do nothing" that never reaches the
  // target before this age reads "65+", and 65 is the number it counts as in the difference.
  const USUAL_RETIREMENT_AGE = 65;
  // A difference under this many whole years is not shown: the ladder moves to the lever
  // age, then hides the comparison.
  const STAKES_MIN_YEARS = 1;

  // `existing` is the money already invested: the projection's starting balance,
  // growing at the same selected rate as the contributions.
  // Returns { feasible, target, months, years, ageAtFreedom } or, when no date exists,
  // { feasible: false, target, atTarget: true } (balance already meets the target) or
  // { feasible: false, target, reason: 'no-investment' | 'horizon' }.
  // `growthOnly: true` lets a $0 monthly amount project the balance on its own (the "do
  // nothing" comparison cell); the page's own result never passes it, so a growth-only
  // date is still never shown as the result (Andrew, 2026-10-23).
  function project(inputs) {
    const age = inputs.age;
    const invest = inputs.invest;
    const retire = inputs.retire;
    const existing = inputs.existing || 0;
    const target = (retire * 12) / WITHDRAWAL_RATE;
    // A balance already at or past the target is its own result state (no date, no age), and it
    // comes before the no-investment check: nothing is being projected.
    if (existing >= target) return { feasible: false, atTarget: true, target: target };
    // A monthly amount is required even with a balance (Andrew, 2026-10-23): the tool projects
    // ongoing investing, so a growth-only date is never shown.
    if (invest <= 0 && !inputs.growthOnly) return { feasible: false, target: target, reason: 'no-investment' };
    // Nothing invested and nothing being invested never reaches anything.
    if (invest <= 0 && existing <= 0) return { feasible: false, target: target, reason: 'horizon' };
    const monthlyRate = inputs.ratePct / 100 / 12;
    // Months until starting balance plus contributions reach the target, one rate on everything.
    // With existing = 0 this is the original ln(target * r / invest + 1) / ln(1 + r).
    const months = Math.log((target * monthlyRate + invest) / (existing * monthlyRate + invest)) / Math.log(1 + monthlyRate);
    const years = months / 12;
    const ageAtFreedom = age + years;
    if (ageAtFreedom > 100 || years > 72) return { feasible: false, target: target, reason: 'horizon' };
    return { feasible: true, target: target, months: months, years: years, ageAtFreedom: ageAtFreedom };
  }

  // The one rounding rule for every age the page shows: nearest whole year, halves up
  // (Math.round), applied to the projected age in years. The main result, the lever age
  // and the delayed age all go through here, so "moves to" and "slips to" can be compared
  // with the shown result whole year for whole year.
  function roundAge(ageAtFreedom) {
    return Math.round(ageAtFreedom);
  }

  function leverStep(invest) {
    return invest < LEVER_STEP_THRESHOLD ? LEVER_STEP_SMALL : LEVER_STEP_LARGE;
  }

  // Row 1. The same projection with the monthly amount raised by the step, every other input
  // unchanged. null when there is nothing to show: no current date, no date for the raised
  // amount either, or a rounded age that is not at least one whole year earlier than the
  // rounded current result (never "moves to 62" when the result is 62).
  function leverScenario(inputs, projectFn) {
    const run = projectFn || project;
    const current = run(inputs);
    if (!current.feasible) return null;
    const step = leverStep(inputs.invest);
    const raised = run(Object.assign({}, inputs, { invest: inputs.invest + step }));
    if (!raised.feasible) return null;
    const currentAge = roundAge(current.ageAtFreedom);
    const leverAge = roundAge(raised.ageAtFreedom);
    const shiftYears = currentAge - leverAge;
    if (shiftYears < 1) return null;
    return { step: step, age: leverAge, shiftYears: shiftYears };
  }

  // Row 2. The same projection with contributions starting DELAY_MONTHS later and every
  // other input unchanged: same person (so the projection starts at age + 1), same monthly
  // amount, same target, same return scenario. The money already invested is already
  // invested, so it keeps compounding at the selected rate through the delay; that grown
  // balance is the starting balance of the delayed run. With nothing invested yet the slip is
  // exactly the delay. The slip is the difference in months between the delayed date and the
  // current date, rounded to the nearest whole month. null when there is nothing to show: no
  // current date, no delayed date (the grown balance meets the target on its own), a slip
  // under DELAY_MIN_MONTHS, or a delayed age that rounds to the same whole year as the
  // result (the row would read "slips to 42" under a 42).
  function delayScenario(inputs, projectFn) {
    const run = projectFn || project;
    const current = run(inputs);
    if (!current.feasible) return null;
    const monthlyRate = inputs.ratePct / 100 / 12;
    const grownBalance = (inputs.existing || 0) * Math.pow(1 + monthlyRate, DELAY_MONTHS);
    const delayed = run(Object.assign({}, inputs, { age: inputs.age + DELAY_MONTHS / 12, existing: grownBalance }));
    if (!delayed.feasible) return null;
    const months = Math.round(DELAY_MONTHS + delayed.months - current.months);
    if (months < DELAY_MIN_MONTHS) return null;
    const delayAge = roundAge(delayed.ageAtFreedom);
    if (delayAge <= roundAge(current.ageAtFreedom)) return null;
    return { age: delayAge, months: months };
  }

  // The comparison's left cell, "do nothing": the usual retirement, always (Andrew, 2026-10-29:
  // "doing nothing" means not investing at all, whatever is already in the accounts, so the
  // cell no longer projects the balance alone). Returns { age: 65, reaches: false }, which the
  // page shows as "65+" / "the usual retirement". The shape is kept, and the arguments are
  // accepted and ignored, so stakes() and its callers did not change. (2026-10-25 to 2026-10-28
  // this projected the balance with invest 0 and growthOnly, and showed that age when it was
  // under 65; project()'s growthOnly option stays for that.)
  function doNothingScenario(inputs, projectFn) { // eslint-disable-line no-unused-vars
    return { age: USUAL_RETIREMENT_AGE, reaches: false };
  }

  // The comparison and the number it argues with:
  //   N = left-cell age (65, shown as 65+) minus the plan's own rounded age, whole year for whole year.
  //   N >= STAKES_MIN_YEARS (1): the comparison shows with the plan's age on the right, source 'plan'.
  //   Else (the plan's age is 65 or later) the comparison is hidden: source 'hidden', years null.
  // Returns { left, right, years, source } for a feasible result or an at-target one, null for
  // the no-date states. At the target (2026-10-30) nothing is projected and the freedom age is
  // the age entered, so the plan's age is that age and N is the years between it and 65: the
  // same line ("That gap is N years of your life.") the dated reveal shows. The lever rung
  // of 2026-10-25 (the right cell became "This plan + $step/mo" when the plan alone was under
  // two years) went on 2026-10-29: the cell is always the visitor's own plan.
  function stakes(inputs, projectFn) {
    const run = projectFn || project;
    const current = run(inputs);
    if (!current.feasible && !current.atTarget) return null;
    const left = doNothingScenario(inputs, run);
    const planAge = roundAge(current.atTarget ? inputs.age : current.ageAtFreedom);
    const planYears = left.age - planAge;
    if (planYears >= STAKES_MIN_YEARS) {
      return { left: left, right: { age: planAge, step: null }, years: planYears, source: 'plan' };
    }
    return { left: left, right: null, years: null, source: 'hidden' };
  }

  // The chart in the personalised welcome email (2026-10-27): the projected balance and the cumulative
  // amount put in, year by year, from the same model as project(): one rate on everything, monthly
  // compounding. balance(m) = existing * (1 + r)^m + invest * ((1 + r)^m - 1) / r is the balance whose
  // inverse project() solves for the months; contributed(m) = existing + invest * m (the money already
  // invested counts as put in, so both lines start at the same point). One point per whole year from
  // `age` to the freedom age, plus the final fractional point, in whole dollars. The page sends this
  // with the signup; the server renders it and never recomputes it. null when project() has no date.
  function growthSeries(inputs, projectFn) {
    const run = projectFn || project;
    const current = run(inputs);
    if (!current.feasible) return null;
    const r = inputs.ratePct / 100 / 12;
    const existing = inputs.existing || 0;
    const balance = (m) => existing * Math.pow(1 + r, m) + inputs.invest * (Math.pow(1 + r, m) - 1) / r;
    const contributed = (m) => existing + inputs.invest * m;
    const series = [];
    const contributions = [];
    const push = (ageAt, m) => {
      series.push({ age: ageAt, value: Math.round(balance(m)) });
      contributions.push({ age: ageAt, value: Math.round(contributed(m)) });
    };
    const wholeYears = Math.floor(current.years);
    for (let y = 0; y <= wholeYears; y++) push(inputs.age + y, y * 12);
    if (current.years > wholeYears) push(current.ageAtFreedom, current.months);
    return { series: series, contributions: contributions, totalContributed: Math.round(contributed(current.months)) };
  }

  // Everything the page renders after a result, at once: { lever, delay, stakes }, the rows
  // null when hidden, stakes null when there is no date.
  function scenarios(inputs, projectFn) {
    const lever = leverScenario(inputs, projectFn);
    return { lever: lever, delay: delayScenario(inputs, projectFn), stakes: stakes(inputs, projectFn) };
  }

  return {
    WITHDRAWAL_RATE: WITHDRAWAL_RATE,
    DELAY_MONTHS: DELAY_MONTHS,
    DELAY_MIN_MONTHS: DELAY_MIN_MONTHS,
    USUAL_RETIREMENT_AGE: USUAL_RETIREMENT_AGE,
    STAKES_MIN_YEARS: STAKES_MIN_YEARS,
    project: project,
    roundAge: roundAge,
    leverStep: leverStep,
    leverScenario: leverScenario,
    delayScenario: delayScenario,
    doNothingScenario: doNothingScenario,
    stakes: stakes,
    scenarios: scenarios,
    growthSeries: growthSeries,
  };
});
