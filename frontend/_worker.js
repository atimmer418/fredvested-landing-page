// Cloudflare Pages advanced-mode worker: first-party proxy for Plausible.
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
// _routes.json restricts this worker to the two proxied paths; everything
// else is served as static assets without invoking it. The ASSETS fallback
// below is still correct if that file were ever ignored.

const PLAUSIBLE_SCRIPT_URL = 'https://plausible.io/js/script.outbound-links.js';
const PLAUSIBLE_EVENT_URL = 'https://plausible.io/api/event';

// Bounded so Plausible's own script updates land: Cloudflare's Cache API takes
// its TTL from the stored response's Cache-Control, so an entry lives at most
// this long (per-colo, and evicted earlier under pressure). The same header
// reaches the browser, so worst-case staleness is 2x this.
const SCRIPT_TTL_SECONDS = 3600;

export default {
  async fetch(request, env, ctx) {
    const { pathname } = new URL(request.url);
    if (pathname === '/js/script.js') return serveScript(request, env, ctx);
    if (pathname === '/api/event') return forwardEvent(request, env);
    return env.ASSETS.fetch(request);
  },
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
