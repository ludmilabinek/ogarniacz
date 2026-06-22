package com.example.app.event;

import com.example.app.testsupport.FixedClockTestConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Pins the deterministic {@code @Scheduled} test pattern that
 * {@code test-plan.md §6.9} codifies: drive the scheduled method directly,
 * never go through Spring's scheduler thread. Reuses {@link FixedClockTestConfig}
 * so {@code duration_ms} in the INFO line resolves to a known {@code 0}
 * (clock is fixed; start == end).
 *
 * <p>The service is mocked at the class level to keep the scheduler test
 * focused on its own responsibility — the logging contract — without
 * re-litigating Phase 3's cascade/repository-state coverage. Each test
 * stubs {@code purgeEligible()} with the specific return shape it needs.
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
@TestPropertySource(properties = "REMEMBER_ME_KEY=test-key-not-for-production")
@ExtendWith(OutputCaptureExtension.class)
class SourceImagePurgeSchedulerTest {

    @Autowired
    SourceImagePurgeScheduler scheduler;

    @MockitoBean
    SourceImagePurgeService purgeService;

    @Test
    void sweepEmitsInfoLineWithPurgedCountAndDurationWhenServiceReturnsPositive(CapturedOutput output) {
        when(purgeService.purgeEligible()).thenReturn(2);

        scheduler.sweep();

        assertThat(output.getAll())
                .contains("source_image_purge_sweep")
                .contains("purged_count=2")
                .contains("duration_ms=0");
    }

    @Test
    void sweepIsSilentWhenServiceReturnsZero(CapturedOutput output) {
        when(purgeService.purgeEligible()).thenReturn(0);

        scheduler.sweep();

        assertThat(output.getAll()).doesNotContain("source_image_purge_sweep");
    }

    @Test
    void sweepLogsErrorWithStacktraceWhenServiceThrows(CapturedOutput output) {
        when(purgeService.purgeEligible())
                .thenThrow(new RuntimeException("synthetic purge failure"));

        scheduler.sweep();

        assertThat(output.getAll())
                .contains("source_image_purge_sweep_failed")
                .contains("synthetic purge failure")
                .contains("RuntimeException");
    }
}
