package com.fredvested.web.email;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Reads a row's stored projection (waitlist_signups.projection_json, V8) back into a
 * {@link Projection}, or null when there is none or it cannot be used. A document that
 * parses but is incomplete (a hand-edited row, an older shape) counts as none: the
 * email and the chart have nothing they can safely fill. Callers log the waitlist id
 * only, never the document.
 */
public final class ProjectionJson {

    private ProjectionJson() {}

    public static Projection read(ObjectMapper objectMapper, String json) {
        if (json == null || json.isBlank()) return null;
        try {
            Projection p = objectMapper.readValue(json, Projection.class);
            if (p == null || p.scenario() == null
                    || p.series() == null || p.series().isEmpty()
                    || p.contributions() == null || p.contributions().isEmpty()) {
                return null;
            }
            return p;
        } catch (Exception e) {
            return null;
        }
    }
}
