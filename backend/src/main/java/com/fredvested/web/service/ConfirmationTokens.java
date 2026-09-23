package com.fredvested.web.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Single-use, URL-safe tokens for confirmation and unsubscribe links. Only the
 * SHA-256 hex digest is ever stored; the raw value goes into the email and
 * nowhere else.
 */
public final class ConfirmationTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;
    private static final int MAX_RAW_LENGTH = 128;

    private ConfirmationTokens() {}

    public record Generated(String raw, String hash) {}

    public static Generated generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new Generated(raw, hash(raw));
    }

    /** Hex SHA-256 of the raw token; null for anything that cannot be a token we issued. */
    public static String hash(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > MAX_RAW_LENGTH) return null;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.US_ASCII));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
