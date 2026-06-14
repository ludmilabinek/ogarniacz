---
change_id: llm-fixture-set-expansion
title: Fixture set expansion — seed batch (9 announcements)
status: implemented
created: 2026-06-12
updated: 2026-06-13
archived_at: null
---

# Fixture set expansion — seed batch (9 announcements)

Expand the LLM extraction regression fixture set from **1 fixture** (`01-sample`,
shipped with the harness) to **10 fixtures** by triaging and processing a curator
batch of 9 real, pre-anonymized kindergarten announcements.

## Outcome

- 10 fixtures total: 1 seed + 9 new (02–10).
- 9 of 10 fixtures complete the full quartet (`image.png`, `expected.json`,
  `recorded-response.json`, `recorded-meta.json`).
- 1 fixture (`07-czerwiec-wazne-daty`) is in `DISABLED_FIXTURES` until a
  referenced prompt-fix change ([[task_199a06fd]]) addresses the model's
  redundant-umbrella-event behaviour that breaks the harness's event-count
  assertion.
- Recorded regression test goes **green** on this state via expanded
  `KNOWN_DIVERGENCES` accept-list (22 documented divergences across 8 fixtures).
- Live regression test goes green on `01-sample` re-grade; the new fixtures'
  recordings were captured atomically in one `OGARNIACZ_RECORD_FIXTURES=true`
  run, validating that the F1/F2 review fixes from the archived harness change
  hold in practice.

## What this change measured

First-extraction accuracy against the PRD §Success Criteria primary (≥80% on
representative real announcements): **1/9 ≈ 11.1%** (or 1/8 ≈ 12.5% over the
new batch alone). Below target, but the dominant cause (15 of 22 documented
divergences = 68%) is a single underlying bug: the model assigns an arbitrary
past year when the poster does not state a year explicitly.

`triage.md` carries the per-fixture detail, the projected post-prompt-fix
accuracy (~62.5%), and a punch-list of remaining gaps (requirements-field
semantics, embedded-time extraction, the still-pending FR-005-unreadable
fixture for the 11th capture).

## Out of scope (deferred to follow-up changes)

- **Prompt year-resolution rule** — spawned as [[task_199a06fd]] before any
  recording landed; the live capture confirmed the prediction. Out of scope
  for this change because data curation and prompt engineering are different
  blast radii — keeping them in separate changes lets each one be reviewed,
  rolled back, or re-recorded independently.
- **FR-005-unreadable fixture** — none of the 9 source announcements meet
  the "should return `[]`" bar (all are clearly readable). A future 11th
  capture from a real illegible announcement closes this policy gap; do not
  synthesise one.
- **Eval-platform investment** — per `test-plan.md` §7, plain
  `@SpringBootTest` + on-disk fixtures stays the chosen pattern until ~30
  fixtures.
- **`notes` field coverage in the diff** — discovered during this change but
  out of scope; soft follow-up captured in `triage.md`.

## Artifacts touched

- `src/test/resources/llm/fixtures/02..10-*/` — 9 new fixture folders, each
  with the full quartet.
- `src/test/resources/llm/fixtures/05-zdjecia-dyplomowe/expected.json` —
  revised from 2 events to 1 after curator agreed the model's
  collapsed-reading was defensible; the year and language divergences remain
  as documented divergences.
- `src/test/resources/llm/fixtures/06-luty-wazne-daty/image.png` —
  white-block PII patch landed on the parent-name line during triage.
- `src/test/resources/llm/fixtures/08-grzybobranie/expected.json` — `notes`
  corrected after model recording exposed the curator's misread of the
  meeting-place text ("wigwam przy Leśnictwie Łękno", not "wjazdem przy
  Leśniczówce Łęsko").
- `src/test/resources/llm/fixtures/09-niepodleglosci-www/expected.json` —
  title quote style corrected to the Polish „..." typography the poster uses
  (this is the only new fixture that ended up `KNOWN_DIVERGENCES`-clean).
- `src/test/java/com/example/app/llm/LlmExtractionRecordedRegressionTest.java` —
  `KNOWN_DIVERGENCES` expanded from 1 fixture to 8; new `DISABLED_FIXTURES`
  set introduced (with a comment policy: each entry must point at a concrete
  pending change, not a vague "we'll get to it").
- `triage.md` — per-fixture triage table, accuracy tally, follow-ups.

`context/foundation/test-plan.md` §6.4 stays the canonical per-fixture
procedure; this change does not duplicate it.
