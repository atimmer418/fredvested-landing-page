package com.fredvested.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fredvested.web.email.ChartRenderer;
import com.fredvested.web.email.GrowthChartRenderer;
import com.fredvested.web.email.Projection;
import com.fredvested.web.model.EmailMessage;
import com.fredvested.web.model.WaitlistEntry;
import com.fredvested.web.repository.EmailMessageRepository;
import com.fredvested.web.repository.WaitlistRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EmailOutboxPublisherTest {

    EmailMessageRepository outbox = mock(EmailMessageRepository.class);
    WaitlistRepository waitlist = mock(WaitlistRepository.class);
    EmailService emailService = mock(EmailService.class);
    ObjectMapper objectMapper = new ObjectMapper();

    // The chart seam: a fake PNG, so nothing here depends on the real renderer.
    static final byte[] FAKE_PNG = "not-really-a-png".getBytes(StandardCharsets.UTF_8);
    static final ChartRenderer FAKE_CHART = p -> new GrowthChartRenderer.Png(FAKE_PNG, 1040, 520, 2.0);
    static final ChartRenderer NO_CHART = p -> { throw new AssertionError("chart must not be rendered"); };

    EmailOutboxPublisher publisher = publisher("https://lpapi.fredvested.com/", false, NO_CHART);

    EmailOutboxPublisher publisher(String apiUrl, boolean v2, ChartRenderer chart) {
        return new EmailOutboxPublisher(outbox, waitlist, emailService, objectMapper, apiUrl, "[PO Box pending]", 3, 7, 15, v2, chart);
    }

    WaitlistEntry entry;
    EmailMessage message;

    @BeforeEach
    void setUp() {
        entry = new WaitlistEntry();
        entry.setId(7L);
        entry.setEmail("a@example.com");
        message = new EmailMessage();
        message.setId(1L);
        message.setWaitlistId(7L);
        message.setTemplate(EmailMessage.TEMPLATE_CONFIRMATION);
        message.setStatus(EmailMessage.STATUS_PENDING);
        message.setAttempts((short) 0);
        when(waitlist.findById(7L)).thenReturn(Optional.of(entry));
        when(outbox.claim(eq(1L), any(), any())).thenReturn(1);
    }

    @Test
    void aRowAnotherPublisherAlreadyClaimed_isLeftAlone() throws Exception {
        when(outbox.claim(eq(1L), any(), any())).thenReturn(0);
        assertFalse(publisher.publish(message));
        verifyNoInteractions(emailService);
        verify(outbox, never()).save(any());
        verify(waitlist, never()).findById(any());
    }

    @Test
    void suppressedAddress_isNeverSent() throws Exception {
        entry.setSuppressedAt(LocalDateTime.now());
        entry.setSuppressionReason(WaitlistEntry.SUPPRESSION_HARD_BOUNCE);

        assertTrue(publisher.publish(message));

        verifyNoInteractions(emailService);
        assertEquals(EmailMessage.STATUS_SUPPRESSED, message.getStatus());
        assertTrue(message.getLastError().contains("hard_bounce"));
        verify(waitlist, never()).setConfirmationToken(any(), any(), any());
    }

    @Test
    void confirmationSend_mintsHashedTokens_storesTheResendId_andStampsTheSend() throws Exception {
        when(emailService.send(eq("a@example.com"), anyString(), anyString(), anyString(), anyMap())).thenReturn("re_abc");
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<LocalDateTime> expires = ArgumentCaptor.forClass(LocalDateTime.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);

        assertTrue(publisher.publish(message));

        verify(emailService).send(eq("a@example.com"), eq("Confirm your email for FRED's waitlist"), html.capture(), anyString(), headers.capture());
        // No List-Unsubscribe headers on any email (Andrew, 2026-09-25): mail clients label
        // such messages as list mail; the footer unsubscribe link is the opt-out.
        assertTrue(headers.getValue().isEmpty(), "no list headers on any email: " + headers.getValue());
        assertEquals("re_abc", message.getResendEmailId());
        assertEquals(EmailMessage.STATUS_SENT, message.getStatus());
        assertEquals((short) 1, message.getAttempts());
        assertNotNull(message.getSentAt());
        assertNull(message.getNextAttemptAt());

        // Only hashes are stored, through targeted updates -- the entity snapshot is never merged back.
        verify(waitlist).setConfirmationToken(eq(7L), hash.capture(), expires.capture());
        verify(waitlist).markConfirmationSent(eq(7L), eq(EmailMessage.STATUS_SENT), any());
        verify(waitlist, never()).save(any());
        assertEquals(64, hash.getValue().length());
        assertNotNull(message.getUnsubscribeTokenHash());
        String confirmRaw = between(html.getValue(), "/api/waitlist/confirm?token=", "\"");
        String unsubRaw = between(html.getValue(), "/api/waitlist/unsubscribe?token=", "\"");
        assertEquals(hash.getValue(), ConfirmationTokens.hash(confirmRaw));
        assertEquals(message.getUnsubscribeTokenHash(), ConfirmationTokens.hash(unsubRaw));
        assertFalse(html.getValue().contains(hash.getValue()));
        assertTrue(html.getValue().contains("https://lpapi.fredvested.com/api/waitlist/confirm?token="), "trailing slash on api.public-url is trimmed");
        assertTrue(html.getValue().contains("FREDvested LLC, [PO Box pending]"), "postal address placeholder stays visible until POSTAL_ADDRESS is set");
        assertTrue(Duration.between(LocalDateTime.now(), expires.getValue()).toHours() >= 7 * 24 - 1);
    }

    // Railway dev, 2026-09-24: API_PUBLIC_URL had been entered as http://. Desktop mail
    // clients followed Cloudflare's 301 to https, the phone did not, and either way the
    // single-use token had crossed the network in cleartext first. A public host is
    // always https, whatever the variable says; only local development stays http.
    @Test
    void publicApiUrl_isForcedToHttps_inEveryLink() throws Exception {
        EmailOutboxPublisher misconfigured = publisher("http://lpapi-dev.fredvested.com", false, NO_CHART);
        when(emailService.send(anyString(), anyString(), anyString(), anyString(), anyMap())).thenReturn("re_1");
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);

        assertTrue(misconfigured.publish(message));

        verify(emailService).send(anyString(), anyString(), html.capture(), text.capture(), headers.capture());
        for (String body : new String[] { html.getValue(), text.getValue() }) {
            assertTrue(body.contains("https://lpapi-dev.fredvested.com/api/waitlist/confirm?token="), body);
            assertTrue(body.contains("https://lpapi-dev.fredvested.com/api/waitlist/unsubscribe?token="), body);
            assertFalse(body.contains("http://"), "no cleartext link anywhere: " + body);
        }
    }

    @Test
    void localApiUrls_stayHttp_soLocalDevelopmentKeepsWorking() throws Exception {
        for (String local : new String[] { "http://localhost:8081", "http://127.0.0.1:8081/", "http://192.168.1.20:8081" }) {
            EmailService svc = mock(EmailService.class);
            when(svc.send(anyString(), anyString(), anyString(), anyString(), anyMap())).thenReturn("re_l");
            EmailOutboxPublisher p = new EmailOutboxPublisher(outbox, waitlist, svc, objectMapper, local, "[PO Box pending]", 3, 7, 15, false, NO_CHART);
            ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
            message.setStatus(EmailMessage.STATUS_PENDING);
            assertTrue(p.publish(message));
            verify(svc).send(anyString(), anyString(), html.capture(), anyString(), anyMap());
            String expected = local.replaceAll("/+$", "") + "/api/waitlist/confirm?token=";
            assertTrue(html.getValue().contains(expected), local + " -> " + expected);
        }
    }

    @Test
    void welcomeTemplate_sendsTheWelcomeEmail_withoutAConfirmationToken() throws Exception {
        message.setTemplate(EmailMessage.TEMPLATE_WELCOME);
        when(emailService.send(anyString(), anyString(), anyString(), anyString(), anyMap())).thenReturn("re_w");

        publisher.publish(message);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
        verify(emailService).send(eq("a@example.com"), eq("You're in"), anyString(), anyString(), headers.capture());
        assertTrue(headers.getValue().isEmpty(), "no list headers on the welcome email either: " + headers.getValue());
        verify(waitlist, never()).setConfirmationToken(any(), any(), any());
        verify(waitlist, never()).markConfirmationSent(any(), any(), any());
        verify(waitlist).setEmailStatus(7L, EmailMessage.STATUS_SENT);
        assertNotNull(message.getUnsubscribeTokenHash());
    }

    // --- email.templates.v2.enabled -------------------------------------------------------

    // Flag off: the v1 welcome, byte for byte EmailTemplates.welcome, through the SDK path,
    // even for a row that carries a projection.
    @Test
    void flagOff_sendsTheV1WelcomeHtml_byteIdentical_andNeverTouchesTheChart() throws Exception {
        message.setTemplate(EmailMessage.TEMPLATE_WELCOME);
        entry.setProjectionJson(sampleJson());
        when(emailService.send(anyString(), anyString(), anyString(), anyString(), anyMap())).thenReturn("re_w");
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);

        publisher.publish(message);

        verify(emailService).send(eq("a@example.com"), eq("You're in"), html.capture(), text.capture(), anyMap());
        verify(emailService, never()).send(anyString(), anyString(), anyString(), anyString(), anyMap(), anyList());
        String unsub = "https://lpapi.fredvested.com/api/waitlist/unsubscribe?token=" + between(html.getValue(), "/api/waitlist/unsubscribe?token=", "\"");
        EmailTemplates.Rendered v1 = EmailTemplates.welcome(unsub, "[PO Box pending]");
        assertEquals(v1.html(), html.getValue());
        assertEquals(v1.text(), text.getValue());
        assertFalse(html.getValue().contains("cid:"));
    }

    @Test
    void flagOn_withAProjection_sendsTheV2Welcome_withExactlyOneInlineChart() throws Exception {
        EmailOutboxPublisher v2 = publisher("https://lpapi.fredvested.com", true, FAKE_CHART);
        message.setTemplate(EmailMessage.TEMPLATE_WELCOME);
        entry.setProjectionJson(sampleJson());
        when(emailService.send(anyString(), anyString(), anyString(), anyString(), anyMap(), anyList())).thenReturn("re_v2");
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EmailService.InlineAttachment>> inline = ArgumentCaptor.forClass(List.class);

        assertTrue(v2.publish(message));

        verify(emailService).send(eq("a@example.com"), eq("You're in"), html.capture(), text.capture(), anyMap(), inline.capture());
        verify(emailService, never()).send(anyString(), anyString(), anyString(), anyString(), anyMap());
        assertEquals(1, inline.getValue().size());
        EmailService.InlineAttachment chart = inline.getValue().get(0);
        assertEquals("growth-chart", chart.contentId());
        assertEquals("growth-chart.png", chart.filename());
        assertEquals("image/png", chart.contentType());
        assertArrayEquals(FAKE_PNG, chart.content());
        assertEquals(1, count(html.getValue(), "cid:growth-chart"), "the cid reference exactly once");
        assertTrue(html.getValue().contains("12 fewer working years could look like:"), html.getValue());
        assertTrue(html.getValue().contains("$2,000</b>/mo from age <b style=\"color:#ffffff;\">30</b>"));
        assertFalse(html.getValue().contains("Put your financial future on autopilot."));
        assertFalse(html.getValue().contains("data-field") || html.getValue().contains("data-projection"));
        assertTrue(text.getValue().contains("$2,000/mo from age 30 -> a projected $7,500/mo starting at 53."));
        assertEquals("re_v2", message.getResendEmailId());
        assertEquals(EmailMessage.STATUS_SENT, message.getStatus());
        verify(waitlist).setEmailStatus(7L, EmailMessage.STATUS_SENT);
    }

    @Test
    void flagOn_withoutAProjection_sendsTheFallback_withNoAttachment() throws Exception {
        EmailOutboxPublisher v2 = publisher("https://lpapi.fredvested.com", true, NO_CHART);
        message.setTemplate(EmailMessage.TEMPLATE_WELCOME);
        entry.setProjectionJson(null);
        when(emailService.send(anyString(), anyString(), anyString(), anyString(), anyMap(), anyList())).thenReturn("re_fb");
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EmailService.InlineAttachment>> inline = ArgumentCaptor.forClass(List.class);

        assertTrue(v2.publish(message));

        verify(emailService).send(eq("a@example.com"), eq("You're in"), html.capture(), anyString(), anyMap(), inline.capture());
        assertTrue(inline.getValue().isEmpty(), "no attachment without a projection");
        assertTrue(html.getValue().contains("Put your financial future on autopilot."));
        assertFalse(html.getValue().contains("cid:"));
        assertFalse(html.getValue().contains("fewer working years"));
        assertEquals(EmailMessage.STATUS_SENT, message.getStatus());
    }

    @Test
    void flagOn_withUnreadableProjectionJson_behavesLikeNull_andDoesNotThrow() throws Exception {
        EmailOutboxPublisher v2 = publisher("https://lpapi.fredvested.com", true, NO_CHART);
        message.setTemplate(EmailMessage.TEMPLATE_WELCOME);
        when(emailService.send(anyString(), anyString(), anyString(), anyString(), anyMap(), anyList())).thenReturn("re_fb");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EmailService.InlineAttachment>> inline = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);

        // Unparseable, blank, mistyped, and parseable but incomplete (nothing the email could safely fill).
        for (String bad : new String[] { "{not json", "  ", "{\"currentAge\":\"thirty\"}", "{}", "{\"currentAge\":30}" }) {
            entry.setProjectionJson(bad);
            message.setStatus(EmailMessage.STATUS_PENDING);
            assertDoesNotThrow(() -> v2.publish(message), bad);
            assertEquals(EmailMessage.STATUS_SENT, message.getStatus(), bad);
        }
        verify(emailService, times(5)).send(anyString(), eq("You're in"), html.capture(), anyString(), anyMap(), inline.capture());
        for (List<EmailService.InlineAttachment> l : inline.getAllValues()) assertTrue(l.isEmpty());
        for (String h : html.getAllValues()) {
            assertTrue(h.contains("Put your financial future on autopilot."));
            assertFalse(h.contains("cid:"));
        }
    }

    // A chart that cannot be drawn for one row degrades to the no-chart copy and the message is
    // still sent, rather than the row burning its attempts (a broken renderer is caught at boot).
    @Test
    void flagOn_whenTheChartThrows_sendsTheFallback_withNoAttachment() throws Exception {
        EmailOutboxPublisher v2 = publisher("https://lpapi.fredvested.com", true, p -> { throw new IllegalArgumentException("growth chart: value out of range"); });
        message.setTemplate(EmailMessage.TEMPLATE_WELCOME);
        entry.setProjectionJson(sampleJson());
        when(emailService.send(anyString(), anyString(), anyString(), anyString(), anyMap(), anyList())).thenReturn("re_nc");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EmailService.InlineAttachment>> inline = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);

        assertTrue(v2.publish(message));

        assertEquals(EmailMessage.STATUS_SENT, message.getStatus());
        assertEquals("re_nc", message.getResendEmailId());
        verify(emailService).send(eq("a@example.com"), eq("You're in"), html.capture(), anyString(), anyMap(), inline.capture());
        assertTrue(inline.getValue().isEmpty());
        assertTrue(html.getValue().contains("Put your financial future on autopilot."));
        assertFalse(html.getValue().contains("cid:"));
    }

    @Test
    void flagOn_sendsTheV2Confirmation_andStillMintsTheToken() throws Exception {
        EmailOutboxPublisher v2 = publisher("https://lpapi.fredvested.com", true, NO_CHART);
        when(emailService.send(anyString(), anyString(), anyString(), anyString(), anyMap(), anyList())).thenReturn("re_c2");
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EmailService.InlineAttachment>> inline = ArgumentCaptor.forClass(List.class);

        assertTrue(v2.publish(message));

        verify(emailService).send(eq("a@example.com"), eq("Confirm your email"), html.capture(), anyString(), anyMap(), inline.capture());
        assertTrue(inline.getValue().isEmpty());
        verify(waitlist).setConfirmationToken(eq(7L), hash.capture(), any());
        verify(waitlist).markConfirmationSent(eq(7L), eq(EmailMessage.STATUS_SENT), any());
        String confirmRaw = between(html.getValue(), "/api/waitlist/confirm?token=", "\"");
        assertEquals(hash.getValue(), ConfirmationTokens.hash(confirmRaw));
        assertTrue(html.getValue().contains("Confirm my email"));
        assertTrue(html.getValue().contains("prefers-color-scheme"), "v2 frame");
        assertFalse(html.getValue().contains("data-field"));
        assertEquals(EmailMessage.STATUS_SENT, message.getStatus());
    }

    @Test
    void alreadyConfirmedAddress_skipsAPendingConfirmation() throws Exception {
        entry.setConfirmedAt(LocalDateTime.now());
        publisher.publish(message);
        verifyNoInteractions(emailService);
        assertEquals(EmailMessage.STATUS_SKIPPED, message.getStatus());
    }

    @Test
    void resendFailure_leavesTheMessagePending_withBackoffAndTheErrorRecorded() throws Exception {
        when(emailService.send(anyString(), anyString(), anyString(), anyString(), anyMap())).thenThrow(new RuntimeException("503 from Resend"));

        publisher.publish(message);

        assertEquals(EmailMessage.STATUS_PENDING, message.getStatus());
        assertEquals((short) 1, message.getAttempts());
        assertTrue(message.getLastError().contains("503 from Resend"));
        assertNull(message.getResendEmailId());
        assertNotNull(message.getNextAttemptAt());
        assertTrue(message.getNextAttemptAt().isAfter(LocalDateTime.now().plusSeconds(50)));
        verify(waitlist, never()).markConfirmationSent(any(), any(), any());
    }

    @Test
    void afterMaxAttempts_theMessageFails() throws Exception {
        when(emailService.send(anyString(), anyString(), anyString(), anyString(), anyMap())).thenThrow(new RuntimeException("down"));
        message.setAttempts((short) 2); // max is 3 in this test

        publisher.publish(message);

        assertEquals(EmailMessage.STATUS_FAILED, message.getStatus());
        assertNull(message.getNextAttemptAt());
        verify(waitlist).setEmailStatus(7L, EmailMessage.STATUS_FAILED);
    }

    @Test
    void backoff_isExponential_andCapped() {
        assertEquals(Duration.ofMinutes(1), EmailOutboxPublisher.backoff(1));
        assertEquals(Duration.ofMinutes(2), EmailOutboxPublisher.backoff(2));
        assertEquals(Duration.ofMinutes(4), EmailOutboxPublisher.backoff(3));
        assertEquals(Duration.ofHours(6), EmailOutboxPublisher.backoff(12));
        assertEquals(Duration.ofMinutes(1), EmailOutboxPublisher.backoff(0));
    }

    private String sampleJson() throws Exception {
        Projection p = objectMapper.readValue(getClass().getClassLoader().getResourceAsStream("projections/sample.json"), Projection.class);
        return objectMapper.writeValueAsString(p);
    }

    private static int count(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + needle.length())) n++;
        return n;
    }

    private static String between(String s, String start, String end) {
        int i = s.indexOf(start);
        assertTrue(i >= 0, "missing " + start);
        int from = i + start.length();
        return s.substring(from, s.indexOf(end, from));
    }
}
