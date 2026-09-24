# UTM conventions

How links into fredvested.com must be tagged so that attribution is clean. Everything below is true of the working tree on 2026-09-24. Where a rule is a convention chosen by Andrew rather than something the code enforces, it is marked as such.

Source of truth for the mechanics:

| Concern | Code |
|---|---|
| Capture in the browser, first touch vs last touch | `frontend/assets/attribution.js` |
| What is sent to Plausible | `frontend/assets/analytics.js` (`'Waitlist Submitted'` builder) |
| Server-side sanitisation | `backend/src/main/java/com/fredvested/web/util/AttributionSanitizer.java` |
| Request fields and where they are applied | `backend/src/main/java/com/fredvested/web/controller/WaitlistController.java` (`WaitlistRequest`, `applyAttribution`) |
| Columns | `backend/src/main/resources/db/migration/V3__attribution_and_projection_columns.sql`, entity `backend/src/main/java/com/fredvested/web/model/WaitlistEntry.java` |
| Weekly funnel grouped by UTM | `v_waitlist_funnel`, last defined in `V5__confirmed_source_and_legacy_backfill.sql` |

## The five parameters

`attribution.js` parses exactly these query-string keys (the `PARAMS` array): `utm_source`, `utm_medium`, `utm_campaign`, `utm_content`, `utm_term`. Nothing else on the URL is read for attribution.

What each one means for FRED is a naming convention, not a rule in the code. The code treats all five identically (same sanitiser, same length cap). The example values `tiktok`, `instagram`, `bio`, `nurse_shift_math`, `compound_basics`, `20260921-night-shift-roi` and `fire` come from `WaitlistControllerAttributionTest.java`, and the `yyyymmdd-slug` form of `utm_content` from the comment in `V3__attribution_and_projection_columns.sql` (`utm_content is the per-post identifier (yyyymmdd-slug)`). The meanings themselves and the other example values (`youtube`, `meta`, `paid_social`, `story`) are proposed conventions and appear nowhere in the code.

| Parameter | Meaning for FRED (convention) | Reaches Plausible | Stored per signup | In `v_waitlist_funnel` |
|---|---|---|---|---|
| `utm_source` | The platform the click came from: `tiktok`, `instagram`, `youtube`, `meta` | Yes (last touch) | `utm_source`, `first_utm_source` | Yes |
| `utm_medium` | The placement type: `bio`, `paid_social`, `story` | Yes (last touch) | `utm_medium` | No |
| `utm_campaign` | The content theme or series the visitor was pulled in by: `nurse_shift_math`, `compound_basics` | Yes (last touch) | `utm_campaign`, `first_utm_campaign` | Yes |
| `utm_content` | The individual post or video, as `yyyymmdd-slug`: `20260921-night-shift-roi` | No | `utm_content`, `first_utm_content` | Yes |
| `utm_term` | Free for paid keyword or audience labels. Database only. | No | `utm_term` | No |

Two consequences of that table:

- `utm_content` is high cardinality by design (one value per post). `attribution.js` keeps it out of the custom events on purpose (comment in `forPlausible()`); in this repo's code it only exists in the database. Whether Plausible's own tracker reads it from the landing URL is an open question below.
- The funnel view groups by `utm_source`, `utm_campaign`, `utm_content` only. A naming choice in `utm_medium` or `utm_term` never changes a funnel row.

## Naming rules that survive the sanitiser

Values are sanitised twice with the same rules: in the browser at capture time (`sanitize()` in `attribution.js`, for data quality) and again on the server (`AttributionSanitizer.token()`, the security boundary). The server version, per method:

| Step | `AttributionSanitizer.token()` behaviour |
|---|---|
| Lowercase | `raw.toLowerCase(Locale.ROOT)`. `TikTok` becomes `tiktok`. |
| Character set | `replaceAll("[^a-z0-9\\-_.]", "")`. Only `a-z`, `0-9`, `-`, `_`, `.` survive. Everything else is removed, not replaced: spaces, `+`, `/`, `:`, `%`, `@`, accented and non-Latin characters, angle brackets, quotes. |
| Length | Truncated to `TOKEN_MAX = 100` characters, counted after the character strip. |
| Empty | `null` if nothing is left. `"   "`, `"!!!"` and `""` all become `null`. |
| Errors | Never throws. `applyAttribution` in `WaitlistController` also wraps the whole block in a `try/catch`; a malformed value costs the tag, never the signup. |

The browser copy in `attribution.js` is the same: `value.toLowerCase().replace(/[^a-z0-9\-_.]/g, '').slice(0, MAX_LEN)` with `MAX_LEN = 100`, `null` if empty. Because the browser sanitises before writing to storage, the stored snapshots already hold cleaned values; the server run is a re-check.

Worked results from `AttributionSanitizerTest.java`:

| Input | Stored as |
|---|---|
| `TikTok` | `tiktok` |
| `20260921-night-shift-roi` | `20260921-night-shift-roi` (unchanged) |
| `a.b_c-d` | `a.b_c-d` (unchanged) |
| `<script>alert(1)</script>bio` | `scriptalert1scriptbio` |
| `nurse'; DROP TABLE waitlist_signups;--` | `nursedroptablewaitlist_signups--` |
| `✓ünïcödé` | `ncd` |
| 5000 x `x` | 100 x `x` |

Rules that follow directly from the code:

1. Use only lowercase `a-z`, digits, `-`, `_` and `.`. Anything else is silently deleted, and deletion changes meaning: `night shift` and `night+shift` both become `nightshift`, which does not match `night_shift` or `night-shift`.
2. Pick one separator and keep it. The sanitiser does not normalise `-` and `_` to each other: `nurse-shift-math` and `nurse_shift_math` are two different values in the database and two different rows in Plausible.
3. Case does not matter for matching (everything is lowercased), but write lowercase anyway so the link reads the way it is stored.
4. Stay well under 100 characters per value. A longer value is cut at 100 with no warning.
5. Percent-encoding: the browser decodes the query string (`URLSearchParams`) before the sanitiser sees it, so `%20` becomes a space and is then removed. Do not rely on encoding to smuggle a character through.
6. Never use the reserved fallback words as real values: `direct` for `utm_source`, `none` for `utm_medium` and `utm_campaign`. Plausible receives those words when a signup had no last-touch tag (`forPlausible()` in `attribution.js`), so a link tagged that way is indistinguishable from an untagged visit in Plausible. The database has no such fallback; untagged signups store `NULL`.

## First touch vs last touch

`attribution.js` loads on every content page (`index.html`, `about.html`, `confirmed.html`, `terms.html`, `privacy.html`), before `analytics.js`, and runs `capture()` immediately on every page load. It is not gated by hostname: it runs the same on previews and local, and the backend stores attribution in every environment.

The one page that does not load it is `frontend/calculator.html`, a stub kept for old `/calculator` links. It redirects to `/` with `location.replace(isDev ? 'index.html' : '/')` and a `<meta http-equiv="refresh" content="0; url=/">` fallback, neither of which carries the query string. A tagged `/calculator` link therefore arrives at `/` untagged.

Three snapshots:

| Snapshot | Storage key | Scope | Written when |
|---|---|---|---|
| Visit | `sessionStorage` `fred_visit` | One tab, until the tab closes | Once per session, on the first page load that finds it absent. Holds `referrer_host`, `landing_path`, `device_type`. |
| Last touch | `sessionStorage` `fred_attr_last` | One tab, until the tab closes | On every page load whose URL carries at least one `utm_*` value that survives sanitising. Replaced as a whole, not merged. |
| First touch | `localStorage` `fred_attr_first` | Whole browser profile for the origin, no expiry | Only if absent, on a page load that carries at least one surviving `utm_*` value. Adds `first_touch_at` (browser clock, ISO-8601 instant). Never overwritten by code. |

What that means in practice:

- A "session" is the browser's `sessionStorage` scope. The code defines no session of its own and has no timeout. Reloads and same-tab navigation stay in the session; a tab or window the visitor opens themselves, or a new browser, starts a new one with empty last-touch and visit snapshots. (Browsers copy `sessionStorage` into a tab that a page opens itself, through a `target="_blank"` link or `window.open`; that is browser behaviour, not code here, and none of the pages opens an internal link that way.) `localStorage` (first touch) is shared across tabs of the same browser profile and origin.
- A page load with no `utm_*` parameters writes neither the last-touch nor the first-touch snapshot (`if (!params) return;` in `capture()`). So a visitor who arrives tagged from a bio link, then browses to `/about` untagged, still submits with the bio-link tags as last touch.
- A second tagged load in the same tab replaces the last-touch object entirely. If the second link carries only `utm_source`, the previous `utm_campaign` and `utm_content` are gone from last touch.
- First touch is the first tagged load this browser ever saw, not the first visit. An untagged first visit records nothing; the tag on a later visit becomes both first and last touch. There is no code path that clears `fred_attr_first`; only the visitor clearing site data does.
- Storage failures (private windows, blocked site data, quota) are swallowed. The snapshot is simply missing and the signup arrives with those fields absent.
- Storage is per origin. `fredvested.com` and `www.fredvested.com` are different origins to the browser, so a first touch recorded on one is invisible on the other. Tag links to the host visitors actually stay on (see open questions).

`referrer_host` is the hostname only of `document.referrer`, lowercased, cut at 255, and `null` when empty or equal to the current hostname. The full referrer URL is never stored. `landing_path` is `location.pathname` of the first page in the session, cut at 255. `device_type` is one of `mobile`, `tablet`, `desktop` from `deviceType()`, which checks the user agent first (`iPad|Tablet|PlayBook|Silk`, or `Android` without `Mobile`, is `tablet`; `Mobi|iPhone|iPod|Android` is `mobile`) and then `innerWidth` (under 768 is `mobile`, under 1024 is `tablet`, else `desktop`); `desktop` if the check throws. The code comment says viewport first, but the code tests the user agent first.

## What is sent with a signup

`FredAttribution.get()` returns a flat camelCase object which `index.html` (`Object.assign(payload, Analytics.getAttribution())`) and `about.html` merge into the JSON body of `POST /api/waitlist`. Keys absent from storage are simply omitted.

| JSON field | Taken from | Column after sanitising |
|---|---|---|
| `utmSource` | last touch `utm_source` | `utm_source` |
| `utmMedium` | last touch `utm_medium` | `utm_medium` |
| `utmCampaign` | last touch `utm_campaign` | `utm_campaign` |
| `utmContent` | last touch `utm_content` | `utm_content` |
| `utmTerm` | last touch `utm_term` | `utm_term` |
| `firstUtmSource` | first touch `utm_source` | `first_utm_source` |
| `firstUtmCampaign` | first touch `utm_campaign` | `first_utm_campaign` |
| `firstUtmContent` | first touch `utm_content` | `first_utm_content` |
| `firstTouchAt` | first touch `first_touch_at` | `first_touch_at` |
| `referrerHost` | visit `referrer_host` | `referrer_host` |
| `landingPath` | visit `landing_path` | `landing_path` |
| `deviceType` | visit `device_type` | `device_type` |

Notes:

- First-touch `utm_medium` and `utm_term` are captured into `localStorage` but `get()` does not read them and there are no columns for them. Only source, campaign and content survive from the first touch.
- `firstTouchAt` goes through `AttributionSanitizer.instantToEastern()`: the string must parse as an ISO-8601 instant and be at most 40 characters, and it is stored as `America/New_York` wall-clock `LocalDateTime`, the same convention as `created_at`. Anything else becomes `null`. The value comes from the visitor's browser clock; the server does not check it against its own time.
- `referrerHost` goes through `host()` (`[a-z0-9.-]`, 255 max), `landingPath` through `path()` (`[a-z0-9/._-]`, 255 max, must start with `/`), `deviceType` through a whitelist. All null on failure.
- The server reads attribution from the JSON body only. It does not look at the `Referer` header or the request URL.
- An address that already exists returns `status: "already_joined"` and nothing is saved (`repository.existsByEmail` check runs before the entry is built). A returning signup therefore never updates the attribution on the existing row. In Plausible this case is reported as `'Waitlist Failed'` with `reason: 'duplicate'`, which carries no UTM props; it doubles as a returning-interest signal.

## Columns (V3)

Added by `V3__attribution_and_projection_columns.sql` to `waitlist_signups`:

| Column | Type |
|---|---|
| `utm_source` | `VARCHAR(100) NULL` |
| `utm_medium` | `VARCHAR(100) NULL` |
| `utm_campaign` | `VARCHAR(100) NULL` |
| `utm_content` | `VARCHAR(100) NULL` |
| `utm_term` | `VARCHAR(100) NULL` |
| `first_utm_source` | `VARCHAR(100) NULL` |
| `first_utm_campaign` | `VARCHAR(100) NULL` |
| `first_utm_content` | `VARCHAR(100) NULL` |
| `first_touch_at` | `DATETIME(6) NULL` |
| `referrer_host` | `VARCHAR(255) NULL` |
| `landing_path` | `VARCHAR(255) NULL` |
| `device_type` | `VARCHAR(20) NULL` |

Index: `idx_waitlist_signups_utm` on `(utm_source, utm_campaign, utm_content)`, which is the same triple the funnel view groups by. V3 also added `computed_freedom_date`, `computed_portfolio_target`, `revealed_before_submit` and `idx_waitlist_signups_created_at`; those are projection columns, not attribution.

The `VARCHAR(100)` widths match `TOKEN_MAX`; the sanitiser guarantees a value never exceeds the column.

## What reaches Plausible

Only `analytics.js` calls `window.plausible()`, and only one event carries UTM data: `'Waitlist Submitted'`. Its builder reads `FredAttribution.forPlausible()` and sends:

| Prop | Value |
|---|---|
| `utm_source` | last touch `utm_source`, else `'direct'` |
| `utm_medium` | last touch `utm_medium`, else `'none'` |
| `utm_campaign` | last touch `utm_campaign`, else `'none'` |
| `source_page` | `'home'` or `'about'` |
| `revealed` | `'yes'` or `'no'` |
| `return_scenario`, `freedom_age_band` | only when the calculator revealed on this page load (`calc.revealed`, set by `'Freedom Date Revealed'`); `freedom_age_band` is `'unreachable'` when the reveal found no date |

Not sent to Plausible by this code: `utm_content`, `utm_term`, any first-touch value, `first_touch_at`, `referrer_host`, `landing_path`, `device_type`. `'Waitlist Submitted'` fires once per page load, only after a 2xx that is not `already_joined`, and only on `fredvested.com` / `www.fredvested.com` (or with `localStorage.fred_analytics_force === '1'`).

Plausible is anonymous and cannot be joined back to a signup row. The per-signup columns exist so that the join can happen in our own database instead; this is why `attribution.js` exists at all (its header comment).

## The `ref` parameter is not captured

`attribution.js` reads the five `utm_*` keys and nothing else. A `?ref=` parameter is ignored by attribution capture and never reaches the database. The comment above `PARAMS` gives the reason: "A `ref` parameter is deliberately not captured: the disclosures counsel is drafting describe UTM attribution and nothing else." That is a decision by Andrew, made to keep the code inside what the disclosures describe, not a technical limitation. If a partner or platform can only add `ref=`, ask for `utm_source=` instead.

## Worked examples

### Social bio links

The bio link is one URL for all posts on a platform, so it identifies the platform and placement but cannot identify the post. Convention: source and medium in the bio link, campaign for the running theme, no `utm_content`.

```
https://fredvested.com/?utm_source=tiktok&utm_medium=bio&utm_campaign=nurse_shift_math
https://fredvested.com/?utm_source=instagram&utm_medium=bio&utm_campaign=nurse_shift_math
```

A visitor who taps the TikTok link in a browser that has never seen a tagged load before, submits on the home page, and confirms, ends up with:

| Where | Values |
|---|---|
| `waitlist_signups` row | `utm_source = 'tiktok'`, `utm_medium = 'bio'`, `utm_campaign = 'nurse_shift_math'`, `utm_content = NULL`, `utm_term = NULL`; `first_utm_source = 'tiktok'`, `first_utm_campaign = 'nurse_shift_math'`, `first_utm_content = NULL`, `first_touch_at` set; `referrer_host` is whatever hostname the app's in-app browser sends, or `NULL`; `landing_path = '/'`; `device_type = 'mobile'` on a phone |
| Plausible `'Waitlist Submitted'` | `utm_source: 'tiktok'`, `utm_medium: 'bio'`, `utm_campaign: 'nurse_shift_math'`, `source_page: 'home'`, `revealed`, and `return_scenario` plus `freedom_age_band` only if the calculator revealed on that page load |
| `v_waitlist_funnel` row | `('tiktok', 'nurse_shift_math', NULL)` |

Where a platform allows a link on the post itself (a story link, a pinned comment, a YouTube description), add the post identifier so the funnel view can attribute confirmations to the individual post:

```
https://fredvested.com/?utm_source=tiktok&utm_medium=story&utm_campaign=nurse_shift_math&utm_content=20260921-night-shift-roi
```

`utm_content` follows `yyyymmdd-slug`: the date the post went out, then a short hyphenated slug. Dates sort, and the value passes the sanitiser unchanged (`20260921-night-shift-roi` is in the tests).

Changing the bio link when a new series starts is what moves `utm_campaign`. Do not change `utm_source` or `utm_medium` for the same placement over time; that splits the history.

### A paid campaign

```
https://fredvested.com/?utm_source=meta&utm_medium=paid_social&utm_campaign=2026q4_nurses&utm_content=20261001-video-a&utm_term=nurses_25-34
```

| Where | Values |
|---|---|
| `waitlist_signups` row | `utm_source = 'meta'`, `utm_medium = 'paid_social'`, `utm_campaign = '2026q4_nurses'`, `utm_content = '20261001-video-a'`, `utm_term = 'nurses_25-34'`, first-touch columns the same on a first visit |
| Plausible | `utm_source: 'meta'`, `utm_medium: 'paid_social'`, `utm_campaign: '2026q4_nurses'`. The ad creative (`utm_content`) and audience (`utm_term`) do not reach Plausible. |
| `v_waitlist_funnel` row | `('meta', '2026q4_nurses', '20261001-video-a')`. The audience label is not a grouping key; query the table directly for `utm_term`. |

If the visitor had first arrived weeks earlier from the TikTok bio link in the same browser, the row would instead carry `first_utm_source = 'tiktok'`, `first_utm_campaign = 'nurse_shift_math'`, `first_utm_content = NULL` and the earlier `first_touch_at`, while the `utm_*` columns and the Plausible props still say `meta`. That is the first-touch vs last-touch split in one row.

### A link that goes wrong

```
https://fredvested.com/?utm_source=TikTok&utm_medium=Bio Link&utm_campaign=Nurse Shift Math
```

Stored as `utm_source = 'tiktok'`, `utm_medium = 'biolink'`, `utm_campaign = 'nurseshiftmath'`. The case is repaired; the spaces are not. `biolink` never matches `bio`, and `nurseshiftmath` never matches `nurse_shift_math`, so this link reports as a separate source in every view.

## Do not

- Do not put personal data in any UTM value: no names, emails, handles, phone numbers. The sanitiser strips characters, it does not detect identity (`jane@example.com` is stored as `janeexample.com`), and `utm_source`, `utm_medium` and `utm_campaign` are also sent to Plausible.
- Do not use spaces or any character outside `a-z 0-9 - _ .`. They are removed, which merges words and breaks matching.
- Do not create mixed-case or mixed-separator variants of the same value. Case is normalised, separators are not. Decide once (`nurse_shift_math`) and reuse it exactly.
- Do not use `direct`, `none` as real values; they are the Plausible fallbacks for untagged visits.
- Do not use `ref=` or any non-`utm_` parameter for attribution. It is not captured.
- Do not tag links between our own pages, or links inside our own emails. `attribution.js` runs on every page including `confirmed.html`, and a tagged internal or emailed link would overwrite the last-touch snapshot (and set first touch where none exists) with a tag that describes our own site, not the channel that brought the visitor. The emailed confirmation and unsubscribe links carry no UTM parameters today; keep it that way.
- Do not tag links to `/calculator`. The stub at `frontend/calculator.html` redirects to `/` and drops the query string, so the tags never reach `attribution.js`. Tag `https://fredvested.com/` directly.
- Do not exceed 100 characters per value. Longer values are cut silently.
- Do not expect `utm_content` or `utm_term` in Plausible, or `utm_medium` and `utm_term` in `v_waitlist_funnel`. They are database columns; plan the naming so the funnel triple (`utm_source`, `utm_campaign`, `utm_content`) says what you need.
- Do not link to a host the visitor will be redirected off. Storage is per origin, and a redirect that drops the query string would drop the tags before `attribution.js` runs (see open questions).

## Open questions

- Plausible's own tracker (the upstream `script.outbound-links.js` served through `/js/script.js`) is documented by Plausible to read `utm_*` and `ref` from the landing page URL for its Sources and Campaigns reports. That is upstream behaviour, not code in this repo, and it has not been verified here. If it holds, `utm_content` and `utm_term` do reach Plausible through the automatic pageview even though `analytics.js` never sends them, and a `ref=` parameter would show in Plausible's referrer report while still never reaching the database. Verify in the Plausible dashboard after the first tagged production visit.
- Whether `www.fredvested.com` redirects to `fredvested.com` (or the reverse), and whether that redirect preserves the query string, is configured in Cloudflare, not in this repo (`frontend/_worker.js` and `frontend/_routes.json` contain no redirects and there is no `_redirects` file). Until confirmed, tag links to the apex host `https://fredvested.com/`.
- Cloudflare Pages redirects `/privacy.html` to `/privacy` (per the comment in `frontend/assets/waitlist.js`); whether that redirect keeps the query string is not verified. Tag links to clean paths.
- `deviceType` is a coarse heuristic and the tablet vs desktop boundary (viewport under 1024px counts as tablet) may misclassify small laptop windows. Not a UTM concern, but it sits in the same row.
