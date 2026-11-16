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
    static final String CHART = "https://lpapi-dev.fredvested.com/api/email/chart/abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG.png";
    // The preheader's padding: the sentence, then enough invisible characters that a snippet shows nothing after it.
    static final String PREHEADER_TAIL = "Here's the math FRED is built around. " + "&zwnj;&nbsp;".repeat(80) + "</div>";
    static final String ADDR = "PO Box 123, Baltimore, MD 21201";
    static final Path GOLDEN = Path.of("src/test/resources/email/welcome-sample.golden.html");

    static EmailTemplates.Rendered sample() {
        return PersonalizedWelcomeEmail.render(Fixtures.projection("sample"), CHART, UNSUB, ADDR);
    }

    static EmailTemplates.Rendered fallback() {
        return PersonalizedWelcomeEmail.render(null, null, UNSUB, ADDR);
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
        assertTrue(r.html().contains("12 fewer working years. " + PREHEADER_TAIL), "preheader");
        assertTrue(r.html().contains("12 fewer working years could look like:"));
        assertTrue(r.html().contains("$2,000</b>/mo from age <b style=\"color:#ffffff;\">30</b> &rarr; a projected <b style=\"color:#ffffff;\">$7,500</b>/mo starting at <b style=\"color:#ffffff;\">53</b>."));
        assertTrue(r.html().contains(">$564k in. A projected $2.25M out. Compounding does the rest. </p>"));
        assertTrue(r.html().contains(">Assumes a 10% avg annual return (Moderate scenario) &amp; 4% withdrawal rate. Hypothetical illustration"));
        assertFalse(r.html().contains("<span>"), "bare marker spans collapse to their text");
        assertFalse(r.html().contains("xmlns="), "the XHTML namespace is dropped");
        assertTrue(r.html().contains("alt=\"Line chart of a projected portfolio growing to $2.25M between age 30 and age 53, with $564k put in\""), "alt carries the four numbers");
        assertEquals(1, count(r.html(), "src=\"" + CHART + "\""), "the chart URL exactly once");
        assertFalse(r.html().contains("cid:"));
        assertFalse(r.html().contains("Put your financial future on autopilot."));

        assertTrue(r.text().contains("12 fewer working years could look like:\n$2,000/mo from age 30 -> a projected $7,500/mo starting at 53.\n$564k in. A projected $2.25M out. Compounding does the rest.\nAssumes a 10% avg annual return (Moderate scenario) & 4% withdrawal rate. Hypothetical illustration"));
    }

    @Test
    void anotherRecipient_getsTheirOwnNumbers_includingTheAltText() {
        EmailTemplates.Rendered r = PersonalizedWelcomeEmail.render(Fixtures.projection("existing-100k-optimistic"), CHART, UNSUB, ADDR);
        assertTrue(r.html().contains("8 fewer working years could look like:"));
        assertTrue(r.html().contains("Assumes a 12% avg annual return (Optimistic scenario)"));
        assertTrue(r.html().contains("growing to $2.25M between age 30 and age 48, with $522k put in\""));
        assertTrue(r.text().contains("starting at 48."));
    }

    // The stake headline is the plan rung's own number. The lever rung's number belongs to a higher
    // monthly amount the template cannot name, and the hidden rung has nothing to show (the page hides
    // its comparison too), so both keep the frozen no-date heading with the chart and the numbers.
    @Test
    void leverAndHiddenRungs_underSixtyFive_keepTheNoDateHeading_withTheChartAndTheNumbers() {
        EmailTemplates.Rendered lever = PersonalizedWelcomeEmail.render(Fixtures.projection("stake-lever"), CHART, UNSUB, ADDR);
        EmailTemplates.Rendered hidden = PersonalizedWelcomeEmail.render(Fixtures.projection("hidden-under-65"), CHART, UNSUB, ADDR);
        for (EmailTemplates.Rendered r : List.of(lever, hidden)) {
            assertTrue(r.html().contains("Put your financial future on autopilot."));
            assertFalse(r.html().contains("fewer working years"), r.html());
            assertFalse(r.html().contains("Dates move.") || r.html().contains("Based on what you entered"), r.html());
            assertTrue(r.html().contains("src=\"" + CHART + "\""));
            assertTrue(r.html().contains("/mo from age"));
            assertTrue(r.html().contains("Compounding does the rest."));
            assertTrue(r.html().contains("Assumes a 10% avg annual return (Moderate scenario)"));
            assertTrue(r.text().contains("Put your financial future on autopilot.\n$"));
            assertFalse(r.text().contains("fewer working years") || r.text().contains("Dates move."));
            assertTrue(r.text().contains("Compounding does the rest."));
        }
        assertTrue(lever.html().contains(">$314k in. A projected $1.5M out."));
        assertFalse(lever.html().contains("3 fewer"));
        // A one-year plan rung (the page shows it since 2026-10-29): the headline is frozen in the plural,
        // so it keeps the no-date heading too, with the chart and the numbers.
        EmailTemplates.Rendered oneYear = PersonalizedWelcomeEmail.render(Fixtures.projection("stake-one-year"), CHART, UNSUB, ADDR);
        assertTrue(oneYear.html().contains("Put your financial future on autopilot."));
        assertFalse(oneYear.html().contains("fewer working"), oneYear.html());
        assertFalse(oneYear.text().contains("fewer working"));
        assertTrue(oneYear.html().contains("src=\"" + CHART + "\""));
        assertTrue(oneYear.html().contains(">$314k in. A projected $1.5M out."));
        assertFalse(hidden.html().contains("1 fewer"));
        assertTrue(hidden.html().contains("starting at <b style=\"color:#ffffff;\">64</b>."), "the boundary: 64 is not late");
    }

    static final String LATE_BODY_73 = "Based on what you entered, your projected Freedom Date landed at <b style=\"color:#ffffff;\">73</b>. A first projection is a starting point, not a verdict. Two things move a date like this in the math: how much goes in each month, and how long compounding gets to run. The calculator stays open, and the sliders are the point: test different monthly amounts and watch what the projection does. When FRED opens, the automation handles the showing-up part, which is the part most people lose years to.";

    // Andrew's copy of 2026-10-28 for a Freedom Date at 65 or later, whatever the stakes rung.
    @Test
    void aDateAtSixtyFiveOrLater_leadsWithDatesMove_andTheParagraphNamingTheDate() {
        EmailTemplates.Rendered hidden = PersonalizedWelcomeEmail.render(Fixtures.projection("stake-hidden"), CHART, UNSUB, ADDR);
        EmailTemplates.Rendered lever = PersonalizedWelcomeEmail.render(Fixtures.projection("late-date-lever"), CHART, UNSUB, ADDR);
        for (EmailTemplates.Rendered r : List.of(hidden, lever)) {
            assertTrue(r.html().contains(">Your date is on the board. Dates move. </p>"), r.html());
            assertFalse(r.html().contains("autopilot") || r.html().contains("fewer working years"), r.html());
            assertTrue(r.html().contains("src=\"" + CHART + "\"") && r.html().contains("/mo from age") && r.html().contains("Compounding does the rest."));
            assertTrue(r.html().contains("Assumes a 10% avg annual return (Moderate scenario)"));
            assertTrue(r.html().contains("color:#f1f5f9;\">" + PREHEADER_TAIL), "preheader keeps only the frozen sentence");
            assertFalse(r.html().contains("data-projection") || r.html().contains("<span>"));
            assertFalse(r.text().contains("autopilot") || r.text().contains("fewer working years"));
            for (String s : SENTENCES) assertTrue(r.html().replace("<b>", "").replace("</b>", "").contains(s), s);
            for (String d : DISCLOSURES) assertTrue(r.html().contains(d) && r.text().contains(d), d);
        }
        assertTrue(hidden.html().contains("<p style=\"margin:0 0 20px;font-size:16px;line-height:1.5;color:#dbeafe;text-align:left;\">" + LATE_BODY_73 + " </p>"), hidden.html());
        assertTrue(hidden.html().contains("starting at <b style=\"color:#ffffff;\">73</b>."));
        assertTrue(lever.html().contains("Freedom Date landed at <b style=\"color:#ffffff;\">65</b>."), "the lever rung's own date, not the lever's");
        assertFalse(lever.html().contains("2 fewer"));

        String[] lines = hidden.text().split("\n", -1);
        assertEquals("Here's the math FRED is built around.", lines[1]);
        assertEquals("", lines[2]);
        assertEquals("Your date is on the board. Dates move.", lines[3]);
        assertEquals(LATE_BODY_73.replace("<b style=\"color:#ffffff;\">", "").replace("</b>", ""), lines[4]);
        assertEquals("$500/mo from age 42 -> a projected $4,000/mo starting at 73.", lines[5]);
        assertTrue(lines[6].startsWith("$183k in. A projected $1.2M out."));
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
            // The one image is the recipient's chart on this API (a token, nothing else); no other remote image.
            assertEquals(count(r.html(), "src=\"http"), count(r.html(), "src=\"" + CHART + "\""), "no image but the chart");
            assertFalse(r.html().contains("@example.com") || r.text().contains("@example.com"));
        }
    }

    @Test
    void fallback_hasTheNoDateHeading_noChart_andNoPersonalLines() {
        EmailTemplates.Rendered r = fallback();
        assertTrue(r.html().contains("Put your financial future on autopilot."));
        assertEquals(0, count(r.html(), "/api/email/chart/"));
        assertFalse(r.html().contains("<img"));
        assertFalse(r.html().contains("fewer working years"));
        assertFalse(r.html().contains("Assumes a"));
        assertFalse(r.html().contains("Compounding does the rest"));
        assertFalse(r.html().contains("/mo from age"));
        // The preheader loses its first sentence; the second stays.
        assertTrue(r.html().contains("color:#f1f5f9;\">" + PREHEADER_TAIL), r.html());
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
    void aProjectionNeedsAChartUrl_andTheUrlIsEscaped() {
        assertThrows(IllegalArgumentException.class, () -> PersonalizedWelcomeEmail.render(Fixtures.projection("sample"), null, UNSUB, ADDR));
        assertThrows(IllegalArgumentException.class, () -> PersonalizedWelcomeEmail.render(Fixtures.projection("sample"), " ", UNSUB, ADDR));
        EmailTemplates.Rendered r = PersonalizedWelcomeEmail.render(Fixtures.projection("sample"), "https://x/c/a&b<c>.png", UNSUB, ADDR);
        assertTrue(r.html().contains("src=\"https://x/c/a&amp;b&lt;c&gt;.png\""));
    }

    // A mail app's notification snippet (Zoho, 2026-11-15) skipped the display:none preheader and
    // ran the body's blocks together ("FREDYou're on FRED's private beta waitlist.Here's the math").
    // The preheader is hidden without display:none and padded past any snippet's length, and
    // every text block ends in a space that rendering collapses and a tag-stripper keeps.
    @Test
    void thePreheaderIsHiddenWithoutDisplayNone_andEveryTextBlockEndsInASpace() {
        for (EmailTemplates.Rendered r : List.of(sample(), fallback(),
                PersonalizedWelcomeEmail.render(Fixtures.projection("stake-hidden"), CHART, UNSUB, ADDR))) {
            String html = r.html();
            assertFalse(html.contains("display:none"), "no display:none anywhere");
            int preheader = html.indexOf("<div aria-hidden=\"true\" style=\"max-height:0;max-width:0;overflow:hidden;mso-hide:all;opacity:0;font-size:1px;line-height:1px;color:#f1f5f9;\">");
            assertTrue(preheader > 0 && preheader < html.indexOf("<table"), "the preheader is the first thing in the body");
            assertTrue(html.contains(PREHEADER_TAIL));
            // Tags stripped and inter-tag whitespace dropped (the snippet engine's reading), the visible
            // text still has a space between every two blocks.
            String stripped = html.substring(html.indexOf("<body")).replaceAll(">\\s+<", "><").replaceAll("<[^>]+>", "").replace("&zwnj;", "").replace("&nbsp;", " ").replaceAll(" +", " ");
            assertTrue(stripped.contains("FRED You're on FRED's private beta waitlist. Here's the math FRED is built around. "), stripped);
            assertTrue(stripped.contains("One-time offer. Invites go out in waves."), stripped);
            assertTrue(stripped.contains("next person in line. Nothing to do now. Watch your inbox. No payment until launch."), stripped);
        }
        String sample = sample().html().substring(sample().html().indexOf("<body")).replaceAll(">\\s+<", "><").replaceAll("<[^>]+>", "").replace("&zwnj;", "").replace("&nbsp;", " ").replaceAll(" +", " ");
        assertTrue(sample.contains("could look like: $2,000/mo from age 30 "), sample);
        assertTrue(sample.contains("starting at 53. $564k in. A projected $2.25M out. Compounding does the rest. Assumes a 10%"), sample);
    }

    @Test
    void postalAddress_andUnsubscribeUrl_areEscaped() {
        EmailTemplates.Rendered r = PersonalizedWelcomeEmail.render(Fixtures.projection("sample"), "https://x/c/<t>.png", "https://x/u?token=a&b=<c>", "<b>x</b> & co");
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
