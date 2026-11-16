package com.fredvested.web.email;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fredvested.web.model.WaitlistEntry;
import com.fredvested.web.repository.WaitlistRepository;
import com.fredvested.web.service.ConfirmationTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class EmailChartControllerTest {

    final WaitlistRepository waitlist = mock(WaitlistRepository.class);
    final ObjectMapper objectMapper = new ObjectMapper();
    final MockMvc mvc = MockMvcBuilders.standaloneSetup(new EmailChartController(waitlist, objectMapper)).build();
    final ConfirmationTokens.Generated token = ConfirmationTokens.generate();

    @BeforeEach
    void setUp() {
        when(waitlist.findByChartTokenHash(anyString())).thenReturn(Optional.empty());
    }

    private WaitlistEntry rowWith(String projectionJson) {
        WaitlistEntry e = new WaitlistEntry();
        e.setId(7L);
        e.setEmail("a@example.com");
        e.setProjectionJson(projectionJson);
        e.setChartTokenHash(token.hash());
        when(waitlist.findByChartTokenHash(token.hash())).thenReturn(Optional.of(e));
        return e;
    }

    @Test
    void aKnownToken_rendersTheRowsOwnChart_asAnImmutablePng() throws Exception {
        rowWith(objectMapper.writeValueAsString(Fixtures.projection("sample")));

        MvcResult r = mvc.perform(get("/api/email/chart/" + token.raw() + ".png"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Cache-Control", "private, max-age=31536000, immutable"))
                .andExpect(header().string("Content-Disposition", "inline; filename=\"growth-chart.png\""))
                .andExpect(header().string("X-Robots-Tag", "noindex"))
                .andReturn();
        byte[] png = r.getResponse().getContentAsByteArray();
        assertArrayEquals(GrowthChartRenderer.render(Fixtures.projection("sample")).bytes(), png, "the same picture the attachment was");
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        assertEquals(1040, img.getWidth());
        assertEquals(520, img.getHeight());
        // The lookup is by hash only; the raw token is never handed to the repository.
        verify(waitlist).findByChartTokenHash(token.hash());
        verify(waitlist, never()).findByChartTokenHash(token.raw());
    }

    @Test
    void headIsAnsweredLikeGet_forProxiesThatProbeFirst() throws Exception {
        rowWith(objectMapper.writeValueAsString(Fixtures.projection("sample")));
        mvc.perform(head("/api/email/chart/" + token.raw() + ".png")).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"));
    }

    @Test
    void anUnknownToken_isABare404() throws Exception {
        mvc.perform(get("/api/email/chart/" + ConfirmationTokens.generate().raw() + ".png"))
                .andExpect(status().isNotFound()).andExpect(content().string(""));
    }

    @Test
    void aRowWithoutAUsableProjection_isABare404_evenWithTheRightToken() throws Exception {
        for (String json : new String[] { null, "", "{not json", "{}", "{\"currentAge\":30}" }) {
            rowWith(json);
            mvc.perform(get("/api/email/chart/" + token.raw() + ".png"))
                    .andExpect(status().isNotFound()).andExpect(content().string(""));
        }
    }

    // A stored document the renderer refuses (only a hand-edited row can hold one): still a bare 404,
    // never the API's JSON 500 and never a stack trace per fetch.
    @Test
    void aProjectionTheRendererRefuses_isABare404() throws Exception {
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> doc = objectMapper.readValue(objectMapper.writeValueAsString(Fixtures.projection("sample")), java.util.Map.class);
        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> series = (java.util.List<java.util.Map<String, Object>>) doc.get("series");
        series.get(series.size() - 1).put("value", 900_000_000_000d);
        rowWith(objectMapper.writeValueAsString(doc));
        mvc.perform(get("/api/email/chart/" + token.raw() + ".png"))
                .andExpect(status().isNotFound()).andExpect(content().string(""));
    }

    @Test
    void anythingThatIsNotATokenShapedPath_isABare404_withoutTouchingTheDatabase() throws Exception {
        for (String path : new String[] {
                "/api/email/chart/short.png", "/api/email/chart/" + token.raw(), "/api/email/chart/" + token.raw() + ".jpg",
                "/api/email/chart/", "/api/email/chart", "/api/email/chart/../chart/x.png", "/api/email/chart/" + "a".repeat(200) + ".png",
                "/api/email/chart/has%20space%20in%20it%20abcdefghij.png", "/api/email/chart/ünïcödé-abcdefghijklmnop.png" }) {
            mvc.perform(get(path)).andExpect(status().isNotFound()).andExpect(content().string(""));
        }
        verify(waitlist, never()).findByChartTokenHash(anyString());
    }
}
