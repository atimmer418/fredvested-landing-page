package com.fredvested.web.controller;

import com.fredvested.web.model.WaitlistEntry;
import com.fredvested.web.repository.WaitlistRepository;
import com.fredvested.web.service.EmailService;
import com.fredvested.web.service.RateLimiterService;
import com.fredvested.web.service.TurnstileService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.security.MessageDigest;
import java.time.LocalDateTime;
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

    @Autowired
    private EmailService emailService;

    private static final Logger log = LoggerFactory.getLogger(WaitlistController.class);

    // US-residents-only gate driven by Cloudflare's CF-IPCountry header. Off by default
    // so dev/local (no Cloudflare in front) keep working; prod turns it on.
    @Value("${waitlist.us-only:false}")
    private boolean usOnly;

    // --- DTOs for Request/Response ---
    @Data
    public static class WaitlistRequest {
        private String email;
        private Integer freedomAge;
        private Integer age;
        private Integer investMonthly;
        private Integer retireMonthly;
        private Boolean interacted;
        private Integer returnAssumptionPct;
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
       
        // -1. Geo gate: only an explicit "US" passes. Missing header, unknown (XX) and
        // Tor (T1) are all rejected. Blocked attempts are not logged or stored.
        if (usOnly && !"US".equalsIgnoreCase(httpRequest.getHeader("CF-IPCountry"))) {
            return ResponseEntity.status(403)
                .body(Map.of("message", "FRED is currently available to US residents only."));
        }

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

        // 2. Check if already joined — return their real status so the frontend can store it
        if (repository.existsByEmail(email)) {
            WaitlistEntry existing = repository.findByEmail(email);
            Map<String, Object> response = buildStatsMap("already_joined");
            response.put("realStatus", existing.getStatus().name());
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
        entry.setCurrentAge(clampOrNull(request.getAge(), 18, 60));
        entry.setInvestMonthly(clampOrNull(request.getInvestMonthly(), 0, 10000));
        entry.setRetireMonthly(clampOrNull(request.getRetireMonthly(), 1000, 30000));
        entry.setInteracted(request.getInteracted());
        // Range, not a fixed set, so retuning the frontend scenarios never silently nulls the data.
        entry.setReturnAssumptionPct(clampOrNull(request.getReturnAssumptionPct(), 1, 30));
        entry.setStatus(newStatus);
        entry.setIpHash(hashedIp);
        repository.save(entry);

        // 5. Send confirmation email (failure must not affect signup response)
        try {
            emailService.sendConfirmationEmail(email);
        } catch (Exception e) {
            log.error("Failed to send confirmation email to {}: {}", email, e.getMessage());
        }

        // 6. Return updated stats and status
        return ResponseEntity.ok(buildStatsMap(newStatus.name()));
    }

    // Helper to package the current stats
    private Map<String, Object> buildStatsMap(String status) {
        Map<String, Object> map = new HashMap<>();
        map.put("status", status);
        map.put("count", repository.count()); // Total rows in the table
        // Founder-cap occupancy: statuses like INVITED/CLAIMED leave the founder bucket
        // without leaving the table, so the cap UI must not be driven by total count
        map.put("founderCount", repository.countByStatus(WaitlistEntry.WaitlistStatus.WAITLISTFOUNDER));
        Double avg = repository.getAverageFreedomAge();
        map.put("avgFreedomAge", avg != null ? avg : 0.0);
        // How many members' projections are inside that average; the frontend
        // discloses this sample size and gates the stat tile on it.
        map.put("projectionCount", repository.countHeadStartProjections());
        // Measurement period of those same rows (ISO yyyy-MM-dd, Eastern), disclosed with the stat.
        // Null when no rows qualify; the frontend does not render the stat without both dates.
        LocalDateTime firstProjection = repository.getFirstProjectionAt();
        LocalDateTime lastProjection = repository.getLastProjectionAt();
        map.put("projectionStartDate", firstProjection != null ? firstProjection.toLocalDate().toString() : null);
        map.put("projectionEndDate", lastProjection != null ? lastProjection.toLocalDate().toString() : null);
        return map;
    }

    // Out-of-range values become null rather than clamped: a clamped value would
    // fabricate a data point the user never chose. Bounds mirror the frontend sliders.
    private static Integer clampOrNull(Integer v, int min, int max) {
        return (v == null || v < min || v > max) ? null : v;
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