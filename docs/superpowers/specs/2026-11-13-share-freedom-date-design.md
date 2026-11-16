# Share my Freedom Date: design

Date: 2026-11-13
Status: approved by Andrew 2026-11-14 ("let's start building it"). Revised 2026-11-13 after his second pass (no switch, share sheet, story image).

Items marked **(Andrew)** are his decisions from the 2026-11-13 conversation. Items marked **(my call)** are choices made while writing this up; he has not confirmed them.

## Intent

After someone joins the waitlist, let them send their result to a friend so the friend runs the calculator and joins too. **(Andrew)** The point is two signups: the person who just joined, and someone they know.

When the link is pasted into a message (iMessage, WhatsApp, Instagram DMs, X, Slack), the preview shows an image made for that result: the age, the Freedom Date and FRED asking "What about you?".

## Goals

1. A share control on index.html that appears only after a successful signup, opening FRED's own share sheet with three actions. **(Andrew)**
2. A share link whose preview image and title carry that visitor's rounded age and month/year.
3. The recipient lands on the normal calculator at its defaults.
3a. A story-sized image (1080 x 1920) of the result that the visitor can save and post. **(Andrew)**
4. No new vendor, no new third-party origin in the browser, no JS build step.

## Non-goals (v1)

- Handing the story image straight to another app. The visitor saves it and posts it themselves.
- A kill switch or feature flag. **(Andrew)** This does not go live until he has counsel's approval, so the build only has to work on dev.
- Any share control before signup, on about.html, on confirmed.html or in the welcome email.
- Per-sharer referral tracking, or any reward for sharing.
- Showing the sharer's result to the recipient on the page itself.
- Sharing the at-target state or the two no-date states. **(my call)** At-target would publish the visitor's real current age and its copy is still attorney-pending; the no-date states have nothing to show.

## Anti-goals

- The visitor's inputs (current age, monthly amount, target income, existing balance, email) travelling in the link, the image, or analytics.
- The exact freedom age or date reaching Plausible. Today only bands leave the page (`FREDdocs/analytics-events.md`).
- Anything added above the email input before signup. The 390 x 660 rule in CLAUDE.md stands.
- A share incentive of any kind (it would turn a share into a paid endorsement).

## The image **(Andrew)**

1200 x 630 PNG. Reference renders, approved by Andrew on 2026-11-13:

- `docs/superpowers/specs/2026-11-13-share-freedom-date/reference-52-july-2048.png`
- `docs/superpowers/specs/2026-11-13-share-freedom-date/reference-38-september-2041.png` (longest month name)

The references came from a throwaway satori render. The production renderer must match them by eye, not by pixel.

Content, in reading order:

| Piece | Text | Type in the reference | Position in the reference (1200 x 630) |
|---|---|---|---|
| Kicker | `I COULD BE FREE AT` | Inter 700, 30px, letter-spacing 5px, white at 85% | top 64; this and the next three rows are centred as a column within the left 620px **(Andrew, 2026-11-14)** |
| Age | rounded age, e.g. `52` | Montserrat 900, 280px, line-height 0.95, white | under the kicker, 10px gap |
| Unit line | `years old in` | Inter 500, 44px, white | under the age, 6px gap |
| Date | month and year, e.g. `July 2048` | Inter 700, 60px, white | under the unit line, 2px gap |
| Bubble | `What about you?` | Montserrat 900, 46px, `#1e60e3` on white, corner radius 23, padding about 13 x 32, small downward tail near its right end | top 56, right 96 |
| FRED | the presenter pig (`design/pig$10k-$100k.svg`) | 370px tall (417 wide) | right 84, bottom 58, under the bubble's tail |
| Disclosure | `Hypothetical illustration, not a prediction or guarantee.` | Inter 500, 21px, white at 80% | centred within the left 620px, bottom 40 |

Background: `linear-gradient(160deg, #1e60e3 0%, #0e3a96 100%)`, the result card's gradient.

Not on the image **(Andrew)**: "fredvested.com" (it read as clutter; the message preview shows the domain itself), the "do nothing" comparison, the stakes line.

The 65-and-later state uses the same image (e.g. `67`, `July 2063`). **(my call)**

Target file size: under 300 KB (WhatsApp guidance). The references are about 195 KB.

## The share control **(Andrew: placement; my call: wording)**

Inside `#success-block` on index.html, under `#success-note`: a row with a short label on the left and a "Share" button on the right, as drawn in direction A of the mockups (https://claude.ai/artifact/VVXkSRwsf4wzdTJ81tEVBA, "3. The share control on the page", left phone).

- Draft copy: label "Show someone your date", sub line "Sends your age and date. Nothing else you entered.", button "Share". All new, all attorney-pending, all in the `COPY` block.
- Shown only when the saved or live result is a dated one (under 65, or 65 and later). Hidden for at-target and no-date results, and in `#pending-block` (double opt-in: the inbox is the one action there).
- On a return visit the success block is shown from saved state with the sliders at defaults (`openZoneForSavedState`), so the control reads the saved result (`waitlist_freedom`: `{age, monthYear}`), never the live render. If there is no saved dated result, the control stays hidden. Two gaps in today's code to close: a signup with a non-dated result must clear `waitlist_freedom` (today it is only written, never cleared, so a stale age could be shared), and right after a signup the control may use the in-memory result when storage is blocked.
- Tapping "Share" opens a bottom sheet of our own **(Andrew)**, as drawn in panel 2 of the counsel board (`docs/superpowers/specs/2026-11-13-share-freedom-date/counsel-board.png`):
  - Header: a thumbnail of the link image, "Share your Freedom Date", "July 2048, age 52".
  - **Share link** / "Opens your phone's share menu": `navigator.share({ url })`. Hidden where `navigator.share` is missing (expected inside some in-app browsers), in which case Copy link leads.
  - **Save a story postable image** (Andrew's wording) / "Story size, ready for Instagram or TikTok": shows the story image full-screen as a real `<img>` with a line telling the visitor to press and hold to save. **(my call)** Blob downloads are unreliable inside Instagram's and TikTok's browsers; where `navigator.canShare({ files })` passes, the builder may also offer the image to the share sheet.
  - **Copy link** / "Pastes with your Freedom Date as the preview": clipboard, confirmed in place ("Link copied").
  - Fine print: "The link carries your age and date only. No email, no income, no amounts."
  - The sheet is a dialog: focus moves in, Escape and a tap on the scrim close it, focus returns to the Share button.
- No pre-written message text is passed with the link. **(my call)**
- No per-app buttons (WhatsApp, SMS, X).

## The story image **(Andrew)**

1080 x 1920 PNG: direction C's upright ticket from the mockups, as shown in panel 4 of the counsel board. On the blue gradient, a paper ticket reads "FREEDOM DATE", the "FRED" wordmark in Montserrat 900 italic like the landing page's nav (Andrew, 2026-11-14), the month and year stacked large ("JULY" / "2048"), a route row ("Do nothing. 65+" to "On FRED's route. 52"), "13 years before the usual 65", then the disclosure line and fredvested.com. A navy stub below a perforation carries "I COULD BE FREE AT" (Andrew, 2026-11-14; the mockup fragment still says "You"), the age, "years old" and FRED centred in a round stamp. Under the ticket: "What's your Freedom Date?" and "fredvested.com". The top and bottom bands stay empty for the Stories interface.

- For 65 and later the route row's left value is "No date" and the gap line is the card's own late stakes line ("Hope isn't a plan. July 2063 is.").
- Reference for layout and wording: `docs/superpowers/specs/2026-11-13-share-freedom-date/story-and-sheet-mockup.html` (the mockup fragment, sections "2. Story image" and "3b" for the sheet; its pig is a `<use>` of a symbol the artifact page supplied, so it shows without FRED when opened alone) and panel 4 of `counsel-board.png`. In the sheet, the header thumbnail is the link image, the second action reads "Save a story postable image" and the third's sub line is "Pastes with your Freedom Date as the preview", as on the counsel board, not as in the fragment.
- Served by the backend next to the link image: `GET /api/og/v1/story/{age}/{yyyy-mm}.png`, same validation and cache headers. **(my call)** The page requests it from the API origin it already talks to, so no new host is involved.
- Both images say "I could be free at".
- The label is "On the FRED plan." since 2026-11-15 (Andrew; it was "On FRED's route." when this was written, which the mockups and panel 4 still show). The story image must take that label from one constant so the change is a one-line edit; do not hard-code it in drawing code.

## The share link **(my call)**

`https://fredvested.com/s?r=52-2048-07&utm_source=share&utm_medium=referral&utm_campaign=freedom_date`

- `r` is `<rounded age>-<yyyy>-<mm>`. Nothing else describes the result.
- The page builds the link from `location.origin`, so it works on dev and on prod. On prod it only makes sense once `main` carries the real site again (it is the maintenance page today).
- A query on a fixed path, not `/s/52-2048-07`: index.html uses relative asset URLs, which break one directory down (seen in the test: `/s/52/dist/output.css` returned 404).
- The UTM values follow `FREDdocs/utm-conventions.md`, so share-driven signups show up in the existing attribution columns and funnel view with no schema change.

## What serves it

**Cloudflare Pages worker** (`frontend/_worker.js`, still one dependency-free file):

- `_routes.json` gains `/s`. The rest of the site stays static.
- On `/s`: fetch the homepage from `env.ASSETS`, and if `r` is valid, use `HTMLRewriter` to set `og:title`, `og:description`, `og:url`, `og:image`, `og:image:width`, `og:image:height`, `og:image:alt`, the matching `twitter:*` tags, and add `<meta name="robots" content="noindex">`. index.html today has only `og:type/url/site_name/title/description/image` and four `twitter:*` tags, so the width, height and alt tags are appended, not rewritten. index.html has no canonical tag and this work does not add one.
- If `r` is missing or invalid, serve the homepage untouched (default tags).
- Valid means: age 18 to 100, month 1 to 12, date from the current month to 72 years out (the calculator's own bounds), and the current age the pair implies (age minus the years until the date) between 18 and 60, the age slider's range, with a year of tolerance for rounding. **(my call)** Anyone can type a link, so this keeps FRED from drawing an image the calculator could never produce ("free at 18" next month).
- Measured in the test at about 5 ms, inside the free plan's 10 ms.

Draft tags (attorney-pending):

- `og:title`: `I could be free at 52. Now your turn!` **(Andrew)** The bubble on the image still reads "What about you?".
- `og:description`: `Hypothetical illustration, not a prediction or guarantee. Find your own Freedom Date in 30 seconds.`
- `og:image:alt`: `I could be free at 52 years old in July 2048. What about you?`

**Backend** (Spring Boot): `GET /api/og/v1/{age}/{yyyy-mm}.png` **(my call)**

- Drawn with Java2D, the way `GrowthChartRenderer` draws the welcome email's chart. Same validation as the worker; invalid input is a 404.
- Needs static Montserrat Black and Inter Medium TTFs added to `backend/src/main/resources/fonts/` (OFL; only Inter Regular and Bold are there today) and the pig as a transparent PNG rasterised once from `design/pig$10k-$100k.svg`.
- The image for a given path never changes, so respond with `Cache-Control: public, max-age=31536000, immutable`. `v1` in the path is there so a redesign gets new URLs (platforms cache previews per URL).
- `og:image` points straight at the API origin (`https://lpapi.fredvested.com/...`; dev uses the dev origin). The worker reads the origin from an environment variable with the prod value as default. Only crawlers and message apps fetch it; the page itself never requests it, so the e2e third-party-host rule is unaffected.

Why not render in the worker: the test did it, but at about 75 ms of CPU per image against a 10 ms free-plan limit, plus a bundling step the site does not have.

## The recipient **(my call)**

- Sees the ordinary calculator at its defaults. No banner, no mention of the sharer's numbers, nothing prefilled.
- Before the Plausible script records the pageview, the page removes `r` from the address with `history.replaceState` (the pattern confirmed.html uses for `hours`), keeping the UTM values, so the age and date never reach analytics. The recorded path may be `/s` or `/`; builder's choice, documented in `analytics-events.md`.

## Analytics **(my call)**

One new event, `Result Shared`, fired when a sheet action is used, with `method` (`native`, `copy` or `story`) and `stake_years_band` / `stake_source` derived from the shared age (65 minus the age; `hidden` at 65 and later), not from the live render, which shows the default sliders on a return visit. Added to `analytics.js`, the events doc and its budget table, and as a Plausible goal. No age, no date.

## Compliance (deploy gate)

Andrew is taking this to counsel himself and it does not go live before that. Build it on dev, mark the new copy `ATTORNEY REVIEW PENDING 2026-11-13`. The one-screenshot summary for counsel is `docs/superpowers/specs/2026-11-13-share-freedom-date/counsel-board.png` (also the first panel of the artifact). Questions worth sending with it:

1. The approved kicker is "You could be free at". Is "I could be free at", written by FRED and shown to people who never used the calculator, acceptable?
2. The image shows a projected age and date with none of the assumptions, and the approved disclosure says "based on the assumptions shown". Is the shorter line on the image enough, must an assumption (the return scenario) appear, or is a personalised number not allowed at all?
3. Does a FRED-built share button plus a FRED-written preview title make the shared post FRED's advertisement, and does the image fall outside the interactive-tool treatment?
4. Is "What about you?" plus "Find your own Freedom Date in 30 seconds." acceptable as the invitation?
5. Should a result at 65 or later be shareable?
6. The story image carries the comparison ("Do nothing. 65+") and "13 years before the usual 65", which is new wording; the comparison copy on the page is itself still pending.

If counsel rules out a personalised number, the fallback is the same link with the default image and title; the share control, link, UTM tracking and event still work. That would be a follow-up change, not something to build now.

## Left to the builder

- Exact Java2D drawing code and how closely metrics match the reference; how the pig PNG is produced and at what resolution.
- How `monthYear` ("July 2048") from saved state becomes `yyyy-mm`.
- Clipboard fallback details and the "Link copied" treatment.
- Test layout. `frontend/serve.py` does not run the worker, so it needs `/s` mapped to index.html for the e2e suite; the tag rewriting is covered by the worker's unit test only. Expected coverage: backend renderer and endpoint validation; a unit test for the worker's parse/validate and tag values; e2e for the control (absent before signup, absent for at-target and no-date, link carries only `r` and UTM, `r` stripped before the pageview, 390 x 660 assertion still passes, new event schema).
- Doc updates: CLAUDE.md, `FREDdocs/analytics-events.md`, `FREDdocs/utm-conventions.md` if the share values need listing, `FREDdocs/go-live-checklist.md` (counsel gate, Plausible goal, worker env var).

## Not proven yet

- `HTMLRewriter` over the asset response was only run locally (`wrangler pages dev`), not on the deployed Pages project.
- How `navigator.share` behaves inside the Instagram and TikTok in-app browsers. The copy-link fallback covers the case where it is missing; worth one check on a real iPhone and Android phone.
