# Analytics and email funnel QA

A checklist to run before every launch. Every item states what to do, what you must see, and what it means if you do not. Everything here describes the code in the working tree on 2026-09-24; where a rule is Andrew's decision rather than code, it says so.

Conventions:

- **Do** / **Expect** / **If not** are the three parts of every check.
- Paths are relative to the repo root.
- `$API` is the backend origin for the environment under test (table below). `$LANDING` is the landing site origin.
- Database checks assume a read connection to the target environment's MySQL (Railway: the MySQL service's public connection values, see `CLAUDE.md`, "Schema Migrations", step 1). Run them read-only unless a check says otherwise.
- Timestamps: the app writes Eastern wall-clock `LocalDateTime` values (`ZoneId.of("America/New_York")` in `SignupService`, `EmailOutboxPublisher`, `EmailEventProcessor`, `WaitlistEntry`). Never compare or overwrite those columns with MySQL `NOW()`; adjust stored values relative to themselves (see D6).

| Environment | Landing site | API (`$API`) | Decided by |
|---|---|---|---|
| Local | `http://localhost:5500` or `http://127.0.0.1:5500` | `http://<host>:8081` | `frontend/assets/waitlist.js` (`isLocal`), `backend/src/main/resources/application-local.properties` (gitignored) |
| Dev / preview | `https://*.fredvested-landing-page.pages.dev` (Andrew tests on `develop.`) | `https://lpapi-dev.fredvested.com` | `waitlist.js` (`isPreview`), `application-dev.properties` |
| Production | `https://fredvested.com` (and `www.`) | `https://lpapi.fredvested.com` | `waitlist.js` (everything else), `application-prod.properties` |

---

## A. Third-party requests

The privacy policy (`frontend/privacy.html`) names three vendors by name: Plausible (reached only through our own proxy), Cloudflare (Turnstile) and Railway. Resend, the email provider, is not named in `privacy.html` or `terms.html` as of 2026-09-24, although `CLAUDE.md` lists it among the disclosed vendors (see Open questions). Any browser request to a host outside our own domain and `challenges.cloudflare.com` is a launch blocker (Andrew's rule; commit `b6b84b9` on 2026-09-22 dropped Google Fonts and the Material Icons font from privacy/terms for exactly this reason). Fonts are self-hosted from `frontend/fonts/`; icons are inline SVG sprites.

Allowed hosts, and why:

| Host | Reason |
|---|---|
| The page's own origin | HTML, `dist/output.css`, `assets/*.js`, `fonts/*`, `/js/script.js` and `/api/event` (the Plausible proxy in `frontend/_worker.js`, scoped by `frontend/_routes.json`) |
| `lpapi.fredvested.com` / `lpapi-dev.fredvested.com` | `GET /api/waitlist/stats`, `POST /api/waitlist`, `POST /api/waitlist/resend-confirmation` |
| `challenges.cloudflare.com` | Turnstile: `<script src="https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit">` in `index.html` and `about.html`, plus the widget it loads |

### A1. DevTools network check (every page)

- **Do:** Open DevTools, Network tab, tick "Disable cache". Load `/`, `/about`, `/privacy`, `/terms` and `/confirmed?status=confirmed` in turn. On `/`, use the calculator, click "Reveal", open the explainer, focus the email field, submit. In the filter box enter `-domain:*.fredvested.com -domain:challenges.cloudflare.com` (on a preview, add `-domain:*.pages.dev`).
- **Expect:** The filtered list is empty on every page. `plausible.io` never appears; the analytics requests are `GET /js/script.js` and `POST /api/event` on the page's own origin.
- **If not:** Any remaining row is a new third-party dependency. Do not launch. Find the tag or CSS `url(...)` that caused it and remove it (fonts belong in `frontend/fonts/`, icons in the inline sprite).

### A2. The proxy is doing the work

- **Do:** `curl -sS -D - -o /dev/null "$LANDING/js/script.js"` and `curl -sS -o /dev/null -w '%{http_code}\n' -X GET "$LANDING/api/event"`. (`$LANDING` is a Pages host; the local `serve.py` server has no worker, so `/js/script.js` is a 404 there and the queue shim keeps the page working.)
- **Expect:** `/js/script.js` answers 200 with `content-type: application/javascript; charset=utf-8` and `cache-control: public, max-age=3600` (`SCRIPT_TTL_SECONDS` in `_worker.js`). `GET /api/event` answers 405 (`forwardEvent` accepts POST only). In the browser, `POST /api/event` returns 202 from Plausible via the worker.
- **If not:** A 200 `text/html` on `/js/script.js` means the worker is not running and the SPA fallback served `index.html` (this happened once when the proxy lived in `frontend/functions/`, see the header comment in `_worker.js`). A 502 means the upstream fetch of `https://plausible.io/js/script.outbound-links.js` failed and nothing was cached; retry.

### A3. Emails carry no external resources

- **Do:** Run `./gradlew test --tests '*EmailTemplatesTest*'` in `backend/`. Then open a received confirmation email's HTML source.
- **Expect:** The test `noExternalResources_noCleartextLinks_noEmDashes` passes. The HTML has no `<link`, no `<img`, no `@import`, no `url(`, no `http://` (the test's assertions). Font stacks are web-safe only (`Helvetica,Arial,sans-serif`; the wordmark uses `'Arial Black','Avenir Next',Arial,Helvetica,sans-serif`).
- **If not:** A mail client would fetch from a vendor the privacy policy does not name. Fix `backend/src/main/java/com/fredvested/web/service/EmailTemplates.java`.

### A4. Playwright enforcement (Phase 8, in the tree since 2026-09-25)

The end-to-end suite lives at the repo root: `playwright.config.js`, `package.json` (`npm run test:e2e`), `tests/helpers.js` (the harness) and the spec files below. It starts `frontend/serve.py` itself and needs no backend: the waitlist API, the resend endpoint and `/api/event` are intercepted in the browser, and `/js/script.js` is replaced by `tests/fixtures/plausible-recorder.js`, which records every `plausible()` call the pages make (the exact boundary `analytics.js` talks to). Turnstile is real (test sitekey on localhost). Delivery to Plausible is the real script's job and was verified live in Phase 1; the suite asserts what the pages emit.

- `tests/third-party-hosts.spec.js` is the A1 enforcement: for every page and for a full signup it subscribes to `page.on('request')`, collects each hostname, and fails on anything outside `localhost`, `127.0.0.1` and `challenges.cloudflare.com` (`ALLOWED_HOSTS` in `helpers.js`). `plausible.io` is therefore a failure by construction. Observed on 2026-09-25: Turnstile contacts only `challenges.cloudflare.com`.
- `tests/analytics-events.spec.js` covers B4, B5, B6 and B7 (every event, exact bands, once guards, the failure reasons and the cap of three, the hostname gate, no raw numbers or addresses in props). Tests that expect events call `forceAnalytics(page)` first, which sets `localStorage.fred_analytics_force = '1'` before the page scripts run.
- `tests/pending-state.spec.js`, `tests/confirmed-page.spec.js` and `tests/about-and-consent.spec.js` cover section D's browser-side checks (the pending state, storage keys, resend, the confirmed page's states and what it stores, the founder wording, the consent sentence).
- Running against a deployed preview instead of the local server is not configured; the preview is a Cloudflare build of the same files, and the live checks in B and D remain manual there.

Backend counterpart: `WaitlistFunnelIntegrationTest` runs the funnel on a real MySQL (Flyway V1..V5 on an empty schema, Hibernate validate, signup to confirmation to stats, the webhook stream, suppression) when `FRED_IT_JDBC_URL` points at a scratch database; see CLAUDE.md "Backend Commands".

---

## B. Analytics

Source of truth: `frontend/assets/analytics.js`. Nothing outside that file calls `window.plausible()` (each page's inline script goes through `window.FredAnalytics`). Load order at the end of `<body>` on `index.html`, `about.html` and `confirmed.html` is `assets/waitlist.js`, `assets/attribution.js`, `assets/analytics.js`, then the page script (on `index.html` and `about.html` the Turnstile `api.js` tag, `async defer`, sits between `analytics.js` and the page script); `privacy.html` and `terms.html` load `assets/attribution.js`, `assets/analytics.js`, then the page script. All of ours are classic scripts; the Plausible loader is `<script defer data-domain="fredvested.com" src="/js/script.js">` in `<head>` with the queue shim (`window.plausible=window.plausible||function(){(plausible.q=plausible.q||[]).push(arguments)}...`). `privacy.html` and `terms.html` load `attribution.js` and `analytics.js` only (for the gate; they fire no custom events).

### B1. Hostname gate

`init()` in `analytics.js` runs at script load. `enabled = isProduction || forced`, where `isProduction` is `location.hostname` in `['fredvested.com', 'www.fredvested.com']` and `forced` is `localStorage.fred_analytics_force === '1'`.

- **Do:** On a preview (`*.fredvested-landing-page.pages.dev`), load `/` with DevTools open, then use the calculator and reveal. (A local server behaves the same for the gate and the console lines, but has no proxy, see A2.)
- **Expect:** Console shows `[analytics] disabled (not production; set localStorage.fred_analytics_force='1' to force)`. Each action logs `[analytics] (gated) <Event> {props}` (debug logging is on whenever the host is not production). Network shows `GET /js/script.js` but no `POST /api/event` at all, not even the pageview. `localStorage.getItem('plausible_ignore')` is `'true'`.
- **If not:** A `POST /api/event` from a non-production host means preview traffic is landing in the production Plausible site (site identity is `data-domain="fredvested.com"`, not the hostname). Check that `analytics.js` loaded before the deferred Plausible script (it must be a body script, not deferred).

On production:

- **Do:** Load `https://fredvested.com/` with DevTools open.
- **Expect:** No `[analytics]` console lines at all (`debug` is false on production). `POST /api/event` with `"n":"pageview"` appears after load.
- **If not:** If nothing posts, first run `localStorage.getItem('plausible_ignore')` in the console; `'true'` means this browser opted itself out (B3), not that the site is broken.

### B2. Escape hatch and clearing it

- **Do (arm):** On a preview, in the console: `localStorage.setItem('fred_analytics_force', '1')`, then reload.
- **Expect:** Console shows `[analytics] enabled`. `localStorage.getItem('plausible_ignore')` is now `null` (the gate removes it when forced). `POST /api/event` requests appear with `"d":"fredvested.com"`, so these events land in the production dashboard: keep the window short and use recognisable test values.
- **Do (disarm, always, when finished):** `localStorage.removeItem('fred_analytics_force')`, reload.
- **Expect:** Back to `[analytics] disabled ...`, `plausible_ignore` is `'true'` again, no `/api/event` posts.
- **If not:** A preview tab left forced keeps polluting production numbers until the flag is cleared; localStorage is per origin, so each preview hostname you armed must be cleared separately.

Limit: the upstream Plausible script (fetched 2026-09-24) drops every event when `location.hostname` matches `localhost`, `127.x.x.x`, `[::1]` or the protocol is `file:`, whatever the flag says. It also drops every event when `navigator.webdriver`, `window._phantom`, `window.__nightmare` or `window.Cypress` is set, unless `window.__plausible` is set (this is what A4 has to work around). The escape hatch therefore works on `pages.dev` previews and ngrok tunnels, not on a local server.

### B3. `plausible_ignore`

`plausible_ignore` is Plausible's own kill switch: its script drops all events (pageviews, custom events, outbound clicks) when `localStorage.plausible_ignore === 'true'` and logs `Ignoring Event: localStorage flag`.

- On a non-production host, `analytics.js` sets it to `'true'` on every load unless forced, and removes it when forced (`init()`).
- On production, `analytics.js` never touches it, so a site owner can set it in their own browser to exclude their visits (Plausible's documented opt-out).
- **Do:** On production, set `localStorage.setItem('plausible_ignore', 'true')` and reload.
- **Expect:** No `POST /api/event`; the console shows Plausible's `Ignoring Event` line. Remove the key and reload: posts resume.
- **If not:** If posts continue with the flag set, the served `/js/script.js` is not Plausible's script (A2).

### B4. Per-event checks

Bands are computed in `analytics.js`; raw numbers never leave that file. Values below are the exact strings. Run these on a forced preview (B2) or on production with test data, and read each `POST /api/event` body (`n` is the event name, `p` the props).

| # | Event (`n`) | Trigger (page code) | Props (`p`) and allowed values |
|---|---|---|---|
| 1 | `Calc Engaged` | First `recordInput(field)` from any slider, number input or return-scenario change (`index.html`, `bindRow` and the `#return-seg` handlers) | `first_field`: `age`, `monthly_invest`, `target_income`, `return_scenario` |
| 2 | `Explainer Opened` | `<details id="calc-method">` toggles open (`index.html`) | none (`{}`) |
| 3 | `Freedom Date Revealed` | First click of `#reveal-btn`, after `render()` (`index.html`) | `return_scenario`: `conservative`, `moderate`, `optimistic`; `age_band`: `18-24`, `25-29`, `30-34`, `35-39`, `40-44`, `45-49`, `50-54`, `55+`; `invest_band`: `0-99`, `100-249`, `250-499`, `500-999`, `1000-1999`, `2000-4999`, `5000+`; `target_income_band`: `<2500`, `2500-4999`, `5000-7499`, `7500-9999`, `10000+`; `freedom_age_band`: `<45`, `45-49`, `50-54`, `55-59`, `60-64`, `65+`, `unreachable`; `sec_to_reveal_band`: `0-10`, `11-30`, `31-60`, `61-120`, `120+`; `input_edits_band`: `0`, `1-3`, `4-8`, `9-15`, `16+` |
| 4 | `Recalculated` | Any later click of `#reveal-btn` (not live slider updates: `liveUpdate()` re-renders without tracking). Dropped unless a first reveal succeeded | `edits_after_reveal_band`: `0`, `1-3`, `4-8`, `9-15`, `16+` |
| 5 | `Email Focused` | `focus` on `#capture-email` (`index.html`, `about.html`) | none (`{}`) |
| 6 | `Waitlist Submitted` | A 2xx from `POST /api/waitlist` whose `status` is not `already_joined`. Under double opt-in an address that is on the list but unconfirmed is answered exactly like a fresh signup (`status: "WAITLISTNORMAL"`, `requiresConfirmation: true`, no `realStatus`), so re-submitting it fires this event, not a failure: the page cannot tell the two apart | `source_page`: `home`, `about`; `revealed`: `yes`, `no`; `utm_source` (last touch, else `direct`); `utm_medium` (else `none`); `utm_campaign` (else `none`); only when `revealed` is `yes`: `return_scenario`, `freedom_age_band` |
| 7 | `Waitlist Failed` | Client validation (empty or invalid address, or no Turnstile token yet: `captcha_failed`), a non-2xx, a network failure or timeout, or a 2xx with `status: "already_joined"` (a confirmed duplicate; with double opt-in off, any duplicate) | `reason`: `invalid_email`, `empty_email`, `duplicate`, `rate_limited`, `server_error`, `network_error`, `timeout`, `geo_blocked`, `captcha_failed`. Anything else is coerced to `server_error` |
| 8 | `Waitlist Confirmed` | `frontend/confirmed.html` when the URL has `status=confirmed` and `hours` is one of the bands | `hours_to_confirm_band`: `<1`, `1-6`, `6-24`, `24-72`, `72+` (the backend passes the band; anything else is dropped) |

Event 8 fires on its own page load with its own pageview, so it sits outside the landing-page budget in the header comment of `analytics.js`.

Per-event steps:

- **1 Calc Engaged. Do:** drag the age slider first. **Expect:** one event, `first_field: "age"`. Drag another slider: no second event. **If not:** two events mean the `fired` guard is broken; a missing event means `recordInput` was not called from that control.
- **2 Explainer Opened. Do:** open the "how this is calculated" details block. **Expect:** one event, empty props; closing and reopening sends nothing more.
- **3 Freedom Date Revealed. Do:** set age 30, invest 1000, income 5000, moderate, wait 15 seconds after the first slider touch, reveal. **Expect:** `age_band: "30-34"`, `invest_band: "1000-1999"`, `target_income_band: "5000-7499"`, `return_scenario: "moderate"`, `sec_to_reveal_band: "11-30"`, `input_edits_band` reflecting the number of debounced edits (400 ms per field), `freedom_age_band` matching the rendered age. Set invest to 0 and reveal on a fresh load: `freedom_age_band: "unreachable"`. Reveal without touching anything: `sec_to_reveal_band: "0-10"`, `input_edits_band: "0"`. **If not:** any raw number in the props is a leak (B7).
- **4 Recalculated. Do:** after a reveal, move a slider (result updates live, no event), then click the reveal button again. **Expect:** exactly one `Recalculated` for the page load, `edits_after_reveal_band` counting edits since the first reveal. **If not:** an event before any reveal means `calc.revealed` gating failed.
- **5 Email Focused. Do:** click into the email field, click out, click in again. **Expect:** one event.
- **6 Waitlist Submitted. Do:** sign up with a new address from `/?utm_source=qa&utm_medium=test&utm_campaign=launch`. **Expect:** `source_page: "home"`, `utm_source: "qa"`, `utm_medium: "test"`, `utm_campaign: "launch"`, `revealed: "yes"` or `"no"` matching what you did, and the two calculator props only when `yes`. From `/about` with no UTM, in a new tab (last touch lives in `sessionStorage`, so a tab that earlier loaded a UTM URL still carries it): `source_page: "about"`, `revealed: "no"`, `utm_source: "direct"`, `utm_medium: "none"`, `utm_campaign: "none"`. **If not:** `utm_content` or `utm_term` in the props is a leak (they go to the database only, `attribution.js` `forPlausible()`).
- **7 Waitlist Failed. Do:** on one page load submit empty (`empty_email`), then `foo` three times (`invalid_email`): three events total, not four (`FAILED_LIMIT`). On a fresh load submit an address that is on the list and confirmed: `duplicate`, and the visitor still sees the success state (an existing address is not a new signup; this doubles as a returning-interest signal). An address that is on the list but unconfirmed is not a duplicate while double opt-in is on: `WaitlistController.joinWaitlist` answers it exactly like a fresh signup (`status: "WAITLISTNORMAL"`, `requiresConfirmation: true`, no `realStatus`, never `already_joined`), the page fires `Waitlist Submitted` and shows the pending block again, and the backend queues a fresh confirmation only when `AddressRateLimiter.allow(email)` permits (1 per 10 minutes, 3 per day per address, the same limiter as `/resend-confirmation`, D7); over that limit the response is identical and nothing is queued. Repeat the confirmed-duplicate submit on three fresh loads within a minute from one IP, then a fourth: `rate_limited`. Each attempt needs the form back: the page stores the returned status in `localStorage.waitlist_status` (`pending_confirmation`, plus the address under `waitlist_pending`, for a pending signup) and a reload shows the success or pending state instead of the form, so before each reload run `localStorage.removeItem('waitlist_status'); localStorage.removeItem('waitlist_pending')`, or click "Wrong address? Use a different one" in the pending block (`pending-reset`, which clears both through `restoreForm()`), or use a fresh private window. The IP limiter runs before the duplicate check in `WaitlistController.joinWaitlist`, so duplicate submissions consume the 3-per-minute budget. **Expect:** one event per attempt with the listed reason, never more than three per page load. **If not:** a fourth failure event on one load means the cap is broken; a `duplicate` for an unconfirmed address means the backend answered `already_joined` for it, which would make the signup form a confirmation-status oracle.
- **8 Waitlist Confirmed. Do:** click a confirmation link. **Expect:** on `/confirmed?status=confirmed&hours=%3C1&tier=founder` (`tier=normal` once 300 founders are confirmed; no `tier` at all when the row was already `INVITED`, `CLAIMED` or `DECLINED`) one event with `hours_to_confirm_band: "<1"`, and the address bar changes to `/confirmed?status=confirmed` (the page strips both `hours` and `tier` with `history.replaceState`). `localStorage.waitlist_status` is `"WAITLISTFOUNDER"` (`"WAITLISTNORMAL"` for `tier=normal`, `"confirmed"` when the redirect carried no `tier`) and `waitlist_pending` is gone. Reload: no second event. Then open `/confirmed?status=confirmed` typed by hand in a fresh private window: the confirmed copy shows, but no event fires and nothing is written to `localStorage` (only a redirect that carries `hours` marks the browser; a shared or retyped URL changes nothing). On `/about` afterwards the price-card CTA reads "Your spot in line is reserved" only for a stored `WAITLISTFOUNDER`; `WAITLISTNORMAL`, `already_joined` and `"confirmed"` all read "You’re on the priority waitlist". **If not:** a second event on reload means the `hours` parameter survived; a stored status after the hand-typed URL means the `hours !== null` guard in `confirmed.html` is gone.

### B5. Once-per-page-load guards

The guard is in memory (`const fired = new Set()` in `analytics.js`); it resets on every page load and is not stored anywhere. So:

- **Do:** Within one page load, repeat each of events 1 to 6 (drag twice, open the explainer twice, focus twice, reveal twice).
- **Expect:** Each of events 1, 2, 3, 5, 6 appears once; event 4 appears once; event 7 at most three times.
- **Do:** Reload the page and engage the calculator again.
- **Expect:** `Calc Engaged` fires again. This is by design (no persistent visitor state, see the header of `attribution.js`); Plausible's own session logic, not this file, decides how it is counted. The only event that must not re-fire on reload is `Waitlist Confirmed` (B4, item 8).
- **If not:** A repeated event within one load means the `ONCE` set or the `fired` set is wrong.

### B6. No PII in any payload

The body of `POST /api/event` is JSON with `n` (event name), `u` (`location.href`), `d` (`data-domain`), `r` (referrer or null) and `p` (props); `v` is the script version (upstream script fetched 2026-09-24).

- **Do:** For every request in B4, open the request's Payload tab.
- **Expect:** No email address anywhere. No raw age, amount or freedom age (bands only). No `utm_content`, `utm_term`. No token: `u` on `/confirmed` carries only `status`, even on the first load. The page calls `track` before `replaceState`, but at that moment `window.plausible` is still the queue shim; Plausible's deferred script executes after the page script and reads `location.href` only when it replays the queued call, by which time `hours` and `tier` are gone. `r` is whatever `document.referrer` the browser exposes; our code adds nothing to it (our own `referrer_host` goes to the database only).
- **If not:** Any raw value or address in `p` means a page script bypassed `analytics.js`; grep the pages for `plausible(`. As of 2026-09-24 the only matches are the comments in `index.html`, `about.html` and `confirmed.html` saying nothing calls `plausible()` directly; the queue shim in `<head>` does not contain that string. Any other match is a bypass.

### B7. `Outbound Link: Click` (out of budget)

`frontend/_worker.js` proxies `script.outbound-links.js`, so Plausible's script itself sends `Outbound Link: Click` with prop `url` whenever a clicked anchor's `host` is non-empty and differs from `location.host`. It does not pass through `analytics.js`, so it has no once-per-load guard, but the hostname gate still applies (B3 drops it on previews). It is a custom event and therefore billable, and it is not one of the seven events above.

Outbound anchors in the working tree: three, all on `privacy.html` (`https://plausible.io/privacy`, `https://www.cloudflare.com/privacypolicy/`, `https://railway.com/legal/privacy`, each `target="_blank"`). None on `/`, `/about`, `/terms`, `/confirmed`. `mailto:` links have an empty `host` and are not counted.

Volume estimate (assumption, not measurement): only visitors who open `/privacy` and then click a vendor policy link produce one. If 2% of visitors open the privacy page and 10% of those click a vendor link, that is about 2 events per 1,000 visitors, so at 5,000 visitors a month roughly 10 billable events. Negligible next to pageviews; revisit if outbound links are added to `/` or `/about`.

- **Do:** On a forced preview, open `/privacy` and click "plausible.io/privacy".
- **Expect:** One `POST /api/event` with `"n":"Outbound Link: Click"` and `"p":{"url":"https://plausible.io/privacy"}`.
- **If not:** No event means the proxied script is not the outbound-links build (check `PLAUSIBLE_SCRIPT_URL` in `_worker.js`, and the Pages environment variables of the same name: `serveScript` uses `env.PLAUSIBLE_SCRIPT_URL` when set, and `forwardEvent` likewise honours `env.PLAUSIBLE_EVENT_URL`).

---

## C. Preview vs production

`frontend/assets/waitlist.js` decides the environment from `location.hostname`:

| Host | `isDev` | Turnstile sitekey | `API_BASE` |
|---|---|---|---|
| `localhost`, `127.0.0.1`, `file:`, private LAN IPs (`192.168.*`, `10.*`, `172.16-31.*`) | true (`isLocal`) | `1x00000000000000000000AA` (test key, always passes) | `http://<hostname>:8081/api/waitlist` |
| `fredvested-landing-page.pages.dev` and any `*.fredvested-landing-page.pages.dev` | true (`isPreview`) | test key | `https://lpapi-dev.fredvested.com/api/waitlist` |
| `*.ngrok-free.app`, `*.ngrok.io` | true (`isNgrok`) | test key | `https://lpapi-dev.fredvested.com/api/waitlist` |
| anything else, including `fredvested.com` | false | `0x4AAAAAACr1ix6Vcrw7VxES` | `https://lpapi.fredvested.com/api/waitlist` |

Analytics is gated separately by `analytics.js` (B1): only `fredvested.com` and `www.fredvested.com` send. The dev API's CORS (`application-dev.properties`, `cors.allowed.origins`) allows `http://127.0.0.1:5500` and `https://*.fredvested-landing-page.pages.dev`; production's origin is deliberately absent. Dev confirmations redirect to `landing.url` = `https://develop.fredvested-landing-page.pages.dev` by default (`LANDING_URL` env var), never to production. Cloudflare Pages project: root directory = repo root, build output = `frontend/`.

- **Do:** On a preview, run `window.FredWaitlist.isDev`, `window.FredWaitlist.API_BASE`, `window.FredWaitlist.TURNSTILE_SITEKEY` in the console, then sign up with a throwaway address.
- **Expect:** `true`, `https://lpapi-dev.fredvested.com/api/waitlist`, `1x00000000000000000000AA`. The `POST` goes to `lpapi-dev`, the row appears in the dev database, the confirmation email's links start with `https://lpapi-dev.fredvested.com/`, and the confirmed page is `https://develop.fredvested-landing-page.pages.dev/confirmed?...`.
- **Do:** On `https://fredvested.com/`, the same three values.
- **Expect:** `false`, `https://lpapi.fredvested.com/api/waitlist`, `0x4AAAAAACr1ix6Vcrw7VxES`. Turnstile renders the real widget.
- **If not:** A preview posting to `lpapi.fredvested.com` puts test rows in the production database and test events in production analytics (if forced). A production page with the test sitekey means bots pass Turnstile.

---

## D. Email funnel

Code: `backend/src/main/java/com/fredvested/web/service/SignupService.java`, `EmailOutboxPublisher.java`, `EmailTemplates.java`, `EmailEventProcessor.java`, `ResendWebhookVerifier.java`, `ConfirmationTokens.java`, `AddressRateLimiter.java`, `LandingUrls.java`; controllers `WaitlistController.java`, `WaitlistConfirmationController.java`, `ResendWebhookController.java`; migrations `V4__email_outbox_events_and_confirmation.sql`, `V5__confirmed_source_and_legacy_backfill.sql`.

Flow: `POST /api/waitlist` writes the `waitlist_signups` row and a `pending` row in `email_message` (`template = 'waitlist_confirmation'`) in one transaction. `EmailOutboxPublisher.publishPending()` runs every 5 s (`email.outbox.poll-ms`, first run after 10 s), claims the row, refuses suppressed addresses, mints the confirmation and unsubscribe tokens at send time (only SHA-256 hashes are stored: `waitlist_signups.confirmation_token_hash`, `email_message.unsubscribe_token_hash`), sends through Resend from `FRED <fred@fredvested.com>` (`EMAIL_FROM`), and records `resend_email_id`. Both emailed links are two-step (Andrew's decision, 2026-09-24): the GET renders a self-contained page and has no side effect; the POST behind it does the work. The two pages differ on purpose. The confirm page's inline script POSTs the form on load (scanners fetch HTML and mostly do not run it, and a consumed confirmation token is recoverable through resend). The unsubscribe page has a visible `Unsubscribe` button and no `<script>` at all, because a suppression is never cleared; unsubscribe is the footer link on every email; no `List-Unsubscribe` headers are sent on any email (Andrew, 2026-09-25: mail clients would label the message as list mail), and `POST /api/waitlist/unsubscribe` still honours an RFC 8058 one-click body for the day volume requires the headers (D8).

### D1. `API_PUBLIC_URL` and `LANDING_URL` per environment

Rule (Andrew's wording): "API_PUBLIC_URL must be set per environment or confirmation links point at production."

What the code does today: `api.public-url` has a default per profile (`application.properties`: `http://localhost:8081`; `application-dev.properties`: `https://lpapi-dev.fredvested.com`; `application-prod.properties`: `https://lpapi.fredvested.com`), so an unset variable falls back to that profile's own API. A blank variable is treated as unset by `BlankEnvironmentVariables` (D11). `EmailOutboxPublisher.publicBaseUrl()` strips trailing slashes and upgrades `http://` to `https://` for any non-local host, logging a WARN. The wrong-environment failure therefore comes from a wrong value (for example the production origin pasted into the dev service), not from a missing one. `landing.url` (`LANDING_URL`) works the same way: default `https://fredvested.com` in `LandingUrls`, overridden to the develop preview in `application-dev.properties`, `same-host` in the local profile (redirects to `http://<host>:5500/confirmed.html`).

- **Do:** In Railway, on each backend service (dev, then prod), read `API_PUBLIC_URL` and `LANDING_URL`.
- **Expect:** Dev: `https://lpapi-dev.fredvested.com` and `https://develop.fredvested-landing-page.pages.dev` (or unset, which gives the same). Prod: `https://lpapi.fredvested.com` and `https://fredvested.com` (or unset). Both `https`, no trailing slash, no blank value.
- **If not:** Dev links that point at `lpapi.fredvested.com` will be answered by the production API, which does not know the token, so it redirects to `https://fredvested.com/confirmed?status=invalid`: a production pageview from a dev test (no `Waitlist Confirmed` event, since the status is not `confirmed`), and the dev row stays unconfirmed.

### D2. The link in a received email

- **Do:** Sign up on the environment under test. Open the email's plain-text part (or "show original").
- **Expect:** From `FRED <fred@fredvested.com>`. Subject `Confirm your email for FRED's waitlist`. The confirm link is `https://<api host>/api/waitlist/confirm?token=<43 URL-safe characters>` (32 random bytes, base64url without padding, `ConfirmationTokens.generate()`), the unsubscribe link `https://<api host>/api/waitlist/unsubscribe?token=...`, api host being `lpapi-dev.fredvested.com` on dev and `lpapi.fredvested.com` on prod. Both start with `https://`. The text part contains `Unsubscribe: https://...` and `FREDvested LLC, <postal address>`. The raw headers carry `Reply-To: help@fredvested.com` and no `List-Unsubscribe` header (Andrew, 2026-09-25); Apple Mail shows no "mailing list" strip.
- **If not:** `http://` means `API_PUBLIC_URL` names a local host (the upgrade only skips `localhost`, `127.0.0.1`, `::1` and private ranges). The wrong host means D1. Missing `List-Unsubscribe` headers mean the headers map never reached Resend (D8). `[PO Box pending]` in the footer means `POSTAL_ADDRESS` is unset on that service, and it can only happen on dev or local: `application.properties` supplies that default, but `application-prod.properties` has `email.postal-address=${POSTAL_ADDRESS}` with no default, so the production service does not start without the variable (D11) and the placeholder can never reach a production email.

### D3. Consent-only confirmation email

Counsel's rule (2026-09-24): the double opt-in email exists to obtain consent and carries nothing promotional. `EmailTemplatesTest.confirmationEmail_isConsentOnly_andCarriesNoPromotionalCopy` holds the deny-list: `founding`, `founder`, `300`, `spots`, `$`, `pricing`, `lifetime`, `autopilot`, `paycheck`, `clock out`, `monte carlo`, `withdrawal`, `financial future`, `private beta`, `beta`, `reviewed in waves`, `waves`, `48 hours`, `claim`, `invite`, `priority`, `what happens next`, `secure your spot`, `lock in` (case-insensitive, across subject, HTML and text).

- **Do:** `cd backend && ./gradlew test --tests '*EmailTemplatesTest*'`, then read the received email once more against the list.
- **Expect:** Tests pass. The email says why it was sent ("because this address was entered on the waitlist form at fredvested.com"), has the button `Confirm my email`, "This link expires in 7 days. If you didn't sign up, you can ignore this email and nothing will happen.", the sign-off "The FRED Team" with `help@fredvested.com`, the footer sentence "You're receiving this because this address was entered at fredvested.com." with the `Unsubscribe` link, and `FREDvested LLC, <postal address>`. Nothing else (`EmailTemplates.confirmation()` and `shell()`).
- **If not:** Any copy change to `EmailTemplates.confirmation()` that trips the list is a counsel issue, not a wording preference.

### D4. GET and HEAD on the confirm link do not consume the token

`GET /api/waitlist/confirm` never looks the token up; it returns the same auto-post page for every token with `Cache-Control: no-store`. `HEAD` is mapped explicitly and returns 200 with no body. Test coverage: `backend/src/test/java/com/fredvested/web/controller/ConfirmationGetIsSideEffectFreeTest.java`.

```bash
LINK='https://lpapi-dev.fredvested.com/api/waitlist/confirm?token=...'   # from the email
curl -sS -I "$LINK" | head -1                       # HEAD, as link scanners probe
curl -sS "$LINK" | grep -o '<form[^>]*>'            # GET without JavaScript
```

- **Expect:** A 200 status line for both. The GET body contains `<form id="f" method="post" action="/api/waitlist/confirm">` and a hidden `token` input. Then in the database: `SELECT confirmation_token_hash IS NOT NULL AS has_token, confirmed_at FROM waitlist_signups WHERE email = '<address>';` gives `has_token = 1`, `confirmed_at = NULL`.
- **If not:** A cleared hash or a set `confirmed_at` after a GET means the scanner protection is gone; every scanned email would burn its token.
- **Known gap (recorded):** a scanner that executes JavaScript would still submit the form and consume the token. The `<noscript>` button and this two-step page defend against fetch-only scanners. The gap is accepted here only because a consumed confirmation token is recoverable through resend; the unsubscribe page carries no script at all for exactly that reason (D8).

### D5. POST confirms once; a second POST is `status=invalid`

```bash
TOKEN='...'   # the value after token= in the email link
curl -sS -o /dev/null -w '%{http_code} %{redirect_url}\n' -X POST "$API/api/waitlist/confirm" --data-urlencode "token=$TOKEN"
curl -sS -o /dev/null -w '%{http_code} %{redirect_url}\n' -X POST "$API/api/waitlist/confirm" --data-urlencode "token=$TOKEN"
```

- **Expect:** First: `302 <landing>/confirmed?status=confirmed&hours=<band>&tier=founder` (or `&tier=normal`; no `tier` parameter when the row was already `INVITED`, `CLAIMED` or `DECLINED`, whose status is left alone) where the band is URL-encoded (`%3C1` for `<1`) and measured from `confirmation_sent_at` (falls back to `created_at`). Second: `302 <landing>/confirmed?status=invalid`, byte-identical to a never-issued token. Database: `confirmed_at` set, `confirmed_source = 'double_opt_in'`, `confirmation_token_hash = NULL`, `confirmation_expires_at = NULL`, `status` is `WAITLISTFOUNDER` or `WAITLISTNORMAL` (decided now, against confirmed founders only, `FOUNDER_CAP = 300`) and matches the `tier` in the redirect: `SignupService.Confirmation` is `(outcome, hoursBand, status)` and `WaitlistConfirmationController.tier()` maps the status to the parameter. The POST must be `application/x-www-form-urlencoded` (`consumes` on the mapping), which is what `--data-urlencode` sends.
- **If not:** A second `status=confirmed` means the hash was not cleared (single use broken). An `INVALID` on the first POST with a fresh link means the token hash in the database does not match the emailed token: usually a resend or a duplicate send minted a newer one (only the most recent link for an address is valid).

### D6. Expired path

`SignupService.confirm()` returns `EXPIRED` when `now` is after `confirmation_expires_at`, or when that column is NULL for a row whose hash matched (`waitlist.confirmation.ttl-days = 7`); it does not clear the token.

- **Do:** For a fresh, unconfirmed signup: `UPDATE waitlist_signups SET confirmation_expires_at = confirmation_expires_at - INTERVAL 8 DAY WHERE email = '<address>';` (relative to the stored value: the column holds Eastern wall-clock time, so never set it from `NOW()`). Then the D5 POST.
- **Expect:** `302 <landing>/confirmed?status=expired`; `/confirmed` shows "That link has expired" with the resend form. The row is still unconfirmed and still has its hash. Request a resend (D7): a new email arrives, the old link now gives `status=invalid` (the hash was overwritten by `WaitlistRepository.setConfirmationToken`).
- **If not:** A `status=confirmed` on an expired token means the expiry check is bypassed.

### D7. Resend limits

`POST /api/waitlist/resend-confirmation` (JSON `{"email": "..."}`) answers identically whether or not the address exists. Limits run before any lookup: `RateLimiterService` (3 requests per minute per IP hash, the same bean and key as `POST /api/waitlist`, so signups and resends from one IP share the budget) and `AddressRateLimiter` (1 per 10 minutes, 3 per 24 hours, per SHA-256 of the address). Both are in memory: a redeploy resets them. The same `AddressRateLimiter` bean also gates the confirmation that `WaitlistController.joinWaitlist` queues when an unconfirmed address is re-entered in the signup form (`addressLimiter.allow(email)` before `signupService.requestResend(email)`; over the limit the response is unchanged and nothing is queued), so a form re-submit and a resend request draw on one per-address budget (B4, item 7).

Pending state in the browser (`frontend/assets/waitlist.js`, used by `index.html` and `about.html`): a signup answered with `requiresConfirmation: true` stores `waitlist_status = "pending_confirmation"` and `waitlist_pending = {"email": "<address>", "at": <ms since epoch>}` (JSON; there is no `waitlist_pending_email` key). `getPendingEmail()` treats a record older than 7 days (`PENDING_MAX_AGE_MS`) as absent and removes it, so a browser whose link was clicked elsewhere gets the form back on its next visit; on load, a `pending_confirmation` status with no valid record is cleared the same way. The address is also kept in the page variable `pendingEmailMemory` for the session, so "resend the email" (`pending-resend`) works with storage blocked; with neither source it restores the form and asks for the address again. "Wrong address? Use a different one" (`pending-reset`) calls `restoreForm()`: it clears `waitlist_pending` and `waitlist_status`, calls `resetForm()` and the Turnstile reset, and shows the form again (`about.html` also puts the nav label back to "Get my Freedom Date").

```bash
curl -sS -w '\n%{http_code}\n' -X POST "$API/api/waitlist/resend-confirmation" -H 'content-type: application/json' -d '{"email":"<address>"}'
```

- **Expect:** First call: `{"status":"ok"}` 200. Second call within 10 minutes: `{"code":"rate_limited","message":"Too many requests. Please try again later."}` 429. Same two answers for an address that is not on the list. Fourth call in 24 hours: 429. Malformed address: `{"code":"invalid_email","message":"Please enter a valid email address."}` 400. Database, for an unconfirmed unsuppressed address: a new `email_message` row (`template = 'waitlist_confirmation'`, `status = 'pending'`), and any older pending confirmation for the same row set to `status = 'skipped'`, `last_error = 'superseded by a resend request'`. For a confirmed, suppressed or unknown address: no new row. `/confirmed` shows the same sentence for any completed request: "If that address is on our waitlist, a new confirmation link is on its way. Check your inbox." (the pending block's `pending-resend` button on `index.html` and `about.html` shows the same sentence).
- **Do (pending state):** Sign up with a throwaway address and reload: the pending block shows the address. In the console: `localStorage.setItem('waitlist_pending', JSON.stringify(Object.assign(JSON.parse(localStorage.getItem('waitlist_pending')), { at: Date.now() - 8 * 24 * 60 * 60 * 1000 })))`, then reload. Sign up again and click "Wrong address? Use a different one".
- **Expect:** After the aged record: the form is back and both `waitlist_pending` and `waitlist_status` are gone. After the reset click: the form is back and focused, still showing the address you typed (`restoreForm()` resets the form's styles and the Turnstile widget, not the input's value), both keys gone, no request made.
- **If not:** Different bodies or statuses for known and unknown addresses would let the endpoint probe the list. A 429 on the first call usually means the IP budget was spent by signups seconds earlier, or the per-address budget by re-submitting the same unconfirmed address through the signup form. A pending block that survives the aged record means `getPendingEmail()` is not checking `at`.
- **Known gap (recorded):** an ambiguous send (Resend accepted the message but the publisher died before recording it) is not reconciled. The row is retried after `email.outbox.stale-sending-minutes` (15) and each send mints a new token, so the earlier email's link lands on `status=invalid`; the visitor's remedy is the resend form.

### D8. Unsubscribe: a human click or an RFC 8058 one-click POST, never a bare GET

`GET /api/waitlist/unsubscribe?token=` checks whether the token was issued (`SignupService.hasUnsubscribeToken`, no write) and renders a page with a visible `Unsubscribe` button and no `<script>` at all (`WaitlistConfirmationController.unsubscribePage`); an unknown token gets a plain "Unsubscribe link not valid" page with no form. Nothing auto-submits here, unlike the confirm page: a suppression is never cleared, so a mail security sandbox that does execute JavaScript (Defender Safe Links, Proofpoint TAP, Mimecast) must not be able to unsubscribe a recipient on delivery. Mail clients get one-click unsubscribe through RFC 8058 instead: `EmailOutboxPublisher.publish()` sends every email with the headers `List-Unsubscribe: <unsubscribe url>` and `List-Unsubscribe-Post: List-Unsubscribe=One-Click` (`EmailService.send` takes a headers map), and `POST /api/waitlist/unsubscribe` with the form body `List-Unsubscribe=One-Click` (token in the query string) unsubscribes and answers `200` `text/plain` with the body `Unsubscribed.` and no redirect, the same for an unknown token. The button's POST (no `List-Unsubscribe` field) still redirects with `302`. `SignupService.unsubscribe` suppresses and is idempotent.

```bash
UNSUB='https://lpapi-dev.fredvested.com/api/waitlist/unsubscribe?token=...'   # from the email footer
curl -sS "$UNSUB" | grep -o -E '<form[^>]*>|<script|<button[^>]*>Unsubscribe</button>'
UTOKEN='...'
curl -sS -o /dev/null -w '%{http_code} %{redirect_url}\n' -X POST "$API/api/waitlist/unsubscribe" --data-urlencode "token=$UTOKEN"          # the button
curl -sS -w '\n%{http_code} %{content_type}\n' -X POST "$API/api/waitlist/unsubscribe?token=$UTOKEN" --data 'List-Unsubscribe=One-Click'   # a mail client
```

- **Expect:** GET: 200 with `<form method="post" action="/api/waitlist/unsubscribe">` and `<button type="submit">Unsubscribe</button>`, and no `<script` match; the database is unchanged after it. Button POST: `302 <landing>/confirmed?status=unsubscribed`; `suppressed_at` set, `suppression_reason = 'unsubscribe'`; the row appears in `SELECT * FROM v_email_suppressions;`. Repeating the POST: the same `status=unsubscribed`. One-click POST: the body `Unsubscribed.` then `200 text/plain` (a charset may follow), no `Location`. A made-up token: GET shows the "not valid" page, the button POST redirects to `status=invalid`, and the one-click POST still answers `200` `Unsubscribed.` (a mail client learns nothing about the token). In the received email's raw source (D2): both `List-Unsubscribe` headers, carrying the same token as the footer link.
- **If not:** A suppression after the GET alone, or a `<script` match in its body, means a JavaScript-executing sandbox could unsubscribe recipients on delivery. Missing headers mean Gmail, Apple Mail and Outlook show no unsubscribe control and fall back to the link, so check `EmailOutboxPublisher.publish()` passes the map to `EmailService.send`.

### D9. Suppression refuses the next send

Suppression is enforced in `EmailOutboxPublisher.publish()`, not only recorded: a claimed row for a suppressed address is finished as `status = 'suppressed'`, `last_error = 'address suppressed: <reason>'`, and nothing is sent. `requestResend` also returns early for a suppressed address, so the endpoint cannot create the row for you.

- **Do:** After D8 on an unconfirmed address, call D7's resend.
- **Expect:** `{"status":"ok"}` and no new `email_message` row.
- **Do (publisher path, dev only):** `INSERT INTO email_message (waitlist_id, template, status, queued_at, attempts) SELECT id, 'waitlist_confirmation', 'pending', created_at, 0 FROM waitlist_signups WHERE email = '<address>';`
- **Expect:** Within about 5 s the row reads `status = 'suppressed'`, `last_error = 'address suppressed: unsubscribe'`; the log has `Message <id> suppressed: address suppressed: unsubscribe`; no email arrives.
- **If not:** A sent email to a suppressed address is a compliance failure (the privacy policy promises the unsubscribe).

Other suppression sources (`EmailEventProcessor`): `email.bounced` with a non-`Transient` bounce type sets `hard_bounce`; `email.complained` sets `complaint`; `email.suppressed` sets `manual`. A transient bounce is recorded and does not suppress.

### D10. Webhook: signed, unsigned, stale, duplicate, ordering, opened

`POST /api/webhooks/resend` verifies `base64(HMAC-SHA256(secret, "{svix-id}.{svix-timestamp}.{raw body}"))` against every `v1,` entry in `svix-signature`, in constant time, before parsing JSON (`ResendWebhookVerifier`). The secret is `RESEND_WEBHOOK_SECRET` (`whsec_` prefix stripped, then base64-decoded). Signature is checked before the timestamp; tolerance is 300 s. Body cap 64 KB (413 above it). Idempotency is the `UNIQUE` on `email_event.svix_id`. Dashboard configuration (not code, Andrew and counsel 2026-09-24): endpoint `https://<api host>/api/webhooks/resend`; subscribe to `email.sent`, `email.delivered`, `email.delivery_delayed`, `email.bounced`, `email.complained`, `email.failed`, `email.suppressed`, `email.clicked`; do not subscribe `email.opened`; open tracking off on the sending domain.

Signing helper (zsh/bash; `base64 -D` on older macOS):

```bash
API=https://lpapi-dev.fredvested.com
SECRET="${RESEND_WEBHOOK_SECRET#whsec_}"                       # the dashboard value without its prefix
KEYHEX=$(printf '%s' "$SECRET" | base64 -d | xxd -p -c 256)
EMAIL_ID=<resend_email_id of a sent row: SELECT resend_email_id FROM email_message WHERE status = 'sent' ORDER BY id DESC LIMIT 1>
ID="msg_qa_$(date +%s)"; TS=$(date +%s)
BODY="{\"type\":\"email.delivered\",\"created_at\":\"$(date -u +%Y-%m-%dT%H:%M:%S.000Z)\",\"data\":{\"email_id\":\"$EMAIL_ID\"}}"
SIG=$(printf '%s.%s.%s' "$ID" "$TS" "$BODY" | openssl dgst -sha256 -mac HMAC -macopt hexkey:$KEYHEX -binary | base64)
curl -sS -w '\n%{http_code}\n' -X POST "$API/api/webhooks/resend" \
  -H 'content-type: application/json' -H "svix-id: $ID" -H "svix-timestamp: $TS" -H "svix-signature: v1,$SIG" \
  --data-binary "$BODY"
```

| Check | Do | Expect | If not |
|---|---|---|---|
| Signed | The helper as is | `{"status":"ok"}` 200; a row in `email_event` with that `svix_id`, `event_type = 'email.delivered'`; `email_message.status = 'delivered'`, `last_event_at` set; `waitlist_signups.email_status = 'delivered'` | 401 means the secret on the service differs from the one you signed with (or is unset, D11) |
| Unsigned | Same request without the three `svix-*` headers | `{"code":"unauthorized","message":"Invalid webhook signature."}` 401 | Anything else accepts forged events |
| Bad signature | `-H "svix-signature: v1,AAAA"` | The same 401 body | |
| Stale | Sign with `TS=$(( $(date +%s) - 600 ))` | `{"code":"stale","message":"Webhook timestamp outside tolerance."}` 400 | A 401 here means the signature was wrong first; fix that before judging staleness |
| Duplicate | Re-send the identical signed request (same `ID`) | `{"status":"duplicate"}` 200, no second `email_event` row, no change to the message | Two rows for one `svix_id` means the UNIQUE key is missing |
| Ordering | After the signed `email.delivered`, send `email.sent` with a new `ID` and `created_at` one hour earlier | 200 `ok`; `email_message.status` stays `delivered`; log: `Out-of-order webhook ignored: sent at ... is older than ... already applied to message <id>` | Status regressing to `sent` means the `last_event_at` guard failed |
| Unknown message | `email_id` set to `re_doesnotexist` | 200 `ok`; a row in `email_event`; nothing else changes; log: `Webhook email.delivered for unknown message id; recorded and ignored` | A non-200 makes Resend retry forever |
| Opened | `type` set to `email.opened` | `{"status":"ok"}` 200, byte-identical to the Signed row's answer (`ResendWebhookControllerTest.ignoredType_is200Ok_indistinguishableFromRecorded`; the controller maps every outcome but `DUPLICATE` to `ok`); `SELECT COUNT(*) FROM email_event WHERE event_type = 'email.opened';` stays 0; log: `Webhook type email.opened is not tracked; ignored` (`EmailEventProcessor` returns `Outcome.IGNORED` for `email.opened` and for any unknown type, asserted by `EmailEventProcessorTest.openedEvent_isIgnored_andNeverPersisted` and `unknownEventTypes_areIgnored_andNeverPersisted`) | A stored row would be open tracking by another name (counsel) |
| Payload stripped | Any stored event | `SELECT JSON_KEYS(payload->'$.data') FROM email_event ORDER BY id DESC LIMIT 1;` contains no `html`, `text` or `click` | `EmailEventProcessor.stripContent` is not running |

### D11. Startup WARN lines in the Railway log

- **Do:** Open the deploy log of each backend service and read the first screen after the banner.
- **Expect:** `Started WaitlistApplication`, preceded by Flyway lines that list exactly the migrations the database was missing (F). No WARN of these forms:
  - `Ignoring blank environment variable(s) [NAME]: treated as unset, so their configured defaults apply. Set a value or delete the variable.` (`BlankEnvironmentVariables`, registered in `META-INF/spring.factories`). It is a warning, not a failure: the variable exists in the dashboard with an empty value and the profile default applied. Railway dev had `WAITLIST_DOUBLE_OPT_IN=""` on 2026-09-24. Set a value or delete the variable.
  - `resend.webhook-secret is not set or is not valid base64 (expected the dashboard's whsec_... value): every webhook delivery will be rejected with 401` (`ResendWebhookVerifier`). D10 will fail wholesale.
  - `api.public-url is http://...; emailed links will use https://... instead. Set API_PUBLIC_URL to the https origin.` (`EmailOutboxPublisher.publicBaseUrl`). Links still work; fix the variable.
- **If not:** `APPLICATION FAILED TO START` with `Schema-validation: missing column` means an entity changed without a migration (F). Fix the migration, never `ddl-auto`. `APPLICATION FAILED TO START` on the production service with `Could not resolve placeholder 'POSTAL_ADDRESS'` means the variable is unset: `application-prod.properties` gives `email.postal-address=${POSTAL_ADDRESS}` no default on purpose, so production can never send the `[PO Box pending]` placeholder (D2). Set the variable; never add a default.

---

## E. Data

### E1. `/stats` counts confirmed rows only

`GET /api/waitlist/stats` (`WaitlistController.buildStatsMap`) returns aggregates only: `status`, `count`, `founderCount`, `avgFreedomAge`, `projectionCount`, `projectionStartDate`, `projectionEndDate`. `count` is `countByConfirmedAtIsNotNull()`, `founderCount` is `countByStatusAndConfirmedAtIsNotNull(WAITLISTFOUNDER)`. The head-start average uses only confirmed rows with `interacted = true`, `return_assumption_pct IN (8, 10, 12)` and `freedom_age BETWEEN 18 AND 100`. The response is memoised for `waitlist.stats-cache-ms` (30000 ms). A signup clears the cache (`cachedStats = null` in `joinWaitlist`, harmless under double opt-in since the numbers do not move), and so does anything that changes the confirmed population: `SignupService.confirm` and the single-opt-in path of `SignupService.createSignup` publish the Spring application event `SignupService.WaitlistCountsChanged`, and the `@EventListener` `onWaitlistCountsChanged` in `WaitlistController` nulls the memo (`WaitlistControllerStatsCacheTest.aConfirmation_invalidatesTheCache`). Only a change made outside the app, such as a direct database edit, can lag by up to 30 s. The pages hide the count below 25 (`STAT_TILE_MIN_N` in `waitlist.js`).

- **Do:** `curl -sS "$API/api/waitlist/stats"` and `SELECT COUNT(*) AS confirmed, SUM(status = 'WAITLISTFOUNDER') AS founders FROM waitlist_signups WHERE confirmed_at IS NOT NULL;`
- **Expect:** `count` equals `confirmed` and `founderCount` equals `founders` (allow the 30 s cache only for rows you changed by hand in the database). No per-row data in the body. Unconfirmed signups (`confirmed_at IS NULL`) do not move either number.
- **If not:** A `count` above the SQL value means unconfirmed rows are being counted (`WaitlistControllerConfirmedOnlyStatsTest` covers this).

### E2. `v_waitlist_funnel` sanity

```sql
SELECT utm_source, utm_campaign, utm_content, submitted, confirmation_sent, delivered, confirmed,
       confirmed_double_opt_in, confirmed_legacy, confirmed_single_opt_in, beta_invited,
       sent_rate, delivered_rate, confirmed_rate, invited_rate, bounce_rate, complaint_rate
FROM v_waitlist_funnel ORDER BY submitted DESC;
```

- **Expect:** Per row: `confirmation_sent <= submitted`; `delivered <= confirmation_sent`; `confirmed = confirmed_double_opt_in + confirmed_legacy + confirmed_single_opt_in`, also for a group whose rows are all still pending: the three source columns and the numerator of `confirmed_rate` use the null-safe comparison `confirmed_source <=> 'x'` (V5), so such a group reports 0, never NULL; every rate is NULL or between 0 and 1. Legacy rows group under NULL `utm_*`. There is no opens column anywhere (V5 header comment). `delivered` counts the first accepted confirmation email per signup for which an `email.delivered` event was ever received.
- **If not:** `delivered = 0` while `confirmed_double_opt_in > 0` means confirmation emails are arriving but no `email.delivered` webhooks are: check the Resend endpoint URL and D10/D11, not the mail itself.

### E3. Legacy rows tagged

V5 backfilled every row with `confirmed_at IS NULL` and no `waitlist_confirmation` outbox row as `confirmed_source = 'legacy'`, `confirmed_at = IFNULL(created_at, CURRENT_TIMESTAMP(6))`.

```sql
SELECT confirmed_source, COUNT(*) FROM waitlist_signups GROUP BY confirmed_source;
SELECT COUNT(*) FROM waitlist_signups WHERE confirmed_at IS NOT NULL AND confirmed_source IS NULL;
SELECT COUNT(*) FROM waitlist_signups w
 WHERE w.confirmed_at IS NULL
   AND NOT EXISTS (SELECT 1 FROM email_message m WHERE m.waitlist_id = w.id AND m.template = 'waitlist_confirmation');
```

- **Expect:** Sources are only `double_opt_in`, `legacy`, `single_opt_in` and NULL (NULL = still unconfirmed). The second and third counts are 0 (a new signup queues its confirmation in the same transaction, so the third stays 0 after V5).
- **If not:** A non-zero third count means rows exist that neither the funnel nor the public count can explain; a non-zero second count means a code path sets `confirmed_at` without `confirmed_source`.

### E4. Founder cap

- **Do:** `SELECT COUNT(*) FROM waitlist_signups WHERE status = 'WAITLISTFOUNDER' AND confirmed_at IS NOT NULL;`
- **Expect:** At most 300 (`SignupService.FOUNDER_CAP`). Unconfirmed rows are `WAITLISTNORMAL` placeholders and hold no slot.
- **Known gap (recorded):** the slot decision in `SignupService.founderSlotStatus()` is a count followed by a write with no lock, so concurrent confirmations in a burst can exceed 300 by a few. (`SignupService.confirm` runs that count before it dirties the managed row, and `createSignup` does the same on the single-opt-in path, so a Hibernate auto-flush can never let the row count itself; the gap is between transactions, not inside one.) If the count is above 300, that is the cause, not a counting bug.

---

## F. Migrations

Every schema change is a new `backend/src/main/resources/db/migration/V<n>__<description>.sql`; `spring.jpa.hibernate.ddl-auto=validate` in every profile, so an entity change without a migration crashes the app on startup by design (`Schema-validation: missing column [...]`), and the fix is always a migration, never `update`. Before deploying any migration, dry-run it against a snapshot of the target environment's schema, never against local (local is usually ahead; that is how the V1 baseline was wrong). Per environment, dev then prod: (1) Railway, the target project, the MySQL service, Variables: copy the public connection values (`MYSQL_PUBLIC_URL`, or `RAILWAY_TCP_PROXY_DOMAIN` + `RAILWAY_TCP_PROXY_PORT` with `MYSQLUSER` / `MYSQLPASSWORD` / `MYSQLDATABASE`), and take a Railway backup of the volume first; (2) `mysqldump -h HOST -P PORT -u USER -p --no-data DATABASE > snapshot-schema.sql` and `mysqldump -h HOST -P PORT -u USER -p DATABASE flyway_schema_history > snapshot-history.sql` (the second fails harmlessly on a database Flyway has never touched); (3) `mysql -u root -p -e "DROP DATABASE IF EXISTS fred_snapshot; CREATE DATABASE fred_snapshot"`, then `mysql -u root -p fred_snapshot < snapshot-schema.sql` and `mysql -u root -p fred_snapshot < snapshot-history.sql`; (4) `./gradlew bootRun --args='--spring.profiles.active=local --spring.datasource.url=jdbc:mysql://localhost:3306/fred_snapshot?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC'` and expect Flyway to log exactly the migrations the target is missing, then `Started WaitlistApplication`; `APPLICATION FAILED TO START` with `Schema-validation` means the migration does not produce what the entities expect, so fix the migration; (5) `mysql -u root -p -e "DROP DATABASE fred_snapshot"`, deploy, and watch the Railway log for the same Flyway lines. Migrations that add columns a pre-Flyway database might lack must be idempotent (the `information_schema` + `PREPARE` pattern in V2 and V5; MySQL has no `ADD COLUMN IF NOT EXISTS`).

---

## Known gaps (summary)

1. Token re-mint after an ambiguous send is not handled; the visitor must use the resend form (D7).
2. A scanner that executes JavaScript would still consume a confirmation token (D4). It cannot unsubscribe anyone: the unsubscribe page has no script, and one-click unsubscribe requires a POST with the body `List-Unsubscribe=One-Click` (D8).
3. The founder-slot decision at confirmation is not serialised across concurrent confirmations; a burst could exceed 300 by a few (E4).

## Open questions

- `analytics.js` names `FREDdocs/analytics-events.md` as the canonical schema. That file is in the working tree on 2026-09-24 (untracked, alongside this one). The event table in B4 is taken from `analytics.js` directly, not from that document; if the two ever disagree, `analytics.js` is what ships.
- `CLAUDE.md` says the privacy policy names Plausible, Resend, Cloudflare and Railway, but `frontend/privacy.html` names only Plausible, Cloudflare and Railway (no occurrence of "Resend" in `privacy.html` or `terms.html`). Whether the policy must name the email provider before launch is a counsel question, not a code one.
- Resolved 2026-09-25: the Playwright suite lives at the repo root (section A4); it runs against the local server, not a preview.
- Resolved 2026-09-25: Turnstile contacts only `challenges.cloudflare.com` (observed by `tests/third-party-hosts.spec.js`).
- Resolved 2026-09-25: the once-per-visit guard being per page load is the designed behaviour (Plausible's goal reports count unique visitors per goal); see `analytics-events.md` Guards.
- `Recalculated` fires only on a second click of the reveal button, not on live slider changes after the reveal (B4, item 4). Confirm that is the intended meaning of the event.
- Forced preview events post to the production Plausible site (`data-domain="fredvested.com"` on every page). Decided 2026-09-25: previews do NOT get their own Plausible site. The hostname gate already keeps preview traffic out unless someone sets the force flag on purpose, and a second site would be a second thing to maintain. Clear `fred_analytics_force` after a forced check.
