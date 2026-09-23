package com.fredvested.web.controller;

import com.fredvested.web.service.LandingUrls;
import jakarta.servlet.http.HttpServletRequest;
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

    private final LandingUrls landingUrls;

    public RootRedirectController(LandingUrls landingUrls) {
        this.landingUrls = landingUrls;
    }

    @GetMapping("/")
    public ResponseEntity<Void> root(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, landingUrls.origin(request) + "/")
                .build();
    }
}
