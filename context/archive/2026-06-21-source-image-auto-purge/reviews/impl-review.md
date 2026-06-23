<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Source Image Auto-Purge (S-06)

- **Plan**: `context/changes/source-image-auto-purge/plan.md`
- **Scope**: All 5 phases (full plan)
- **Date**: 2026-06-23
- **Verdict**: APPROVED
- **Findings**: 0 critical · 1 warning · 3 observations
- **Triage**: 4 fixed (F1 Fix A · F2 Fix · F3 custom variant — plan §Performance · F4 expanded — §6.9 + §3 row), 0 skipped, 0 dismissed (2026-06-23)

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | PASS |
| Architecture | PASS |
| Pattern Consistency | WARNING |
| Success Criteria | PASS |

## Evidence

- `./gradlew build` — BUILD SUCCESSFUL (full suite green).
- 5 plan-named test classes pass: `SourceImageRepositoryTest`, `ExtractionServiceTest`, `SourceImagePurgeServiceTest`, `SourceImagePurgeSchedulerTest`, `EventReviewControllerTest`.
- Diff vs. plan: 16 files changed, 1:1 mapping to planned changes. No EXTRA files.
- Phase 1.3 manual (Neon `ALTER`) explicitly deferred-to-deployment by the plan itself (plan.md:354) — Neon schema is still pre-S-05 (no `source_image` table), so `migration.sql` can't be run until first `bootRun` against Neon creates the entities. Not a review gap.
- Phase 4.5 manual smoke verified by the implementer at commit 55feb91.

## Findings

### F1 — EventReviewControllerTest couples to SourceImagePurgeService implementation

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Pattern Consistency
- **Location**: `src/test/java/com/example/app/event/EventReviewControllerTest.java:64-66, 309-331`
- **Detail**: `getReviewAfterPurgeReturns404` autowires `SourceImagePurgeService` and calls `purgeService.purgeEligible()` inline to construct the post-purge state (line 325), then asserts `purged ≥ 1` (line 327) instead of exactly 1.

  Two concerns:
  - (a) The purge predicate is **global** (no user filter). `SourceImagePurgeServiceTest` defends with `@BeforeEach` truncation (lines 49–59). `EventReviewControllerTest` has no such cleanup. If a future sibling test leaves a State-D-shaped image behind, this test will silently delete it (≥1 still passes) — masking a real accumulating-state bug elsewhere in the class.
  - (b) It couples the controller's 404 contract to the purge service implementation. If `purgeEligible()` ever grows a user filter or a different shape, this controller test breaks for reasons unrelated to the controller. The JavaDoc on `EventReviewController.java:68-76` literally specifies `findByIdAndUser` as the 404 origin — a direct delete is the most truthful seed.

- **Fix A ⭐ Recommended**: Decouple — delete via the repository directly
  - **Approach**: Replace `purgeService.purgeEligible()` and the injected field with `sourceImageRepository.deleteById(imageId); sourceImageRepository.flush();`. Assert image absent via `findById`; drop the `@Autowired SourceImagePurgeService` field.
  - **Strength**: Pins the controller's contract (purged → 404) against the JavaDoc's actual claim ("via findByIdAndUser miss branch"), not against another service's predicate.
  - **Tradeoff**: The test no longer indirectly exercises the cascade + 3-clause predicate — but that's the right concern separation: `SourceImagePurgeServiceTest` already pins both, and the cascade test in `SourceImageRepositoryTest` pins the FK side.
  - **Confidence**: HIGH — the JavaDoc contract literally specifies `findByIdAndUser` as the 404 origin, so a direct delete is the most truthful seed.
  - **Blind spot**: None significant.
- **Fix B**: Keep `purgeService` coupling, add `@BeforeEach` cleanup
  - **Approach**: Mirror `SourceImagePurgeServiceTest`'s `@BeforeEach` truncation in `EventReviewControllerTest`; tighten the assertion from `≥1` to `==1`.
  - **Strength**: Preserves the test's "real purge produces real 404" intent more literally.
  - **Tradeoff**: Truncating `proposed_event` + `source_image` in `@BeforeEach` on this class affects all 7+ tests — paying setup cost class-wide for one test's needs. Still couples controller test to service implementation.
  - **Confidence**: MEDIUM — fixes (a) but not (b).
  - **Blind spot**: Whether sibling tests in this class quietly depend on state surviving across tests.
- **Decision**: FIXED via Fix A (2026-06-23) — dropped the `@Autowired SourceImagePurgeService` field and the inline `purgeService.purgeEligible()` seed; replaced with `sourceImageRepository.deleteById(imageId); sourceImageRepository.flush();` + `findById(...).isEmpty()` assertion. Removed the now-unused `Instant` import. `./gradlew test --tests com.example.app.event.EventReviewControllerTest` green.

### F2 — duration_ms=0 exact assertion is brittle

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: `src/test/java/com/example/app/event/SourceImagePurgeSchedulerTest.java:50`
- **Detail**: Asserts the INFO line contains `duration_ms=0` — relies on `Clock.fixed` making both `Instant.now(clock)` calls equal. Asserts an implementation detail (exact zero from fixed-clock arithmetic) instead of the contract ("a duration is logged"). Negligible risk today; would break if Clock injection ever changes shape.
- **Fix**: Replace `.contains("duration_ms=0")` with `.containsPattern("duration_ms=\\d+")` — same regression-killing power on the "is duration logged" question, no false coupling to fixed-clock semantics.
- **Decision**: FIXED (2026-06-23) — swapped `.contains("duration_ms=0")` for `.containsPattern("duration_ms=\\d+")` and rewrote the class-level JavaDoc so it no longer pins `duration_ms` to a known `0` value. Full suite green.

### F3 — Composite index use unverified against real predicate

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: `context/changes/source-image-auto-purge/migration.sql:16-17`
- **Detail**: The composite index `(source_image_id, status)` is justified in the plan for the `NOT EXISTS` subquery, but never `EXPLAIN`-verified against real data on Neon. At MVP scale Postgres may seq-scan `proposed_event` instead — not a defect (cheaper at small N) but the assumption "index is used" is currently untested in the production environment.
- **Fix**: Add a one-line TODO in `migration.sql` header asking the operator to capture `EXPLAIN ANALYZE` output of the purge DELETE during the first post-deploy sweep, and either confirm index use or document why seq-scan is preferred.
- **Decision**: FIXED via custom variant (2026-06-23) — landed the note in `plan.md §"Performance Considerations"` instead of `migration.sql`: "The duration_ms field in the sweep log line is the runtime canary for the composite index. If it ever exceeds ~100 ms in production, capture EXPLAIN ANALYZE on the predicate's SELECT form (substitute SELECT id for DELETE) and re-evaluate whether the composite index is actually being used. At MVP scale (<1000 rows) Postgres will almost certainly seq-scan and that is correct." Pins the canary (`duration_ms`) to a concrete threshold without polluting the SQL artifact.

### F4 — Cosmetic plan drift: OutputCaptureExtension instead of ListAppender

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: `src/test/java/com/example/app/event/SourceImagePurgeSchedulerTest.java:9-10, 32, 42`
- **Detail**: Plan Phase 4.3 specified a "ListAppender-style Logback fixture"; implementation uses Spring Boot's built-in `OutputCaptureExtension` / `CapturedOutput`. Same assertion surface (rendered log lines), more Boot-idiomatic. No behavioral drift. Worth either updating the plan/cookbook §6.9 to codify the actual choice, or recording the substitution.
- **Fix**: Update the §6.9 cookbook entry (when written) to seed `OutputCaptureExtension` as the canonical pattern, not `ListAppender` — what shipped is the better default.
- **Decision**: FIXED via expanded scope (2026-06-23) — wrote the missing `test-plan.md §6.9` cookbook entry from scratch ("Adding a deterministic `@Scheduled` test"), naming `OutputCaptureExtension` + `CapturedOutput` as canonical with rationale (asserts rendered log lines, no Logback wiring, no appender leakage), the assert-by-pattern rule (F2 lesson folded in), and the empty/exception branch coverage shape. Also appended the missing `§3` Phase 5 row ("Source image auto-purge (NFR retention)", complete, change folder `source-image-auto-purge`) so the rollout ledger matches reality. Closes out the plan's "Closeout" expectation that commit ca5b933 left unmet.

## Items explicitly considered and not flagged

- **Shared single-threaded `TaskScheduler`** (purge sweep can queue against `ExtractionJobRegistry.sweep`) — plan §"Performance Considerations" and §"Critical Implementation Details" both explicitly accepted this with rationale (sub-ms duration at MVP scale, `fixedDelay` not `fixedRate`). Implementation matches plan intent.
- **Redundant `stateA` / `abandonedA` tests** (same data shape) — plan's truth table deliberately enumerates 8 distinct lifecycle states; pinning each independently makes the truth-table-as-tests self-documenting.
- **Fully-qualified enum in JPQL** (`com.example.app.event.ProposedEvent.ProposedEventStatus.PENDING`) — this was the prior plan-review F5's prescribed fix; implementation matches.
- **Migration runbook coupling** — plan §"Migration Notes" and Phase 1.3 Progress already pin the operator sequence.
