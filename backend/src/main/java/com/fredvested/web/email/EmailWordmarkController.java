package com.fredvested.web.email;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The v2 emails' header wordmark, GET /api/email/wordmark-v1.png: one static picture for
 * every recipient ({@link WordmarkRenderer}), so the URL carries nothing about anyone and
 * a fetch says nothing about who opened what. Cached for a year as immutable; the picture
 * under a URL never changes, so a redesign of the wordmark bumps the {@code v1} in the path
 * (mail-client image proxies keep the old one under the old URL).
 */
@RestController
public class EmailWordmarkController {

    public static final String PATH = "/api/email/wordmark-v1.png";
    static final String CACHE_CONTROL = "public, max-age=31536000, immutable";
    static final String FILENAME = "fred.png";

    /** The URL the emails carry, on the API's public origin. */
    public static String url(String apiPublicUrl) {
        return apiPublicUrl + PATH;
    }

    @GetMapping(PATH)
    public ResponseEntity<byte[]> wordmark() {
        WordmarkRenderer.Png png = WordmarkRenderer.render();
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + FILENAME + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .header("X-Robots-Tag", "noindex")
                .body(png.bytes());
    }
}
