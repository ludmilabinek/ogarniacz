package com.example.app.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * S-06 source-image auto-purge scheduler. Decouples the "when" (cadence) from
 * the "what" (3-clause predicate in {@link SourceImagePurgeService}), so the
 * service stays trivially callable from tests without any scheduling concern.
 *
 * <p>Logging contract (load-bearing — see plan §Phase 4):
 * <ul>
 *   <li>{@code purgedCount > 0}: emit one INFO line
 *       {@code source_image_purge_sweep purged_count=N duration_ms=M}.</li>
 *   <li>{@code purgedCount == 0}: silent. No-op cycles are by design — at MVP
 *       scale the sweep fires every 10 minutes and most cycles will find
 *       nothing.</li>
 *   <li>Exception thrown: ERROR with full stacktrace via
 *       {@code log.error("source_image_purge_sweep_failed", ex)}. Without this
 *       a silently-stuck sweep would only surface when disk fills.</li>
 * </ul>
 *
 * <p>Per-row forensics (which ids vanished and when) are explicitly out of
 * scope for this slice — deferred to the observability slice. The bulk DELETE
 * stays a single statement; {@code purgedCount} is the operationally meaningful
 * signal.
 */
@Component
public class SourceImagePurgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(SourceImagePurgeScheduler.class);

    private final SourceImagePurgeService purgeService;
    private final Clock clock;

    public SourceImagePurgeScheduler(SourceImagePurgeService purgeService, Clock clock) {
        this.purgeService = purgeService;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.event.source-image.purge.interval-ms:600000}")
    public void sweep() {
        Instant start = Instant.now(clock);
        try {
            int purgedCount = purgeService.purgeEligible();
            if (purgedCount > 0) {
                long durationMs = Duration.between(start, Instant.now(clock)).toMillis();
                log.info("source_image_purge_sweep purged_count={} duration_ms={}", purgedCount, durationMs);
            }
        } catch (RuntimeException ex) {
            log.error("source_image_purge_sweep_failed", ex);
        }
    }
}
