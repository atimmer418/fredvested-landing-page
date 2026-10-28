package com.fredvested.web.email;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrowthChartRendererTest {

    private static final int BACKGROUND = 0x1d4ed8;
    private static final Path PREVIEW_DIR = Path.of("build", "email-preview");

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    static Projection load(String name) throws IOException {
        try (InputStream in = GrowthChartRendererTest.class.getResourceAsStream("/projections/" + name + ".json")) {
            assertNotNull(in, "fixture " + name);
            return new ObjectMapper().readValue(in, Projection.class);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"sample", "existing-100k-optimistic", "short-horizon", "long-horizon",
            "target-under-1m", "target-over-5m", "stake-lever", "stake-hidden"})
    void rendersAValidPngOnTheBlueBackgroundWithTheLineReachingTheEndPoint(String fixture) throws IOException {
        Projection p = load(fixture);
        GrowthChartRenderer.Png png = GrowthChartRenderer.render(p);

        byte[] b = png.bytes();
        assertTrue(b.length > 8, "has bytes");
        assertArrayEquals(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a},
                Arrays.copyOf(b, 8), "PNG signature");
        assertTrue(b.length <= GrowthChartRenderer.MAX_BYTES, fixture + " is " + b.length + " bytes");

        assertTrue(png.scale() == 2.0 || png.scale() == 1.5, "scale " + png.scale());
        int expectedW = (int) Math.round(GrowthChartRenderer.BASE_WIDTH * png.scale());
        int expectedH = (int) Math.round(GrowthChartRenderer.BASE_HEIGHT * png.scale());
        assertEquals(expectedW, png.width());
        assertEquals(expectedH, png.height());

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(b));
        assertNotNull(image, "ImageIO reads it back");
        assertEquals(expectedW, image.getWidth());
        assertEquals(expectedH, image.getHeight());

        int w = image.getWidth(), h = image.getHeight();
        for (int[] corner : new int[][] {{0, 0}, {w - 1, 0}, {0, h - 1}, {w - 1, h - 1}}) {
            assertEquals(BACKGROUND, image.getRGB(corner[0], corner[1]) & 0xffffff,
                    fixture + " corner " + corner[0] + "," + corner[1] + " is the background (no border)");
        }

        double[] end = GrowthChartRenderer.endPixel(p, png.scale());
        int ex = (int) Math.round(end[0]), ey = (int) Math.round(end[1]);
        assertTrue(ex > 0 && ex < w && ey > 0 && ey < h, "end point is inside the image");
        int reach = (int) Math.round(6 * png.scale());
        boolean whiteish = false;
        for (int x = Math.max(0, ex - reach); x <= Math.min(w - 1, ex + reach) && !whiteish; x++) {
            for (int y = Math.max(0, ey - reach); y <= Math.min(h - 1, ey + reach); y++) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xff, g = (rgb >> 8) & 0xff, bl = rgb & 0xff;
                if (r > 220 && g > 220 && bl > 220) { whiteish = true; break; }
            }
        }
        assertTrue(whiteish, fixture + " has a white pixel near the last series point");
    }

    @Test
    void sampleRendersAtTwoTimes() throws IOException {
        GrowthChartRenderer.Png png = GrowthChartRenderer.render(load("sample"));
        assertEquals(2.0, png.scale());
        assertEquals(1040, png.width());
        assertEquals(520, png.height());
    }

    @Test
    void writesPreviewPngsForTheLead() throws IOException {
        Files.createDirectories(PREVIEW_DIR);
        Files.write(PREVIEW_DIR.resolve("growth-chart-sample.png"), GrowthChartRenderer.render(load("sample")).bytes());
        Files.write(PREVIEW_DIR.resolve("growth-chart-existing-100k.png"), GrowthChartRenderer.render(load("existing-100k-optimistic")).bytes());
        Files.write(PREVIEW_DIR.resolve("growth-chart-short-horizon.png"), GrowthChartRenderer.render(load("short-horizon")).bytes());
        Files.write(PREVIEW_DIR.resolve("growth-chart-long-horizon.png"), GrowthChartRenderer.render(load("long-horizon")).bytes());
        assertTrue(Files.size(PREVIEW_DIR.resolve("growth-chart-sample.png")) > 0);
    }

    @Test
    void tickAgesStartAtCurrentAgeStepFiveYearsAndEndAtTheRoundedFreedomAge() {
        assertEquals(List.of(30, 35, 40, 45, 50, 53), GrowthChartRenderer.tickAges(30, 53.491330801497085));
        assertEquals(List.of(30, 33), GrowthChartRenderer.tickAges(30, 32.88879089827239));
        assertEquals(List.of(18, 23, 28, 33, 38, 43, 48, 53, 58, 63), GrowthChartRenderer.tickAges(18, 63.04518258969191));
        assertEquals(List.of(30, 35, 40, 45, 50), GrowthChartRenderer.tickAges(30, 50.4));
        assertEquals(List.of(30, 35, 40, 45, 51), GrowthChartRenderer.tickAges(30, 50.6));
    }

    @Test
    void tickAgesNeverRepeatOrThrowOnDegenerateHorizons() {
        assertEquals(List.of(30), GrowthChartRenderer.tickAges(30, 30.0));
        assertEquals(List.of(30), GrowthChartRenderer.tickAges(30, 30.3));
        assertEquals(List.of(30, 31), GrowthChartRenderer.tickAges(30, 30.6));
    }

    @Test
    void gridStepFollowsTheTarget() {
        assertEquals(100_000L, GrowthChartRenderer.gridStep(600_000));
        assertEquals(500_000L, GrowthChartRenderer.gridStep(2_250_000));
        assertEquals(1_000_000L, GrowthChartRenderer.gridStep(6_000_000));
        assertEquals(500_000L, GrowthChartRenderer.gridStep(1_000_000));
        assertEquals(500_000L, GrowthChartRenderer.gridStep(5_000_000));
    }

    static Projection horizon(int currentAge, double freedomAge, double target) {
        List<Projection.Point> series = new java.util.ArrayList<>();
        List<Projection.Point> contributions = new java.util.ArrayList<>();
        int whole = (int) Math.floor(freedomAge - currentAge);
        for (int y = 0; y <= whole; y++) {
            double t = (double) y / Math.max(1, freedomAge - currentAge);
            series.add(new Projection.Point(currentAge + y, target * t * t));
            contributions.add(new Projection.Point(currentAge + y, target * t * 0.3));
        }
        if (freedomAge > currentAge + whole) {
            series.add(new Projection.Point(freedomAge, target));
            contributions.add(new Projection.Point(freedomAge, target * 0.3));
        }
        return new Projection(currentAge, freedomAge, 1, 2060, 500, 3000, 0, 8, Projection.Scenario.CONSERVATIVE,
                2, Projection.StakeSource.PLAN, Math.round(target * 0.3), Math.round(target), series, contributions);
    }

    // The renderer is safe on its own: a value beyond what the chart will draw is refused at once
    // (the publisher then sends the no-chart copy), and the gridline loops are counted, never
    // driven by the data's magnitude, so nothing can hold the outbox thread.
    @Test
    void hugeValuesAreRefusedPromptly_andTheCeilingItselfRenders() throws IOException {
        for (double huge : new double[] { 1e18, 1e300, Double.POSITIVE_INFINITY }) {
            Projection p = horizon(30, 53.5, huge);
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertThrows(IllegalArgumentException.class, () -> GrowthChartRenderer.render(p)), String.valueOf(huge));
        }
        Projection ceiling = horizon(30, 53.5, 100_000_000);
        GrowthChartRenderer.Png png = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> GrowthChartRenderer.render(ceiling));
        assertTrue(png.bytes().length > 0 && png.bytes().length <= GrowthChartRenderer.MAX_BYTES);
        // 100 lines at the $1M base step would pass MAX_GRIDLINES, so the step is doubled to $2M (50 lines).
        assertEquals(2_000_000L, new GrowthChartRenderer.Layout(ceiling, 2.0).step);
        assertEquals(50, new GrowthChartRenderer.Layout(ceiling, 2.0).lines);
        assertEquals(500_000L, new GrowthChartRenderer.Layout(load("sample"), 2.0).step);
    }

    // On a long horizon a year is narrower than a label, so the five-year tick just before the
    // freedom age is left unlabelled rather than printed over it; the rule's ticks are unchanged.
    @Test
    void anOverprintingPenultimateTickLabelIsSkipped() throws IOException {
        assertEquals(List.of(18, 23, 28, 33, 38, 43, 48, 53, 58, 63, 64), GrowthChartRenderer.tickAges(18, 64.0));
        assertEquals(List.of(18, 23, 28, 33, 38, 43, 48, 53, 58, 64), GrowthChartRenderer.drawnTickAges(horizon(18, 64.0, 900_000), 2.0));
        assertEquals(List.of(30, 35, 40, 45, 50, 55, 60, 66), GrowthChartRenderer.drawnTickAges(horizon(30, 66.0, 900_000), 2.0));
        // Where the labels fit, every tick is drawn (the sample: 50 and 53 are three and a half years apart).
        assertEquals(List.of(30, 35, 40, 45, 50, 53), GrowthChartRenderer.drawnTickAges(load("sample"), 2.0));
        assertTrue(GrowthChartRenderer.render(horizon(18, 64.0, 900_000)).bytes().length > 0);
    }

    @Test
    void degenerateProjectionsStillRender() {
        Projection flat = new Projection(30, 31.0, 1, 2027, 0, 0, 0, 10, Projection.Scenario.MODERATE,
                0, Projection.StakeSource.HIDDEN, 0, 0,
                List.of(new Projection.Point(30, 0), new Projection.Point(31, 0)),
                List.of(new Projection.Point(30, 0), new Projection.Point(31, 0)));
        assertTrue(GrowthChartRenderer.render(flat).bytes().length > 0);

        Projection twoPoints = new Projection(40, 40.5, 6, 2027, 500, 1000, 50_000, 7, Projection.Scenario.CONSERVATIVE,
                0, null, 53_000, 60_000,
                List.of(new Projection.Point(40, 50_000), new Projection.Point(40.5, 60_000)),
                List.of(new Projection.Point(40, 50_000), new Projection.Point(40.5, 53_000)));
        assertTrue(GrowthChartRenderer.render(twoPoints).bytes().length > 0);

        Projection empty = new Projection(30, 30.0, 1, 2026, 0, 0, 0, 10, Projection.Scenario.MODERATE,
                0, null, 0, 0, List.of(), List.of());
        assertTrue(GrowthChartRenderer.render(empty).bytes().length > 0);
    }
}
