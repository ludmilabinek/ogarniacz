<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: LLM Prompt Year Resolution

- **Plan**: `context/changes/llm-prompt-year-resolution/plan.md`
- **Scope**: Full plan (Phase 1 + Phase 2)
- **Date**: 2026-06-14
- **Verdict**: NEEDS ATTENTION
- **Findings**: 0 critical · 3 warnings · 3 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | WARNING |
| Scope Discipline | PASS |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | WARNING |
| Success Criteria | PASS |

## Findings

### F1 — `chatClient` bean returns null when Builder absent

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: `src/main/java/com/example/app/AppApplication.java:25-29`
- **Detail**: The bean signature is `ChatClient chatClient(ObjectProvider<ChatClient.Builder> b)` with body `b != null ? b.build() : null`. The intent (per Phase 1 commit body) is "ObjectProvider-guarded so @DataJpaTest slices stay loadable", but the implementation half-finishes it: `ObjectProvider.getIfAvailable()` is the idiomatic way to express "optional builder"; null-returning a bean means downstream injection of `ChatClient` silently NPEs at first call rather than failing fast at context load. `@DataJpaTest` is safe today only because `OpenRouterLlmVisionClient` isn't pulled into that slice, but any future `@WebMvcTest` that imports the LLM component will hit a null deref on the first `.extract()` call instead of a clear "missing bean" error.
- **Fix A ⭐ Recommended**: Use `@ConditionalOnBean(ChatClient.Builder.class)` on the bean method and drop the null branch.
  - Strength: Spring just won't register the bean when Builder is absent; callers get a clear `NoSuchBeanDefinitionException` at context load (fail-fast) instead of an NPE at call time.
  - Tradeoff: None significant — the slice-test path still works (the bean is absent, but so is everything that injects it).
  - Confidence: HIGH — idiomatic Spring Boot 4 pattern, used widely.
  - Blind spot: None significant.
- **Fix B**: Use `builder.getIfAvailable()` and let null propagate via JSR-305 annotations.
  - Strength: Closer to current shape; one-line change.
  - Tradeoff: Doesn't actually fix the silent-NPE-at-call-time problem; just moves the null check syntactically.
  - Confidence: MEDIUM — works but doesn't address the real failure mode.
  - Blind spot: Future test slices still NPE.
- **Decision**: REVERTED — Fix A was applied then reverted. `@ConditionalOnBean(ChatClient.Builder.class)` is evaluated during bean-definition processing, BEFORE Spring AI's autoconfig registers `ChatClient.Builder` — so the condition is false even in `@SpringBootTest` contexts where Builder eventually becomes available. The fix broke all 46 `@SpringBootTest` tests (`NoSuchBeanDefinitionException: ChatClient`). The review's "HIGH confidence — idiomatic Spring Boot 4" claim was wrong about this ordering for Spring AI's autoconfig. ObjectProvider+null restored; tracked as follow-up `llm-chatclient-fail-fast` (`context/changes/llm-chatclient-fail-fast/change.md`) with the constraint set + candidate approaches captured.

### F2 — `KNOWN_DIVERGENCES` duplicated byte-for-byte across two test classes

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Pattern Consistency
- **Location**: `LlmExtractionRecordedRegressionTest.java:61-89` + `LlmExtractionLiveRegressionTest.java:69-97`
- **Detail**: Both `KNOWN_DIVERGENCES` maps are now byte-identical 8-entry blocks plus a duplicated `private record KnownDivergence(String, String, String)` inside each class. Every divergence resync (this change just did one) requires editing both maps in lockstep — exactly the failure mode `lessons.md §"sweep sibling setup blocks"` names. The lesson is cited in the live test's new `DISABLED_FIXTURES` javadoc (line 100-103), which means it was front of mind during this change but only applied to the 1-entry set. The 8-entry surface is the one most likely to drift first under future re-records.
- **Fix**: Lift `KnownDivergence` (the record) and a `KNOWN_DIVERGENCES` constant into `LlmTestFixtures` (or a sibling `LlmDivergenceCatalog`); have both regression tests reference the lifted symbol. Mechanically trivial — Edit two test files to delete the private record + map; Edit `LlmTestFixtures` to add a public constant.
- **Decision**: FIXED — lifted to sibling `LlmDivergenceCatalog` (kept separate from `LlmTestFixtures` to preserve concept boundary: divergence catalog = model-quality expectations, fixtures = input data); both regression tests now reference the single source of truth.

### F3 — Bean placement on `@SpringBootApplication` will need rehoming when a third bean joins

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: `src/main/java/com/example/app/AppApplication.java:20-29`
- **Detail**: Plan §96 explicitly accepted "no separate @Configuration class is needed at this size" — plan-aligned today. Flagging as the cheap warning to revisit when `AppApplication` picks up a third unrelated `@Bean` (cache, mailer, scheduler), at which point lifting `Clock` + `ChatClient` into `LlmConfig` (alongside any future Spring AI tuning) is worth a small change. Not actionable now; recording for the next adjacent change.
- **Fix**: No fix now. Track for the next change that adds a third bean.
- **Decision**: SKIPPED — acknowledged; revisit when a third unrelated @Bean joins AppApplication.

### F4 — `DISABLED_FIXTURES` (recorded) swap added 03 in addition to removing 07

- **Severity**: 📝 OBSERVATION
- **Impact**: 🏃 LOW
- **Dimension**: Plan Adherence
- **Location**: `LlmExtractionRecordedRegressionTest.java:98-106`
- **Detail**: Plan §230 said "the `Set.of(...)` at line 88-95 no longer includes `07-czerwiec-wazne-daty`"; actual implementation removed 07 AND added 03 (compound-title collapse). Mid-flight adaptation approved by operator at the `AskUserQuestion` gate, documented in the source Javadoc (lines 99-105) AND in the Phase 2 commit body. Three discrete follow-ups landed in the commit (incl. `llm-prompt-no-compound-titles`). Calling out for transparency, not action.
- **Fix**: None — already tracked via follow-up `llm-prompt-no-compound-titles`.
- **Decision**: ACKNOWLEDGED — no action; mid-flight scope expansion already documented in commit body + source Javadoc, fix tracked in follow-up.

### F5 — `KNOWN_DIVERGENCES` retains 7 of 15 date-mismatch rows as residuals

- **Severity**: 📝 OBSERVATION
- **Impact**: 🏃 LOW
- **Dimension**: Plan Adherence
- **Location**: `LlmExtractionRecordedRegressionTest.java:75-79, 80-87`
- **Detail**: Plan §241-247 set up a discover-then-update workflow and accepted "non-empty entry here is a residual the prompt fix didn't close — note in the commit message". Fixture 06 (5 rows) + fixture 07 (2 of its 7 rows) carry date-mismatch as documented residuals. Year-rule miss tracked under follow-up `llm-oracle-production-semantic-alignment`. Recording the count for visibility.
- **Fix**: None — already tracked via follow-up `llm-oracle-production-semantic-alignment`.
- **Decision**: ACKNOWLEDGED — residual count visible; year-rule miss tracked under existing follow-up.

### F6 — Fixture 10 `time-mismatch` row absent from maps (deviates from plan §244 prediction)

- **Severity**: 📝 OBSERVATION
- **Impact**: 🏃 LOW
- **Dimension**: Plan Adherence
- **Location**: Both `KNOWN_DIVERGENCES` maps (no `10-warsztaty-www` entry)
- **Detail**: Plan §244 predicted "Fixture 10 `time-mismatch` stays in `KNOWN_DIVERGENCES` after re-record" because the embedded-time extraction follow-up was explicitly NOT in scope. Discovery showed the time-mismatch healed incidentally — re-recorded fixture 10 has `"time": "09:00"` matching expected `"time": "09:00"`. Discover-then-update workflow correctly surfaced the empty divergence set. The plan's prediction was conservative; actual model behaviour was better. Not actionable.
- **Fix**: None.
- **Decision**: ACKNOWLEDGED — plan prediction was conservative; model behaviour exceeded expectations.
