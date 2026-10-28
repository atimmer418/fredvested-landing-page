package com.fredvested.web.email;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * The number formats of the personalised welcome email and its chart. en-US, no
 * decimals on whole-dollar amounts, and a compact form for the big figures:
 *   under $1,000      -> "$950"
 *   under $1,000,000  -> "$564k"  (whole thousands)
 *   under $10,000,000 -> "$2.25M" (up to two decimals, trailing zeros dropped: "$1.2M", "$2M")
 *   from $10,000,000  -> "$12M"   (no decimals)
 * The two-decimal rule follows Andrew's render of 2026-10-27 ("$2.25M" for the
 * $2,250,000 target): one decimal would print the exact page figure as "$2.3M".
 */
public final class EmailFormats {

    private EmailFormats() {}

    /** "$2,000" */
    public static String money(long dollars) {
        return "$" + NumberFormat.getIntegerInstance(Locale.US).format(dollars);
    }

    /** "$564k", "$2.25M" */
    public static String compact(long dollars) {
        long abs = Math.abs(dollars);
        if (abs < 1_000) return money(dollars);
        if (abs < 1_000_000) return "$" + Math.round(dollars / 1_000.0) + "k";
        if (abs < 10_000_000) return "$" + trim(BigDecimal.valueOf(dollars).divide(BigDecimal.valueOf(1_000_000), 2, RoundingMode.HALF_UP)) + "M";
        return "$" + Math.round(dollars / 1_000_000.0) + "M";
    }

    /**
     * Y-axis labels, in the unit of the gridline step: "$0.5M", "$1M", "$1.5M" for
     * half-million and million steps (as the render shows), "$100k" for the hundred
     * thousand steps of a target under $1M. Zero prints as "$0".
     */
    public static String axis(long dollars, long step) {
        if (dollars == 0) return "$0";
        if (step >= 500_000) return "$" + trim(BigDecimal.valueOf(dollars).divide(BigDecimal.valueOf(1_000_000), 1, RoundingMode.HALF_UP)) + "M";
        return "$" + Math.round(dollars / 1_000.0) + "k";
    }

    /** "10%" */
    public static String percent(int pct) {
        return pct + "%";
    }

    private static String trim(BigDecimal d) {
        return d.stripTrailingZeros().toPlainString();
    }
}
