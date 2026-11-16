package com.fredvested.web.share;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.LineMetrics;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Draws the two "Share my Freedom Date" images as PNGs, in process (plain Java2D like
 * GrowthChartRenderer: no library, no headless browser, nothing fetched).
 *
 *   link(r)  -> 1200 x 630, the link preview image (og:image): the kicker, the age, "years old in",
 *               the month and year and the disclosure centred within the left 620px; the
 *               "What about you?" bubble with its tail above FRED the presenter on the right.
 *               References: docs/superpowers/specs/2026-11-13-share-freedom-date/reference-*.png
 *               (a satori render: this matches it by eye, not by pixel).
 *   story(r) -> 1080 x 1920, the story image: the upright paper ticket with the month and year
 *               stacked, the route row, the gap line, the disclosure and the domain; below the
 *               perforation a navy stub with the kicker, the age and FRED in a round stamp; the
 *               question and the domain under the ticket. Layout source: story-and-sheet-mockup.html
 *               in the same folder (its `.story` rules; U below is its --u, 1.6% of the width) and
 *               panel 4 of counsel-board.png. The top and bottom bands stay empty for the Stories
 *               interface.
 *
 * Every word comes from ShareImageCopy; every text is measured and shrunk to its slot if it
 * would not fit, so a longer label (counsel may change the route label) cannot clip or overlap.
 * Vertical positions follow the CSS line-box model (a line box of a given height with the font's
 * ascent and descent centred in it), which is why the numbers below read like the stylesheet.
 *
 * Faces: Montserrat Black / Black Italic and Inter Regular / Medium / Bold from the bundled TTFs.
 * The mockup's Montserrat 800 and Inter 600 are drawn in Black and Medium (not bundled).
 */
public final class ShareImageRenderer {

    public static final int LINK_WIDTH = 1200;
    public static final int LINK_HEIGHT = 630;
    public static final int STORY_WIDTH = 1080;
    public static final int STORY_HEIGHT = 1920;

    static {
        System.setProperty("java.awt.headless", "true");
    }

    // The result card's gradient: linear-gradient(160deg, #1e60e3 0%, #0e3a96 100%).
    private static final Color GRADIENT_FROM = new Color(0x1e60e3);
    private static final Color GRADIENT_TO = new Color(0x0e3a96);
    private static final double GRADIENT_DEGREES = 160;

    private static final Color WHITE = Color.WHITE;
    private static final Color WHITE_85 = new Color(255, 255, 255, 217);
    private static final Color WHITE_80 = new Color(255, 255, 255, 204);
    private static final Color WHITE_60 = new Color(255, 255, 255, 153);
    private static final Color WHITE_40 = new Color(255, 255, 255, 102);
    private static final Color WHITE_35 = new Color(255, 255, 255, 89);
    private static final Color BUBBLE_TEXT = new Color(0x1e60e3);
    private static final Color PRIMARY = new Color(0x135bec);
    private static final Color INK = new Color(0x0F172A);
    private static final Color MUTED = new Color(0x5B6B80);
    private static final Color PAPER = new Color(0xf6f6f8);
    private static final Color RULE = new Color(0xdfe3ea);
    private static final Color DOTS = new Color(0x9aa8bd);
    private static final Color STUB_KICKER = new Color(0xe3edff);
    private static final Color STUB_SOFT = new Color(0xdbe7ff);
    private static final Color SHADOW = new Color(5, 20, 60, 115);

    /** The presenter's box on the page and in the mockups: viewBox 95 132 660 586. */
    private static final double PIG_ASPECT = 660.0 / 586.0;

    private static final boolean[] MISSING = new boolean[1];
    private static final Font MONTSERRAT_BLACK = loadFont("/fonts/Montserrat-Black.ttf", Font.BOLD);
    private static final Font MONTSERRAT_BLACK_ITALIC = loadFont("/fonts/Montserrat-BlackItalic.ttf", Font.BOLD | Font.ITALIC);
    private static final Font INTER_REGULAR = loadFont("/fonts/Inter-Regular.ttf", Font.PLAIN);
    private static final Font INTER_MEDIUM = loadFont("/fonts/Inter-Medium.ttf", Font.PLAIN);
    private static final Font INTER_BOLD = loadFont("/fonts/Inter-Bold.ttf", Font.BOLD);
    private static final BufferedImage PIG = loadImage("/share/fred-presenter.png");

    // ---- link image geometry (the spec's table, 1200 x 630) ------------------------------------
    private static final double LINK_COLUMN_WIDTH = 620;
    private static final double LINK_COLUMN_MAX_TEXT = 560;
    private static final double LINK_KICKER_TOP = 64;
    private static final double LINK_KICKER_SIZE = 30;
    private static final double LINK_KICKER_TRACKING_PX = 5;
    private static final double LINK_AGE_GAP = 10;
    private static final double LINK_AGE_SIZE = 280;
    private static final double LINK_AGE_LINE_HEIGHT = 0.95;
    /** The reference render sets Montserrat a touch tighter than the static face's own advances. */
    private static final double LINK_MONTSERRAT_TRACKING = -0.023;
    /**
     * The age is the exception: at 280px that tightening made 23, 25, 44 and 72 touch, so it keeps
     * the face's own spacing ("52" is about 334px wide against the reference's 328).
     */
    private static final double LINK_AGE_TRACKING = 0;
    private static final double LINK_UNIT_GAP = 6;
    private static final double LINK_UNIT_SIZE = 44;
    private static final double LINK_DATE_GAP = 2;
    private static final double LINK_DATE_SIZE = 60;
    private static final double LINK_DISCLOSURE_SIZE = 21;
    private static final double LINK_DISCLOSURE_BOTTOM = 40;
    private static final double BUBBLE_TOP = 56;
    private static final double BUBBLE_RIGHT = 96;
    private static final double BUBBLE_SIZE = 46;
    private static final double BUBBLE_PAD_X = 32;
    private static final double BUBBLE_PAD_Y = 13;
    private static final double BUBBLE_RADIUS = 23;
    private static final double BUBBLE_TAIL_FROM_RIGHT = 164;
    private static final double BUBBLE_TAIL_HALF_WIDTH = 19;
    private static final double BUBBLE_TAIL_DROP = 20;
    private static final double LINK_PIG_HEIGHT = 370;
    private static final double LINK_PIG_RIGHT = 84;
    private static final double LINK_PIG_BOTTOM = 58;

    // ---- story image geometry (the mockup's --u is 1.6% of the width) --------------------------
    private static final double U = STORY_WIDTH * 0.016;
    private static final double TICKET_WIDTH = STORY_WIDTH * 0.76;
    private static final double TICKET_RADIUS = U * 2.2;
    private static final double NOTCH_RADIUS = U * 1.6;
    private static final double PAPER_PAD = U * 3.2;
    private static final double PAPER_GAP = U * 2.4;
    private static final double HEADING_SIZE = U * 1.5;
    private static final double HEADING_TRACKING = 0.16;
    private static final double WORDMARK_SIZE = U * 3.3;
    private static final double WORDMARK_TRACKING = -0.01;
    private static final double MONTH_SIZE = U * 4.8;
    private static final double MONTH_BOX = U * 5;
    private static final double MONTH_TRACKING = 0.06;
    private static final double YEAR_SIZE = U * 13.5;
    private static final double YEAR_LINE_HEIGHT = 0.86;
    /**
     * The mockup sets the year, the route values and the stub age with negative letter-spacing
     * (-.04em, -.02em, -.03em) AND tabular figures, whose digits all sit in a 0.70em cell. Java2D
     * draws the face's proportional digits (it cannot switch `tnum` on), where a 4 is 0.71em wide
     * and already close to its neighbour: with the mockup's spacing 48, 44, 23, 72 and others ran
     * into each other. So the year and the stub age are set at the face's own spacing ("2048" is
     * then about 627px wide against the mockup's 612) and the small route values a touch wider,
     * which leaves a clear pixel between 2 and 3 or 7 and 2 at that size. ShareImageRendererTest
     * holds every year and every age apart; change these only with that test green.
     */
    private static final double YEAR_TRACKING = 0;
    private static final double ROUTE_LABEL_SIZE = U * 1.45;
    private static final double ROUTE_VALUE_SIZE = U * 3.6;
    private static final double ROUTE_VALUE_LINE_HEIGHT = 1.1;
    private static final double ROUTE_VALUE_TRACKING = 0.02;
    private static final double ROUTE_GAP = U * 1.4;
    private static final double ROUTE_DOT = U * 0.3;
    private static final double ROUTE_ARROW_ARM = U * 1.3;
    private static final double ROUTE_ARROW_STROKE = U * 0.35;
    private static final double ROUTE_ARROW_OVERHANG = U * 0.47;
    /** The most of the row the two cells may take, so the dotted arrow always has room. */
    private static final double ROUTE_CELL_MAX = 0.4;
    private static final double GAP_LINE_SIZE = U * 2.15;
    private static final double FOOT_SIZE = U * 1.5;
    private static final double FOOT_LINE_HEIGHT = 1.35;
    private static final double FOOT_PAD_TOP = U * 1.1;
    private static final double FOOT_RULE = U * 0.15;
    private static final double TEXT_LINE_HEIGHT = 1.4;
    private static final double STUB_PAD_Y = U * 2.6;
    private static final double PERFORATION = U * 0.3;
    private static final double STUB_KICKER_SIZE = U * 1.35;
    private static final double STUB_KICKER_TRACKING = 0.14;
    private static final double STUB_AGE_SIZE = U * 10.5;
    private static final double STUB_AGE_LINE_HEIGHT = 0.95;
    private static final double STUB_AGE_TRACKING = 0;
    private static final double STUB_AGE_TOP = U * 0.6;
    private static final double STUB_UNIT_SIZE = U * 1.9;
    private static final double STAMP_DIAMETER = U * 17;
    private static final double STAMP_BORDER = U * 0.35;
    private static final double STAMP_RING_INNER = U * 0.55;
    private static final double STAMP_RING_OUTER = U * 0.8;
    /** Upright (Andrew, 2026-11-15); the mockup turned the stamp, and FRED with it, by -8 degrees. */
    private static final double STAMP_DEGREES = 0;
    /** FRED's box inside the stamp, as a share of its diameter (centred; the corners stay inside the dashes). */
    private static final double STAMP_PIG = 0.74;
    /**
     * Where the drawing's visual weight sits in the pig PNG, as a share of its width and height (the
     * alpha-weighted centroid: the chart and the cash pull it left and up of the box's middle). This
     * point, not the box's middle, goes on the stamp's centre (Andrew, 2026-11-15: centring the box
     * left FRED visibly off to the left).
     */
    private static final double STAMP_PIG_CENTRE_X = 0.44;
    private static final double STAMP_PIG_CENTRE_Y = 0.46;
    private static final double SHADOW_DROP = U * 1.2;
    private static final double SHADOW_SIGMA = U;
    private static final double TAG_GAP = STORY_WIDTH * 0.06;
    private static final double QUESTION_SIZE = STORY_WIDTH * 0.052;
    private static final double QUESTION_BOX = STORY_WIDTH * 0.054 * 1.2;
    private static final double DOMAIN_GAP = STORY_WIDTH * 0.014;
    private static final double DOMAIN_SIZE = STORY_WIDTH * 0.036;
    private static final double TAG_MAX_WIDTH = STORY_WIDTH - 2 * 64;
    /** The paper's and the stub's text width, and the slots the big numbers are fitted to. */
    private static final double STORY_INNER = TICKET_WIDTH - 2 * PAPER_PAD;
    private static final double ROUTE_CELL_WIDTH = STORY_INNER * ROUTE_CELL_MAX;
    private static final double STUB_COLUMN_WIDTH = STORY_INNER - STAMP_DIAMETER - STAMP_RING_OUTER - 2 * U;

    private static final BufferedImage LINK_PIG = PIG == null ? null
            : scaled(PIG, (int) Math.round(LINK_PIG_HEIGHT * PIG_ASPECT), (int) Math.round(LINK_PIG_HEIGHT));
    private static final BufferedImage STAMP_PIG_IMAGE = PIG == null ? null
            : scaled(PIG, (int) Math.round(STAMP_DIAMETER * STAMP_PIG), (int) Math.round(STAMP_DIAMETER * STAMP_PIG / PIG_ASPECT));

    private ShareImageRenderer() {}

    /** True when every bundled face and the pig were found on the classpath (the tests hold this). */
    static boolean assetsLoaded() {
        return !MISSING[0];
    }

    // ---- link image ---------------------------------------------------------------------------

    public static byte[] link(ShareResult r) {
        BufferedImage image = new BufferedImage(LINK_WIDTH, LINK_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            hints(g);
            background(g, LINK_WIDTH, LINK_HEIGHT);

            double cx = LINK_COLUMN_WIDTH / 2;
            double y = LINK_KICKER_TOP;

            Text kicker = text(g, ShareImageCopy.KICKER, INTER_BOLD, LINK_KICKER_SIZE, LINK_KICKER_TRACKING_PX / LINK_KICKER_SIZE, LINK_COLUMN_MAX_TEXT);
            double kickerBox = naturalLineHeight(g, INTER_BOLD, LINK_KICKER_SIZE);
            g.setColor(WHITE_85);
            kicker.drawCentred(g, cx, baseline(g, INTER_BOLD, LINK_KICKER_SIZE, y, kickerBox));
            y += kickerBox + LINK_AGE_GAP;

            Text age = linkAgeText(g, r.age());
            double ageBox = LINK_AGE_SIZE * LINK_AGE_LINE_HEIGHT;
            g.setColor(WHITE);
            age.drawCentred(g, cx, baseline(g, MONTSERRAT_BLACK, LINK_AGE_SIZE, y, ageBox));
            y += ageBox + LINK_UNIT_GAP;

            Text unit = text(g, ShareImageCopy.UNIT, INTER_MEDIUM, LINK_UNIT_SIZE, 0, LINK_COLUMN_MAX_TEXT);
            double unitBox = naturalLineHeight(g, INTER_MEDIUM, LINK_UNIT_SIZE);
            unit.drawCentred(g, cx, baseline(g, INTER_MEDIUM, LINK_UNIT_SIZE, y, unitBox));
            y += unitBox + LINK_DATE_GAP;

            Text date = text(g, r.dateLabel(), INTER_BOLD, LINK_DATE_SIZE, 0, LINK_COLUMN_MAX_TEXT);
            double dateBox = naturalLineHeight(g, INTER_BOLD, LINK_DATE_SIZE);
            date.drawCentred(g, cx, baseline(g, INTER_BOLD, LINK_DATE_SIZE, y, dateBox));

            Text disclosure = text(g, ShareImageCopy.DISCLOSURE, INTER_MEDIUM, LINK_DISCLOSURE_SIZE, 0, LINK_COLUMN_MAX_TEXT + 20);
            double disclosureBox = naturalLineHeight(g, INTER_MEDIUM, LINK_DISCLOSURE_SIZE);
            g.setColor(WHITE_80);
            disclosure.drawCentred(g, cx, baseline(g, INTER_MEDIUM, LINK_DISCLOSURE_SIZE,
                    LINK_HEIGHT - LINK_DISCLOSURE_BOTTOM - disclosureBox, disclosureBox));

            if (LINK_PIG != null) {
                int px = (int) Math.round(LINK_WIDTH - LINK_PIG_RIGHT - LINK_PIG.getWidth());
                int py = (int) Math.round(LINK_HEIGHT - LINK_PIG_BOTTOM - LINK_PIG.getHeight());
                g.drawImage(LINK_PIG, px, py, null);
            }
            bubble(g);
        } finally {
            g.dispose();
        }
        return encode(image);
    }

    /** The white speech bubble, its right edge fixed, with a small tail pointing down at FRED. */
    private static void bubble(Graphics2D g) {
        double right = LINK_WIDTH - BUBBLE_RIGHT;
        double maxText = right - LINK_COLUMN_WIDTH - 2 * BUBBLE_PAD_X + 20;
        Text label = text(g, ShareImageCopy.BUBBLE, MONTSERRAT_BLACK, BUBBLE_SIZE, LINK_MONTSERRAT_TRACKING, maxText);
        double lineBox = naturalLineHeight(g, MONTSERRAT_BLACK, BUBBLE_SIZE);
        double w = label.width + 2 * BUBBLE_PAD_X;
        double h = lineBox + 2 * BUBBLE_PAD_Y;
        double left = right - w;
        double bottom = BUBBLE_TOP + h;

        Area shape = new Area(new RoundRectangle2D.Double(left, BUBBLE_TOP, w, h, 2 * BUBBLE_RADIUS, 2 * BUBBLE_RADIUS));
        double tail = Math.max(left + BUBBLE_RADIUS + BUBBLE_TAIL_HALF_WIDTH, right - BUBBLE_TAIL_FROM_RIGHT);
        Path2D.Double tip = new Path2D.Double();
        tip.moveTo(tail - BUBBLE_TAIL_HALF_WIDTH, bottom - 1);
        tip.lineTo(tail + BUBBLE_TAIL_HALF_WIDTH, bottom - 1);
        tip.lineTo(tail + 3, bottom + BUBBLE_TAIL_DROP - 3);
        tip.quadTo(tail, bottom + BUBBLE_TAIL_DROP, tail - 3, bottom + BUBBLE_TAIL_DROP - 3);
        tip.closePath();
        shape.add(new Area(tip));
        g.setColor(WHITE);
        g.fill(shape);

        g.setColor(BUBBLE_TEXT);
        label.drawCentred(g, left + w / 2, baseline(g, MONTSERRAT_BLACK, BUBBLE_SIZE, BUBBLE_TOP + BUBBLE_PAD_Y, lineBox));
    }

    // ---- story image --------------------------------------------------------------------------

    public static byte[] story(ShareResult r) {
        BufferedImage image = new BufferedImage(STORY_WIDTH, STORY_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            hints(g);
            background(g, STORY_WIDTH, STORY_HEIGHT);

            // Heights first: the ticket and the two lines under it are centred as one block.
            double headBox = WORDMARK_SIZE;
            double yearBox = YEAR_SIZE * YEAR_LINE_HEIGHT;
            double routeLabelBox = ROUTE_LABEL_SIZE * TEXT_LINE_HEIGHT;
            double routeValueBox = ROUTE_VALUE_SIZE * ROUTE_VALUE_LINE_HEIGHT;
            double gapBox = GAP_LINE_SIZE * TEXT_LINE_HEIGHT;
            double footLine = FOOT_SIZE * FOOT_LINE_HEIGHT;
            double footBox = FOOT_RULE + FOOT_PAD_TOP + 2 * footLine;
            double paperHeight = 2 * PAPER_PAD + headBox + PAPER_GAP + MONTH_BOX + yearBox + PAPER_GAP
                    + routeLabelBox + routeValueBox + PAPER_GAP + gapBox + PAPER_GAP + footBox;
            double stubHeight = 2 * STUB_PAD_Y + STAMP_DIAMETER;
            double domainBox = DOMAIN_SIZE * 1.2;
            double total = paperHeight + stubHeight + TAG_GAP + QUESTION_BOX + DOMAIN_GAP + domainBox;

            double left = (STORY_WIDTH - TICKET_WIDTH) / 2;
            double right = left + TICKET_WIDTH;
            double top = (STORY_HEIGHT - total) / 2;
            double tear = top + paperHeight;
            double x0 = left + PAPER_PAD;
            double x1 = right - PAPER_PAD;
            double inner = x1 - x0;

            // The ticket: one rounded slab with a notch bitten out of each side at the perforation.
            Area ticket = new Area(new RoundRectangle2D.Double(left, top, TICKET_WIDTH, paperHeight + stubHeight, 2 * TICKET_RADIUS, 2 * TICKET_RADIUS));
            ticket.subtract(new Area(circle(left, tear, NOTCH_RADIUS)));
            ticket.subtract(new Area(circle(right, tear, NOTCH_RADIUS)));
            shadow(g, ticket);
            g.setColor(INK);
            g.fill(ticket);
            Area paper = new Area(ticket);
            paper.intersect(new Area(new Rectangle2D.Double(left, top, TICKET_WIDTH, paperHeight)));
            g.setColor(PAPER);
            g.fill(paper);
            g.setColor(WHITE_40);
            g.setStroke(new BasicStroke((float) PERFORATION, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
                    new float[] {(float) (PERFORATION * 2.4), (float) (PERFORATION * 1.8)}, 0f));
            g.draw(new Line2D.Double(left + NOTCH_RADIUS + PERFORATION, tear + PERFORATION / 2, right - NOTCH_RADIUS - PERFORATION, tear + PERFORATION / 2));

            // Paper: heading and the italic wordmark on one baseline.
            double y = top + PAPER_PAD;
            double headBaseline = baseline(g, MONTSERRAT_BLACK_ITALIC, WORDMARK_SIZE, y, headBox);
            Text wordmark = text(g, ShareImageCopy.WORDMARK, MONTSERRAT_BLACK_ITALIC, WORDMARK_SIZE, WORDMARK_TRACKING, inner * 0.3);
            g.setColor(PRIMARY);
            wordmark.draw(g, x1 - wordmark.width, headBaseline);
            Text heading = text(g, ShareImageCopy.TICKET_HEADING, INTER_BOLD, HEADING_SIZE, HEADING_TRACKING, inner - wordmark.width - 2 * U);
            g.setColor(MUTED);
            heading.draw(g, x0, headBaseline);
            y += headBox + PAPER_GAP;

            // Month over year.
            Text month = text(g, r.monthName().toUpperCase(java.util.Locale.ROOT), MONTSERRAT_BLACK, MONTH_SIZE, MONTH_TRACKING, inner);
            g.setColor(PRIMARY);
            month.draw(g, x0, baseline(g, MONTSERRAT_BLACK, MONTH_SIZE, y, MONTH_BOX));
            y += MONTH_BOX;
            Text year = storyYearText(g, r.year());
            g.setColor(INK);
            year.draw(g, x0 - YEAR_SIZE * 0.01, baseline(g, MONTSERRAT_BLACK, YEAR_SIZE, y, yearBox));
            y += yearBox + PAPER_GAP;

            // Route row: "Do nothing. 65+" ......> "<route label> 52".
            double cellMax = ROUTE_CELL_WIDTH;
            Text fromLabel = text(g, ShareImageCopy.DO_NOTHING_LABEL, INTER_MEDIUM, ROUTE_LABEL_SIZE, 0, cellMax);
            Text fromValue = storyRouteValueText(g, r.late() ? ShareImageCopy.NO_DATE : ShareImageCopy.DO_NOTHING_VALUE);
            Text toLabel = text(g, ShareImageCopy.ROUTE_LABEL, INTER_MEDIUM, ROUTE_LABEL_SIZE, 0, cellMax);
            Text toValue = storyRouteValueText(g, Integer.toString(r.age()));
            double labelBaseline = baseline(g, INTER_MEDIUM, ROUTE_LABEL_SIZE, y, routeLabelBox);
            double valueBaseline = baseline(g, MONTSERRAT_BLACK, ROUTE_VALUE_SIZE, y + routeLabelBox, routeValueBox);
            double fromWidth = Math.max(fromLabel.width, fromValue.width);
            double toWidth = Math.max(toLabel.width, toValue.width);
            double toX = x1 - toWidth;
            g.setColor(MUTED);
            fromLabel.draw(g, x0, labelBaseline);
            toLabel.draw(g, toX, labelBaseline);
            g.setColor(INK);
            fromValue.draw(g, x0, valueBaseline);
            g.setColor(PRIMARY);
            toValue.draw(g, toX, valueBaseline);
            // The mockup's `.ln` is centred on the whole row (label plus value), not on the number.
            routeArrow(g, x0 + fromWidth + ROUTE_GAP, toX - ROUTE_GAP, y + (routeLabelBox + routeValueBox) / 2);
            y += routeLabelBox + routeValueBox + PAPER_GAP;

            Text gap = text(g, ShareImageCopy.gapLine(r), INTER_BOLD, GAP_LINE_SIZE, 0, inner);
            g.setColor(INK);
            gap.draw(g, x0, baseline(g, INTER_BOLD, GAP_LINE_SIZE, y, gapBox));
            y += gapBox + PAPER_GAP;

            // Foot: a rule, the disclosure, the domain.
            g.setColor(RULE);
            g.fill(new Rectangle2D.Double(x0, y, inner, FOOT_RULE));
            y += FOOT_RULE + FOOT_PAD_TOP;
            Text disclosure = text(g, ShareImageCopy.DISCLOSURE, INTER_REGULAR, FOOT_SIZE, 0, inner);
            g.setColor(MUTED);
            disclosure.draw(g, x0, baseline(g, INTER_REGULAR, FOOT_SIZE, y, footLine));
            Text footDomain = text(g, ShareImageCopy.DOMAIN, INTER_BOLD, FOOT_SIZE, 0, inner);
            g.setColor(INK);
            footDomain.draw(g, x0, baseline(g, INTER_BOLD, FOOT_SIZE, y + footLine, footLine));

            // Stub: kicker, age and "years old" on the left, FRED in the stamp on the right.
            double stubTop = tear + STUB_PAD_Y;
            double kickerBox = STUB_KICKER_SIZE * TEXT_LINE_HEIGHT;
            double ageBox = STUB_AGE_SIZE * STUB_AGE_LINE_HEIGHT;
            double unitBox = STUB_UNIT_SIZE * TEXT_LINE_HEIGHT;
            double columnHeight = kickerBox + STUB_AGE_TOP + ageBox + unitBox;
            double columnWidth = STUB_COLUMN_WIDTH;
            double sy = stubTop + (STAMP_DIAMETER - columnHeight) / 2;
            Text kicker = text(g, ShareImageCopy.KICKER, INTER_BOLD, STUB_KICKER_SIZE, STUB_KICKER_TRACKING, columnWidth);
            g.setColor(STUB_KICKER);
            kicker.draw(g, x0, baseline(g, INTER_BOLD, STUB_KICKER_SIZE, sy, kickerBox));
            sy += kickerBox + STUB_AGE_TOP;
            Text age = storyStubAgeText(g, r.age());
            g.setColor(WHITE);
            age.draw(g, x0 - STUB_AGE_SIZE * 0.02, baseline(g, MONTSERRAT_BLACK, STUB_AGE_SIZE, sy, ageBox));
            sy += ageBox;
            Text unit = text(g, ShareImageCopy.YEARS_OLD, INTER_MEDIUM, STUB_UNIT_SIZE, 0, columnWidth);
            g.setColor(STUB_SOFT);
            unit.draw(g, x0, baseline(g, INTER_MEDIUM, STUB_UNIT_SIZE, sy, unitBox));
            stamp(g, x1 - STAMP_DIAMETER / 2, stubTop + STAMP_DIAMETER / 2);

            // Under the ticket.
            double tagTop = tear + stubHeight + TAG_GAP;
            Text question = text(g, ShareImageCopy.STORY_QUESTION, MONTSERRAT_BLACK, QUESTION_SIZE, -0.01, TAG_MAX_WIDTH);
            g.setColor(WHITE);
            question.drawCentred(g, STORY_WIDTH / 2.0, baseline(g, MONTSERRAT_BLACK, QUESTION_SIZE, tagTop, QUESTION_BOX));
            Text domain = text(g, ShareImageCopy.DOMAIN, INTER_MEDIUM, DOMAIN_SIZE, 0, TAG_MAX_WIDTH);
            g.setColor(STUB_SOFT);
            domain.drawCentred(g, STORY_WIDTH / 2.0, baseline(g, INTER_MEDIUM, DOMAIN_SIZE, tagTop + QUESTION_BOX + DOMAIN_GAP, domainBox));
        } finally {
            g.dispose();
        }
        return encode(image);
    }

    /**
     * A dotted line from x0 to x1 ending in a chevron. As in the mockup (`.ln::after`, a square
     * turned 45 degrees that hangs 0.2u over the line's end), the chevron's outer point is 0.47u
     * past x1, towards the value.
     */
    private static void routeArrow(Graphics2D g, double x0, double x1, double y) {
        double arm = ROUTE_ARROW_ARM / Math.sqrt(2);
        if (x1 - x0 < arm * 2) return;
        // The stroke's mitred point lies half a stroke width times root two beyond the path's corner.
        double tip = x1 + ROUTE_ARROW_OVERHANG - ROUTE_ARROW_STROKE / Math.sqrt(2);
        g.setColor(DOTS);
        g.setStroke(new BasicStroke((float) ROUTE_DOT, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f,
                new float[] {0f, (float) (ROUTE_DOT * 2)}, 0f));
        g.draw(new Line2D.Double(x0 + ROUTE_DOT / 2, y, tip - arm * 0.9, y));
        Path2D.Double head = new Path2D.Double();
        head.moveTo(tip - arm, y - arm);
        head.lineTo(tip, y);
        head.lineTo(tip - arm, y + arm);
        g.setColor(PRIMARY);
        g.setStroke(new BasicStroke((float) ROUTE_ARROW_STROKE, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
        g.draw(head);
    }

    /** The round stamp with FRED centred in it, upright. */
    private static void stamp(Graphics2D g, double cx, double cy) {
        double radius = STAMP_DIAMETER / 2;
        AffineTransform before = g.getTransform();
        Shape clipBefore = g.getClip();
        try {
            g.rotate(Math.toRadians(STAMP_DEGREES), cx, cy);

            g.setColor(WHITE_35);
            double ring = radius + (STAMP_RING_INNER + STAMP_RING_OUTER) / 2;
            g.setStroke(new BasicStroke((float) (STAMP_RING_OUTER - STAMP_RING_INNER)));
            g.draw(circle(cx, cy, ring));

            g.setPaint(new RadialGradientPaint((float) cx, (float) cy, (float) radius, new float[] {0f, 1f}, new Color[] {GRADIENT_FROM, GRADIENT_TO}));
            g.fill(circle(cx, cy, radius));

            // The dashed border sits inside the edge; the dash count is whole so the ring closes evenly.
            double dashRadius = radius - STAMP_BORDER / 2;
            double circumference = 2 * Math.PI * dashRadius;
            long dashes = Math.max(8, Math.round(circumference / (STAMP_BORDER * 4)));
            double period = circumference / dashes;
            g.setColor(WHITE_60);
            g.setStroke(new BasicStroke((float) STAMP_BORDER, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f,
                    new float[] {(float) (period * 0.6), (float) (period * 0.4)}, 0f));
            g.draw(circle(cx, cy, dashRadius));

            if (STAMP_PIG_IMAGE != null) {
                g.clip(circle(cx, cy, radius - STAMP_BORDER));
                g.drawImage(STAMP_PIG_IMAGE,
                        AffineTransform.getTranslateInstance(cx - STAMP_PIG_IMAGE.getWidth() * STAMP_PIG_CENTRE_X,
                                cy - STAMP_PIG_IMAGE.getHeight() * STAMP_PIG_CENTRE_Y), null);
            }
        } finally {
            g.setTransform(before);
            g.setClip(clipBefore);
        }
    }

    /** CSS drop-shadow(0 1.2u 2u rgba(5,20,60,.45)): drawn at quarter size, blurred, and scaled back up. */
    private static void shadow(Graphics2D g, Shape shape) {
        int scale = 4;
        int w = STORY_WIDTH / scale, h = STORY_HEIGHT / scale;
        BufferedImage mask = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D mg = mask.createGraphics();
        try {
            mg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            mg.scale(1.0 / scale, 1.0 / scale);
            mg.translate(0, SHADOW_DROP);
            mg.setColor(SHADOW);
            mg.fill(shape);
        } finally {
            mg.dispose();
        }
        float[] kernel = gaussian(SHADOW_SIGMA / scale);
        BufferedImage pass = new ConvolveOp(new Kernel(kernel.length, 1, kernel), ConvolveOp.EDGE_NO_OP, null).filter(mask, null);
        BufferedImage blurred = new ConvolveOp(new Kernel(1, kernel.length, kernel), ConvolveOp.EDGE_NO_OP, null).filter(pass, null);
        g.drawImage(blurred, 0, 0, STORY_WIDTH, STORY_HEIGHT, null);
    }

    private static float[] gaussian(double sigma) {
        int reach = (int) Math.ceil(sigma * 3);
        float[] k = new float[2 * reach + 1];
        double sum = 0;
        for (int i = -reach; i <= reach; i++) {
            double v = Math.exp(-(i * i) / (2 * sigma * sigma));
            k[i + reach] = (float) v;
            sum += v;
        }
        for (int i = 0; i < k.length; i++) k[i] /= (float) sum;
        return k;
    }

    // ---- the big numbers ----------------------------------------------------------------------
    // One layout each, used by the drawing code above and (as outlines) by ShareImageRendererTest,
    // which holds that no two digits of any value touch.

    private static Text linkAgeText(Graphics2D g, int age) {
        return text(g, Integer.toString(age), MONTSERRAT_BLACK, LINK_AGE_SIZE, LINK_AGE_TRACKING, LINK_COLUMN_MAX_TEXT);
    }

    private static Text storyYearText(Graphics2D g, int year) {
        return text(g, Integer.toString(year), MONTSERRAT_BLACK, YEAR_SIZE, YEAR_TRACKING, STORY_INNER);
    }

    private static Text storyRouteValueText(Graphics2D g, String value) {
        return text(g, value, MONTSERRAT_BLACK, ROUTE_VALUE_SIZE, ROUTE_VALUE_TRACKING, ROUTE_CELL_WIDTH);
    }

    private static Text storyStubAgeText(Graphics2D g, int age) {
        return text(g, Integer.toString(age), MONTSERRAT_BLACK, STUB_AGE_SIZE, STUB_AGE_TRACKING, STUB_COLUMN_WIDTH);
    }

    static Shape linkAgeOutline(int age) {
        return outline(g -> linkAgeText(g, age));
    }

    static Shape storyYearOutline(int year) {
        return outline(g -> storyYearText(g, year));
    }

    static Shape storyRouteValueOutline(int age) {
        return outline(g -> storyRouteValueText(g, Integer.toString(age)));
    }

    static Shape storyStubAgeOutline(int age) {
        return outline(g -> storyStubAgeText(g, age));
    }

    private static Shape outline(java.util.function.Function<Graphics2D, Text> laidOut) {
        Graphics2D g = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics();
        try {
            hints(g);
            return laidOut.apply(g).layout().getOutline(null);
        } finally {
            g.dispose();
        }
    }

    // ---- shared drawing helpers ---------------------------------------------------------------

    private static void hints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    }

    /** The CSS gradient line for an angle: it runs through the centre and reaches the corners at 0% and 100%. */
    private static void background(Graphics2D g, int w, int h) {
        double a = Math.toRadians(GRADIENT_DEGREES);
        double dx = Math.sin(a), dy = -Math.cos(a);
        double half = (Math.abs(w * dx) + Math.abs(h * dy)) / 2;
        double cx = w / 2.0, cy = h / 2.0;
        g.setPaint(new GradientPaint((float) (cx - dx * half), (float) (cy - dy * half), GRADIENT_FROM,
                (float) (cx + dx * half), (float) (cy + dy * half), GRADIENT_TO));
        g.fillRect(0, 0, w, h);
    }

    private static Ellipse2D.Double circle(double cx, double cy, double r) {
        return new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r);
    }

    /** One laid-out line: what to draw and how wide its ink run is (without the trailing tracking). */
    private record Text(TextLayout layout, double width) {
        void draw(Graphics2D g, double x, double baseline) {
            layout.draw(g, (float) x, (float) baseline);
        }

        void drawCentred(Graphics2D g, double cx, double baseline) {
            draw(g, cx - width / 2, baseline);
        }
    }

    /** Lays a line out at the given size, shrinking it until it fits maxWidth (it never grows). */
    private static Text text(Graphics2D g, String s, Font face, double size, double trackingEm, double maxWidth) {
        FontRenderContext frc = g.getFontRenderContext();
        double current = size;
        for (int i = 0; ; i++) {
            TextLayout layout = new TextLayout(s, sized(face, current, trackingEm), frc);
            double width = layout.getAdvance() - trackingEm * current;
            if (width <= maxWidth || i >= 4 || maxWidth <= 0) return new Text(layout, width);
            current *= maxWidth / width * 0.995;
        }
    }

    private static Font sized(Font face, double size, double trackingEm) {
        Map<TextAttribute, Object> attributes = new HashMap<>();
        attributes.put(TextAttribute.SIZE, (float) size);
        attributes.put(TextAttribute.KERNING, TextAttribute.KERNING_ON);
        if (trackingEm != 0) attributes.put(TextAttribute.TRACKING, (float) trackingEm);
        return face.deriveFont(attributes);
    }

    /** CSS `line-height: normal`: the face's ascent plus descent plus line gap. */
    private static double naturalLineHeight(Graphics2D g, Font face, double size) {
        LineMetrics m = face.deriveFont((float) size).getLineMetrics("Hg", g.getFontRenderContext());
        return m.getAscent() + m.getDescent() + m.getLeading();
    }

    /** The baseline of a line box that starts at top and is boxHeight tall, for the face at its nominal size. */
    private static double baseline(Graphics2D g, Font face, double size, double top, double boxHeight) {
        LineMetrics m = face.deriveFont((float) size).getLineMetrics("Hg", g.getFontRenderContext());
        return top + (boxHeight - (m.getAscent() + m.getDescent())) / 2 + m.getAscent();
    }

    /** Area-averaged downscale: halve while more than twice the target, then one bicubic step. */
    private static BufferedImage scaled(BufferedImage source, int w, int h) {
        BufferedImage current = source;
        while (current.getWidth() / 2 >= w && current.getHeight() / 2 >= h) {
            current = resample(current, current.getWidth() / 2, current.getHeight() / 2, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        }
        return resample(current, w, h, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    }

    private static BufferedImage resample(BufferedImage source, int w, int h, Object interpolation) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(source, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static byte[] encode(BufferedImage image) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(256 * 1024);
            if (!ImageIO.write(image, "png", out)) throw new IOException("no PNG writer");
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("share image PNG encoding failed", e);
        }
    }

    private static Font loadFont(String resource, int fallbackStyle) {
        try (InputStream in = ShareImageRenderer.class.getResourceAsStream(resource)) {
            if (in != null) return Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (IOException | FontFormatException | RuntimeException ignored) {
            // fall through to the logical face
        }
        MISSING[0] = true;
        return new Font(Font.SANS_SERIF, fallbackStyle, 12);
    }

    private static BufferedImage loadImage(String resource) {
        try (InputStream in = ShareImageRenderer.class.getResourceAsStream(resource)) {
            if (in != null) {
                BufferedImage image = ImageIO.read(in);
                if (image != null) return image;
            }
        } catch (IOException | RuntimeException ignored) {
            // drawn without FRED rather than not at all
        }
        MISSING[0] = true;
        return null;
    }
}
