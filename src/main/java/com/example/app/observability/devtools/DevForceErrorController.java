package com.example.app.observability.devtools;

import com.example.app.event.ExtractionJobRegistry;
import com.example.app.event.ExtractionService;
import com.example.app.event.SourceImage;
import com.example.app.event.SourceImageRepository;
import com.example.app.event.SourceImagePurgeScheduler;
import com.example.app.user.AppUser;
import com.example.app.user.AppUserRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

/**
 * Dev-only HTTP entry point to trigger each of the three known production error
 * paths on demand — extraction failure, purge failure, controller exception —
 * so we can verify end-to-end that errors reach Sentry with the right shape,
 * scrubbed correctly, tagged correctly, before the prod DSN is wired.
 *
 * <p>Gated by {@code @Profile("dev")} — under any other profile (default, e2e,
 * test, production) Spring skips the bean and {@code POST /__dev/force-error/*}
 * returns 404. {@link DevForceErrorControllerNonDevTest} pins this contract;
 * any future change that drops the {@code @Profile("dev")} annotation here or
 * on the two {@code DevFailable*} override beans fails the test before merge.
 */
@RestController
@RequestMapping("/__dev/force-error")
@Profile("dev")
public class DevForceErrorController {

    private final AppUserRepository appUserRepository;
    private final SourceImageRepository sourceImageRepository;
    private final ExtractionJobRegistry jobRegistry;
    private final ExtractionService extractionService;
    private final SourceImagePurgeScheduler sourceImagePurgeScheduler;

    public DevForceErrorController(AppUserRepository appUserRepository,
                                   SourceImageRepository sourceImageRepository,
                                   ExtractionJobRegistry jobRegistry,
                                   ExtractionService extractionService,
                                   SourceImagePurgeScheduler sourceImagePurgeScheduler) {
        this.appUserRepository = appUserRepository;
        this.sourceImageRepository = sourceImageRepository;
        this.jobRegistry = jobRegistry;
        this.extractionService = extractionService;
        this.sourceImagePurgeScheduler = sourceImagePurgeScheduler;
    }

    @PostMapping("/{type}")
    public ResponseEntity<Void> trigger(@PathVariable String type, Authentication auth) {
        return switch (type) {
            case "extraction" -> triggerExtraction(auth);
            case "purge" -> triggerPurge();
            case "controller" -> triggerController();
            default -> throw new ResponseStatusException(BAD_REQUEST,
                    "unknown force-error type: " + type);
        };
    }

    private ResponseEntity<Void> triggerExtraction(Authentication auth) {
        AppUser user = appUserRepository.findByEmail(auth.getName()).orElseThrow();
        SourceImage saved = sourceImageRepository.save(
                new SourceImage(user, new byte[] { 0x00 }, "image/jpeg"));
        UUID jobId = jobRegistry.register(saved.getId());
        DevFailableLlmVisionClient.failNextCall = true;
        extractionService.runExtraction(jobId, saved.getId());
        return ResponseEntity.accepted().build();
    }

    private ResponseEntity<Void> triggerPurge() {
        DevFailableSourceImagePurgeService.failNextCall = true;
        sourceImagePurgeScheduler.sweep();
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<Void> triggerController() {
        throw new RuntimeException("dev smoke: controller path");
    }
}
