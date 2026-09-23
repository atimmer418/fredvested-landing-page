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
        payload.put("returnAssumptionPct", 12);

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
        // freedom_age is recomputed server-side from the inputs (30 / 2000 / 7500 at 12% -> 51);
        // the client's 53 is only compared against it and logged as drift.
        assertEquals(51, saved.getFreedomAge());
        assertEquals(12, saved.getReturnAssumptionPct());
    }

    @Test
    void joinWaitlist_nullsOutOfRangeCalculatorInputs() throws Exception {
        Map<String, Object> payload = basePayload();
        payload.put("age", 150);
        payload.put("investMonthly", -5);
        payload.put("retireMonthly", 999);
        payload.put("interacted", false);
        payload.put("returnAssumptionPct", 0);

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
        assertNull(saved.getReturnAssumptionPct());
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
        assertNull(saved.getReturnAssumptionPct());
    }
}
