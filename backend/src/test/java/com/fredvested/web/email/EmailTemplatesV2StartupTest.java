package com.fredvested.web.email;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import static org.junit.jupiter.api.Assertions.*;

// With the v2 templates on, the sample email and chart are rendered once at boot so a JDK without a
// working Java2D or font stack stops startup loudly instead of failing one welcome email at a time.
class EmailTemplatesV2StartupTest {

    @Test
    void rendersTheSampleEmailAndChart_atBoot() {
        assertDoesNotThrow(() -> new EmailTemplatesV2Startup(new ObjectMapper()).check());
    }

    @Test
    void existsOnlyWhenTheFlagIsOn() {
        ConditionalOnProperty on = EmailTemplatesV2Startup.class.getAnnotation(ConditionalOnProperty.class);
        assertNotNull(on);
        assertArrayEquals(new String[] { "email.templates.v2.enabled" }, on.name());
        assertEquals("true", on.havingValue());
        assertFalse(on.matchIfMissing());
    }
}
