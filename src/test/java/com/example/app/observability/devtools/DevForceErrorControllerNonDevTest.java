package com.example.app.observability.devtools;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test: under the default profile (no {@code @ActiveProfiles("dev")}),
 * the dev force-error controller AND both {@code DevFailable*} override beans
 * must be absent from the application context. If a future change accidentally
 * drops {@code @Profile("dev")} from any of the three, this test fails before
 * merge — preventing a permanent dev-tooling surface from leaking into
 * production.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "REMEMBER_ME_KEY=test-key-not-for-production"
})
class DevForceErrorControllerNonDevTest {

    @Autowired
    ApplicationContext ctx;

    @Test
    void devForceErrorBeansAreAbsentUnderDefaultProfile() {
        assertThat(ctx.getBeansOfType(DevForceErrorController.class))
                .as("DevForceErrorController must be @Profile(\"dev\")-gated")
                .isEmpty();
        assertThat(ctx.getBeansOfType(DevFailableLlmVisionClient.class))
                .as("DevFailableLlmVisionClient must be @Profile(\"dev\")-gated")
                .isEmpty();
        assertThat(ctx.getBeansOfType(DevFailableSourceImagePurgeService.class))
                .as("DevFailableSourceImagePurgeService must be @Profile(\"dev\")-gated")
                .isEmpty();
        assertThat(ctx.getBeansOfType(DevForceErrorSecurityConfig.class))
                .as("DevForceErrorSecurityConfig must be @Profile(\"dev\")-gated")
                .isEmpty();
    }
}
