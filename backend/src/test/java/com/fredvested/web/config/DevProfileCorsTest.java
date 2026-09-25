package com.fredvested.web.config;

import com.fredvested.web.controller.WaitlistConfirmationController;
import com.fredvested.web.service.AddressRateLimiter;
import com.fredvested.web.service.LandingUrls;
import com.fredvested.web.service.RateLimiterService;
import com.fredvested.web.service.SignupService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Loads the REAL application-dev.properties: the Cloudflare Pages previews
// (develop.<project>.pages.dev and the per-deployment hashes) must be able to
// call lpapi-dev, and nothing else may. Production's own origin is deliberately
// not allowed here: prod pages never talk to the dev API.
@WebMvcTest(WaitlistConfirmationController.class)
@Import(LandingUrls.class)
@ActiveProfiles("dev")
class DevProfileCorsTest {

    @Autowired MockMvc mockMvc;
    @MockBean SignupService signupService;
    @MockBean RateLimiterService ipLimiter;
    @MockBean AddressRateLimiter addressLimiter;

    private void preflight(String origin, boolean allowed) throws Exception {
        var result = mockMvc.perform(options("/api/waitlist/resend-confirmation")
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type"));
        if (allowed) {
            result.andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", origin));
        } else {
            result.andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        }
    }

    @Test
    void thePagesPreviews_areAllowed() throws Exception {
        preflight("https://develop.fredvested-landing-page.pages.dev", true);
        preflight("https://1a2b3c4d.fredvested-landing-page.pages.dev", true);
    }

    @Test
    void theLocalStaticServer_isStillAllowed() throws Exception {
        preflight("http://127.0.0.1:5500", true);
    }

    // The API's own origin (api.public-url, dev default lpapi-dev): the confirm and
    // unsubscribe pages it serves POST back to it with that Origin.
    @Test
    void theApiOwnOrigin_isAllowed() throws Exception {
        preflight("https://lpapi-dev.fredvested.com", true);
    }

    @Test
    void everythingElse_isRefused() throws Exception {
        preflight("https://fredvested.com", false);
        preflight("https://evil.example", false);
        preflight("https://fredvested-landing-page.pages.dev.evil.example", false);
        preflight("http://develop.fredvested-landing-page.pages.dev", false); // http, not https
    }
}
