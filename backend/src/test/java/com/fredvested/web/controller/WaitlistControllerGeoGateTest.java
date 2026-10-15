package com.fredvested.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fredvested.web.model.WaitlistEntry;
import com.fredvested.web.repository.EmailMessageRepository;
import com.fredvested.web.repository.WaitlistRepository;
import com.fredvested.web.service.EmailService;
import com.fredvested.web.service.AddressRateLimiter;
import com.fredvested.web.service.RateLimiterService;
import com.fredvested.web.service.SignupService;
import com.fredvested.web.service.TurnstileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WaitlistController.class)
@Import(SignupService.class)
@TestPropertySource(properties = "waitlist.us-only=true")
class WaitlistControllerGeoGateTest {

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
        when(repository.existsByEmail(anyString())).thenReturn(false);
        when(repository.countByStatus(any())).thenReturn(0L);
        when(repository.count()).thenReturn(1L);
    }

    private MockHttpServletRequestBuilder signup() throws Exception {
        return post("/api/waitlist")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        Map.of("email", "test@example.com", "turnstileToken", "token")));
    }

    @Test
    void usRequest_isAccepted() throws Exception {
        mockMvc.perform(signup().header("CF-IPCountry", "us"))
                .andExpect(status().isOk());
        verify(repository).save(any(WaitlistEntry.class));
    }

    @Test
    void nonUsRequest_isRejectedAndNotStored() throws Exception {
        mockMvc.perform(signup().header("CF-IPCountry", "CA"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("geo_blocked"));
        verify(repository, never()).save(any());
        verify(emailMessageRepository, never()).save(any()); // nothing queued for a blocked signup
    }

    @Test
    void unknownCountry_isRejected() throws Exception {
        mockMvc.perform(signup().header("CF-IPCountry", "XX"))
                .andExpect(status().isForbidden());
        mockMvc.perform(signup().header("CF-IPCountry", "T1"))
                .andExpect(status().isForbidden());
        verify(repository, never()).save(any());
    }

    @Test
    void missingHeader_isRejected() throws Exception {
        mockMvc.perform(signup())
                .andExpect(status().isForbidden());
        verify(repository, never()).save(any());
    }

    @Test
    void geoGateRunsBeforeTurnstile_soBlockedRequestsSpendNoVerification() throws Exception {
        mockMvc.perform(signup().header("CF-IPCountry", "GB"))
                .andExpect(status().isForbidden());
        verify(turnstileService, never()).verifyToken(anyString());
    }
}
