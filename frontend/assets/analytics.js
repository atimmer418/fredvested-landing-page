// The one place analytics events are defined. Every event name, every property
// key, every band boundary lives here and nowhere else. Nothing outside this
// file may call window.plausible(). Canonical schema: FREDdocs/analytics-events.md.
//
// Load order on a page: attribution.js, then this file, then the page script.
// Both are classic scripts at the end of <body>, which run before Plausible's
// deferred script executes -- that ordering is what lets the hostname gate
// below switch the pageview off before it is sent.
//
// Plausible bills pageviews + custom events (its automatic "engagement" events
// are excluded from billing). Per visitor per page session this file can send
// at most: Calc Engaged, Explainer Opened, Freedom Date Revealed, Recalculated,
// Email Focused, Waitlist Submitted (each once) and Waitlist Failed (max 3).
// Waitlist Confirmed fires once on confirmed.html, a separate page load with
// its own pageview, so it sits outside that 7-per-visitor budget.
(function (global) {
  const PRODUCTION_HOSTS = ['fredvested.com', 'www.fredvested.com'];
  const FORCE_KEY = 'fred_analytics_force'; // localStorage; '1' sends from any host
  const PLAUSIBLE_IGNORE_KEY = 'plausible_ignore'; // Plausible's own kill switch, read by its script

  const ONCE = new Set([
    'Calc Engaged', 'Explainer Opened', 'Freedom Date Revealed', 'Recalculated',
    'Email Focused', 'Waitlist Submitted', 'Waitlist Confirmed',
  ]);
  const CONFIRM_BANDS = new Set(['<1', '1-6', '6-24', '24-72', '72+']);
  const FAILED_LIMIT = 3;
  const FAILED_REASONS = new Set([
    'invalid_email', 'empty_email', 'duplicate', 'rate_limited', 'server_error',
    'network_error', 'timeout', 'geo_blocked', 'captcha_failed',
  ]);
  const FIELDS = new Set(['age', 'monthly_invest', 'target_income', 'return_scenario']);
  const SCENARIOS = new Set(['conservative', 'moderate', 'optimistic']);
  const EDIT_DEBOUNCE_MS = 400;

  // --- bands: raw numbers never leave this file ---------------------------
  function band(value, edges, labels) {
    // edges: ascending thresholds; labels: edges.length + 1 entries
    const n = Number(value);
    if (!Number.isFinite(n)) return null;
    for (let i = 0; i < edges.length; i++) if (n < edges[i]) return labels[i];
    return labels[labels.length - 1];
  }
  const ageBand = (v) => band(v, [25, 30, 35, 40, 45, 50, 55], ['18-24', '25-29', '30-34', '35-39', '40-44', '45-49', '50-54', '55+']);
  const investBand = (v) => band(v, [100, 250, 500, 1000, 2000, 5000], ['0-99', '100-249', '250-499', '500-999', '1000-1999', '2000-4999', '5000+']);
  const targetIncomeBand = (v) => band(v, [2500, 5000, 7500, 10000], ['<2500', '2500-4999', '5000-7499', '7500-9999', '10000+']);
  const freedomAgeBand = (v) => (v === null || v === undefined ? 'unreachable' : band(v, [45, 50, 55, 60, 65], ['<45', '45-49', '50-54', '55-59', '60-64', '65+']));
  const secondsBand = (v) => band(v, [11, 31, 61, 121], ['0-10', '11-30', '31-60', '61-120', '120+']);
  const editsBand = (v) => band(v, [1, 4, 9, 16], ['0', '1-3', '4-8', '9-15', '16+']);

  // --- session state (in memory only; a new page load starts over) --------
  const fired = new Set();
  let failedCount = 0;
  let enabled = false;
  let debug = false;
  let firstInteractionAt = null;
  let editCount = 0;
  let editsAtFirstReveal = null;
  const editTimers = {};
  let calc = { scenario: null, freedomAge: undefined, revealed: false };

  function log() {
    if (debug && global.console) global.console.log.apply(global.console, ['[analytics]'].concat([].slice.call(arguments)));
  }
  function storageGet(key) { try { return global.localStorage.getItem(key); } catch (e) { return null; } }
  function storageSet(key, v) { try { global.localStorage.setItem(key, v); } catch (e) { /* blocked */ } }
  function storageRemove(key) { try { global.localStorage.removeItem(key); } catch (e) { /* blocked */ } }

  // Sends only on the production hostnames. Anywhere else (previews, local,
  // tunnels) is a no-op so preview traffic never lands in the production
  // dashboard -- both for the events below and for Plausible's own automatic
  // pageview, which is switched off through the localStorage flag its script
  // honours. Escape hatch for verifying on a preview:
  //   localStorage.fred_analytics_force = '1'
  let initialized = false;
  function init() {
    if (initialized) return;
    initialized = true;
    try {
      const host = String(global.location.hostname || '').toLowerCase();
      const isProduction = PRODUCTION_HOSTS.indexOf(host) !== -1;
      const forced = storageGet(FORCE_KEY) === '1';
      enabled = isProduction || forced;
      debug = !isProduction;
      if (!isProduction) {
        // Never touch the flag on production: a site owner may have set it to
        // exclude their own visits (Plausible's documented opt-out).
        if (forced) storageRemove(PLAUSIBLE_IGNORE_KEY); else storageSet(PLAUSIBLE_IGNORE_KEY, 'true');
      }
      log(enabled ? 'enabled' : 'disabled (not production; set localStorage.' + FORCE_KEY + "='1' to force)");
    } catch (e) { enabled = false; }
  }

  // --- event schema ---------------------------------------------------------
  // Each builder receives the raw context the page passed in and returns the
  // exact props that go to Plausible, or false to drop the event.
  const BUILDERS = {
    'Calc Engaged': (ctx) => (FIELDS.has(ctx.first_field) ? { first_field: ctx.first_field } : false),

    'Explainer Opened': () => ({}),

    'Freedom Date Revealed': (ctx) => {
      if (!SCENARIOS.has(ctx.return_scenario)) return false;
      const unreachable = ctx.freedom_age === null || ctx.freedom_age === undefined;
      calc = { scenario: ctx.return_scenario, freedomAge: unreachable ? null : ctx.freedom_age, revealed: true };
      if (editsAtFirstReveal === null) editsAtFirstReveal = editCount;
      const secs = firstInteractionAt === null ? 0 : Math.round((Date.now() - firstInteractionAt) / 1000);
      return {
        return_scenario: ctx.return_scenario,
        age_band: ageBand(ctx.age),
        invest_band: investBand(ctx.invest),
        target_income_band: targetIncomeBand(ctx.retire),
        freedom_age_band: freedomAgeBand(unreachable ? null : ctx.freedom_age),
        sec_to_reveal_band: secondsBand(secs),
        input_edits_band: editsBand(editCount),
      };
    },

    'Recalculated': (ctx) => {
      if (!calc.revealed) return false; // only after a successful first reveal
      if (SCENARIOS.has(ctx.return_scenario)) {
        calc.scenario = ctx.return_scenario;
        calc.freedomAge = (ctx.freedom_age === null || ctx.freedom_age === undefined) ? null : ctx.freedom_age;
      }
      return { edits_after_reveal_band: editsBand(editCount - (editsAtFirstReveal || 0)) };
    },

    'Email Focused': () => ({}),

    // No calculator properties when the page never revealed (about.html, or
    // a submit before revealing): revealed:'no' and the utm keys only.
    'Waitlist Submitted': (ctx) => {
      const attr = (global.FredAttribution && global.FredAttribution.forPlausible()) || {};
      const props = {
        source_page: ctx.source_page === 'about' ? 'about' : 'home',
        revealed: calc.revealed ? 'yes' : 'no',
        utm_source: attr.utm_source || 'direct',
        utm_medium: attr.utm_medium || 'none',
        utm_campaign: attr.utm_campaign || 'none',
      };
      if (calc.revealed) {
        props.return_scenario = calc.scenario;
        props.freedom_age_band = freedomAgeBand(calc.freedomAge);
      }
      return props;
    },

    'Waitlist Failed': (ctx) => (FAILED_REASONS.has(ctx.reason) ? { reason: ctx.reason } : { reason: 'server_error' }),

    // Fired by confirmed.html. The backend bands the hours from confirmation
    // email to click and passes it in the query string; anything else is dropped.
    'Waitlist Confirmed': (ctx) => (CONFIRM_BANDS.has(ctx.hours_to_confirm_band)
      ? { hours_to_confirm_band: ctx.hours_to_confirm_band }
      : false),
  };

  // Fire an event. Never throws: an analytics failure must never break the
  // calculator or block a signup.
  function track(name, ctx) {
    try {
      const build = BUILDERS[name];
      if (!build) { log('unknown event dropped:', name); return false; }
      if (ONCE.has(name) && fired.has(name)) return false;
      if (name === 'Waitlist Failed' && failedCount >= FAILED_LIMIT) return false;
      const props = build(ctx || {});
      if (props === false) return false;
      if (ONCE.has(name)) fired.add(name);
      if (name === 'Waitlist Failed') failedCount++;
      if (!enabled) { log('(gated)', name, props); return false; }
      if (typeof global.plausible !== 'function') return false;
      global.plausible(name, { props: props });
      log(name, props);
      return true;
    } catch (e) {
      if (debug && global.console) global.console.error('[analytics] track failed', e);
      return false;
    }
  }

  // Called by the page from every calculator input listener. Counts discrete
  // edits (400ms debounce per field, so one slider drag is one edit), starts
  // the time-to-reveal clock on the first interaction, and fires Calc Engaged
  // once with the field that started it.
  function recordInput(field) {
    try {
      if (!FIELDS.has(field)) return;
      if (firstInteractionAt === null) firstInteractionAt = Date.now();
      if (editTimers[field]) global.clearTimeout(editTimers[field]);
      editTimers[field] = global.setTimeout(() => { editTimers[field] = null; editCount++; }, EDIT_DEBOUNCE_MS);
      track('Calc Engaged', { first_field: field });
    } catch (e) { /* never break the calculator */ }
  }

  function getAttribution() {
    try { return (global.FredAttribution && global.FredAttribution.get()) || {}; } catch (e) { return {}; }
  }

  // Banded view of the current calculator state, for the page or for tests.
  function getCalcContext() {
    return {
      revealed: calc.revealed ? 'yes' : 'no',
      return_scenario: calc.scenario,
      freedom_age_band: calc.revealed ? freedomAgeBand(calc.freedomAge) : null,
      input_edits_band: editsBand(editCount),
    };
  }

  global.FredAnalytics = { init, track, recordInput, getAttribution, getCalcContext };

  // Runs at load rather than on DOMContentLoaded: this script sits at the end of
  // <body>, so the DOM is already parsed, and the gate must be decided before
  // Plausible's deferred script executes (which is after all body scripts).
  init();
})(window);
