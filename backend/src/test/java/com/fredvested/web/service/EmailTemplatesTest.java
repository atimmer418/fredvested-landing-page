package com.fredvested.web.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EmailTemplatesTest {

    static final String CONFIRM = "https://lpapi-dev.fredvested.com/api/waitlist/confirm?token=abc";
    static final String UNSUB = "https://lpapi-dev.fredvested.com/api/waitlist/unsubscribe?token=def";
    static final String ADDR = "PO Box 123, Baltimore, MD 21201";

    static List<EmailTemplates.Rendered> all() {
        return List.of(EmailTemplates.confirmation(CONFIRM, UNSUB, 7, ADDR), EmailTemplates.welcome(UNSUB, ADDR));
    }

    // Counsel, 2026-09-24: every email carries an unsubscribe link and our postal address,
    // in both the HTML and the plain-text part. A placeholder shows until the address is set.
    @Test
    void everyTemplate_carriesTheUnsubscribeLink_andThePostalAddress_inBothParts() {
        for (EmailTemplates.Rendered r : all()) {
            assertTrue(r.html().contains("href=\"" + UNSUB + "\""), r.subject());
            assertTrue(r.text().contains("Unsubscribe: " + UNSUB), r.subject());
            assertTrue(r.html().contains("FREDvested LLC, " + ADDR), r.subject());
            assertTrue(r.text().contains("FREDvested LLC, " + ADDR), r.subject());
        }
        EmailTemplates.Rendered pending = EmailTemplates.confirmation(CONFIRM, UNSUB, 7, "[PO Box pending]");
        assertTrue(pending.html().contains("[PO Box pending]") && pending.text().contains("[PO Box pending]"));
        EmailTemplates.Rendered hostile = EmailTemplates.confirmation(CONFIRM, UNSUB, 7, "<b>x</b>");
        assertFalse(hostile.html().contains("<b>x</b>"), "address is escaped in HTML");
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

    // Counsel, 2026-09-24: a double opt-in email exists to obtain consent, so it is not
    // transactional and must carry nothing promotional. The deny-list is taken from the
    // current page copy (pricing, the cap, feature and scarcity lines, the "what happens
    // next" block). Checked case-insensitively across subject, HTML and text.
    static final String[] PROMOTIONAL = {
            "founding", "founder", "300", "spots", "$", "pricing", "lifetime",
            "autopilot", "paycheck", "clock out", "monte carlo", "withdrawal", "financial future",
            "private beta", "beta", "reviewed in waves", "waves", "48 hours", "claim", "invite", "priority",
            "what happens next", "secure your spot", "lock in",
    };

    @Test
    void confirmationEmail_isConsentOnly_andCarriesNoPromotionalCopy() {
        EmailTemplates.Rendered r = EmailTemplates.confirmation(CONFIRM, UNSUB, 7, ADDR);
        String all = (r.subject() + "\n" + r.html() + "\n" + r.text()).toLowerCase();
        for (String phrase : PROMOTIONAL) {
            assertFalse(all.contains(phrase.toLowerCase()), "promotional phrase in confirmation email: " + phrase);
        }
        // What it must still say: why they are receiving it, the link, the expiry, the unsubscribe link.
        assertTrue(all.contains("because"), "one sentence on why they're receiving it");
        assertTrue(all.contains(CONFIRM.toLowerCase()) && all.contains("7 days"));
        assertTrue(all.contains(UNSUB.toLowerCase()));
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
