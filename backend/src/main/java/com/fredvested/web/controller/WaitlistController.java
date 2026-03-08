package com.fredvested.web.controller;

import com.fredvested.web.model.WaitlistEntry;
import com.fredvested.web.repository.WaitlistRepository;
import com.fredvested.web.service.RateLimiterService;
import com.fredvested.web.service.TurnstileService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/waitlist")
public class WaitlistController {

    @Autowired
    private WaitlistRepository repository;

    @Autowired
    private TurnstileService turnstileService;

    @Autowired
    private RateLimiterService rateLimiterService;

    // --- DTOs for Request/Response ---
    @Data
    public static class WaitlistRequest {
        private String email;
        private Integer freedomAge;
        private String turnstileToken;
    }

    // --- GET: Fetch Stats on Load ---
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        return ResponseEntity.ok(buildStatsMap("success"));
    }

    // --- POST: Handle Form Submission ---
    @PostMapping
    public ResponseEntity<?> joinWaitlist(@RequestBody WaitlistRequest request, HttpServletRequest httpRequest) {
       
        // 0. Rate limit by IP
        String rawIp = httpRequest.getHeader("CF-Connecting-IP");
        if (rawIp == null) rawIp = httpRequest.getRemoteAddr();
        String hashedIp = hashIp(rawIp);

        if (!rateLimiterService.isAllowed(hashedIp)) {
            return ResponseEntity.status(429)
                .body(Map.of("message", "Too many requests. Please try again in a minute."));
        }

        // 1. Verify Cloudflare Turnstile
        if (!turnstileService.verifyToken(request.getTurnstileToken())) {
            return ResponseEntity.badRequest().body(Map.of("message", "Security check failed."));
        }

        String email = request.getEmail().trim().toLowerCase();

        // 2. Check if already joined
        if (repository.existsByEmail(email)) {
            Map<String, Object> response = buildStatsMap("already_joined");
            return ResponseEntity.ok(response);
        }

        // 3. Determine Status based on Cap
        long foundersCount = repository.countByStatus(WaitlistEntry.WaitlistStatus.WAITLISTFOUNDER);
        WaitlistEntry.WaitlistStatus newStatus = (foundersCount < 300)
            ? WaitlistEntry.WaitlistStatus.WAITLISTFOUNDER
            : WaitlistEntry.WaitlistStatus.WAITLISTNORMAL;

        // 4. Save Entry
        WaitlistEntry entry = new WaitlistEntry();
        entry.setEmail(email);
        entry.setFreedomAge(request.getFreedomAge());
        entry.setStatus(newStatus);
        entry.setIpHash(hashedIp);
        repository.save(entry);

        // 5. Return updated stats and status
        return ResponseEntity.ok(buildStatsMap(newStatus.name()));
    }

    // Helper to package the current stats
    private Map<String, Object> buildStatsMap(String status) {
        Map<String, Object> map = new HashMap<>();
        map.put("status", status);
        map.put("count", repository.count()); // Total rows in the table
        Double avg = repository.getAverageFreedomAge();
        map.put("avgFreedomAge", avg != null ? avg : 0.0);
        return map;
    }

    // Helper to Hash the IP Address (SHA-256)
    private String hashIp(String ip) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(ip.getBytes("UTF-8"));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception ex) {
            return "hash_error";
        }
    }
}