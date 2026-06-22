package com.example.app.event;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * S-06 source-image auto-purge. A single transactional wrapper around the
 * 3-clause JPQL DELETE on {@link SourceImageRepository#purgeEligible()}; the
 * {@code ON DELETE CASCADE} declared on {@code ProposedEvent.sourceImage}
 * carries the decided children with the parent in one commit.
 *
 * <p>Eligibility predicate (verbatim from the plan):
 * <pre>
 *   NOT EXISTS (PENDING)
 *   AND lastErrorKind IS NULL
 *   AND resolvedAt IS NOT NULL
 * </pre>
 *
 * <p>Logging lives in {@code SourceImagePurgeScheduler} (Phase 4); this
 * service intentionally has no side effects beyond the DELETE so it stays
 * trivially callable from tests.
 */
@Service
public class SourceImagePurgeService {

    private final SourceImageRepository sourceImageRepository;

    public SourceImagePurgeService(SourceImageRepository sourceImageRepository) {
        this.sourceImageRepository = sourceImageRepository;
    }

    @Transactional
    public int purgeEligible() {
        return sourceImageRepository.purgeEligible();
    }
}
