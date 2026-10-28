package com.fredvested.web.email;

import com.fredvested.web.util.FreedomCalculator;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks the page's projection before it is stored and later rendered into the
 * welcome email. The server never recomputes the projection (no projection math
 * in Java): it only checks that what was sent is internally consistent, within
 * the calculator's own bounds, and shaped like projection.js's output, so a
 * tampered payload cannot put arbitrary numbers or text into an email we sign.
 * The cross-field checks reuse FreedomCalculator, the server's existing twin of
 * the page's compute() (already used for the drift warning in WaitlistController):
 * the 4% rule target and the freedom age must agree with it, the series must
 * start at the submitted balance, contributions can never exceed the balance,
 * the total put in must be the balance plus the monthly amount over the horizon,
 * and every dollar figure is capped at MAX_VALUE (the page's largest target is
 * $9M and its largest balance $10M). Nothing is recomputed for rendering.
 * Every rejection is an InvalidProjectionException whose message is a reason
 * code only, never a value.
 */
public final class ProjectionValidator {

    private ProjectionValidator() {}

    static final int MIN_AGE = 18;
    static final int MAX_AGE = 80;
    static final int MIN_POINTS = 2;
    static final int MAX_POINTS = 70;
    static final int USUAL_RETIREMENT_AGE = 65;
    static final long MAX_MONTHLY = 10_000_000L;
    static final long MAX_EXISTING = 100_000_000L;
    /** Ceiling on every balance, target and total: nothing the page can produce comes near it. */
    static final long MAX_VALUE = 100_000_000L;
    static final int STAKES_MIN_YEARS = 2;
    private static final ZoneId EASTERN = ZoneId.of("America/New_York");
    static final double AGE_EPSILON = 1e-6;
    static final double MONEY_TOLERANCE = 0.01;

    public static Projection validate(ProjectionRequest r) {
        if (r == null) throw new InvalidProjectionException("missing");

        double currentAge = number("currentAge", r.getCurrentAge());
        double freedomAge = number("freedomAge", r.getFreedomAge());
        int freedomMonth = whole("freedomMonth", r.getFreedomMonth());
        int freedomYear = whole("freedomYear", r.getFreedomYear());
        double monthlyInvest = number("monthlyInvest", r.getMonthlyInvest());
        double targetIncome = number("targetIncome", r.getTargetIncome());
        double existingBalance = number("existingBalance", r.getExistingBalance());
        int returnRate = whole("returnRate", r.getReturnRate());
        int stakeYears = whole("stakeYears", r.getStakeYears());
        double totalContributed = number("totalContributed", r.getTotalContributed());
        double portfolioTarget = number("portfolioTarget", r.getPortfolioTarget());
        if (r.getScenario() == null) throw new InvalidProjectionException("scenario_missing");
        if (r.getSeries() == null) throw new InvalidProjectionException("series_missing");
        if (r.getContributions() == null) throw new InvalidProjectionException("contributions_missing");

        if (currentAge != Math.rint(currentAge)) throw new InvalidProjectionException("current_age_not_whole");
        if (currentAge < MIN_AGE || currentAge > MAX_AGE) throw new InvalidProjectionException("current_age_out_of_range");
        if (freedomAge < MIN_AGE || freedomAge > MAX_AGE) throw new InvalidProjectionException("freedom_age_out_of_range");
        if (freedomAge < currentAge) throw new InvalidProjectionException("freedom_age_before_current_age");
        if (freedomMonth < 1 || freedomMonth > 12) throw new InvalidProjectionException("freedom_month_out_of_range");
        if (freedomYear < 2000 || freedomYear > 2200) throw new InvalidProjectionException("freedom_year_out_of_range");

        Projection.Scenario scenario = scenarioFor(returnRate);
        if (scenario == null) throw new InvalidProjectionException("return_rate_not_allowed");
        Projection.Scenario sent;
        try {
            sent = Projection.Scenario.valueOf(r.getScenario());
        } catch (IllegalArgumentException e) {
            throw new InvalidProjectionException("scenario_unknown");
        }
        if (sent != scenario) throw new InvalidProjectionException("scenario_rate_mismatch");

        List<Projection.Point> series = points("series", r.getSeries());
        List<Projection.Point> contributions = points("contributions", r.getContributions());
        if (series.size() != contributions.size()) throw new InvalidProjectionException("series_length_mismatch");
        for (int i = 0; i < series.size(); i++) {
            if (Math.abs(series.get(i).age() - contributions.get(i).age()) > AGE_EPSILON) {
                throw new InvalidProjectionException("series_ages_mismatch");
            }
        }
        if (Math.abs(series.get(0).age() - currentAge) > AGE_EPSILON) throw new InvalidProjectionException("series_first_age");
        if (Math.abs(series.get(series.size() - 1).age() - freedomAge) > AGE_EPSILON) throw new InvalidProjectionException("series_last_age");
        for (int i = 0; i < series.size(); i++) {
            if (contributions.get(i).value() > series.get(i).value() * (1 + MONEY_TOLERANCE) + 1) {
                throw new InvalidProjectionException("contributions_above_balance");
            }
        }

        long monthly = money("monthlyInvest", monthlyInvest, MAX_MONTHLY);
        long income = money("targetIncome", targetIncome, MAX_MONTHLY);
        long existing = money("existingBalance", existingBalance, MAX_EXISTING);
        long target = money("portfolioTarget", portfolioTarget, MAX_VALUE);
        long contributed = money("totalContributed", totalContributed, MAX_VALUE);

        if (!within(target, series.get(series.size() - 1).value())) throw new InvalidProjectionException("portfolio_target_mismatch");
        if (!within(contributed, contributions.get(contributions.size() - 1).value())) throw new InvalidProjectionException("total_contributed_mismatch");
        // Both lines start at the money already invested, and the total put in is that balance plus
        // the monthly amount over the horizon (the page's own contributed(months) arithmetic).
        if (!within(series.get(0).value(), existing) || !within(contributions.get(0).value(), existing)) {
            throw new InvalidProjectionException("series_start_mismatch");
        }
        if (!within(contributed, existing + monthly * 12.0 * (freedomAge - currentAge))) throw new InvalidProjectionException("total_contributed_mismatch");
        // The target and the age must be the ones the calculator gives for these inputs
        // (FreedomCalculator is the server's twin of compute(); a one-year rounding gap is allowed).
        FreedomCalculator.Result calc = FreedomCalculator.compute((int) currentAge, (int) monthly, (int) income, returnRate, existing, LocalDate.now(EASTERN));
        if (!calc.reachable()) throw new InvalidProjectionException("projection_unreachable");
        if (Math.abs(calc.portfolioTarget() - target) > 1) throw new InvalidProjectionException("portfolio_target_not_4pct_rule");
        int roundedFreedomAge = (int) Math.round(freedomAge);
        if (Math.abs(calc.freedomAge() - roundedFreedomAge) > 1) throw new InvalidProjectionException("freedom_age_mismatch");

        // The stakes ladder's own bounds (projection.js stakes()): the plan rung is the do-nothing age
        // (65 at most) minus the plan's age, the lever rung that age minus the lever age (never under
        // currentAge), both shown only from STAKES_MIN_YEARS; hidden or absent is the plain years-to-65 figure.
        Projection.StakeSource stakeSource = stakeSource(r.getStakeSource());
        if (stakeSource == null || stakeSource == Projection.StakeSource.HIDDEN) {
            if (stakeYears != Math.max(0, USUAL_RETIREMENT_AGE - roundedFreedomAge)) throw new InvalidProjectionException("stake_years_mismatch");
        } else {
            int ceiling = stakeSource == Projection.StakeSource.PLAN
                    ? USUAL_RETIREMENT_AGE - roundedFreedomAge
                    : USUAL_RETIREMENT_AGE - (int) currentAge;
            if (stakeYears < STAKES_MIN_YEARS || stakeYears > ceiling) throw new InvalidProjectionException("stake_years_out_of_range");
        }

        return new Projection(
                (int) currentAge, freedomAge, freedomMonth, freedomYear,
                monthly, income, existing, returnRate, scenario,
                stakeYears, stakeSource,
                contributed, target,
                series, contributions);
    }

    private static double number(String field, Double v) {
        if (v == null) throw new InvalidProjectionException(field + "_missing");
        if (v.isNaN() || v.isInfinite()) throw new InvalidProjectionException(field + "_not_finite");
        if (v < 0) throw new InvalidProjectionException(field + "_negative");
        return v;
    }

    private static int whole(String field, Integer v) {
        if (v == null) throw new InvalidProjectionException(field + "_missing");
        if (v < 0) throw new InvalidProjectionException(field + "_negative");
        return v;
    }

    private static long money(String field, double v, long max) {
        long rounded = Math.round(v);
        if (rounded < 0 || rounded > max) throw new InvalidProjectionException(field + "_out_of_range");
        return rounded;
    }

    private static boolean within(double claimed, double actual) {
        return Math.abs(claimed - actual) <= MONEY_TOLERANCE * Math.max(Math.abs(actual), 1.0);
    }

    private static Projection.Scenario scenarioFor(int rate) {
        return switch (rate) {
            case 8 -> Projection.Scenario.CONSERVATIVE;
            case 10 -> Projection.Scenario.MODERATE;
            case 12 -> Projection.Scenario.OPTIMISTIC;
            default -> null;
        };
    }

    private static Projection.StakeSource stakeSource(String s) {
        if (s == null) return null;
        try {
            return Projection.StakeSource.fromJson(s);
        } catch (IllegalArgumentException e) {
            throw new InvalidProjectionException("stake_source_unknown");
        }
    }

    private static List<Projection.Point> points(String field, List<ProjectionRequest.PointRequest> raw) {
        if (raw.size() < MIN_POINTS || raw.size() > MAX_POINTS) throw new InvalidProjectionException(field + "_length");
        List<Projection.Point> out = new ArrayList<>(raw.size());
        double lastAge = Double.NEGATIVE_INFINITY;
        double lastValue = Double.NEGATIVE_INFINITY;
        for (ProjectionRequest.PointRequest p : raw) {
            if (p == null) throw new InvalidProjectionException(field + "_point_missing");
            double age = number(field + "_age", p.getAge());
            double value = number(field + "_value", p.getValue());
            if (value > MAX_VALUE) throw new InvalidProjectionException(field + "_value_out_of_range");
            if (age <= lastAge) throw new InvalidProjectionException(field + "_ages_not_increasing");
            if (value < lastValue) throw new InvalidProjectionException(field + "_values_decreasing");
            lastAge = age;
            lastValue = value;
            out.add(new Projection.Point(age, value));
        }
        return out;
    }
}
