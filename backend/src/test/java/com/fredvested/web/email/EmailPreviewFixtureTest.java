package com.fredvested.web.email;

import com.fredvested.web.service.EmailTemplates;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Writes the rendered v2 emails for the sample projection to build/email-preview/ so
 * they can be opened in a browser: welcome-sample.html (+ .txt), welcome-fallback.html
 * (+ .txt), welcome-late-date.html (+ .txt; the 65-or-later variant from the stake-hidden
 * sample, with its own chart) and confirmation-v2.html (+ .txt). The on-disk HTML's chart
 * URL is a PNG in the same directory (growth-chart-sample.png is written by
 * GrowthChartRendererTest); the sent email carries the recipient's tokened URL instead.
 */
class EmailPreviewFixtureTest {

    static final Path DIR = Path.of("build/email-preview");
    static final String UNSUB = "https://lpapi-dev.fredvested.com/api/waitlist/unsubscribe?token=sample";
    static final String CONFIRM = "https://lpapi-dev.fredvested.com/api/waitlist/confirm?token=sample";
    static final String ADDR = "PO Box 2406, Rockville, MD 20847";

    @Test
    void writesTheSampleRenders() throws IOException {
        Files.createDirectories(DIR);
        write("welcome-sample", PersonalizedWelcomeEmail.render(Fixtures.projection("sample"), "growth-chart-sample.png", UNSUB, ADDR));
        write("welcome-fallback", PersonalizedWelcomeEmail.render(null, null, UNSUB, ADDR));
        write("welcome-late-date", PersonalizedWelcomeEmail.render(Fixtures.projection("stake-hidden"), "growth-chart-late-date.png", UNSUB, ADDR));
        Files.write(DIR.resolve("growth-chart-late-date.png"), GrowthChartRenderer.render(Fixtures.projection("stake-hidden")).bytes());
        write("confirmation-v2", EmailTemplates.confirmationV2(CONFIRM, UNSUB, 7, ADDR));
        for (String name : new String[] { "welcome-sample", "welcome-fallback", "welcome-late-date", "confirmation-v2" }) {
            assertTrue(Files.size(DIR.resolve(name + ".html")) > 1000, name);
            assertTrue(Files.size(DIR.resolve(name + ".txt")) > 100, name);
        }
    }

    private static void write(String name, EmailTemplates.Rendered r) throws IOException {
        Files.writeString(DIR.resolve(name + ".html"), r.html(), StandardCharsets.UTF_8);
        Files.writeString(DIR.resolve(name + ".txt"), r.text(), StandardCharsets.UTF_8);
    }
}
