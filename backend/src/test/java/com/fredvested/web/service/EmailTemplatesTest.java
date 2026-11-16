package com.fredvested.web.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EmailTemplatesTest {

    static final String CONFIRM = "https://lpapi-dev.fredvested.com/api/waitlist/confirm?token=abc";
    static final String UNSUB = "https://lpapi-dev.fredvested.com/api/waitlist/unsubscribe?token=def";
    static final String ADDR = "PO Box 123, Baltimore, MD 21201";
    static final String WORDMARK = "https://lpapi-dev.fredvested.com/api/email/wordmark-v1.png";

    // The v1 templates (email.templates.v2.enabled=false, the default): every assertion on
    // them is unchanged. The v2 confirmation (confirmationV2, in the personalised welcome's
    // frame) gets its own assertions below rather than loosening these.
    static List<EmailTemplates.Rendered> all() {
        return List.of(EmailTemplates.confirmation(CONFIRM, UNSUB, 7, ADDR), EmailTemplates.welcome(UNSUB, ADDR));
    }

    static EmailTemplates.Rendered v2() {
        return EmailTemplates.confirmationV2(CONFIRM, UNSUB, 7, ADDR, WORDMARK);
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

    // Counsel, 2026-10-13: every email footer carries the development disclaimer verbatim
    // and a privacy policy link, in both parts, after the unsubscribe link and postal address.
    @Test
    void everyTemplate_footerCarriesTheDevelopmentDisclaimer_andThePrivacyLink_inBothParts() {
        String disclaimer = "FRED is still in development. Any investment advisory services will be made available only "
                + "after applicable registration requirements have been satisfied and subject to eligibility and onboarding "
                + "requirements. Joining the waitlist does not create an advisory relationship or guarantee account approval "
                + "or a launch date.";
        for (EmailTemplates.Rendered r : all()) {
            assertTrue(r.html().contains(disclaimer), r.subject() + ": disclaimer in HTML");
            assertTrue(r.text().contains(disclaimer), r.subject() + ": disclaimer in text");
            assertTrue(r.html().contains("href=\"https://fredvested.com/privacy\""), r.subject() + ": privacy link in HTML");
            assertTrue(r.html().contains(">Privacy Policy</a>"), r.subject() + ": privacy link label");
            assertTrue(r.text().contains("Privacy Policy: https://fredvested.com/privacy"), r.subject() + ": privacy link in text");
            int unsub = r.html().indexOf("href=\"" + UNSUB + "\"");
            int privacy = r.html().indexOf("https://fredvested.com/privacy");
            int address = r.html().indexOf("FREDvested LLC, " + ADDR);
            int note = r.html().indexOf(disclaimer);
            assertTrue(unsub < privacy && privacy < address && address < note, r.subject() + ": footer order");
            assertTrue(r.html().contains("text-wrap:pretty"), r.subject() + ": disclaimer paragraph wraps pretty");
        }
    }

    // Counsel, 2026-09-24 and 2026-10-13, applied to the v2 confirmation: unsubscribe link,
    // postal address, development disclaimer and privacy link in both parts. In the v2 frame
    // the whole footer sits inside the card (Andrew's render), the privacy label is
    // "Privacy policy", and the disclaimer precedes the address line.
    @Test
    void confirmationV2_carriesTheLinks_theAddress_andTheDisclaimer_inBothParts_insideTheCard() {
        EmailTemplates.Rendered r = v2();
        String disclaimer = EmailTemplates.DEVELOPMENT_DISCLAIMER;
        assertEquals("Confirm your email", r.subject());
        assertTrue(r.html().contains("href=\"" + CONFIRM + "\"") && r.text().contains(CONFIRM));
        assertTrue(r.html().contains("href=\"" + UNSUB + "\"") && r.text().contains("Unsubscribe: " + UNSUB));
        assertTrue(r.html().contains("FREDvested LLC, " + ADDR) && r.text().contains("FREDvested LLC, " + ADDR));
        assertTrue(r.html().contains(disclaimer) && r.text().contains(disclaimer));
        assertTrue(r.html().contains("href=\"https://fredvested.com/privacy\"") && r.html().contains(">Privacy policy</a>"));
        assertTrue(r.text().contains("Privacy policy: https://fredvested.com/privacy"));
        assertTrue(r.html().contains("7 days") && r.text().contains("7 days"));
        assertTrue(r.html().contains("Confirm my email"));
        // Footer inside the card: the footer band is a row of the 600px card table, before its </table>.
        int footer = r.html().indexOf("class=\"footer\"");
        assertTrue(footer > 0 && footer < r.html().lastIndexOf("</table>"), "footer band inside the card");
        assertTrue(r.html().indexOf("</td>", footer) > r.html().indexOf(disclaimer), "disclaimer inside the footer band");
        assertTrue(r.html().contains("text-wrap:pretty"));
        // No calculator sentence on a consent email, and nothing else from the welcome's body.
        assertFalse(r.html().contains("This email reflects") || r.text().contains("This email reflects"));
        // The one image is the static header wordmark on this API (the same for everyone); no chart, no cid.
        assertFalse(r.html().contains("cid:") || r.html().contains("/api/email/chart/"));
        assertEquals(1, count(r.html(), "<img"));
        assertTrue(r.html().contains("src=\"" + WORDMARK + "\"") && r.html().contains("alt=\"FRED\""));
        assertThrows(IllegalArgumentException.class, () -> EmailTemplates.confirmationV2(CONFIRM, UNSUB, 7, ADDR, " "));
        assertFalse(r.html().contains("data-field") || r.html().contains("data-projection") || r.html().startsWith("<!--"));
        assertTrue(r.html().startsWith("<!DOCTYPE html>"));
        EmailTemplates.Rendered hostile = EmailTemplates.confirmationV2(CONFIRM, UNSUB, 7, "<b>x</b>", WORDMARK);
        assertFalse(hostile.html().contains("<b>x</b>"), "address is escaped in HTML");
        EmailTemplates.Rendered ten = EmailTemplates.confirmationV2(CONFIRM, UNSUB, 10, ADDR, WORDMARK);
        assertTrue(ten.html().contains("10 days") && ten.text().contains("10 days") && !ten.html().contains("7 days"));
    }

    // The v2 text part is the v1 text with only the privacy label changed.
    @Test
    void confirmationV2_textPart_isTheV1Text_withThePrivacyLabel() {
        String v1 = EmailTemplates.confirmation(CONFIRM, UNSUB, 7, ADDR).text();
        assertEquals(v1.replace("Privacy Policy:", "Privacy policy:"), v2().text());
    }

    // Dark-mode handling is deliberately left to the mail client for the v1 templates
    // (Andrew, 2026-09-24: the inverted rendering on iPhone Mail looks right; no color-scheme
    // declaration). The v2 frame deliberately carries prefers-color-scheme and [data-ogsc]
    // rules per Andrew's light and dark renders of 2026-10-27, so this stays scoped to v1.
    @Test
    void leavesDarkModeToTheClient_noColorSchemeDeclaration() {
        for (EmailTemplates.Rendered r : all()) {
            assertFalse(r.html().contains("color-scheme"), r.html());
        }
    }

    // The v2 wordmark is the navbar logo as an image since 2026-11-16 (Andrew), with a styled "FRED"
    // as the alt text; its size attributes are the PNG's CSS size.
    @Test
    void v2_declaresBothColorSchemes_andTheNavbarWordmarkImage() {
        EmailTemplates.Rendered r = v2();
        assertTrue(r.html().contains("prefers-color-scheme: dark") && r.html().contains("[data-ogsc]"));
        assertTrue(r.html().contains("<meta name=\"color-scheme\" content=\"light dark\">"));
        int img = r.html().indexOf("<img class=\"wordmark\"");
        assertTrue(img > 0 && img < r.html().indexOf("<h1"));
        String tag = r.html().substring(img, r.html().indexOf(">", img) + 1);
        assertTrue(tag.contains("src=\"" + WORDMARK + "\"") && tag.contains("alt=\"FRED\"") && tag.contains("font-style:italic") && tag.contains("Montserrat,"), tag);
        assertTrue(tag.contains("width=\"" + com.fredvested.web.email.WordmarkRenderer.render().cssWidth() + "\" height=\"" + com.fredvested.web.email.WordmarkRenderer.render().cssHeight() + "\""), tag);
        assertFalse(r.html().contains(">FRED </span>"));
    }

    // iOS has no Arial Black; Arial's heaviest weight there is bold, so the wordmark
    // lost its weight. Avenir Next ships on every Apple platform with a Heavy Italic
    // and needs no download, so it is the fallback before plain Arial. Scoped to v1: the
    // v2 frame's wordmark is upright Inter 800 per Andrew's render.
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
            // The personalised welcome email's own promotional strings (2026-10-27).
            "freedom date", "fewer working years", "compounding", "one-time offer", "watch your inbox",
            "no payment", "ios only", "you put in", "the math fred is built around", "next person in line",
    };

    @Test
    void confirmationEmail_isConsentOnly_andCarriesNoPromotionalCopy() {
        for (EmailTemplates.Rendered r : List.of(EmailTemplates.confirmation(CONFIRM, UNSUB, 7, ADDR), v2())) {
            String all = (r.subject() + "\n" + r.html() + "\n" + r.text()).toLowerCase();
            for (String phrase : PROMOTIONAL) {
                assertFalse(all.contains(phrase.toLowerCase()), "promotional phrase in confirmation email (" + r.subject() + "): " + phrase);
            }
            // What it must still say: why they are receiving it, the link, the expiry, the unsubscribe link.
            assertTrue(all.contains("because"), "one sentence on why they're receiving it");
            assertTrue(all.contains(CONFIRM.toLowerCase()) && all.contains("7 days"));
            assertTrue(all.contains(UNSUB.toLowerCase()));
        }
    }

    // Standing rules: no third-party fetch of any kind (the privacy policy names the
    // vendors), no cleartext links, no em dashes in user-facing copy.
    @Test
    void noExternalResources_noCleartextLinks_noEmDashes() {
        for (EmailTemplates.Rendered r : List.of(all().get(0), all().get(1), v2())) {
            String html = r.html();
            assertFalse(html.contains("<link"), "no stylesheet links");
            assertFalse(html.contains("@import") || html.contains("url("), "no fetched fonts or images");
            // v1: no image at all. v2: only the header wordmark, on this API.
            if (html.contains("wordmark-v1.png")) {
                assertEquals(count(html, "<img"), count(html, "src=\"" + WORDMARK + "\""), "no image but the wordmark");
            } else {
                assertFalse(html.contains("<img"), "no remote images");
            }
            assertFalse(html.contains("http://"), "no cleartext links");
            assertFalse(html.contains("—") || r.text().contains("—") || r.subject().contains("—"), "no em dashes");
            assertTrue(html.contains(UNSUB) && r.text().contains(UNSUB), "unsubscribe link in both parts");
        }
    }

    private static int count(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + needle.length())) n++;
        return n;
    }
}
