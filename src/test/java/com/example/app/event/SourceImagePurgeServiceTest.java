package com.example.app.event;

import com.example.app.event.ProposedEvent.ProposedEventStatus;
import com.example.app.testsupport.UserTestFixtures;
import com.example.app.user.AppUser;
import com.example.app.user.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Truth-table coverage for the 3-clause purge predicate. Each test seeds one
 * specific row state from the table in plan.md §Desired End State and asserts
 * whether {@code purgeEligible()} removed the row. The States F (success-empty
 * with Phase 2 stamping) and F' (hypothetical unstamped success-empty) split
 * the same data shape into two distinct narratives — F' is the regression
 * guard pinning clause 3 ({@code resolvedAt IS NOT NULL}) as load-bearing.
 */
@SpringBootTest
@TestPropertySource(properties = "REMEMBER_ME_KEY=test-key-not-for-production")
class SourceImagePurgeServiceTest {

    private static final Instant FIXED_RESOLVED_AT = Instant.parse("2026-01-15T12:00:00Z");

    @Autowired
    SourceImagePurgeService purgeService;

    @Autowired
    SourceImageRepository sourceImageRepository;

    @Autowired
    ProposedEventRepository proposedEventRepository;

    @Autowired
    AppUserRepository appUserRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @BeforeEach
    void clearSourceImageRowsAcrossTheSuite() {
        // The purge predicate is global (does not filter by user), so sibling tests
        // leaving stamped images in the shared @SpringBootTest schema would pollute
        // both the `purged` count and the surviving-row assertions here. Children
        // disappear via ON DELETE CASCADE; we still clear them explicitly for
        // belt-and-braces against any future FK shape that might block the parent
        // delete.
        proposedEventRepository.deleteAllInBatch();
        sourceImageRepository.deleteAllInBatch();
    }

    @Test
    void stateA_uploadExtractionInFlight_isKept() {
        // resolvedAt=null, lastErrorKind=null, no children → blocked by clause 3.
        SourceImage image = persistImage("purge-state-a@example.com", null, null);

        int purged = purgeService.purgeEligible();

        assertThat(purged).isZero();
        assertThat(sourceImageRepository.findById(image.getId())).isPresent();
    }

    @Test
    void stateB_extractionFailed_isKept() {
        // resolvedAt=null, lastErrorKind=TIMEOUT, no children → blocked by clause 2.
        SourceImage image = persistImage("purge-state-b@example.com", null, "TIMEOUT");

        int purged = purgeService.purgeEligible();

        assertThat(purged).isZero();
        assertThat(sourceImageRepository.findById(image.getId())).isPresent();
    }

    @Test
    void stateC_abandonedAfterExtraction_isKeptAndPendingChildrenSurvive() {
        // resolvedAt=null, PENDING children present → blocked by clauses 1 and 3.
        SourceImage image = persistImage("purge-state-c@example.com", null, null);
        ProposedEvent pending = persistChild(image, ProposedEventStatus.PENDING);

        int purged = purgeService.purgeEligible();

        assertThat(purged).isZero();
        assertThat(sourceImageRepository.findById(image.getId())).isPresent();
        assertThat(proposedEventRepository.findById(pending.getId())).isPresent();
    }

    @Test
    void stateD_fullReviewSubmitted_isPurgedAndCascadeRemovesDecidedChildren() {
        // resolvedAt set, no PENDING (all ACCEPTED/REJECTED) → main purge path.
        SourceImage image = persistImage("purge-state-d@example.com", FIXED_RESOLVED_AT, null);
        ProposedEvent accepted = persistChild(image, ProposedEventStatus.ACCEPTED);
        ProposedEvent rejected = persistChild(image, ProposedEventStatus.REJECTED);

        int purged = purgeService.purgeEligible();

        assertThat(purged).isOne();
        assertThat(sourceImageRepository.findById(image.getId())).isEmpty();
        assertThat(proposedEventRepository.findById(accepted.getId())).isEmpty();
        assertThat(proposedEventRepository.findById(rejected.getId())).isEmpty();
    }

    @Test
    void stateE_partialReviewResidue_isKept() {
        // resolvedAt set, PENDING + ACCEPTED mix → clause 1 catches the S-05 bug.
        SourceImage image = persistImage("purge-state-e@example.com", FIXED_RESOLVED_AT, null);
        persistChild(image, ProposedEventStatus.ACCEPTED);
        ProposedEvent pending = persistChild(image, ProposedEventStatus.PENDING);

        int purged = purgeService.purgeEligible();

        assertThat(purged).isZero();
        assertThat(sourceImageRepository.findById(image.getId())).isPresent();
        assertThat(proposedEventRepository.findById(pending.getId())).isPresent();
    }

    @Test
    void stateF_successEmptyWithPhase2Stamping_isPurged() {
        // resolvedAt set (by Phase 2), lastErrorKind=null, no children → purged.
        SourceImage image = persistImage("purge-state-f@example.com", FIXED_RESOLVED_AT, null);

        int purged = purgeService.purgeEligible();

        assertThat(purged).isOne();
        assertThat(sourceImageRepository.findById(image.getId())).isEmpty();
    }

    @Test
    void stateFPrime_unstampedSuccessEmpty_isKept_regressionGuardForClause3() {
        // Identical to State F minus the stamp: resolvedAt=null, no errorKind, no children.
        // Without clause 3, this would be eligible — pin clause 3 as load-bearing
        // (Phase 2's stamp itself is regression-guarded by ExtractionServiceTest 2.1).
        SourceImage image = persistImage("purge-state-fprime@example.com", null, null);

        int purged = purgeService.purgeEligible();

        assertThat(purged).isZero();
        assertThat(sourceImageRepository.findById(image.getId())).isPresent();
    }

    @Test
    void abandonedA_extractionSilentlyNeverRan_isKept() {
        // resolvedAt=null, lastErrorKind=null, no children — same data shape as State A
        // and F', but documented as the abandoned-A row from the truth table (out of
        // scope for S-06; follow-up source-image-orphan-ttl slice owns this orphan class).
        SourceImage image = persistImage("purge-state-abandoned-a@example.com", null, null);

        int purged = purgeService.purgeEligible();

        assertThat(purged).isZero();
        assertThat(sourceImageRepository.findById(image.getId())).isPresent();
    }

    private SourceImage persistImage(String email, Instant resolvedAt, String lastErrorKind) {
        AppUser user = UserTestFixtures.saveUser(appUserRepository, passwordEncoder, email);
        SourceImage image = new SourceImage(user, new byte[]{1, 2, 3}, "image/jpeg");
        image.setResolvedAt(resolvedAt);
        image.setLastErrorKind(lastErrorKind);
        return sourceImageRepository.save(image);
    }

    private ProposedEvent persistChild(SourceImage image, ProposedEventStatus status) {
        ProposedEvent child = new ProposedEvent(
                image, LocalDate.of(2026, 9, 1), null,
                "child-" + UUID.randomUUID().toString().substring(0, 6), null, null);
        child.setStatus(status);
        return proposedEventRepository.save(child);
    }
}
