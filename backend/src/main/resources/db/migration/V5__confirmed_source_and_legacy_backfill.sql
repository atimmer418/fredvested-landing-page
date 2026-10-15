-- V5: confirmed_source, the legacy backfill, and the funnel view split by source.
--
-- The public statistic, the founder cap and the funnel now count confirmed rows
-- only. Signups from before double opt-in never had a confirmation email, but
-- they did receive the old welcome email, which is reasonable evidence of a
-- reachable address, so they are kept and tagged 'legacy' with
-- confirmed_at = created_at. A later hard bounce or complaint suppresses them
-- like anyone else.
--
-- "Predates double opt-in" is defined per environment as "no confirmation email
-- was ever queued for the row" (the signup transaction queues one for every
-- double opt-in signup), so the same file is correct on dev and on prod without
-- a deploy timestamp. Rows with a pending confirmation are left alone.
--
-- Idempotent column add: databases baselined before Flyway may or may not have
-- the column (MySQL has no ADD COLUMN IF NOT EXISTS).

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE waitlist_signups ADD COLUMN confirmed_source VARCHAR(20) NULL AFTER confirmed_at',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'waitlist_signups' AND COLUMN_NAME = 'confirmed_source');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Rows confirmed through the double opt-in flow before this column existed.
UPDATE waitlist_signups
SET confirmed_source = 'double_opt_in'
WHERE confirmed_at IS NOT NULL AND confirmed_source IS NULL;

-- The legacy backfill.
UPDATE waitlist_signups w
SET w.confirmed_at = IFNULL(w.created_at, CURRENT_TIMESTAMP(6)),
    w.confirmed_source = 'legacy'
WHERE w.confirmed_at IS NULL
  AND NOT EXISTS (SELECT 1 FROM email_message m
                  WHERE m.waitlist_id = w.id AND m.template = 'waitlist_confirmation');

-- The weekly funnel, now with the confirmed split. confirmed_source is NULL until a
-- row confirms, so the per-source counts use the null-safe <=> comparison: a
-- group whose rows are all pending must report 0, not NULL. Same measurement rules as
-- V4: each signup's FIRST confirmation email that Resend accepted is the one
-- measured; "delivered" means a delivered event was ever received for it.
-- confirmed_rate is double-opt-in confirmations over delivered confirmation
-- emails (legacy and single-opt-in rows never had one to click). Opens are not
-- tracked anywhere: open tracking is off on the sending domain and email.opened
-- is not subscribed, so nothing here can count them.
CREATE OR REPLACE VIEW v_waitlist_funnel AS
SELECT
    w.utm_source,
    w.utm_campaign,
    w.utm_content,
    COUNT(*)                                                                                        AS submitted,
    SUM(w.confirmation_sent_at IS NOT NULL)                                                         AS confirmation_sent,
    SUM(m.resend_email_id IS NOT NULL AND EXISTS (SELECT 1 FROM email_event e WHERE e.resend_email_id = m.resend_email_id AND e.event_type = 'email.delivered'))                                                                     AS delivered,
    SUM(w.confirmed_at IS NOT NULL)                                                                 AS confirmed,
    SUM(w.confirmed_source <=> 'double_opt_in')                                                     AS confirmed_double_opt_in,
    SUM(w.confirmed_source <=> 'legacy')                                                            AS confirmed_legacy,
    SUM(w.confirmed_source <=> 'single_opt_in')                                                     AS confirmed_single_opt_in,
    SUM(w.status IN ('INVITED', 'CLAIMED'))                                                         AS beta_invited,
    ROUND(SUM(w.confirmation_sent_at IS NOT NULL) / NULLIF(COUNT(*), 0), 4)                         AS sent_rate,
    ROUND(SUM(m.resend_email_id IS NOT NULL AND EXISTS (SELECT 1 FROM email_event e WHERE e.resend_email_id = m.resend_email_id AND e.event_type = 'email.delivered')) / NULLIF(SUM(w.confirmation_sent_at IS NOT NULL), 0), 4)      AS delivered_rate,
    ROUND(SUM(w.confirmed_source <=> 'double_opt_in') / NULLIF(SUM(m.resend_email_id IS NOT NULL AND EXISTS (SELECT 1 FROM email_event e WHERE e.resend_email_id = m.resend_email_id AND e.event_type = 'email.delivered')), 0), 4)  AS confirmed_rate,
    ROUND(SUM(w.status IN ('INVITED', 'CLAIMED')) / NULLIF(SUM(w.confirmed_at IS NOT NULL), 0), 4)  AS invited_rate,
    ROUND(IFNULL(SUM(m.status = 'bounced'), 0) / NULLIF(SUM(w.confirmation_sent_at IS NOT NULL), 0), 4)    AS bounce_rate,
    ROUND(IFNULL(SUM(m.status = 'complained'), 0) / NULLIF(SUM(w.confirmation_sent_at IS NOT NULL), 0), 4) AS complaint_rate
FROM waitlist_signups w
LEFT JOIN email_message m
       ON m.id = (SELECT MIN(m2.id) FROM email_message m2
                  WHERE m2.waitlist_id = w.id AND m2.template = 'waitlist_confirmation' AND m2.sent_at IS NOT NULL)
GROUP BY w.utm_source, w.utm_campaign, w.utm_content;
