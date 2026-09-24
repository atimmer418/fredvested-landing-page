package com.fredvested.web.controller;

import com.fredvested.web.model.WaitlistEntry;
import com.fredvested.web.repository.WaitlistRepository;
import com.fredvested.web.service.AddressRateLimiter;
import com.fredvested.web.service.RateLimiterService;
import com.fredvested.web.service.SignupService;
import com.fredvested.web.service.TurnstileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Decision 2026-09-24: the public statistic and the founder cap count confirmed
// rows only (double opt-in, legacy backfill, single opt-in). The controller never
// consults a total-rows count, and the cap itself is decided by SignupService when
// a row becomes confirmed, not by the controller at submit time.
@WebMvcTest(WaitlistController.class)
@TestPropertySource(properties = "waitlist.stats-cache-ms=0")
class WaitlistControllerConfirmedOnlyStatsTest {

    @Autowired MockMvc mockMvc;
    @MockBean WaitlistRepository repository;
    @MockBean TurnstileService turnstileService;
    @MockBean RateLimiterService rateLimiterService;
    @MockBean AddressRateLimiter addressLimiter;
    @MockBean SignupService signupService;

    @Test
    void stats_countConfirmedRowsOnly() throws Exception {
        when(repository.countByConfirmedAtIsNotNull()).thenReturn(40L);
        when(repository.countByStatusAndConfirmedAtIsNotNull(WaitlistEntry.WaitlistStatus.WAITLISTFOUNDER)).thenReturn(33L);
        when(repository.count()).thenReturn(999L);
        when(repository.countByStatus(any())).thenReturn(999L);

        mockMvc.perform(get("/api/waitlist/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(40))
                .andExpect(jsonPath("$.founderCount").value(33));

        verify(repository, never()).count();
        verify(repository, never()).countByStatus(any());
    }

    @Test
    void signup_reportsTheStatusTheServiceDecided_andNeverDecidesTheCapItself() throws Exception {
        when(turnstileService.verifyToken(anyString())).thenReturn(true);
        when(rateLimiterService.isAllowed(anyString())).thenReturn(true);
        when(repository.existsByEmail("new@example.com")).thenReturn(false);
        when(signupService.isDoubleOptIn()).thenReturn(true);
        when(signupService.createSignup(any())).thenAnswer(inv -> {
            WaitlistEntry e = inv.getArgument(0);
            e.setStatus(WaitlistEntry.WaitlistStatus.WAITLISTNORMAL); // placeholder until confirmed
            return e;
        });

        mockMvc.perform(post("/api/waitlist").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"new@example.com\",\"turnstileToken\":\"t\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITLISTNORMAL"))
                .andExpect(jsonPath("$.requiresConfirmation").value(true));

        verify(repository, never()).countByStatus(any());
        verify(repository, never()).count();
    }
}
