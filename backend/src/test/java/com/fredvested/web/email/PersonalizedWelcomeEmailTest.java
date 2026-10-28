package com.fredvested.web.email;

import com.fredvested.web.service.EmailTemplates;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PersonalizedWelcomeEmailTest {

    static final String UNSUB = "https://lpapi-dev.fredvested.com/api/waitlist/unsubscribe?token=def";
    static final String ADDR = "PO Box 123, Baltimore, MD 21201";
    static final Path GOLDEN = Path.of("src/test/resources/email/welcome-sample.golden.html");

    static EmailTemplates.Rendered sample() {
        return PersonalizedWelcomeEmail.render(Fixtures.projection("sample"), UNSUB, ADDR);
    }

    static EmailTemplates.Rendered fallback() {
        return PersonalizedWelcomeEmail.render(null, UNSUB, ADDR);
    }

    // Every sentence of the template (frozen; pending counsel), in both parts.
    static final List<String> SENTENCES = List.of(
            "You're on FRED's private beta waitlist.",
            "Here's the math FRED is built around.",
            "300 spots. 48 hours. One-time offer.",
            "Invites go out in waves. When yours lands, you have",
            "48 hours",
            "to claim it. After that, it goes to the next person in line.",
            "Nothing to do now. Watch your inbox.",
            "No payment until launch. Private beta is iOS only.",
            "The FRED Team",
            "help@fredvested.com");

    static final List<String> DISCLOSURES = List.of(
            "Hypothetical illustration based on the assumptions shown, not a prediction or guarantee. Actual returns may vary. Investing involves risk, including possible loss of principal.",
            "This email reflects the numbers you entered on fredvested.com. FRED's calculator is an educational planning tool. It does not provide investment advice or recommend any investment.",
            EmailTemplates.DEVELOPMENT_DISCLAIMER);

    // The HTML is snapshotted: the golden file is written on the first run and compared after.
    @Test
    void sampleHtml_matchesTheGoldenFile() throws IOException {
        String html = sample().html();
        if (!Files.exists(GOLDEN)) {
            Files.createDirectories(GOLDEN.getParent());
            Files.writeString(GOLDEN, html, StandardCharsets.UTF_8);
        }
        assertEquals(Files.readString(GOLDEN, StandardCharsets.UTF_8), html, "welcome-sample.golden.html drifted: delete it to re-snapshot a deliberate change");
    }

    @Test
    void subject_isYoureIn() {
        assertEquals("You're in", sample().subject());
        assertEquals("You're in", fallback().subject());
    }

    @Test
    void sample_carriesEveryFrozenSentence_everyDisclosure_andTheFooter_inBothParts() {
        EmailTemplates.Rendered r = sample();
        String htmlText = r.html().replace("<b>", "").replace("</b>", "");
        for (String s : SENTENCES) {
            assertTrue(htmlText.contains(s), "HTML: " + s);
            assertTrue(r.text().contains(s), "text: " + s);
        }
        for (String d : DISCLOSURES) {
            assertTrue(r.html().contains(d), "HTML: " + d);
            assertTrue(r.text().contains(d), "text: " + d);
        }
        assertTrue(r.html().contains("FREDvested LLC, " + ADDR) && r.text().contains("FREDvested LLC, " + ADDR));
        assertTrue(r.html().contains("href=\"" + UNSUB + "\"") && r.text().contains("Unsubscribe: " + UNSUB));
        assertTrue(r.html().contains("href=\"https://fredvested.com/privacy\"") && r.html().contains(">Privacy policy</a>"));
        assertTrue(r.text().contains("Privacy policy: https://fredvested.com/privacy"));
    }

    @Test
    void sample_isFilledWithTheRecipientsNumbers() {
        EmailTemplates.Rendered r = sample();
        assertTrue(r.html().contains("12 fewer working years. Here's the math FRED is built around."), "preheader");
        assertTrue(r.html().contains("12 fewer working years could look like:"));
        assertTrue(r.html().contains("$2,000</b>/mo from age <b style=\"color:#ffffff;\">30</b> &rarr; a projected <b style=\"color:#ffffff;\">$7,500</b>/mo starting at <b style=\"color:#ffffff;\">53</b>."));
        assertTrue(r.html().contains(">$564k in. A projected $2.25M out. Compounding does the rest.</p>"));
        assertTrue(r.html().contains(">Assumes a 10% avg annual return (Moderate scenario) &amp; 4% withdrawal rate. Hypothetical illustration"));
        assertFalse(r.html().contains("<span>"), "bare marker spans collapse to their text");
        assertFalse(r.html().contains("xmlns="), "the XHTML namespace is dropped");
        assertTrue(r.html().contains("alt=\"Line chart of a projected portfolio growing to $2.25M between age 30 and age 53, with $564k put in\""), "alt carries the four numbers");
        assertEquals(1, count(r.html(), "src=\"cid:growth-chart\""), "cid reference exactly once");
        assertFalse(r.html().contains("Put your financial future on autopilot."));

        assertTrue(r.text().contains("12 fewer working years could look like:\n$2,000/mo from age 30 -> a projected $7,500/mo starting at 53.\n$564k in. A projected $2.25M out. Compounding does the rest.\nAssumes a 10% avg annual return (Moderate scenario) & 4% withdrawal rate. Hypothetical illustration"));
    }

    @Test
    void anotherRecipient_getsTheirOwnNumbers_includingTheAltText() {
        EmailTemplates.Rendered r = PersonalizedWelcomeEmail.render(Fixtures.projection("existing-100k-optimistic"), UNSUB, ADDR);
        assertTrue(r.html().contains("8 fewer working years could look like:"));
        assertTrue(r.html().contains("Assumes a 12% avg annual return (Optimistic scenario)"));
        assertTrue(r.html().contains("growing to $2.25M between age 30 and age 48, with $522k put in\""));
        assertTrue(r.text().contains("starting at 48."));
    }

    // The stake headline is the plan rung's own number. The lever rung's number belongs to a higher
    // monthly amount the template cannot name, and the hidden rung has nothing to show (the page hides
    // its comparison too), so both keep the frozen no-date heading with the chart and the numbers.
    @Test
    void leverAndHiddenRungs_keepTheNoDateHeading_withTheChartAndTheNumbers() {
        EmailTemplates.Rendered lever = PersonalizedWelcomeEmail.render(Fixtures.projection("stake-lever"), UNSUB, ADDR);
        EmailTemplates.Rendered hidden = PersonalizedWelcomeEmail.render(Fixtures.projection("stake-hidden"), UNSUB, ADDR);
        for (EmailTemplates.Rendered r : List.of(lever, hidden)) {
            assertTrue(r.html().contains("Put your financial future on autopilot."));
            assertFalse(r.html().contains("fewer working years"), r.html());
            assertTrue(r.html().contains("cid:growth-chart"));
            assertTrue(r.html().contains("/mo from age"));
            assertTrue(r.html().contains("Compounding does the rest."));
            assertTrue(r.html().contains("Assumes a 10% avg annual return (Moderate scenario)"));
            assertTrue(r.text().contains("Put your financial future on autopilot.\n$"));
            assertFalse(r.text().contains("fewer working years"));
            assertTrue(r.text().contains("Compounding does the rest."));
        }
        assertTrue(lever.html().contains(">$314k in. A projected $1.5M out."));
        assertFalse(lever.html().contains("3 fewer"));
        assertFalse(hidden.html().contains("0 fewer"));
        assertTrue(hidden.html().contains("starting at <b style=\"color:#ffffff;\">73</b>."));
    }

    @Test
    void sentOutput_hasNoMarkers_noComment_noEmDash_noCleartextLink_noRemoteImage() {
        for (EmailTemplates.Rendered r : List.of(sample(), fallback())) {
            assertFalse(r.html().contains("data-field"), "data-field stripped");
            assertFalse(r.html().contains("data-projection"), "data-projection stripped");
            assertFalse(r.html().startsWith("<!--") || r.html().stripLeading().startsWith("<!--"), "leading comment stripped");
            assertTrue(r.html().startsWith("<!DOCTYPE html>"), r.html().substring(0, 40));
            assertFalse(r.html().contains("—") || r.text().contains("—") || r.subject().contains("—"), "no em dash");
            assertFalse(r.html().contains("http://"), "no cleartext links");
            assertFalse(r.html().contains("<link") || r.html().contains("@import") || r.html().contains("url("), "nothing fetched");
            assertFalse(r.html().contains("src=\"http"), "no hosted image");
            assertFalse(r.html().contains("@example.com") || r.text().contains("@example.com"));
        }
    }

    @Test
    void fallback_hasTheNoDateHeading_noChart_andNoPersonalLines() {
        EmailTemplates.Rendered r = fallback();
        assertTrue(r.html().contains("Put your financial future on autopilot."));
        assertEquals(0, count(r.html(), "cid:"));
        assertFalse(r.html().contains("<img"));
        assertFalse(r.html().contains("fewer working years"));
        assertFalse(r.html().contains("Assumes a"));
        assertFalse(r.html().contains("Compounding does the rest"));
        assertFalse(r.html().contains("/mo from age"));
        // The preheader loses its first sentence; the second stays.
        assertTrue(r.html().contains("color:#f1f5f9;\">Here's the math FRED is built around.</div>"), r.html());
        for (String s : SENTENCES) assertTrue(r.html().replace("<b>", "").replace("</b>", "").contains(s), s);
        for (String d : DISCLOSURES) assertTrue(r.html().contains(d), d);

        assertTrue(r.text().contains("Here's the math FRED is built around.\n\nPut your financial future on autopilot.\nHypothetical illustration based on"));
        assertFalse(r.text().contains("Assumes a") || r.text().contains("fewer working years") || r.text().contains("/mo from age"));
        for (String d : DISCLOSURES) assertTrue(r.text().contains(d), d);
    }

    @Test
    void textPart_hasExactlyTheSpecifiedLines() {
        String[] lines = sample().text().split("\n", -1);
        String[] expected = {
                "You're on FRED's private beta waitlist.",
                "Here's the math FRED is built around.",
                "",
                "12 fewer working years could look like:",
                "$2,000/mo from age 30 -> a projected $7,500/mo starting at 53.",
                "$564k in. A projected $2.25M out. Compounding does the rest.",
                "Assumes a 10% avg annual return (Moderate scenario) & 4% withdrawal rate. " + DISCLOSURES.get(0),
                "",
                "300 spots. 48 hours. One-time offer.",
                "Invites go out in waves. When yours lands, you have 48 hours to claim it. After that, it goes to the next person in line.",
                "",
                "Nothing to do now. Watch your inbox.",
                "No payment until launch. Private beta is iOS only.",
                "",
                "The FRED Team",
                "help@fredvested.com",
                "",
                DISCLOSURES.get(1),
                "",
                EmailTemplates.DEVELOPMENT_DISCLAIMER,
                "",
                "FREDvested LLC, " + ADDR,
                "Unsubscribe: " + UNSUB,
                "Privacy policy: https://fredvested.com/privacy",
                "",
        };
        assertArrayEquals(expected, lines);
    }

    @Test
    void postalAddress_andUnsubscribeUrl_areEscaped() {
        EmailTemplates.Rendered r = PersonalizedWelcomeEmail.render(Fixtures.projection("sample"), "https://x/u?token=a&b=<c>", "<b>x</b> & co");
        assertFalse(r.html().contains("<b>x</b>"));
        assertTrue(r.html().contains("&lt;b&gt;x&lt;/b&gt; &amp; co"));
        assertTrue(r.html().contains("href=\"https://x/u?token=a&amp;b=&lt;c&gt;\""));
    }

    private static int count(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + needle.length())) n++;
        return n;
    }
}
