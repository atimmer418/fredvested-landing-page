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
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WaitlistController.class)
class WaitlistControllerAttributionTest {

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
        when(repository.existsByEmail(anyString())).thenReturn(false);
        when(repository.countByStatus(any())).thenReturn(0L);
        when(repository.count()).thenReturn(1L);
    }

    private Map<String, Object> basePayload() {
        Map<String, Object> p = new HashMap<>();
        p.put("email", "test@example.com");
        p.put("turnstileToken", "token");
        return p;
    }

    private ResultActions submit(Map<String, Object> payload) throws Exception {
        return mockMvc.perform(post("/api/waitlist")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)));
    }

    private WaitlistEntry saved() {
        ArgumentCaptor<WaitlistEntry> captor = ArgumentCaptor.forClass(WaitlistEntry.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    // --- attribution is a data-quality concern, never a rejection ---

    @Test
    void hostileAttribution_isSanitized_andTheSignupStillSucceeds() throws Exception {
        Map<String, Object> p = basePayload();
        p.put("utmSource", "<script>alert(1)</script>TikTok");
        p.put("utmMedium", "x".repeat(5000));
        p.put("utmCampaign", "nurse'; DROP TABLE waitlist_signups;--");
        p.put("utmContent", "20260921-night-shift-roi");
        p.put("utmTerm", "✓ünïcödé");
        p.put("firstUtmSource", "IG");
        p.put("firstUtmCampaign", "");
        p.put("firstUtmContent", "%00");
        p.put("firstTouchAt", "not-a-date");
        p.put("referrerHost", "https://evil.com/path?x=1");
        p.put("landingPath", "javascript:alert(1)");
        p.put("deviceType", "toaster");
        p.put("revealedBeforeSubmit", true);

        submit(p).andExpect(status().isOk());

        WaitlistEntry e = saved();
        assertEquals("test@example.com", e.getEmail());
        assertEquals("scriptalert1scripttiktok", e.getUtmSource());
        assertEquals(100, e.getUtmMedium().length());
        assertEquals("x".repeat(100), e.getUtmMedium());
        assertEquals("nursedroptablewaitlist_signups--", e.getUtmCampaign());
        assertEquals("20260921-night-shift-roi", e.getUtmContent());
        assertEquals("ncd", e.getUtmTerm());
        assertEquals("ig", e.getFirstUtmSource());
        assertNull(e.getFirstUtmCampaign());
        assertEquals("00", e.getFirstUtmContent());
        assertNull(e.getFirstTouchAt());
        assertEquals("httpsevil.compathx1", e.getReferrerHost());
        assertNull(e.getLandingPath());
        assertNull(e.getDeviceType());
        assertEquals(Boolean.TRUE, e.getRevealedBeforeSubmit());
    }

    @Test
    void validAttribution_isPersistedAsSent() throws Exception {
        Map<String, Object> p = basePayload();
        p.put("utmSource", "tiktok");
        p.put("utmMedium", "bio");
        p.put("utmCampaign", "nurse_shift_math");
        p.put("utmContent", "20260921-night-shift-roi");
        p.put("utmTerm", "fire");
        p.put("firstUtmSource", "instagram");
        p.put("firstUtmCampaign", "compound_basics");
        p.put("firstUtmContent", "20260901-first-post");
        p.put("firstTouchAt", "2026-09-01T12:34:56.000Z");
        p.put("referrerHost", "www.tiktok.com");
        p.put("landingPath", "/");
        p.put("deviceType", "mobile");

        submit(p).andExpect(status().isOk());

        WaitlistEntry e = saved();
        assertEquals("tiktok", e.getUtmSource());
        assertEquals("bio", e.getUtmMedium());
        assertEquals("nurse_shift_math", e.getUtmCampaign());
        assertEquals("20260921-night-shift-roi", e.getUtmContent());
        assertEquals("fire", e.getUtmTerm());
        assertEquals("instagram", e.getFirstUtmSource());
        assertEquals("compound_basics", e.getFirstUtmCampaign());
        assertEquals("20260901-first-post", e.getFirstUtmContent());
        assertEquals(LocalDateTime.of(2026, 9, 1, 8, 34, 56), e.getFirstTouchAt()); // 12:34:56Z in Eastern (EDT)
        assertEquals("www.tiktok.com", e.getReferrerHost());
        assertEquals("/", e.getLandingPath());
        assertEquals("mobile", e.getDeviceType());
    }

    @Test
    void noAttribution_leavesEveryAttributionColumnNull_andRevealedFalse() throws Exception {
        submit(basePayload()).andExpect(status().isOk());
        WaitlistEntry e = saved();
        assertNull(e.getUtmSource());
        assertNull(e.getFirstUtmSource());
        assertNull(e.getFirstTouchAt());
        assertNull(e.getReferrerHost());
        assertNull(e.getLandingPath());
        assertNull(e.getDeviceType());
        assertEquals(Boolean.FALSE, e.getRevealedBeforeSubmit());
    }

    // --- email is the one field that can reject, and it maps to a clean code ---

    @Test
    void missingEmail_is400_invalid_email() throws Exception {
        Map<String, Object> p = basePayload();
        p.remove("email");
        submit(p).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_email"));
        verify(repository, never()).save(any());
    }

    @Test
    void blankEmail_is400_invalid_email() throws Exception {
        Map<String, Object> p = basePayload();
        p.put("email", "   ");
        submit(p).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_email"));
        verify(repository, never()).save(any());
    }

    @Test
    void malformedEmail_is400_invalid_email() throws Exception {
        Map<String, Object> p = basePayload();
        p.put("email", "not-an-email");
        submit(p).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_email"));
        verify(repository, never()).save(any());
    }

    @Test
    void malformedBody_is400_withACode_notA500() throws Exception {
        mockMvc.perform(post("/api/waitlist").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("server_error"));
    }

    // --- every existing failure branch carries its code ---

    @Test
    void rateLimited_is429_rate_limited() throws Exception {
        when(rateLimiterService.isAllowed(anyString())).thenReturn(false);
        submit(basePayload()).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("rate_limited"));
    }

    @Test
    void turnstileFailure_is400_captcha_failed() throws Exception {
        when(turnstileService.verifyToken(anyString())).thenReturn(false);
        submit(basePayload()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("captcha_failed"));
    }

    @Test
    void alreadyJoined_isStill200_withAlreadyJoinedStatus() throws Exception {
        WaitlistEntry existing = new WaitlistEntry();
        existing.setStatus(WaitlistEntry.WaitlistStatus.WAITLISTFOUNDER);
        when(repository.existsByEmail("test@example.com")).thenReturn(true);
        when(repository.findByEmail("test@example.com")).thenReturn(existing);
        submit(basePayload()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("already_joined"))
                .andExpect(jsonPath("$.realStatus").value("WAITLISTFOUNDER"));
        verify(repository, never()).save(any());
    }

    // --- the server recomputes the projection; the client's numbers are never stored ---

    @Test
    void projection_isRecomputedFromTheInputs() throws Exception {
        Map<String, Object> p = basePayload();
        p.put("age", 30);
        p.put("investMonthly", 2000);
        p.put("retireMonthly", 7500);
        p.put("returnAssumptionPct", 12);
        p.put("interacted", true);
        p.put("freedomAge", 51);
        LocalDate expectedDate = LocalDate.now(ZoneId.of("America/New_York")).withDayOfMonth(1).plusMonths(252);
        p.put("freedomDate", expectedDate.toString());
        p.put("portfolioTarget", 2250000L);

        submit(p).andExpect(status().isOk());

        WaitlistEntry e = saved();
        assertEquals(51, e.getFreedomAge());
        assertEquals(expectedDate, e.getComputedFreedomDate());
        assertEquals(2250000L, e.getComputedPortfolioTarget());
    }

    @Test
    void projection_ignoresTheClientsNumbers_whenTheyDrift() throws Exception {
        Map<String, Object> p = basePayload();
        p.put("age", 30);
        p.put("investMonthly", 2000);
        p.put("retireMonthly", 7500);
        p.put("returnAssumptionPct", 12);
        p.put("freedomAge", 99);           // drifted / tampered
        p.put("portfolioTarget", 1L);

        submit(p).andExpect(status().isOk());

        WaitlistEntry e = saved();
        assertEquals(51, e.getFreedomAge());
        assertEquals(2250000L, e.getComputedPortfolioTarget());
    }

    @Test
    void projection_isNull_whenUnreachable_butTheTargetIsStillStored() throws Exception {
        Map<String, Object> p = basePayload();
        p.put("age", 30);
        p.put("investMonthly", 0);
        p.put("retireMonthly", 7500);
        p.put("returnAssumptionPct", 10);

        submit(p).andExpect(status().isOk());

        WaitlistEntry e = saved();
        assertNull(e.getFreedomAge());
        assertNull(e.getComputedFreedomDate());
        assertEquals(2250000L, e.getComputedPortfolioTarget());
    }

    @Test
    void projection_isNull_whenInputsAreIncomplete_evenIfTheClientSentOne() throws Exception {
        Map<String, Object> p = basePayload();
        p.put("freedomAge", 45);

        submit(p).andExpect(status().isOk());

        WaitlistEntry e = saved();
        assertNull(e.getFreedomAge());
        assertNull(e.getComputedFreedomDate());
        assertNull(e.getComputedPortfolioTarget());
    }
}
