# OpenRouter LLM Client Wired (F-01) — Plan Brief

> Full plan: `context/changes/openrouter-llm-client-wired/plan.md`

## What & Why

Close roadmap foundation **F-01**: put a callable, OpenRouter-backed vision LLM client into the Spring Boot 4.0.6 / Java 21 app so a single call from anywhere inside the application can send a kindergarten-announcement image to a vision model and receive a parseable, structured response within the PRD's 60-second extraction ceiling. F-01 is integration plumbing only — no UI, no DB schema, no production HTTP route. Its acceptance gate is a manually-enabled JUnit smoke test that posts a real sample image to OpenRouter and prints the parsed result. Validating the integration in isolation removes the most likely source of S-05 debugging time *before* the wedge slice commits any UI on top of it.

## Starting Point

Spring Boot 4.0.6 / Java 21 / Gradle scaffold with the S-01 auth slice landed. No Spring AI on the classpath. `application.properties` has actuator + cookie hardening + `ddl-auto=update` (additive-only migrations). The Fly secret name `AI_PROVIDER_API_KEY` is already reserved with placeholder value `pending-provider-selection` (deploy-plan §2.3); F-01 rotates it to a real OpenRouter key as part of the manual gate. Roadmap baseline names `spring-ai-openai-spring-boot-starter` — Context7 confirms this is the **legacy** artifact, renamed to `spring-ai-starter-model-openai` in Spring AI 1.0.0.

## Desired End State

Spring AI 1.0.x's modern OpenAI starter is on the classpath, pointed at `https://openrouter.ai/api/v1`. A thin project-owned `LlmVisionClient` interface (one method: `LlmExtractionResult extract(byte[] image, MimeType mimeType)`) is the only seam S-05 will depend on; `OpenRouterLlmVisionClient` implements it on top of Spring AI's `ChatModel` with a 5 s connect / 55 s read timeout (5 s margin under PRD's 60 s ceiling). `./gradlew test` runs with `ChatModel` mocked at the Spring AI boundary — no real OpenRouter calls in CI. A manually-enabled `LlmVisionSmokeTest` (gated by `@EnabledIfEnvironmentVariable(named="OPENROUTER_API_KEY", matches=".+")`) sends a real PNG sample to `google/gemini-2.0-flash-001` via OpenRouter and prints the parsed `LlmExtractionResult`. After secret rotation (`fly secrets set AI_PROVIDER_API_KEY=…`), the deployed app still boots clean — F-01 introduces zero production code paths that actually call OpenRouter (S-05 does that).

## Key Decisions Made

| Decision | Choice | Why | Source |
| --- | --- | --- | --- |
| LLM gateway | OpenRouter | Model-swap flexibility without code changes — only a `model` string per request. Established by user; foundation-level. | Memory + Roadmap |
| Spring AI artifact | `spring-ai-starter-model-openai` (NOT `spring-ai-openai-spring-boot-starter`); BOM `spring-ai-bom:2.0.0-M6` from the Spring milestones repo | The starter was renamed in 1.0.0; the legacy name no longer resolves. **Spring AI 2.0.x is required for Boot 4 — 1.0.x only supports Boot 3.4/3.5.** 2.0 has no GA yet; pin M6 milestone explicitly and add `https://repo.spring.io/milestone` to `repositories { }`. | Plan (Context7-confirmed) |
| Spring AI surface used in F-01 | Portable `ChatClient` fluent API + properties-driven model/temperature | Avoids naming `OpenAiChatOptions` / `OpenAiSdkChatOptions` (renamed between 2.0 M5 and M6); S-05 introduces a provider-specific options class only if/when it needs per-call model override. | Plan |
| Smoke-test surface | Manually-enabled JUnit `@EnabledIfEnvironmentVariable` test | Lives where future S-05 tests live, no production route to lock down, zero CI cost, no spurious skipped tests on contributor clones with no key. | Plan |
| Default vision model | `google/gemini-2.0-flash-001` | Cheapest + lowest-latency vision model on OpenRouter; leaves the largest slack under the 55 s budget. Per-request swap to claude-3.5-sonnet / gpt-4o-mini is a one-string change. | Plan (user override) |
| Mocking strategy | `@MockitoBean ChatModel` at Spring AI boundary | Zero real API hits on CI; tests survive a future provider swap because they mock the framework type our impl uses, not raw HTTP. | Plan |
| Timeout + retry | 5 s connect / 55 s read, no retry | 5 s margin under PRD's 60 s ceiling for prompt build + parse; no double-spend on retries; failure surfaces as a single typed exception S-05 can map to FR-005's actionable error copy. | Plan (user override on read timeout) |
| Client shape | `LlmVisionClient` interface with one `OpenRouterLlmVisionClient` impl | Insulates S-05 + future callers from Spring AI surface churn; mock at domain boundary in unit tests; honors the swappable-provider property from the project memory. | Plan |
| Secret naming | Local: `OPENROUTER_API_KEY`; prod: `AI_PROVIDER_API_KEY` (Fly secret). Chained `${OPENROUTER_API_KEY:${AI_PROVIDER_API_KEY:placeholder-not-a-real-key}}` placeholder. | Local dev wins when set; prod uses the already-reserved Fly secret name (no `fly.toml` change); placeholder lets boot succeed in environments with neither set (CI, contributor clone). | Plan |
| Observability | INFO-level structured log (model, image bytes, latency, outcome) — NOT prompt body, NOT raw response, NOT key | Deploy-plan §6 stays monitor-only; logging PII from kindergarten notes is wrong, and dumping prompts is noise. | Plan (deploy-plan + PRD) |

## Scope

**In scope:**
- Spring AI BOM 1.0.x + `spring-ai-starter-model-openai` on `build.gradle`
- `application.properties` keys for OpenRouter base-url, chained API-key placeholder, default model, temperature
- `OpenRouterRestClientConfig` (`RestClientCustomizer` `@Bean`) — 5 s/55 s timeouts + OpenRouter attribution headers
- `com.example.app.llm.{LlmVisionClient, OpenRouterLlmVisionClient, LlmExtractionResult, LlmExtractionException}`
- `LlmVisionClientTest` (5 unit tests with `@MockitoBean ChatModel`)
- `src/test/resources/llm/sample-announcement.png` fixture
- `LlmVisionSmokeTest` (manually-enabled live test)
- Operator runbook: secret rotation, model swap, failure interpretation
- Fly secret rotation from placeholder to real key (operator gate)

**Out of scope:**
- S-05's image-upload route, storage decision, review/accept UI, streamed progress
- Prompt engineering beyond a "good-enough" extraction prompt (S-05 owns iteration)
- Structured-output binding / `BeanOutputConverter` / function-calling (S-05 territory)
- Retry / circuit breaker / Resilience4j (PRD 60 s budget shapes failure already)
- Production HTTP endpoint for the LLM call (deferred to S-05)
- Metrics / tracing instrumentation (deploy-plan §6 monitor-only for MVP)
- Image-content moderation / PII redaction
- Fallback model / multi-model orchestration
- CI secret for live OpenRouter call (smoke is operator-run only)

## Architecture / Approach

```
caller (S-05 controller / smoke test)
      │
      ▼
LlmVisionClient.extract(byte[], MimeType)        ← project-owned domain interface
      │
      ▼
OpenRouterLlmVisionClient (@Component)            ← only impl; calls ChatClient fluent API, parses JSON, translates exceptions
      │ uses
      ▼
Spring AI ChatClient (built once from autoconfigured ChatClient.Builder; backed by ChatModel)
      │ uses
      ▼
Spring RestClient (customized: 5s connect / 55s read + HTTP-Referer / X-Title headers)
      │ HTTPS
      ▼
https://openrouter.ai/api/v1/chat/completions    ← Authorization: Bearer <OPENROUTER_API_KEY | AI_PROVIDER_API_KEY>
```

`@MockitoBean ChatModel` swaps the autoconfigured bean in `LlmVisionClientTest` so unit tests run with no network. `LlmVisionSmokeTest` skips itself unless the operator has exported `OPENROUTER_API_KEY` — CI sees it as "skipped", operator sees it as the F-01 acceptance gate.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Dependency + config wiring | Spring AI BOM + starter on classpath under the modern artifact name; `application.properties` carries OpenRouter base-URL + chained API-key fallback + default model; `RestClientCustomizer` bean sets 5 s/55 s timeouts and OpenRouter attribution headers. App boots clean with or without a key. | The starter was renamed in Spring AI 1.0.0 — pinning the legacy name `spring-ai-openai-spring-boot-starter` (as the roadmap baseline text says) would resolve a non-existent artifact. The plan explicitly calls out the new name. |
| 2. Vision client + DTO | `LlmVisionClient` interface; `OpenRouterLlmVisionClient` impl with markdown-fence-stripping JSON parser + exception translation table; `LlmExtractionResult` + `ProposedEvent` records; `LlmExtractionException` (kind enum: TIMEOUT / PROVIDER_ERROR / MALFORMED_RESPONSE). 5 unit tests with `@MockitoBean ChatModel`. | Spring AI 1.0.x's exception types may not match what the catches assume (the framework is young; class renames between minor versions have happened). Plan calls this out in the manual-verification step. |
| 3. Live smoke + secret rotation + runbook | Sample PNG fixture; `LlmVisionSmokeTest` env-gated and printing the parsed result; operator runbook (model swap, secret rotation, failure interpretation) mirrored to `change.md` Notes. Operator rotates Fly secret from placeholder to real key. | The first real OpenRouter call may surface model/slug or header issues invisible in unit tests. The runbook's "common failures" table is the recovery path. Model accuracy is NOT graded here (that's S-05); the smoke validates the bridge, not the wedge. |

**Prerequisites:** S-01 must be merged (it is). The Fly app is deployed (it is). An OpenRouter account + API key exists on the operator's side — if not, `https://openrouter.ai/keys` is a 5-minute signup. No CI changes; no `fly.toml` changes.

**Estimated effort:** ~1–2 evening sessions across 3 phases. Phase 1 is ~30 min of YAML/Gradle. Phase 2 is the most code (~2 h with tests). Phase 3 is ~30 min including the secret rotation.

## Open Risks & Assumptions

- **Spring AI version pin (`2.0.0-M6`, milestone)** — Boot 4 has no GA Spring AI line. The plan pins `2.0.0-M6` from `https://repo.spring.io/milestone`; implementer runs `./gradlew dependencyInsight --dependency spring-ai-bom` immediately after the edit and falls back to `2.0.0-M3` only if M6 is unavailable. Milestone risk is contained because F-01 stays on the portable `ChatClient` surface — the parts most likely to rename between milestones (provider-specific `*ChatOptions` classes) are not referenced.
- **`@MockitoBean` is the Spring Boot 4 replacement for `@MockBean`** — verify it's available on the version pinned in `build.gradle`; if not, fall back to manual `Mockito.mock(ChatModel.class)` + `@TestConfiguration` injection.
- **OpenRouter slug for Gemini 2.0 Flash is `google/gemini-2.0-flash-001`** — verify against `curl https://openrouter.ai/api/v1/models | jq` at implementation time; slug renames have happened.
- **`RestClientCustomizer` applies globally** — F-01 is currently the only `RestClient` consumer; if a future slice adds another (e.g., S-04's iCalendar fetcher won't need it, but a webhook receiver might), isolate via `@Qualifier`-keyed builder.
- **Sample-image fixture (`src/test/resources/llm/sample-announcement.png`)** — synthetic-typed-note acceptable for F-01 if a real photo isn't available; real announcements arrive during S-05 prompt iteration.
- **No CI smoke** — `LlmVisionSmokeTest` is operator-run only; F-01's deploy-plan §F.1 rotation IS the gate. If CI someday needs live integration, that's a separate change with its own secret hygiene story.
- **Markdown-fence JSON stripping is a known vision-model quirk** — implementation strips ` ```json ` / ` ``` ` around responses before `objectMapper.readValue`. If a future model wraps differently (XML, double-fenced), the strip regex needs updating; the malformed-JSON exception kind is the visible signal.

## Success Criteria (Summary)

- `./gradlew test` with no `OPENROUTER_API_KEY` set: green, smoke test "skipped", existing 16 tests + 5 new mock-backed tests pass.
- `./gradlew test` with `OPENROUTER_API_KEY=sk-or-…` exported: green, smoke test finishes ≤ 55 s, prints a parseable `LlmExtractionResult`.
- `fly secrets set AI_PROVIDER_API_KEY='sk-or-…' -a ogarniacz` succeeds; `fly logs` shows clean restart; no F-01-introduced production code calls OpenRouter (only S-05 will).
- Roadmap F-01's two unknowns (`spring-ai-openai-spring-boot-starter` compatibility; default vision model) are answered in the plan + runbook — S-05 can start on top of this without re-litigating either.
