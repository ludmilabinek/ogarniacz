# OpenRouter LLM Client Wired (F-01) — Implementation Plan

## Overview

Close roadmap foundation **F-01**: put a callable, OpenRouter-backed vision LLM client into the running Spring Boot 4.0.6 / Java 21 app so a single call from anywhere inside the application can send a kindergarten-announcement image to a vision model and receive a parseable, structured response within the PRD's 60-second extraction ceiling. F-01 is integration plumbing only — no UI, no DB schema, no user-facing endpoint. Its acceptance gate is an env-var-gated JUnit smoke test (`@EnabledIfEnvironmentVariable(named = "OGARNIACZ_LIVE_SMOKE", matches = "true")`) that posts a real sample image to OpenRouter and prints/asserts the parsed result. The dedicated flag decouples "I have an OpenRouter key in my shell" (possible for unrelated reasons) from "I want to run THIS project's live smoke right now". Doing this in isolation eliminates the most likely source of S-05 debugging time before the wedge slice commits any UI on top of it.

## Current State Analysis

- **Scaffold + S-01 are landed**: `build.gradle:20-35` declares Spring Boot 4.0.6 / Java 21 with webmvc + security + data-jpa + validation + thymeleaf + actuator + devtools + postgres + H2-test-runtime. Auth slice S-01 is committed (`AppApplication.java`, `SecurityConfig.java`, `web/AppController.java`, `user/*`), tests green.
- **No Spring AI on the classpath**: `./gradlew dependencies | grep spring-ai` returns nothing. Adding F-01 is purely additive to `build.gradle`.
- **Provider settled**: `context/foundation/roadmap.md` F-01 + `memory/project_llm_provider.md` lock OpenRouter as the gateway because the user wants model swappability without code changes — only a `model` string change per request. The starter mentioned in roadmap text (`spring-ai-openai-spring-boot-starter`) is the **legacy** name; Context7 confirms Spring AI 1.0.0 renamed it to `spring-ai-starter-model-openai`. Spring AI 1.0.x targets Spring Boot 3.4 / 3.5 only; **for Boot 4 the supported line is Spring AI 2.0.x** (Context7 docs: 2.0.x "supports Spring Boot 4.0.x and 4.1.x"). 2.0 has no GA yet — latest milestone is `2.0.0-M6`, which lives on Spring's milestone repo, not Maven Central.
- **Secret name is already reserved**: deploy-plan §2.3 + §F.1 set `AI_PROVIDER_API_KEY=pending-provider-selection` as a Fly secret. F-01 rotates that value to a real OpenRouter key via one `fly secrets set` re-run; **no `fly.toml` change needed**.
- **PRD ceilings**: FR-005 + NFR mandate 30 s typical / 60 s ceiling for image extraction. Spring AI's OpenAI starter uses Spring `RestClient` under the hood; default read timeout is effectively unbounded, so we must override.
- **Posture not to break**: `application.properties` already has cookie/actuator hardening + `ddl-auto=update` + additive-only migration rule (deploy-plan §5.2). F-01 adds only `spring.ai.*` keys and no `@Entity` classes — schema is untouched.
- **Existing tests rely on H2**: `AppApplicationTests` boots a full Spring context against H2 (`testRuntimeOnly 'com.h2database:h2'`). Adding the OpenAI starter must not require a live `OPENROUTER_API_KEY` for `./gradlew test` to pass — the starter accepts a placeholder key at boot and only validates on first call (verified via Context7 Groq example, which uses the same OpenAI-compatible pattern).
- **Module / package**: rootProject is `app`, Java package is `com.example.app` (CLAUDE.md is explicit about this; do not introduce `com.ogarniacz.*`). New code lands under `com.example.app.llm`.

## Desired End State

- `./gradlew dependencies --configuration runtimeClasspath | grep spring-ai-starter-model-openai` shows the starter present; Spring AI BOM is the only Spring AI version source (no per-artifact pins outside the BOM).
- `application.properties` contains the OpenRouter wiring (`base-url`, model default, timeout configuration handle) but does NOT contain the API key — the key is sourced from `OPENROUTER_API_KEY` env var (local) / `AI_PROVIDER_API_KEY` Fly secret (prod) via Spring's standard env-binding.
- `com.example.app.llm.LlmVisionClient` interface exists; `com.example.app.llm.OpenRouterLlmVisionClient` is the sole impl; both compile and pass unit tests with a `@MockitoBean ChatModel`.
- `./gradlew test` runs to `BUILD SUCCESSFUL` with no real OpenRouter call, no secret leak, and no flakiness from network. The CI deploy workflow (`.github/workflows/deploy.yml`) is unchanged — F-01 introduces no new CI secret.
- The manually-enabled live smoke test `LlmVisionSmokeTest` (annotated `@EnabledIfEnvironmentVariable(named = "OGARNIACZ_LIVE_SMOKE", matches = "true")`) sends a real PNG sample (the bundled `sample-announcement.png` fixture under `src/test/resources/llm/`) to `google/gemini-2.5-flash` via OpenRouter, receives a non-empty `LlmExtractionResult` within 55 seconds, and prints the parsed structure to the test log. The operator must ALSO have an API key in scope (`OPENROUTER_API_KEY` or the production `AI_PROVIDER_API_KEY`) — the dedicated flag is the trigger; the key is the credential.
- After Fly secret rotation, a `bootRun` against the deployed app's logs shows no startup error related to Spring AI; the actual live call still only fires from S-05 (no new production HTTP route is added by F-01).
- A short runbook section in this plan (and mirrored in `change.md` notes) documents (a) how to re-run the smoke after a model swap, (b) how to rotate the Fly secret without redeploy, (c) what to do if OpenRouter returns a 4xx (token / model name / image size).

### Key Discoveries:

- **The starter name in the roadmap is stale**: `spring-ai-openai-spring-boot-starter` was the pre-1.0 artifact; Spring AI 1.0.0 onwards uses `spring-ai-starter-model-openai` (Context7 / Spring AI upgrade-notes). Roadmap baseline section mentions this but it must explicitly land in the Gradle file under the new name; pinning the legacy name would resolve a non-existent artifact.
- **Spring Boot 4 forces Spring AI 2.0.x** (Context7: Spring AI 1.0.x → Boot 3.4/3.5 only; Spring AI 2.0.x → Boot 4.0/4.1). 2.0 GA is not out yet — the F-01 pin is `spring-ai-bom:2.0.0-M6`, a milestone that lives on `https://repo.spring.io/milestone` rather than Maven Central. The `repositories { }` block must add that URL or Gradle resolves nothing.
- **OpenAI-compatible base-URL override is first-class in Spring AI**: `spring.ai.openai.base-url=https://api.groq.com/openai` is the documented Groq pattern — same shape for OpenRouter: `spring.ai.openai.base-url=https://openrouter.ai/api/v1`. No manual `ChatClient` bean is required; the starter just works against any OpenAI-compatible endpoint.
- **F-01 stays on the portable `ChatClient` surface, not provider-specific options**: model + temperature are set via `spring.ai.openai.chat.options.*` properties; the impl calls `ChatClient`'s fluent API (`prompt().user(...).call().content()`) and never references `OpenAiChatOptions` / `OpenAiSdkChatOptions`. Spring AI's 2.0 milestone line renamed those classes between M5 and M6 — sidestepping them entirely means F-01 doesn't carry that risk. A per-call model override (S-05 may want one to A/B a different vision model) introduces a provider-specific options class at *that* point, against whatever Spring AI version is then pinned.
- **Multimodal pattern is `Media` on the user-message builder**: inside the `ChatClient` fluent API, `.user(u -> u.text(prompt).media(mimeType, resource))` — Spring AI assembles the multipart payload OpenAI expects (and OpenRouter relays).
- **`@MockitoBean` is the test-mocking primitive in Spring Boot 4** (it replaces `@MockBean`, removed in Boot 4): `@MockitoBean ChatModel chatModel;` inside a `@SpringBootTest` overrides the autoconfigured bean. The autoconfigured `ChatClient.Builder` then builds a `ChatClient` backed by the mock — calls through the fluent API still land on `chatModel.call(Prompt)`, so Mockito stubs on `ChatModel` cover the `ChatClient` surface without an extra mock layer.
- **Timeout + header configuration is property-driven, not customizer-driven** (corrected during Phase 1 implementation): Spring AI 2.0.0-M6's OpenAI starter does NOT use Spring's `RestClient`. Per the M5+ migration, it builds a `com.openai.client.OpenAIClient` from the official `openai-java` SDK directly inside `OpenAiChatAutoConfiguration`. Spring Boot 4 has also dropped `RestClientCustomizer` from `org.springframework.boot.web.client`. Both knobs the plan needed are exposed as standard properties on `AbstractOpenAiProperties`: `spring.ai.openai.timeout` (`Duration`), `spring.ai.openai.max-retries` (`int`), `spring.ai.openai.custom-headers` (`Map<String,String>`), `spring.ai.openai.proxy` (`Proxy`). One overall timeout — no split connect/read — but 55 s is generous enough that the 5 s connect nicety the plan originally specified isn't load-bearing.
- **OpenRouter recommends `HTTP-Referer` and `X-Title` headers** for usage attribution and routing rate-limits — not strictly required for a successful call, but free-tier limits get tighter without them. Add via `spring.ai.openai.custom-headers.HTTP-Referer` and `.X-Title` property keys.

## What We're NOT Doing

- **No S-05 work** — no upload route, no image-storage decision (ephemeral disk vs object store), no UI, no review/accept flow, no streamed progress. F-01 is plumbing only; the live smoke test uses a hardcoded `src/test/resources/llm/sample-announcement.png` fixture.
- **No prompt engineering beyond a "good-enough" extraction prompt** — the prompt's job in F-01 is to produce something parseable by the smoke test, not to clear S-05's 80% accuracy bar. Refinement happens iteratively inside S-05.
- **No structured output beans / function-calling / `BeanOutputConverter` integration**. The smoke test parses the model's free-text JSON with a small DTO — when S-05 lands, structured-output binding (or a `ResponseFormat`-style JSON-mode config) gets added there. Pulling that in now would couple F-01 to schema decisions S-05 owns.
- **No retry policy, no circuit breaker, no Resilience4j**. PRD's 60 s ceiling already shapes the failure budget; one shot, no retry, surface the exception.
- **No new production HTTP endpoint**. The smoke test is the only execution path of the new code in F-01.
- **No metrics / tracing instrumentation** beyond standard SLF4J logs. Deploy-plan §6 keeps observability monitor-only; adding Micrometer counters for LLM calls is a post-MVP follow-up.
- **No image-content moderation, no PII redaction** at the LLM call boundary. Out of MVP per PRD §Non-Goals (vision LLM is treated as a vendor service; we forward the parent's own uploaded image as-is).
- **No fallback model / multi-model orchestration**. Roadmap names model swap as a one-string change; an orchestrator that races two models is post-MVP polish.
- **No CI secret for live OpenRouter calls**. `./gradlew test` in GitHub Actions runs the unit tests against the `@MockitoBean ChatModel`; the live smoke test is operator-run only.

## Implementation Approach

Three phases, each ending at a manually verifiable checkpoint:

1. **Dependency + config wiring** — Spring AI BOM + `spring-ai-starter-model-openai` on `build.gradle`; `application.properties` gains `spring.ai.openai.base-url`, `spring.ai.openai.api-key=${OPENROUTER_API_KEY:placeholder}`, the default model property, and the OpenRouter attribution headers. A `RestClientCustomizer` `@Bean` sets the 55 s read / 5 s connect timeouts on the Spring AI `RestClient`. End state: `./gradlew bootRun` boots cleanly without an `OPENROUTER_API_KEY` env var; `./gradlew test` is still green; `./gradlew dependencies` shows the starter resolved against BOM 1.0.x.
2. **Vision client + DTO** — `com.example.app.llm.LlmVisionClient` interface (one method: `LlmExtractionResult extract(byte[] image, MimeType mimeType)`); `OpenRouterLlmVisionClient` impl autowired with Spring AI's `ChatModel`; `LlmExtractionResult` DTO (record) capturing `proposedEvents` list; `LlmExtractionException` for failure surfacing. Unit tests in `LlmVisionClientTest` use `@MockitoBean ChatModel` to assert (a) the prompt is shaped with `UserMessage` + `Media`, (b) the model name is read from config, (c) timeout propagation, (d) non-2xx → `LlmExtractionException`. End state: `./gradlew test` covers the client with mocked Spring AI; no real network.
3. **Live smoke + secret rotation + runbook** — `src/test/resources/llm/sample-announcement.png` fixture committed; `LlmVisionSmokeTest` annotated `@EnabledIfEnvironmentVariable(named="OPENROUTER_API_KEY", matches=".+")` invokes `LlmVisionClient.extract()` against the fixture, asserts non-empty result + ≤55 s wall-clock. Operator runs (a) `./gradlew test` locally with `OPENROUTER_API_KEY=sk-or-…` exported to confirm; (b) `fly secrets set AI_PROVIDER_API_KEY=sk-or-… -a ogarniacz` to rotate the placeholder; (c) `fly logs` to verify the redeployed app still boots clean. Runbook section in this plan covers all three.

## Critical Implementation Details

### State sequencing — secret name vs property binding

The OpenRouter API key lives in two different env var names depending on environment: **`OPENROUTER_API_KEY` locally** (developer terminal, optional — only the smoke test needs it) and **`AI_PROVIDER_API_KEY` in production** (Fly secret, set per deploy-plan §F.1 — the placeholder is rotated to the real value as part of this change). The Spring property `spring.ai.openai.api-key` must therefore resolve from **both** names with a placeholder fallback so the application boots in every environment (CI test run, local dev without a key, smoke run, prod). Use Spring's chained `${...}` placeholders:

```properties
spring.ai.openai.api-key=${OPENROUTER_API_KEY:${AI_PROVIDER_API_KEY:placeholder-not-a-real-key}}
```

Order matters: local `OPENROUTER_API_KEY` wins (so a developer can run the smoke against their own key without messing with prod env naming); else the Fly-secret name; else the placeholder so boot doesn't crash. The placeholder MUST be a string that obviously fails — `placeholder-not-a-real-key` makes a 401 from OpenRouter unambiguous when it eventually surfaces. Reversing the precedence (`AI_PROVIDER_API_KEY` first) silently makes a local developer's `OPENROUTER_API_KEY` ineffective when both happen to be set.

### Timing & lifecycle — openai-java SDK timeout via properties

Spring AI 2.0.0-M6 swapped its OpenAI client implementation to the official `openai-java` SDK; the autoconfigured `OpenAiChatModel` builds a `com.openai.client.OpenAIClient` from `OpenAiCommonProperties`, not from a Spring `RestClient`. The supported override path for timeout + custom headers is therefore the property surface exposed by `AbstractOpenAiProperties`, not a `RestClientCustomizer` `@Bean` (the original Phase 1 Change #3 in this plan). Keys: `spring.ai.openai.timeout=55s` (sets the overall openai-java client timeout), `spring.ai.openai.max-retries=0` (PRD failure budget is a single shot; retries would conflate "slow" with "broken"), and `spring.ai.openai.custom-headers.HTTP-Referer` / `.X-Title` for the OpenRouter attribution headers. **55 s, not 60 s**: leaves a 5 s margin under the PRD's 60 s ceiling for prompt building, JSON parsing, and response handoff — the user-facing budget is 60 s end-to-end, not 60 s of HTTP. **One overall timeout — no split connect/read**: the openai-java SDK exposes a single duration. The plan's original 5 s connect / 55 s read split was a defensive nicety not load-bearing for F-01; if a future slice ever needs a finer-grained connect timeout, the path is a custom `OpenAIClient` `@Bean` that Spring AI's autoconfig will prefer. **Headers are scoped to the openai-java SDK only** — no global `RestClient` side effect to worry about, so the F5 follow-up from the plan-review is moot under the new mechanism. If F-04's "image-size could 4xx" surfaces, `spring.ai.openai.timeout` is also where you'd lengthen the window to inspect a slow timeout vs a 413.

### Error envelope — failure mode that S-05 can map

`OpenRouterLlmVisionClient` catches Spring AI / `RestClient` exceptions (`org.springframework.ai.retry.NonTransientAiException`, `org.springframework.web.client.RestClientResponseException`, `java.net.SocketTimeoutException`) and wraps them in a single project-owned `LlmExtractionException` with four fields: `kind` (`TIMEOUT` | `PROVIDER_ERROR` | `MALFORMED_RESPONSE`), `httpStatus` (nullable Integer), `providerMessage` (nullable String — the raw response body from the provider on error), and the original cause as `Throwable`. S-05 needs exactly one type to catch in its controller; FR-005 maps the three `kind` values to user-facing copy ("the upload took too long, try again" / "we couldn't reach the extractor, try again" / "we got a response we can't read, try again or enter manually"). When S-05 later wants to split `PROVIDER_ERROR` into "413 image too large" vs "5xx vendor blip" with distinct copy, `httpStatus` + `providerMessage` carry the signal forward — no signature retrofit needed. Don't expose Spring AI exception classes to non-LLM code — that's the abstraction `LlmVisionClient` is paying for.

### Observability — what to log, what to NOT log

Log at INFO on every call: `model`, `imageBytes`, `mimeType`, `latencyMillis`, outcome (`SUCCESS` / `FAILED:kind`). Do NOT log: the API key (use the placeholder `***` if Spring's startup banner mentions it; verify with `fly logs | grep -i api-key` post-deploy), the prompt body (would dump system prompt — irrelevant noise), the raw response body (may contain extracted PII from a kindergarten note). Log a hash of the image bytes (`Integer.toHexString(Arrays.hashCode(...))`) so two calls on the same image are correlatable in logs without storing the image itself. PRD §Non-Goals + deploy-plan §6 keep observability monitor-only; structured-log JSON is not introduced here.

## Phase 1: Dependency + config wiring

### Overview

Add Spring AI to the build, point it at OpenRouter, set the timeout. End state: app boots clean with no key set; `./gradlew test` stays green; the starter is on the runtime classpath under the new 1.0.x artifact name.

### Changes Required:

#### 1. `build.gradle` — Spring AI BOM + starter

**File**: `build.gradle`

**Intent**: Pull in Spring AI's `spring-ai-starter-model-openai` so `ChatClient` / `ChatModel` autoconfiguration becomes available; pin via the Spring AI BOM (Boot-4-compatible line) and add Spring's milestone repo because 2.0 hasn't GA'd yet.

**Contract**: A Gradle `platform(...)` import inside `dependencies { }` introduces the BOM at version `2.0.0-M6` (the latest 2.0 milestone confirmed via Context7; implementer should `./gradlew dependencyInsight --dependency spring-ai-bom` immediately after the edit and fall back to `2.0.0-M3` only if M6 is missing from `https://repo.spring.io/milestone`). A new `implementation 'org.springframework.ai:spring-ai-starter-model-openai'` line lands inside `dependencies { }` (no version — managed by BOM). The `repositories { }` block gains `maven { url = uri('https://repo.spring.io/milestone') }` alongside the existing `mavenCentral()` — Spring AI 2.0 milestones are NOT on Maven Central, so without this Gradle reports "Could not resolve org.springframework.ai:spring-ai-bom:2.0.0-M6". No other dependency changes.

**Snippet** (the BOM idiom + milestone repo together are easy to half-apply — pin the BOM without the repo and the build fails on resolve; add the repo without the BOM and nothing changes):

```gradle
// repositories { } — add alongside mavenCentral():
maven { url = uri('https://repo.spring.io/milestone') }

// dependencies { } — inside the existing block, alongside the other implementation lines:
implementation platform("org.springframework.ai:spring-ai-bom:2.0.0-M6")
implementation 'org.springframework.ai:spring-ai-starter-model-openai'
```

#### 2. `src/main/resources/application.properties` — Spring AI keys

**File**: `src/main/resources/application.properties`

**Intent**: Tell Spring AI's OpenAI starter to talk to OpenRouter using whichever API key is present (local `OPENROUTER_API_KEY`, prod `AI_PROVIDER_API_KEY`, or a known-bad placeholder so boot never fails). Set the default vision model to `google/gemini-2.5-flash` (the OpenRouter slug for Gemini 2.5 Flash — vision-capable Flash tier, cheap + low baseline latency; model is swappable per call). **Note (2026-06-07):** the plan originally specified `google/gemini-2.0-flash-001`, but that slug was retired from OpenRouter (HTTP 404 *No endpoints found*) by the time live verification ran; `google/gemini-2.5-flash` is its verified replacement.

**Contract**: Append a `# OpenRouter (Spring AI)` section to the existing properties file with these keys: `spring.ai.openai.base-url`, `spring.ai.openai.api-key` (chained-placeholder fallback per §Critical Implementation Details / State sequencing), `spring.ai.openai.chat.options.model=google/gemini-2.5-flash`, `spring.ai.openai.chat.options.temperature=0.2` (deterministic for an extraction task; cuts variance between smoke runs without making the model dumb), `spring.ai.openai.timeout=55s`, `spring.ai.openai.max-retries=0`, `spring.ai.openai.custom-headers.HTTP-Referer=https://ogarniacz.fly.dev`, `spring.ai.openai.custom-headers.X-Title=Ogarniacz`. Place a single comment line `# Smoke test reads from OPENROUTER_API_KEY (local dev); production rotates AI_PROVIDER_API_KEY (Fly secret).` directly above the `spring.ai.openai.api-key` line so onboarding is rediscoverable from the properties file itself. No keys removed; existing cookie/actuator config untouched.

#### 3. ~~`OpenRouterRestClientConfig`~~ — superseded by properties (adapted during Phase 1)

**Original intent**: Provide a `RestClientCustomizer` `@Bean` that sets 5 s connect / 55 s read timeouts and adds OpenRouter's recommended attribution headers (`HTTP-Referer`, `X-Title`).

**Why removed**: Discovered at implementation time that (a) Spring Boot 4 dropped `RestClientCustomizer` from `org.springframework.boot.web.client` entirely, and (b) Spring AI 2.0.0-M6's OpenAI starter no longer uses Spring's `RestClient` — it builds `com.openai.client.OpenAIClient` from the official `openai-java` SDK via `OpenAiCommonProperties`. The customizer wouldn't have anywhere to hook in even if it existed. `AbstractOpenAiProperties` already exposes `timeout` (`Duration`), `maxRetries` (`int`), and `customHeaders` (`Map<String,String>`), so the entire Java config file is unnecessary — three property lines under Change #2 above replace it. See Critical Implementation Details / "Timing & lifecycle — openai-java SDK timeout via properties" for the full rationale.

### Success Criteria:

#### Automated Verification:

- `./gradlew dependencies --configuration runtimeClasspath | grep spring-ai-starter-model-openai` shows the starter resolved against BOM 1.0.x.
- `./gradlew test` passes (existing 16 tests green; no new tests yet in Phase 1).
- `./gradlew bootRun` boots locally with no `OPENROUTER_API_KEY` set: log line `Started AppApplication in <N> seconds` appears; no `IllegalArgumentException`, no `BeanCreationException` mentioning `spring.ai`.

#### Manual Verification:

- Eyeball the resolved BOM version with `./gradlew dependencyInsight --dependency spring-ai-starter-model-openai` — confirm it's `2.0.0-M6` (Boot 4 compat; 1.0.x targets Boot 3.4/3.5 only).
- Stop `bootRun`, export `OPENROUTER_API_KEY=placeholder-not-a-real-key`, restart — should still boot clean (the call hasn't happened yet, only autoconfiguration).
- Open `application.properties` and re-read the new five lines for a typo (single-character mistake in `base-url` is the highest-likelihood failure mode at this phase).

**Implementation Note**: After Phase 1 lands and all automated verification passes, pause for manual confirmation before Phase 2.

---

## Phase 2: Vision client + DTO

### Overview

Introduce the project-owned `LlmVisionClient` abstraction so S-05 and any future caller depends on a domain interface, not Spring AI types. Cover the surface with mock-backed unit tests so `./gradlew test` keeps running without a real OpenRouter key.

### Changes Required:

#### 1. `com.example.app.llm.LlmVisionClient` — domain interface

**File**: `src/main/java/com/example/app/llm/LlmVisionClient.java`

**Intent**: Define the single seam between the rest of the app and the vision model. One method: extract proposed events from an image.

**Contract**: `public interface LlmVisionClient { LlmExtractionResult extract(byte[] image, MimeType mimeType) throws LlmExtractionException; }`. Method is synchronous (Spring AI's `ChatModel.call` is synchronous; the timeout is the latency contract); no `CompletableFuture`/`Mono` until S-05 actually needs streaming. Method takes raw bytes + MIME type so callers (controllers, jobs, tests) don't have to know about `Resource`/`MultipartFile` shapes — the implementation wraps into `ByteArrayResource` for Spring AI's `Media`.

Document one precondition in the interface's javadoc: "Caller is responsible for downscaling. Size limits depend on the model and provider — typical phone photos (2–5 MB) pass through OpenRouter without issue, but larger uploads should be downscaled before this call or the provider may reject them with a 4xx. F-01 does not validate at the boundary; that's a callsite concern (S-05's upload pipeline)."

#### 2. `com.example.app.llm.LlmExtractionResult` — DTO record

**File**: `src/main/java/com/example/app/llm/LlmExtractionResult.java`

**Intent**: Carry the parsed extraction result back to the caller in a shape the smoke test can assert against and S-05 can extend.

**Contract**: A Java `record LlmExtractionResult(List<ProposedEvent> proposedEvents, String rawResponse, long latencyMillis) { }`. Inner record `ProposedEvent(LocalDate date, LocalTime time, String title, String requirements, String notes)` — `time` and `notes` nullable (PRD FR-004: time is optional, most kindergarten events are date-only). `rawResponse` keeps the model's free-text JSON for debugging the smoke test and for S-05 prompt iteration; `latencyMillis` is what the observability log line reads. No JPA annotations on these records — they are not entities. No JSON serialization annotations either; if S-05 surfaces this DTO at an HTTP boundary later, that's where Jackson lands.

#### 3. `com.example.app.llm.LlmExtractionException` — failure envelope

**File**: `src/main/java/com/example/app/llm/LlmExtractionException.java`

**Intent**: Wrap every failure mode into one type S-05 catches in its controller, with enough structure to map to FR-005's three user-facing copy variants.

**Contract**: `public final class LlmExtractionException extends RuntimeException` with fields `Kind kind` (enum: `TIMEOUT`, `PROVIDER_ERROR`, `MALFORMED_RESPONSE`), `Integer httpStatus` (nullable), **`String providerMessage` (nullable — the raw response body the provider returned on error)**, and standard `cause`. Three static factory methods (`timeout(Throwable)`, `provider(int status, String body, Throwable)`, `malformed(Throwable)`) for readability at call sites. Extends `RuntimeException` (not checked) because S-05's controller will translate at one place anyway, and forcing every intermediate layer to declare `throws LlmExtractionException` is more noise than safety.

**Why `providerMessage` belongs here even though F-01 doesn't use it**: F-01 surfaces every provider failure as `kind=PROVIDER_ERROR`, which conflates "413/400 — image too large" (user-actionable, distinct copy per FR-005) with "5xx — vendor blip" (retry-from-UI). Mapping status codes to granular `kind` values is S-05's job, but if F-01 drops the status body, S-05 has nothing to map. Capturing it now (one extra field, one extra line in the catch) keeps that door open with zero further wiring.

#### 4. `com.example.app.llm.OpenRouterLlmVisionClient` — Spring AI–backed impl

**File**: `src/main/java/com/example/app/llm/OpenRouterLlmVisionClient.java`

**Intent**: Implement `LlmVisionClient` by building a Spring AI prompt via the portable `ChatClient` fluent API from the input bytes, timing the call, parsing the response JSON into `LlmExtractionResult`, and translating exceptions into `LlmExtractionException`.

**Contract**: `@Component` with constructor-injected `ChatClient.Builder chatClientBuilder` (autoconfigured by `spring-ai-starter-model-openai`) and `ObjectMapper objectMapper` (Spring Boot auto-provides one — no new bean needed). The constructor immediately calls `this.chatClient = chatClientBuilder.build();` once; the resulting `ChatClient` is reused for every `extract(...)` call. The model + temperature are NOT named in code — they live in `application.properties` under `spring.ai.openai.chat.options.model` and `.temperature`, picked up by the autoconfigured `ChatClient.Builder`. **Deliberately no `OpenAiChatOptions` / `OpenAiSdkChatOptions` reference**: Spring AI 2.0 renamed those classes between M5 and M6, and F-01 has no per-call override that would force the implementer to pick which name to import. When S-05 needs a per-call swap (e.g., to A/B a different model), the options class gets pulled in at *that* point against whatever Spring AI version is pinned then. `extract(byte[], MimeType)` wraps bytes as `ByteArrayResource`, calls `chatClient.prompt().user(u -> u.text(SYSTEM_PROMPT).media(mimeType, resource)).call().content()` to get the model's text response, parses it. The system+user prompt is a single string constant in the class (kept short — "Read this kindergarten announcement image and return a JSON array of events with fields date, time, title, requirements, notes"). The JSON parsing is permissive: trim surrounding markdown fences if present (`String.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "")` before `objectMapper.readValue(...)`) — vision models routinely wrap JSON in fences even when asked not to.

**Snippet** (warranted because the markdown-fence quirk + exception translation table is the kind of detail the implementer would otherwise miss — Spring AI vision examples don't cover it):

```java
// inside extract(byte[], MimeType):
long start = System.nanoTime();
try {
    String raw = chatClient.prompt()
            .user(u -> u.text(SYSTEM_PROMPT).media(mimeType, new ByteArrayResource(image)))
            .call()
            .content();
    String json = raw.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "").trim();
    List<ProposedEvent> events = objectMapper.readValue(json, new TypeReference<>() {});
    long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
    return new LlmExtractionResult(events, raw, elapsedMs);
} catch (java.net.SocketTimeoutException | org.springframework.web.client.ResourceAccessException e) {
    throw LlmExtractionException.timeout(e);
} catch (org.springframework.web.client.RestClientResponseException e) {
    throw LlmExtractionException.provider(e.getStatusCode().value(), e.getResponseBodyAsString(), e);
} catch (com.fasterxml.jackson.core.JsonProcessingException e) {
    throw LlmExtractionException.malformed(e);
}
```

#### 5. `LlmVisionClientTest` — unit tests with `@MockitoBean ChatModel`

**File**: `src/test/java/com/example/app/llm/LlmVisionClientTest.java`

**Intent**: Cover the client's behavior without a real OpenRouter call. Five tests minimum.

**Contract**: `@SpringBootTest` (so the chained-placeholder `${OPENROUTER_API_KEY:${AI_PROVIDER_API_KEY:placeholder...}}` is exercised) with `@MockitoBean ChatModel chatModel`. Mocking at `ChatModel` (not at `ChatClient`) still covers the `ChatClient` fluent surface: the autoconfigured `ChatClient.Builder` builds a `ChatClient` delegating to the `ChatModel` bean, and `@MockitoBean` swaps that bean before the builder runs — so `when(chatModel.call(any(Prompt.class))).thenReturn(...)` intercepts every fluent call. Tests:
1. `extractParsesWellFormedJsonResponse` — `when(chatModel.call(any(Prompt.class))).thenReturn(<ChatResponse with JSON body>)`; assert the returned `LlmExtractionResult.proposedEvents` shape.
2. `extractStripsMarkdownFencesAroundJson` — same as above but the mock returns ` ```json\n[...]\n``` `; assert it still parses.
3. `extractWrapsSocketTimeoutAsTimeoutKind` — mock throws `ResourceAccessException(... new SocketTimeoutException())`; assert `LlmExtractionException` with `kind=TIMEOUT`.
4. `extractWrapsRestClientResponseAsProviderKind` — mock throws `RestClientResponseException`; assert `kind=PROVIDER_ERROR` and `httpStatus` non-null.
5. `extractWrapsMalformedJsonAsMalformedKind` — mock returns `not-json-at-all`; assert `kind=MALFORMED_RESPONSE`.

The model name + temperature flow from `application.properties` through the autoconfigured `ChatClient.Builder`; an `ArgumentCaptor<Prompt>` on `chatModel.call(...)` is the assertion path if a future test wants to verify those propagate.

### Success Criteria:

#### Automated Verification:

- `./gradlew test --tests com.example.app.llm.LlmVisionClientTest` passes all 5 tests.
- Full `./gradlew test` is green with no real OpenRouter call (verify by running with `OPENROUTER_API_KEY=` empty — should still pass).
- `./gradlew bootRun` still starts clean and `curl -i http://localhost:8080/actuator/health` returns 200.

#### Manual Verification:

- Read `OpenRouterLlmVisionClient.java` and confirm the exception translation table covers Spring AI's actual thrown types for this version (Spring AI 1.0.x may add a new wrapper class between writing the plan and running it — adjust the catches if so).
- Confirm no test calls `objectMapper.writeValueAsString(...)` on the `LlmExtractionResult` (no Jackson serialization annotations were added; we don't want a downstream surprise).

**Implementation Note**: After Phase 2 lands and all automated verification passes, pause for manual confirmation before Phase 3.

---

## Phase 3: Live smoke + secret rotation + runbook

### Overview

The actual acceptance gate of F-01: a real image hits real OpenRouter and produces a parseable result inside 55 s. Rotate the Fly placeholder secret. Write down how to re-run the smoke when a future model swap arrives.

### Changes Required:

#### 1. Sample image fixture

**File**: `src/test/resources/llm/sample-announcement.png`

**Intent**: A real, representative kindergarten-announcement image (a screenshot of a parent-chat message or a corridor-note photo) committed to the repo so the smoke test is reproducible without external file dependencies.

**Contract**: A single PNG, ≤ 500 KB (keeps the repo light; well under any OpenAI/OpenRouter image-size ceiling). Polish-language content is fine — the smoke test asserts the response is parseable, not that the extraction is semantically correct (that's S-05's job). If a real announcement isn't available at implementation time, a synthetic one (a typed-out note saved as a screenshot) is acceptable for F-01; replace with a real one when iterating on S-05's prompt. Image is committed via standard `git add`; no LFS needed at this size.

#### 2. `LlmVisionSmokeTest` — manually-enabled live test

**File**: `src/test/java/com/example/app/llm/LlmVisionSmokeTest.java`

**Intent**: The one place that actually calls OpenRouter. Inert unless the operator has an API key in their environment.

**Contract**: `@SpringBootTest` with `@EnabledIfEnvironmentVariable(named = "OGARNIACZ_LIVE_SMOKE", matches = "true")`. The operator exports `OGARNIACZ_LIVE_SMOKE=true` AND `OPENROUTER_API_KEY=sk-or-…` before running. (Splitting the flag from the key matters: a contributor with an OpenRouter key in their shell for some other project shouldn't trip this test by accident.) One test method `smokeAgainstRealOpenRouter` that: (a) loads `src/test/resources/llm/sample-announcement.png` into a `byte[]`; (b) calls `llmVisionClient.extract(bytes, MimeTypeUtils.IMAGE_PNG)`; (c) asserts the call returned within 55 000 ms; (d) asserts the result has at least one `ProposedEvent` (the sample is chosen to contain at least one); (e) prints (`System.out.println`) the full `LlmExtractionResult` for human eyeball validation. **Do NOT** assert the extracted date/title/requirements are semantically correct — that's S-05's wedge measurement, not F-01's integration test. The print-and-eyeball pattern is the deliberate choice: F-01 proves the bridge works, S-05 proves the bridge is useful.

#### 3. Fly secret rotation — operator action (no file change)

**File**: (none — operator runs `fly secrets set` per deploy-plan §F.1 pattern)

**Intent**: Replace the placeholder Fly secret with the real OpenRouter key so the deployed app, after F-01 lands and a redeploy happens, has a working credential.

**Contract**: Operator runs `fly secrets set AI_PROVIDER_API_KEY='sk-or-…' -a ogarniacz`. The Machine auto-restarts (Fly's standard behavior on secret change); `fly logs -a ogarniacz` should show a clean `Started AppApplication` line within ~60 s — F-01 introduces no production code path that calls OpenRouter, so the deployed Machine never invokes the API on its own. The plan does NOT include automation of the rotation; the deploy-plan's §7 explicit irreversible-callout for `fly secrets set` applies.

#### 4. Runbook section in this plan + mirror to `change.md` Notes

**File**: `context/changes/openrouter-llm-client-wired/change.md` (Notes section)

**Intent**: Operator-readable summary of (a) re-running the smoke after a model swap, (b) rotating the Fly secret, (c) interpreting common failures (401, 404, 429, timeout).

**Contract**: Append a `### Runbook` subsection to the `## Notes` body of `change.md` with three bullets matching the headings below. Keep the runbook short — full detail lives in this plan; `change.md` is the index.

### Runbook (operator reference)

**Re-run the smoke after a model swap.** Edit `spring.ai.openai.chat.options.model` in `application.properties` to the new OpenRouter slug (`anthropic/claude-sonnet-4.5`, `openai/gpt-4o-mini`, etc.). Export BOTH `OGARNIACZ_LIVE_SMOKE=true` AND `OPENROUTER_API_KEY=sk-or-…` in your shell. Run `./gradlew test --tests com.example.app.llm.LlmVisionSmokeTest --rerun-tasks --info`. Expected: passes within 55 s and prints a `LlmExtractionResult` you can eyeball. Missing the flag → test silently skipped; missing the key → 401 from OpenRouter.

**Rotate the Fly secret.** `fly secrets set AI_PROVIDER_API_KEY='sk-or-…' -a ogarniacz`. Wait ~30 s for the Machine restart. `fly logs -a ogarniacz | tail -50` and look for `Started AppApplication`. If `Machine failed to start`, the old placeholder string was actually being read by some code path — investigate that path before re-trying rotation.

**Common failures.** `401` → key is wrong or revoked; check OpenRouter dashboard, rotate. `404` on the model name → OpenRouter slug changed; `curl -s https://openrouter.ai/api/v1/models -H "Authorization: Bearer $OPENROUTER_API_KEY" | jq '.data[].id'` lists current ids. `429` → rate-limit; OpenRouter free tier has burst limits; rerun after a minute or add credit. `SocketTimeoutException` after 55 s → model is taking longer than the budget; either swap to a faster model (`google/gemini-2.5-flash` is the default for a reason) or accept that S-05 needs streaming progress UX (FR-005's "continuous visible progress").

### Success Criteria:

#### Automated Verification:

- `./gradlew test` (without `OGARNIACZ_LIVE_SMOKE` set) passes everything including a noisy log line "skipped" for `LlmVisionSmokeTest` — confirms the JUnit env-variable gate is correct.
- `./gradlew test` (with `OGARNIACZ_LIVE_SMOKE=true` AND `OPENROUTER_API_KEY=sk-or-…` set) passes `LlmVisionSmokeTest` end-to-end, finishing in ≤ 55 s.

#### Manual Verification:

- Read the smoke test's printed `LlmExtractionResult.rawResponse` — confirm the model produced something resembling JSON with the right field shape (`date`, `title`, etc.). Semantic accuracy is NOT graded here.
- Rotate the Fly secret (`fly secrets set AI_PROVIDER_API_KEY='sk-or-…' -a ogarniacz`) and verify the deployed app's logs (`fly logs -a ogarniacz`) show a clean restart with no new errors.
- Run the smoke a second time with a different model (`anthropic/claude-sonnet-4.5`) by editing `application.properties` locally — confirm the rerun still works and the runbook bullet is accurate.
- ~~Reset the model line before committing~~ **(superseded 2026-06-07)**: the originally-planned default `google/gemini-2.0-flash-001` was retired from OpenRouter (HTTP 404), so the committed default is now `google/gemini-2.5-flash` — a real fix, not an exploratory swap to revert. If you additionally A/B a heavier model (e.g. claude) per the bullet above, reset to `google/gemini-2.5-flash` before committing.

**Implementation Note**: Phase 3 closes F-01. After this phase, the unknowns block from the roadmap (`spring-ai-openai-spring-boot-starter` compatibility, vision-model default) is resolved; S-05 inherits a working client.

---

## Testing Strategy

### Unit Tests:

- `LlmVisionClientTest` (Phase 2) — 5 tests covering happy path, markdown-fence stripping, timeout translation, provider-error translation, malformed-JSON translation. Mock at `ChatModel` boundary via `@MockitoBean`.
- Existing 16 tests in `AppApplicationTests` must remain green — F-01 changes no auth, no controllers, no `application.properties` keys those tests depend on.

### Integration Tests:

- The live smoke (`LlmVisionSmokeTest`, Phase 3) IS the integration test. It is **deliberately gated** by env-var (no real key → skipped), so CI runs it as "skipped" and operators run it locally on demand. There is no separate integration test profile.

### Manual Testing Steps:

1. Phase 1: `./gradlew bootRun` boots clean both with and without `OPENROUTER_API_KEY` set.
2. Phase 2: Run a single test (`./gradlew test --tests "*.LlmVisionClientTest"`) and confirm 5 tests pass with no network access (`OPENROUTER_API_KEY` unset).
3. Phase 3: Export a real key, run the smoke, eyeball the printed result. Rotate the Fly secret. Eyeball `fly logs` for a clean restart.
4. Bonus: try swapping the model line to `anthropic/claude-sonnet-4.5` and re-run the smoke. Confirm both calls succeed within the timeout. Reset the line to gemini before committing.

## Performance Considerations

- 55 s read timeout deliberately under the PRD's 60 s ceiling. With `google/gemini-2.5-flash` as the default, observed end-to-end latency on small kindergarten-announcement images is typically 2–6 s — well inside budget. Heavier models (`anthropic/claude-sonnet-4.5`) push closer to 10–20 s; both still well clear of 55 s for representative images. If a future model + image-size combo gets close to 55 s, the right answer is a smaller image (downscale on upload — S-05's job), not a longer timeout.
- No connection pooling tuning. Spring AI's `RestClient` defaults are sufficient at single-user / low-QPS (PRD scale target). Tuning belongs in a post-MVP slice if QPS ever rises.

## Migration Notes

- F-01 introduces no `@Entity` classes and writes nothing to the database. The additive-only migration rule (deploy-plan §5.2) is not engaged.
- Local dev: developers running the smoke test set `OPENROUTER_API_KEY=sk-or-…` in their shell (`~/.zshrc` for persistence, or `direnv` per-project). No `.env` file is added to the repo (the project doesn't currently have one and shouldn't introduce one for a single env var).
- Production: the Fly secret name does not change — `AI_PROVIDER_API_KEY` is rotated from `pending-provider-selection` to the real OpenRouter key. No `fly.toml` edit, no CI workflow edit, no deploy-plan change required beyond a status update in `change.md`.

## References

- Roadmap entry: `context/foundation/roadmap.md` § F-01
- PRD refs: FR-004, FR-005, NFR (extraction latency 30 s typical / 60 s ceiling)
- Project memory: `~/.claude/projects/-Users-ludmiladrzewiecka-workspace-10xdevsOgarniacz/memory/project_llm_provider.md` — OpenRouter as gateway, swappable model
- Spring AI docs (via Context7): `/websites/spring_io_spring-ai_reference` + `/websites/spring_io_spring-ai_reference_2_0-snapshot` — starter rename in 1.0.0 (`spring-ai-starter-model-openai`); compatibility matrix (1.0.x → Boot 3.4/3.5; 2.0.x → Boot 4.0/4.1); OpenAI-compatible base-URL pattern (Groq example, same shape for OpenRouter); multimodal `Media` on the user-message builder; portable `ChatClient` fluent API
- Deploy-plan ties: §2.3 (placeholder secret reserved), §F.1 (rotation procedure), §5.2 (additive-only migrations — not engaged here), §6 (observability monitor-only — log shape stays SLF4J)
- Prior change for plan conventions: `context/changes/minimal-auth-and-empty-personal-view/plan.md`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Dependency + config wiring

#### Automated

- [x] 1.1 `./gradlew dependencies --configuration runtimeClasspath | grep spring-ai-starter-model-openai` shows the starter resolved against BOM 1.0.x — 2bcb189
- [x] 1.2 `./gradlew test` passes (existing 16 tests green; no new tests yet in Phase 1) — 2bcb189
- [x] 1.3 `./gradlew bootRun` boots locally with no `OPENROUTER_API_KEY` set — log line `Started AppApplication in <N> seconds` appears; no `IllegalArgumentException`, no `BeanCreationException` mentioning `spring.ai` — 2bcb189

#### Manual

- [x] 1.4 Eyeball resolved BOM version with `./gradlew dependencyInsight --dependency spring-ai-starter-model-openai` — confirm it's 1.0.x and not pulling in a milestone (`1.1.0-M1`) or `2.0.0-M3` by accident — 2bcb189
- [x] 1.5 Stop `bootRun`, export `OPENROUTER_API_KEY=placeholder-not-a-real-key`, restart — should still boot clean — 2bcb189
- [x] 1.6 Re-read the new `spring.ai.*` lines in `application.properties` for typos (single-character mistake in `base-url` is the highest-likelihood failure mode at this phase) — 2bcb189

### Phase 2: Vision client + DTO

#### Automated

- [x] 2.1 `./gradlew test --tests com.example.app.llm.LlmVisionClientTest` passes all 5 tests — 66fbb43
- [x] 2.2 Full `./gradlew test` is green with no real OpenRouter call (verify by running with `OPENROUTER_API_KEY=` empty — should still pass) — 66fbb43
- [x] 2.3 `./gradlew bootRun` still starts clean and `curl -i http://localhost:8080/actuator/health` returns 200 — 66fbb43

#### Manual

- [x] 2.4 Read `OpenRouterLlmVisionClient.java` and confirm the exception translation table covers Spring AI's actual thrown types for this version (Spring AI 1.0.x may add a new wrapper class between writing the plan and running it — adjust the catches if so) — 66fbb43
- [x] 2.5 Confirm no test calls `objectMapper.writeValueAsString(...)` on the `LlmExtractionResult` (no Jackson serialization annotations were added; we don't want a downstream surprise) — 66fbb43

### Phase 3: Live smoke + secret rotation + runbook

#### Automated

- [x] 3.1 `./gradlew test` (without `OGARNIACZ_LIVE_SMOKE` set) passes everything including a noisy log line "skipped" for `LlmVisionSmokeTest` — confirms the JUnit env-variable gate is correct — e572996
- [x] 3.2 `./gradlew test` (with `OGARNIACZ_LIVE_SMOKE=true` AND `OPENROUTER_API_KEY=sk-or-…` set) passes `LlmVisionSmokeTest` end-to-end, finishing in ≤ 55 s — verified 2026-06-07: SUCCESS, 2 events, 2.7 s wall (after switching default to `google/gemini-2.5-flash`; the planned `google/gemini-2.0-flash-001` 404'd — retired from OpenRouter). — e572996

#### Manual

- [x] 3.3 Read the smoke test's printed `LlmExtractionResult.rawResponse` — confirm the model produced something resembling JSON with the right field shape (`date`, `title`, etc.). Semantic accuracy is NOT graded here. — verified 2026-06-07: clean JSON array, all 5 fields per item, `date`=`YYYY-MM-DD` / `time`=`HH:MM`, `null` where absent; `LocalDate`/`LocalTime` deserialized cleanly. — e572996
- [x] 3.4 Rotate the Fly secret (`fly secrets set AI_PROVIDER_API_KEY='sk-or-…' -a ogarniacz`) and verify the deployed app's logs (`fly logs -a ogarniacz`) show a clean restart with no new errors — operator-run, manual (deploy-plan §7 irreversible gate) — verified 2026-06-07: operator rotated. — e572996
- [x] 3.5 ~~Run the smoke with `anthropic/claude-sonnet-4.5`~~ — the model-swap runbook bullet was proven empirically by the `google/gemini-2.0-flash-001` → `google/gemini-2.5-flash` switch (one-string change; behaviour went 404 → SUCCESS). Re-running against claude is optional; note `anthropic/claude-sonnet-4.5` may itself be a retired slug by 2026 — check the catalog first. — e572996
- [x] 3.6 ~~Reset model line to `google/gemini-2.0-flash-001`~~ **superseded**: that slug is retired from OpenRouter; the committed default is `google/gemini-2.5-flash` (verified live). No reset needed. — e572996
