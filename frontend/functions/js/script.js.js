// Route: /js/script.js
// (Named script.js.js because Cloudflare Pages Functions strip exactly one
// trailing extension from the filename to derive the route -- e.g. Cloudflare's
// own docs use sitemap.xml.js -> /sitemap.xml. Verified locally with wrangler:
// functions/js/script.js maps to /js/script, NOT /js/script.js.)
//
// First-party proxy for our Plausible script, so ad-block lists that target
// plausible.io by hostname don't silently drop it.
//
// This deliberately fetches the CLASSIC script.outbound-links.js, not the
// personalized pa-<id>.js snippet the dashboard gives you by default. Read
// straight from the fetched source of each:
//   - pa-<id>.js hardcodes `endpoint:"https://plausible.io/api/event"` as an
//     absolute URL with no override -- serving that file from our own domain
//     would NOT change where the browser sends events. Proxying only the
//     script and not the events is the "looks like it works, records
//     nothing" failure mode.
//   - script.outbound-links.js computes
//     `data-api attribute || new URL(<its own src>).origin + "/api/event"`
//     at runtime, so loading it from our own domain makes it post events to
//     a same-origin /api/event automatically -- see ../api/event.js.
// Site identity in Plausible is by the `data-domain` attribute on the
// <script> tag (fredvested.com), not by which script file/variant serves
// it, so this is the same Plausible site as before, just a different
// script build.
//
// Adapted from Plausible's own reference Cloudflare Worker
// (https://plausible.io/docs/proxy/guides/cloudflare), including its use of
// the Cache API so we don't re-fetch plausible.io on every page load.
const PLAUSIBLE_SCRIPT_URL = 'https://plausible.io/js/script.outbound-links.js';

export async function onRequestGet({ request, waitUntil }) {
  const cache = caches.default;
  const cached = await cache.match(request);
  if (cached) return cached;

  const upstream = await fetch(PLAUSIBLE_SCRIPT_URL);
  const response = new Response(upstream.body, {
    status: upstream.status,
    headers: {
      'content-type': upstream.headers.get('content-type') || 'application/javascript; charset=utf-8',
      'cache-control': 'public, max-age=3600',
    },
  });
  waitUntil(cache.put(request, response.clone()));
  return response;
}
