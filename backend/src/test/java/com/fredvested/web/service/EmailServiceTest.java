package com.fredvested.web.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmailServiceTest {

    @Test
    void confirmationEmail_carriesTheLinks_andNoHostedFont() {
        EmailTemplates.Rendered r = EmailTemplates.confirmation(
                "https://lpapi.fredvested.com/api/waitlist/confirm?token=abc",
                "https://lpapi.fredvested.com/api/waitlist/unsubscribe?token=xyz", 7);

        assertThat(r.subject()).isEqualTo("Confirm your email for the FRED waitlist");
        assertThat(r.html()).contains("confirm?token=abc").contains("unsubscribe?token=xyz");
        assertThat(r.text()).contains("confirm?token=abc").contains("Unsubscribe: https://lpapi.fredvested.com/api/waitlist/unsubscribe?token=xyz");
        assertThat(r.html()).contains("Confirm my email").contains("7 days").contains("The FRED Team");
        assertThat(r.text()).contains("7 days");
        assertThat(r.html()).doesNotContain("googleapis").doesNotContain("gstatic").doesNotContain("fonts.google");
        assertThat(r.html()).doesNotContain("—").doesNotContain("&#8212;");
        assertThat(r.text()).doesNotContain("—");
    }

    @Test
    void confirmationEmail_statesTheConfiguredExpiry() {
        EmailTemplates.Rendered r = EmailTemplates.confirmation("https://x/c?token=a", "https://x/u?token=b", 10);
        assertThat(r.html()).contains("10 days").doesNotContain("7 days");
        assertThat(r.text()).contains("10 days");
    }

    @Test
    void welcomeEmail_keepsTheCopy_andCarriesTheUnsubscribeLink() {
        EmailTemplates.Rendered r = EmailTemplates.welcome("https://lpapi.fredvested.com/api/waitlist/unsubscribe?token=xyz");

        assertThat(r.subject()).isEqualTo("You're in");
        assertThat(r.html()).contains("FRED's private beta waitlist");
        assertThat(r.html()).contains("clock out early");
        assertThat(r.html()).contains("48 hours to claim your spot");
        assertThat(r.html()).contains("Signups are being reviewed in waves");
        assertThat(r.html()).contains("If invited, you&#39;ll get an email with next steps");
        assertThat(r.html()).contains("The FRED Team");
        assertThat(r.html()).contains("#0F172A").contains("#135bec");
        assertThat(r.html()).contains("unsubscribe?token=xyz");
        assertThat(r.text()).contains("Unsubscribe: https://lpapi.fredvested.com/api/waitlist/unsubscribe?token=xyz");
        assertThat(r.html()).doesNotContain("googleapis").doesNotContain("—").doesNotContain("&#8212;");
    }

    @Test
    void emailsNeverContainTheRecipientsAddress() {
        // Only the address is personal data, and it lives in the To header, not the body.
        EmailTemplates.Rendered r = EmailTemplates.confirmation("https://x/confirm?token=a", "https://x/unsubscribe?token=b", 7);
        assertThat(r.html()).doesNotContain("@example.com");
        assertThat(r.text()).doesNotContain("@example.com");
    }
}
