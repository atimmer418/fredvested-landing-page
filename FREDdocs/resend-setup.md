# Resend setup

Written 2026-09-24 against the working tree of `develop`. Everything in the "what the code expects" section is read from the code; the dashboard, DNS and Railway steps are things only Andrew can do, and where a step is a choice rather than a code requirement it says so. Where a Resend or Cloudflare screen label is uncertain, the step is described by purpose.

## What the code expects

| Need | Where in code | Property | Environment variable and default |
|---|---|---|---|
| API key | `backend/src/main/java/com/fredvested/web/service/EmailService.java`, constructor `@Value("${resend.api-key}")` | `resend.api-key` | dev: `${RESEND_API_KEY:re_test_placeholder}` (`application-dev.properties`); prod: `${RESEND_API_KEY}`, no default (`application-prod.properties`); the base `application.properties` does not define it |
| From address | `EmailService` constructor `@Value("${email.from:FRED <fred@fredvested.com>}")` | `email.from` | `${EMAIL_FROM:FRED <fred@fredvested.com>}` (`application.properties`) |
| Webhook endpoint | `backend/src/main/java/com/fredvested/web/controller/ResendWebhookController.java`, `@PostMapping("/api/webhooks/resend")` | n/a | n/a |
| Webhook signing secret | `backend/src/main/java/com/fredvested/web/service/ResendWebhookVerifier.java`, `@Value("${resend.webhook-secret:}")` | `resend.webhook-secret` | base: `${RESEND_WEBHOOK_SECRET:}` (empty = every delivery answered 401); prod: `${RESEND_WEBHOOK_SECRET}`, no default |
| Event types handled | `backend/src/main/java/com/fredvested/web/service/EmailEventProcessor.java`, `HANDLED` | n/a | n/a |
| Link base for emailed links | `backend/src/main/java/com/fredvested/web/service/EmailOutboxPublisher.java`, `@Value("${api.public-url:http://localhost:8081}")` | `api.public-url` | base: `${API_PUBLIC_URL:http://localhost:8081}`; dev: `${API_PUBLIC_URL:https://lpapi-dev.fredvested.com}`; prod: `${API_PUBLIC_URL:https://lpapi.fredvested.com}` |
| Postal address in every footer | `EmailOutboxPublisher`, `@Value("${email.postal-address:[PO Box pending]}")` | `email.postal-address` | base: `${POSTAL_ADDRESS:[PO Box pending]}` (`application.properties`); prod: `${POSTAL_ADDRESS}`, no default (`application-prod.properties`), so the placeholder can only ever appear in dev or local |
| Landing site for redirects | `backend/src/main/java/com/fredvested/web/service/LandingUrls.java`, `@Value("${landing.url:https://fredvested.com}")` | `landing.url` | dev: `${LANDING_URL:https://develop.fredvested-landing-page.pages.dev}`; prod: not set in the profile, so the code default `https://fredvested.com` applies |
| Double opt-in flag | `backend/src/main/java/com/fredvested/web/service/SignupService.java`, `@Value("${waitlist.double-opt-in.enabled:true}")` | `waitlist.double-opt-in.enabled` | `${WAITLIST_DOUBLE_OPT_IN:true}` |
| CORS | `backend/src/main/java/com/fredvested/web/config/WebConfig.java`, `@Value("${cors.allowed.origins}")` | `cors.allowed.origins` | base: `${CORS_ALLOWED_ORIGINS:http://localhost:3000}`; dev: `${CORS_ALLOWED_ORIGINS:http://127.0.0.1:5500,https://*.fredvested-landing-page.pages.dev}`; prod: `${CORS_ALLOWED_ORIGINS}`, no default |

The SDK is `com.resend:resend-java:3.1.0` (`backend/build.gradle`). `EmailService.send(to, subject, html, text, headers)` sets `from`, `to`, `subject`, `html`, `text` and, when the map is not empty, `headers` on the Resend request. `EmailOutboxPublisher.publish` passes the same two headers on every email, the RFC 8058 one-click unsubscribe pair: `List-Unsubscribe: <unsubscribe url>` (the same per-email token as the footer link) and `List-Unsubscribe-Post: List-Unsubscribe=One-Click`. `EmailService.options` also sets Reply-To on every message from `email.reply-to` (`EMAIL_REPLY_TO`, default `help@fredvested.com`), so replies never land on the sending subdomain. Nothing sets tags.

How a send happens, in short:

1. `SignupService.createSignup` writes the `waitlist_signups` row and a `pending` row in `email_message` (template `waitlist_confirmation`, or `waitlist_welcome` when the flag is off) in one transaction. Nothing calls Resend on the request thread.
2. `EmailOutboxPublisher.publishPending` runs on a schedule: first run 10 s after boot, then every 5 s (`email.outbox.poll-ms=5000`), 25 rows per run. It claims the row, refuses suppressed addresses (`suppressed`), skips a confirmation whose address has confirmed meanwhile (`skipped`), mints the confirmation and unsubscribe tokens (only SHA-256 hashes are stored), renders the email from `EmailTemplates`, calls Resend with the two `List-Unsubscribe` headers and records the returned message id in `email_message.resend_email_id`.
3. A failed send backs off exponentially (1, 2, 4 ... minutes, capped at 6 h) for up to `email.outbox.max-attempts=12` attempts, then the row is `failed` and `waitlist_signups.email_status` is `failed`. The eleven waits between the twelve attempts add up to 1,231 minutes, about 20.5 hours (`EmailOutboxPublisher.backoff`; the comment on `email.outbox.max-attempts` in `application.properties` says the same, about 20 hours).
4. Resend reports back through the webhook; `EmailEventProcessor` records every verified event of a handled type (the `HANDLED` set, section 4) in `email_event` and updates `email_message.status` and `waitlist_signups.email_status`, suppressing the address on a hard bounce or a complaint. A verified event of any other type is answered 200 and not stored.

Timestamps: the app writes Eastern wall-clock `LocalDateTime` values (`ZoneId.of("America/New_York")` in `EmailOutboxPublisher`, `EmailEventProcessor`, `SignupService`, `ResendWebhookController`). `email_event.occurred_at` is Resend's `created_at` converted to Eastern. Do not mix `NOW()` into these columns from raw SQL.

## Current state before this setup (not from code)

A note from an earlier session (2026-09-16) says the root domain `fredvested.com` is already verified in Resend (a `resend._domainkey` DKIM TXT record and the `send` subdomain MX/TXT for bounces), that Zoho handles inbound mail on the root domain, and that the send address has been `fred@fredvested.com`. None of that is visible in the repository. Check the Resend Domains page and Cloudflare DNS before changing anything, and treat the steps below as "add the subdomain alongside, switch, then retire the root entry".

## 1. Domain authentication on a sending subdomain

### Why a subdomain

Mailbox providers score reputation per sending domain (and per DKIM `d=` domain). If the waitlist stream ever draws complaints, a bad list import, or a bounce spike, a subdomain such as `mail.fredvested.com` takes the hit while `fredvested.com`, which carries the help@ mailbox and any person-to-person mail, keeps its own record. It also keeps Resend's records out of the root's DNS: the root SPF record stays whatever the inbound provider needs, and the root DMARC policy can be tightened independently of the app's stream. The code does not care which domain is used; it only needs `EMAIL_FROM` to be an address Resend will accept. Recommendation: `mail.fredvested.com`. Using one sending domain for both dev and prod (two API keys, two webhooks) is the simplest arrangement; a separate dev subdomain doubles the DNS work for a handful of test sends. Both are Andrew's decisions.

### 1a. Add the domain in Resend

1. Resend dashboard, left menu, **Domains**, **Add Domain**.
2. Enter `mail.fredvested.com`. Pick the region (the MX hostname Resend shows depends on it; for a US audience the US East option is the natural choice, Andrew's call).
3. Resend shows a list of DNS records with a status of "not started" or "pending". Leave the page open.

### 1b. The DNS records Resend asks for

Copy every value from the Resend page, not from this table; the table gives the shape as last seen, and the exact MX hostname and DKIM key come only from the dashboard.

| Purpose | Type | Name as Resend shows it (relative to `mail.fredvested.com`) | Name to type in Cloudflare (zone `fredvested.com`) | Value |
|---|---|---|---|---|
| DKIM signing key | TXT | `resend._domainkey` | `resend._domainkey.mail` | `p=MIGf...` (the long public key string, paste exactly) |
| Return-Path (bounces) | MX | `send` | `send.mail` | `feedback-smtp.<region>.amazonses.com`, priority `10` |
| Return-Path SPF | TXT | `send` | `send.mail` | `v=spf1 include:amazonses.com ~all` |
| DMARC policy (Resend lists it as recommended) | TXT | `_dmarc` | `_dmarc.mail` | see 1d |

Resend has historically presented DKIM as a single TXT record with selector `resend`. If the dashboard instead shows CNAME records (some providers do for DKIM or for a tracking domain), add them as CNAMEs and see the proxy note below.

### 1c. Add them in Cloudflare DNS

1. `dash.cloudflare.com`, the `fredvested.com` zone, **DNS**, **Records**, **Add record**.
2. For each row above: choose the Type, type the Name from the "Name to type in Cloudflare" column (Cloudflare appends `.fredvested.com` itself; typing the full hostname also works, but never type `...fredvested.com` into a field that already appends it, or the record lands at `...fredvested.com.fredvested.com`), paste the value into Content (TXT) or Mail server plus Priority (MX), leave TTL on Auto, Save.
3. Proxy status: TXT and MX records have no proxy toggle. If Resend asks for any CNAME, set its proxy status to **DNS only** (grey cloud). A proxied CNAME resolves to Cloudflare's IPs instead of the target hostname, and the DKIM or tracking lookup behind it fails.
4. Do not touch the root domain's existing records (`fredvested.com` MX, root SPF TXT) for this: the sending subdomain is self-contained.

Check from a terminal once saved (Cloudflare usually answers within a minute):

```
dig +short TXT resend._domainkey.mail.fredvested.com
dig +short MX  send.mail.fredvested.com
dig +short TXT send.mail.fredvested.com
dig +short TXT _dmarc.mail.fredvested.com
```

### 1d. DMARC

Add a TXT record at `_dmarc.mail` with a monitoring-only policy first:

```
v=DMARC1; p=none; rua=mailto:dmarc@fredvested.com
```

- `p=none` asks receivers to deliver as usual and only report. Aggregate reports (`rua`) arrive daily as XML attachments from Google, Microsoft, Yahoo and others; they show which servers sent as `mail.fredvested.com` and whether SPF and DKIM aligned.
- `dmarc@fredvested.com` must be a mailbox or alias Andrew can read (a Zoho alias forwarding to help@, or a dedicated address). Because `fredvested.com` and `mail.fredvested.com` share the same organizational domain, no extra "external destination" authorization record is needed for the report address. Cloudflare also offers a DMARC Management feature under the zone's **Email** section that supplies its own report address and renders the reports; using it instead of a mailbox is Andrew's choice.
- Alignment stays at the default relaxed mode (`adkim=r; aspf=r`), so `send.mail.fredvested.com` (SPF) and `mail.fredvested.com` (DKIM `d=`) both align with a From address on `mail.fredvested.com`.
- After two to four weeks of reports that show only Resend sending as this domain, change `p=none` to `p=quarantine` (optionally ramping with `pct=25`, `pct=50`, `pct=100`), and later to `p=reject`. Each change is one edit to the same TXT record.
- Interplay with the root: if `_dmarc.fredvested.com` exists, its `sp=` (or its `p=` when `sp=` is absent) governs subdomains until `_dmarc.mail.fredvested.com` exists. The subdomain record makes the policy explicit. Whether the root gets its own DMARC record, and when it tightens, depends on how Zoho's outbound mail is signed; that is outside this document and listed under open questions.

### 1e. Verify in Resend

Back on the Resend domain page, click the verify action (a button whose purpose is "check DNS records now"). Each record flips to verified as Resend sees it; the domain status becomes **Verified** when all required records pass. If a record stays pending after a few minutes, re-check the record name (the doubled-zone mistake above is the usual cause) and the value (a truncated DKIM key). The DMARC record is not required for verification.

## 2. The From address

- The address comes from `EMAIL_FROM`, default `FRED <fred@fredvested.com>` (`email.from` in `application.properties`, read by `EmailService`). The value is passed to Resend as the `from` field exactly as written, so the `Name <address>` form is correct.
- Resend only accepts a From address on a domain that shows as Verified in its Domains page. With the sending subdomain, set on both Railway services:

  ```
  EMAIL_FROM=FRED <fred@mail.fredvested.com>
  ```

  Leave the display name `FRED`; only the domain part changes. If Andrew keeps the root domain verified and sends from it, `EMAIL_FROM` can stay unset.
- Replies: every message carries `Reply-To: help@fredvested.com` (`email.reply-to`, env `EMAIL_REPLY_TO`; decided 2026-09-25), so a recipient who hits Reply writes to the help mailbox even when the From address is on the sending subdomain and has no mailbox behind it. The email body and footer name the same address.

## 3. API keys

1. Resend dashboard, **API Keys**, create a key.
2. Name it for the environment (`railway-dev`, `railway-prod`): one key per environment so either can be revoked alone.
3. Permission: the sending-only option (Resend calls it "Sending access"; the alternative "Full access" also manages domains and keys, which the app never does). Domain restriction: limit the key to the sending domain if the form offers it.
4. The key (`re_...`) is shown once. Copy it straight into Railway: the project, the backend service, **Variables**, `RESEND_API_KEY`, then redeploy if Railway does not do it automatically.

What happens when it is wrong or missing:

- dev: the default `re_test_placeholder` lets the API start. Every send then fails, `email_message.last_error` holds the Resend error, the row retries on the backoff schedule, and after 12 attempts it is `failed`. A signup is never lost, but its email is delayed by however long the key stays wrong (retries continue for about 20 hours, the sum of the backoff intervals; a row that has reached `failed` is not retried and needs a resend request).
- prod: `resend.api-key=${RESEND_API_KEY}` has no default, so the API does not start without it. A wrong key starts fine and fails at the first send, exactly as in dev.
- `EmailService` builds the Resend client at startup and does not validate the key until the first send, so the Railway boot log says nothing about a bad key.

## 4. Webhooks

One webhook per environment, each with its own signing secret.

| Environment | Endpoint URL |
|---|---|
| dev | `https://lpapi-dev.fredvested.com/api/webhooks/resend` |
| prod | `https://lpapi.fredvested.com/api/webhooks/resend` |

1. Resend dashboard, **Webhooks**, add a webhook, paste the endpoint URL.
2. Subscribe exactly these event types (the `HANDLED` set in `EmailEventProcessor`):

   `email.sent`, `email.delivered`, `email.delivery_delayed`, `email.bounced`, `email.complained`, `email.failed`, `email.suppressed`, `email.clicked`

3. Do **not** subscribe `email.opened` (counsel, 2026-09-24: opens are not tracked) nor any `contact.*` or `domain.*` event. Anything outside `HANDLED` is answered 200 and dropped before it touches the database (`Outcome.IGNORED`), so a stray subscription does no harm to the data, but it is still a request per event and, for `email.opened`, the thing counsel said not to collect.
4. Open the new webhook's detail page and copy its signing secret (`whsec_...`) into the matching Railway service as `RESEND_WEBHOOK_SECRET`. The dev webhook's secret goes to the dev service, prod's to prod; they are different values.
5. Repeat for the second environment.

What each event does in the code (`EmailEventProcessor.apply`):

| Event | Effect |
|---|---|
| `email.sent` | `email_message.status` = `sent`, `waitlist_signups.email_status` = `sent` |
| `email.delivered` | status `delivered` |
| `email.delivery_delayed` | recorded in `email_event` only |
| `email.bounced` | status `bounced`; the address is suppressed with `suppression_reason` = `hard_bounce` unless `data.bounce.type` is `Transient` |
| `email.complained` | status `complained`; suppressed with `complaint` |
| `email.failed` | status `failed` |
| `email.suppressed` | suppressed with `manual`; message status unchanged |
| `email.clicked` | recorded only; the `data.click` block (clicked URL, IP, user agent) is stripped before storage |
| `email.opened`, `contact.*`, anything else | 200, never stored |

Status-changing events for one message are applied only if their `created_at` is not older than `email_message.last_event_at`, so a late `email.sent` cannot overwrite a `delivered`. Stored payloads have `data.html`, `data.text` and `data.click` removed.

How the endpoint answers (`ResendWebhookController`, `ResendWebhookVerifier`):

| Condition | Response |
|---|---|
| Secret unset, or not valid base64 after the `whsec_` prefix | 401 `{"code":"unauthorized","message":"Invalid webhook signature."}` for every delivery, plus a WARN at startup: `resend.webhook-secret is not set or is not valid base64 (expected the dashboard's whsec_... value): every webhook delivery will be rejected with 401` |
| Missing `svix-id`, `svix-timestamp` or `svix-signature`, or no `v1,` signature matches HMAC-SHA256 over `{svix-id}.{svix-timestamp}.{raw body}` | 401, same body |
| Signature valid but `svix-timestamp` more than 300 s from now (`TOLERANCE_SECONDS`) | 400 `stale` |
| Body over 65536 bytes (`MAX_BODY_BYTES`) | 413 `too_large` |
| Verified but not JSON | 400 `malformed` |
| Verified, first delivery of this `svix-id` | 200 `{"status":"ok"}` |
| Verified, repeated `svix-id` (`email_event.svix_id` is UNIQUE) | 200 `{"status":"duplicate"}` |
| Verified, unknown `data.email_id` | 200 `ok`; the event is stored in `email_event`, nothing else changes |

A 200 for duplicates and unknown ids is what stops Resend retrying. A 401 or 400 makes Resend retry on its own schedule, so a wrong secret shows up in the Resend webhook log as a run of failed deliveries.

## 5. Tracking settings on the domain

On the Resend domain page for `mail.fredvested.com` there are per-domain toggles for open tracking and click tracking.

- **Open tracking: OFF.** Counsel's decision on 2026-09-24. The code treats `email.opened` as untracked (`EmailEventProcessor` comment, `HANDLED` excludes it, a stray one is answered 200 and never stored), the V5 view comment says "Opens are not tracked anywhere", and open tracking works by inserting a remote pixel that makes every recipient's mail client fetch from a vendor host. Leaving the toggle on would collect the data at Resend even though the app never sees it.
- **Click tracking: ON.** It is what produces `email.clicked`, which the app records (never applied, never persisted with the clicked URL). It rewrites every link in the email through Resend's redirect, including the confirmation and unsubscribe links, which is fine: the GET side of both links has no side effect (`WaitlistConfirmationController`), so a redirect in front of it changes nothing. Two consequences to know: (a) mail security scanners that follow links also register as clicks, so `email.clicked` is not a clean human signal, which is why the code only records it; (b) Resend sees the raw token in the clicked URL, which it already had as the sender of the email; the app discards the `click` block precisely so the token is not stored a second time on our side.

If click tracking must be off for deliverability reasons later, nothing in the code breaks: `email.clicked` simply stops arriving.

## 6. Header inspection test (Gmail and Microsoft 365)

Run this from the dev preview first (`https://develop.fredvested-landing-page.pages.dev`, which `frontend/assets/waitlist.js` treats as dev: Turnstile test key, API `https://lpapi-dev.fredvested.com`), once the dev service has `RESEND_API_KEY`, `EMAIL_FROM` and `POSTAL_ADDRESS` set. Sign up once with a Gmail address and once with a Microsoft address (Outlook.com or a Microsoft 365 mailbox). The confirmation email arrives within about 15 s of the signup (10 s initial delay after a boot, 5 s poll).

The expected values below assume `EMAIL_FROM` on `mail.fredvested.com`. If sending from the root domain, read `fredvested.com` wherever `mail.fredvested.com` appears.

### Gmail

1. Open the message, the three-dot menu at the top right of the message, **Show original**.
2. The summary block at the top should read:
   - `SPF: PASS with IP <an amazonses.com address>`
   - `DKIM: 'PASS' with domain mail.fredvested.com`
   - `DMARC: 'PASS'`
3. In the raw headers below, the `Authentication-Results: mx.google.com;` line should contain:
   - `dkim=pass header.i=@mail.fredvested.com header.s=resend` (selector `resend`, the `resend._domainkey` record)
   - `spf=pass ... smtp.mailfrom=<bounce address>@send.mail.fredvested.com`
   - `dmarc=pass (p=NONE sp=NONE dis=NONE) header.from=mail.fredvested.com` (the `p=` value is whatever the `_dmarc.mail` record says)
4. Alignment check: the `From:` header domain (`mail.fredvested.com`) equals the DKIM `header.i` domain and shares the organizational domain with the SPF `smtp.mailfrom` domain. Gmail shows a "via amazonses.com" label next to the sender when this alignment is missing; there should be no "via".
5. The message should be in the inbox, not Spam or Promotions, and the footer should show the real postal address, not `[PO Box pending]`.

### Microsoft 365 / Outlook.com

1. Open the message in Outlook on the web, the three-dot menu, **View**, **View message source** (classic desktop Outlook: File, Properties, Internet headers).
2. Find `Authentication-Results:` (the one ending in `compauth=`). Expected:
   - `spf=pass (sender IP is ...) smtp.mailfrom=send.mail.fredvested.com`
   - `dkim=pass (signature was verified) header.d=mail.fredvested.com`
   - `dmarc=pass action=none header.from=mail.fredvested.com`
   - `compauth=pass reason=100`
3. `X-Forefront-Antispam-Report` contains `SCL:` followed by a number; `SCL:1` or lower is clean, `SCL:5` and above went to Junk.

### What a failure looks like and what to fix

| Symptom | Likely cause | Fix |
|---|---|---|
| `dkim=none` or `dkim=fail`, or DKIM listed as a different domain | `resend._domainkey.mail` TXT missing, at the wrong name (doubled zone), truncated, or the domain not yet Verified in Resend | `dig +short TXT resend._domainkey.mail.fredvested.com` must return the `p=` value; fix the record, re-verify in Resend, send again |
| `spf=none`, `spf=softfail` or `spf=fail` | `send.mail` TXT or MX missing or wrong | `dig +short TXT send.mail.fredvested.com` must return `v=spf1 include:amazonses.com ~all` |
| `dkim=pass` but `dmarc=fail`, or Gmail shows "via amazonses.com" | From domain does not align with the DKIM `d=` domain: `EMAIL_FROM` is on a different domain than the one verified | Set `EMAIL_FROM` to an address on the verified domain and redeploy |
| Microsoft `compauth=fail reason=001` | Implicit DMARC failure, same causes as the row above | Same |
| `dmarc=none` with both passes | No `_dmarc.mail` record (and no root DMARC either) | Add the record from 1d; not fatal, but Gmail and Yahoo expect a DMARC record from bulk senders |
| Everything passes, message in Spam or Junk | New domain with no history, the `[PO Box pending]` placeholder still in the footer (dev only; prod does not start without `POSTAL_ADDRESS`), or a test address that has marked earlier mail as spam | Set `POSTAL_ADDRESS`, keep volume low for the first weeks, mark "not spam" on the test mailbox |
| Email never arrives, `email_message.status` stays `pending` with `last_error` set | Wrong or placeholder API key, or `EMAIL_FROM` on an unverified domain (Resend rejects the send) | Read `last_error` in the row or the Railway log line `Send attempt N failed for message ...`; fix the key or the domain |
| Email arrives but its links point at the other environment | `API_PUBLIC_URL` set to the other environment's origin | Fix the variable (see section 7) |

## 7. Railway variables per environment

Both environments: Railway project, the backend service, **Variables**. A variable that exists but is blank is treated as unset (`backend/src/main/java/com/fredvested/web/config/BlankEnvironmentVariables.java`, registered in `META-INF/spring.factories`): the property default applies and the boot log carries `Ignoring blank environment variable(s) [...]`. Variables whose property has no default (prod's `RESEND_API_KEY`, `RESEND_WEBHOOK_SECRET`, `CORS_ALLOWED_ORIGINS`, `CLOUDFLARE_TURNSTILE_SECRET`, `POSTAL_ADDRESS`, and the MySQL ones in both profiles) still stop the app from starting when blank or missing, on purpose. So: set a real value or delete the variable; never leave it empty.

### dev (`--spring.profiles.active=dev`, API `https://lpapi-dev.fredvested.com`)

| Variable | Value | Notes |
|---|---|---|
| `RESEND_API_KEY` | the `railway-dev` key from section 3 | Default `re_test_placeholder`: app starts, every send fails and retries |
| `RESEND_WEBHOOK_SECRET` | the dev webhook's `whsec_...` secret from section 4 | Default empty: app starts, every webhook delivery is 401 |
| `EMAIL_FROM` | `FRED <fred@mail.fredvested.com>` | Unset = `FRED <fred@fredvested.com>`; must be on a domain Resend shows as Verified |
| `POSTAL_ADDRESS` | FREDvested LLC's mailing address as one line, e.g. `PO Box 123, Baltimore, MD 21201` | Unset = `[PO Box pending]` printed in every email footer |
| `API_PUBLIC_URL` | `https://lpapi-dev.fredvested.com`, or unset (same default) | This is the base of the confirm and unsubscribe links. Pointing it at `https://lpapi.fredvested.com` would put production links (and production tokens that do not exist there) into dev emails. `http://` on a public host is upgraded to `https://` with a WARN (`EmailOutboxPublisher.publicBaseUrl`); only localhost and private-range hosts keep `http` |
| `LANDING_URL` | unset | Default `https://develop.fredvested-landing-page.pages.dev`; where `/confirmed?status=...` redirects land and where `GET /` on the API redirects |
| `WAITLIST_DOUBLE_OPT_IN` | unset | Default `true`. A blank value took dev down on 2026-09-24 before the blank-as-unset fix; now blank = default |
| `CORS_ALLOWED_ORIGINS` | unset | Default `http://127.0.0.1:5500,https://*.fredvested-landing-page.pages.dev` (patterns, comma-separated) |
| `CLOUDFLARE_TURNSTILE_SECRET` | unset | Default is the Turnstile test secret |
| `MYSQLHOST`, `MYSQLPORT`, `MYSQLDATABASE`, `MYSQLUSER`, `MYSQLPASSWORD` | from the Railway MySQL service | Required; unchanged by this setup |

### prod (`--spring.profiles.active=prod`, API `https://lpapi.fredvested.com`)

| Variable | Value | Notes |
|---|---|---|
| `RESEND_API_KEY` | the `railway-prod` key | Required, no default: the API does not start without it |
| `RESEND_WEBHOOK_SECRET` | the prod webhook's `whsec_...` secret | Required, no default |
| `EMAIL_FROM` | `FRED <fred@mail.fredvested.com>` | Same rule as dev |
| `POSTAL_ADDRESS` | same address as dev | Required, no default: `application-prod.properties` sets `email.postal-address=${POSTAL_ADDRESS}`, so the API does not start without it (counsel classed the confirmation email as commercial, so the CAN-SPAM address must never ship as the placeholder). `[PO Box pending]` can only appear in dev or local |
| `API_PUBLIC_URL` | `https://lpapi.fredvested.com`, or unset (same default) | Pointing it at the dev origin would put dev links into production emails, and the tokens in them would be unknown to the dev database: a confirmation click would end on the dev landing site's `/confirmed?status=invalid`, and an unsubscribe click would get the dev API's `Unsubscribe link not valid` page |
| `LANDING_URL` | unset | `application-prod.properties` does not set `landing.url`; the code default `https://fredvested.com` in `LandingUrls` applies |
| `WAITLIST_DOUBLE_OPT_IN` | unset | Default `true` |
| `CORS_ALLOWED_ORIGINS` | `https://fredvested.com`, plus `https://www.fredvested.com` if the www host serves the site | Required, no default. `frontend/assets/analytics.js` treats both hosts as production, so include both if both resolve |
| `CLOUDFLARE_TURNSTILE_SECRET` | the real Turnstile secret | Required, no default |
| `WAITLIST_US_ONLY` | unset | Default `true` in prod |
| `MYSQLHOST`, `MYSQLPORT`, `MYSQLDATABASE`, `MYSQLUSER`, `MYSQLPASSWORD` | from the Railway MySQL service | Required |

Change one environment at a time and read its boot log before touching the other.

## 8. Smoke test checklist

Do dev first, then prod. Test rows can be deleted afterwards with `DELETE FROM waitlist_signups WHERE email = '...'`; `email_message` rows go with them (`fk_email_message_waitlist ... ON DELETE CASCADE`, V4), `email_event` rows stay (deliberately not FK-linked). While a test row is confirmed it is counted in the public statistic (`GET /api/waitlist/stats` counts `confirmed_at IS NOT NULL`, cached 30 s; the memo is dropped on every signup and on every confirmation).

Boot:

- [ ] Railway log shows `Started WaitlistApplication` and Flyway reports nothing new (or exactly the expected migrations).
- [ ] No `Ignoring blank environment variable(s)` line.
- [ ] No `resend.webhook-secret is not set or is not valid base64` line.
- [ ] No `api.public-url is http://...` line.

Signup and send:

- [ ] Sign up on the environment's site with a fresh test address (dev preview for dev; `https://fredvested.com` for prod, which needs a US IP and passes the real Turnstile). The page shows the "check your email" pending state.
- [ ] `waitlist_signups`: new row, `confirmed_at` NULL, `status` `WAITLISTNORMAL`, `email_status` NULL at first.
- [ ] `email_message`: one row, template `waitlist_confirmation`, `status` goes `pending` to `sent` within about 15 s, `resend_email_id` set, `sent_at` set.
- [ ] `waitlist_signups.confirmation_sent_at` set, `email_status` `sent`, `confirmation_token_hash` set, `confirmation_expires_at` = the time the publisher minted the token (a moment before `sent_at`) + 7 days (`waitlist.confirmation.ttl-days=7`).
- [ ] Resend dashboard, **Emails**: the message is listed with status delivered.

Webhook:

- [ ] Resend dashboard, the environment's webhook, delivery log: `email.sent` and `email.delivered` answered 200.
- [ ] `email_event`: one row per delivery, `event_type` as above, `svix_id` set, `payload` without `html`, `text` or `click` keys.
- [ ] `email_message.status` = `delivered`, `waitlist_signups.email_status` = `delivered`, `last_event_at` set.
- [ ] A test event sent from the Resend dashboard (if offered) of a handled type (say `email.sent`) with a made-up `email_id` is answered 200 and appears in `email_event` with that id; nothing else changes. A test event of a type outside `HANDLED` is answered 200 and stored nowhere. That row is harmless and can be deleted.

The email itself:

- [ ] From shows `FRED` and the `EMAIL_FROM` address; subject `Confirm your email for FRED's waitlist`.
- [ ] The confirm button and the `Unsubscribe` link point at this environment's API origin (`API_PUBLIC_URL`), through Resend's click-tracking redirect if click tracking is on.
- [ ] The raw message source (Show original in Gmail, View message source in Outlook, section 6) carries `List-Unsubscribe: <https://<API_PUBLIC_URL host>/api/waitlist/unsubscribe?token=...>` and `List-Unsubscribe-Post: List-Unsubscribe=One-Click` (`EmailOutboxPublisher.publish` builds them, `EmailService.send` passes them to Resend). The token in the header is the same one as in the footer link. Gmail may show its own Unsubscribe control next to the sender once both headers are present.
- [ ] The footer shows `FREDvested LLC, <POSTAL_ADDRESS>` and not `[PO Box pending]`.
- [ ] Headers pass per section 6 (Gmail and Microsoft).

Confirm:

- [ ] Click the confirm link: a page titled `Confirm your email - FRED` appears briefly (its script POSTs the token), then a redirect to `<LANDING_URL>/confirmed?status=confirmed&hours=%3C1&tier=founder` (the band `<1`, URL-encoded by `WaitlistConfirmationController`; the browser may show it decoded; `tier=normal` once the founder cap is full, and no `tier` at all when the row was already `INVITED`, `CLAIMED` or `DECLINED`). Because the redirect carries `hours`, the page's script stores `localStorage.waitlist_status` (`WAITLISTFOUNDER`, `WAITLISTNORMAL`, or `confirmed` when `tier` is absent), removes `waitlist_pending`, fires `Waitlist Confirmed`, then drops both `hours` and `tier` from the address bar so a reload can neither fire the event again nor re-store the status. A `/confirmed?status=confirmed` URL without `hours` (shared or retyped) changes nothing in the browser.
- [ ] `waitlist_signups`: `confirmed_at` set, `confirmed_source` = `double_opt_in`, `confirmation_token_hash` NULL, `confirmation_expires_at` NULL, `status` `WAITLISTFOUNDER` while fewer than 300 confirmed founders exist (`SignupService.FOUNDER_CAP`).
- [ ] Click the same link again: `status=invalid` (single use; unknown and used tokens are indistinguishable).
- [ ] `GET /api/waitlist/stats`: `count` includes the row at once (`SignupService.confirm` publishes `SignupService.WaitlistCountsChanged`, and the `@EventListener` in `WaitlistController` drops the 30 s memo).
- [ ] On production only: the `Waitlist Confirmed` event with `hours_to_confirm_band` shows in Plausible. On the dev preview `frontend/assets/analytics.js` does not send unless `localStorage.fred_analytics_force === '1'`.

Resend and unsubscribe:

- [ ] Sign up a second test address, then use "resend the email" on the pending state (or the form on `/confirmed`, or enter the same address in the signup form again: while it is unconfirmed, `WaitlistController` answers exactly like a fresh signup, `status` `WAITLISTNORMAL` with `requiresConfirmation` true and never `already_joined`, and queues the resend itself through the same limiter): a second `email_message` row is queued and the first pending one, if still pending, is `skipped` with `superseded by a resend request`. A second request within 10 minutes is rate limited (`AddressRateLimiter`: 1 per 10 min, 3 per day; over the limit the signup form's answer is unchanged and nothing is queued).
- [ ] Click `Unsubscribe` in an email: a page titled `Unsubscribe from FRED emails - FRED` shows a visible `Unsubscribe` button and nothing happens until it is clicked (the page carries no `<script>` at all, `WaitlistConfirmationController.unsubscribePage`; only the confirm page auto-submits, because a consumed confirmation token is recoverable via resend and a suppression is never cleared). Clicking the button POSTs the token, then `<LANDING_URL>/confirmed?status=unsubscribed`. `waitlist_signups.suppressed_at` set, `suppression_reason` = `unsubscribe`. The row appears in `v_email_suppressions`.
- [ ] One-click unsubscribe the way a mail client does it (RFC 8058): take the `List-Unsubscribe` URL from the raw headers of a third test address's email and run `curl -i -X POST '<that url>' -d 'List-Unsubscribe=One-Click'`. The answer is `200` with `Content-Type: text/plain` and the body `Unsubscribed.`, no redirect, and the address is suppressed exactly as above. The same call with a made-up token gets the identical `200 Unsubscribed.` (`WaitlistConfirmationController.unsubscribe`); only the button form's POST gets the `status=invalid` redirect for an unknown token.
- [ ] Request a resend for the unsubscribed address: the API answers 200 `{"status":"ok"}` (or 429 if the address or IP limit has been hit; never anything that reveals whether the address is on the list), but no `email_message` row is queued (`SignupService.requestResend` returns for a suppressed address).

Bounce path (dev only, if Resend still offers its test addresses such as `bounced@resend.dev` and `complained@resend.dev`):

- [ ] Sign up with the bounce test address: `email.bounced` arrives, `email_message.status` = `bounced`, `waitlist_signups.suppressed_at` set with `suppression_reason` = `hard_bounce` (a `Transient` bounce type records without suppressing).
- [ ] Sign up with the complaint test address: `complained`, `suppression_reason` = `complaint`.

Funnel:

- [ ] `SELECT * FROM v_waitlist_funnel` shows the test rows under their UTM group with `confirmation_sent`, `delivered` and `confirmed_double_opt_in` incremented.

## Known gaps

These are in the code today and this setup does not change them.

1. **Ambiguous send.** `EmailOutboxPublisher.publish` mints a new confirmation token on every attempt (`waitlist.setConfirmationToken` replaces the hash; the row's `unsubscribe_token_hash` is replaced the same way), and a send that Resend accepted but whose response was lost is retried as a failure. The email from the first attempt then carries tokens that no longer match; only the newest email's links work. The publisher's own comment also notes that a crash between the send and recording its result can double-send once. There is no re-mint or reconciliation; the recipient's remedy is the resend button.
2. **Scanners that run JavaScript.** The two-step links defeat scanners that only fetch HTML. A scanner that executes the confirm page's inline script would still POST the form and confirm the row before the human clicks, consuming the single-use token. The human then lands on `status=invalid`; the resend button queues nothing, because `SignupService.requestResend` returns for a confirmed address, and the browser that signed up keeps its pending state (`confirmed.html` stores `waitlist_status` only when the redirect carries `hours`) until `getPendingEmail()` in `assets/waitlist.js` ages it out after 7 days or the person clicks "Use a different one". The row is confirmed either way; what is lost is the evidence of a human click. The unsubscribe page no longer has this exposure: it carries no script and needs a real click on its button (`WaitlistConfirmationController.unsubscribePage`), because a suppression is never cleared. What remains on the unsubscribe side is the one-click POST itself: anything that sends `List-Unsubscribe=One-Click` to the header URL is treated as the recipient's mail client (`WaitlistConfirmationController.unsubscribe`), which is what RFC 8058 asks for.
3. **Founder slot under concurrency.** `SignupService.confirm` counts confirmed founders (`founderSlotStatus`) and then sets the status without serialising across transactions, so a burst of simultaneous confirmations around the 300th can exceed `FOUNDER_CAP` by a few. The 2026-09-24 change only moved that count ahead of dirtying the managed entity, so a Hibernate auto-flush can no longer let the row count itself; it adds no lock.

## Open questions

1. **Root vs subdomain acceptance in Resend.** This document assumes the From address must be on exactly the domain verified in Resend. Whether Resend accepts a From address on a subdomain of a verified root (or on the root when only a subdomain is verified) is a Resend rule to confirm in the dashboard or docs before deciding between `fred@fredvested.com` and `fred@mail.fredvested.com`.
2. **Replies.** Resolved 2026-09-25: `EmailService.options` sets `Reply-To: help@fredvested.com` (`EMAIL_REPLY_TO`) on every message, so nothing needs routing on the subdomain for replies.
3. **Privacy policy vendor list.** `frontend/privacy.html` in the working tree names Cloudflare (Turnstile), Railway and Plausible as third parties; a search for "Resend" in that file finds nothing, while `CLAUDE.md` says the policy names Resend. Counsel and Andrew to decide whether an entry is needed before confirmation emails go out at volume.
4. **Existing Resend state.** The root-domain verification and the `send` subdomain records described under "Current state" come from a session note, not from code or DNS. Confirm in the dashboard, and decide when to remove the root domain from Resend after the switch.
5. **Root DMARC and Zoho.** Whether `_dmarc.fredvested.com` exists, and whether Zoho's outbound mail signs DKIM for the root, determines how far the root policy can be tightened. Not needed for the subdomain, but the reports will raise it.
6. **`List-Unsubscribe` headers.** Set by the code as of 2026-09-24: `EmailOutboxPublisher.publish` sends `List-Unsubscribe: <unsubscribe url>` and `List-Unsubscribe-Post: List-Unsubscribe=One-Click` on every email, and `POST /api/waitlist/unsubscribe` honours the one-click body with a plain `200 Unsubscribed.`, so the Gmail and Yahoo bulk-sender requirement (one-click unsubscribe above roughly 5,000 messages a day) is met before volume gets there. Still to confirm on a real send: that Resend passes both headers through unchanged (the header check in section 8) and that Gmail shows its Unsubscribe control for the sender.
7. **Resend test addresses.** The bounce and complaint checks in section 8 depend on Resend still offering `bounced@resend.dev` and `complained@resend.dev`; confirm in Resend's docs before relying on them.
8. **Dashboard labels.** Button and toggle names in sections 1, 3, 4 and 5 are described by purpose where the exact label was uncertain; the Resend UI may have moved them.
