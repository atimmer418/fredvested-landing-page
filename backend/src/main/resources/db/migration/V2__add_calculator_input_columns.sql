-- V2: the five calculator-input columns, added only where they are missing.
--
-- Why this is conditional instead of a plain ALTER TABLE ... ADD COLUMN:
-- V1__baseline.sql captured the entity as it stood in the working tree when
-- Flyway was introduced (11 columns). But the databases that already existed
-- had been built by hibernate.ddl-auto=update from whatever code was deployed
-- to them, and the committed entity at that point had only six columns (id,
-- email, freedom_age, status, ip_hash, created_at) -- these five lived
-- uncommitted from 2026-08 until 1f26268. baseline-on-migrate records V1 as
-- applied WITHOUT executing it, so such a database is "at V1" while lacking
-- these columns, and Hibernate validate refuses to start (Railway dev,
-- 2026-09-22). Dev was then booted once with ddl-auto=update, which created
-- the columns there. So the states this must handle:
--   Railway dev  : all five present, history at V1     -> no-op
--   local dev DB : all five present, no history yet    -> baseline, no-op
--   Railway prod : none present, no history yet        -> baseline, add five
--   fresh schema : V1 already created them             -> no-op
-- MySQL has no ADD COLUMN IF NOT EXISTS, so each column is added through a
-- prepared statement chosen by an information_schema lookup. Types match
-- V1__baseline.sql exactly, which is what Hibernate generates for the entity.

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE waitlist_signups ADD COLUMN current_age INT NULL',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'waitlist_signups' AND COLUMN_NAME = 'current_age');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE waitlist_signups ADD COLUMN invest_monthly INT NULL',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'waitlist_signups' AND COLUMN_NAME = 'invest_monthly');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE waitlist_signups ADD COLUMN retire_monthly INT NULL',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'waitlist_signups' AND COLUMN_NAME = 'retire_monthly');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE waitlist_signups ADD COLUMN interacted BIT(1) NULL',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'waitlist_signups' AND COLUMN_NAME = 'interacted');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE waitlist_signups ADD COLUMN return_assumption_pct INT NULL',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'waitlist_signups' AND COLUMN_NAME = 'return_assumption_pct');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
