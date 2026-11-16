// Unit tests for the share route of frontend/_worker.js (node --test, no browser, no
// workerd): shareMeta(), the pure function that decides what the Open Graph tags of
// /s?r=<age>-<yyyy>-<mm> become, and the _routes.json entry that sends /s to the worker.
// The fetch handler itself needs HTMLRewriter and env.ASSETS, which node does not have;
// it is checked on the deployed Pages project (see the go-live checklist).
//
// The validation rule is implemented three times (ShareResult.java, share.js, _worker.js)
// and all three are pinned by the SAME vector table at now = 2026-11-14T12:00:00Z; change
// the table in all three tests or in none.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');

const FRONTEND = path.join(__dirname, '..', '..', 'frontend');

// _worker.js is an ES module (Cloudflare requires `export default`), but frontend/package.json
// says "type": "commonjs", so node's import() of the path would parse it as CommonJS and
// fail. Importing its source as a data: URL makes node treat it as a module without
// touching the file or the package; it works because the worker imports nothing.
function loadWorker() {
  const source = fs.readFileSync(path.join(FRONTEND, '_worker.js'), 'utf8');
  return import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'))
    .then((m) => m.default);
}

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
  ['90-2030-01', false],  // implied age 86.8
  ['30-2060-01', false],  // implied age -3.2
  ['', false],
  ['abc', false],
  ['52-2048-07x', false],
];

const shareUrl = (r, host) =>
  new URL('https://' + (host || 'fredvested.com') + '/s?r=' + encodeURIComponent(r));

test('shareMeta is a function on the default export, next to fetch', async () => {
  const worker = await loadWorker();
  assert.equal(typeof worker.fetch, 'function');
  assert.equal(typeof worker.shareMeta, 'function');
});

test('shareMeta: the shared vector table', async () => {
  const worker = await loadWorker();
  for (const [r, valid] of VECTORS) {
    const meta = worker.shareMeta(shareUrl(r), NOW);
    assert.equal(meta !== null, valid, 'r=' + JSON.stringify(r));
  }
});

test('shareMeta: exact tags for 52-2048-07 on the production host', async () => {
  const worker = await loadWorker();
  const url = new URL('https://fredvested.com/s?r=52-2048-07&utm_source=share&utm_medium=referral&utm_campaign=freedom_date');
  assert.deepEqual(worker.shareMeta(url, NOW), {
    title: 'I could be free at 52. Now your turn!',
    description: 'Hypothetical illustration, not a prediction or guarantee. Find your own Freedom Date in 30 seconds.',
    url: 'https://fredvested.com/s?r=52-2048-07',
    image: 'https://lpapi.fredvested.com/api/og/v1/52/2048-07.png',
    imageAlt: 'I could be free at 52 years old in July 2048. What about you?',
    width: '1200',
    height: '630',
  });
});

test('shareMeta: month names, a three-digit age and a normalised leading zero', async () => {
  const worker = await loadWorker();
  assert.equal(worker.shareMeta(shareUrl('38-2041-09'), NOW).imageAlt,
    'I could be free at 38 years old in September 2041. What about you?');
  assert.equal(worker.shareMeta(shareUrl('18-2026-12'), NOW).imageAlt,
    'I could be free at 18 years old in December 2026. What about you?');

  const hundred = worker.shareMeta(shareUrl('100-2090-01'), NOW);
  assert.equal(hundred.title, 'I could be free at 100. Now your turn!');
  assert.equal(hundred.image, 'https://lpapi.fredvested.com/api/og/v1/100/2090-01.png');

  // share.js accepts 052 as 52 and emits 52; the worker's tags do the same.
  const padded = worker.shareMeta(shareUrl('052-2048-07'), NOW);
  assert.equal(padded.url, 'https://fredvested.com/s?r=52-2048-07');
  assert.equal(padded.image, 'https://lpapi.fredvested.com/api/og/v1/52/2048-07.png');
});

test('shareMeta: www is production too, every other host gets the dev API origin', async () => {
  const worker = await loadWorker();
  assert.equal(worker.shareMeta(shareUrl('52-2048-07', 'www.fredvested.com'), NOW).image,
    'https://lpapi.fredvested.com/api/og/v1/52/2048-07.png');

  const preview = worker.shareMeta(shareUrl('52-2048-07', 'abc123.fred-landing.pages.dev'), NOW);
  assert.equal(preview.image, 'https://lpapi-dev.fredvested.com/api/og/v1/52/2048-07.png');
  assert.equal(preview.url, 'https://abc123.fred-landing.pages.dev/s?r=52-2048-07');

  assert.equal(worker.shareMeta(shareUrl('52-2048-07', 'dev.fredvested.com'), NOW).image,
    'https://lpapi-dev.fredvested.com/api/og/v1/52/2048-07.png');
});

test('shareMeta: env.API_ORIGIN overrides the image origin on any host', async () => {
  const worker = await loadWorker();
  const env = { API_ORIGIN: 'https://api.example.test/' };
  assert.equal(worker.shareMeta(shareUrl('52-2048-07'), NOW, env).image,
    'https://api.example.test/api/og/v1/52/2048-07.png');
  assert.equal(worker.shareMeta(shareUrl('52-2048-07', 'x.pages.dev'), NOW, env).image,
    'https://api.example.test/api/og/v1/52/2048-07.png');
  // A blank variable is unset, and so is one that is only whitespace (a stray space in the Pages
  // dashboard would otherwise make og:image a broken relative value); spaces around a value go.
  for (const blank of ['', '   ', '\t\n', null, undefined, 42]) {
    assert.equal(worker.shareMeta(shareUrl('52-2048-07'), NOW, { API_ORIGIN: blank }).image,
      'https://lpapi.fredvested.com/api/og/v1/52/2048-07.png', JSON.stringify(blank));
    assert.equal(worker.shareMeta(shareUrl('52-2048-07', 'x.pages.dev'), NOW, { API_ORIGIN: blank }).image,
      'https://lpapi-dev.fredvested.com/api/og/v1/52/2048-07.png', JSON.stringify(blank));
  }
  assert.equal(worker.shareMeta(shareUrl('52-2048-07'), NOW, { API_ORIGIN: '  https://api.example.test/  ' }).image,
    'https://api.example.test/api/og/v1/52/2048-07.png');
});

test('shareMeta: null without r, off /s, or with a bad clock; never throws', async () => {
  const worker = await loadWorker();
  assert.equal(worker.shareMeta(new URL('https://fredvested.com/s'), NOW), null);
  assert.equal(worker.shareMeta(new URL('https://fredvested.com/s?utm_source=share'), NOW), null);
  assert.equal(worker.shareMeta(new URL('https://fredvested.com/?r=52-2048-07'), NOW), null);
  assert.equal(worker.shareMeta(shareUrl('52-2048-07'), new Date(NaN)), null);
  assert.equal(worker.shareMeta(shareUrl('52-2048-07'), undefined), null);
});

test('shareMeta: a link whose date has passed since it was shared is null (Review Focus 1)', async () => {
  const worker = await loadWorker();
  assert.notEqual(worker.shareMeta(shareUrl('19-2026-11'), NOW), null);
  assert.equal(worker.shareMeta(shareUrl('19-2026-11'), new Date('2026-12-01T00:00:00Z')), null);
});

test('shareMeta: no em dash, and the title and alt are first person', async () => {
  const worker = await loadWorker();
  const meta = worker.shareMeta(shareUrl('67-2063-07'), NOW);
  for (const value of Object.values(meta)) assert.ok(!value.includes('—'), value);
  assert.ok(meta.title.startsWith('I could be free at 67.'));
  assert.ok(meta.imageAlt.startsWith('I could be free at 67 years old in July 2063.'));
});

test('_routes.json sends /s and the two Plausible paths to the worker', () => {
  const routes = JSON.parse(fs.readFileSync(path.join(FRONTEND, '_routes.json'), 'utf8'));
  assert.equal(routes.version, 1);
  assert.ok(routes.include.includes('/s'));
  assert.ok(routes.include.includes('/js/script.js'));
  assert.ok(routes.include.includes('/api/event'));
  assert.deepEqual(routes.exclude, []);
});

// frontend/serve.py is the dev and e2e server; it does not run the worker, so it maps /s to
// index.html itself. The e2e harness (tests/helpers.js) covers for a dev server that was started
// before serve.py learned that and still holds port 5500, which means the e2e suite alone cannot
// tell whether the mapping exists. This starts the real handler on a free port and asks it.
test('serve.py serves the homepage at /s with any query, and only there', async (t) => {
  const { spawn } = require('child_process');
  const code = [
    'import serve',
    'from http.server import ThreadingHTTPServer',
    's = ThreadingHTTPServer(("127.0.0.1", 0), serve.NoCacheHandler)',
    'print(s.server_address[1], flush=True)',
    's.serve_forever()',
  ].join('\n');
  // -B: no __pycache__ written into frontend/.
  const server = spawn('python3', ['-B', '-c', code], { cwd: FRONTEND, stdio: ['ignore', 'pipe', 'ignore'] });
  t.after(() => server.kill());
  const port = await new Promise((resolve, reject) => {
    let out = '';
    server.stdout.on('data', (chunk) => {
      out += chunk;
      const m = /^(\d+)\s/.exec(out);
      if (m) resolve(Number(m[1]));
    });
    server.on('error', reject);
    server.on('exit', (exitCode) => reject(new Error('serve.py handler did not start (exit ' + exitCode + ')')));
  });
  const get = (pathAndQuery) => fetch('http://127.0.0.1:' + port + pathAndQuery);

  const index = fs.readFileSync(path.join(FRONTEND, 'index.html'), 'utf8');
  for (const url of ['/s', '/s?r=52-2048-07', '/s?r=52-2048-07&utm_source=share&utm_medium=referral&utm_campaign=freedom_date', '/s?r=abc']) {
    const response = await get(url);
    assert.equal(response.status, 200, url);
    assert.equal(response.headers.get('cache-control'), 'no-store', url);
    assert.equal(await response.text(), index, url + ' is index.html, byte for byte');
  }
  for (const url of ['/s/', '/sx', '/S', '/s/52-2048-07']) {
    const response = await get(url);
    await response.arrayBuffer();
    assert.equal(response.status, 404, url);
  }
  // The clean URLs and the assets next to it are as they were.
  for (const url of ['/', '/index.html', '/privacy', '/about', '/assets/share.js']) {
    const response = await get(url);
    await response.arrayBuffer();
    assert.equal(response.status, 200, url);
  }
});
