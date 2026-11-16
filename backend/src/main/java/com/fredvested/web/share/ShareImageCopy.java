package com.fredvested.web.share;

/**
 * ATTORNEY REVIEW PENDING 2026-11-14.
 * Every word the two share images carry (the link preview image and the story image).
 * Nothing in ShareImageRenderer spells out copy of its own; change wording here only.
 *
 * A change here changes the picture behind URLs that may already have been fetched: both
 * images are served immutable for a year and message apps keep previews per URL. Once any
 * image has been requested from an environment, give the new wording new URLs by bumping the
 * path version (v1) in three places together: ShareImageController's two mappings,
 * IMAGE_PATH in frontend/assets/share.js and the image URL in frontend/_worker.js
 * (tests/unit/share.test.js fails when they disagree).
 */
public final class ShareImageCopy {

    private ShareImageCopy() {}

    // Both images.
    public static final String KICKER = "I COULD BE FREE AT";
    public static final String DISCLOSURE = "Hypothetical illustration, not a prediction or guarantee.";

    // Link image (1200 x 630).
    public static final String UNIT = "years old in";
    public static final String BUBBLE = "What about you?";

    // Story image (1080 x 1920).
    public static final String TICKET_HEADING = "FREEDOM DATE";
    public static final String WORDMARK = "FRED";
    public static final String DO_NOTHING_LABEL = "Do nothing.";
    public static final String DO_NOTHING_VALUE = "65+";
    /** The left cell's value at 65 and later, as on the page's comparison. */
    public static final String NO_DATE = "No date";
    /**
     * Waiting on counsel (Andrew, 2026-11-14): may become "On the FRED plan."
     * This is the only place the backend spells it; the renderer measures whatever is here.
     * See the class comment: with the wording, bump the image path version.
     */
    public static final String ROUTE_LABEL = "On the FRED plan.";
    public static final String YEARS_OLD = "years old";
    public static final String STORY_QUESTION = "What’s your Freedom Date?";
    public static final String DOMAIN = "fredvested.com";

    private static final String GAP_ONE = "1 year before the usual 65";
    private static final String GAP_MANY = "%d years before the usual 65";
    private static final String GAP_LATE = "Hope isn’t a plan. %s is.";

    /** The line under the route row: the years before 65, or the page's own late stakes line. */
    public static String gapLine(ShareResult r) {
        if (r.late()) return String.format(java.util.Locale.ROOT, GAP_LATE, r.dateLabel());
        int n = r.gapYears();
        return n == 1 ? GAP_ONE : String.format(java.util.Locale.ROOT, GAP_MANY, n);
    }
}
