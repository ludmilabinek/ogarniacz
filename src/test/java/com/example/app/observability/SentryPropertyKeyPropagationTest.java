package com.example.app.observability;

import io.sentry.SentryOptions;
import io.sentry.spring.boot4.SentryProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Overrides each errors-only key to a deliberate non-default value and asserts the
 * value reaches {@link SentryOptions}. Catches "key silently ignored" failures —
 * a typo in {@code application.properties}, or an 8.x→9.x SDK rename — without
 * depending on knowing the canonical key name at plan time. If the key doesn't
 * bind, the SDK applies its own default (0.0 / 0.0 / false), the override is
 * absent, and the assertion fails with a clear "key not consumed" diagnostic.
 *
 * <p>Operator guidance: when this test fails after an SDK upgrade, the canonical
 * key likely changed — grep the upgraded SDK source for the option setter
 * ({@code setProfileSessionSampleRate} etc.), find the new key spelling in
 * {@code ExternalOptions.from(...)}, update {@code application.properties} +
 * this test's {@code @TestPropertySource} to match.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "REMEMBER_ME_KEY=test-key-not-for-production",
        // Fake DSN forces Sentry's @ConditionalOnProperty(name="sentry.dsn") autoconfig to activate;
        // sentry.enabled=false keeps the SDK quiet so the SentryConfig runtime invariant check
        // (which would refuse these deliberately-violating values) doesn't trip the test.
        "sentry.dsn=https://public@example.invalid/0",
        "sentry.enabled=false",
        "sentry.traces-sample-rate=0.42",
        "sentry.profile-session-sample-rate=0.37",
        "sentry.logs.enabled=true"
})
class SentryPropertyKeyPropagationTest {

    // SentryProperties extends SentryOptions; injecting the bound bean avoids the static
    // Sentry.getCurrentScopes() path which returns NoOp options when SDK init is skipped
    // (e.g., sentry.enabled=false during tests).
    @Autowired
    SentryProperties options;

    @Test
    void overridenKeysPropagateToSentryOptions() {
        assertThat(options.getTracesSampleRate())
                .as("sentry.traces-sample-rate did not bind — key may have been renamed or typo'd")
                .isEqualTo(0.42);
        assertThat(options.getProfileSessionSampleRate())
                .as("sentry.profile-session-sample-rate did not bind — key may have been renamed or typo'd")
                .isEqualTo(0.37);
        assertThat(options.getLogs().isEnabled())
                .as("sentry.logs.enabled did not bind — key may have been renamed or typo'd")
                .isTrue();
    }
}
