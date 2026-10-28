package com.fredvested.web.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.CreateEmailOptions;
import com.resend.services.emails.model.CreateEmailResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin Resend client. Called only by the outbox publisher, never from a request
 * thread: if Resend is slow or down the signup has already been committed and
 * the message is retried later. The API key comes from configuration only.
 *
 * Two paths to the same vendor: the resend-java SDK for a plain message, and a
 * direct POST to Resend's /emails for a message with an inline attachment (the
 * welcome email's chart): the SDK (3.1.0, and still 4.4.0) has no content_id on
 * its Attachment, so a cid-referenced image cannot go through it. Same API, same
 * key, no new third party.
 */
@Service
public class EmailService {

    public static final String DEFAULT_API_BASE_URL = "https://api.resend.com";

    /** An image (or other file) embedded in the message and referenced from the HTML as cid:contentId. */
    public record InlineAttachment(String filename, String contentId, String contentType, byte[] content) {}

    /** Resend answered a direct send with an error, or without a message id. */
    public static class EmailSendException extends RuntimeException {
        public EmailSendException(String message) { super(message); }
        public EmailSendException(String message, Throwable cause) { super(message, cause); }
    }

    private final Resend resend;
    private final RestClient restClient;
    private final String apiKey;
    private final String from;
    private final String replyTo;

    public EmailService(String apiKey, String from, String replyTo) {
        this(apiKey, from, replyTo, DEFAULT_API_BASE_URL, RestClient.builder().requestFactory(boundedRequestFactory()));
    }

    /**
     * The direct path's timeouts when the client is built by hand (Spring's autoconfigured
     * builder takes them from spring.http.client.* instead): a stalled Resend call must not
     * hold the outbox's single scheduler thread.
     */
    static ClientHttpRequestFactory boundedRequestFactory() {
        return ClientHttpRequestFactoryBuilder.jdk().build(ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(CONNECT_TIMEOUT).withReadTimeout(READ_TIMEOUT));
    }

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    public EmailService(@Value("${resend.api-key}") String apiKey,
                        @Value("${email.from:FRED <fred@fredvested.com>}") String from,
                        @Value("${email.reply-to:help@fredvested.com}") String replyTo,
                        @Value("${resend.api-base-url:https://api.resend.com}") String apiBaseUrl,
                        RestClient.Builder restClientBuilder) {
        this.resend = new Resend(apiKey);
        this.apiKey = apiKey;
        this.from = from;
        this.replyTo = replyTo;
        this.restClient = restClientBuilder.baseUrl(apiBaseUrl.replaceAll("/+$", "")).build();
    }

    /**
     * Sends one email and returns Resend's message id, the key webhook events join on.
     * Headers carry the RFC 8058 one-click unsubscribe pair (List-Unsubscribe and
     * List-Unsubscribe-Post) so mail clients can unsubscribe without a page.
     */
    public String send(String to, String subject, String html, String text, Map<String, String> headers) throws ResendException {
        CreateEmailResponse response = resend.emails().send(options(to, subject, html, text, headers));
        return response.getId();
    }

    /**
     * Sends one email with inline attachments and returns Resend's message id. With no
     * attachments this is exactly {@link #send(String, String, String, String, Map)}.
     * The attachment bytes travel base64 inside the request: nothing is hosted anywhere,
     * and the HTML carries only the cid reference.
     */
    public String send(String to, String subject, String html, String text, Map<String, String> headers,
                       List<InlineAttachment> inline) throws ResendException {
        if (inline == null || inline.isEmpty()) return send(to, subject, html, text, headers);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", from);
        body.put("to", List.of(to));
        body.put("subject", subject);
        body.put("html", html);
        body.put("text", text);
        if (replyTo != null && !replyTo.isBlank()) body.put("reply_to", List.of(replyTo.trim()));
        if (headers != null && !headers.isEmpty()) body.put("headers", headers);
        List<Map<String, String>> attachments = new ArrayList<>();
        for (InlineAttachment a : inline) {
            Map<String, String> att = new LinkedHashMap<>();
            att.put("filename", a.filename());
            att.put("content", Base64.getEncoder().encodeToString(a.content()));
            att.put("content_type", a.contentType());
            att.put("content_id", a.contentId());
            attachments.add(att);
        }
        body.put("attachments", attachments);

        ResponseEntity<JsonNode> response;
        try {
            response = restClient.post()
                    .uri("/emails")
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toEntity(JsonNode.class);
        } catch (RestClientException e) {
            throw new EmailSendException("Resend direct send failed: " + e.getClass().getSimpleName(), e);
        }
        HttpStatusCode status = response.getStatusCode();
        if (!status.is2xxSuccessful()) throw new EmailSendException("Resend direct send answered " + status.value());
        JsonNode id = response.getBody() == null ? null : response.getBody().get("id");
        if (id == null || id.asText().isBlank()) throw new EmailSendException("Resend direct send returned no id");
        return id.asText();
    }

    /**
     * Every message replies to the help mailbox (email.reply-to), so sending from a
     * subdomain such as mail.fredvested.com never strands a reply (decision 2026-09-25).
     */
    CreateEmailOptions options(String to, String subject, String html, String text, Map<String, String> headers) {
        CreateEmailOptions.Builder builder = CreateEmailOptions.builder()
                .from(from)
                .to(to)
                .subject(subject)
                .html(html)
                .text(text);
        if (replyTo != null && !replyTo.isBlank()) builder.replyTo(replyTo.trim());
        if (headers != null && !headers.isEmpty()) builder.headers(headers);
        return builder.build();
    }
}
