---
date: 2026-06-29T08:15:33Z
researcher: Ludmiła Drzewiecka
git_commit: d72fd4e002822dbf34ae28eeb937a32f7f92214c
branch: main
repository: 10xdevsOgarniacz
topic: "Sentry instrumentation (errors-only) for Ogarniacz"
tags: [research, codebase, sentry, observability, error-tracking, spring-boot-4, pii, async]
status: complete
last_updated: 2026-06-29
last_updated_by: Ludmiła Drzewiecka
---

# Research: Sentry instrumentation (errors-only) for Ogarniacz

**Date**: 2026-06-29T08:15:33Z
**Researcher**: Ludmiła Drzewiecka
**Git Commit**: d72fd4e002822dbf34ae28eeb937a32f7f92214c
**Branch**: main
**Repository**: 10xdevsOgarniacz

## Research Question

Adopt Sentry for error tracking on Ogarniacz (Spring Boot 4.0.6 / Java 21 / Gradle on Fly.io). Errors-only scope — no performance tracing, no logs ingestion, no profiling. Three focus areas drove the research: (1) Spring Boot 4 SDK compatibility, (2) PII / privacy filtering in a kindergarten-event domain, (3) async / scheduled error capture in a codebase whose hottest failure paths run off-thread.

## Summary

**Sentry officially ships a dedicated Spring Boot 4 starter** — `io.sentry:sentry-spring-boot-4` at v8.15.1 (with the `sentry-java` BOM at 8.43.0+), plus a sibling `io.sentry:sentry-spring7` for plain Spring Framework 7. No manual `Sentry.init()` workaround needed, no fallback to the Boot 3 `sentry-spring-boot-starter-jakarta`. Pair it with `io.sentry:sentry-logback` for error capture via the Logback appender, which the starter auto-attaches.

**The errors-only wiring is small** — five `application.properties` keys plus two Gradle deps. The DSN follows the `${SENTRY_DSN:}` env-placeholder pattern that already dominates [`application.properties`](src/main/resources/application.properties:1-58); the kill-switch is `sentry.enabled=false` in `application-test.properties` / `application-e2e.properties`.

**The hard work is not the wiring — it is the scrubbing.** Five PII surfaces leak by default and must be neutralized before the first event ships:
1. iCal token in URL path (`/calendar/{token}.ics`) — the token IS the credential.
2. User email auto-attached as `user.username` (Spring Security integration + `send-default-pii=true`).
3. Photo bytes via `EventReviewController`'s model containing `SourceImage.data` (bytea).
4. Extracted event content (`title`, `requirements`, `notes`) in form-binding errors and `LlmExtractionResult.rawResponse`.
5. `persistent_logins.username` (= email) in any DB error context.

**The async architecture has two silent-failure spots** that Sentry's request-thread auto-attach will NOT cover automatically:
- `SourceImagePurgeScheduler.sweep()` ([SourceImagePurgeScheduler.java:50-58](src/main/java/com/example/app/event/SourceImagePurgeScheduler.java)) — `@Scheduled`, catches `RuntimeException`, logs ERROR, **does not rethrow**.
- `ExtractionService.runExtraction()` ([ExtractionService.java:48-91](src/main/java/com/example/app/event/ExtractionService.java)) — `@Async("extractionExecutor")` on a void method, catches `LlmExtractionException` and `RuntimeException`, logs ERROR, **does not rethrow**.

Both rely on `log.error(...)` for forensics today. **The cheapest path to capture them is the Logback appender** — once `sentry-logback` is on the classpath, every `log.error(...)` becomes a Sentry event automatically. No code changes inside the schedulers/services required. (A `SentryTaskDecorator` bean is still worth wiring for scope-attribute propagation, but not the load-bearing mechanism for error visibility.)

## Detailed Findings

### Sentry SDK for Spring Boot 4

**Artifact and version.** From the Sentry docs primer (`/getsentry/sentry-docs`, `platform-includes/getting-started-primer/java.spring-boot.mdx`):

> "Sentry provides specific starter dependencies for different Spring Boot versions. For Spring Boot 2, use `sentry-spring-boot-starter`. For Spring Boot 3, use `sentry-spring-boot-starter-jakarta`. **For Spring Boot 4, use `sentry-spring-boot-4`**."

Gradle DSL:
```groovy
implementation 'io.sentry:sentry-spring-boot-4:8.15.1'   // bump to latest 8.x at integration time
implementation 'io.sentry:sentry-logback:8.15.1'         // match version
```

A working sample app for Boot 4 is checked into the SDK repo: `sentry-samples/sentry-samples-spring-boot-4-opentelemetry-noagent` (`/getsentry/sentry-java`).

**No OpenTelemetry agent required for errors-only.** The agent (`sentry-opentelemetry-agent`) is only mentioned in the docs in the context of distributed tracing.

**Boot 4's `spring-boot-starter-webmvc` rename has no impact** — the Sentry starter activates on the presence of `spring-webmvc` on the classpath, which both Boot 3's `spring-boot-starter-web` and Boot 4's `spring-boot-starter-webmvc` transitively pull in. The starter's auto-config keys reference `sentry.*` properties, not `spring.web.*`.

**Known issues:** Context7 surfaced **no blockers, no partial-support warnings**. The `sentry-java` changelog top-of-tree is 8.43.0, well past the 8.15.1 first-release of `sentry-spring-boot-4` — multiple maintenance releases have already landed for the Boot 4 line.

**Coverage gap:** Context7 did not surface the exact release date when `sentry-spring-boot-4` first shipped. If exact dates matter for risk assessment, fall back to GitHub releases via WebFetch — but the docs coverage is sufficient to commit to the integration today.

### Minimal errors-only configuration

The Sentry docs primer (`/getsentry/sentry-docs`, `platform-includes/getting-started-config/java.spring-boot.mdx`) shows the full-fat config; the errors-only subset for Ogarniacz is:

```properties
# Production application.properties
sentry.dsn=${SENTRY_DSN:}
sentry.enabled=${SENTRY_ENABLED:true}
sentry.traces-sample-rate=0.0
sentry.profile-session-sample-rate=0.0
sentry.logs.enabled=false
sentry.send-default-pii=false
sentry.in-app-includes=com.example.app
sentry.logging.minimum-event-level=error
sentry.logging.minimum-breadcrumb-level=info
sentry.release=${SENTRY_RELEASE:}
sentry.environment=${SENTRY_ENVIRONMENT:production}
```

```properties
# application-test.properties + application-e2e.properties
sentry.enabled=false
sentry.dsn=
```

Key reasoning:
- **`${SENTRY_DSN:}`** — empty default = disabled; same pattern as [`spring.ai.openai.api-key`](src/main/resources/application.properties:37) which already uses `${OPENROUTER_API_KEY:${AI_PROVIDER_API_KEY:placeholder}}`.
- **`sentry.enabled=${SENTRY_ENABLED:true}`** — canonical kill-switch via `ExternalOptions.from()` (`/getsentry/sentry-java`, `ExternalOptions.java`); accepts the same `${ENV:default}` syntax as everything else.
- **`sentry.send-default-pii=false`** — the SDK's PII gate; controls IP capture and `Principal#name → user.username` attachment from HTTP requests (`/getsentry/sentry-docs`, `docs/platforms/java/guides/spring-boot/record-user.mdx`).
- **`sentry.in-app-includes=com.example.app`** — matches the project's only package; stack frames in your code render as in-app, kindergarten-extraction errors group correctly.
- **`sentry.logging.minimum-event-level=error`** — wires the Logback appender to send `log.error(...)` calls as Sentry events. INFO/WARN become breadcrumbs.

### Async / scheduled error capture

**The Spring 7 / Boot 4 SDK package is `io.sentry.spring7.*`** — confirmed via doc imports like `io.sentry.spring7.SentryTaskDecorator`, `io.sentry.spring7.tracing.SentrySpan`, `io.sentry.spring7.EnableSentry` (`/getsentry/sentry-docs`, `docs/platforms/java/common/async/index.mdx`).

**`SentryTaskDecorator` for @Async hub propagation** — declare as a `@Bean`; Spring auto-applies it to executors created from `TaskExecutorBuilder` defaults. For named executors (Ogarniacz has [`extractionExecutor`](src/main/java/com/example/app/AppApplication.java:41-52), core=2, max=2, queue=10), wire it explicitly:

```java
@Configuration
class SentryConfig {
    @Bean
    public SentryTaskDecorator sentryTaskDecorator() {
        return new SentryTaskDecorator();
    }
}
```

…then in [`AppApplication.extractionExecutor()`](src/main/java/com/example/app/AppApplication.java:41-52) call `exec.setTaskDecorator(new SentryTaskDecorator())` (or inject the bean). The decorator's role is to copy scope/tags onto the executor thread — **it is not what propagates exceptions**; the Logback appender does that.

**For `@Scheduled` methods** ([SourceImagePurgeScheduler.java:47](src/main/java/com/example/app/event/SourceImagePurgeScheduler.java), [ExtractionJobRegistry.java:81](src/main/java/com/example/app/event/ExtractionJobRegistry.java)) — Context7 surfaced no Sentry-specific `@Scheduled` auto-config. The exceptions land in the Spring `TaskUtils$LoggingErrorHandler` at ERROR level → the Logback appender captures them. If you want scope propagation onto the scheduled thread, register a `ThreadPoolTaskScheduler` with `setTaskDecorator(new SentryTaskDecorator())` via a `SchedulingConfigurer` — but this is polish, not load-bearing.

**`AsyncUncaughtExceptionHandler`** — generic Spring pattern: implement, call `Sentry.captureException(throwable)` in `handleUncaughtException`. The Sentry side is just a one-liner.

### Current error surface — where exceptions arise today

**No `@ControllerAdvice` or `@RestControllerAdvice` exists.** The closest thing is [`MaxUploadSizeExceededHandler`](src/main/java/com/example/app/event/MaxUploadSizeExceededHandler.java) — an `OncePerRequestFilter` at `Ordered.HIGHEST_PRECEDENCE` that catches `MaxUploadSizeExceededException` / `SizeException` for `/events/from-image*` paths and writes an HTTP 413 JSON envelope. It does not log to Sentry today.

**Controllers throw `ResponseStatusException(NOT_FOUND)` via `Optional.orElseThrow(...)`** consistently — verified across [`EventController.java:140`](src/main/java/com/example/app/event/EventController.java), [`EventReviewController.java:91,131,153`](src/main/java/com/example/app/event/EventReviewController.java). These are intentional 404s, not errors worth Sentry-capturing.

**User-lookup `orElseThrow()` without an explicit exception type** throws `NoSuchElementException` and propagates to Spring's default error handler (HTTP 500) — silent in logs, would surface in Sentry as "uncaught at controller" once instrumented. Locations:
- [`AppController.java:43`](src/main/java/com/example/app/web/AppController.java)
- [`SettingsController.java:23`](src/main/java/com/example/app/user/SettingsController.java)
- [`EventController.java:59,75,109,127`](src/main/java/com/example/app/event/EventController.java)
- [`ImageUploadController.java:69`](src/main/java/com/example/app/event/ImageUploadController.java)
- [`ExtractionStatusController.java:65`](src/main/java/com/example/app/event/ExtractionStatusController.java)

These would be the **first signal Sentry surfaces**: a session-expiry/auth-state mismatch turning a normal action into a 500. Useful diagnostic, low PII risk (no body, just stack).

**Custom exception types** — there is exactly one: [`LlmExtractionException`](src/main/java/com/example/app/llm/LlmExtractionException.java) (RuntimeException with `Kind` enum: TIMEOUT, PROVIDER_ERROR, MALFORMED_RESPONSE, plus an UNEXPECTED variant). Thrown by [`OpenRouterLlmVisionClient.extract()`](src/main/java/com/example/app/llm/OpenRouterLlmVisionClient.java:98-121), caught by [`ExtractionService.runExtraction()`](src/main/java/com/example/app/event/ExtractionService.java:70-77).

**Three loggers exist in production code, all SLF4J:**
- [`ExtractionService`](src/main/java/com/example/app/event/ExtractionService.java:28) — 3× `log.error(...)`.
- [`OpenRouterLlmVisionClient`](src/main/java/com/example/app/llm/OpenRouterLlmVisionClient.java:26) — 5× `log.info(...)` (outcome metrics, not errors — would not auto-promote to Sentry events at `minimum-event-level=error`).
- [`SourceImagePurgeScheduler`](src/main/java/com/example/app/event/SourceImagePurgeScheduler.java:37) — 1× INFO, 1× ERROR.

**No `logback-spring.xml` / `logback.xml` exists** — Spring Boot's default config is in effect. Adding `sentry-logback` to the classpath auto-attaches the appender; no XML required unless you want MDC `<contextTag>` mappings (and the project uses no MDC today).

**Correlation IDs already exist** — [`ExtractionService.java:93-94`](src/main/java/com/example/app/event/ExtractionService.java) generates `UUID.randomUUID().toString().substring(0, 8)` per extraction failure, stamped onto both the log line and `SourceImage.lastErrorKind` / `correlationId`. This pairs well with Sentry's per-event ID — surfacing both side by side lets you cross-reference in-app error pages with Sentry's dashboard.

### Async / scheduled silent-failure inventory

| Spot | File:line | Mechanism | Today | After errors-only Sentry |
|---|---|---|---|---|
| `ExtractionService.runExtraction()` happy path | [event/ExtractionService.java:48-91](src/main/java/com/example/app/event/ExtractionService.java) | `@Async("extractionExecutor")`, void return | Catches both `LlmExtractionException` and `RuntimeException`, calls `log.error(...)` and marks `ExtractionJobRegistry` FAILED — **never rethrows** | Logback appender forwards both `log.error` calls as Sentry events. Stack trace + extracted-kind tag visible. Job registry path unchanged. |
| `ExtractionService.runExtraction()` before-try | [event/ExtractionService.java:50-56](src/main/java/com/example/app/event/ExtractionService.java) | Pre-try `sourceImageRepository.findById()` | If it throws (DB connection error), propagates to Spring's default `SimpleAsyncUncaughtExceptionHandler` → logs at ERROR + ignores | Covered by Logback appender (Spring's handler uses an SLF4J logger). |
| `SourceImagePurgeScheduler.sweep()` | [event/SourceImagePurgeScheduler.java:50-58](src/main/java/com/example/app/event/SourceImagePurgeScheduler.java) | `@Scheduled(fixedDelayString=...)`, default schedule 10 min, 10 s in e2e | Catches `RuntimeException`, calls `log.error(...)`, **does not rethrow** | Covered by Logback appender. Critical because the operating-by-design path is "log loud if the sweep stalls"; Sentry makes that loud across the network, not just in `fly logs`. |
| `ExtractionJobRegistry.sweep()` | [event/ExtractionJobRegistry.java:81-85](src/main/java/com/example/app/event/ExtractionJobRegistry.java) | `@Scheduled(fixedDelay=60_000L)`, no try/catch | `ConcurrentHashMap.removeIf()` on TTL'd entries; any throw bubbles to Spring's `TaskUtils$LoggingErrorHandler` → logs at ERROR + swallows | Covered by Logback appender. |
| `MaxUploadSizeExceededHandler` size-limit branch | [event/MaxUploadSizeExceededHandler.java:56-67](src/main/java/com/example/app/event/MaxUploadSizeExceededHandler.java) | Filter catches `MaxUploadSizeExceededException` / `SizeException` and writes HTTP 413 | No log call today; user gets the JSON envelope, ops gets nothing | **Will NOT appear in Sentry** — the filter handles cleanly with no `log.error` or rethrow. If oversize uploads are a signal worth seeing in Sentry, add a `log.warn(...)` and bump `minimum-event-level=warn`. **Decision deferred to /10x-plan.** |
| `IcalFeedWriter.write()` failure | [event/IcalFeedWriter.java:126-130](src/main/java/com/example/app/event/IcalFeedWriter.java) | Wraps `IOException` from ical4j as `IllegalStateException("ical4j outputter failed", e)` — propagated, not logged | Bubbles to `CalendarController.renderFeed()` → default 500 page | Captured by Sentry's HTTP-request integration as an uncaught controller exception. |
| `SignupController` duplicate-email race | [user/SignupController.java:70-75](src/main/java/com/example/app/user/SignupController.java) | Catches `DataIntegrityViolationException`, rejects field, re-renders form | No log; user-facing validation error | **Will NOT appear in Sentry** — this is correct behavior; not an error worth alerting on. |

**Key observation:** Of the four async/scheduled spots, **the Logback appender covers all of them automatically** because every one of them already calls `log.error(...)` or has Spring's default error handler doing the logging. No surgery inside `ExtractionService` / `SourceImagePurgeScheduler` / `ExtractionJobRegistry` is required for visibility. The `SentryTaskDecorator` bean adds scope-propagation polish but is not load-bearing.

### PII risk register

The kindergarten-event domain makes Sentry PII careful — extracted event content can name children, teachers, and addresses. Eight surfaces would leak by default:

| # | Surface | Where | Sentry default behavior | Mitigation |
|---|---|---|---|---|
| 1 | **iCal token in URL path** (`/calendar/{token}.ics`) | [event/CalendarController.java:39](src/main/java/com/example/app/event/CalendarController.java); composed in [user/SettingsController.java:30-35](src/main/java/com/example/app/user/SettingsController.java) | Captured in request URL on any error during feed render; the token IS the only credential for the feed | `BeforeSendCallback` strips/redacts the path segment if it matches `/calendar/[^.]+\.ics`. **Critical — non-negotiable.** |
| 2 | **User email as `user.username`** | [config/SecurityConfig.java](src/main/java/com/example/app/config/SecurityConfig.java); `Authentication.getName()` in controllers | Spring Security integration auto-attaches `Principal#name` when `send-default-pii=true` | `sentry.send-default-pii=false` (already in proposed config). Optionally: pseudonymize user.id only via `BeforeSendCallback` if attribution is needed. |
| 3 | **Photo bytes via view-model** | [event/EventReviewController.java:96](src/main/java/com/example/app/event/EventReviewController.java) adds `sourceImage` to Thymeleaf model; `SourceImage.data` is `bytea` ([event/SourceImage.java:35](src/main/java/com/example/app/event/SourceImage.java)) | If a Thymeleaf rendering exception captures the model in event context, the byte array could serialize | `BeforeSendCallback` rejects events whose `extra` contains a key named `sourceImage` or removes the `data` field. **Critical.** |
| 4 | **Extracted event content** (`title`, `requirements`, `notes`) | [event/ProposedEvent.java:56,59,62](src/main/java/com/example/app/event/ProposedEvent.java), [event/Event.java:43-49](src/main/java/com/example/app/event/Event.java), [event/EventReviewForm.java](src/main/java/com/example/app/event/EventReviewForm.java) | Form-binding errors (Spring's `FieldError.getInvalidValue()`) echo the offending value; if those become event extras, child/teacher names leak | `BeforeSendCallback` strips form-error `invalidValue` payloads — keep field names and codes, drop the values. |
| 5 | **`LlmExtractionResult.rawResponse`** | [llm/LlmExtractionResult.java](src/main/java/com/example/app/llm/LlmExtractionResult.java) — `String rawResponse` field holds the LLM's full JSON output (= every extracted title/requirements/notes from the photo) | If an exception caught in [event/ExtractionService.java:78-89](src/main/java/com/example/app/event/ExtractionService.java) carries the result object as state or in its message, the rawResponse leaks | The current catch (`UNEXPECTED` branch) does NOT include `rawResponse` in the log line — but be vigilant; `BeforeSendCallback` should strip any extra named `rawResponse` defensively. |
| 6 | **`persistent_logins.username`** (= email) | Spring Security's `PersistentTokenBasedRememberMeServices` writes to this table ([config/SecurityConfig.java:56-65](src/main/java/com/example/app/config/SecurityConfig.java)); schema in [src/test/resources/schema-e2e.sql](src/test/resources/schema-e2e.sql) | Any DB error that includes the offending row in its message leaks the email | `BeforeSendCallback` plus `server.error.include-stacktrace=never` (already set, [application.properties:54](src/main/resources/application.properties)) cuts most leakage; for SQL exceptions, also consider scrubbing `message` for `@` patterns. |
| 7 | **OpenRouter API key fragments** | [application.properties:37](src/main/resources/application.properties) — `spring.ai.openai.api-key=${OPENROUTER_API_KEY:${AI_PROVIDER_API_KEY:...}}` | OpenAI SDK exception messages can echo part of the key on auth failures | `BeforeSendCallback` strips `Authorization: Bearer ...` patterns from `request.headers` and `extra` strings. |
| 8 | **Request headers (Cookie, Authorization)** | Captured by default when `send-default-pii=true`; the Sentry server-side scrubber covers `Authorization` / `Cookie` headers by default | With `send-default-pii=false` already, headers are not auto-attached. | No additional action required — but reverify with a smoke test before declaring shipped. |

**Single chokepoint for all of the above: a `BeforeSendCallback` bean.** Sentry allows exactly one (`/getsentry/sentry-docs`, `docs/platforms/java/guides/spring-boot/advanced-usage.mdx`: "Only a single `BeforeSendCallback` bean can be registered"). That callback is the place where all the project-specific scrubbing rules live. Plan it as a single Spring `@Component` with explicit rules, not as scattered field-level annotations.

**Coverage gap:** Context7 did not return a definitive Java-side list of what `send-default-pii` toggles for cookies / request body — the JavaScript `RequestData` integration is documented, but that's the JS integration, not Java. **Treat the Java behavior conservatively** — assume `send-default-pii=false` disables IP, Principal username, and header-with-PII attachment, but verify on the first deploy by triggering a known error and inspecting the event payload.

### Environment / profile configuration patterns

The project already has the patterns Sentry slots into:

- **`${ENV_VAR:default}` placeholders** — every secret in [application.properties](src/main/resources/application.properties) follows this shape; Sentry's keys fit identically.
- **Spring profile `e2e`** — [`src/test/resources/application-e2e.properties`](src/test/resources/application-e2e.properties) is the canonical e2e override; the project already swaps `OpenRouterLlmVisionClient` for `StubLlmVisionClient` ([`@Profile("e2e")`, `@Primary`](src/test/java/com/example/app/llm/StubLlmVisionClient.java)) under that profile.
- **Fly secrets pattern** — secrets like `AI_PROVIDER_API_KEY` are set via `flyctl secrets set`, materialized as env vars at runtime ([fly.toml](fly.toml), [deploy.yml](.github/workflows/deploy.yml)). `SENTRY_DSN` and `SENTRY_RELEASE` follow the same convention.

**Profile gating for Sentry (proposed):**

```properties
# src/test/resources/application-e2e.properties — add at end
sentry.enabled=false
sentry.dsn=
```

```properties
# src/test/resources/application-test.properties — create if missing, same content
sentry.enabled=false
sentry.dsn=
```

There is no `application-test.properties` today — only `application-e2e.properties`. Default `@SpringBootTest` boot uses the main `application.properties`; `sentry.dsn=${SENTRY_DSN:}` with no env var defaults to empty → SDK disabled → no transport. So adding `application-test.properties` is **defense in depth**, not strictly required. Decision deferred to `/10x-plan`.

**No `application-prod.properties` exists** — production config IS the main `application.properties` with env-var overrides. Sentry's "production" environment tag should come from `SENTRY_ENVIRONMENT` set in Fly secrets, NOT from a Spring profile.

### Release tagging

`sentry.release` and `sentry.environment` can come from env vars natively (standard SDK convention via `ExternalOptions`). Minimal Ogarniacz wiring:

```properties
sentry.release=${SENTRY_RELEASE:}
sentry.environment=${SENTRY_ENVIRONMENT:production}
```

In Fly, set `SENTRY_RELEASE=$FLY_RELEASE_VERSION` (or the git SHA from CI) and `SENTRY_ENVIRONMENT=production`. The `io.sentry.jvm.gradle` plugin is **not** needed for errors-only — it adds source-context upload (lets Sentry render decompiled stack frames), which is polish.

**`build.gradle` is clean** — Spring Boot BOM + dep-management plugin manage versions; no conflicts expected when adding `sentry-spring-boot-4` and `sentry-logback`. Repositories include `mavenCentral()` plus the Spring milestone repo for spring-ai pre-release ([build.gradle:16-19](build.gradle)) — Sentry resolves cleanly from Maven Central.

## Code References

### Error surface to instrument
- [`src/main/java/com/example/app/event/MaxUploadSizeExceededHandler.java`](src/main/java/com/example/app/event/MaxUploadSizeExceededHandler.java) — OncePerRequestFilter, runs before Security; the only existing global error handler. No log call today.
- [`src/main/java/com/example/app/event/ExtractionService.java:70-89`](src/main/java/com/example/app/event/ExtractionService.java) — async catches both `LlmExtractionException` and `RuntimeException`, `log.error(...)` only.
- [`src/main/java/com/example/app/event/SourceImagePurgeScheduler.java:50-58`](src/main/java/com/example/app/event/SourceImagePurgeScheduler.java) — `@Scheduled`, catches and logs, no rethrow.
- [`src/main/java/com/example/app/event/ExtractionJobRegistry.java:81-85`](src/main/java/com/example/app/event/ExtractionJobRegistry.java) — `@Scheduled`, no try/catch.
- [`src/main/java/com/example/app/llm/OpenRouterLlmVisionClient.java:73-122`](src/main/java/com/example/app/llm/OpenRouterLlmVisionClient.java) — throws `LlmExtractionException`; rich `log.info(...)` for metrics, no error logging.
- [`src/main/java/com/example/app/llm/LlmExtractionException.java`](src/main/java/com/example/app/llm/LlmExtractionException.java) — the only custom exception type; Sentry will group by `Kind` once instrumented.

### PII chokepoints to scrub
- [`src/main/java/com/example/app/user/AppUser.java:24,27,30`](src/main/java/com/example/app/user/AppUser.java) — email, bcrypt, iCal token.
- [`src/main/java/com/example/app/event/CalendarController.java:39`](src/main/java/com/example/app/event/CalendarController.java) — `/calendar/{token}.ics` endpoint, token-in-path.
- [`src/main/java/com/example/app/user/SettingsController.java:30-35`](src/main/java/com/example/app/user/SettingsController.java) — feed URL composition.
- [`src/main/java/com/example/app/event/SourceImage.java:35`](src/main/java/com/example/app/event/SourceImage.java) — `byte[] data` photo bytes.
- [`src/main/java/com/example/app/event/EventReviewController.java:96`](src/main/java/com/example/app/event/EventReviewController.java) — adds `sourceImage` to view model.
- [`src/main/java/com/example/app/event/ProposedEvent.java:56,59,62`](src/main/java/com/example/app/event/ProposedEvent.java) — extracted `title`, `requirements`, `notes`.
- [`src/main/java/com/example/app/event/Event.java:43-49`](src/main/java/com/example/app/event/Event.java) — persisted equivalent.
- [`src/main/java/com/example/app/event/EventReviewForm.java:204`](src/main/java/com/example/app/event/EventReviewForm.java) — `FieldError.getInvalidValue()` echoes user input.
- [`src/main/java/com/example/app/llm/LlmExtractionResult.java`](src/main/java/com/example/app/llm/LlmExtractionResult.java) — `rawResponse` field carries full LLM JSON.

### Wiring points
- [`build.gradle:21-39`](build.gradle) — add `sentry-spring-boot-4` and `sentry-logback`; Spring BOM resolves versions cleanly.
- [`src/main/resources/application.properties:54-57`](src/main/resources/application.properties) — `server.error.include-stacktrace=never` and `include-message=always` already in place; Sentry config sits well alongside these.
- [`src/main/java/com/example/app/AppApplication.java:17,41-52`](src/main/java/com/example/app/AppApplication.java) — `@EnableAsync` and the named `extractionExecutor` bean; the place to add `setTaskDecorator(new SentryTaskDecorator())` if scope-propagation polish is wanted.
- [`fly.toml`](fly.toml) — add `SENTRY_DSN`, `SENTRY_RELEASE`, `SENTRY_ENVIRONMENT` as Fly secrets; no `[env]` section update needed (secrets are runtime-only).
- [`Dockerfile:17`](Dockerfile) — `JAVA_TOOL_OPTIONS` already passes `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError`; no Sentry-side change needed.
- [`.github/workflows/deploy.yml`](.github/workflows/deploy.yml) — no change needed; secrets land via `flyctl secrets set` outside CI. If you want a release tag pushed at deploy time, add a `flyctl secrets set SENTRY_RELEASE=${{ github.sha }}` step.

## Architecture Insights

**The async-extraction failure path is already error-rich.** [`ExtractionService.runExtraction()`](src/main/java/com/example/app/event/ExtractionService.java:48-91) catches both `LlmExtractionException` and `RuntimeException`, generates a per-error correlation ID, stamps `lastErrorKind` + `correlationId` onto the `SourceImage` entity, marks the job FAILED in `ExtractionJobRegistry`, and emits a structured `log.error(...)` with the same correlation ID. **This is unusually well-instrumented for an MVP** — the only thing missing is centralized aggregation, which is exactly what Sentry adds. The correlation ID is a strong pairing key: in-app error pages show it ([EventReviewController.java:102-107](src/main/java/com/example/app/event/EventReviewController.java)), Sentry would show it as a tag, and the user can paste the ID into a support ticket.

**The "logger errors → Sentry events" pattern is load-bearing.** Because [`ExtractionService.java`](src/main/java/com/example/app/event/ExtractionService.java), [`SourceImagePurgeScheduler.java`](src/main/java/com/example/app/event/SourceImagePurgeScheduler.java), and Spring's default `TaskUtils$LoggingErrorHandler` all funnel through SLF4J's `error` level, **the Logback appender is the single mechanism that captures every silent-failure spot** in the current async architecture. This means the Sentry integration's blast radius is small: one `build.gradle` add, one `application.properties` add, one `BeforeSendCallback` bean. No `try/catch` rewrites, no `Sentry.captureException(...)` sprinkled through services.

**The PII surface concentrates around two files.** [`LlmExtractionResult.java`](src/main/java/com/example/app/llm/LlmExtractionResult.java) (rawResponse) and [`SourceImage.java`](src/main/java/com/example/app/event/SourceImage.java) (data bytes) are the two "leak vectors of last resort" if a downstream caller serializes them into an exception or event context. Defensive `BeforeSendCallback` rules keyed on those field names (e.g., reject events whose `extra` contains `rawResponse` or `data`) are cheaper than auditing every consumer.

**The e2e profile pattern is reusable.** The same mechanism that swaps `StubLlmVisionClient` for `OpenRouterLlmVisionClient` under `@Profile("e2e")` ([StubLlmVisionClient.java](src/test/java/com/example/app/llm/StubLlmVisionClient.java)) can swap a `NoOpSentryConfig` in for the real `BeforeSendCallback` bean if you ever want to test Sentry-error-flow paths without hitting the real ingest. Not needed for the MVP — `sentry.enabled=false` is sufficient — but worth noting for future test scenarios.

**No competing observability framework.** Grep of the repo turned up zero references to Micrometer/MeterRegistry, OpenTelemetry, DataDog, New Relic, Honeycomb, Logflare. The only existing observability seam is `/actuator/health` (and `management.health.db.enabled=false` deliberately disables the Neon idle-compute drain). Sentry is the first cross-cutting observability addition; no integration conflicts to worry about.

## Historical Context (from prior changes)

- [`context/foundation/infrastructure.md`](context/foundation/infrastructure.md) §Risk Register — flagged "Silent iCalendar feed failure masked by client-side caching" as a Medium/High risk; UptimeRobot keyword-check on the feed URL was the chosen mitigation. Sentry's role complements that: UptimeRobot catches "feed is down," Sentry catches "feed is up but something inside threw and got swallowed." The combination closes the loop the infrastructure doc identified.
- [`context/foundation/roadmap.md`](context/foundation/roadmap.md) §Baseline observability — "partial — /actuator/health exposed; UptimeRobot polls it; No structured logging beyond stdout → fly logs; no error tracking; no metrics. PRD does not mandate more for MVP." This change formalizes the "no error tracking" gap into an active resolution.
- [`context/archive/2026-06-10-llm-extraction-regression-harness/`](context/archive/2026-06-10-llm-extraction-regression-harness/) — established the LLM extraction's structured logging pattern (`outcome=`, `latencyMs=`, etc.) at INFO level. These are the breadcrumbs Sentry will attach to errors. Suggests the right `minimum-breadcrumb-level` is `info` (the proposed config), not `debug`.
- [`context/archive/2026-06-21-source-image-auto-purge/`](context/archive/2026-06-21-source-image-auto-purge/) — established the "log loud if the sweep stalls" contract for `SourceImagePurgeScheduler` (lesson from the change's `change.md` and the explicit `log.error(...)` design). Sentry makes that loudness cross-network rather than just `fly logs`.
- [`context/foundation/lessons.md`](context/foundation/lessons.md) — none of the registered lessons apply directly to Sentry instrumentation, but the meta-pattern "validation rules on a shared form DTO must stay symmetric across create + edit" is a useful reminder: any error-handling rule introduced in `BeforeSendCallback` must hold across all controllers, not just the one that prompted it.

## Related Research

No prior research artifacts under `context/changes/**/research.md` or `context/archive/**/research.md` discuss Sentry or error tracking. This is a greenfield instrumentation effort.

## Open Questions

1. **Should oversize-upload 413s reach Sentry?** [`MaxUploadSizeExceededHandler`](src/main/java/com/example/app/event/MaxUploadSizeExceededHandler.java) handles cleanly today with no log call. If "user uploaded a 30MB file" is a useful signal (UX feedback loop), add a `log.warn(...)` and consider bumping `sentry.logging.minimum-event-level=warn`. If it's just user error, leave it silent. **Decision for `/10x-plan`.**

2. **Should `BeforeSendCallback` strip the entire `request.url` or just the iCal token segment?** Stripping the whole URL hides legitimate forensic value (which controller threw); stripping just the token segment requires a regex. The right answer is probably the regex — but a `before-send` rule too aggressive risks hiding all url-based grouping. **Decision for `/10x-plan`.**

3. **Should `sentry.release` come from CI (commit SHA) or from `git describe`?** Fly's `FLY_RELEASE_VERSION` env is the easiest, but it doesn't tie back to commits. Commit SHA via `${{ github.sha }}` in the deploy workflow is more diagnostic. **Decision for `/10x-plan`.**

4. **Should we add `application-test.properties` defensively?** Today only `application-e2e.properties` exists. `sentry.dsn=${SENTRY_DSN:}` with no env var defaults to empty → SDK disabled → no transport. So this is defense-in-depth, not strictly required. The same question applies to unit tests under `@SpringBootTest` that boot the full context. **Decision for `/10x-plan`.**

5. **Is `SentryTaskDecorator` wiring on `extractionExecutor` worth it for errors-only?** The decorator propagates scope (tags, user, breadcrumbs) onto the async thread. For errors-only, the breadcrumbs trail might still be useful (the request that triggered the extraction). But the catch blocks in `ExtractionService` already log a structured message with `imageId` and `correlationId` — those are arguably better than ambient scope. **Decision for `/10x-plan`.**

6. **Java-side `send-default-pii` semantics — what exactly does it toggle?** Context7 returned the JS integration's defaults (cookies, headers, query_string, url default true) but no definitive Java-side list. Conservative assumption: `send-default-pii=false` disables IP, Principal username, and PII-bearing headers. Verify empirically on first deploy by triggering a known error and inspecting the resulting Sentry event payload. **Verification step for `/10x-plan` or `/10x-implement`.**
