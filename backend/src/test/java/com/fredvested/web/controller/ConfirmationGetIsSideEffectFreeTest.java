package com.fredvested.web.controller;

import com.fredvested.web.model.WaitlistEntry;
import com.fredvested.web.repository.EmailMessageRepository;
import com.fredvested.web.repository.WaitlistRepository;
import com.fredvested.web.service.AddressRateLimiter;
import com.fredvested.web.service.ConfirmationTokens;
import com.fredvested.web.service.LandingUrls;
import com.fredvested.web.service.RateLimiterService;
import com.fredvested.web.service.SignupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The real SignupService behind the controller (repositories mocked): proves end to end
// that fetching the emailed link with GET, as mail security scanners do, leaves the
// single-use token intact, and only the POST behind it confirms.
@WebMvcTest(WaitlistConfirmationController.class)
@Import({LandingUrls.class, SignupService.class})
class ConfirmationGetIsSideEffectFreeTest {

    @Autowired MockMvc mockMvc;
    @MockBean WaitlistRepository waitlist;
    @MockBean EmailMessageRepository outbox;
    @MockBean PlatformTransactionManager txManager;
    @MockBean RateLimiterService ipLimiter;
    @MockBean AddressRateLimiter addressLimiter;

    WaitlistEntry entry;
    ConfirmationTokens.Generated token;

    @BeforeEach
    void setUp() {
        token = ConfirmationTokens.generate();
        entry = new WaitlistEntry();
        entry.setId(7L);
        entry.setEmail("a@example.com");
        entry.setCreatedAt(LocalDateTime.now().minusHours(2));
        entry.setConfirmationSentAt(LocalDateTime.now().minusMinutes(30));
        entry.setConfirmationTokenHash(token.hash());
        entry.setConfirmationExpiresAt(LocalDateTime.now().plusDays(7));
        // Answers by the entry's CURRENT hash, so clearing it on use is observable.
        when(waitlist.findByConfirmationTokenHash(anyString())).thenAnswer(inv ->
                Optional.ofNullable(entry.getConfirmationTokenHash()).filter(h -> h.equals(inv.getArgument(0))).map(h -> entry));
    }

    @Test
    void scannerStyleGetAndHead_leaveTheTokenUnconsumed_thenThePostConfirmsOnce() throws Exception {
        mockMvc.perform(get("/api/waitlist/confirm").param("token", token.raw())).andExpect(status().isOk());
        mockMvc.perform(get("/api/waitlist/confirm").param("token", token.raw())).andExpect(status().isOk());
        mockMvc.perform(head("/api/waitlist/confirm").param("token", token.raw())).andExpect(status().isOk());
        assertNull(entry.getConfirmedAt(), "a GET must not confirm");
        assertEquals(token.hash(), entry.getConfirmationTokenHash(), "a GET must not consume the token");

        mockMvc.perform(post("/api/waitlist/confirm").contentType(MediaType.APPLICATION_FORM_URLENCODED).param("token", token.raw()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://fredvested.com/confirmed?status=confirmed&hours=%3C1&tier=founder"));
        assertNotNull(entry.getConfirmedAt());
        assertNull(entry.getConfirmationTokenHash(), "single use: the hash is cleared");

        // A replay and a never-issued token are byte-identical afterwards.
        MockHttpServletResponse replay = mockMvc.perform(post("/api/waitlist/confirm").contentType(MediaType.APPLICATION_FORM_URLENCODED).param("token", token.raw())).andReturn().getResponse();
        MockHttpServletResponse unknown = mockMvc.perform(post("/api/waitlist/confirm").contentType(MediaType.APPLICATION_FORM_URLENCODED).param("token", ConfirmationTokens.generate().raw())).andReturn().getResponse();
        assertEquals(replay.getStatus(), unknown.getStatus());
        assertEquals(replay.getRedirectedUrl(), unknown.getRedirectedUrl());
        assertEquals("https://fredvested.com/confirmed?status=invalid", replay.getRedirectedUrl());
        assertEquals(replay.getContentAsString(), unknown.getContentAsString());
    }
}
