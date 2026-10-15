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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The verifier is real (signing with the configured secret); the processor is mocked.
@WebMvcTest(ResendWebhookController.class)
@Import(ResendWebhookVerifier.class)
@TestPropertySource(properties = "resend.webhook-secret=whsec_dGVzdC1zZWNyZXQ=") // base64("test-secret")
class ResendWebhookControllerTest {

    private static final byte[] SECRET = "test-secret".getBytes(StandardCharsets.UTF_8);
    private static final String BODY = "{\"type\":\"email.delivered\",\"created_at\":\"2026-09-23T12:00:00.000Z\",\"data\":{\"email_id\":\"re_123\",\"to\":[\"a@example.com\"]}}";

    @Autowired MockMvc mockMvc;
    @MockBean EmailEventProcessor processor;

    static String sign(String id, String ts, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET, "HmacSHA256"));
        mac.update((id + "." + ts + ".").getBytes(StandardCharsets.UTF_8));
        mac.update(body.getBytes(StandardCharsets.UTF_8));
        return "v1," + Base64.getEncoder().encodeToString(mac.doFinal());
    }

    static String now() {
        return String.valueOf(Instant.now().getEpochSecond());
    }

    // Opens are not tracked: an email.opened (or any untracked type) is 200 "ok" like a
    // recorded one, so Resend never retries it and nothing distinguishes it on the wire.
    @Test
    void ignoredType_is200Ok_indistinguishableFromRecorded() throws Exception {
        when(processor.process(eq("msg_open"), any(), any())).thenReturn(EmailEventProcessor.Outcome.IGNORED);
        String body = "{\"type\":\"email.opened\",\"created_at\":\"2026-09-23T12:00:00.000Z\",\"data\":{\"email_id\":\"re_123\"}}";
        String ts = now();
        mockMvc.perform(post("/api/webhooks/resend").contentType(MediaType.APPLICATION_JSON)
                .header("svix-id", "msg_open").header("svix-timestamp", ts).header("svix-signature", sign("msg_open", ts, body))
                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void validSignedPayload_isAcceptedAndPersisted() throws Exception {
        when(processor.process(eq("msg_1"), any(), any())).thenReturn(EmailEventProcessor.Outcome.RECORDED);
        String ts = now();
        mockMvc.perform(post("/api/webhooks/resend").contentType(MediaType.APPLICATION_JSON)
                .header("svix-id", "msg_1").header("svix-timestamp", ts).header("svix-signature", sign("msg_1", ts, BODY))
                .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
        verify(processor).process(eq("msg_1"), eq(BODY.getBytes(StandardCharsets.UTF_8)), any());
    }

    @Test
    void tamperedBody_withAValidLookingSignature_is401_andNothingIsProcessed() throws Exception {
        String ts = now();
        String sigForOriginal = sign("msg_1", ts, BODY);
        String tampered = BODY.replace("email.delivered", "email.bounced");
        mockMvc.perform(post("/api/webhooks/resend").contentType(MediaType.APPLICATION_JSON)
                .header("svix-id", "msg_1").header("svix-timestamp", ts).header("svix-signature", sigForOriginal)
                .content(tampered))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(processor);
    }

    @Test
    void correctSignature_butTenMinuteOldTimestamp_is400() throws Exception {
        String ts = String.valueOf(Instant.now().getEpochSecond() - 600);
        mockMvc.perform(post("/api/webhooks/resend").contentType(MediaType.APPLICATION_JSON)
                .header("svix-id", "msg_1").header("svix-timestamp", ts).header("svix-signature", sign("msg_1", ts, BODY))
                .content(BODY))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(processor);
    }

    @Test
    void missingSignatureHeaders_is401() throws Exception {
        mockMvc.perform(post("/api/webhooks/resend").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(processor);
    }

    @Test
    void duplicateDelivery_is200() throws Exception {
        when(processor.process(eq("msg_dup"), any(), any())).thenReturn(EmailEventProcessor.Outcome.DUPLICATE);
        String ts = now();
        mockMvc.perform(post("/api/webhooks/resend").contentType(MediaType.APPLICATION_JSON)
                .header("svix-id", "msg_dup").header("svix-timestamp", ts).header("svix-signature", sign("msg_dup", ts, BODY))
                .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("duplicate"));
    }

    @Test
    void unparseableButCorrectlySignedBody_is400() throws Exception {
        String bad = "{not json";
        when(processor.process(eq("msg_bad"), any(), any())).thenThrow(new com.fasterxml.jackson.core.JsonParseException(null, "bad"));
        String ts = now();
        mockMvc.perform(post("/api/webhooks/resend").contentType(MediaType.APPLICATION_JSON)
                .header("svix-id", "msg_bad").header("svix-timestamp", ts).header("svix-signature", sign("msg_bad", ts, bad))
                .content(bad))
                .andExpect(status().isBadRequest());
    }

    @Test
    void oversizedBody_is413_beforeAnyVerification() throws Exception {
        byte[] big = new byte[ResendWebhookController.MAX_BODY_BYTES + 1];
        java.util.Arrays.fill(big, (byte) 'a');
        mockMvc.perform(post("/api/webhooks/resend").contentType(MediaType.APPLICATION_JSON)
                .header("svix-id", "msg_big").header("svix-timestamp", now()).header("svix-signature", "v1,AAAA")
                .content(big))
                .andExpect(status().isPayloadTooLarge());
        verifyNoInteractions(processor);
    }

    @Test
    void racingDuplicate_thatHitsTheUniqueConstraint_is200Duplicate() throws Exception {
        when(processor.process(eq("msg_race"), any(), any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uk_email_event_svix_id"));
        String ts = now();
        mockMvc.perform(post("/api/webhooks/resend").contentType(MediaType.APPLICATION_JSON)
                .header("svix-id", "msg_race").header("svix-timestamp", ts).header("svix-signature", sign("msg_race", ts, BODY))
                .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("duplicate"));
    }

    @Test
    void badSignature_usesTheGenericUnauthorizedBody() throws Exception {
        mockMvc.perform(post("/api/webhooks/resend").contentType(MediaType.APPLICATION_JSON)
                .header("svix-id", "msg_1").header("svix-timestamp", now()).header("svix-signature", "v1,AAAA")
                .content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthorized"))
                .andExpect(jsonPath("$.message").value("Invalid webhook signature."));
    }
}
