<!-- PLAN-REVIEW-REPORT -->
# Plan Review: LLM Prompt: Year Resolution + Multi-Group + Language

- **Plan**: `context/changes/llm-prompt-year-resolution/plan.md`
- **Mode**: Deep
- **Date**: 2026-06-13
- **Verdict**: REVISE
- **Findings**: 1 critical, 0 warnings, 1 observation

## Verdicts

| Dimension              | Verdict   |
| ---------------------- | --------- |
| End-State Alignment    | FAIL      |
| Lean Execution         | PASS      |
| Architectural Fitness  | PASS      |
| Blind Spots            | WARNING   |
| Plan Completeness      | PASS      |

## Grounding

- 7/7 file paths exist (`OpenRouterLlmVisionClient.java`, `AppApplication.java`, `AppController.java`, recorded test, live test, `LlmVisionClientTest`, `LlmTestFixtures`).
- All referenced symbols resolved (`KNOWN_DIVERGENCES`, `DISABLED_FIXTURES`, `SYSTEM_PROMPT` line range, `LocalDate.now()` only at `AppController.java:42`).
- Brief↔plan consistent except for the framing issue called out in F1.
- Spring AI verification (via context7 against `/websites/spring_io_spring-ai_reference`): `.param(...)` interpolation is **eager** (Prompt passed to `ChatModel.call(...)` contains rendered text); `ChatClient.builder(ChatModel)` and `ChatClient.create(ChatModel)` **both exist** as static factories with no Spring context required; `Prompt.getInstructions()` returns `List<Message>`; rendered text method is `Content.getText()` (not `getContent()`); `UserMessage.getMedia()` returns `Collection<Media>` (not `List<Media>`) — implementer-resolvable from docs since the plan stays at intent level.
- Blast radius: no other callers of `OpenRouterLlmVisionClient`'s constructor; the `ChatClient.Builder` → `ChatClient` refactor is safe.
- Progress↔Phase mechanical check: 8 Phase 1 + 10 Phase 2 success-criteria bullets all mirrored in `## Progress`.
- Lessons priors satisfied: "sweep sibling setup blocks" (both `KNOWN_DIVERGENCES` maps move together); "audit short-circuited fields" prerequisite already done by `llm-diff-title-tier`.

## Findings

### F1 — Phase 1 alone does NOT make the recorded harness red

- **Severity**: ❌ CRITICAL
- **Impact**: 🔬 HIGH — affects narrative, exit gates, and commit discipline plan-wide
- **Dimension**: End-State Alignment
- **Location**: Implementation Approach paragraph; Phase 1 Automated Verification §"./gradlew build" bullet; Phase 1 Manual Verification 1.7; `plan-brief.md` Phases at a Glance row 1; `plan-brief.md` Key Decisions Made row "Commit discipline".
- **Detail**:
  The recorded test mocks `chatModel.call(any(Prompt.class))` at `LlmExtractionRecordedRegressionTest.java:120` and returns a stubbed response from `recorded-response.json` regardless of the `Prompt` argument. Phase 1's changes only affect prompt *building* in production code — the mock ignores the new Prompt shape and replays the same OLD recorded response from disk. The diff result is therefore unchanged from before Phase 1, and the test continues to pass against the existing `KNOWN_DIVERGENCES` rows.

  The "missing divergence" signal the plan calls "load-bearing" only surfaces AFTER Phase 2 re-records (fresh responses with correct dates produce clean per-field diffs, which then miss the documented `date-mismatch` divergences).

  Consequences as currently written:
  - Phase 1 success criteria `./gradlew build succeeds (excluding the recorded harness's accepted redness)` is self-contradictory — there is no redness in Phase 1 to exclude.
  - Manual Verification 1.7 instructs the implementer to confirm a "missing divergence" failure shape that won't appear in Phase 1 output.
  - The one-commit-for-both-phases discipline is rationalized as "main never sits in the in-between red state" — but main is fine after Phase 1 alone. The actual red state is INSIDE Phase 2, between re-recording and the `KNOWN_DIVERGENCES` update.

- **Fix A ⭐ Recommended**: Correct the plan; recommend two commits (one per phase)
  - Strength: Truthful narrative; each commit is independently reviewable and revertable; clean bisect; Phase 1 is a self-contained refactor + new unit test that gains the Clock bean and prompt rewrite without touching test data. Each phase has a clean green-to-green transition.
  - Tradeoff: Slightly larger git footprint (2 commits vs 1). Loses the "feature ships atomically" framing.
  - Confidence: HIGH — traced through `LlmExtractionRecordedRegressionTest.java:120` and verified the mock ignores Prompt argument.
  - Blind spot: Verify `EventControllerTest` and `AppApplicationTests` also pass after Phase 1 alone (likely fine; only Clock injection changes — system clock semantics preserved).
- **Fix B**: Keep one-commit discipline; rewrite the rationale
  - Strength: Less plan rewriting; preserves the "single coherent change" framing for review/bisect.
  - Tradeoff: The "main never red between phases" rationale was doing real work. The replacement ("atomic ship + clean bisect") is weaker and doesn't justify the additional Phase-1-red gymnastics the plan currently requires.
  - Confidence: MEDIUM — workable but loses rigor.
  - Blind spot: Implementer may still try to interpret "Phase 1 red" as a real signal to watch for and be confused.
- **Decision**: FIXED via Fix A — rewrote Overview, Implementation Approach, Phase 1 Auto 1.6, Phase 1 Manual (replaced false "intentional redness" item with bootRun-only check), Phase 2 Overview, Phase 2 Manual 2.10, Progress 1.6/1.7, plan-brief.md Commit discipline row, Phases at a Glance Phase 1 risk, and Success Criteria summary bullet.

### F2 — Defense-in-depth: assert no placeholder remains in rendered system message

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — one-line addition to the test contract
- **Dimension**: Blind Spots
- **Location**: Phase 1 Change #4 (prompt-construction unit test) — the test contract listing the five anchor assertions.
- **Detail**:
  Spring AI verification confirmed eager interpolation: the captured `Prompt` contains rendered text, not raw `{placeholder}`. However, the docs don't explicitly state the rendering happens before advisor chain dispatch — that's inferred from `StTemplateRenderer` being a synchronous string transform and advisor docs operating on already-shaped requests. If Spring AI ever moves to lazy rendering in a future minor version, the current anchor assertions ("Today is 2026-06-12", "Polish") would still match incidentally if the implementer accidentally hardcoded the date — but the `{placeholder}` → `param` interpolation path would silently break.
- **Fix**: Add a 6th assertion to the prompt-construction test contract: "system message text does NOT contain the literal substring `{today}` or `{language}` — placeholders must be fully substituted". This is a cheap canary that fails loudly if interpolation timing ever changes.
- **Decision**: FIXED — added 6th anchor to Phase 1 Change #4 contract; updated all "five anchors" / "5 anchors" references in plan.md (Desired End State, Change #4 Intent, Auto Verification 1.2, Testing Strategy, Progress 1.2) and plan-brief.md (scope, key decisions, architecture diagram, phases at a glance).
