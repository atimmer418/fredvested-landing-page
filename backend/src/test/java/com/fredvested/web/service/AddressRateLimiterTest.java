package com.fredvested.web.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AddressRateLimiterTest {

    @Test
    void onePerTenMinutes_threePerDay() {
        AddressRateLimiter limiter = new AddressRateLimiter();
        long t0 = 1_000_000_000_000L;
        long minute = 60_000L;

        assertTrue(limiter.allow("a@example.com", t0));
        assertFalse(limiter.allow("a@example.com", t0 + 5 * minute));          // within 10 minutes
        assertTrue(limiter.allow("a@example.com", t0 + 11 * minute));
        assertTrue(limiter.allow("a@example.com", t0 + 22 * minute));          // third of the day
        assertFalse(limiter.allow("a@example.com", t0 + 60 * minute));         // daily cap
        assertFalse(limiter.allow("a@example.com", t0 + 23 * 60 * minute));
        assertTrue(limiter.allow("a@example.com", t0 + 25 * 60 * minute));     // window rolled
    }

    @Test
    void addressesAreIndependent_andCaseInsensitive() {
        AddressRateLimiter limiter = new AddressRateLimiter();
        long t0 = 1_000_000_000_000L;
        assertTrue(limiter.allow("a@example.com", t0));
        assertTrue(limiter.allow("b@example.com", t0));
        assertFalse(limiter.allow("A@Example.com ", t0 + 1));
    }
}
