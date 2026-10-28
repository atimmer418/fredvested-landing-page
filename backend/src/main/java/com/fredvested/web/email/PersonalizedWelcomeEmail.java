package com.fredvested.web.email;

import com.fredvested.web.service.EmailTemplates;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The v2 welcome email ("You're in"): email/welcome-personalized.html filled with
 * the recipient's own projection, or its no-projection fallback. Every sentence is
 * the template's (frozen, pending counsel); only the data-field values change.
 * The chart is an inline attachment referenced as cid:growth-chart, never a
 * hosted image, so the sent HTML contains no URL to it.
 */
public final class PersonalizedWelcomeEmail {

    private PersonalizedWelcomeEmail() {}

    public static final String TEMPLATE = "email/welcome-personalized.html";
    public static final String CID_REFERENCE = "cid:" + GrowthChartRenderer.CONTENT_ID;

    /** The data-projection parts that exist only with a projection. */
    static final List<String> PROJECTION_PARTS = List.of("preheader", "headline", "inputs", "chart", "summary", "assumptions");
    static final String FALLBACK_PART = "headline-fallback";
    /** The parts that carry the stake number: shown only for the plan rung of the stakes ladder. */
    static final List<String> STAKE_PARTS = List.of("preheader", "headline");

    /** Only the plan rung's number is printed; lever and hidden get the frozen no-date heading. */
    static boolean hasStakeHeadline(Projection p) {
        return p != null && p.stakeSource() == Projection.StakeSource.PLAN;
    }

    private static final class Holder {
        static final String HTML = EmailTemplateEngine.load(TEMPLATE);
        static final String SUBJECT = EmailTemplateEngine.title(HTML);
    }

    // The sample numbers the template's alt text carries; each is swapped for the recipient's.
    private static final Pattern ALT_TOKENS = Pattern.compile("\\$2\\.25M|age 30|age 53|\\$560k");

    public static EmailTemplates.Rendered render(Projection p, String unsubscribeUrl, String postalAddress) {
        return new EmailTemplates.Rendered(Holder.SUBJECT, html(p, unsubscribeUrl, postalAddress), text(p, unsubscribeUrl, postalAddress));
    }

    static String html(Projection p, String unsubscribeUrl, String postalAddress) {
        String html = EmailTemplateEngine.stripLeadingComment(Holder.HTML);
        if (p != null) {
            // The stake headline is the plan rung's own number ("N fewer working years"). The lever
            // rung's number belongs to a higher monthly amount the template cannot name, and the hidden
            // rung has nothing to show (the page hides its comparison too), so both keep the frozen
            // no-date heading while the inputs line, the chart, the summary and the assumptions stay.
            if (hasStakeHeadline(p)) {
                html = EmailTemplateEngine.removeProjection(html, FALLBACK_PART);
                html = EmailTemplateEngine.fillText(html, "stake_years", String.valueOf(p.stakeYears()));
            } else {
                for (String part : STAKE_PARTS) html = EmailTemplateEngine.removeProjection(html, part);
            }
            html = EmailTemplateEngine.fillText(html, "monthly_invest", EmailFormats.money(p.monthlyInvest()));
            html = EmailTemplateEngine.fillText(html, "current_age", String.valueOf(p.currentAge()));
            html = EmailTemplateEngine.fillText(html, "target_income", EmailFormats.money(p.targetIncome()));
            html = EmailTemplateEngine.fillText(html, "freedom_age", String.valueOf(p.roundedFreedomAge()));
            html = EmailTemplateEngine.fillText(html, "total_contributed", EmailFormats.compact(p.totalContributed()));
            html = EmailTemplateEngine.fillText(html, "portfolio_target", EmailFormats.compact(p.portfolioTarget()));
            html = EmailTemplateEngine.fillText(html, "return_rate", EmailFormats.percent(p.returnRate()));
            html = EmailTemplateEngine.fillText(html, "scenario", p.scenario().label());
            String alt = EmailTemplateEngine.attribute(html, "chart_cid", "alt");
            html = EmailTemplateEngine.setAlt(html, "chart_cid", alt(alt, p));
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
            personal = (hasStakeHeadline(p) ? p.stakeYears() + " fewer working years could look like:\n" : "Put your financial future on autopilot.\n")
                    + EmailFormats.money(p.monthlyInvest()) + "/mo from age " + p.currentAge() + " -> a projected "
                    + EmailFormats.money(p.targetIncome()) + "/mo starting at " + p.roundedFreedomAge() + ".\n"
                    + EmailFormats.compact(p.totalContributed()) + " in. A projected " + EmailFormats.compact(p.portfolioTarget())
                    + " out. Compounding does the rest.\n";
            finePrint = "Assumes a " + EmailFormats.percent(p.returnRate()) + " avg annual return (" + p.scenario().label()
                    + " scenario) & 4% withdrawal rate. " + DISCLOSURE;
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
                + "Nothing to do now. Watch your inbox.\n"
                + "No payment until launch. Private beta is iOS only.\n"
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

    static final String DISCLOSURE = "Hypothetical illustration based on the assumptions shown, not a prediction or guarantee. Actual returns may vary. Investing involves risk, including possible loss of principal.";
}
