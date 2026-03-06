package com.fredvested.web.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
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
       
        // Cloudflare expects form data, but handles POST parameters easily
        String requestBody = "secret=" + turnstileSecret + "&response=" + token;

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = (Map<String, Object>) restTemplate.postForObject(
                url + "?" + requestBody, null, Map.class
            );
            return response != null && (Boolean) response.getOrDefault("success", false);
        } catch (Exception e) {
            return false;
        }
    }
}