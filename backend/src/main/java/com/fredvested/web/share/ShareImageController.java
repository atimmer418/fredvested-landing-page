package com.fredvested.web.share;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.Optional;
import java.util.function.Function;

/**
 * The two "Share my Freedom Date" images, drawn on request from nothing but the path:
 *
 *   GET /api/og/v1/{age}/{yyyy-mm}.png        the link preview image (og:image), 1200 x 630
 *   GET /api/og/v1/story/{age}/{yyyy-mm}.png  the story image, 1080 x 1920
 *
 * Available in every profile. The image for a path never changes, so it is cacheable for a
 * year and immutable; `v1` is in the path so a redesign gets new URLs (message apps cache
 * previews per URL). Anything ShareResult refuses, including a date that has passed since the
 * link was shared, and any other path under /api/og, is a 404 with no body. Nothing is stored
 * or logged: the path carries a rounded age and a month, never an input or an address.
 */
@RestController
@RequestMapping("/api/og")
public class ShareImageController {

    static final String CACHE_CONTROL = "public, max-age=31536000, immutable";

    private final Clock clock;

    /** A Clock bean is used when the context has one (the tests fix it); otherwise UTC wall time. */
    public ShareImageController(ObjectProvider<Clock> clock) {
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    @GetMapping("/v1/{age}/{ym}.png")
    public ResponseEntity<byte[]> link(@PathVariable String age, @PathVariable String ym) {
        return png(ShareResult.parse(age, ym, clock), ShareImageRenderer::link);
    }

    @GetMapping("/v1/story/{age}/{ym}.png")
    public ResponseEntity<byte[]> story(@PathVariable String age, @PathVariable String ym) {
        return png(ShareResult.parse(age, ym, clock), ShareImageRenderer::story);
    }

    /** Everything else under /api/og, whatever the method: a bare 404 rather than the API's JSON error. */
    @RequestMapping("/**")
    public ResponseEntity<Void> notFound() {
        return ResponseEntity.notFound().build();
    }

    private static ResponseEntity<byte[]> png(Optional<ShareResult> result, Function<ShareResult, byte[]> renderer) {
        if (result.isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .body(renderer.apply(result.get()));
    }
}
