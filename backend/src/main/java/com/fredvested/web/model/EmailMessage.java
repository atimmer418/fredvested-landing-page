package com.fredvested.web.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** Outbox row: one email we intend to send (or have sent) to one waitlist entry. */
@Entity
@Table(name = "email_message")
@Data
public class EmailMessage {

    public static final String TEMPLATE_CONFIRMATION = "waitlist_confirmation";
    public static final String TEMPLATE_WELCOME = "waitlist_welcome";

    public static final String STATUS_PENDING = "pending";
    /** Claimed by a publisher run; reclaimed as due again if it stays here too long (crash mid-send). */
    public static final String STATUS_SENDING = "sending";
    public static final String STATUS_SENT = "sent";
    public static final String STATUS_DELIVERED = "delivered";
    public static final String STATUS_BOUNCED = "bounced";
    public static final String STATUS_COMPLAINED = "complained";
    public static final String STATUS_FAILED = "failed";
    /** Never sent: the address was suppressed by the time the publisher got to it. */
    public static final String STATUS_SUPPRESSED = "suppressed";
    /** Never sent: no longer needed (e.g. a confirmation for an address that confirmed meanwhile). */
    public static final String STATUS_SKIPPED = "skipped";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "waitlist_id", nullable = false)
    private Long waitlistId;

    @Column(name = "resend_email_id", length = 64)
    private String resendEmailId;

    @Column(nullable = false, length = 50)
    private String template;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "status_updated_at")
    private LocalDateTime statusUpdatedAt;

    // created_at of the newest webhook event applied; older events are ignored
    @Column(name = "last_event_at")
    private LocalDateTime lastEventAt;

    @Column(name = "queued_at", nullable = false)
    private LocalDateTime queuedAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(nullable = false)
    private Short attempts = 0;

    @Column(name = "last_error", length = 500)
    private String lastError;

    // null = due now; set to now + backoff after a failed attempt
    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @Column(name = "unsubscribe_token_hash", length = 64)
    private String unsubscribeTokenHash;
}
