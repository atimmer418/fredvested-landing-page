package com.fredvested.web.util;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class AttributionSanitizerTest {

    @Test
    void token_lowercasesAndStripsEverythingOutsideTheAllowedSet() {
        assertEquals("tiktok", AttributionSanitizer.token("TikTok"));
        assertEquals("scriptalert1scriptbio", AttributionSanitizer.token("<script>alert(1)</script>bio"));
        assertEquals("nursedroptablewaitlist_signups--", AttributionSanitizer.token("nurse'; DROP TABLE waitlist_signups;--"));
        assertEquals("20260921-night-shift-roi", AttributionSanitizer.token("20260921-night-shift-roi"));
        assertEquals("a.b_c-d", AttributionSanitizer.token("a.b_c-d"));
        assertEquals("ncd", AttributionSanitizer.token("✓ünïcödé"));
    }

    @Test
    void token_truncatesTo100_andDropsEmpty() {
        assertEquals(100, AttributionSanitizer.token("x".repeat(5000)).length());
        assertNull(AttributionSanitizer.token(""));
        assertNull(AttributionSanitizer.token("   "));
        assertNull(AttributionSanitizer.token("!!!"));
        assertNull(AttributionSanitizer.token(null));
    }

    @Test
    void host_keepsOnlyHostnameCharacters() {
        assertEquals("www.tiktok.com", AttributionSanitizer.host("www.TikTok.com"));
        assertEquals("httpsevil.compathx1", AttributionSanitizer.host("https://evil.com/path?x=1"));
        assertNull(AttributionSanitizer.host("://"));
        assertEquals(255, AttributionSanitizer.host("a".repeat(300)).length());
    }

    @Test
    void path_mustStartWithASlash() {
        assertEquals("/", AttributionSanitizer.path("/"));
        assertEquals("/about", AttributionSanitizer.path("/About"));
        assertNull(AttributionSanitizer.path("javascript:alert(1)"));
        assertNull(AttributionSanitizer.path("about"));
        assertEquals(255, AttributionSanitizer.path("/" + "a".repeat(300)).length());
    }

    @Test
    void deviceType_isAWhitelist() {
        assertEquals("mobile", AttributionSanitizer.deviceType("Mobile"));
        assertEquals("tablet", AttributionSanitizer.deviceType("tablet"));
        assertEquals("desktop", AttributionSanitizer.deviceType("desktop "));
        assertNull(AttributionSanitizer.deviceType("toaster"));
        assertNull(AttributionSanitizer.deviceType(null));
    }

    @Test
    void instant_isParsedToEasternLocalTime_orDropped() {
        assertEquals(LocalDateTime.of(2026, 9, 1, 8, 34, 56), AttributionSanitizer.instantToEastern("2026-09-01T12:34:56.000Z"));
        assertEquals(LocalDateTime.of(2026, 1, 15, 19, 0, 0), AttributionSanitizer.instantToEastern("2026-01-16T00:00:00Z")); // EST
        assertNull(AttributionSanitizer.instantToEastern("not-a-date"));
        assertNull(AttributionSanitizer.instantToEastern("2026-09-01"));
        assertNull(AttributionSanitizer.instantToEastern("x".repeat(100)));
        assertNull(AttributionSanitizer.instantToEastern(null));
    }
}
