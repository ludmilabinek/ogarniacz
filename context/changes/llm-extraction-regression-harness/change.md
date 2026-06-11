---
change_id: llm-extraction-regression-harness
title: LLM extraction regression harness (test-plan §3 Phase 1, risks #1 + #2)
status: implementing
created: 2026-06-10
updated: 2026-06-11
---

## Notes

Phase 1 of `context/foundation/test-plan.md` §3 — a fixture-based regression
harness that closes:

- **Risk #1**: vision LLM produces a credible-looking but wrong event and the
  per-event accept gate rubber-stamps it (PRD ≥ 80 % correctness on
  date / title / requirements).
- **Risk #2**: a prompt tweak or one-string model swap silently regresses
  extraction on a class of announcements that used to work.

Locked scope (per user, 2026-06-10):

- **Mock seam**: `@MockitoBean ChatModel` — extend the pattern already in
  `src/test/java/com/example/app/llm/LlmVisionClientTest.java`.
- **Fixtures**: assumed to exist (or be collected outside this work); the
  harness research focuses on mechanics only.
- **Diff strictness**: per-field tolerant — `date` / `time` exact-match,
  `title` / `requirements` tolerant (NFC + lower + whitespace-collapse),
  `notes` not graded.

`§7 negative-space (locked)`: no LangSmith, no Promptfoo, no hosted eval
platform. Plain `@SpringBootTest` with on-disk fixtures + JSON labels;
re-evaluate at ~30 fixtures.

Research artifact: `research.md` (this folder).
