package com.fredvested.web.service;

import com.fredvested.web.model.EmailMessage;
import com.fredvested.web.model.WaitlistEntry;
import com.fredvested.web.repository.EmailMessageRepository;
import com.fredvested.web.repository.WaitlistRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EmailOutboxPublisherTest {

    EmailMessageRepository outbox = mock(EmailMessageRepository.class);
    WaitlistRepository waitlist = mock(WaitlistRepository.class);
    EmailService emailService = mock(EmailService.class);
    EmailOutboxPublisher publisher = new EmailOutboxPublisher(outbox, waitlist, emailService, "https://lpapi.fredvested.com/", 3, 7, 15);

    WaitlistEntry entry;
    EmailMessage message;

    @BeforeEach
    void setUp() {
        entry = new WaitlistEntry();
        entry.setId(7L);
        entry.setEmail("a@example.com");
        message = new EmailMessage();
        message.setId(1L);
        message.setWaitlistId(7L);
        message.setTemplate(EmailMessage.TEMPLATE_CONFIRMATION);
        message.setStatus(EmailMessage.STATUS_PENDING);
        message.setAttempts((short) 0);
        when(waitlist.findById(7L)).thenReturn(Optional.of(entry));
        when(outbox.claim(eq(1L), any(), any())).thenReturn(1);
    }

    @Test
    void aRowAnotherPublisherAlreadyClaimed_isLeftAlone() throws Exception {
        when(outbox.claim(eq(1L), any(), any())).thenReturn(0);
        assertFalse(publisher.publish(message));
        verifyNoInteractions(emailService);
        verify(outbox, never()).save(any());
        verify(waitlist, never()).findById(any());
    }

    @Test
    void suppressedAddress_isNeverSent() throws Exception {
        entry.setSuppressedAt(LocalDateTime.now());
        entry.setSuppressionReason(WaitlistEntry.SUPPRESSION_HARD_BOUNCE);

        assertTrue(publisher.publish(message));

        verifyNoInteractions(emailService);
        assertEquals(EmailMessage.STATUS_SUPPRESSED, message.getStatus());
        assertTrue(message.getLastError().contains("hard_bounce"));
        verify(waitlist, never()).setConfirmationToken(any(), any(), any());
    }

    @Test
    void confirmationSend_mintsHashedTokens_storesTheResendId_andStampsTheSend() throws Exception {
        when(emailService.send(eq("a@example.com"), anyString(), anyString(), anyString())).thenReturn("re_abc");
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<LocalDateTime> expires = ArgumentCaptor.forClass(LocalDateTime.class);

        assertTrue(publisher.publish(message));

        verify(emailService).send(eq("a@example.com"), eq("Confirm your email for the FRED waitlist"), html.capture(), anyString());
        assertEquals("re_abc", message.getResendEmailId());
        assertEquals(EmailMessage.STATUS_SENT, message.getStatus());
        assertEquals((short) 1, message.getAttempts());
        assertNotNull(message.getSentAt());
        assertNull(message.getNextAttemptAt());

        // Only hashes are stored, through targeted updates -- the entity snapshot is never merged back.
        verify(waitlist).setConfirmationToken(eq(7L), hash.capture(), expires.capture());
        verify(waitlist).markConfirmationSent(eq(7L), eq(EmailMessage.STATUS_SENT), any());
        verify(waitlist, never()).save(any());
        assertEquals(64, hash.getValue().length());
        assertNotNull(message.getUnsubscribeTokenHash());
        String confirmRaw = between(html.getValue(), "/api/waitlist/confirm?token=", "\"");
        String unsubRaw = between(html.getValue(), "/api/waitlist/unsubscribe?token=", "\"");
        assertEquals(hash.getValue(), ConfirmationTokens.hash(confirmRaw));
        assertEquals(message.getUnsubscribeTokenHash(), ConfirmationTokens.hash(unsubRaw));
        assertFalse(html.getValue().contains(hash.getValue()));
        assertTrue(html.getValue().contains("https://lpapi.fredvested.com/api/waitlist/confirm?token="), "trailing slash on api.public-url is trimmed");
        assertTrue(Duration.between(LocalDateTime.now(), expires.getValue()).toHours() >= 7 * 24 - 1);
    }

    @Test
    void welcomeTemplate_sendsTheWelcomeEmail_withoutAConfirmationToken() throws Exception {
        message.setTemplate(EmailMessage.TEMPLATE_WELCOME);
        when(emailService.send(anyString(), anyString(), anyString(), anyString())).thenReturn("re_w");

        publisher.publish(message);

        verify(emailService).send(eq("a@example.com"), eq("You're in"), anyString(), anyString());
        verify(waitlist, never()).setConfirmationToken(any(), any(), any());
        verify(waitlist, never()).markConfirmationSent(any(), any(), any());
        verify(waitlist).setEmailStatus(7L, EmailMessage.STATUS_SENT);
        assertNotNull(message.getUnsubscribeTokenHash());
    }

    @Test
    void alreadyConfirmedAddress_skipsAPendingConfirmation() throws Exception {
        entry.setConfirmedAt(LocalDateTime.now());
        publisher.publish(message);
        verifyNoInteractions(emailService);
        assertEquals(EmailMessage.STATUS_SKIPPED, message.getStatus());
    }

    @Test
    void resendFailure_leavesTheMessagePending_withBackoffAndTheErrorRecorded() throws Exception {
        when(emailService.send(anyString(), anyString(), anyString(), anyString())).thenThrow(new RuntimeException("503 from Resend"));

        publisher.publish(message);

        assertEquals(EmailMessage.STATUS_PENDING, message.getStatus());
        assertEquals((short) 1, message.getAttempts());
        assertTrue(message.getLastError().contains("503 from Resend"));
        assertNull(message.getResendEmailId());
        assertNotNull(message.getNextAttemptAt());
        assertTrue(message.getNextAttemptAt().isAfter(LocalDateTime.now().plusSeconds(50)));
        verify(waitlist, never()).markConfirmationSent(any(), any(), any());
    }

    @Test
    void afterMaxAttempts_theMessageFails() throws Exception {
        when(emailService.send(anyString(), anyString(), anyString(), anyString())).thenThrow(new RuntimeException("down"));
        message.setAttempts((short) 2); // max is 3 in this test

        publisher.publish(message);

        assertEquals(EmailMessage.STATUS_FAILED, message.getStatus());
        assertNull(message.getNextAttemptAt());
        verify(waitlist).setEmailStatus(7L, EmailMessage.STATUS_FAILED);
    }

    @Test
    void backoff_isExponential_andCapped() {
        assertEquals(Duration.ofMinutes(1), EmailOutboxPublisher.backoff(1));
        assertEquals(Duration.ofMinutes(2), EmailOutboxPublisher.backoff(2));
        assertEquals(Duration.ofMinutes(4), EmailOutboxPublisher.backoff(3));
        assertEquals(Duration.ofHours(6), EmailOutboxPublisher.backoff(12));
        assertEquals(Duration.ofMinutes(1), EmailOutboxPublisher.backoff(0));
    }

    private static String between(String s, String start, String end) {
        int i = s.indexOf(start);
        assertTrue(i >= 0, "missing " + start);
        int from = i + start.length();
        return s.substring(from, s.indexOf(end, from));
    }
}
