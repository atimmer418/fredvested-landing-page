# Plausible dashboard setup

Click-by-click configuration of the Plausible Business plan dashboard for fredvested.com, derived from the code in the working tree on 2026-09-24.

Sources of truth, in order:

- `frontend/assets/analytics.js`: every event name, property key, band boundary and once-per-load guard. Nothing outside this file calls `window.plausible()`.
- `frontend/assets/attribution.js`: the UTM values that end up on `Waitlist Submitted`.
- `frontend/_worker.js` and `frontend/_routes.json`: the first-party proxy.
- `frontend/index.html`, `frontend/about.html`, `frontend/confirmed.html`, `frontend/privacy.html`, `frontend/terms.html`: where each event is wired.
- `backend/src/main/java/com/fredvested/web/service/SignupService.java` and `backend/src/main/java/com/fredvested/web/controller/WaitlistConfirmationController.java`: the `hours` band that `Waitlist Confirmed` carries.

Where this document names a Plausible screen or control it does so by purpose. Plausible's labels change; the event names and property keys below do not, because they are literals in the code.

## 1. What the pages send

Every page that carries analytics loads the same tag in `<head>`:

```html
<script defer data-domain="fredvested.com" src="/js/script.js"></script>
```

followed by Plausible's queue shim, verbatim on every page:

```html
<script>
  window.plausible=window.plausible||function(){(plausible.q=plausible.q||[]).push(arguments)},plausible.init=plausible.init||function(i){plausible.o=i||{}};
  plausible.init()
</script>
```

so that calls made before the script arrives are queued in `plausible.q`, not lost; the upstream script replays that queue when it executes.

| Page | Loads the Plausible tag | Loads `attribution.js` + `analytics.js` | Custom events it can fire |
|---|---|---|---|
| `index.html` (served at `/`) | yes | yes | Calc Engaged, Explainer Opened, Freedom Date Revealed, Recalculated, Email Focused, Waitlist Submitted, Waitlist Failed |
| `about.html` (`/about`) | yes | yes | Email Focused, Waitlist Submitted, Waitlist Failed |
| `confirmed.html` (`/confirmed`) | yes | yes | Waitlist Confirmed |
| `privacy.html` (`/privacy`) | yes | yes (UTM capture and the hostname gate; no `track()` calls) | none |
| `terms.html` (`/terms`) | yes | yes (UTM capture and the hostname gate; no `track()` calls) | none |
| `calculator.html` | no | no | none (redirect stub: `location.replace('/')`, or `index.html` on `localhost`, `127.0.0.1`, ngrok and `file:` hosts, plus a meta refresh to `/`) |

The `data-domain` is `fredvested.com` on every page. That attribute, not the hostname the page is served from, is the site identity in Plausible. A preview deployment on `*.fredvested-landing-page.pages.dev` would therefore post into the production dashboard, which is why `analytics.js` gates sending by hostname (section 8).

The script the proxy serves is Plausible's classic `script.outbound-links.js` build. Besides the events in `analytics.js` it sends, on its own:

- a `pageview` on load, on `history.pushState` and on `popstate` (these two only when `location.pathname` changed since the last pageview), and on a `pageshow` restore from the back-forward cache (`event.persisted`);
- `engagement` events (scroll depth and engaged time), which Plausible does not bill;
- `Outbound Link: Click` with a `url` property, on a `click` or middle-button `auxclick` on an anchor whose `host` is non-empty and differs from `location.host` (verified from the upstream script fetched on 2026-09-24). `mailto:` links have an empty host and do not count.

## 2. The site in Plausible

1. Add the site with domain `fredvested.com`. It must match the `data-domain` attribute exactly. Do not add `www.fredvested.com` as a second site: both hostnames report into this one because the tag says `fredvested.com` on both.
2. Reporting timezone: the backend writes Eastern wall-clock timestamps (`LocalDateTime.now(EASTERN)` in `SignupService`), so setting the Plausible site to America/New_York keeps dashboard days aligned with database days. This is a recommendation, not something the code enforces.
3. Ignore the snippet Plausible offers during onboarding. The pages already carry the tag, and the proxied path `/js/script.js` replaces the `plausible.io` URL the snippet would contain. Do not swap in the newer personalised `pa-<id>.js` snippet: `_worker.js` documents that it hardcodes `https://plausible.io/api/event` as its endpoint, so events would bypass the proxy.
4. If Plausible's installation check does not detect the script, that is expected to be a limitation of the check against a proxied script, not a fault in the pages. Confirm with the network tab instead (section 9).

## 3. Goals to create

Create one custom event goal per event name. The names are case-sensitive literals from `analytics.js`; copy them exactly.

| Goal (custom event name) | Fires from | Guard | Properties carried |
|---|---|---|---|
| `Calc Engaged` | `index.html`, first slider drag, number edit or return-scenario change (`FredAnalytics.recordInput`) | once per page load | `first_field` |
| `Explainer Opened` | `index.html`, the `<details id="calc-method">` element opening | once per page load | none |
| `Freedom Date Revealed` | `index.html`, first click on `#reveal-btn` | once per page load | `return_scenario`, `age_band`, `invest_band`, `target_income_band`, `freedom_age_band`, `sec_to_reveal_band`, `input_edits_band` |
| `Recalculated` | `index.html`, a later click on `#reveal-btn` (the button stays in place after the reveal) | once per page load, and only after `Freedom Date Revealed` | `edits_after_reveal_band` |
| `Email Focused` | `index.html` and `about.html`, focus on `#capture-email` | once per page load | none |
| `Waitlist Submitted` | `index.html` and `about.html`, after a 2xx from `POST /api/waitlist` whose `status` is not `already_joined` | once per page load | `source_page`, `revealed`, `utm_source`, `utm_medium`, `utm_campaign`; plus `return_scenario` and `freedom_age_band` when `revealed` is `yes` |
| `Waitlist Failed` | `index.html` and `about.html`, client-side validation failure, missing Turnstile token, non-2xx, timeout, network error, or a 2xx with `status` `already_joined` | at most 3 per page load | `reason` |
| `Waitlist Confirmed` | `confirmed.html`, when the URL carries `status=confirmed` and an `hours` value that is one of the five bands | once per page load; `hours` is removed from the address bar with `history.replaceState` so a reload cannot fire it again | `hours_to_confirm_band` |

Notes:

- The task brief called the reveal step "Calculator Revealed". The code name is `Freedom Date Revealed`. Use the code name.
- A duplicate address is reported as `Waitlist Failed` with `reason` = `duplicate` even though the visitor sees the success state (`index.html` line 822, `about.html` line 1155). Read this as a returning-interest signal, not an error.
- Slider changes after the first reveal re-render the result live but do not fire an event; only a second click on the reveal button fires `Recalculated`.
- `Waitlist Submitted` carries `return_scenario` and `freedom_age_band` as they stood at the last reveal-button click that produced an event: the first click (`Freedom Date Revealed`) or, if there was one, the second click (`Recalculated`). Nothing later updates them: `track()` in `analytics.js` applies the once-per-load guard before it runs the event builder, so a third or later click never reaches the `Recalculated` builder, and slider changes after the reveal re-render the page without calling `track()` at all. A visitor who reveals, then moves a slider, then submits is reported with the values from the reveal, not the values on screen at submit.

Optional goals:

| Goal | Type | Why |
|---|---|---|
| `Outbound Link: Click` | custom event | Only makes visible what the script already sends and Plausible already bills (section 8). Property: `url`. |
| `Visit /` | pageview goal on path `/` | Needed as the first step of the funnels in section 5. |
| `Visit /about` | pageview goal on path `/about` | First step of the about-page funnel. |
| `Visit /confirmed` | pageview goal on path `/confirmed` | Every confirmation, expiry, invalid link and unsubscribe lands here; pairs with `Waitlist Confirmed` to show how many landings were real confirmations. |

## 4. Custom properties to enable

Plausible stores every property it receives, but the dashboard only breaks down the keys you enable in the site's custom-properties settings. Enable each key below by its exact name.

| Property key | On events | Values (exact strings from `analytics.js`) |
|---|---|---|
| `first_field` | Calc Engaged | `age`, `monthly_invest`, `target_income`, `return_scenario` |
| `return_scenario` | Freedom Date Revealed, Waitlist Submitted (when revealed) | `conservative`, `moderate`, `optimistic` |
| `age_band` | Freedom Date Revealed | `18-24`, `25-29`, `30-34`, `35-39`, `40-44`, `45-49`, `50-54`, `55+` |
| `invest_band` | Freedom Date Revealed | `0-99`, `100-249`, `250-499`, `500-999`, `1000-1999`, `2000-4999`, `5000+` (monthly investment, USD) |
| `target_income_band` | Freedom Date Revealed | `<2500`, `2500-4999`, `5000-7499`, `7500-9999`, `10000+` (monthly retirement income, USD) |
| `freedom_age_band` | Freedom Date Revealed, Waitlist Submitted (when revealed) | `<45`, `45-49`, `50-54`, `55-59`, `60-64`, `65+`, `unreachable` |
| `sec_to_reveal_band` | Freedom Date Revealed | `0-10`, `11-30`, `31-60`, `61-120`, `120+` (seconds from first input to reveal; `0-10` when the visitor never touched an input) |
| `input_edits_band` | Freedom Date Revealed | `0`, `1-3`, `4-8`, `9-15`, `16+` (debounced 400 ms per field, so one drag is one edit) |
| `edits_after_reveal_band` | Recalculated | `0`, `1-3`, `4-8`, `9-15`, `16+` |
| `source_page` | Waitlist Submitted | `home`, `about` |
| `revealed` | Waitlist Submitted | `yes`, `no` |
| `utm_source` | Waitlist Submitted | last-touch `utm_source` from the session, sanitised to `[a-z0-9-_.]`, max 100 chars; `direct` when absent |
| `utm_medium` | Waitlist Submitted | same sanitisation; `none` when absent |
| `utm_campaign` | Waitlist Submitted | same sanitisation; `none` when absent |
| `reason` | Waitlist Failed | `invalid_email`, `empty_email`, `duplicate`, `rate_limited`, `server_error`, `network_error`, `timeout`, `geo_blocked`, `captcha_failed` (anything else is mapped to `server_error`) |
| `hours_to_confirm_band` | Waitlist Confirmed | `<1`, `1-6`, `6-24`, `24-72`, `72+` |
| `url` (optional) | Outbound Link: Click | the clicked `href` |

Not sent to Plausible on purpose: `utm_content` and `utm_term` (high cardinality; they go only to the database with the signup), the `ref` query parameter (deliberately not captured at all, per the comment in `attribution.js`), raw ages, amounts or seconds (only bands leave the browser), and any email address or visitor identifier.

Two different UTM sources exist in the dashboard and should not be confused:

- Plausible's own UTM dimensions, which it reads from the URL of each pageview. They describe the landing pageview.
- The `utm_*` properties on `Waitlist Submitted`, which `attribution.js` reads from `sessionStorage` key `fred_attr_last` (last touch in this tab's session) and `analytics.js` attaches at submit time. They describe the signup, even when it happens several pages after the tagged landing.

## 5. Funnels to build

Funnel steps in Plausible are goals, so create the goals in section 3 first. Plausible allows between two and eight steps per funnel at the time of writing.

### 5.1 Calculator to confirmation (the main funnel)

| Step | Goal |
|---|---|
| 1 | `Visit /` |
| 2 | `Freedom Date Revealed` |
| 3 | `Email Focused` |
| 4 | `Waitlist Submitted` |
| 5 | `Waitlist Confirmed` |

Caveat on step 5: `Waitlist Confirmed` fires on `/confirmed` after the visitor clicks a link in their email, so it is a separate page load, usually a separate session, often a different day, and possibly a different device. Plausible has no cookie and no cross-day visitor identity, so the step-4-to-step-5 drop in this funnel understates real confirmation. Use the goal's own conversion count for the confirmation rate (confirmed count divided by submitted count over the same period), or the database view `v_waitlist_funnel` (defined in `V5__confirmed_source_and_legacy_backfill.sql`), which counts rows directly, grouped by `utm_source`, `utm_campaign` and `utm_content`. Read its columns carefully: `submitted` is every row, `confirmed` is every row with `confirmed_at` set (legacy rows backfilled by V5 included), `confirmed_double_opt_in` is the rows confirmed through the emailed link, and its `confirmed_rate` column is `confirmed_double_opt_in` divided by `delivered`, not by `submitted`. The submitted-to-confirmed rate is `confirmed_double_opt_in / submitted`, computed from those two columns. Keep step 5 in the funnel for same-session confirmations only.

### 5.2 Calculator engagement

| Step | Goal |
|---|---|
| 1 | `Visit /` |
| 2 | `Calc Engaged` |
| 3 | `Freedom Date Revealed` |
| 4 | `Recalculated` |

Shows how many visitors touch an input, how many reveal, and how many go back to try other numbers.

### 5.3 Source-page split

Two funnels with the same shape so the pages can be compared side by side:

| Funnel | Step 1 | Step 2 | Step 3 |
|---|---|---|---|
| Home capture | `Visit /` | `Email Focused` | `Waitlist Submitted` |
| About capture | `Visit /about` | `Email Focused` | `Waitlist Submitted` |

`Waitlist Submitted` is fired from both pages, so the funnel steps themselves do not distinguish the page. The split is created by the first step. For the exact page of each submission, filter the `Waitlist Submitted` goal by the `source_page` property instead (`home` or `about`).

## 6. Segments and filters worth saving

All of these are filters on the dashboard. Where Plausible offers saved segments, save the ones you return to.

| Question | Filter |
|---|---|
| Which campaign produced signups | Goal `Waitlist Submitted`, break down by property `utm_campaign`; then `utm_source` |
| Which page converts | Goal `Waitlist Submitted`, property `source_page` |
| Does revealing a date matter | Goal `Waitlist Submitted`, property `revealed` (`yes` vs `no`); `no` covers both about-page signups and home signups before revealing |
| Who signs up (freedom age) | Goal `Waitlist Submitted`, property `freedom_age_band` |
| Unreachable results | Goal `Freedom Date Revealed`, property `freedom_age_band` = `unreachable` |
| Which input people touch first | Goal `Calc Engaged`, property `first_field` |
| Why submissions fail | Goal `Waitlist Failed`, property `reason` |
| Returning interest | Goal `Waitlist Failed`, property `reason` = `duplicate` |
| Where the calculator stalls | Goal `Freedom Date Revealed`, properties `sec_to_reveal_band` and `input_edits_band` |
| How fast people confirm | Goal `Waitlist Confirmed`, property `hours_to_confirm_band` |
| Mobile vs desktop conversion | Any goal, with Plausible's device filter |
| Landing-page UTMs (pageviews, not signups) | Plausible's UTM source / medium / campaign dimensions in the Sources report |

The `utm_*` property values are lowercased and stripped to `[a-z0-9-_.]` by `attribution.js` (and again by `AttributionSanitizer` on the backend), so plan campaign names to survive that: `tiktok`, `ig-bio`, `reel-2026-09`.

## 7. What the proxy changes

Nothing needs to be configured in Plausible for the proxy. The pages request the script from the site's own origin, and the classic script computes its event endpoint at runtime as `data-api` attribute or `new URL(<its own src>).origin + "/api/event"` (per the comment in `_worker.js`, verified in the fetched script). Loaded from `https://fredvested.com/js/script.js`, it therefore posts to `https://fredvested.com/api/event`.

`frontend/_worker.js` (a Cloudflare Pages advanced-mode worker, picked up from the output directory `frontend/`) handles exactly two paths; `frontend/_routes.json` restricts the worker to them so every other path is served as a static asset without invoking it:

| Path | Method | Behaviour |
|---|---|---|
| `/js/script.js` | GET, HEAD (405 otherwise) | Fetches `https://plausible.io/js/script.outbound-links.js`, serves it as `content-type: application/javascript; charset=utf-8` with `cache-control: public, max-age=3600`, and stores it in the Cloudflare cache (`caches.default`) under the same header, so a Plausible script update reaches browsers within 2 hours at worst (`SCRIPT_TTL_SECONDS = 3600`; the cache and the browser each hold it up to that long). An upstream non-2xx returns an empty 502 and is not cached. |
| `/api/event` | POST (405 otherwise) | Forwards the request (method, headers, body streamed through unread) to `https://plausible.io/api/event`, deletes the `cookie` header, and, when the request carries a `cf-connecting-ip` header, sets `x-forwarded-for` to that value so Plausible geolocates the visitor rather than the Cloudflare edge. |

Both upstream URLs can be overridden by Pages environment bindings of the same names as the constants: `_worker.js` reads `env.PLAUSIBLE_SCRIPT_URL || PLAUSIBLE_SCRIPT_URL` and `env.PLAUSIBLE_EVENT_URL || PLAUSIBLE_EVENT_URL`. The repository sets neither (there is no `wrangler.toml` or `.dev.vars`, and no other file names them), so unless the Pages project's dashboard defines them the literals in the file apply. Whether the dashboard defines them is recorded under Open questions.

Consequences for the dashboard:

- Country, region and city come from the visitor's IP as forwarded, so geography reports work as they would with a direct install.
- No cookie ever reaches Plausible, in line with the privacy page's claim that analytics are cookie-free.
- The only third-party analytics origin in the browser is none: every analytics request goes to `fredvested.com`. This matters because the privacy policy names Plausible, Resend, Cloudflare and Railway as the only vendors.
- Ad blockers that block by the `plausible.io` hostname do not see this traffic; ones that block by path pattern may still catch `/api/event`.

## 8. Billing notes

Plausible bills pageviews plus custom events. Its own `engagement` events are excluded. The gate in `analytics.js` means only `fredvested.com` and `www.fredvested.com` send anything at all; on any other host it sets `localStorage.plausible_ignore = 'true'` before the Plausible script runs, which switches off the automatic pageview and all events from that browser, so preview and local traffic costs nothing. On production it never touches that flag, so an owner who set `plausible_ignore` to exclude their own visits keeps that exclusion.

Ceiling per page load, from the guards in `analytics.js`:

| Page | Pageviews | Custom events, maximum |
|---|---|---|
| `/` | 1 | 6 once-only events (`Calc Engaged`, `Explainer Opened`, `Freedom Date Revealed`, `Recalculated`, `Email Focused`, `Waitlist Submitted`) + up to 3 `Waitlist Failed` = 9 |
| `/about` | 1 | `Email Focused`, `Waitlist Submitted`, up to 3 `Waitlist Failed` = 5 |
| `/confirmed` | 1 | `Waitlist Confirmed` = 1 |
| `/privacy`, `/terms` | 1 each | 0 |

The comment at the top of `analytics.js` calls the landing-page allowance the "7-per-visitor budget": seven event names on a landing-page load, with `Waitlist Confirmed` outside it because it is a separate page load. The arithmetic ceiling is 9 because `Waitlist Failed` may fire three times; a typical converting visitor sends 4 to 6 (engage, reveal, focus, submit, perhaps explainer and recalculate). `Waitlist Submitted` and `Waitlist Failed` are mutually exclusive per attempt, and every successful path ends with exactly one `Waitlist Submitted`.

`Outbound Link: Click` is outside that budget. It is sent by the script extension on every qualifying click whether or not a goal exists in Plausible, and it is a billable custom event either way; adding the goal only makes it visible. Volume estimate from the anchors in the working tree:

- The only outbound anchors on pages that load the script are the three vendor links on `privacy.html`: `https://plausible.io/privacy`, `https://www.cloudflare.com/privacypolicy/`, `https://railway.com/legal/privacy`. `index.html`, `about.html`, `confirmed.html` and `terms.html` contain no outbound anchors; their `mailto:help@fredvested.com` links have no host and are not counted by the script.
- So an outbound click requires a visit to `/privacy` and then a click on one of three legal links. Privacy-page visits are typically a low single-digit percentage of visitors, and clicks on vendor policy links a small fraction of those. Expect fewer than one `Outbound Link: Click` per thousand visitors: tens of events across a campaign that brings tens of thousands of visitors. Against a landing-page ceiling of 9 events per visitor this is noise. If a future page adds outbound links (a social profile, an app store), redo this count.

Other things that add pageviews: `/confirmed` is one extra pageview per confirmation, expiry, invalid link or unsubscribe landing; the `history.replaceState` call that strips `hours` does not trigger another pageview (the script hooks `pushState` and `popstate` only). Visitors who open a page from the back-forward cache send a pageview on `pageshow`.

## 9. Verify it works

### 9.1 In the browser, on production

1. Open `https://fredvested.com/` in a browser where `localStorage.plausible_ignore` is not `'true'` for that origin (check in the console; `analytics.js` never changes it on production, so if you once opted yourself out you will see nothing).
2. Network tab: `GET /js/script.js` returns 200 with `content-type: application/javascript; charset=utf-8` and `cache-control: public, max-age=3600`. A `text/html` response here means the worker was not deployed and the request fell through to the SPA fallback (the failure `_worker.js` describes).
3. Network tab: a `POST /api/event` fires on load with a JSON body whose `n` is `pageview` and `d` is `fredvested.com`, and returns 2xx.
4. Drag a slider: one `POST /api/event` with `n` = `Calc Engaged` and `p.first_field` set. Click the reveal button: `n` = `Freedom Date Revealed` with all seven properties (`return_scenario` and the six `*_band` keys). Focus the email field: `Email Focused`.
5. There is no console output on production (`debug` is true only off-production).

### 9.2 In the browser, on a preview with the force flag

Use this when you want to test a branch before it reaches production. The events still land in the production dashboard, so keep the test short and remove the flag afterwards.

1. Open the preview (`https://develop.fredvested-landing-page.pages.dev/` or a per-deployment hash host).
2. In the console: `localStorage.fred_analytics_force = '1'`, then reload. `analytics.js` removes `plausible_ignore` and logs `[analytics] enabled`. Without the flag it logs `[analytics] disabled (not production; set localStorage.fred_analytics_force='1' to force)`.
3. Each event now logs `[analytics] <name> {props}` and posts to `/api/event` on the preview host, whose worker forwards it to Plausible under `data-domain` `fredvested.com`. A gated event (flag not set) logs `[analytics] (gated) <name> {props}` and sends nothing.
4. The flag is per origin. A confirmation on the dev backend redirects to `https://develop.fredvested-landing-page.pages.dev/confirmed?status=confirmed&hours=<band>` (`landing.url=${LANDING_URL:https://develop.fredvested-landing-page.pages.dev}` in `application-dev.properties`; a `LANDING_URL` env var on the Railway dev service would replace it), so the flag must be set on that exact origin for `Waitlist Confirmed` to send. The band is URL-encoded in the redirect (`%3C1` for `<1`, `72%2B` for `72+`); `confirmed.html` reads it through `URLSearchParams`, which decodes it.
5. Afterwards: `localStorage.removeItem('fred_analytics_force')` and reload; `analytics.js` re-sets `plausible_ignore` on the next load.
6. This does not work on `localhost`, `127.0.0.1` or `file:`: the upstream script drops everything on those hosts regardless of the flag (verified in the fetched script). Use a Pages preview or an ngrok tunnel.

### 9.3 In Plausible

1. Switch the dashboard period to Realtime. Your visit shows in the current-visitors count and `/` (or the page you opened) in the pages list within a few seconds.
2. Goal conversions for the events you fired appear in the goals section of the dashboard. If the Realtime period does not list goals, switch to Today.
3. Click a goal to filter by it, then open the properties breakdown: the band values from section 4 should show with a count of 1 for your test.
4. Full-funnel test on production: sign up with an address not already on the list (a duplicate produces `Waitlist Failed` `reason` = `duplicate`, not `Waitlist Submitted`), open the confirmation email, click the link. The API page at `https://lpapi.fredvested.com/api/waitlist/confirm?token=...` auto-posts and redirects with `302` to `https://fredvested.com/confirmed?status=confirmed&hours=%3C1` (`%3C1` is `<1` URL-encoded by `URLEncoder.encode` in `WaitlistConfirmationController.confirm`; the band depends on how quickly you clicked, measured by `SignupService.hoursBand` from `confirmation_sent_at`, falling back to `created_at`, with `72+` when both are null). The dashboard shows one `Visit /confirmed` pageview and one `Waitlist Confirmed` with `hours_to_confirm_band` = `<1`. Note that this writes a real confirmed row to the production database and counts toward the founder cap; deleting it afterwards is Andrew's call.
5. Reload `/confirmed` after the test: no second `Waitlist Confirmed`, because `hours` was removed from the URL.

## Open questions

- Plausible funnel semantics: whether a funnel is evaluated within one session or across a visitor's sessions in the period. The main funnel's last step (`Waitlist Confirmed`) depends on this; section 5.1 assumes the conservative reading and points to the goal count and `v_waitlist_funnel` for the real rate.
- Whether Plausible's installation verifier recognises a proxied classic script. Section 2 says to trust the network tab; the verifier's behaviour was not tested.
- `analytics.js` line 3 names `FREDdocs/analytics-events.md` as the canonical schema. That file exists in the working tree as of 2026-09-24 (untracked, written the same day as this one). The tables above were derived from `analytics.js` directly, not from that file; if the two documents ever disagree, `analytics.js` is the source of truth for both.
- Whether the Cloudflare Pages project defines the `PLAUSIBLE_SCRIPT_URL` or `PLAUSIBLE_EVENT_URL` bindings that `_worker.js` reads. Nothing in the repository sets them; the dashboard was not checked. If either is set, section 7's upstream URLs are wrong for that environment.
- The "7-per-visitor budget" wording in `analytics.js` versus the arithmetic ceiling of 9 custom events per landing-page load. Section 8 records both; which number the Plausible plan was sized against is a decision for Andrew.
- Reporting timezone for the Plausible site (section 2 recommends America/New_York to match the backend's Eastern timestamps) is a setting Andrew chooses; nothing in the code depends on it.
- Whether Plausible's outbound-links extension has its own additional throttling or de-duplication of `Outbound Link: Click`. The volume estimate in section 8 assumes one event per click.
