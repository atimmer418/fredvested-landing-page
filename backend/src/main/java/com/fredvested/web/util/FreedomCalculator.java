package com.fredvested.web.util;

import java.time.LocalDate;

/**
 * Server-side twin of compute() in frontend/index.html. Both implementations
 * must agree; the controller compares the client's submitted numbers with
 * these and logs a warning on drift, which would mean the two have diverged.
 *
 * target       = monthly income * 12 / 4% withdrawal rate
 * monthly rate = annual return % / 100 / 12
 * months n     = ln(target * r / invest + 1) / ln(1 + r)
 * unreachable when invest <= 0, the freedom age would exceed 100, or it would take more than 72 years.
 */
public final class FreedomCalculator {

    public static final double WITHDRAWAL_RATE = 0.04;
    public static final int MAX_AGE = 100;
    public static final int MAX_YEARS = 72;

    private FreedomCalculator() {}

    /** freedomAge and freedomDate are null when the target is unreachable; portfolioTarget is always set. */
    public record Result(Integer freedomAge, LocalDate freedomDate, long portfolioTarget) {
        public boolean reachable() { return freedomAge != null; }
    }

    public static Result compute(int age, int investMonthly, int retireMonthly, int returnPct, LocalDate today) {
        double target = (retireMonthly * 12.0) / WITHDRAWAL_RATE;
        long portfolioTarget = Math.round(target);
        if (investMonthly <= 0) return new Result(null, null, portfolioTarget);

        double r = returnPct / 100.0 / 12.0;
        double n = Math.log((target * r) / investMonthly + 1) / Math.log(1 + r);
        double years = n / 12.0;
        double ageAtFreedom = age + years;
        if (ageAtFreedom > MAX_AGE || years > MAX_YEARS) return new Result(null, null, portfolioTarget);

        // The page builds new Date(year, month + round(n), 1): first of the month, round(n) months out.
        LocalDate freedomDate = today.withDayOfMonth(1).plusMonths(Math.round(n));
        return new Result((int) Math.round(ageAtFreedom), freedomDate, portfolioTarget);
    }
}
