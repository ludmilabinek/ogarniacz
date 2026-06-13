<!-- PLAN-REVIEW-REPORT -->
# Plan Review: Relax Fixture Diff: Title Becomes Informational

- **Plan**: `context/changes/llm-diff-title-tier/plan.md`
- **Mode**: Deep
- **Date**: 2026-06-13
- **Verdict**: REVISE → SOUND after triage (all 3 findings fixed in plan)
- **Findings**: 1 critical · 1 warning · 1 observation

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| End-State Alignment | PASS |
| Lean Execution | PASS |
| Architectural Fitness | PASS |
| Blind Spots | WARNING |
| Plan Completeness | WARNING |

## Grounding

7/7 paths ✓ (diff predicate, diff tests, recorded harness, live harness, fixture README, sibling triage, year-resolution change, lessons.md), 4/4 symbols ✓ (`diff()` at `LlmTestFixtures.java:117`, `canonicalSort()` at `:145`, `KNOWN_DIVERGENCES` at `LlmExtractionRecordedRegressionTest.java:49` with zero `field: "title"` rows, same in `LlmExtractionLiveRegressionTest.java:67`), brief↔plan ✓ (consistent — both carry the same test-count error). `docs/reference/contract-surfaces.md` absent — contract-surface scan skipped per skill convention.

## Findings

### F1 — Test count math is wrong; SC 1.1 will fail mechanically

- **Severity**: ❌ CRITICAL
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 1, `plan.md:80` + Progress 1.1; `plan-brief.md:54`
- **Detail**: `LlmTestFixturesDiffTest.Diff` has **10** tests today (verified by reading the file: `exactMatch`, 3× title, `dateOffByOneDay`, 2× time, 2× requirements, `notes`). The plan removes 3 title tests and adds 1, which yields **8** (10 − 3 + 1). But the plan claims:
  - `plan.md:80` — "ends up with one fewer test net (six → five Diff tests)"
  - SC 1.1 / Progress 1.1 — "passes (5 tests in Diff, 5 tests in CanonicalSort)"
  - The same sentence on `plan.md:80` enumerates 7 retained + 1 new = 8 — so the enumeration is consistent with reality and inconsistent with the "five" summary in the same sentence.
  - CanonicalSort count of 5 is correct.

  A runner reporting "8 tests" against an SC that says "5" reads as a Phase-1 failure to the executor, who then has to decide whether to treat the plan as authoritative (delete two more tests to hit 5) or the file as authoritative (edit the plan). Either way Phase 1 stalls.
- **Fix**: Replace "six → five" on `plan.md:80` with "ten → eight". Update SC 1.1 + Progress 1.1 to "8 tests in Diff, 5 tests in CanonicalSort". Verify nothing in `plan-brief.md` carries the same wrong count. The enumeration of retained tests on `plan.md:80` is the source of truth: 7 retained + 1 new = 8.
- **Decision**: FIXED — counts corrected in plan.md (line 80, SC 1.1, Progress 1.1); plan-brief.md scanned, no leftover wrong counts.

### F2 — `norm()` case/diacritics/whitespace coverage drops to zero after Phase 1

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Blind Spots
- **Location**: `plan.md:33`, `plan-brief.md:23`
- **Detail**: The plan and brief both claim "removing title-specific norm tests does not lose `norm()` coverage" because requirements tests still exercise `norm()`. The two remaining requirements tests — `requirementsNullVsEmptyStringMatches` and `requirementsNullVsBlankStringMatches` — only cover the null/empty/blank branches of `norm()` (the early `if (s == null)` return plus `strip()`). They do **not** cover the three behaviours the deleted title tests proved:
  - NFC normalization (deleted: `titleDifferingOnlyByCaseAndDiacritics…`)
  - lowercasing (same)
  - internal whitespace collapse `\\s+` → " " (deleted: `titleDifferingOnlyByWhitespace…`)

  After Phase 1, a regression that broke `Normalizer.normalize(s, NFC)`, `toLowerCase`, or the whitespace collapse would not be caught by any direct test. `norm()` is small and stable, but the plan's claim is misleading — and it'll be especially awkward when the Phase-2 lesson generalises the rule: telling future readers "no coverage loss" is the wrong frame if the loss is real.
- **Fix**: Either (a) add a dedicated `@Nested class Norm` to `LlmTestFixturesDiffTest` with three small tests hitting `LlmTestFixtures.norm()` directly (case+diacritics, internal whitespace, leading/trailing whitespace) — cheapest, no field plumbing — or (b) parameterise the requirements tests to cover those branches via the requirements field. Update `plan.md:33` and `plan-brief.md:23` to stop claiming "no coverage loss" — either name the new tests or acknowledge the gap.
  - Strength: Keeps the contract of `norm()` directly tested as the lesson framing this change wants future contributors to do.
  - Tradeoff: Adds 3 short tests to Phase 1; one extra paragraph in the plan body. Adds one more thing to verify.
  - Confidence: HIGH — `norm()` is short, deterministic, and pure; direct tests are unambiguous.
  - Blind spot: None significant.
- **Decision**: FIXED — plan.md gains a new "#3 Direct `norm()` unit tests" section under Phase 1 (a `@Nested class Norm` with 3 tests: case-folding, NFC+diacritics, whitespace+strip); Key Discovery on norm() coverage rewritten to acknowledge the gap and name the fix; SC 1.1 + Progress 1.1 expanded to "8 Diff, 5 CanonicalSort, 3 Norm"; new Progress 1.6 + manual verification step added; plan-brief.md decision row corrected.

### F3 — SC 2.5 expects `status: planned`, but by Phase 2 the status has moved on

- **Severity**: ℹ️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 2, `plan.md:140` + Progress 2.5
- **Detail**: SC 2.5 reads: "Update `context/changes/llm-diff-title-tier/change.md` to `status: planned` and bump `updated: 2026-06-13` (done by `/10x-plan` itself before Phase 1; verify it is right)." By the time Phase 2 is being executed, the change has moved past `planned` — `/10x-implement` flips it to `in_progress`, and Phase 2 ends with it sitting at `implemented`. The current wording asks the verifier to confirm a state that should no longer exist.
- **Fix**: Rewrite SC 2.5 / Progress 2.5 to verify the expected end-of-Phase-2 status (e.g. "shows `status: implemented`, `updated: <today>` after both phases land"), or drop the line entirely — `/10x-implement`'s own progress hooks manage the status transitions.
- **Decision**: FIXED — line dropped from both Phase 2 manual verification list (plan.md) and Progress 2.5.
