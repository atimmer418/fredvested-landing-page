// Cloudflare Pages advanced-mode worker: first-party proxy for Plausible, and
// the share link's message preview (/s, at the bottom of this file).
//
// Why _worker.js and not a functions/ directory: Pages compiles functions/
// only from the project's ROOT directory ("Make sure that the /functions
// directory is at the root of your Pages project (and not in the static
// root, such as /dist)"). This repo's Pages project has the repo root as its
// root directory and frontend/ as its build output directory, so a
// frontend/functions/ tree sat in the static root and was silently neither
// compiled nor served -- the first deploy of this proxy shipped as a purely
// static site, /js/script.js fell through to the SPA fallback (index.html as
// text/html), and the browser never sent a single event. _worker.js is
// resolved from the OUTPUT directory, which is the one thing about this
// project's layout that's certain from the outside, so it can't be misplaced
// the same way. When _worker.js exists Pages ignores functions/ entirely.
//
// _routes.json restricts this worker to the two proxied paths and /s; everything
// else is served as static assets without invoking it. The ASSETS fallback
// below is still correct if that file were ever ignored.

const PLAUSIBLE_SCRIPT_URL = 'https://plausible.io/js/script.outbound-links.js';
const PLAUSIBLE_EVENT_URL = 'https://plausible.io/api/event';

// Bounded so Plausible's own script updates land: Cloudflare's Cache API takes
// its TTL from the stored response's Cache-Control, so an entry lives at most
// this long (per-colo, and evicted earlier under pressure). The same header
// reaches the browser, so worst-case staleness is 2x this.
const SCRIPT_TTL_SECONDS = 3600;

// The share link, /s?r=<age>-<yyyy>-<mm> (plus UTM values): the homepage with
// its Open Graph tags rewritten so a message app's preview shows the sender's
// rounded age and date. The image is drawn by the API, not here (about 75 ms
// of CPU per image against the free plan's 10 ms).
const SHARE_PATH = '/s';
const API_ORIGIN_PROD = 'https://lpapi.fredvested.com';
const API_ORIGIN_DEV = 'https://lpapi-dev.fredvested.com';
const PROD_HOSTNAMES = ['fredvested.com', 'www.fredvested.com'];
const MONTH_NAMES = ['January', 'February', 'March', 'April', 'May', 'June', 'July',
  'August', 'September', 'October', 'November', 'December'];

// ATTORNEY REVIEW PENDING 2026-11-14. Every sentence the share preview shows.
// First person: the sender is the one speaking. {AGE} and {DATE} ("July 2048")
// are filled in by shareMeta().
const SHARE_COPY = {
  TITLE: 'I could be free at {AGE}. Now your turn!',
  DESCRIPTION: 'Hypothetical illustration, not a prediction or guarantee. Find your own Freedom Date in 30 seconds.',
  IMAGE_ALT: 'I could be free at {AGE} years old in {DATE}. What about you?',
};

export default {
  async fetch(request, env, ctx) {
    const { pathname } = new URL(request.url);
    if (pathname === '/js/script.js') return serveScript(request, env, ctx);
    if (pathname === '/api/event') return forwardEvent(request, env);
    if (pathname === SHARE_PATH) return serveShare(request, env);
    return env.ASSETS.fetch(request);
  },

  // On the default export, and free of HTMLRewriter, so node can unit-test it
  // (tests/unit/worker-share.test.js). Cloudflare only ever calls fetch.
  shareMeta,
};

// Proxies Plausible's CLASSIC script.outbound-links.js, not the personalized
// pa-<id>.js snippet the dashboard hands out. Read from the fetched source of
// each: pa-<id>.js hardcodes endpoint:"https://plausible.io/api/event" as an
// absolute URL with no override, so serving it from our domain would not move
// the events. The classic build computes
//   data-api attribute || new URL(<its own src>).origin + "/api/event"
// at runtime, so loading it from our domain makes it post to /api/event here.
async function serveScript(request, env, ctx) {
  if (request.method !== 'GET' && request.method !== 'HEAD') {
    return new Response('Method Not Allowed', { status: 405 });
  }

  const cache = caches.default;
  const cacheKey = new Request(new URL('/js/script.js', request.url).toString());
  let response = await cache.match(cacheKey);

  if (!response) {
    const upstream = await fetch(env.PLAUSIBLE_SCRIPT_URL || PLAUSIBLE_SCRIPT_URL);
    if (!upstream.ok) {
      // Not cached: the page's queue shim keeps the site working, and the next
      // request retries plausible.io instead of pinning a failure.
      return new Response('', { status: 502 });
    }
    response = new Response(upstream.body, {
      status: 200,
      headers: {
        'content-type': 'application/javascript; charset=utf-8',
        'cache-control': `public, max-age=${SCRIPT_TTL_SECONDS}`,
      },
    });
    ctx.waitUntil(cache.put(cacheKey, response.clone()));
  }

  return request.method === 'HEAD' ? new Response(null, response) : response;
}

// Forwards the original request (method, headers, body streamed through
// unread and unmodified) the same way Plausible's own reference Cloudflare
// Worker does: `fetch(url, new Request(request))`.
async function forwardEvent(request, env) {
  if (request.method !== 'POST') {
    return new Response('Method Not Allowed', { status: 405 });
  }

  const upstreamRequest = new Request(request);
  upstreamRequest.headers.delete('cookie'); // Plausible is cookie-free; never forward one.

  // Plausible geolocates from X-Forwarded-For. A browser never sends that
  // header itself, so set it explicitly from Cloudflare's own client-IP
  // header rather than assuming it already arrived correct.
  const clientIp = request.headers.get('cf-connecting-ip');
  if (clientIp) upstreamRequest.headers.set('x-forwarded-for', clientIp);

  return fetch(env.PLAUSIBLE_EVENT_URL || PLAUSIBLE_EVENT_URL, upstreamRequest);
}

// The same rule as ShareResult.java and frontend/assets/share.js (this file
// cannot import the latter: it must stay one dependency-free module). All
// three are pinned by one vector table; change them together. `now` is a Date,
// read in UTC. Returns {age, year, month} or null, never throws.
function parseShareParam(r, now) {
  if (typeof r !== 'string' || !(now instanceof Date) || isNaN(now.getTime())) return null;
  const m = /^(\d{2,3})-(\d{4})-(\d{2})$/.exec(r);
  if (!m) return null;
  const age = parseInt(m[1], 10);
  const year = parseInt(m[2], 10);
  const month = parseInt(m[3], 10);
  if (age < 18 || age > 100 || month < 1 || month > 12) return null;
  // Not in the past, and no further out than 72 years.
  const monthsOut = (year * 12 + month - 1) - (now.getUTCFullYear() * 12 + now.getUTCMonth());
  if (monthsOut < 0 || monthsOut > 864) return null;
  // The age today that this result implies. The age slider is 18 to 80; one
  // year of tolerance either side.
  const impliedAge = age - monthsOut / 12;
  if (impliedAge < 17 || impliedAge > 81) return null;
  return { age, year, month };
}

// What the tags of `url` become at `now`, or null when it is not a valid share
// link (no r, a malformed one, or a date that has passed since it was shared):
// then the plain homepage is served with its own tags.
function shareMeta(url, now, env) {
  if (url.pathname !== SHARE_PATH) return null;
  const result = parseShareParam(url.searchParams.get('r'), now);
  if (!result) return null;

  const ym = result.year + '-' + String(result.month).padStart(2, '0');
  const fill = (text) => text
    .replace('{AGE}', result.age)
    .replace('{DATE}', MONTH_NAMES[result.month - 1] + ' ' + result.year);
  // A variable that is blank or only whitespace counts as unset (a stray space in
  // the Pages dashboard must not turn og:image into a relative value).
  const configured = env && typeof env.API_ORIGIN === 'string' ? env.API_ORIGIN.trim() : '';
  const apiOrigin = (configured
    || (PROD_HOSTNAMES.includes(url.hostname) ? API_ORIGIN_PROD : API_ORIGIN_DEV)).replace(/\/+$/, '');

  return {
    title: fill(SHARE_COPY.TITLE),
    description: fill(SHARE_COPY.DESCRIPTION),
    // Rebuilt from the parsed values, so nothing else in the query (UTM values,
    // anything a sender appended) is echoed into the page.
    url: url.origin + SHARE_PATH + '?r=' + result.age + '-' + ym,
    // `v1` changes together with share.js's IMAGE_PATH and the API's mappings
    // whenever anything drawn on the image changes (previews are cached per URL).
    image: apiOrigin + '/api/og/v1/' + result.age + '/' + ym + '.png',
    imageAlt: fill(SHARE_COPY.IMAGE_ALT),
    width: '1200',
    height: '630',
  };
}

// /s is the homepage (the page's own script drops `r` from the address bar
// before the pageview). Without a valid r it is returned untouched.
async function serveShare(request, env) {
  if (request.method !== 'GET' && request.method !== 'HEAD') {
    return new Response('Method Not Allowed', { status: 405 });
  }

  const url = new URL(request.url);
  const meta = shareMeta(url, new Date(), env);
  const homepage = new Request(new URL('/', url).toString(), request);
  if (!meta) return env.ASSETS.fetch(homepage);

  // The rewritten page differs from the asset whose validators these are, so
  // never answer 304 for it and never hand out the homepage's ETag.
  homepage.headers.delete('if-none-match');
  homepage.headers.delete('if-modified-since');
  const response = await env.ASSETS.fetch(homepage);
  if (!response.ok) return response;

  const content = (value) => ({ element(el) { el.setAttribute('content', value); } });
  const tag = (attr, key, value) =>
    '<meta ' + attr + '="' + key + '" content="' + escapeAttribute(value) + '">\n';
  const rewritten = new HTMLRewriter()
    .on('meta[property="og:title"]', content(meta.title))
    .on('meta[property="og:description"]', content(meta.description))
    .on('meta[property="og:url"]', content(meta.url))
    .on('meta[property="og:image"]', content(meta.image))
    .on('meta[name="twitter:title"]', content(meta.title))
    .on('meta[name="twitter:description"]', content(meta.description))
    .on('meta[name="twitter:image"]', content(meta.image))
    // index.html has none of these, so they are appended, not rewritten.
    .on('head', {
      element(el) {
        el.append(
          tag('property', 'og:image:width', meta.width)
          + tag('property', 'og:image:height', meta.height)
          + tag('property', 'og:image:alt', meta.imageAlt)
          + tag('name', 'twitter:image:alt', meta.imageAlt)
          + tag('name', 'robots', 'noindex'),
          { html: true },
        );
      },
    })
    .transform(response);

  const headers = new Headers(rewritten.headers);
  headers.delete('etag');
  headers.delete('last-modified');
  return new Response(rewritten.body, { status: rewritten.status, headers });
}

function escapeAttribute(value) {
  return String(value).replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}
