package com.fredvested.web.controller;

import com.fredvested.web.repository.WaitlistRepository;
import com.fredvested.web.service.EmailService;
import com.fredvested.web.service.RateLimiterService;
import com.fredvested.web.service.TurnstileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WaitlistController.class)
class WaitlistControllerStatsTest {

    @Autowired MockMvc mockMvc;

    @MockBean WaitlistRepository repository;
    @MockBean TurnstileService turnstileService;
    @MockBean RateLimiterService rateLimiterService;
    @MockBean EmailService emailService;

    @Test
    void stats_includeProjectionCountBehindTheAverage() throws Exception {
        when(repository.count()).thenReturn(40L);
        when(repository.getAverageFreedomAge()).thenReturn(54.5);
        when(repository.countHeadStartProjections()).thenReturn(31L);

        mockMvc.perform(get("/api/waitlist/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(40))
                .andExpect(jsonPath("$.avgFreedomAge").value(54.5))
                .andExpect(jsonPath("$.projectionCount").value(31));
    }

    @Test
    void stats_includeMeasurementPeriodAsIsoDates() throws Exception {
        when(repository.countHeadStartProjections()).thenReturn(31L);
        when(repository.getFirstProjectionAt()).thenReturn(LocalDateTime.of(2026, 9, 3, 23, 59, 30));
        when(repository.getLastProjectionAt()).thenReturn(LocalDateTime.of(2026, 9, 14, 0, 0, 5));

        mockMvc.perform(get("/api/waitlist/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectionStartDate").value("2026-09-03"))
                .andExpect(jsonPath("$.projectionEndDate").value("2026-09-14"));
    }

    @Test
    void stats_projectionCountIsZeroWhenNoProjectionsExist() throws Exception {
        when(repository.count()).thenReturn(3L);
        when(repository.getAverageFreedomAge()).thenReturn(null);
        when(repository.countHeadStartProjections()).thenReturn(0L);
        when(repository.getFirstProjectionAt()).thenReturn(null);
        when(repository.getLastProjectionAt()).thenReturn(null);

        mockMvc.perform(get("/api/waitlist/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avgFreedomAge").value(0.0))
                .andExpect(jsonPath("$.projectionCount").value(0))
                .andExpect(jsonPath("$.projectionStartDate").value(nullValue()))
                .andExpect(jsonPath("$.projectionEndDate").value(nullValue()));
    }
}
