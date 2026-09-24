package com.fredvested.web.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EmailTemplatesTest {

    static final String CONFIRM = "https://lpapi-dev.fredvested.com/api/waitlist/confirm?token=abc";
    static final String UNSUB = "https://lpapi-dev.fredvested.com/api/waitlist/unsubscribe?token=def";

    static List<EmailTemplates.Rendered> all() {
        return List.of(EmailTemplates.confirmation(CONFIRM, UNSUB, 7), EmailTemplates.welcome(UNSUB));
    }

    // Dark-mode handling is deliberately left to the mail client (Andrew, 2026-09-24:
    // the inverted rendering on iPhone Mail looks right; no color-scheme declaration).
    @Test
    void leavesDarkModeToTheClient_noColorSchemeDeclaration() {
        for (EmailTemplates.Rendered r : all()) {
            assertFalse(r.html().contains("color-scheme"), r.html());
        }
    }

    // iOS has no Arial Black; Arial's heaviest weight there is bold, so the wordmark
    // lost its weight. Avenir Next ships on every Apple platform with a Heavy Italic
    // and needs no download, so it is the fallback before plain Arial.
    @Test
    void wordmark_fallsBackToAnAppleSystemHeavyFace_beforePlainArial() {
        for (EmailTemplates.Rendered r : all()) {
            int fred = r.html().indexOf(">FRED</span>");
            assertTrue(fred > 0);
            String span = r.html().substring(r.html().lastIndexOf("<span", fred), fred);
            int black = span.indexOf("'Arial Black'");
            int avenir = span.indexOf("'Avenir Next'");
            int arial = span.indexOf(",Arial,");
            assertTrue(black >= 0 && avenir > black && arial > avenir, span);
            assertTrue(span.contains("font-weight:900") && span.contains("font-style:italic"), span);
        }
    }

    // Standing rules: no third-party fetch of any kind (the privacy policy names the
    // vendors), no cleartext links, no em dashes in user-facing copy.
    @Test
    void noExternalResources_noCleartextLinks_noEmDashes() {
        for (EmailTemplates.Rendered r : all()) {
            String html = r.html();
            assertFalse(html.contains("<link"), "no stylesheet links");
            assertFalse(html.contains("@import") || html.contains("url("), "no fetched fonts or images");
            assertFalse(html.contains("<img"), "no remote images");
            assertFalse(html.contains("http://"), "no cleartext links");
            assertFalse(html.contains("—") || r.text().contains("—") || r.subject().contains("—"), "no em dashes");
            assertTrue(html.contains(UNSUB) && r.text().contains(UNSUB), "unsubscribe link in both parts");
        }
    }
}
