package com.fredvested.web.email;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// The v2 emails' header wordmark (Andrew, 2026-11-16: the navbar's logo): one static PNG on the API,
// Montserrat Black Italic in the brand blue on a transparent ground, at 3x, cached for a year.
class EmailWordmarkControllerTest {

    final MockMvc mvc = MockMvcBuilders.standaloneSetup(new EmailWordmarkController()).build();

    @Test
    void theWordmark_isAPublicImmutablePng_theRendererDrew() throws Exception {
        MvcResult r = mvc.perform(get(EmailWordmarkController.PATH))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Cache-Control", "public, max-age=31536000, immutable"))
                .andExpect(header().string("Content-Disposition", "inline; filename=\"fred.png\""))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Robots-Tag", "noindex"))
                .andReturn();
        byte[] png = r.getResponse().getContentAsByteArray();
        assertArrayEquals(WordmarkRenderer.render().bytes(), png);
        assertEquals("https://lpapi.fredvested.com/api/email/wordmark-v1.png", EmailWordmarkController.url("https://lpapi.fredvested.com"));
        mvc.perform(head(EmailWordmarkController.PATH)).andExpect(status().isOk()).andExpect(header().string("Content-Type", "image/png"));
    }

    @Test
    void thePicture_isTheBrandBlueOnATransparentGround_atThreeTimesTheCssSize_andDeterministic() throws Exception {
        WordmarkRenderer.Png png = WordmarkRenderer.render();
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png.bytes()));
        assertEquals(png.width(), img.getWidth());
        assertEquals(png.height(), img.getHeight());
        assertEquals(WordmarkRenderer.LINE_HEIGHT * WordmarkRenderer.SCALE, img.getHeight(), "a 40px CSS line box at 3x");
        assertEquals(0, img.getWidth() % WordmarkRenderer.SCALE, "a whole number of CSS pixels");
        assertEquals(img.getWidth() / WordmarkRenderer.SCALE, png.cssWidth());
        assertEquals(40, png.cssHeight());
        // Corners and the side padding are transparent; the ink is the brand blue and nothing else.
        for (int[] c : new int[][] { { 0, 0 }, { img.getWidth() - 1, 0 }, { 0, img.getHeight() - 1 }, { img.getWidth() - 1, img.getHeight() - 1 } }) {
            assertEquals(0, (img.getRGB(c[0], c[1]) >>> 24), "transparent corner " + c[0] + "," + c[1]);
        }
        int opaqueInk = 0, minX = Integer.MAX_VALUE, maxX = -1, minY = Integer.MAX_VALUE, maxY = -1;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int argb = img.getRGB(x, y);
                int alpha = argb >>> 24;
                if (alpha == 0) continue;
                // Anti-aliased edge pixels carry the blue at partial alpha (and a rounding step in the
                // channels); every solid pixel is exactly the brand blue.
                if (alpha == 255) {
                    assertEquals(0x135bec, argb & 0xffffff, "every solid pixel is the brand blue at " + x + "," + y);
                    opaqueInk++;
                }
                minX = Math.min(minX, x); maxX = Math.max(maxX, x); minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            }
        }
        assertTrue(opaqueInk > 2000, "a solid wordmark, not a hairline: " + opaqueInk);
        assertTrue(minX >= 1 && maxX <= img.getWidth() - 2, "the italic overhang is inside the side padding: " + minX + ".." + maxX);
        assertTrue(maxX - minX > img.getWidth() * 0.8, "the ink spans the picture: " + minX + ".." + maxX + " of " + img.getWidth());
        int capHeight = maxY - minY + 1;
        assertTrue(capHeight > 0.6 * WordmarkRenderer.FONT_SIZE * WordmarkRenderer.SCALE && capHeight < 0.85 * WordmarkRenderer.FONT_SIZE * WordmarkRenderer.SCALE, "Montserrat's caps: " + capHeight);
        assertArrayEquals(png.bytes(), WordmarkRenderer.draw().bytes(), "deterministic");
        assertTrue(png.bytes().length < 20 * 1024, "small: " + png.bytes().length);
    }

    // The templates' width/height attributes (what Outlook's Word engine sizes the image by, and what
    // every other client reserves before the image loads) are the PNG's CSS size.
    @Test
    void bothTemplates_carryThePngsCssSize_onTheWordmarkImage() {
        WordmarkRenderer.Png png = WordmarkRenderer.render();
        Pattern tag = Pattern.compile("<img class=\"wordmark\" data-field=\"wordmark_url\" src=\"https://lpapi\\.fredvested\\.com/api/email/wordmark-v1\\.png\" width=\"(\\d+)\" height=\"(\\d+)\" alt=\"FRED\" style=\"display:block;margin:0 auto;width:(\\d+)px;height:(\\d+)px;[^\"]*\">");
        for (String template : new String[] { PersonalizedWelcomeEmail.TEMPLATE, "email/confirmation-v2.html" }) {
            Matcher m = tag.matcher(EmailTemplateEngine.load(template));
            assertTrue(m.find(), template + ": the wordmark image tag");
            assertEquals(png.cssWidth(), Integer.parseInt(m.group(1)), template + ": width attribute (the PNG is " + png.cssWidth() + "x" + png.cssHeight() + " CSS px)");
            assertEquals(png.cssHeight(), Integer.parseInt(m.group(2)), template + ": height attribute");
            assertEquals(png.cssWidth(), Integer.parseInt(m.group(3)), template + ": CSS width");
            assertEquals(png.cssHeight(), Integer.parseInt(m.group(4)), template + ": CSS height");
            assertFalse(m.find(), template + ": one wordmark");
        }
    }
}
