package com.fredvested.web.email;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders the personalised welcome email's growth chart as a PNG, in process
 * (plain Java2D, no library, no headless browser, nothing fetched). The chart
 * draws exactly the series the page sent: the portfolio line starts at the
 * submitted starting balance and climbs at the submitted rate.
 *
 * API CONTRACT (implemented by the chart stage; the sending code builds against it):
 *   render(p)      -> the PNG bytes, 1040 x 520 (2x of 520 x 260) on #1d4ed8, no border;
 *                     if that exceeds MAX_BYTES the chart is re-rendered at 1.5x (780 x 390).
 *   tickAges(c, f) -> the x-axis tick ages: currentAge, then every 5 years, then freedomAge.
 *   gridStep(t)    -> the y-axis gridline step in dollars for a portfolio target:
 *                     $100k under $1M, $0.5M up to $5M, $1M over $5M.
 *
 * Look (Andrew's render of 2026-10-27): #1d4ed8 edge to edge, no border, no title.
 * Portfolio line white 2.6 px with a white fill fading out under it, contributions
 * line #dbeafe dashed 1.4 px, white gridlines at 12% alpha with #dbeafe labels on
 * the left, age ticks and a "your age" caption under the plot, a white dot with a
 * blue centre at the end of the portfolio line with the target label bold white
 * above-left of it and the "you put in" label at the end of the dashed line.
 * Text is Inter from the bundled TTFs, falling back to the logical SansSerif.
 */
public final class GrowthChartRenderer {

    public static final String FILENAME = "growth-chart.png";
    public static final String CONTENT_TYPE = "image/png";
    public static final int MAX_BYTES = 150 * 1024;
    public static final int BASE_WIDTH = 520;
    public static final int BASE_HEIGHT = 260;
    /** The largest value the chart will draw (the validator's own ceiling); anything above is refused. */
    static final double MAX_VALUE = 100_000_000d;
    /** The gridline step is doubled until the data fits in this many lines. */
    static final int MAX_GRIDLINES = 64;

    static {
        System.setProperty("java.awt.headless", "true");
    }

    private static final Color BACKGROUND = new Color(0x1d4ed8);
    private static final Color WHITE = Color.WHITE;
    private static final Color LIGHT = new Color(0xdbeafe);
    private static final Color GRID = new Color(255, 255, 255, Math.round(255 * 0.12f));
    private static final Color FILL_TOP = new Color(255, 255, 255, Math.round(255 * 0.25f));
    private static final Color FILL_BOTTOM = new Color(255, 255, 255, 0);

    // Margins at 1x (logical pixels).
    private static final double MARGIN_LEFT = 56;
    private static final double MARGIN_RIGHT = 20;
    private static final double MARGIN_TOP = 30;
    private static final double MARGIN_BOTTOM = 36;

    private static final double PORTFOLIO_STROKE = 2.6;
    private static final double CONTRIBUTIONS_STROKE = 1.4;
    private static final double DOT_RADIUS = 5;
    private static final double DOT_CENTRE_RADIUS = 2.5;
    private static final float LABEL_SIZE = 11f;
    private static final float END_LABEL_SIZE = 13f;

    private static final Font REGULAR = loadFont("/fonts/Inter-Regular.ttf", Font.PLAIN);
    private static final Font BOLD = loadFont("/fonts/Inter-Bold.ttf", Font.BOLD);

    private GrowthChartRenderer() {}

    public record Png(byte[] bytes, int width, int height, double scale) {}

    public static Png render(Projection p) {
        Png full = renderAt(p, 2.0);
        if (full.bytes().length <= MAX_BYTES) return full;
        return renderAt(p, 1.5);
    }

    public static List<Integer> tickAges(int currentAge, double freedomAge) {
        List<Integer> ticks = new ArrayList<>();
        ticks.add(currentAge);
        for (int age = currentAge + 5; age <= freedomAge - 1.0; age += 5) {
            ticks.add(age);
        }
        int last = (int) Math.round(freedomAge);
        if (last > ticks.get(ticks.size() - 1)) ticks.add(last);
        return ticks;
    }

    public static long gridStep(long portfolioTarget) {
        if (portfolioTarget < 1_000_000L) return 100_000L;
        if (portfolioTarget > 5_000_000L) return 1_000_000L;
        return 500_000L;
    }

    // ---- layout ---------------------------------------------------------------

    /** The plot's pixel geometry at one scale; package-private so the tests can find the end point. */
    static final class Layout {
        final double scale;
        final int width;
        final int height;
        final double x0, x1, yTop, yBottom;
        final double ageMin, ageMax, valueMax;
        // The highest value either line reaches: gridlines stop there (Andrew's render has no line
        // above the end dot), while the domain still runs to the next step for headroom.
        final double valueTop;
        /** Gridlines above $0, counted: the loops never depend on the data's magnitude. */
        final int lines;
        final long step;

        Layout(Projection p, double scale) {
            this.scale = scale;
            this.width = (int) Math.round(BASE_WIDTH * scale);
            this.height = (int) Math.round(BASE_HEIGHT * scale);
            this.x0 = MARGIN_LEFT * scale;
            this.x1 = width - MARGIN_RIGHT * scale;
            this.yTop = MARGIN_TOP * scale;
            this.yBottom = height - MARGIN_BOTTOM * scale;
            this.ageMin = p.currentAge();
            double maxAge = Double.isFinite(p.freedomAge()) ? p.freedomAge() : ageMin;
            this.ageMax = maxAge > ageMin ? maxAge : ageMin + 1.0;
            long base = gridStep(p.portfolioTarget());
            double max = 0;
            for (Projection.Point pt : safe(p.series())) max = Math.max(max, finite(pt.value()));
            for (Projection.Point pt : safe(p.contributions())) max = Math.max(max, finite(pt.value()));
            // The renderer is safe on its own, whatever the row holds: a value that is not finite,
            // negative, or beyond what MAX_GRIDLINES steps can cover is refused (the chart endpoint
            // then answers a bare 404), so the gridline loops below are always short and counted.
            if (!Double.isFinite(max) || max < 0 || max > MAX_VALUE) throw new IllegalArgumentException("growth chart: value out of range");
            long widened = base;
            while (max / widened > MAX_GRIDLINES) widened *= 2;
            this.step = widened;
            this.lines = (int) Math.ceil(max / widened);
            long top = (long) lines * widened;
            this.valueMax = top > 0 ? top : widened;
            this.valueTop = max;
        }

        private static double finite(double v) {
            if (!Double.isFinite(v)) throw new IllegalArgumentException("growth chart: value out of range");
            return v;
        }

        double x(double age) {
            double t = (age - ageMin) / (ageMax - ageMin);
            return x0 + Math.max(0, Math.min(1, t)) * (x1 - x0);
        }

        double y(double value) {
            double t = Double.isFinite(value) ? value / valueMax : 0;
            return yBottom - Math.max(0, Math.min(1, t)) * (yBottom - yTop);
        }
    }

    /** The pixel of the last series point at the given scale: {x, y}. */
    static double[] endPixel(Projection p, double scale) {
        Layout l = new Layout(p, scale);
        List<Projection.Point> series = safe(p.series());
        if (series.isEmpty()) return new double[] {l.x(l.ageMin), l.y(0)};
        Projection.Point last = series.get(series.size() - 1);
        return new double[] {l.x(last.age()), l.y(last.value())};
    }

    // ---- drawing --------------------------------------------------------------

    private static Png renderAt(Projection p, double scale) {
        Layout l = new Layout(p, scale);
        BufferedImage image = new BufferedImage(l.width, l.height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);

            g.setColor(BACKGROUND);
            g.fillRect(0, 0, l.width, l.height);

            List<Projection.Point> series = safe(p.series());
            List<Projection.Point> contributions = safe(p.contributions());

            drawGrid(g, l);
            drawFill(g, l, series);
            drawContributions(g, l, contributions, p);
            drawPortfolio(g, l, series);
            drawAxes(g, l, p);
            drawEndMarker(g, l, series, p);
        } finally {
            g.dispose();
        }
        return new Png(encode(image), l.width, l.height, scale);
    }

    private static void drawGrid(Graphics2D g, Layout l) {
        g.setColor(GRID);
        g.setStroke(new BasicStroke((float) (1.0 * l.scale)));
        for (int i = 0; i <= l.lines; i++) {
            long v = i * l.step;
            if (v > l.valueTop) break;
            double y = l.y(v);
            g.draw(new Line2D.Double(l.x0, y, l.x1, y));
        }
        g.setColor(LIGHT);
        g.setFont(REGULAR.deriveFont((float) (LABEL_SIZE * l.scale)));
        FontMetrics fm = g.getFontMetrics();
        for (int i = 1; i <= l.lines; i++) {
            long v = i * l.step;
            if (v > l.valueTop) break;
            String label = EmailFormats.axis(v, l.step);
            double y = l.y(v);
            float tx = (float) (l.x0 - 8 * l.scale - fm.stringWidth(label));
            float ty = (float) (y + (fm.getAscent() - fm.getDescent()) / 2.0);
            g.drawString(label, tx, ty);
        }
    }

    private static GeneralPath linePath(Layout l, List<Projection.Point> points) {
        GeneralPath path = new GeneralPath();
        boolean started = false;
        for (Projection.Point pt : points) {
            if (!Double.isFinite(pt.age())) continue;
            double x = l.x(pt.age());
            double y = l.y(pt.value());
            if (!started) {
                path.moveTo(x, y);
                started = true;
            } else {
                path.lineTo(x, y);
            }
        }
        return started ? path : null;
    }

    private static void drawFill(Graphics2D g, Layout l, List<Projection.Point> series) {
        GeneralPath line = linePath(l, series);
        if (line == null || series.size() < 2) return;
        GeneralPath area = new GeneralPath(line);
        Projection.Point first = series.get(0);
        Projection.Point last = series.get(series.size() - 1);
        area.lineTo(l.x(last.age()), l.yBottom);
        area.lineTo(l.x(first.age()), l.yBottom);
        area.closePath();
        double top = l.yBottom;
        for (Projection.Point pt : series) top = Math.min(top, l.y(pt.value()));
        if (l.yBottom - top < 1) return;
        g.setPaint(new GradientPaint(0f, (float) top, FILL_TOP, 0f, (float) l.yBottom, FILL_BOTTOM));
        g.fill(area);
    }

    private static void drawContributions(Graphics2D g, Layout l, List<Projection.Point> contributions, Projection p) {
        GeneralPath line = linePath(l, contributions);
        if (line == null) return;
        float w = (float) (CONTRIBUTIONS_STROKE * l.scale);
        float[] dash = {(float) (5 * l.scale), (float) (4 * l.scale)};
        g.setColor(LIGHT);
        g.setStroke(new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, dash, 0f));
        g.draw(line);
    }

    private static void drawPortfolio(Graphics2D g, Layout l, List<Projection.Point> series) {
        GeneralPath line = linePath(l, series);
        if (line == null) return;
        g.setColor(WHITE);
        g.setStroke(new BasicStroke((float) (PORTFOLIO_STROKE * l.scale), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(line);
    }

    private static void drawAxes(Graphics2D g, Layout l, Projection p) {
        g.setColor(LIGHT);
        g.setFont(REGULAR.deriveFont((float) (LABEL_SIZE * l.scale)));
        FontMetrics fm = g.getFontMetrics();
        List<Integer> ticks = tickAges(p.currentAge(), p.freedomAge());
        float ty = (float) (l.yBottom + 14 * l.scale);
        for (int i = 0; i < ticks.size(); i++) {
            boolean last = i == ticks.size() - 1 && ticks.size() > 1;
            double age = last ? p.freedomAge() : ticks.get(i);
            String label = Integer.toString(ticks.get(i));
            double x = l.x(age);
            // On a long horizon one year is fewer pixels than a label is wide: the five-year tick
            // just before the freedom age is left unlabelled when the two would overprint.
            if (!last && i == ticks.size() - 2 && overlapsLast(l, fm, label, x, ticks.get(ticks.size() - 1), p.freedomAge())) continue;
            float tx = (float) (x - fm.stringWidth(label) / 2.0);
            g.drawString(label, tx, ty);
        }
        String caption = "your age";
        float cx = (float) ((l.x0 + l.x1) / 2.0 - fm.stringWidth(caption) / 2.0);
        g.drawString(caption, cx, (float) (l.yBottom + 29 * l.scale));
    }

    /** Whether a tick label centred at x would run into the freedom-age label (package-private for the tests). */
    static boolean overlapsLast(Layout l, FontMetrics fm, String label, double x, int lastTick, double freedomAge) {
        double gap = l.x(freedomAge) - x;
        double needed = (fm.stringWidth(label) + fm.stringWidth(Integer.toString(lastTick))) / 2.0 + 6 * l.scale;
        return gap < needed;
    }

    /** The tick ages whose labels are drawn at the given scale (the rule's ticks minus an overprinting one). */
    static List<Integer> drawnTickAges(Projection p, double scale) {
        Layout l = new Layout(p, scale);
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = probe.createGraphics();
        try {
            g.setFont(REGULAR.deriveFont((float) (LABEL_SIZE * scale)));
            FontMetrics fm = g.getFontMetrics();
            List<Integer> ticks = tickAges(p.currentAge(), p.freedomAge());
            List<Integer> drawn = new ArrayList<>();
            for (int i = 0; i < ticks.size(); i++) {
                boolean last = i == ticks.size() - 1 && ticks.size() > 1;
                if (!last && i == ticks.size() - 2 && overlapsLast(l, fm, Integer.toString(ticks.get(i)), l.x(ticks.get(i)), ticks.get(ticks.size() - 1), p.freedomAge())) continue;
                drawn.add(ticks.get(i));
            }
            return drawn;
        } finally {
            g.dispose();
        }
    }

    private static void drawEndMarker(Graphics2D g, Layout l, List<Projection.Point> series, Projection p) {
        if (series.isEmpty()) return;
        Projection.Point last = series.get(series.size() - 1);
        double ex = l.x(last.age());
        double ey = l.y(last.value());

        // Contributions label: just above the end of the dashed line, or below it when
        // it would collide with the portfolio label (short horizons).
        List<Projection.Point> contributions = safe(p.contributions());
        g.setFont(REGULAR.deriveFont((float) (LABEL_SIZE * l.scale)));
        FontMetrics rfm = g.getFontMetrics();
        if (!contributions.isEmpty()) {
            Projection.Point c = contributions.get(contributions.size() - 1);
            double cx = l.x(c.age());
            double cy = l.y(c.value());
            String label = EmailFormats.compact(p.totalContributed()) + " you put in";
            float right = (float) (cx - 6 * l.scale);
            float tx = (float) Math.max(l.x0, right - rfm.stringWidth(label));
            float above = (float) (cy - 7 * l.scale);
            float top = above - rfm.getAscent();
            boolean collides = cy - ey < 30 * l.scale || lineCrosses(l, series, tx, right, top - 2 * l.scale);
            float ty = above;
            if (collides) {
                // Below the dashed line instead, under its lowest point across the label's width
                // (the left edge, since contributions only grow), so a steep pair of lines cannot
                // run through the text either.
                double dashedAtLeft = lineYAt(l, contributions, tx);
                double under = Double.isNaN(dashedAtLeft) ? cy : Math.max(cy, dashedAtLeft);
                ty = (float) Math.min(l.yBottom - 2 * l.scale, under + 6 * l.scale + rfm.getAscent());
            }
            g.setColor(LIGHT);
            g.drawString(label, tx, ty);
        }

        // Portfolio label: bold white, above-left of the end dot.
        g.setFont(BOLD.deriveFont((float) (END_LABEL_SIZE * l.scale)));
        FontMetrics bfm = g.getFontMetrics();
        String target = EmailFormats.compact(p.portfolioTarget());
        float tx = (float) Math.max(l.x0, ex - 8 * l.scale - bfm.stringWidth(target));
        float ty = (float) Math.max(bfm.getAscent(), ey - 11 * l.scale);
        g.setColor(WHITE);
        g.drawString(target, tx, ty);

        // End marker last: white dot with a blue centre.
        double r = DOT_RADIUS * l.scale;
        double rc = DOT_CENTRE_RADIUS * l.scale;
        g.setColor(WHITE);
        g.fill(new Ellipse2D.Double(ex - r, ey - r, 2 * r, 2 * r));
        g.setColor(BACKGROUND);
        g.fill(new Ellipse2D.Double(ex - rc, ey - rc, 2 * rc, 2 * rc));
    }

    /** True when the portfolio line dips below {@code top} anywhere over [left, right] (it would run through a label there). */
    private static boolean lineCrosses(Layout l, List<Projection.Point> series, double left, double right, double top) {
        if (series.size() < 2 || right <= left) return false;
        int samples = 12;
        for (int i = 0; i <= samples; i++) {
            double px = left + (right - left) * i / samples;
            double y = lineYAt(l, series, px);
            if (!Double.isNaN(y) && y > top) return true;
        }
        return false;
    }

    /** A line's pixel y at pixel x, by linear interpolation between its yearly points; NaN outside the line. */
    private static double lineYAt(Layout l, List<Projection.Point> points, double px) {
        double prevX = Double.NaN, prevY = Double.NaN;
        for (Projection.Point pt : points) {
            if (!Double.isFinite(pt.age())) continue;
            double x = l.x(pt.age());
            double y = l.y(pt.value());
            if (!Double.isNaN(prevX) && px >= prevX && px <= x) {
                double span = x - prevX;
                double t = span > 0 ? (px - prevX) / span : 0;
                return prevY + t * (y - prevY);
            }
            prevX = x;
            prevY = y;
        }
        return Double.NaN;
    }

    // ---- helpers --------------------------------------------------------------

    private static List<Projection.Point> safe(List<Projection.Point> points) {
        if (points == null) return List.of();
        List<Projection.Point> out = new ArrayList<>(points.size());
        for (Projection.Point pt : points) if (pt != null) out.add(pt);
        return out;
    }

    private static byte[] encode(BufferedImage image) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            if (!ImageIO.write(image, "png", out)) throw new IOException("no PNG writer");
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("growth chart PNG encoding failed", e);
        }
    }

    private static Font loadFont(String resource, int fallbackStyle) {
        try (InputStream in = GrowthChartRenderer.class.getResourceAsStream(resource)) {
            if (in != null) {
                return Font.createFont(Font.TRUETYPE_FONT, in).deriveFont(LABEL_SIZE);
            }
        } catch (IOException | FontFormatException | RuntimeException ignored) {
            // fall through to the logical face
        }
        return new Font(Font.SANS_SERIF, fallbackStyle, Math.round(LABEL_SIZE));
    }
}
