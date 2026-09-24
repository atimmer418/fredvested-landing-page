package com.fredvested.web.config;

import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Makes an environment variable that is set but blank behave exactly like an
 * unset one, so every {@code ${VAR:default}} in the properties files falls back
 * to its default instead of binding "".
 *
 * Why: a hosting dashboard makes it easy to create a variable and leave the
 * value empty. Spring's placeholder default only applies when the variable is
 * absent, so "" reaches the bean, and a boolean or numeric {@code @Value} then
 * fails to convert and the whole API refuses to start (Railway dev,
 * 2026-09-24, {@code WAITLIST_DOUBLE_OPT_IN=""}). A string binding fails more
 * quietly: an empty {@code API_PUBLIC_URL} would have produced broken links in
 * every email. Neither is a reason to be down.
 *
 * Registered in META-INF/spring.factories; runs before any placeholder resolves.
 */
public class BlankEnvironmentVariables implements EnvironmentPostProcessor {

    private final Log log;

    public BlankEnvironmentVariables(DeferredLogFactory logs) {
        this.log = logs.getLog(BlankEnvironmentVariables.class);
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        MutablePropertySources sources = environment.getPropertySources();
        PropertySource<?> source = sources.get(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        if (!(source instanceof SystemEnvironmentPropertySource system)) return;

        Map<String, Object> kept = new LinkedHashMap<>();
        List<String> blank = new ArrayList<>();
        for (Map.Entry<String, Object> entry : system.getSource().entrySet()) {
            if (entry.getValue() instanceof String value && value.isBlank()) {
                blank.add(entry.getKey());
            } else {
                kept.put(entry.getKey(), entry.getValue());
            }
        }
        if (blank.isEmpty()) return;

        // Same class as the original so Spring Boot's relaxed binding
        // (PORT <-> server.port style lookups) keeps working.
        sources.replace(source.getName(), new SystemEnvironmentPropertySource(source.getName(), kept));
        log.warn("Ignoring blank environment variable(s) " + blank
                + ": treated as unset, so their configured defaults apply. Set a value or delete the variable.");
    }
}
