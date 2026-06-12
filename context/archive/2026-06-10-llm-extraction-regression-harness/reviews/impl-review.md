<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: LLM Extraction Regression Harness

- **Plan**: `context/changes/llm-extraction-regression-harness/plan.md`
- **Scope**: All 4 phases (full plan)
- **Date**: 2026-06-12
- **Verdict**: NEEDS ATTENTION
- **Findings**: 0 critical · 2 warnings · 1 observation

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

Plan executed faithfully — no drift, no missing files, no scope creep. Two reliability concerns in the live-variant recording path are worth fixing before the fixture set grows; one minor pattern cleanup.

`./gradlew test` is green; all Phase 4 grep checks pass.

## Findings

### F1 — Recording-mode write executes before payload-shape assertion

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: `src/test/java/com/example/app/llm/LlmExtractionLiveRegressionTest.java:104-112`
- **Detail**: `writeRecordingAtomically(...)` runs at line 105, THEN the shape assertion (`isNotBlank().contains("[")`) runs at 106-110. If the live model returns a blank or non-JSON-array payload (refusal, rate-limit prose page, etc.), the recording is already on disk and the curator must `git rm` to recover. The class Javadoc and the fixtures README both lean on "never overwrites" as the safety property — but the failure mode here is a BAD first recording, not a re-record. A future operator running recording mode in batch would slip a bad fixture into the source tree before noticing the assertion failure.
- **Fix A ⭐ Recommended**: Hoist the `rawResponse` check above `writeRecordingAtomically` — abort the write if the payload looks unusable.
  - Strength: Removes the test-pollution path entirely; cheap (move 5 lines up). Makes the assertion gate the side effect, matching the spirit of "atomic on success only".
  - Tradeoff: Slightly changes failure ordering — Phase 3 manual step 3.4 should be re-eyeballed once.
  - Confidence: HIGH — standard "validate before write" idiom; no hidden interaction with other test paths.
  - Blind spot: None significant.
- **Fix B**: Document the failed-first-recording recovery path in the recording-mode comment.
  - Strength: Zero code change; matches existing README guidance on `git rm` to re-record.
  - Tradeoff: Leaves the failure mode reachable; relies on every curator reading and remembering the comment.
  - Confidence: MEDIUM — docs are easy to bypass.
  - Blind spot: If recording mode ever gets automated (scheduled job, CI capture), bad payloads can land in commits.
- **Decision**: FIXED via Fix A

### F2 — Partial-write failure can leak `.tmp` files or produce a half-written fixture

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pair-atomicity vs. simpler code
- **Dimension**: Safety & Quality
- **Location**: `src/test/java/com/example/app/llm/LlmExtractionLiveRegressionTest.java:158-173`
- **Detail**: Two independent `createTempFile` + `writeString` + atomic-move cycles. Per-file atomicity holds; pair atomicity does not:
  - If `writeString` fails after `createTempFile`, the `.tmp` file stays in `sourceFixtureDir`.
  - If the FIRST move succeeds (recorded-response.json written) but the SECOND block fails (`writeValueAsString` throws, disk fills, FS errors on the second move), the source tree carries a half-written fixture — response present, meta missing.

  The next grading-mode run then crashes inside `LlmTestFixtures.loadMeta` with `NoSuchFileException` instead of the loud "no recording" message the absent-recording branch designed for. Probability is low (disk/IO failures during a test) but non-zero, and the harness's promised safety property leans on the assumption.
- **Fix**: Wrap each `createTempFile` in try/finally with `deleteIfExists` on the tmp path; either write both temp files first then move both at the end, or harden the `gradeAgainstExpected` entry with an explicit "both files exist" precondition.
  - Strength: Closer-to-true pair atomicity; no orphan files on partial failure; loudens the failure mode if the invariant ever breaks.
  - Tradeoff: ~10 extra lines; pair atomicity is best-effort (move-2 can still fail after move-1).
  - Confidence: MEDIUM — low-probability failure modes; the fix is defensive, not corrective.
  - Blind spot: Windows `ATOMIC_MOVE` behaviour not exercised — the project hasn't tested on Windows.
- **Decision**: FIXED via custom approach — try/finally + `deleteIfExists` on both `.tmp` paths in `writeRecordingAtomically`; call-site precondition before `gradeAgainstExpected` fails loudly when `recorded-response.json` exists but `recorded-meta.json` is missing.

### F3 — `@BeforeEach stubDefaultOptions` duplicated across two test classes

- **Severity**: 🔍 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**:
  - `src/test/java/com/example/app/llm/LlmVisionClientTest.java:37-40`
  - `src/test/java/com/example/app/llm/LlmExtractionRecordedRegressionTest.java:56-59`
- **Detail**: Identical 4-line `@BeforeEach void stubDefaultOptions()` that stubs `chatModel.getDefaultOptions()` non-null. This change already lifted `chatResponseOf` to `LlmTestFixtures` (Phase 1, change #2); the same treatment fits this stub.
- **Fix**: Lift to `LlmTestFixtures.stubDefaultChatOptions(ChatModel)` and call from both `@BeforeEach` blocks.
- **Decision**: FIXED + ACCEPTED-AS-RULE: "When lifting a test helper, sweep sibling `@BeforeEach` / setup blocks for duplication too"

## Excluded (already documented)

The following are already documented as Findings #1–#4 in `change.md` and are not re-flagged here:

1. Greedy `\[.*\]` regex in `OpenRouterLlmVisionClient.java:96`.
2. Recording-mode atomic write produces files with `0600` mode.
3. Live LLM call fires before the recording-existence check.
4. `KNOWN_DIVERGENCES` duplicated across the two harness classes.
