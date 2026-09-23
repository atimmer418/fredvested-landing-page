package com.fredvested.web.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fredvested.web.model.EmailEvent;
import com.fredvested.web.model.EmailMessage;
import com.fredvested.web.model.WaitlistEntry;
import com.fredvested.web.repository.EmailEventRepository;
import com.fredvested.web.repository.EmailMessageRepository;
import com.fredvested.web.repository.WaitlistRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

/**
 * Records a verified webhook event and applies it, in one short transaction.
 *   Idempotency: svix_id is UNIQUE on email_event. The fast path checks it
 *     first; if two deliveries of the same id race, the constraint rejects the
 *     second insert, that transaction rolls back (so nothing was applied twice),
 *     and the controller answers 200 duplicate.
 *   Ordering: the message row is read with a row lock (SELECT ... FOR UPDATE),
 *     so events for one message serialise; a status-changing event is applied
 *     only if its created_at is not older than the newest status-changing event
 *     already applied (email_message.last_event_at). Record-only events (opened,
 *     clicked, delayed) never move that watermark, so a late "delivered" still
 *     lands. An event with no usable created_at is recorded but never applied.
 *   Unknown message id: recorded, otherwise ignored. Never an error.
 *   Payload: html/text and the click block (which carries the clicked URL, i.e.
 *     a raw token, plus the recipient's IP and user agent) are never persisted.
 */
@Service
public class EmailEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(EmailEventProcessor.class);
    private static final ZoneId EASTERN = ZoneId.of("America/New_York");

    public enum Outcome { RECORDED, DUPLICATE }

    private final EmailEventRepository events;
    private final EmailMessageRepository outbox;
    private final WaitlistRepository waitlist;
    private final ObjectMapper mapper;

    public EmailEventProcessor(EmailEventRepository events, EmailMessageRepository outbox,
                               WaitlistRepository waitlist, ObjectMapper mapper) {
        this.events = events;
        this.outbox = outbox;
        this.waitlist = waitlist;
        this.mapper = mapper;
    }

    @Transactional
    public Outcome process(String svixId, byte[] body, LocalDateTime receivedAt) throws IOException {
        if (events.existsBySvixId(svixId)) return Outcome.DUPLICATE;

        JsonNode root = mapper.readTree(body);
        String type = root.path("type").asText(null);
        JsonNode data = root.path("data");
        String emailId = data.path("email_id").asText(null);
        LocalDateTime occurredAt = parseInstant(root.path("created_at").asText(null));
        String bounceType = data.path("bounce").path("type").asText(null);

        EmailEvent event = new EmailEvent();
        event.setSvixId(svixId);
        event.setResendEmailId(emailId);
        event.setEventType(type == null ? "unknown" : type);
        event.setOccurredAt(occurredAt);
        event.setReceivedAt(receivedAt);
        event.setPayload(mapper.writeValueAsString(stripContent(root)));
        events.save(event);

        if (type != null && emailId != null) apply(type, emailId, occurredAt, bounceType);
        return Outcome.RECORDED;
    }

    static JsonNode stripContent(JsonNode root) {
        if (!root.isObject()) return root;
        ObjectNode copy = root.deepCopy();
        JsonNode data = copy.get("data");
        if (data != null && data.isObject()) {
            ((ObjectNode) data).remove("html");
            ((ObjectNode) data).remove("text");
            ((ObjectNode) data).remove("click");
        }
        return copy;
    }

    private void apply(String type, String emailId, LocalDateTime occurredAt, String bounceType) {
        Optional<EmailMessage> found = outbox.findByResendEmailIdForUpdate(emailId);
        if (found.isEmpty()) {
            log.info("Webhook {} for unknown message id; recorded and ignored", type);
            return;
        }
        EmailMessage message = found.get();
        WaitlistEntry entry = waitlist.findById(message.getWaitlistId()).orElse(null);

        switch (type) {
            case "email.sent" -> setStatus(message, entry, EmailMessage.STATUS_SENT, occurredAt);
            case "email.delivered" -> setStatus(message, entry, EmailMessage.STATUS_DELIVERED, occurredAt);
            case "email.bounced" -> {
                setStatus(message, entry, EmailMessage.STATUS_BOUNCED, occurredAt);
                // A transient bounce (mailbox full, greylisting) is not a dead address.
                if (!"Transient".equalsIgnoreCase(bounceType)) {
                    suppress(entry, WaitlistEntry.SUPPRESSION_HARD_BOUNCE, occurredAt);
                } else {
                    log.info("Transient bounce for message {}: recorded, address not suppressed", message.getId());
                }
            }
            case "email.complained" -> {
                setStatus(message, entry, EmailMessage.STATUS_COMPLAINED, occurredAt);
                suppress(entry, WaitlistEntry.SUPPRESSION_COMPLAINT, occurredAt);
            }
            case "email.failed" -> {
                setStatus(message, entry, EmailMessage.STATUS_FAILED, occurredAt);
                log.warn("Resend reported email.failed for message {}", message.getId());
            }
            case "email.suppressed" -> suppress(entry, WaitlistEntry.SUPPRESSION_MANUAL, occurredAt);
            case "email.delivery_delayed", "email.opened", "email.clicked" -> { /* recorded only */ }
            default -> log.info("Unhandled webhook type {} recorded only", type);
        }
        outbox.save(message);
        if (entry != null) waitlist.save(entry);
    }

    private void setStatus(EmailMessage message, WaitlistEntry entry, String status, LocalDateTime occurredAt) {
        if (occurredAt == null) {
            log.warn("Webhook {} for message {} has no usable created_at; recorded, not applied", status, message.getId());
            return;
        }
        if (message.getLastEventAt() != null && occurredAt.isBefore(message.getLastEventAt())) {
            log.info("Out-of-order webhook ignored: {} at {} is older than {} already applied to message {}",
                    status, occurredAt, message.getLastEventAt(), message.getId());
            return;
        }
        message.setStatus(status);
        message.setStatusUpdatedAt(LocalDateTime.now(EASTERN));
        message.setLastEventAt(occurredAt);
        if (entry != null) entry.setEmailStatus(status);
    }

    private void suppress(WaitlistEntry entry, String reason, LocalDateTime occurredAt) {
        if (entry == null || entry.isSuppressed()) return;
        entry.setSuppressedAt(occurredAt != null ? occurredAt : LocalDateTime.now(EASTERN));
        entry.setSuppressionReason(reason);
        log.warn("Address suppressed ({}) for waitlist id {}", reason, entry.getId());
    }

    static LocalDateTime parseInstant(String iso) {
        if (iso == null) return null;
        try {
            return LocalDateTime.ofInstant(Instant.parse(iso), EASTERN);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
