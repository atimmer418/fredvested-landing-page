// Cloudflare Pages Function: first-party proxy for Plausible's event
// ingestion endpoint (POST /api/event). The proxied script at /js/script.js
// (see ../../_redirects) posts here automatically once it's loaded from our
// own domain instead of plausible.io -- no client-side config needed.
//
// This is a Pages Function rather than a _redirects rule because the
// _redirects "200 = proxy" mechanism is documented and reliable for a plain
// GET (the script), but is not a documented or verifiable way to forward a
// POST body untouched. A proxy that silently drops or mangles that body
// would look like it works while quietly recording nothing, so this path
// gets full manual control instead: the original request (method, headers,
// and body, streamed through unread and unmodified) is forwarded as-is.
//
// Adapted from Plausible's own reference Cloudflare Worker
// (https://plausible.io/docs/proxy/guides/cloudflare), which does the same
// forward via `new Request(request)` + `fetch(url, request)`.
export async function onRequestPost({ request }) {
  const upstreamRequest = new Request(request);
  upstreamRequest.headers.delete('cookie'); // Plausible is cookie-free; never forward one.

  // Plausible geolocates visitors from X-Forwarded-For. A browser never
  // sends this header itself, so set it explicitly from Cloudflare's own
  // client-IP header rather than assuming it already arrived correctly.
  const clientIp = request.headers.get('cf-connecting-ip');
  if (clientIp) upstreamRequest.headers.set('x-forwarded-for', clientIp);

  return fetch('https://plausible.io/api/event', upstreamRequest);
}

export async function onRequestGet() {
  return new Response('Method Not Allowed', { status: 405 });
}
