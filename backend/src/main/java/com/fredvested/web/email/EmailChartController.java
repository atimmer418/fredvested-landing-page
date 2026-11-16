package com.fredvested.web.email;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fredvested.web.model.WaitlistEntry;
import com.fredvested.web.repository.WaitlistRepository;
import com.fredvested.web.service.ConfirmationTokens;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * The welcome email's growth chart, GET /api/email/chart/{token}.png. The token is the
 * one minted for the row at send time (only its SHA-256 is stored, chart_token_hash, V9);
 * the chart is drawn on request from the row's own stored projection (V8), exactly as
 * the inline attachment was, so the picture is the same and nothing is recomputed.
 *
 * Why a URL and not a cid attachment: some mail apps never resolve cid images (the Zoho
 * Mail iOS app showed a broken image where its webmail showed the chart, 2026-11-15),
 * while every client renders an image URL once images are loaded. The URL carries an
 * unguessable token and nothing else: no address, no number, no id. Nothing about the
 * request is logged or stored, so a fetch is not treated as an open (opens are not
 * tracked, by policy). Anything that does not resolve is a bare 404 with no body, the
 * same for a malformed token, an unknown one and a row without a projection.
 */
@RestController
@RequestMapping("/api/email/chart")
public class EmailChartController {

    /** A year, immutable: the picture for a token never changes, and mail-client image proxies may cache it. */
    static final String CACHE_CONTROL = "private, max-age=31536000, immutable";
    public static final String PATH = "/api/email/chart/";
    public static final String SUFFIX = ".png";

    private final WaitlistRepository waitlist;
    private final ObjectMapper objectMapper;

    public EmailChartController(WaitlistRepository waitlist, ObjectMapper objectMapper) {
        this.waitlist = waitlist;
        this.objectMapper = objectMapper;
    }

    /** The URL the email carries for a raw token, on the API's public origin. */
    public static String url(String apiPublicUrl, String rawToken) {
        return apiPublicUrl + PATH + rawToken + SUFFIX;
    }

    @GetMapping("/{token:[A-Za-z0-9_-]{16,128}}.png")
    public ResponseEntity<byte[]> chart(@PathVariable String token) {
        String hash = ConfirmationTokens.hash(token);
        if (hash == null) return ResponseEntity.notFound().build();
        Optional<WaitlistEntry> entry = waitlist.findByChartTokenHash(hash);
        if (entry.isEmpty()) return ResponseEntity.notFound().build();
        Projection projection = ProjectionJson.read(objectMapper, entry.get().getProjectionJson());
        if (projection == null) return ResponseEntity.notFound().build();
        GrowthChartRenderer.Png png;
        try {
            png = GrowthChartRenderer.render(projection);
        } catch (RuntimeException e) {
            // A stored document the renderer refuses (every row the API wrote passed the same
            // bounds, so only a hand-edited one): the same bare 404 as the other cases, never
            // the API's JSON error and never a stack trace per fetch.
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + GrowthChartRenderer.FILENAME + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .header("X-Robots-Tag", "noindex")
                .body(png.bytes());
    }

    /** Everything else under /api/email/chart: a bare 404 rather than the API's JSON error. */
    @RequestMapping("/**")
    public ResponseEntity<Void> notFound() {
        return ResponseEntity.notFound().build();
    }
}
