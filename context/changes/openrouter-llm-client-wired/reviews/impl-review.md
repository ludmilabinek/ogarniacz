<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: OpenRouter LLM Client Wired (F-01)

- **Plan**: context/changes/openrouter-llm-client-wired/plan.md
- **Scope**: All 3 phases
- **Date**: 2026-06-07
- **Verdict**: NEEDS ATTENTION
- **Findings**: 0 critical, 4 warnings, 0 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | WARNING |
| Scope Discipline | PASS |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

## PASS-dimension notes

- **Scope Discipline** — every "NOT doing" item honored; no entities, no controllers, no Resilience4j, no `BeanOutputConverter`, no metrics beyond SLF4J. Struck-through `OpenRouterRestClientConfig` is correctly absent.
- **Architecture** — single seam (`LlmVisionClient`), single envelope (`LlmExtractionException` with `Kind`/`httpStatus`/`providerMessage`), `ChatClient` built once and reused.
- **Pattern Consistency** — codebase is small; no strongly established pattern to clash with.
- **Success Criteria** — `./gradlew dependencies` shows the starter resolved against BOM 2.0.0-M6; `./gradlew test` is `BUILD SUCCESSFUL` with no LLM env vars; live smoke + Fly rotation documented as verified in `change.md` on 2026-06-07.

## Sanctioned drift (not a finding)

- Exception-translation table catches OpenAI SDK / Jackson 3 types (`OpenAIIoException`, `OpenAIServiceException`, `OpenAIInvalidDataException`, `JacksonException`) instead of the plan-named `SocketTimeoutException`/`ResourceAccessException`/`RestClientResponseException`/`JsonProcessingException`. Phase 2 manual check 2.4 explicitly anticipated this ("Spring AI 1.0.x may add a new wrapper class … adjust the catches if so"); the actual M6 SDK shape is openai-java + Jackson 3. The test file mirrors the drift correctly.

## Findings

### F1 — Observability log line omits the `model` field

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: src/main/java/com/example/app/llm/OpenRouterLlmVisionClient.java:59-77
- **Detail**: The plan's "Observability — what to log, what to NOT log" section is explicit: "Log at INFO on every call: `model`, `imageBytes`, `mimeType`, `latencyMillis`, outcome". Actual log lines emit `imageHash bytes mimeType latencyMs outcome [events|httpStatus]` — `model` is absent. With the model swappable via `spring.ai.openai.chat.options.model`, a future log search for "which calls hit Gemini vs Claude" has no in-line signal — it has to be reconstructed from deploy history. (Field renames `imageBytes`→`bytes`, `latencyMillis`→`latencyMs` are noise; the missing `model` is load-bearing.)
- **Fix**: Inject the configured model via `@Value("${spring.ai.openai.chat.options.model}") String configuredModel` into the constructor; include `model={}` in every log line (all 4 outcomes).
- **Decision**: FIXED

### F2 — Catch chain leaks uncategorized `RuntimeException`

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: src/main/java/com/example/app/llm/OpenRouterLlmVisionClient.java:62-78
- **Detail**: The four catches handle `OpenAIIoException`, `OpenAIServiceException`, `OpenAIInvalidDataException`, `JacksonException`. Anything else escapes as a raw exception — e.g. `new ByteArrayResource(image)` throws `IllegalArgumentException` synchronously when `image == null`, a Spring AI wrapper that doesn't extend the four types, `IllegalStateException` from a builder, or any future M7+ rename. The plan's "Error envelope" section is explicit that S-05's controller catches ONE type (`LlmExtractionException`). Uncategorized exceptions break that contract and force every caller to layer a second catch.
- **Fix A ⭐ Recommended**: Add a final `catch (RuntimeException e)` mapping to `provider(0, e.getMessage(), e)`
  - Strength: One-line guarantee that every failure translates through the boundary. Future SDK renames don't silently leak. Catches the null-image NPE class too.
  - Tradeoff: `kind=PROVIDER_ERROR` becomes mildly overloaded — truly internal errors share a bucket with provider 4xx/5xx. Acceptable given S-05's copy already says "we couldn't reach the extractor".
  - Confidence: HIGH — pattern is standard for boundary translators.
  - Blind spot: Doesn't add a distinct kind for "unknown internal"; if future ops needs to separate them, `providerMessage` carries enough signal.
- **Fix B**: Add a fifth `Kind.UNKNOWN` and a `LlmExtractionException.unknown(Throwable)` factory
  - Strength: Distinct bucket lets S-05 / monitoring split copy if needed; keeps `PROVIDER_ERROR` semantically pure.
  - Tradeoff: Wider blast — new enum value, new factory, S-05 copy decision deferred from a future slice to now. Plan said three Kinds; this expands the contract.
  - Confidence: MEDIUM — works, but introduces a contract change that should be agreed with the S-05 author first.
  - Blind spot: FR-005's three copy variants assumed three kinds; a fourth needs its own user-facing copy.
- **Decision**: FIXED via Fix A (with `outcome=FAILED:UNKNOWN` log distinction)

### F3 — Markdown-fence regex strips only one trailing terminator

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/com/example/app/llm/OpenRouterLlmVisionClient.java:85
- **Detail**: `replaceAll("\\s*```$", "")` uses default `Pattern` semantics where `$` matches before a single trailing line terminator. If the model emits ` ```\n\n` (observed occasionally in vision-model output), the closing fence is not stripped and `objectMapper.readValue` throws `MALFORMED_RESPONSE` on otherwise-valid JSON. Today's smoke happens to pass — this is a latent fragility, not a current bug. The unit test `extractStripsMarkdownFencesAroundJson` covers only the strict-format case.
- **Fix**: Replace the two-call chain with `Pattern.compile("\\[.*\\]", Pattern.DOTALL).matcher(raw)` and take the first match's `group(0)`. Add a unit test covering trailing `\n\n` after the closing fence so the contract is locked in.
- **Decision**: FIXED (renamed `stripMarkdownFences` → `extractJsonArray`; added `extractHandlesTrailingWhitespaceAfterFence` test)

### F4 — providerMessage captures full response body unbounded

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: src/main/java/com/example/app/llm/OpenRouterLlmVisionClient.java:69
- **Detail**: `String body = e.body() != null ? e.body().toString() : null;` captures the entire OpenRouter error body and stores it on the `LlmExtractionException.providerMessage` field. The plan's Observability section explicitly says "Do NOT log the raw response body … may contain extracted PII from a kindergarten note." That guarantee holds at the local log line (good — body is NOT logged at line 70-71), but the field rides on the exception, so any downstream handler that logs `exception.getMessage()` or serializes the exception breaks the promise. On a 4xx tied to image content (e.g. 413/422), the body can echo Polish parent text or kindergarten names back.
- **Fix A ⭐ Recommended**: Truncate to a fixed budget at the source
  - Strength: One-line cap (e.g. 500 chars) prevents unbounded log payloads while preserving the OpenAI error `code`/`type` fields that always sit early in the JSON. Doesn't change the field's contract.
  - Tradeoff: Body is structured JSON; a fixed 500-char cut may land mid-string and look ugly. Cosmetic only — the diagnostic content is intact.
  - Confidence: HIGH — standard truncation pattern; openai-java bodies are typically <500 chars on a structured error.
  - Blind spot: A future error body bigger than 500 chars with the message buried late would lose its tail.
- **Fix B**: Parse the OpenAI error JSON and keep only allowlisted fields
  - Strength: Structured, PII-free providerMessage — only `code`, `type`, `message`, `param`. Logging it anywhere is safe by construction.
  - Tradeoff: Adds JSON parsing on the error path (more code, more failure modes — what if the body isn't JSON?). Requires a fallback for non-JSON bodies.
  - Confidence: MEDIUM — correct in shape but introduces parsing on a path that's already handling a failure.
  - Blind spot: Non-JSON error responses (HTML 502 from an edge, truncated body) need their own fallback.
- **Decision**: FIXED via Fix B (hybrid — used typed SDK accessors `e.code()/e.type()/e.param()` instead of JSON parsing, sidestepping the non-JSON fallback concern)
