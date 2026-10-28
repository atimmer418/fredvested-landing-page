package com.fredvested.web.email;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * With the v2 templates on, renders the bundled sample projection (email and chart) once
 * at boot and lets any failure stop startup: a JDK without a working Java2D or font stack
 * would otherwise be discovered only by the scheduler thread, one welcome email at a time
 * (the same principle as ddl-auto=validate: loud at boot, never silent later).
 */
@Component
@ConditionalOnProperty(name = "email.templates.v2.enabled", havingValue = "true")
public class EmailTemplatesV2Startup {

    private static final Logger log = LoggerFactory.getLogger(EmailTemplatesV2Startup.class);
    static final String SAMPLE = "email/samples/sample.json";

    private final ObjectMapper objectMapper;

    public EmailTemplatesV2Startup(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void check() {
        Projection sample = sample();
        PersonalizedWelcomeEmail.render(sample, "https://example.invalid/unsubscribe", "[PO Box pending]");
        GrowthChartRenderer.Png png = GrowthChartRenderer.render(sample);
        log.info("email templates v2 enabled: sample chart {} bytes at {}x{}", png.bytes().length, png.width(), png.height());
    }

    private Projection sample() {
        try (InputStream in = EmailTemplatesV2Startup.class.getClassLoader().getResourceAsStream(SAMPLE)) {
            if (in == null) throw new IllegalStateException("sample projection missing: " + SAMPLE);
            return objectMapper.readValue(in, Projection.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
