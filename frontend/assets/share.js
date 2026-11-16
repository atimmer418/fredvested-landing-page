// "Share my Freedom Date": the share link's `r` parameter and everything derived from it,
// as pure functions. `r` is "<rounded age>-<yyyy>-<mm>" (e.g. "52-2048-07"): the rounded
// freedom age and its month, and nothing else the visitor entered.
//
// The validation rule below exists three times and must stay identical in all three:
// ShareResult.java (the image endpoints), this file (the page) and frontend/_worker.js
// (the Open Graph tags on /s). Each is pinned by the same vector table in its own test;
// change all three or none.
//
// Loads as a classic script (window.FredShare) and as a CommonJS module for the unit
// tests in tests/unit/, the same dual pattern as projection.js. The page treats it as
// optional: without it there is no share row and nothing else changes. The inline script
// in index.html's <head> that strips `r` from the address on /s (before Plausible) does
// NOT use this file; it carries its own copy of stripParam()'s rule, and
// tests/unit/share.test.js holds the two identical.
(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  root.FredShare = api;
})(typeof window !== 'undefined' ? window : globalThis, function () {
  const MONTH_NAMES = ['January', 'February', 'March', 'April', 'May', 'June',
    'July', 'August', 'September', 'October', 'November', 'December'];

  const PARAM_SHAPE = /^(\d{2,3})-(\d{4})-(\d{2})$/;
  const MIN_AGE = 18;
  const MAX_AGE = 100;
  // The date runs from the current month to 72 years out, the calculator's own horizon.
  const MAX_MONTHS_OUT = 864;
  // The current age the pair implies (the age minus the years until the date) must be one
  // the age slider can produce, 18 to 80, with a year of tolerance for rounding. Anyone
  // can type a link; this keeps FRED from showing a result the calculator never could.
  const MIN_IMPLIED_AGE = 17;
  const MAX_IMPLIED_AGE = 81;

  // The path and the UTM values of the share link (FREDdocs/utm-conventions.md).
  const SHARE_PATH = '/s';
  const SHARE_UTM = 'utm_source=share&utm_medium=referral&utm_campaign=freedom_date';
  // `v1`: the images are cached for a year as immutable and message apps keep previews per
  // URL, so a change to anything drawn on them needs a new version here, in the two mappings
  // of ShareImageController.java and in _worker.js's image URL, together (pinned by the test).
  const IMAGE_PATH = '/api/og/v1/';

  // The analytics bands are 65 minus the shared age, the same bands the reveal sends.
  const USUAL_RETIREMENT_AGE = 65;

  function pad2(n) { return n < 10 ? '0' + n : String(n); }

  // `r` -> { age, year, month, ym, dateLabel }, or null when it is not valid at `now`
  // (a Date; its UTC month is "the current month").
  function parse(r, now) {
    if (typeof r !== 'string') return null;
    if (!(now instanceof Date) || isNaN(now.getTime())) return null;
    const m = PARAM_SHAPE.exec(r);
    if (!m) return null;
    const age = Number(m[1]);
    const year = Number(m[2]);
    const month = Number(m[3]);
    if (age < MIN_AGE || age > MAX_AGE) return null;
    if (month < 1 || month > 12) return null;
    const monthsOut = (year * 12 + month - 1) - (now.getUTCFullYear() * 12 + now.getUTCMonth());
    if (monthsOut < 0 || monthsOut > MAX_MONTHS_OUT) return null;
    const impliedAge = age - monthsOut / 12;
    if (impliedAge < MIN_IMPLIED_AGE || impliedAge > MAX_IMPLIED_AGE) return null;
    return {
      age: age,
      year: year,
      month: month,
      ym: m[2] + '-' + m[3],
      dateLabel: MONTH_NAMES[month - 1] + ' ' + year,
    };
  }

  // The saved signup result (`waitlist_freedom`: the rounded age and "July 2048") -> the
  // same shape as parse(), or null. Null covers a missing or malformed saved value and one
  // that has gone stale, so the page hides the share control rather than share a wrong age.
  function fromSaved(age, monthYear, now) {
    let ageText;
    if (typeof age === 'number' && Number.isInteger(age)) ageText = String(age);
    else if (typeof age === 'string') ageText = age;
    else return null;
    if (!/^\d{2,3}$/.test(ageText)) return null;
    if (typeof monthYear !== 'string') return null;
    const m = /^([A-Za-z]+) (\d{4})$/.exec(monthYear);
    if (!m) return null;
    const monthIndex = MONTH_NAMES.indexOf(m[1]);
    if (monthIndex < 0) return null;
    return parse(Number(ageText) + '-' + m[2] + '-' + pad2(monthIndex + 1), now);
  }

  // A result -> "52-2048-07".
  function param(result) {
    return result.age + '-' + result.ym;
  }

  function trimOrigin(origin) {
    return String(origin).replace(/\/+$/, '');
  }

  // The link the visitor shares. Built from the page's own origin, so it works on dev and prod.
  function shareUrl(origin, result) {
    return trimOrigin(origin) + SHARE_PATH + '?r=' + param(result) + '&' + SHARE_UTM;
  }

  // The 1200x630 link-preview image and the 1080x1920 story image, both on the API origin.
  function linkImageUrl(apiOrigin, result) {
    return trimOrigin(apiOrigin) + IMAGE_PATH + result.age + '/' + result.ym + '.png';
  }

  function storyImageUrl(apiOrigin, result) {
    return trimOrigin(apiOrigin) + IMAGE_PATH + 'story/' + result.age + '/' + result.ym + '.png';
  }

  // The properties `Result Shared` carries: bands only, derived from the SHARED age (on a
  // return visit the live render shows the default sliders). The age itself never travels.
  function band(age) {
    const n = USUAL_RETIREMENT_AGE - age;
    if (!(n >= 1)) return { stake_years_band: 'hidden', stake_source: 'hidden' };
    return { stake_years_band: n <= 5 ? '1-5' : n <= 10 ? '6-10' : '11+', stake_source: 'plan' };
  }

  function isParamR(segment) {
    const eq = segment.indexOf('=');
    const key = eq < 0 ? segment : segment.slice(0, eq);
    if (key === 'r') return true;
    try { return decodeURIComponent(key) === 'r'; } catch (e) { return false; }
  }

  // location.search without `r`: "" or "?...". Every other parameter is kept byte for
  // byte (the UTM values are read later by attribution.js), so nothing is re-encoded.
  function stripParam(search) {
    if (typeof search !== 'string') return '';
    const query = search.charAt(0) === '?' ? search.slice(1) : search;
    if (query === '') return '';
    const kept = query.split('&').filter(function (segment) { return !isParamR(segment); });
    const rest = kept.join('&');
    return rest === '' ? '' : '?' + rest;
  }

  return {
    MONTH_NAMES: MONTH_NAMES,
    parse: parse,
    fromSaved: fromSaved,
    param: param,
    shareUrl: shareUrl,
    linkImageUrl: linkImageUrl,
    storyImageUrl: storyImageUrl,
    band: band,
    stripParam: stripParam,
  };
});
