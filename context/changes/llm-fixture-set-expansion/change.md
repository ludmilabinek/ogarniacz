---
id: llm-fixture-set-expansion
status: in_progress
opened: 2026-06-12
owner: Ludmiła
---

# Fixture set expansion — seed batch (9 announcements)

Expand the LLM extraction regression fixture set from **1 fixture** (`01-sample`,
shipped with the harness) to **10 fixtures** by triaging and processing a curator
batch of 9 real, pre-anonymized kindergarten announcements.

## Why this change exists

The `llm-extraction-regression-harness` change (archived 2026-06-11) shipped the
**machinery** for fixture-based regression detection but only seeded **one**
fixture. PRD §Success Criteria primary claims ≥80% first-extraction correctness
on representative real kindergarten announcements — a target that is statistically
unmeasurable on a sample size of 1.

This change closes that measurement gap by expanding to 10 fixtures (the lower
bound of the 8–10 target in `src/test/resources/llm/fixtures/README.md`).

## Out of scope

- **Prompt or model tuning** — if the post-batch accuracy comes in below 80%, that
  is a separate change. This change is data curation, not engineering.
- **FR-005-unreadable fixture** — none of the 9 source announcements meet the
  "should return `[]`" bar. Tracked as a follow-up in `triage.md`; a future
  fixture (11th) will close this gap when an unreadable announcement is captured.
- **Eval-platform investment** — per `test-plan.md` §7, plain `@SpringBootTest` +
  on-disk fixtures stays the chosen pattern until ~30 fixtures.

## Artifacts

- `triage.md` — per-fixture triage table (input → fixture id + category + status)
  and progress tracker for fazy 2 (capture) and 3 (accuracy tally).
- Source-of-truth for the per-fixture procedure: `context/foundation/test-plan.md`
  §6.4 ("Adding an LLM extraction test"). This change does not duplicate it.
