# LLM Prompt: Year Resolution + Multi-Group + Language Implementation Plan

## Overview

Lift the LLM extraction prompt from its F-01 stub state by injecting today's date, encoding a year-resolution rule (closest-to-today ±6 months, tie → future), a multi-group / no-umbrella rule, and a parametrized language-passthrough directive. The call shape moves from a single `.user(...)` message to a Spring AI `system + user` split using `.param(...)` template interpolation, with a new `Clock` bean making the date deterministic and testable. Then re-record fixtures 02-08 and 10 against the new prompt and sync both `KNOWN_DIVERGENCES` maps to the post-fix divergence set in lockstep — landing as **two commits, one per phase**, each green-to-green so main is reviewable and bisectable at every step.

## Current State Analysis

The prompt at `src/main/java/com/example/app/llm/OpenRouterLlmVisionClient.java:26-33` is a deliberate stub from change `2026-06-01-openrouter-llm-client-wired` (F-01 explicitly deferred prompt engineering to S-05). It does not tell the model today's date, a year-resolution rule, an output language, or a rule for multi-group event slots. The call at line 59-62 routes both rules and image through `.user(u -> u.text(SYSTEM_PROMPT).media(mimeType, resource))` — the prompt is a *user* message; no `.system(...)` call exists anywhere in `src/main/`.

The consequences surfaced in the `llm-fixture-set-expansion` (2026-06-12) batch at scale: **15 of 22 documented divergences (68%) are year-mismatch entries** (fixtures 03→2024, 04→2023, 05→2025, 06→2023, all wrong; the model regresses by 1-3 years with no apparent pattern when the source lacks a year header). First-extraction accuracy is **~11%** against the **80% PRD target**. Fixture 07 carries an additional umbrella entry (one consolidated daily event in addition to the per-group time slots) that blows the recorded harness's event-count assertion — only `DISABLED_FIXTURES` keeps it green today. Fixture 05's title comes back in English (`"Group photos for diplomas and end of preschool year 2025/26"`) instead of the Polish source.

The harness contract (`src/test/java/com/example/app/llm/LlmExtractionRecordedRegressionTest.java`) compares divergence multisets against `KNOWN_DIVERGENCES`. A clean model improvement on a documented-divergence fixture surfaces as a "missing divergence" failure — that's the load-bearing signal the prompt fix worked. The recorded test asserts `actual.size() == expected.size()` before per-field diff, so fixture 07's umbrella entry blocks per-field diff and forces `DISABLED_FIXTURES`. `KNOWN_DIVERGENCES` is duplicated in `LlmExtractionRecordedRegressionTest.java:49-79` (22 rows, current) and `LlmExtractionLiveRegressionTest.java:67-70` (only `01-sample`, lagging behind expansion) — editing one without the other is the exact failure mode `lessons.md §"sweep sibling setup blocks"` warns against.

No `Clock` bean exists. One production `LocalDate.now()` call sits at `src/main/java/com/example/app/web/AppController.java:42`. The constructor of `OpenRouterLlmVisionClient` takes a `ChatClient.Builder` (Spring AI's auto-configured bean) and calls `.build()` at construction time — meaning a "pure unit test" of prompt construction requires the constructor to take `ChatClient` directly, with the build step lifted to a `@Bean ChatClient`.

The prerequisite change `llm-diff-title-tier` (commits `3131077` → `ac90819`) already relaxed `title` from the diff predicate, satisfying `lessons.md §"audit short-circuited fields"`: flipping the 15 `date-mismatch` rows to clean now happens against an honest baseline, with no risk of attributing newly-visible title divergences to this fix.

## Desired End State

After this change:

- `OpenRouterLlmVisionClient` issues a Spring AI prompt with a `.system(...)` message carrying the rules-and-format template (year-resolution rule, multi-group/no-umbrella rule, language-passthrough rule, JSON schema, today's date) and a separate `.user(u -> u.text(USER_HINT).media(mimeType, resource))` message carrying the image. Today's date comes from an injected `Clock` (system-default zone in prod, fixed in tests).
- `AppApplication` declares `@Bean Clock clock()` and `@Bean ChatClient chatClient(ChatClient.Builder)`; `OpenRouterLlmVisionClient` and `AppController` both constructor-inject `Clock` and use `LocalDate.now(clock)`.
- A new `OpenRouterLlmVisionClientPromptTest` (pure unit, no `@SpringBootTest`) instantiates the client directly with mocks + a `Clock.fixed(...)`, captures the `Prompt` passed to a mocked `ChatModel.call(...)`, and asserts on six named anchors (five content anchors + one placeholder-substitution canary; see Phase 1.5).
- Recorded fixtures 02-08 and 10 carry fresh `recorded-response.json` + `recorded-meta.json` from the new prompt. `DISABLED_FIXTURES` no longer contains `07-czerwiec-wazne-daty`.
- `KNOWN_DIVERGENCES` in **both** `LlmExtractionRecordedRegressionTest` and `LlmExtractionLiveRegressionTest` contain only the divergences that actually survived re-recording (the 15 date-mismatch rows are gone; residual rows are whatever real-world re-recording surfaces — expected: fixture 02/08 `requirements-norm-mismatch`, fixture 10 `time-mismatch`, plus anything new the prompt change incidentally produced). Both maps are byte-identical in their entries.
- `./gradlew test` is green (recorded harness passes); manual run of `LlmExtractionLiveRegressionTest` with creds is green against the fresh recordings.

### Verification:

- `./gradlew build` — green (compiles, units pass).
- `./gradlew test` — green (recorded harness passes against re-recorded fixtures; new prompt-construction unit test passes).
- `OGARNIACZ_LIVE_SMOKE=true ./gradlew test --tests com.example.app.llm.LlmExtractionLiveRegressionTest` — passes manually with `OPENROUTER_API_KEY` set (no recording-mode env var; this exercises the playback branch against the freshly recorded files and the live divergence map).
- `git grep -n "DISABLED_FIXTURES" src/test/java/com/example/app/llm/` shows the set no longer contains `07-czerwiec-wazne-daty`.
- The two `KNOWN_DIVERGENCES` maps (recorded test + live test) have identical contents per `git diff --stat`.

### Key Discoveries:

- `OpenRouterLlmVisionClient.java:26-33` — SYSTEM_PROMPT stub to replace.
- `OpenRouterLlmVisionClient.java:59-62` — single `.user(...)` call to split into `.system(...)` + `.user(...)`.
- `OpenRouterLlmVisionClient.java:39-46` — constructor takes `ChatClient.Builder`; refactor to `ChatClient` directly so the prompt-construction test can be a pure unit (no `@SpringBootTest`).
- `AppApplication.java` — no `Clock` bean, no `ChatClient` bean; both go here.
- `AppController.java:42` — only production `LocalDate.now()`; opportunistic migration to `LocalDate.now(clock)`.
- `LlmExtractionRecordedRegressionTest.java:49-79` — 22-row `KNOWN_DIVERGENCES`; 15 date-mismatch rows to remove after re-record.
- `LlmExtractionRecordedRegressionTest.java:88-95` — `DISABLED_FIXTURES` containing `07-czerwiec-wazne-daty`.
- `LlmExtractionLiveRegressionTest.java:67-70` — duplicated `KNOWN_DIVERGENCES`, only `01-sample`; sync to match recorded.
- `LlmExtractionLiveRegressionTest.java:100,108` — recording mode activates only when `recorded-response.json` is absent; `git rm` is required before re-record.
- `LlmVisionClientTest.java` — existing test uses `@SpringBootTest + @MockitoBean ChatModel`; the new prompt-construction test is intentionally a different shape (pure unit, no Spring context).
- Recording-mode atomic-write produces `0600` files on macOS (lesson from archived harness change); `chmod 644` before `git add`.
- `lessons.md §"sweep sibling setup blocks"` — both `KNOWN_DIVERGENCES` maps must move together.
- `lessons.md §"audit short-circuited fields"` — the prerequisite `llm-diff-title-tier` change has already landed; flipping date-mismatch to clean now happens against an honest title baseline.

## What We're NOT Doing

- **Embedded-time extraction (fixture 10)**: not adding a "scan body text for times" rule in this change. Risk of regressing fixtures 02/07 (per-group time slots); tracked as a separate follow-up. Fixture 10 `time-mismatch` stays in `KNOWN_DIVERGENCES` after re-record.
- **Structured output binding** (`OpenAiChatOptions.responseFormat(JSON_SCHEMA, schema)` / `BeanOutputConverter`): deferred to a later S-05 refinement; the regex parse path (`JSON_ARRAY = Pattern.compile("\\[.*\\]", Pattern.DOTALL)`) stays as-is.
- **Migration of `LocalDate.now()` test sites** (`AppApplicationTests`, `EventRepositoryTest`, `EventControllerTest` — 6+ call sites): out of scope. These tests don't exercise the Clock-injected paths; migration would be scope creep.
- **Polish-specific rule wording** for the year resolution: we explicitly chose closest-to-today over academic-year-aware. The rule does not mention "Polish kindergarten" or "rok szkolny" — keeps the prompt provider-agnostic and robust on summer programmes / multi-year reminders.
- **Locale plumbing** (`LocaleResolver`, `Locale` API on `ChatClient`): the language directive is parametrized via `.param("language", "Polish")` but no machinery wires locale to the call site yet. A future change adding the secondary persona (non-Polish announcements) plumbs that in.
- **`AppEventProperties` or any non-prompt configuration**: this change does not touch event-domain config, security, persistence, or the controller surface beyond the one-line `LocalDate.now()` → `LocalDate.now(clock)` migration.
- **Changing the model or temperature** (`spring.ai.openai.chat.options.model=google/gemini-2.5-flash`, `temperature=0.2`): the model selection is a separate concern; if accuracy still misses the 80% PRD target after this change, model choice gets re-evaluated in a different change.

## Implementation Approach

Two phases, two commits — each phase commits green. Phase 1 is TDD-able: write the prompt-construction unit test first (it fails because the production code still emits a single user message); then refactor the constructor, introduce the `Clock` and `ChatClient` beans, rewrite the prompt as a templated system + user call. **Phase 1 stays green end-to-end.** The recorded harness mocks `chatModel.call(any(Prompt.class))` at `LlmExtractionRecordedRegressionTest.java:120` and replays the on-disk `recorded-response.json` regardless of the new `Prompt` shape — so per-field diffs are unchanged from before Phase 1, and `KNOWN_DIVERGENCES` still matches. The "missing divergence" signal does not surface here; it only surfaces inside Phase 2 after re-recording (see below).

Phase 2 is `/10x-implement` territory (side-effecting recording, no useful red-test gate). The workflow: `git rm` the stale recordings (atomic-write contract: never overwrites, only writes when absent), run `LlmExtractionLiveRegressionTest` with `OGARNIACZ_RECORD_FIXTURES=true` + creds, `chmod 644` the resulting files. Then run the recorded test — **this is where the harness goes red** with "missing divergence" messages, because the freshly recorded responses produce clean per-field diffs that no longer match the documented `date-mismatch` rows. Read the actual surviving divergences out of the failure output, write those rows into both `KNOWN_DIVERGENCES` maps (recorded + live, in lockstep), and remove `07-czerwiec-wazne-daty` from `DISABLED_FIXTURES`. Phase 2 closes green. Each phase ships as its own commit — Phase 1 is a self-contained refactor + new unit test; Phase 2 is the fixture re-record + divergence-map sync.

## Critical Implementation Details

- **Constructor refactor enables the pure-unit test.** The current constructor takes `ChatClient.Builder` (Spring AI's auto-configured bean). To support `new OpenRouterLlmVisionClient(mockChatClient, mockClock, ...)` without `@SpringBootTest`, change the parameter type to `ChatClient` and add `@Bean ChatClient chatClient(ChatClient.Builder b) { return b.build(); }` in `AppApplication` (next to the `Clock` bean). The existing `LlmVisionClientTest` (which uses `@SpringBootTest + @MockitoBean ChatModel`) keeps working unchanged — the autowired `ChatClient` is built once at context start, and the underlying `ChatModel` is what gets mocked.
- **Recording mode is strictly create-not-overwrite.** `LlmExtractionLiveRegressionTest.java:100,108` checks `Files.exists(recorded-response.json)` first; if present, recording is silently skipped and grading runs. Without `git rm` of the stale files, the prompt change would never get recorded — the test would just grade old responses and report "everything fine".
- **Phase ordering inside the commit matters for SIGINT safety.** Run `git rm` before recording, not after — atomic-write produces `0600` files (chmod papercut) and SIGINT mid-record leaves valid partial state. If you `git rm` after recording, you've lost the safety net.
- **Discover-then-update is the only correct way to write the post-fix `KNOWN_DIVERGENCES`.** The recorded test's divergence multiset is what survives the new prompt against the freshly recorded responses; predicting the exact rows up front guesses about the model's behaviour and will be wrong on at least one row. Workflow: leave both maps empty after pruning the 15 date-mismatch rows, run `./gradlew test`, read the failure message (it prints actual vs expected divergences), write the actual rows in.
- **Both `KNOWN_DIVERGENCES` maps live or die together.** `lessons.md §"sweep sibling setup blocks"` — editing only the recorded map is the exact failure mode the lesson names. Live test's map currently lags behind expansion (only `01-sample`); this change is the natural place to sync.

## Phase 1: Prompt template, Clock + ChatClient beans, prompt-construction unit test

### Overview

Replace the static `SYSTEM_PROMPT` with a templated system message, introduce `Clock` and `ChatClient` beans, split the call into `.system(...)` + `.user(...)`, and add a pure unit test that asserts on the outgoing prompt's named anchors. Phase exits when `./gradlew build` is green and the new unit test passes — recorded harness redness on 02-08 + 10 is accepted (that's Phase 2's job).

### Changes Required:

#### 1. `Clock` bean + `ChatClient` bean

**File**: `src/main/java/com/example/app/AppApplication.java`

**Intent**: Make today's date a testable Spring bean (system-default zone in prod), and lift `ChatClient` build out of `OpenRouterLlmVisionClient`'s constructor so the client can be instantiated directly in a pure unit test.

**Contract**: Two new `@Bean` methods on `AppApplication`:
- `Clock clock()` returning `Clock.systemDefaultZone()`.
- `ChatClient chatClient(ChatClient.Builder builder)` returning `builder.build()`.

Both methods sit alongside the existing `@SpringBootApplication`-annotated class; no separate `@Configuration` class is needed at this size.

#### 2. `OpenRouterLlmVisionClient` constructor + prompt template + call shape

**File**: `src/main/java/com/example/app/llm/OpenRouterLlmVisionClient.java`

**Intent**: Switch the client to consume `ChatClient` directly (not `Builder`) and a `Clock`. Replace the `SYSTEM_PROMPT` string with a `SYSTEM_TEMPLATE` carrying `{today}` and `{language}` placeholders plus three new rules. Split the `.user(SYSTEM_PROMPT + media)` call into `.system(TEMPLATE params)` + `.user(USER_HINT + media)`.

**Contract**:
- Constructor signature changes to `OpenRouterLlmVisionClient(ChatClient chatClient, Clock clock, ObjectMapper objectMapper, @Value("${spring.ai.openai.chat.options.model}") String configuredModel)`. Builder is gone; `Clock` is new.
- `SYSTEM_TEMPLATE` is a private static final `String` containing:
  - The JSON schema clause (`Return ONLY a JSON array...` plus the per-item shape; preserved from the current prompt).
  - A "Today is {today}." line.
  - A year-resolution rule: choose the year that places the event closest to today (±6 months from today); when two years are equidistant, prefer the future. The rule's prompt wording must contain a recognisable anchor — implementer's choice of phrasing, but the unit test asserts the substring **"closest to today"** appears in the system message (case-insensitive).
  - A multi-group / no-umbrella rule: if the same date carries multiple per-group time slots, emit one entry per slot; do NOT also emit a consolidating entry. The rule's wording must contain the anchor **"one entry per group"** (case-insensitive) for the unit test.
  - A language-passthrough rule: return all string fields (`title`, `requirements`, `notes`) in `{language}`; do not translate.
  - The empty-array clause (`If no events are visible, return [].`) preserved from the current prompt.
- `USER_HINT` is a private static final `String` short user-message text — implementer's choice, but the unit test asserts it is non-blank and appears in the captured user message. Use `"Extract events from the attached image."` as the reference shape; any equivalent one-line directive is fine.
- The `extract(...)` method body changes:
  ```java
  String today = LocalDate.now(clock).toString();
  String raw = chatClient.prompt()
      .system(sp -> sp.text(SYSTEM_TEMPLATE)
                      .param("today", today)
                      .param("language", "Polish"))
      .user(u -> u.text(USER_HINT).media(mimeType, resource))
      .call()
      .content();
  ```
  Snippet included because the split-call shape is the load-bearing API contract the test asserts against — getting `.system(...)` vs `.user(...)` routing wrong is the most likely silent bug. Everything else in `extract(...)` (the try/catch, the latency timer, the logging, the JSON regex parse) is unchanged.

#### 3. `AppController` `Clock` migration

**File**: `src/main/java/com/example/app/web/AppController.java`

**Intent**: Once the `Clock` bean exists, the only other production `LocalDate.now()` site should use it too — the project gets one source of "now" instead of two.

**Contract**: Constructor takes a `Clock` parameter (alongside its existing dependencies). The call at `AppController.java:42` changes from `LocalDate.now()` to `LocalDate.now(clock)`. No other behavioural change.

#### 4. Prompt-construction unit test

**File**: `src/test/java/com/example/app/llm/OpenRouterLlmVisionClientPromptTest.java` (new)

**Intent**: Lock the outgoing prompt's shape by asserting on six named anchors (five content anchors + one placeholder-substitution canary). The test is a pure unit (no `@SpringBootTest`, no Spring context): construct the client directly with mocked dependencies and a `Clock.fixed(...)`, capture the `Prompt` argument passed to a mocked `ChatModel.call(...)`, and run assertions on its system and user messages.

**Contract**:
- Test class: plain JUnit 5, no Spring annotations. Mocks via Mockito.
- Setup: build a mock `ChatClient` via `ChatClient.builder(mockChatModel).build()` (the Spring AI builder constructs a `ChatClient` around any `ChatModel`) — this is the standard hermetic shape and reuses `LlmTestFixtures.stubDefaultChatOptions` to satisfy the OpenAI-options stub. Inject a fixed clock `Clock.fixed(Instant.parse("2026-06-12T10:00:00Z"), ZoneId.of("Europe/Warsaw"))`.
- Action: call `client.extract(FAKE_IMAGE, MimeTypeUtils.IMAGE_PNG)` with a happy-path JSON response stubbed on `chatModel.call(any(Prompt.class))` via `LlmTestFixtures.chatResponseOf("[]")`.
- Capture: `ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class); verify(chatModel).call(promptCaptor.capture());`
- Assertions on `promptCaptor.getValue()`:
  1. System message text contains `"Today is 2026-06-12"` (case-sensitive — that's the canonical date format).
  2. System message text contains `"Polish"` (case-sensitive — the `.param("language", "Polish")` interpolation result).
  3. System message text contains `"closest to today"` (case-insensitive — the year-rule anchor).
  4. System message text contains `"one entry per group"` (case-insensitive — the multi-group rule anchor).
  5. User message text contains the `USER_HINT` content AND user message has exactly 1 media item with `MimeTypeUtils.IMAGE_PNG` mime type.
  6. System message text does NOT contain the literal substring `"{today}"` or `"{language}"` (case-sensitive). This is a defense-in-depth canary: Spring AI's `.param(...)` interpolation is eager today (the captured `Prompt` carries already-rendered text), but if a future Spring AI minor version moves to lazy rendering, the other anchors would still pass incidentally while the placeholder substitution path silently breaks. This assertion fails loudly in that scenario.
- The test does NOT assert on the full prompt text or schema — only the six anchors. Anything else is implementation latitude.

If `LlmTestFixtures` does not yet have a helper that bridges `ChatClient.builder(mockChatModel).build()` cleanly, the test inlines the setup; if a sibling test would benefit (it would: `LlmVisionClientTest` and the recorded test both do this), lift via the rule in `lessons.md §"sweep sibling setup blocks"`.

### Success Criteria:

#### Automated Verification:

- `./gradlew compileJava compileTestJava` succeeds (the constructor refactor and Clock injection compile cleanly).
- `./gradlew test --tests com.example.app.llm.OpenRouterLlmVisionClientPromptTest` passes (all six anchor assertions green).
- `./gradlew test --tests com.example.app.llm.LlmVisionClientTest` passes (existing client tests still work after constructor refactor; `@SpringBootTest` autowires the new `ChatClient` bean).
- `./gradlew test --tests com.example.app.AppApplicationTests` passes (context loads with the new `Clock` and `ChatClient` beans).
- `./gradlew test --tests "com.example.app.event.*"` passes (`AppController` `Clock` migration didn't break controller/repository tests).
- `./gradlew build` succeeds end-to-end. The recorded harness still passes in Phase 1 — its mock replays the on-disk responses regardless of the new prompt shape, so divergences are unchanged from before this phase.

#### Manual Verification:

- Run `./gradlew bootRun` locally (with `SPRING_DATASOURCE_*` set); the app starts without errors. Hit `/upload` with any kindergarten poster JPG via the existing UI; the LLM returns events whose dates land in 2025 or 2026 (year is no longer wildly wrong). This is qualitative confirmation that the new prompt reaches the model in production; the recorded harness cannot show the same signal in Phase 1 because its mock replays old responses (the divergence flip surfaces in Phase 2 only).

**Implementation Note**: Phase 1 is a self-contained refactor + new unit test that lands as its own commit. Both `./gradlew build` and the recorded harness stay green — the prompt change reaches production but does not reach the mocked replay path. After Phase 1's commit, proceed to Phase 2 (re-record + divergence-map sync) as a separate commit. Phase blocks use plain bullets — the corresponding `- [ ]` checkboxes for these items live in the `## Progress` section at the bottom.

---

## Phase 2: Re-record fixtures + sync `KNOWN_DIVERGENCES` maps + remove from `DISABLED_FIXTURES`

### Overview

Re-record fixtures 02-08 + 10 against the new prompt via `LlmExtractionLiveRegressionTest`'s recording mode. Then run the recorded harness to discover what divergences actually survived the prompt change, write those rows into both `KNOWN_DIVERGENCES` maps (recorded + live, in lockstep), and remove `07-czerwiec-wazne-daty` from `DISABLED_FIXTURES`. Phase 2 closes when `./gradlew test` is green and the live test passes manually with creds. Phase 2 lands as its own commit; the intermediate red state (between re-recording and updating `KNOWN_DIVERGENCES`) lives inside the implementer's working tree only and does not appear on main.

### Changes Required:

#### 1. Delete stale recordings (mandatory before re-record)

**Files**: under `src/test/resources/llm/fixtures/`:
- `02-wielkanoc-sniadanie/recorded-response.json`, `02-wielkanoc-sniadanie/recorded-meta.json`
- `03-marzec-bez-godzin/recorded-response.json`, `03-marzec-bez-godzin/recorded-meta.json`
- `04-marzec-wazne-daty/recorded-response.json`, `04-marzec-wazne-daty/recorded-meta.json`
- `05-zdjecia-dyplomowe/recorded-response.json`, `05-zdjecia-dyplomowe/recorded-meta.json`
- `06-luty-wazne-daty/recorded-response.json`, `06-luty-wazne-daty/recorded-meta.json`
- `07-czerwiec-wazne-daty/recorded-response.json`, `07-czerwiec-wazne-daty/recorded-meta.json`
- `08-grzybobranie/recorded-response.json`, `08-grzybobranie/recorded-meta.json`
- `10-warsztaty-www/recorded-response.json`, `10-warsztaty-www/recorded-meta.json`

**Intent**: The recording-mode branch (`LlmExtractionLiveRegressionTest.java:100,108`) only writes when the response file is absent. Without `git rm` first, recording silently skips and grading runs against the stale buggy responses.

**Contract**: Use `git rm` (not `rm`) — both for clean staging and so a SIGINT mid-phase doesn't leave the working tree with un-rm'd files lying around. After this step the listed paths are absent from the working tree and the index. Fixtures `01-sample` and `09-niepodleglosci-www` keep their recordings — they were already getting the year right.

#### 2. Recording run

**Action** (not a file edit — operational step):
```bash
OGARNIACZ_LIVE_SMOKE=true OGARNIACZ_RECORD_FIXTURES=true \
OPENROUTER_API_KEY=sk-or-... \
  ./gradlew test --tests com.example.app.llm.LlmExtractionLiveRegressionTest --info
```

**Intent**: Capture fresh `recorded-response.json` + `recorded-meta.json` for the 8 deleted fixtures using the new prompt. The atomic-write contract makes SIGINT mid-run safe; budget is ~$0.008 + 6-8 minutes wall-clock.

**Contract**: After the run completes, the 16 deleted files (2 per fixture × 8 fixtures) are all back, with mode `0600` (the macOS papercut). The test report shows recording-mode passes for all 8 fixtures.

#### 3. Fix recorded-file permissions

**Action**:
```bash
chmod 644 src/test/resources/llm/fixtures/*/recorded-*.json
```

**Intent**: Recording-mode atomic-write produces `0600`-mode files on macOS. Fix before `git add` so the repo doesn't carry inconsistent file modes.

**Contract**: All `recorded-*.json` files under `src/test/resources/llm/fixtures/` are mode `0644`.

#### 4. Remove `07-czerwiec-wazne-daty` from `DISABLED_FIXTURES`

**File**: `src/test/java/com/example/app/llm/LlmExtractionRecordedRegressionTest.java`

**Intent**: The umbrella-event behaviour is gone after the prompt change; the fixture can re-enable.

**Contract**: The `Set.of(...)` at line 88-95 no longer includes `"07-czerwiec-wazne-daty"`. If the set becomes empty, leave it as `Set.of()` (don't delete the field — keeps the surface available for future disablements).

#### 5. Update `KNOWN_DIVERGENCES` (recorded test) — discover-then-update

**File**: `src/test/java/com/example/app/llm/LlmExtractionRecordedRegressionTest.java`

**Intent**: Replace the current 22-row `KNOWN_DIVERGENCES` map (lines 49-79) with the set of divergences that actually survived re-recording. The 15 date-mismatch rows definitely go (research-supported); the rest is discover-then-update.

**Contract**:
- Workflow: after Phase 2 steps 1-4, run `./gradlew test --tests com.example.app.llm.LlmExtractionRecordedRegressionTest`. The failures' assertion messages enumerate actual vs expected divergences per fixture. Read each fixture's actual divergence multiset and write it into the map.
- Expected residuals (from research; verify against actual):
  - `01-sample` keeps its 2 `requirements-norm-mismatch` rows (unchanged — not re-recorded).
  - `02-wielkanoc-sniadanie` keeps its `requirements-norm-mismatch` rows (model condenses politeness markers); may gain/lose rows depending on Polish-output interaction.
  - `08-grzybobranie` keeps its `requirements-norm-mismatch` row.
  - `10-warsztaty-www` keeps its `time-mismatch` row (embedded-time follow-up explicitly out of scope).
  - `03-marzec-bez-godzin`, `04-marzec-wazne-daty`, `05-zdjecia-dyplomowe`, `06-luty-wazne-daty`, `07-czerwiec-wazne-daty`, `09-niepodleglosci-www`: ideally empty entries (or absent from the map). Any non-empty entry here is a residual the prompt fix didn't close — note in the commit message.
- If a fixture's divergences include something new the prompt change introduced (e.g., a regression on requirements wording from the Polish directive), call it out in the commit and surface in the impl-review.
- The map is the authoritative documentation of "currently-tolerated divergences" — every row is a debt the team is choosing to carry. If an entry feels wrong, **don't** silence by adding it; investigate.

#### 6. Sync `KNOWN_DIVERGENCES` (live test)

**File**: `src/test/java/com/example/app/llm/LlmExtractionLiveRegressionTest.java`

**Intent**: The live test's map currently only carries `01-sample` (line 67-70). Per `lessons.md §"sweep sibling setup blocks"`, the recorded and live maps belong to the same fixture-family and must move together. Sync the live map to match the recorded map exactly.

**Contract**: After this step, `git diff` shows the live map's `Map.of(...)` block has the same entries as the recorded map's `Map.ofEntries(...)` block (modulo the `Map.of` vs `Map.ofEntries` syntactic difference required by the entry count). Byte-equal `KnownDivergence(...)` instantiations row-for-row.

### Success Criteria:

#### Automated Verification:

- `./gradlew test --tests com.example.app.llm.LlmExtractionRecordedRegressionTest` passes (recorded harness green after `KNOWN_DIVERGENCES` updated to match actual surviving divergences).
- `./gradlew test` passes end-to-end (no regression in any other test suite).
- `git ls-files src/test/resources/llm/fixtures/ | xargs -I{} stat -f "%Lp {}" {} | grep recorded` shows mode `644` on every recorded file (no `600` leftovers).
- `git grep -n "07-czerwiec-wazne-daty" src/test/java/com/example/app/llm/LlmExtractionRecordedRegressionTest.java` returns no `DISABLED_FIXTURES` hit (may still appear in comments).
- Diff between the two `KNOWN_DIVERGENCES` map bodies (recorded vs live) shows no row-level differences.

#### Manual Verification:

- Run `OGARNIACZ_LIVE_SMOKE=true OPENROUTER_API_KEY=... ./gradlew test --tests com.example.app.llm.LlmExtractionLiveRegressionTest` (no `OGARNIACZ_RECORD_FIXTURES`). The test exercises the playback branch against the freshly recorded responses and uses the live divergence map. Passes green. (Cost: ~$0.001-0.002 for the live API checks the test does beyond playback.)
- Eyeball the new `recorded-response.json` files for fixtures 03-06: dates should now be 2026-xx (or whatever closest-to-today resolved to from 2026-06-12 — Feb, March 2026 should be in the past but inside the ±6mo window).
- Eyeball fixture 05's new title: should be in Polish (`"Pamiątkowe zdjęcia grupowe do dyplomów"` shape), not English translation.
- Eyeball fixture 07's new payload: should contain per-group time slots (e.g., 09:00, 11:30, 13:00) and NO consolidating umbrella entry.
- Two commits, one per phase. Run `git log -2 --stat` and confirm: commit 1 carries Phase 1 (Clock bean, ChatClient bean, prompt template + split call, prompt-construction unit test, AppController Clock migration) and is fully green; commit 2 carries Phase 2 (re-recorded fixtures, KNOWN_DIVERGENCES sync, DISABLED_FIXTURES update) and is also fully green. Each commit is independently reviewable, revertable, and bisectable.

**Implementation Note**: This is the last phase; on success, the change is ready for `/10x-impl-review` and then `/10x-archive`. Update `change.md` to `status: implemented` and bump `updated` to today as part of this commit. Phase blocks use plain bullets — the corresponding `- [ ]` checkboxes for these items live in the `## Progress` section.

---

## Testing Strategy

### Unit Tests:

- New `OpenRouterLlmVisionClientPromptTest` (Phase 1): six anchors on the captured `Prompt` (five content + one placeholder-substitution canary). Pure unit — no `@SpringBootTest`, no real `ChatClient`. Catches the exact regression class the change is most exposed to (silent `.system(...)` vs `.user(...)` routing bugs, `.param(...)` not interpolating, fixed-clock not flowing through, future Spring AI shift from eager to lazy interpolation).
- Existing `LlmVisionClientTest` (Phase 1): smoke check that the constructor refactor + new `ChatClient` bean don't break the autowired path. Same surface as today.

### Integration Tests:

- Recorded harness (`LlmExtractionRecordedRegressionTest`, Phase 2): the divergence-multiset equality assertion is the load-bearing signal the prompt fix worked. Re-recorded responses replay through the production parser; divergences match the post-fix `KNOWN_DIVERGENCES`.
- Live harness (`LlmExtractionLiveRegressionTest`, Phase 2): manual run with creds catches anything that survives mocking (real API request shape, real cost, real wall-clock). Not part of CI.

### Manual Testing Steps:

1. Phase 1: confirm recorded harness fails on fixtures 02-08 + 10 with "missing divergence" shape (not "extra divergence" or Spring AI exception).
2. Phase 2: after re-record, eyeball fixture 03's recorded date (should be 2026-03, not 2024-03), fixture 05's title (Polish, not English), fixture 07's event count (per-group slots, no umbrella).
3. Phase 2: run the live test manually with creds; passes green.
4. Phase 2: `git log -1 --stat` shows one commit covers both phases.

## Performance Considerations

- The prompt grows from ~7 lines to ~15 lines (year rule + multi-group rule + language rule + template interpolation). Adds maybe 50-100 input tokens per call; at OpenRouter prices for `gemini-2.5-flash` this is ~$0.000005 per call — immaterial.
- Wall-clock per call is unchanged (LLM-bound, not prompt-construction-bound).
- The `Clock` injection has zero perf cost (one method call per `extract(...)`).
- Phase 2's re-recording is a one-time operational cost (~$0.008 + 6-8 min wall-clock); not a per-call concern.

## Migration Notes

- No data migration. No schema changes.
- The `Clock` bean change is backwards-compatible: `Clock.systemDefaultZone()` produces the same instant as `LocalDate.now()`.
- The `ChatClient` bean change is backwards-compatible: `ChatClient.Builder.build()` is what `OpenRouterLlmVisionClient`'s constructor does today; lifting it to a `@Bean` produces the same `ChatClient` instance.
- The constructor signature change of `OpenRouterLlmVisionClient` is internal — the only callers are Spring's container and the new unit test.
- No production data is touched. Recorded fixtures in `src/test/resources/llm/fixtures/` are test data, not prod data.

## References

- Related research: `context/changes/llm-prompt-year-resolution/research.md`
- Curator triage that surfaced the 68% year-mismatch failure class: `context/changes/llm-fixture-set-expansion/triage.md:39-49`
- Archived prompt-stub rationale: `context/archive/2026-06-01-openrouter-llm-client-wired/plan.md:42-43`
- Archived harness contract (divergence-multiset equality, recording workflow): `context/archive/2026-06-10-llm-extraction-regression-harness/plan.md:473-478`
- Archived KNOWN_DIVERGENCES duplication finding: `context/archive/2026-06-10-llm-extraction-regression-harness/change.md:101-128`
- Spring AI `ChatClient` reference (`.system(...)`, `.param(...)`, `.media(...)` routing rules): https://docs.spring.io/spring-ai/reference/api/chatclient.html
- Spring AI multimodality reference: https://docs.spring.io/spring-ai/reference/api/multimodality.html
- Lesson (fixture-family setup duplication): `context/foundation/lessons.md` §"When lifting a test helper, sweep sibling `@BeforeEach` / setup blocks for duplication too"
- Lesson (grade what users care about + audit short-circuited fields — prerequisite satisfied by `llm-diff-title-tier`): `context/foundation/lessons.md` §"Grade only what a user would call wrong"
- Existing client test as test-shape reference: `src/test/java/com/example/app/llm/LlmVisionClientTest.java`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Prompt template, Clock + ChatClient beans, prompt-construction unit test

#### Automated

- [x] 1.1 `./gradlew compileJava compileTestJava` succeeds (the constructor refactor and Clock injection compile cleanly)
- [x] 1.2 `./gradlew test --tests com.example.app.llm.OpenRouterLlmVisionClientPromptTest` passes (all six anchor assertions green)
- [x] 1.3 `./gradlew test --tests com.example.app.llm.LlmVisionClientTest` passes (existing client tests still work after constructor refactor)
- [x] 1.4 `./gradlew test --tests com.example.app.AppApplicationTests` passes (context loads with the new Clock and ChatClient beans)
- [x] 1.5 `./gradlew test --tests "com.example.app.event.*"` passes (AppController Clock migration didn't break controller/repository tests)
- [x] 1.6 `./gradlew build` succeeds end-to-end (recorded harness stays green; the mock replays old responses regardless of the new prompt shape)

#### Manual

- [x] 1.7 Run `OGARNIACZ_LIVE_SMOKE=true OPENROUTER_API_KEY=... ./gradlew test --tests com.example.app.llm.LlmVisionSmokeTest` (adapted from the original /upload UI step — no upload endpoint exists yet); eyeball that returned event dates land in 2025 or 2026 (qualitative confirmation that the new prompt reaches the live model in production)

### Phase 2: Re-record fixtures + sync `KNOWN_DIVERGENCES` maps + remove from `DISABLED_FIXTURES`

#### Automated

- [ ] 2.1 `./gradlew test --tests com.example.app.llm.LlmExtractionRecordedRegressionTest` passes (recorded harness green after `KNOWN_DIVERGENCES` updated to match actual surviving divergences)
- [ ] 2.2 `./gradlew test` passes end-to-end (no regression in any other test suite)
- [ ] 2.3 `git ls-files src/test/resources/llm/fixtures/ | xargs -I{} stat -f "%Lp {}" {} | grep recorded` shows mode 644 on every recorded file
- [ ] 2.4 `git grep -n "07-czerwiec-wazne-daty" src/test/java/com/example/app/llm/LlmExtractionRecordedRegressionTest.java` returns no `DISABLED_FIXTURES` hit
- [ ] 2.5 Diff between the two `KNOWN_DIVERGENCES` map bodies (recorded vs live) shows no row-level differences

#### Manual

- [ ] 2.6 Run live test (`OGARNIACZ_LIVE_SMOKE=true OPENROUTER_API_KEY=... ./gradlew test --tests com.example.app.llm.LlmExtractionLiveRegressionTest`) passes green
- [ ] 2.7 Eyeball new `recorded-response.json` for fixtures 03-06: dates land in 2026-xx (or whatever closest-to-today resolved to from 2026-06-12)
- [ ] 2.8 Eyeball fixture 05's new title: Polish (`"Pamiątkowe zdjęcia grupowe do dyplomów"` shape), not English translation
- [ ] 2.9 Eyeball fixture 07's new payload: per-group time slots (09:00, 11:30, 13:00) and NO consolidating umbrella entry
- [ ] 2.10 `git log -1 --stat` shows one commit covers both phases (Phase 1 alone leaves main red)
