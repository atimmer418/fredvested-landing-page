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
- To test a migration locally against a real MySQL: create a scratch database (`mysql -u root -p -e "CREATE DATABASE fred_scratch"`), then `./gradlew bootRun --args='--spring.profiles.active=local --spring.datasource.url=jdbc:mysql://localhost:3306/fred_scratch?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC'` and watch for `Started WaitlistApplication` (pass) or `APPLICATION FAILED TO START` (fail). Drop the scratch database afterward.

### Profiles & Environment Variables

| Profile | Config file | Notes |
|---------|-------------|-------|
| `dev` | `application-dev.properties` | Uses `MYSQLHOST/PORT/DATABASE/USER/PASSWORD` env vars; CORS allows `http://127.0.0.1:5500`; uses Turnstile test key by default |
| `prod` | `application-prod.properties` | Deployed on Railway |

Required env vars for dev: `MYSQLHOST`, `MYSQLPORT`, `MYSQLDATABASE`, `MYSQLUSER`, `MYSQLPASSWORD`
Required for prod: above + `CLOUDFLARE_TURNSTILE_SECRET`, `CORS_ALLOWED_ORIGINS`

### Founder Cap Logic
The first 300 waitlist signups get `WAITLISTFOUNDER` status; subsequent signups get `WAITLISTNORMAL`.
