package com.example.app.observability.devtools;

import com.example.app.event.SourceImagePurgeService;
import com.example.app.event.SourceImageRepository;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dev-only override of {@link SourceImagePurgeService} for the Phase 3 smoke
 * endpoint. When {@link #failNextCall} is set, the next {@code purgeEligible()}
 * call throws {@link RuntimeException}, hitting the production catch-and-log
 * path in {@code SourceImagePurgeScheduler.sweep()} — that {@code log.error}
 * line then becomes a Sentry event via the Logback appender, exercising the
 * exact prod-incident pipeline. After failing once, the flag self-resets.
 *
 * <p>Active under {@code spring.profiles.active=dev} only. The non-dev
 * regression test {@code DevForceErrorControllerNonDevTest} pins the gating.
 */
@Service
@Primary
@Profile("dev")
public class DevFailableSourceImagePurgeService extends SourceImagePurgeService {

    public static volatile boolean failNextCall = false;

    public DevFailableSourceImagePurgeService(SourceImageRepository sourceImageRepository) {
        super(sourceImageRepository);
    }

    @Override
    @Transactional
    public int purgeEligible() {
        if (failNextCall) {
            failNextCall = false;
            throw new RuntimeException("forced purge failure for dev smoke");
        }
        return super.purgeEligible();
    }
}
