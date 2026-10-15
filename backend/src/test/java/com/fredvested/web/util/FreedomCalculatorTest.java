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
}
