# Analytics events

State of the working tree on 2026-09-24. Every statement below is read from the code; where a choice is Andrew's rather than the code's, it says so.

## Where things live

| Concern | File |
|---|---|
| Event names, prop keys, band edges, guards, hostname gate | `frontend/assets/analytics.js` (the only file that calls `window.plausible()`) |
| UTM capture that `Waitlist Submitted` reads | `frontend/assets/attribution.js` (`forPlausible()`) |
| Failure reason vocabulary on the network path | `frontend/assets/waitlist.js` (`FAILURE_REASONS`, `REASON_BY_STATUS`, `reasonFor`) |
| Page triggers | `frontend/index.html`, `frontend/about.html`, `frontend/confirmed.html` |
| Pages with a pageview only | `frontend/privacy.html`, `frontend/terms.html` |
| First-party proxy to Plausible | `frontend/_worker.js`, scoped by `frontend/_routes.json` |
| Hours-to-confirm band and decided tier | `backend/src/main/java/com/fredvested/web/service/SignupService.java` (`hoursBand`, `confirm`, the `Confirmation` record) and `WaitlistConfirmationController.confirm` (POST) with its `tier` helper |

Load order on every page: `assets/attribution.js`, then `assets/analytics.js`, then the page's own inline script, all classic scripts at the end of `<body>`. On index.html, about.html and confirmed.html `assets/waitlist.js` comes before those two (index.html:360, about.html:869, confirmed.html:139); on index.html and about.html the Turnstile `<script async defer>` (index.html:365, about.html:874) sits between `analytics.js` and the inline script and plays no part in analytics. privacy.html and terms.html load only `attribution.js` and `analytics.js` plus a link-rewriting inline script that calls nothing in `FredAnalytics`. The Plausible script is `<script defer data-domain="fredvested.com" src="/js/script.js">` in `<head>` (index.html:28, about.html:28, privacy.html:21, terms.html:21, confirmed.html:21) with the queue shim right after it (`window.plausible.q`). Deferred scripts run after the body scripts, so `analytics.js` decides the hostname gate and any early `track()` call is queued in the shim before Plausible's script executes.

## Transport

- The page loads `/js/script.js`; `_worker.js` serves Plausible's classic `https://plausible.io/js/script.outbound-links.js` (`PLAUSIBLE_SCRIPT_URL`, overridable by an env binding of the same name), cached with `cache-control: public, max-age=3600` (`SCRIPT_TTL_SECONDS = 3600`).
- The classic script posts to `new URL(<its own src>).origin + "/api/event"`, so events go to `POST /api/event` on our own origin. `_worker.js` (`forwardEvent`) forwards them to `https://plausible.io/api/event` (`PLAUSIBLE_EVENT_URL`, overridable by an env binding of the same name), deletes the `cookie` header, and sets `x-forwarded-for` from `cf-connecting-ip`. Non-POST to `/api/event` gets 405.
- `_routes.json` includes only `/js/script.js` and `/api/event`; every other path is a static asset.
- Each request the Plausible script sends is a JSON body (`Content-Type: text/plain`, `keepalive: true`) with `n` (event name), `v: 36`, `u: location.href`, `d: "fredvested.com"`, `r: document.referrer || null`, and `p` (the props object). This was read from the script as served by plausible.io on 2026-09-24.
- Plausible's own automatic events: one `pageview` per page load (sent again on a back-forward-cache restore via `pageshow` with `persisted`, and on `history.pushState` / `popstate` when the pathname changes; `history.replaceState` is not hooked), plus `engagement` events (scroll depth `sd`, engaged milliseconds `e`) when the page is hidden or loses focus, provided at least 3 s were engaged or the scroll depth grew since the last one. An engagement event is never sent when the pageview was ignored. Pageviews and custom events are billed; engagement events are not.

## The event vocabulary

Eight event names exist. Seven fire on `index.html` / `about.html`; `Waitlist Confirmed` fires on `confirmed.html`.

| Event | Page | Trigger | Props |
|---|---|---|---|
| `Calc Engaged` | index | first calculator input the visitor touches | `first_field` |
| `Explainer Opened` | index | `<details id="calc-method">` opened | none |
| `Freedom Date Revealed` | index | first click on `#reveal-btn` | `return_scenario`, `age_band`, `invest_band`, `target_income_band`, `freedom_age_band`, `sec_to_reveal_band`, `input_edits_band` |
| `Recalculated` | index | second click on `#reveal-btn` | `edits_after_reveal_band` |
| `Email Focused` | index, about | `focus` on `#capture-email` | none |
| `Waitlist Submitted` | index, about | 2xx from `POST /api/waitlist` with `status` other than `already_joined` (under double opt-in that includes an unconfirmed address entered again, see the duplicate note) | `source_page`, `revealed`, `utm_source`, `utm_medium`, `utm_campaign`, plus `return_scenario` and `freedom_age_band` only when `revealed` is `yes` |
| `Waitlist Failed` | index, about | any failed submit, or a 2xx answered `already_joined` (under double opt-in a confirmed duplicate; with the flag off any repeat) | `reason` |
| `Waitlist Confirmed` | confirmed | page load with `?status=confirmed&hours=<band>` (the backend redirect; `tier` rides along and is not a prop) | `hours_to_confirm_band` |

### Calc Engaged

`analytics.js:183-191` (`recordInput`) and `:97`. The page calls `Analytics.recordInput(field)` from every calculator input listener: `input` on `#age-range` / `#age-number` (`age`), `#invest-range` / `#invest-number` (`monthly_invest`), `#retire-range` / `#retire-number` (`target_income`) at index.html:493 and :500; a click on a `#return-seg .seg-btn` that changes the scenario (index.html:466) or an arrow key on `#return-seg` (index.html:477) for `return_scenario`. The first call fires the event with `first_field` set to that field; any other value is dropped (`FIELDS` set). The same call starts the time-to-reveal clock and counts edits with a 400 ms per-field debounce (`EDIT_DEBOUNCE_MS`), so one slider drag is one edit.

### Explainer Opened

index.html:655-657: `toggle` on `#calc-method` when `e.target.open` is true. No props.

### Freedom Date Revealed

index.html:608-622: on the `#reveal-btn` click, after `render()` has run, the page passes `age`, `invest`, `retire`, `return_scenario`, `freedom_age` (null when the target is unreachable). Builder at `analytics.js:101-116`. Dropped if `return_scenario` is not one of `conservative`, `moderate`, `optimistic`.

- `sec_to_reveal_band`: seconds from the first `recordInput` call to this click, rounded; 0 when no input was touched.
- `input_edits_band`: debounced edit count at the click. An edit made less than 400 ms before the click is not yet counted.

### Recalculated

Same click handler; fires when the reveal zone was already open (`firstReveal` false). Builder at `analytics.js:118-125`: dropped unless a `Freedom Date Revealed` already fired in this page load. `edits_after_reveal_band` bands `editCount` minus the count captured at the first reveal. Live re-rendering from slider changes after the reveal (`liveUpdate`) does not fire anything; only a second click of the button does, and only once.

### Email Focused

index.html:658 and about.html:981: `focus` on `#capture-email`. When the browser holds a saved `waitlist_status`, the form is hidden and this cannot fire, with two ways back to the form. A pending browser (`waitlist_status` = `pending_confirmation` plus a `localStorage["waitlist_pending"]` record, JSON `{email, at}`, less than 7 days old) shows the pending block instead of the form; its "Wrong address? Use a different one" control (`#pending-reset`, index.html:714-717, about.html:1052-1055) calls `restoreForm()`, which removes `waitlist_pending` and `waitlist_status`, resets the form and the Turnstile widget and shows the form again, after which the focus fires as on a first visit. And a `pending_confirmation` status whose `waitlist_pending` record is missing or older than 7 days reads as absent on load (`getPendingEmail()` clears it, `waitlist.js:68-83`; index.html:744-747, about.html:1081-1084 then call `clearStatus()`), so a browser whose confirmation link was clicked elsewhere gets the form, and this event, back on its next visit.

### Waitlist Submitted

index.html:847 and about.html:1182, after `submitWaitlist` resolves with a 2xx and `data.status !== "already_joined"`. Under double opt-in that condition also holds for an address that is already on the list but unconfirmed: `WaitlistController.joinWaitlist` answers such a re-submit exactly like a fresh signup (`status` `WAITLISTNORMAL`, `requiresConfirmation` true, no `realStatus`), so the page fires `Waitlist Submitted` again and cannot tell the two apart (see the duplicate note under `Waitlist Failed`). Builder at `analytics.js:131-145`:

| Prop | Value |
|---|---|
| `source_page` | `about` when the page passes `about`, otherwise `home` |
| `revealed` | `yes` if a `Freedom Date Revealed` fired in this page load, else `no` |
| `utm_source` | last-touch `utm_source` from `sessionStorage["fred_attr_last"]`, else `direct` |
| `utm_medium` | same, else `none` |
| `utm_campaign` | same, else `none` |
| `return_scenario` | only when `revealed` is `yes`: scenario at the last reveal-button click |
| `freedom_age_band` | only when `revealed` is `yes`: banded freedom age at the last reveal-button click |

`about.html` has no calculator, so its submits always carry `revealed: "no"` and no calculator props. On `index.html` a submit before any reveal also carries `revealed: "no"`. `utm_content` and `utm_term` never go to Plausible (`attribution.js:123-133`).

### Waitlist Failed

Builder at `analytics.js:147`: `reason` must be in `FAILED_REASONS`, otherwise it is sent as `server_error`. Up to three per page load (`FAILED_LIMIT = 3`). Sources:

| `reason` | Where it comes from |
|---|---|
| `empty_email` | client, before any request (index.html:809, about.html:1157) |
| `invalid_email` | client regex (index.html:814, about.html:1162); also backend `code` on a 400 from Bean Validation of `email` (`ApiErrorHandler.invalidRequest`) |
| `captcha_failed` | client when the Turnstile token is still null (index.html:820, about.html:1168); also backend `code` on a 400 when `TurnstileService.verifyToken` fails (`WaitlistController.joinWaitlist`) |
| `duplicate` | 2xx response whose `status` is `already_joined` (index.html:846, about.html:1181): under double opt-in only a confirmed address gets that answer; with the flag off, any repeat does |
| `rate_limited` | backend `code` on 429 (`WaitlistController`), or the 429 status fallback |
| `geo_blocked` | backend `code` on 403, or the 403 status fallback |
| `server_error` | backend `code` on a malformed body (400) or an unhandled exception (500) (`ApiErrorHandler`), any status with no or unknown `code` (`reasonFor` fallback), or any unknown reason passed to `track()` |
| `timeout` | `AbortError` after `SUBMIT_TIMEOUT_MS = 15000` (`waitlist.js:129`, `:172`) |
| `network_error` | fetch rejected for any other reason (`waitlist.js:172`) |

The catch path (index.html:871, about.html:1203) uses `err.reason` from `WaitlistError`; `reasonFor(status, code)` prefers the backend's `code`, then `REASON_BY_STATUS` (`429`, `403`, `409`), then `server_error`. The backend never answers a duplicate with 409 (it returns 200 with `already_joined`, `WaitlistController.joinWaitlist`), so in practice `duplicate` only arrives through the 2xx path.

Duplicate note: which re-submits count as `duplicate` depends on the row's confirmation state (`WaitlistController.joinWaitlist`, step 2). With double opt-in on, an address whose row is confirmed gets `status` `already_joined` with `realStatus`, and the page fires `Waitlist Failed` with `reason: "duplicate"`; the visitor sees the success state ("You're already on the list."). An address that is on the list but unconfirmed is answered exactly like a fresh signup (`status` `WAITLISTNORMAL`, `requiresConfirmation` true, no `realStatus`, never `already_joined`), and the backend calls `SignupService.requestResend` when `AddressRateLimiter.allow(email)` permits (1 per 10 minutes and 3 per day per address, the same limiter as `/resend-confirmation`); over the limit the response is identical and nothing is queued (`requestResend` itself also queues nothing for a suppressed address). The page cannot tell that response from a new signup, so an unconfirmed re-submit fires `Waitlist Submitted`, never `duplicate`, and `Waitlist Submitted` counts such repeats as signups; `v_waitlist_funnel` counts rows and is the ground truth for distinct addresses. With double opt-in off, every repeat is `already_joined` as before. Because a browser that already holds `waitlist_status` never shows the form, a duplicate submit means the same address came back from a different browser, after clearing storage, after the pending block's "Use a different one" control (`#pending-reset`, which removes `waitlist_pending` and `waitlist_status`), after a `waitlist_pending` record aged past 7 days brought the form back, or from a browser where `localStorage` is blocked (nothing was saved, so the form shows again on the next load; within the page load the address is kept in `pendingEmailMemory`, so the pending block's resend button still works, and it only brings the form back when neither storage nor that variable holds the address, index.html:718-727, about.html:1056-1065). Read `duplicate` as a returning-interest signal, not as an error.

### Waitlist Confirmed

confirmed.html:146-179. The POST step of `/api/waitlist/confirm` (`WaitlistConfirmationController.confirm`) answers 302 to `<landing.url>/confirmed?status=confirmed&hours=<band>&tier=founder` or `&tier=normal` on success (`status=expired` / `status=invalid` otherwise); the band is URL-encoded, and `LandingUrls.page` builds the absolute URL from the `landing.url` property (default `https://fredvested.com`; the local `same-host` value appends `.html`). `SignupService.confirm` returns `SignupService.Confirmation(outcome, hoursBand, status)`, where `status` is the row's tier after the founder slot is decided at confirmation, and the controller's `tier` helper maps `WAITLISTFOUNDER` to `founder` and `WAITLISTNORMAL` to `normal`; a row that was already `INVITED`, `CLAIMED` or `DECLINED` keeps its status and the redirect carries no `tier` parameter. `tier` is never sent to Plausible. The human unsubscribe POST (the button on the unsubscribe page, which has no script and never auto-submits) redirects to the same page with `status=unsubscribed`, or `status=invalid` for an unknown token, and neither fires anything; the RFC 8058 one-click POST a mail client sends to the `List-Unsubscribe` URL (body `List-Unsubscribe=One-Click`) is answered 200 `text/plain` with no redirect, so it never reaches this page at all. The page fires `Waitlist Confirmed` with `hours_to_confirm_band` only when `status` is `confirmed` and `hours` is exactly one of `<1`, `1-6`, `6-24`, `24-72`, `72+` (checked in confirmed.html:168 and again in `analytics.js:151-153` against `CONFIRM_BANDS`).

What the page stores: only when the redirect carries `hours` (confirmed.html:158, `hours !== null`), it writes `localStorage["waitlist_status"]` = `WAITLISTFOUNDER` for `tier=founder`, `WAITLISTNORMAL` for `tier=normal`, or `confirmed` when `tier` is absent, and removes `localStorage["waitlist_pending"]` (confirmed.html:161-165), so index.html and about.html stop showing the pending block and show the decided status. A shared or retyped `/confirmed?status=confirmed` URL has no `hours`, so it shows the confirmed copy but changes nothing in the browser and fires nothing.

Band computation, `SignupService.hoursBand(from, to)` (:198-206): `from` is `confirmation_sent_at`, falling back to `created_at` (`confirm`, :137); `to` is `LocalDateTime.now(EASTERN)`. `confirmation_sent_at` is written by `WaitlistRepository.markConfirmationSent`, called from `EmailOutboxPublisher.publish` (:175) with `LocalDateTime.now(EASTERN)` taken right after Resend accepted the confirmation email, and every resend overwrites it, so the band measures from the most recent confirmation send, not from the signup. Hours are `Duration.toMinutes() / 60.0`:

| Hours | Band |
|---|---|
| `from` null | `72+` |
| < 1 | `<1` |
| < 6 | `1-6` |
| < 24 | `6-24` |
| < 72 | `24-72` |
| otherwise | `72+` |

Both timestamps are app-written Eastern wall-clock values; a row whose `confirmation_sent_at` was edited with SQL `NOW()` would band against a different clock.

Stripping the band from the URL: right after `track()`, confirmed.html:174-178 deletes both `hours` and `tier` from the query and calls `history.replaceState(null, "", location.pathname + "?" + params.toString())`, leaving `?status=confirmed`. This happens before Plausible's deferred script runs, so neither the automatic pageview nor the queued custom event carries the band or the tier in `u` (`location.href` is read at send time), and Plausible's script does not hook `history.replaceState`, so the strip itself sends no second pageview. A reload or an "open in browser" from a mail app therefore can neither fire the event again nor re-store `waitlist_status`. The in-memory `ONCE` guard covers only the current page load; the URL strip is the cross-reload guard. Re-opening the original redirect URL (with `hours` still in it) would fire it again and store the status again.

## Bands

All numbers are banded inside `analytics.js` (`band()`, :36-48) before they leave the page. A value that is not a finite number bands to `null`.

| Band | Edges | Labels |
|---|---|---|
| `age_band` | 25, 30, 35, 40, 45, 50, 55 | `18-24`, `25-29`, `30-34`, `35-39`, `40-44`, `45-49`, `50-54`, `55+` |
| `invest_band` | 100, 250, 500, 1000, 2000, 5000 | `0-99`, `100-249`, `250-499`, `500-999`, `1000-1999`, `2000-4999`, `5000+` |
| `target_income_band` | 2500, 5000, 7500, 10000 | `<2500`, `2500-4999`, `5000-7499`, `7500-9999`, `10000+` |
| `freedom_age_band` | 45, 50, 55, 60, 65 (null input: `unreachable`) | `unreachable`, `<45`, `45-49`, `50-54`, `55-59`, `60-64`, `65+` |
| `sec_to_reveal_band` | 11, 31, 61, 121 | `0-10`, `11-30`, `31-60`, `61-120`, `120+` |
| `input_edits_band`, `edits_after_reveal_band` | 1, 4, 9, 16 | `0`, `1-3`, `4-8`, `9-15`, `16+` |
| `hours_to_confirm_band` | backend, see above | `<1`, `1-6`, `6-24`, `24-72`, `72+` |

Fixed vocabularies: `first_field` in `age`, `monthly_invest`, `target_income`, `return_scenario`; `return_scenario` in `conservative`, `moderate`, `optimistic`; `source_page` in `home`, `about`; `revealed` in `yes`, `no`; `reason` as listed above. Slider ranges (index.html:181, :194, :207) are age 18-60, invest 0-10000, retire 1000-30000, so `55+` means 55-60 and `10000+` means 10000-30000.

## Guards

`track()` at `analytics.js:158-177`, in order:

1. Unknown event name: dropped.
2. Name in `ONCE` (`Calc Engaged`, `Explainer Opened`, `Freedom Date Revealed`, `Recalculated`, `Email Focused`, `Waitlist Submitted`, `Waitlist Confirmed`) and already fired: dropped.
3. `Waitlist Failed` and `failedCount >= 3`: dropped.
4. Builder returns `false` (bad context): dropped, and the once-guard is not consumed.
5. Once-guard marked, failure counter incremented.
6. Not enabled (hostname gate): logged as `(gated)` off production, not sent.
7. `window.plausible` not a function: not sent.
8. `window.plausible(name, { props })`.

All state (`fired`, `failedCount`, edit counters, calculator context) is in memory. "Session" means one page load: a reload starts over, `index.html` and `about.html` are separate page loads, and nothing is written to storage for guarding, so a returning visitor counts again. `track()` and `recordInput()` catch every exception; analytics cannot break the calculator or block a signup.

## Per-visitor budget

Plausible bills pageviews plus custom events. What one page load can send:

| Page | Custom events, happy path | Custom events, ceiling | Billable ceiling (with pageview) |
|---|---|---|---|
| index.html | 6 (`Calc Engaged`, `Explainer Opened`, `Freedom Date Revealed`, `Recalculated`, `Email Focused`, `Waitlist Submitted`) | 9 (the 6 plus up to 3 `Waitlist Failed`) | 10 |
| about.html | 2 (`Email Focused`, `Waitlist Submitted`) | 5 | 6 |
| confirmed.html | 1 (`Waitlist Confirmed`) | 1 | 2 |
| privacy.html, terms.html | 0 | 0 | 1 (plus outbound clicks on privacy, see below) |

The "7-per-visitor budget" named in `analytics.js:10-15` is the happy path on `index.html`: 6 custom events plus 1 pageview. `Waitlist Confirmed` is a separate page load with its own pageview and sits outside it. The failure cap can push a single `index.html` load to 9 custom events.

## What is deliberately not sent

- No email address, no name, nothing typed into the form. The signup payload goes to `/api/waitlist` only.
- No visitor id. `attribution.js:12-13`: a durable first-party id would be a cookie in all but name. The Plausible script sets no cookie, and `_worker.js` strips the `cookie` header before forwarding.
- No raw numbers: age, investment, income, freedom age, seconds and edit counts all leave as bands; the freedom date (month/year) and portfolio target are never sent.
- No `utm_content`, `utm_term`, first-touch UTM, `first_touch_at`, `referrer_host`, `landing_path` or `device_type` as props; those go to the waitlist API only (`attribution.js:get()` versus `forPlausible()`).
- No error message text; only the fixed `reason` vocabulary.

What Plausible does receive with every event, from its own script: `u` (the full page URL including any query string, so UTM parameters on a landing URL do reach Plausible that way), `r` (`document.referrer`), the User-Agent of the request, and, via the worker's `x-forwarded-for`, the visitor's IP for geolocation. What Plausible does with those is outside this repo.

## Hostname gate and escape hatch

`analytics.js:75-91` (`init`, run at load):

- `enabled = isProduction || forced`, where `isProduction` is `location.hostname` in `PRODUCTION_HOSTS = ['fredvested.com', 'www.fredvested.com']` and `forced` is `localStorage["fred_analytics_force"] === '1'`.
- Off production, when not forced, it writes `localStorage["plausible_ignore"] = "true"`; Plausible's script checks that key on every event (pageview, custom, outbound) and drops them, so preview, ngrok and local traffic never lands in the production dashboard even though every page carries `data-domain="fredvested.com"`. When forced, it removes `plausible_ignore`. On production it never touches `plausible_ignore`, since a site owner may have set it to exclude their own visits.
- `debug = !isProduction`: off production, `track()` logs `[analytics] ...` lines for an unknown event name, for a gated event (`(gated)` plus the event and the props that would have been sent) and for a sent event (when forced). A repeat of a once-only event and a builder rejection log nothing.
- Escape hatch for verifying on a preview: `localStorage.fred_analytics_force = '1'` in that browser. Events then send from any host and land in the production `fredvested.com` site; remove the key afterwards. The key is per origin, so it must be set on the host being tested.
- Plausible's script independently ignores `localhost`, `127.x`, `[::1]` and `file:` pages unconditionally, and automation (`navigator.webdriver`, `window.Cypress`, `window._phantom`, `window.__nightmare`) unless `window.__plausible` is set; the force flag overrides neither.

Environments, for reference: previews at `*.fredvested-landing-page.pages.dev` are DEV for the API and Turnstile (`waitlist.js:16-23`) and are gated off for analytics; only `fredvested.com` / `www.fredvested.com` send.

## Out-of-budget events

### `Outbound Link: Click`

Sent by Plausible's `script.outbound-links.js` extension, not by `analytics.js`. It does not pass through `track()`, so the `ONCE` guard, the band code and the `FredAnalytics` enable flag do not apply; off production it is still suppressed by the `plausible_ignore` flag `init()` sets, and with the force flag it sends like everything else. Read from the script as served on 2026-09-24:

- Listens for `click` and `auxclick` (middle button only) on `document`, walks up from the target to the nearest `<a href>`, and fires when the anchor's `host` is non-empty and differs from `location.host`.
- Event name `Outbound Link: Click`, props `{ url: <the anchor's absolute href> }`. Every click sends; there is no per-session guard, so repeated clicks send repeatedly.
- `mailto:` and `tel:` anchors have an empty `host` and do not count. Same-site links (`/about`, `/privacy`, `#join`) do not count.

Outbound anchors present on the pages today:

| Page | Outbound anchors |
|---|---|
| index.html | none |
| about.html | none |
| privacy.html | 3: `https://plausible.io/privacy` (:151), `https://www.cloudflare.com/privacypolicy/` (:166), `https://railway.com/legal/privacy` (:173), all `target="_blank"` |
| terms.html | none |
| confirmed.html | none |

The Turnstile script (`challenges.cloudflare.com`) is a `<script>`, not an anchor. The pages carry twelve `mailto:help@fredvested.com` anchors, none of which count. No page links to the other production host (`fredvested.com` versus `www.fredvested.com`), so no internal link is misread as outbound.

Volume estimate (an estimate, not a measurement): the event can only fire on `/privacy`, from three vendor links. If 2% of visitors open the privacy page and 10% of those click a vendor link, that is 0.2% of visitors, about 2 events per 1,000 visitors or roughly 20 per month at 10,000 visitors. Even an implausible upper bound (every privacy visitor clicks all three links) is 6% of visitors, under 0.1 event per visitor, against the 7 to 10 billable events a signup-flow visitor can generate on `index.html`.

Why it is present: commit `74b4a61` (2026-09-18) replaced the direct `<script async src="https://plausible.io/js/pa-<id>.js">` tag (Plausible's personalized snippet, which hardcodes `https://plausible.io/api/event` and cannot be proxied) with the proxied classic build and chose `script.outbound-links.js` as that build; the proxy then lived in `frontend/functions/` and moved to `frontend/_worker.js` in commit `e3b22ab` (2026-09-22). The commit records why the classic build was needed, not why the outbound-links variant was picked over plain `script.js`; that choice is Andrew's. Keeping it costs almost nothing given three anchors on one low-traffic page. Dropping it is a one-line change: point `PLAUSIBLE_SCRIPT_URL` (constant in `_worker.js:20`, or the env binding of the same name) at `https://plausible.io/js/script.js`; the page code does not change because the event API is identical (both builds are `v: 36` and expose the same `window.plausible(name, { props })`, read from plausible.io on 2026-09-24).

## Known quirks

- `freedom_age_band` and `return_scenario` on `Waitlist Submitted` reflect the calculator state at the last click of `#reveal-btn` (first or second), because `calc` is only updated by the `Freedom Date Revealed` and `Recalculated` builders and `Recalculated` is once-only. Slider edits after that click re-render the page and update the API payload (`lastFreedomAge`) but not these props.
- A `Freedom Date Revealed` cannot send from a preview host without the force flag, but the once-guard is still consumed: the first click logs `(gated) Freedom Date Revealed ...`, the second click logs `(gated) Recalculated ...` (the page never sends `Freedom Date Revealed` twice), and a further `track('Freedom Date Revealed')` from the console logs nothing, which is the same guard shape as production.
- Known gap (email funnel): a mail scanner that executes JavaScript would submit the two-step confirmation form, confirm the row in the database, and, if it also renders the redirect target and runs its scripts, fire `Waitlist Confirmed` with band `<1` (scanners run at delivery, minutes after `confirmation_sent_at`). The funnel view counts database rows and Plausible counts page events, so the two can disagree by such cases. The unsubscribe page is not exposed the same way: `WaitlistConfirmationController.unsubscribePage` renders a visible button and no `<script>` at all, because a suppression is never cleared and a sandbox that runs JavaScript must not be able to unsubscribe a recipient on delivery; only the confirm page keeps the auto-POST, since a consumed confirmation token is recoverable via resend.

## Summary table

| Event | Page(s) | Props | Guard | Source file:line |
|---|---|---|---|---|
| `Calc Engaged` | index | `first_field` | once per page load; dropped if field unknown | `analytics.js:97`, `:183-191`; index.html:466, :477, :493, :500 |
| `Explainer Opened` | index | none | once per page load | `analytics.js:99`; index.html:655-657 |
| `Freedom Date Revealed` | index | `return_scenario`, `age_band`, `invest_band`, `target_income_band`, `freedom_age_band`, `sec_to_reveal_band`, `input_edits_band` | once per page load; dropped if scenario unknown | `analytics.js:101-116`; index.html:608-622 |
| `Recalculated` | index | `edits_after_reveal_band` | once per page load; only after a reveal | `analytics.js:118-125`; index.html:619 |
| `Email Focused` | index, about | none | once per page load | `analytics.js:127`; index.html:658; about.html:981 |
| `Waitlist Submitted` | index, about | `source_page`, `revealed`, `utm_source`, `utm_medium`, `utm_campaign` (+ `return_scenario`, `freedom_age_band` when revealed) | once per page load; only on 2xx with status other than `already_joined` (an unconfirmed re-submit counts) | `analytics.js:131-145`; index.html:847; about.html:1182 |
| `Waitlist Failed` | index, about | `reason` | max 3 per page load; unknown reason becomes `server_error`; `duplicate` only for a confirmed address under double opt-in | `analytics.js:147`, `:163`; index.html:809, :814, :820, :846, :871; about.html:1157, :1162, :1168, :1181, :1203; `waitlist.js:129-155` |
| `Waitlist Confirmed` | confirmed | `hours_to_confirm_band` | once per page load; `hours` and `tier` removed from the URL after firing; dropped unless band is exact | `analytics.js:151-153`; confirmed.html:156-179; `SignupService.java:137-138`, `:198-206` |
| `Outbound Link: Click` (out of budget) | privacy (only page with outbound anchors) | `url` | none; every click sends | Plausible `script.outbound-links.js` via `_worker.js:20`; privacy.html:151, :166, :173 |

## Open questions

- The reason for choosing `script.outbound-links.js` over plain `script.js` in commit `74b4a61` is not recorded; whether to keep the outbound extension at all is Andrew's call.
- `analytics.js:15` describes a "7-per-visitor budget" while the code allows up to 9 custom events on one `index.html` load through the `Waitlist Failed` cap. Commit `7375950` spells out the arithmetic (6 + pageview = 7 on the happy path, 9 with failures). Whether the header comment should say so explicitly is a wording question.
- `waitlist.js:132` maps HTTP 409 to `duplicate`, but no backend path returns 409 for a duplicate today. Dead mapping or reserved for a future change: not stated in the code.
- Whether Plausible strips the `?status=confirmed` query string from the `confirmed` pageview in its dashboard is Plausible-side behaviour, not verified here.
