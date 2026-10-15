package com.fredvested.web.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Where the landing site lives, for redirects out of the API. landing.url is an
 * absolute origin in dev/prod; the literal "same-host" (local profile) means the
 * frontend dev server on port 5500 of whatever host served the request, where
 * clean URLs do not exist and pages need their .html extension.
 */
@Component
public class LandingUrls {

    private final String landingUrl;

    public LandingUrls(@Value("${landing.url:https://fredvested.com}") String landingUrl) {
        this.landingUrl = landingUrl;
    }

    public boolean sameHost() {
        return "same-host".equals(landingUrl);
    }

    public String origin(HttpServletRequest request) {
        return sameHost() ? "http://" + request.getServerName() + ":5500" : landingUrl.replaceAll("/+$", "");
    }

    /** Absolute URL of a landing page, e.g. page("confirmed", "status=expired"). */
    public String page(HttpServletRequest request, String name, String query) {
        String path = "/" + name + (sameHost() ? ".html" : "");
        return origin(request) + path + (query == null || query.isEmpty() ? "" : "?" + query);
    }
}
