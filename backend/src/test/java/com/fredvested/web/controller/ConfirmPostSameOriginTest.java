package com.fredvested.web.controller;

import com.fredvested.web.model.WaitlistEntry;
import com.fredvested.web.service.AddressRateLimiter;
import com.fredvested.web.service.LandingUrls;
import com.fredvested.web.service.RateLimiterService;
import com.fredvested.web.service.SignupService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Live on dev, 2026-09-25: the confirm page's own form POST (Origin = the API host)
// came back "Invalid CORS request". TLS ends at the edge, so Spring saw http://... and
// treated the browser's Origin as foreign. Two independent defences, each tested alone:
// forwarded headers make the request know its https origin (same-origin, CORS not
// consulted), and the API's own origin is on the CORS allow-list anyway.
@WebMvcTest(WaitlistConfirmationController.class)
@Import(LandingUrls.class)
@TestPropertySource(properties = {
        "server.forward-headers-strategy=framework",
        "api.public-url=https://lpapi-dev.fredvested.com",
        "cors.allowed.origins=http://127.0.0.1:5500",
})
class ConfirmPostSameOriginTest {

    @Autowired MockMvc mockMvc;
    @MockBean SignupService signupService;
    @MockBean RateLimiterService ipLimiter;
    @MockBean AddressRateLimiter addressLimiter;

    private void confirmed() {
        when(signupService.confirm(anyString())).thenReturn(new SignupService.Confirmation(
                SignupService.ConfirmOutcome.CONFIRMED, "<1", WaitlistEntry.WaitlistStatus.WAITLISTFOUNDER));
    }

    @Test
    void behindTheProxy_theForwardedHeadersMakeThePostSameOrigin() throws Exception {
        confirmed();
        mockMvc.perform(post("/api/waitlist/confirm")
                .header("Origin", "https://lpapi-dev.fredvested.com")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "lpapi-dev.fredvested.com")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).param("token", "tok"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("https://fredvested.com/confirmed?status=confirmed")));
    }

    @Test
    void withoutForwardedHeaders_theApiOwnOriginIsStillAllowedByCors() throws Exception {
        confirmed();
        mockMvc.perform(post("/api/waitlist/confirm")
                .header("Origin", "https://lpapi-dev.fredvested.com")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).param("token", "tok"))
                .andExpect(status().isFound());
    }

    @Test
    void aForeignOrigin_isStillRefused() throws Exception {
        confirmed();
        mockMvc.perform(post("/api/waitlist/confirm")
                .header("Origin", "https://evil.example")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).param("token", "tok"))
                .andExpect(status().isForbidden());
    }

    @Test
    void theUnsubscribeButtonPost_isSameOriginToo() throws Exception {
        when(signupService.unsubscribe("good")).thenReturn(true);
        mockMvc.perform(post("/api/waitlist/unsubscribe")
                .header("Origin", "https://lpapi-dev.fredvested.com")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "lpapi-dev.fredvested.com")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).param("token", "good"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://fredvested.com/confirmed?status=unsubscribed"));
    }
}
