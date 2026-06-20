<!-- PLAN-REVIEW-REPORT -->
# Plan Review: Image extraction + review/acceptance (S-05)

- **Plan**: `context/changes/image-extraction-and-review-acceptance/plan.md`
- **Mode**: Deep
- **Date**: 2026-06-20
- **Verdict (pre-triage)**: REVISE
- **Verdict (post-triage, 2026-06-20)**: SOUND — all 8 findings fixed in the plan
- **Findings**: 0 critical · 5 warnings · 3 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| End-State Alignment | PASS |
| Lean Execution | PASS |
| Architectural Fitness | WARNING |
| Blind Spots | WARNING |
| Plan Completeness | WARNING |

## Grounding

8/8 paths ✓ (Event.java, EventForm.java, EventController.java, EventRepository.java, CalendarController.java, OpenRouterLlmVisionClient.java, LlmExtractionException.java, application.properties), 6/6 symbols ✓ (`@MockitoBean`, `findUpcomingByUser`, `LlmExtractionResult.ProposedEvent`, `Event(user, date, time, title, requirements, notes)` ctor, `LlmExtractionException.Kind`, `permitAll`/`/calendar/*.ics`), brief↔plan ✓ (six phases, decisions table, in/out scope all consistent).

## Findings

### F1 — Heap budget per request meaningfully under-counted

- **Severity**: ⚠️ WARNING
- **Impact**: 🔬 HIGH — architectural stakes; think carefully before deciding
- **Dimension**: Architectural Fitness
- **Location**: "Performance Considerations" (plan.md:598)
- **Detail**: Plan states "worst-case in-flight = 2 × 15 MB = 30 MB". The real per-request floor is closer to ~50–70 MB: `MultipartFile.getBytes()` copy on the controller thread (~15 MB) + the `@Lob byte[]` held in the JPA persistence context during the tx (~15 MB) + `OpenRouterLlmVisionClient` (OpenRouterLlmVisionClient.java:77-90) wraps the bytes in `ByteArrayResource` which Spring AI base64-inlines into the JSON request body (~20 MB string) + Jackson + HTTP-client buffers. Two concurrent extractions on a 1 GB Fly Machine (heap ~512 MB) → 100–140 MB just for image churn. Survivable at single-user MVP, but the documented budget is wrong, which will mislead the first scale-up decision.
- **Fix**: Replace the "2 × 15 MB = 30 MB" line with a realistic per-request floor (~50 MB). Add a Phase 5 manual-verification step that reads `jvm.memory.used` (via `/actuator/metrics`) after a genuine ~12 MB photo upload, so future scaling has a baseline.
  - Strength: Honest budget; cheap to add; matches the plan's "documented constraints" discipline.
  - Tradeoff: None — the cap (core=2, max=2, queue=10) doesn't need to change at MVP scale; only the documentation does.
  - Confidence: HIGH — Spring AI's base64 inlining behaviour is verified in OpenRouterLlmVisionClient:77-90.
  - Blind spot: Spring AI may stream-chunk the request body in some configurations — unverified for the OpenRouter path.
- **Decision**: FIXED — Performance Considerations paragraph rewritten with realistic ~50 MB per-request floor and ~100–140 MB concurrent worst-case; Phase 5 manual-verification + Progress entry 5.6 added for a one-off `jcmd GC.heap_info` baseline (actuator is not on the classpath, so the original suggestion was replaced).

### F2 — Retry → empty-state render race on direct review-URL hits

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Blind Spots
- **Location**: Phase 4b, EventReviewController routing (plan.md:427-431)
- **Detail**: Retry handler clears `lastErrorKind` and kicks a new async extraction (plan.md:439). Between that POST returning and the new extraction's terminal `markDone`/`markFailed`, the SourceImage has `lastErrorKind == null` AND zero proposals → routing matches the empty-page branch ("Nie znaleźliśmy wydarzeń"). The retry-form JS polls the status URL and navigates only on terminal state, so the happy path is fine — but a direct review-URL hit (manually typed, browser back, or a polled status race) silently shows the misleading empty page. The plan's GET handler has no RUNNING branch.
- **Fix**: Add a RUNNING render branch to `EventReviewController.GET`: when `resolvedAt == null AND lastErrorKind == null AND zero proposals AND the registry shows a job in RUNNING for this imageId` → render a small "Wyciąganie wydarzeń… odśwież za chwilę" page. Document this branch explicitly in Phase 4b §1 routing rules.
  - Strength: Closes the only state where the GET handler can lie.
  - Tradeoff: Adds a 4th render branch + a registry lookup in the GET path; small surface increase.
  - Confidence: HIGH — race is mechanical, derivable from the routing rules + in-memory registry contract.
  - Blind spot: Cross-restart case (registry has no entry but extraction was running) still lies — acceptable per the existing "in-memory registry, single-user MVP" trade-off.
- **Decision**: FIXED — Phase 4b §1 routing gained a RUNNING branch (with explicit ordering), §3b adds `events/review-running.html` with a `<meta http-equiv="refresh" content="3">` auto-refresh per user direction (self-healing screen, no JS framework), `ExtractionJobRegistry` gains `findRunningByImageId(UUID)`, the test list adds `getRendersRunningTemplateWhenExtractionInFlight` and the registry test adds the new helper; Progress section synced (`4b.5` for the running-tab walkthrough, automated test-count updated).

### F3 — Silent no-op on re-decision via browser back; gap in "What We're NOT Doing"

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Blind Spots / Plan Completeness
- **Location**: EventReviewService step 3 (plan.md:347)
- **Detail**: "if its status != PENDING skip (idempotent re-submit)" means a parent who clicks browser-back to fix a row they accidentally rejected gets a 200, no error, no flash — and the row stays REJECTED. The "What We're NOT Doing" section (plan.md:32-43) excludes "edit/delete of accepted events" (S-03's surface) but is silent on re-decisions on the review page itself. Two legitimate strategies are conflated: skip-for-idempotency (correct for duplicate POST replay) and refuse-to-update (a UX choice that needs explicit framing).
- **Fix**: Add to "What We're NOT Doing": "No re-decision on already-decided rows from the review page — second pass through the form skips non-PENDING rows silently." In the review template (Phase 4a §4), hide or disable rows whose status is not PENDING on re-render after first submit so the user can't even attempt a silent-no-op. Alternative (more work): show a banner "This image's proposals are already decided — go to /app to edit accepted events" when GET hits a fully-resolved image.
  - Strength: Makes the UX contract explicit; removes a class of silent failure; tiny template diff.
  - Tradeoff: Slightly different review-template logic for the "already-resolved" path; small surface increase.
  - Confidence: HIGH — the skip semantics are unambiguous in plan.md:347.
  - Blind spot: A second image upload by the same user is unrelated and unaffected — confirmed.
- **Decision**: FIXED — "What We're NOT Doing" gains an explicit "no re-decision" entry framing the skip semantics as a UX choice (link to S-03 for accepted-event edits, suggest re-upload for rejected ones); Phase 4a §4 review template hides non-`PENDING` rows via `th:if` and shows an info banner when every row is decided; `ProposedEventDecision` carries a `status` field for the template to read; `EventReviewControllerTest` gains `getAfterDecisionsRendersOnlyPendingRows`; Progress 4a.2 + Phase 4a automated criteria updated to name the new test.

### F4 — Idempotent re-submit overwrites `resolvedAt`, resetting S-06 purge clock

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: EventReviewService step 4 (plan.md:348)
- **Detail**: Step 4 writes `sourceImage.resolvedAt = Instant.now()` unconditionally after the per-row idempotent skip. Replay of the same form (browser back + re-POST) thus overwrites the original timestamp. The plan explicitly names `resolved_at` as S-06's purge scan key (plan.md:75) — a replay effectively resets the purge clock and lets an image linger longer than intended.
- **Fix**: Guard the stamp: `if (sourceImage.resolvedAt == null) sourceImage.resolvedAt = Instant.now();`. Add a one-line assertion to `EventReviewServiceTest.idempotentSecondSubmit` that the original `resolvedAt` is preserved.
- **Decision**: FIXED — Phase 4a `EventReviewService` step 4 rewritten with the guard + S-06 rationale; `EventReviewServiceTest.idempotentSecondSubmit` now asserts `sourceImage.resolvedAt` equals the first-submit value.

### F5 — Content-negotiation contract on POST validation failure left unresolved

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: ImageUploadController POST contract (plan.md:259)
- **Detail**: Plan reads: "Validation failures still re-render the GET form (use Spring MVC content negotiation — the form's XHR sets `Accept: application/json`; if absent, fall back to redirect — or always JSON since the form is XHR-driven)." Two incompatible strategies are left open. With the same controller method handling BOTH the XHR upload (Phase 3+) and the size-rejection redirect (via `MaxUploadSizeExceededHandler`, plan.md:163), the response shape must be specified before code lands.
- **Fix**: Pick always-JSON (form is XHR-driven; validation errors return `{errors: [...]}` with HTTP 422; JS renders inline under `#upload-error`). Document that the `MaxUploadSizeException` handler also returns JSON for the XHR case (not a redirect) so the JS handles both failure paths uniformly. Aligns with F6.
- **Decision**: FIXED — Phase 3 §4 (`ImageUploadController` POST) pinned to always-JSON with an explicit error-code table (`file.empty` 422, `file.wrongType` 422, `file.tooLarge` 413, `csrf.invalid` 403) and a uniform `{ errors: [{ field, code, message }] }` envelope; Phase 2 §2 (`ImageUploadController` POST mapping) updated to return error envelopes for empty/wrong-type with the same shape; Phase 2 §3 (`MaxUploadSizeExceededHandler`) rewritten as `@RestControllerAdvice` returning HTTP 413 (transport semantic, not 422) — this resolves F6 in the same change; Phase 2 §4 (upload form template) lost the `${uploadError}` flash read and gained an inline Polish i18n map keyed by `code` for the JS error-render path; Phase 5 manual verification gained two checks (20 MB JPEG → 413/`file.tooLarge`, `.txt` → 422/`file.wrongType`) with matching Progress entries 5.7 and 5.8; consciously accepted that no-JS users will see raw JSON (out-of-scope progressive enhancement, noted in the contract).

### F6 — MaxUploadSizeExceededHandler redirects, but POST is XHR

- **Severity**: 📝 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness / Blind Spots
- **Location**: MaxUploadSizeExceededHandler (plan.md:163)
- **Detail**: Handler redirects to `/events/from-image` with a flash. But Phase 3 switches the POST to XHR — the browser sees a 302, and unless the JS explicitly handles `xhr.status === 302` (XHR auto-follows by default), the redirect target page won't show the flash to the same user-visible surface where the JS pre-flight error lives. Symmetric with F5; resolve together.
- **Fix**: Replace the redirect/flash with a JSON `{error: "..."}` + HTTP 413 response for XHR uploads. Drop the flash mechanism.
- **Decision**: FIXED — subsumed by F5's always-JSON contract: Phase 2 §3 `MaxUploadSizeExceededHandler` is now `@RestControllerAdvice` returning HTTP 413 with the same `{ errors: [{ field, code, message }] }` envelope as the rest of the upload error paths; redirect/flash mechanism dropped. No additional plan edit needed.

### F7 — "UNEXPECTED" error-kind branch likely never fires for LlmVisionClient itself

- **Severity**: 📝 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Lean Execution
- **Location**: ExtractionService failure flow (plan.md:251)
- **Detail**: `OpenRouterLlmVisionClient` already converts every `RuntimeException` to `LlmExtractionException` (catch-all at OpenRouterLlmVisionClient.java:116-121 → `LlmExtractionException.provider(0, ...)`). So "UNEXPECTED" only catches errors AROUND the extract call (entity reload, persistence, registry update). The plan reads as if the LLM call itself can throw a non-LLM `RuntimeException` — it cannot.
- **Fix**: Reword the bullet to "On any other `RuntimeException` (e.g., `SourceImage` reload failure, persistence error): mark FAILED with `errorKind = 'UNEXPECTED'`…" — clarifies the branch's actual purpose.
- **Decision**: FIXED — Phase 3 §3 `ExtractionService` step 5 rewritten to name the actual `UNEXPECTED` sources (entity reload, persistence, registry update) and cross-references `OpenRouterLlmVisionClient.java:116-121` for the LLM-side wrap that makes step 4 exhaustive for model-path failures.

### F8 — Stub review controller location ambiguous in Phase 2

- **Severity**: 📝 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 2 stub review page (plan.md:179)
- **Detail**: Phase 2 §5 says the stub GET handler is "added to `ImageUploadController` or a new `EventReviewController`". Phase 4a creates `EventReviewController` and moves the handler there. Leaving the location open in Phase 2 risks needless code movement in Phase 4a (or, worse, a missed move + cross-controller routing inconsistency).
- **Fix**: Pick `EventReviewController` from Phase 2 onward — Phase 2 creates it with just the stub GET handler; Phase 4a expands it with the real GET + POST decisions + retry.
- **Decision**: FIXED — Phase 2 §5 stub controller location pinned to a new `EventReviewController` (no `ImageUploadController` fallback); Phase 4a §1 contract reworded from "Move the stub… to this new controller" to "expand the existing GET" — no code move at Phase 4a, per-controller test layout intact from Phase 2.
