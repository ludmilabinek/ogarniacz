package com.example.app.observability.devtools;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Companion to {@link DevForceErrorControllerNonDevTest}: under
 * {@code @ActiveProfiles("dev")}, the dev controller AND both
 * {@code DevFailable*} override beans must be registered. Locks in the positive
 * arm of the {@code @Profile("dev")} contract — if a typo or refactor breaks
 * bean activation under dev, the smoke runbook would silently miss the
 * trigger; this test surfaces it pre-merge.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "REMEMBER_ME_KEY=test-key-not-for-production"
})
class DevForceErrorControllerDevTest {

    @Autowired
    ApplicationContext ctx;

    @Test
    void devForceErrorBeansArePresentUnderDevProfile() {
        assertThat(ctx.getBeansOfType(DevForceErrorController.class))
                .as("DevForceErrorController must be registered under @Profile(\"dev\")")
                .hasSize(1);
        assertThat(ctx.getBeansOfType(DevFailableLlmVisionClient.class))
                .as("DevFailableLlmVisionClient must be registered under @Profile(\"dev\")")
                .hasSize(1);
        assertThat(ctx.getBeansOfType(DevFailableSourceImagePurgeService.class))
                .as("DevFailableSourceImagePurgeService must be registered under @Profile(\"dev\")")
                .hasSize(1);
        assertThat(ctx.getBeansOfType(DevForceErrorSecurityConfig.class))
                .as("DevForceErrorSecurityConfig must be registered under @Profile(\"dev\")")
                .hasSize(1);
    }
}
