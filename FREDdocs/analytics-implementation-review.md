# Analytics and email funnel: implementation review

Completion review for the analytics and email-funnel work on the FRED landing page, sessions of 2026-09-16 to 2026-09-25. One row per numbered item in every message Andrew sent, in order, with where it lives and what proves it. Deviations, manual steps, known gaps and the runbook follow the table.

State at review time: `develop` at the commit named at the end, all work committed, nothing force-pushed, history never rewritten. Backend suite: 156 tests green (unit, slice, and the real-MySQL integration test). End-to-end suite: 91 tests green; see the Phase 8 section.

## How to read the table

- **Where**: the file, class or commit that implements the item.
- **Proof**: the automated test, dry-run, or live check that shows it. "Live" means it was observed on the deployed preview or production during the session.
- **Status**: done, done with a deviation (explained in the Deviations section), decided-by-Andrew (recorded, no code), or gap.

## Message 1: the original specification

| Item | Where | Proof | Status |
|---|---|---|---|
| Phase 0 audit of the existing analytics and email code | session report (audit delivered before any change) | Andrew: "Good audit" | done |
| Phase 1 Plausible via first-party proxy on Cloudflare Pages | `frontend/_worker.js`, `frontend/_routes.json` (commits `74b4a61`, `e3b22ab`) | live: `/js/script.js` served, `/api/event` forwarded, cookies stripped, XFF from CF-Connecting-IP; cache 1 h | done |
| Phase 2 single analytics module, exact 7-event schema, banded props, once guards, at most 7 events per visitor | `frontend/assets/analytics.js` (`7375950`) | Node VM harness (48 assertions); `tests/analytics-events.spec.js` | done |
| Phase 3 attribution: first and last touch, sanitised UTMs, no visitor id | `frontend/assets/attribution.js` (`a79a8f6`), `AttributionSanitizer` | harness; `AttributionSanitizerTest`; `WaitlistControllerAttributionTest` | done (`ref` dropped, per message 6) |
| Phase 4 wiring on every page | `index.html`, `about.html`, `privacy.html`, `terms.html`, `confirmed.html` (`b51671b`) | end-to-end suite; headless Chrome checks | done |
| Phase 5 backend storage with validation, sanitisation, recompute | `WaitlistController`, `FreedomCalculator`, V3 (`e8abcf8`) | `FreedomCalculatorTest`, attribution tests; V3 dry-run on three DB shapes | done |
| Phase 6 double opt-in via Resend: hashed tokens, outbox, Svix-verified idempotent ordered webhook, suppression in the send path, `v_waitlist_funnel`, `confirmed.html` with `Waitlist Confirmed`, unsubscribe in every email | `SignupService`, `EmailOutboxPublisher`, `EmailEventProcessor`, `ResendWebhookVerifier`, V4 (`c46998e`) | 30+ tests across the email classes; V4 dry-run on three shapes; live end-to-end run on dev (signup, Resend delivered, webhook 200, confirm click) | done |
| Phase 7 docs: analytics-events, utm-conventions, analytics-dashboard-setup, email-funnel, resend-setup, analytics-qa | `FREDdocs/*.md` (`4a3afce`) | each written from code, verified claim by claim by a second agent, re-verified after later changes | done |
| Phase 8 Playwright, integration, webhook and confirmation tests, plus the third-party-host check | `tests/*.spec.js`, `WaitlistFunnelIntegrationTest` | see the Phase 8 section | done |
| Completion review with requirement traceability, deviations, manual steps, gaps, runbook | this document | | done |
| Rule: Plausible only | no other analytics vendor anywhere; the third-party-host test fails on any other host | `tests/third-party-hosts.spec.js` | done |
| Rule: all email via the backend | `EmailService` is the only sender; nothing in the browser sends mail | code | done |
| Rule: no PII to Plausible | props are bands only; `analytics-events.md` "What is deliberately not sent" | harness; `tests/analytics-events.spec.js` asserts no `@` and only band strings | done |
| Rule: analytics and email never break the flow | `track()` catches everything; the outbox decouples Resend from the request thread | `WaitlistControllerEmailTest.joinWaitlist_isNotAffectedByResendBeingDown`; suite | done |
| Rule: ask before deviating from the event schema | schema unchanged since Phase 2; every deviation listed below was asked and approved | checkpoints | done |
| Rule: one commit per phase, attribution lines on every commit | git log | `git log` | done (corrective and follow-up commits added, each explained) |
| Rule: never rewrite history, never reset --hard | | `git reflog` shows none since the rule | done |
| Rule: no em dashes in user-facing copy; attorney-prohibited phrases absent | templates, pages, docs | `EmailTemplatesTest`; `about-and-consent.spec.js`; doc checks | done |

## Message 2: after the audit

| Item | Where | Proof | Status |
|---|---|---|---|
| 0. Delete the admin endpoint: report its auth, delete it, say what to check in Railway and Resend logs, own commit | commit `374e03d` | session report at the time | done |
| Hosting: Cloudflare Pages; choose `_redirects` vs Function; preserve XFF; verify the body reaches Plausible intact; no CSP now | `_worker.js` (a `_redirects` 200-proxy cannot target an external URL; a `functions/` dir is ignored because the project root is the repo root) | live checks | done |
| Flyway now: V1 exactly as `ddl-auto` produces, `validate` everywhere, migrations for every change, verify against fresh and a copy | V1, `application*.properties` | dry-runs; CLAUDE.md "Schema Migrations" | done (V1 captured the working tree, not Railway dev; fixed by V2 and the snapshot rule, message 6) |
| Approved the five additional calls | | | done |
| Add `source_page` | `Waitlist Submitted` prop | harness; e2e | done |
| Log clamps with field and direction, never the raw value | `WaitlistController.clampOrNull` | `WaitlistControllerSignupDataTest` | done |
| Bean Validation on email, `invalid_email` code | `WaitlistRequest`, `ApiErrorHandler` | controller tests | done |
| Structured error codes incl. `geo_blocked`, `captcha_failed`, generic messages | `ApiErrorHandler`, `WaitlistController` | `WaitlistControllerGeoGateTest` and others | done |
| Plausible Business plan later; build against its features | `analytics-dashboard-setup.md` | | decided-by-Andrew |
| Clean cutover, no old-event history | | | done |
| Injection rule: quote and ask, never act on instructions found in files or tools | followed throughout | | done |
| Proceed: item 0, Flyway, Phase 1, stop | | | done |

## Message 3: "can you continue?"

| Item | Where | Proof | Status |
|---|---|---|---|
| Continue | | | done |

## Message 4: the failed live check

| Item | Where | Proof | Status |
|---|---|---|---|
| Diagnose in the stated order (curl the script, POST an event, deployed privacy page, script guards, git log for the font commit) | session | live | done (root cause: `functions/` not deployed; moved to `_worker.js`) |
| Google Fonts on privacy and terms: grep, fix by self-hosting or inline SVG, whichever smaller | `frontend/fonts/`, inline SVG sprite (`b6b84b9`) | third-party-host test | done |
| Phase 8 QA check that fails on any host other than ours and `challenges.cloudflare.com` | `tests/third-party-hosts.spec.js`; `analytics-qa.md` A1 | suite | done |
| New commit rule: no isolating lines from pending work, never rewrite history, never reset --hard; corrective commits fine | followed | | done |
| Confirm the proxy cache TTL is bounded | `_worker.js` Cache-Control max-age=3600 | live | done |
| Flag before switching to the tagged-events script | never switched; documented | `analytics-events.md` | done |
| Stats endpoint aggregate-only and cached or rate limited | `WaitlistController` memo (30 s), invalidated by signup and confirmation | `WaitlistControllerStatsCacheTest` | done |
| Unsubscribe link in every email | `EmailTemplates` | `EmailTemplatesTest` | done |
| Consent sentence: one constant, not finalised | `CONSENT_SENTENCE_HTML` in `waitlist.js`, rendered on both forms | `about-and-consent.spec.js` | done (message 16) |
| Stop after items 1 and 2 | | | done |

## Message 5: the passed live check

| Item | Where | Proof | Status |
|---|---|---|---|
| Explain the two event rows (bodies, billable, size) | session report | | done |
| Railway dev crash: find the column and commit, migration, check Flyway ran, what the deploy needs, `validate` everywhere, docs note | V2, CLAUDE.md | V2 dry-run; dev recovered | done |
| Preview pollution gate: fredvested.com and www only, `fred_analytics_force` escape hatch, documented in QA | `analytics.js`; `analytics-qa.md` B1, C | `tests/analytics-events.spec.js` gate test | done |
| Items 3 to 8 stand | | | done |
| Continue to Phase 2, stop at its checkpoint | | | done |

## Message 6: Phase 2 checkpoint decisions

| Item | Where | Proof | Status |
|---|---|---|---|
| Keep the outbound-links extension; document `Outbound Link: Click` as out-of-budget with a volume estimate | `analytics-events.md` "Out-of-budget events" | doc | done |
| Crash fix accepted; process rule: dry-run every migration against a snapshot of the target environment, in CLAUDE.md with exact Railway steps before prod's first Flyway deploy | CLAUDE.md "Schema Migrations" | | done |
| Duplicate as `Waitlist Failed reason=duplicate`, with the returning-interest note | `analytics.js`; `analytics-events.md` | e2e | done (narrowed to confirmed duplicates in message 16) |
| Server-side recompute; no `return_scenario` stored | `FreedomCalculator`; V3 | tests | done |
| Drop `ref` entirely | `attribution.js` | harness | done |
| Attribution per session; camelCase globals | `attribution.js` | harness | done |
| No Google Fonts in email | `EmailTemplates` web-safe and system fonts | `EmailTemplatesTest` | done |
| `.wrangler/` gitignored | `.gitignore` | | done |
| Phase 6 in full: identical responses for unknown and used tokens; unknown message id 200 ignored | `WaitlistConfirmationController`, `EmailEventProcessor` | tests | done |
| Stop at the Phase 6 checkpoint with the combined Phase 2 to 6 table | | | done |

## Messages 7 to 14: the preview test session (2026-09-24)

| Item | Where | Proof | Status |
|---|---|---|---|
| 7. Turnstile 110200 on the develop preview | diagnosis: previews classed as production (real sitekey, prod API, CORS 403); also lpapi-dev down | | done |
| 8. Railway dev crash: empty string to boolean | `BlankEnvironmentVariables` (`35ed5e0`): a blank env var counts as unset, WARN names it | `BlankEnvironmentVariablesTest`; real boot with blank vars | done |
| 9. Value for `WAITLIST_DOUBLE_OPT_IN` | `true` | dev came back | done |
| 10. Preview still failing after the push | previews are dev: test sitekey, lpapi-dev, dev CORS pattern (`0667e32`) | `DevProfileCorsTest`; host harness; live 200 | done |
| 11. Should an email arrive | yes; duplicate address explained; resend triggered | Resend showed delivered | done |
| 12. Confirm button did nothing | link was `http://`: `API_PUBLIC_URL` entered as http; links forced to https for public hosts (`fa876ae`) | `EmailOutboxPublisherTest` https tests | done |
| 13. Link worked on web, not on the phone | same root cause; fixed above | Andrew: "it worked" | done |
| 14. FRED wordmark styling on Apple Mail; keep the dark-mode rendering | `'Avenir Next'` fallback (`adbcdce`); no color-scheme declaration | `EmailTemplatesTest` wordmark and no-color-scheme tests; rendered comparison | done |

## Message 15: Phase 6 checkpoint decisions

| Item | Where | Proof | Status |
|---|---|---|---|
| 1. Scanner-safe confirmation (GET page, auto-POST, noscript button, byte-identical POST) | `WaitlistConfirmationController` (`0d82951`) | `ConfirmationGetIsSideEffectFreeTest`, controller test; integration test | done |
| 1. Same pattern for unsubscribe | reverted to a button in `dc38953`; RFC 8058 headers instead | tests | deviation, approved in message 16 |
| 2. Token re-mint: leave as is | `email-funnel.md` Known gaps | | decided-by-Andrew |
| 3. Legacy rows kept and tagged; `confirmed_source`; backfill; funnel split | V5, `WaitlistEntry`, `SignupService` (`8a2c71e`) | V5 dry-run on four shapes; null-safe view check | done (`single_opt_in` third value, approved) |
| 4. Stats and founder cap confirmed-only; stop if the footnote wording breaks | `WaitlistRepository`, `WaitlistController`, `SignupService` | `WaitlistControllerConfirmedOnlyStatsTest`; footnote checked, still accurate | done |
| 5. Success copy on index and about under double opt-in; keep "you're in" for single opt-in | `index.html`, `about.html` (`5fac0d0`) | `tests/pending-state.spec.js` | done |
| 6. Flags 6, 7, 8 documented | `analytics-events.md`, `email-funnel.md` | | done |
| 7a. Open tracking off; `email.opened` never first-class; stray one 200 and ignored | `EmailEventProcessor.HANDLED` (`ab0a2f4`) | processor and controller tests; integration test | done |
| 7b. Consent-only confirmation email; `POSTAL_ADDRESS` from one config; tests for unsubscribe link, address, and the deny-list | `EmailTemplates`, `EmailOutboxPublisher` | `EmailTemplatesTest` | done (prod requires the variable) |
| 7c. `/privacy` and `/terms` everywhere; `.html` redirects, no second copy | audit; `devLinkMap` narrowed to local hosts | live 308 redirects | done (all links were already clean) |
| 8a. Webhook fails closed when the secret is unset | `ResendWebhookControllerUnconfiguredTest` asserts 401, the bad-signature body, processor never called | | done |
| 8b. SPF, DKIM, DMARC on a sending subdomain, Gmail and Microsoft 365 header test, click by click | `resend-setup.md` sections 1 and 6 | doc verifier | done |
| 8c. Referral TODO in confirmed.html | `confirmed.html` | grep | done |
| 9. Email-sending fixes in the traceability table | messages 7 to 14 above | | done |
| 10. `API_PUBLIC_URL` note in analytics-qa.md; Phase 7 then Phase 8 with checkpoints | `analytics-qa.md` D | | done |

## Message 16: Phase 7 checkpoint rulings

| Item | Where | Proof | Status |
|---|---|---|---|
| 1. Unsubscribe as a human click; record the reasoning | `email-funnel.md` "Why confirm auto-posts and unsubscribe does not" | `unsubscribeGet_onlyShowsAButton…`; the one-click endpoint test | done (the RFC 8058 headers were then removed from every email in message 17; the footer link is the opt-out) |
| 2. Unconfirmed re-submit as a fresh signup; one line in analytics-events.md | `WaitlistController`; `analytics-events.md` duplicate note | `WaitlistControllerAlreadyJoinedTest`; integration test | done |
| 3. `single_opt_in` approved | | | done |
| 4. privacy.html untouched; consent constant rendered on the about.html form | `waitlist.js` `CONSENT_SENTENCE_HTML`, `#consent-note` on both pages | `about-and-consent.spec.js` | done |
| 5. Reply-To help@fredvested.com on every message | `EmailService.options`, `email.reply-to` | `EmailServiceOptionsTest` | done |
| 5. No separate Plausible site for previews | `analytics-qa.md` | | decided-by-Andrew |
| 5. Once-per-page-load guard is intended; note it | `analytics-events.md` Guards | | done |
| 6. `POSTAL_ADDRESS`: keep fail-on-missing in prod | `application-prod.properties` | | done |
| 7. Phase 8 suite incl. third-party check, pending state, confirmed page, the failure paths, webhook and confirmation beyond unit level; then this review | `tests/`, `WaitlistFunnelIntegrationTest`, this document | Phase 8 section | done |

## Phase 8: what the tests cover

Backend (`cd backend && ./gradlew test`): 156 tests.
- Slice and unit tests for every class in the funnel (tokens, publisher, processor, verifier, limiters, templates, config), the controllers (signup, stats, confirmation, resend, unsubscribe, webhook), the blank-env-var and dev-CORS behaviour.
- `WaitlistFunnelIntegrationTest` (runs when `FRED_IT_JDBC_URL` points at a scratch MySQL): Flyway V1..V5 on an empty schema, Hibernate validate, then signup -> outbox row -> publisher (Resend mocked) -> token hashes -> GET consumes nothing -> POST confirms once with the founder tier -> replay is unknown -> stats count the confirmed row; an unconfirmed re-submit looks fresh and re-queues; a signed webhook stream (delivered, duplicate, late sent, opened, hard bounce) persists exactly the tracked events, never the open, strips html, orders statuses, suppresses, and the publisher then refuses the next send; an unknown message id is stored and touches no row.

End-to-end (`npm run test:e2e`, Chromium, pages served by `frontend/serve.py`, API and Plausible intercepted in the browser, Turnstile real):
- `tests/third-party-hosts.spec.js`: every page and a full signup make requests only to our origin and `challenges.cloudflare.com`.
- `tests/analytics-events.spec.js`: the schema on the real pages: `Calc Engaged`, `Freedom Date Revealed` with exact bands, `Recalculated`, `Explainer Opened`, `Email Focused`, `Waitlist Submitted` (home and about), `Waitlist Failed` for a confirmed duplicate, a 500 and a 429, the cap of three, the hostname gate, and that no prop is a raw number or an address.
- `tests/pending-state.spec.js`: the double opt-in pending state on both pages, storage keys, reload, resend, "Use a different one", storage blocked, the 7-day expiry, the single opt-in and confirmed-duplicate copy.
- `tests/confirmed-page.spec.js`: every state of confirmed.html, `Waitlist Confirmed` with the exact band once, the tier stored only when the redirect carries `hours`, URL stripping, shared links storing nothing, the resend form's single answer.
- `tests/about-and-consent.spec.js`: the founder wording only for a known founder, the consent sentence identical on both forms, the stat tile threshold.

Results at review time (2026-09-25): backend 156 tests, 0 failures (including the 4 real-MySQL integration tests); end-to-end 91 tests, 0 failures, 0 skipped, in one invocation of `npm run test:e2e` (6 + 29 + 19 + 25 + 12 across the five files), each spec also run twice by its author to check for flakiness. Every spec was written by one agent and run to green by another that was allowed to change only the spec; none needed weakening. What the runners flagged and what happened to it: `showMsg()` on both pages rendered the API's `message` field with `innerHTML` (an injection sink if the API origin were ever compromised): switched to text. A malformed `waitlist_pending` record was left in storage: now cleared. Two Playwright invocations at once race on `test-results/`: run the suite from one invocation (documented in analytics-qa.md A4). The `409 -> duplicate` mapping in `waitlist.js` is unreachable (the backend answers duplicates with 200): left, documented. A product question, not a defect: on `index.html` a returning visitor with a saved pending or success state sees the calculator first, with the pending or success block inside the collapsed reveal zone until they click Reveal; that is the calculator-first design applied to a returning visitor, and it is Andrew's call whether the zone should open itself when a state is saved.

## Message 17: the confirm click and the Apple Mail strip (2026-09-25)

| Item | Where | Proof | Status |
|---|---|---|---|
| "Invalid CORS Request" on the confirm button | the confirm page's own form POST carried `Origin: https://lpapi-dev…` and, with TLS ended at the edge, Spring saw an `http://` request and treated it as cross-origin. Fixed two ways: `server.forward-headers-strategy=framework` (the request knows its https origin, same-origin, CORS not consulted) and `api.public-url` added to the CORS allow-list (`WebConfig`) | reproduced with curl (403, then 302 without Origin); `ConfirmPostSameOriginTest` proves each defence alone plus the unsubscribe button and a foreign origin still refused; `DevProfileCorsTest.theApiOwnOrigin_isAllowed` | done |
| Apple Mail "this message is from a mailing list" strip | caused by the `List-Unsubscribe` header added on 2026-09-24. Removed from every email; the footer link is the opt-out (CAN-SPAM). The RFC 8058 pair is what Gmail and Yahoo require above roughly 5,000 messages a day; `POST /unsubscribe` still honours the one-click body so the headers can return per template at that volume | `EmailOutboxPublisherTest` asserts an empty headers map for both templates; docs updated (email-funnel.md, resend-setup.md, analytics-qa.md, CLAUDE.md) | done, decided-by-Andrew |

## Deviations from the original specification (all asked and approved)

1. Duplicate signups fire `Waitlist Failed reason=duplicate` only for a confirmed address; an unconfirmed re-submit is answered like a fresh signup and fires `Waitlist Submitted` (messages 6 and 16). `v_waitlist_funnel` is authoritative for submit-to-confirm.
2. The `ref` query parameter is not captured (message 6).
3. Unsubscribe is a human click on the footer link, not an auto-POST (message 16); no `List-Unsubscribe` headers on any email (message 17). Confirm auto-posts.
4. `confirmed_source` has three values, not two (message 16).
5. `Outbound Link: Click` is kept and billable, outside the 7-event budget (message 6).
6. Corrective and follow-up commits exist beyond one per phase, each with its reason in the message.

## Manual steps that only Andrew can do

- Railway prod, before the first deploy: `POSTAL_ADDRESS` (required), `RESEND_API_KEY`, `RESEND_WEBHOOK_SECRET`, `API_PUBLIC_URL=https://lpapi.fredvested.com`, `CORS_ALLOWED_ORIGINS`, `CLOUDFLARE_TURNSTILE_SECRET`; then the snapshot dry-run of V1..V5 against a copy of prod's schema (CLAUDE.md "Schema Migrations"); then deploy and watch the Flyway lines.
- Resend: sending subdomain with SPF, DKIM, DMARC; open tracking off, click tracking on; webhook per environment without `email.opened`; the Gmail and Microsoft 365 header test (`resend-setup.md`).
- Plausible Business: site, goals, custom properties, funnels (`analytics-dashboard-setup.md`).
- Counsel: privacy policy to name Resend and drop "cannot opt out of transactional emails"; the final consent sentence (one constant); the confirmation email copy sign-off.
- `about.html` has the consent sentence now; counsel's final wording replaces the constant.

## Known gaps

- Token re-mint after an ambiguous send is not handled (the person uses resend). Decided.
- A mail sandbox that executes JavaScript confirms the row itself when it opens the confirm link; what is lost is the evidence of a human click, not the signup (`email-funnel.md`).
- The founder-slot decision is not serialised across concurrent confirmations; a burst at exactly 300 could exceed the cap by a few.
- `privacy.html` does not name Resend and still says transactional emails cannot be opted out of; counsel's final policy replaces the page.
- The Plausible upstream script is fetched live through the proxy; Plausible-side changes are outside this repository's control (documented with the verification date).
- No Docker on the build machine, so the integration test uses a local MySQL behind an environment variable instead of Testcontainers.
- No `List-Unsubscribe` headers by decision; if the list ever exceeds roughly 5,000 messages a day, Gmail and Yahoo require them and they must be added back per template (`EmailOutboxPublisher.publish`; the POST handler is ready).

## Runbook (dev, then prod)

1. Push `develop`; Cloudflare Pages builds the preview; Railway dev redeploys and Flyway applies any new migration. Watch the Railway log for `Migrating schema` lines and `Started WaitlistApplication`; a `BlankEnvironmentVariables` WARN means a variable was created empty.
2. On the preview (treated as dev): one real signup with a fresh address; the email arrives from `fred@…` with an `https://lpapi-dev…` confirm link, `Reply-To help@fredvested.com`, no list headers (no "mailing list" strip in Apple Mail); click; the confirm page posts to itself without a CORS error; `/confirmed?status=confirmed&hours=…&tier=…`; `v_waitlist_funnel` shows the row.
3. Webhook: Resend dashboard shows `email.sent` and `email.delivered` answered 200; `email_event` has both; a test bounce suppresses.
4. Prod: variables above, snapshot dry-run, backup, merge, deploy, repeat step 2 with a real address, check `/stats` counts confirmed rows only.
5. Analytics: set `fred_analytics_force=1` on production once, run the QA checklist B, clear the flag.

Commit at review time: see `git log -1` on `develop`.
