package com.fredvested.web.controller;

import com.fredvested.web.model.WaitlistEntry;
import com.fredvested.web.repository.WaitlistRepository;
import com.fredvested.web.service.AddressRateLimiter;
import com.fredvested.web.service.RateLimiterService;
import com.fredvested.web.service.SignupService;
import com.fredvested.web.service.TurnstileService;
import com.fredvested.web.util.AttributionSanitizer;
import com.fredvested.web.util.FreedomCalculator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

import static com.fredvested.web.controller.ApiErrorHandler.error;

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
    private SignupService signupService;

    // Bounds the confirmation emails one address can be sent through the signup form
    // (an unconfirmed address re-submitted gets a fresh link); same limiter as /resend-confirmation.
    @Autowired
    private AddressRateLimiter addressLimiter;

    private static final Logger log = LoggerFactory.getLogger(WaitlistController.class);
    private static final ZoneId EASTERN = ZoneId.of("America/New_York");

    // US-residents-only gate driven by Cloudflare's CF-IPCountry header. Off by default
    // so dev/local (no Cloudflare in front) keep working; prod turns it on.
    @Value("${waitlist.us-only:false}")
    private boolean usOnly;

    // GET /stats is public and fetched on every page load, and it runs six aggregate
    // queries. The result is memoised for this long; a signup invalidates it.
    @Value("${waitlist.stats-cache-ms:30000}")
    private long statsCacheMs;
    private volatile Map<String, Object> cachedStats;
    private volatile long cachedStatsAt;

    // --- DTOs for Request/Response ---
    @Data
    public static class WaitlistRequest {
        // The only field that can reject a request. Everything else is sanitised or
        // dropped: losing a UTM tag is acceptable, losing a signup is not.
        @NotBlank
        @Email
        @Size(max = 254)
        private String email;
        private String turnstileToken;

        // Calculator inputs
        private Integer age;
        private Integer investMonthly;
        private Integer retireMonthly;
        private Boolean interacted;
        private Integer returnAssumptionPct;

        // The client's own projection. Never stored as-is: the server recomputes from the
        // inputs and logs a warning if the two disagree (drift between the implementations).
        private Integer freedomAge;
        private String freedomDate;      // yyyy-MM-dd
        private Long portfolioTarget;
        private Boolean revealedBeforeSubmit;

        // Attribution snapshot from frontend/assets/attribution.js
        private String utmSource;
        private String utmMedium;
        private String utmCampaign;
        private String utmContent;
        private String utmTerm;
        private String firstUtmSource;
        private String firstUtmCampaign;
        private String firstUtmContent;
        private String firstTouchAt;     // ISO-8601 instant
        private String referrerHost;
        private String landingPath;
        private String deviceType;
    }

    // Under double opt-in the public numbers change on confirmation, not on signup;
    // SignupService announces both, and the memo drops its snapshot.
    @EventListener(SignupService.WaitlistCountsChanged.class)
    public void onWaitlistCountsChanged() {
        cachedStats = null;
    }

    // --- GET: Fetch Stats on Load ---
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        long now = System.currentTimeMillis();
        Map<String, Object> cached = cachedStats;
        if (statsCacheMs > 0 && cached != null && now - cachedStatsAt < statsCacheMs) {
            return ResponseEntity.ok(cached);
        }
        Map<String, Object> fresh = buildStatsMap("success");
        cachedStatsAt = now;
        cachedStats = fresh;
        return ResponseEntity.ok(fresh);
    }

    // --- POST: Handle Form Submission ---
    @PostMapping
    public ResponseEntity<?> joinWaitlist(@Valid @RequestBody WaitlistRequest request, HttpServletRequest httpRequest) {

        // -1. Geo gate: only an explicit "US" passes. Missing header, unknown (XX) and
        // Tor (T1) are all rejected. Blocked attempts are not logged or stored.
        if (usOnly && !"US".equalsIgnoreCase(httpRequest.getHeader("CF-IPCountry"))) {
            return ResponseEntity.status(403)
                .body(error("geo_blocked", "FRED is currently available to US residents only."));
        }

        // 0. Rate limit by IP
        String rawIp = httpRequest.getHeader("CF-Connecting-IP");
        if (rawIp == null) rawIp = httpRequest.getRemoteAddr();
        String hashedIp = hashIp(rawIp);

        if (!rateLimiterService.isAllowed(hashedIp)) {
            return ResponseEntity.status(429)
                .body(error("rate_limited", "Too many requests. Please try again in a minute."));
        }

        // 1. Verify Cloudflare Turnstile
        if (!turnstileService.verifyToken(request.getTurnstileToken())) {
            return ResponseEntity.badRequest().body(error("captcha_failed", "Security check failed."));
        }

        String email = request.getEmail().trim().toLowerCase();

        // 2. Check if already joined — return their real status so the frontend can store it
        if (repository.existsByEmail(email)) {
            WaitlistEntry existing = repository.findByEmail(email);
            if (signupService.isDoubleOptIn() && existing != null && !existing.isConfirmed()) {
                // On the list but never confirmed: the person most likely lost or never got
                // the email, so re-entering the address queues a fresh confirmation and is
                // answered exactly like a fresh signup. Nothing in the response says the
                // address was already known, so this is not a confirmation-status oracle.
                if (addressLimiter.allow(email)) signupService.requestResend(email);
                Map<String, Object> response = buildStatsMap(WaitlistEntry.WaitlistStatus.WAITLISTNORMAL.name());
                response.put("requiresConfirmation", true);
                return ResponseEntity.ok(response);
            }
            Map<String, Object> response = buildStatsMap("already_joined");
            response.put("realStatus", existing != null && existing.getStatus() != null
                    ? existing.getStatus().name() : WaitlistEntry.WaitlistStatus.WAITLISTNORMAL.name());
            return ResponseEntity.ok(response);
        }

        // 3. Build the entry. Its status (the founder cap) is decided by SignupService:
        // at confirmation under double opt-in, at signup with the flag off.
        WaitlistEntry entry = new WaitlistEntry();
        entry.setEmail(email);
        entry.setCurrentAge(clampOrNull("age", request.getAge(), 18, 60));
        entry.setInvestMonthly(clampOrNull("investMonthly", request.getInvestMonthly(), 0, 10000));
        entry.setRetireMonthly(clampOrNull("retireMonthly", request.getRetireMonthly(), 1000, 30000));
        entry.setInteracted(request.getInteracted());
        // Range, not a fixed set, so retuning the frontend scenarios never silently nulls the data.
        entry.setReturnAssumptionPct(clampOrNull("returnAssumptionPct", request.getReturnAssumptionPct(), 1, 30));
        applyProjection(entry, request);
        applyAttribution(entry, request);
        entry.setRevealedBeforeSubmit(Boolean.TRUE.equals(request.getRevealedBeforeSubmit()));
        entry.setIpHash(hashedIp);
        // Saves the row and its pending email (confirmation, or the welcome email when
        // double opt-in is off) in one transaction. Nothing calls Resend on this thread:
        // the outbox publisher sends later and retries, so a Resend outage never costs
        // a signup and the HTTP response never waits on it.
        WaitlistEntry saved = signupService.createSignup(entry);
        if (saved == null) saved = entry;
        cachedStats = null;

        // 4. Return updated stats and status. requiresConfirmation tells the page whether a
        // confirmation click is still needed (double opt-in), so its success copy can say so.
        WaitlistEntry.WaitlistStatus status = saved.getStatus() != null ? saved.getStatus() : WaitlistEntry.WaitlistStatus.WAITLISTNORMAL;
        Map<String, Object> response = buildStatsMap(status.name());
        response.put("requiresConfirmation", signupService.isDoubleOptIn());
        return ResponseEntity.ok(response);
    }

    // Recompute the projection from the (already bounded) inputs and store the server's
    // numbers. The client's numbers are only compared against them: a mismatch beyond
    // rounding means frontend compute() and FreedomCalculator have drifted.
    private void applyProjection(WaitlistEntry entry, WaitlistRequest request) {
        Integer age = entry.getCurrentAge();
        Integer invest = entry.getInvestMonthly();
        Integer retire = entry.getRetireMonthly();
        Integer pct = entry.getReturnAssumptionPct();
        if (age == null || invest == null || retire == null || pct == null) {
            if (request.getFreedomAge() != null) {
                log.debug("Client projection ignored: calculator inputs incomplete or out of range");
            }
            return;
        }
        FreedomCalculator.Result server = FreedomCalculator.compute(age, invest, retire, pct, LocalDate.now(EASTERN));
        entry.setFreedomAge(server.freedomAge());
        entry.setComputedFreedomDate(server.freedomDate());
        entry.setComputedPortfolioTarget(server.portfolioTarget());
        warnIfDrifted(request, server);
    }

    private void warnIfDrifted(WaitlistRequest request, FreedomCalculator.Result server) {
        Integer clientAge = request.getFreedomAge();
        boolean sameAge = (clientAge == null && server.freedomAge() == null)
            || (clientAge != null && server.freedomAge() != null && Math.abs(clientAge - server.freedomAge()) <= 1);
        if (!sameAge) {
            log.warn("Projection drift: freedomAge client={} server={}", clientAge, server.freedomAge());
        }

        if (request.getFreedomDate() != null) {
            LocalDate clientDate = null;
            try { clientDate = LocalDate.parse(request.getFreedomDate()); } catch (RuntimeException ignored) { /* logged below */ }
            boolean sameDate = clientDate != null && server.freedomDate() != null
                && Math.abs(ChronoUnit.MONTHS.between(server.freedomDate(), clientDate)) <= 1;
            if (!sameDate) {
                log.warn("Projection drift: freedomDate client={} server={}", request.getFreedomDate(), server.freedomDate());
            }
        }

        if (request.getPortfolioTarget() != null && Math.abs(request.getPortfolioTarget() - server.portfolioTarget()) > 1) {
            log.warn("Projection drift: portfolioTarget client={} server={}", request.getPortfolioTarget(), server.portfolioTarget());
        }
    }

    // Sanitised copies of the attribution fields. A malformed value becomes null; nothing
    // here can reject the signup.
    private void applyAttribution(WaitlistEntry entry, WaitlistRequest request) {
        try {
            entry.setUtmSource(AttributionSanitizer.token(request.getUtmSource()));
            entry.setUtmMedium(AttributionSanitizer.token(request.getUtmMedium()));
            entry.setUtmCampaign(AttributionSanitizer.token(request.getUtmCampaign()));
            entry.setUtmContent(AttributionSanitizer.token(request.getUtmContent()));
            entry.setUtmTerm(AttributionSanitizer.token(request.getUtmTerm()));
            entry.setFirstUtmSource(AttributionSanitizer.token(request.getFirstUtmSource()));
            entry.setFirstUtmCampaign(AttributionSanitizer.token(request.getFirstUtmCampaign()));
            entry.setFirstUtmContent(AttributionSanitizer.token(request.getFirstUtmContent()));
            entry.setFirstTouchAt(AttributionSanitizer.instantToEastern(request.getFirstTouchAt()));
            entry.setReferrerHost(AttributionSanitizer.host(request.getReferrerHost()));
            entry.setLandingPath(AttributionSanitizer.path(request.getLandingPath()));
            entry.setDeviceType(AttributionSanitizer.deviceType(request.getDeviceType()));
        } catch (RuntimeException e) {
            log.warn("Attribution dropped for a signup: {}", e.getClass().getSimpleName());
        }
    }

    // Helper to package the current stats
    private Map<String, Object> buildStatsMap(String status) {
        Map<String, Object> map = new HashMap<>();
        map.put("status", status);
        // Confirmed rows only (double opt-in, legacy backfill, single opt-in): an address
        // nobody has confirmed is never part of a public number.
        map.put("count", repository.countByConfirmedAtIsNotNull());
        // Founder-cap occupancy: statuses like INVITED/CLAIMED leave the founder bucket
        // without leaving the table, so the cap UI must not be driven by total count
        map.put("founderCount", repository.countByStatusAndConfirmedAtIsNotNull(WaitlistEntry.WaitlistStatus.WAITLISTFOUNDER));
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
    // fabricate a data point the user never chose. Bounds mirror the frontend sliders,
    // so a hit here is a tampered request -- logged (field and direction only, never
    // the value) because a spike is a bot signature.
    private static Integer clampOrNull(String field, Integer v, int min, int max) {
        if (v == null) return null;
        if (v < min || v > max) {
            log.warn("Calculator input out of range: field={} direction={}", field, v < min ? "below" : "above");
            return null;
        }
        return v;
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
