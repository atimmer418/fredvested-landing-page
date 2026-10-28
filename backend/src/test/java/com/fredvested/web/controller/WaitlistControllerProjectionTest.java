package com.fredvested.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fredvested.web.email.Fixtures;
import com.fredvested.web.email.Projection;
import com.fredvested.web.model.EmailMessage;
import com.fredvested.web.model.WaitlistEntry;
import com.fredvested.web.repository.EmailMessageRepository;
import com.fredvested.web.repository.WaitlistRepository;
import com.fredvested.web.service.AddressRateLimiter;
import com.fredvested.web.service.EmailService;
import com.fredvested.web.service.RateLimiterService;
import com.fredvested.web.service.SignupService;
import com.fredvested.web.service.TurnstileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The page's projection rides along with the signup: validated before Turnstile, stored as
// projection_json (V8) for the personalised welcome email, and a bad one rejects the request.
@WebMvcTest(WaitlistController.class)
@Import(SignupService.class)
class WaitlistControllerProjectionTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean WaitlistRepository repository;
    @MockBean TurnstileService turnstileService;
    @MockBean RateLimiterService rateLimiterService;
    @MockBean AddressRateLimiter addressLimiter;
    @MockBean EmailService emailService;
    @MockBean EmailMessageRepository emailMessageRepository;
    @MockBean PlatformTransactionManager transactionManager;

    @BeforeEach
    void allowThrough() {
        when(rateLimiterService.isAllowed(anyString())).thenReturn(true);
        when(turnstileService.verifyToken(anyString())).thenReturn(true);
        when(repository.existsByEmail("test@example.com")).thenReturn(false);
        when(repository.save(any(WaitlistEntry.class))).thenAnswer(inv -> {
            WaitlistEntry e = inv.getArgument(0);
            e.setId(42L);
            return e;
        });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(String fixture) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("email", "test@example.com");
        payload.put("turnstileToken", "token");
        payload.put("age", 30);
        payload.put("investMonthly", 2000);
        payload.put("retireMonthly", 7500);
        payload.put("returnAssumptionPct", 10);
        if (fixture != null) payload.put("projection", Fixtures.read(fixture, Map.class));
        return payload;
    }

    @Test
    void validProjection_isStoredAsJson_andRoundTripsToAnEqualProjection() throws Exception {
        mockMvc.perform(post("/api/waitlist").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload("sample"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITLISTED"));

        ArgumentCaptor<WaitlistEntry> captor = ArgumentCaptor.forClass(WaitlistEntry.class);
        verify(repository).save(captor.capture());
        String json = captor.getValue().getProjectionJson();
        assertNotNull(json);
        Projection stored = objectMapper.readValue(json, Projection.class);
        assertEquals(Fixtures.projection("sample"), stored);
        assertEquals("plan", objectMapper.readTree(json).get("stakeSource").asText());
        verify(emailMessageRepository).save(any(EmailMessage.class));
    }

    @Test
    void existingBalanceAndRate_areStoredExactlyAsSubmitted() throws Exception {
        mockMvc.perform(post("/api/waitlist").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload("existing-100k-optimistic"))))
                .andExpect(status().isOk());

        ArgumentCaptor<WaitlistEntry> captor = ArgumentCaptor.forClass(WaitlistEntry.class);
        verify(repository).save(captor.capture());
        Projection stored = objectMapper.readValue(captor.getValue().getProjectionJson(), Projection.class);
        assertEquals(100_000L, stored.existingBalance());
        assertEquals(12, stored.returnRate());
        assertEquals(100_000.0, stored.series().get(0).value());
    }

    @Test
    void absentProjection_savesNull_andStillQueuesTheEmail() throws Exception {
        mockMvc.perform(post("/api/waitlist").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload(null))))
                .andExpect(status().isOk());

        ArgumentCaptor<WaitlistEntry> captor = ArgumentCaptor.forClass(WaitlistEntry.class);
        verify(repository).save(captor.capture());
        assertNull(captor.getValue().getProjectionJson());
        verify(emailMessageRepository, times(1)).save(any(EmailMessage.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void malformedProjection_is400InvalidProjection_andNothingIsSavedOrQueued() throws Exception {
        Map<String, Object> off = payload("sample");
        ((Map<String, Object>) off.get("projection")).put("portfolioTarget", 2_250_000 * 1.05);

        Map<String, Object> decreasing = payload("sample");
        List<Map<String, Object>> series = (List<Map<String, Object>>) ((Map<String, Object>) decreasing.get("projection")).get("series");
        series.get(5).put("age", 33);

        Map<String, Object> text = payload("sample");
        ((Map<String, Object>) text.get("projection")).put("scenario", "<b>Moderate</b>");

        for (Map<String, Object> bad : List.of(off, decreasing, text)) {
            mockMvc.perform(post("/api/waitlist").contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(bad)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("invalid_projection"))
                    .andExpect(jsonPath("$.message").value("Something went wrong. Please try again."));
        }
        verify(repository, never()).save(any());
        verify(emailMessageRepository, never()).save(any());
        verify(turnstileService, never()).verifyToken(anyString());
    }
}
