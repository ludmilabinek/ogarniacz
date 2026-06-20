<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Image extraction + review/acceptance (S-05)

- **Plan**: context/changes/image-extraction-and-review-acceptance/plan.md
- **Scope**: All 5 phases (full plan)
- **Date**: 2026-06-21
- **Verdict**: APPROVED
- **Findings**: 0 critical · 0 warnings · 4 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | PASS |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

## Coverage

- 36/36 plan items verified across phases 1–5.
- `./gradlew build` — green (cached UP-TO-DATE on commit 90ddd7a).
- All success-criteria checkboxes in plan §Progress completed.
- Notable correctness callouts that landed as planned:
  - `SourceImage.data` uses `@Column(columnDefinition="bytea")`, not `@Lob` (avoids PG LO storage requiring `lo_unlink` on delete).
  - `ExtractionService.runExtraction` catches `LlmExtractionException` then generic `RuntimeException` with `errorKind="UNEXPECTED"` — proper exception ordering, image's `lastErrorKind` + `correlationId` always set on failure.
  - `ExtractionStatusController` cross-user → 404 (not 403) to prevent jobId enumeration.
  - `EventReviewService.applyDecisions` is `@Transactional` and only stamps `resolvedAt` when null — preserves S-06 purge anchor across replays.
  - `EventReviewController.GET review` branch order: error → running → empty → happy, with explicit ordering comment.
  - `@Async` self-invocation hazard avoided — controllers inject `ExtractionService` as a separate bean.
  - Cross-user `findByIdAndUser` partition on every `SourceImage` boundary; `with(csrf())` on every POST test.
  - `@MockitoBean LlmVisionClient` is the hermetic seam in `ExtractionServiceTest` + `ImageUploadControllerTest`.

## Findings

### F1 — MaxUploadSizeExceededHandler implemented as filter, not @RestControllerAdvice

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: src/main/java/com/example/app/event/MaxUploadSizeExceededHandler.java
- **Detail**: Plan §Phase 2.3 specified `@RestControllerAdvice(assignableTypes=ImageUploadController.class)`. Implementation is a `OncePerRequestFilter` at `HIGHEST_PRECEDENCE` because `CsrfFilter` forces multipart parse before the `DispatcherServlet` runs, so `@ControllerAdvice` never fires. JavaDoc documents the refactor. The p5 fix (catch `SizeException` parent to cover both per-file `FileSizeLimitExceededException` and per-request `SizeLimitExceededException`) is pinned by `ImageUploadOversizeIntegrationTest` against 16 MB + 22 MB uploads. Intent — HTTP 413 + JSON envelope with `file.tooLarge` code — is preserved end-to-end.
- **Fix**: No change. The refactor and the parent-class catch are both intentional and documented.
- **Decision**: ACCEPTED — refactor and parent-class catch are intentional and documented in JavaDoc + p5 commit.

### F2 — review-running.html uses setTimeout(reload) instead of <meta refresh>

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: src/main/resources/templates/events/review-running.html
- **Detail**: Plan §Phase 4b.3b offered `<meta http-equiv="refresh" content="3">` or `<script>setTimeout(...reload(), 3000)</script>` as equivalent alternatives. The JS path was chosen — equivalent observable behaviour (page reloads after 3s with no user action).
- **Fix**: No change.
- **Decision**: ACCEPTED — plan explicitly offered both paths as equivalent; JS path retained.

### F3 — EventReviewService.applyDecisions calls sourceImageRepository.save unconditionally

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/com/example/app/event/EventReviewService.java:79
- **Detail**: The `resolvedAt` guard correctly skips the timestamp on replay, but `sourceImageRepository.save(image)` runs regardless. Inside a `@Transactional` method on a managed entity, this is a redundant explicit save — JPA dirty checking flushes on commit anyway. Idempotency test asserts the `resolvedAt` value, not the `save()` invocation, so behaviour is unchanged either way.
- **Fix**: Move `sourceImageRepository.save(image)` inside the `if (image.getResolvedAt() == null)` block, or drop the explicit save entirely (managed entity flushes on commit). Cosmetic.
- **Decision**: FIXED + ACCEPTED-AS-RULE — explicit `save(image)` dropped (EventReviewService.java); JPA dirty-checking flushes the managed entity on commit. Rule recorded as lessons.md "Inside `@Transactional` on a managed JPA entity, the explicit `save()` is redundant". Focused `./gradlew test` (EventReviewServiceTest + EventReviewControllerTest) green.

### F4 — ExtractionService orphan path: missing image → polling redirects to 404 review

- **Severity**: OBSERVATION
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: src/main/java/com/example/app/event/ExtractionService.java:45-52
- **Detail**: If `sourceImageRepository.findById(imageId)` returns empty in the async thread (race vs. delete, or image never persisted), the registry is marked `FAILED` but the source image was never saved. The browser follows the `FAILED` branch to `/events/from-image/{imageId}/review`, which 404s via `findByIdAndUser`. The user sees a generic 404 page instead of the localized error template. At single-user MVP scale with no concurrent delete path, this race is essentially unreachable.
- **Fix**: Acceptable as-is. Revisit if S-06 (or any future flow) can delete `SourceImage` rows while extraction is in flight — at that point either surface a synthetic `errorKind=IMAGE_GONE` via the status response or document the 404 contract on the GET handler.
- **Decision**: FIXED — 404 contract documented as JavaDoc on `EventReviewController#review`, naming both branches (cross-user 404-not-403, orphan poll → generic 404) and the S-06 revisit trigger. `./gradlew compileJava` green.

## Observations not included in the report

Six lower-bar items were considered and dropped:

- Polish flash pluralisation in `app.html` ("Dodano N wydarzeń" is grammatically off for N=1, 2–4) — UX nit.
- `SourceImage`/`ProposedEvent` `@PrePersist` uses `Instant.now()` directly while `EventReviewService` and `ExtractionJobRegistry` use the injected `Clock`. Matches existing `Event.onCreate()` pattern — package-consistent.
- `ExtractionJobRegistry.findRunningByImageId` is O(n) over the map; bounded at ~12 entries by `(corePool=2 + queue=10)` × 5-min TTL.
- MIME validation is content-type + extension, no magic-bytes sniff. Bytes are stored in `bytea` and never echoed back into a response or template — surface is closed.
- `placeholder-not-a-real-key` fallback in `application.properties` is intentional (Spring AI auto-config requires a non-blank key) and labelled in place.
- Manual `BeanPropertyBindingResult` in `EventReviewController.submitDecisions` is the right tool for conditional per-row validation when `@Valid` cannot conditionally skip rows. Pinned by `postWithInvalidDateOnRejectRowIsAccepted`.
