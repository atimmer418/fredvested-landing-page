// Shared waitlist API + Turnstile helpers used by index.html and about.html.
(function (global) {
  // Private LAN IPs (192.168.x.x, 10.x.x.x, 172.16-31.x.x) count as local so a
  // phone on the same wifi can hit the dev server via this machine's IP.
  const isLanHost = /^(192\.168\.|10\.|172\.(1[6-9]|2\d|3[01])\.)/.test(location.hostname);

  const isLocal = location.hostname === 'localhost'
    || location.hostname === '127.0.0.1'
    || location.protocol === 'file:'
    || isLanHost;

  const isDev = isLocal
    || location.hostname.endsWith('.ngrok-free.app')
    || location.hostname.endsWith('.ngrok.io');

  // Cloudflare test key (always passes) in dev; real sitekey in production
  const TURNSTILE_SITEKEY = isDev
    ? '1x00000000000000000000AA'
    : '0x4AAAAAACr1ix6Vcrw7VxES';

  // Pages served locally talk to the local backend
  // (./gradlew bootRun --args='--spring.profiles.active=local') on the same
  // host that served the page: localhost on this machine, the machine's LAN
  // IP when opened from a phone. Ngrok tunnels still hit the deployed dev API.
  const API_BASE = isLocal
    ? 'http://' + (location.hostname || 'localhost') + ':8081/api/waitlist'
    : isDev
      ? 'https://lpapi-dev.fredvested.com/api/waitlist'
      : 'https://lpapi.fredvested.com/api/waitlist';

  // Aggregate proof numbers (waitlist counts, projection averages) render only at
  // or above this sample size; below it the elements stay hidden entirely.
  const STAT_TILE_MIN_N = 25;

  function isValidEmail(email) {
    return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email);
  }

  // localStorage throws in browsers with site data blocked; a failed read/write
  // must never take the signup flow down with it.
  function getSavedStatus() {
    try { return localStorage.getItem('waitlist_status'); } catch (e) { return null; }
  }
  function saveStatus(status) {
    try { localStorage.setItem('waitlist_status', status); } catch (e) { /* storage blocked */ }
  }

  // The freedom date computed at signup, so other pages can show it back.
  function saveFreedomDate(age, monthYear) {
    try { localStorage.setItem('waitlist_freedom', JSON.stringify({ age: age, monthYear: monthYear })); } catch (e) { /* storage blocked */ }
  }
  function getFreedomDate() {
    try { return JSON.parse(localStorage.getItem('waitlist_freedom')); } catch (e) { return null; }
  }

  function fetchStats() {
    return fetch(API_BASE + '/stats').then((r) => r.json());
  }

  // Invisible Turnstile bound to a form element. Returns a handle; token is
  // null until the widget script loads and the challenge completes.
  function createTurnstile(formEl) {
    let token = null;
    let widgetId = null;
    (function init() {
      if (typeof turnstile === 'undefined') { setTimeout(init, 200); return; }
      widgetId = turnstile.render(formEl, {
        sitekey: TURNSTILE_SITEKEY,
        appearance: 'interaction-only',
        callback: (t) => { token = t; },
        'expired-callback': () => { token = null; },
        'error-callback': () => { token = null; },
      });
    })();
    return {
      getToken: () => token,
      reset: () => {
        if (widgetId !== null && typeof turnstile !== 'undefined') {
          turnstile.reset(widgetId);
          token = null;
        }
      },
    };
  }

  // Structured failure reasons (the analytics vocabulary; see assets/analytics.js).
  // The backend names the reason in a `code` field; the HTTP status is the fallback
  // for responses that carry none. The visitor only ever sees a generic message.
  const SUBMIT_TIMEOUT_MS = 15000;
  const FAILURE_REASONS = ['invalid_email', 'empty_email', 'duplicate', 'rate_limited',
    'server_error', 'network_error', 'timeout', 'geo_blocked', 'captcha_failed'];
  const REASON_BY_STATUS = { 429: 'rate_limited', 403: 'geo_blocked', 409: 'duplicate' };
  const GENERIC_MESSAGES = {
    invalid_email: 'Please enter a valid email address.',
    empty_email: 'Please enter your email address.',
    rate_limited: 'Too many requests. Please try again in a minute.',
    geo_blocked: 'FRED is currently available to US residents only.',
    captcha_failed: 'Security check failed. Please try again.',
    timeout: 'That took too long. Please check your connection and try again.',
    network_error: 'Could not reach FRED. Please check your connection and try again.',
    server_error: 'Something went wrong. Please try again.',
  };

  class WaitlistError extends Error {
    constructor(reason, message) {
      super(message || GENERIC_MESSAGES[reason] || GENERIC_MESSAGES.server_error);
      this.name = 'WaitlistError';
      this.reason = reason;
    }
  }

  function reasonFor(status, code) {
    if (FAILURE_REASONS.indexOf(code) !== -1) return code;
    return REASON_BY_STATUS[status] || 'server_error';
  }

  function submitWaitlist(payload) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), SUBMIT_TIMEOUT_MS);
    return fetch(API_BASE, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
      signal: controller.signal,
    }).then(async (res) => {
      let data = {};
      try { data = await res.json(); } catch (e) { /* non-JSON body (proxy error page, empty 5xx) */ }
      if (!res.ok) throw new WaitlistError(reasonFor(res.status, data.code), data.message);
      return data;
    }).catch((err) => {
      if (err instanceof WaitlistError) throw err;
      throw new WaitlistError(err && err.name === 'AbortError' ? 'timeout' : 'network_error');
    }).finally(() => clearTimeout(timer));
  }

  // Clean URLs (/about, /privacy) resolve to .html files when developing
  // locally; production hosting maps the clean paths itself.
  function devLinkMap(map) {
    if (!isDev) return;
    document.querySelectorAll('a[href]').forEach((a) => {
      const href = a.getAttribute('href');
      if (map[href]) a.href = map[href];
    });
  }

  global.FredWaitlist = {
    isDev,
    STAT_TILE_MIN_N,
    isValidEmail,
    getSavedStatus,
    saveStatus,
    saveFreedomDate,
    getFreedomDate,
    fetchStats,
    createTurnstile,
    submitWaitlist,
    devLinkMap,
  };
})(window);
