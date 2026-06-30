<!-- PLAN-REVIEW-REPORT -->
# Plan Review: Sentry instrumentation (errors-only)

- **Plan**: context/changes/sentry-instrumentation/plan.md
- **Mode**: Deep
- **Date**: 2026-06-29 (initial); 2026-06-30 (triage applied)
- **Verdict**: REVISE → **SOUND** after triage (all 8 findings fixed)
- **Findings**: 0 critical · 6 warnings · 2 observations — all FIXED

## Verdicts

| Dimension | Verdict (initial → post-triage) |
|-----------|---------|
| End-State Alignment | WARNING → PASS (F2 surface 4 coverage rewritten; LLM-sanitization honesty added) |
| Lean Execution | PASS |
| Architectural Fitness | PASS |
| Blind Spots | WARNING → PASS (F3 rule 6 split; F5 key-propagation test; F8 rationale split) |
| Plan Completeness | WARNING → PASS (F1 8→10 rules; F4 dev override beans + recipe; F6 both layers committed; F7 endpoint recipe) |

## Grounding

10/10 paths ✓ (`ExtractionService`, `SourceImagePurgeScheduler`, `ExtractionJobRegistry`, `MaxUploadSizeExceededHandler`, `AppApplication`, `application.properties`, `application-e2e.properties`, `deploy.yml`, `fly.toml`, `Dockerfile`); `extractionExecutor` + `log.error` sites confirmed in code; brief ↔ plan consistent.

## Findings

### F1 — Scrubber spec promises "7 tests prove each rule"; plan ships 8 rules and 7 tests

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Plan Completeness
- **Location**: Phase 1 §5 (rules 1–8) vs §7 (7 test methods); Desired End State
- **Detail**: Desired End State says "Seven JUnit tests prove each rule independently; the test suite fails if a rule regresses." Phase 1 §5 actually enumerates 8 rules (rules 1–7 + "bonus" rule 8 = Authorization/Bearer scrub). Phase 1 §7 enumerates 7 tests. Mapping: tests 1+2 both cover rule 1; test 5 (`scrubsPhotoBytesFromViewModelExtra`) covers rule 3 (`sourceImage`); **rule 5** (`event.removeExtra("data")` — `SourceImage.data` bytea leak) has NO matching test; **rule 8** (Authorization/Bearer scrub) has NO matching test. The safety-net claim doesn't hold for two rules.
- **Fix A ⭐ Recommended**: Add two tests (`scrubsRawBytesFromDataExtra`, `scrubsAuthorizationBearerFromMessage`); update Desired End State / Progress 1.3 to "8 tests, one per rule".
  - Strength: Restores the spec↔test parity that the plan made the safety net for the whole change.
  - Tradeoff: ~30 LOC across the test class + 2 progress edits.
  - Confidence: HIGH — both rules are pure-string transforms, trivially testable.
  - Blind spot: None significant.
- **Fix B**: Drop rules 5 and 8 from the implementation; rely on existing upstream defaults (rule 5 was already defensive; rule 8 redundant with `send-default-pii=false`).
  - Strength: Removes asymmetry without writing more code; preserves the "7 surfaces, 7 tests" symmetry the spec claimed.
  - Tradeoff: Surrenders defense-in-depth on bytea + Authorization paths.
  - Confidence: MEDIUM — depends on `send-default-pii=false` semantics still unconfirmed (see F8).
  - Blind spot: Whether any current `log.error` already includes a Bearer fragment under specific OpenRouter error shapes.
- **Decision**: FIXED (Fix A) — Plan now spec'd at 8 rules + 8 tests; Desired End State, Phase 1 §7, Phase 1 success criteria, Testing Strategy, and Progress 1.3 all updated.

### F2 — Surface 4 (extracted event content / child names) only partially scrubbed

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: End-State Alignment
- **Location**: Current State Analysis Key Discoveries; Phase 1 §5 rule 6
- **Detail**: Current State Analysis names 8 PII surfaces; surface #4 is "extracted event content (titles/requirements/notes including possible child names)". The plan's only scrubber touching it is rule 6 (FieldError.invalidValue) — the validation-error path. But extracted content surfaces in many non-validation channels left uncovered:
  - `ExtractionService.runExtraction()` `log.error("extraction_failed imageId=… providerMessage={}", … ex.providerMessage())` at `src/main/java/com/example/app/event/ExtractionService.java:75` — OpenRouter can echo prompt or partial output in error messages.
  - JPA persistence exceptions on `ProposedEvent.save()` (line 62-63) — the failing row's `title`/`requirements`/`notes` end up in the SQLException message via Hibernate, which becomes the Sentry event title.
  - Any future `applyDecisions` / review-stage error surfacing event title or requirements.
  The plan promises this surface is covered; the implementation covers ~10% of the channels through which the content actually leaks.
- **Fix ⭐ Recommended**: Add a generic ninth rule: on event `message`/`logentry.formatted` and breadcrumb messages, if substring matches a `ProposedEvent` shape OR a JPA exception pattern, redact title/requirements/notes content. Pair with a unit test synthesizing a `DataIntegrityViolationException` with a `proposed_event.title='…' duplicate key` substring and asserting the title is redacted. Update Key Discoveries to acknowledge the 9th rule.
  - Strength: Closes the actual leak channel for the most sensitive surface (child names); bigger blast radius than per-extras drops.
  - Tradeoff: Regex over event message is more error-prone than key-based drops; pattern needs care to avoid redacting unrelated SQL.
  - Confidence: MEDIUM — regex isn't hard but tuning across JPA dialects takes one pass.
  - Blind spot: Whether Sentry's `logentry.formatted` is what arrives from the Logback appender — verify via Context7 / 30-line smoke before locking the shape.
- **Decision**: FIXED (Fix differently — three-layer defense per user direction):
  - **Rule 9** (narrow, deterministic): scrub entire message to `[REDACTED SQL ROW DATA]` when exception type ∈ `{PSQLException, DataIntegrityViolationException, SQLException}` AND message contains `proposed_event`. No regex tuning over arbitrary content.
  - **Rule 9 tests**: 1 positive (`scrubsProposedEventRowDataFromJpaException`, PSQLException + SQLState 23505 + `Key (title)=(Adam urodziny)`) + 1 negative (`doesNotScrubUnrelatedSqlException`) to prevent false positives. Total tests bumped to 10.
  - **§5b — `lessons.md` entry** added to Phase 1 contract: "Never log `ProposedEvent`/`ExtractedEvent` entities or their `toString()` — log `id` + `status` only." Closes the third leak channel (undisciplined contributor `log.error(..., entity)`) that no scrubber can structurally catch.
  - **Key Discoveries** rewritten to enumerate the three real channels (Spring binding / JPA constraint / undisciplined log) and to honestly characterize LLM-error sanitization (dominant path sanitized to `code/type/param`; narrow `RuntimeException` catch-all on `OpenRouterLlmVisionClient:116-120` still propagates raw `e.getMessage()`).

### F3 — Rule 6 targets a Sentry slot Spring binding errors don't actually populate

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Blind Spots
- **Location**: Phase 1 §5 rule 6; §7 test 6 (`scrubsInvalidValueFromFieldError`)
- **Detail**: The rule scrubs "any extra whose key matches `bindingResult.*` or whose value contains a `FieldError`-shaped string". But Sentry's Java SDK and the Logback appender do NOT auto-attach Spring `BindingResult` as named event extras. `MethodArgumentNotValidException` surfaces with its `BindingResult` rendered into the **exception message** string (`"Field error in object 'eventForm' on field 'title': rejected value [actual child name]…"`), not as `event.extras["bindingResult.*"]`. The test constructs `extra.bindingResult` directly — a structure that won't appear in production. Both rule and test exercise a phantom path; the real PII channel (the exception message) stays unscrubbed.
- **Fix**: Rewrite rule 6 to operate on `event.message` / exception message: redact any substring matching `rejected value \[([^\]]*)\]` → `rejected value [REDACTED]`. Rewrite the test to construct a real `MethodArgumentNotValidException` from a `DataBinder` bound to a throwaway DTO with `@NotBlank title` and `title="Adelka K."`, run through the callback, assert `[REDACTED]` present and `Adelka` absent.
  - Strength: Hits the actual leak channel; test exercises the production shape end-to-end via a real Spring exception.
  - Tradeoff: Test ~10 lines longer than the synthetic-extra version.
  - Confidence: HIGH — the `rejected value [...]` formatting is part of Spring's stable `DefaultMessageSourceResolvable.toString()` contract since Spring 3.
  - Blind spot: Other validation paths (Hibernate Validator standalone `ConstraintViolationException`) format differently and would still leak — note this in the rule body.
- **Decision**: FIXED (Fix differently — two-arm defense + honesty per user direction):
  - **Rule 6 split into 6a + 6b**: 6a keeps the extras-key scrub (defensive against explicit `Sentry.setExtra(...)` of binding state); 6b adds the regex `rejected value \[[^\]]*\] → rejected value [REDACTED]` on event message / exception message / breadcrumb messages (defensive against a future REST endpoint without a `BindingResult` parameter).
  - **Two tests** replace one: `scrubsInvalidValueFromFieldErrorExtra()` (rule 6a — synthetic extra) and `scrubsRejectedValueFromExceptionMessage()` (rule 6b — real `MethodArgumentNotValidException` via `DataBinder` on a throwaway `@NotBlank` DTO with `title="Adelka K."`).
  - **Key Discoveries rewritten** to acknowledge that the binding-error channel does NOT currently leak in this codebase (every `@Valid` is paired with a `BindingResult` param; no controller logs the form) — rule 6 is forward-defensive, not active sealing.
  - **Counts bumped to 10 rules / 12 tests** (11 positive + 1 negative on rule 9); Desired End State, §7, success criteria, Testing Strategy, Progress 1.3, and Phase 1 title updated together.

### F4 — Dev `/__dev/force-error/purge` mechanism is undefined; sweep() catches RuntimeException

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Plan Completeness
- **Location**: Phase 3 §1, second endpoint
- **Detail**: Contract: "directly invokes `sourceImagePurgeScheduler.sweep()` after injecting a temporary state that forces a `RuntimeException` (e.g., a corrupted entity or a mocked repository throw)." But `SourceImagePurgeScheduler.sweep()` at `src/main/java/com/example/app/event/SourceImagePurgeScheduler.java:48-59` catches `RuntimeException` internally and only logs ERROR — it cannot rethrow. A `force-error/purge` endpoint invoking `sweep()` will not propagate an exception; the "scheduled-error" path it claims to exercise is actually the **Logback appender capturing the inner `log.error("source_image_purge_sweep_failed", ex)`**, not a bubble. There is no way to "inject temporary state" from a REST handler without mock surgery on the prod `purgeService` bean.
- **Fix ⭐ Recommended**: Call `purgeService.purgeEligible()` directly (one level deeper, where throw still bubbles) wrapped in `try { } catch (RuntimeException ex) { log.error("dev_smoke purge_failure", ex); throw ex; }`. To force the underlying throw, seed a temporary `SourceImage` row with `markedDeletedAt=NOW()-1d` and a deliberately corrupted FK violating a constraint at delete time. Document seed+cleanup in runbook step 4.
  - Strength: Exercises the actual production error-recovery code path; smoke verifies the same Logback capture prod will use.
  - Tradeoff: Runbook gains 2 setup/cleanup steps; controller gains a DB seed dependency.
  - Confidence: MEDIUM — depends on whether a constraint can be reliably violated at delete time on the current schema; might need a JdbcTemplate-level row tweak instead.
  - Blind spot: Whether the seeded row leaves residue if cleanup fails.
- **Decision**: FIXED (Fix differently — mirror existing `StubLlmVisionClient @Primary @Profile("e2e")` pattern):
  - **New Phase 3 §2** introduces two dev override beans in `com.example.app.observability.devtools`:
    - `DevFailableLlmVisionClient @Primary @Profile("dev")` (implements `LlmVisionClient`, delegates to `OpenRouterLlmVisionClient`, throws `LlmExtractionException.provider(0, "dev smoke: forced LLM failure", null)` when `failNextCall` flag is set).
    - `DevFailableSourceImagePurgeService @Primary @Profile("dev")` (extends `SourceImagePurgeService`, throws `RuntimeException("forced purge failure for dev smoke")` when `failNextCall` flag is set). Verified `SourceImagePurgeService` is public non-final with public constructor — extension is straightforward.
  - **`/__dev/force-error/purge` endpoint** rewritten: flips `DevFailableSourceImagePurgeService.failNextCall = true`, calls `sourceImagePurgeScheduler.sweep()`. The scheduler's existing `catch (RuntimeException ex) { log.error("source_image_purge_sweep_failed", ex); }` runs — the exact prod-incident path (`scheduler.catch → log.error → Sentry-Logback → event`).
  - **`/__dev/force-error/extraction` endpoint** rewritten in same shape: flips `DevFailableLlmVisionClient.failNextCall = true`, dispatches `extractionService.runExtraction(jobId, sentinelImageId)` through `extractionExecutor`. The throw runs through the real `ExtractionService.runExtraction()` catch path, stamping correlation ID + persisting `SourceImage` + emitting `extraction_failed imageId=… correlationId=… kind=PROVIDER_ERROR` — same shape as a prod incident. (Also resolves F7's concern about the synthetic-executor-throw path.)
  - **Non-dev gating test** (now §3) bumped to also assert `DevFailableLlmVisionClient` and `DevFailableSourceImagePurgeService` beans are absent under default profile.
  - **Key Discoveries** gains: "Dev smoke verifies the exact prod-incident pipeline … the smoke is not a synthetic shortcut; it is the real path with a forced failure."

### F5 — `sentry.profile-session-sample-rate` key name unverified; ErrorsOnlyEnforcementTest can pass with the key ignored

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Blind Spots
- **Location**: Phase 1 §2 (properties), §8 (test); Plan Brief Open Risk #1
- **Detail**: Plan-brief flags this as open risk; Phase 1 properties block sets only `sentry.profile-session-sample-rate=0.0`; Critical Implementation Details says "If 8.x also accepts `sentry.profiles-sample-rate` as an alias, set both defensively" — but the properties file does not actually set both. `ErrorsOnlyEnforcementTest` asserts `SentryOptions.profileSessionSampleRate == 0.0`, which IS the SDK's default for 8.x. So if the property name is wrong, the SDK applies its own default (0.0), the test passes, and the property has no effect. The "explicit zero-out" safety contract is undermined: a future SDK that bumps the default or adds a profiler feature behind a different key would silently activate.
- **Fix ⭐ Recommended**: Resolve the key name at plan time via Context7 against `/getsentry/sentry-java` v8.x (30-second lookup); commit to the correct key in the properties block. Harden the test: instead of asserting `== 0.0`, log the resolved value via `IHub.getOptions().toString()` and snapshot it; or assert the property was actually consumed by the binder (e.g., `Environment.getProperty(...)`-vs-options cross-check).
  - Strength: Removes a knowable unknown before code is written; hardened test catches "key ignored" not just "value matches default".
  - Tradeoff: ~5 extra minutes of Context7 research; test ~5 lines longer.
  - Confidence: HIGH — exactly the kind of question Context7 is for; sentry-java's docs are well-indexed there.
  - Blind spot: None significant.
- **Decision**: FIXED (Fix recommended, hardening-first execution):
  - **Context7 lookup attempted**: queried `/getsentry/sentry-java` for 8.x property keys. Returned hits for `traces-sample-rate`, `sentry.enabled`, `dsn` but did NOT surface the canonical key for continuous profiling (no `profile-session-sample-rate` / `profiles-sample-rate` snippet). Honest note in Critical Implementation Details: canonical 8.x key name unconfirmed at plan time.
  - **§8 split into 8a + 8b**:
    - `ErrorsOnlyEnforcementTest` (8a) keeps the prod-config value check.
    - **New `SentryPropertyKeyPropagationTest` (8b)** — `@SpringBootTest` + `@TestPropertySource` overrides each errors-only key to a deliberate non-default (`traces-sample-rate=0.42`, `profile-session-sample-rate=0.37`, `logs.enabled=true`) and asserts the value propagates to `SentryOptions`. If the key name is wrong (typo today, or 9.x rename later), the SDK applies its own default instead of the override; the assertion fails with a clear "key not consumed" diagnostic. This is a stronger safety net than verifying one specific name — it works regardless of which key turns out to be canonical, and it survives SDK upgrades.
  - **Progress** gains step `1.2b`; **Desired End State**, **Phase 1 verification**, **Testing Strategy** updated to describe both tests and the propagation safety net.
  - **Critical Implementation Details** rewritten to explain why the propagation test exists (SDK default 0.0 == prod value 0.0 makes naive value-only assertion vacuous) and to give operator guidance for when the test fails on a future SDK upgrade (grep `ExternalOptions.from(...)` for the new key spelling).

### F6 — Boot-time errors-only enforcement is "test-only OR @PostConstruct" — choose one

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 1 §5 closing paragraph; Phase 1 §8; Critical Implementation Details
- **Detail**: The plan offers a menu ("`@PostConstruct`, `ApplicationReadyEvent` listener, or test-only assertion") and leaves the choice to the implementer. These are not equivalent: test-only blocks deploy only if CI runs; `@PostConstruct` `IllegalStateException` crashes prod startup on misconfig (defense-in-depth at cost of one boot failure). Without committing, the implementer might pick "test-only" because it's cheaper; a contributor who later force-merges past CI ships a misconfig.
- **Fix**: Commit to **both**: the dedicated `ErrorsOnlyEnforcementTest` (already mandated in §8) AND a `@PostConstruct` runtime check on `SentryConfig` that throws `IllegalStateException` on mismatch. Test catches it before merge; runtime check catches it if CI is bypassed or the SDK loads options post-startup.
- **Decision**: FIXED (Fix recommended):
  - **Phase 1 §5 closing paragraph** rewritten to mandate BOTH layers: `@PostConstruct` runtime check in `SentryConfig` (active under default + dev + production profiles; skipped under test/e2e where `sentry.enabled=false`) AND the §8a `ErrorsOnlyEnforcementTest`. "Why both" rationale captured: test gates merge, runtime check gates boot.
  - **Critical Implementation Details** rewritten to remove the "either/or implementer choice" language; commits to both mechanisms explicitly.
  - **Desired End State** updated to mention the runtime check alongside both tests.
  - **Manual Verification + Progress 1.9** added: starting `bootRun` with a deliberately bad `sentry.traces-sample-rate=0.1` must crash with `IllegalStateException` — proves the runtime layer is wired.

### F7 — Dev `/extraction` endpoint bypasses the real `runExtraction()` error path

- **Severity**: 🔍 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 3 §1, first endpoint
- **Detail**: Contract: `extractionExecutor.execute(() -> { throw new LlmExtractionException(...); })`. The throw happens at executor level, bypassing `ExtractionService.runExtraction()` entirely. The production error path catches `LlmExtractionException`, sets correlationId on the `SourceImage`, persists it, and emits a structured log `extraction_failed imageId=… correlationId=… kind=…`. The synthetic throw exercises the executor's default uncaught-exception handler, not the real log shape. Sentry verifies "an error from `@Async` reaches us with TaskDecorator scope" but not "the real `extraction_failed` event arrives with correlationId tag" — the actual operational signal.
- **Fix**: Have the endpoint dispatch through `extractionService.runExtraction(jobId, missingImageId)` with a deliberately bad imageId — that path hits the `image == null` branch which already emits the exact production log shape with correlationId.
- **Decision**: FIXED (resolved by F4 + explicit endpoint details per user):
  - F4's `DevFailableLlmVisionClient @Primary @Profile("dev")` override + `extractionService.runExtraction(jobId, sourceImageId)` dispatch mechanism mechanically resolves this finding — the throw goes through the real catch-and-log path.
  - Phase 3 §1 `/extraction` endpoint contract expanded to a 6-step recipe: (1) insert throwaway `SourceImage`, capture id, (2) flip `failNextCall`, (3) dispatch via `extractionExecutor.execute(...)` (preserves async + `SentryTaskDecorator` scope propagation from F1 fix), (4) return 202, (5) `DevFailableLlmVisionClient` throws `LlmExtractionException.provider(503, "code=forced_dev_error type=test param=?", null)` — wire shape identical to `OpenRouterLlmVisionClient:105-110`'s sanitized provider error, hitting `ExtractionService:75` (`kind=PROVIDER_ERROR`) for matching Sentry-event signature, (6) throwaway row cleaned up by the next `SourceImagePurgeScheduler.sweep()` under the existing 3-clause predicate.
  - The dev-override bean's exception now mirrors the real prod-error sanitization — dev events are indistinguishable from real ones in Sentry.

### F8 — `sentry.send-default-pii=false` Java SDK semantics deferred to empirical Phase 3 smoke

- **Severity**: 🔍 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Blind Spots
- **Location**: Plan Brief Open Risk #2; Phase 1 §2
- **Detail**: Plan-brief lists this as TBD; plan defers to empirical Phase 3/4 verification. But what `send-default-pii` actually toggles (IP? Principal? cookie headers? Authorization?) determines whether rule 8 (Authorization Bearer) is necessary defense, redundant, or insufficient. Same Context7 lookup as F5 resolves it in 5 minutes.
- **Fix**: Resolve at plan time via Context7 on `/getsentry/sentry-java`; use the result to either drop rule 8 (Authorization auto-stripped) or keep it with a clarified rationale (rule 8 catches body content, not headers).
- **Decision**: FIXED (Keep rule 8 + 4-part rationale split per user):
  - **Part A — Phase 1 §5 rule 8** rewritten: drops "bonus / covered by `send-default-pii=false`" framing; explicit pattern `Authorization: Bearer [^\s]+` → `Authorization: Bearer [REDACTED]`; broadened scope from event.message only to event.message + `event.exception.values[].value` + breadcrumb messages; rationale states "Complementary to `send-default-pii=false`, not redundant with it" with concrete examples (OpenAI SDK exception cause; flattened request headers in a future `log.error`).
  - **Part B — Key Discoveries** gains a responsibility-split table mapping each surface to either the SDK flag (inbound request metadata) or rule 8 (body-content / message / exception value / breadcrumb). Five rows; no overlap; the Phase 3/4 Bearer-grep stays as defense-in-depth confirmation.
  - **Part C — plan-brief Open Risk #2** moved from "TBD / verified empirically" to "resolved by rationale split"; the empirical Phase 3/4 smoke is reframed as expected confirmation, not pending resolution. (Open Risk #1 similarly marked resolved by F5's propagation-test mechanism.)
  - **Part D — Phase 4 verification checklist** Bearer grep step explicitly added (`grep -iE 'Bearer [A-Za-z0-9_\-\.]{16,}'`) alongside the existing email + iCal-token greps; rationale annotation pins it to the rule 8 / SDK flag split.
  - **Rule 8 test** (`scrubsAuthorizationBearerFromMessage`) contract expanded to assert all three carriers: `event.message`, `event.exception.values[].value`, breadcrumb message — confirms the broader body-content scope.
