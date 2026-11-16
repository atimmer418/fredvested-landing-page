package com.fredvested.web.share;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The validation rule is implemented three times (here, frontend/assets/share.js, frontend/_worker.js)
// and pinned by the same vectors: docs/superpowers/plans/2026-11-14-share-freedom-date.md.
class ShareResultTest {

    static final Clock NOW = Clock.fixed(Instant.parse("2026-11-14T12:00:00Z"), ZoneOffset.UTC);

    @ParameterizedTest
    @CsvSource({
            "52-2048-07, true",
            "38-2041-09, true",
            "67-2063-07, true",
            "18-2026-12, true",
            "19-2026-11, true",
            "18-2026-10, false",
            "52-2026-12, true",
            "100-2099-01, false",
            "17-2030-01, false",
            "101-2090-01, false",
            "52-2048-13, false",
            "52-2048-7, false",
            "90-2030-01, false",
            "30-2060-01, false",
            "'', false",
            "abc, false",
            "52-2048-07x, false",
    })
    void theSharedVectors(String r, boolean valid) {
        assertEquals(valid, ShareResult.parseParam(r, NOW).isPresent(), "parseParam " + r);
        int dash = r.indexOf('-');
        if (dash > 0) {
            assertEquals(valid, ShareResult.parse(r.substring(0, dash), r.substring(dash + 1), NOW).isPresent(), "parse " + r);
        }
    }

    @Test
    void nullAndOddShapesAreRefused() {
        assertTrue(ShareResult.parseParam(null, NOW).isEmpty());
        assertTrue(ShareResult.parse(null, "2048-07", NOW).isEmpty());
        assertTrue(ShareResult.parse("52", null, NOW).isEmpty());
        assertTrue(ShareResult.parse("052", "2048-07", NOW).isPresent() == ShareResult.parseParam("052-2048-07", NOW).isPresent());
        assertTrue(ShareResult.parse("5", "2048-07", NOW).isEmpty());
        assertTrue(ShareResult.parse("52", "2048-07\n", NOW).isEmpty());
        assertTrue(ShareResult.parse(" 52", "2048-07", NOW).isEmpty());
        assertTrue(ShareResult.parse("５２", "2048-07", NOW).isEmpty(), "ASCII digits only");
        assertTrue(ShareResult.parse("52", "2048-07.png", NOW).isEmpty());
    }

    @Test
    void theClockIsReadInUtc() {
        // 2026-11-30T23:30 in New York is already December in UTC: November is in the past there.
        Clock late = Clock.fixed(Instant.parse("2026-12-01T04:30:00Z"), java.time.ZoneId.of("America/New_York"));
        assertTrue(ShareResult.parseParam("19-2026-11", late).isEmpty());
        assertTrue(ShareResult.parseParam("19-2026-12", late).isPresent());
    }

    @Test
    void derivedValues() {
        Optional<ShareResult> r = ShareResult.parseParam("52-2048-07", NOW);
        assertEquals(new ShareResult(52, 2048, 7), r.orElseThrow());
        assertEquals("July 2048", r.get().dateLabel());
        assertEquals("July", r.get().monthName());
        assertFalse(r.get().late());
        assertEquals(13, r.get().gapYears());

        ShareResult late = ShareResult.parseParam("67-2063-07", NOW).orElseThrow();
        assertEquals("July 2063", late.dateLabel());
        assertTrue(late.late());
        assertEquals(-2, late.gapYears());

        assertEquals("September 2041", ShareResult.parseParam("38-2041-09", NOW).orElseThrow().dateLabel());
        assertTrue(new ShareResult(65, 2060, 1).late());
        assertFalse(new ShareResult(64, 2060, 1).late());
    }

    @Test
    void copy() {
        assertEquals("13 years before the usual 65", ShareImageCopy.gapLine(new ShareResult(52, 2048, 7)));
        assertEquals("1 year before the usual 65", ShareImageCopy.gapLine(new ShareResult(64, 2060, 1)));
        assertEquals("Hope isn’t a plan. July 2063 is.", ShareImageCopy.gapLine(new ShareResult(67, 2063, 7)));
        assertEquals("Hope isn’t a plan. January 2060 is.", ShareImageCopy.gapLine(new ShareResult(65, 2060, 1)));
        assertEquals("I COULD BE FREE AT", ShareImageCopy.KICKER);
        assertEquals("On the FRED plan.", ShareImageCopy.ROUTE_LABEL);
    }
}
