package com.fredvested.web.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

/**
 * Svix signature verification for Resend webhooks, against the RAW request
 * body bytes: expected = base64(HMAC-SHA256(secret, "{svix-id}.{svix-timestamp}.{body}")),
 * compared in constant time with every "v1,..." entry in svix-signature.
 * Fails closed: no configured secret means nothing verifies.
 */
@Component
public class ResendWebhookVerifier {

    private static final Logger log = LoggerFactory.getLogger(ResendWebhookVerifier.class);
    public static final long TOLERANCE_SECONDS = 5 * 60;

    public enum Result { OK, NOT_CONFIGURED, MISSING_HEADERS, BAD_SIGNATURE, STALE_TIMESTAMP }

    private final byte[] secret;

    public ResendWebhookVerifier(@Value("${resend.webhook-secret:}") String configured) {
        this.secret = decodeSecret(configured);
        if (secret.length == 0) {
            log.warn("resend.webhook-secret is not set or is not valid base64 (expected the dashboard's whsec_... value):"
                    + " every webhook delivery will be rejected with 401");
        }
    }

    static byte[] decodeSecret(String configured) {
        if (configured == null) return new byte[0];
        String s = configured.trim();
        if (s.startsWith("whsec_")) s = s.substring("whsec_".length());
        if (s.isEmpty()) return new byte[0];
        try {
            return Base64.getDecoder().decode(s);
        } catch (IllegalArgumentException e) {
            return new byte[0];
        }
    }

    public Result verify(String svixId, String svixTimestamp, String svixSignature, byte[] body, Instant now) {
        if (secret.length == 0) return Result.NOT_CONFIGURED;
        if (isBlank(svixId) || isBlank(svixTimestamp) || isBlank(svixSignature) || body == null) return Result.MISSING_HEADERS;

        byte[] expected = sign(svixId, svixTimestamp, body);
        boolean matched = false;
        for (String part : svixSignature.trim().split("\\s+")) {
            if (!part.startsWith("v1,")) continue;
            byte[] provided;
            try {
                provided = Base64.getDecoder().decode(part.substring(3));
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (MessageDigest.isEqual(expected, provided)) matched = true;
        }
        if (!matched) return Result.BAD_SIGNATURE;

        long ts;
        try {
            ts = Long.parseLong(svixTimestamp.trim());
        } catch (NumberFormatException e) {
            return Result.STALE_TIMESTAMP;
        }
        if (Math.abs(now.getEpochSecond() - ts) > TOLERANCE_SECONDS) return Result.STALE_TIMESTAMP;
        return Result.OK;
    }

    byte[] sign(String svixId, String svixTimestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update((svixId + "." + svixTimestamp + ".").getBytes(StandardCharsets.UTF_8));
            mac.update(body);
            return mac.doFinal();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC failure", e);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
