package com.fredvested.web.email;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmailFormatsTest {

    @Test
    void moneyPrintsWholeDollarsWithThousandsSeparators() {
        assertEquals("$2,000", EmailFormats.money(2000));
        assertEquals("$0", EmailFormats.money(0));
        assertEquals("$2,250,000", EmailFormats.money(2_250_000));
    }

    @Test
    void compactUsesThousandsBelowAMillion() {
        assertEquals("$950", EmailFormats.compact(950));
        assertEquals("$564k", EmailFormats.compact(563_792));
    }

    @Test
    void compactUsesUpToTwoDecimalsOfAMillionAndDropsTrailingZeros() {
        assertEquals("$2.25M", EmailFormats.compact(2_250_000));
        assertEquals("$1.2M", EmailFormats.compact(1_200_000));
        assertEquals("$2M", EmailFormats.compact(2_000_000));
    }

    @Test
    void compactDropsDecimalsFromTenMillion() {
        assertEquals("$12M", EmailFormats.compact(12_345_678));
    }

    @Test
    void axisLabelsFollowTheGridStep() {
        assertEquals("$0.5M", EmailFormats.axis(500_000, 500_000));
        assertEquals("$1M", EmailFormats.axis(1_000_000, 500_000));
        assertEquals("$1.5M", EmailFormats.axis(1_500_000, 500_000));
        assertEquals("$100k", EmailFormats.axis(100_000, 100_000));
        assertEquals("$2M", EmailFormats.axis(2_000_000, 1_000_000));
        assertEquals("$0", EmailFormats.axis(0, 500_000));
    }

    @Test
    void percentAppendsTheSign() {
        assertEquals("10%", EmailFormats.percent(10));
    }
}
