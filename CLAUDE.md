# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

FRED is a financial independence landing page with a waitlist signup. It has two parts:

- **`frontend/`** — Static HTML/CSS landing page using Tailwind CSS v4
- **`backend/`** — Spring Boot 3.5 REST API (Java 17) connected to a MySQL database

## Frontend Commands

```bash
cd frontend

# Compile CSS
npm run build
```

The frontend is a single `index.html` file. Tailwind styles are compiled from `src/input.css` to `dist/output.css`. There is no JS bundler — scripts are inline in `index.html`.

## Backend Commands

```bash
cd backend

# Run with dev profile (connects to MySQL via env vars, verbose logging)
./gradlew bootRun --args='--spring.profiles.active=dev'

# Run tests (slice and unit tests; no database needed)
./gradlew test

# Also run the real-MySQL integration test (WaitlistFunnelIntegrationTest: Flyway V1..Vn on an
# empty scratch schema, Hibernate validate, signup -> outbox -> confirm -> stats, webhook ordering
# and suppression). The schema is WIPED; point it at a scratch database, never a real one.
FRED_IT_JDBC_URL='jdbc:mysql://localhost:3306/fred_it?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC' \
FRED_IT_DB_USER=root FRED_IT_DB_PASSWORD=... ./gradlew test

# Build JAR
./gradlew build
```

## End-to-end tests (Playwright, repo root)

```bash
npm install && npx playwright install chromium   # once
npm run test:e2e                                 # starts frontend/serve.py itself; API and Plausible mocked in the browser
```

The suite lives in `tests/` (helpers.js is the harness). It fails on any request to a host other than our own origin and `challenges.cloudflare.com`, and covers the event schema, the double opt-in pending state, the confirmed page, the about-page wording and (`existing-invested.spec.js`) the "I already have invested" slider, its projection and the at-target result state. Turnstile is real (test sitekey on localhost), everything else is intercepted, so no backend runs.

## Architecture

### Frontend
- Single `index.html` with inline JavaScript
- Tailwind CSS v4 (no config file — uses CSS-based config in `src/input.css`)
- Fonts: Inter (body), Montserrat (logo), **self-hosted** from `frontend/fonts/` via `@font-face` in `src/input.css`. Do not add Google Fonts (or any third-party origin) back: the only vendors the privacy policy may name are Plausible, Resend, Cloudflare and Railway, so any other third-party request is a launch blocker. (Since 2026-10-06 `frontend/privacy.html` is counsel's final policy, rendered verbatim from `legal/privacy-content.html`; it names all four. `legal/` is the source of record for the privacy and terms text; change the fragment, never the page's words.) Icons are inline SVG sprites, not an icon font.
- Calls backend API at `/api/waitlist` for form submission (index.html) and `/api/waitlist/stats` (about.html's tiles, and index.html's list-size line: fetched on load and again after a signup, shown only at or above `STAT_TILE_MIN_N`; removed 2026-10-24, restored 2026-10-25 by Andrew's follow-up)
- **Post-reveal conversion (2026-10-24, reshaped 2026-10-25, polished 2026-10-26):** on index.html the result card itself carries, below the age, "years old", the "Freedom Date · Month Year" pill (`#result-chip`, the markup it had before 2026-10-25), the "do nothing vs this plan" comparison (`#compare`), the stakes line, the capture headline, sub line and the one waitlist form (`#join`), the list-size count line, the consent sentence, the payment / iOS note and one merged assumptions-plus-disclosure block. The comparison and the capture section are stamped out of `#post-reveal-template` by `mountPostReveal()` on the first reveal or on load for a saved signup state, never before, so no email input and no comparison exist in the DOM pre-reveal. The reveal and recalculate scroll the result card's top to 8px below the viewport top (instant under reduced motion); at 390 x 660 the email input and button are in view with no further scroll (phone top padding `pt-7` and `mt-3` steps under the pill were the 2026-10-26 trims). Every sentence is in the `COPY` block at the top of the inline script (attorney review pending; 2026-10-27 wording: "That gap is N years of your life." / "FRED exists to close the gap." / "Let FRED help me get there"). The submit button carries the pig from `design/pig$100k-$1m.svg` inlined (the source file moved out of `frontend/` on 2026-10-27 so it never deploys) (Illustrator data, ids and attribute styles stripped, coordinates rounded; the 1.4 MB file itself is untouched and never requested) and no arrow (it did not fit at 390px with the pig). Gone: the dark capture card (2026-10-24), the "N years before the usual 65" line (2026-10-25), the "what moves your date" card with its `Lever Viewed` goal and `lever_shift_band` / `delay_months_band` properties (2026-10-26, Andrew deleting them in Plausible); the scarcity pill of the old capture block stays out. The projection is `frontend/assets/projection.js` (`project()`, `leverScenario()`, `delayScenario()`, `doNothingScenario()` and the `stakes()` ladder, loaded as a classic script and as CommonJS for `tests/unit/`, run with `npm run test:unit`); `compute()` in index.html wraps `project()` and adds the calendar date. "Do nothing" is `project()` with `invest: 0, growthOnly: true` and every other input unchanged; a rounded age under 65 is shown, else "65+" counting as 65; `N` under 2 falls to the lever age ("This plan + $step/mo", `leverScenario()` still computed for that cell alone), then hides the comparison. Ages round with `roundAge()` (Math.round) everywhere. The `stake_years_band` / `stake_source` properties (on Freedom Date Revealed, Email Focused and Waitlist Submitted) are documented in `FREDdocs/analytics-events.md`.

### Backend
- **`WaitlistController`** — REST endpoints: `GET /api/waitlist/stats` and `POST /api/waitlist`
- **`WaitlistEntry`** — JPA entity mapped to `waitlist_signups` table; status enum: `WAITLISTED`, `INVITED`, `CLAIMED`, `DECLINED` (one list, no tiers since 2026-10-10 / V6; `WAITLISTFOUNDER` and `WAITLISTNORMAL` no longer exist anywhere)
- **`existing_invested`** (V7, 2026-10-22): stores the "I already have invested" calculator input (`existingInvested` in the signup payload, BIGINT NULL). `FreedomCalculator` and `index.html` `compute()` share the starting-balance formula (one scenario rate on everything, monthly compounding). The head-start statistic (`WaitlistRepository.HEAD_START_ROWS`) excludes rows with a nonzero value so the about-page disclosure ("no existing savings") stays true without re-approval. The at-target result state (existing amount >= portfolio target) has copy pending attorney approval before deploy.
- **`TurnstileService`** — Verifies Cloudflare Turnstile tokens against `https://challenges.cloudflare.com/turnstile/v0/siteverify`
- **`RateLimiterService`** — In-memory sliding window rate limiter (3 requests/minute per IP hash); cleans up every 5 minutes via `@Scheduled`
- **`WebConfig`** — CORS configuration driven by `cors.allowed.origins` property
- IPs are SHA-256 hashed before storage; Cloudflare's `CF-Connecting-IP` header is used when present

### Schema Migrations (Flyway)
- The database schema is owned by Flyway: `backend/src/main/resources/db/migration/V<n>__<description>.sql`. `V1__baseline.sql` is the `waitlist_signups` table as it existed when Flyway was introduced; every change since is a new numbered file. Never edit a migration that has been applied anywhere.
- `spring.jpa.hibernate.ddl-auto=validate` in **every** profile (base default, dev, prod, local). Hibernate only checks the entities against the schema on startup; it never creates or alters tables.
- **Any entity change without a matching migration will crash the app on startup by design** (`Schema-validation: missing column [...]`). That crash is the feature: it replaces a silent auto-migration with a loud failure. The fix is always a new migration, never switching `ddl-auto` to `update` -- `update` would mutate the database outside version control and leave the Flyway history lying about what the schema is. (This happened once: Railway dev, 2026-09-22.)
- `spring.flyway.baseline-on-migrate=true` / `baseline-version=1`: a pre-Flyway database that already has the table is recorded as "at V1" without running V1. Because such databases may predate columns V1 assumes, migrations that add columns those databases might lack must be written idempotently (see `V2__add_calculator_input_columns.sql` for the information_schema + PREPARE pattern; MySQL has no `ADD COLUMN IF NOT EXISTS`).
- **Before deploying any migration, dry-run it against a snapshot of the TARGET environment's schema, never against local.** Local is usually ahead of what is deployed; that is exactly how V1's baseline was wrong (it captured the working tree while Railway dev was still five columns behind, and the deploy crashed). Recipe, per environment (dev, then prod):
  1. Railway → the target project → the MySQL service → **Variables**: copy the public connection values (`MYSQL_PUBLIC_URL`, or `RAILWAY_TCP_PROXY_DOMAIN` + `RAILWAY_TCP_PROXY_PORT` with `MYSQLUSER` / `MYSQLPASSWORD` / `MYSQLDATABASE`). Also take a Railway backup of the database volume first (service → **Backups**), so the deploy itself is reversible.
  2. Schema-only dump plus the Flyway history: `mysqldump -h HOST -P PORT -u USER -p --no-data DATABASE > snapshot-schema.sql` and `mysqldump -h HOST -P PORT -u USER -p DATABASE flyway_schema_history > snapshot-history.sql` (the second one fails harmlessly on a database Flyway has never touched -- skip it then). No row data is needed or wanted.
  3. Load it locally: `mysql -u root -p -e "DROP DATABASE IF EXISTS fred_snapshot; CREATE DATABASE fred_snapshot"`, then `mysql -u root -p fred_snapshot < snapshot-schema.sql` and `mysql -u root -p fred_snapshot < snapshot-history.sql`.
  4. Boot against it with the code you are about to deploy: `./gradlew bootRun --args='--spring.profiles.active=local --spring.datasource.url=jdbc:mysql://localhost:3306/fred_snapshot?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC'`. Expect Flyway to log exactly the migrations the target is missing, then `Started WaitlistApplication`. `APPLICATION FAILED TO START` with `Schema-validation` means the migration does not produce what the entities expect: fix the migration, never the setting.
  5. `mysql -u root -p -e "DROP DATABASE fred_snapshot"`. Then deploy, and watch the Railway log for the same Flyway lines.
- For a quick local check without a snapshot (not a substitute for the above): create a scratch database (`mysql -u root -p -e "CREATE DATABASE fred_scratch"`), boot against it the same way, and drop it afterward.

### Profiles & Environment Variables

| Profile | Config file | Notes |
|---------|-------------|-------|
| `dev` | `application-dev.properties` | Uses `MYSQLHOST/PORT/DATABASE/USER/PASSWORD` env vars; CORS allows `http://127.0.0.1:5500`; uses Turnstile test key by default |
| `prod` | `application-prod.properties` | Deployed on Railway |

Required env vars for dev: `MYSQLHOST`, `MYSQLPORT`, `MYSQLDATABASE`, `MYSQLUSER`, `MYSQLPASSWORD`
Required for prod: above + `CLOUDFLARE_TURNSTILE_SECRET`, `CORS_ALLOWED_ORIGINS`, `RESEND_API_KEY`, `RESEND_WEBHOOK_SECRET`
Optional (defaults in `application*.properties`): `WAITLIST_DOUBLE_OPT_IN` (true), `API_PUBLIC_URL` (the API's own origin, used in email links), `EMAIL_FROM`, `POSTAL_ADDRESS` (rendered in every email footer; the visible placeholder `[PO Box pending]` until set in dev/local; REQUIRED in prod), `WAITLIST_US_ONLY`

`API_PUBLIC_URL` must be the https origin (`https://lpapi-dev.fredvested.com`, `https://lpapi.fredvested.com`). If it is given as `http://` for a non-local host, `EmailOutboxPublisher` upgrades emailed links to https and logs a WARN (Railway dev had it as http on 2026-09-24: the phone mail client never followed the edge's 301, and the single-use token had crossed the network in cleartext). Local hosts (localhost, 127.0.0.1, LAN ranges) keep http.

A variable that exists but is blank is treated as unset (`BlankEnvironmentVariables`, an `EnvironmentPostProcessor`): the default applies and startup logs a WARN naming the variable. Spring's own `${VAR:default}` only falls back when the variable is absent, and a blank boolean took Railway dev down on 2026-09-24. Variables without a default (`CLOUDFLARE_TURNSTILE_SECRET`, the MySQL ones, prod's `RESEND_WEBHOOK_SECRET`) still fail startup when blank, on purpose.

### Email funnel (double opt-in)
- Signup writes the waitlist row and a pending `email_message` row in one transaction; `EmailOutboxPublisher` (scheduled, single instance) sends it via Resend, refusing suppressed addresses, and mints the confirmation/unsubscribe tokens at send time so only their SHA-256 hashes are ever stored. A confirmation click (`SignupService.confirm`) queues the welcome email (`You're in`) in the same transaction; with the flag off the welcome goes out at signup instead of a confirmation.
- Both emailed links are two-step so mail security scanners (which GET every link) cannot act on them. `GET /api/waitlist/confirm?token=` renders a self-contained page whose inline script POSTs the token on load (scanners almost never run JavaScript; a consumed token is recoverable through resend), with a visible button inside `<noscript>` as the only other control. `GET /api/waitlist/unsubscribe?token=` renders a button and NO script, deliberately: a suppression is never cleared, so a sandbox that does run JavaScript must not be able to unsubscribe someone on delivery. No `List-Unsubscribe` headers on any email (Andrew, 2026-09-25: mail clients label such messages as list mail); the footer link is the opt-out. `POST /unsubscribe` still honours an RFC 8058 one-click body (`List-Unsubscribe=One-Click`, answered 200 with no redirect) so the header pair can return per template if volume ever nears the Gmail/Yahoo threshold (about 5,000 a day). The confirm and unsubscribe pages POST back to the API's own origin: `server.forward-headers-strategy=framework` makes that same-origin behind the edge, and `api.public-url` is on the CORS allow-list as a second line. `POST /confirm` confirms once and redirects to the landing site's `/confirmed` page (`status=confirmed&hours=<band>` | `expired` | `invalid`; no tier parameter); unknown and already-used tokens are byte-identical. `POST /api/waitlist/resend-confirmation` is rate limited per address (1/10 min, 3/day) and answers identically whether or not the address exists.
- Re-submitting an address that is on the list but unconfirmed (double opt-in) is answered exactly like a fresh signup (`status=WAITLISTED`, `requiresConfirmation=true`, no `realStatus`) and queues a fresh confirmation when the address limiter allows; only a confirmed duplicate gets `already_joined` + `realStatus`.
- The confirmation email is consent-only (counsel, 2026-09-24): why they are receiving it, the link, the expiry, the unsubscribe link and the postal address, nothing promotional (`EmailTemplatesTest` holds the deny-list). Every email carries the unsubscribe link and `POSTAL_ADDRESS`, which prod REQUIRES (no default: startup fails without it, so the `[PO Box pending]` placeholder can never reach a real recipient).
- `POST /api/webhooks/resend` verifies the Svix signature over the raw body, rejects stale timestamps, is idempotent on `svix-id` (UNIQUE on `email_event`), ignores older events for a message that already has a newer status, and suppresses on bounce/complaint. Unknown message ids get a 200 and are ignored. **Opens are not tracked**: open tracking is off on the sending domain, `email.opened` is not subscribed, and if one arrives anyway it (like any type outside `EmailEventProcessor.HANDLED`) is answered 200 and never stored. Click tracking stays (record only, with the click block stripped).
- The public statistic and the funnel count **confirmed rows only** (`confirmed_at IS NOT NULL`). `confirmed_source` says how: `double_opt_in` (clicked the link), `legacy` (pre double opt-in, backfilled by V5 with `confirmed_at = created_at`; they received the old welcome email), `single_opt_in` (flag off). Confirmation never changes a row's status: it only sets `confirmed_at`/`confirmed_source` and queues the welcome email. `SignupService` publishes `WaitlistCountsChanged` on every confirmation and single-opt-in signup; `WaitlistController` drops its `/stats` memo on it.
- Weekly view: `v_waitlist_funnel` (with the confirmed split by source); suppressions: `v_email_suppressions`.

### Founder Access (one waitlist, no tiers)
Founder decision, 2026-10-10: there is no founder tier at signup and no "priority waitlist". Everyone who confirms is `WAITLISTED` on one list, and `/stats` carries no `founderCount`. Founder Access invites will later go out in the order people joined (`created_at`), as long as the 300 founding spots remain; `INVITED` / `CLAIMED` / `DECLINED` are that invite workflow and are never set by signup or confirmation. `V6__single_waitlist_status.sql` folded the old `WAITLISTFOUNDER` / `WAITLISTNORMAL` rows into `WAITLISTED` (widen enum, update, narrow enum, so a still-running old build never writes an invalid value mid-migration).
