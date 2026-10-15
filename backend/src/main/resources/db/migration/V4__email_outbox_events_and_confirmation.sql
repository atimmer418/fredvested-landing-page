-- V4: double opt-in, email outbox, webhook events, suppression, funnel view.
--
-- Waitlist additions. Only the SHA-256 of a confirmation token is ever stored
-- (the raw token exists only in the email), so a database read cannot confirm
-- someone else's address. VARCHAR(64) rather than CHAR(64) so Hibernate's
-- schema validation matches the String mapping without a columnDefinition.
ALTER TABLE waitlist_signups
    ADD COLUMN confirmation_token_hash  VARCHAR(64)  NULL,
    ADD COLUMN confirmation_sent_at     DATETIME(6)  NULL,
    ADD COLUMN confirmation_expires_at  DATETIME(6)  NULL,
    ADD COLUMN confirmed_at             DATETIME(6)  NULL,
    ADD COLUMN email_status             VARCHAR(20)  NULL,
    ADD COLUMN suppressed_at            DATETIME(6)  NULL,
    ADD COLUMN suppression_reason       VARCHAR(40)  NULL;

CREATE INDEX idx_waitlist_signups_confirmation_token ON waitlist_signups (confirmation_token_hash);

-- Outbox. A pending row is written in the same transaction as the signup; the
-- scheduled publisher sends it and records Resend's message id, which is the
-- key every webhook event joins back on. Each email carries its own random
-- unsubscribe token (hash only, like the confirmation token) so the link in a
-- sent email keeps working without the raw value ever being stored.
-- last_event_at is the created_at of the newest status-changing webhook event
-- applied to the row: an older event is ignored, which is the out-of-order guard.
-- next_attempt_at drives retries (null = due now), so rows in backoff never
-- crowd newer rows out of the publisher's fetch window.
CREATE TABLE email_message (
    id                      BIGINT       NOT NULL AUTO_INCREMENT,
    waitlist_id             BIGINT       NOT NULL,
    resend_email_id         VARCHAR(64)  NULL,
    template                VARCHAR(50)  NOT NULL,
    status                  VARCHAR(20)  NOT NULL,
    status_updated_at       DATETIME(6)  NULL,
    last_event_at           DATETIME(6)  NULL,
    queued_at               DATETIME(6)  NOT NULL,
    sent_at                 DATETIME(6)  NULL,
    attempts                SMALLINT     NOT NULL DEFAULT 0,
    last_error              VARCHAR(500) NULL,
    next_attempt_at         DATETIME(6)  NULL,
    unsubscribe_token_hash  VARCHAR(64)  NULL,
    PRIMARY KEY (id),
    -- A signup can be deleted (the privacy policy promises it); its outbox rows go with it.
    -- email_event is deliberately not FK-linked and keeps the audit trail by resend_email_id.
    CONSTRAINT fk_email_message_waitlist FOREIGN KEY (waitlist_id) REFERENCES waitlist_signups (id) ON DELETE CASCADE,
    INDEX idx_email_message_resend_id (resend_email_id),
    INDEX idx_email_message_status_next (status, next_attempt_at, queued_at),
    INDEX idx_email_message_unsubscribe_token (unsubscribe_token_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Every webhook delivery, keyed by svix-id: the UNIQUE constraint is the
-- idempotency guarantee (Resend delivers at least once). payload is the raw
-- event minus any html/text keys.
CREATE TABLE email_event (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    svix_id          VARCHAR(64) NOT NULL,
    resend_email_id  VARCHAR(64) NULL,
    event_type       VARCHAR(40) NOT NULL,
    occurred_at      DATETIME(6) NULL,
    received_at      DATETIME(6) NOT NULL,
    payload          JSON        NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_email_event_svix_id (svix_id),
    INDEX idx_email_event_resend_id (resend_email_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- The weekly view: the whole post-submit funnel in one row per piece of
-- content. Each signup's FIRST confirmation email that Resend actually
-- accepted (sent_at set) is the one measured; "delivered" means a delivered
-- event was ever received for it, so a later complaint does not remove it.
-- Rates are step over step; bounce/complaint rates are against confirmation
-- emails sent. Legacy rows (before attribution/double opt-in) group under NULL.
CREATE VIEW v_waitlist_funnel AS
SELECT
    w.utm_source,
    w.utm_campaign,
    w.utm_content,
    COUNT(*)                                                                                        AS submitted,
    SUM(w.confirmation_sent_at IS NOT NULL)                                                         AS confirmation_sent,
    SUM(m.resend_email_id IS NOT NULL AND EXISTS (SELECT 1 FROM email_event e WHERE e.resend_email_id = m.resend_email_id AND e.event_type = 'email.delivered'))                                                                     AS delivered,
    SUM(w.confirmed_at IS NOT NULL)                                                                 AS confirmed,
    SUM(w.status IN ('INVITED', 'CLAIMED'))                                                         AS beta_invited,
    ROUND(SUM(w.confirmation_sent_at IS NOT NULL) / NULLIF(COUNT(*), 0), 4)                         AS sent_rate,
    ROUND(SUM(m.resend_email_id IS NOT NULL AND EXISTS (SELECT 1 FROM email_event e WHERE e.resend_email_id = m.resend_email_id AND e.event_type = 'email.delivered')) / NULLIF(SUM(w.confirmation_sent_at IS NOT NULL), 0), 4)      AS delivered_rate,
    ROUND(SUM(w.confirmed_at IS NOT NULL) / NULLIF(SUM(m.resend_email_id IS NOT NULL AND EXISTS (SELECT 1 FROM email_event e WHERE e.resend_email_id = m.resend_email_id AND e.event_type = 'email.delivered')), 0), 4)              AS confirmed_rate,
    ROUND(SUM(w.status IN ('INVITED', 'CLAIMED')) / NULLIF(SUM(w.confirmed_at IS NOT NULL), 0), 4)  AS invited_rate,
    ROUND(IFNULL(SUM(m.status = 'bounced'), 0) / NULLIF(SUM(w.confirmation_sent_at IS NOT NULL), 0), 4)    AS bounce_rate,
    ROUND(IFNULL(SUM(m.status = 'complained'), 0) / NULLIF(SUM(w.confirmation_sent_at IS NOT NULL), 0), 4) AS complaint_rate
FROM waitlist_signups w
LEFT JOIN email_message m
       ON m.id = (SELECT MIN(m2.id) FROM email_message m2
                  WHERE m2.waitlist_id = w.id AND m2.template = 'waitlist_confirmation' AND m2.sent_at IS NOT NULL)
GROUP BY w.utm_source, w.utm_campaign, w.utm_content;

-- The admin query for suppression: who we will never mail again, and why.
CREATE VIEW v_email_suppressions AS
SELECT id, email, email_status, suppressed_at, suppression_reason, created_at
FROM waitlist_signups
WHERE suppressed_at IS NOT NULL;
