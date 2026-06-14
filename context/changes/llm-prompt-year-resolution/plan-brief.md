# LLM Prompt: Year Resolution + Multi-Group + Language — Plan Brief

> Full plan: `context/changes/llm-prompt-year-resolution/plan.md`
> Research: `context/changes/llm-prompt-year-resolution/research.md`

## What & Why

Rewrite the LLM extraction prompt so the model resolves dates without an explicit year correctly (the single bug responsible for **15 of 22 documented divergences — 68%** in the recorded harness), stops emitting a redundant umbrella entry on multi-group days, and stops translating Polish source titles into English. The fix moves the prompt from a single `.user(...)` message to a Spring AI `system + user` split with a templated `{today}` (sourced from a new `Clock` bean) and `{language}` parameter. This is S-05's first refinement of the F-01 stub prompt and is expected to lift first-extraction accuracy from ~11% toward the 80% PRD target.

## Starting Point

`OpenRouterLlmVisionClient.java:26-33` carries a deliberate F-01 stub prompt: no current-date anchor, no year-resolution rule, no language directive, no multi-group rule. The call at line 59-62 routes both rules and image through a single `.user(...)` message. No `Clock` bean exists; the only production `LocalDate.now()` site is `AppController.java:42`. `LlmExtractionRecordedRegressionTest.KNOWN_DIVERGENCES` carries 22 documented divergences (15 of them date-mismatch) and `DISABLED_FIXTURES` contains `07-czerwiec-wazne-daty` (umbrella-event behaviour blows the event-count assertion). The sibling `LlmExtractionLiveRegressionTest.KNOWN_DIVERGENCES` lags at `01-sample` only. The prerequisite change `llm-diff-title-tier` already relaxed title from the diff predicate — the lesson-mandated ordering for flipping `date-mismatch` to clean is satisfied.

## Desired End State

The LLM client issues a Spring AI prompt with a `.system(...)` carrying today's date (from an injected `Clock`), a closest-to-today year-resolution rule, a "one entry per group/slot, no umbrella" rule, and a `language`-parametrized passthrough directive — and a separate `.user(...)` carrying the image with a short instructional hint. Recorded fixtures 02-08 + 10 are re-recorded against the new prompt. `DISABLED_FIXTURES` no longer hides `07-czerwiec-wazne-daty`. Both `KNOWN_DIVERGENCES` maps (recorded + live) carry the same row-for-row set of divergences that actually survived the prompt change. `./gradlew test` is green; the live regression test passes manually with creds.

## Key Decisions Made

| Decision                                | Choice                                                                 | Why (1 sentence)                                                                                                                                                  | Source |
| --------------------------------------- | ---------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------ |
| Year-resolution rule wording            | Closest-to-today (±6 months, tie → future)                             | Shorter and more robust on summer programmes / multi-year reminders than academic-year-aware; produces the same answer on every fixture seen.                     | Plan   |
| Language directive shape                | `.param("language", "Polish")` (parametrized template)                 | Matches Spring AI canonical shape; secondary persona (non-Polish announcements) can branch on locale later without re-editing the prompt template.                | Plan   |
| Embedded-time extraction (fixture 10)   | Out of scope — separate follow-up                                      | Adding a "scan body text for times" rule risks regressing fixtures 02/07's per-group time slots; this change stays focused on the 68% failure class.              | Plan   |
| Prompt-construction test shape          | Pure unit test, `ArgumentCaptor<Prompt>` on mocked `ChatModel.call(...)` with fixed `Clock` | Hermetic, deterministic, asserts on six named anchors (five content + one placeholder-substitution canary); gives a TDD seed for Phase 1 without `@SpringBootTest` overhead. | Plan   |
| `Clock` bean scope                      | Inject into both `OpenRouterLlmVisionClient` AND `AppController`       | Project gets one source of "now" — cheap (one line + ctor change in `AppController`), avoids future-Ludmiła rediscovering two flavours of "now".                  | Plan   |
| Commit discipline                       | Two commits, one per phase                                              | Phase 1 stays green (the recorded harness mock replays old responses regardless of prompt shape); each phase is independently reviewable, revertable, and bisectable. | Plan   |

## Scope

**In scope:**
- New `@Bean Clock clock()` + `@Bean ChatClient chatClient(ChatClient.Builder)` in `AppApplication`
- `OpenRouterLlmVisionClient` constructor refactor (`ChatClient` direct instead of `Builder`; add `Clock`)
- `SYSTEM_TEMPLATE` with `{today}` + `{language}` placeholders + three rules (year, multi-group, language)
- `.system(...)` + `.user(...)` split call shape
- `AppController.java:42` migration from `LocalDate.now()` to `LocalDate.now(clock)`
- New pure-unit test `OpenRouterLlmVisionClientPromptTest` with six named anchors (five content + one placeholder-substitution canary)
- Re-record fixtures 02-08 + 10 via `LlmExtractionLiveRegressionTest` recording mode
- Remove `07-czerwiec-wazne-daty` from `DISABLED_FIXTURES`
- Update `KNOWN_DIVERGENCES` in **both** recorded + live regression tests (discover-then-update, in lockstep)

**Out of scope:**
- Embedded-time extraction rule (fixture 10's `time-mismatch` stays)
- Structured-output binding (`OpenAiChatOptions.responseFormat` / `BeanOutputConverter`) — still S-05 follow-up
- Migration of test-site `LocalDate.now()` calls (6+ sites in `AppApplicationTests`, `EventRepositoryTest`, `EventControllerTest`)
- Locale plumbing (`LocaleResolver`, runtime locale binding to `.param("language", ...)`)
- Model or temperature change
- Polish-kindergarten-specific rule wording (we explicitly kept the rule provider-agnostic)

## Architecture / Approach

```
AppApplication (@SpringBootApplication)
  ├─ @Bean Clock clock()                       (NEW — systemDefaultZone)
  └─ @Bean ChatClient chatClient(Builder)      (NEW — lifts .build() out of ctor)

OpenRouterLlmVisionClient (REFACTORED)
  ctor(ChatClient, Clock, ObjectMapper, model)
  extract(image, mimeType):
    today = LocalDate.now(clock).toString()
    chatClient.prompt()
      .system(sp -> sp.text(SYSTEM_TEMPLATE)
                      .param("today", today)
                      .param("language", "Polish"))   ← rules + interpolation
      .user(u -> u.text(USER_HINT)
                  .media(mimeType, resource))         ← image stays here
      .call().content()

AppController (MIGRATED)
  findUpcomingByUser(user, LocalDate.now(clock))      (was: LocalDate.now())

OpenRouterLlmVisionClientPromptTest (NEW — pure unit, no @SpringBootTest)
  fixed Clock(2026-06-12) + mocked ChatModel + ArgumentCaptor<Prompt>
  asserts 6 anchors: "Today is 2026-06-12", "Polish", "closest to today",
                     "one entry per group", USER_HINT + 1 media item,
                     no literal "{today}"/"{language}" left (canary for eager-render)
```

Then Phase 2 is a recording dance: `git rm` stale `recorded-*.json` files for 8 fixtures → `OGARNIACZ_LIVE_SMOKE=true OGARNIACZ_RECORD_FIXTURES=true ./gradlew test --tests LlmExtractionLiveRegressionTest` → `chmod 644` → run recorded test → read failure output → write actual surviving divergences into both `KNOWN_DIVERGENCES` maps. One commit covers both phases.

## Phases at a Glance

| Phase                                                                   | What it delivers                                                                                       | Key risk                                                                                                                                       |
| ----------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| 1. Prompt template + `Clock`/`ChatClient` beans + prompt-construction test | New prompt shape compiles, ships, and is locked by a unit test asserting on six named anchors          | Phase 1 stays green (the recorded harness mock replays old responses regardless of the new prompt) — the divergence-flip signal won't appear until Phase 2 |
| 2. Re-record fixtures + sync `KNOWN_DIVERGENCES`                        | Recorded harness green; both maps (recorded + live) carry the post-fix divergence set in lockstep      | Discover-then-update produces surprises (e.g., Polish directive interacting with `requirements-norm-mismatch`); commit message must call out  |

**Prerequisites:**
- `llm-diff-title-tier` change must have landed (✓ done — commits `3131077` → `ac90819`).
- `OPENROUTER_API_KEY` available for the Phase 2 recording run.
- macOS-ready for the `chmod 644` papercut.

**Estimated effort:** ~1-2 sessions. Phase 1 is straightforward TDD (test → refactor ctor → rewrite prompt → green). Phase 2 is ~10 minutes of operational work (delete, record, chmod, observe, update maps) plus thoughtful review of surviving divergences.

## Open Risks & Assumptions

- **Discover-then-update could surface a regression.** The Polish-language directive's interaction with the existing `requirements-norm-mismatch` rows on fixtures 02 and 08 is empirically unknown. If re-recording reveals new categories of divergence (e.g., the model adding politeness markers back because it's been told "do not translate"), this gets called out in the commit and triaged as a follow-up — not silenced.
- **Closest-to-today's tie-breaking rule is untested on real edge cases.** No fixture in the current set has a date equidistant between two years. If the model interprets "tie-break to future" oddly, a follow-up fixture and rule refinement may be needed — but no fixture currently exercises this branch.
- **The Spring AI `.param(...)` interpolation requires the template literal to use `{today}` not `${today}`** (Mustache-style, not Spring-EL). The unit test's anchor check catches mistakes here; calling it out for the implementer.
- **`ChatClient.builder(ChatModel)` is the test-side construction path** — if Spring AI changes that surface in a future minor version, the unit test breaks but the production path keeps working. Accept this brittleness in exchange for the hermetic unit test.

## Success Criteria (Summary)

- The 15 date-mismatch divergences in `LlmExtractionRecordedRegressionTest.KNOWN_DIVERGENCES` are gone after re-record.
- Fixture 07 (`07-czerwiec-wazne-daty`) is out of `DISABLED_FIXTURES` and its recorded payload carries per-group time slots without an umbrella entry.
- Fixture 05's title is in Polish (`"Pamiątkowe zdjęcia grupowe do dyplomów"` shape), not English.
- Both `KNOWN_DIVERGENCES` maps (recorded + live) are byte-identical in their entries.
- `./gradlew test` is green; manual live run with creds is green.
- The change ships as two commits, one per phase; each commit lands fully green and is independently reviewable, revertable, and bisectable.
