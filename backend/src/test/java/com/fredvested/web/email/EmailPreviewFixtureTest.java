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
 * (+ .txt) and confirmation-v2.html (+ .txt). The on-disk HTML has the chart's cid
 * reference rewritten to growth-chart-sample.png, which GrowthChartRendererTest writes
 * to the same directory; the sent HTML is unchanged.
 */
class EmailPreviewFixtureTest {

    static final Path DIR = Path.of("build/email-preview");
    static final String UNSUB = "https://lpapi-dev.fredvested.com/api/waitlist/unsubscribe?token=sample";
    static final String CONFIRM = "https://lpapi-dev.fredvested.com/api/waitlist/confirm?token=sample";
    static final String ADDR = "PO Box 2406, Rockville, MD 20847";

    @Test
    void writesTheSampleRenders() throws IOException {
        Files.createDirectories(DIR);
        write("welcome-sample", PersonalizedWelcomeEmail.render(Fixtures.projection("sample"), UNSUB, ADDR));
        write("welcome-fallback", PersonalizedWelcomeEmail.render(null, UNSUB, ADDR));
        write("confirmation-v2", EmailTemplates.confirmationV2(CONFIRM, UNSUB, 7, ADDR));
        for (String name : new String[] { "welcome-sample", "welcome-fallback", "confirmation-v2" }) {
            assertTrue(Files.size(DIR.resolve(name + ".html")) > 1000, name);
            assertTrue(Files.size(DIR.resolve(name + ".txt")) > 100, name);
        }
    }

    private static void write(String name, EmailTemplates.Rendered r) throws IOException {
        Files.writeString(DIR.resolve(name + ".html"), EmailPreviewController.withChartUrl(r.html(), "growth-chart-sample.png"), StandardCharsets.UTF_8);
        Files.writeString(DIR.resolve(name + ".txt"), r.text(), StandardCharsets.UTF_8);
    }
}
