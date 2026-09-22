package com.fredvested.web.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The API has no pages of its own — anyone landing on the server root gets
 * redirected to the landing site instead of a Whitelabel 404.
 */
@RestController
public class RootRedirectController {

    // "same-host" -> the locally served frontend on port 5500 (local profile);
    // otherwise an absolute URL (prod default: the live site)
    @Value("${landing.url:https://fredvested.com}")
    private String landingUrl;

    @GetMapping("/")
    public ResponseEntity<Void> root(HttpServletRequest request) {
        String target = "same-host".equals(landingUrl)
                ? "http://" + request.getServerName() + ":5500/"
                : landingUrl;
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, target)
                .build();
    }
}
