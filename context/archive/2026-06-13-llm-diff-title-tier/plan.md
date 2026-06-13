# Relax Fixture Diff: Title Becomes Informational — Implementation Plan

## Overview

Stop asserting on the `title` field in `LlmTestFixtures.diff()` so accuracy measures what the user values — date, optional time, requirements. Title stays in `expected.json` as informational, stays in `KnownDivergence` for failure-message readability, and stays in `canonicalSort` as a tertiary tie-breaker for deterministic ordering. Lands before `llm-prompt-year-resolution` so the year-fix lift is measured against an honest baseline, and so the year fix cannot unmask previously-hidden title divergences.

## Current State Analysis

- `LlmTestFixtures.diff()` ([src/test/java/com/example/app/llm/LlmTestFixtures.java:117](src/test/java/com/example/app/llm/LlmTestFixtures.java:117)) compares per-field in this short-circuiting order: `date` → `time` → `title` → `requirements`. Each comparison uses `Objects.equals` or `norm()` (NFC + lowercase + whitespace collapse).
- `LlmTestFixtures.canonicalSort()` ([src/test/java/com/example/app/llm/LlmTestFixtures.java:145](src/test/java/com/example/app/llm/LlmTestFixtures.java:145)) sorts by `(date ASC, time ASC nulls-first, norm(title) ASC)`.
- `LlmTestFixturesDiffTest` ([src/test/java/com/example/app/llm/LlmTestFixturesDiffTest.java](src/test/java/com/example/app/llm/LlmTestFixturesDiffTest.java)) has three title-specific tests: `titleDifferingOnlyByCaseAndDiacriticsIsTolerated` (line 36), `titleDifferingOnlyByWhitespaceIsTolerated` (line 44), `titleDifferingOnRealCharacterFailsOnTitleField` (line 52). The existing `notesDifferingIsNotGraded` (line 114) is the template to mirror.
- `KNOWN_DIVERGENCES` ([src/test/java/com/example/app/llm/LlmExtractionRecordedRegressionTest.java:49](src/test/java/com/example/app/llm/LlmExtractionRecordedRegressionTest.java:49)) has **zero** entries with `field: "title"`. All current divergences are `date` (16 entries, all year-resolution failures), `requirements` (5 entries), or `time` (1 entry). No accept-list migration is required by this change.
- `KnownDivergence(title, field, reason)` in both regression tests uses `title` purely as a human-readable identifier for failure messages and accept-list matching — it is independent of the grading assertion.
- `src/test/resources/llm/fixtures/README.md` documents `notes` as intentionally ungraded (line 66) and references the canonical sort order in the array-order note (lines 67–72).
- `context/changes/llm-fixture-set-expansion/triage.md` records the post-batch baseline as **1/9 ≈ 11.1%** under the strict "any divergence = not clean" rule.

## Desired End State

After this change ships:

- `LlmTestFixtures.diff()` returns `match=true` whenever `date`, `time`, and `requirements` agree under `norm()`, regardless of any `title` difference. No `DiffResult` is ever produced with `field="title"`.
- Title remains in `expected.json`, in `ProposedEvent`, in `canonicalSort` as a tertiary tie-breaker, and as the `KnownDivergence` label.
- `LlmTestFixturesDiffTest` has one explicit anchor (`titleDifferingIsNotGraded`) parallel to `notesDifferingIsNotGraded`, asserting the new contract.
- `./gradlew test` is green. The recorded regression harness asserts the same `KNOWN_DIVERGENCES` set as today (no entries to remove, no fixtures shift category).
- The fixture README explicitly lists `title` as informational alongside `notes`. The `llm-fixture-set-expansion` triage carries an addendum naming the rule change, the unchanged-by-construction baseline, and the short-circuit reasoning that motivated landing this change before `llm-prompt-year-resolution`.

### Key Discoveries:

- `KNOWN_DIVERGENCES` has no `field: "title"` rows today, so this change cannot break the recorded regression harness on existing fixtures ([src/test/java/com/example/app/llm/LlmExtractionRecordedRegressionTest.java:49](src/test/java/com/example/app/llm/LlmExtractionRecordedRegressionTest.java:49)).
- `diff()` short-circuits at the first mismatch. On the 16 fixtures-events with `date-mismatch` (years 2023–2025 instead of 2026), the title check **never runs** — those fixtures could have hidden title divergences that have never been measured.
- Fixture 09 (`niepodleglosci-www`) had a real title divergence (typographic `„..."` vs ASCII `"..."`); the curator already resolved it by editing `expected.json` to match the poster's typography (per `context/changes/llm-fixture-set-expansion/triage.md` line 21). That decision happened before this change and is unrelated.
- Sibling `llm-prompt-year-resolution` is preparing — `context/changes/llm-prompt-year-resolution/change.md` exists with `status: preparing`. After it resolves the date-mismatches, the title check would start running for those previously-short-circuited fixtures. Landing the title relaxation first prevents the year fix from being credited with — or blamed for — surfacing a class of title divergences that have always been there but masked.
- `norm()` is still exercised by `requirements` tolerance tests (lines 99–111), but those tests only cover the null/empty/blank branches — they do not assert NFC normalization, lowercasing, or internal whitespace collapse. The three deleted title tests covered exactly those behaviours, so removing them without replacement would leave `norm()`'s core branches untested. Phase 1 therefore adds a new `@Nested class Norm` with three direct-on-`norm()` tests to keep the contract anchored.

## What We're NOT Doing

- Not removing `title` from `expected.json`, `ProposedEvent`, `recorded-response.json`, the LLM prompt, or the JSON schema. Title is still produced, still labelled, still rendered.
- Not removing `title` from `KnownDivergence(title, field, reason)`. It remains the human-readable identifier for divergence rows and failure messages.
- Not dropping `title` from `canonicalSort` — the tertiary tie-breaker stays so two events sharing date+time still order deterministically.
- Not editing `KNOWN_DIVERGENCES` in `LlmExtractionRecordedRegressionTest` — there are no `title` rows to remove, and the `date` / `requirements` / `time` rows are untouched.
- Not touching `LlmExtractionLiveRegressionTest` divergence map or the live harness gating — it already shares `LlmTestFixtures.diff()` and inherits the new behaviour for free.
- Not enabling fixture `07-czerwiec-wazne-daty` (still on the event-count assertion blocked by the same year-fix change).
- Not regrading prior triage rows or rewriting past divergence narratives — the addendum sits next to the original tally.
- Not changing the diff predicate's short-circuit semantics — first mismatch still wins; we are only removing one step from the sequence.

## Implementation Approach

Two phases, gated by `./gradlew test`:

1. **Code + tests** — narrow surgical edit to `diff()`, parallel test rework. Recorded regression harness should pass without any KNOWN_DIVERGENCES edit (verified empirically by the test run).
2. **Documentation** — README, triage addendum, and a foundation lesson. Pure prose; no code, no tests.

## Critical Implementation Details

- **Short-circuit semantics drive the addendum framing.** Because `diff()` returns on first mismatch, removing the title check is not just "we accept different title formats" — it is also "we no longer expose any title divergence that was previously masked behind date / time mismatches." The triage addendum must call this out so the year-fix attribution is honest.
- **Test ordering matters in `LlmTestFixturesDiffTest`.** Place the new `titleDifferingIsNotGraded` test adjacent to `notesDifferingIsNotGraded` to make the parallel obvious to future readers. Don't scatter it.

## Phase 1: Relax title from the diff predicate

### Overview

Strip the title comparison from `LlmTestFixtures.diff()` and replace the three title-specific unit tests with a single test that mirrors the existing `notesDifferingIsNotGraded` pattern. Verify the full regression harness still passes without any accept-list edits.

### Changes Required:

#### 1. Diff predicate

**File**: `src/test/java/com/example/app/llm/LlmTestFixtures.java`

**Intent**: Remove the title comparison block from `diff()`. The method now checks `date` → `time` → `requirements` and returns `DiffResult.success()` if all match. Leave everything else in this file untouched — `norm()`, `canonicalSort()`, `DiffResult`, `loadExpected`, and all other helpers stay as-is.

**Contract**: `diff(ProposedEvent expected, ProposedEvent actual)` retains its `DiffResult` return type and short-circuit semantics. It can no longer produce a `DiffResult` with `field="title"` or `reason="title-norm-mismatch"`. The comparison sequence after the change is `date-mismatch` → `time-mismatch` → `requirements-norm-mismatch` → `success`.

#### 2. Diff unit tests

**File**: `src/test/java/com/example/app/llm/LlmTestFixturesDiffTest.java`

**Intent**: Delete the three title-specific tests (`titleDifferingOnlyByCaseAndDiacriticsIsTolerated`, `titleDifferingOnlyByWhitespaceIsTolerated`, `titleDifferingOnRealCharacterFailsOnTitleField` at lines 36–64). Add one new test `titleDifferingIsNotGraded` in the same `@Nested class Diff` block, placed immediately above `notesDifferingIsNotGraded` so the two ungraded-field tests live next to each other. The new test uses a totally different title in `actual` vs `expected` (e.g. `"Wycieczka do ZOO"` vs `"Festyn rodzinny"`) with all other graded fields identical, asserts `match=true`.

**Contract**: `LlmTestFixturesDiffTest.Diff` ends up with two fewer tests net (ten → eight `Diff` tests). `exactMatchAcrossAllGradedFieldsReturnsMatch`, `dateOffByOneDayFailsOnDateField`, `timeNullOnBothSidesMatches`, `timeNullExpectedVsValueActualFailsOnTimeField`, `requirementsNullVsEmptyStringMatches`, `requirementsNullVsBlankStringMatches`, and `notesDifferingIsNotGraded` remain unchanged. `canonicalSort` `@Nested` block is untouched — `titleIsFinalTieBreakerUnderNorm` still passes because the sort key is unchanged.

#### 3. Direct `norm()` unit tests

**File**: `src/test/java/com/example/app/llm/LlmTestFixturesDiffTest.java`

**Intent**: Add a third `@Nested class Norm` block (sibling to `Diff` and `CanonicalSort`) with three tests that call `LlmTestFixtures.norm()` directly. The block exists to keep the three behaviours the deleted title tests proved under direct assertion — once title is no longer graded, no other test in the suite touches case folding, NFC normalization, or internal-whitespace collapsing for non-null strings, and `requirements` tolerance tests only cover the null/empty/blank branches.

**Contract**: New `@Nested class Norm` placed below `CanonicalSort` (so file order reads top-to-bottom as "core diff predicate → ordering → underlying normalizer"). Three tests:
- `lowercasesAsciiAndCollapsesCase`: `norm("Wycieczka")` equals `norm("WYCIECZKA")` equals `"wycieczka"`.
- `normalisesNfcAndIsCaseFoldingDiacritics`: `norm("Pasowanie")` equals `norm("pasowanie")` equals `"pasowanie"` (asserts the deleted `titleDifferingOnlyByCaseAndDiacritics…` semantics; pick inputs that exercise NFC composition).
- `collapsesInternalWhitespaceAndStripsEdges`: `norm("  Wycieczka  do   ZOO  ")` equals `"wycieczka do zoo"` (asserts the deleted `titleDifferingOnlyByWhitespace…` semantics plus the `strip()` step).

No new dependencies, no helper changes; the tests call `norm()` directly via `LlmTestFixtures.norm(...)`.

### Success Criteria:

#### Automated Verification:

- `./gradlew test --tests com.example.app.llm.LlmTestFixturesDiffTest` passes (8 tests in `Diff`, 5 tests in `CanonicalSort`, 3 tests in `Norm`).
- `./gradlew test --tests com.example.app.llm.LlmExtractionRecordedRegressionTest` passes — the recorded regression harness asserts the same `KNOWN_DIVERGENCES` set as before this change. No accept-list edits are needed; if the test fails because a fixture now reports an extra divergence or a missing one, that is a real signal to investigate (most likely a `title` divergence that was masked by short-circuit and is now bypassed cleanly — verify it is *not* visible because of the change).
- `./gradlew build` passes (full suite + assemble).

#### Manual Verification:

- Re-read `LlmTestFixtures.diff()` after the edit and confirm the four-stage comparison sequence reads as: `date` → `time` → `requirements` → success. No `title` reference remains in the body.
- Re-read `LlmTestFixturesDiffTest.Diff` and confirm `titleDifferingIsNotGraded` sits adjacent to `notesDifferingIsNotGraded` and uses materially different titles (not just normalization differences) to make the intent unambiguous.
- Re-read `LlmTestFixturesDiffTest.Norm` and confirm three tests cover (a) lowercasing, (b) NFC + diacritics, (c) internal-whitespace collapse + edge strip, each calling `LlmTestFixtures.norm()` directly rather than going through the diff predicate.

---

## Phase 2: Document the new grading contract

### Overview

Update the fixture authoring docs and the prior-change triage so future contributors — and the next change in line, `llm-prompt-year-resolution` — understand the new grading rule and the baseline it leaves behind.

### Changes Required:

#### 1. Fixture README — schema and sort-order docs

**File**: `src/test/resources/llm/fixtures/README.md`

**Intent**: Mirror the existing "notes is intentionally omitted — the harness does not grade it" note for title, and explain that title is still used by `canonicalSort` for deterministic ordering even though it is not graded. The intuition to convey: *what we grade* is the subset of fields that move the accuracy metric; *what we sort by* is whatever yields a stable comparison.

**Contract**: The schema section (lines 51–72) carries a bullet next to the existing `notes` bullet stating that `title` is informational — kept in `expected.json` and rendered in failure messages, but not asserted by the diff. The array-order note retains its reference to `(date ASC, time ASC null-first, title ASC under norm())` with one added sentence clarifying that the use of `title` in the sort key is intentional (deterministic ordering) and distinct from grading. Mention briefly that `canonicalSort` is the only place `title` still has any harness-side semantics.

#### 2. Sibling triage addendum

**File**: `context/changes/llm-fixture-set-expansion/triage.md`

**Intent**: Append a new section titled `## Addendum (2026-06-13): Baseline under relaxed title diff` after the existing "Follow-ups" section. The addendum must (a) note the rule change shipped by `llm-diff-title-tier`, (b) record the new baseline (numerically identical: 1/9 ≈ 11.1%) and explain why it is unchanged — no `field: title` rows in `KNOWN_DIVERGENCES`, and on the 16 fixtures-events where `date-mismatch` fires first, the title check was *never running* because `diff()` short-circuits, so any title divergences on those fixtures were already invisible to the metric, (c) explicitly note that landing this change before `llm-prompt-year-resolution` is what keeps that condition true — once the year fix flips those 16 entries to clean, the title check would have started running on them, potentially exposing previously-masked title divergences and dirtying the year-fix attribution. The addendum closes with a one-line pointer to this change folder.

**Contract**: A single new `## Addendum` heading at the bottom of `triage.md`, dated `2026-06-13`. The original tally section and the original "Follow-ups" stay untouched as a snapshot. The addendum is 6–12 lines, prose only, no tables.

#### 3. Lessons append

**File**: `context/foundation/lessons.md`

**Intent**: Append a new bullet rule capturing the broader principle this change establishes: *don't grade fields whose mismatch the user wouldn't recognise as a regression, and remember that a short-circuiting diff can mask divergences on later fields whenever earlier fields fail — so changing the field order, or removing a field from the assertion, can change which categories of divergence are even visible.* Frame it as a rule for future regression-harness work — applies to any predicate that compares structured records field by field.

**Contract**: Follow the existing lessons format — heading `## <one-line rule>`, then `- **Context**:`, `- **Problem**:`, `- **Rule**:`, `- **Applies to**:`. The context is "designing or changing per-field comparison predicates in regression test harnesses." The problem references this change specifically (with the year-resolution short-circuit framing). The rule has two clauses: (1) grade only what a user would call wrong, (2) when removing a field from a short-circuiting diff, audit what later-stage divergences become visible on fixtures where the removed field used to short-circuit. Applies-to: `/10x-plan`, `/10x-implement`, `/10x-impl-review`.

### Success Criteria:

#### Automated Verification:

- `./gradlew test` still passes (no code touched in this phase, but run it to catch accidental cross-file edits).

#### Manual Verification:

- `src/test/resources/llm/fixtures/README.md`: re-read the schema section and confirm `title` is documented as informational on the same footing as `notes`, and the canonical-sort note distinguishes grading from ordering.
- `context/changes/llm-fixture-set-expansion/triage.md`: re-read the addendum and confirm it (a) names the rule change, (b) explains the unchanged baseline via both the empty-title-rows reason AND the short-circuit reason, (c) explains why landing before `llm-prompt-year-resolution` matters for attribution.
- `context/foundation/lessons.md`: re-read the new lesson and confirm it generalises beyond title — any future contributor designing a structured-record diff should be able to apply it.

---

## Testing Strategy

### Unit Tests:

- One new `titleDifferingIsNotGraded` test in `LlmTestFixturesDiffTest.Diff` asserts the new contract directly.
- The retained `exactMatch`, `date`, `time`, `requirements`, and `notes` tests preserve the existing per-field coverage.
- A new `LlmTestFixturesDiffTest.Norm` `@Nested` block carries three direct-on-`norm()` tests (case-folding, NFC + diacritics, whitespace collapse + edge strip) to keep the normalizer's contract under explicit assertion after the title tests are removed.
- `canonicalSort` tests are untouched — the sort key did not change.

### Integration Tests:

- `LlmExtractionRecordedRegressionTest` is the harness-level acceptance gate. After Phase 1, it must still pass without any edits to `KNOWN_DIVERGENCES` or `DISABLED_FIXTURES`. A failure here would be the signal that a previously-masked title divergence has become visible — which would itself be informative and inform whether the change needs an accept-list adjustment after all.
- `LlmExtractionLiveRegressionTest` is opt-in (`OGARNIACZ_LIVE_SMOKE=true`) and not run in this change.

### Manual Testing Steps:

1. Run `./gradlew clean test` end-to-end. Confirm a green build.
2. Inspect `LlmTestFixtures.diff()` body — confirm `title` does not appear.
3. Inspect `LlmTestFixturesDiffTest.Diff` — confirm `titleDifferingIsNotGraded` is present and sits adjacent to `notesDifferingIsNotGraded`.
4. Open the README, triage addendum, and lesson — confirm the cross-references between them (README ↔ triage ↔ lesson) make a coherent narrative.

## Performance Considerations

`diff()` runs once per event per fixture in tests; removing a comparison is a microscopic speedup. No performance considerations beyond that.

## Migration Notes

- `KNOWN_DIVERGENCES` in `LlmExtractionRecordedRegressionTest`: zero current entries reference `title`, so no migration is required. Verified empirically before planning ([src/test/java/com/example/app/llm/LlmExtractionRecordedRegressionTest.java:49](src/test/java/com/example/app/llm/LlmExtractionRecordedRegressionTest.java:49)).
- `recorded-response.json` and `expected.json` files: no changes — title stays in both.
- After `llm-prompt-year-resolution` lands and the curator re-records, the title-relaxation contract continues to hold. The follow-up change is responsible for pruning `date-mismatch` entries from `KNOWN_DIVERGENCES`, not this one.

## References

- Change identity: [context/changes/llm-diff-title-tier/change.md](context/changes/llm-diff-title-tier/change.md)
- Diff predicate: [src/test/java/com/example/app/llm/LlmTestFixtures.java:117](src/test/java/com/example/app/llm/LlmTestFixtures.java:117)
- Diff tests: [src/test/java/com/example/app/llm/LlmTestFixturesDiffTest.java](src/test/java/com/example/app/llm/LlmTestFixturesDiffTest.java)
- Recorded regression harness: [src/test/java/com/example/app/llm/LlmExtractionRecordedRegressionTest.java:49](src/test/java/com/example/app/llm/LlmExtractionRecordedRegressionTest.java:49)
- Fixture authoring docs: [src/test/resources/llm/fixtures/README.md](src/test/resources/llm/fixtures/README.md)
- Sibling change (preparing): [context/changes/llm-prompt-year-resolution/change.md](context/changes/llm-prompt-year-resolution/change.md)
- Prior triage / baseline source: [context/changes/llm-fixture-set-expansion/triage.md](context/changes/llm-fixture-set-expansion/triage.md)
- Foundation lessons: [context/foundation/lessons.md](context/foundation/lessons.md)

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Relax title from the diff predicate

#### Automated

- [x] 1.1 `./gradlew test --tests com.example.app.llm.LlmTestFixturesDiffTest` passes (8 tests in `Diff`, 5 tests in `CanonicalSort`, 3 tests in `Norm`) — 3131077
- [x] 1.2 `./gradlew test --tests com.example.app.llm.LlmExtractionRecordedRegressionTest` passes — same `KNOWN_DIVERGENCES` set, no accept-list edits — 3131077
- [x] 1.3 `./gradlew build` passes (full suite + assemble) — 3131077

#### Manual

- [x] 1.4 `LlmTestFixtures.diff()` body reviewed — no `title` reference remains; sequence is `date` → `time` → `requirements` → success — 3131077
- [x] 1.5 `LlmTestFixturesDiffTest.Diff` reviewed — `titleDifferingIsNotGraded` sits adjacent to `notesDifferingIsNotGraded` and uses materially different titles — 3131077
- [x] 1.6 `LlmTestFixturesDiffTest.Norm` reviewed — three tests cover case-folding, NFC + diacritics, and internal-whitespace/edge-strip, all calling `LlmTestFixtures.norm()` directly — 3131077

### Phase 2: Document the new grading contract

#### Automated

- [x] 2.1 `./gradlew test` still passes after doc-only edits — ec959f6

#### Manual

- [x] 2.2 `src/test/resources/llm/fixtures/README.md` — `title` documented as informational on same footing as `notes`; canonical-sort note distinguishes grading from ordering — ec959f6
- [x] 2.3 `context/changes/llm-fixture-set-expansion/triage.md` addendum — names rule change, explains unchanged baseline via both empty-title-rows AND short-circuit reasoning, explains why landing before `llm-prompt-year-resolution` matters for attribution — ec959f6
- [x] 2.4 `context/foundation/lessons.md` — new lesson generalises beyond title; applies to any short-circuiting per-field diff predicate — ec959f6
