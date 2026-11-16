// Unit tests for frontend/assets/share.js (node --test, no browser): the share link's
// `r` parameter, the strings derived from it and the analytics bands.
//
// The validation rule is implemented three times (ShareResult.java, share.js, _worker.js)
// and all three are pinned by the SAME vector table at now = 2026-11-14T12:00:00Z; change
// the table in all three tests or in none.
const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('path');

const S = require(path.join(__dirname, '..', '..', 'frontend', 'assets', 'share.js'));

const NOW = new Date('2026-11-14T12:00:00Z');

// [r, valid]
const VECTORS = [
  ['52-2048-07', true],
  ['38-2041-09', true],
  ['67-2063-07', true],
  ['18-2026-12', true],   // implied age 17.9
  ['19-2026-11', true],
  ['18-2026-10', false],  // past
  ['52-2026-12', true],   // implied age 51.9
  ['100-2099-01', false], // monthsOut 866
  ['17-2030-01', false],  // age
  ['101-2090-01', false], // age
  ['52-2048-13', false],  // month
  ['52-2048-7', false],   // shape
  ['83-2030-01', true],   // implied age 79.8 (the slider's 80)
  ['85-2030-01', false],  // implied age 81.8
  ['90-2030-01', false],  // implied age 86.8
  ['30-2060-01', false],  // implied age -3.2
  ['', false],
  ['abc', false],
  ['52-2048-07x', false],
];

test.describe('parse', () => {
  for (const [r, valid] of VECTORS) {
    test(`"${r}" is ${valid ? 'valid' : 'invalid'}`, () => {
      const result = S.parse(r, NOW);
      if (valid) assert.ok(result, `expected a result for ${r}`);
      else assert.equal(result, null);
    });
  }

  test('returns the age, the date parts, ym and the date label', () => {
    assert.deepEqual(S.parse('52-2048-07', NOW), { age: 52, year: 2048, month: 7, ym: '2048-07', dateLabel: 'July 2048' });
    assert.deepEqual(S.parse('38-2041-09', NOW), { age: 38, year: 2041, month: 9, ym: '2041-09', dateLabel: 'September 2041' });
    assert.deepEqual(S.parse('100-2090-01', NOW), { age: 100, year: 2090, month: 1, ym: '2090-01', dateLabel: 'January 2090' });
  });

  test('anything that is not a string is invalid', () => {
    for (const r of [null, undefined, 52, {}, ['52-2048-07']]) assert.equal(S.parse(r, NOW), null);
  });

  test('surrounding whitespace, signs and a leading zero on a two-digit age are not accepted', () => {
    assert.equal(S.parse(' 52-2048-07', NOW), null);
    assert.equal(S.parse('52-2048-07\n', NOW), null);
    assert.equal(S.parse('+52-2048-07', NOW), null);
    assert.equal(S.parse('52-2048-00', NOW), null);
  });

  test('the window is measured in UTC months from `now`', () => {
    // 23:30 on 31 October in New York is already November in UTC.
    const lateOctoberLocal = new Date('2026-11-01T03:30:00Z');
    assert.equal(S.parse('18-2026-10', lateOctoberLocal), null);
    assert.ok(S.parse('19-2026-11', lateOctoberLocal));
    // The last valid month is 864 months out.
    assert.ok(S.parse('100-2098-11', NOW));
    assert.equal(S.parse('100-2098-12', NOW), null);
  });

  test('a link whose date has passed since it was shared stops being valid', () => {
    assert.ok(S.parse('40-2027-03', NOW));
    assert.equal(S.parse('40-2027-03', new Date('2027-04-01T00:00:00Z')), null);
  });

  test('an invalid `now` is invalid, not an exception', () => {
    assert.equal(S.parse('52-2048-07', new Date(NaN)), null);
    assert.equal(S.parse('52-2048-07', undefined), null);
  });
});

test.describe('fromSaved', () => {
  test('the saved age and "July 2048" give the same result as the parameter', () => {
    assert.deepEqual(S.fromSaved(52, 'July 2048', NOW), S.parse('52-2048-07', NOW));
    assert.deepEqual(S.fromSaved(38, 'September 2041', NOW), S.parse('38-2041-09', NOW));
    assert.deepEqual(S.fromSaved(67, 'July 2063', NOW), S.parse('67-2063-07', NOW));
  });

  test('every month name is understood', () => {
    const names = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
    names.forEach((name, i) => {
      const result = S.fromSaved(52, `${name} 2048`, NOW);
      assert.ok(result, name);
      assert.equal(result.month, i + 1);
      assert.equal(result.dateLabel, `${name} 2048`);
    });
  });

  test('null for a month that does not exist, a missing value, a non-integer age and an at-target-style null', () => {
    assert.equal(S.fromSaved(52, 'Julember 2048', NOW), null);
    assert.equal(S.fromSaved(52, undefined, NOW), null);
    assert.equal(S.fromSaved(52, '', NOW), null);
    assert.equal(S.fromSaved(undefined, 'July 2048', NOW), null);
    assert.equal(S.fromSaved(52.5, 'July 2048', NOW), null);
    assert.equal(S.fromSaved('52.5', 'July 2048', NOW), null);
    assert.equal(S.fromSaved('fifty', 'July 2048', NOW), null);
    assert.equal(S.fromSaved(NaN, 'July 2048', NOW), null);
    assert.equal(S.fromSaved(null, null, NOW), null);
    assert.equal(S.fromSaved(null, 'July 2048', NOW), null);
    assert.equal(S.fromSaved(52, null, NOW), null);
  });

  test('null for a malformed month and year', () => {
    for (const monthYear of ['July', '2048', 'July 48', 'July 2048 ', 'july 2048', 'July  2048', 'July 2048x', '07 2048', 2048, {}]) {
      assert.equal(S.fromSaved(52, monthYear, NOW), null, JSON.stringify(monthYear));
    }
  });

  test('the same validation rule applies: a stale or impossible saved result is null', () => {
    assert.equal(S.fromSaved(18, 'October 2026', NOW), null);  // past
    assert.equal(S.fromSaved(90, 'January 2030', NOW), null);  // implied age 86.8
    assert.equal(S.fromSaved(17, 'January 2030', NOW), null);  // age
    assert.equal(S.fromSaved(52, 'July 2048', new Date('2048-08-01T00:00:00Z')), null);
  });

  test('an age saved as a string of digits is accepted', () => {
    assert.deepEqual(S.fromSaved('52', 'July 2048', NOW), S.parse('52-2048-07', NOW));
  });
});

test.describe('derived strings', () => {
  const result = S.parse('52-2048-07', NOW);

  test('param round-trips', () => {
    assert.equal(S.param(result), '52-2048-07');
    assert.equal(S.param(S.parse('38-2041-09', NOW)), '38-2041-09');
    assert.equal(S.param(S.parse('100-2090-01', NOW)), '100-2090-01');
    for (const [r, valid] of VECTORS) if (valid) assert.equal(S.param(S.parse(r, NOW)), r);
  });

  test('shareUrl is the origin, /s, r and the three UTM values, in that order', () => {
    assert.equal(
      S.shareUrl('https://fredvested.com', result),
      'https://fredvested.com/s?r=52-2048-07&utm_source=share&utm_medium=referral&utm_campaign=freedom_date'
    );
    assert.equal(
      S.shareUrl('http://127.0.0.1:5500', S.parse('67-2063-07', NOW)),
      'http://127.0.0.1:5500/s?r=67-2063-07&utm_source=share&utm_medium=referral&utm_campaign=freedom_date'
    );
  });

  test('the share URL carries nothing but r and the UTM values', () => {
    const url = new URL(S.shareUrl('https://fredvested.com', result));
    assert.equal(url.pathname, '/s');
    assert.deepEqual([...url.searchParams.keys()], ['r', 'utm_source', 'utm_medium', 'utm_campaign']);
    assert.deepEqual(S.parse(url.searchParams.get('r'), NOW), result);
  });

  test('a trailing slash on the origin does not double up', () => {
    assert.equal(
      S.shareUrl('https://fredvested.com/', result),
      'https://fredvested.com/s?r=52-2048-07&utm_source=share&utm_medium=referral&utm_campaign=freedom_date'
    );
    assert.equal(S.linkImageUrl('https://lpapi.fredvested.com/', result), 'https://lpapi.fredvested.com/api/og/v1/52/2048-07.png');
  });

  test('the image URLs are on the API origin', () => {
    assert.equal(S.linkImageUrl('https://lpapi.fredvested.com', result), 'https://lpapi.fredvested.com/api/og/v1/52/2048-07.png');
    assert.equal(S.storyImageUrl('https://lpapi.fredvested.com', result), 'https://lpapi.fredvested.com/api/og/v1/story/52/2048-07.png');
    assert.equal(S.storyImageUrl('http://localhost:8081', S.parse('38-2041-09', NOW)), 'http://localhost:8081/api/og/v1/story/38/2041-09.png');
  });
});

test.describe('band', () => {
  test('65 minus the shared age, in the bands the page already sends', () => {
    assert.deepEqual(S.band(52), { stake_years_band: '11+', stake_source: 'plan' });
    assert.deepEqual(S.band(54), { stake_years_band: '11+', stake_source: 'plan' });
    assert.deepEqual(S.band(55), { stake_years_band: '6-10', stake_source: 'plan' });
    assert.deepEqual(S.band(57), { stake_years_band: '6-10', stake_source: 'plan' });
    assert.deepEqual(S.band(59), { stake_years_band: '6-10', stake_source: 'plan' });
    assert.deepEqual(S.band(60), { stake_years_band: '1-5', stake_source: 'plan' });
    assert.deepEqual(S.band(64), { stake_years_band: '1-5', stake_source: 'plan' });
  });

  test('65 and later is hidden', () => {
    assert.deepEqual(S.band(65), { stake_years_band: 'hidden', stake_source: 'hidden' });
    assert.deepEqual(S.band(70), { stake_years_band: 'hidden', stake_source: 'hidden' });
    assert.deepEqual(S.band(100), { stake_years_band: 'hidden', stake_source: 'hidden' });
  });

  test('neither the age nor the gap itself is in the properties', () => {
    for (const age of [18, 52, 57, 60, 64, 65, 99]) {
      const props = S.band(age);
      assert.deepEqual(Object.keys(props), ['stake_years_band', 'stake_source']);
      assert.ok(['hidden', '1-5', '6-10', '11+'].includes(props.stake_years_band));
      assert.ok(['hidden', 'plan'].includes(props.stake_source));
    }
  });
});

test.describe('stripParam', () => {
  test('removes r and keeps everything else as it was', () => {
    assert.equal(S.stripParam('?r=52-2048-07&utm_source=share'), '?utm_source=share');
    assert.equal(
      S.stripParam('?r=52-2048-07&utm_source=share&utm_medium=referral&utm_campaign=freedom_date'),
      '?utm_source=share&utm_medium=referral&utm_campaign=freedom_date'
    );
    assert.equal(S.stripParam('?utm_source=share&r=52-2048-07&utm_medium=referral'), '?utm_source=share&utm_medium=referral');
    assert.equal(S.stripParam('?utm_source=share&r=52-2048-07'), '?utm_source=share');
  });

  test('nothing left gives an empty string', () => {
    assert.equal(S.stripParam('?r=x'), '');
    assert.equal(S.stripParam('?r='), '');
    assert.equal(S.stripParam('?r'), '');
    assert.equal(S.stripParam('?'), '');
    assert.equal(S.stripParam(''), '');
    assert.equal(S.stripParam(undefined), '');
  });

  test('a search without r is returned untouched, encoding included', () => {
    assert.equal(S.stripParam('?utm_source=share'), '?utm_source=share');
    assert.equal(S.stripParam('?utm_content=a%20b+c&x'), '?utm_content=a%20b+c&x');
  });

  test('every r goes, and a parameter that merely ends in r stays', () => {
    assert.equal(S.stripParam('?r=1&r=2&utm_source=share'), '?utm_source=share');
    assert.equal(S.stripParam('?ar=1&r=2&rr=3'), '?ar=1&rr=3');
    assert.equal(S.stripParam('?%72=52-2048-07&utm_source=share'), '?utm_source=share');
  });

  test('a search given without its question mark is handled the same way', () => {
    assert.equal(S.stripParam('r=52-2048-07&utm_source=share'), '?utm_source=share');
  });
});

// index.html strips `r` from the address on /s with an inline script in <head>, BEFORE the
// Plausible script records its pageview. That script must not depend on this file having
// loaded (a failed request for share.js would otherwise leave the sharer's age and date in
// the address Plausible sees), so it carries its own copy of stripParam's rule. This pins
// the two together: the same searches, the same answers.
test.describe('the inline strip script in index.html', () => {
  const fs = require('fs');
  const vm = require('vm');
  const html = fs.readFileSync(path.join(__dirname, '..', '..', 'frontend', 'index.html'), 'utf8');
  const found = /<script id="share-strip">([\s\S]*?)<\/script>/.exec(html);

  function run(pathname, search, hash) {
    const replaced = [];
    const context = {
      location: { pathname, search, hash: hash || '' },
      history: { replaceState: (state, title, url) => { replaced.push(url); } },
      decodeURIComponent,
    };
    vm.runInNewContext(found[1], context);
    return replaced;
  }

  const SEARCHES = [
    '?r=52-2048-07&utm_source=share&utm_medium=referral&utm_campaign=freedom_date',
    '?utm_source=share&r=52-2048-07&utm_medium=referral',
    '?utm_source=share&r=52-2048-07',
    '?r=x', '?r=', '?r', '?', '',
    '?utm_source=share',
    '?utm_content=a%20b+c&x',
    '?r=1&r=2&utm_source=share',
    '?ar=1&r=2&rr=3',
    '?%72=52-2048-07&utm_source=share',
    '?%E0%A4%A=1&r=2', // a key that does not decode is kept, not thrown on
  ];

  test('exists, before the Plausible script tag and with no script file loaded ahead of it', () => {
    assert.ok(found, 'a <script id="share-strip"> in index.html');
    const at = html.indexOf('<script id="share-strip">');
    assert.ok(at < html.indexOf('src="/js/script.js"'), 'it runs before the Plausible script');
    assert.equal(/<script[^>]*\ssrc=/.test(html.slice(0, at)), false, 'nothing it could depend on loads before it');
    assert.equal(/FredShare/.test(found[1]), false, 'it does not use share.js');
  });

  test('on /s it replaces the address with "/" plus what stripParam keeps, plus the hash', () => {
    for (const search of SEARCHES) {
      assert.deepEqual(run('/s', search), ['/' + S.stripParam(search)], search);
      assert.deepEqual(run('/s', search, '#calc'), ['/' + S.stripParam(search) + '#calc'], search);
    }
  });

  test('on any other path it leaves the address alone', () => {
    for (const pathname of ['/', '/index.html', '/about', '/s/', '/sx', '/S']) {
      assert.deepEqual(run(pathname, '?r=52-2048-07&utm_source=share'), [], pathname);
    }
  });

  test('a history that throws is swallowed', () => {
    const context = {
      location: { pathname: '/s', search: '?r=52-2048-07', hash: '' },
      history: { replaceState: () => { throw new Error('SecurityError'); } },
      decodeURIComponent,
    };
    assert.doesNotThrow(() => vm.runInNewContext(found[1], context));
  });
});

// Both images are served `immutable` for a year and message apps keep previews per URL, so a
// change to anything drawn on them (counsel may change the route label) needs new URLs: the
// `v1` in the path. It is spelled in three places; a bump that misses one fails here.
test.describe('the image path version', () => {
  const fs = require('fs');
  const root = path.join(__dirname, '..', '..');
  const result = S.parse('52-2048-07', NOW);

  test('share.js, the worker and the API controller agree on /api/og/v<n>/', async () => {
    const fromShareJs = /^\/api\/og\/(v\d+)\/52\/2048-07\.png$/.exec(S.linkImageUrl('', result));
    assert.ok(fromShareJs, S.linkImageUrl('', result));
    const version = fromShareJs[1];
    assert.equal(S.storyImageUrl('', result), '/api/og/' + version + '/story/52/2048-07.png');

    const controller = fs.readFileSync(path.join(root, 'backend', 'src', 'main', 'java', 'com', 'fredvested', 'web', 'share', 'ShareImageController.java'), 'utf8');
    assert.ok(/@RequestMapping\("\/api\/og"\)/.test(controller));
    const mappings = [...controller.matchAll(/@GetMapping\("\/(v\d+)\/(story\/)?\{age\}\/\{ym\}\.png"\)/g)].map((m) => m[1]);
    assert.deepEqual(mappings, [version, version]);

    const source = fs.readFileSync(path.join(root, 'frontend', '_worker.js'), 'utf8');
    const worker = (await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'))).default;
    assert.equal(worker.shareMeta(new URL('https://fredvested.com/s?r=52-2048-07'), NOW).image,
      'https://lpapi.fredvested.com/api/og/' + version + '/52/2048-07.png');
  });
});

// Known edge, accepted 2026-11-14: the page computes the Freedom Date from the visitor's LOCAL
// month, the rule measures from the UTC month. In the hours when UTC is already in the next
// month, a result that lands in the visitor's current month (only reachable with a balance
// within a month's growth of the target) reads as past: no share row, which is the safe side.
// The rule is not loosened here alone; it would have to change in all three implementations.
test('a result in the local month is refused once UTC has moved to the next month', () => {
  assert.equal(S.fromSaved(30, 'November 2026', new Date('2026-12-01T03:00:00Z')), null);
  assert.ok(S.fromSaved(30, 'November 2026', new Date('2026-11-30T23:59:59Z')));
  assert.ok(S.fromSaved(30, 'December 2026', new Date('2026-12-01T03:00:00Z')));
});
