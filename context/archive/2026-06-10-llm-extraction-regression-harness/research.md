---
date: 2026-06-10T11:21:09Z
researcher: Ludmiła Drzewiecka
git_commit: 861c47d24d8fe1a8a6bac332780d1c1fb9a4cc8e
branch: main
repository: ogarniacz
topic: "LLM extraction regression harness (test-plan §3 Phase 1)"
tags: [research, codebase, llm, testing, regression-harness, spring-ai, phase-1]
status: complete
last_updated: 2026-06-10
last_updated_by: Ludmiła Drzewiecka
---

# Research: LLM extraction regression harness (test-plan §3 Phase 1)

**Date**: 2026-06-10T11:21:09Z
**Researcher**: Ludmiła Drzewiecka
**Git Commit**: 861c47d24d8fe1a8a6bac332780d1c1fb9a4cc8e (local — not yet pushed)
**Branch**: main
**Repository**: ogarniacz

## Research Question

How should the **LLM extraction regression harness** (Phase 1 of
`context/foundation/test-plan.md` §3) be wired, given:

- mock seam locked to `@MockitoBean ChatModel` (extend the
  `LlmVisionClientTest` pattern);
- fixtures (real announcement images + human labels) assumed to exist;
- per-field tolerant diff (`date` / `time` exact, `title` / `requirements`
  tolerant, `notes` not graded);
- §7 negative-space exclusion of any eval platform (no LangSmith,
  no Promptfoo, no hosted service)?

The harness must close **risk #1** (confidently-wrong extraction)
and **risk #2** (silent prompt / model-swap regression) in
`test-plan.md` §2.

## Summary

The harness is one `@SpringBootTest` per variant (live + recorded-mock),
both iterating the same fixture set under
`src/test/resources/llm/fixtures/<id>/` and applying the same per-field
tolerant diff. Each fixture is a co-located triplet of
**`image.png` + `expected.json` (human label) + `recorded-response.json`
(captured raw model output)**.

The two variants split responsibility cleanly:

- **Live variant** is env-gated by `OGARNIACZ_LIVE_SMOKE=true` and
  `OPENROUTER_API_KEY` (the proven dual-gate from `LlmVisionSmokeTest`);
  it calls the real model, fails on real model regression, and — when
  `OGARNIACZ_RECORD_FIXTURES=true` and `recorded-response.json` is
  missing — writes the captured raw response to disk via atomic rename.
- **Recorded-mock variant** runs in CI by default; `@MockitoBean
  ChatModel` returns the stored raw response per fixture; failure means
  the **parser** path (regex, Jackson, or diff predicate) regressed,
  **never** the model.

Diff predicate: `date` / `time` exact (`LocalDate` / `LocalTime`
equality with null-match); `title` / `requirements` use
`Normalizer.normalize(_, NFC).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip()`
on both sides; `notes` is not compared (PRD ≥ 80 % rule names date /
title / requirements only).

No code change is required in `src/main/`. The harness is purely
additive under `src/test/java/com/example/app/llm/` and
`src/test/resources/llm/fixtures/`. Spring AI 2.0.0-M6 publishes no
dedicated test utility — manual `ChatModel.call(...)` stubbing **is**
the idiomatic path (context7 confirmed).

## Detailed Findings

### 1. Production extraction path (what the harness exercises)

The harness exercises the **whole** production extraction call chain
from `LlmVisionClient.extract(byte[], MimeType)` down to the parsed
`LlmExtractionResult` — the only thing stubbed is the autowired
`ChatModel` bean.

- **Interface**:
  `src/main/java/com/example/app/llm/LlmVisionClient.java:15` —
  `LlmExtractionResult extract(byte[] image, MimeType mimeType) throws LlmExtractionException`.
- **Production implementation**:
  `src/main/java/com/example/app/llm/OpenRouterLlmVisionClient.java:22-94`.
  Constructor wires `ChatClient.Builder`, `ObjectMapper`, and
  `${spring.ai.openai.chat.options.model}` (`:39-46`); `SYSTEM_PROMPT`
  (`:26-33`); request/response/parse loop (`:49-94`); JSON-array
  stripping regex (`:96` — `Pattern.compile("\\[.*\\]", Pattern.DOTALL)`).
- **Result types**:
  `src/main/java/com/example/app/llm/LlmExtractionResult.java:7-10` —
  `record LlmExtractionResult(List<ProposedEvent> proposedEvents, String rawResponse, long latencyMillis)`.
  The `rawResponse` field is load-bearing for the harness: the live
  variant uses it as the *recorded* raw payload (see §5).
- **`ProposedEvent` shape**:
  `src/main/java/com/example/app/llm/LlmExtractionResult.java:12-18` —
  `record ProposedEvent(LocalDate date, LocalTime time, String title, String requirements, String notes)`.
- **Exception envelope**:
  `src/main/java/com/example/app/llm/LlmExtractionException.java:5-9` —
  `Kind { TIMEOUT, PROVIDER_ERROR, MALFORMED_RESPONSE }`. The harness
  treats `MALFORMED_RESPONSE` as a parser regression in the
  recorded-mock variant and as a model regression in the live variant.
- **No downstream consumer yet**: `LlmVisionClient` is not wired into
  any production controller or service. The upload pipeline (`S-05`)
  that consumes it is not yet implemented; Phase 1 deliberately does
  not require it.
- **Model configuration**:
  `src/main/resources/application.properties:32` —
  `spring.ai.openai.chat.options.model=google/gemini-2.5-flash`;
  `:29` base URL; `:31` API key chained placeholder
  (`${OPENROUTER_API_KEY:${AI_PROVIDER_API_KEY:placeholder-not-a-real-key}}`);
  `:33` temperature `0.2`; `:36` `timeout=55s`; `:37` `max-retries=0`.

### 2. Existing test infrastructure to reuse

The harness is a direct extension of three patterns already on disk;
nothing new must be invented at the test-infra layer.

- **`@MockitoBean ChatModel` mock-bean override**:
  `src/test/java/com/example/app/llm/LlmVisionClientTest.java:29-37`
  (annotation stack + the `@MockitoBean ChatModel chatModel` declaration
  at `:36`).
- **`ChatResponse` construction helper**:
  `src/test/java/com/example/app/llm/LlmVisionClientTest.java:151-153`
  — `static ChatResponse chatResponseOf(String content) { return new ChatResponse(List.of(new Generation(new AssistantMessage(content)))); }`.
  Lift this into a shared `LlmTestFixtures` helper so both harness
  classes reuse it.
- **`getDefaultOptions()` `@BeforeEach` stub**:
  `src/test/java/com/example/app/llm/LlmVisionClientTest.java:41-44`
  — the ChatClient fluent builder NPEs before reaching `.call()`
  without it. Must be copied verbatim into each harness class.
- **Env-gated live-test pattern (`OGARNIACZ_LIVE_SMOKE` + `OPENROUTER_API_KEY`)**:
  `src/test/java/com/example/app/llm/LlmVisionSmokeTest.java:25`
  (annotation), `:28` (`BUDGET_MS = 55_000L`), `:36-38` (`ClassPathResource`
  fixture-loading pattern).
- **Test property convention**:
  `@TestPropertySource(properties = "REMEMBER_ME_KEY=test-key-not-for-production")`
  at `src/test/java/com/example/app/llm/LlmVisionClientTest.java:30` and
  `src/test/java/com/example/app/llm/LlmVisionSmokeTest.java:24` —
  carry it on both new harness classes for context-cache reuse with the
  rest of the suite.
- **Single existing fixture on the classpath**:
  `src/test/resources/llm/sample-announcement.png` (61 834 bytes,
  committed). The harness extends `src/test/resources/llm/` rather than
  inventing a new root directory.
- **Build dependency**:
  `build.gradle:22` (`spring-ai-bom:2.0.0-M6`) +
  `build.gradle:29` (`spring-ai-starter-model-openai`). No new
  dependency required.

### 3. The harness mechanic in one paragraph

A single fixture test runs end-to-end as follows: load
`image.png` for the fixture via `new ClassPathResource("llm/fixtures/<id>/image.png").getInputStream()` + `StreamUtils.copyToByteArray(...)`
(`LlmVisionSmokeTest.java:36-38` pattern); load
`recorded-response.json` for the fixture as a `String`; stub
`@MockitoBean ChatModel chatModel`
(`LlmVisionClientTest.java:36`) via
`when(chatModel.call(any(Prompt.class))).thenReturn(chatResponseOf(recorded))`
— reusing the `ChatResponse` → `Generation` → `AssistantMessage`
helper at `LlmVisionClientTest.java:151-153`; call
`llmVisionClient.extract(bytes, MimeTypeUtils.IMAGE_PNG)`
(`LlmVisionClient.java:15`); load `expected.json` and deserialize into
`List<ProposedEvent>` via the same Spring-autowired `ObjectMapper`
that production uses; pair against `result.proposedEvents()` and run
the per-field tolerant diff (§ 6). Spring Boot 4 / Spring AI types
involved: `org.springframework.ai.chat.model.{ChatModel,ChatResponse,Generation}`,
`org.springframework.ai.chat.messages.AssistantMessage`,
`org.springframework.ai.chat.prompt.Prompt`,
`org.springframework.test.context.bean.override.mockito.MockitoBean`,
`org.springframework.boot.test.context.SpringBootTest`.

### 4. File layout on disk

Recommended (**triplet co-located per fixture**):

```
src/test/resources/llm/
  sample-announcement.png                # legacy F-01 smoke fixture, kept
  fixtures/
    01-zoo-trip/
      image.png
      expected.json
      recorded-response.json
    02-pasowanie/
      image.png
      expected.json
      recorded-response.json
    …
```

Rationale: the unit of churn (the fixture) matches the unit of
directory, so adding / removing / regenerating a fixture is one
`git add` or `git rm` of a folder. The `live` vs `recorded-mock`
variant choice is obvious from filename: the same `image.png` is the
input in both variants; the variant only differs in whether
`recorded-response.json` is *read* (CI grading) or *written* (live
recording). Listing fixtures is `Files.list(.../fixtures)` — JUnit
`@ParameterizedTest` with a `MethodSource` enumerating sub-directories
is the natural driver, no per-fixture annotation.

`expected.json` shape (one fixture):

```json
{
  "events": [
    {
      "date": "2026-06-12",
      "time": "09:00",
      "title": "Wycieczka do ZOO",
      "requirements": "kanapka, picie"
    }
  ]
}
```

`notes` is intentionally omitted from `expected.json` because it is
not graded (see § 6). `time` is `null` for date-only events.

`recorded-response.json` is the raw model output exactly as produced
by `chatClient.prompt()...content()` — markdown fences, prose
surround, JSON-array, whatever the model emitted. This is what
`result.rawResponse()` returns at
`LlmExtractionResult.java:9`; the live variant captures it
verbatim (§ 5).

The alternative "parallel sibling dirs by file kind"
(`images/01-zoo-trip.png`, `expected/01-zoo-trip.json`,
`recorded/01-zoo-trip.json`) was rejected: it forces three diffs to
review on every fixture change and makes "is this fixture complete?"
non-obvious from a single `ls`.

### 5. The two test variants — exactly what differs

**Live variant** — proposed class
`src/test/java/com/example/app/llm/LlmExtractionLiveRegressionTest.java`.

- `@SpringBootTest` + `@AutoConfigureMockMvc` is **not** needed (no
  MVC); `@TestPropertySource(properties = "REMEMBER_ME_KEY=test-key-not-for-production")` carried over;
  `@EnabledIfEnvironmentVariable(named = "OGARNIACZ_LIVE_SMOKE", matches = "true")`
  on the class (identical gate to `LlmVisionSmokeTest:25`).
- For each fixture: read `image.png`; call the real
  `llmVisionClient.extract(...)`; branch on `recorded-response.json`
  existence (§ 6 below).
- Reuse `BUDGET_MS = 55_000L` (`LlmVisionSmokeTest.java:28`) as a
  per-fixture wall-clock ceiling.
- **(a) Fails when**: the live model regresses on a fixture
  (date wrong, fuzzy-mismatching title), the 55 s budget is breached,
  or `recorded-response.json` is missing and `OGARNIACZ_RECORD_FIXTURES`
  is not set.
- **(b) Failure message**:
  `fixture=02-pasowanie field=title expected="Pasowanie Pierwszaków" actual="Pasowanie pierwszakow" reason=fuzzy_threshold_exceeded` —
  fixture id + field + both values + which diff rule fired.
- **(c) Developer next step**: open the image + label by hand, decide
  if the model regressed (file an issue / revert the model swap)
  or the label is wrong (correct `expected.json` and re-record with
  `OGARNIACZ_RECORD_FIXTURES=true` after `git rm`-ing the stale
  recording).

**Recorded-mock variant** — proposed class
`src/test/java/com/example/app/llm/LlmExtractionRecordedRegressionTest.java`.

- `@SpringBootTest` + `@TestPropertySource(...REMEMBER_ME_KEY...)`,
  no env gate (CI default).
- `@MockitoBean ChatModel chatModel` + `@BeforeEach` that stubs
  `getDefaultOptions()` (verbatim from `LlmVisionClientTest.java:41-44`).
- For each fixture: read `image.png` (unused except to flow through
  the production path), read `recorded-response.json`, stub
  `chatModel.call(any(Prompt.class))` to return
  `chatResponseOf(recordedRaw)`, call
  `llmVisionClient.extract(...)`, run the per-field tolerant diff
  against `expected.json`.
- **(a) Fails when**: the parser path regresses (the regex at
  `OpenRouterLlmVisionClient.java:96`, the Jackson `TypeReference`
  at `:65`), the ObjectMapper config changes, or the diff predicate
  itself regresses. Cannot fail because of model drift — that is the
  live variant's job.
- **(b) Failure message**: same shape as live, plus a stable
  `recorded-at=<commit-sha>` line referencing the git commit at
  which `recorded-response.json` was last touched, so the dev sees
  the test fixed a model output captured at a specific point.
- **(c) Developer next step**: this is a *parser* signal. Inspect
  `OpenRouterLlmVisionClient.extractJsonArray` (`:98-104`), the
  ObjectMapper config, or the diff predicate. **Do not** "fix" by
  re-running the live variant — that would mask the parser regression
  behind a fresh recording.

### 6. The per-field tolerant diff — one-paragraph spec

```
diff(expected, actual):
  date:         expected.date.equals(actual.date)                            # LocalDate exact
  time:         (expected.time == null && actual.time == null)
                || (expected.time != null && expected.time.equals(actual.time))   # LocalTime exact + null-match
  title:        norm(expected.title).equals(norm(actual.title))              # tolerant
  requirements: nullOrBlank(expected.requirements) == nullOrBlank(actual.requirements)
                || norm(coalesce(expected.requirements)).equals(norm(coalesce(actual.requirements)))  # tolerant + null/blank tolerance
  notes:        NOT COMPARED                                                 # PRD ≥ 80 % rule covers date/title/requirements only
```

`norm(s) = java.text.Normalizer.normalize(s, Form.NFC).toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ").strip()`.

Rationale per choice:

- **NFC, not NFKD**: we want canonical-composed Polish diacritics
  (ą, ę, ś) to be byte-equal between sides without stripping
  them — stripping would conflate "Ślubowanie" and "Slubowanie",
  which is exactly the silent regression risk #1 names.
- **`Locale.ROOT`**: developer-locale dependent `toLowerCase` is a
  known JVM portability hazard (canonical example: Turkish `I` → `ı`
  instead of `i`), so we never depend on the default locale for case
  folding inside a test diff.
- **`coalesce(null) = ""` for `requirements` only**: null vs empty
  string is a known model output coin-flip and PRD FR-004
  (`prd.md:111-114`) calls `requirements` optional.
- **No edit-distance threshold on `title`**: NFC + lower + whitespace-
  collapse already absorbs the common drift. An edit-distance threshold
  is the most common path to a **mirror test** (passes against the bug).
  If real fixtures show stable trailing punctuation drift
  („Ślubowanie." vs „Ślubowanie"), add a single rule: strip trailing
  `.;,` after `norm`. Anything beyond that comes back through
  `/10x-research`.

Failure surface: emit fixture id + field + both raw values + which
rule fired. Never emit the normalized strings — those are an
implementation detail and would mislead a reader who copies them
back into `expected.json`.

### 7. Recording-mode trigger and round-trip

One mechanism, atomic, never overwrites:

```java
Path recorded = fixtureDir.resolve("recorded-response.json");
if (!Files.exists(recorded)) {
    if (!"true".equals(System.getenv("OGARNIACZ_RECORD_FIXTURES"))) {
        fail("fixture %s has no recording; rerun with OGARNIACZ_RECORD_FIXTURES=true".formatted(id));
    }
    Path tmp = Files.createTempFile(fixtureDir, "recording-", ".json.tmp");
    Files.writeString(tmp, result.rawResponse(), StandardCharsets.UTF_8);
    Files.move(tmp, recorded, StandardCopyOption.ATOMIC_MOVE);  // atomic rename
    // assertion in recording mode: rawResponse is non-blank and parses as a JSON array
} else {
    // grading mode: per-field tolerant diff against expected.json
}
```

Env var name: **`OGARNIACZ_RECORD_FIXTURES=true`**. Naming parallels
the existing `OGARNIACZ_LIVE_SMOKE`, so both flags live in the same
prefix and the smoke gate is still the outer guard (no recording can
happen without the live gate also being on).

Safety properties:

- A fixture that already has a recording is **never** overwritten by
  this path. Re-recording requires an explicit `git rm` first. That
  makes "recording" a deliberate human act, not a side-effect of
  running the suite — the core defense against accidental oracle
  drift (see § 8 failure mode 4).
- `Files.move(..., ATOMIC_MOVE)` ensures a SIGINT mid-write does not
  leave a half-written `recorded-response.json` that a CI run would
  later trust.
- The recording-mode assertion is intentionally weak ("non-blank +
  parses as JSON array") because a *live* response is by definition
  authoritative for itself — the per-field diff comes in once a human
  has reviewed and edited `expected.json` to match the image, not the
  model.

### 8. Non-obvious failure modes

1. **Greedy `[ .* ]` regex swallows prose around the JSON array.**
   `OpenRouterLlmVisionClient.java:96` —
   `Pattern.compile("\\[.*\\]", Pattern.DOTALL)`. If the model returns
   `[{"title":"Wycieczka [klasa A]", ...}]` the greedy `.*` correctly
   captures the whole array. But if the model emits **prose before
   the JSON** containing `[` (e.g. "Found events [list below]:
   [{...}]"), the regex grabs from the first `[` onward, producing
   `[list below]: [{...}]` which Jackson rejects → `MALFORMED_RESPONSE`.
   The recorded-mock variant will surface this on any fixture whose
   recording contains a chatty model preamble — i.e. a *recording*
   exposes the parser fragility while live calls have so far happened
   to dodge it. Treat as parser-improvement candidate, not as a
   "fix the fixture" instruction.

2. **Polish diacritic encoding round-trip.** `Pasowanie`,
   `Wycieczka do ZOO` are already in the LLM tests
   (`LlmVisionClientTest.java:58, 79`). Risks: (a) `expected.json`
   saved on Windows as CP-1250 instead of UTF-8 → `ą` becomes `ą`;
   (b) the file system on macOS normalizes filenames to NFD while
   the JSON content is NFC → string equality on `title` fails for no
   visible reason. Mitigation: always read both files via
   `Files.readString(path, StandardCharsets.UTF_8)` and NFC-normalize
   both sides of every string comparison (§ 6 already encodes this).

3. **Stale recording from Spring-AI shape drift.** Spring AI 2.0.0-M6
   is a milestone release (`build.gradle:22`). A minor bump could
   change `ChatResponse` / `Generation` so that
   `new Generation(new AssistantMessage(recorded))` no longer wraps
   the way `chatModel.call(...)` returns it in production. The mock
   would feed a payload through a constructor path real traffic never
   takes; the recorded-mock test would go green while live is broken.
   Mitigation: keep **at least one** fixture exercised in the live
   variant every Spring-AI version bump, and assert
   `recorded.equals(result.rawResponse())` when the live test runs
   in *grading* mode — that catches "shape drift between live and
   helper".

4. **The oracle-problem face here.** A future contributor regenerates
   `recorded-response.json` *and* `expected.json` from the same live
   run. The mock test passes because the label was derived from the
   model, not from the announcement; the suite becomes a mirror of
   whatever the current model says. Mitigations: (a) § 5 never
   overwrites an existing `recorded-response.json`; (b) `expected.json`
   filling is a separate, manual step — **no test ever writes
   `expected.json`**; (c) the PR template should require a screenshot
   of the announcement image alongside any `expected.json` change so
   the reviewer can read the label off the image, not the diff.
   Cookbook § 6.4 in `test-plan.md` must restate the rule:
   *recordings come from the model; labels come from a human reading
   the image*.

5. **`getDefaultOptions()` NPE on naive port.** A new harness class
   that copies `LlmVisionClientTest` skeleton without copying the
   `@BeforeEach` at `LlmVisionClientTest.java:41-44` gets an opaque
   `NullPointerException` deep inside the ChatClient builder, with no
   reference to `ChatModel` in the stack trace. Surface this in
   cookbook § 6.4 as a one-line "always stub `getDefaultOptions`
   before stubbing `call(...)` when using `@MockitoBean ChatModel`".

### 9. What this harness does **not** cover (hand-off)

- LLM timeout / FR-005 unreadable-image UX → **Phase 4** (upload
  pipeline + lifecycle boundary, MockMvc test).
- Notes-field correctness → not graded; PRD ≥ 80 % rule excludes it
  and parents edit it later (FR-006).
- iCal-feed serialization of extracted events (DTSTART, VALARM,
  VTIMEZONE) → **Phase 2** (iCal feed serialization + freshness).
- Cross-account isolation of extraction results → **Phase 3** (iCal
  feed access control).
- Prompt-injection defense inside the uploaded image → permanently
  out (`test-plan.md` § 7; the per-event accept gate is the safety
  floor).
- Fixture collection itself (how to source images, how many, how to
  label) → out of scope by user clarification 2026-06-10; assumed
  to be already in hand.

## Code References

- `src/main/java/com/example/app/llm/LlmVisionClient.java:15` —
  the interface contract the harness exercises end-to-end.
- `src/main/java/com/example/app/llm/OpenRouterLlmVisionClient.java:39-46`
  — constructor wiring of `ChatClient`, `ObjectMapper`, and configured
  model name (load-bearing for the @MockitoBean seam).
- `src/main/java/com/example/app/llm/OpenRouterLlmVisionClient.java:49-94`
  — full extract loop: prompt + media → ChatClient → JSON strip → parse.
- `src/main/java/com/example/app/llm/OpenRouterLlmVisionClient.java:96`
  — the greedy `Pattern.compile("\\[.*\\]")` regex; failure mode 1
  candidate.
- `src/main/java/com/example/app/llm/LlmExtractionResult.java:7-18`
  — `LlmExtractionResult` + `ProposedEvent` records; field-by-field
  contract for the diff predicate.
- `src/main/java/com/example/app/llm/LlmExtractionException.java:5-9`
  — `Kind { TIMEOUT, PROVIDER_ERROR, MALFORMED_RESPONSE }` envelope.
- `src/main/resources/application.properties:29-37` — Spring AI
  OpenAI / OpenRouter configuration (base URL, API key chain, model,
  temperature, 55 s timeout, no retries).
- `src/test/java/com/example/app/llm/LlmVisionClientTest.java:29-37`
  — annotation stack + `@MockitoBean ChatModel` to lift into the
  harness.
- `src/test/java/com/example/app/llm/LlmVisionClientTest.java:41-44`
  — `getDefaultOptions()` `@BeforeEach` stub (failure mode 5).
- `src/test/java/com/example/app/llm/LlmVisionClientTest.java:151-153`
  — `chatResponseOf(String)` helper to factor out into
  `LlmTestFixtures`.
- `src/test/java/com/example/app/llm/LlmVisionSmokeTest.java:25` —
  `@EnabledIfEnvironmentVariable(named = "OGARNIACZ_LIVE_SMOKE", matches = "true")`
  pattern for the live variant gate.
- `src/test/java/com/example/app/llm/LlmVisionSmokeTest.java:28` —
  `BUDGET_MS = 55_000L`, the per-fixture wall-clock ceiling.
- `src/test/java/com/example/app/llm/LlmVisionSmokeTest.java:36-38`
  — `ClassPathResource` + `StreamUtils.copyToByteArray` for image
  bytes load.
- `src/test/resources/llm/sample-announcement.png` — existing
  classpath fixture; the harness directory extends `llm/` rather than
  inventing a new root.
- `build.gradle:22` — `spring-ai-bom:2.0.0-M6`; pin to watch for
  shape drift (failure mode 3).
- `build.gradle:29` — `spring-ai-starter-model-openai`; no new
  dependency required.
- `context/foundation/test-plan.md:44-52` — risk-map row #1 and #2,
  the failure scenarios the harness closes.
- `context/foundation/test-plan.md:58-59` — risk-response guidance
  for #1 + #2 (oracle source, layer choice, anti-patterns).
- `context/foundation/test-plan.md:74` — Phase 1 rollout row
  (status: not started, this change is the start).
- `context/foundation/test-plan.md:91-94` — stack §4 row "LLM
  extraction regression suite: none yet — see §3 Phase 1".
- `context/foundation/test-plan.md:113` — quality gate "LLM
  extraction regression suite required after §3 Phase 1".
- `context/foundation/test-plan.md:146-148` — cookbook § 6.4 stub
  the harness must fill in.
- `context/foundation/test-plan.md:164-165` — §7 "lean, no eval
  platform, re-evaluate at ~30 fixtures".
- `context/foundation/prd.md:43` — Primary Success Criterion: ≥ 80 %
  first-extraction correctness on **date, title, requirements**
  — the diff's per-field choice.
- `context/foundation/prd.md:111-114` — FR-004: time optional,
  requirements is a merged "what to bring + dress code".
- `context/foundation/prd.md:113-114` — FR-005: unreadable image
  → actionable error path; **out of scope here** (Phase 4).
- `context/foundation/lessons.md:40-44` — H2 testRuntimeOnly lesson
  (already applied at `build.gradle:37`; the harness does not need
  the DB but the `@SpringBootTest` context will still spin one up).

## Architecture Insights

1. **The harness has no production-code dependency.** Everything lives
   under `src/test/` and `src/test/resources/`. The production
   `LlmVisionClient` interface (`LlmVisionClient.java:15`) and result
   record (`LlmExtractionResult.java:7-18`) already expose everything
   the diff predicate needs — `rawResponse` is what makes
   live-recording cheap (no separate capture API) and what makes the
   recorded-mock variant trustable (same string the production parser
   would see).

2. **Same fixture set, two variants** — and one diff predicate. This
   is the cost-× -signal collapse the test plan §1 calls for: one
   labelled fixture closes both risks because the *predicate* is the
   same; only the source of the raw response differs. There is no
   "harness for risk #1" vs "harness for risk #2" — they are the
   same test, run in two modes.

3. **`@MockitoBean` is the Boot 4 documented mock-bean primitive.**
   Spring Boot 4 docs (via context7 — `/websites/spring_io_spring-boot_4_0-snapshot`) only show
   `@MockitoBean` in concrete examples; `@MockBean` is deprecated.
   The existing tests already use the right import
   (`org.springframework.test.context.bean.override.mockito.MockitoBean`,
   `LlmVisionClientTest.java:26`); the harness copies that import,
   not the older `MockBean`.

4. **No `spring-ai-test` utility exists.** A targeted context7 query
   on the Spring AI 2.0 SNAPSHOT site
   (`/websites/spring_io_spring-ai_reference_2_0-snapshot`) returned
   zero hits for `spring-ai-test`, `MockChatModel`, or
   `TestChatClient`. Spring AI's published testing story is
   evaluator-driven (BLEU / relevance over live models), not
   fixture-based stubbing. Manual stubbing of `ChatModel.call(...)`
   is the idiomatic path — no documented framework alternative to
   adopt.

5. **The `@SpringBootTest` context cache is the harness's friend.**
   Lessons.md `:47-55` ("Per-controller `@SpringBootTest` is the
   layout standard … splitting is ~ free because Spring's context
   cache reuses contexts with identical config") applies: the live
   and recorded-mock harness classes both carry the same
   `REMEMBER_ME_KEY` test property, so their contexts coalesce with
   the rest of the suite. The cache **does** fragment when
   `@MockitoBean` is introduced — but the existing
   `LlmVisionClientTest` already pays that cost, so the recorded-mock
   harness joins its bucket at no extra context-spin cost.

6. **`rawResponse` as the recording medium dodges a class of bugs.**
   Capturing the model's raw string (rather than the parsed
   `List<ProposedEvent>`) means the recorded-mock variant *also*
   exercises the JSON-strip regex and the Jackson parse path — i.e.
   a Phase 1 regression suite gives Phase 4 (upload pipeline)
   incidental coverage of the parser's robustness too. If the harness
   recorded already-parsed events instead, parser regressions could
   only surface in live runs, defeating the CI gate.

## Historical Context (from prior changes)

- `context/archive/2026-06-01-openrouter-llm-client-wired/plan.md:76-77`
  — exception-translation table contract (Kind enum + three factory
  methods). The harness assumes this envelope is stable;
  `MALFORMED_RESPONSE` is the failure mode the recorded-mock variant
  uses to detect parser regression.
- `context/archive/2026-06-01-openrouter-llm-client-wired/plan.md:257`
  — the rationale for the **dual** env-gate (`OGARNIACZ_LIVE_SMOKE`
  *and* `OPENROUTER_API_KEY`): "a contributor with an OpenRouter key
  in their shell for some other project shouldn't trip this test by
  accident". The harness inherits the same dual-gate so that pattern
  is reused, not re-invented.
- `context/archive/2026-06-01-openrouter-llm-client-wired/plan.md:13,55,72-73`
  — the 60 s PRD ceiling → 55 s `BUDGET_MS` rationale ("5 s margin
  for prompt building, JSON parsing, and response handoff"). The
  harness reuses `BUDGET_MS` as the per-fixture wall-clock cap.
- `context/archive/2026-06-01-openrouter-llm-client-wired/plan.md:200-206`
  vs `context/archive/2026-06-01-openrouter-llm-client-wired/reviews/impl-review.md:30,44-61`
  — the plan named JDK / Spring exceptions (`SocketTimeoutException`,
  `RestClientResponseException`, `JsonProcessingException`); the
  actual implementation under Spring AI 2.0.0-M6 catches
  `OpenAIIoException` / `OpenAIServiceException` /
  `OpenAIInvalidDataException` / `JacksonException`. The fallback
  `RuntimeException → provider(0, …)` was added on impl-review and
  is in the live code (`OpenRouterLlmVisionClient.java:88-93`). The
  harness does **not** test these mappings — `LlmVisionClientTest`
  already does.
- `context/archive/2026-06-01-openrouter-llm-client-wired/change.md:23`
  — runbook step "Re-run the smoke after a model swap" is the
  *manual* equivalent of what Phase 1 makes automatic: a model swap
  should now also re-run the live variant of the regression harness
  (with `OGARNIACZ_RECORD_FIXTURES=true` after `git rm`-ing the
  stale recordings if the swap is intentional).
- `context/archive/2026-06-07-manual-event-entry/plan.md:86-88,104`
  — established the per-controller `@SpringBootTest` layout that
  Phase 4 will pick up; Phase 1 simply gets a per-feature
  `*RegressionTest.java` instead of a controller test.
- `context/archive/2026-06-07-manual-event-entry/follow-ups/review-fixes.md:23-42`
  — re-asserted the per-controller test-class rule. Phase 1's two
  harness classes are NOT controller tests; they are
  per-feature regression tests, so this rule does not constrain
  them beyond "give each class a focused name".

No prior change has implemented anything titled "regression harness",
"fixture suite", or "recorded mock". Phase 1 is the first; the
conventions established here become the cookbook entry § 6.4 in
`test-plan.md`.

## Related Research

None yet — this is the first research artifact in
`context/changes/llm-extraction-regression-harness/`. The next
artifact in this change folder is the plan
(`/10x-plan llm-extraction-regression-harness` will produce
`plan.md`).

## Open Questions

These are the design points the plan step must resolve. None block
this research artifact, but each becomes a concrete plan decision.

1. **Fixture count for the first plan iteration.** The §7 negative-
   space says "re-evaluate at ~30 fixtures". What's the *initial*
   number — 5? 10? — that lands in the plan as Phase 1's "done"
   bar? Suggest 8–10: enough to cover ≥ 1 ambiguous and ≥ 1
   FR-005-unreadable case alongside the happy-path mix, small enough
   to label by hand in one sitting.
2. **Where the recorded-mock variant runs in CI.** Today's CI gate is
   `./gradlew test` (per `test-plan.md` §5). The recorded-mock
   variant is plain JUnit so it joins automatically — but should the
   live variant be wired into a separate GitHub Actions job that
   runs on a schedule (e.g. nightly with a billed `OPENROUTER_API_KEY`)
   or stay strictly operator-run? Cost vs cadence is a plan call.
3. **`recorded-at=<sha>` enrichment in failure messages.** Failure
   mode 3 (Spring AI shape drift) recommends embedding the commit
   sha at which a recording was last touched. Easiest implementation:
   `git log -1 --format=%h -- <path>` at test runtime via
   `ProcessBuilder`. Less ergonomic but no-process: bundle a sidecar
   `recorded-at` metadata file. Plan decides.
4. **Should the harness assert `MALFORMED_RESPONSE` is not raised on
   any recorded fixture?** Implied by failure mode 1 (greedy regex)
   — but the diff predicate already catches a missing-events list.
   Adding a top-level `assertThat(result.proposedEvents()).isNotNull()`
   may be enough; an explicit "no `LlmExtractionException` for any
   recorded fixture" assertion would be louder. Plan decides.
5. **Does the `chatResponseOf` helper move into a shared
   `LlmTestFixtures` class, or get duplicated?** Context7 confirms
   the helper is the documented construction path — extracting it
   into one place is good hygiene, but at two callers (current test +
   harness) it could be left in place. Plan decides; impl-review
   should hold the line at 3 callers.
