package com.fredvested.web.controller;

import com.fredvested.web.service.EmailEventProcessor;
import com.fredvested.web.service.ResendWebhookVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// No secret configured: fail closed, and say nothing more than a bad signature would.
@WebMvcTest(ResendWebhookController.class)
@Import(ResendWebhookVerifier.class)
@TestPropertySource(properties = "resend.webhook-secret=")
class ResendWebhookControllerUnconfiguredTest {

    @Autowired MockMvc mockMvc;
    @MockBean EmailEventProcessor processor;

    @Test
    void everyDelivery_is401_withTheSameBodyAsABadSignature() throws Exception {
        mockMvc.perform(post("/api/webhooks/resend").contentType(MediaType.APPLICATION_JSON)
                .header("svix-id", "msg_1").header("svix-timestamp", "1").header("svix-signature", "v1,AAAA")
                .content("{\"type\":\"email.delivered\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthorized"))
                .andExpect(jsonPath("$.message").value("Invalid webhook signature."));
        verifyNoInteractions(processor);
    }
}
