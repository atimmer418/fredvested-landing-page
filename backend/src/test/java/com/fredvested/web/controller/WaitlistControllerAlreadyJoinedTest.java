package com.fredvested.web.controller;

import com.fredvested.web.model.WaitlistEntry;
import com.fredvested.web.repository.WaitlistRepository;
import com.fredvested.web.service.AddressRateLimiter;
import com.fredvested.web.service.RateLimiterService;
import com.fredvested.web.service.SignupService;
import com.fredvested.web.service.TurnstileService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Review finding 2026-09-24: under double opt-in, re-submitting an address that is on
// the list but not confirmed used to answer already_joined with the WAITLISTNORMAL
// placeholder: the page then said "You're in" with no way to the email, and the
// response doubled as a confirmation-status oracle. Now such a submit is answered
// exactly like a fresh signup and queues a fresh confirmation (address-limited).
@WebMvcTest(WaitlistController.class)
@TestPropertySource(properties = "waitlist.stats-cache-ms=0")
class WaitlistControllerAlreadyJoinedTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;
    @MockBean WaitlistRepository repository;
    @MockBean TurnstileService turnstileService;
    @MockBean RateLimiterService rateLimiterService;
    @MockBean AddressRateLimiter addressLimiter;
    @MockBean SignupService signupService;

    WaitlistEntry existing;

    @BeforeEach
    void setUp() {
        when(turnstileService.verifyToken(anyString())).thenReturn(true);
        when(rateLimiterService.isAllowed(anyString())).thenReturn(true);
        when(addressLimiter.allow(anyString())).thenReturn(true);
        when(signupService.isDoubleOptIn()).thenReturn(true);
        existing = new WaitlistEntry();
        existing.setId(9L);
        existing.setEmail("dup@example.com");
        existing.setStatus(WaitlistEntry.WaitlistStatus.WAITLISTNORMAL);
        when(repository.existsByEmail("dup@example.com")).thenReturn(true);
        when(repository.findByEmail("dup@example.com")).thenReturn(existing);
        when(repository.existsByEmail("new@example.com")).thenReturn(false);
        when(signupService.createSignup(any())).thenAnswer(inv -> {
            WaitlistEntry e = inv.getArgument(0);
            e.setStatus(WaitlistEntry.WaitlistStatus.WAITLISTNORMAL);
            return e;
        });
    }

    private JsonNode submit(String email) throws Exception {
        String body = mockMvc.perform(post("/api/waitlist").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"turnstileToken\":\"t\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body);
    }

    @Test
    void unconfirmedDuplicate_looksExactlyLikeAFreshSignup_andQueuesAFreshConfirmation() throws Exception {
        JsonNode fresh = submit("new@example.com");
        JsonNode dup = submit("dup@example.com");

        assertEquals(keys(fresh), keys(dup), "same keys");
        assertEquals("WAITLISTNORMAL", dup.get("status").asText());
        assertTrue(dup.get("requiresConfirmation").asBoolean());
        assertNull(dup.get("realStatus"));
        assertFalse(dup.toString().contains("already_joined"));
        verify(signupService).requestResend("dup@example.com");
        verify(signupService, times(1)).createSignup(any()); // only the fresh one
    }

    @Test
    void unconfirmedDuplicate_overTheAddressLimit_stillLooksFresh_butQueuesNothing() throws Exception {
        when(addressLimiter.allow("dup@example.com")).thenReturn(false);
        JsonNode dup = submit("dup@example.com");
        assertEquals("WAITLISTNORMAL", dup.get("status").asText());
        assertTrue(dup.get("requiresConfirmation").asBoolean());
        verify(signupService, never()).requestResend(anyString());
    }

    @Test
    void confirmedDuplicate_isStillAlreadyJoined_withItsRealStatus() throws Exception {
        existing.setConfirmedAt(LocalDateTime.now());
        existing.setStatus(WaitlistEntry.WaitlistStatus.WAITLISTFOUNDER);
        mockMvc.perform(post("/api/waitlist").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"dup@example.com\",\"turnstileToken\":\"t\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("already_joined"))
                .andExpect(jsonPath("$.realStatus").value("WAITLISTFOUNDER"));
        verify(signupService, never()).requestResend(anyString());
    }

    @Test
    void withDoubleOptInOff_aDuplicateIsAlreadyJoined_asBefore() throws Exception {
        when(signupService.isDoubleOptIn()).thenReturn(false);
        mockMvc.perform(post("/api/waitlist").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"dup@example.com\",\"turnstileToken\":\"t\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("already_joined"))
                .andExpect(jsonPath("$.realStatus").value("WAITLISTNORMAL"));
        verify(signupService, never()).requestResend(anyString());
    }

    private static TreeSet<String> keys(JsonNode node) {
        TreeSet<String> out = new TreeSet<>();
        node.fieldNames().forEachRemaining(out::add);
        return out;
    }
}
