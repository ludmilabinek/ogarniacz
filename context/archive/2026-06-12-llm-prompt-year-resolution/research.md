---
date: 2026-06-12
researcher: Ludmiła
git_commit: a8a5f7af1e78d6c1118651e3d0f7de46bd6210e3
branch: main
repository: 10xdevsOgarniacz
topic: "Resolve year for dates without an explicit year in the LLM extraction prompt"
tags: [research, llm, prompt-engineering, regression-harness, openrouter, spring-ai]
status: complete
last_updated: 2026-06-12
last_updated_by: Ludmiła
---

# Research: Resolve year for dates without an explicit year in the LLM extraction prompt

**Date**: 2026-06-12
**Researcher**: Ludmiła
**Git Commit**: a8a5f7af1e78d6c1118651e3d0f7de46bd6210e3
**Branch**: main
**Repository**: 10xdevsOgarniacz

## Research Question

Update the LLM extraction prompt so that dates without an explicit year are resolved correctly (currently the model picks 2023/2024/2025 instead of the current academic year), and address two sibling failures the curator bundled into the same prompt pass:

1. **Redundant umbrella event** on fixture `07-czerwiec-wazne-daty` (model emits 10 events vs oracle 9, with an extra umbrella entry that consolidates per-group time slots).
2. **Output language drift** — the model occasionally translates Polish source content into English (fixture 05 returns `"Group photos for diplomas and end of preschool year 2025/26"` instead of the Polish source title), with no explicit Polish-output instruction in the prompt.

Investigate two solution dimensions: (a) prompt rule + inject today's date into the prompt at call time, and (b) multi-year edge cases — so the new rule does not regress announcements that DO carry a year or that legitimately span calendar boundaries.

## Summary

The `SYSTEM_PROMPT` at `src/main/java/com/example/app/llm/OpenRouterLlmVisionClient.java:26-33` was intentionally minimal in change `2026-06-01-openrouter-llm-client-wired` — F-01 explicitly deferred prompt engineering to S-05 and never told the model **(a)** today's date, **(b)** how to resolve missing years, **(c)** what language to respond in, or **(d)** how to handle multi-group event slots. The fixture-expansion batch (2026-06-12) surfaced the consequences in concrete numbers: **15 of 22 documented divergences (68%) are year-mismatch entries**, and the model picks 2023, 2024, or 2025 with no apparent pattern when the source poster does not state a year. First-extraction accuracy is currently **~11%** against an **80% PRD target**; the back-of-envelope estimate after this prompt fix lifts it to ~62.5%.

The right prompt shape, given the Spring AI 1.0+ ChatClient API, is to **split the call** into a `.system(sp -> sp.text(template).param("today", today).param("language", "Polish"))` for rules + parameters and a `.user(u -> u.text("...").media(mimeType, resource))` for the image — Spring AI's multimodality docs are explicit that `.media(...)` is only valid on user messages, but the textual rules belong on the system message where prompt templating + variable interpolation are supported. A `java.time.Clock` bean must be wired in (none exists today) so the date is testable and not pinned to `LocalDate.now()` in production code.

The umbrella-event behaviour needs a second prompt rule — *"if a date carries multiple group/time-slot entries, emit one event per slot and do not also emit a consolidating entry"* — because the recorded harness asserts on event count before per-field diff, and there is no `extra-event` slot in `KnownDivergence`. The Polish-language directive is a one-line addition (`.param("language", "Polish")`) but eliminates the current English-translation drift seen on fixture 05.

After the prompt change ships, the curator must re-record affected fixtures with `OGARNIACZ_LIVE_SMOKE=true OGARNIACZ_RECORD_FIXTURES=true` and prune resolved `date-mismatch` rows from **both** copies of `KNOWN_DIVERGENCES` (`LlmExtractionRecordedRegressionTest.java:49-79` and `LlmExtractionLiveRegressionTest.java:67-70`) in lockstep — they drift silently otherwise.

## Detailed Findings

### Current prompt and call shape

- The prompt is a static final string at [OpenRouterLlmVisionClient.java:26-33](src/main/java/com/example/app/llm/OpenRouterLlmVisionClient.java). It contains the JSON schema (date/time/title/requirements/notes) and a "return `[]` if no events" empty-array clause. **It contains no current-date anchor, no language directive, no rule for multi-group event slots.**
- The call at [OpenRouterLlmVisionClient.java:60](src/main/java/com/example/app/llm/OpenRouterLlmVisionClient.java) routes BOTH rules and image through a single `.user(u -> u.text(SYSTEM_PROMPT).media(mimeType, resource))`. The prompt is therefore a *user* message, not a system message — confirmed by grep: zero `.system(...)` calls exist anywhere in `src/main/`.
- `ChatClient.Builder` is autowired and `.build()` is called with no customization. There is no `@Configuration` that calls `.defaultSystem(...)` or `.defaultOptions(...)`. The only `application.properties` configuration is `spring.ai.openai.chat.options.model=google/gemini-2.5-flash` and `temperature=0.2` ([application.properties:32-33](src/main/resources/application.properties)).
- The schema target is the record [LlmExtractionResult.ProposedEvent](src/main/java/com/example/app/llm/LlmExtractionResult.java) at lines 12-18: `(LocalDate date, LocalTime time, String title, String requirements, String notes)`. No JSON validation annotations.

### Year-resolution failure pattern (raw fixture evidence)

From the per-fixture survey of `src/test/resources/llm/fixtures/`:

| Fixture | Oracle dates | Model's years | Source has year? | Source type |
|---|---|---|---|---|
| `03-marzec-bez-godzin` | 2026-03/04 (×5) | 2024 (×5) | No (poster, no header) | Poster photo |
| `04-marzec-wazne-daty` | 2026-03 (×5) | 2023 (×5) | No | Poster photo |
| `05-zdjecia-dyplomowe` | 2026-05-26 | 2025 | "rok przedszkolny 2025/26" mentioned but NOT in model output | Poster photo |
| `06-luty-wazne-daty` | 2026-02 (×5) | 2023 (×5) | No | Poster photo |
| `07-czerwiec-wazne-daty` | 2026-06 (×9) | 2025 (umbrella entry); rest mixed | "rok przedszkolny 2025/26" implicit | Poster photo |
| `08-grzybobranie` | 2025-09-27 | 2025 ✓ | No, but autumn 2025 announcement | Poster photo |
| `09-niepodleglosci-www` | 2025-11-06 | 2025 ✓ | Yes — header `"czwartek, 6 listopad 2025"` | HTML screenshot |
| `10-warsztaty-www` | 2026-05-15 | 2026 ✓ | Yes — header `"piątek, 15 maj 2026"` | HTML screenshot |

**Pattern:** the model regresses by 1-3 years when the poster lacks an explicit year header, and the error is *not* "pick the past year always" (fixtures 03→2024, 04→2023, 06→2023, 05→2025 — three different years, all wrong). When the source carries a year (HTML web layouts in 09 and 10), the model gets it right. Today is `2026-06-12`; the curator's intended interpretation is "this poster is part of the current 2025/26 academic year, so March-without-year means March 2026 (already past, but within the school year), February-without-year means February 2026". No fixture in the seed batch carries a date that should roll into the *next* academic year.

### What the prompt rule must say

A *next future occurrence* rule does NOT match the curator's oracle. Fixture 03 is dated "20 marca" — March 2026 is in the past relative to today (2026-06-12), but the curator expects 2026-03-20, not 2027-03-20. The semantically clean rule is **academic-year-aware**:

- The Polish academic year runs September through June. Today (2026-06-12) is the tail of the 2025/2026 academic year.
- If the announcement carries a year (header `"rok szkolny 2025/2026"`, breadcrumb date metadata, body phrase like `"2025/26"`), use that year directly.
- Otherwise, infer the year so the date falls within the academic year that contains `today`: dates in months **September-December → use the first year of the academic year**; dates in months **January-August → use the second year of the academic year**.
- A simpler stand-in that produces the same answer on every fixture seen: *"choose the year that places the event closest to today (±6 months); break ties by choosing the future."* This rule degrades gracefully on edge cases the academic-year rule does not cover (kindergarten summer programmes in July, multi-year reminders, etc.).

The Spring AI canonical injection shape for the date is `.system(sp -> sp.text("... Today is {today}. ...").param("today", today.toString()))` ([Spring AI docs, ChatClient](https://docs.spring.io/spring-ai/reference/api/chatclient.html); see *Architecture Insights* for the snippet).

### Umbrella event (fixture 07)

The model output on `07-czerwiec-wazne-daty` includes a redundant entry:

```json
{
  "date": "2025-06-19",
  "time": null,
  "title": "Uroczyste zakończenie roku przedszkolnego 2025/26",
  "requirements": "odświętne stroje",
  "notes": "Spektakl dyplomowy \"Brzydkie kaczątko\" ..."
}
```

…in addition to the three per-group time-slot entries the curator's oracle expects (09:00, 11:30, 13:00). The harness fails this fixture at the event-count assertion before per-field diff runs, so today this can only be `DISABLED_FIXTURES` ([LlmExtractionRecordedRegressionTest.java:88-95](src/test/java/com/example/app/llm/LlmExtractionRecordedRegressionTest.java)). The triage [triage.md:19](context/changes/llm-fixture-set-expansion/triage.md) ties unblocking it to this change.

The prompt rule needs to be precise about "one event per slot, no umbrella consolidation" without overshooting into "always split everything". A working shape: *"Each calendar event is one entry. If the same announcement lists multiple start times for different groups on the same date (e.g., 'Wrzosy 9:00, Eukaliptusy 11:30, Żonkile 13:00'), emit one event per group/time slot. Do NOT also emit a separate consolidated entry covering the whole day — that produces a duplicate."*

### Output-language drift

The model returns mixed languages today. Of 9 measurable new fixtures, **fixture 05** is the clearest English translation:

- Source: Polish poster announcing `"Pamiątkowe zdjęcia grupowe do dyplomów"`
- Curator oracle: `"Pamiątkowe zdjęcia grupowe do dyplomów"` (Polish, source-passthrough)
- Model output: `"Group photos for diplomas and end of preschool year 2025/26"` (translated)

The prompt has never specified output language; the project has never explicitly decided one. The implicit oracle is "source-language passthrough" — all `expected.json` files use the source's Polish wording, including diacritics (the diff path uses `Normalizer.normalize(_, NFC)` to keep `ą/ę/ś` byte-equal, [research.md:301-306 of archived harness change](context/archive/2026-06-10-llm-extraction-regression-harness/research.md)). A one-line directive — *"Return all string fields (title, requirements, notes) in Polish; do not translate"* — closes the gap. Spring AI's idiomatic shape: `.param("language", "Polish")` in the system template, since there is no first-class `Locale` API on `ChatClient`.

### Time / Clock plumbing (needs to be added)

There is **no `Clock` bean** anywhere in `src/main/`. The only direct date call today is [AppController.java:42](src/main/java/com/example/app/web/AppController.java): `eventRepository.findUpcomingByUser(user, LocalDate.now())` — that uses the system clock implicitly. The prompt-fix change needs:

1. A `@Bean Clock clock() { return Clock.systemDefaultZone(); }` in `AppApplication` (or a new `TimeConfig`).
2. Constructor-inject `Clock` into `OpenRouterLlmVisionClient`, call `LocalDate.now(clock)` per `.extract(...)` invocation.
3. In tests, override the bean with `Clock.fixed(...)` so prompts are deterministic. The recorded regression test mocks `ChatModel` — `Clock` would only matter for tests that assert on the *outgoing* prompt text (currently none exist; see *Open Questions*).

The same `Clock` should also be propagated to `AppController` opportunistically (it is the only other date call), so the project does not ship one `Clock` for LLM and another implicit one for queries.

### Spring AI ChatClient — the API shape to target

From Spring AI 1.0+ reference docs (`docs.spring.io/spring-ai/reference/api/`):

```java
String today = LocalDate.now(clock).toString(); // e.g. "2026-06-12"
String raw = chatClient.prompt()
    .system(sp -> sp.text(SYSTEM_TEMPLATE)
                    .param("today", today)
                    .param("language", "Polish"))
    .user(u -> u.text(USER_HINT).media(mimeType, resource))
    .call()
    .content();
```

Key points (citations: `.../api/chatclient.html`, `.../api/multimodality.html`, `.../api/prompt.html`):
- `.text(template)` accepts `{var}` placeholders; `.param(key, value)` binds them per call. Default templates can be pre-bound via `ChatClient.Builder.defaultSystem(template)` — relevant if S-05 later wants to share a template across multiple call sites.
- `.media(mimeType, Resource)` is documented as **"applicable only for user input messages and does not hold significance for system messages"** — so the image must stay on the user message; the system message carries pure text.
- A short user `.text("...")` alongside `.media(...)` is recommended by Spring AI examples (`"Explain what you see"`) — many providers reject user messages that are media-only.
- Structured JSON output via `OpenAiChatOptions.responseFormat(JSON_SCHEMA, schema)` is supported and orthogonal to vision input; no docs caveat about combining the two. *Not required for this change* — F-01 explicitly deferred structured output binding to S-05 — but worth flagging as a future tightening of the greedy `[.*]` regex at `OpenRouterLlmVisionClient.java:96-97`.

### Harness contract — what the prompt fix obligates

From `context/archive/2026-06-10-llm-extraction-regression-harness/`:

- **Recorded test** ([LlmExtractionRecordedRegressionTest.java](src/test/java/com/example/app/llm/LlmExtractionRecordedRegressionTest.java)) mocks `ChatModel` with `recorded-response.json`, runs through the production parser, asserts divergence multiset equals `KNOWN_DIVERGENCES[fixtureId]`. **A clean model improvement on a documented-divergence fixture surfaces as a "missing divergence" failure** ([plan-review-phase2.md F1, archived harness change](context/archive/2026-06-10-llm-extraction-regression-harness/reviews/plan-review-phase2.md:36-38)) — that's the load-bearing signal the prompt fix worked.
- The recorded test asserts `actual.size() == expected.size()` *before* per-field diff ([plan.md:527, archived harness change](context/archive/2026-06-10-llm-extraction-regression-harness/plan.md)). Fixture 07's umbrella entry blows this assertion; the only escape today is `DISABLED_FIXTURES`. The prompt fix should re-enable 07 by removing the umbrella behaviour, then re-recording.
- **Re-recording is required, not just a list edit.** The on-disk `recorded-response.json` files were captured with the buggy prompt; if `date-mismatch` rows are pruned without re-recording, the mock still replays the wrong year and the test fails with "extra divergence". The workflow ([test-plan.md:146-170](context/foundation/test-plan.md)):
  ```bash
  git rm src/test/resources/llm/fixtures/<id>/recorded-response.json \
         src/test/resources/llm/fixtures/<id>/recorded-meta.json
  OGARNIACZ_LIVE_SMOKE=true OGARNIACZ_RECORD_FIXTURES=true \
    OPENROUTER_API_KEY=sk-or-... \
    ./gradlew test --tests com.example.app.llm.LlmExtractionLiveRegressionTest --info
  ```
  Cost: ~$0.001 per fixture call, ≤55s wall-clock per fixture (BUDGET_MS). For the affected set (02-08, 10), expect ~$0.008 and 6-8 minutes. The atomic-write + never-overwrite contract makes SIGINT mid-run safe.
- **A papercut to expect**: recording-mode atomic-write produces `0600`-mode files on macOS ([finding #2, archived harness change.md:62-80](context/archive/2026-06-10-llm-extraction-regression-harness/change.md)). `chmod 644 src/test/resources/llm/fixtures/*/recorded-*.json` after recording, before `git add`.
- **The KNOWN_DIVERGENCES duplication** ([finding #4, archived harness change.md:101-128](context/archive/2026-06-10-llm-extraction-regression-harness/change.md)) means the prompt-fix change must touch both maps. Today the live map ([LlmExtractionLiveRegressionTest.java:67-70](src/test/java/com/example/app/llm/LlmExtractionLiveRegressionTest.java)) only has `01-sample` — it never caught up to the 02-10 expansion. After the prompt fix, the live map should match the recorded map (whatever residual divergences survive, in both files).

### Affected `KNOWN_DIVERGENCES` rows the prompt fix will prune

From [LlmExtractionRecordedRegressionTest.java:49-79](src/test/java/com/example/app/llm/LlmExtractionRecordedRegressionTest.java), **15 of 22 entries are date-mismatch** and should be removed after re-recording:

- `03-marzec-bez-godzin`: all 5 rows (date-mismatch)
- `04-marzec-wazne-daty`: all 5 rows (date-mismatch)
- `05-zdjecia-dyplomowe`: 1 row (date-mismatch)
- `06-luty-wazne-daty`: all 5 rows (date-mismatch)

Rows that are **unrelated to year-resolution** and likely stay (or get re-evaluated after re-record):
- `01-sample`, `02-wielkanoc-sniadanie`, `08-grzybobranie`: `requirements-norm-mismatch` (model condenses politeness markers in the requirements text)
- `02-wielkanoc-sniadanie` second entry: also a known group-label-in-requirements bug ([triage.md:65-70](context/changes/llm-fixture-set-expansion/triage.md)) — this MAY get worse or better with the Polish-output instruction; re-record to find out
- `10-warsztaty-www`: `time-mismatch` (model misses the embedded `"Wyjście o g.9:00"` time)

Estimated post-fix accuracy (from triage.md tally): if the year fix resolves all date-mismatch entries and umbrella behaviour resolves fixture 07, clean matches jump from 1/9 (~11%) to ~5/9 (~62.5%). Still below the 80% PRD target; closing the rest needs a second pass on requirements-field semantics and embedded-time extraction.

## Code References

- `src/main/java/com/example/app/llm/OpenRouterLlmVisionClient.java:26-33` — the SYSTEM_PROMPT string to edit; no current-date, no language, no umbrella rule.
- `src/main/java/com/example/app/llm/OpenRouterLlmVisionClient.java:59-62` — the `.user(u -> u.text(...).media(...))` call that must be split into `.system(...)` + `.user(...)` to use Spring AI's prompt-template variable interpolation.
- `src/main/java/com/example/app/llm/OpenRouterLlmVisionClient.java:39-46` — constructor; add `Clock` parameter here.
- `src/main/java/com/example/app/llm/OpenRouterLlmVisionClient.java:96-97` — the `JSON_ARRAY = Pattern.compile("\\[.*\\]", Pattern.DOTALL)` regex; not part of this change but worth knowing if the prompt change tightens "return ONLY a JSON array".
- `src/main/java/com/example/app/llm/LlmExtractionResult.java:12-18` — `ProposedEvent` record; the JSON schema target.
- `src/main/java/com/example/app/AppApplication.java` — likely host for the new `@Bean Clock clock()` declaration.
- `src/main/java/com/example/app/web/AppController.java:42` — current `LocalDate.now()` without `Clock`; opportunistically migrate to `LocalDate.now(clock)` so the project has one source of "now".
- `src/main/resources/application.properties:29-33` — OpenRouter base URL, model (`google/gemini-2.5-flash`), temperature (0.2). The model is vision-capable Flash tier; year-resolution behaviour is model-specific, so a model change in S-05 may require a prompt re-tune.
- `src/test/java/com/example/app/llm/LlmExtractionRecordedRegressionTest.java:49-79` — `KNOWN_DIVERGENCES` map; prune 15 date-mismatch rows here after re-recording.
- `src/test/java/com/example/app/llm/LlmExtractionRecordedRegressionTest.java:88-95` — `DISABLED_FIXTURES`; remove `07-czerwiec-wazne-daty` after umbrella behaviour goes away, then re-record.
- `src/test/java/com/example/app/llm/LlmExtractionLiveRegressionTest.java:67-70` — duplicate `KNOWN_DIVERGENCES` map; keep in sync with the recorded test in the same change.
- `src/test/java/com/example/app/llm/LlmTestFixtures.java` — the `diff(...)` helper; currently does not compare `notes`. Out of scope but worth knowing.
- `src/test/resources/llm/fixtures/<id>/expected.json` — curator oracle (Polish source-passthrough, year 2026 for the affected fixtures).
- `src/test/resources/llm/fixtures/<id>/recorded-response.json` — to be re-recorded for fixtures 02-08 and 10 after the prompt change lands.

## Architecture Insights

**Spring AI ChatClient pattern for this fix.** The full call shape:

```java
private static final String SYSTEM_TEMPLATE = """
    You are extracting kindergarten-announcement events from an image.

    Today is {today}.

    Return ONLY a JSON array (no prose, no markdown fences). Each item:
      { "date": "YYYY-MM-DD", "time": "HH:MM" | null, "title": "string",
        "requirements": "string" | null, "notes": "string" | null }

    Date resolution: if the announcement does not state a year, choose the year
    that places the event within the current Polish academic year (September
    through June) containing today. Dates in months Sep-Dec use the first year
    of that academic year; dates in months Jan-Aug use the second year.

    Multi-group events: if the same date carries multiple per-group time slots
    (e.g. "Wrzosy 9:00, Eukaliptusy 11:30"), emit one entry per group/time slot.
    Do NOT also emit a separate consolidating entry covering the whole day.

    Language: return all string fields ({title}, {requirements}, {notes}) in
    {language}; do not translate.

    Use null when a field is not present. If no events are visible, return [].
    """;

private static final String USER_HINT = "Extract events from the attached image.";

// ... in extract(...):
String today = LocalDate.now(clock).toString();
String raw = chatClient.prompt()
    .system(sp -> sp.text(SYSTEM_TEMPLATE)
                    .param("today", today)
                    .param("language", "Polish"))
    .user(u -> u.text(USER_HINT).media(mimeType, resource))
    .call()
    .content();
```

**Why split system + user.** Spring AI docs explicitly state `.media(...)` is meaningful only on user messages — so the image stays on user. The textual rules belong on system because (a) `system` is the canonical home for behaviour rules in chat models, (b) Spring AI's `.param(...)` interpolation is documented on system templates, and (c) provider-side caching often differentiates system text from per-call user text.

**Why a `Clock` bean.** Without a `Clock`, the prompt's `{today}` is whatever `LocalDate.now()` returns in production code — untestable, non-deterministic, and a future-Ludmiła trap if S-05 ever wants to record prompts deterministically. `@Bean Clock clock() { return Clock.systemDefaultZone(); }` + constructor injection is the conventional Spring shape.

**Why source-language passthrough, not always-Polish.** The Polish-output directive should be a parameter, not a literal, because the project will eventually accept non-Polish announcements (the spouse persona, the secondary persona "other parents" in PRD §User). Wiring it as `.param("language", ...)` lets a future change branch on locale (e.g., via `LocaleResolver`) without re-touching the prompt template.

**Two-phase rollout.** This change is naturally two phases per the test-plan §3 phased rollout pattern: **(1)** prompt change + Clock bean + system+user split + `application-test.yml` fixed clock for deterministic tests; **(2)** re-record fixtures 02-08 and 10, sync the two `KNOWN_DIVERGENCES` maps, remove `07-czerwiec-wazne-daty` from `DISABLED_FIXTURES`. Phase 1 is TDD-able (the prompt-construction test asserts on the outgoing system message text given a fixed clock and language); phase 2 is `/10x-implement` territory (recording is a side-effecting operation; verification is "test passes").

## Historical Context (from prior changes)

- [`context/archive/2026-06-01-openrouter-llm-client-wired/plan-brief.md:48`](context/archive/2026-06-01-openrouter-llm-client-wired/plan-brief.md) — *"Prompt engineering beyond a 'good-enough' extraction prompt (S-05 owns iteration)"*. The current prompt was always known to be a stub.
- [`context/archive/2026-06-01-openrouter-llm-client-wired/plan.md:42`](context/archive/2026-06-01-openrouter-llm-client-wired/plan.md) — *"No prompt engineering beyond a 'good-enough' extraction prompt — the prompt's job in F-01 is to produce something parseable by the smoke test, not to clear S-05's 80% accuracy bar. Refinement happens iteratively inside S-05."* This is S-05's first refinement.
- [`context/archive/2026-06-01-openrouter-llm-client-wired/plan.md:43`](context/archive/2026-06-01-openrouter-llm-client-wired/plan.md) — *"No structured output beans / function-calling / `BeanOutputConverter` integration… When S-05 lands, structured-output binding (or a `ResponseFormat`-style JSON-mode config) gets added there."* Structured output is a *later* tightening; this change does not need it, but the regex parse path becomes slightly more brittle the moment the prompt grows in size.
- [`context/archive/2026-06-01-openrouter-llm-client-wired/change.md:16`](context/archive/2026-06-01-openrouter-llm-client-wired/change.md) — the model selection rationale: `gemini-2.5-flash` was picked for vision + Flash-tier latency + cost (`~$1.55 per 1000 announcements`), not Polish fluency. Polish was only mentioned in passing as "Polish text + `LocalDate` deserialized correctly".
- [`context/archive/2026-06-10-llm-extraction-regression-harness/plan.md:473-478`](context/archive/2026-06-10-llm-extraction-regression-harness/plan.md) — the harness contract: divergence-multiset equality. This is the test the prompt fix runs against.
- [`context/archive/2026-06-10-llm-extraction-regression-harness/reviews/plan-review-phase2.md:36-38`](context/archive/2026-06-10-llm-extraction-regression-harness/reviews/plan-review-phase2.md) — *"a clean model improvement on a documented-divergence fixture surfaces as a 'missing divergence' failure"*. The load-bearing signal of this prompt fix.
- [`context/archive/2026-06-10-llm-extraction-regression-harness/change.md:101-128`](context/archive/2026-06-10-llm-extraction-regression-harness/change.md) — `KNOWN_DIVERGENCES` is duplicated across recorded + live test classes. Both must be edited in this change.
- [`context/changes/llm-fixture-set-expansion/triage.md:39-49`](context/changes/llm-fixture-set-expansion/triage.md) — *"Dominant cause: year-resolution failure… This single bug accounts for 15 of 22 documented divergences (68%)."* — the framing this change inherits.
- [`context/changes/llm-fixture-set-expansion/triage.md:19`](context/changes/llm-fixture-set-expansion/triage.md) — *"the same change that will resolve the year-resolution date-mismatch entries above is the right place to also tighten the don't-emit-umbrella behaviour."* — bundles fixture 07.
- [`context/foundation/lessons.md` §"When lifting a test helper, sweep sibling `@BeforeEach` / setup blocks for duplication too"](context/foundation/lessons.md) — applies here: the two `KNOWN_DIVERGENCES` maps are a known duplication; editing one without the other is the exact failure mode the lesson warns about.

## Related Research

- [`context/archive/2026-06-01-openrouter-llm-client-wired/research.md`](context/archive/2026-06-01-openrouter-llm-client-wired/research.md) — original Spring AI OpenAI client wiring research; recommends `.media(mimeType, Resource)` for image input, confirms vision support via Spring AI's OpenAI integration against OpenRouter's `/api/v1/` endpoint.
- [`context/archive/2026-06-10-llm-extraction-regression-harness/research.md`](context/archive/2026-06-10-llm-extraction-regression-harness/research.md) — fixture diff machinery, Polish diacritic NFC normalization, recording-mode atomic-write protocol.
- [`context/changes/llm-fixture-set-expansion/triage.md`](context/changes/llm-fixture-set-expansion/triage.md) — the 9-fixture batch that surfaced the year-resolution bug at scale; tally and follow-ups list.

## Open Questions

1. **Academic-year rule vs closest-to-today rule.** Two rule shapes produce the same answer on every fixture seen, but the academic-year rule is semantically precise (Polish kindergarten context) while closest-to-today is robust to unanticipated edge cases (summer programmes, multi-year reminders). Recommend the planning phase pick one; the *change*'s success criteria can be "this rule yields the curator's year on all measurable fixtures", letting the rule wording flex.
2. **Polish vs source-language passthrough.** The fix wires `.param("language", "Polish")` because Ludmiła's source corpus is 100% Polish. For the secondary persona (other parents — see PRD §User) the rule could become "respond in the source's primary language" or "respond in {locale}". The change should at minimum make `language` a `.param(...)` value, not a literal, so the future generalization is free.
3. **Embedded-time extraction.** Fixture 10 has `"Wyjście o g.9:00"` in body text but the model returns `time=null`. The triage lists this as a follow-up (`time-mismatch`). The prompt fix could add a rule (*"if the announcement contains a time of day in the body text, set the time field"*), but doing so without re-evaluating fixture 02 and 07 (which DO have explicit per-group time slots) might cause regressions. Recommend leaving this out of the current change and tracking it as a separate refinement.
4. **Prompt-construction unit test.** Today no test asserts on the *outgoing* prompt text — `LlmVisionClientTest.java` mocks the `ChatModel` and checks parsing/exception paths only. A new test that captures the prompt sent to the model (via Spring AI's `ChatClient` interception, or by capturing the `Prompt` object passed to a mock `ChatModel`) is the right TDD seed for phase 1 of this change. Worth surfacing the fixed `Clock` use here so the assertion is `assertThat(systemMessage).contains("Today is 2026-06-12.")`.
5. **Fixture 02 second `requirements-norm-mismatch`.** The triage attributes one of fixture 02's two `requirements-norm-mismatch` entries to the "group label in requirements field" bug. The Polish-output directive may incidentally improve or worsen this. Re-recording will reveal which.
6. **Live test `KNOWN_DIVERGENCES` lag.** The live test map currently only carries `01-sample` ([LlmExtractionLiveRegressionTest.java:67-70](src/test/java/com/example/app/llm/LlmExtractionLiveRegressionTest.java)) — it never caught up to the 02-10 expansion. The prompt-fix change is the natural place to sync the two maps to the post-fix divergence set, in lockstep.
