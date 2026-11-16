package com.fredvested.web.email;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.LineMetrics;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * The "FRED" wordmark in the v2 emails' header: the landing page's navbar wordmark
 * (Montserrat Black Italic in the brand blue, {@code font-montserrat font-black italic
 * text-primary} on about.html and the legal pages), drawn once from the bundled TTF as a
 * transparent PNG at 3x for retina screens and served by {@link EmailWordmarkController}.
 * No mail client has Montserrat and none fetches a web font from an email, so the header
 * was a system-font "FRED" until 2026-11-16 (Andrew: the proper logo, the navbar's); an
 * image is the same face in every client, with a styled "FRED" as the alt text for the
 * clients that show no images. One picture serves light and dark mode: the brand blue
 * reads on the white card and on the dark one (#151b2b).
 *
 * The picture is a CSS line box, not the ink: {@link #LINE_HEIGHT} CSS pixels tall with the
 * glyphs on the baseline where {@code line-height: 1} would put them, so the header keeps
 * exactly the height the text wordmark gave it. The width is the advance plus the italic
 * overhang and {@link #SIDE_PAD} on each side. Deterministic: the bytes never change for
 * the same JDK, and the templates' width/height attributes equal {@link Png#width()} /
 * {@link Png#height()} divided by {@link #SCALE} (EmailWordmarkControllerTest holds them
 * together).
 */
public final class WordmarkRenderer {

    private WordmarkRenderer() {}

    static final String TEXT = "FRED";
    static final String FONT_RESOURCE = "/fonts/Montserrat-BlackItalic.ttf";
    /** The landing page's --color-primary. */
    static final Color INK = new Color(0x13, 0x5b, 0xec);
    /** CSS pixels: the font size and line box the header's text wordmark had, and the side padding. */
    static final int FONT_SIZE = 40;
    static final int LINE_HEIGHT = 40;
    static final int SIDE_PAD = 2;
    /** Device pixels per CSS pixel. */
    static final int SCALE = 3;

    /** The encoded PNG and its size in device pixels. */
    public record Png(byte[] bytes, int width, int height) {
        /** The width attribute the template carries (CSS pixels). */
        public int cssWidth() { return width / SCALE; }
        public int cssHeight() { return height / SCALE; }
    }

    private static final class Holder {
        static final Png PNG = draw();
    }

    /** The wordmark, drawn on first use and kept. */
    public static Png render() {
        return Holder.PNG;
    }

    static Png draw() {
        Font face = loadFont().deriveFont(Map.of(
                TextAttribute.SIZE, (float) (FONT_SIZE * SCALE),
                TextAttribute.KERNING, TextAttribute.KERNING_ON));
        FontRenderContext frc = new FontRenderContext(null, RenderingHints.VALUE_TEXT_ANTIALIAS_ON, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        TextLayout layout = new TextLayout(TEXT, face, frc);
        Rectangle2D ink = layout.getBounds();
        double left = Math.min(0, ink.getMinX());
        double right = Math.max(layout.getAdvance(), ink.getMaxX());
        int cssWidth = (int) Math.ceil((right - left) / SCALE) + 2 * SIDE_PAD;
        int width = cssWidth * SCALE;
        int height = LINE_HEIGHT * SCALE;
        LineMetrics m = face.getLineMetrics(TEXT, frc);
        float baseline = (float) ((height - (m.getAscent() + m.getDescent())) / 2 + m.getAscent());
        float x = (float) (SIDE_PAD * SCALE - left);

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setColor(INK);
            layout.draw(g, x, baseline);
        } finally {
            g.dispose();
        }
        return new Png(encode(image), width, height);
    }

    /** The bundled face, or an exception: an Arial wordmark must never go out quietly. */
    private static Font loadFont() {
        try (InputStream in = WordmarkRenderer.class.getResourceAsStream(FONT_RESOURCE)) {
            if (in == null) throw new IllegalStateException("wordmark font missing: " + FONT_RESOURCE);
            return Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (IOException | FontFormatException e) {
            throw new IllegalStateException("wordmark font unreadable: " + FONT_RESOURCE, e);
        }
    }

    private static byte[] encode(BufferedImage image) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024);
            if (!ImageIO.write(image, "png", out)) throw new IOException("no PNG writer");
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("wordmark PNG encoding failed", e);
        }
    }
}
