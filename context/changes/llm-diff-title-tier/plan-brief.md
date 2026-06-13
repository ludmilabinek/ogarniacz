# Relax Fixture Diff: Title Becomes Informational — Plan Brief

> Full plan: `context/changes/llm-diff-title-tier/plan.md`

## What & Why

Stop asserting on `title` in `LlmTestFixtures.diff()` so the recorded LLM regression harness measures only what the user values — date, optional time, requirements. Title stays in `expected.json` and in `KnownDivergence` for human-readable failure messages, but it no longer moves the accuracy metric. Lands before `llm-prompt-year-resolution` so the year-fix lift is measured against an honest baseline, and so the year fix cannot inadvertently unmask title divergences that were previously hidden behind `date-mismatch` short-circuits.

## Starting Point

`LlmTestFixtures.diff()` ([line 117](src/test/java/com/example/app/llm/LlmTestFixtures.java:117)) short-circuits through `date` → `time` → `title` → `requirements`. `KNOWN_DIVERGENCES` ([line 49](src/test/java/com/example/app/llm/LlmExtractionRecordedRegressionTest.java:49)) has zero `title` entries today — but 16 of 22 documented divergences are `date-mismatch` rows, on which the title check never runs because `diff()` returns on the first mismatch. Triage of the `llm-fixture-set-expansion` batch put the strict baseline at **1/9 ≈ 11.1%**.

## Desired End State

`diff()` returns `match=true` whenever date, time, and requirements agree, regardless of title format. The recorded regression harness asserts the same `KNOWN_DIVERGENCES` set as today with no accept-list edits. Fixture authoring docs, the sibling triage, and `lessons.md` all carry the new contract explicitly. The follow-up `llm-prompt-year-resolution` change inherits a baseline whose grading rule is fixed and whose accuracy lift will be attributable to the year fix alone.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| `diff()` title check | Remove entirely | Title format is not a regression a user would recognise; the strict `norm()`-equality assertion produced noise that obscured the year-resolution bug. | Plan |
| `canonicalSort` title key | Keep as tertiary tie-breaker | Two events sharing date+time still need a deterministic order so the pairwise assertion in the regression harness doesn't flake. | Plan |
| Diff unit tests | Replace 3 title tests with one `titleDifferingIsNotGraded` mirroring `notesDifferingIsNotGraded`, AND add a new `@Nested class Norm` with three direct-on-`norm()` tests | Single explicit anchor for the new contract; matches the existing "field intentionally not graded" pattern; the deleted title tests carried `norm()`'s case-folding / NFC / whitespace-collapse coverage and the surviving requirements tests only cover null/empty/blank, so direct `norm()` tests are the cheapest way to keep the normalizer under assertion. | Plan |
| `KnownDivergence(title, …)` | Keep title untouched | Title is purely a human-readable label in failure output; removing it would make the assertion-set messages worse and force a 25-row accept-list rewrite for zero gain. | Plan |
| `KNOWN_DIVERGENCES` accept-list | No edits required | Empirical: zero entries reference `field: "title"` today; verified before planning. | Plan |
| Baseline tally treatment | Append addendum to `llm-fixture-set-expansion/triage.md`, leave the original tally intact | Original triage is a snapshot of what we saw immediately after capture and should not be rewritten; addendum makes the rule change explicit and traceable for the next change. | Plan |
| Lessons addition | Append a generalised rule about graded-vs-sorted fields and short-circuiting diffs | The short-circuit-masking insight is broader than this one change; future per-field diff predicates should inherit the audit discipline. | Plan |

## Scope

**In scope:**
- Remove the title comparison from `LlmTestFixtures.diff()`.
- Replace three title-specific tests in `LlmTestFixturesDiffTest` with one `titleDifferingIsNotGraded` test.
- Update `src/test/resources/llm/fixtures/README.md` to document title as informational and clarify grading-vs-sorting.
- Append a baseline addendum to `context/changes/llm-fixture-set-expansion/triage.md`.
- Append a generalised lesson to `context/foundation/lessons.md`.

**Out of scope:**
- Any change to the LLM prompt, schema, or `OpenRouterLlmVisionClient`.
- Editing `KNOWN_DIVERGENCES` rows (none reference `title`; `date` / `requirements` / `time` rows are untouched).
- Touching `LlmExtractionLiveRegressionTest` or the live harness gating.
- Re-enabling fixture `07-czerwiec-wazne-daty` (still blocked by the event-count assertion).
- Removing title from `expected.json`, `ProposedEvent`, or `KnownDivergence`.
- Dropping title from `canonicalSort`.

## Architecture / Approach

The change is local to one helper class and its unit tests. Both regression harnesses (`Recorded` and `Live`) consume `LlmTestFixtures.diff()` directly, so updating the predicate propagates without touching the runners. `KNOWN_DIVERGENCES` is checked empirically to confirm no accept-list migration is required. Documentation in three places (README, triage addendum, lessons) ensures the new contract — and the short-circuit reasoning that motivated landing now — is discoverable from each of the natural entry points (fixture authoring, change history, future-planning priors).

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Relax title from the diff predicate | `diff()` skips title; one `titleDifferingIsNotGraded` test replaces three older title tests; recorded regression harness still green with no accept-list edits | A previously-masked title divergence becomes visible once short-circuit no longer runs, surfacing a new harness failure that would require investigation rather than rubber-stamping |
| 2. Document the new grading contract | README schema and sort-order note updated; triage addendum explains unchanged baseline and the short-circuit reasoning; lesson generalises the rule for future diffs | Low — pure prose, but the triage addendum's framing must be precise for the next change's attribution to be honest |

**Prerequisites:** None. The change touches test-only code paths. `llm-fixture-set-expansion` is already closed; `llm-prompt-year-resolution` is `status: preparing` and is the explicit downstream beneficiary.

**Estimated effort:** ~1 session across two phases. Phase 1 is one comparator edit + one test rewrite. Phase 2 is three prose edits.

## Open Risks & Assumptions

- **Assumption (verified):** No `field: "title"` row currently exists in `KNOWN_DIVERGENCES`. If a future fixture is added between plan and implementation that introduces one, the Phase 1 harness run would fail and the accept-list would need a corresponding edit. Empirically verified at plan time; re-verify before merge.
- **Risk:** The Phase 1 `LlmExtractionRecordedRegressionTest` run could surface a title divergence that was previously short-circuited behind a `date-mismatch` row — but since title is now skipped, that divergence would *not* appear in the divergence set, so the assertion would still pass. The real risk is the inverse: that some other previously-suppressed field-level divergence becomes visible. Phase 1's `1.2` success criterion treats any harness failure as a real signal to investigate, not to silence.

## Success Criteria (Summary)

- `LlmTestFixtures.diff()` no longer references `title`; the comparison sequence reads `date` → `time` → `requirements` → success.
- `./gradlew test` is green; the recorded regression harness asserts the same `KNOWN_DIVERGENCES` set as today with no accept-list edits.
- The fixture README, the `llm-fixture-set-expansion` triage, and `context/foundation/lessons.md` all carry the new contract, and the triage addendum names the short-circuit reasoning that justifies landing this change before `llm-prompt-year-resolution`.
