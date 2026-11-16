package com.fredvested.web.email;

import com.fredvested.web.service.EmailTemplates;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The v2 welcome email ("You're in"): email/welcome-personalized.html filled with
 * the recipient's own projection, or its no-projection fallback. Every sentence is
 * the template's (frozen, pending counsel); only the data-field values change.
 * The chart is an image URL on this API (EmailChartController: an unguessable token,
 * nothing else), drawn on request from the same stored projection; it was a cid
 * attachment until 2026-11-15, which some mail apps never resolve. The header's wordmark is
 * the navbar logo as an image on this API too (EmailWordmarkController, one static picture).
 */
public final class PersonalizedWelcomeEmail {

    private PersonalizedWelcomeEmail() {}

    public static final String TEMPLATE = "email/welcome-personalized.html";

    /** The data-projection parts that exist only with a projection. */
    static final List<String> PROJECTION_PARTS = List.of("preheader", "headline", "headline-late", "body-late", "inputs", "chart", "summary", "assumptions");
    static final String FALLBACK_PART = "headline-fallback";
    /** The parts that carry the stake number: shown only for the plan rung of the stakes ladder. */
    static final List<String> STAKE_PARTS = List.of("preheader", "headline");
    /** The 65-or-later variant's parts (Andrew's copy, 2026-10-28): the heading and the paragraph under it. */
    static final List<String> LATE_PARTS = List.of("headline-late", "body-late");
    /** A rounded Freedom Date at this age or later gets the "Dates move." heading and paragraph. */
    static final int LATE_DATE_AGE = 65;

    /** The stake headline needs a plural: the page shows a one-year gap since 2026-10-29, the template says "years". */
    static final int STAKE_HEADLINE_MIN_YEARS = 2;

    /**
     * Only the plan rung's number is printed, and only from two years ("N fewer working years" is
     * frozen in the plural; a one-year gap gets the no-date heading like lever and hidden do).
     */
    static boolean hasStakeHeadline(Projection p) {
        return p != null && !hasLateDate(p) && p.stakeSource() == Projection.StakeSource.PLAN && p.stakeYears() >= STAKE_HEADLINE_MIN_YEARS;
    }

    /**
     * A Freedom Date at 65 or later: "N fewer working years" has no number to print (the plan
     * rung cannot exist there) and the lever rung's number belongs to another monthly amount,
     * so the card leads with "Your date is on the board. Dates move." and the paragraph that
     * names the date, above the same inputs line, chart, summary and assumptions.
     */
    static boolean hasLateDate(Projection p) {
        return p != null && p.roundedFreedomAge() >= LATE_DATE_AGE;
    }

    private static final class Holder {
        static final String HTML = EmailTemplateEngine.load(TEMPLATE);
        static final String SUBJECT = EmailTemplateEngine.title(HTML);
    }

    // The sample numbers the template's alt text carries; each is swapped for the recipient's.
    private static final Pattern ALT_TOKENS = Pattern.compile("\\$2\\.25M|age 30|age 53|\\$560k");

    /**
     * @param p        the recipient's projection, or null for the no-chart fallback
     * @param chartUrl    the URL of this recipient's chart image (ignored without a projection)
     * @param wordmarkUrl the URL of the header wordmark image (EmailWordmarkController.url)
     */
    public static EmailTemplates.Rendered render(Projection p, String chartUrl, String wordmarkUrl, String unsubscribeUrl, String postalAddress) {
        return new EmailTemplates.Rendered(Holder.SUBJECT, html(p, chartUrl, wordmarkUrl, unsubscribeUrl, postalAddress), text(p, unsubscribeUrl, postalAddress));
    }

    static String html(Projection p, String chartUrl, String wordmarkUrl, String unsubscribeUrl, String postalAddress) {
        if (wordmarkUrl == null || wordmarkUrl.isBlank()) throw new IllegalArgumentException("the header wordmark needs a URL");
        String html = EmailTemplateEngine.stripLeadingComment(Holder.HTML);
        html = EmailTemplateEngine.setSrc(html, "wordmark_url", wordmarkUrl);
        if (p != null) {
            if (chartUrl == null || chartUrl.isBlank()) throw new IllegalArgumentException("a projection needs a chart URL");
            html = EmailTemplateEngine.setSrc(html, "chart_url", chartUrl);
            // The stake headline is the plan rung's own number ("N fewer working years"). The lever
            // rung's number belongs to a higher monthly amount the template cannot name, and the hidden
            // rung has nothing to show (the page hides its comparison too), so both keep the frozen
            // no-date heading while the inputs line, the chart, the summary and the assumptions stay.
            if (hasStakeHeadline(p)) {
                html = EmailTemplateEngine.removeProjection(html, FALLBACK_PART);
                for (String part : LATE_PARTS) html = EmailTemplateEngine.removeProjection(html, part);
                html = EmailTemplateEngine.fillText(html, "stake_years", String.valueOf(p.stakeYears()));
            } else if (hasLateDate(p)) {
                html = EmailTemplateEngine.removeProjection(html, FALLBACK_PART);
                for (String part : STAKE_PARTS) html = EmailTemplateEngine.removeProjection(html, part);
            } else {
                for (String part : STAKE_PARTS) html = EmailTemplateEngine.removeProjection(html, part);
                for (String part : LATE_PARTS) html = EmailTemplateEngine.removeProjection(html, part);
            }
            html = EmailTemplateEngine.fillText(html, "monthly_invest", EmailFormats.money(p.monthlyInvest()));
            html = EmailTemplateEngine.fillText(html, "current_age", String.valueOf(p.currentAge()));
            html = EmailTemplateEngine.fillText(html, "target_income", EmailFormats.money(p.targetIncome()));
            html = EmailTemplateEngine.fillText(html, "freedom_age", String.valueOf(p.roundedFreedomAge()));
            html = EmailTemplateEngine.fillText(html, "total_contributed", EmailFormats.compact(p.totalContributed()));
            html = EmailTemplateEngine.fillText(html, "portfolio_target", EmailFormats.compact(p.portfolioTarget()));
            html = EmailTemplateEngine.fillText(html, "return_rate", EmailFormats.percent(p.returnRate()));
            String alt = EmailTemplateEngine.attribute(html, "chart_url", "alt");
            html = EmailTemplateEngine.setAlt(html, "chart_url", alt(alt, p));
        } else {
            for (String part : PROJECTION_PARTS) html = EmailTemplateEngine.removeProjection(html, part);
        }
        html = EmailTemplateEngine.fillText(html, "postal_address", postalAddress == null ? "" : postalAddress);
        html = EmailTemplateEngine.setHref(html, "unsubscribe_url", unsubscribeUrl);
        return EmailTemplateEngine.stripMarkers(html);
    }

    /** The template's sample alt text with the recipient's four numbers substituted in one pass. */
    static String alt(String sample, Projection p) {
        Matcher m = ALT_TOKENS.matcher(sample);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String token = m.group();
            String value = switch (token) {
                case "$2.25M" -> EmailFormats.compact(p.portfolioTarget());
                case "age 30" -> "age " + p.currentAge();
                case "age 53" -> "age " + p.roundedFreedomAge();
                default -> EmailFormats.compact(p.totalContributed());
            };
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    static String text(Projection p, String unsubscribeUrl, String postalAddress) {
        String personal;
        String finePrint;
        if (p != null) {
            personal = heading(p)
                    + EmailFormats.money(p.monthlyInvest()) + "/mo from age " + p.currentAge() + " -> a projected "
                    + EmailFormats.money(p.targetIncome()) + "/mo starting at " + p.roundedFreedomAge() + ".\n"
                    + EmailFormats.compact(p.totalContributed()) + " in. A projected " + EmailFormats.compact(p.portfolioTarget())
                    + " out. Compounding does the rest.\n";
            finePrint = "Assumes a " + EmailFormats.percent(p.returnRate()) + " avg annual return & 4% withdrawal rate. " + DISCLOSURE;
        } else {
            personal = "Put your financial future on autopilot.\n";
            finePrint = DISCLOSURE;
        }
        return "You're on FRED's private beta waitlist.\n"
                + "Here's the math FRED is built around.\n"
                + "\n"
                + personal
                + finePrint + "\n"
                + "\n"
                + "300 spots. 48 hours. One-time offer.\n"
                + "Invites go out in waves. When yours lands, you have 48 hours to claim it. After that, it goes to the next person in line.\n"
                + "\n"
                + "Kick your hooves up. FRED will be oinking in your inbox when it's time.\n"
                + "No payment until launch.\n"
                + "Private beta is iOS only.\n"
                + "\n"
                + "The FRED Team\n"
                + "help@fredvested.com\n"
                + "\n"
                + "This email reflects the numbers you entered on fredvested.com. FRED's calculator is an educational planning tool. It does not provide investment advice or recommend any investment.\n"
                + "\n"
                + EmailTemplates.DEVELOPMENT_DISCLAIMER + "\n"
                + "\n"
                + "FREDvested LLC, " + (postalAddress == null ? "" : postalAddress) + "\n"
                + "Unsubscribe: " + unsubscribeUrl + "\n"
                + "Privacy policy: " + EmailTemplates.PRIVACY_URL + "\n";
    }

    /** The text part's heading line(s), mirroring the HTML card's headline (and the late date's paragraph). */
    static String heading(Projection p) {
        if (hasStakeHeadline(p)) return p.stakeYears() + " fewer working years could look like:\n";
        if (hasLateDate(p)) return LATE_HEADLINE + "\n" + LATE_BODY.formatted(p.roundedFreedomAge()) + "\n";
        return "Put your financial future on autopilot.\n";
    }

    static final String LATE_HEADLINE = "Your date is on the board. Dates move.";
    static final String LATE_BODY = "Based on what you entered, your projected Freedom Date landed at %d. A first projection is a starting point, not a verdict. Two things move a date like this in the math: how much goes in each month, and how long compounding gets to run. The calculator stays open, and the sliders are the point: test different monthly amounts and watch what the projection does. When FRED opens, the automation handles the showing-up part, which is the part most people lose years to.";

    static final String DISCLOSURE = "Hypothetical illustration based on the assumptions shown, not a prediction or guarantee. Actual returns may vary. Investing involves risk, including possible loss of principal.";
}
