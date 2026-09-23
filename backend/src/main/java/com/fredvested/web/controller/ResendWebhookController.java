package com.fredvested.web.controller;

import com.fredvested.web.service.EmailEventProcessor;
import com.fredvested.web.service.ResendWebhookVerifier;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

import static com.fredvested.web.controller.ApiErrorHandler.error;

/**
 * POST /api/webhooks/resend. The body is read as raw bytes, capped at 64 KB
 * (real events are 1-2 KB), so the signature is checked over exactly what
 * Resend signed, before any JSON parsing, and an anonymous client cannot make
 * the API buffer arbitrary amounts of memory. Anything unverifiable is the same
 * 401, a stale timestamp is 400, and every verified delivery (including
 * duplicates and unknown message ids) is 200 so Resend stops retrying.
 */
@RestController
public class ResendWebhookController {

    private static final Logger log = LoggerFactory.getLogger(ResendWebhookController.class);
    private static final ZoneId EASTERN = ZoneId.of("America/New_York");
    static final int MAX_BODY_BYTES = 64 * 1024;
    private static final Map<String, String> UNAUTHORIZED = error("unauthorized", "Invalid webhook signature.");

    private final ResendWebhookVerifier verifier;
    private final EmailEventProcessor processor;

    public ResendWebhookController(ResendWebhookVerifier verifier, EmailEventProcessor processor) {
        this.verifier = verifier;
        this.processor = processor;
    }

    @PostMapping("/api/webhooks/resend")
    public ResponseEntity<Map<String, String>> receive(
            @RequestHeader(value = "svix-id", required = false) String svixId,
            @RequestHeader(value = "svix-timestamp", required = false) String svixTimestamp,
            @RequestHeader(value = "svix-signature", required = false) String svixSignature,
            HttpServletRequest request) throws IOException {

        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(error("too_large", "Webhook body too large."));
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(error("too_large", "Webhook body too large."));
        }
        if (body.length == 0) body = null;

        ResendWebhookVerifier.Result result = verifier.verify(svixId, svixTimestamp, svixSignature, body, Instant.now());
        switch (result) {
            case NOT_CONFIGURED -> {
                log.debug("Webhook rejected: resend.webhook-secret is not configured");
                return ResponseEntity.status(401).body(UNAUTHORIZED);
            }
            case MISSING_HEADERS, BAD_SIGNATURE -> {
                return ResponseEntity.status(401).body(UNAUTHORIZED);
            }
            case STALE_TIMESTAMP -> {
                return ResponseEntity.badRequest().body(error("stale", "Webhook timestamp outside tolerance."));
            }
            case OK -> { /* fall through */ }
        }

        try {
            EmailEventProcessor.Outcome outcome = processor.process(svixId, body, LocalDateTime.now(EASTERN));
            return ResponseEntity.ok(Map.of("status", outcome == EmailEventProcessor.Outcome.DUPLICATE ? "duplicate" : "ok"));
        } catch (DataIntegrityViolationException e) {
            // Two deliveries of one svix-id raced past the fast path; the UNIQUE constraint
            // rolled this one back before anything was applied, so it is a plain duplicate.
            return ResponseEntity.ok(Map.of("status", "duplicate"));
        } catch (IOException e) {
            log.warn("Webhook body could not be parsed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(error("malformed", "Webhook body is not valid JSON."));
        }
    }
}
