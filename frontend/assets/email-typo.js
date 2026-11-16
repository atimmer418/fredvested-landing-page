// "Did you mean name@gmail.com?" for the waitlist email fields (index.html, about.html,
// confirmed.html). suggestEmail() is pure: it looks only at the domain, against a curated
// list of consumer mail domains, and returns the corrected full address or null. attach()
// is the small DOM helper the pages share: it shows the hint on blur and holds the first
// submit of a suggestable value (no network call, no Turnstile token spent); a second
// submit of the same value goes ahead with what was typed.
//
// Loads as a classic script (window.FredEmailTypo) and as a CommonJS module for the
// unit tests in tests/unit/email-typo.test.js, the same way as projection.js.
(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  root.FredEmailTypo = api;
})(typeof window !== 'undefined' ? window : globalThis, function () {
  // The domains we correct TOWARD, most used first (ties in distance go to the earlier
  // one). Each is its own first label + the canonical rest.
  const KNOWN = [
    'gmail.com', 'yahoo.com', 'hotmail.com', 'outlook.com', 'icloud.com', 'aol.com',
    'live.com', 'msn.com', 'me.com', 'mac.com', 'comcast.net', 'att.net', 'verizon.net',
    'sbcglobal.net', 'protonmail.com', 'proton.me', 'ymail.com', 'googlemail.com',
    'rocketmail.com', 'bellsouth.net', 'charter.net', 'cox.net', 'earthlink.net',
    'optonline.net', 'frontier.com', 'windstream.net', 'hotmail.co.uk', 'yahoo.co.uk',
    'yahoo.ca', 'live.ca', 'aim.com',
  ];
  const KNOWN_SET = new Set(KNOWN);

  // Real domains a few edits from a KNOWN one, never "corrected".
  const REAL_LOOKALIKES = new Set([
    'mail.com', 'email.com', 'gmx.com', 'gmx.net', 'gmx.de', 'gmx.us', 'cloud.com',
    'mail.ru', 'yandex.com', 'yandex.ru', 'zoho.com', 'hey.com', 'fastmail.com',
    'tutanota.com', 'tuta.io', 'inbox.com', 'hushmail.com', 'outlook.de', 'mymail.com',
    'yahoo.fr', 'yahoo.de', 'yahoo.es', 'yahoo.it', 'yahoo.in', 'yahoo.co.in', 'yahoo.com.au',
    'hotmail.fr', 'hotmail.de', 'hotmail.it', 'hotmail.es', 'hotmail.ca', 'live.co.uk',
    'att.com', 'verizon.com', 'comcast.com', 'charter.com', 'cox.com', 'frontier.net',
    'wahoo.com', 'yahoo.net', 'gmail.net',
    // Real companies and mail services within the long names' distance 2.
    'frontiers.com', 'fronter.com', 'earthling.net', 'pocketmail.com',
  ]);

  // The first label -> its canonical domain, for the TLD fixes and the missing dot.
  const CANONICAL = {};
  KNOWN.forEach((d) => {
    const name = d.split('.')[0];
    if (!(name in CANONICAL)) CANONICAL[name] = d;
  });

  // Explicit TLD typos, by the canonical TLD they stand for.
  const TLD_TYPOS = {
    com: ['con', 'cmo', 'cm', 'om', 'comm', 'co', 'cpm', 'xom', 'vom', 'ocm', 'coom', 'cim', 'comn', 'cok', 'c', 'colm', 'c0m'],
    net: ['ent', 'nte', 'nt', 'ne', 'neet', 'nett', 'met', 'bet', 'ner', 'nwt'],
    me: [],
  };
  // These run only for a KNOWN first label (gmail.co, never acme.co), and before the
  // look-alike check: msn.co or me.co may be real sites, but nobody's mailbox is there.

  // Institutions are never consumer mail: leave them alone.
  const INSTITUTIONAL = /\.(edu|gov|mil)(\.[a-z]{2})?$|\.ac\.[a-z]{2}$|\.k12\.[a-z]{2}\.us$/;

  // How far a first label may be from a known one: the short ones (aol, msn, me, mac,
  // att, cox, aim, live) sit one edit from many real companies (aon, max, love), so they
  // get no fuzzy match at all, only the TLD and missing-dot fixes.
  function maxDistance(name) {
    if (name.length <= 4) return 0;
    if (name.length <= 7) return 1;
    return 2;
  }

  // Optimal string alignment distance (Damerau-Levenshtein where an adjacent
  // transposition counts 1 and no substring is edited twice).
  function osaDistance(a, b) {
    const n = a.length, m = b.length;
    if (Math.abs(n - m) > 2) return 3; // callers never accept more than 2
    let prev2 = null;
    let prev = new Array(m + 1);
    for (let j = 0; j <= m; j++) prev[j] = j;
    for (let i = 1; i <= n; i++) {
      const cur = new Array(m + 1);
      cur[0] = i;
      for (let j = 1; j <= m; j++) {
        const cost = a[i - 1] === b[j - 1] ? 0 : 1;
        let v = Math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost);
        if (i > 1 && j > 1 && a[i - 1] === b[j - 2] && a[i - 2] === b[j - 1]) v = Math.min(v, prev2[j - 2] + 1);
        cur[j] = v;
      }
      prev2 = prev;
      prev = cur;
    }
    return prev[m];
  }

  // The domain with an explicit TLD typo fixed, when its first label is a known name
  // (gmail.con -> gmail.com, comcast.ent -> comcast.net), else null.
  function fixTld(domain) {
    const dot = domain.indexOf('.');
    if (dot < 1) return null;
    const name = domain.slice(0, dot);
    const rest = domain.slice(dot + 1);
    const canonical = CANONICAL[name];
    if (!canonical) return null;
    const tld = canonical.slice(name.length + 1);
    return (TLD_TYPOS[tld] || []).indexOf(rest) !== -1 ? canonical : null;
  }

  // gmailcom -> gmail.com, gmail -> gmail.com, hotmailcon -> hotmail.com.
  function fixMissingDot(domain) {
    if (domain.indexOf('.') !== -1) return null;
    let best = null;
    Object.keys(CANONICAL).forEach((name) => {
      if (domain.slice(0, name.length) !== name) return;
      const rest = domain.slice(name.length);
      const canonical = CANONICAL[name];
      const tld = canonical.slice(name.length + 1);
      const ok = rest === '' || rest === tld.replace('.', '') || (TLD_TYPOS[tld] || []).indexOf(rest) !== -1;
      // The longest matching name wins (googlemailcom is googlemail, not a "google").
      if (ok && (!best || name.length > best.length)) best = name;
    });
    return best ? CANONICAL[best] : null;
  }

  // The closest known domain within its allowed distance, else null.
  function fuzzy(domain) {
    let best = null, bestD = Infinity;
    for (let k = 0; k < KNOWN.length; k++) {
      const known = KNOWN[k];
      const limit = maxDistance(known.split('.')[0]);
      if (!limit) continue;
      const d = osaDistance(domain, known);
      if (d >= 1 && d <= limit && d < bestD) { best = known; bestD = d; }
    }
    return best;
  }

  function suggestDomain(raw) {
    // A comma for a dot, a doubled dot (gmail..com) and trailing dots are typos too.
    let domain = raw.toLowerCase().replace(/,/g, '.').replace(/\.{2,}/g, '.').replace(/\.+$/, '');
    const changedChars = domain !== raw.toLowerCase();
    if (!/^[a-z0-9.-]+$/.test(domain) || domain[0] === '.') return null;
    if (KNOWN_SET.has(domain)) return changedChars ? domain : null;
    const tldFixed = fixTld(domain);
    if (tldFixed) return tldFixed;
    if (REAL_LOOKALIKES.has(domain) || INSTITUTIONAL.test(domain)) return null;
    const dotFixed = fixMissingDot(domain);
    if (dotFixed) return dotFixed;
    // No dot at all is not an address anyway: a misspelt name alone (gmial) is read as .com.
    if (domain.indexOf('.') === -1) return fuzzy(domain + '.com');
    // A known name with a real, non-typo TLD (yahoo.fr, gmail.net, verizon.com) is
    // somebody's real domain, or at least not one we can correct with confidence.
    const name = domain.split('.')[0];
    if (CANONICAL[name] && domain.indexOf('.') !== -1) return null;
    // The typed TLD might be a typo AND the name misspelt: gmial.con -> gmial.com -> gmail.com.
    const dot = domain.lastIndexOf('.');
    if (dot > 0) {
      const tld = domain.slice(dot + 1);
      for (const canonicalTld in TLD_TYPOS) {
        if (TLD_TYPOS[canonicalTld].indexOf(tld) === -1) continue;
        const hit = fuzzy(domain.slice(0, dot + 1) + canonicalTld);
        if (hit) return hit;
      }
    }
    return fuzzy(domain);
  }

  // The corrected full address (local part exactly as typed, domain lowercased), or
  // null when there is nothing to suggest or the input is not an address at all.
  function suggestEmail(email) {
    if (typeof email !== 'string') return null;
    const value = email.trim();
    if (!value || /\s/.test(value)) return null;
    const at = value.lastIndexOf('@');
    if (at < 1 || at === value.length - 1 || value.indexOf('@') !== at) return null;
    const local = value.slice(0, at);
    const fixed = suggestDomain(value.slice(at + 1));
    if (!fixed || fixed === value.slice(at + 1).toLowerCase()) return null;
    return local + '@' + fixed;
  }

  // A hint typed into the page directly under `options.after`, an empty live region until
  // shown (so it adds no height before then). Returns { hold, hide }:
  //   hold() -- call first thing on submit. True means "do not submit": the hint is now
  //   showing for this value. False means go ahead (and the hint is hidden).
  //   hide() -- for the success, pending and reset states.
  // A hint shown on blur less than SEEN_MS before the submit does not count as seen, and
  // neither does one shown by the blur that pressing the submit button itself causes (a
  // mouse or touch press blurs the field on pointerdown, before the submit on release,
  // however long the press is held). `options.submit` is that button; by default the
  // form's own submit button.
  const SEEN_MS = 1000;
  function attach(input, options) {
    const opts = options || {};
    const region = document.createElement('div');
    region.id = input.id + '-typo';
    region.setAttribute('aria-live', 'polite');
    (opts.after || input).insertAdjacentElement('afterend', region);
    let shown = null; // { value, at, held, seen }
    let insisted = null; // a value submitted after its hint was seen: never held again
    let pressing = false; // the submit button is being pressed (pointerdown, not yet up)
    const submit = opts.submit ||
      (input.form && input.form.querySelector('button[type="submit"], button:not([type]), input[type="submit"]'));
    if (submit) {
      submit.addEventListener('pointerdown', () => { pressing = true; });
      ['pointerup', 'pointercancel'].forEach((type) =>
        submit.addEventListener(type, () => setTimeout(() => { pressing = false; }, 0)));
    }

    function hide() {
      shown = null;
      region.replaceChildren();
    }
    function show(value, suggestion, held, seen) {
      shown = { value: value, at: Date.now(), held: held, seen: seen !== false };
      const p = document.createElement('p');
      p.className = opts.className || '';
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = opts.buttonClassName || '';
      btn.textContent = suggestion;
      btn.addEventListener('click', () => {
        input.value = suggestion;
        hide();
        input.focus();
      });
      p.append('Did you mean ', btn, '?');
      region.replaceChildren(p);
    }
    input.addEventListener('blur', () => {
      const value = input.value.trim();
      const suggestion = value === insisted ? null : suggestEmail(value);
      // Shown by pressing the submit button: not read yet, so that submit is still held.
      if (suggestion && !(shown && shown.value === value)) show(value, suggestion, false, !pressing);
    });
    input.addEventListener('input', () => {
      if (shown && input.value.trim() !== shown.value) hide();
    });
    function hold() {
      const value = input.value.trim();
      const suggestion = suggestEmail(value);
      if (!suggestion || value === insisted) { hide(); return false; }
      if (shown && shown.value === value && (shown.held || (shown.seen && Date.now() - shown.at >= SEEN_MS))) {
        insisted = value;
        hide();
        return false;
      }
      show(value, suggestion, true);
      return true;
    }
    return { hold: hold, hide: hide, element: region };
  }

  return {
    KNOWN_DOMAINS: KNOWN.slice(),
    suggestEmail: suggestEmail,
    osaDistance: osaDistance,
    attach: attach,
  };
});
