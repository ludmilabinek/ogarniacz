# LLM Extraction Regression Harness — Plan Brief

> Full plan: `context/changes/llm-extraction-regression-harness/plan.md`
> Research: `context/changes/llm-extraction-regression-harness/research.md`

## What & Why

Build the **LLM extraction regression harness** that closes risks
#1 (confidently-wrong extraction) and #2 (silent prompt/model-swap
regression) from `context/foundation/test-plan.md` §2. Two
`@SpringBootTest` classes — a live, operator-gated variant and a
CI-default recorded-mock variant — share one fixture set and one
per-field tolerant diff predicate. After this change, a prompt
tweak or one-string model swap surfaces a per-fixture diff *before*
merge instead of drifting silently into the parent's calendar.

## Starting Point

`LlmVisionClient` + `OpenRouterLlmVisionClient` are shipped and
exercised by two existing tests:
`LlmVisionClientTest` (`@MockitoBean ChatModel`, 6 parser/exception
cases at `src/test/java/com/example/app/llm/LlmVisionClientTest.java:29-153`)
and `LlmVisionSmokeTest` (live env-gated, single sample at
`src/test/java/com/example/app/llm/LlmVisionSmokeTest.java:23-61`).
Neither grades semantic correctness against a human label.
`test-plan.md` §3 Phase 1 is **not started**; §6.4 cookbook is **TBD**;
the only fixture on disk is `src/test/resources/llm/sample-announcement.png`.

## Desired End State

Two new test classes + one shared helper + one seed fixture
(`01-sample`) + a curator-facing `fixtures/README.md` with PII +
sourcing policy + cookbook `§6.4` filled in. A future contributor
can add a fixture by following the cookbook only — no need to read
`plan.md` or `research.md`. The curator (Ludmiła) can expand the
fixture set to the 8–10 target incrementally after the harness ships,
using the live variant's automated recording mode
(`OGARNIACZ_RECORD_FIXTURES=true`) to capture raw responses
atomically and never-overwrite.

## Key Decisions Made

| Decision                                    | Choice                                                                                       | Why (1 sentence)                                                                                                                                                       | Source   |
| ------------------------------------------- | -------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------- |
| Mock seam                                   | `@MockitoBean ChatModel`                                                                     | Extend the proven `LlmVisionClientTest` pattern; Spring AI 2.0.0-M6 publishes no test utility                                                                          | User     |
| Diff strictness                             | Per-field tolerant — date/time exact, title/requirements NFC-normalized, notes not graded    | Matches PRD ≥ 80 % rule (date/title/requirements); NFC + lower + whitespace-collapse absorbs benign drift without a Levenshtein mirror-test trap                       | User     |
| Fixture curation scope                      | Only seed fixture `01-sample` here; curator expands later                                    | Full 8–10 set is curator work; the seed makes Phase 2/3 TDD a real diff instead of a meta-test of empty-directory handling                                             | User     |
| Live variant in CI                          | NOT wired — operator-gated by `OGARNIACZ_LIVE_SMOKE` + `OPENROUTER_API_KEY`                  | Same gate as `LlmVisionSmokeTest`; CI gets deterministic signal from the recorded-mock variant; live costs and OpenRouter rate-limit flake belong outside CI           | User     |
| Greedy regex at `OpenRouterLlmVisionClient.java:96` | Documented as a **Findings** entry in `change.md` only — not fixed in this change                  | Out of scope; separate change once a chatty-preamble fixture reproduces it; user direction explicit                                                                    | User     |
| PII handling                                | Curator-guaranteed PII-free fixtures; policy written to `fixtures/README.md`                 | No need for anonymization tooling or a two-tier fixture model at MVP scale                                                                                             | User     |
| Sidecar metadata file                       | `recorded-meta.json` with `model` + `recordedAt` (ISO timestamp) — no commit SHA              | ProcessBuilder for `git log` at test runtime defeats ergonomics; SHA is recoverable via `git log -1 -- recorded-response.json` when needed                             | User     |
| No-exception assertion                      | Explicit `assertDoesNotThrow(...)` on every fixture run                                       | Louder than implicit `null` checks; zero cost; surfaces parser-level breakage directly                                                                                  | User     |
| Shared helper                               | Lift `chatResponseOf` (and friends) into `LlmTestFixtures`                                    | Two callers (existing + new harness) is enough to share; avoids copy-paste                                                                                              | User     |
| Empty-fixtures behaviour                    | JUnit 5 `@DisabledIf("fixturesAreEmpty")` — class skips visibly with a documented reason     | Safety net for hypothetical future emptiness; not load-bearing in this change because `01-sample` keeps it enabled                                                       | Plan     |
| Phase split                                 | 4 phases: helper+scaffold+seed → recorded-mock → live → cookbook                              | Phases 2 and 3 map cleanly to `/10x-tdd` (first red is behavioural, not meta); Phase 1 and 4 map to `/10x-implement`                                                     | User     |

## Scope

**In scope:**

- `src/test/java/com/example/app/llm/LlmTestFixtures.java` (new) — shared helper
- `src/test/java/com/example/app/llm/LlmTestFixturesDiffTest.java` (new) — pure-JUnit diff predicate unit test
- `src/test/java/com/example/app/llm/LlmExtractionRecordedRegressionTest.java` (new) — CI-default class
- `src/test/java/com/example/app/llm/LlmExtractionLiveRegressionTest.java` (new) — operator-gated class with recording mode
- `src/test/resources/llm/fixtures/` (new directory) + `01-sample/` complete seed + `README.md`
- `context/foundation/test-plan.md` updates: §3 Phase 1 row, §4 stack row, §6.4 cookbook
- `change.md` Findings section (greedy-regex)
- One-line change in `LlmVisionClientTest` to use the lifted `chatResponseOf`

**Out of scope:**

- Curating fixtures 2–10 (curator-driven follow-up)
- Anonymization / PII-redaction tooling
- Wiring live variant into GitHub Actions / any scheduled job
- Fixing the greedy `[ .* ]` regex
- WireMock, LangSmith, Promptfoo, or any hosted eval platform
- Editing `LlmVisionSmokeTest` (untouched; `image.png` is a duplicate copy)
- `LlmExtractionException` envelope tests (already covered)
- Edit-distance / Levenshtein tolerance on title

## Architecture / Approach

```
src/test/java/com/example/app/llm/
  LlmTestFixtures.java         <-- helper: chatResponseOf, listFixtures,
                                   loadImage/Expected/RecordedRaw/Meta,
                                   norm, diff, DiffResult, FixtureMeta
  LlmTestFixturesDiffTest      <-- pure-JUnit unit test of the diff predicate
  LlmExtractionRecordedRegressionTest  (CI default)
                                <-- @MockitoBean ChatModel + chatResponseOf(recorded);
                                    @ParameterizedTest @MethodSource("fixtures");
                                    @DisabledIf("fixturesAreEmpty")
  LlmExtractionLiveRegressionTest      (operator-gated)
                                <-- @EnabledIfEnvironmentVariable(OGARNIACZ_LIVE_SMOKE);
                                    real OpenRouter call under BUDGET_MS = 55_000L;
                                    branch: grading (file exists) → diff;
                                            recording (file missing + OGARNIACZ_RECORD_FIXTURES) → atomic write;
                                            else → fail with documented message

src/test/resources/llm/
  sample-announcement.png      <-- existing, unchanged (smoke test)
  fixtures/README.md           <-- PII + sourcing policy + JSON schemas + oracle rule
  fixtures/01-sample/
    image.png                  <-- binary copy of sample-announcement.png
    expected.json              <-- hand-filled by curator from reading the image
    recorded-response.json     <-- captured manually via smoke-test stdout in Phase 1
    recorded-meta.json         <-- hand-written: model + ISO timestamp
```

Both harness classes iterate the same `fixtures/<id>/` set via
`@ParameterizedTest @MethodSource`. The recorded-mock variant stubs
the captured raw response into `ChatModel.call(...)`; the live
variant calls real OpenRouter and either grades or atomically records.
The diff predicate is identical across both — that's the
cost-×-signal collapse `test-plan.md` §1 calls for.

## Phases at a Glance

| Phase                                          | What it delivers                                                                                                                                              | Key risk                                                                                                                                              |
| ---------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1. Helper + scaffold + seed fixture            | `LlmTestFixtures` helper, `LlmTestFixturesDiffTest`, `fixtures/README.md` with PII + sourcing policy, complete `01-sample/` seed, lifted `chatResponseOf`     | Manual capture step for `01-sample/recorded-response.json` requires curator to copy stdout correctly; verified by the diff-predicate unit test       |
| 2. Recorded-mock harness class                 | `LlmExtractionRecordedRegressionTest` (CI default), explicit `assertDoesNotThrow`, structured failure message with fixture id + field + values + reason + model | Failure-message readability — load-bearing because all future fixture failures land here first; manually verified by a deliberate-break-and-revert    |
| 3. Live variant + recording mode               | `LlmExtractionLiveRegressionTest` env-gated; recording-mode branch with atomic `Files.move` + never-overwrite; per-fixture `BUDGET_MS = 55_000L`                | Atomic-write semantics + never-overwrite is the load-bearing safety property; verified manually with a throwaway fixture and a triple round-trip      |
| 4. Cookbook + test-plan sync + regex finding   | `test-plan.md` §6.4 filled in; §3 Phase 1 row → `in progress` + change folder; §4 row updated; `change.md` `## Findings` entry for greedy regex                | Cookbook completeness — cold-read by a fresh contributor must succeed without `plan.md` or `research.md`                                              |

**Prerequisites:**
- Curator (Ludmiła) has an `OPENROUTER_API_KEY` and has previously run
  `LlmVisionSmokeTest` successfully — both required for the Phase 1
  manual `recorded-response.json` capture.
- The curator has time to hand-fill `expected.json` for `01-sample`
  while looking at the image (~ 5 minutes).
- No CI changes required.

**Estimated effort:** ~ 3–4 working sessions across the four phases;
Phase 1 is the longest because of manual capture and JSON schema +
README authoring. Phases 2 and 3 are ~ 1 session each via `/10x-tdd`.
Phase 4 is ≤ 30 minutes.

## Open Risks & Assumptions

- The greedy `[ .* ]` regex at `OpenRouterLlmVisionClient.java:96`
  may surface as `MALFORMED_RESPONSE` on a future fixture whose
  recorded response contains chatty prose with `[` before the JSON.
  Mitigation: Findings entry; future change.
- Spring AI 2.0.0-M6 → next milestone bump may change
  `ChatResponse` / `Generation` shape so the recorded payload
  reaches the production parser through a different surface than
  live traffic. Mitigation: at every Spring-AI bump, run the live
  variant in grading mode against `01-sample` to detect drift.
- The curator's PII guarantee is procedural, not automated; one
  miss bypasses the policy. Mitigation: the policy is written and
  committed in `fixtures/README.md`; PR review of any
  `fixtures/<id>/` addition is the second eye.

## Success Criteria (Summary)

- A prompt tweak or one-string model swap produces a per-fixture
  diff (live variant grading mode) before the change reaches merge —
  not a silent calendar drift after deploy.
- A change to `OpenRouterLlmVisionClient`'s parser or the
  `LlmTestFixtures.diff(...)` predicate is caught by the
  recorded-mock variant on every CI run, without any operator
  intervention or OpenRouter cost.
- A future contributor can add a fixture by following `test-plan.md`
  §6.4 alone, with the live variant in `OGARNIACZ_RECORD_FIXTURES`
  mode handling the recording write automatically.
