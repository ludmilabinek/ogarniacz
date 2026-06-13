<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Relax Fixture Diff — Title Becomes Informational

- **Plan**: [context/changes/llm-diff-title-tier/plan.md](../plan.md)
- **Scope**: Full plan (Phase 1 + Phase 2)
- **Date**: 2026-06-13
- **Verdict**: APPROVED with notes
- **Findings**: 0 critical · 1 warning · 2 observations
- **Commits in scope**: `3131077` (p1 — code + tests), `ec959f6` (p2 — docs), `252f36e` (epilogue)

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

All 6 planned items implemented as written; all 4 explicit "What we are NOT doing" guardrails respected. Automated success criteria (`./gradlew test --tests …LlmTestFixturesDiffTest`, `…LlmExtractionRecordedRegressionTest`, `./gradlew build`, full `./gradlew test`) all green at commit time. Manual gates confirmed by the human at the phase-end ritual for both phases.

## Findings

### F1 — Off-by-one count "15" propagated into addendum and lesson

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**:
  - `context/changes/llm-fixture-set-expansion/triage.md:39` (pre-existing prose)
  - `context/changes/llm-fixture-set-expansion/triage.md:90` (addendum, this change)
  - `context/changes/llm-fixture-set-expansion/triage.md:92` (addendum, this change)
  - `context/foundation/lessons.md:67` (lesson, this change — "15" appears twice on this line)
- **Detail**: `KNOWN_DIVERGENCES` in `LlmExtractionRecordedRegressionTest.java` contains **16** `date-mismatch` rows, verified by `grep -c '"date-mismatch"'` and by per-fixture sum (5 on `03-marzec-bez-godzin` + 5 on `04-marzec-wazne-daty` + 1 on `05-zdjecia-dyplomowe` + 5 on `06-luty-wazne-daty` = 16). The original triage prose at `triage.md:39` said "15 of 22 documented divergences (68%)" — a pre-existing off-by-one. The plan inherited "15" verbatim into its Critical Implementation Details section, and the implementation faithfully copied it into both the new addendum and the new lesson. The corrected ratio is 16/22 ≈ 72.7%, not 68%. The addendum and lesson now contradict the per-fixture table in the same triage file (which sums to 16 unambiguously).
- **Fix A ⭐ Recommended**: Fix all four occurrences (including the pre-existing line 39 + the 68% → ~73% recalculation)
  - Strength: Restores internal consistency across `triage.md` and `lessons.md`; the per-fixture table is the source of truth and it already sums to 16. Leaving line 39 wrong while fixing the addendum/lesson would make the file contradict itself more visibly than today.
  - Tradeoff: Mildly violates the plan's "Original tally section stays untouched as a snapshot" rule — but the original tally is numerically wrong; preserving an error isn't a snapshot, it's a propagating bug.
  - Confidence: HIGH — grep on `KNOWN_DIVERGENCES` + the table's own per-row counts both agree on 16.
  - Blind spot: None significant.
- **Fix B**: Fix only the three lines I added in this change (triage.md:90, triage.md:92, lessons.md:67)
  - Strength: Strictly respects the plan's "leave original tally untouched" rule. Scope stays narrow.
  - Tradeoff: `triage.md` is left internally inconsistent — the per-fixture table and the addendum both say 16, but line 39 still says 15. Future readers see two numbers and won't know which to trust.
  - Confidence: HIGH — mechanical edit.
  - Blind spot: Doesn't address the root cause; a future read of the original tally will still mislead.
- **Decision**: FIXED via Fix A (4 occurrences corrected: triage.md:39, :90, :92 and lessons.md:67 ×2; 68% recalc → ~73%)

### F2 — Redundant third assertion in lowercasesAsciiAndCollapsesCase

- **Severity**: 📝 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: `src/test/java/com/example/app/llm/LlmTestFixturesDiffTest.java:167`
- **Detail**: The third assertion `norm("Wycieczka").isEqualTo(norm("WYCIECZKA"))` is logically implied by the two preceding assertions that both pin those inputs to `"wycieczka"`. Harmless redundancy. If preserved, consider replacing inputs with a mixed-case form (`"WyCiEcZkA"`) that exercises a regression path the other two don't.
- **Fix**: Drop the third assertion, or swap it to use a mixed-case input the other two don't already cover.
- **Decision**: FIXED — dropped the third assertion at LlmTestFixturesDiffTest.java:167.

### F3 — Asymmetric phrasing of `notes` vs `title` bullets in README

- **Severity**: 📝 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: `src/test/resources/llm/fixtures/README.md:66-72`
- **Detail**: The two bullets describe semantically different situations (`notes` is omitted from the schema entirely; `title` is in the schema but ungraded), so the asymmetric phrasing — "intentionally omitted" vs "informational" — is defensible. A reader scanning the section may briefly wonder why two adjacent bullets use different framing for what initially look like parallel facts.
- **Fix**: Optional. If consistency wins, mirror the structure to read "intentionally omitted from schema" vs "included but ungraded".
- **Decision**: SKIPPED — asymmetric phrasing is defensible (the two cases are semantically different).

## Notes

- Plan-drift agent confirmed 6/6 MATCH on planned items and 4/4 GUARDRAIL-OK on the "What we are NOT doing" list (verified `canonicalSort` still uses title as tertiary key; `KNOWN_DIVERGENCES` and `KnownDivergence` signature both untouched; `LlmExtractionLiveRegressionTest` untouched; no production code, fixture data, or recorded responses touched).
- Safety/quality agent confirmed `Objects` import still in use, `DiffResult` still referenced by remaining tests, and NFD/NFC literals in the new Norm test are correctly encoded (`ą` precomposed vs `ą` decomposed both normalize via `Form.NFC` to the same NFC form).
- One observation worth noting in passing: the `triage.md` denominator 22 is correct (16 date + 2 + 1 + 1 + 2 = 22), so only the date-mismatch count and the dependent percentage need fixing.
