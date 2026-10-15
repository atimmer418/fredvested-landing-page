package com.fredvested.web.service;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class ResendWebhookVerifierTest {

    private static final String SECRET_B64 = Base64.getEncoder().encodeToString("test-secret".getBytes(StandardCharsets.UTF_8));
    private static final byte[] BODY = "{\"type\":\"email.delivered\",\"data\":{\"email_id\":\"abc\"}}".getBytes(StandardCharsets.UTF_8);
    private static final Instant NOW = Instant.ofEpochSecond(1_800_000_000L);

    static String sign(String secretB64, String id, String ts, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Base64.getDecoder().decode(secretB64), "HmacSHA256"));
        mac.update((id + "." + ts + ".").getBytes(StandardCharsets.UTF_8));
        mac.update(body);
        return "v1," + Base64.getEncoder().encodeToString(mac.doFinal());
    }

    @Test
    void validSignatureAndFreshTimestamp_isOk() throws Exception {
        ResendWebhookVerifier v = new ResendWebhookVerifier("whsec_" + SECRET_B64);
        String ts = String.valueOf(NOW.getEpochSecond() - 30);
        assertEquals(ResendWebhookVerifier.Result.OK, v.verify("msg_1", ts, sign(SECRET_B64, "msg_1", ts, BODY), BODY, NOW));
    }

    @Test
    void tamperedBody_isRejected_evenWithAValidLookingSignature() throws Exception {
        ResendWebhookVerifier v = new ResendWebhookVerifier("whsec_" + SECRET_B64);
        String ts = String.valueOf(NOW.getEpochSecond());
        String sig = sign(SECRET_B64, "msg_1", ts, BODY);
        byte[] tampered = "{\"type\":\"email.bounced\",\"data\":{\"email_id\":\"abc\"}}".getBytes(StandardCharsets.UTF_8);
        assertEquals(ResendWebhookVerifier.Result.BAD_SIGNATURE, v.verify("msg_1", ts, sig, tampered, NOW));
    }

    @Test
    void signatureWithTheWrongSecret_isRejected() throws Exception {
        ResendWebhookVerifier v = new ResendWebhookVerifier("whsec_" + SECRET_B64);
        String ts = String.valueOf(NOW.getEpochSecond());
        String other = Base64.getEncoder().encodeToString("other-secret".getBytes(StandardCharsets.UTF_8));
        assertEquals(ResendWebhookVerifier.Result.BAD_SIGNATURE, v.verify("msg_1", ts, sign(other, "msg_1", ts, BODY), BODY, NOW));
    }

    @Test
    void tenMinuteOldTimestamp_isStale_evenWhenCorrectlySigned() throws Exception {
        ResendWebhookVerifier v = new ResendWebhookVerifier("whsec_" + SECRET_B64);
        String ts = String.valueOf(NOW.getEpochSecond() - 600);
        assertEquals(ResendWebhookVerifier.Result.STALE_TIMESTAMP, v.verify("msg_1", ts, sign(SECRET_B64, "msg_1", ts, BODY), BODY, NOW));
        String future = String.valueOf(NOW.getEpochSecond() + 600);
        assertEquals(ResendWebhookVerifier.Result.STALE_TIMESTAMP, v.verify("msg_1", future, sign(SECRET_B64, "msg_1", future, BODY), BODY, NOW));
    }

    @Test
    void multipleSignatures_oneValid_isOk() throws Exception {
        ResendWebhookVerifier v = new ResendWebhookVerifier("whsec_" + SECRET_B64);
        String ts = String.valueOf(NOW.getEpochSecond());
        String sig = "v1,AAAA " + sign(SECRET_B64, "msg_1", ts, BODY) + " v1,not-base64!";
        assertEquals(ResendWebhookVerifier.Result.OK, v.verify("msg_1", ts, sig, BODY, NOW));
    }

    @Test
    void missingHeaders_areRejected() throws Exception {
        ResendWebhookVerifier v = new ResendWebhookVerifier("whsec_" + SECRET_B64);
        String ts = String.valueOf(NOW.getEpochSecond());
        assertEquals(ResendWebhookVerifier.Result.MISSING_HEADERS, v.verify(null, ts, sign(SECRET_B64, "msg_1", ts, BODY), BODY, NOW));
        assertEquals(ResendWebhookVerifier.Result.MISSING_HEADERS, v.verify("msg_1", ts, "", BODY, NOW));
        assertEquals(ResendWebhookVerifier.Result.MISSING_HEADERS, v.verify("msg_1", ts, sign(SECRET_B64, "msg_1", ts, BODY), null, NOW));
    }

    @Test
    void noConfiguredSecret_failsClosed() throws Exception {
        String ts = String.valueOf(NOW.getEpochSecond());
        String sig = sign(SECRET_B64, "msg_1", ts, BODY);
        assertEquals(ResendWebhookVerifier.Result.NOT_CONFIGURED, new ResendWebhookVerifier("").verify("msg_1", ts, sig, BODY, NOW));
        assertEquals(ResendWebhookVerifier.Result.NOT_CONFIGURED, new ResendWebhookVerifier(null).verify("msg_1", ts, sig, BODY, NOW));
        assertEquals(ResendWebhookVerifier.Result.NOT_CONFIGURED, new ResendWebhookVerifier("whsec_").verify("msg_1", ts, sig, BODY, NOW));
        assertEquals(ResendWebhookVerifier.Result.NOT_CONFIGURED, new ResendWebhookVerifier("not base64!!").verify("msg_1", ts, sig, BODY, NOW));
    }

    @Test
    void secretPrefixIsOptional() throws Exception {
        String ts = String.valueOf(NOW.getEpochSecond());
        assertEquals(ResendWebhookVerifier.Result.OK, new ResendWebhookVerifier(SECRET_B64).verify("msg_1", ts, sign(SECRET_B64, "msg_1", ts, BODY), BODY, NOW));
    }
}
