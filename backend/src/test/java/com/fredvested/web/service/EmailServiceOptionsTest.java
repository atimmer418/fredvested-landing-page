package com.fredvested.web.service;

import com.resend.services.emails.model.CreateEmailOptions;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

// What actually reaches Resend for one message: from, reply-to, the RFC 8058 headers.
class EmailServiceOptionsTest {

    @Test
    void everyMessage_repliesToTheHelpMailbox_andCarriesTheHeaders() {
        EmailService svc = new EmailService("re_test", "FRED <fred@mail.fredvested.com>", "help@fredvested.com");
        Map<String, String> headers = Map.of(
                "List-Unsubscribe", "<https://lpapi.fredvested.com/api/waitlist/unsubscribe?token=x>",
                "List-Unsubscribe-Post", "List-Unsubscribe=One-Click");

        CreateEmailOptions o = svc.options("a@example.com", "Subject", "<p>h</p>", "t", headers);

        assertEquals("FRED <fred@mail.fredvested.com>", o.getFrom());
        assertEquals(java.util.List.of("a@example.com"), o.getTo());
        assertEquals(java.util.List.of("help@fredvested.com"), o.getReplyTo());
        assertEquals(headers, o.getHeaders());
        assertEquals("Subject", o.getSubject());
    }

    @Test
    void aBlankReplyTo_leavesTheHeaderOut() {
        EmailService svc = new EmailService("re_test", "FRED <fred@fredvested.com>", " ");
        CreateEmailOptions o = svc.options("a@example.com", "S", "<p/>", "t", Map.of());
        assertTrue(o.getReplyTo() == null || o.getReplyTo().isEmpty());
        assertTrue(o.getHeaders() == null || o.getHeaders().isEmpty());
    }
}
