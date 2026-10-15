# Resend Email System — Design Spec
**Date:** 2026-03-26
**Status:** Approved

---

## Overview

Integrate Resend into the Spring Boot backend to:
1. Send a one-time batch confirmation email to all existing `waitlist_signups` entries (temporary admin endpoint, removed after use)
2. Automatically send a confirmation email on every new successful waitlist signup going forward

From address: `fred@fredvested.com`
Resend API key env var: `RESEND_API_KEY`

---

## Email Content

**Subject:** You're in

**Body (plain intent, rendered as HTML):**

> You're on the **FRED private beta waitlist**.
>
> A few early signups may not have received a confirmation email, so I wanted to make sure you got one now.
>
> FRED is built to help you automatically invest part of every paycheck so you can clock out early — for good.
>
> **What happens next**
> — Signups are being reviewed in waves
> — If invited, you'll get an email with next steps to claim access
> — Access is limited — you'll have 48 hours to claim your spot when invited
>
> You don't need to do anything else right now — you're in line.
>
> Thanks,
> The FRED Team

---

## HTML Template Styling

Uses inline CSS only (required for email clients). No external stylesheets.

| Element | Style |
|---------|-------|
| Background | `#f6f6f8` (FRED background-light) |
| Card container | White, `1px solid #e2e8f0` border, `border-radius: 8px`, max-width 600px |
| Header | `#0F172A` (navy) background, `3px solid #135bec` bottom border |
| FRED wordmark | Georgia serif, italic, bold, `#135bec`, ~42px |
| Body text | Inter/Helvetica stack, `#334155`, 15px, 1.7 line-height |
| Section label ("What happens next") | Uppercase, `#64748B`, 14px, 0.05em letter-spacing |
| Bullet dashes | `#135bec` bold em-dash (`—`) |
| Footer | `#f8fafc` background, `border-top: 1px solid #e2e8f0`, "The FRED Team" + `fred@fredvested.com` |
| Below-card note | 12px, `#94a3b8`, centered: "You're receiving this because you signed up at fredvested.com" |

No unsubscribe link (private beta context).

---

## Architecture

### New Files

#### `EmailService.java`
- Package: `com.fredvested.web.service`
- `@Service`
- `@Value("${resend.api-key}")` injects the API key
- Single public method: `sendConfirmationEmail(String toEmail)`
  - Instantiates `Resend` client with API key
  - Builds `CreateEmailOptions` with from, to, subject, and HTML
  - Calls `resend.emails().send(params)`
  - Throws `RuntimeException` on failure (callers decide how to handle)
- Private method `buildHtmlEmail()` returns the full inline-CSS HTML string

#### `AdminController.java` *(TEMPORARY — delete after batch send)*
- Package: `com.fredvested.web.controller`
- `@RestController`, `@RequestMapping("/api/admin")`
- `@Value("${admin.secret}")` injected secret for auth
- `POST /api/admin/batch-email`
  - Reads `X-Admin-Secret` request header; returns 403 if it doesn't match `${admin.secret}`
  - Calls `repository.findAll()` to get all entries
  - Iterates: for each entry with a non-null, non-empty email, calls `emailService.sendConfirmationEmail(email)` inside a try/catch — logs errors and continues
  - Returns JSON: `{ "sent": N, "skipped": M, "errors": K }`
- After triggering once in production, this file is deleted and the service is redeployed

### Modified Files

#### `WaitlistController.java`
- After `repository.save(entry)` (step 4), add:
  ```java
  try {
      emailService.sendConfirmationEmail(email);
  } catch (Exception e) {
      // log but do not fail the signup response
  }
  ```
- Inject `EmailService` via `@Autowired`

#### `build.gradle`
Add dependency:
```groovy
implementation 'com.resend:resend-java:3.1.0'
```

#### `application-dev.properties`
```properties
resend.api-key=${RESEND_API_KEY:re_test_placeholder}
admin.secret=${ADMIN_SECRET:dev-secret}
```

#### `application-prod.properties`
```properties
resend.api-key=${RESEND_API_KEY}
admin.secret=${ADMIN_SECRET}
```

---

## Environment Variables

| Variable | Where set | Purpose |
|----------|-----------|---------|
| `RESEND_API_KEY` | Railway (prod) + local `.env` (dev) | Resend API authentication |
| `ADMIN_SECRET` | Railway (prod) + local `.env` (dev) | Protects the batch-email endpoint |

---

## Error Handling

| Scenario | Behavior |
|----------|----------|
| Resend fails on new signup | Caught silently; signup still succeeds and returns normal response |
| Resend fails on a batch entry | Caught per-entry; logged; counter incremented; loop continues |
| Invalid `X-Admin-Secret` on batch endpoint | Returns `403 Forbidden` immediately |
| Entry has null/empty email | Skipped, counted in `skipped` |

---

## Batch Send Process (One-Time)

1. Set `RESEND_API_KEY` and `ADMIN_SECRET` as Railway env vars
2. Deploy the build that includes `AdminController`
3. Trigger via curl (replace `<your-prod-api-url>` with your Railway API domain):
   ```bash
   curl -X POST https://<your-prod-api-url>/api/admin/batch-email \
     -H "X-Admin-Secret: <your-secret>"
   ```
4. Verify the JSON response (`sent`, `skipped`, `errors` counts)
5. Delete `AdminController.java`, redeploy

---

## Out of Scope

- Unsubscribe/opt-out mechanism (not needed for private beta)
- Email open/click tracking
- Retry logic for failed sends
- First name personalization (not collected)
