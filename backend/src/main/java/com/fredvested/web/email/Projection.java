package com.fredvested.web.email;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.List;

/**
 * The calculator's own projection, as the landing page sent it with the signup
 * (ProjectionValidator turns the raw request into this) and as it is stored on
 * the waitlist row (projection_json, V8). Every number here was computed by
 * frontend/assets/projection.js at reveal time; the server renders what it was
 * given and never recomputes any of it (no projection math in Java).
 *
 * Ages are in years; freedomAge may be fractional (the page rounds it for display
 * with Math.round, mirrored by roundedFreedomAge()). Money is whole dollars.
 * series and contributions carry one point per whole year from currentAge to
 * freedomAge plus the final fractional point: series is the projected balance,
 * contributions the cumulative amount put in (the starting balance plus the
 * monthly amounts), so the two lines of the chart start at the same point.
 */
public record Projection(
        int currentAge,
        double freedomAge,
        int freedomMonth,
        int freedomYear,
        long monthlyInvest,
        long targetIncome,
        long existingBalance,
        int returnRate,
        Scenario scenario,
        int stakeYears,
        StakeSource stakeSource,
        long totalContributed,
        long portfolioTarget,
        List<Point> series,
        List<Point> contributions) {

    public enum Scenario {
        CONSERVATIVE("Conservative"), MODERATE("Moderate"), OPTIMISTIC("Optimistic");

        private final String label;

        Scenario(String label) { this.label = label; }

        /** The word the email prints, e.g. "Moderate"; never free text. */
        public String label() { return label; }
    }

    /** Which rung of the page's stakes ladder produced stakeYears ("plan" | "lever" | "hidden"); null when the page sent none. */
    public enum StakeSource {
        PLAN, LEVER, HIDDEN;

        @JsonValue
        public String json() { return name().toLowerCase(); }

        @JsonCreator
        public static StakeSource fromJson(String value) {
            if (value == null) return null;
            for (StakeSource s : values()) if (s.json().equals(value)) return s;
            throw new IllegalArgumentException("unknown stakeSource");
        }
    }

    public record Point(double age, double value) {}

    /** The whole-year age the page shows (Math.round in projection.js's roundAge()). */
    public int roundedFreedomAge() {
        return (int) Math.round(freedomAge);
    }
}
