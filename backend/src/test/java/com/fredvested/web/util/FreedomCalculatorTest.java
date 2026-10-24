package com.fredvested.web.util;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

// Expected values were produced by running compute() from frontend/index.html
// in Node on the same inputs. If these ever disagree, the two implementations
// have drifted, which the controller also logs on every signup.
class FreedomCalculatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 1, 15);

    @Test
    void moderateCase_matchesThePage() {
        FreedomCalculator.Result r = FreedomCalculator.compute(30, 2000, 7500, 12, TODAY);
        assertEquals(51, r.freedomAge());
        assertEquals(LocalDate.of(2047, 1, 1), r.freedomDate()); // 252 months, first of month
        assertEquals(2_250_000L, r.portfolioTarget());
        assertTrue(r.reachable());
    }

    @Test
    void tenPercentScenario() {
        FreedomCalculator.Result r = FreedomCalculator.compute(30, 2000, 7500, 10, TODAY);
        assertEquals(53, r.freedomAge());
        assertEquals(LocalDate.of(2049, 7, 1), r.freedomDate()); // 282 months
        assertEquals(2_250_000L, r.portfolioTarget());
    }

    @Test
    void conservativeLongHorizon() {
        FreedomCalculator.Result r = FreedomCalculator.compute(25, 500, 5000, 8, TODAY);
        assertEquals(63, r.freedomAge());
        assertEquals(LocalDate.of(2064, 3, 1), r.freedomDate()); // 458 months
        assertEquals(1_500_000L, r.portfolioTarget());
    }

    @Test
    void smallTargetReachedQuickly() {
        FreedomCalculator.Result r = FreedomCalculator.compute(18, 10000, 1000, 12, TODAY);
        assertEquals(20, r.freedomAge());
        assertEquals(LocalDate.of(2028, 3, 1), r.freedomDate()); // 26 months
        assertEquals(300_000L, r.portfolioTarget());
    }

    @Test
    void reachableJustUnderTheAgeCap() {
        FreedomCalculator.Result r = FreedomCalculator.compute(60, 10000, 30000, 8, TODAY);
        assertEquals(84, r.freedomAge());
        assertEquals(LocalDate.of(2050, 6, 1), r.freedomDate()); // 293 months
        assertEquals(9_000_000L, r.portfolioTarget());
    }

    @Test
    void noInvestment_isUnreachable_targetStillComputed() {
        FreedomCalculator.Result r = FreedomCalculator.compute(30, 0, 7500, 10, TODAY);
        assertFalse(r.reachable());
        assertNull(r.freedomAge());
        assertNull(r.freedomDate());
        assertEquals(2_250_000L, r.portfolioTarget());
    }

    @Test
    void beyondTheHorizon_isUnreachable() {
        FreedomCalculator.Result r = FreedomCalculator.compute(55, 50, 30000, 8, TODAY);
        assertFalse(r.reachable());
        assertNull(r.freedomAge());
        assertEquals(9_000_000L, r.portfolioTarget());
    }

    // --- Existing invested amount (starting balance) ---
    // Expected values from the contract formula run in Node on the same inputs:
    // n = ln((target*r + invest) / (existing*r + invest)) / ln(1 + r), months.

    @Test
    void startingBalance_shortensTheHorizon() {
        // The attorney-reviewed reference case: 2,250,000 target, 1842/month (850 biweekly), 10%.
        FreedomCalculator.Result with = FreedomCalculator.compute(30, 1842, 7500, 10, 250_000, TODAY);
        assertEquals(47, with.freedomAge());
        assertEquals(LocalDate.of(2042, 9, 1), with.freedomDate()); // 199.7 months, about 16.6 years
        assertEquals(2_250_000L, with.portfolioTarget());
        assertFalse(with.atTarget());
        assertTrue(with.reachable());

        FreedomCalculator.Result without = FreedomCalculator.compute(30, 1842, 7500, 10, 0, TODAY);
        assertEquals(54, without.freedomAge());
        assertEquals(LocalDate.of(2050, 4, 1), without.freedomDate()); // 290.9 months, about 24.2 years
        assertEquals(2_250_000L, without.portfolioTarget());
        assertFalse(without.atTarget());
    }

    @Test
    void startingBalance_everyScenarioIsConsistent() {
        // 30 / 2000 / 7500 with 250,000 already invested: 8% -> 49 (231 months),
        // 10% -> 46 (196 months), 12% -> 44 (170 months); from zero: 57 / 53 / 51.
        int[] pcts = {8, 10, 12};
        int[] expected = {49, 46, 44};
        for (int i = 0; i < pcts.length; i++) {
            FreedomCalculator.Result with = FreedomCalculator.compute(30, 2000, 7500, pcts[i], 250_000, TODAY);
            FreedomCalculator.Result without = FreedomCalculator.compute(30, 2000, 7500, pcts[i], 0, TODAY);
            assertEquals(expected[i], with.freedomAge(), "scenario " + pcts[i]);
            assertTrue(with.freedomAge() < without.freedomAge(), "scenario " + pcts[i] + " must finish earlier with a balance");
            assertEquals(without.portfolioTarget(), with.portfolioTarget());
        }
    }

    @Test
    void noContributions_withABalanceBelowTarget_isUnreachable() {
        // A monthly amount is required even with a balance (Andrew, 2026-10-23); growth alone
        // would reach the target in 264.8 months, but the tool never shows that date.
        FreedomCalculator.Result r = FreedomCalculator.compute(30, 0, 7500, 10, 250_000, TODAY);
        assertFalse(r.reachable());
        assertNull(r.freedomAge());
        assertNull(r.freedomDate());
        assertFalse(r.atTarget());
        assertEquals(2_250_000L, r.portfolioTarget());
    }

    @Test
    void noContributions_withABalanceAtTarget_isStillAtTarget() {
        FreedomCalculator.Result r = FreedomCalculator.compute(30, 0, 1000, 10, 300_000, TODAY);
        assertTrue(r.atTarget());
        assertNull(r.freedomAge());
    }

    @Test
    void noContributionsAndNothingInvested_isUnreachable() {
        FreedomCalculator.Result r = FreedomCalculator.compute(30, 0, 7500, 10, 0, TODAY);
        assertFalse(r.reachable());
        assertFalse(r.atTarget());
        assertNull(r.freedomAge());
        assertNull(r.freedomDate());
        assertEquals(2_250_000L, r.portfolioTarget());
    }

    @Test
    void existingMeetsTarget_isAtTarget_withNoDateOrAge() {
        FreedomCalculator.Result r = FreedomCalculator.compute(30, 2000, 1000, 10, 300_000, TODAY);
        assertTrue(r.atTarget());
        assertFalse(r.reachable());
        assertNull(r.freedomAge());
        assertNull(r.freedomDate());
        assertEquals(300_000L, r.portfolioTarget());
    }

    @Test
    void existingJustUnderTarget_isNotAtTarget_andReachesItAtOnce() {
        FreedomCalculator.Result r = FreedomCalculator.compute(30, 2000, 1000, 10, 299_999, TODAY);
        assertFalse(r.atTarget());
        assertTrue(r.reachable());
        assertEquals(30, r.freedomAge()); // n is a fraction of a month
        assertEquals(LocalDate.of(2026, 1, 1), r.freedomDate());
        assertEquals(300_000L, r.portfolioTarget());
    }

    @Test
    void fiveArgOverload_equalsSixArgWithZero() {
        assertEquals(FreedomCalculator.compute(30, 2000, 7500, 10, 0, TODAY), FreedomCalculator.compute(30, 2000, 7500, 10, TODAY));
        assertEquals(FreedomCalculator.compute(30, 0, 7500, 10, 0, TODAY), FreedomCalculator.compute(30, 0, 7500, 10, TODAY));
        assertEquals(FreedomCalculator.compute(55, 50, 30000, 8, 0, TODAY), FreedomCalculator.compute(55, 50, 30000, 8, TODAY));
    }
}
