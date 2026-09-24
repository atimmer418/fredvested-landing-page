package com.fredvested.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fredvested.web.model.EmailMessage;
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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The signup never talks to Resend on the request thread: it queues an outbox row
// in the same transaction, and the scheduled publisher sends it later.
@WebMvcTest(WaitlistController.class)
@Import(SignupService.class)
class WaitlistControllerEmailTest {

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
        when(repository.countByStatus(WaitlistEntry.WaitlistStatus.WAITLISTFOUNDER)).thenReturn(0L);
        when(repository.count()).thenReturn(1L);
        when(repository.save(any(WaitlistEntry.class))).thenAnswer(inv -> {
            WaitlistEntry e = inv.getArgument(0);
            e.setId(42L);
            return e;
        });
    }

    @Test
    void joinWaitlist_queuesAConfirmationEmail_andNeverCallsResendInRequest() throws Exception {
        mockMvc.perform(post("/api/waitlist")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", "test@example.com", "turnstileToken", "token"))))
                .andExpect(status().isOk());

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailMessageRepository, times(1)).save(captor.capture());
        EmailMessage queued = captor.getValue();
        assertEquals(42L, queued.getWaitlistId());
        assertEquals(EmailMessage.TEMPLATE_CONFIRMATION, queued.getTemplate());
        assertEquals(EmailMessage.STATUS_PENDING, queued.getStatus());
        assertEquals((short) 0, queued.getAttempts());
        assertNotNull(queued.getQueuedAt());
        verifyNoInteractions(emailService);
    }

    @Test
    void joinWaitlist_isNotAffectedByResendBeingDown() throws Exception {
        // Resend is never called here, so there is nothing to fail: the send is the publisher's job.
        doThrow(new RuntimeException("Resend unavailable")).when(emailService).send(anyString(), anyString(), anyString(), anyString(), anyMap());

        mockMvc.perform(post("/api/waitlist")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", "test@example.com", "turnstileToken", "token"))))
                .andExpect(status().isOk());

        verify(repository, times(1)).save(any(WaitlistEntry.class));
        verify(emailMessageRepository, times(1)).save(any(EmailMessage.class));
    }
}
