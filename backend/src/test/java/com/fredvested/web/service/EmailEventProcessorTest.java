package com.fredvested.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fredvested.web.model.EmailEvent;
import com.fredvested.web.model.EmailMessage;
import com.fredvested.web.model.WaitlistEntry;
import com.fredvested.web.repository.EmailEventRepository;
import com.fredvested.web.repository.EmailMessageRepository;
import com.fredvested.web.repository.WaitlistRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EmailEventProcessorTest {

    EmailEventRepository events = mock(EmailEventRepository.class);
    EmailMessageRepository outbox = mock(EmailMessageRepository.class);
    WaitlistRepository waitlist = mock(WaitlistRepository.class);
    EmailEventProcessor processor = new EmailEventProcessor(events, outbox, waitlist, new ObjectMapper());

    EmailMessage message;
    WaitlistEntry entry;
    final LocalDateTime received = LocalDateTime.of(2026, 9, 23, 12, 0);

    @BeforeEach
    void setUp() {
        entry = new WaitlistEntry();
        entry.setId(7L);
        entry.setEmail("a@example.com");
        message = new EmailMessage();
        message.setId(1L);
        message.setWaitlistId(7L);
        message.setResendEmailId("re_123");
        message.setTemplate(EmailMessage.TEMPLATE_CONFIRMATION);
        message.setStatus(EmailMessage.STATUS_SENT);
        when(outbox.findByResendEmailIdForUpdate("re_123")).thenReturn(Optional.of(message));
        when(waitlist.findById(7L)).thenReturn(Optional.of(entry));
    }

    static byte[] event(String type, String createdAt, String emailId) {
        return ("{\"type\":\"" + type + "\",\"created_at\":\"" + createdAt + "\",\"data\":{\"email_id\":\"" + emailId
                + "\",\"to\":[\"a@example.com\"],\"subject\":\"Confirm\",\"html\":\"<p>secret</p>\",\"text\":\"secret\"}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void deliveredEvent_isRecorded_withContentStripped_andApplied() throws Exception {
        EmailEventProcessor.Outcome out = processor.process("svix_1", event("email.delivered", "2026-09-23T16:00:00Z", "re_123"), received);

        assertEquals(EmailEventProcessor.Outcome.RECORDED, out);
        ArgumentCaptor<EmailEvent> captor = ArgumentCaptor.forClass(EmailEvent.class);
        verify(events).save(captor.capture());
        EmailEvent saved = captor.getValue();
        assertEquals("svix_1", saved.getSvixId());
        assertEquals("email.delivered", saved.getEventType());
        assertEquals("re_123", saved.getResendEmailId());
        assertEquals(LocalDateTime.of(2026, 9, 23, 12, 0), saved.getOccurredAt()); // 16:00Z in Eastern (EDT)
        assertFalse(saved.getPayload().contains("secret"), "html/text must be stripped before persisting");
        assertTrue(saved.getPayload().contains("re_123"));
        assertEquals(EmailMessage.STATUS_DELIVERED, message.getStatus());
        assertEquals(EmailMessage.STATUS_DELIVERED, entry.getEmailStatus());
        assertEquals(LocalDateTime.of(2026, 9, 23, 12, 0), message.getLastEventAt());
    }

    @Test
    void sameSvixIdTwice_isRecordedOnce_andAppliedOnce() throws Exception {
        byte[] body = event("email.delivered", "2026-09-23T16:00:00Z", "re_123");
        when(events.existsBySvixId("svix_1")).thenReturn(false).thenReturn(true);

        assertEquals(EmailEventProcessor.Outcome.RECORDED, processor.process("svix_1", body, received));
        assertEquals(EmailEventProcessor.Outcome.DUPLICATE, processor.process("svix_1", body, received));

        verify(events, times(1)).save(any());
        verify(outbox, times(1)).save(any());
    }

    @Test
    void olderSentEvent_arrivingAfterDelivered_doesNotDowngrade() throws Exception {
        processor.process("svix_del", event("email.delivered", "2026-09-23T16:00:00Z", "re_123"), received);
        assertEquals(EmailMessage.STATUS_DELIVERED, message.getStatus());

        processor.process("svix_sent", event("email.sent", "2026-09-23T15:59:00Z", "re_123"), received);

        assertEquals(EmailMessage.STATUS_DELIVERED, message.getStatus());
        assertEquals(EmailMessage.STATUS_DELIVERED, entry.getEmailStatus());
        verify(events, times(2)).save(any()); // still recorded, just not applied
    }

    // Counsel, 2026-09-24: open tracking is off entirely. email.opened is not subscribed;
    // if one arrives anyway it is answered 200 and ignored like any unknown event, and
    // it is never persisted (persisting it would be open tracking by another name).
    @Test
    void openedEvent_isIgnored_andNeverPersisted() throws Exception {
        EmailEventProcessor.Outcome out = processor.process("svix_o", event("email.opened", "2026-09-23T16:00:00Z", "re_123"), received);
        assertNotEquals(EmailEventProcessor.Outcome.DUPLICATE, out);
        verify(events, never()).save(any());
        verify(outbox, never()).save(any());
        assertEquals(EmailMessage.STATUS_SENT, message.getStatus());
    }

    @Test
    void unknownEventTypes_areIgnored_andNeverPersisted() throws Exception {
        byte[] contact = "{\"type\":\"contact.created\",\"created_at\":\"2026-09-23T16:00:00Z\",\"data\":{\"email\":\"someone@example.com\"}}".getBytes(StandardCharsets.UTF_8);
        EmailEventProcessor.Outcome out = processor.process("svix_c1", contact, received);
        assertNotEquals(EmailEventProcessor.Outcome.DUPLICATE, out);
        verify(events, never()).save(any());
        verify(outbox, never()).findByResendEmailIdForUpdate(any());
    }

    @Test
    void recordOnlyEvents_doNotMoveTheOrderingWatermark() throws Exception {
        processor.process("s1", event("email.sent", "2026-09-23T15:00:00Z", "re_123"), received);
        processor.process("s3", event("email.clicked", "2026-09-23T17:00:00Z", "re_123"), received);
        // "delivered" arrives late but is newer than the last status change ("sent")
        processor.process("s2", event("email.delivered", "2026-09-23T16:00:00Z", "re_123"), received);

        assertEquals(EmailMessage.STATUS_DELIVERED, message.getStatus());
    }

    @Test
    void bouncedEvent_setsStatus_andSuppressesTheAddress() throws Exception {
        processor.process("svix_b", event("email.bounced", "2026-09-23T16:00:00Z", "re_123"), received);

        assertEquals(EmailMessage.STATUS_BOUNCED, message.getStatus());
        assertNotNull(entry.getSuppressedAt());
        assertEquals(WaitlistEntry.SUPPRESSION_HARD_BOUNCE, entry.getSuppressionReason());
        verify(waitlist).save(entry);
    }

    @Test
    void complainedEvent_suppressesWithComplaintReason() throws Exception {
        processor.process("svix_c", event("email.complained", "2026-09-23T16:00:00Z", "re_123"), received);
        assertEquals(EmailMessage.STATUS_COMPLAINED, message.getStatus());
        assertEquals(WaitlistEntry.SUPPRESSION_COMPLAINT, entry.getSuppressionReason());
    }

    @Test
    void unknownMessageId_isRecorded_andIgnored() throws Exception {
        when(outbox.findByResendEmailIdForUpdate("re_unknown")).thenReturn(Optional.empty());

        EmailEventProcessor.Outcome out = processor.process("svix_u", event("email.delivered", "2026-09-23T16:00:00Z", "re_unknown"), received);

        assertEquals(EmailEventProcessor.Outcome.RECORDED, out);
        verify(events).save(any());
        verify(outbox, never()).save(any());
        verify(waitlist, never()).save(any());
    }

    @Test
    void firstSuppressionWins_laterEventsDoNotOverwriteTheReason() throws Exception {
        processor.process("svix_b", event("email.bounced", "2026-09-23T16:00:00Z", "re_123"), received);
        processor.process("svix_c", event("email.complained", "2026-09-23T16:30:00Z", "re_123"), received);
        assertEquals(WaitlistEntry.SUPPRESSION_HARD_BOUNCE, entry.getSuppressionReason());
    }

    @Test
    void clickedEvent_isRecordedWithoutTheClickBlock_soRawTokensNeverLand() throws Exception {
        byte[] body = ("{\"type\":\"email.clicked\",\"created_at\":\"2026-09-23T16:00:00Z\",\"data\":{\"email_id\":\"re_123\","
                + "\"click\":{\"link\":\"https://lpapi.fredvested.com/api/waitlist/confirm?token=RAWTOKEN\",\"ipAddress\":\"203.0.113.9\",\"userAgent\":\"UA\"}}}")
                .getBytes(StandardCharsets.UTF_8);
        processor.process("svix_click", body, received);
        ArgumentCaptor<EmailEvent> captor = ArgumentCaptor.forClass(EmailEvent.class);
        verify(events).save(captor.capture());
        assertFalse(captor.getValue().getPayload().contains("token="));
        assertFalse(captor.getValue().getPayload().contains("203.0.113.9"));
        assertEquals(EmailMessage.STATUS_SENT, message.getStatus()); // record only
    }

    @Test
    void transientBounce_isRecorded_butDoesNotSuppress() throws Exception {
        byte[] body = ("{\"type\":\"email.bounced\",\"created_at\":\"2026-09-23T16:00:00Z\",\"data\":{\"email_id\":\"re_123\","
                + "\"bounce\":{\"type\":\"Transient\",\"message\":\"mailbox full\"}}}").getBytes(StandardCharsets.UTF_8);
        processor.process("svix_tb", body, received);
        assertEquals(EmailMessage.STATUS_BOUNCED, message.getStatus());
        assertNull(entry.getSuppressedAt());
    }

    @Test
    void permanentBounce_suppresses() throws Exception {
        byte[] body = ("{\"type\":\"email.bounced\",\"created_at\":\"2026-09-23T16:00:00Z\",\"data\":{\"email_id\":\"re_123\","
                + "\"bounce\":{\"type\":\"Permanent\"}}}").getBytes(StandardCharsets.UTF_8);
        processor.process("svix_pb", body, received);
        assertEquals(WaitlistEntry.SUPPRESSION_HARD_BOUNCE, entry.getSuppressionReason());
    }

    @Test
    void statusEventWithoutAUsableTimestamp_isRecordedButNeverApplied() throws Exception {
        processor.process("s_del", event("email.delivered", "2026-09-23T16:00:00Z", "re_123"), received);
        byte[] noTime = "{\"type\":\"email.sent\",\"data\":{\"email_id\":\"re_123\"}}".getBytes(StandardCharsets.UTF_8);
        processor.process("s_notime", noTime, received);
        assertEquals(EmailMessage.STATUS_DELIVERED, message.getStatus());
        verify(events, times(2)).save(any());
    }
}
