package com.fredvested.web.share;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A shared calculator result: the rounded freedom age and the month and year of the
 * Freedom Date, and nothing else. It is what a share link carries ({@code r=52-2048-07})
 * and what the two share images draw.
 *
 * THE VALIDATION RULE is implemented three times and pinned by the same test vectors
 * (here, frontend/assets/share.js and frontend/_worker.js); change all three together.
 * {@code r} is valid at instant {@code now} (UTC) iff:
 *   1. it matches {@code ^(\d{2,3})-(\d{4})-(\d{2})$} giving age, year, month;
 *   2. 18 <= age <= 100 and 1 <= month <= 12;
 *   3. monthsOut = (year*12 + month-1) - (nowYear*12 + nowMonth-1) is within 0..864;
 *   4. 17 <= age - monthsOut/12 <= 81 (the age slider is 18 to 80; one year of tolerance).
 * Anyone can type a link, so this keeps FRED from drawing an image the calculator could
 * never produce.
 */
public record ShareResult(int age, int year, int month) {

    static final int MIN_AGE = 18;
    static final int MAX_AGE = 100;
    static final int MAX_MONTHS_OUT = 864;
    static final int MIN_IMPLIED_AGE = 17;
    static final int MAX_IMPLIED_AGE = 81;
    /** The usual retirement age the page compares against. */
    static final int USUAL_AGE = 65;

    private static final Pattern AGE = Pattern.compile("(\\d{2,3})");
    private static final Pattern YEAR_MONTH = Pattern.compile("(\\d{4})-(\\d{2})");
    private static final Pattern PARAM = Pattern.compile("(\\d{2,3})-(\\d{4}-\\d{2})");

    private static final String[] MONTHS = {
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December"};

    /** The two path segments of the image URLs: {@code 52} and {@code 2048-07}. */
    public static Optional<ShareResult> parse(String age, String ym, Clock clock) {
        if (age == null || ym == null || clock == null) return Optional.empty();
        Matcher a = AGE.matcher(age);
        Matcher d = YEAR_MONTH.matcher(ym);
        // matches(), not find(): the whole string, so a trailing newline or suffix is refused.
        if (!a.matches() || !d.matches()) return Optional.empty();
        int ageValue = Integer.parseInt(a.group(1));
        int year = Integer.parseInt(d.group(1));
        int month = Integer.parseInt(d.group(2));
        if (ageValue < MIN_AGE || ageValue > MAX_AGE || month < 1 || month > 12) return Optional.empty();

        YearMonth now = YearMonth.now(clock.withZone(ZoneOffset.UTC));
        int monthsOut = (year * 12 + month - 1) - (now.getYear() * 12 + now.getMonthValue() - 1);
        if (monthsOut < 0 || monthsOut > MAX_MONTHS_OUT) return Optional.empty();
        // age - monthsOut/12 within 17..81, in whole months so no rounding is involved.
        int impliedMonths = ageValue * 12 - monthsOut;
        if (impliedMonths < MIN_IMPLIED_AGE * 12 || impliedMonths > MAX_IMPLIED_AGE * 12) return Optional.empty();
        return Optional.of(new ShareResult(ageValue, year, month));
    }

    /** The share link's {@code r} parameter: {@code 52-2048-07}. */
    public static Optional<ShareResult> parseParam(String r, Clock clock) {
        if (r == null) return Optional.empty();
        Matcher m = PARAM.matcher(r);
        if (!m.matches()) return Optional.empty();
        return parse(m.group(1), m.group(2), clock);
    }

    public String monthName() {
        return MONTHS[month - 1];
    }

    /** {@code July 2048}. */
    public String dateLabel() {
        return monthName() + " " + year;
    }

    /** At 65 or later: the 65-and-later copy applies (no "years before" line, "No date" on the left). */
    public boolean late() {
        return age >= USUAL_AGE;
    }

    /** 65 minus the age; zero or negative when {@link #late()}. */
    public int gapYears() {
        return USUAL_AGE - age;
    }
}
