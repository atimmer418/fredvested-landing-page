package com.fredvested.web;

import com.fredvested.web.model.EmailEvent;
import com.fredvested.web.model.EmailMessage;
import com.fredvested.web.model.WaitlistEntry;
import com.fredvested.web.repository.EmailEventRepository;
import com.fredvested.web.repository.EmailMessageRepository;
import com.fredvested.web.repository.WaitlistRepository;
import com.fredvested.web.service.EmailOutboxPublisher;
import com.fredvested.web.service.EmailService;
import com.fredvested.web.service.TurnstileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The funnel against a REAL MySQL: Flyway applies V1..V5 to an empty schema,
 * Hibernate validates the entities against it, and the HTTP layer, the outbox
 * publisher (with Resend mocked), the confirmation and the webhook all run on
 * real rows and real queries. The slice tests prove each part with mocks; this
 * proves the parts agree with the schema and with each other.
 *
 * Runs only when FRED_IT_JDBC_URL points at a scratch database (it is wiped):
 *   FRED_IT_JDBC_URL='jdbc:mysql://localhost:3306/fred_it?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC' \
 *   FRED_IT_DB_USER=root FRED_IT_DB_PASSWORD=... ./gradlew test --tests '*WaitlistFunnelIntegrationTest'
 * (No Docker on the build machine, so no Testcontainers; see FREDdocs/analytics-qa.md.)
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "FRED_IT_JDBC_URL", matches = ".+")
@TestPropertySource(properties = {
        "spring.datasource.url=${FRED_IT_JDBC_URL}",
        "spring.datasource.username=${FRED_IT_DB_USER:root}",
        "spring.datasource.password=${FRED_IT_DB_PASSWORD:}",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "waitlist.double-opt-in.enabled=true",
        "waitlist.stats-cache-ms=0",
        "waitlist.us-only=false",
        "email.outbox.poll-ms=3600000",
        "email.outbox.initial-delay-ms=3600000",
        "resend.api-key=re_test",
        "resend.webhook-secret=whsec_dGVzdC1zZWNyZXQ=",
        "cloudflare.turnstile.secret=1x0000000000000000000000000000000AA",
        "landing.url=https://fredvested.com",
        "api.public-url=https://lpapi-dev.fredvested.com",
        "email.postal-address=PO Box 1, Baltimore, MD 21201",
        "cors.allowed.origins=http://localhost:5500",
})
class WaitlistFunnelIntegrationTest {

    private static final byte[] SECRET = "test-secret".getBytes(StandardCharsets.UTF_8);

    @Autowired MockMvc mvc;
    @Autowired WaitlistRepository waitlist;
    @Autowired EmailMessageRepository outbox;
    @Autowired EmailEventRepository events;
    @Autowired EmailOutboxPublisher publisher;
    @MockBean EmailService emailService;
    @MockBean TurnstileService turnstile;

    @BeforeEach
    void wipe() throws Exception {
        events.deleteAll();
        outbox.deleteAll();
        waitlist.deleteAll();
        when(turnstile.verifyToken(anyString())).thenReturn(true);
        when(emailService.send(anyString(), anyString(), anyString(), anyString(), anyMap())).thenReturn("re_it_1");
    }

    static String sign(String id, String ts, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET, "HmacSHA256"));
        mac.update((id + "." + ts + ".").getBytes(StandardCharsets.UTF_8));
        mac.update(body.getBytes(StandardCharsets.UTF_8));
        return "v1," + Base64.getEncoder().encodeToString(mac.doFinal());
    }

    // Each test signs up from its own address: the real IP limiter (3 per minute) is in play.
    private void signup(String json, String ip) throws Exception {
        mvc.perform(post("/api/waitlist").contentType(MediaType.APPLICATION_JSON).header("CF-Connecting-IP", ip).content(json))
                .andExpect(status().isOk());
    }

    private void webhook(String svixId, String body) throws Exception {
        String ts = String.valueOf(Instant.now().getEpochSecond());
        mvc.perform(post("/api/webhooks/resend").contentType(MediaType.APPLICATION_JSON)
                .header("svix-id", svixId).header("svix-timestamp", ts).header("svix-signature", sign(svixId, ts, body))
                .content(body)).andExpect(status().isOk());
    }

    private static String between(String s, String a, String b) {
        int i = s.indexOf(a); if (i < 0) return null;
        int j = s.indexOf(b, i + a.length()); return s.substring(i + a.length(), j < 0 ? s.length() : j);
    }

    @Test
    void signup_outbox_confirm_stats_onRealRows() throws Exception {
        // Signup: row + pending confirmation in one transaction, nothing sent yet.
        mvc.perform(post("/api/waitlist").contentType(MediaType.APPLICATION_JSON).header("CF-Connecting-IP", "203.0.113.1")
                .content("{\"email\":\"it-one@example.com\",\"turnstileToken\":\"t\",\"age\":30,\"investMonthly\":2000,\"retireMonthly\":7500,\"interacted\":true,\"returnAssumptionPct\":10,\"utmSource\":\"tiktok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITLISTNORMAL"))
                .andExpect(jsonPath("$.requiresConfirmation").value(true))
                .andExpect(jsonPath("$.count").value(0)); // confirmed rows only
        WaitlistEntry row = waitlist.findByEmail("it-one@example.com");
        assertNotNull(row);
        assertNull(row.getConfirmedAt());
        assertEquals("tiktok", row.getUtmSource());
        List<EmailMessage> pending = outbox.findByWaitlistIdAndTemplateAndStatus(row.getId(), EmailMessage.TEMPLATE_CONFIRMATION, EmailMessage.STATUS_PENDING);
        assertEquals(1, pending.size());
        verifyNoInteractions(emailService);

        // Publisher: mints tokens, stores hashes, sends (mocked), records the id.
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        assertTrue(publisher.publish(pending.get(0)));
        verify(emailService).send(eq("it-one@example.com"), eq("Confirm your email for FRED's waitlist"), html.capture(), anyString(), anyMap());
        String rawConfirm = between(html.getValue(), "/api/waitlist/confirm?token=", "\"");
        assertNotNull(rawConfirm);
        row = waitlist.findByEmail("it-one@example.com");
        assertNotNull(row.getConfirmationTokenHash());
        assertNotNull(row.getConfirmationSentAt());
        assertEquals(EmailMessage.STATUS_SENT, outbox.findById(pending.get(0).getId()).orElseThrow().getStatus());
        assertEquals("re_it_1", outbox.findById(pending.get(0).getId()).orElseThrow().getResendEmailId());

        // GET is a page and consumes nothing; the POST confirms once, decides the founder slot.
        mvc.perform(get("/api/waitlist/confirm").param("token", rawConfirm)).andExpect(status().isOk());
        assertNull(waitlist.findByEmail("it-one@example.com").getConfirmedAt());
        mvc.perform(post("/api/waitlist/confirm").contentType(MediaType.APPLICATION_FORM_URLENCODED).param("token", rawConfirm))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("https://fredvested.com/confirmed?status=confirmed&hours=")))
                .andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("&tier=founder")));
        row = waitlist.findByEmail("it-one@example.com");
        assertNotNull(row.getConfirmedAt());
        assertEquals(WaitlistEntry.CONFIRMED_DOUBLE_OPT_IN, row.getConfirmedSource());
        assertEquals(WaitlistEntry.WaitlistStatus.WAITLISTFOUNDER, row.getStatus());
        assertNull(row.getConfirmationTokenHash());

        // Replay = unknown.
        mvc.perform(post("/api/waitlist/confirm").contentType(MediaType.APPLICATION_FORM_URLENCODED).param("token", rawConfirm))
                .andExpect(header().string("Location", "https://fredvested.com/confirmed?status=invalid"));

        // Public numbers now count the confirmed row; the cache was invalidated by the confirmation.
        mvc.perform(get("/api/waitlist/stats")).andExpect(jsonPath("$.count").value(1)).andExpect(jsonPath("$.founderCount").value(1));
    }

    @Test
    void unconfirmedResubmit_looksFresh_andQueuesAnotherConfirmation() throws Exception {
        String body = "{\"email\":\"it-two@example.com\",\"turnstileToken\":\"t\"}";
        signup(body, "203.0.113.2");
        Long id = waitlist.findByEmail("it-two@example.com").getId();
        assertEquals(1, outbox.findByWaitlistIdAndTemplateAndStatus(id, EmailMessage.TEMPLATE_CONFIRMATION, EmailMessage.STATUS_PENDING).size());

        mvc.perform(post("/api/waitlist").contentType(MediaType.APPLICATION_JSON).header("CF-Connecting-IP", "203.0.113.2").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITLISTNORMAL"))
                .andExpect(jsonPath("$.requiresConfirmation").value(true))
                .andExpect(jsonPath("$.realStatus").doesNotExist());
        // The first pending confirmation was retired and a fresh one queued (one pending at a time).
        assertEquals(1, outbox.findByWaitlistIdAndTemplateAndStatus(id, EmailMessage.TEMPLATE_CONFIRMATION, EmailMessage.STATUS_PENDING).size());
        assertEquals(1, outbox.findByWaitlistIdAndTemplateAndStatus(id, EmailMessage.TEMPLATE_CONFIRMATION, EmailMessage.STATUS_SKIPPED).size());
        assertEquals(1, waitlist.count(), "still one row");
    }

    @Test
    void webhook_persistsEvents_ordersStatuses_suppressesOnHardBounce_andThePublisherRefuses() throws Exception {
        signup("{\"email\":\"it-three@example.com\",\"turnstileToken\":\"t\"}", "203.0.113.3");
        WaitlistEntry row = waitlist.findByEmail("it-three@example.com");
        EmailMessage msg = outbox.findByWaitlistIdAndTemplateAndStatus(row.getId(), EmailMessage.TEMPLATE_CONFIRMATION, EmailMessage.STATUS_PENDING).get(0);
        assertTrue(publisher.publish(msg));

        String delivered = "{\"type\":\"email.delivered\",\"created_at\":\"2026-09-25T12:00:00.000Z\",\"data\":{\"email_id\":\"re_it_1\",\"to\":[\"it-three@example.com\"],\"html\":\"<p>secret</p>\"}}";
        String sentLate = "{\"type\":\"email.sent\",\"created_at\":\"2026-09-25T11:59:00.000Z\",\"data\":{\"email_id\":\"re_it_1\"}}";
        String opened = "{\"type\":\"email.opened\",\"created_at\":\"2026-09-25T12:05:00.000Z\",\"data\":{\"email_id\":\"re_it_1\"}}";
        String bounced = "{\"type\":\"email.bounced\",\"created_at\":\"2026-09-25T12:10:00.000Z\",\"data\":{\"email_id\":\"re_it_1\",\"bounce\":{\"type\":\"Permanent\"}}}";

        webhook("it_svix_1", delivered);
        webhook("it_svix_1", delivered); // duplicate delivery
        webhook("it_svix_2", sentLate);  // older status event: recorded, not applied
        webhook("it_svix_3", opened);    // not tracked: 200, never stored
        webhook("it_svix_4", bounced);

        List<EmailEvent> stored = events.findAll();
        assertEquals(3, stored.size(), "delivered once, sent, bounced; the duplicate and the open never stored");
        assertTrue(stored.stream().noneMatch(e -> "email.opened".equals(e.getEventType())));
        assertTrue(stored.stream().noneMatch(e -> e.getPayload() != null && e.getPayload().contains("secret")), "html stripped");

        EmailMessage after = outbox.findById(msg.getId()).orElseThrow();
        assertEquals(EmailMessage.STATUS_BOUNCED, after.getStatus());
        row = waitlist.findByEmail("it-three@example.com");
        assertNotNull(row.getSuppressedAt());
        assertEquals(WaitlistEntry.SUPPRESSION_HARD_BOUNCE, row.getSuppressionReason());

        // A later message to the suppressed address is refused in the send path.
        EmailMessage again = new EmailMessage();
        again.setWaitlistId(row.getId());
        again.setTemplate(EmailMessage.TEMPLATE_CONFIRMATION);
        again.setStatus(EmailMessage.STATUS_PENDING);
        again.setQueuedAt(java.time.LocalDateTime.now());
        again.setAttempts((short) 0);
        again = outbox.save(again);
        clearInvocations(emailService);
        assertTrue(publisher.publish(again));
        verifyNoInteractions(emailService);
        assertEquals(EmailMessage.STATUS_SUPPRESSED, outbox.findById(again.getId()).orElseThrow().getStatus());

        // The suppression view sees it.
        assertTrue(waitlist.findByEmail("it-three@example.com").isSuppressed());
    }

    @Test
    void unknownWebhookMessageId_is200_andStoredWithoutTouchingRows() throws Exception {
        String body = "{\"type\":\"email.delivered\",\"created_at\":\"2026-09-25T12:00:00.000Z\",\"data\":{\"email_id\":\"re_nobody\"}}";
        webhook("it_svix_u", body);
        assertEquals(1, events.count());
        assertEquals(0, waitlist.count());
    }
}
