package com.example.app.event;

import com.example.app.event.ExtractionJobRegistry.JobState;
import com.example.app.event.ExtractionJobRegistry.JobStatusEntry;
import com.example.app.user.AppUser;
import com.example.app.user.AppUserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * JSON endpoint polled by the upload-form JS every 1.5s. Returns the registry
 * entry for the jobId or 404 when the TTL has evicted (the JS treats 404 as
 * "session expired").
 *
 * <p>Cross-user partition: a leaked jobId from user B can't reveal that user's
 * status. Mirrors the {@code findByIdAndUser} pattern from {@link
 * EventReviewController}; a mismatched user returns 404 (NOT 403) so a jobId
 * enumeration attack can't distinguish "valid jobId, wrong user" from
 * "unknown jobId".
 */
@RestController
public class ExtractionStatusController {

    public record StatusResponse(
            JobState state,
            String reviewUrl,
            String errorKind,
            String correlationId,
            long elapsedMs) {
    }

    private final ExtractionJobRegistry jobRegistry;
    private final SourceImageRepository sourceImageRepository;
    private final AppUserRepository appUserRepository;
    private final Clock clock;

    public ExtractionStatusController(ExtractionJobRegistry jobRegistry,
                                      SourceImageRepository sourceImageRepository,
                                      AppUserRepository appUserRepository,
                                      Clock clock) {
        this.jobRegistry = jobRegistry;
        this.sourceImageRepository = sourceImageRepository;
        this.appUserRepository = appUserRepository;
        this.clock = clock;
    }

    @GetMapping("/events/from-image/status/{jobId}")
    public ResponseEntity<StatusResponse> status(@PathVariable UUID jobId,
                                                 Authentication auth) {
        Optional<JobStatusEntry> entryOpt = jobRegistry.get(jobId);
        if (entryOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        JobStatusEntry entry = entryOpt.get();

        AppUser user = appUserRepository.findByEmail(auth.getName()).orElseThrow();
        if (sourceImageRepository.findByIdAndUser(entry.imageId(), user).isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        boolean terminal = entry.state() == JobState.DONE || entry.state() == JobState.FAILED;
        String reviewUrl = terminal
                ? "/events/from-image/" + entry.imageId() + "/review"
                : null;
        long elapsedMs = Duration.between(entry.createdAt(), Instant.now(clock)).toMillis();

        return ResponseEntity.ok(new StatusResponse(
                entry.state(), reviewUrl, entry.errorKind(), entry.correlationId(), elapsedMs));
    }
}
