package com.example.app.event;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * In-memory map of extraction jobIds to their status. The browser polls
 * {@code /events/from-image/status/{jobId}} every 1.5s; this registry is what
 * that endpoint reads. A {@link Scheduled} sweep evicts entries older than
 * {@link #TTL} so completed jobs don't accumulate.
 *
 * <p>Single-user MVP scale: a JVM restart mid-extraction strands the polling
 * browser (acceptable risk per the plan's "What We're NOT Doing").
 */
@Component
public class ExtractionJobRegistry {

    public enum JobState {
        RUNNING,
        DONE,
        FAILED
    }

    public record JobStatusEntry(
            UUID imageId,
            JobState state,
            Instant createdAt,
            Instant updatedAt,
            String errorKind,
            String correlationId) {
    }

    static final Duration TTL = Duration.ofMinutes(5);

    private final Clock clock;
    private final ConcurrentHashMap<UUID, JobStatusEntry> entries = new ConcurrentHashMap<>();

    public ExtractionJobRegistry(Clock clock) {
        this.clock = clock;
    }

    public UUID register(UUID imageId) {
        UUID jobId = UUID.randomUUID();
        Instant now = Instant.now(clock);
        entries.put(jobId, new JobStatusEntry(imageId, JobState.RUNNING, now, now, null, null));
        return jobId;
    }

    public void markDone(UUID jobId) {
        Instant now = Instant.now(clock);
        entries.computeIfPresent(jobId, (k, prev) -> new JobStatusEntry(
                prev.imageId(), JobState.DONE, prev.createdAt(), now, null, null));
    }

    public void markFailed(UUID jobId, String errorKind, String correlationId) {
        Instant now = Instant.now(clock);
        entries.computeIfPresent(jobId, (k, prev) -> new JobStatusEntry(
                prev.imageId(), JobState.FAILED, prev.createdAt(), now, errorKind, correlationId));
    }

    public Optional<JobStatusEntry> get(UUID jobId) {
        return Optional.ofNullable(entries.get(jobId));
    }

    public Optional<JobStatusEntry> findRunningByImageId(UUID imageId) {
        for (JobStatusEntry entry : entries.values()) {
            if (imageId.equals(entry.imageId()) && entry.state() == JobState.RUNNING) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    @Scheduled(fixedDelay = 60_000L)
    public void sweep() {
        Instant cutoff = Instant.now(clock).minus(TTL);
        entries.entrySet().removeIf(e -> e.getValue().updatedAt().isBefore(cutoff));
    }
}
