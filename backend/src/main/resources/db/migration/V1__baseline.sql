-- Baseline migration. Captures the waitlist_signups table exactly as
-- hibernate.ddl-auto=update produced it as of 2026-09-18 (verified by
-- creating the table from the live WaitlistEntry entity against an empty
-- MySQL schema and running SHOW CREATE TABLE against the result).
--
-- From here on, schema changes are numbered migrations only. Hibernate is
-- set to ddl-auto=validate: it checks entities against this schema on boot
-- and refuses to start on a mismatch, it never mutates the database.
CREATE TABLE waitlist_signups (
    id                     BIGINT NOT NULL AUTO_INCREMENT,
    email                  VARCHAR(255) NOT NULL,
    freedom_age            INT,
    current_age            INT,
    invest_monthly         INT,
    retire_monthly         INT,
    interacted             BIT(1),
    return_assumption_pct  INT,
    status                 ENUM('CLAIMED','DECLINED','INVITED','WAITLISTFOUNDER','WAITLISTNORMAL') NOT NULL,
    ip_hash                VARCHAR(255),
    created_at             DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_waitlist_signups_email UNIQUE (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
