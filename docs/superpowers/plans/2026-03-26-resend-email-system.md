# Resend Email System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integrate Resend into the Spring Boot backend to send a branded HTML confirmation email on every new waitlist signup, plus a one-time temporary admin endpoint to batch-send to all existing entries.

**Architecture:** A new `EmailService` wraps the Resend Java SDK and owns the HTML template. `WaitlistController` calls it (fire-and-forget, failure never blocks signup) after each successful `repository.save()`. A temporary `AdminController` exposes `POST /api/admin/batch-email` protected by `X-Admin-Secret` header — deleted and redeployed after the one-time batch send.

**Tech Stack:** Spring Boot 3.5, Java 17, Resend Java SDK (`com.resend:resend-java:3.1.0`), JUnit 5 + MockMvc + Mockito (via `spring-boot-starter-test`)

---

### Task 1: Fix test dependencies, add Resend SDK, configure properties

**Files:**
- Modify: `backend/build.gradle`
- Modify: `backend/src/main/resources/application-dev.properties`
- Modify: `backend/src/main/resources/application-prod.properties`

- [ ] **Step 1: Fix the test dependencies in build.gradle**

The four existing `testImplementation` lines use nonstandard artifact names that don't exist on Maven Central and will fail when tests are run. Replace all of them with the standard Spring Boot test starter.

In `backend/build.gradle`, replace:
```groovy
testImplementation 'org.springframework.boot:spring-boot-starter-data-jpa-test'
testImplementation 'org.springframework.boot:spring-boot-starter-flyway-test'
testImplementation 'org.springframework.boot:spring-boot-starter-validation-test'
testImplementation 'org.springframework.boot:spring-boot-starter-webmvc-test'
testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
```
with:
```groovy
testImplementation 'org.springframework.boot:spring-boot-starter-test'
testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
```

`spring-boot-starter-test` bundles JUnit 5, Mockito, MockMvc, and AssertJ — everything this plan uses.

- [ ] **Step 2: Add Resend SDK dependency**

In the same `dependencies` block, add after the existing `implementation` lines:
```groovy
implementation 'com.resend:resend-java:3.1.0'
```

- [ ] **Step 3: Add properties to application-dev.properties**

Append to `backend/src/main/resources/application-dev.properties`:
```properties
# Resend (email)
resend.api-key=${RESEND_API_KEY:re_test_placeholder}

# Admin batch endpoint secret
admin.secret=${ADMIN_SECRET:dev-secret}
```

- [ ] **Step 4: Add properties to application-prod.properties**

Append to `backend/src/main/resources/application-prod.properties`:
```properties
# Resend (email)
resend.api-key=${RESEND_API_KEY}

# Admin batch endpoint secret
admin.secret=${ADMIN_SECRET}
```

- [ ] **Step 5: Verify Resend dependency resolves**

```bash
cd backend && ./gradlew dependencies --configuration compileClasspath 2>&1 | grep resend
```

Expected output includes:
```
\--- com.resend:resend-java:3.1.0
```

- [ ] **Step 6: Commit**

```bash
git add backend/build.gradle \
        backend/src/main/resources/application-dev.properties \
        backend/src/main/resources/application-prod.properties
git commit -m "chore: fix test dependencies, add Resend SDK, configure email/admin properties"
```

---

### Task 2: Create EmailService with branded HTML template

**Files:**
- Create: `backend/src/main/java/com/fredvested/web/service/EmailService.java`
- Create: `backend/src/test/java/com/fredvested/web/service/EmailServiceTest.java`

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/fredvested/web/service/EmailServiceTest.java`:

```java
package com.fredvested.web.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EmailServiceTest {

    @Test
    void buildHtmlEmail_containsExpectedCopyAndBranding() {
        EmailService service = new EmailService();
        String html = service.buildHtmlEmail();

        assertThat(html).contains("You&#39;re on the <strong>FRED private beta waitlist</strong>");
        assertThat(html).contains("clock out early");
        assertThat(html).contains("48 hours to claim your spot");
        assertThat(html).contains("Signups are being reviewed in waves");
        assertThat(html).contains("If invited, you&#39;ll get an email with next steps");
        assertThat(html).contains("The FRED Team");
        assertThat(html).contains("#0F172A");
        assertThat(html).contains("#135bec");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
cd backend && ./gradlew test --tests "com.fredvested.web.service.EmailServiceTest" 2>&1 | tail -20
```

Expected: compilation failure — `EmailService` does not exist yet.

- [ ] **Step 3: Create EmailService.java**

Create `backend/src/main/java/com/fredvested/web/service/EmailService.java`:

```java
package com.fredvested.web.service;

import com.resend.Resend;
import com.resend.services.emails.model.CreateEmailOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class EmailService {

    @Value("${resend.api-key}")
    private String apiKey;

    public void sendConfirmationEmail(String toEmail) {
        Resend resend = new Resend(apiKey);
        CreateEmailOptions params = CreateEmailOptions.builder()
                .from("FRED <fred@fredvested.com>")
                .to(toEmail)
                .subject("You're in")
                .html(buildHtmlEmail())
                .build();
        resend.emails().send(params);
    }

    String buildHtmlEmail() {
        return """
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>You&#39;re in</title>
</head>
<body style="margin:0;padding:0;background-color:#f6f6f8;font-family:'Inter','Helvetica Neue',Helvetica,Arial,sans-serif;">
  <table width="100%" cellpadding="0" cellspacing="0" style="background-color:#f6f6f8;padding:40px 16px;">
    <tr>
      <td align="center">
        <table width="600" cellpadding="0" cellspacing="0" style="max-width:600px;width:100%;background-color:#ffffff;border:1px solid #e2e8f0;border-radius:8px;overflow:hidden;">

          <!-- Header -->
          <tr>
            <td style="background-color:#0F172A;padding:32px 40px;text-align:center;border-bottom:3px solid #135bec;">
              <span style="font-family:Georgia,'Times New Roman',serif;font-size:42px;font-weight:900;font-style:italic;color:#135bec;letter-spacing:-1px;line-height:1;">FRED</span>
            </td>
          </tr>

          <!-- Body -->
          <tr>
            <td style="padding:40px 40px 32px;color:#0F172A;">
              <p style="margin:0 0 24px;font-size:16px;line-height:1.6;color:#0F172A;">
                You&#39;re on the <strong>FRED private beta waitlist</strong>.
              </p>
              <p style="margin:0 0 24px;font-size:15px;line-height:1.7;color:#334155;">
                A few early signups may not have received a confirmation email, so I wanted to make sure you got one now.
              </p>
              <p style="margin:0 0 24px;font-size:15px;line-height:1.7;color:#334155;">
                FRED is built to help you automatically invest part of every paycheck so you can clock out early &#8212; for good.
              </p>

              <!-- Divider -->
              <hr style="border:none;border-top:1px solid #e2e8f0;margin:28px 0;">

              <!-- What happens next -->
              <p style="margin:0 0 16px;font-size:13px;font-weight:600;letter-spacing:0.08em;text-transform:uppercase;color:#64748B;">
                What happens next
              </p>
              <table cellpadding="0" cellspacing="0" style="width:100%;">
                <tr>
                  <td style="padding:6px 0;vertical-align:top;width:20px;">
                    <span style="color:#135bec;font-weight:700;">&#8212;</span>
                  </td>
                  <td style="padding:6px 0 6px 8px;font-size:15px;line-height:1.6;color:#334155;">
                    Signups are being reviewed in waves
                  </td>
                </tr>
                <tr>
                  <td style="padding:6px 0;vertical-align:top;width:20px;">
                    <span style="color:#135bec;font-weight:700;">&#8212;</span>
                  </td>
                  <td style="padding:6px 0 6px 8px;font-size:15px;line-height:1.6;color:#334155;">
                    If invited, you&#39;ll get an email with next steps to claim access
                  </td>
                </tr>
                <tr>
                  <td style="padding:6px 0;vertical-align:top;width:20px;">
                    <span style="color:#135bec;font-weight:700;">&#8212;</span>
                  </td>
                  <td style="padding:6px 0 6px 8px;font-size:15px;line-height:1.6;color:#334155;">
                    Access is limited &#8212; you&#39;ll have 48 hours to claim your spot when invited
                  </td>
                </tr>
              </table>

              <!-- Divider -->
              <hr style="border:none;border-top:1px solid #e2e8f0;margin:28px 0;">

              <p style="margin:0;font-size:15px;line-height:1.7;color:#334155;">
                You don&#39;t need to do anything else right now &#8212; you&#39;re in line.
              </p>
            </td>
          </tr>

          <!-- Footer -->
          <tr>
            <td style="background-color:#f8fafc;padding:24px 40px;border-top:1px solid #e2e8f0;">
              <p style="margin:0 0 4px;font-size:14px;font-weight:600;color:#0F172A;">The FRED Team</p>
              <p style="margin:0;font-size:13px;color:#94a3b8;">fred@fredvested.com</p>
            </td>
          </tr>

        </table>

        <!-- Below-card note -->
        <p style="margin:20px 0 0;font-size:12px;color:#94a3b8;text-align:center;">
          You&#39;re receiving this because you signed up at fredvested.com
        </p>
      </td>
    </tr>
  </table>
</body>
</html>
""";
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd backend && ./gradlew test --tests "com.fredvested.web.service.EmailServiceTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL` with 1 test passing.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/fredvested/web/service/EmailService.java \
        backend/src/test/java/com/fredvested/web/service/EmailServiceTest.java
git commit -m "feat: add EmailService with Resend integration and branded HTML template"
```

---

### Task 3: Wire EmailService into WaitlistController

**Files:**
- Modify: `backend/src/main/java/com/fredvested/web/controller/WaitlistController.java`
- Create: `backend/src/test/java/com/fredvested/web/controller/WaitlistControllerEmailTest.java`

- [ ] **Step 1: Write the failing tests**

Create `backend/src/test/java/com/fredvested/web/controller/WaitlistControllerEmailTest.java`:

```java
package com.fredvested.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fredvested.web.model.WaitlistEntry;
import com.fredvested.web.repository.WaitlistRepository;
import com.fredvested.web.service.EmailService;
import com.fredvested.web.service.RateLimiterService;
import com.fredvested.web.service.TurnstileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WaitlistController.class)
class WaitlistControllerEmailTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean WaitlistRepository repository;
    @MockBean TurnstileService turnstileService;
    @MockBean RateLimiterService rateLimiterService;
    @MockBean EmailService emailService;

    @Test
    void joinWaitlist_sendsConfirmationEmail_onSuccessfulSignup() throws Exception {
        when(rateLimiterService.isAllowed(anyString())).thenReturn(true);
        when(turnstileService.verifyToken(anyString())).thenReturn(true);
        when(repository.existsByEmail("test@example.com")).thenReturn(false);
        when(repository.countByStatus(WaitlistEntry.WaitlistStatus.WAITLISTFOUNDER)).thenReturn(0L);
        when(repository.count()).thenReturn(1L);
        when(repository.getAverageFreedomAge()).thenReturn(40.0);

        mockMvc.perform(post("/api/waitlist")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "email", "test@example.com",
                        "freedomAge", 45,
                        "turnstileToken", "token"
                ))))
                .andExpect(status().isOk());

        verify(emailService, times(1)).sendConfirmationEmail("test@example.com");
    }

    @Test
    void joinWaitlist_stillSucceeds_whenEmailServiceThrows() throws Exception {
        when(rateLimiterService.isAllowed(anyString())).thenReturn(true);
        when(turnstileService.verifyToken(anyString())).thenReturn(true);
        when(repository.existsByEmail("test@example.com")).thenReturn(false);
        when(repository.countByStatus(WaitlistEntry.WaitlistStatus.WAITLISTFOUNDER)).thenReturn(0L);
        when(repository.count()).thenReturn(1L);
        when(repository.getAverageFreedomAge()).thenReturn(40.0);
        doThrow(new RuntimeException("Resend unavailable"))
                .when(emailService).sendConfirmationEmail(anyString());

        mockMvc.perform(post("/api/waitlist")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "email", "test@example.com",
                        "freedomAge", 45,
                        "turnstileToken", "token"
                ))))
                .andExpect(status().isOk());
    }
}
```

- [ ] **Step 2: Run to verify they fail**

```bash
cd backend && ./gradlew test --tests "com.fredvested.web.controller.WaitlistControllerEmailTest" 2>&1 | tail -20
```

Expected: tests fail — `EmailService` is not yet injected into `WaitlistController`, so the `@MockBean` causes a wiring error or the `verify` check fails.

- [ ] **Step 3: Modify WaitlistController.java**

Add two imports at the top of the file (after the existing imports):
```java
import com.fredvested.web.service.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
```

Add two fields after the existing `@Autowired` fields:
```java
@Autowired
private EmailService emailService;

private static final Logger log = LoggerFactory.getLogger(WaitlistController.class);
```

Replace the existing step-4 and step-5 block inside `joinWaitlist` (currently `repository.save(entry);` followed by `return ResponseEntity.ok(buildStatsMap(newStatus.name()));`) with:
```java
        // 4. Save Entry
        WaitlistEntry entry = new WaitlistEntry();
        entry.setEmail(email);
        entry.setFreedomAge(request.getFreedomAge());
        entry.setStatus(newStatus);
        entry.setIpHash(hashedIp);
        repository.save(entry);

        // 5. Send confirmation email (failure must not affect signup response)
        try {
            emailService.sendConfirmationEmail(email);
        } catch (Exception e) {
            log.error("Failed to send confirmation email to {}: {}", email, e.getMessage());
        }

        // 6. Return updated stats and status
        return ResponseEntity.ok(buildStatsMap(newStatus.name()));
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
cd backend && ./gradlew test --tests "com.fredvested.web.controller.WaitlistControllerEmailTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL` with 2 tests passing.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/fredvested/web/controller/WaitlistController.java \
        backend/src/test/java/com/fredvested/web/controller/WaitlistControllerEmailTest.java
git commit -m "feat: send confirmation email on successful waitlist signup"
```

---

### Task 4: Create temporary AdminController for one-time batch send

**Files:**
- Create: `backend/src/main/java/com/fredvested/web/controller/AdminController.java`
- Create: `backend/src/test/java/com/fredvested/web/controller/AdminControllerTest.java`

- [ ] **Step 1: Write the failing tests**

Create `backend/src/test/java/com/fredvested/web/controller/AdminControllerTest.java`:

```java
package com.fredvested.web.controller;

import com.fredvested.web.model.WaitlistEntry;
import com.fredvested.web.repository.WaitlistRepository;
import com.fredvested.web.service.EmailService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminController.class)
@TestPropertySource(properties = "admin.secret=test-secret")
class AdminControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean WaitlistRepository repository;
    @MockBean EmailService emailService;

    @Test
    void batchEmail_returns403_whenSecretIsWrong() throws Exception {
        mockMvc.perform(post("/api/admin/batch-email")
                .header("X-Admin-Secret", "wrong-secret"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(emailService);
    }

    @Test
    void batchEmail_sendsToAllEntriesWithEmail_andReturnsCount() throws Exception {
        WaitlistEntry e1 = new WaitlistEntry();
        e1.setEmail("a@example.com");
        e1.setStatus(WaitlistEntry.WaitlistStatus.WAITLISTFOUNDER);

        WaitlistEntry e2 = new WaitlistEntry();
        e2.setEmail("b@example.com");
        e2.setStatus(WaitlistEntry.WaitlistStatus.WAITLISTNORMAL);

        WaitlistEntry e3 = new WaitlistEntry(); // null email — should be skipped
        e3.setStatus(WaitlistEntry.WaitlistStatus.WAITLISTFOUNDER);

        when(repository.findAll()).thenReturn(List.of(e1, e2, e3));

        mockMvc.perform(post("/api/admin/batch-email")
                .header("X-Admin-Secret", "test-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sent").value(2))
                .andExpect(jsonPath("$.skipped").value(1))
                .andExpect(jsonPath("$.errors").value(0));

        verify(emailService).sendConfirmationEmail("a@example.com");
        verify(emailService).sendConfirmationEmail("b@example.com");
    }

    @Test
    void batchEmail_countsErrors_andContinuesLoop() throws Exception {
        WaitlistEntry e1 = new WaitlistEntry();
        e1.setEmail("good@example.com");
        e1.setStatus(WaitlistEntry.WaitlistStatus.WAITLISTFOUNDER);

        WaitlistEntry e2 = new WaitlistEntry();
        e2.setEmail("bad@example.com");
        e2.setStatus(WaitlistEntry.WaitlistStatus.WAITLISTNORMAL);

        when(repository.findAll()).thenReturn(List.of(e1, e2));
        doThrow(new RuntimeException("Resend error"))
                .when(emailService).sendConfirmationEmail("bad@example.com");

        mockMvc.perform(post("/api/admin/batch-email")
                .header("X-Admin-Secret", "test-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sent").value(1))
                .andExpect(jsonPath("$.skipped").value(0))
                .andExpect(jsonPath("$.errors").value(1));
    }
}
```

- [ ] **Step 2: Run to verify they fail**

```bash
cd backend && ./gradlew test --tests "com.fredvested.web.controller.AdminControllerTest" 2>&1 | tail -20
```

Expected: compilation failure — `AdminController` does not exist yet.

- [ ] **Step 3: Create AdminController.java**

Create `backend/src/main/java/com/fredvested/web/controller/AdminController.java`:

```java
package com.fredvested.web.controller;

import com.fredvested.web.model.WaitlistEntry;
import com.fredvested.web.repository.WaitlistRepository;
import com.fredvested.web.service.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    @Value("${admin.secret}")
    private String adminSecret;

    @Autowired
    private WaitlistRepository repository;

    @Autowired
    private EmailService emailService;

    @PostMapping("/batch-email")
    public ResponseEntity<?> sendBatchEmail(@RequestHeader("X-Admin-Secret") String secret) {
        if (!adminSecret.equals(secret)) {
            return ResponseEntity.status(403).body(Map.of("message", "Forbidden"));
        }

        List<WaitlistEntry> entries = repository.findAll();
        int sent = 0, skipped = 0, errors = 0;

        for (WaitlistEntry entry : entries) {
            if (entry.getEmail() == null || entry.getEmail().isEmpty()) {
                skipped++;
                continue;
            }
            try {
                emailService.sendConfirmationEmail(entry.getEmail());
                sent++;
            } catch (Exception e) {
                log.error("Batch send failed for {}: {}", entry.getEmail(), e.getMessage());
                errors++;
            }
        }

        return ResponseEntity.ok(Map.of("sent", sent, "skipped", skipped, "errors", errors));
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
cd backend && ./gradlew test --tests "com.fredvested.web.controller.AdminControllerTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL` with 3 tests passing.

- [ ] **Step 5: Run the full test suite**

```bash
cd backend && ./gradlew test 2>&1 | tail -30
```

Expected: `BUILD SUCCESSFUL` — all tests pass.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/fredvested/web/controller/AdminController.java \
        backend/src/test/java/com/fredvested/web/controller/AdminControllerTest.java
git commit -m "feat: add temporary admin batch-email endpoint"
```

---

### Task 5: Deploy, batch send, and remove AdminController

- [ ] **Step 1: Add env vars in Railway**

In the Railway dashboard, add to the backend service environment:
- `RESEND_API_KEY` — your Resend API key
- `ADMIN_SECRET` — a strong secret string (store it somewhere safe; you'll need it for the curl call)

- [ ] **Step 2: Push and wait for Railway to deploy**

```bash
git push origin develop
```

Wait for the Railway deploy to complete (check Railway dashboard).

- [ ] **Step 3: Trigger the batch send**

Replace `<your-prod-api-url>` with your Railway backend domain and `<your-ADMIN_SECRET>` with the value you set:

```bash
curl -X POST https://<your-prod-api-url>/api/admin/batch-email \
  -H "X-Admin-Secret: <your-ADMIN_SECRET>"
```

Expected response:
```json
{ "sent": N, "skipped": 0, "errors": 0 }
```

`sent` should match the number of rows in `waitlist_signups`. If `errors` > 0, check Railway logs for which addresses failed.

- [ ] **Step 4: Delete AdminController and its test**

```bash
rm backend/src/main/java/com/fredvested/web/controller/AdminController.java
rm backend/src/test/java/com/fredvested/web/controller/AdminControllerTest.java
```

- [ ] **Step 5: Run the full test suite to confirm nothing is broken**

```bash
cd backend && ./gradlew test 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit and redeploy**

```bash
git add -u
git commit -m "chore: remove temporary admin batch-email endpoint after one-time send"
git push origin develop
```

Railway redeploys. The `/api/admin/batch-email` endpoint no longer exists.
