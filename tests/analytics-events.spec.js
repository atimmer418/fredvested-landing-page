// The analytics event schema, proven end to end on the real pages. Every
// window.plausible() call the pages make lands in the recorder installed by
// tests/helpers.js, and this spec asserts the exact names, prop keys and band
// strings that FREDdocs/analytics-events.md documents and assets/analytics.js
// implements: the once-per-page-load guards, the Waitlist Failed cap of three,
// the failure reason vocabulary, the hostname gate (nothing is sent on
// localhost without the force flag) and the rule that no raw number and no
// email address ever leaves a page. Waitlist Confirmed lives on confirmed.html
// and is covered by the confirmed-page spec.
//
// Numbers below are the calculator's defaults (age 30, $2,000/mo, $7,500/mo,
// moderate 10%) run through index.html's compute(): a 4% withdrawal target of
// $2.25M reached in 281.9 months, so free at 53 (band 50-54); at 8% it is 322.1
// months, free at 57 (band 55-59). The page's own rendered age is asserted next
// to each band so the arithmetic here can never drift from the page's.
const { test, expect } = require('@playwright/test');
const {
  STATS, FRESH_SIGNUP,
  installMocks, forceAnalytics, events, eventNames, waitForTurnstile, revealIfNeeded, submitWaitlist, storage,
} = require('./helpers');

// --- the documented vocabulary (FREDdocs/analytics-events.md, "Bands") --------
const EDITS = ['0', '1-3', '4-8', '9-15', '16+'];
const VOCAB = {
  first_field: ['age', 'monthly_invest', 'target_income', 'return_scenario'],
  return_scenario: ['conservative', 'moderate', 'optimistic'],
  age_band: ['18-24', '25-29', '30-34', '35-39', '40-44', '45-49', '50-54', '55+'],
  invest_band: ['0-99', '100-249', '250-499', '500-999', '1000-1999', '2000-4999', '5000+'],
  target_income_band: ['<2500', '2500-4999', '5000-7499', '7500-9999', '10000+'],
  freedom_age_band: ['unreachable', '<45', '45-49', '50-54', '55-59', '60-64', '65+'],
  sec_to_reveal_band: ['0-10', '11-30', '31-60', '61-120', '120+'],
  input_edits_band: EDITS,
  edits_after_reveal_band: EDITS,
  source_page: ['home', 'about'],
  revealed: ['yes', 'no'],
  reason: ['invalid_email', 'empty_email', 'duplicate', 'rate_limited', 'server_error', 'network_error', 'timeout', 'geo_blocked', 'captcha_failed'],
};
const UTM_KEYS = ['utm_source', 'utm_medium', 'utm_campaign'];
const SUBMITTED_KEYS = ['source_page', 'revealed', ...UTM_KEYS];
const EVENT_KEYS = {
  'Calc Engaged': ['first_field'],
  'Explainer Opened': [],
  'Freedom Date Revealed': ['return_scenario', 'age_band', 'invest_band', 'target_income_band', 'freedom_age_band', 'sec_to_reveal_band', 'input_edits_band'],
  'Recalculated': ['edits_after_reveal_band'],
  'Email Focused': [],
  'Waitlist Submitted': SUBMITTED_KEYS, // plus return_scenario and freedom_age_band when revealed is "yes"
  'Waitlist Failed': ['reason'],
};
const LANDING_EVENTS = Object.keys(EVENT_KEYS);

// The props Freedom Date Revealed carries for an untouched calculator.
const DEFAULT_REVEAL = {
  return_scenario: 'moderate',
  age_band: '30-34',
  invest_band: '2000-4999',
  target_income_band: '7500-9999',
  freedom_age_band: '50-54',
  sec_to_reveal_band: '0-10',
  input_edits_band: '0',
};
const NO_UTM = { utm_source: 'direct', utm_medium: 'none', utm_campaign: 'none' };

// --- API answers used by the failure paths (shapes from WaitlistController) ---
const SERVER_ERROR = { status: 500, body: { code: 'server_error', message: 'Something went wrong. Please try again.' } };
const RATE_LIMITED = { status: 429, body: { code: 'rate_limited', message: 'Too many requests. Please try again in a minute.' } };
const GEO_BLOCKED = { status: 403, body: { code: 'geo_blocked', message: 'FRED is currently available to US residents only.' } };
const CAPTCHA_REJECTED = { status: 400, body: { code: 'captcha_failed', message: 'Security check failed.' } };
// A 200 for a CONFIRMED address: already_joined plus the row's real status, no requiresConfirmation.
const confirmedDuplicate = (realStatus) => ({ ...STATS, status: 'already_joined', realStatus, count: 41 });
// The first POST fails as `first`, every later one is a fresh signup.
const failOnceThenSucceed = (first) => {
  let n = 0;
  return () => (n++ === 0 ? first : { status: 200, body: FRESH_SIGNUP });
};

// --- local helpers ----------------------------------------------------------
const named = (list, name) => list.filter((e) => e.name === name);
async function eventsNamed(page, name) { return named(await events(page), name); }

async function reveal(page) {
  await page.click('#reveal-btn');
  await expect(page.locator('#capture-email')).toBeVisible();
}

// Edits are counted 400 ms after the last input on a field (EDIT_DEBOUNCE_MS);
// wait for the count instead of sleeping. getCalcContext() is exposed for tests.
async function waitForEditsBand(page, band) {
  await page.waitForFunction((b) => window.FredAnalytics.getCalcContext().input_edits_band === b, band);
}

// A submit that does not go through the helper: for attempts that never reach
// the API (client validation, no captcha token) or never get a response.
async function clickSubmit(page) {
  await page.click('#join-waitlist-btn');
}

async function expectFormUsable(page) {
  await expect(page.locator('#capture-email')).toBeVisible();
  await expect(page.locator('#capture-email')).toBeEnabled();
  await expect(page.locator('#join-waitlist-btn')).toBeEnabled();
  await expect(page.locator('#btn-text')).toBeVisible();
  await expect(page.locator('#pending-block')).toBeHidden();
  await expect(page.locator('#success-block')).toBeHidden();
}

// =============================================================================
test.describe('index.html: calculator events', () => {
  for (const [selector, value, field] of [
    ['#age-range', '35', 'age'],
    ['#invest-number', '900', 'monthly_invest'],
    ['#retire-range', '4000', 'target_income'],
  ]) {
    test(`the first input on ${selector} fires Calc Engaged once with first_field ${field}`, async ({ page }) => {
      await forceAnalytics(page);
      await installMocks(page);
      await page.goto('/index.html');
      expect(await events(page)).toEqual([]);

      await page.fill(selector, value);
      expect(await events(page)).toEqual([{ name: 'Calc Engaged', props: { first_field: field }, url: 'http://localhost:5500/index.html' }]);
    });
  }

  test('later inputs and the scenario control do not fire Calc Engaged again', async ({ page }) => {
    await forceAnalytics(page);
    await installMocks(page);
    await page.goto('/index.html');

    await page.fill('#age-range', '35');
    await page.fill('#invest-range', '3000');
    await page.fill('#retire-number', '5000');
    await page.click('#return-seg [data-return="optimistic"]');
    // The reveal is a sentinel: anything the extra inputs fired would sit before it.
    await reveal(page);

    expect(await eventNames(page)).toEqual(['Calc Engaged', 'Freedom Date Revealed']);
    expect((await eventsNamed(page, 'Calc Engaged'))[0].props).toEqual({ first_field: 'age' });
  });

  test('clicking the active scenario fires nothing; a new scenario is first_field return_scenario and bands the 8% result', async ({ page }) => {
    await forceAnalytics(page);
    await installMocks(page);
    await page.goto('/index.html');

    await page.click('#return-seg [data-return="moderate"]'); // already selected
    expect(await events(page)).toEqual([]);

    await page.click('#return-seg [data-return="conservative"]');
    expect(await events(page)).toEqual([{ name: 'Calc Engaged', props: { first_field: 'return_scenario' }, url: 'http://localhost:5500/index.html' }]);

    await reveal(page);
    await expect(page.locator('#result-age')).toHaveText('57');
    const revealed = await eventsNamed(page, 'Freedom Date Revealed');
    expect(revealed).toHaveLength(1);
    expect(revealed[0].props).toEqual({ ...DEFAULT_REVEAL, return_scenario: 'conservative', freedom_age_band: '55-59' });
  });

  test('the first reveal with untouched defaults fires Freedom Date Revealed once with the exact bands', async ({ page }) => {
    await forceAnalytics(page);
    await installMocks(page);
    await page.goto('/index.html');
    expect(await storage(page, 'plausible_ignore')).toBeNull(); // the force flag removes Plausible's own kill switch

    await reveal(page);
    await expect(page.locator('#result-age')).toHaveText('53');
    await expect(page.locator('#reveal-btn')).toHaveAttribute('aria-expanded', 'true');

    expect(await events(page)).toEqual([{ name: 'Freedom Date Revealed', props: DEFAULT_REVEAL, url: 'http://localhost:5500/index.html' }]);
  });

  test('a reveal after adjusting every input bands the new values and counts the debounced edits', async ({ page }) => {
    await forceAnalytics(page);
    await installMocks(page);
    await page.goto('/index.html');

    await page.fill('#age-range', '42');
    await page.fill('#invest-range', '500');
    await page.fill('#retire-range', '4000');
    await waitForEditsBand(page, '1-3'); // three fields, one edit each
    await reveal(page);
    // compute(42, 500, 4000) at 10%: $1.2M target, 366.9 months, free at 73.
    await expect(page.locator('#result-age')).toHaveText('73');

    const revealed = await eventsNamed(page, 'Freedom Date Revealed');
    expect(revealed).toHaveLength(1);
    expect(revealed[0].props).toEqual({
      return_scenario: 'moderate',
      age_band: '40-44',
      invest_band: '500-999',
      target_income_band: '2500-4999',
      freedom_age_band: '65+',
      sec_to_reveal_band: '0-10',
      input_edits_band: '1-3',
    });
  });

  test('an unreachable target bands freedom_age_band as unreachable, on the reveal and on the submit', async ({ page }) => {
    await forceAnalytics(page);
    await installMocks(page);
    await page.goto('/index.html');

    await page.fill('#invest-range', '0');
    await waitForEditsBand(page, '1-3');
    await reveal(page);
    await expect(page.locator('#infeasible-block')).toBeVisible();
    await expect(page.locator('#infeasible-message')).toContainText('Add a monthly investment amount');

    const revealed = await eventsNamed(page, 'Freedom Date Revealed');
    expect(revealed).toHaveLength(1);
    expect(revealed[0].props).toEqual({ ...DEFAULT_REVEAL, invest_band: '0-99', freedom_age_band: 'unreachable', input_edits_band: '1-3' });

    await submitWaitlist(page, 'unreachable@example.com');
    await expect(page.locator('#pending-block')).toBeVisible({ timeout: 10_000 });
    const submitted = await eventsNamed(page, 'Waitlist Submitted');
    expect(submitted).toHaveLength(1);
    expect(submitted[0].props).toEqual({ source_page: 'home', revealed: 'yes', ...NO_UTM, return_scenario: 'moderate', freedom_age_band: 'unreachable' });
  });

  test('a second reveal click fires Recalculated once with edits_after_reveal_band; live edits and a third click add nothing', async ({ page }) => {
    await forceAnalytics(page);
    await installMocks(page);
    await page.goto('/index.html');

    await reveal(page);
    await page.fill('#age-range', '35'); // re-renders live; fires Calc Engaged, never a reveal event
    await waitForEditsBand(page, '1-3');
    expect(await eventNames(page)).toEqual(['Freedom Date Revealed', 'Calc Engaged']);

    await page.click('#reveal-btn');
    expect(await eventNames(page)).toEqual(['Freedom Date Revealed', 'Calc Engaged', 'Recalculated']);
    expect((await eventsNamed(page, 'Recalculated'))[0].props).toEqual({ edits_after_reveal_band: '1-3' });

    await page.click('#reveal-btn');
    expect(await eventNames(page)).toEqual(['Freedom Date Revealed', 'Calc Engaged', 'Recalculated']);
  });

  test('opening the explainer fires Explainer Opened once; closing and reopening adds nothing', async ({ page }) => {
    await forceAnalytics(page);
    await installMocks(page);
    await page.goto('/index.html');
    await reveal(page);

    const details = page.locator('#calc-method');
    const summary = page.locator('#calc-method summary');
    await summary.click();
    await expect(details).toHaveJSProperty('open', true);
    await expect.poll(() => eventsNamed(page, 'Explainer Opened')).toEqual([{ name: 'Explainer Opened', props: {}, url: 'http://localhost:5500/index.html' }]);

    await summary.click();
    await expect(details).toHaveJSProperty('open', false);
    await summary.click();
    await expect(details).toHaveJSProperty('open', true);
    // The focus is a sentinel: a second Explainer Opened would be recorded before it.
    await page.locator('#capture-email').focus();

    expect(await eventNames(page)).toEqual(['Freedom Date Revealed', 'Explainer Opened', 'Email Focused']);
  });

  test('focusing the email field fires Email Focused once; blur and refocus add nothing', async ({ page }) => {
    await forceAnalytics(page);
    await installMocks(page);
    await page.goto('/index.html');
    await reveal(page);

    const email = page.locator('#capture-email');
    await email.focus();
    await email.blur();
    await email.focus();
    await email.blur();
    await email.focus();
    // The explainer is a sentinel: a repeated Email Focused would sit before it.
    await page.locator('#calc-method summary').click();
    await expect.poll(() => eventNames(page)).toEqual(['Freedom Date Revealed', 'Email Focused', 'Explainer Opened']);
    expect((await eventsNamed(page, 'Email Focused'))[0].props).toEqual({});
  });

  test('a successful submit fires Waitlist Submitted once with source_page home and the calculator bands', async ({ page }) => {
    await forceAnalytics(page);
    const calls = await installMocks(page);
    await page.goto('/index.html');
    await reveal(page);

    await submitWaitlist(page, 'home@example.com');
    await expect(page.locator('#pending-block')).toBeVisible({ timeout: 10_000 });
    await expect(page.locator('#pending-email')).toHaveText('home@example.com');

    expect(calls.signup).toHaveLength(1);
    expect(await eventNames(page)).toEqual(['Freedom Date Revealed', 'Email Focused', 'Waitlist Submitted']);
    expect((await eventsNamed(page, 'Waitlist Submitted'))[0].props).toEqual({
      source_page: 'home',
      revealed: 'yes',
      ...NO_UTM,
      return_scenario: 'moderate',
      freedom_age_band: '50-54',
    });
    expect(await eventsNamed(page, 'Waitlist Failed')).toEqual([]);
  });

  test('Waitlist Submitted carries the sanitised last-touch UTM keys and never utm_content or utm_term', async ({ page }) => {
    await forceAnalytics(page);
    await installMocks(page);
    await page.goto('/index.html?utm_source=QA&utm_medium=Test&utm_campaign=Launch&utm_content=video-1&utm_term=quit');
    await reveal(page);

    await submitWaitlist(page, 'utm@example.com');
    await expect(page.locator('#pending-block')).toBeVisible({ timeout: 10_000 });

    const submitted = await eventsNamed(page, 'Waitlist Submitted');
    expect(submitted).toHaveLength(1);
    expect(submitted[0].props).toEqual({
      source_page: 'home',
      revealed: 'yes',
      utm_source: 'qa',
      utm_medium: 'test',
      utm_campaign: 'launch',
      return_scenario: 'moderate',
      freedom_age_band: '50-54',
    });
  });
});

// =============================================================================
test.describe('about.html: email events without a calculator', () => {
  test('Email Focused fires once and Waitlist Submitted carries source_page about with no calculator props', async ({ page }) => {
    await forceAnalytics(page);
    const calls = await installMocks(page);
    await page.goto('/about.html');
    expect(await events(page)).toEqual([]);

    const email = page.locator('#capture-email');
    await email.focus();
    await email.blur();
    await email.focus();
    expect(await events(page)).toEqual([{ name: 'Email Focused', props: {}, url: 'http://localhost:5500/about.html' }]);

    await submitWaitlist(page, 'about@example.com');
    await expect(page.locator('#pending-block')).toBeVisible({ timeout: 10_000 });
    await expect(page.locator('#nav-cta')).toHaveText('Check your email');

    expect(calls.signup).toHaveLength(1);
    expect(calls.signup[0]).toMatchObject({ email: 'about@example.com', revealedBeforeSubmit: false });
    expect(await eventNames(page)).toEqual(['Email Focused', 'Waitlist Submitted']);
    expect((await eventsNamed(page, 'Waitlist Submitted'))[0].props).toEqual({ source_page: 'about', revealed: 'no', ...NO_UTM });
  });
});

// =============================================================================
test.describe('failure paths', () => {
  test('index: a confirmed duplicate (200 already_joined) fires Waitlist Failed reason duplicate and shows the already-on-the-list note', async ({ page }) => {
    await forceAnalytics(page);
    await installMocks(page, { signup: confirmedDuplicate('WAITLISTFOUNDER') });
    await page.goto('/index.html');

    await submitWaitlist(page, 'founder@example.com');
    await expect(page.locator('#success-block')).toBeVisible({ timeout: 10_000 });
    await expect(page.locator('#success-note')).toHaveText('You’re already on the list.');
    await expect(page.locator('#pending-block')).toBeHidden();

    expect(await eventNames(page)).toEqual(['Freedom Date Revealed', 'Email Focused', 'Waitlist Failed']);
    expect((await eventsNamed(page, 'Waitlist Failed'))[0].props).toEqual({ reason: 'duplicate' });
    expect(await eventsNamed(page, 'Waitlist Submitted')).toEqual([]);
    // The row's real status is what the browser remembers, not "already_joined".
    expect(await storage(page, 'waitlist_status')).toBe('WAITLISTFOUNDER');
    expect(await storage(page, 'waitlist_pending')).toBeNull();
  });

  test('about: a confirmed duplicate fires reason duplicate and the CTAs read as already joined', async ({ page }) => {
    await forceAnalytics(page);
    await installMocks(page, { signup: confirmedDuplicate('WAITLISTNORMAL') });
    await page.goto('/about.html');

    await submitWaitlist(page, 'normal@example.com');
    await expect(page.locator('#success-block')).toBeVisible({ timeout: 10_000 });
    await expect(page.locator('#success-note')).toHaveText('You’re already on the list.');
    await expect(page.locator('#nav-cta')).toHaveText('You’re in ✓');
    await expect(page.locator('#founder-cta')).toContainText('You’re on the priority waitlist');

    expect(await eventNames(page)).toEqual(['Email Focused', 'Waitlist Failed']);
    expect((await eventsNamed(page, 'Waitlist Failed'))[0].props).toEqual({ reason: 'duplicate' });
    expect(await storage(page, 'waitlist_status')).toBe('WAITLISTNORMAL');
  });

  test('a 500 fires Waitlist Failed reason server_error, shows the error, leaves the form usable and a retry succeeds', async ({ page }) => {
    await forceAnalytics(page);
    const calls = await installMocks(page, { signup: failOnceThenSucceed(SERVER_ERROR) });
    await page.goto('/index.html');

    await submitWaitlist(page, 'retry@example.com');
    await expect(page.locator('#waitlist-msg')).toContainText('Something went wrong. Please try again.');
    await expectFormUsable(page);
    await expect(page.locator('#capture-email')).toHaveValue('retry@example.com');
    expect(await storage(page, 'waitlist_status')).toBeNull();
    expect(await eventsNamed(page, 'Waitlist Failed')).toEqual([{ name: 'Waitlist Failed', props: { reason: 'server_error' }, url: 'http://localhost:5500/index.html' }]);
    expect(await eventsNamed(page, 'Waitlist Submitted')).toEqual([]);

    // The failure reset Turnstile; the helper waits for the fresh token.
    await submitWaitlist(page, 'retry@example.com');
    await expect(page.locator('#pending-block')).toBeVisible({ timeout: 10_000 });
    expect(calls.signup).toHaveLength(2);
    expect(await eventNames(page)).toEqual(['Freedom Date Revealed', 'Email Focused', 'Waitlist Failed', 'Waitlist Submitted']);
  });

  for (const [label, answer, reason, message] of [
    ['a 429 with code rate_limited', RATE_LIMITED, 'rate_limited', 'Too many requests'],
    ['a 429 with no code (status fallback)', { status: 429, body: {} }, 'rate_limited', 'Too many requests. Please try again in a minute.'],
    ['a 403 with code geo_blocked', GEO_BLOCKED, 'geo_blocked', 'available to US residents only'],
    ['a 400 with code captcha_failed', CAPTCHA_REJECTED, 'captcha_failed', 'Security check failed.'],
    ['a 500 with no code (reasonFor fallback)', { status: 500, body: {} }, 'server_error', 'Something went wrong. Please try again.'],
  ]) {
    test(`${label} fires Waitlist Failed reason ${reason} and leaves the form usable`, async ({ page }) => {
      await forceAnalytics(page);
      await installMocks(page, { signup: () => answer });
      await page.goto('/about.html');

      await submitWaitlist(page, 'fail@example.com');
      await expect(page.locator('#waitlist-msg')).toContainText(message);
      await expectFormUsable(page);
      expect(await storage(page, 'waitlist_status')).toBeNull();

      expect(await eventNames(page)).toEqual(['Email Focused', 'Waitlist Failed']);
      expect((await eventsNamed(page, 'Waitlist Failed'))[0].props).toEqual({ reason });
    });
  }

  test('a request that never gets a response fires reason network_error', async ({ page }) => {
    await forceAnalytics(page);
    const calls = await installMocks(page);
    // Registered after the harness so it wins: the POST dies on the wire.
    await page.route('**/api/waitlist', (route, request) => (request.method() === 'POST' ? route.abort('failed') : route.fallback()));
    await page.goto('/index.html');

    await revealIfNeeded(page);
    await page.fill('#capture-email', 'offline@example.com');
    await waitForTurnstile(page);
    await clickSubmit(page);
    await expect(page.locator('#waitlist-msg')).toContainText('Could not reach FRED. Please check your connection and try again.');
    await expectFormUsable(page);

    expect(calls.signup).toEqual([]);
    expect((await eventsNamed(page, 'Waitlist Failed')).map((e) => e.props)).toEqual([{ reason: 'network_error' }]);
  });

  test('a submit before Turnstile has issued a token fires reason captcha_failed and makes no request', async ({ page }) => {
    await forceAnalytics(page);
    const calls = await installMocks(page);
    // Turnstile never loads, so the page never receives a token.
    await page.route('https://challenges.cloudflare.com/**', (route) => route.abort('blockedbyclient'));
    await page.goto('/index.html');

    await revealIfNeeded(page);
    await page.fill('#capture-email', 'notoken@example.com');
    await clickSubmit(page);
    await expect(page.locator('#waitlist-msg')).toContainText('Security verification in progress. Please try again in a moment.');
    await expectFormUsable(page);

    expect(calls.signup).toEqual([]);
    expect((await eventsNamed(page, 'Waitlist Failed')).map((e) => e.props)).toEqual([{ reason: 'captcha_failed' }]);
  });

  test('client-side validation fires empty_email and invalid_email without calling the API', async ({ page }) => {
    await forceAnalytics(page);
    const calls = await installMocks(page);
    await page.goto('/index.html');
    await revealIfNeeded(page);

    await clickSubmit(page); // empty field; the form is novalidate so the handler sees it
    await expect(page.locator('#waitlist-msg')).toContainText('Please enter your email address.');
    await page.fill('#capture-email', 'foo');
    await clickSubmit(page);
    await expect(page.locator('#waitlist-msg')).toContainText('Please enter a valid email address.');

    expect(calls.signup).toEqual([]);
    expect((await eventsNamed(page, 'Waitlist Failed')).map((e) => e.props)).toEqual([{ reason: 'empty_email' }, { reason: 'invalid_email' }]);
  });

  test('Waitlist Failed stops at three per page load: a fourth failure fires no fourth event', async ({ page }) => {
    await forceAnalytics(page);
    const calls = await installMocks(page, { signup: () => SERVER_ERROR });
    await page.goto('/index.html');

    // 1: the API fails.
    await submitWaitlist(page, 'cap@example.com');
    await expect(page.locator('#waitlist-msg')).toContainText('Something went wrong');
    // 2: empty. 3: invalid. 4: invalid again, over the cap.
    await page.fill('#capture-email', '');
    await clickSubmit(page);
    await expect(page.locator('#waitlist-msg')).toContainText('Please enter your email address.');
    await page.fill('#capture-email', 'foo');
    await clickSubmit(page);
    await expect(page.locator('#waitlist-msg')).toContainText('Please enter a valid email address.');
    await page.fill('#capture-email', 'bar');
    await clickSubmit(page);
    await expect(page.locator('#waitlist-msg')).toContainText('Please enter a valid email address.');

    expect(calls.signup).toHaveLength(1);
    expect((await eventsNamed(page, 'Waitlist Failed')).map((e) => e.props)).toEqual([
      { reason: 'server_error' }, { reason: 'empty_email' }, { reason: 'invalid_email' },
    ]);
  });
});

// =============================================================================
test.describe('hostname gate', () => {
  test('without the force flag a full index.html journey records nothing and the page still works', async ({ page }) => {
    const calls = await installMocks(page);
    await page.goto('/index.html');
    expect(await storage(page, 'fred_analytics_force')).toBeNull();
    expect(await storage(page, 'plausible_ignore')).toBe('true'); // analytics.js arms Plausible's own kill switch off production

    await page.fill('#age-range', '35');
    await page.click('#return-seg [data-return="optimistic"]');
    await reveal(page);
    await page.click('#reveal-btn');
    await page.locator('#calc-method summary').click();
    await expect(page.locator('#calc-method')).toHaveJSProperty('open', true);
    await submitWaitlist(page, 'gated@example.com');
    await expect(page.locator('#pending-block')).toBeVisible({ timeout: 10_000 });

    expect(calls.signup).toHaveLength(1);
    expect(await events(page)).toEqual([]);
  });

  test('without the force flag about.html records nothing, not even a failure', async ({ page }) => {
    await installMocks(page, { signup: () => SERVER_ERROR });
    await page.goto('/about.html');
    expect(await storage(page, 'plausible_ignore')).toBe('true');

    await page.locator('#capture-email').focus();
    await submitWaitlist(page, 'gated@example.com');
    await expect(page.locator('#waitlist-msg')).toContainText('Something went wrong');

    expect(await events(page)).toEqual([]);
  });
});

// =============================================================================
test.describe('schema sweep', () => {
  test('every prop is a documented band string, never a raw number, and no call contains an email address', async ({ page }) => {
    await forceAnalytics(page);
    await installMocks(page, { signup: failOnceThenSucceed(SERVER_ERROR) });
    await page.goto('/index.html');

    // Odd raw values (on each slider's step grid), so any leak is unmistakable.
    await page.fill('#age-range', '47');
    await page.fill('#invest-range', '3350');
    await page.fill('#retire-range', '12300');
    await waitForEditsBand(page, '1-3');
    await reveal(page);
    // The age counts up for 900 ms and is aria-hidden until it settles.
    const resultAge = page.locator('#result-age');
    await expect(resultAge).not.toHaveAttribute('aria-hidden');
    const renderedAges = [await resultAge.textContent()];
    await page.fill('#age-range', '48');
    await waitForEditsBand(page, '4-8');
    await page.click('#reveal-btn');
    renderedAges.push(await resultAge.textContent());
    await page.locator('#calc-method summary').click();
    await submitWaitlist(page, 'sweep+tag@example.com');
    await expect(page.locator('#waitlist-msg')).toContainText('Something went wrong');
    await submitWaitlist(page, 'sweep+tag@example.com');
    await expect(page.locator('#pending-block')).toBeVisible({ timeout: 10_000 });

    const all = await events(page);
    expect(new Set(all.map((e) => e.name))).toEqual(new Set(LANDING_EVENTS));

    const raw = new Set(['47', '48', '3350', '12300', ...renderedAges.map((a) => String(a).trim())]);
    for (const e of all) {
      const keys = Object.keys(e.props).sort();
      const expected = e.name === 'Waitlist Submitted' && e.props.revealed === 'yes'
        ? [...SUBMITTED_KEYS, 'return_scenario', 'freedom_age_band']
        : EVENT_KEYS[e.name];
      expect(keys, `${e.name} prop keys`).toEqual([...expected].sort());
      for (const [k, v] of Object.entries(e.props)) {
        expect(typeof v, `${e.name}.${k} must be a string`).toBe('string');
        expect(raw.has(v), `${e.name}.${k} leaked a raw value: ${v}`).toBe(false);
        expect(/^\d+(\.\d+)?$/.test(v), `${e.name}.${k} is a bare number: ${v}`).toBe(false);
        if (VOCAB[k]) expect(VOCAB[k], `${e.name}.${k}`).toContain(v);
        else expect(UTM_KEYS, `${e.name}.${k} is not a documented key`).toContain(k);
      }
      expect(e.url).not.toContain('@');
    }
    expect(JSON.stringify(all)).not.toContain('@');
    expect(JSON.stringify(all)).not.toContain('example.com');
  });
});
