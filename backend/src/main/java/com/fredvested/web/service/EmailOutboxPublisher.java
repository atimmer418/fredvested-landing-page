package com.fredvested.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fredvested.web.email.EmailChartController;
import com.fredvested.web.email.PersonalizedWelcomeEmail;
import com.fredvested.web.email.Projection;
import com.fredvested.web.email.ProjectionJson;
import com.fredvested.web.model.EmailMessage;
import com.fredvested.web.model.WaitlistEntry;
import com.fredvested.web.repository.EmailMessageRepository;
import com.fredvested.web.repository.WaitlistRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

/**
 * Sends due outbox rows. Per message:
 *   1. claim it atomically (pending -> sending), so two publishers (a deploy
 *      overlap, a second replica) can never both send it;
 *   2. refuse if the address is suppressed -- suppression is enforced HERE, in the
 *      sending path, not only recorded at the webhook;
 *   3. mint the confirmation / unsubscribe / chart tokens now, at send time, storing only
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
    // Rendered in every email footer (CAN-SPAM). "[PO Box pending]" until POSTAL_ADDRESS is set.
    private final String postalAddress;
    private final int maxAttempts;
    private final int ttlDays;
    private final int staleSendingMinutes;
    // email.templates.v2.enabled: the personalised welcome (projection + chart) and the
    // confirmation in the same frame. Off = the v1 templates, byte for byte as before.
    private final boolean templatesV2;
    private final ObjectMapper objectMapper;

    @Autowired
    public EmailOutboxPublisher(EmailMessageRepository outbox,
                                WaitlistRepository waitlist,
                                EmailService emailService,
                                ObjectMapper objectMapper,
                                @Value("${api.public-url:http://localhost:8081}") String apiPublicUrl,
                                @Value("${email.postal-address:[PO Box pending]}") String postalAddress,
                                @Value("${email.outbox.max-attempts:12}") int maxAttempts,
                                @Value("${waitlist.confirmation.ttl-days:7}") int ttlDays,
                                @Value("${email.outbox.stale-sending-minutes:15}") int staleSendingMinutes,
                                @Value("${email.templates.v2.enabled:false}") boolean templatesV2) {
        this.outbox = outbox;
        this.waitlist = waitlist;
        this.emailService = emailService;
        this.objectMapper = objectMapper;
        this.apiPublicUrl = publicBaseUrl(apiPublicUrl);
        this.postalAddress = postalAddress;
        this.maxAttempts = maxAttempts;
        this.ttlDays = ttlDays;
        this.staleSendingMinutes = staleSendingMinutes;
        this.templatesV2 = templatesV2;
    }

    /**
     * The base every emailed link is built on. Trailing slashes are dropped, and a
     * public host is forced to https whatever the variable says: the links carry
     * single-use tokens, and an http link sends the token in cleartext before the
     * edge's 301 upgrades it (and some phone mail clients never follow that 301).
     * Only local development hosts keep http.
     */
    static String publicBaseUrl(String configured) {
        String base = configured.trim().replaceAll("/+$", "");
        if (!base.regionMatches(true, 0, "http://", 0, 7)) return base;
        String host;
        try {
            host = java.net.URI.create(base).getHost();
        } catch (IllegalArgumentException e) {
            host = null;
        }
        if (host != null && isLocalHost(host)) return base;
        String upgraded = "https://" + base.substring(7);
        log.warn("api.public-url is {}; emailed links will use {} instead. Set API_PUBLIC_URL to the https origin.", base, upgraded);
        return upgraded;
    }

    private static boolean isLocalHost(String host) {
        return host.equals("localhost")
                || host.equals("127.0.0.1")
                || host.equals("::1")
                || host.equals("[::1]")
                || host.startsWith("192.168.")
                || host.startsWith("10.")
                || host.matches("172\\.(1[6-9]|2\\d|3[01])\\..*");
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

        String confirmUrl = null;
        if (confirmation) {
            ConfirmationTokens.Generated confirm = ConfirmationTokens.generate();
            waitlist.setConfirmationToken(entry.getId(), confirm.hash(), now.plusDays(ttlDays));
            confirmUrl = apiPublicUrl + "/api/waitlist/confirm?token=" + confirm.raw();
        }

        try {
            // Rendering happens inside the try as well, so a template or projection problem
            // backs off and eventually fails the message like a failed send, instead of
            // escaping publish() and leaving the row in "sending" to be reclaimed forever.
            EmailTemplates.Rendered rendered;
            if (confirmation) {
                rendered = templatesV2
                        ? EmailTemplates.confirmationV2(confirmUrl, unsubscribeUrl, ttlDays, postalAddress)
                        : EmailTemplates.confirmation(confirmUrl, unsubscribeUrl, ttlDays, postalAddress);
            } else if (templatesV2) {
                Projection projection = projection(entry);
                String chartUrl = null;
                if (projection != null) {
                    // The chart is a URL on this API (EmailChartController) drawn from the row's
                    // stored projection on request. Its token is minted here like the unsubscribe
                    // token, only the hash stored, and written before the send so the picture in
                    // the email always resolves. A retry mints a fresh one; the old URL dies with
                    // the message that never went out.
                    ConfirmationTokens.Generated chart = ConfirmationTokens.generate();
                    waitlist.setChartToken(entry.getId(), chart.hash());
                    chartUrl = EmailChartController.url(apiPublicUrl, chart.raw());
                }
                rendered = PersonalizedWelcomeEmail.render(projection, chartUrl, unsubscribeUrl, postalAddress);
            } else {
                rendered = EmailTemplates.welcome(unsubscribeUrl, postalAddress);
            }
            // No List-Unsubscribe headers on any email (Andrew, 2026-09-25): mail clients
            // label any message carrying them as list mail (Apple Mail's "this message is
            // from a mailing list" strip), and the footer unsubscribe link is the opt-out.
            // The RFC 8058 header pair is what Gmail and Yahoo require above roughly 5,000
            // messages a day; if volume ever gets there, add it back here per template
            // (POST /api/waitlist/unsubscribe already honours the one-click body).
            Map<String, String> headers = Map.of();
            String resendId = emailService.send(entry.getEmail(), rendered.subject(), rendered.html(), rendered.text(), headers);
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

    /**
     * The row's stored projection (projection_json, V8), or null when there is none or it
     * cannot be read: the welcome email then falls back to its no-projection copy rather
     * than failing. Only the waitlist id is logged.
     */
    private Projection projection(WaitlistEntry entry) {
        String json = entry.getProjectionJson();
        if (json == null || json.isBlank()) return null;
        Projection p = ProjectionJson.read(objectMapper, json);
        if (p == null) log.warn("welcome projection unreadable for waitlist id {}", entry.getId());
        return p;
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
