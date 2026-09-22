# Hormozi Audit Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Apply the 7 approved findings from the Hormozi 80/20 landing-page audit: remove the above-headline proof pill, get the mobile CTA above the fold, floor the live count, rename the capture CTA, tighten the capture copy, shorten the fake spinner, and persist calculator inputs with each signup.

**Architecture:** Frontend changes are surgical edits to `frontend/index.html` (Tailwind utility classes + inline JS) with a CSS rebuild; backend change extends the existing WaitlistRequest DTO -> WaitlistEntry entity -> controller mapping path with three nullable int columns and a boolean, auto-created by `ddl-auto=update`.

**Tech Stack:** Static HTML + Tailwind CSS v4 (`npm run build` in `frontend/`), Spring Boot 3.5 / Java 17 / JPA (`./gradlew test` in `backend/`), MockMvc + Mockito for tests.

## Global Constraints

- No em dashes anywhere in user-facing copy (hyphens ok). No AI-sounding phrasing.
- Desktop layout must not change visually: all mobile spacing edits use base-breakpoint values with `sm:` restoring today's classes.
- The two dashed `legal-ph` placeholder boxes are intentional pre-launch state: do not remove, move, or restyle them.
- The baked fallback count ("180+") in the capture line must remain in the HTML as served.
- `spring.jpa.hibernate.ddl-auto=update` is set in all profiles; new entity columns must be nullable so existing `waitlist_signups` rows stay valid.
- Working tree already has uncommitted changes; every commit must `git add` only the files named in its task.
- After any Tailwind class edit, rebuild CSS: `cd frontend && npm run build`.

---

### Task 1: Remove the proof pill (audit item 1)

**Files:**
- Modify: `frontend/index.html:109-118` (delete `.pulse-dot` CSS), `frontend/index.html:152-156` (delete pill markup), `frontend/index.html:497-502` (updateCounts)

**Interfaces:**
- Produces: `updateCounts(count)` now writes only `#capture-proof-count`. Task 3 edits this same function; Task 3's code block shows the post-Task-1 shape.

- [ ] **Step 1: Delete the pill markup**

Delete these lines (currently 152-156):

```html
  <!-- Social proof pill (count wired to /stats; baked fallback stays honest) -->
  <div class="inline-flex items-center gap-2.5 rounded-full border border-blue-100 bg-white px-3.5 py-1.5 text-[12.5px] font-semibold text-navy shadow-soft mb-6">
    <span class="pulse-dot" aria-hidden="true"></span>
    <span><b id="pill-proof-count">180+</b> people on the waitlist</span>
  </div>
```

- [ ] **Step 2: Delete the pulse-dot CSS**

Delete this block from the inline `<style>` (currently lines 109-118):

```css
  /* Live-count pulse dot in the proof pill */
  .pulse-dot {
    width: 8px; height: 8px; border-radius: 9999px;
    background: #135bec;
    animation: pulse-dot 2s ease-in-out infinite;
  }
  @keyframes pulse-dot {
    0%, 100% { box-shadow: 0 0 0 0 rgba(19, 91, 236, 0.35); }
    50% { box-shadow: 0 0 0 5px rgba(19, 91, 236, 0); }
  }
```

- [ ] **Step 3: Drop the pill reference from updateCounts**

In the inline script, change:

```js
  function updateCounts(count) {
    if (!count) return;
    const formatted = formatCount(count);
    document.getElementById("pill-proof-count").textContent = formatted;
    document.getElementById("capture-proof-count").textContent = formatted;
  }
```

to:

```js
  function updateCounts(count) {
    if (!count) return;
    document.getElementById("capture-proof-count").textContent = formatCount(count);
  }
```

(If Task 3 has already run, its version of this function stands and this step only removes the `pill-proof-count` line.)

- [ ] **Step 4: Verify in browser**

Run: `cd frontend && python3 serve.py` then open `http://127.0.0.1:5500/index.html` in Chrome.
Expected: headline is the first content element after the logo; no pill; DevTools console shows no errors (a TypeError on `pill-proof-count` means Step 3 was missed); reveal flow still opens.

- [ ] **Step 5: Commit**

```bash
git add frontend/index.html
git commit -m "remove waitlist proof pill from above the headline"
```

---

### Task 2: Mobile fold spacing pass (audit item 2)

**Files:**
- Modify: `frontend/index.html:143` (body), `:146-147` (header/logo), `:159` (h1), `:164` (subhead), `:177` and `:189` (slider rows), `:215-216` (reveal button)
- Rebuild: `frontend/dist/output.css` via `npm run build`

**Interfaces:**
- Consumes: Task 1 must already be done (its ~57px of savings is part of the fold budget).
- Produces: nothing other tasks depend on.

Baseline (measured in a real 390px viewport): reveal button top = 722px against a ~660px visible iPhone Safari viewport. Budget below reclaims ~64px on top of Task 1's ~57px, targeting button top <= ~605px.

- [ ] **Step 1: Apply responsive spacing edits**

Each edit changes only the base breakpoint; `sm:` keeps today's value so desktop is unchanged.

Line 143, body tag: change `pt-7` to `pt-5 sm:pt-7`
Line 146: change `<header class="mb-8">` to `<header class="mb-5 sm:mb-8">`
Line 147, logo anchor: change `text-[48px]` to `text-[36px] sm:text-[48px]`
Line 159, h1: change `mb-4` to `mb-3 sm:mb-4`
Line 164, subhead p: change `mb-3` to `mb-2 sm:mb-3`
Lines 177 and 189, the two `<div class="mb-6">` slider-row wrappers: change to `<div class="mb-4 sm:mb-6">` (the third row's `mb-1` at line 202 stays)
Line 216, reveal button: change `mt-5` to `mt-4 sm:mt-5`

- [ ] **Step 2: Rebuild CSS**

Run: `cd frontend && npm run build`
Expected: exits 0; `dist/output.css` newer than `src/input.css`.

- [ ] **Step 3: Measure the fold at 390px**

With `serve.py` running, open `http://127.0.0.1:5500/index.html` in Chrome and run in DevTools console:

```js
const f = document.createElement('iframe');
f.style.cssText = 'position:fixed;top:0;left:0;width:390px;height:844px;border:0;z-index:9999;background:#fff';
f.src = '/index.html';
f.onload = () => {
  const r = f.contentDocument.getElementById('reveal-btn').getBoundingClientRect();
  console.log('button top:', Math.round(r.top), 'bottom:', Math.round(r.bottom));
};
document.body.appendChild(f);
```

Expected: `button top` <= 610. If over, first drop the two slider-row wrappers to `mb-3 sm:mb-6`, remeasure; do not touch the legal-ph box.
Also eyeball the iframe: nothing should look cramped; headline still 2 lines.

- [ ] **Step 4: Verify desktop unchanged**

Resize the browser window wide (>= 1280px), reload `http://127.0.0.1:5500/index.html`.
Expected: logo back at 48px, spacing visually identical to before this task (compare against git stash if unsure).

- [ ] **Step 5: Commit**

```bash
git add frontend/index.html frontend/dist/output.css
git commit -m "pull reveal CTA above the mobile fold with responsive spacing"
```

---

### Task 3: Floor the live count at the baked fallback (audit item 3)

**Files:**
- Modify: `frontend/index.html` `updateCounts` (post-Task-1 shape)

**Interfaces:**
- Consumes: Task 1's single-element `updateCounts`.
- Produces: final `updateCounts(count)`; both call sites (`fetchStats` on load, `updateCounts(data.count)` post-signup) go through it unchanged.

- [ ] **Step 1: Add the floor guard**

Change:

```js
  function updateCounts(count) {
    if (!count) return;
    document.getElementById("capture-proof-count").textContent = formatCount(count);
  }
```

to:

```js
  function updateCounts(count) {
    const el = document.getElementById("capture-proof-count");
    // Never replace the baked fallback with a smaller number: a low or reset live
    // count reads as anti-proof, and formatCount renders raw values at 10 and under.
    const baked = parseInt(el.textContent, 10) || 0;
    if (!count || count < baked) return;
    el.textContent = formatCount(count);
  }
```

- [ ] **Step 2: Verify in browser**

Reload `http://127.0.0.1:5500/index.html` with the local backend either stopped or holding a low count.
Expected: capture line still reads "Join 180+ people already on the list." after load and no console errors. (The dev API count of 1 previously rewrote it to "1".)

- [ ] **Step 3: Commit**

```bash
git add frontend/index.html
git commit -m "keep baked waitlist count when live count is lower"
```

---

### Task 4: Rename the capture CTA (audit item 4)

**Files:**
- Modify: `frontend/index.html:256`

- [ ] **Step 1: Change the button label**

Change:

```html
            <span id="btn-text">Join Waitlist</span>
```

to:

```html
            <span id="btn-text">Claim my spot</span>
```

Do NOT use "founder spot": once the 300-founder cap is hit the backend assigns WAITLISTNORMAL, and the page only ever promises "a shot at one".

- [ ] **Step 2: Verify in browser**

Reload, click reveal, check the capture card.
Expected: button reads "Claim my spot" on one line (whitespace-nowrap intact); loading state "FRED's saving your spot" and success states unchanged.

- [ ] **Step 3: Commit**

```bash
git add frontend/index.html
git commit -m "rename capture CTA to name the payoff"
```

---

### Task 5: Tighten capture copy and resolve the price tease (audit item 6)

**Files:**
- Modify: `frontend/index.html:245-247`

- [ ] **Step 1: Replace the capture paragraph**

Change:

```html
      <p class="mt-2 text-[14.5px] leading-relaxed text-slate-text">
        FRED automates your path to freedom. At launch there are only 300 founding-member offers at a price we&rsquo;ll never bring back, and the waitlist is the only way in. Drop your email for a shot at one.
      </p>
```

to (Option A, approved by Andrew 2026-08-16: qualification happens post-capture via calculator data + waved invites, not page copy):

```html
      <p class="mt-2 text-[14.5px] leading-relaxed text-slate-text">
        FRED automates your path to freedom. Only 300 founding spots at launch, and the waitlist is the only way in. Drop your email for a shot at one.
      </p>
```

- [ ] **Step 2: Verify in browser**

Reload, reveal, read the capture card.
Expected: copy wraps to at most 3 lines at 390px (check in the Task 2 iframe), no price mention, no em dashes.

- [ ] **Step 3: Commit**

```bash
git add frontend/index.html
git commit -m "tighten capture copy and drop unanswered price tease"
```

---

### Task 6: Shorten the post-submit spinner floor (audit item 7)

**Files:**
- Modify: `frontend/index.html:610-612`

Floor is 2s per Andrew's revision (2026-08-16), not the originally proposed 1s.

- [ ] **Step 1: Change the floor**

Change:

```js
      // Keep the loading animation on screen for at least 3s before the payoff.
      const elapsed = Date.now() - loadingStartedAt;
      await new Promise((r) => setTimeout(r, Math.max(0, 3000 - elapsed)));
```

to:

```js
      // Keep the loading animation on screen for at least 2s before the payoff.
      const elapsed = Date.now() - loadingStartedAt;
      await new Promise((r) => setTimeout(r, Math.max(0, 2000 - elapsed)));
```

- [ ] **Step 2: Verify in browser**

With the local backend running on 8081, submit a test email through the form.
Expected: "FRED's saving your spot" shows for about 2s, then the success card fades in. If no local backend is available, verify by code review that only the constant changed.

- [ ] **Step 3: Commit**

```bash
git add frontend/index.html
git commit -m "cut post-submit spinner floor from 3s to 2s"
```

---

### Task 7: Backend accepts and persists calculator inputs (audit item 5, TDD)

**Files:**
- Test: `backend/src/test/java/com/fredvested/web/controller/WaitlistControllerSignupDataTest.java` (create)
- Modify: `backend/src/main/java/com/fredvested/web/controller/WaitlistController.java` (DTO + save mapping + helper)
- Modify: `backend/src/main/java/com/fredvested/web/model/WaitlistEntry.java` (4 new columns)

**Interfaces:**
- Produces: JSON request fields `age`, `investMonthly`, `retireMonthly`, `interacted` (all optional) on `POST /api/waitlist`; entity getters `getCurrentAge()`, `getInvestMonthly()`, `getRetireMonthly()`, `getInteracted()`. Task 8's payload must use exactly these JSON field names.
- Schema: columns `current_age`, `invest_monthly`, `retire_monthly`, `interacted` on `waitlist_signups`, created automatically by `ddl-auto=update` on next boot in each environment.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/fredvested/web/controller/WaitlistControllerSignupDataTest.java`:

```java
package com.fredvested.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fredvested.web.model.WaitlistEntry;
import com.fredvested.web.repository.WaitlistRepository;
import com.fredvested.web.service.EmailService;
import com.fredvested.web.service.RateLimiterService;
import com.fredvested.web.service.TurnstileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WaitlistController.class)
class WaitlistControllerSignupDataTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean WaitlistRepository repository;
    @MockBean TurnstileService turnstileService;
    @MockBean RateLimiterService rateLimiterService;
    @MockBean EmailService emailService;

    @BeforeEach
    void allowThrough() {
        when(rateLimiterService.isAllowed(anyString())).thenReturn(true);
        when(turnstileService.verifyToken(anyString())).thenReturn(true);
        when(repository.existsByEmail("test@example.com")).thenReturn(false);
        when(repository.countByStatus(WaitlistEntry.WaitlistStatus.WAITLISTFOUNDER)).thenReturn(0L);
        when(repository.count()).thenReturn(1L);
        when(repository.getAverageFreedomAge()).thenReturn(40.0);
    }

    private Map<String, Object> basePayload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("email", "test@example.com");
        payload.put("turnstileToken", "token");
        return payload;
    }

    @Test
    void joinWaitlist_persistsCalculatorInputs() throws Exception {
        Map<String, Object> payload = basePayload();
        payload.put("freedomAge", 53);
        payload.put("age", 30);
        payload.put("investMonthly", 2000);
        payload.put("retireMonthly", 7500);
        payload.put("interacted", true);

        mockMvc.perform(post("/api/waitlist")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk());

        ArgumentCaptor<WaitlistEntry> captor = ArgumentCaptor.forClass(WaitlistEntry.class);
        verify(repository).save(captor.capture());
        WaitlistEntry saved = captor.getValue();
        assertEquals(30, saved.getCurrentAge());
        assertEquals(2000, saved.getInvestMonthly());
        assertEquals(7500, saved.getRetireMonthly());
        assertEquals(Boolean.TRUE, saved.getInteracted());
        assertEquals(53, saved.getFreedomAge());
    }

    @Test
    void joinWaitlist_nullsOutOfRangeCalculatorInputs() throws Exception {
        Map<String, Object> payload = basePayload();
        payload.put("age", 150);
        payload.put("investMonthly", -5);
        payload.put("retireMonthly", 999);
        payload.put("interacted", false);

        mockMvc.perform(post("/api/waitlist")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk());

        ArgumentCaptor<WaitlistEntry> captor = ArgumentCaptor.forClass(WaitlistEntry.class);
        verify(repository).save(captor.capture());
        WaitlistEntry saved = captor.getValue();
        assertNull(saved.getCurrentAge());
        assertNull(saved.getInvestMonthly());
        assertNull(saved.getRetireMonthly());
        assertEquals(Boolean.FALSE, saved.getInteracted());
    }

    @Test
    void joinWaitlist_acceptsLegacyPayloadWithoutCalculatorInputs() throws Exception {
        Map<String, Object> payload = basePayload();
        payload.put("freedomAge", 45);

        mockMvc.perform(post("/api/waitlist")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk());

        ArgumentCaptor<WaitlistEntry> captor = ArgumentCaptor.forClass(WaitlistEntry.class);
        verify(repository).save(captor.capture());
        WaitlistEntry saved = captor.getValue();
        assertNull(saved.getCurrentAge());
        assertNull(saved.getInvestMonthly());
        assertNull(saved.getRetireMonthly());
        assertNull(saved.getInteracted());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./gradlew test --tests WaitlistControllerSignupDataTest`
Expected: COMPILE FAILURE ("cannot find symbol: method getCurrentAge()" etc.) - that is the failing state for a compiled language.

- [ ] **Step 3: Add the entity columns**

In `WaitlistEntry.java`, after the `freedomAge` field (line 21), add:

```java
    // Calculator inputs captured at signup; null when out of range or from a
    // pre-calculator client. `interacted` false = untouched slider defaults.
    @Column(name = "current_age")
    private Integer currentAge;

    @Column(name = "invest_monthly")
    private Integer investMonthly;

    @Column(name = "retire_monthly")
    private Integer retireMonthly;

    @Column(name = "interacted")
    private Boolean interacted;
```

- [ ] **Step 4: Extend the DTO and the save mapping**

In `WaitlistController.java`, change the DTO to:

```java
    @Data
    public static class WaitlistRequest {
        private String email;
        private Integer freedomAge;
        private Integer age;
        private Integer investMonthly;
        private Integer retireMonthly;
        private Boolean interacted;
        private String turnstileToken;
    }
```

In `joinWaitlist`, after `entry.setFreedomAge(request.getFreedomAge());` (line 89), add:

```java
        entry.setCurrentAge(clampOrNull(request.getAge(), 18, 60));
        entry.setInvestMonthly(clampOrNull(request.getInvestMonthly(), 0, 10000));
        entry.setRetireMonthly(clampOrNull(request.getRetireMonthly(), 1000, 30000));
        entry.setInteracted(request.getInteracted());
```

Next to the other private helpers, add:

```java
    // Out-of-range values become null rather than clamped: a clamped value would
    // fabricate a data point the user never chose. Bounds mirror the frontend sliders.
    private static Integer clampOrNull(Integer v, int min, int max) {
        return (v == null || v < min || v > max) ? null : v;
    }
```

- [ ] **Step 5: Run the new test to verify it passes**

Run: `cd backend && ./gradlew test --tests WaitlistControllerSignupDataTest`
Expected: 3 tests PASS.

- [ ] **Step 6: Run the full backend suite**

Run: `cd backend && ./gradlew test`
Expected: all tests pass (existing WaitlistControllerEmailTest and AdminControllerTest must stay green).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/fredvested/web/controller/WaitlistController.java backend/src/main/java/com/fredvested/web/model/WaitlistEntry.java backend/src/test/java/com/fredvested/web/controller/WaitlistControllerSignupDataTest.java
git commit -m "persist calculator inputs with waitlist signups"
```

---

### Task 8: Frontend sends calculator inputs (audit item 5, frontend half)

**Files:**
- Modify: `frontend/index.html` (`bindRow`, submit handler)

**Interfaces:**
- Consumes: Task 7's JSON fields `age`, `investMonthly`, `retireMonthly`, `interacted` - names must match exactly.

- [ ] **Step 1: Track slider interaction**

Above `function bindRow(...)`, add:

```js
  // False until the user touches any slider or number input; untouched defaults
  // revealed as-is should not be recorded as chosen numbers.
  let hasInteracted = false;
```

Inside `bindRow`, add `hasInteracted = true;` as the first line of BOTH `input` listeners (the `range.addEventListener("input", ...)` and `num.addEventListener("input", ...)` callbacks; leave the `blur` handler alone).

- [ ] **Step 2: Extend the submit payload**

In the form submit handler, change:

```js
      const payload = { email, turnstileToken: token };
      if (lastFreedomAge !== null) payload.freedomAge = lastFreedomAge;
```

to:

```js
      const { age, invest, retire } = readInputs();
      const payload = {
        email,
        turnstileToken: token,
        age,
        investMonthly: invest,
        retireMonthly: retire,
        interacted: hasInteracted,
      };
      if (lastFreedomAge !== null) payload.freedomAge = lastFreedomAge;
```

- [ ] **Step 3: Verify end-to-end**

Start the local backend (`cd backend && ./gradlew bootRun --args='--spring.profiles.active=local'`) and `serve.py`, open the page, move a slider, reveal, submit a fresh email. In DevTools Network tab inspect the POST body.
Expected: body contains `age`, `investMonthly`, `retireMonthly`, `interacted: true`, `freedomAge`; response 200; success card shows. Then check the row: the new columns are populated. If the local DB is unavailable, verifying the request body shape in the Network tab is sufficient.

- [ ] **Step 4: Commit**

```bash
git add frontend/index.html
git commit -m "send calculator inputs and interaction flag with signup"
```

---

### Task 9: Final verification sweep

**Files:** none modified.

- [ ] **Step 1: Rebuild CSS one last time**

Run: `cd frontend && npm run build`
Expected: exits 0.

- [ ] **Step 2: Full browser pass**

With `serve.py` running, in Chrome:
1. Load `http://127.0.0.1:5500/index.html` - no console errors, no pill, headline first.
2. Run the Task 2 Step 3 iframe measurement - reveal button top <= 610 at 390px.
3. Capture line reads "Join 180+ people already on the list." (floor guard holding against the dev API).
4. Reveal -> result card -> capture card: button "Claim my spot", copy has no price tease.
5. localStorage cleared (`localStorage.clear()`) then reload: form shows again.

- [ ] **Step 3: Backend suite green**

Run: `cd backend && ./gradlew test`
Expected: BUILD SUCCESSFUL.

---

## Self-Review

- Spec coverage: item 1 -> Task 1, item 2 -> Task 2, item 3 -> Task 3, item 4 -> Task 4, item 5 -> Tasks 7+8, item 6 -> Task 5, item 7 -> Task 6. All 7 covered; Task 9 is the cross-cutting verification.
- Placeholder scan: no TBDs; every code step carries the exact content. Task 5 references an approval-time Option B by design (copy decision belongs to Andrew).
- Type consistency: JSON field names `age`/`investMonthly`/`retireMonthly`/`interacted` match between Task 7 DTO and Task 8 payload; entity accessors `getCurrentAge`/`getInvestMonthly`/`getRetireMonthly`/`getInteracted` match the test's assertions; `updateCounts` shape is consistent across Tasks 1 and 3.
