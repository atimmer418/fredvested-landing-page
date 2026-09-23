-- V3: attribution stored with each signup, plus the server-recomputed projection.
--
-- Deliberately NOT added, to keep one source of truth per fact:
--   input_age / input_monthly_invest / input_target_income -> current_age,
--     invest_monthly, retire_monthly already hold the calculator inputs.
--   computed_freedom_age -> freedom_age, which from V3 on holds the value the
--     server recomputes from the inputs (the client's number is only compared
--     against it and logged on drift).
--   return_scenario -> return_assumption_pct already encodes it (8/10/12).
-- utm_content is the per-post identifier (yyyymmdd-slug) and lives only here,
-- never in Plausible. All attribution strings are sanitised server-side to
-- [a-z0-9-_.] and capped before they reach these columns.
ALTER TABLE waitlist_signups
    ADD COLUMN utm_source                VARCHAR(100) NULL,
    ADD COLUMN utm_medium                VARCHAR(100) NULL,
    ADD COLUMN utm_campaign              VARCHAR(100) NULL,
    ADD COLUMN utm_content               VARCHAR(100) NULL,
    ADD COLUMN utm_term                  VARCHAR(100) NULL,
    ADD COLUMN first_utm_source          VARCHAR(100) NULL,
    ADD COLUMN first_utm_campaign        VARCHAR(100) NULL,
    ADD COLUMN first_utm_content         VARCHAR(100) NULL,
    ADD COLUMN first_touch_at            DATETIME(6)  NULL,
    ADD COLUMN referrer_host             VARCHAR(255) NULL,
    ADD COLUMN landing_path              VARCHAR(255) NULL,
    ADD COLUMN device_type               VARCHAR(20)  NULL,
    ADD COLUMN computed_freedom_date     DATE         NULL,
    ADD COLUMN computed_portfolio_target BIGINT       NULL,
    ADD COLUMN revealed_before_submit    BIT(1)       NOT NULL DEFAULT b'0';

CREATE INDEX idx_waitlist_signups_utm ON waitlist_signups (utm_source, utm_campaign, utm_content);
CREATE INDEX idx_waitlist_signups_created_at ON waitlist_signups (created_at);
