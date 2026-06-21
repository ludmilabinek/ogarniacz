<!-- PLAN-REVIEW-REPORT -->
# Plan Review: Source Image Auto-Purge (S-06)

- **Plan**: `context/changes/source-image-auto-purge/plan.md`
- **Mode**: Deep
- **Date**: 2026-06-21
- **Verdict**: REVISE
- **Findings**: 1 critical, 3 warnings, 3 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| End-State Alignment | PASS |
| Lean Execution | WARNING |
| Architectural Fitness | PASS |
| Blind Spots | FAIL |
| Plan Completeness | WARNING |

## Grounding

9/9 paths ✓, 3/4 symbols ✓ (`ProposedEvent.Status` mis-named — see F5), plan-brief ↔ plan ✓.

## Findings

### F1 — Phase 2 "no explicit save()" defeats itself: runExtraction is not @Transactional

- **Severity**: ❌ CRITICAL
- **Impact**: 🔬 HIGH — architectural stakes; think carefully before deciding
- **Dimension**: Blind Spots
- **Location**: Phase 2 — "Close the success-empty leak (ExtractionService)"
- **Detail**: Plan §Phase 2 says: "set `image.resolvedAt` if it's still null, then let dirty-checking flush on commit (no explicit `save(image)` per lessons.md:80-85)". But `ExtractionService.runExtraction` (`event/ExtractionService.java:43`) is `@Async("extractionExecutor")` with NO `@Transactional`. The lesson at lessons.md:80-85 explicitly scopes itself to "*inside a `@Transactional` Spring service method*". Outside a transaction, dirty-checking has no commit to flush against — the mutation on a detached `image` is lost. Evidence the existing code follows the opposite pattern in this exact method: both failure branches DO call `sourceImageRepository.save(image)` (`ExtractionService.java:65` and `:77`). Consequence as written: Phase 2 silently no-ops; State F images stay `{resolvedAt=null, lastErrorKind=null, 0 PENDING}`; the predicate's clause 3 (`resolvedAt IS NOT NULL`) keeps them alive forever; the NFR leak the phase exists to close remains open. Test 2.1 would fail and surface the contradiction, but the plan ships an internally inconsistent contract.
- **Fix A ⭐ Recommended**: Stamp with explicit `save()` — mirror the failure branches
  - Approach: Replace the Phase 2 contract with `if (image.getResolvedAt() == null) { image.setResolvedAt(Instant.now(clock)); sourceImageRepository.save(image); }`. Drop the lessons.md:80-85 citation — it's the wrong precedent here.
  - Strength: Matches the pattern the same method already uses in both catch branches (`:65, :77`); zero new architectural surface; obvious to any reader. Asymmetry that lessons.md warns against would be "conditional state + unconditional save" — here both are conditional, so the lesson's spirit is preserved.
  - Tradeoff: The plan loses its "we follow lessons.md" framing. Worth it.
  - Confidence: HIGH — failure-branch precedent in the same file is unambiguous.
  - Blind spot: None significant.
- **Fix B**: Wrap `runExtraction` in `@Transactional` so dirty-checking actually works
  - Approach: Add `@Transactional` to `runExtraction` (or to a tighter private helper). Then the plan's "no save" wording becomes correct.
  - Strength: Preserves the plan's lessons.md framing literally.
  - Tradeoff: `@Async` + `@Transactional` on the same method is a known Spring footgun (proxy stacking; exception semantics change). Forces a transaction around the LLM call itself (55s budget) — long-lived txns hold a HikariCP connection and can starve the pool. Rewrites the existing failure-branch `save()` pattern for no NFR gain.
  - Confidence: MEDIUM — works mechanically but expands the slice's blast radius from "one field write" to "method-level transaction semantics", which is far beyond S-06's NFR-compliance scope.
  - Blind spot: Interaction with the proxy-only `@Async` self-invocation rule the existing JavaDoc already calls out.
- **Decision**: FIXED via Fix A — Phase 2 §Intent + §Contract updated to require explicit `sourceImageRepository.save(image)` after the conditional stamp; lessons.md:80-85 citation reframed (rule scoped to `@Transactional` contexts; `runExtraction` is `@Async` without `@Transactional`, so dirty-checking has no commit to flush against).

### F2 — Phase 4 per-row DEBUG inflates the service from 1 query to 2, and contract self-contradicts on return type

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Lean Execution
- **Location**: Phase 4, change #2 — "Per-row DEBUG logging in the purge service"
- **Detail**: Plan adds a SELECT-then-DELETE shape purely to emit DEBUG lines per id. Default Spring Boot log level is INFO; production rarely runs at DEBUG and there's no runtime knob in this slice to flip it — the per-row line is operationally invisible without an opt-in restart. The INFO line already carries `purged_count` (the operationally meaningful number), and `@Modifying`'s return value would let the service log the count without any extra query. Contract self-contradiction: change #2 says "Add `List<UUID> findEligibleIdsForLogging()`" but the body line says "log one DEBUG line per id … `decided_at={}` (returns ids + `decided_at` derived as `resolvedAt`)". UUID-list and id+timestamp tuple are different return shapes; implementer can't pick the right repository signature from the plan alone.
- **Fix A ⭐ Recommended**: Drop the SELECT; keep only the INFO line on `purged_count > 0`
  - Approach: Remove change #2 entirely. `SourceImagePurgeService.purgeEligible()` is the bulk DELETE; scheduler logs `purged_count` on INFO. Defer per-row forensics to the future observability slice (already planned in §"What We're NOT Doing").
  - Strength: One query per cycle, one log shape, no return-type ambiguity. Honest about value — DEBUG is off by default; we don't pay for channels we don't read. Aligns with the slice's explicit "no Micrometer; observability is its own slice" decision.
  - Tradeoff: On the day the operator wants "which 5 rows vanished at 14:03", the answer is "rerun with DEBUG on next time" — not retroactive. At MVP scale (<1000 rows/year) this is unlikely to matter.
  - Confidence: HIGH — matches the rest of the plan's posture on observability.
  - Blind spot: None significant.
- **Fix B**: Keep per-row DEBUG, but fix the return-type contract
  - Approach: Pin the repository signature to a small record/projection: `record PurgeCandidate(UUID id, Instant resolvedAt) {}` and `List<PurgeCandidate> findEligibleForLogging();`. Service logs one DEBUG line per candidate, then runs the bulk DELETE.
  - Strength: Preserves the forensics intent.
  - Tradeoff: Adds a public type to the repository surface and a second query per cycle, for a channel that's off by default in prod.
  - Confidence: MEDIUM — fixes the literal contract bug but doesn't address the value question.
  - Blind spot: Whether anyone will ever read DEBUG output for this service.
- **Decision**: FIXED via Fix A — Phase 4 change #2 removed entirely; remaining changes renumbered (#3→#2, #4→#3); cross-reference in change #1 rewritten to point at the observability slice; Performance Considerations line about SELECT-before-DELETE deleted.

### F3 — Phase 1 success criterion 1.2 references a test class that doesn't exist

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 1, Success Criteria 1.2; also §"Testing Strategy"
- **Detail**: Plan line 1.2 says "`./gradlew test --tests com.example.app.event.ProposedEventRepositoryTest` (existing) still passes — annotation change does not regress finder semantics". §"Testing Strategy" similarly calls it a "ProposedEventRepositoryTest extension (Phase 1)". `find src/test -name "ProposedEvent*"` returns nothing and `grep -r "ProposedEventRepositoryTest"` over `src/test` returns no hits. There is no existing class to regress; cascade test in Phase 1 step #3 becomes the file's first test.
- **Fix**: Reword 1.2 from "(existing) still passes" to "(new) cascade test passes", and pick the home for the cascade test explicitly — most natural is a new `ProposedEventCascadeTest` (or fold into a new `SourceImageRepositoryTest`). Update §"Testing Strategy" in lockstep.
- **Decision**: FIXED — chose new `SourceImageRepositoryTest` as the cascade test's home; Phase 1 Success Criteria 1.2 (existing class regression) and 1.3 (new cascade test) collapsed into a single bullet pointing at `SourceImageRepositoryTest`; Progress and §"Testing Strategy" updated in lockstep; manual verification renumbered 1.4 → 1.3.

### F4 — Phase 2 needs a deterministic Clock in @SpringBootTest; pattern is unprecedented and unspecified

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Plan Completeness
- **Location**: Phase 2 — Success Criteria 2.1
- **Detail**: Test 2.1 says "after `runExtraction` returns the image's `resolvedAt` equals the **fixed `Clock` instant**". Research.md:117 flagged: "there is no `@MockitoBean Clock` anywhere" — only `OpenRouterLlmVisionClientPromptTest` has a `Clock.fixed` precedent via hand-construction. Plan doesn't pick between (a) `@MockitoBean Clock` (which fragments the `@SpringBootTest` context cache — see lessons.md:47-55 — and propagates to every other Clock consumer: `EventReviewService`, `ExtractionJobRegistry`, `CalendarController`, etc., some of which assert on real time), (b) `@TestConfiguration` with a `@Primary Clock` bean, or (c) assert `resolvedAt` is between test-start and test-end rather than `equals` a fixed instant. Each has different cache and blast-radius implications. Phase 4 inherits the same problem because the scheduler also needs the same deterministic Clock for its `duration_ms` log assertion.
- **Fix**: Specify the deterministic-Clock pattern explicitly in Phase 2 (likely choice: `@TestConfiguration`-scoped `@Primary Clock` bean in a shared test-support class — same scope as `UserTestFixtures` — that all S-06 tests can opt into). Note it as the seed for the §6.9 cookbook entry alongside the `@Scheduled` pattern. Apply the same choice in Phase 4.
- **Decision**: FIXED via option (b) — added Phase 2 change #2 `FixedClockTestConfig` (new `src/test/java/com/example/app/testsupport/FixedClockTestConfig.java`) with `@TestConfiguration`, `@Bean @Primary Clock fixedClock()`, and `public static final Instant FIXED_INSTANT`. Phase 2 success criteria (2.1 / 2.3) and Phase 4 scheduler test rewritten to `@Import(FixedClockTestConfig.class)` and assert against `FIXED_INSTANT`. Phase 4 context-cache wording (4.3) updated — opt-in `@Import` does not globally fragment the cache the way `@MockitoBean Clock` would. Pattern marked as the seed for the §6.9 cookbook entry on the contract.

### F5 — Phase 3 JPQL references wrong enum class name

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 3, change #1 — `SourceImageRepository.purgeEligible` JPQL
- **Detail**: Contract says `pe.status = com.example.app.event.ProposedEvent.Status.PENDING`. Actual enum is `ProposedEvent.ProposedEventStatus.PENDING` (`ProposedEvent.java:29-33`). Plan hedges in the parenthetical ("implementer confirms"), so this won't surprise anyone, but a plan that states the wrong symbol is a small grounding miss worth fixing in place.
- **Fix**: Replace `Status.PENDING` with `ProposedEventStatus.PENDING` throughout Phase 3's contract text.
- **Decision**: FIXED — Phase 3 change #1 JPQL updated to `ProposedEvent.ProposedEventStatus.PENDING`; parenthetical hedge rewritten to a positive reference to `ProposedEvent.java:29-33`.

### F6 — Phase 5 stacks new JavaDoc on top of stale text that S-06 has now obsoleted

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 5 — `EventReviewController` JavaDoc
- **Detail**: Existing JavaDoc on `EventReviewController.review` (lines 58-73) already contains the S-06 hand-off: "revisit when S-06 purge or any other flow can delete `SourceImage` rows while extraction is in flight — at that point surface a synthetic `errorKind=IMAGE_GONE` via the status response". S-06 IS that revisit, and Phase 5's explicit decision is the opposite: "plain 404, no IMAGE_GONE, no tombstone". Plan tells the implementer to "Add a JavaDoc block above the `review` GET handler" (additive). After the implementation, the file carries two JavaDoc paragraphs in direct contradiction — one saying "do nothing yet", one saying "decision is plain 404, no tombstone".
- **Fix**: Phase 5 should REPLACE (not stack onto) lines 67-71's stale "revisit when S-06" sub-bullet — fold the post-purge 404 contract into the existing JavaDoc instead of bolting a separate block on top.
- **Decision**: FIXED — Phase 5 change #1 §Intent rewritten from "Add a JavaDoc block above" to "REPLACE the stale 'revisit when S-06' sub-bullet at `EventReviewController.java:67-71`"; §Contract preamble updated to "Replace the existing 'revisit when S-06 purge ...' sub-bullet" before the verbatim text block.

### F7 — Phase 3 misattributes what the F′ regression-guard test actually pins

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 3, change #3 — "Truth-table integration tests" rationale; also §"Key Discoveries" truth-table row F′
- **Detail**: Plan claims the F′ test ("seed `resolvedAt=null` manually, assert image survives") "proves the test would catch a regression if someone removed Phase 2's stamp". That's not what F′ does — F′ pins predicate clause 3 (`resolvedAt IS NOT NULL`). If Phase 2's stamp were removed, F-stamped images would morph back into F′-unstamped images, both tests still pass, and the leak silently returns. The actual regression-guard for Phase 2's stamp is test 2.1 ("after `runExtraction`, image's `resolvedAt` equals the fixed Clock instant"). Implementer will write both tests regardless, so this doesn't break anything — but the rationale should be honest.
- **Fix**: In Phase 3 change #3 and the truth-table prose, reattribute F′ from "pins Phase 2 as load-bearing" to "pins predicate clause 3 (`resolvedAt IS NOT NULL`) as load-bearing".
- **Decision**: FIXED — Phase 3 change #3 §Intent trailing clause rewritten to "pinning JPQL predicate clause 3 (`resolvedAt IS NOT NULL`) as load-bearing — without this clause, never-stamped images (legitimate failures, manual seeds, in-flight extractions) would be eligible for purge. Phase 2's stamp itself is regression-guarded separately by test 2.1." §Key Discoveries F′ truth-table row Notes column rewritten to "regression guard for predicate clause 3 (`resolvedAt IS NOT NULL`), not for Phase 2's stamp — see test 2.1". §Testing Strategy `SourceImagePurgeServiceTest` line updated in lockstep.
