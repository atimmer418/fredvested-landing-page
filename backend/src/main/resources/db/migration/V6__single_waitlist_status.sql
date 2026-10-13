-- One waitlist, no tiers (founder decision, 2026-10-10).
--
-- WAITLISTFOUNDER and WAITLISTNORMAL are gone: every confirmed signup is simply
-- WAITLISTED, and Founder Access invites later go out in signup order while the
-- 300 founding spots last (INVITED / CLAIMED / DECLINED stay as the invite
-- workflow). The 300 is no longer assigned or counted at signup.
--
-- The column is a MySQL ENUM, so this is done in three statements rather than one
-- MODIFY: the old build may still be running while Flyway applies this file, and it
-- keeps writing WAITLISTNORMAL until it is replaced. Widening the enum first means
-- those writes never hit an invalid value mid-migration; the UPDATE then folds the
-- old values into WAITLISTED; only then is the enum narrowed to the four live values.
-- Any WAITLISTFOUNDER/WAITLISTNORMAL row written between (b) and (c) would make (c)
-- fail loudly (strict mode) instead of being silently truncated. MySQL DDL is not
-- transactional, so (a) and (b) stay applied and Flyway records V6 as failed; the
-- next start stops with "Detected failed migration to version 6". Recovery: rerun
-- (b), then `flyway repair` (or delete the failed row from flyway_schema_history)
-- and redeploy. Never bypass the migration.
-- Widening only protects the old build's WRITES: its enum has no WAITLISTED, so
-- from (b) onward it throws on any row it loads (duplicate submit, confirm click,
-- the outbox publisher's findById). On Railway that is a few seconds of 500s and
-- outbox retries during the overlap; a rollback to the old build is NOT safe
-- without restoring the database backup taken before the deploy.
--
-- v_waitlist_funnel and v_email_suppressions (V4/V5) reference only INVITED and
-- CLAIMED, so neither view needs recreating.

-- (a) widen: old and new values both valid
ALTER TABLE waitlist_signups
    MODIFY status ENUM('CLAIMED','DECLINED','INVITED','WAITLISTED','WAITLISTFOUNDER','WAITLISTNORMAL') NOT NULL;

-- (b) fold the two pre-invitation tiers into the single waitlist state
UPDATE waitlist_signups
   SET status = 'WAITLISTED'
 WHERE status IN ('WAITLISTFOUNDER','WAITLISTNORMAL');

-- (c) narrow: the retired values cease to exist
ALTER TABLE waitlist_signups
    MODIFY status ENUM('CLAIMED','DECLINED','INVITED','WAITLISTED') NOT NULL;
