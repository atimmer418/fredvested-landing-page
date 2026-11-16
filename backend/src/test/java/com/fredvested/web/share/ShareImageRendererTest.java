package com.fredvested.web.share;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShareImageRendererTest {

    /** Written on every run so the images can be looked at: the visual check is the real acceptance test. */
    private static final Path PREVIEW_DIR = Path.of("build", "share-preview");
    private static final int LINK_MAX_BYTES = 300 * 1024;
    private static final int STORY_MAX_BYTES = 600 * 1024;
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a};

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    // 52 / July 2048 is the reference; September is the longest month; 67 is the 65-and-later copy
    // ("No date", "Hope isn’t a plan. ..."); 100 is the widest age; 64 is the singular gap line.
    @ParameterizedTest
    // 44 / 2044 and 72 / 2072 are digit pairs that once ran into each other (see noTwoDigitsOfABigNumberTouch).
    @CsvSource({"52, 2048, 7", "38, 2041, 9", "67, 2063, 7", "100, 2090, 1", "67, 2063, 9", "64, 2060, 5", "44, 2044, 9", "72, 2072, 3", "23, 2050, 3"})
    void bothImagesAreDecodablePngsOfTheExactSizeAndUnderTheirBudgets(int age, int year, int month) throws IOException {
        ShareResult r = new ShareResult(age, year, month);
        String name = age + "-" + year + "-" + String.format("%02d", month);

        byte[] link = ShareImageRenderer.link(r);
        BufferedImage linkImage = decode(link);
        assertEquals(1200, linkImage.getWidth());
        assertEquals(630, linkImage.getHeight());
        assertTrue(link.length <= LINK_MAX_BYTES, name + " link image is " + link.length + " bytes");

        byte[] story = ShareImageRenderer.story(r);
        BufferedImage storyImage = decode(story);
        assertEquals(1080, storyImage.getWidth());
        assertEquals(1920, storyImage.getHeight());
        assertTrue(story.length <= STORY_MAX_BYTES, name + " story image is " + story.length + " bytes");

        Files.createDirectories(PREVIEW_DIR);
        Files.write(PREVIEW_DIR.resolve("link-" + name + ".png"), link);
        Files.write(PREVIEW_DIR.resolve("story-" + name + ".png"), story);

        // The card's gradient, edge to edge: #1e60e3 in the top-left corner, #0e3a96 in the bottom-right.
        for (BufferedImage image : new BufferedImage[] {linkImage, storyImage}) {
            assertNear(0x1e60e3, image.getRGB(0, 0), name + " top-left");
            assertNear(0x0e3a96, image.getRGB(image.getWidth() - 1, image.getHeight() - 1), name + " bottom-right");
            assertEquals(0xff, image.getRGB(0, 0) >>> 24, "opaque");
        }

        // Story: the top and bottom bands stay empty for the Stories interface (no white, no paper, no navy).
        for (int y : new int[] {40, 120, 200, 1720, 1800, 1880}) {
            for (int x = 0; x < 1080; x += 7) {
                int rgb = storyImage.getRGB(x, y);
                int red = (rgb >> 16) & 0xff, blue = rgb & 0xff;
                assertTrue(red < 0x40 && blue > 0x80, name + " story band at " + x + "," + y + " is the gradient: " + Integer.toHexString(rgb));
            }
        }
        // Story: paper at the ticket's upper middle, navy stub lower down, on the centre line's left.
        assertNear(0xf6f6f8, storyImage.getRGB(540, 300), name + " paper");
        assertNear(0x0f172a, storyImage.getRGB(160, 1150), name + " stub");
    }

    @Test
    void renderingIsDeterministic() {
        ShareResult r = new ShareResult(52, 2048, 7);
        assertArrayEquals(ShareImageRenderer.link(r), ShareImageRenderer.link(r));
        assertArrayEquals(ShareImageRenderer.story(r), ShareImageRenderer.story(r));
    }

    @Test
    void differentResultsDrawDifferentImages() {
        assertNotEquals(Arrays.hashCode(ShareImageRenderer.link(new ShareResult(52, 2048, 7))),
                Arrays.hashCode(ShareImageRenderer.link(new ShareResult(53, 2048, 7))));
        assertNotEquals(Arrays.hashCode(ShareImageRenderer.story(new ShareResult(52, 2048, 7))),
                Arrays.hashCode(ShareImageRenderer.story(new ShareResult(52, 2048, 8))));
    }

    // The bundled faces are what is drawn: a missing file would silently fall back to SansSerif.
    @Test
    void theBundledFontsAndThePigLoad() {
        assertTrue(ShareImageRenderer.assetsLoaded(), "Montserrat Black, Black Italic, Inter Medium, Inter Bold, Inter Regular and the pig PNG");
    }

    // The big Montserrat Black numbers (the story's year, its stub age and route value, the link image's
    // age) are drawn with the face's proportional digits. The mockup's negative letter-spacing was made
    // for tabular figures, and with it pairs such as 48, 44, 23 and 72 ran into each other (2026-11-14
    // review). Every value the endpoints can be asked for is laid out here exactly as it is drawn, and
    // each digit must be its own shape with at least one clear pixel between it and the next.
    @Test
    void noTwoDigitsOfABigNumberTouch() {
        List<String> fused = new ArrayList<>();
        for (int year = 2026; year <= 2100; year++) {
            if (inkShapes(ShareImageRenderer.storyYearOutline(year)) != 4) fused.add("story year " + year);
        }
        for (int age = 18; age <= 100; age++) {
            int digits = Integer.toString(age).length();
            if (inkShapes(ShareImageRenderer.storyStubAgeOutline(age)) != digits) fused.add("story stub age " + age);
            if (inkShapes(ShareImageRenderer.storyRouteValueOutline(age)) != digits) fused.add("story route value " + age);
            if (inkShapes(ShareImageRenderer.linkAgeOutline(age)) != digits) fused.add("link age " + age);
        }
        assertEquals(List.of(), fused, "digits that touch");
    }

    // The reference width of the link image's "52" is 328px and panel 4's "2048" is about 612px at 1080:
    // opening the spacing must not have changed the look.
    @Test
    void theBigNumbersKeepTheirReferenceWidths() {
        double linkAge = ShareImageRenderer.linkAgeOutline(52).getBounds2D().getWidth();
        assertTrue(linkAge > 318 && linkAge < 340, "link age 52 is " + linkAge + "px wide");
        double year = ShareImageRenderer.storyYearOutline(2048).getBounds2D().getWidth();
        assertTrue(year > 596 && year < 632, "story year 2048 is " + year + "px wide");
    }

    // The route row's dotted arrow, as in the mockup (`.ln` is align-self:center in the row of label plus
    // value, and its chevron overhangs the line's end): in panel 4 of the counsel board, scaled to 1080,
    // the dots are at y 757 to 760 and the chevron's tip is about 15px from the "52". The first build drew
    // it 20px lower, level with the middle of the number, and a full gap (24px and more) short of it.
    @Test
    void theRouteArrowIsLevelWithTheMiddleOfItsRowAndReachesTheValue() throws IOException {
        BufferedImage story = decode(ShareImageRenderer.story(new ShareResult(52, 2048, 7)));
        int dotsTop = Integer.MAX_VALUE, dotsBottom = -1, dotsRight = -1;
        for (int y = 600; y < 950; y++) {
            for (int x = 130; x < 950; x++) {
                if (!near(story.getRGB(x, y), 0x9aa8bd, 4)) continue;
                dotsTop = Math.min(dotsTop, y);
                dotsBottom = Math.max(dotsBottom, y);
                dotsRight = Math.max(dotsRight, x);
            }
        }
        assertTrue(dotsBottom > 0, "the dotted line is drawn");
        double dotsMiddle = (dotsTop + dotsBottom) / 2.0;
        // The mockup's dots are at 757..760; the larger wordmark of 2026-11-15 made the heading row
        // taller and moved every row under it down by 11px.
        assertTrue(Math.abs(dotsMiddle - 769.5) <= 6, "dots at y " + dotsTop + ".." + dotsBottom + ", expected 768..771");

        // Rightwards from the dots, in the chevron's rows: the chevron, a paper gap, then the digits.
        int chevronEnd = -1, digitsStart = -1;
        for (int x = dotsRight + 1; x < 950 && digitsStart < 0; x++) {
            boolean blue = false;
            for (int y = (int) dotsMiddle - 12; y <= (int) dotsMiddle + 12; y++) blue |= isBlueInk(story.getRGB(x, y));
            if (blue && chevronEnd >= 0 && x - chevronEnd > 3) digitsStart = x;
            else if (blue) chevronEnd = x;
        }
        assertTrue(chevronEnd > 0 && digitsStart > 0, "a chevron and then the value, with paper between them");
        int gap = digitsStart - chevronEnd - 1;
        // The stylesheet puts the chevron's point 0.93u (16px) before the value's cell; the point itself is
        // a few faint pixels, so a crisp render measures 17 to 20 where the blurred panel reads about 15.
        assertTrue(gap >= 13 && gap <= 21, "the chevron's tip is " + gap + "px from the value");
    }

    private static boolean near(int argb, int rgb, int tolerance) {
        for (int shift = 16; shift >= 0; shift -= 8) {
            if (Math.abs(((argb >> shift) & 0xff) - ((rgb >> shift) & 0xff)) > tolerance) return false;
        }
        return true;
    }

    /** #135bec on the paper, at any antialiased strength short of faint. */
    private static boolean isBlueInk(int argb) {
        int red = (argb >> 16) & 0xff, blue = argb & 0xff;
        return blue > 0xd0 && red < 0xa0;
    }

    /** How many separate shapes the outline makes once drawn: any ink at all counts, diagonals connect. */
    private static int inkShapes(Shape outline) {
        Rectangle2D box = outline.getBounds2D();
        int w = (int) Math.ceil(box.getWidth()) + 4, h = (int) Math.ceil(box.getHeight()) + 4;
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.translate(2 - box.getX(), 2 - box.getY());
        g.setColor(Color.WHITE);
        g.fill(outline);
        g.dispose();
        boolean[] ink = new boolean[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) ink[y * w + x] = (image.getRaster().getSample(x, y, 0)) > 0;
        int shapes = 0;
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int start = 0; start < ink.length; start++) {
            if (!ink[start]) continue;
            shapes++;
            ink[start] = false;
            queue.add(start);
            while (!queue.isEmpty()) {
                int at = queue.poll(), ax = at % w, ay = at / w;
                for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                    int nx = ax + dx, ny = ay + dy;
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h || !ink[ny * w + nx]) continue;
                    ink[ny * w + nx] = false;
                    queue.add(ny * w + nx);
                }
            }
        }
        return shapes;
    }

    private static BufferedImage decode(byte[] png) throws IOException {
        assertArrayEquals(PNG_SIGNATURE, Arrays.copyOf(png, 8), "PNG signature");
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(image, "ImageIO reads it back");
        return image;
    }

    private static void assertNear(int expectedRgb, int actualArgb, String what) {
        for (int shift = 16; shift >= 0; shift -= 8) {
            int e = (expectedRgb >> shift) & 0xff, a = (actualArgb >> shift) & 0xff;
            assertTrue(Math.abs(e - a) <= 6, what + ": expected #" + Integer.toHexString(expectedRgb) + " got #" + Integer.toHexString(actualArgb & 0xffffff));
        }
    }
}
