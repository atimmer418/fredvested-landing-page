# Share my Freedom Date Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** After signup, a visitor can share a link whose message preview shows their rounded freedom age and date, or save a story-sized image of it.

**Architecture:** The backend draws two PNGs per result with Java2D (link image 1200x630, story image 1080x1920). The Cloudflare Pages worker rewrites the Open Graph tags of the homepage when it is requested as `/s?r=<age>-<yyyy>-<mm>`. index.html gains a share row in the success state that opens FRED's own share sheet. One validation rule is implemented three times (Java, `frontend/assets/share.js`, `_worker.js`) and pinned by the same test vectors.

**Tech Stack:** Spring Boot 3.5 / Java 17 / Java2D; dependency-free `_worker.js` with `HTMLRewriter`; inline JS in index.html plus a classic-script/CommonJS helper; Tailwind v4; node:test; Playwright.

**Spec:** `docs/superpowers/specs/2026-11-13-share-freedom-date-design.md` (references and the counsel board are in `docs/superpowers/specs/2026-11-13-share-freedom-date/`).

## Global Constraints

- **No commits.** The working tree holds Andrew's uncommitted work (single opt-in, email typo hint). Never revert, stash or reformat anything you did not write; if an existing test conflicts with his edits, update the test. Leave everything uncommitted.
- Headless only. Never open a browser window on this Mac.
- No third-party origin may be requested by any page. No new vendor, no npm dependency in `frontend/`, no JS build step, `_worker.js` stays one dependency-free file.
- User-facing copy: no em dashes, no emoji, none of the prohibited terms in `CLAUDE.md` / the attorney memory ("guaranteed" as a claim, "will retire", "you'll be free", "predictions"). Every new sentence goes in a constants block, and every new block of copy carries `ATTORNEY REVIEW PENDING 2026-11-14`.
- "On FRED’s route." may become "On the FRED plan." It must exist in exactly one constant per language.
- Nothing above the email input changes before signup. `tests/post-reveal.spec.js`'s 390 x 660 assertion must still pass.
- The exact age and date never reach Plausible; only bands.
- Shareable results: dated (under 65) and 65-and-later. Not at-target, not the two no-date states.

### The validation rule (identical in all three implementations)

`r` is valid at instant `now` (UTC) iff all hold:

1. It matches `^(\d{2,3})-(\d{4})-(\d{2})$` giving `age`, `year`, `month`.
2. `18 <= age <= 100` and `1 <= month <= 12`.
3. `monthsOut = (year*12 + month-1) - (nowYear*12 + nowMonth-1)` satisfies `0 <= monthsOut <= 864`.
4. `17 <= age - monthsOut/12 <= 61` (the age slider is 18 to 60; one year of tolerance).

Shared test vectors, `now = 2026-11-14T12:00:00Z`:

| r | valid |
|---|---|
| `52-2048-07` | yes |
| `38-2041-09` | yes |
| `67-2063-07` | yes |
| `18-2026-12` | yes (implied age 17.9) |
| `19-2026-11` | yes |
| `18-2026-10` | no (past) |
| `52-2026-12` | yes (implied age 51.9) |
| `100-2099-01` | no (monthsOut 866) |
| `17-2030-01` | no (age) |
| `101-2090-01` | no (age) |
| `52-2048-13` | no (month) |
| `52-2048-7` | no (shape) |
| `90-2030-01` | no (implied age 86.8) |
| `30-2060-01` | no (implied age -3.2) |
| `` / `abc` / `52-2048-07x` | no |

### Derived strings

- `monthName`: English month names. `dateLabel = "<MonthName> <year>"`, e.g. `July 2048`.
- `ym = "<yyyy>-<mm>"`, zero-padded.
- Link image URL: `<apiOrigin>/api/og/v1/<age>/<ym>.png`. Story image URL: `<apiOrigin>/api/og/v1/story/<age>/<ym>.png`.
- Share URL: `<origin>/s?r=<age>-<ym>&utm_source=share&utm_medium=referral&utm_campaign=freedom_date`.
- `og:title`: `I could be free at <age>. Now your turn!`
- `og:description`: `Hypothetical illustration, not a prediction or guarantee. Find your own Freedom Date in 30 seconds.`
- `og:image:alt`: `I could be free at <age> years old in <dateLabel>. What about you?`
- Band for analytics from the shared age: `n = 65 - age`; `n < 1` gives `stake_years_band: "hidden", stake_source: "hidden"`; otherwise source `plan` and band `1-5`, `6-10`, `11+`.

## Review Focus

1. A link whose date has passed since it was shared: the worker serves the plain homepage with default tags and the image endpoint 404s; nothing errors.
2. Return visit with a saved signup but storage holding a stale or malformed `waitlist_freedom`: the share row stays hidden rather than sharing a wrong age.
3. `navigator.share` missing, or rejecting with `AbortError` when the visitor cancels: no error surfaced, no event for a cancel, "Copy link" still works.
4. Longest strings: `September` in both images, three-digit age `100`, and 65-and-later copy on the story ticket; nothing clips or overlaps.
5. A recipient URL with `r` plus UTM values: `r` is gone before Plausible's pageview, the UTM values survive for `attribution.js`.

---

### Task 1: Backend share images

**Files:**
- Create: `backend/src/main/java/com/fredvested/web/share/ShareResult.java`, `ShareImageCopy.java`, `ShareImageRenderer.java`, `ShareImageController.java`
- Create: `backend/src/main/resources/fonts/Montserrat-Black.ttf`, `Montserrat-BlackItalic.ttf`, `Inter-Medium.ttf` (static instances, OFL; update `fonts/README.md` and keep `OFL.txt` accurate for both families)
- Create: `backend/src/main/resources/share/fred-presenter.png` (transparent, about 1100px tall, rasterised headlessly from `design/pig$10k-$100k.svg` cropped to viewBox `95 132 660 586`)
- Test: `backend/src/test/java/com/fredvested/web/share/ShareResultTest.java`, `ShareImageRendererTest.java`, `ShareImageControllerTest.java`

**Interfaces (produces):**
- `record ShareResult(int age, int year, int month)` with `static Optional<ShareResult> parse(String age, String ym, Clock clock)`, `static Optional<ShareResult> parseParam(String r, Clock clock)`, `String dateLabel()`, `boolean late()` (age >= 65), `int gapYears()` (65 - age).
- `ShareImageCopy`: `KICKER = "I COULD BE FREE AT"`, `UNIT = "years old in"`, `BUBBLE = "What about you?"`, `DISCLOSURE = "Hypothetical illustration, not a prediction or guarantee."`, `ROUTE_LABEL = "On FRED’s route."`, `DO_NOTHING_LABEL = "Do nothing."`, `DO_NOTHING_VALUE = "65+"`, `NO_DATE = "No date"`, `gapLine(ShareResult)` returning `"<n> years before the usual 65"` (`"1 year before the usual 65"` for 1) or, when late, `"Hope isn’t a plan. <dateLabel> is."`, `STORY_QUESTION = "What’s your Freedom Date?"`, `DOMAIN = "fredvested.com"`, `TICKET_HEADING = "FREEDOM DATE"`, `WORDMARK = "FRED"`, `YEARS_OLD = "years old"`.
- `ShareImageRenderer.link(ShareResult) -> byte[]` (PNG 1200x630) and `.story(ShareResult) -> byte[]` (PNG 1080x1920).
- `GET /api/og/v1/{age}/{ym}.png` and `GET /api/og/v1/story/{age}/{ym}.png`: 200 `image/png` with `Cache-Control: public, max-age=31536000, immutable`; anything invalid is 404 with no body. Available in every profile. A `Clock` bean is injected (add one if none exists).

- [ ] **Step 1:** Write `ShareResultTest` from the vector table (fixed `Clock` at 2026-11-14T12:00:00Z), plus `dateLabel()` = `July 2048`, `late()` and `gapYears()` for 52 and 67. Run `./gradlew test --tests '*ShareResultTest'`; expect failure (class missing).
- [ ] **Step 2:** Implement `ShareResult` and `ShareImageCopy`. Run; expect pass.
- [ ] **Step 3:** Add the three fonts and the pig PNG. Fonts: static TTFs from the official Montserrat and Inter releases. Pig: render headlessly (the repo's Playwright Chromium can screenshot an SVG on a transparent page).
- [ ] **Step 4:** Write `ShareImageRendererTest`: both methods return a decodable PNG of the exact dimensions for `52/2048-07`, `38/2041-09`, `67/2063-07` and `100/2090-01`; link image under 300 KB, story under 600 KB; rendering is deterministic (two calls, equal bytes); a helper writes the PNGs to `backend/build/share-preview/` for eyeballing. Expect failure.
- [ ] **Step 5:** Implement `ShareImageRenderer` following `GrowthChartRenderer`'s pattern (headless property, fonts from classpath, antialiasing and fractional metrics on).
  - Link image: the spec's table, with the kicker, age, unit line, date and disclosure centred within the left 620px. Reference: `reference-52-july-2048.png`, `reference-38-september-2041.png`.
  - Story image: spec section "The story image" and panel 4 of `counsel-board.png`; layout source `story-and-sheet-mockup.html` (`.story` rules; `--u` is 1.6% of the width). Stub kicker is `I COULD BE FREE AT`; the `FRED` wordmark is Montserrat Black Italic; FRED is centred in the round stamp.
  - **Read your own output PNGs and the references side by side and iterate until they match by eye.** This is the acceptance test for this step.
- [ ] **Step 6:** Write `ShareImageControllerTest` (slice test, fixed clock): 200 + content type + cache header for both routes with `52/2048-07`; 404 for `18/2026-10`, `52/2048-13`, `abc/2048-07`, `52/2048-07x`. Implement the controller. Run `./gradlew test`; expect the whole backend suite green.

### Task 2: `frontend/assets/share.js`

**Files:**
- Create: `frontend/assets/share.js` (classic script exposing `window.FredShare`, and CommonJS, the same dual pattern as `frontend/assets/projection.js`)
- Test: `tests/unit/share.test.js`

**Interfaces (produces):** `FredShare.parse(r, now) -> {age, year, month, ym, dateLabel} | null`; `FredShare.fromSaved(age, monthYear, now) -> same | null` (`monthYear` is the saved `"July 2048"` string); `FredShare.param(result) -> "52-2048-07"`; `FredShare.shareUrl(origin, result) -> string`; `FredShare.linkImageUrl(apiOrigin, result)`; `FredShare.storyImageUrl(apiOrigin, result)`; `FredShare.band(age) -> {stake_years_band, stake_source}`; `FredShare.stripParam(search) -> string` (removes `r`, keeps everything else, returns `""` or `"?..."`).

- [ ] **Step 1:** Write `tests/unit/share.test.js`: the vector table against `parse`; `fromSaved(52, "July 2048", now)` equals `parse("52-2048-07", now)`; `fromSaved` returns null for `"Julember 2048"`, a missing value, a non-integer age and an at-target-style null; `shareUrl("https://fredvested.com", …)` equals the Derived strings example exactly; `band(52)` is `11+`/`plan`, `band(60)` is `1-5`, `band(57)` is `6-10`, `band(65)` and `band(70)` are hidden; `stripParam("?r=52-2048-07&utm_source=share")` is `"?utm_source=share"`, `stripParam("?r=x")` is `""`. Run `npm run test:unit`; expect failure.
- [ ] **Step 2:** Implement. Run; expect pass.

### Task 3: Worker route, routes file, dev server

**Files:**
- Modify: `frontend/_worker.js`, `frontend/_routes.json` (add `/s`), `frontend/serve.py` (serve index.html for `/s`)
- Test: `tests/unit/worker-share.test.js`

**Interfaces:**
- Produces: the default export gains `shareMeta(url, now) -> null | {title, description, url, image, imageAlt, width: "1200", height: "630"}` so node can test it without `HTMLRewriter`. `image` uses the API origin: `env.API_ORIGIN` if set, else `https://lpapi.fredvested.com` when the hostname is `fredvested.com` or `www.fredvested.com`, else `https://lpapi-dev.fredvested.com`.
- `/s` handling: fetch `/` from `env.ASSETS`; when `shareMeta` is non-null, rewrite `og:title`, `og:description`, `og:url`, `og:image`, `twitter:title`, `twitter:description`, `twitter:image`, and append to `<head>`: `og:image:width`, `og:image:height`, `og:image:alt`, `twitter:image:alt`, `<meta name="robots" content="noindex">`. Otherwise return the homepage untouched. GET and HEAD only.

- [ ] **Step 1:** Write `tests/unit/worker-share.test.js` (dynamic `import()` of the worker): the vector table through `shareMeta(new URL("https://fredvested.com/s?r=" + r), now)`; exact title/description/alt/url/image for `52-2048-07`; dev origin for a `*.pages.dev` hostname; `env.API_ORIGIN` override; a test that `_routes.json` includes `/s` and still includes the two Plausible paths. Run `npm run test:unit`; expect failure.
- [ ] **Step 2:** Implement in `_worker.js` and `_routes.json`. Keep the file's existing comment style. Run; expect pass.
- [ ] **Step 3:** `serve.py`: `/s` (with any query) serves `index.html`. Verify with `curl -s 'http://127.0.0.1:5500/s?r=52-2048-07' | grep -c 'id="result-card"'` giving 1 while `npm run serve` (or the script directly) is running; stop it afterwards.

### Task 4: The page

**Files:**
- Modify: `frontend/index.html`, `frontend/assets/analytics.js`, `frontend/assets/waitlist.js` (only if a clear/expose helper is needed), `frontend/dist/output.css` (via `cd frontend && npm run build`), `tests/helpers.js` (mock the story image route, expose `/s`)
- Test: `tests/share.spec.js`

**Interfaces:** Consumes `FredShare` (Task 2) and the image URL shapes (Derived strings). The API origin is `Waitlist.API_BASE` minus its `/api/waitlist` suffix.

Copy (all in the `COPY` block, attorney-pending): `SHARE_LABEL` "Show someone your date"; `SHARE_SUB` "Sends your age and date. Nothing else you entered."; `SHARE_BUTTON` "Share"; `SHEET_TITLE` "Share your Freedom Date"; `SHEET_SUMMARY` "{DATE}, age {AGE}"; `SHEET_LINK` "Share link" / `SHEET_LINK_SUB` "Opens your phone’s share menu"; `SHEET_STORY` "Save a story postable image" / `SHEET_STORY_SUB` "Story size, ready for Instagram or TikTok"; `SHEET_COPY` "Copy link" / `SHEET_COPY_SUB` "Pastes with your Freedom Date as the preview"; `SHEET_COPIED` "Link copied"; `SHEET_FINE` "The link carries your age and date only. No email, no income, no amounts."; `STORY_HINT` "Press and hold the image to save it."; `SHEET_CLOSE` "Close".

Element ids the tests rely on: `share-row`, `share-open`, `share-sheet` (`role="dialog"`, `aria-modal="true"`), `share-sheet-thumb`, `share-native`, `share-story`, `share-copy`, `share-copy-status` (live region), `share-close`, `story-view`, `story-img`, `story-close`.

Behaviour:
- `#share-row` lives in `#success-block` under `#success-note`, built like panel 1 of the counsel board. Hidden unless a shareable result exists: the live dated result right after a signup, otherwise `FredShare.fromSaved` on the saved `waitlist_freedom`. Never in `#pending-block`.
- A signup whose result is not dated clears `waitlist_freedom`.
- The sheet and story view are not in the DOM before the success state exists (same rule as the post-reveal template). Opening moves focus into the sheet; Escape, the close button and the scrim close it and return focus to `#share-open`. Reduced motion: no slide animation.
- `#share-native` is hidden when `navigator.share` is undefined. A rejection named `AbortError` is silent and fires no event.
- `#share-copy` uses `navigator.clipboard.writeText` with an `execCommand('copy')` fallback and announces `SHEET_COPIED`.
- `#share-story` shows `#story-view` with `#story-img` pointing at the story image URL and `STORY_HINT`.
- `Result Shared` fires once per completed action with `method` `native` | `copy` | `story` and the band properties from `FredShare.band(age)`. Register it in `analytics.js` the way the existing events are.
- Recipient: an inline script placed before the Plausible script tag calls `history.replaceState(null, "", "/" + FredShare.stripParam(location.search))` when the path is `/s`. `share.js` must therefore load before it.

- [ ] **Step 1:** Write `tests/share.spec.js` using the harness in `tests/helpers.js`:
  - no `#share-row` / `#share-sheet` in the DOM before reveal and after reveal but before signup;
  - after a dated signup the row is visible, and at 390 x 660 the pre-signup form-in-view assertion of `post-reveal.spec.js` is unaffected;
  - at-target and both no-date signups: row hidden, `waitlist_freedom` cleared;
  - 65-and-later signup: row visible;
  - return visit with saved state and default sliders: the copied link carries the saved age and date, not the defaults';
  - "Copy link" writes a URL whose query is exactly `r`, `utm_source=share`, `utm_medium=referral`, `utm_campaign=freedom_date`, and announces "Link copied";
  - with `navigator.share` stubbed: called with `{url}` only; with it deleted: `#share-native` hidden; with it rejecting `AbortError`: no `Result Shared` event;
  - story option shows `#story-img` with the expected URL (route mocked with a tiny PNG);
  - Escape closes the sheet and focus returns to `#share-open`;
  - `Result Shared` carries `method` and bands and no property containing the age or the date;
  - loading `/s?r=52-2048-07&utm_source=share&utm_medium=referral&utm_campaign=freedom_date`: the recorded Plausible pageview URL has no `r=`, keeps `utm_source=share`, and the sliders are at their defaults;
  - the third-party-host guard stays green.
  Run `npx playwright test tests/share.spec.js`; expect failures.
- [ ] **Step 2:** Implement. Rebuild CSS with `cd frontend && npm run build`.
- [ ] **Step 3:** Run `npm test` (unit + full e2e). Expect everything green; fix regressions in code you wrote, and update existing specs only where they assert the absence of the new elements or a fixed event list.

### Task 5: Documentation

**Files:** `CLAUDE.md` (a short "Share my Freedom Date" paragraph under Frontend and the endpoint under Backend), `FREDdocs/analytics-events.md` (`Result Shared`, its properties, the budget table, and the `/s` pageview behaviour), `FREDdocs/utm-conventions.md` (the share values), `FREDdocs/go-live-checklist.md` (counsel approval of the share copy and both images; create the `Result Shared` goal in Plausible; optional `API_ORIGIN` worker variable; verify `/s` on the deployed Pages project because `HTMLRewriter` was only proven locally; test the sheet inside Instagram and TikTok on a real iPhone and Android phone).

- [ ] **Step 1:** Write the updates from the finished code, not from this plan. Append; do not rewrite Andrew's existing text.
