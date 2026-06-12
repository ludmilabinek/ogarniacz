<!-- PLAN-REVIEW-REPORT -->
# Plan Review: LLM extraction regression harness — Phase 2 (post-pivot)

- **Plan**: `context/changes/llm-extraction-regression-harness/plan.md`
- **Mode**: Deep
- **Date**: 2026-06-11
- **Verdict**: REVISE (cross-phase ripples only; Phase 2 itself is sound)
- **Findings**: 0 critical, 2 warnings, 1 observation
- **Scope note**: this review runs *after* Phase 2 shipped (commit `1323e74`). The plan was revised mid-implementation when the seed fixture's `expected.json` vs `recorded-response.json` divergence forced a pivot from "diff must match" to "observed divergence set must equal `KNOWN_DIVERGENCES[fixtureId]`". The findings below cover ripples that pivot left in Phase 3, Phase 4, and the top-of-plan Desired End State — not Phase 2's own implementation. The pre-implementation review remains at `plan-review.md` in this directory with its triage decisions intact; this file is intentionally separate to preserve that history.
- **change.md note**: `status` stays `implementing` (Phase 3 and Phase 4 still pending).

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| End-State Alignment | PASS |
| Lean Execution | PASS |
| Architectural Fitness | PASS |
| Blind Spots | WARNING |
| Plan Completeness | WARNING |

## Grounding

5/5 paths ✓ (`LlmExtractionRecordedRegressionTest.java`, `LlmTestFixtures.java`, `fixtures/01-sample/`, `fixtures/README.md`, `test-plan.md`), 3/3 symbols ✓ (`KNOWN_DIVERGENCES`, `canonicalSort`, `containsExactlyInAnyOrderElementsOf`), brief↔plan consistent.

## Findings

### F1 — Phase 3 grading mode silently inherits the divergence-set assertion

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Blind Spots
- **Location**: Phase 3 — Changes Required, branch on `Files.exists(recordedInSource)` (plan.md ≈ lines 603-606)
- **Detail**:
  Phase 3's grading-mode branch reads "run the same diff loop as Phase 2; failure message identical shape". After the Phase 2 pivot, "diff loop" no longer means "first failing field aborts" — it means "build observed divergence set, compare to `KNOWN_DIVERGENCES`." Two downstream consequences are not written down:
  1. Phase 3 grading on `01-sample` passes only while the live model produces exactly the documented divergences. If a future model swap "fixes" the requirements field, observed = [] but documented has two entries → Phase 3 fails on a clean model improvement until the curator updates the constant. That is the curator's "feature not a bug" intent per the 2026-06-11 pivot direction, but the plan doesn't say so.
  2. Phase 3's recording-mode branch writes a fresh `recorded-response.json` but never touches `KNOWN_DIVERGENCES`. If the regenerated raw response has a different divergence shape, the next recorded-mock run breaks.
- **Fix**: Add one paragraph to Phase 3 Overview making the inheritance explicit — "grading-mode failure paths are the same `KNOWN_DIVERGENCES` set assertion as Phase 2; a clean model improvement on a documented-divergence fixture surfaces as a 'missing divergence' failure, signaling the curator to re-evaluate and update the constant" — and add a one-line note to Phase 3's recording-mode branch that re-recording may invalidate `KNOWN_DIVERGENCES` for that fixture (curator must re-run the recorded-mock variant after re-recording to confirm or revise the documented set).
  - Strength: Removes the only ambiguity a Phase 3 implementer would have to reconstruct from cross-phase reading; keeps Phase 3's behavioural contract self-contained.
  - Tradeoff: ~6 lines of plan prose; no code change.
  - Confidence: HIGH — the pivot's two downstream consequences are mechanical once the divergence-set assertion is shared.
  - Blind spot: Doesn't address how a curator should *atomically* re-record + re-update `KNOWN_DIVERGENCES` (defer to Phase 4 cookbook — see F2).
- **Decision**: FIXED — added a "Grading-mode assertion inherits the Phase 2 pivot" block to Phase 3 Overview spelling out the clean-improvement-as-missing-divergence and regression-as-extra-divergence consequences, plus a "Note on KNOWN_DIVERGENCES" paragraph on the recording-mode branch instructing the curator to re-run the recorded-mock variant after a fresh capture.

### F2 — Phase 4 cookbook contract doesn't cover KNOWN_DIVERGENCES

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 4 — Changes Required §1 (`test-plan.md` §6.4 cookbook contract)
- **Detail**: Phase 4's §6.4 contract lists "Adding a regression fixture", "Adding a parser invariant test", and "The greedy regex caveat". No mention of `KNOWN_DIVERGENCES`, the documented-divergence fixture category, or the decision rule now in `fixtures/README.md`. A contributor adding `02-foo` who sees the recorded-mock test fail on their fixture has no pointer from §6.4 to the constant — they'll discover it by reading test source.
- **Fix**: Expand Phase 4 §6.4 "Adding a regression fixture" sub-point to add a step: "After Phase 3 captures `recorded-response.json`, re-run the recorded-mock harness; if it reports divergences, follow `fixtures/README.md` §Fixture categories to decide whether to add a `KNOWN_DIVERGENCES` entry (documented divergence) or re-record / re-label (clean match)."
- **Decision**: FIXED — Phase 4 §6.4 "Adding a regression fixture" contract now ends with the post-capture acceptance step pointing at `fixtures/README.md` §Fixture categories and `LlmExtractionRecordedRegressionTest#KNOWN_DIVERGENCES`.

### F3 — Desired End State paragraph still describes the pre-pivot assertion

- **Severity**: ℹ️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Plan top — Desired End State (plan.md ≈ lines 91-97)
- **Detail**: The top-of-plan Desired End State for the recorded-mock variant reads "asserts the per-field tolerant diff against the human label". After the pivot, the assertion is "the observed per-field-diff set equals the documented set in `KNOWN_DIVERGENCES`". Cosmetic, but anyone reading top-down gets a stale mental model.
- **Fix**: Replace "asserts the per-field tolerant diff against the human label" with "asserts the observed per-field-diff divergence set against the documented `KNOWN_DIVERGENCES` entry (clean match if the fixture isn't listed)".
- **Decision**: FIXED — Desired End State now describes the divergence-set assertion and notes `01-sample` as a documented-divergence fixture.
