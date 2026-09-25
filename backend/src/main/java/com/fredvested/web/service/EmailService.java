package com.fredvested.web.service;

import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.CreateEmailOptions;
import com.resend.services.emails.model.CreateEmailResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Thin Resend client. Called only by the outbox publisher, never from a request
 * thread: if Resend is slow or down the signup has already been committed and
 * the message is retried later. The API key comes from configuration only.
 */
@Service
public class EmailService {

    private final Resend resend;
    private final String from;
    private final String replyTo;

    public EmailService(@Value("${resend.api-key}") String apiKey,
                        @Value("${email.from:FRED <fred@fredvested.com>}") String from,
                        @Value("${email.reply-to:help@fredvested.com}") String replyTo) {
        this.resend = new Resend(apiKey);
        this.from = from;
        this.replyTo = replyTo;
    }

    /**
     * Sends one email and returns Resend's message id, the key webhook events join on.
     * Headers carry the RFC 8058 one-click unsubscribe pair (List-Unsubscribe and
     * List-Unsubscribe-Post) so mail clients can unsubscribe without a page.
     */
    public String send(String to, String subject, String html, String text, Map<String, String> headers) throws ResendException {
        CreateEmailResponse response = resend.emails().send(options(to, subject, html, text, headers));
        return response.getId();
    }

    /**
     * Every message replies to the help mailbox (email.reply-to), so sending from a
     * subdomain such as mail.fredvested.com never strands a reply (decision 2026-09-25).
     */
    CreateEmailOptions options(String to, String subject, String html, String text, Map<String, String> headers) {
        CreateEmailOptions.Builder builder = CreateEmailOptions.builder()
                .from(from)
                .to(to)
                .subject(subject)
                .html(html)
                .text(text);
        if (replyTo != null && !replyTo.isBlank()) builder.replyTo(replyTo.trim());
        if (headers != null && !headers.isEmpty()) builder.headers(headers);
        return builder.build();
    }
}
