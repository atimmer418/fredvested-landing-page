package com.fredvested.web.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EmailServiceTest {

    @Test
    void buildHtmlEmail_containsExpectedCopyAndBranding() {
        EmailService service = new EmailService("test-key");
        String html = service.buildHtmlEmail();

        assertThat(html).contains("FRED's private beta waitlist");
        assertThat(html).contains("clock out early");
        assertThat(html).contains("48 hours to claim your spot");
        assertThat(html).contains("Signups are being reviewed in waves");
        assertThat(html).contains("If invited, you&#39;ll get an email with next steps");
        assertThat(html).contains("The FRED Team");
        assertThat(html).contains("#0F172A");
        assertThat(html).contains("#135bec");
    }
}
