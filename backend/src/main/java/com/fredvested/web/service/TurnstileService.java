package com.fredvested.web.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import java.util.Map;

@Service
public class TurnstileService {

    @Value("${cloudflare.turnstile.secret}")
    private String turnstileSecret;

    public boolean verifyToken(String token) {
        if (token == null || token.isEmpty()) return false;

        RestTemplate restTemplate = new RestTemplate();
        String url = "https://challenges.cloudflare.com/turnstile/v0/siteverify";

        // Cloudflare expects application/x-www-form-urlencoded POST body
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("secret", turnstileSecret);
        body.add("response", token);

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(url, request, Map.class);
            return response != null && (Boolean) response.getOrDefault("success", false);
        } catch (Exception e) {
            return false;
        }
    }
}