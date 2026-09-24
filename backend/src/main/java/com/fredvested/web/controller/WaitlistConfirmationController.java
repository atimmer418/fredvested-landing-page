package com.fredvested.web.controller;

import com.fredvested.web.model.WaitlistEntry;
import com.fredvested.web.service.AddressRateLimiter;
import com.fredvested.web.service.LandingUrls;
import com.fredvested.web.service.RateLimiterService;
import com.fredvested.web.service.SignupService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

import static com.fredvested.web.controller.ApiErrorHandler.error;

/**
 * Confirmation, resend and unsubscribe. Every response here is deliberately
 * uninformative about whether an address is on the list: unknown and
 * already-used tokens produce byte-identical redirects, and a resend request
 * answers the same way whether or not the address exists.
 *
 * Both emailed links are two-step. The GET renders a small self-contained page
 * and has no side effect; the POST behind it does the work. Mail security
 * scanners fetch every link in an inbound message, and a bare GET would have
 * consumed the single-use confirmation token (the human then lands on
 * "invalid") or silently unsubscribed the recipient. The page's inline script
 * submits the form on load, so a human still gets one-click behaviour;
 * scanners fetch HTML and almost never execute JavaScript, so the token
 * survives them. A visible button inside noscript is the fallback for anyone
 * with JavaScript off. HEAD is a no-op too.
 */
@RestController
@RequestMapping("/api/waitlist")
public class WaitlistConfirmationController {

    private final SignupService signupService;
    private final LandingUrls landingUrls;
    private final RateLimiterService ipLimiter;
    private final AddressRateLimiter addressLimiter;

    public WaitlistConfirmationController(SignupService signupService, LandingUrls landingUrls,
                                          RateLimiterService ipLimiter, AddressRateLimiter addressLimiter) {
        this.signupService = signupService;
        this.landingUrls = landingUrls;
        this.ipLimiter = ipLimiter;
        this.addressLimiter = addressLimiter;
    }

    @Data
    public static class ResendRequest {
        @NotBlank
        @Email
        @Size(max = 254)
        private String email;
    }

    /**
     * Step one: no side effect, and the same page for every token (it never
     * looks the token up, so it reveals nothing). The script posts the form on
     * load; the button inside noscript is the only other way to submit it.
     */
    @GetMapping(value = "/confirm", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> confirmPage(@RequestParam(value = "token", required = false) String token) {
        return autoPostPage("Confirm your email",
                "<p id=\"auto\">Confirming your email address&hellip;</p>",
                "/api/waitlist/confirm", token,
                "<p>Click the button to confirm your email address.</p>", "Confirm my spot");
    }

    /** Step two: the act. Unknown and already-used tokens are indistinguishable here. */
    @PostMapping(value = "/confirm", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> confirm(@RequestParam(value = "token", required = false) String token,
                                        HttpServletRequest request) {
        SignupService.Confirmation result = signupService.confirm(token);
        String query = switch (result.outcome()) {
            case CONFIRMED -> "status=confirmed&hours=" + URLEncoder.encode(result.hoursBand(), StandardCharsets.UTF_8) + tier(result.status());
            case EXPIRED -> "status=expired";
            case INVALID -> "status=invalid";
        };
        return redirect(landingUrls.page(request, "confirmed", query));
    }

    // The decided tier travels with the redirect so the landing pages can show the right
    // status afterwards (the founder slot is decided at confirmation, not at signup).
    private static String tier(WaitlistEntry.WaitlistStatus status) {
        if (status == WaitlistEntry.WaitlistStatus.WAITLISTFOUNDER) return "&tier=founder";
        if (status == WaitlistEntry.WaitlistStatus.WAITLISTNORMAL) return "&tier=normal";
        return "";
    }

    // Link scanners that probe with HEAD must not consume the single-use token.
    @RequestMapping(value = "/confirm", method = RequestMethod.HEAD)
    public ResponseEntity<Void> confirmHead() {
        return ResponseEntity.ok().build();
    }

    @PostMapping("/resend-confirmation")
    public ResponseEntity<Map<String, String>> resend(@Valid @RequestBody ResendRequest body, HttpServletRequest request) {
        String email = body.getEmail().trim().toLowerCase();
        String rawIp = request.getHeader("CF-Connecting-IP");
        if (rawIp == null) rawIp = request.getRemoteAddr();
        // Both limiters run before any lookup, so their answers say nothing about the list.
        if (!ipLimiter.isAllowed(hashIp(rawIp)) || !addressLimiter.allow(email)) {
            return ResponseEntity.status(429).body(error("rate_limited", "Too many requests. Please try again later."));
        }
        signupService.requestResend(email);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    /**
     * Step one: no side effect, and deliberately NO auto-submit here. A suppression is
     * never cleared, so a mail security sandbox that does execute JavaScript (Defender
     * Safe Links, Proofpoint, Mimecast) must not be able to unsubscribe the recipient
     * on delivery; a human click is required. Mail clients get one-click unsubscribe
     * through the RFC 8058 List-Unsubscribe headers on every email instead.
     * (A consumed confirmation token is recoverable via resend, so the confirm page
     * can afford the auto-submit; this one cannot.)
     */
    @GetMapping(value = "/unsubscribe", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> unsubscribePage(@RequestParam(value = "token", required = false) String token) {
        if (!signupService.hasUnsubscribeToken(token)) {
            return htmlPage("Unsubscribe link not valid",
                    "<p>This unsubscribe link isn't valid. It may have been cut short by your email app.</p>"
                    + "<p class=\"muted\">Need help? Write to help@fredvested.com.</p>");
        }
        return htmlPage("Unsubscribe from FRED emails",
                "<p>Click below to stop receiving emails from FRED at this address.</p>"
                + "<form method=\"post\" action=\"/api/waitlist/unsubscribe\">"
                + "<input type=\"hidden\" name=\"token\" value=\"" + escape(token) + "\">"
                + "<button type=\"submit\">Unsubscribe</button></form>");
    }

    /**
     * The shared two-step page: a form the inline script submits on load, with the
     * fallback button (the only control) inside noscript. The "working" line ships
     * hidden and the script reveals it, so with scripts off the page shows only
     * the intro and the button.
     */
    private static ResponseEntity<String> autoPostPage(String title, String workingLine, String action, String token,
                                                       String noscriptIntro, String buttonLabel) {
        String body = workingLine.replace("<p id=\"auto\">", "<p id=\"auto\" hidden>")
                + "<form id=\"f\" method=\"post\" action=\"" + action + "\">"
                + "<input type=\"hidden\" name=\"token\" value=\"" + escape(token) + "\">"
                + "<noscript>" + noscriptIntro + "<button type=\"submit\">" + buttonLabel + "</button></noscript>"
                + "</form>"
                + "<script>document.getElementById('auto').hidden=false;document.getElementById('f').submit();</script>";
        return htmlPage(title, body);
    }

    private static ResponseEntity<String> htmlPage(String title, String body) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.TEXT_HTML)
                .body(page(title, body));
    }

    /**
     * Step two: the human clicked the button (302 to the landing page), or a mail client
     * performed an RFC 8058 one-click unsubscribe: a POST to the List-Unsubscribe URL
     * (token in the query) with the body "List-Unsubscribe=One-Click", answered 200 with
     * no redirect and the same body whether or not the token was known.
     */
    @PostMapping(value = "/unsubscribe", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<?> unsubscribe(@RequestParam(value = "token", required = false) String token,
                                         @RequestParam(value = "List-Unsubscribe", required = false) String oneClick,
                                         HttpServletRequest request) {
        boolean done = signupService.unsubscribe(token);
        if ("One-Click".equals(oneClick)) {
            return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body("Unsubscribed.");
        }
        return redirect(landingUrls.page(request, "confirmed", done ? "status=unsubscribed" : "status=invalid"));
    }

    private static ResponseEntity<Void> redirect(String location) {
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, location).build();
    }

    // Self-contained: no stylesheet, script, font or image from anywhere.
    private static String page(String title, String body) {
        return "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<meta name=\"robots\" content=\"noindex\"><title>" + escape(title) + " - FRED</title>"
                + "<style>body{margin:0;background:#f6f6f8;font-family:Helvetica,Arial,sans-serif;color:#0F172A}"
                + "main{max-width:480px;margin:48px auto;padding:32px;background:#fff;border:1px solid #e2e8f0;border-radius:16px}"
                + "h1{font-size:22px;margin:0 0 16px}p{line-height:1.6;color:#334155}.muted{font-size:13px;color:#64748B}"
                + "button{background:#135bec;color:#fff;border:0;border-radius:10px;padding:12px 24px;font-size:16px;font-weight:700;cursor:pointer}"
                + ".logo{font-family:'Arial Black',Arial,sans-serif;font-style:italic;font-weight:900;font-size:28px;color:#135bec;margin-bottom:8px}</style>"
                + "</head><body><main><div class=\"logo\">FRED</div><h1>" + escape(title) + "</h1>" + body + "</main></body></html>";
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String hashIp(String ip) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(ip.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return "hash_error";
        }
    }
}
