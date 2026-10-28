package com.fredvested.web.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

// A message with an inline chart goes to Resend's /emails directly (the SDK has no content_id):
// same vendor, same key, the image base64 inside the request and only the cid in the HTML.
class EmailServiceInlineAttachmentTest {

    static final byte[] PNG = "fake-png-bytes".getBytes(StandardCharsets.UTF_8);
    static final String HTML = "<html><body><img src=\"cid:growth-chart\" alt=\"chart\"><a href=\"https://fredvested.com/privacy\">Privacy policy</a></body></html>";
    static final EmailService.InlineAttachment CHART = new EmailService.InlineAttachment("growth-chart.png", "growth-chart", "image/png", PNG);

    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    EmailService svc = new EmailService("re_key", "FRED <fred@fredvested.com>", "help@fredvested.com", "https://api.resend.test/", builder);

    @Test
    void postsTheAttachmentInline_andReturnsTheId() throws Exception {
        StringBuilder captured = new StringBuilder();
        server.expect(requestTo("https://api.resend.test/emails"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer re_key"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.from").value("FRED <fred@fredvested.com>"))
                .andExpect(jsonPath("$.to[0]").value("a@example.com"))
                .andExpect(jsonPath("$.reply_to[0]").value("help@fredvested.com"))
                .andExpect(jsonPath("$.subject").value("You're in"))
                .andExpect(jsonPath("$.text").value("plain"))
                .andExpect(jsonPath("$.attachments.length()").value(1))
                .andExpect(jsonPath("$.attachments[0].filename").value("growth-chart.png"))
                .andExpect(jsonPath("$.attachments[0].content_type").value("image/png"))
                .andExpect(jsonPath("$.attachments[0].content_id").value("growth-chart"))
                .andExpect(jsonPath("$.attachments[0].content").value(Base64.getEncoder().encodeToString(PNG)))
                .andExpect(jsonPath("$.attachments[0].path").doesNotExist())
                .andExpect(jsonPath("$.headers").doesNotExist())
                .andExpect(request -> captured.append(((MockClientHttpRequest) request).getBodyAsString()))
                .andRespond(withSuccess("{\"id\":\"re_123\"}", MediaType.APPLICATION_JSON));

        String id = svc.send("a@example.com", "You're in", HTML, "plain", Map.of(), List.of(CHART));

        assertEquals("re_123", id);
        server.verify();
        JsonNode body = new ObjectMapper().readTree(captured.toString());
        assertTrue(body.get("html").asText().contains("cid:growth-chart"));
        // No URL to the image anywhere: the only http(s) strings are the HTML's own links.
        String raw = captured.toString();
        assertFalse(raw.matches("(?s).*https?://[^\"]*(growth-chart|\\.png).*"), raw);
        assertFalse(raw.contains("\"path\""), raw);
    }

    // A stalled Resend call must not hold the outbox's single scheduler thread: the hand-built
    // client carries connect and read timeouts (the Spring-built one takes spring.http.client.*).
    @Test
    void theHandBuiltClient_carriesConnectAndReadTimeouts() throws Exception {
        assertDoesNotThrow(() -> new EmailService("re_key", "FRED <fred@fredvested.com>", "help@fredvested.com"));
        org.springframework.http.client.ClientHttpRequestFactory factory = EmailService.boundedRequestFactory();
        assertTrue(factory instanceof org.springframework.http.client.JdkClientHttpRequestFactory, factory.getClass().getName());
        java.lang.reflect.Field read = factory.getClass().getDeclaredField("readTimeout");
        read.setAccessible(true);
        assertEquals(EmailService.READ_TIMEOUT, read.get(factory));
        assertEquals(java.time.Duration.ofSeconds(10), EmailService.CONNECT_TIMEOUT);
    }

    @Test
    void headersAreForwarded_whenPresent() throws Exception {
        server.expect(requestTo("https://api.resend.test/emails"))
                .andExpect(jsonPath("$.headers.X-Entity-Ref-ID").value("abc"))
                .andRespond(withSuccess("{\"id\":\"re_h\"}", MediaType.APPLICATION_JSON));
        assertEquals("re_h", svc.send("a@example.com", "S", HTML, "t", Map.of("X-Entity-Ref-ID", "abc"), List.of(CHART)));
        server.verify();
    }

    @Test
    void aNon2xx_throws() {
        server.expect(requestTo("https://api.resend.test/emails"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY).contentType(MediaType.APPLICATION_JSON).body("{\"message\":\"bad\"}"));
        assertThrows(EmailService.EmailSendException.class,
                () -> svc.send("a@example.com", "S", HTML, "t", Map.of(), List.of(CHART)));
        server.verify();
    }

    @Test
    void aResponseWithoutAnId_throws() {
        server.expect(requestTo("https://api.resend.test/emails"))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));
        assertThrows(EmailService.EmailSendException.class,
                () -> svc.send("a@example.com", "S", HTML, "t", Map.of(), List.of(CHART)));
    }
}
