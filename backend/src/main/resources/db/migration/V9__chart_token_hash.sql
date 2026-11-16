-- V9: the welcome email's chart moves from an inline (cid) attachment to a URL on this
-- API, /api/email/chart/<token>.png, because some mail apps (the Zoho Mail iOS app,
-- 2026-11-15) never resolve cid images while every client loads an image URL.
--
-- chart_token_hash is the SHA-256 hex of that URL's token, minted by the outbox
-- publisher at send time exactly like the unsubscribe token: the raw value exists only
-- in the email, and the endpoint looks the row up by the hash and renders the chart from
-- projection_json (V8). UNIQUE so the lookup is indexed and a hash can name one row only.
-- NULL for every row without a chart email (no projection, flag off, or before V9).
--
-- Plain ALTER like V8: the column exists in no environment and prod is at zero rows.
ALTER TABLE waitlist_signups
    ADD COLUMN chart_token_hash VARCHAR(64) NULL,
    ADD UNIQUE KEY uk_waitlist_chart_token_hash (chart_token_hash);
