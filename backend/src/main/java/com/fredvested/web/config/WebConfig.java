package com.fredvested.web.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${cors.allowed.origins}")
    private String allowedOrigins;

    // The API's own public origin. The confirm and unsubscribe pages are served by
    // the API and POST back to it; the browser sends Origin on that same-origin POST,
    // and behind a proxy that lost the forwarded scheme it would look cross-origin.
    @Value("${api.public-url:}")
    private String apiPublicUrl;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // Patterns (not exact origins) so the local profile can allow any
        // same-wifi device via e.g. http://192.168.*:5500; exact origins in
        // dev/prod configs match identically under pattern semantics.
        registry.addMapping("/api/**")
                .allowedOriginPatterns(allowedOriginPatterns())
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false);
    }

    String[] allowedOriginPatterns() {
        List<String> patterns = new ArrayList<>(Arrays.asList(allowedOrigins.split(",")));
        String own = apiPublicUrl == null ? "" : apiPublicUrl.trim().replaceAll("/+$", "");
        if (!own.isEmpty() && !patterns.contains(own)) patterns.add(own);
        return patterns.toArray(new String[0]);
    }
}