# FRED Landing Page Audit — vs. 2026 Landing Page Best Practices

Audit of the pre-maintenance landing page (`frontend/index.html` as of commit `e0fe1a2`,
currently hidden behind the "Under Reconstruction" page) against the frameworks taught in:

1. **Alex Hormozi's Landing Page Strategy for 2026** (zA0B-VwOPn4)
2. **4 Proven Steps to Build a MILLION DOLLAR Landing Page** — ThrillX Design (KneaEGicMZ4)
3. **Brutally Honest Landing Page Advice from Alex Hormozi** (Qgtq-xxA00I)
4. **The NEW Way Of Landing Pages in 2026** — ThrillX Design (1gvPLQzrbmM)

> Note on sourcing: YouTube transcripts were not directly retrievable from this environment.
> The frameworks below were reconstructed from indexed summaries of these exact videos, the
> creators' companion materials (Skool lessons, ThrillX's published framework), and Hormozi's
> extensively documented systems ($100M Offers value equation, proof-promise-plan, 3
> landing-page tests). Video 4 is too recent to have indexed summaries; its audit points come
> from the creator's adjacent 2026 videos and documented positions.

---

## The combined framework used for this audit

- **Value Equation (Hormozi):** Value = (Dream Outcome × Perceived Likelihood) ÷ (Time Delay × Effort). The page must maximize the top and minimize the bottom.
- **15-second clarity test (Hormozi):** within 15 seconds a visitor knows what it is, who it's for, and what to do.
- **Headline = avatar + dream outcome**, subheadline substantiates with specifics.
- **Proof → Promise → Plan ordering;** proof density is the cheapest conversion lever; proof must match the promise.
- **One CTA / 1:1 attention ratio;** two-step opt-in for forms; CTA states what to do + why now.
- **Simplicity wins:** title + info + button out-converts clever layouts for waitlist pages.
- **Ad-to-page message match (ThrillX step 1):** the page keeps the exact promise of the ad/post that drove the click.
- **Customer language, zero internal jargon (ThrillX step 2);** qualify AND disqualify explicitly.
- **Above-the-fold dominance (ThrillX step 3):** ~60% never scroll; value prop + proof + risk-reducer must all be in the fold.
- **Micro-commitment offer (ThrillX step 4):** a low-friction next step, not a hard sell.
- **2026 trends:** mobile-first, speed/Core Web Vitals, purposeful interactivity, founder video, FAQ/GEO structure, continuous headline/image/CTA testing (one variable per week, above the fold).

---

## What the page already does well

These are genuinely strong and worth keeping:

| Element | Why it matches the videos |
|---|---|
| Outcome-driven headline ("Automatically Invest. Retire Years Earlier.") | Dream outcome up front, not a product description |
| Real scarcity (300 founder cap, live progress bar wired to the API) | Hormozi: urgency/scarcity only when real — this is real |
| Email-only waitlist, "No payment today" | Perfect micro-commitment (ThrillX step 4); minimizes effort & sacrifice |
| Interactive freedom-date calculator | 2026 interactivity trend; personalizes the dream outcome ("free at 49") |
| Price transparency ($99 lifetime vs $8/mo, competitor fees shown) | Hormozi shows his $5k price on his own pages for the same reason |
| Comparison table vs Autopilot/Alinea/advisors | Objection handling; "why you over alternatives" |
| Honest disqualifier ("iOS only") | Deliberate disqualification builds trust |
| Mobile-specific layouts throughout | Mobile-first, ~68% of traffic |
| Plausible analytics installed | Prerequisite for the weekly-test cadence |

---

## Gaps and improvements (prioritized)

### P1 — The conversion action is a full page away from the fold
The single most valuable action (email capture) lives at the very bottom (`#join`). The hero's
primary CTA ("See My Freedom Date") only scrolls to the calculator, and the calculator's CTA
("Start My Automated Plan") scrolls again to the bottom. Hormozi's opt-in doctrine and the 1:1
attention-ratio principle both say the one action should be reachable immediately.

**Fix:**
- Put a **two-step opt-in in the hero**: a single button ("Reserve My Founder Spot") that reveals
  the email field on click (Hormozi explicitly teaches button-first → form-second; it raises
  opt-in rates via micro-commitment).
- Make the **calculator result an email gate or capture moment**: after the user sees "Free at
  49," that's the emotional peak — capture the email right there ("Email me my freedom plan")
  instead of bouncing them to the footer.
- Point every CTA on the page at the same single action.

### P2 — Headline and subheadline fail the customer-language test
"Automatically Invest. Retire Years Earlier." is good but generic — no avatar, no specificity.
The subheadline is internal jargon: *"builds and maintains your long-term investing system —
so your money compounds toward early corporate freedom."* No customer says "long-term
investing system" or "corporate freedom." ThrillX step 2 and Hormozi's 15-second test both
flag this.

**Fix (candidates to A/B test):**
- Add the avatar + a specific number you already have: **"Salaried and stuck? Retire 6 years
  earlier on autopilot."** (the 6.2-year stat is already on the page — promote it into the headline)
- Subheadline in plain words: *"FRED invests a slice of every paycheck into index funds
  automatically — so you can quit the 9–5 years sooner without thinking about it."*
- Explicit qualify/disqualify line near the hero: *"Built for W-2 employees who want out early.
  Not for day traders."*

### P3 — Proof is thin, and what exists doesn't match the promise
Current proof: "Trusted by 180+ founding members," partner logos, and "Avg Freedom Date: 6.2
years earlier." Problems:
- **Zero testimonials.** Hormozi calls proof density the cheapest lever most pages skip.
- **Proof-promise mismatch:** the promise is retiring earlier, but nobody has retired via FRED
  yet — "Avg Freedom Date 6.2 years earlier" is a projection dressed as a result. That
  undermines perceived likelihood if a visitor thinks about it for two seconds.
- "Secured by industry leaders" listing **Cloudflare** next to Alpaca/Plaid reads as filler —
  every website uses Cloudflare.

**Fix:**
- Add 2–3 real founding-member quotes (name, photo/initials, before → after → bridge format:
  "I was doing nothing with my savings → now $X/mo invests itself → freedom date moved from
  65 to 52"). Put one **above the fold**.
- Relabel projections honestly: *"Founding members project retiring 6.2 years earlier on
  average"* — honesty is the brand ("Brutally Honest" video: trust beats polish).
- Consider process proof: a real screenshot of the actual app/portfolio instead of the mock
  "$1,482,902.14" card, or the founder's own account. Keep illustrative numbers clearly labeled.
- Trust bar: "Brokerage by Alpaca (member FINRA/SIPC) · Bank connections by Plaid" is stronger
  and more specific than a generic "secured by" row.

### P4 — Attention leaks above the fold
Nav has 3 anchor links + a nav CTA; hero has 2 CTAs; the mobile fold stacks badge → headline →
trust line → image card → paragraph → stat line → 2 buttons, which pushes the CTA below the
fold on small phones. Hormozi's "2026 strategy" video is explicit: title + info + button;
fewer elements = more signups.

**Fix:**
- Cut the hero to one CTA (drop or demote "How it works" — the scroll journey covers it).
- Trim the mobile fold so headline → one-line subhead → proof line → CTA all fit on one screen.
- Keep nav anchors if you want, but the nav CTA and hero CTA should be the same action with
  the same label.

### P5 — The guarantee is the strongest lever on the page and it's 9px fine print
"Full refund," "No payment today," and "pricing switches to $8/month once full" are all buried
in `text-[9px]` under the pricing card. Risk reversal directly raises perceived likelihood and
lowers sacrifice — Hormozi would make this a headline element, not a footnote.

**Fix:** Promote to a visible badge/line under the pricing CTA and next to the waitlist form:
*"No payment today · Full refund if you're not more confident in your financial future."*

### P6 — No FAQ, no objection section
Hormozi's own workshop page carries pricing and disqualification in an FAQ; ThrillX teaches
answering objections early. Obvious unanswered objections: Is my money safe if FRED dies? What
exactly do I pay and when? Why iOS only? How is this different from just buying VTI myself?
What happens after I join the waitlist?

**Fix:** Add a short FAQ (5–7 questions) above the join section. Bonus: mark it up with FAQ
schema — feeds the 2026 "GEO" trend of being citable by AI search.

### P7 — Technical fragility that silently kills proof and speed
- **Hero social-proof lines start at `opacity-0`** and only appear after the stats API call
  succeeds. If `lpapi.fredvested.com` is slow or down, the page shows *no* social proof at all.
  Render the fallback numbers server-side/statically and let the fetch update them.
- **The hero image is hot-linked from `lh3.googleusercontent.com/aida-public/...`** — an
  AI-generated image on a Google-owned CDN with no delivery guarantee. If that URL dies, the
  hero breaks. Self-host it (and compress it).
- Font payload: Inter (6 weights) + Montserrat + Material Icons + Material Symbols = heavy for
  Core Web Vitals. Subset to the weights actually used; consider inlining the icon glyphs used.
- Stray jargon: "Freedom Date **w/ Strats**" in the comparison table means nothing to a
  visitor.

### P8 — No message-match or testing plan for launch traffic
ThrillX step 1: the page must keep the exact promise of the ad/post that brought the visitor.
Hormozi's testing doctrine: test one above-the-fold variable per week — headline first, then
image, then CTA (~90% of gains live there).

**Fix (for relaunch):**
- Mirror the winning ad hook in the hero verbatim; optionally swap the headline via UTM
  parameter for each traffic source (lightweight 2026 personalization).
- Set up a weekly A/B cadence with Plausible goals on: hero CTA click, calculator interaction,
  email submit. Test headline variants (P2 candidates) first.

### P9 — Consider a short founder video
2026 trend across both ThrillX and general research: a 60–90s face-to-camera founder video
(why FRED exists, what the beta is, honest about what's not built yet) raises trust for a
pre-launch fintech more than any layout tweak. Hormozi's 2026 pages lead with his face for the
same recognition/trust reason. Optional, but high leverage for a product asking for trust with
money.

---

## Suggested order of work

| # | Change | Effort | Framework source |
|---|--------|--------|------------------|
| 1 | Two-step opt-in in hero + email capture at calculator result | Medium | Hormozi CTA doctrine, ThrillX fold optimization |
| 2 | Headline/subheadline rewrite in customer language (+ avatar, + specific number) | Low | Hormozi headline rules, ThrillX step 2 |
| 3 | Add testimonials; relabel projections honestly; fix trust bar | Medium | Proof > Promise |
| 4 | Promote guarantee out of fine print | Low | Value equation (risk reversal) |
| 5 | Single-CTA cleanup + tighter mobile fold | Low | 1:1 attention ratio, simplicity |
| 6 | FAQ with objections + FAQ schema | Low | Hormozi FAQ pattern, GEO |
| 7 | Static-render proof numbers; self-host hero image; trim fonts | Low | 2026 speed/CWV |
| 8 | Message-match + weekly headline test cadence at relaunch | Ongoing | ThrillX step 1, Hormozi 3 tests |
| 9 | Founder video | High | 2026 trend |
