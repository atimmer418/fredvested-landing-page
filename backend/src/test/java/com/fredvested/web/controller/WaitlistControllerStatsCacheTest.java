package com.fredvested.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fredvested.web.repository.EmailMessageRepository;
import com.fredvested.web.repository.WaitlistRepository;
import com.fredvested.web.service.EmailService;
import com.fredvested.web.service.RateLimiterService;
import com.fredvested.web.service.SignupService;
import com.fredvested.web.service.TurnstileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// GET /stats is public and hit on every page load. With the default cache TTL,
// repeated GETs must not each hit the database, and a signup must invalidate it.
// The memo lives on the controller bean, so each method gets a fresh context.
@WebMvcTest(WaitlistController.class)
@Import(SignupService.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class WaitlistControllerStatsCacheTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean WaitlistRepository repository;
    @MockBean TurnstileService turnstileService;
    @MockBean RateLimiterService rateLimiterService;
    @MockBean EmailService emailService;
    @MockBean EmailMessageRepository emailMessageRepository;
    @MockBean PlatformTransactionManager transactionManager;

    @Test
    void repeatedStatsReads_hitTheDatabaseOnce_untilASignupInvalidates() throws Exception {
        when(repository.countByConfirmedAtIsNotNull()).thenReturn(40L);

        mockMvc.perform(get("/api/waitlist/stats")).andExpect(status().isOk()).andExpect(jsonPath("$.count").value(40));
        mockMvc.perform(get("/api/waitlist/stats")).andExpect(status().isOk()).andExpect(jsonPath("$.count").value(40));
        mockMvc.perform(get("/api/waitlist/stats")).andExpect(status().isOk());
        verify(repository, times(1)).countByConfirmedAtIsNotNull();

        when(rateLimiterService.isAllowed(anyString())).thenReturn(true);
        when(turnstileService.verifyToken(anyString())).thenReturn(true);
        when(repository.existsByEmail(anyString())).thenReturn(false);
        when(repository.countByStatus(any())).thenReturn(0L);
        when(repository.countByConfirmedAtIsNotNull()).thenReturn(41L);
        mockMvc.perform(post("/api/waitlist")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", "cache@example.com", "turnstileToken", "token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(41));

        mockMvc.perform(get("/api/waitlist/stats")).andExpect(status().isOk()).andExpect(jsonPath("$.count").value(41));
        verify(repository, times(3)).countByConfirmedAtIsNotNull(); // the signup response + one fresh read after invalidation
    }

    @Test
    void statsNeverExposePerRowData() throws Exception {
        when(repository.countByConfirmedAtIsNotNull()).thenReturn(5L);
        mockMvc.perform(get("/api/waitlist/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.entries").doesNotExist())
                .andExpect(jsonPath("$.ipHash").doesNotExist());
        verify(repository, never()).findAll();
        verify(repository, never()).findByEmail(anyString());
    }
}
