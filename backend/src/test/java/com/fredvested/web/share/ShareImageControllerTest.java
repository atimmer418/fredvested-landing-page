package com.fredvested.web.share;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The image for a path never changes, so it is cached for a year; anything the calculator could
// not have produced (or whose date has passed since the link was shared) is a bare 404.
@WebMvcTest(ShareImageController.class)
class ShareImageControllerTest {

    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-11-14T12:00:00Z"), ZoneOffset.UTC);
        }
    }

    static final String CACHE = "public, max-age=31536000, immutable";

    @Autowired MockMvc mockMvc;

    @Test
    void linkImage() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/og/v1/52/2048-07.png"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Cache-Control", CACHE))
                .andReturn();
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(result.getResponse().getContentAsByteArray()));
        assertEquals(1200, image.getWidth());
        assertEquals(630, image.getHeight());
    }

    @Test
    void storyImage() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/og/v1/story/52/2048-07.png"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Cache-Control", CACHE))
                .andReturn();
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(result.getResponse().getContentAsByteArray()));
        assertEquals(1080, image.getWidth());
        assertEquals(1920, image.getHeight());
    }

    @Test
    void headIsAnsweredLikeGet() throws Exception {
        mockMvc.perform(head("/api/og/v1/52/2048-07.png"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Cache-Control", CACHE));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "18/2026-10.png",      // the date has passed
            "52/2048-13.png",      // month
            "abc/2048-07.png",     // age shape
            "52/2048-07x.png",     // date shape
            "17/2030-01.png",      // age below the range
            "101/2090-01.png",     // age above the range
            "90/2030-01.png",      // implied current age 86.8
            "52/2048-7.png",       // unpadded month
            "52/2048-07",          // no extension
            "52/2048-07.jpg",
            "52.png",
            "52/2048-07.png/extra",
            ""})
    void anythingInvalidIsABare404OnBothRoutes(String tail) throws Exception {
        for (String base : new String[] {"/api/og/v1/", "/api/og/v1/story/"}) {
            mockMvc.perform(get(base + tail))
                    .andExpect(status().isNotFound())
                    .andExpect(content().bytes(new byte[0]))
                    .andExpect(header().doesNotExist("Cache-Control"));
        }
    }

    @Test
    void otherVersionsAndMethodsAreNotServed() throws Exception {
        mockMvc.perform(get("/api/og/v2/52/2048-07.png")).andExpect(status().isNotFound()).andExpect(content().bytes(new byte[0]));
        mockMvc.perform(post("/api/og/v1/52/2048-07.png")).andExpect(status().is4xxClientError());
    }
}
