package com.fredvested.web.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ConfirmationTokensTest {

    @Test
    void tokensAre32RandomBytes_urlSafe_andHashedWithSha256() {
        ConfirmationTokens.Generated g = ConfirmationTokens.generate();
        assertEquals(43, g.raw().length());                       // 32 bytes, base64url, no padding
        assertTrue(g.raw().matches("[A-Za-z0-9_-]+"));
        assertEquals(64, g.hash().length());
        assertTrue(g.hash().matches("[0-9a-f]+"));
        assertEquals(g.hash(), ConfirmationTokens.hash(g.raw()));
        assertNotEquals(g.raw(), g.hash());
    }

    @Test
    void tokensAreUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) assertTrue(seen.add(ConfirmationTokens.generate().raw()));
    }

    @Test
    void hashRejectsWhatWeNeverIssued() {
        assertNull(ConfirmationTokens.hash(null));
        assertNull(ConfirmationTokens.hash(""));
        assertNull(ConfirmationTokens.hash("x".repeat(129)));
        assertEquals("2c26b46b68ffc68ff99b453c1d30413413422d706483bfa0f98a5e886266e7ae", ConfirmationTokens.hash("foo"));
    }
}
