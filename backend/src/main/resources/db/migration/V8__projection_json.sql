-- V8: the calculator's own projection, as the page sent it with the signup.
--
-- projection_json holds the validated projection payload (ProjectionValidator ->
-- com.fredvested.web.email.Projection, serialised by Jackson): inputs, freedom
-- age and date, the series and contributions the chart draws, the stakes
-- number. It is stored so the personalised welcome email can be re-sent later
-- from exactly what the person submitted (never recomputed in Java), and so a
-- later view can read freedomAge and the inputs from one document instead of
-- the scattered calculator columns. NULL for a signup that sent no projection
-- (a pre-reveal submit, an old client) and for every row that predates V8.
--
-- Plain ALTER: the column exists in no environment, and prod is at zero rows,
-- so there is nothing to backfill and no information_schema guard is needed.
-- v_waitlist_funnel does not reference it and is unchanged.
ALTER TABLE waitlist_signups
    ADD COLUMN projection_json JSON NULL;
