package com.fredvested.web.email;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

// The preview endpoint exists for dev and local only: with the prod profile the bean is not created.
class EmailPreviewControllerProfileTest {

    final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withUserConfiguration(EmailPreviewController.class);

    @Test
    void absentOnProd() {
        runner.withPropertyValues("spring.profiles.active=prod")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(EmailPreviewController.class));
    }

    @Test
    void presentOnDevLocalAndDefault() {
        for (String profile : new String[] { "dev", "local", "" }) {
            ApplicationContextRunner r = profile.isEmpty() ? runner : runner.withPropertyValues("spring.profiles.active=" + profile);
            r.run(ctx -> assertThat(ctx).hasSingleBean(EmailPreviewController.class));
        }
    }

    @Test
    void samplesComeFromTheAllowListOnly() {
        runner.run(ctx -> {
            EmailPreviewController c = ctx.getBean(EmailPreviewController.class);
            assertThat(c.load("existing-100k-optimistic").existingBalance()).isEqualTo(100_000L);
            assertThat(c.preview("welcome", "../../etc/passwd").getStatusCode().value()).isEqualTo(400);
            assertThat(c.preview("nope", "sample").getStatusCode().value()).isEqualTo(400);
            assertThat(c.chart("nope").getStatusCode().value()).isEqualTo(400);
            String html = c.preview("welcome", "sample").getBody();
            assertThat(html).contains("src=\"/api/dev/email-preview/growth-chart.png?sample=sample\"").doesNotContain("cid:").doesNotContain("/api/email/chart/");
            assertThat(c.preview("welcome-fallback", "sample").getBody()).contains("Put your financial future on autopilot.");
            assertThat(c.preview("confirmation", "sample").getBody()).contains("Confirm my email");
        });
    }
}
