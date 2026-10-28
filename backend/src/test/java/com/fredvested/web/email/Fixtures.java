package com.fredvested.web.email;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/** The calculator-generated sample projections (src/test/resources/projections/*.json). */
public final class Fixtures {

    private Fixtures() {}

    public static final ObjectMapper MAPPER = new ObjectMapper();

    public static Projection projection(String name) {
        return read(name, Projection.class);
    }

    public static ProjectionRequest request(String name) {
        return read(name, ProjectionRequest.class);
    }

    public static <T> T read(String name, Class<T> type) {
        try (InputStream in = Fixtures.class.getClassLoader().getResourceAsStream("projections/" + name + ".json")) {
            if (in == null) throw new IllegalStateException("fixture missing: " + name);
            return MAPPER.readValue(in, type);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
