package com.fredvested.web.util;

import java.time.LocalDate;

/**
 * Server-side twin of compute() in frontend/index.html. Both implementations
 * must agree; the controller compares the client's submitted numbers with
 * these and logs a warning on drift, which would mean the two have diverged.
 *
 * target       = monthly income * 12 / 4% withdrawal rate
 * monthly rate = annual return % / 100 / 12
 * months n     = ln((target * r + invest) / (existing * r + invest)) / ln(1 + r)
 *                (with existing = 0 this is the original ln(target * r / invest + 1) / ln(1 + r))
 * at target    when existing >= target: no date and no age, checked before anything else.
 * unreachable when invest <= 0 (a monthly amount is required even with a balance; Andrew,
 * 2026-10-23: the tool projects ongoing investing, so no growth-only date is ever shown),
 * the freedom age would exceed 100, or it would take more than 72 years. Money already
 * invested (existing) is the starting balance and compounds at the same selected rate as
 * the contributions.
 */
public final class FreedomCalculator {

    public static final double WITHDRAWAL_RATE = 0.04;
    public static final int MAX_AGE = 100;
    public static final int MAX_YEARS = 72;

    private FreedomCalculator() {}

    /**
     * freedomAge and freedomDate are null when the target is unreachable or already met
     * (atTarget true); portfolioTarget is always set.
     */
    public record Result(Integer freedomAge, LocalDate freedomDate, long portfolioTarget, boolean atTarget) {
        public boolean reachable() { return freedomAge != null; }
    }

    /** Nothing already invested: the projection starts from zero, as every client before the input did. */
    public static Result compute(int age, int investMonthly, int retireMonthly, int returnPct, LocalDate today) {
        return compute(age, investMonthly, retireMonthly, returnPct, 0, today);
    }

    public static Result compute(int age, int investMonthly, int retireMonthly, int returnPct, long existingInvested, LocalDate today) {
        double target = (retireMonthly * 12.0) / WITHDRAWAL_RATE;
        long portfolioTarget = Math.round(target);
        // Already there: the page shows the at-target state instead of a date. This comes
        // first: nothing is being projected, so the monthly-amount rule below does not apply.
        if (existingInvested >= target) return new Result(null, null, portfolioTarget, true);
        if (investMonthly <= 0) return new Result(null, null, portfolioTarget, false);

        double r = returnPct / 100.0 / 12.0;
        double n = Math.log((target * r + investMonthly) / (existingInvested * r + investMonthly)) / Math.log(1 + r);
        double years = n / 12.0;
        double ageAtFreedom = age + years;
        if (ageAtFreedom > MAX_AGE || years > MAX_YEARS) return new Result(null, null, portfolioTarget, false);

        // The page builds new Date(year, month + round(n), 1): first of the month, round(n) months out.
        LocalDate freedomDate = today.withDayOfMonth(1).plusMonths(Math.round(n));
        return new Result((int) Math.round(ageAtFreedom), freedomDate, portfolioTarget, false);
    }
}
