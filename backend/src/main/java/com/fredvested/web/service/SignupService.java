package com.fredvested.web.service;

import com.fredvested.web.model.EmailMessage;
import com.fredvested.web.model.WaitlistEntry;
import com.fredvested.web.repository.EmailMessageRepository;
import com.fredvested.web.repository.WaitlistRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * The waitlist's state transitions: signup (with its outbox row, in one
 * transaction), confirmation, resend, unsubscribe. Nothing here talks to
 * Resend; the outbox publisher does, later, on its own thread.
 */
@Service
public class SignupService {

    private static final Logger log = LoggerFactory.getLogger(SignupService.class);
    private static final ZoneId EASTERN = ZoneId.of("America/New_York");

    private final WaitlistRepository waitlist;
    private final EmailMessageRepository outbox;
    private final boolean doubleOptIn;

    public SignupService(WaitlistRepository waitlist,
                         EmailMessageRepository outbox,
                         @Value("${waitlist.double-opt-in.enabled:true}") boolean doubleOptIn) {
        this.waitlist = waitlist;
        this.outbox = outbox;
        this.doubleOptIn = doubleOptIn;
    }

    public boolean isDoubleOptIn() {
        return doubleOptIn;
    }

    /**
     * Saves the signup and its pending email in the same transaction, so a crash
     * between the two can never lose the confirmation. The email itself is sent
     * asynchronously by EmailOutboxPublisher.
     */
    @Transactional
    public WaitlistEntry createSignup(WaitlistEntry entry) {
        WaitlistEntry saved = waitlist.save(entry);
        if (saved == null) saved = entry;
        enqueue(saved, doubleOptIn ? EmailMessage.TEMPLATE_CONFIRMATION : EmailMessage.TEMPLATE_WELCOME);
        return saved;
    }

    public enum ConfirmOutcome { CONFIRMED, EXPIRED, INVALID }

    /** hoursBand is set only when CONFIRMED. */
    public record Confirmation(ConfirmOutcome outcome, String hoursBand) {}

    /**
     * Single use: the token hash is cleared on success, so replaying the same
     * token afterwards is an unknown token, and the caller cannot tell "never
     * existed" from "already used" -- by construction, not by convention.
     */
    @Transactional
    public Confirmation confirm(String rawToken) {
        String hash = ConfirmationTokens.hash(rawToken);
        if (hash == null) return new Confirmation(ConfirmOutcome.INVALID, null);
        Optional<WaitlistEntry> found = waitlist.findByConfirmationTokenHash(hash);
        if (found.isEmpty()) return new Confirmation(ConfirmOutcome.INVALID, null);

        WaitlistEntry entry = found.get();
        LocalDateTime now = now();
        if (entry.getConfirmationExpiresAt() == null || now.isAfter(entry.getConfirmationExpiresAt())) {
            return new Confirmation(ConfirmOutcome.EXPIRED, null);
        }
        entry.setConfirmedAt(now);
        entry.setConfirmationTokenHash(null);
        entry.setConfirmationExpiresAt(null);
        waitlist.save(entry);

        LocalDateTime from = entry.getConfirmationSentAt() != null ? entry.getConfirmationSentAt() : entry.getCreatedAt();
        return new Confirmation(ConfirmOutcome.CONFIRMED, hoursBand(from, now));
    }

    /**
     * Queues a fresh confirmation for an unconfirmed, unsuppressed address. Does
     * nothing otherwise. The caller's response is the same either way; rate
     * limiting is the caller's job.
     */
    @Transactional
    public void requestResend(String email) {
        if (!doubleOptIn) return;
        WaitlistEntry entry = waitlist.findByEmail(email);
        if (entry == null || entry.getConfirmedAt() != null || entry.isSuppressed()) return;
        // A still-pending confirmation would go out alongside the new one; retire it.
        List<EmailMessage> pending = outbox.findByWaitlistIdAndTemplateAndStatus(
                entry.getId(), EmailMessage.TEMPLATE_CONFIRMATION, EmailMessage.STATUS_PENDING);
        for (EmailMessage m : pending) {
            m.setStatus(EmailMessage.STATUS_SKIPPED);
            m.setStatusUpdatedAt(now());
            m.setLastError("superseded by a resend request");
            outbox.save(m);
        }
        enqueue(entry, EmailMessage.TEMPLATE_CONFIRMATION);
    }

    /** Whether an unsubscribe token was issued (no side effect; backs the GET step of the two-step unsubscribe). */
    public boolean hasUnsubscribeToken(String rawToken) {
        String hash = ConfirmationTokens.hash(rawToken);
        return hash != null && outbox.findByUnsubscribeTokenHash(hash).isPresent();
    }

    /** Idempotent: an unsubscribe link keeps working. Returns false only for an unknown token. */
    @Transactional
    public boolean unsubscribe(String rawToken) {
        String hash = ConfirmationTokens.hash(rawToken);
        if (hash == null) return false;
        Optional<EmailMessage> message = outbox.findByUnsubscribeTokenHash(hash);
        if (message.isEmpty()) return false;
        WaitlistEntry entry = waitlist.findById(message.get().getWaitlistId()).orElse(null);
        if (entry == null) return false;
        if (!entry.isSuppressed()) {
            entry.setSuppressedAt(now());
            entry.setSuppressionReason(WaitlistEntry.SUPPRESSION_UNSUBSCRIBE);
            waitlist.save(entry);
        }
        return true;
    }

    private void enqueue(WaitlistEntry entry, String template) {
        EmailMessage m = new EmailMessage();
        m.setWaitlistId(entry.getId());
        m.setTemplate(template);
        m.setStatus(EmailMessage.STATUS_PENDING);
        m.setQueuedAt(now());
        m.setAttempts((short) 0);
        outbox.save(m);
        log.debug("Queued {} for waitlist id {}", template, entry.getId());
    }

    // <1 | 1-6 | 6-24 | 24-72 | 72+ hours between the confirmation being sent and clicked
    static String hoursBand(LocalDateTime from, LocalDateTime to) {
        if (from == null) return "72+";
        double hours = Duration.between(from, to).toMinutes() / 60.0;
        if (hours < 1) return "<1";
        if (hours < 6) return "1-6";
        if (hours < 24) return "6-24";
        if (hours < 72) return "24-72";
        return "72+";
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(EASTERN);
    }
}
