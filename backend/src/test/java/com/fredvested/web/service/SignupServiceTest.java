package com.fredvested.web.service;

import com.fredvested.web.model.EmailMessage;
import com.fredvested.web.model.WaitlistEntry;
import com.fredvested.web.repository.EmailMessageRepository;
import com.fredvested.web.repository.WaitlistRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SignupServiceTest {

    WaitlistRepository waitlist = mock(WaitlistRepository.class);
    EmailMessageRepository outbox = mock(EmailMessageRepository.class);
    SignupService service = new SignupService(waitlist, outbox, true);

    WaitlistEntry entry;

    @BeforeEach
    void setUp() {
        entry = new WaitlistEntry();
        entry.setId(7L);
        entry.setEmail("a@example.com");
        entry.setCreatedAt(LocalDateTime.now().minusHours(2));
        // The mock answers by the entry's CURRENT hash, so clearing the hash on use is observable.
        when(waitlist.findByConfirmationTokenHash(anyString())).thenAnswer(inv ->
                Optional.ofNullable(entry.getConfirmationTokenHash())
                        .filter(h -> h.equals(inv.getArgument(0)))
                        .map(h -> entry));
    }

    @Test
    void createSignup_savesTheRowAndQueuesTheConfirmation() {
        when(waitlist.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service.createSignup(entry);
        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(outbox).save(captor.capture());
        assertEquals(7L, captor.getValue().getWaitlistId());
        assertEquals(EmailMessage.TEMPLATE_CONFIRMATION, captor.getValue().getTemplate());
        assertEquals(EmailMessage.STATUS_PENDING, captor.getValue().getStatus());
    }

    @Test
    void createSignup_queuesTheWelcomeEmail_whenDoubleOptInIsOff() {
        SignupService single = new SignupService(waitlist, outbox, false);
        when(waitlist.save(any())).thenAnswer(inv -> inv.getArgument(0));
        single.createSignup(entry);
        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(outbox).save(captor.capture());
        assertEquals(EmailMessage.TEMPLATE_WELCOME, captor.getValue().getTemplate());
    }

    @Test
    void confirm_isSingleUse_andAReplayLooksExactlyLikeAnUnknownToken() {
        ConfirmationTokens.Generated token = ConfirmationTokens.generate();
        entry.setConfirmationTokenHash(token.hash());
        entry.setConfirmationExpiresAt(LocalDateTime.now().plusDays(7));
        entry.setConfirmationSentAt(LocalDateTime.now().minusMinutes(20));

        SignupService.Confirmation first = service.confirm(token.raw());
        assertEquals(SignupService.ConfirmOutcome.CONFIRMED, first.outcome());
        assertEquals("<1", first.hoursBand());
        assertNotNull(entry.getConfirmedAt());
        assertNull(entry.getConfirmationTokenHash());
        assertNull(entry.getConfirmationExpiresAt());

        SignupService.Confirmation replay = service.confirm(token.raw());
        SignupService.Confirmation unknown = service.confirm(ConfirmationTokens.generate().raw());
        assertEquals(replay, unknown); // records compare by value: identical outcome, identical (null) band
        assertEquals(SignupService.ConfirmOutcome.INVALID, replay.outcome());
        verify(waitlist, times(1)).save(entry);
    }

    @Test
    void confirm_expiredToken_isExpired_andStaysUsable_forTheResendFlow() {
        ConfirmationTokens.Generated token = ConfirmationTokens.generate();
        entry.setConfirmationTokenHash(token.hash());
        entry.setConfirmationExpiresAt(LocalDateTime.now().minusMinutes(1));
        assertEquals(SignupService.ConfirmOutcome.EXPIRED, service.confirm(token.raw()).outcome());
        assertNull(entry.getConfirmedAt());
        verify(waitlist, never()).save(any());
    }

    @Test
    void confirm_rejectsGarbage_withoutTouchingTheDatabase() {
        assertEquals(SignupService.ConfirmOutcome.INVALID, service.confirm(null).outcome());
        assertEquals(SignupService.ConfirmOutcome.INVALID, service.confirm("").outcome());
        assertEquals(SignupService.ConfirmOutcome.INVALID, service.confirm("x".repeat(5000)).outcome());
        verify(waitlist, never()).findByConfirmationTokenHash(anyString());
    }

    @Test
    void requestResend_doesNothing_forUnknownConfirmedOrSuppressedAddresses() {
        when(waitlist.findByEmail("nobody@example.com")).thenReturn(null);
        service.requestResend("nobody@example.com");

        entry.setConfirmedAt(LocalDateTime.now());
        when(waitlist.findByEmail("a@example.com")).thenReturn(entry);
        service.requestResend("a@example.com");

        entry.setConfirmedAt(null);
        entry.setSuppressedAt(LocalDateTime.now());
        service.requestResend("a@example.com");

        verify(outbox, never()).save(any());
    }

    @Test
    void requestResend_retiresPendingConfirmations_andQueuesAFreshOne() {
        when(waitlist.findByEmail("a@example.com")).thenReturn(entry);
        EmailMessage stale = new EmailMessage();
        stale.setStatus(EmailMessage.STATUS_PENDING);
        when(outbox.findByWaitlistIdAndTemplateAndStatus(7L, EmailMessage.TEMPLATE_CONFIRMATION, EmailMessage.STATUS_PENDING))
                .thenReturn(List.of(stale));

        service.requestResend("a@example.com");

        assertEquals(EmailMessage.STATUS_SKIPPED, stale.getStatus());
        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(outbox, times(2)).save(captor.capture());
        assertEquals(EmailMessage.STATUS_PENDING, captor.getAllValues().get(1).getStatus());
        assertEquals(EmailMessage.TEMPLATE_CONFIRMATION, captor.getAllValues().get(1).getTemplate());
    }

    @Test
    void unsubscribe_suppressesOnce_andKeepsWorking() {
        ConfirmationTokens.Generated token = ConfirmationTokens.generate();
        EmailMessage m = new EmailMessage();
        m.setWaitlistId(7L);
        m.setUnsubscribeTokenHash(token.hash());
        when(outbox.findByUnsubscribeTokenHash(token.hash())).thenReturn(Optional.of(m));
        when(waitlist.findById(7L)).thenReturn(Optional.of(entry));

        assertTrue(service.unsubscribe(token.raw()));
        assertEquals(WaitlistEntry.SUPPRESSION_UNSUBSCRIBE, entry.getSuppressionReason());
        LocalDateTime firstAt = entry.getSuppressedAt();
        assertTrue(service.unsubscribe(token.raw()));
        assertEquals(firstAt, entry.getSuppressedAt());
        assertFalse(service.unsubscribe("nope"));
        verify(waitlist, times(1)).save(entry);
    }

    @Test
    void hoursBand_boundaries() {
        LocalDateTime t = LocalDateTime.of(2026, 9, 23, 12, 0);
        assertEquals("<1", SignupService.hoursBand(t.minusMinutes(59), t));
        assertEquals("1-6", SignupService.hoursBand(t.minusHours(1), t));
        assertEquals("6-24", SignupService.hoursBand(t.minusHours(6), t));
        assertEquals("24-72", SignupService.hoursBand(t.minusHours(24), t));
        assertEquals("72+", SignupService.hoursBand(t.minusHours(72), t));
        assertEquals("72+", SignupService.hoursBand(null, t));
    }

    @Test
    void hasUnsubscribeToken_hasNoSideEffect() {
        ConfirmationTokens.Generated token = ConfirmationTokens.generate();
        EmailMessage m = new EmailMessage();
        m.setWaitlistId(7L);
        when(outbox.findByUnsubscribeTokenHash(token.hash())).thenReturn(Optional.of(m));
        assertTrue(service.hasUnsubscribeToken(token.raw()));
        assertFalse(service.hasUnsubscribeToken("nope"));
        assertFalse(service.hasUnsubscribeToken(null));
        verify(waitlist, never()).save(any());
        assertNull(entry.getSuppressedAt());
    }
}
