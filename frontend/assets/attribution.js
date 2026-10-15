// Attribution capture. Loaded on every page before analytics.js.
//
// Records which content brought a visitor here so it can be stored with their
// waitlist signup (Plausible is anonymous and cannot be joined to a person).
// Parses utm_source, utm_medium, utm_campaign, utm_content and utm_term only.
// Two snapshots:
//   first touch -> localStorage  "fred_attr_first"  written once, never overwritten
//   last touch  -> sessionStorage "fred_attr_last"  overwritten on every load that
//                                                   carries attribution parameters
// A load with no attribution parameters writes neither snapshot.
//
// Deliberately NO persistent visitor ID: a durable first-party ID would be a
// cookie in all but name and would need a privacy-policy revision.
(function (global) {
  // UTM only. A `ref` parameter is deliberately not captured: the disclosures
  // counsel is drafting describe UTM attribution and nothing else.
  const PARAMS = ['utm_source', 'utm_medium', 'utm_campaign', 'utm_content', 'utm_term'];
  const FIRST_KEY = 'fred_attr_first';
  const LAST_KEY = 'fred_attr_last';
  const VISIT_KEY = 'fred_visit';
  const MAX_LEN = 100;

  // The client-side sanitizer is for data quality; the server re-applies the
  // same rules as the security boundary. Lowercase, keep only [a-z0-9-_.],
  // cap at 100 chars, drop if nothing is left.
  function sanitize(value) {
    if (typeof value !== 'string') return null;
    const cleaned = value.toLowerCase().replace(/[^a-z0-9\-_.]/g, '').slice(0, MAX_LEN);
    return cleaned.length ? cleaned : null;
  }

  function readParams() {
    const out = {};
    let any = false;
    let search;
    try { search = new URLSearchParams(global.location.search); } catch (e) { return out; }
    PARAMS.forEach((key) => {
      const v = sanitize(search.get(key));
      if (v) { out[key] = v; any = true; }
    });
    return any ? out : null;
  }

  // Hostname only, never the full referrer URL (which can carry someone else's
  // query strings and search terms).
  function referrerHost() {
    try {
      const r = global.document.referrer;
      if (!r) return null;
      const host = new URL(r).hostname.toLowerCase();
      // Same-site navigation isn't a referral.
      if (host === global.location.hostname.toLowerCase()) return null;
      return host.slice(0, 255) || null;
    } catch (e) { return null; }
  }

  // Coarse on purpose: viewport width first, user agent as a tie-breaker.
  function deviceType() {
    try {
      const ua = global.navigator.userAgent || '';
      const w = global.innerWidth || 0;
      if (/iPad|Tablet|PlayBook|Silk/i.test(ua) || (/Android/i.test(ua) && !/Mobile/i.test(ua))) return 'tablet';
      if (/Mobi|iPhone|iPod|Android/i.test(ua) || w < 768) return 'mobile';
      if (w < 1024) return 'tablet';
      return 'desktop';
    } catch (e) { return 'desktop'; }
  }

  // Storage can throw (site data blocked, private windows, quota). A failure
  // here must never reach the page.
  function read(storage, key) {
    try { return JSON.parse(global[storage].getItem(key)); } catch (e) { return null; }
  }
  function write(storage, key, value) {
    try { global[storage].setItem(key, JSON.stringify(value)); } catch (e) { /* storage blocked */ }
  }

  function capture() {
    // Visit-level facts, recorded once per session so landing_path is the page
    // the visitor actually landed on, not whichever page they submit from.
    if (!read('sessionStorage', VISIT_KEY)) {
      write('sessionStorage', VISIT_KEY, {
        referrer_host: referrerHost(),
        landing_path: String(global.location.pathname || '/').slice(0, 255),
        device_type: deviceType(),
      });
    }

    const params = readParams();
    if (!params) return; // no attribution on this load: preserve whatever is stored

    write('sessionStorage', LAST_KEY, params);
    if (!read('localStorage', FIRST_KEY)) {
      write('localStorage', FIRST_KEY, Object.assign({}, params, {
        first_touch_at: new Date().toISOString(),
      }));
    }
  }

  // Flat, camelCase (the waitlist API's JSON convention), ready to merge into
  // the POST body. Missing values are simply absent.
  function get() {
    const first = read('localStorage', FIRST_KEY) || {};
    const last = read('sessionStorage', LAST_KEY) || {};
    const visit = read('sessionStorage', VISIT_KEY) || {};
    const out = {};
    const put = (k, v) => { if (v !== null && v !== undefined) out[k] = v; };
    put('utmSource', last.utm_source);
    put('utmMedium', last.utm_medium);
    put('utmCampaign', last.utm_campaign);
    put('utmContent', last.utm_content);
    put('utmTerm', last.utm_term);
    put('firstUtmSource', first.utm_source);
    put('firstUtmCampaign', first.utm_campaign);
    put('firstUtmContent', first.utm_content);
    put('firstTouchAt', first.first_touch_at);
    put('referrerHost', visit.referrer_host);
    put('landingPath', visit.landing_path);
    put('deviceType', visit.device_type);
    return out;
  }

  // What Plausible gets: only the low-cardinality last-touch keys, with the
  // spec's fallbacks. utm_content is high cardinality (one value per video)
  // and goes to our own database only.
  function forPlausible() {
    const last = read('sessionStorage', LAST_KEY) || {};
    return {
      utm_source: last.utm_source || 'direct',
      utm_medium: last.utm_medium || 'none',
      utm_campaign: last.utm_campaign || 'none',
    };
  }

  try { capture(); } catch (e) { /* attribution must never break the page */ }

  global.FredAttribution = { get, forPlausible, sanitize };
})(window);
