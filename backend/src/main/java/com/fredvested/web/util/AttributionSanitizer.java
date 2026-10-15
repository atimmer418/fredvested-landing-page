package com.fredvested.web.util;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Set;

/**
 * Server-side sanitisation of attribution values. The client applies the same
 * rules for data quality; this is the security boundary. Every method returns
 * null for anything it cannot accept and never throws, so a malformed tag can
 * only ever cost us that tag, never the signup.
 */
public final class AttributionSanitizer {

    public static final int TOKEN_MAX = 100;
    public static final int HOST_MAX = 255;
    public static final int PATH_MAX = 255;
    private static final ZoneId EASTERN = ZoneId.of("America/New_York");
    private static final Set<String> DEVICE_TYPES = Set.of("mobile", "tablet", "desktop");

    private AttributionSanitizer() {}

    /** utm_* values: lowercase, only [a-z0-9-_.], at most 100 chars, null if empty. */
    public static String token(String raw) {
        if (raw == null) return null;
        String cleaned = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\-_.]", "");
        if (cleaned.length() > TOKEN_MAX) cleaned = cleaned.substring(0, TOKEN_MAX);
        return cleaned.isEmpty() ? null : cleaned;
    }

    /** A hostname: lowercase, only [a-z0-9.-], at most 255 chars. */
    public static String host(String raw) {
        if (raw == null) return null;
        String cleaned = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9.\\-]", "");
        if (cleaned.length() > HOST_MAX) cleaned = cleaned.substring(0, HOST_MAX);
        return cleaned.isEmpty() ? null : cleaned;
    }

    /** A path on our own site: lowercase, only [a-z0-9/._-], must start with '/', at most 255 chars. */
    public static String path(String raw) {
        if (raw == null) return null;
        String cleaned = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9/._\\-]", "");
        if (cleaned.length() > PATH_MAX) cleaned = cleaned.substring(0, PATH_MAX);
        if (cleaned.isEmpty() || cleaned.charAt(0) != '/') return null;
        return cleaned;
    }

    /** One of mobile | tablet | desktop, else null. */
    public static String deviceType(String raw) {
        if (raw == null) return null;
        String cleaned = raw.toLowerCase(Locale.ROOT).trim();
        return DEVICE_TYPES.contains(cleaned) ? cleaned : null;
    }

    /** An ISO-8601 instant (what the browser's toISOString() produces), stored as Eastern local time like created_at. */
    public static LocalDateTime instantToEastern(String raw) {
        if (raw == null || raw.length() > 40) return null;
        try {
            return LocalDateTime.ofInstant(Instant.parse(raw.trim()), EASTERN);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
