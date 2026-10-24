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

  // `existing` is the money already invested: the projection's starting balance,
  // growing at the same selected rate as the contributions.
  // Returns { feasible, target, months, years, ageAtFreedom } or, when no date exists,
  // { feasible: false, target, atTarget: true } (balance already meets the target) or
  // { feasible: false, target, reason: 'no-investment' | 'horizon' }.
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
    if (invest <= 0) return { feasible: false, target: target, reason: 'no-investment' };
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

  // Both rows at once, for the page: { lever, delay }, each null when hidden.
  function scenarios(inputs, projectFn) {
    return { lever: leverScenario(inputs, projectFn), delay: delayScenario(inputs, projectFn) };
  }

  return {
    WITHDRAWAL_RATE: WITHDRAWAL_RATE,
    DELAY_MONTHS: DELAY_MONTHS,
    DELAY_MIN_MONTHS: DELAY_MIN_MONTHS,
    project: project,
    roundAge: roundAge,
    leverStep: leverStep,
    leverScenario: leverScenario,
    delayScenario: delayScenario,
    scenarios: scenarios,
  };
});
