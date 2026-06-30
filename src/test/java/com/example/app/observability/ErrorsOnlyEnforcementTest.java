package com.example.app.observability;

import io.sentry.SentryOptions;
import io.sentry.spring.boot4.SentryProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the application with prod-config property values resolved and asserts that the
 * three errors-only invariants are satisfied: {@code traces-sample-rate == 0.0},
 * {@code profile-session-sample-rate == 0.0}, {@code logs.enabled == false}.
 *
 * <p>Guards against a future maintainer bumping {@code sentry.traces-sample-rate=0.1}
 * for debugging and forgetting to revert. Companion test
 * {@link SentryPropertyKeyPropagationTest} guards against the failure mode this test
 * cannot detect (a key-name typo silently falling back to the SDK default).
 */
@SpringBootTest
@TestPropertySource(properties = {
        "REMEMBER_ME_KEY=test-key-not-for-production",
        // Fake DSN forces Sentry's @ConditionalOnProperty(name="sentry.dsn") autoconfig to activate
        // so SentryOptions is bound from application.properties and we can assert on the resolved values.
        // sentry.enabled=false keeps the SDK quiet (no transport attempts).
        "sentry.dsn=https://public@example.invalid/0",
        "sentry.enabled=false"
})
class ErrorsOnlyEnforcementTest {

    // SentryProperties extends SentryOptions; injecting the bound bean avoids the static
    // Sentry.getCurrentScopes() path which returns NoOp options when SDK init is skipped
    // (e.g., sentry.enabled=false during tests).
    @Autowired
    SentryProperties options;

    @Test
    void errorsOnlyInvariantsHoldOnBoot() {
        assertThat(options.getTracesSampleRate())
                .as("sentry.traces-sample-rate must stay at 0.0 — errors-only")
                .isEqualTo(0.0);
        assertThat(options.getProfileSessionSampleRate())
                .as("sentry.profile-session-sample-rate must stay at 0.0 — errors-only")
                .isEqualTo(0.0);
        assertThat(options.getLogs().isEnabled())
                .as("sentry.logs.enabled must stay false — log volume is not in scope")
                .isFalse();
    }
}
