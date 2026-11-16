// Unit tests for frontend/assets/email-typo.js (node --test, no browser). suggestEmail()
// looks only at the domain: it corrects TOWARD a curated list of consumer mail domains
// (transpositions, one or two edits by length, explicit TLD typos, a missing dot) and
// must never "correct" a real domain, a company domain or an institution.
const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('path');

const T = require(path.join(__dirname, '..', '..', 'frontend', 'assets', 'email-typo.js'));

const POSITIVE = [
  // one edit / one transposition on a known name
  ['ann@gmial.com', 'ann@gmail.com'],
  ['ann@gamil.com', 'ann@gmail.com'],
  ['ann@gmai.com', 'ann@gmail.com'],
  ['ann@gmaill.com', 'ann@gmail.com'],
  ['ann@gnail.com', 'ann@gmail.com'],
  ['ann@gmaik.com', 'ann@gmail.com'],
  ['ann@yaho.com', 'ann@yahoo.com'],
  ['ann@yahooo.com', 'ann@yahoo.com'],
  ['ann@tahoo.com', 'ann@yahoo.com'],
  ['ann@hotmial.com', 'ann@hotmail.com'],
  ['ann@hotmal.com', 'ann@hotmail.com'],
  ['ann@hotmai.com', 'ann@hotmail.com'],
  ['ann@outlok.com', 'ann@outlook.com'],
  ['ann@outloo.com', 'ann@outlook.com'],
  ['ann@iclod.com', 'ann@icloud.com'],
  ['ann@icluod.com', 'ann@icloud.com'],
  ['ann@comcat.net', 'ann@comcast.net'],
  ['ann@concast.net', 'ann@comcast.net'],
  ['ann@verizn.net', 'ann@verizon.net'],
  ['ann@hotmial.co.uk', 'ann@hotmail.co.uk'],
  // up to two edits on the longer names
  ['ann@sbcgloabl.net', 'ann@sbcglobal.net'],
  ['ann@sbcglobl.net', 'ann@sbcglobal.net'],
  ['ann@protonmial.com', 'ann@protonmail.com'],
  ['ann@protnmal.com', 'ann@protonmail.com'],
  ['ann@googlemial.com', 'ann@googlemail.com'],
  ['ann@bellsout.net', 'ann@bellsouth.net'],
  // explicit TLD typos on a known name
  ['ann@gmail.con', 'ann@gmail.com'],
  ['ann@gmail.cmo', 'ann@gmail.com'],
  ['ann@gmail.cm', 'ann@gmail.com'],
  ['ann@gmail.om', 'ann@gmail.com'],
  ['ann@gmail.comm', 'ann@gmail.com'],
  ['ann@gmail.co', 'ann@gmail.com'],
  ['ann@gmail.cpm', 'ann@gmail.com'],
  ['ann@yahoo.con', 'ann@yahoo.com'],
  ['ann@hotmail.co', 'ann@hotmail.com'],
  ['ann@icloud.co', 'ann@icloud.com'],
  ['ann@aol.con', 'ann@aol.com'],
  ['ann@me.con', 'ann@me.com'],
  ['ann@live.cm', 'ann@live.com'],
  ['ann@comcast.ent', 'ann@comcast.net'],
  ['ann@comcast.nte', 'ann@comcast.net'],
  ['ann@att.ent', 'ann@att.net'],
  ['ann@verizon.nte', 'ann@verizon.net'],
  ['ann@sbcglobal.ne', 'ann@sbcglobal.net'],
  // a typo in the name AND the TLD
  ['ann@gmial.con', 'ann@gmail.com'],
  ['ann@hotmial.cmo', 'ann@hotmail.com'],
  // a missing or misplaced dot, a comma, a trailing dot
  ['ann@gmailcom', 'ann@gmail.com'],
  ['ann@hotmailcom', 'ann@hotmail.com'],
  ['ann@yahoocom', 'ann@yahoo.com'],
  ['ann@comcastnet', 'ann@comcast.net'],
  ['ann@aolcom', 'ann@aol.com'],
  ['ann@gmailcon', 'ann@gmail.com'],
  ['ann@gmail', 'ann@gmail.com'],
  ['ann@googlemailcom', 'ann@googlemail.com'],
  ['ann@gmailc.om', 'ann@gmail.com'],
  ['ann@gmail,com', 'ann@gmail.com'],
  ['ann@gmail.com.', 'ann@gmail.com'],
  ['ann@gmail..com', 'ann@gmail.com'],
  ['ann@hotmail...com', 'ann@hotmail.com'],
  ['ann@gmial..com', 'ann@gmail.com'],
  // no dot and a misspelt name, a zero for the o
  ['ann@gmial', 'ann@gmail.com'],
  ['ann@hotmial', 'ann@hotmail.com'],
  ['ann@gmail.c0m', 'ann@gmail.com'],
];

const NEGATIVE = [
  // every known domain as typed
  ...T.KNOWN_DOMAINS.map((d) => 'ann@' + d),
  // real domains near a known one
  'ann@mail.com', 'ann@email.com', 'ann@gmx.com', 'ann@gmx.net', 'ann@cloud.com',
  'ann@zoho.com', 'ann@hey.com', 'ann@fastmail.com', 'ann@mail.ru', 'ann@yandex.com',
  'ann@wahoo.com', 'ann@hotmart.com',
  // real companies and mail services within the long names' distance 2
  'ann@frontiers.com', 'ann@fronter.com', 'ann@earthling.net', 'ann@pocketmail.com',
  // a dotless word that is no known name is left alone
  'ann@localhost', 'ann@acme',
  // short known names get no fuzzy match: their neighbours are real companies
  'ann@aon.com', 'ann@max.com', 'ann@love.com', 'ann@line.com', 'ann@msm.com', 'ann@ma.com',
  // regional and alternative TLDs of a known name
  'ann@yahoo.fr', 'ann@yahoo.co.jp', 'ann@yahoo.com.au', 'ann@hotmail.fr', 'ann@outlook.de',
  'ann@live.co.uk', 'ann@protonmail.ch', 'ann@verizon.com', 'ann@comcast.com', 'ann@att.com',
  'ann@gmail.net',
  // companies, institutions
  'ann@fredvested.com', 'andrew@fredvested.com', 'ann@acme.co', 'ann@stripe.com',
  'ann@umich.edu', 'ann@mit.edu', 'ann@cs.stanford.edu', 'ann@nasa.gov', 'ann@army.mil',
  'ann@ox.ac.uk', 'ann@students.k12.ca.us',
];

const INVALID = [
  null, undefined, 42, {}, '', '   ', 'gmail.com', 'ann', '@gmail.con', 'ann@', 'ann@@gmail.con',
  'a@b@gmail.con', 'ann smith@gmail.con', 'ann@gm ail.com', 'ann@.gmail.com',
  'ann@gmail.c*m',
];

test('suggests the corrected address for common typos', () => {
  for (const [typed, expected] of POSITIVE) {
    assert.equal(T.suggestEmail(typed), expected, typed);
  }
});

test('never suggests for a known, real, company or institutional domain', () => {
  for (const typed of NEGATIVE) {
    assert.equal(T.suggestEmail(typed), null, typed);
  }
});

test('returns null for anything that is not one address', () => {
  for (const typed of INVALID) {
    assert.equal(T.suggestEmail(typed), null, String(typed));
  }
});

test('the domain is case-insensitive and the local part is kept exactly as typed', () => {
  assert.equal(T.suggestEmail('Ann.Smith+fred@GMIAL.COM'), 'Ann.Smith+fred@gmail.com');
  assert.equal(T.suggestEmail('ANN@Gmail.Con'), 'ANN@gmail.com');
  assert.equal(T.suggestEmail('o\'brien_99@Hotmial.com'), 'o\'brien_99@hotmail.com');
  assert.equal(T.suggestEmail('ANN@GMAIL.COM'), null, 'a known domain in capitals is not a typo');
  assert.equal(T.suggestEmail('  ann@gmial.com  '), 'ann@gmail.com', 'surrounding spaces are trimmed');
});

test('the suggestion is never the address as typed, and a suggestion has no further suggestion', () => {
  for (const [typed] of POSITIVE) {
    const s = T.suggestEmail(typed);
    assert.notEqual(s.toLowerCase(), typed.toLowerCase(), typed);
    assert.equal(T.suggestEmail(s), null, s);
  }
});

test('osaDistance counts an adjacent transposition as one edit', () => {
  assert.equal(T.osaDistance('gmail', 'gmail'), 0);
  assert.equal(T.osaDistance('gmial', 'gmail'), 1);
  assert.equal(T.osaDistance('gmal', 'gmail'), 1);
  assert.equal(T.osaDistance('gmaill', 'gmail'), 1);
  assert.equal(T.osaDistance('gnail', 'gmail'), 1);
  assert.equal(T.osaDistance('hotmart', 'hotmail'), 2);
  assert.equal(T.osaDistance('ca', 'abc'), 3, 'OSA, not unrestricted Damerau-Levenshtein');
});

test('the known list holds the domains the waitlist cares about', () => {
  for (const d of ['gmail.com', 'yahoo.com', 'hotmail.com', 'outlook.com', 'icloud.com', 'aol.com',
    'live.com', 'msn.com', 'me.com', 'mac.com', 'comcast.net', 'protonmail.com', 'proton.me',
    'att.net', 'verizon.net', 'sbcglobal.net', 'ymail.com', 'googlemail.com']) {
    assert.ok(T.KNOWN_DOMAINS.includes(d), d);
  }
});
