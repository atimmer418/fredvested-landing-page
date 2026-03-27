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

import static org.mockito.ArgumentMatchers.any;
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

        verify(repository, times(1)).save(any(WaitlistEntry.class));
    }
}
