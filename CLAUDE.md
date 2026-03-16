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
- Google Fonts: Inter (body), Montserrat (logo)
- Calls backend API at `/api/waitlist` for form submission and stats

### Backend
- **`WaitlistController`** — REST endpoints: `GET /api/waitlist/stats` and `POST /api/waitlist`
- **`WaitlistEntry`** — JPA entity mapped to `waitlist_signups` table; status enum: `WAITLISTFOUNDER`, `WAITLISTNORMAL`, `INVITED`, `CLAIMED`, `DECLINED`
- **`TurnstileService`** — Verifies Cloudflare Turnstile tokens against `https://challenges.cloudflare.com/turnstile/v0/siteverify`
- **`RateLimiterService`** — In-memory sliding window rate limiter (3 requests/minute per IP hash); cleans up every 5 minutes via `@Scheduled`
- **`WebConfig`** — CORS configuration driven by `cors.allowed.origins` property
- IPs are SHA-256 hashed before storage; Cloudflare's `CF-Connecting-IP` header is used when present

### Profiles & Environment Variables

| Profile | Config file | Notes |
|---------|-------------|-------|
| `dev` | `application-dev.properties` | Uses `MYSQLHOST/PORT/DATABASE/USER/PASSWORD` env vars; CORS allows `http://127.0.0.1:5500`; uses Turnstile test key by default |
| `prod` | `application-prod.properties` | Deployed on Railway |

Required env vars for dev: `MYSQLHOST`, `MYSQLPORT`, `MYSQLDATABASE`, `MYSQLUSER`, `MYSQLPASSWORD`
Required for prod: above + `CLOUDFLARE_TURNSTILE_SECRET`, `CORS_ALLOWED_ORIGINS`

### Founder Cap Logic
The first 300 waitlist signups get `WAITLISTFOUNDER` status; subsequent signups get `WAITLISTNORMAL`.
