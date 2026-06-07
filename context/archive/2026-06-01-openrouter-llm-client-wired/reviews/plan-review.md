<!-- PLAN-REVIEW-REPORT -->
# Plan Review: OpenRouter LLM Client Wired (F-01)

- **Plan**: `context/changes/openrouter-llm-client-wired/plan.md`
- **Mode**: Deep
- **Date**: 2026-06-04
- **Verdict**: REVISE
- **Findings**: 1 critical, 2 warnings, 2 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| End-State Alignment | PASS |
| Lean Execution | PASS |
| Architectural Fitness | WARNING |
| Blind Spots | FAIL |
| Plan Completeness | WARNING |

## Grounding

4/4 existing paths ✓ (`build.gradle`, `application.properties`, `AppApplication.java`, `AppApplicationTests.java`). New `llm/` directories not yet created (expected). No `docs/reference/contract-surfaces.md` in project — surface check skipped. brief↔plan ✓. Progress↔Phase mechanical contract ✓ (1.1–1.6, 2.1–2.5, 3.1–3.6 all map to Success Criteria bullets; one `## Progress` block at the bottom; Phase blocks use plain `-` bullets).

Authoritative external reference for compatibility findings: Spring AI getting-started + upgrade-notes pages via Context7 (`/websites/spring_io_spring-ai_reference`, `/websites/spring_io_spring-ai_reference_2_0-snapshot`), fetched 2026-06-04.

## Findings

### F1 — Spring AI 1.0.x is incompatible with Spring Boot 4

- **Severity**: ❌ CRITICAL
- **Impact**: 🔬 HIGH — wrong dependency line; Phase 1 stops cold
- **Dimension**: Blind Spots
- **Location**: Phase 1 Change #1 (`build.gradle`), Phase 2 Change #4 (`OpenRouterLlmVisionClient` snippet — `OpenAiChatOptions`)
- **Detail**: Spring AI's own docs state Spring AI 1.0.x is compatible only with Spring Boot 3.4.x / 3.5.x; Spring AI 2.0.x supports Spring Boot 4.0.x / 4.1.x. This project pins Boot 4.0.6 (`build.gradle:3`). The plan pins `spring-ai-bom:1.0.3`, which targets Boot 3.5.x and will pull conflicting transitive autoconfiguration / break on Boot 4's reorganised packages. Spring AI 2.0 has no GA yet — latest available milestone is `2.0.0-M6`. Spring AI 2.0.0-M5+ also renamed `OpenAiChatOptions` → `OpenAiSdkChatOptions` (the OpenAI Java SDK became canonical), which cascades into the snippet at plan.md:197.
- **Fix A ⭐ Recommended**: Pin `spring-ai-bom:2.0.0-M6` and swap the options class name to `OpenAiSdkChatOptions`
  - Strength: Matches Spring's own compatibility matrix; the only line that actually works on Boot 4 today. The plan's own "Open Risks" warning about milestones inverts here — a milestone IS the right choice when Boot 4 has no GA Spring AI line yet.
  - Tradeoff: 2.0.0-M6 is a milestone; Spring may rename / reshape APIs before GA. Lock the version explicitly; base-url, multimodal Media, BOM management are stable across the 2.0 milestone line.
  - Confidence: HIGH — Spring AI's getting-started page is unambiguous; Context7 returned the same answer from both 1.0 and 2.0 docs.
  - Blind spot: Haven't validated whether `2.0.0-M6` is on Maven Central yet (implementer should `./gradlew dependencyInsight` immediately after the BOM bump). If absent, fall back to `2.0.0-M3`.
- **Fix B**: Downgrade the project to Spring Boot 3.5.x and keep Spring AI 1.0.3
  - Strength: Keeps Spring AI on a GA line.
  - Tradeoff: Reverses the bootstrap choice; the auth slice S-01 was authored against Boot 4 APIs (`webmvc-test` imports). Reverting Boot is a slice-sized change, not a one-line edit; conflicts with `tech-stack.md` / `deploy-plan.md` having Boot 4.0.6 as the operational target.
  - Confidence: MEDIUM — would work but expensive.
  - Blind spot: Could regress already-shipped Boot-4-specific code paths.
- **Decision**: FIXED (Fix differently) — pin `spring-ai-bom:2.0.0-M6` and add `https://repo.spring.io/milestone` to `repositories { }` (Fix A's core), but route all F-01 calls through the portable `ChatClient` fluent API with model/temperature in `application.properties`. No `OpenAiChatOptions` / `OpenAiSdkChatOptions` reference in F-01 → milestone-rename risk between M5/M6 is sidestepped. The provider-specific options class only gets named when S-05 (or later) genuinely needs a per-call override.

### F2 — Phase 1 Change #4 is a comment-only entry that should fold into Change #2

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 1 Change #4 ("application.properties — local-dev override note")
- **Detail**: Change #4 adds one comment line above the api-key entry that Change #2 already writes. As a standalone "Change Required" it's noise: the implementer has to read four sub-headings to apply two edits to the same file. Phases land cleanly when each #Change touches a distinct file or a distinct semantic unit; a comment line is neither.
- **Fix**: Merge Change #4 into Change #2's Contract by appending one sentence: "Place a single line `# Smoke test reads from OPENROUTER_API_KEY …` directly above the `spring.ai.openai.api-key` line." Delete the standalone Change #4 sub-section. Phase-1 Progress numbering is unaffected (1.4/1.5/1.6 were not comment-only).
- **Decision**: FIXED (Fix in plan) — comment line folded into Change #2's Contract; standalone Change #4 removed.

### F3 — Overview's "@Disabled" wording contradicts the actual chosen gate

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Overview (`plan.md:5`) — "manual `@Disabled` JUnit smoke test"
- **Detail**: The Overview describes the smoke test as `@Disabled`, but every other mention (Desired End State, Phase 3 Change #2 Contract, Progress 3.1 "noisy log line 'skipped'") uses `@EnabledIfEnvironmentVariable(named = "OPENROUTER_API_KEY", matches = ".+")`. The two are NOT equivalent — `@Disabled` makes the test invisible to a key-equipped operator unless they edit source; `@EnabledIfEnvironmentVariable` is self-enabling when the env var is exported. The body uses the right one; the Overview just hasn't caught up.
- **Fix**: In Overview, replace "manual `@Disabled` JUnit smoke test" with "env-var-gated JUnit smoke test (`@EnabledIfEnvironmentVariable`)".
- **Decision**: FIXED (Fix differently) — also switched the trigger from `OPENROUTER_API_KEY` (a generic "any key in shell" signal) to a dedicated `OGARNIACZ_LIVE_SMOKE=true` flag. Decouples "I have a key" from "I want to run THIS project's live smoke right now". Cascaded the new gate condition through Overview, Desired End State, Phase 3 Change #2 Contract, Phase 3 Success Criteria, Progress 3.1–3.2, the runbook in `plan.md`, and the runbook mirror in `change.md`.

### F4 — Image-size guard deliberately deferred but the LlmVisionClient contract leaks raw byte[]

- **Severity**: 💡 OBSERVATION
- **Impact**: 🔎 MEDIUM — real tradeoff between F-01 scope and S-05 contract
- **Dimension**: Blind Spots
- **Location**: Phase 2 Change #1 (`LlmVisionClient` interface)
- **Detail**: Phone photos are routinely 2–5 MB JPEG / 5–15 MP; OpenRouter / OpenAI Vision endpoints have practical size ceilings. The plan correctly defers downscale to S-05 (Performance Considerations, plan.md:327). But `LlmVisionClient` accepts raw `byte[]` with no documented size precondition — S-05 will hand in a `MultipartFile.getBytes()` of whatever the user uploaded. A 15 MB image will likely 4xx at OpenRouter and surface as `kind=PROVIDER_ERROR`, looking like a flaky vendor rather than an over-large upload.
- **Fix**: Add a one-sentence javadoc on `LlmVisionClient` specifying the contract: "Caller is responsible for downscaling. Images > ~10 MB after base64 encoding may be rejected by the provider." Add NO runtime check in F-01 (S-05 owns the validation); document the boundary so S-05's contract author knows where to put it.
- **Decision**: FIXED (Fix differently) — two correlated edits applied: (a) softened the javadoc to be model-dependent rather than naming a hard "10 MB" ceiling that depends on provider/model; (b) added a `String providerMessage` field + factory-method signature to `LlmExtractionException` so the provider's response body survives the wrap. F-01 still surfaces a single `kind=PROVIDER_ERROR`, but S-05 (or later) can split 413/400-too-large from 5xx-vendor-blip using `httpStatus + providerMessage` without a signature retrofit. Updated Phase 2 Change #1 (javadoc), Change #3 (exception fields + factory signature), Change #4 (impl snippet captures `getResponseBodyAsString()`), and Critical Implementation Details / Error envelope.

### F5 — RestClientCustomizer applies globally; document or scope it

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — practical impact minimal today; cheap to future-proof
- **Dimension**: Architectural Fitness
- **Location**: Phase 1 Change #3 (`OpenRouterRestClientConfig`)
- **Detail**: `RestClientCustomizer` `@Bean` applies to every autowired `RestClient.Builder` in the context. The plan acknowledges this for timeouts (Critical Implementation Details / "Timing & lifecycle") but the customizer also unconditionally injects `HTTP-Referer` and `X-Title` headers. If a future slice introduces a different outbound RestClient consumer, those calls would carry OpenRouter attribution headers — harmless but misleading in third-party logs.
- **Fix**: No code change in F-01. Add a sentence to the Critical Implementation Details section noting "Scope this customizer via a Spring AI–specific RestClient.Builder qualifier the first time a second RestClient consumer lands." Converts an implicit landmine into an explicit follow-up.
- **Decision**: FIXED (Fix in plan) — appended the follow-up sentence to Critical Implementation Details / "Timing & lifecycle", calling out the header leak explicitly and the qualifier-based fix.
