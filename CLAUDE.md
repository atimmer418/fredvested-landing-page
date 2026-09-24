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

# Run tests
./gradlew test

# Build JAR
./gradlew build
```

## Architecture

### Frontend
- Single `index.html` with inline JavaScript
- Tailwind CSS v4 (no config file — uses CSS-based config in `src/input.css`)
- Fonts: Inter (body), Montserrat (logo), **self-hosted** from `frontend/fonts/` via `@font-face` in `src/input.css`. Do not add Google Fonts (or any third-party origin) back: the privacy policy names only Plausible, Resend, Cloudflare and Railway as vendors, so any other third-party request is a launch blocker. Icons are inline SVG sprites, not an icon font.
- Calls backend API at `/api/waitlist` for form submission and stats

### Backend
- **`WaitlistController`** — REST endpoints: `GET /api/waitlist/stats` and `POST /api/waitlist`
- **`WaitlistEntry`** — JPA entity mapped to `waitlist_signups` table; status enum: `WAITLISTFOUNDER`, `WAITLISTNORMAL`, `INVITED`, `CLAIMED`, `DECLINED`
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
Optional (defaults in `application*.properties`): `WAITLIST_DOUBLE_OPT_IN` (true), `API_PUBLIC_URL` (the API's own origin, used in email links), `EMAIL_FROM`, `WAITLIST_US_ONLY`

A variable that exists but is blank is treated as unset (`BlankEnvironmentVariables`, an `EnvironmentPostProcessor`): the default applies and startup logs a WARN naming the variable. Spring's own `${VAR:default}` only falls back when the variable is absent, and a blank boolean took Railway dev down on 2026-09-24. Variables without a default (`CLOUDFLARE_TURNSTILE_SECRET`, the MySQL ones, prod's `RESEND_WEBHOOK_SECRET`) still fail startup when blank, on purpose.

### Email funnel (double opt-in)
- Signup writes the waitlist row and a pending `email_message` row in one transaction; `EmailOutboxPublisher` (scheduled, single instance) sends it via Resend, refusing suppressed addresses, and mints the confirmation/unsubscribe tokens at send time so only their SHA-256 hashes are ever stored.
- `GET /api/waitlist/confirm?token=` confirms once and redirects to the landing site's `/confirmed` page (`status=confirmed|expired|invalid`); unknown and already-used tokens are indistinguishable. `POST /api/waitlist/resend-confirmation` is rate limited per address (1/10 min, 3/day) and answers identically whether or not the address exists. `GET /api/waitlist/unsubscribe?token=` suppresses the address.
- `POST /api/webhooks/resend` verifies the Svix signature over the raw body, rejects stale timestamps, is idempotent on `svix-id` (UNIQUE on `email_event`), ignores older events for a message that already has a newer status, and suppresses on bounce/complaint. Unknown message ids get a 200 and are ignored.
- Weekly view: `v_waitlist_funnel`; suppressions: `v_email_suppressions`.

### Founder Cap Logic
The first 300 waitlist signups get `WAITLISTFOUNDER` status; subsequent signups get `WAITLISTNORMAL`.
