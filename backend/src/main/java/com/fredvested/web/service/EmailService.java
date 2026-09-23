package com.fredvested.web.service;

import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.CreateEmailOptions;
import com.resend.services.emails.model.CreateEmailResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Thin Resend client. Called only by the outbox publisher, never from a request
 * thread: if Resend is slow or down the signup has already been committed and
 * the message is retried later. The API key comes from configuration only.
 */
@Service
public class EmailService {

    private final Resend resend;
    private final String from;

    public EmailService(@Value("${resend.api-key}") String apiKey,
                        @Value("${email.from:FRED <fred@fredvested.com>}") String from) {
        this.resend = new Resend(apiKey);
        this.from = from;
    }

    /** Sends one email and returns Resend's message id, the key webhook events join on. */
    public String send(String to, String subject, String html, String text) throws ResendException {
        CreateEmailOptions params = CreateEmailOptions.builder()
                .from(from)
                .to(to)
                .subject(subject)
                .html(html)
                .text(text)
                .build();
        CreateEmailResponse response = resend.emails().send(params);
        return response.getId();
    }
}
