# LLM extraction regression fixtures

This directory holds the inputs and labels that drive
`LlmExtractionRecordedRegressionTest` (CI-default, mock-backed) and
`LlmExtractionLiveRegressionTest` (operator-gated, real OpenRouter).
Both classes share `LlmTestFixtures` for loading, normalization, and
the per-field tolerant diff.

## Fixture sourcing policy

- The **curator** (Ludmiła) is the only fixture author for the MVP.
- Target shape: **8–10 fixtures**, including **≥ 1 ambiguous case**
  (e.g. partial date, smudged time, two events implied but only one
  named) and **≥ 1 FR-005-unreadable case** (image where the model
  should return `[]` rather than confabulate).
- Re-evaluate the harness pattern at **~30 fixtures** per
  `context/foundation/test-plan.md` §7 — at that volume a hosted eval
  platform (LangSmith, Promptfoo, …) may earn its keep; below it,
  the plain `@SpringBootTest` + on-disk fixtures pattern is the brief.

## PII policy

Real announcements **MUST NOT** contain:

- child / teacher / parent names,
- kindergarten name, address, phone number,
- handwritten signatures or any identifying mark.

PII screening is the curator's responsibility, performed **by hand
before commit**. There is no automated redaction step and no two-tier
fixture model. A fixture that fails the screen does not enter the
repository — anonymize or replace it before adding the directory.

## Directory layout

Each fixture lives under `fixtures/<id>/` and ships exactly four files:

```
fixtures/
├── README.md                  (this file)
└── <id>/
    ├── image.png              (PNG announcement; the input)
    ├── expected.json          (human label; the oracle)
    ├── recorded-response.json (raw model output, captured live)
    └── recorded-meta.json     (sidecar: model + recordedAt)
```

`<id>` is a two-digit ordinal plus a kebab-case descriptor (e.g.
`01-sample`, `02-pasowanie`, `03-zoo-trip`).

## `expected.json` schema

```json
{
  "events": [
    {
      "date": "YYYY-MM-DD",
      "time": "HH:MM" | null,
      "title": "string",
      "requirements": "string" | null
    }
  ]
}
```

- `notes` is **intentionally omitted** — the harness does not grade it.
- **Array order is informational, not load-bearing.** The diff
  predicate sorts both sides by `(date ASC, time ASC with null first,
  title ASC under norm())` before pairwise comparison, so a live
  response that returns the same events in a different order is not
  flagged as a regression. Write events in whatever order reads most
  naturally from the image.

## Fixture categories

Each fixture falls into one of two categories at the harness layer:

- **Clean match** — `expected.json` and the parsed
  `recorded-response.json` agree on every graded field under the
  per-field tolerant diff. This is the default; a fixture is in this
  category implicitly when it has no entry in
  `LlmExtractionRecordedRegressionTest#KNOWN_DIVERGENCES`. The
  recorded-mock harness asserts zero divergences.
- **Documented divergence** — `expected.json` is the curator's truth
  from the image, but the model's recorded response disagrees on one
  or more fields in a known, accepted way (e.g. condenses polite
  filler, routes free text into `notes` instead of `requirements`).
  The fixture id is listed in
  `LlmExtractionRecordedRegressionTest#KNOWN_DIVERGENCES` with the
  exact `(event-title, field, reason)` tuples the model is expected
  to produce. The harness asserts the divergence set matches the
  documented set — extra entries flag a parser / diff regression,
  missing entries flag recording drift.

The seed `01-sample` is a **documented-divergence** fixture (the
synthetic seed image is not representative of production-style
ogłoszenia and the curator chose to preserve the discrepancy as a
load-bearing signal rather than re-label or re-record). Clean-match
fixtures land as the curator collects real announcements.

**Decision rule when adding a fixture**: write `expected.json` from
the image. After Phase 3 captures `recorded-response.json`, re-run
the recorded-mock harness once: if it reports zero divergences, leave
the fixture out of `KNOWN_DIVERGENCES` (clean match). If it reports
divergences and the curator accepts each one as a real model trait
worth preserving, add the entry to `KNOWN_DIVERGENCES`. Never edit
`expected.json` toward the recording to silence a divergence — that
recreates the oracle-problem failure mode (§ "The oracle rule").

## `recorded-meta.json` schema

```json
{
  "model": "string (e.g. google/gemini-2.5-flash)",
  "recordedAt": "string (ISO-8601 instant, e.g. 2026-06-11T14:23:00Z)"
}
```

The sidecar holds **only** the model name and the capture timestamp.
Commit history for the recording is recoverable via
`git log -1 -- recorded-response.json`; the sidecar deliberately does
not duplicate that.

## `recorded-response.json` rule

- **Raw model output**, captured by the live variant's recording mode
  (`OGARNIACZ_LIVE_SMOKE=true OGARNIACZ_RECORD_FIXTURES=true ./gradlew test
  --tests com.example.app.llm.LlmExtractionLiveRegressionTest`) or, for
  the seed fixture only, by hand from a `LlmVisionSmokeTest --info` run.
- The captured payload **may include markdown fences or prose** around
  the JSON array — the production parser
  (`OpenRouterLlmVisionClient#extractJsonArray`) handles that.
- **Never edited by hand.** A regression is real only when the model's
  raw output differs; editing the recording erases that signal.
- **Re-recording requires `git rm` first.** The live variant's atomic
  writer never overwrites an existing `recorded-response.json`.

## The oracle rule

> **Recordings come from the model. Labels come from a human reading
> the image.**

Filling `expected.json` from the current `recorded-response.json` (or
from any model output, on any model) is the documented failure mode
#4 in `research.md` §8: it produces a mirror test that passes against
the model's current behaviour — including any wrong answers — and so
cannot detect a regression. **Do not do it.**

The seed `01-sample` was bootstrapped under this rule: `expected.json`
was hand-written from the curator reading `image.png`; the
`recorded-response.json` was captured separately via
`LlmVisionSmokeTest`; the two were never derived from each other.
