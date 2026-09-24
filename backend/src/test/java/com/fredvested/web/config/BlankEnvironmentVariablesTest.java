package com.fredvested.web.config;

import com.fredvested.web.repository.EmailMessageRepository;
import com.fredvested.web.repository.WaitlistRepository;
import com.fredvested.web.service.SignupService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.logging.DeferredLogs;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

// Railway dev, 2026-09-24: WAITLIST_DOUBLE_OPT_IN existed with an empty value.
// `${WAITLIST_DOUBLE_OPT_IN:true}` only falls back when the variable is ABSENT,
// so "" reached a boolean @Value and the API never started. A blank variable
// must behave exactly like an unset one.
class BlankEnvironmentVariablesTest {

    // Stand in for the OS environment (System.getenv() is immutable in a test).
    static void fakeEnvironment(ConfigurableEnvironment env, Map<String, Object> vars) {
        env.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, new HashMap<>(vars)));
    }

    @Test
    void blankVariables_fallBackToThePlaceholderDefault_andSetOnesStillBind() {
        StandardEnvironment env = new StandardEnvironment();
        fakeEnvironment(env, Map.of(
                "WAITLIST_DOUBLE_OPT_IN", "",
                "RESEND_WEBHOOK_SECRET", "   ",
                "WAITLIST_US_ONLY", "true",
                "PORT", "8081"));
        assertEquals("", env.resolvePlaceholders("${WAITLIST_DOUBLE_OPT_IN:true}"), "the bug: blank wins over the default");

        new BlankEnvironmentVariables(new DeferredLogs()).postProcessEnvironment(env, null);

        assertEquals("true", env.resolvePlaceholders("${WAITLIST_DOUBLE_OPT_IN:true}"));
        assertEquals("unset", env.resolvePlaceholders("${RESEND_WEBHOOK_SECRET:unset}"));
        assertEquals("true", env.resolvePlaceholders("${WAITLIST_US_ONLY:false}"));
        assertEquals("8081", env.resolvePlaceholders("${PORT:8080}"));
        assertFalse(env.containsProperty("WAITLIST_DOUBLE_OPT_IN"));
        assertTrue(env.containsProperty("PORT"));
        // Relaxed binding survives the replacement (the source must stay a SystemEnvironmentPropertySource).
        assertEquals("8081", env.getProperty("port"));
    }

    @Test
    void withoutTheFilter_theDevCrashReproduces_withIt_signupServiceStartsOnTheDefault() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withInitializer(ctx -> fakeEnvironment(ctx.getEnvironment(), Map.of("WAITLIST_DOUBLE_OPT_IN", "")))
                .withPropertyValues("waitlist.double-opt-in.enabled=${WAITLIST_DOUBLE_OPT_IN:true}")
                .withBean(WaitlistRepository.class, () -> mock(WaitlistRepository.class))
                .withBean(EmailMessageRepository.class, () -> mock(EmailMessageRepository.class))
                .withBean(SignupService.class);

        runner.run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("boolean");
        });

        runner.withInitializer(ctx -> new BlankEnvironmentVariables(new DeferredLogs()).postProcessEnvironment(ctx.getEnvironment(), null))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertTrue(ctx.getBean(SignupService.class).isDoubleOptIn(), "blank flag means the documented default: double opt-in on");
                });
    }

    @Test
    void nothingBlank_leavesTheEnvironmentUntouched() {
        StandardEnvironment env = new StandardEnvironment();
        fakeEnvironment(env, Map.of("PORT", "8081"));
        var before = env.getPropertySources().get(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        new BlankEnvironmentVariables(new DeferredLogs()).postProcessEnvironment(env, null);
        assertSame(before, env.getPropertySources().get(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME));
    }
}
