package com.fredvested.web.email;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

// One rejecting and one accepting case per rule, each built by mutating a calculator-generated
// fixture, so the accepted shape is always the page's real output.
class ProjectionValidatorTest {

    static ProjectionRequest sample() { return Fixtures.request("sample"); }

    static String rejects(ProjectionRequest r) {
        InvalidProjectionException e = assertThrows(InvalidProjectionException.class, () -> ProjectionValidator.validate(r));
        return e.reason();
    }

    static String rejects(Consumer<ProjectionRequest> mutate) {
        ProjectionRequest r = sample();
        mutate.accept(r);
        return rejects(r);
    }

    static Projection accepts(Consumer<ProjectionRequest> mutate) {
        ProjectionRequest r = sample();
        mutate.accept(r);
        return ProjectionValidator.validate(r);
    }

    static ProjectionRequest.PointRequest point(double age, double value) {
        ProjectionRequest.PointRequest p = new ProjectionRequest.PointRequest();
        p.setAge(age);
        p.setValue(value);
        return p;
    }

    @Test
    void everyFixture_isAccepted_andNormalisedToItsOwnNumbers() {
        for (String name : List.of("sample", "existing-100k-optimistic", "short-horizon", "long-horizon",
                "target-under-1m", "target-over-5m", "stake-lever", "stake-hidden")) {
            Projection p = ProjectionValidator.validate(Fixtures.request(name));
            assertEquals(Fixtures.projection(name), p, name);
        }
        Projection s = ProjectionValidator.validate(sample());
        assertEquals(30, s.currentAge());
        assertEquals(53, s.roundedFreedomAge());
        assertEquals(2_250_000L, s.portfolioTarget());
        assertEquals(563_792L, s.totalContributed());
        assertEquals(Projection.Scenario.MODERATE, s.scenario());
        assertEquals(Projection.StakeSource.PLAN, s.stakeSource());
        assertEquals(25, s.series().size());
    }

    @Test
    void nullRequest_isRejected() {
        assertEquals("missing", rejects((ProjectionRequest) null));
    }

    @Test
    void everyNumberMustBePresent() {
        assertEquals("currentAge_missing", rejects(r -> r.setCurrentAge(null)));
        assertEquals("freedomAge_missing", rejects(r -> r.setFreedomAge(null)));
        assertEquals("freedomMonth_missing", rejects(r -> r.setFreedomMonth(null)));
        assertEquals("freedomYear_missing", rejects(r -> r.setFreedomYear(null)));
        assertEquals("monthlyInvest_missing", rejects(r -> r.setMonthlyInvest(null)));
        assertEquals("targetIncome_missing", rejects(r -> r.setTargetIncome(null)));
        assertEquals("existingBalance_missing", rejects(r -> r.setExistingBalance(null)));
        assertEquals("returnRate_missing", rejects(r -> r.setReturnRate(null)));
        assertEquals("stakeYears_missing", rejects(r -> r.setStakeYears(null)));
        assertEquals("totalContributed_missing", rejects(r -> r.setTotalContributed(null)));
        assertEquals("portfolioTarget_missing", rejects(r -> r.setPortfolioTarget(null)));
        assertEquals("scenario_missing", rejects(r -> r.setScenario(null)));
        assertEquals("series_missing", rejects(r -> r.setSeries(null)));
        assertEquals("contributions_missing", rejects(r -> r.setContributions(null)));
        assertEquals("series_age_missing", rejects(r -> r.getSeries().get(3).setAge(null)));
        assertEquals("contributions_value_missing", rejects(r -> r.getContributions().get(3).setValue(null)));
        // stakeSource alone is optional
        Projection p = accepts(r -> { r.setStakeSource(null); r.setStakeYears(12); });
        assertNull(p.stakeSource());
    }

    @Test
    void everyNumberMustBeFinite_andNonNegative() {
        assertEquals("monthlyInvest_not_finite", rejects(r -> r.setMonthlyInvest(Double.NaN)));
        assertEquals("portfolioTarget_not_finite", rejects(r -> r.setPortfolioTarget(Double.POSITIVE_INFINITY)));
        assertEquals("existingBalance_negative", rejects(r -> r.setExistingBalance(-1.0)));
        assertEquals("freedomMonth_negative", rejects(r -> r.setFreedomMonth(-4)));
        assertEquals("series_value_negative", rejects(r -> r.getSeries().get(0).setValue(-5.0)));
        accepts(r -> r.setExistingBalance(0.0));
    }

    @Test
    void currentAge_mustBeWhole_andAgesWithin18To80() {
        assertEquals("current_age_not_whole", rejects(r -> r.setCurrentAge(30.5)));
        assertEquals("current_age_out_of_range", rejects(r -> { r.setCurrentAge(17.0); r.getSeries().get(0).setAge(17.0); r.getContributions().get(0).setAge(17.0); }));
        assertEquals("current_age_out_of_range", rejects(r -> r.setCurrentAge(81.0)));
        assertEquals("freedom_age_out_of_range", rejects(r -> r.setFreedomAge(80.5)));
        assertEquals("freedom_age_out_of_range", rejects(r -> r.setFreedomAge(17.0)));
        assertEquals(30, accepts(r -> r.setCurrentAge(30.0)).currentAge());
        // A fixture at the long end of the range (18 -> 64.x) passes.
        assertEquals(18, ProjectionValidator.validate(Fixtures.request("long-horizon")).currentAge());
    }

    @Test
    void freedomAge_mustNotPrecedeCurrentAge() {
        assertEquals("freedom_age_before_current_age", rejects(r -> {
            r.setCurrentAge(60.0);
            r.getSeries().get(0).setAge(60.0);
            r.getContributions().get(0).setAge(60.0);
        }));
        // freedom == current passes the age rule itself (the calculator check then refuses it,
        // since a target above the balance is never reached in zero months).
        ProjectionRequest equal = sample();
        equal.setFreedomAge(30.0);
        equal.setStakeYears(35);
        equal.setSeries(new ArrayList<>(List.of(point(30, 0), point(30.0000001, 0))));
        equal.setContributions(new ArrayList<>(List.of(point(30, 0), point(30.0000001, 0))));
        equal.setPortfolioTarget(0.0);
        equal.setTotalContributed(0.0);
        assertEquals("portfolio_target_not_4pct_rule", rejects(equal));
    }

    @Test
    void freedomMonth_1To12_andFreedomYear_2000To2200() {
        assertEquals("freedom_month_out_of_range", rejects(r -> r.setFreedomMonth(0)));
        assertEquals("freedom_month_out_of_range", rejects(r -> r.setFreedomMonth(13)));
        assertEquals(12, accepts(r -> r.setFreedomMonth(12)).freedomMonth());
        assertEquals("freedom_year_out_of_range", rejects(r -> r.setFreedomYear(1999)));
        assertEquals("freedom_year_out_of_range", rejects(r -> r.setFreedomYear(2201)));
        assertEquals(2200, accepts(r -> r.setFreedomYear(2200)).freedomYear());
    }

    @Test
    void returnRate_mustBe8_10_or_12_andMatchTheScenario() {
        assertEquals("return_rate_not_allowed", rejects(r -> r.setReturnRate(9)));
        assertEquals("scenario_unknown", rejects(r -> r.setScenario("moderate")));
        assertEquals("scenario_unknown", rejects(r -> r.setScenario("Aggressive")));
        assertEquals("scenario_rate_mismatch", rejects(r -> r.setScenario("OPTIMISTIC")));
        assertEquals("scenario_rate_mismatch", rejects(r -> r.setReturnRate(8)));
        // The other two scenarios, as the calculator produced them (a relabelled rate fails the age check).
        assertEquals(Projection.Scenario.OPTIMISTIC, ProjectionValidator.validate(Fixtures.request("existing-100k-optimistic")).scenario());
        assertEquals(Projection.Scenario.CONSERVATIVE, ProjectionValidator.validate(Fixtures.request("long-horizon")).scenario());
    }

    @Test
    void series_2To70Points_sameLength_sameAges() {
        assertEquals("series_length", rejects(r -> r.setSeries(new ArrayList<>(List.of(point(30, 0))))));
        assertEquals("contributions_length", rejects(r -> {
            List<ProjectionRequest.PointRequest> many = new ArrayList<>();
            for (int i = 0; i < 71; i++) many.add(point(30 + i * 0.3, i));
            r.setContributions(many);
        }));
        assertEquals("series_length_mismatch", rejects(r -> r.getContributions().remove(5)));
        assertEquals("series_ages_mismatch", rejects(r -> r.getContributions().get(5).setAge(35.5)));
        // The short fixture (4 points) and the long one (47) are both within bounds.
        assertEquals(4, ProjectionValidator.validate(Fixtures.request("short-horizon")).series().size());
        assertEquals(47, ProjectionValidator.validate(Fixtures.request("long-horizon")).series().size());
    }

    @Test
    void series_agesStrictlyIncreasing_firstIsCurrentAge_lastIsFreedomAge() {
        assertEquals("series_ages_not_increasing", rejects(r -> r.getSeries().get(4).setAge(33.0)));
        assertEquals("contributions_ages_not_increasing", rejects(r -> r.getContributions().get(5).setAge(34.0)));
        assertEquals("series_first_age", rejects(r -> {
            r.getSeries().remove(0);
            r.getContributions().remove(0);
        }));
        assertEquals("series_last_age", rejects(r -> r.setFreedomAge(54.0)));
        assertEquals("series_last_age", rejects(r -> {
            r.getSeries().get(24).setAge(53.4913);
            r.getContributions().get(24).setAge(53.4913);
        }));
        accepts(r -> {
            r.getSeries().get(24).setAge(53.491330801497085 + 5e-7);
            r.getContributions().get(24).setAge(53.491330801497085 + 5e-7);
        });
    }

    @Test
    void series_valuesNonDecreasing() {
        assertEquals("series_values_decreasing", rejects(r -> r.getSeries().get(10).setValue(1.0)));
        assertEquals("contributions_values_decreasing", rejects(r -> r.getContributions().get(10).setValue(1.0)));
        // A flat step is allowed (non-decreasing, not strictly increasing).
        accepts(r -> { r.getSeries().get(1).setValue(0.0); r.getContributions().get(1).setValue(0.0); });
    }

    @Test
    void portfolioTarget_andTotalContributed_within1PercentOfTheirLastPoints() {
        assertEquals("portfolio_target_mismatch", rejects(r -> r.setPortfolioTarget(2_250_000 * 1.05)));
        assertEquals("portfolio_target_mismatch", rejects(r -> r.setPortfolioTarget(2_250_000 * 0.98)));
        // Inside 1% of the last point but not the 4% rule's own figure: the calculator check refuses it.
        assertEquals("portfolio_target_not_4pct_rule", rejects(r -> r.setPortfolioTarget(2_270_000.0)));
        assertEquals(2_250_001L, accepts(r -> r.setPortfolioTarget(2_250_001.0)).portfolioTarget());
        assertEquals("total_contributed_mismatch", rejects(r -> r.setTotalContributed(600_000.0)));
        assertEquals(560_000L, accepts(r -> r.setTotalContributed(560_000.0)).totalContributed());
    }

    @Test
    void everyDollarFigure_isCappedAtMaxValue() {
        for (double huge : new double[] { 1e18, 1e300, 100_000_001.0 }) {
            assertEquals("portfolioTarget_out_of_range", rejects(r -> r.setPortfolioTarget(huge)), String.valueOf(huge));
            assertEquals("totalContributed_out_of_range", rejects(r -> r.setTotalContributed(huge)), String.valueOf(huge));
            assertEquals("series_value_out_of_range", rejects(r -> r.getSeries().get(24).setValue(huge)), String.valueOf(huge));
            assertEquals("contributions_value_out_of_range", rejects(r -> r.getContributions().get(24).setValue(huge)), String.valueOf(huge));
        }
        // The ceiling itself passes the bound (and then fails the consistency checks like any other wrong figure).
        assertEquals("portfolio_target_mismatch", rejects(r -> r.setPortfolioTarget(100_000_000.0)));
    }

    @Test
    void theNumbersMustAgreeWithTheCalculator_andWithEachOther() {
        // The target is the 4% rule for the submitted income (FreedomCalculator, the server's twin of compute()).
        assertEquals("portfolio_target_not_4pct_rule", rejects(r -> r.setTargetIncome(8_000.0)));
        // The age is the calculator's for these inputs (a one-year rounding gap is allowed): at 12% the sample is 51, not 53.
        assertEquals("freedom_age_mismatch", rejects(r -> { r.setReturnRate(12); r.setScenario("OPTIMISTIC"); }));
        // Nothing invested monthly never reaches the target.
        assertEquals("projection_unreachable", rejects(r -> {
            r.setMonthlyInvest(0.0);
            r.setTotalContributed(0.0);
            for (ProjectionRequest.PointRequest pt : r.getContributions()) pt.setValue(0.0);
        }));
        // Both lines start at the money already invested.
        assertEquals("series_start_mismatch", rejects(r -> r.getSeries().get(0).setValue(5_000.0)));
        assertEquals("series_start_mismatch", rejects(r -> { r.setExistingBalance(5_000.0); r.getSeries().get(0).setValue(5_000.0); }));
        // What was put in can never exceed the balance.
        assertEquals("contributions_above_balance", rejects(r -> r.getContributions().get(1).setValue(30_000.0)));
        // The total put in is the balance plus the monthly amount over the horizon.
        assertEquals("total_contributed_mismatch", rejects(r -> r.setMonthlyInvest(2_100.0)));
        // The $100k at 12% fixture satisfies all of it, balance included.
        assertEquals(100_000L, ProjectionValidator.validate(Fixtures.request("existing-100k-optimistic")).existingBalance());
    }

    @Test
    void stakeYears_followsTheSource() {
        // plan: the page's own number, at least 2 and at most the years to 65 from the rounded freedom age (12 for the sample)
        assertEquals(12, ProjectionValidator.validate(sample()).stakeYears());
        assertEquals(2, accepts(r -> r.setStakeYears(2)).stakeYears());
        assertEquals("stake_years_out_of_range", rejects(r -> r.setStakeYears(13)));
        assertEquals("stake_years_out_of_range", rejects(r -> r.setStakeYears(1)));
        assertEquals("stake_years_out_of_range", rejects(r -> r.setStakeYears(100)));
        // lever: at most the years to 65 from the current age (38 -> 27)
        assertEquals(3, ProjectionValidator.validate(Fixtures.request("stake-lever")).stakeYears());
        ProjectionRequest lever = Fixtures.request("stake-lever");
        lever.setStakeYears(27);
        assertEquals(27, ProjectionValidator.validate(lever).stakeYears());
        lever.setStakeYears(28);
        assertEquals("stake_years_out_of_range", rejects(lever));
        lever.setStakeYears(1);
        assertEquals("stake_years_out_of_range", rejects(lever));
        // hidden (or absent): must equal max(0, 65 - round(freedomAge))
        assertEquals(0, ProjectionValidator.validate(Fixtures.request("stake-hidden")).stakeYears());
        assertEquals("stake_years_mismatch", rejects(r -> { r.setStakeSource("hidden"); r.setStakeYears(11); }));
        assertEquals(12, accepts(r -> { r.setStakeSource("hidden"); r.setStakeYears(12); }).stakeYears());
        assertEquals("stake_years_mismatch", rejects(r -> { r.setStakeSource(null); r.setStakeYears(13); }));
        ProjectionRequest hiddenPast65 = Fixtures.request("stake-hidden");
        hiddenPast65.setStakeYears(1);
        assertEquals("stake_years_mismatch", rejects(hiddenPast65));
        // any other source is invalid
        assertEquals("stake_source_unknown", rejects(r -> r.setStakeSource("PLAN")));
        assertEquals("stake_source_unknown", rejects(r -> r.setStakeSource("magic")));
    }

    @Test
    void moneyBounds_afterRounding() {
        assertEquals("monthlyInvest_out_of_range", rejects(r -> r.setMonthlyInvest(10_000_000.6)));
        assertEquals(2_000L, accepts(r -> r.setMonthlyInvest(2_000.4)).monthlyInvest());
        assertEquals("targetIncome_out_of_range", rejects(r -> r.setTargetIncome(10_000_001.0)));
        assertEquals(7_500L, accepts(r -> r.setTargetIncome(7_499.6)).targetIncome());
        assertEquals("existingBalance_out_of_range", rejects(r -> r.setExistingBalance(100_000_001.0)));
        assertEquals(0L, accepts(r -> r.setExistingBalance(0.4)).existingBalance());
        // $100k at 12%: the series starts at the balance, and the normalised record keeps it.
        Projection existing = ProjectionValidator.validate(Fixtures.request("existing-100k-optimistic"));
        assertEquals(100_000L, existing.existingBalance());
        assertEquals(100_000.0, existing.series().get(0).value());
        assertEquals(12, existing.returnRate());
    }

    @Test
    void reasonCodes_neverCarryAValue() {
        for (String reason : List.of(
                rejects(r -> r.setMonthlyInvest(123_456_789.0)),
                rejects(r -> r.setScenario("HACKED <script>")),
                rejects(r -> r.setStakeSource("<img src=x>")),
                rejects(r -> r.setFreedomYear(9999)))) {
            assertFalse(reason.contains("123") || reason.contains("<") || reason.contains("9999"), reason);
            assertTrue(reason.matches("[a-zA-Z_]+"), reason);
        }
    }
}
