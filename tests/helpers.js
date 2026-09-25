// Shared harness for the end-to-end suite.
//
// The pages are served by frontend/serve.py; nothing else runs. Everything the
// pages would call over the network is intercepted here, in the browser:
//   - /js/script.js         -> tests/fixtures/plausible-recorder.js (records plausible() calls)
//   - /api/event            -> 202 (never reached with the recorder, kept for safety)
//   - .../api/waitlist/stats            -> the stats JSON the test chooses
//   - .../api/waitlist (POST)           -> the signup response the test chooses
//   - .../api/waitlist/resend-confirmation -> {"status":"ok"}
// On localhost, assets/waitlist.js points the API at http://localhost:8081, and
// the routes below match any host, so no backend is needed.
//
// Analytics: assets/analytics.js only fires on the production hostnames unless
// localStorage.fred_analytics_force === '1' (the documented escape hatch). Tests
// that assert events call forceAnalytics(page) before navigating.
const fs = require('fs');
const path = require('path');
const { expect } = require('@playwright/test');

const RECORDER = fs.readFileSync(path.join(__dirname, 'fixtures', 'plausible-recorder.js'), 'utf8');

// The only hosts a page may talk to. The privacy policy names Plausible (reached
// through our own origin), Resend (never from the browser), Cloudflare and
// Railway (our API origins). Turnstile is served from challenges.cloudflare.com.
const ALLOWED_HOSTS = new Set([
  'localhost',
  '127.0.0.1',
  'challenges.cloudflare.com',
]);

const FRESH_SIGNUP = {
  status: 'WAITLISTNORMAL',
  requiresConfirmation: true,
  count: 41,
  founderCount: 12,
  avgFreedomAge: 0,
  projectionCount: 0,
  projectionStartDate: null,
  projectionEndDate: null,
};

const STATS = {
  status: 'success',
  count: 40,
  founderCount: 12,
  avgFreedomAge: 0,
  projectionCount: 0,
  projectionStartDate: null,
  projectionEndDate: null,
};

/**
 * Installs every network interception. `signup` may be an object (the JSON to
 * return with 200) or a function (route, request) => ({ status, body }) for
 * failure paths. Returns a recorder of the API calls the page made.
 */
async function installMocks(page, { stats = STATS, signup = FRESH_SIGNUP, resend = { status: 'ok' } } = {}) {
  const calls = { signup: [], resend: [], stats: 0, events: [] };

  await page.route('**/js/script.js', (route) => route.fulfill({ status: 200, contentType: 'application/javascript', body: RECORDER }));
  await page.route('**/api/event', (route) => {
    calls.events.push(route.request().postData());
    route.fulfill({ status: 202, contentType: 'text/plain', body: 'ok' });
  });
  await page.route('**/api/waitlist/stats', (route) => {
    calls.stats += 1;
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(stats) });
  });
  await page.route('**/api/waitlist/resend-confirmation', (route) => {
    calls.resend.push(JSON.parse(route.request().postData() || '{}'));
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(resend) });
  });
  await page.route('**/api/waitlist', (route, request) => {
    if (request.method() !== 'POST') return route.fallback();
    const body = JSON.parse(request.postData() || '{}');
    calls.signup.push(body);
    const answer = typeof signup === 'function' ? signup(body) : { status: 200, body: signup };
    route.fulfill({ status: answer.status, contentType: 'application/json', body: JSON.stringify(answer.body) });
  });
  return calls;
}

/** Enables analytics on localhost through the documented escape hatch. */
async function forceAnalytics(page) {
  await page.addInitScript(() => {
    try { localStorage.setItem('fred_analytics_force', '1'); } catch (e) { /* storage blocked */ }
  });
}

/** Pre-seeds localStorage before any page script runs (e.g. a saved status). */
async function seedStorage(page, entries) {
  await page.addInitScript((e) => {
    try { for (const [k, v] of Object.entries(e)) localStorage.setItem(k, v); } catch (err) { /* storage blocked */ }
  }, entries);
}

/** Makes every localStorage access throw, like a browser with site data blocked. */
async function blockStorage(page) {
  await page.addInitScript(() => {
    const boom = () => { throw new Error('storage blocked'); };
    Object.defineProperty(window, 'localStorage', { get: boom, configurable: true });
  });
}

/** The plausible() calls the page has made so far: [{ name, props, url }]. */
async function events(page) {
  return page.evaluate(() => (window.__plausible || []).map((e) => ({ name: e.name, props: e.props, url: e.url })));
}

async function eventNames(page) {
  return (await events(page)).map((e) => e.name);
}

/** Collects the host of every request the page makes from now on. */
function collectHosts(page) {
  const seen = new Map();
  page.on('request', (request) => {
    try {
      const host = new URL(request.url()).hostname;
      seen.set(host, (seen.get(host) || 0) + 1);
    } catch (e) { /* data: or about: urls */ }
  });
  return seen;
}

/** Waits for Turnstile (test sitekey on localhost) to hand the form a token. */
async function waitForTurnstile(page) {
  await page.waitForFunction(() => {
    const el = document.querySelector('input[name="cf-turnstile-response"]');
    return !!(el && el.value);
  }, null, { timeout: 20_000 });
}

/**
 * On index.html the waitlist form sits inside the reveal zone (calculator first):
 * it appears only after "Reveal my Freedom Date". Clicks the reveal when needed.
 */
async function revealIfNeeded(page) {
  const email = page.locator('#capture-email');
  if (await email.isVisible()) return;
  const reveal = page.locator('#reveal-btn');
  if (await reveal.count()) {
    await reveal.click();
    await email.waitFor({ state: 'visible', timeout: 10_000 });
  }
}

/** Fills the email field and submits the waitlist form, waiting for the API call. */
async function submitWaitlist(page, email) {
  await revealIfNeeded(page);
  await page.fill('#capture-email', email);
  await waitForTurnstile(page);
  const done = page.waitForResponse((r) => /\/api\/waitlist(\?|$)/.test(r.url()) && r.request().method() === 'POST');
  await page.click('#join-waitlist-btn');
  await done;
}

async function storage(page, key) {
  return page.evaluate((k) => { try { return localStorage.getItem(k); } catch (e) { return null; } }, key);
}

function assertOnlyAllowedHosts(seen) {
  const offenders = [...seen.keys()].filter((h) => !ALLOWED_HOSTS.has(h));
  expect(offenders, `requests to hosts outside the allowed set: ${offenders.join(', ')}`).toEqual([]);
}

module.exports = {
  ALLOWED_HOSTS, FRESH_SIGNUP, STATS,
  installMocks, forceAnalytics, seedStorage, blockStorage,
  events, eventNames, collectHosts, waitForTurnstile, revealIfNeeded, submitWaitlist, storage, assertOnlyAllowedHosts,
};
