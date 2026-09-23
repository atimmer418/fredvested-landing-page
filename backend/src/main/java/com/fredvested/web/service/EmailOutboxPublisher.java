package com.fredvested.web.service;

import com.fredvested.web.model.EmailMessage;
import com.fredvested.web.model.WaitlistEntry;
import com.fredvested.web.repository.EmailMessageRepository;
import com.fredvested.web.repository.WaitlistRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Sends due outbox rows. Per message:
 *   1. claim it atomically (pending -> sending), so two publishers (a deploy
 *      overlap, a second replica) can never both send it;
 *   2. refuse if the address is suppressed -- suppression is enforced HERE, in the
 *      sending path, not only recorded at the webhook;
 *   3. mint the confirmation / unsubscribe tokens now, at send time, storing only
 *      their hashes, so the raw values exist in the email and nowhere else;
 *   4. call Resend, then record the message id.
 * Waitlist columns are written with targeted UPDATEs, never by merging the
 * snapshot loaded before the network call, so a confirmation, unsubscribe or
 * bounce that lands while Resend is being called is never silently undone.
 * Failures back off exponentially (next_attempt_at) up to max-attempts; a row
 * left in "sending" by a publisher that died is retried after stale-sending-minutes.
 * A crash between the send and recording its result can double-send once.
 */
@Service
public class EmailOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(EmailOutboxPublisher.class);
    private static final ZoneId EASTERN = ZoneId.of("America/New_York");
    private static final Duration MAX_BACKOFF = Duration.ofHours(6);
    private static final int BATCH = 25;

    private final EmailMessageRepository outbox;
    private final WaitlistRepository waitlist;
    private final EmailService emailService;
    private final String apiPublicUrl;
    private final int maxAttempts;
    private final int ttlDays;
    private final int staleSendingMinutes;

    public EmailOutboxPublisher(EmailMessageRepository outbox,
                                WaitlistRepository waitlist,
                                EmailService emailService,
                                @Value("${api.public-url:http://localhost:8081}") String apiPublicUrl,
                                @Value("${email.outbox.max-attempts:12}") int maxAttempts,
                                @Value("${waitlist.confirmation.ttl-days:7}") int ttlDays,
                                @Value("${email.outbox.stale-sending-minutes:15}") int staleSendingMinutes) {
        this.outbox = outbox;
        this.waitlist = waitlist;
        this.emailService = emailService;
        this.apiPublicUrl = apiPublicUrl.replaceAll("/+$", "");
        this.maxAttempts = maxAttempts;
        this.ttlDays = ttlDays;
        this.staleSendingMinutes = staleSendingMinutes;
    }

    @Scheduled(fixedDelayString = "${email.outbox.poll-ms:5000}", initialDelayString = "${email.outbox.initial-delay-ms:10000}")
    public void publishPending() {
        LocalDateTime now = now();
        for (EmailMessage message : outbox.findDue(now, now.minusMinutes(staleSendingMinutes), PageRequest.of(0, BATCH))) {
            try {
                publish(message);
            } catch (RuntimeException e) {
                log.error("Outbox publish failed for message {}: {}", message.getId(), e.toString());
            }
        }
    }

    static Duration backoff(int attempts) {
        Duration d = Duration.ofMinutes(1L << Math.min(Math.max(attempts, 1) - 1, 10));
        return d.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : d;
    }

    /** Returns true if this call sent the message (or settled it), false if another publisher had already claimed it. */
    public boolean publish(EmailMessage message) {
        LocalDateTime now = now();
        if (outbox.claim(message.getId(), now, now.minusMinutes(staleSendingMinutes)) == 0) {
            return false;
        }
        message.setStatus(EmailMessage.STATUS_SENDING);
        message.setAttempts((short) (message.getAttempts() + 1));
        message.setStatusUpdatedAt(now);

        WaitlistEntry entry = waitlist.findById(message.getWaitlistId()).orElse(null);
        if (entry == null) {
            finish(message, EmailMessage.STATUS_FAILED, "waitlist row missing", now);
            return true;
        }
        if (entry.isSuppressed()) {
            finish(message, EmailMessage.STATUS_SUPPRESSED, "address suppressed: " + entry.getSuppressionReason(), now);
            return true;
        }
        boolean confirmation = EmailMessage.TEMPLATE_CONFIRMATION.equals(message.getTemplate());
        if (confirmation && entry.getConfirmedAt() != null) {
            finish(message, EmailMessage.STATUS_SKIPPED, "already confirmed", now);
            return true;
        }

        ConfirmationTokens.Generated unsubscribe = ConfirmationTokens.generate();
        message.setUnsubscribeTokenHash(unsubscribe.hash());
        outbox.save(message);
        String unsubscribeUrl = apiPublicUrl + "/api/waitlist/unsubscribe?token=" + unsubscribe.raw();

        EmailTemplates.Rendered rendered;
        if (confirmation) {
            ConfirmationTokens.Generated confirm = ConfirmationTokens.generate();
            waitlist.setConfirmationToken(entry.getId(), confirm.hash(), now.plusDays(ttlDays));
            rendered = EmailTemplates.confirmation(apiPublicUrl + "/api/waitlist/confirm?token=" + confirm.raw(), unsubscribeUrl, ttlDays);
        } else {
            rendered = EmailTemplates.welcome(unsubscribeUrl);
        }

        try {
            String resendId = emailService.send(entry.getEmail(), rendered.subject(), rendered.html(), rendered.text());
            LocalDateTime sentAt = now();
            message.setResendEmailId(resendId);
            message.setStatus(EmailMessage.STATUS_SENT);
            message.setSentAt(sentAt);
            message.setStatusUpdatedAt(sentAt);
            message.setNextAttemptAt(null);
            message.setLastError(null);
            outbox.save(message);
            if (confirmation) {
                waitlist.markConfirmationSent(entry.getId(), EmailMessage.STATUS_SENT, sentAt);
            } else {
                waitlist.setEmailStatus(entry.getId(), EmailMessage.STATUS_SENT);
            }
            log.info("Sent {} for waitlist id {} (resend id {})", message.getTemplate(), entry.getId(), resendId);
        } catch (Exception e) {
            String error = e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "" : e.getMessage());
            message.setLastError(error.length() > 500 ? error.substring(0, 500) : error);
            message.setStatusUpdatedAt(now());
            if (message.getAttempts() >= maxAttempts) {
                message.setStatus(EmailMessage.STATUS_FAILED);
                message.setNextAttemptAt(null);
                waitlist.setEmailStatus(entry.getId(), EmailMessage.STATUS_FAILED);
                log.error("Giving up on message {} after {} attempts: {}", message.getId(), message.getAttempts(), error);
            } else {
                message.setStatus(EmailMessage.STATUS_PENDING);
                message.setNextAttemptAt(now().plus(backoff(message.getAttempts())));
                log.warn("Send attempt {} failed for message {}: {}", message.getAttempts(), message.getId(), error);
            }
            outbox.save(message);
        }
        return true;
    }

    private void finish(EmailMessage message, String status, String note, LocalDateTime now) {
        message.setStatus(status);
        message.setLastError(note);
        message.setStatusUpdatedAt(now);
        message.setNextAttemptAt(null);
        outbox.save(message);
        log.info("Message {} {}: {}", message.getId(), status, note);
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(EASTERN);
    }
}
