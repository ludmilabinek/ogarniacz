# Sentry instrumentation (errors-only) Implementation Plan

## Overview

Wire Sentry into Ogarniacz for **errors-only** observability — capture uncaught exceptions and `log.error(...)` events from controllers, async services, and `@Scheduled` sweeps; ship them to Sentry with PII scrubbed at the source (`BeforeSendCallback`), tagged with the deploy's commit SHA. No performance tracing, no logs ingestion, no profiling. The hard work is not the wiring; the hard work is the scrubber.

## Current State Analysis

- **No `@ControllerAdvice` / `@RestControllerAdvice` exists.** Controller exceptions hit Spring's default 500 page; ops sees them only in `fly logs`. The only existing global handler is [`MaxUploadSizeExceededHandler`](src/main/java/com/example/app/event/MaxUploadSizeExceededHandler.java), which writes JSON 413s and does not log.
- **Four async/scheduled silent-failure spots** already exist, all funneling through `log.error(...)`:
  - [`ExtractionService.runExtraction()`](src/main/java/com/example/app/event/ExtractionService.java:48-91) — `@Async("extractionExecutor")`, catches and logs, does not rethrow.
  - [`SourceImagePurgeScheduler.sweep()`](src/main/java/com/example/app/event/SourceImagePurgeScheduler.java:50-58) — `@Scheduled`, catches and logs, does not rethrow.
  - [`ExtractionJobRegistry.sweep()`](src/main/java/com/example/app/event/ExtractionJobRegistry.java:81-85) — `@Scheduled`, throws bubble to Spring's `TaskUtils$LoggingErrorHandler` (also SLF4J ERROR).
  - `ExtractionService.runExtraction()` pre-try DB lookup — propagates to Spring's `SimpleAsyncUncaughtExceptionHandler` (also SLF4J ERROR).
  
  All four are covered by the Logback appender once `sentry-logback` is on the classpath. No try/catch surgery needed.
- **No observability framework exists.** No Micrometer, no OpenTelemetry, no DataDog. Sentry is the first cross-cutting observability addition; no integration conflicts. The only existing seam is `/actuator/health` (DB indicator deliberately disabled — see [application.properties:11](src/main/resources/application.properties)).
- **Deploy pipeline exists.** [`.github/workflows/deploy.yml`](.github/workflows/deploy.yml) runs `./gradlew test` then `flyctl deploy --remote-only` on every push to `main`. [`fly.toml`](fly.toml) has no `[env]` section today; [`Dockerfile`](Dockerfile) takes no `ARG`s. Both are clean addition surfaces.
- **Correlation IDs already exist** — [`ExtractionService`](src/main/java/com/example/app/event/ExtractionService.java:93-94) stamps an 8-char ID onto failed `SourceImage` records and the log line. Pairs naturally with Sentry's per-event ID.

### Key Discoveries:

- **Sentry's load-bearing mechanism for async/scheduled is the Logback appender**, not `Sentry.captureException(...)` calls — because the codebase already calls `log.error(...)` at every silent-failure spot. Blast radius is small: one `build.gradle` add, one config block, one bean class. ([research.md "Logger errors → Sentry events" Architecture Insight](research.md))
- **Five PII surfaces must be scrubbed before the first event ships**: iCal token in URL path, user email auto-attached as `user.username`, photo bytes via view-model, extracted event content (titles/requirements/notes including possible child names), `LlmExtractionResult.rawResponse`. Plus three more: `persistent_logins.username`, OpenRouter API-key fragments, headers. ([research.md PII risk register](research.md))
- **Surface 4 (extracted content) has three distinct leak channels**, and the scrubber+convention combination is built around them:
  1. **Spring binding errors** — `MethodArgumentNotValidException` would embed `rejected value [...]` in its message. **Not currently leaking in this codebase**: every `@Valid` parameter is paired with a `BindingResult` parameter, which suppresses `MethodArgumentNotValidException`; no controller logs the form/`BindingResult`. Rule 6 (split into 6a extras + 6b message regex) is **forward-defensive** against (a) a future REST endpoint without a `BindingResult` param, (b) an explicit `Sentry.setExtra(...)` of binding state, or (c) a contributor adding `log.error(form)`.
  2. **JPA constraint violations on `proposed_event`** — `PSQLException` / `DataIntegrityViolationException` message embeds row content (e.g., `Key (title)=(Adam urodziny)`). Covered by rule 9, a narrow metadata-pattern scrubber. Defensive today (the current schema has no unique constraint on title/requirements/notes); seals the channel against any future one.
  3. **Undisciplined `log.error("…", proposedEvent)`** — an arbitrary string no scrubber can safely flag. Covered by **code convention only**: the `lessons.md` entry added in §5b ("never log entity / `toString()` — log `id` + `status`").
- **LLM-error message is largely sanitized at source**: the dominant provider-error path in [`OpenRouterLlmVisionClient`](src/main/java/com/example/app/llm/OpenRouterLlmVisionClient.java) truncates `OpenAIServiceException` to OpenRouter's three structured fields (`code` / `type` / `param`) at line 105–110; timeout and malformed paths attach only the cause. The catch-all `RuntimeException` branch ([line 116–120](src/main/java/com/example/app/llm/OpenRouterLlmVisionClient.java)) propagates raw `e.getMessage()` — narrow but in principle could carry partial LLM output. Acceptable for this change: the channel is small, the §5b lessons.md entry adds convention discipline, and a future change can tighten the catch-all if needed.
- **Dev smoke (Phase 3) verifies the exact prod-incident pipeline**: `(scheduler|service).catch → log.error(...) → Sentry-Logback appender → BeforeSendCallback → transport → event`. The Phase 3 §2 dev override beans (`DevFailableLlmVisionClient`, `DevFailableSourceImagePurgeService`) deliberately throw **inside** the real workflow so the production catch-and-log path runs end-to-end — same correlation ID, same log shape, same scrubber pass. The smoke is not a synthetic shortcut; it is the real path with a forced failure.
- **`send-default-pii=false` and scrubber rule 8 are complementary, not redundant** — clean responsibility split:

  | Surface | Handled by | Channel |
  |---|---|---|
  | Inbound `request.headers.Authorization` (auto-attached by SDK) | `sentry.send-default-pii=false` | request-metadata auto-attachment |
  | Inbound `request.headers.Cookie`, IP, Principal username | `sentry.send-default-pii=false` | request-metadata auto-attachment |
  | Outbound HTTP request snapshot embedded in exception message (e.g., OpenAI SDK exception cause string carrying `Authorization: Bearer …`) | Scrubber rule 8 | body-content / message |
  | `log.error("…", request.getHeaders())` flattened into a log line by a future contributor | Scrubber rule 8 | body-content / breadcrumb |
  | Bearer token in a stack-trace `caused by` chain (rare, but possible) | Scrubber rule 8 | exception `values[].value` |

  The flag covers inbound request metadata; the rule covers body-content. The Phase 3/4 manual Bearer-grep remains as a defense-in-depth empirical confirmation — expected, not exploratory.
- **Spring Boot 4 starter is `io.sentry:sentry-spring-boot-4`**, currently 8.15.1. Companion artifact: `io.sentry:sentry-logback`. No OpenTelemetry agent required for errors-only. ([research.md "Sentry SDK for Spring Boot 4"](research.md))
- **Spring Boot 4 webmvc rename has no impact** — Sentry's starter activates on `spring-webmvc` classpath, both `spring-boot-starter-web` and `spring-boot-starter-webmvc` pull it transitively.
- **Errors-only must be enforced explicitly in config**, not assumed from starter defaults — `sentry.traces-sample-rate=0.0`, `sentry.profile-session-sample-rate=0.0`, `sentry.logs.enabled=false` are part of the scope contract.
- **`sentry.environment` empty default is fail-loud**: with `sentry.environment=${SENTRY_ENVIRONMENT:}`, a local dev who points at the prod DSN ships events tagged with empty environment — visibly wrong in the dashboard, surfaces the misconfiguration. Defaulting to `production` would silently mislabel local errors.

## Desired End State

When this plan is complete:

- **`sentry-spring-boot-4` 8.x is on the classpath** with `sentry-logback` as the SLF4J→Sentry bridge.
- **Every `log.error(...)` in production code automatically becomes a Sentry event**, including all four async/scheduled spots, with INFO/WARN logs attached as breadcrumbs.
- **A single `BeforeSendCallback` bean** scrubs the 10 scrubber rules before any event leaves the JVM (rules 1, 2, 3, 4, 5, 6a, 6b, 7, 8, 9). Twelve JUnit tests cover them: 11 positive (rule 1 has both URL + breadcrumb arms) plus 1 negative for rule 9 (no false positive on unrelated SQL errors). The test suite fails if a rule regresses.
- **`SentryTaskDecorator` propagates request scope onto `extractionExecutor` threads** — async LLM failures carry the originating user/request breadcrumb trail.
- **`tracesSampleRate`, `profileSessionSampleRate`, and `logs.enabled` are zero/false in the resolved `SentryOptions`** — verified by two complementary boot-time tests and one runtime check: `ErrorsOnlyEnforcementTest` checks the prod values; `SentryPropertyKeyPropagationTest` overrides each key to a non-default and asserts the value propagates (catches "key silently ignored" cases — 8.x typo or 9.x rename); a `@PostConstruct` runtime check in `SentryConfig` throws `IllegalStateException` on mismatch so misconfig also crashes boot if CI is bypassed.
- **`sentry.dsn` is empty in tests** via `src/test/resources/application.properties` shadow (`@SpringBootTest` default profile) and via `application-e2e.properties` (Playwright profile); no test transit risk even if a developer exports `SENTRY_DSN` locally.
- **Release tagging works end-to-end**: every Fly deploy ships with `SENTRY_RELEASE` = the git commit SHA, baked into the image at build time via `--build-arg`. `SENTRY_ENVIRONMENT=production` lives in `fly.toml [env]` (config, in git); `SENTRY_DSN` lives in `flyctl secrets` (sensitive, one-time).
- **A permanent dev-only `POST /__dev/force-error/{type}` endpoint** under `@Profile("dev")` lets us smoke errors against the dev Sentry project without contaminating prod. A regression test asserts the bean is not registered under any non-dev profile.
- **The production verification checklist in `change.md`** is fully ticked off after the first deploy: a known 500, an event in prod Sentry ≤ 30s, release tag matches the deploy SHA, raw payload greps clean against the test-account email/token, event deleted after verification.

### Verification

- `./gradlew build` passes (compile, tests, dependency resolution).
- Boot smoke: `./gradlew bootRun` with no `SENTRY_DSN` env var starts cleanly; Sentry SDK debug log line confirms transport disabled.
- `SentryConfigTest` passes — all 10 scrubber rules fire (12 methods: 11 positive + 1 negative on rule 9).
- `SentryPropertyKeyPropagationTest` passes — each errors-only key is actually consumed by the SDK (no silent default fallback).
- `DevForceErrorControllerNonDevTest` passes — the bean is absent under the default profile.
- Production deploy verification checklist in `change.md` Verification section ticked.

## What We're NOT Doing

The errors-only invariant is enforced in config (`sentry.traces-sample-rate=0.0`, `sentry.profile-session-sample-rate=0.0`, `sentry.logs.enabled=false`). The following are intentionally out of scope:

- **Performance tracing.** No `tracesSampleRate > 0`, no `@SentrySpan` annotations, no transaction sampling. Separate change if/when business case appears.
- **Sentry logs ingestion.** `sentry.logs.enabled=false`. We do not pay for log volume; SLF4J→Sentry remains an error-level bridge only.
- **Profiling.** `sentry.profile-session-sample-rate=0.0`. No `sentry-spring-boot-4` profiling auto-detect.
- **OpenTelemetry agent / distributed tracing.** `sentry-opentelemetry-agent` only matters for distributed tracing.
- **Source-context upload (`io.sentry.jvm.gradle` plugin).** Polish — current stack traces are sufficient for MVP triage.
- **Rewriting `ExtractionService` / `SourceImagePurgeScheduler` / `ExtractionJobRegistry` catch blocks.** Logback appender covers them automatically; no service-code surgery.
- **`AsyncUncaughtExceptionHandler` bean.** Spring's default `SimpleAsyncUncaughtExceptionHandler` logs at ERROR via SLF4J → the Logback appender catches it for free.
- **Bumping `MaxUploadSizeExceededHandler` to emit `log.warn`** to make oversize 413s reach Sentry. Stays silent — user-error 413s aren't ops-actionable. Revisit only if oversize uploads become a recurring support theme.
- **Renaming `groupId` from `com.example.app` to `com.ogarniacz.*`.** A separate refactor; `sentry.in-app-includes=com.example.app` follows whatever the package is today.

## Implementation Approach

**Phase 1 is load-bearing.** It carries an invariant: **no live DSN can transit until the scrubber is wired and tested.** That's why SDK wiring + config + async decorator + `BeforeSendCallback` + 12 unit tests for the scrubber + 2 boot-level tests + `@PostConstruct` runtime check + lessons.md entry all land together. Splitting them would create a window where SDK is on the classpath but the safety net is in the next PR — a reviewer who LGTMs Phase 1 without seeing the scrubber would have approved an unsafe state. We close that window by making the safety net part of the same change.

Phases 2-4 build on top of that fundament:
- Phase 2 wires the deploy plumbing (release tag, environment config, DSN secret) — three mechanisms by category (per-deploy CI, in-git config, one-time secret).
- Phase 3 adds the dev smoke endpoint so we can verify the scrubber against a real Sentry event before touching prod.
- Phase 4 deploys to prod and works through the verification checklist.

The reasoning order matches the implementation order: prove the safety net, prove the dev signal, ship to prod with eyes on the dashboard.

## Critical Implementation Details

- **`BeforeSendCallback` registration is bean-driven.** Sentry's Spring Boot 4 starter scans for exactly one `BeforeSendCallback` bean; registering two is an error per Sentry docs. Keep all scrubbing rules in one bean.
- **Logback appender is auto-attached** when `sentry-logback` is on the classpath; no `logback-spring.xml` is required. The project has no `logback-spring.xml` today, and we don't add one — the appender's behavior is controlled entirely via `sentry.logging.*` properties.
- **Errors-only enforcement is verified at boot via two complementary mechanisms — not "either/or".** (1) A `@PostConstruct` runtime check in `SentryConfig` throws `IllegalStateException` if the resolved `SentryOptions` does not have `tracesSampleRate == 0.0`, `profileSessionSampleRate == 0.0`, `logs.enabled == false` — misconfiguration crashes startup. (2) `ErrorsOnlyEnforcementTest` (§8a) asserts the same invariant pre-merge. The test gates merge; the runtime check gates boot — both together close the holes either has alone (force-merge past CI / SDK refactor that loads options post-context-init).
- **`sentry.profile-session-sample-rate` is the assumed 8.x SDK property name for continuous profiling**, mapped via Spring Boot's relaxed binding to `SentryOptions.profileSessionSampleRate`. **The canonical key name in 8.x could not be confirmed via Context7's surfaced docs at plan time** (responses covered `traces-sample-rate`, `enabled`, and `dsn` but not the profiling-specific key). Rather than guess and silently fall back to the SDK default on a typo, Phase 1 §8 ships a **dedicated `SentryPropertyKeyPropagationTest`** that sets each errors-only key to a deliberate non-default value via `@TestPropertySource` and asserts the value reaches `SentryOptions`. If the key name is wrong, the SDK applies its default instead of the test value, the assertion fails, and CI blocks the merge — catching "key silently ignored" without depending on documentation precision. Belt-and-braces: if the implementer (or a future SDK upgrade) discovers the canonical key is `sentry.profiles-sample-rate` or any other variant, set both in `application.properties`; the propagation test still locks in the working one.

## Preconditions

A flat `[ ]` checklist gating Phase 2 and Phase 4 (Phase 1 + Phase 3 can land before the prod DSN exists). The implementer must tick each item or surface why it doesn't apply before starting the gated phase.

- [x] `.github/workflows/deploy.yml` exists and deploys to Fly via `flyctl deploy --remote-only` (verified at d72fd4e).
- [x] `fly.toml` has no existing `[env]` block (verified — clean addition).
- [x] `Dockerfile` declares no existing `ARG`s (verified — clean addition).
- [ ] Sentry org exists.
- [ ] Sentry **dev** project created; DSN_DEV captured in the operator's secrets manager.
- [ ] Sentry **prod** project created; DSN_PROD captured in the operator's secrets manager.

If any Sentry-side checkbox is unchecked at Phase 4 start: **stop**. Either complete the Sentry-side preconditions or defer Phase 4 to a separate change. Phases 1+3 may proceed without them (empty DSN = SDK disabled at boot).

## Phase 1: SDK + config + async wiring + `BeforeSendCallback` + 8 unit tests

### Overview

The load-bearing safety phase. After this lands, an operator may set `SENTRY_DSN=<DSN_PROD>` and ship events with full PII-scrubbed safety. Nothing in Phases 2-4 can introduce a "live DSN before scrubber" window because the scrubber is already there.

### Changes Required:

#### 1. Add Sentry dependencies

**File**: `build.gradle`

**Intent**: Add `sentry-spring-boot-4` and `sentry-logback` as runtime classpath additions so Spring Boot's auto-config picks up the starter, and Logback's classloader sees the Sentry appender.

**Contract**: Both artifacts come from `io.sentry`; pin both to the same minor version (8.x, latest available at integration time — research surfaced 8.15.1 as the floor and 8.43.0 as the top-of-tree of the sibling `sentry-java`). Spring Boot's dependency-management plugin will not manage these; pin explicitly. Coordinates: `io.sentry:sentry-spring-boot-4` and `io.sentry:sentry-logback`. No transitive conflicts with the existing Spring AI / ical4j / postgres stack — both resolve cleanly from Maven Central.

#### 2. Add errors-only Sentry properties block

**File**: `src/main/resources/application.properties`

**Intent**: Tell Spring Boot's Sentry starter exactly what scope to operate in: errors-only, PII-off, in-app package known, log levels mapped. All keys use the project's existing `${ENV:default}` placeholder pattern. The `sentry.environment` empty default is deliberate fail-loud behavior — local dev with a misconfigured prod DSN ships events with empty environment, visibly wrong, surfacing the misconfiguration.

**Contract**: The block appears at the end of the file (after the existing `server.error.*` keys). Eleven keys; nothing optional:

```properties
# Sentry — errors-only observability. See context/changes/sentry-instrumentation/plan.md.
# Empty SENTRY_DSN = SDK disabled (no transport). SENTRY_ENABLED kill-switch overrides.
sentry.dsn=${SENTRY_DSN:}
sentry.enabled=${SENTRY_ENABLED:true}
# Errors-only enforcement — do NOT rely on starter defaults; assertion in SentryConfig verifies these.
sentry.traces-sample-rate=0.0
sentry.profile-session-sample-rate=0.0
sentry.logs.enabled=false
# PII gate. BeforeSendCallback is the second line of defense.
sentry.send-default-pii=false
# Grouping + log-bridge.
sentry.in-app-includes=com.example.app
sentry.logging.minimum-event-level=error
sentry.logging.minimum-breadcrumb-level=info
# Release + environment. Empty defaults are deliberate (fail-loud on misconfiguration).
sentry.release=${SENTRY_RELEASE:}
sentry.environment=${SENTRY_ENVIRONMENT:}
```

#### 3. Shadow `application.properties` for unit tests

**File**: `src/test/resources/application.properties` (new file)

**Intent**: Defense-in-depth — guarantee Sentry never transits during `./gradlew test`, even if a developer has `SENTRY_DSN` exported in their shell. `@SpringBootTest` does NOT auto-activate a `test` profile, so the canonical mechanism is a same-name file in the test classpath, which Spring Boot resolves before the main copy.

**Contract**: New file containing only Sentry kill-switches. Spring Boot's classpath resolution puts `src/test/resources` ahead of `src/main/resources`; `application.properties` here shadows the main copy for tests. The implementer must verify that this shadowing does not break the existing test suite — if any tests depend on a property only in the main `application.properties`, switch to `@TestPropertySource` on a shared test config base class instead. **Verification step in Success Criteria.**

```properties
# Test-classpath shadow — keeps Sentry disabled during ./gradlew test. See plan.md Phase 1.
sentry.enabled=false
sentry.dsn=
```

#### 4. Disable Sentry under the e2e profile

**File**: `src/test/resources/application-e2e.properties`

**Intent**: Symmetric override for the Playwright profile, which boots the real Spring context via `./gradlew bootTestRun --args='--spring.profiles.active=e2e'`. The e2e profile already swaps `StubLlmVisionClient` for the real LLM client; same pattern for Sentry.

**Contract**: Append two lines at the end of the file (after the existing `spring.web.locale-resolver=fixed`):

```properties
# Sentry — disabled under e2e. Playwright never ships events.
sentry.enabled=false
sentry.dsn=
```

#### 5. `SentryConfig` — both beans (`SentryTaskDecorator` + `BeforeSendCallback`)

**File**: `src/main/java/com/example/app/observability/SentryConfig.java` (new file, new package)

**Intent**: One `@Configuration` class with two beans: (a) `SentryTaskDecorator` for propagating request scope onto async executor threads; (b) `BeforeSendCallback` implementing 10 PII scrubber rules (1, 2, 3, 4, 5, 6a, 6b, 7, 8, 9). Co-located so future contributors find the whole Sentry surface in one place. The package `com.example.app.observability` is new; it follows the project's package-per-domain convention (cf. `com.example.app.event`, `com.example.app.user`, `com.example.app.llm`).

**Contract**: 
- Class is `@Configuration`. Two `@Bean` methods.
- `SentryTaskDecorator` bean: `return new io.sentry.spring7.SentryTaskDecorator();` — the Spring 7 / Boot 4 SDK package is `io.sentry.spring7.*` (confirmed by research from `io.sentry.spring7.SentryTaskDecorator` import in Sentry docs).
- `BeforeSendCallback` bean: implements `io.sentry.SentryOptions.BeforeSendCallback`. The 10 rules (1, 2, 3, 4, 5, 6a, 6b, 7, 8, 9 — full spec):
  1. **iCal token URL regex-redact** — for any `SentryEvent` whose `request.url` matches `/calendar/[^./]+\.ics`, rewrite that segment to `/calendar/[REDACTED].ics`. Also strip from all breadcrumbs where `data.url` contains the same pattern.
  2. **Strip `user.username`** — if `event.user != null`, set `user.setUsername(null)`. Keep `user.id` (already pseudonymous in our schema — `AppUser.id` is a numeric PK).
  3. **Drop `extra` keyed `sourceImage`** — `event.removeExtra("sourceImage")`. Defensive against a future caller serializing the view-model into event context.
  4. **Drop `extra` keyed `rawResponse`** — `event.removeExtra("rawResponse")`. Defensive against `LlmExtractionResult` ever appearing in event context.
  5. **Drop `extra` keyed `data`** — `event.removeExtra("data")`. Defensive against `SourceImage.data` bytea leak.
  6. **Binding-error scrub — two arms (forward-defensive)** — the current codebase does not leak Spring binding errors to Sentry (every `@Valid` is paired with a `BindingResult` param, so `MethodArgumentNotValidException` is never thrown, and no controller logs the form/`BindingResult` — see Key Discoveries). Rule 6 is forward-defensive: it covers a future contributor adding a REST endpoint without a `BindingResult` param, an explicit `Sentry.setExtra(...)` of a `FieldError`, or a `log.error(form)`. Two arms:
     - **6a**: For any extra whose key matches `bindingResult.*` or whose value contains a `FieldError`-shaped string, redact the `invalidValue` portion. Keep field names and codes; drop values. *(Defensive against explicit `Sentry.setExtra(...)` of binding state.)*
     - **6b**: In `event.message`, exception messages, and breadcrumb messages, redact the substring matched by the regex `rejected value \[[^\]]*\]` to `rejected value [REDACTED]`; also redact the longer `Field error in object '[^']*' on field '[^']*': rejected value \[[^\]]*\]` shape to keep field/object names but blank the value. *(Defensive against a future REST endpoint without a `BindingResult` parameter, where `MethodArgumentNotValidException` reaches Sentry with the binding state rendered into the exception message via `DefaultMessageSourceResolvable.toString()` — stable contract since Spring 3.)*
  7. **Persistent-logins email scrub** — for any `SentryEvent.message` or exception message containing the substring `persistent_logins`, redact email-shaped substrings (`[^\s@]+@[^\s@]+\.[^\s@]+` → `[REDACTED-EMAIL]`). Defensive against Spring Security's remember-me table leaking via SQL exception messages.
  8. **Authorization/Bearer body-content scrub** — on `event.message`, `event.exception.values[].value`, and breadcrumb messages, strip `Authorization: Bearer [^\s]+` → `Authorization: Bearer [REDACTED]`. **Complementary to `send-default-pii=false`, not redundant with it.** Responsibility split (see Key Discoveries below): the SDK flag handles **inbound request metadata** auto-attachment (`request.headers.Authorization`); this rule handles **body-content** leaks where Bearer tokens end up inside exception messages or logged strings (e.g., an OpenAI SDK exception cause embedding the outbound HTTP request snapshot into its message; a future contributor flattening request headers into a `log.error(...)` argument). Neither surface subsumes the other.
  9. **JPA `proposed_event` row data scrub** — for any event whose `event.exception.values[].type` is one of `{PSQLException, DataIntegrityViolationException, SQLException}` AND whose `event.message` (or `logentry.formatted`) contains the substring `proposed_event` (the table name), replace the entire message/formatted with the marker `[REDACTED SQL ROW DATA]`. Pattern match on exception **metadata**, not on the row content — keeps the rule deterministic, no regex tuning. This closes the JPA constraint-violation leak channel (today defensive since the schema has no unique constraints on title/requirements/notes, but seals it against future ones). Companion code-convention lesson goes to `context/foundation/lessons.md` during this phase (see §5b below).

The callback returns the modified event (never `null` — `null` would drop the event entirely, which we do not want).

**Errors-only post-init assertion — both layers, not "or"**: defense-in-depth via two complementary mechanisms.

- **Runtime check (this section)**: in `SentryConfig`, add a `@PostConstruct` method (or `ApplicationReadyEvent` listener if it executes after Sentry options finish binding — verify at implementation time) that fetches the resolved `SentryOptions` via `io.sentry.Sentry.getCurrentHub().getOptions()` and asserts `tracesSampleRate == 0.0`, `profileSessionSampleRate == 0.0`, `logs.enabled == false`. Throw `IllegalStateException` on mismatch. Misconfiguration crashes the app at startup — catches the case where CI is bypassed (force-merge, hot-fix branch deploying directly) or a future SDK refactor loads options post-context-init. Gate the runtime check by profile to skip under `test`/`e2e` where `sentry.enabled=false` and the assertion is uninteresting; **active under default + dev + production profiles**.
- **Test check (§8a)**: `ErrorsOnlyEnforcementTest` catches misconfig before merge. Cheaper, faster signal — usually catches the issue first.

Why both: the test gates merge; the runtime check gates boot. Either alone has a hole the other closes.

#### 5b. Lessons.md entry — code-convention companion to rule 9

**File**: `context/foundation/lessons.md`

**Intent**: Rule 9 closes the JPA-exception leak channel for `proposed_event` row data. But no scrubber can catch the third channel: a contributor writing `log.error("extraction failed for {}", proposedEvent)` (the whole entity, or its `toString()`) — that string is arbitrary and structured-matching can't safely flag it. Convention is the only defense; the lesson makes it discoverable for future code review and AI agents.

**Contract**: Add one accepted lesson titled "Never log `ProposedEvent` / `ExtractedEvent` entities (or their `toString()`) — Sentry-logback ships them as message/breadcrumb. Log `id` + `status` only." Reference rule 9 in `SentryConfig` and the corresponding `SentryConfigTest` so a reader following the trail lands on the safety net. Use the same accepted-lesson format the existing `lessons.md` follows. No code change beyond the lesson entry itself in this step.

#### 6. Wire `SentryTaskDecorator` onto `extractionExecutor`

**File**: `src/main/java/com/example/app/AppApplication.java`

**Intent**: The existing `extractionExecutor` bean ([AppApplication.java:41-52](src/main/java/com/example/app/AppApplication.java)) is a `ThreadPoolTaskExecutor` configured with core=2, max=2, queue=10. Inject the `SentryTaskDecorator` bean and call `exec.setTaskDecorator(sentryTaskDecorator)` before returning. This makes the scope/tags/breadcrumbs accumulated on the request thread visible inside `@Async` LLM-extraction failures.

**Contract**: One injected dependency, one method call. No other behavior changes.

#### 7. `SentryConfigTest` — 12 named scrubber test methods

**File**: `src/test/java/com/example/app/observability/SentryConfigTest.java` (new file)

**Intent**: One JUnit test per scrubber rule (rule 9 additionally carries a negative test to prevent false positives on unrelated SQL exceptions). Each test constructs a synthetic `SentryEvent` carrying the PII shape (or the negative case), passes it through the `BeforeSendCallback` bean, and asserts the PII is gone (or, for the negative case, that the message is untouched). These tests are the safety contract — if any rule regresses, CI fails before a leaky build can be merged.

**Contract**: 12 test method names — one positive per scrubber rule (rule 1 has 2 — URL + breadcrumb arms; rule 6 has 2 — extras + message arms; one each for rules 2, 3, 4, 5, 7, 8, 9) plus 1 negative for rule 9:

- `scrubsIcalTokenFromRequestUrl()` *(rule 1)* — event with `request.url = "https://ogarniacz.fly.dev/calendar/abc123def.ics"` → assert URL becomes `"https://ogarniacz.fly.dev/calendar/[REDACTED].ics"`.
- `scrubsIcalTokenFromBreadcrumbs()` *(rule 1, breadcrumb arm)* — breadcrumb with `data.url` containing the token → assert redacted.
- `scrubsUserEmailFromUserUsername()` *(rule 2)* — event with `user.username = "parent@example.com"` → assert `user.username == null` and `user.id` preserved.
- `scrubsPhotoBytesFromViewModelExtra()` *(rule 3)* — event with `extra.sourceImage = <Map containing byte[]>` → assert extra is removed.
- `scrubsRawLlmResponseFromExtraTag()` *(rule 4)* — event with `extra.rawResponse = "<full LLM JSON>"` → assert extra is removed.
- `scrubsRawBytesFromDataExtra()` *(rule 5)* — event with `extra.data = <byte[]>` → assert extra is removed.
- `scrubsInvalidValueFromFieldErrorExtra()` *(rule 6a)* — event with `extra.bindingResult` containing a `FieldError`-shaped value (e.g., `extra.bindingResult.title = { field: "title", invalidValue: "Adelka K.", code: "NotBlank" }`) → assert `invalidValue` redacted, field name + code preserved.
- `scrubsRejectedValueFromExceptionMessage()` *(rule 6b)* — construct a real `MethodArgumentNotValidException` via `DataBinder` bound to a throwaway DTO with `@NotBlank title` and `title = "Adelka K."`; pass the resulting event through the callback → assert the rendered `rejected value [Adelka K.]` substring becomes `rejected value [REDACTED]`; `Adelka` absent from event JSON. Exercises the actual production shape end-to-end (Spring's `DefaultMessageSourceResolvable.toString()`).
- `scrubsPersistentLoginsUsernameFromDbErrorContext()` *(rule 7)* — event with `message = "DataAccessException: persistent_logins.username='parent@example.com' duplicate key"` → assert email portion redacted.
- `scrubsAuthorizationBearerFromMessage()` *(rule 8)* — assert the Bearer scrub fires on all three carriers the rule covers: (a) `event.message = "OpenRouter call failed: Authorization: Bearer sk-or-v1-abcdef…"` → message becomes `"OpenRouter call failed: Authorization: Bearer [REDACTED]"`; (b) `event.exception.values[].value = "… Authorization: Bearer sk-or-v1-xyz …"` → value redacted; (c) `breadcrumb.message = "outbound HTTP 401 with Authorization: Bearer …"` → breadcrumb message redacted. All three asserted in one test. Confirms rule 8's body-content scope (complementary to `send-default-pii=false`'s request-header scope — see Key Discoveries).
- `scrubsProposedEventRowDataFromJpaException()` *(rule 9, positive)* — event with `exception.type = "org.postgresql.util.PSQLException"` (SQLState 23505) and `message = "ERROR: duplicate key value violates unique constraint \"uniq_proposed_event_title\"\n  Detail: Key (title)=(Adam urodziny) already exists in proposed_event."` → assert `message == "[REDACTED SQL ROW DATA]"`; `Adam urodziny` absent from event JSON.
- `doesNotScrubUnrelatedSqlException()` *(rule 9, negative)* — event with `exception.type = "org.postgresql.util.PSQLException"` and `message = "ERROR: relation \"app_user\" does not exist"` (no `proposed_event` substring) → assert message untouched (no false positive on unrelated DB errors).

Each test wires the `BeforeSendCallback` directly (unit test, no Spring context). The bean's class is package-visible from `com.example.app.observability` to enable this.

#### 8. Boot-time `SentryOptions` tests — value + key-name propagation

The plan splits boot-time `SentryOptions` verification into **two** tests because they answer different questions:

- `ErrorsOnlyEnforcementTest` answers **"does prod config end up with the errors-only values?"** — guards against a future maintainer bumping `sentry.traces-sample-rate=0.1` for debugging and forgetting to revert.
- `SentryPropertyKeyPropagationTest` answers **"does the SDK actually consume the keys we set?"** — guards against a key-name typo (or an 8.x→9.x rename) silently falling back to the SDK default. Without this, `ErrorsOnlyEnforcementTest` can pass while the property has no effect, because the SDK default for all three keys is also "errors-only".

##### 8a. `ErrorsOnlyEnforcementTest` — production-config values

**File**: `src/test/java/com/example/app/observability/ErrorsOnlyEnforcementTest.java` (new file)

**Intent**: Boot the application context with the production property values resolved, fetch `SentryOptions`, assert errors-only.

**Contract**: One `@Test` method. `@SpringBootTest` with default profile. Reads `io.sentry.Sentry.getCurrentHub().getOptions()` (or equivalent for the 8.x API). Three `assertThat(...)` lines: `tracesSampleRate == 0.0`, `profileSessionSampleRate == 0.0`, `logs.enabled == false`. The test runs under the test profile where `sentry.enabled=false`; the assertion is on the *configured* options regardless of enabled state.

##### 8b. `SentryPropertyKeyPropagationTest` — key-name propagation safety net

**File**: `src/test/java/com/example/app/observability/SentryPropertyKeyPropagationTest.java` (new file)

**Intent**: Catch the case where a key in `application.properties` is typo'd, renamed by an SDK upgrade, or otherwise silently ignored — without depending on knowing the canonical key name at plan time. By overriding each key to a **deliberately non-default value** via `@TestPropertySource` and asserting the value reaches `SentryOptions`, this test fails fast if the SDK falls back to its own default. (If the canonical 8.x key turns out to be `sentry.profiles-sample-rate` instead of `sentry.profile-session-sample-rate`, the implementer updates `application.properties` AND this test's `@TestPropertySource` together — they fall out of sync only if someone bypasses the test.)

**Contract**: One `@SpringBootTest` test method with `@TestPropertySource(properties = { "sentry.dsn=", "sentry.enabled=false", "sentry.traces-sample-rate=0.42", "sentry.profile-session-sample-rate=0.37", "sentry.logs.enabled=true" })`. Reads the resolved `SentryOptions`. Asserts each value matches the override (0.42, 0.37, true respectively). If the key name doesn't bind, the SDK applies its default (0.0, 0.0, false) — assertion fails with a clear "key not consumed" diagnostic.

Operator guidance: when this test fails after an SDK upgrade, the canonical key likely changed — grep the upgraded SDK source for the option setter (`setProfileSessionSampleRate` etc.), find the new key spelling in `ExternalOptions.from(...)`, update `application.properties` + this test's `@TestPropertySource` to match.

### Success Criteria:

#### Automated Verification:

- Compile + dependency resolution: `./gradlew build` passes.
- Errors-only enforcement: `./gradlew test --tests com.example.app.observability.ErrorsOnlyEnforcementTest` passes.
- Key-name propagation: `./gradlew test --tests com.example.app.observability.SentryPropertyKeyPropagationTest` passes (catches "key silently ignored" — see Critical Implementation Details).
- Scrubber rules: `./gradlew test --tests com.example.app.observability.SentryConfigTest` passes (all 12 methods green — 11 positive + 1 negative on rule 9).
- Test suite as a whole: `./gradlew test` passes (no regressions from the `src/test/resources/application.properties` shadow file).
- E2E suite still green: `cd e2e && npx playwright test --reporter=list` passes (Sentry disabled under `e2e` profile; no test transit).

#### Manual Verification:

- Boot smoke with empty DSN: `./gradlew bootRun` starts cleanly. No `SENTRY_DSN` exported. Log line `Sentry SDK disabled (no DSN configured)` (or equivalent 8.x message) appears in startup output.
- Boot smoke with DSN-but-disabled: `SENTRY_ENABLED=false SENTRY_DSN=<dummy> ./gradlew bootRun` starts cleanly; no transport.
- Test-shadow check: verify the file at `src/test/resources/application.properties` does not break any tests that depend on properties only in the main file (e.g., `spring.servlet.multipart.max-file-size`). If it does, switch to `@TestPropertySource` on a base test class.
- Runtime-check smoke: `SENTRY_DSN=https://invalid@example/0 ./gradlew bootRun -Dsentry.traces-sample-rate=0.1` (or equivalent) **fails to start** with `IllegalStateException` from `SentryConfig`'s `@PostConstruct` runtime check. Confirms the runtime layer is wired and active under the default profile.

**Implementation Note**: After Phase 1 lands and all automated verification passes, pause for manual confirmation that boot smoke + test-shadow check passed before starting Phase 2. The Progress section's checkboxes own the state.

---

## Phase 2: Release tag + deploy plumbing

### Overview

Wire the three deploy-side mechanisms by category so future maintainers don't blur the lines: per-deploy CI (`SENTRY_RELEASE`), in-git config (`SENTRY_ENVIRONMENT`), one-time secret (`SENTRY_DSN`). Each goes to its category-appropriate mechanism — no Fly-secret churn for non-sensitive metadata, no `[env]` block for credentials.

### Changes Required:

#### 1. Dockerfile — accept `SENTRY_RELEASE` as build arg, pass through to runtime env

**File**: `Dockerfile`

**Intent**: Make the deploy's commit SHA part of the immutable image. `ARG` accepts it at `docker build` time; `ENV` persists it to runtime so Spring Boot resolves `${SENTRY_RELEASE:}` to the SHA. Ties the Sentry release tag literally to the deployed artifact — paste the SHA into `git show` to find the exact commit.

**Contract**: Two new lines in the runtime stage. `ARG` lives in the build stage (where `bootJar` runs); `ENV` lives in the runtime stage. Empty default in the `ARG` keeps local `docker build` (without `--build-arg`) working — image just gets an empty release tag.

```dockerfile
# ---- runtime stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/build/libs/app-0.0.1-SNAPSHOT.jar /app/app.jar
EXPOSE 8080
ARG SENTRY_RELEASE=
ENV SENTRY_RELEASE=$SENTRY_RELEASE
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

#### 2. `deploy.yml` — pass `--build-arg SENTRY_RELEASE=${{ github.sha }}` to `flyctl deploy`

**File**: `.github/workflows/deploy.yml`

**Intent**: At deploy time, pass the commit SHA into the Docker build via Fly's `--build-arg` pass-through. No new step required — modify the existing `flyctl deploy --remote-only` invocation.

**Contract**: Single-line change. The `--build-arg` flag is forwarded to the underlying `docker build` invocation Fly runs remotely. `github.sha` is the full 40-char SHA of the pushed commit.

```yaml
      - run: flyctl deploy --remote-only --build-arg SENTRY_RELEASE=${{ github.sha }}
        env:
          FLY_API_TOKEN: ${{ secrets.FLY_API_TOKEN }}
```

#### 3. `fly.toml` — add `[env]` block with `SENTRY_ENVIRONMENT = "production"`

**File**: `fly.toml`

**Intent**: `SENTRY_ENVIRONMENT=production` is config, not secret — it belongs in version-controlled infra config that reviewers see and PR-approve, not in opaque runtime secrets. After this lands, **deleting the `[env]` block silently breaks Sentry environment tagging** (events ship with empty environment per the fail-loud default in Phase 1). The plan calls this out so future cleanup PRs know what they're touching.

**Contract**: New `[env]` section anywhere in `fly.toml` (convention: after `[build]`). One key.

```toml
[env]
  SENTRY_ENVIRONMENT = "production"
```

#### 4. `SENTRY_DSN` runbook — one-time `flyctl secrets set` (documented in plan, not in code)

**Intent**: Capture the operator step for setting `SENTRY_DSN` as a Fly secret. Not code; documentation. The implementer runs this once per environment; subsequent deploys inherit the secret automatically (no per-deploy rotation needed).

**Contract**: One operator command, run with the prod project's DSN value, after the Sentry preconditions are met:

```bash
flyctl secrets set SENTRY_DSN='<DSN_PROD>' --app ogarniacz
```

This triggers a machine restart with the secret available as an env var. After this, the next deploy (or this restart) has Sentry live.

### Success Criteria:

#### Automated Verification:

- Docker image accepts the build arg: `docker build --build-arg SENTRY_RELEASE=test123 -t ogarniacz:test .` succeeds.
- Image carries the env var: `docker run --rm ogarniacz:test env | grep SENTRY_RELEASE` outputs `SENTRY_RELEASE=test123`.
- `fly.toml` parses: `flyctl config validate` returns clean.
- `deploy.yml` syntax check: GitHub Actions UI shows no syntax errors (or `actionlint` if available locally).

#### Manual Verification:

- Stage-deploy dry run *(optional, if a non-prod Fly app exists)*: deploy to a sandbox app, confirm `SENTRY_RELEASE` env var is present on the machine via `flyctl ssh console` → `env | grep SENTRY`.
- `flyctl secrets set SENTRY_DSN=<dummy>` triggers machine restart; `flyctl logs` shows no Sentry-related startup errors.

**Implementation Note**: After Phase 2 lands, pause for manual confirmation before starting Phase 3.

---

## Phase 3: Dev `__dev/force-error/{type}` smoke endpoint

### Overview

A permanent dev-only HTTP endpoint that triggers each of the three known error paths on demand: extraction failure, purge failure, controller exception. Lets us verify end-to-end that errors reach Sentry with the right shape, scrubbed correctly, tagged correctly — before the prod DSN is wired. Permanent tooling rather than delete-after-smoke: it costs us a few dozen lines of test-covered code in exchange for reproducible verification on future SDK upgrades, scrubber-rule changes, and incident drills.

### Changes Required:

#### 1. `DevForceErrorController` — three error triggers, gated by `@Profile("dev")`

**File**: `src/main/java/com/example/app/observability/devtools/DevForceErrorController.java` (new file, new sub-package)

**Intent**: Three HTTP endpoints under one `@RestController`, each forcing a different error path through the codebase. `@Profile("dev")` ensures the bean is registered only when `spring.profiles.active=dev`; under any other profile (default, e2e, test, production) Spring skips it entirely — the URL returns 404. Sub-package `observability.devtools` keeps it findable next to the rest of the Sentry surface but visually flagged as tooling.

**Contract**:
- `@RestController`, `@RequestMapping("/__dev/force-error")`, `@Profile("dev")`.
- `POST /__dev/force-error/extraction` — produces a Sentry event with the **exact** signature of a real provider failure, via the real workflow. Steps:
  1. Insert a throwaway `SourceImage` row with minimal valid data (placeholder bytes, real `mimeType`, owning user = the authenticated dev user); capture its `id` as `sourceImageId`. Generate a `jobId` (UUID).
  2. Flip `DevFailableLlmVisionClient.failNextCall = true`.
  3. Dispatch through the real async executor: `extractionExecutor.execute(() -> extractionService.runExtraction(jobId, sourceImageId))`. Going through `extractionExecutor` (not calling `runExtraction` inline) preserves the async path, including `SentryTaskDecorator` request-scope propagation onto the worker thread.
  4. Return `202 Accepted` immediately; the failure surfaces on the worker thread.
  5. On the worker, `DevFailableLlmVisionClient.extract(...)` throws `LlmExtractionException.provider(503, "code=forced_dev_error type=test param=?", null)` — hitting [`ExtractionService.runExtraction()` line 75](src/main/java/com/example/app/event/ExtractionService.java) (`kind=PROVIDER_ERROR`) and producing the same `extraction_failed imageId=… correlationId=… kind=PROVIDER_ERROR` log line a real provider failure produces. That line becomes the Sentry event via the Logback appender.
  6. The throwaway `SourceImage` is left with `lastErrorKind` set; it gets swept by the next [`SourceImagePurgeScheduler.sweep()`](src/main/java/com/example/app/event/SourceImagePurgeScheduler.java) under the existing 3-clause predicate — no manual cleanup needed.
- `POST /__dev/force-error/purge` — sets `DevFailableSourceImagePurgeService.failNextCall = true` and invokes `sourceImagePurgeScheduler.sweep()`. The dev-profile-only `DevFailableSourceImagePurgeService` (see §2 below) throws `RuntimeException("forced purge failure for dev smoke")` on the next `purgeEligible()`. The scheduler's existing `catch (RuntimeException ex) { log.error("source_image_purge_sweep_failed", ex); }` block runs — that log line becomes the Sentry event via the Logback appender. Returns 204 once `sweep()` returns. Exercises the exact prod-incident path: `scheduler.catch → log.error → Sentry-Logback → event`.
- `POST /__dev/force-error/controller` — throws `RuntimeException("dev smoke: controller path")` directly from the handler. Verifies the controller-uncaught path. (No Dev*Override needed — simplest of the three.)

The endpoint has no body and no parameters beyond the `{type}` path variable. Operator hits it with `curl -X POST -u <devuser>:<pass> http://localhost:8080/__dev/force-error/extraction`.

#### 2. Dev override beans — mirror the existing `StubLlmVisionClient @Primary @Profile("e2e")` pattern

**Files**:
- `src/main/java/com/example/app/observability/devtools/DevFailableLlmVisionClient.java` (new file)
- `src/main/java/com/example/app/observability/devtools/DevFailableSourceImagePurgeService.java` (new file)

**Intent**: Two `@Primary @Profile("dev")` beans that wrap the prod implementations. Each carries a `static volatile boolean failNextCall` flag the dev controller flips before invoking the real workflow. After failing once, the flag self-resets — so the next call goes through the real implementation. Mirrors the project's existing dev/test-override pattern ([`StubLlmVisionClient @Primary @Profile("e2e")`](src/test/java/com/example/app/llm/StubLlmVisionClient.java)) — one convention across all three dev-profile triggers + the existing e2e profile. The sub-package `observability.devtools` is the same one the controller lives in.

**Contract — `DevFailableLlmVisionClient`** (`implements LlmVisionClient`):

```java
@Component
@Primary
@Profile("dev")
class DevFailableLlmVisionClient implements LlmVisionClient {
    static volatile boolean failNextCall = false;
    private final OpenRouterLlmVisionClient delegate;
    DevFailableLlmVisionClient(OpenRouterLlmVisionClient delegate) { this.delegate = delegate; }
    public LlmExtractionResult extract(byte[] image, MimeType mimeType) throws LlmExtractionException {
        if (failNextCall) {
            failNextCall = false;
            // Same wire shape as a real OpenAIServiceException sanitized at OpenRouterLlmVisionClient:105-110:
            // status + "code=… type=… param=…". Produces an identical Sentry event signature to a prod incident.
            throw LlmExtractionException.provider(503, "code=forced_dev_error type=test param=?", null);
        }
        return delegate.extract(image, mimeType);
    }
}
```

If `OpenRouterLlmVisionClient` is not bean-name-resolvable without `@Qualifier` once `@Primary` is taken away from it (which it loses to this wrapper), inject by type with `@Qualifier("openRouterLlmVisionClient")` or expose the prod bean explicitly via a `@Bean` factory method on `LlmVisionClientConfig`. Implementer chooses; the contract is that this wrapper holds the `@Primary` while the prod client remains injectable.

**Contract — `DevFailableSourceImagePurgeService`** (`extends SourceImagePurgeService`):

```java
@Service
@Primary
@Profile("dev")
class DevFailableSourceImagePurgeService extends SourceImagePurgeService {
    static volatile boolean failNextCall = false;
    DevFailableSourceImagePurgeService(SourceImageRepository repo) { super(repo); }
    @Override
    @Transactional
    public int purgeEligible() {
        if (failNextCall) {
            failNextCall = false;
            throw new RuntimeException("forced purge failure for dev smoke");
        }
        return super.purgeEligible();
    }
}
```

`SourceImagePurgeService` is a public non-final class with a public constructor and a public `purgeEligible()` method ([SourceImagePurgeService.java:24-35](src/main/java/com/example/app/event/SourceImagePurgeService.java)) — extension is straightforward; no interface extraction needed.

Both override beans **must** carry `@Profile("dev")`. Under any other profile (default, e2e, test, production) Spring skips them entirely; the prod beans stay `@Primary` by default and unchanged. The dev controller's three endpoints flip flags directly on the static fields (`DevFailableLlmVisionClient.failNextCall = true;` etc.), then invoke the real workflow.

#### 3. `DevForceErrorControllerNonDevTest` — regression test for non-dev profile gating

**File**: `src/test/java/com/example/app/observability/devtools/DevForceErrorControllerNonDevTest.java` (new file)

**Intent**: Guarantee the dev endpoint AND the two dev override beans are NOT registered under any non-dev profile. If a future change accidentally drops the `@Profile("dev")` annotation from any of them, this test fails before merge — preventing a permanent dev-tooling surface from leaking into production.

**Contract**: `@SpringBootTest` with default profiles (i.e., no `@ActiveProfiles("dev")`). Inject the `ApplicationContext`. Assert all three are empty in one test (or three small tests, implementer's choice):

```java
assertThat(ctx.getBeansOfType(DevForceErrorController.class)).isEmpty();
assertThat(ctx.getBeansOfType(DevFailableLlmVisionClient.class)).isEmpty();
assertThat(ctx.getBeansOfType(DevFailableSourceImagePurgeService.class)).isEmpty();
```

Under the default profile, the prod `LlmVisionClient` and `SourceImagePurgeService` beans remain `@Primary`-by-default and uniquely satisfy `@Autowired` consumers.

#### 4. Local smoke runbook — embedded in `change.md`

**Intent**: Capture the operator steps for running the smoke against a dev Sentry project. Lives in `change.md` so it's discoverable from the change folder without spelunking the plan.

**Contract**: New section in `change.md` titled `## Dev smoke runbook (Phase 3)`. Six steps:

1. Verify the dev Sentry project exists (preconditions checklist) and capture `DSN_DEV`.
2. Start the app under the dev profile with the dev DSN and a dev environment tag:
   ```bash
   SENTRY_DSN='<DSN_DEV>' \
   SENTRY_ENVIRONMENT=dev \
   SPRING_PROFILES_ACTIVE=dev \
   ./gradlew bootRun
   ```
3. Log in (any user) so the `extractionExecutor` async dispatch has a request scope to propagate.
4. For each type, trigger the endpoint:
   ```bash
   curl -X POST -u <devuser>:<pass> http://localhost:8080/__dev/force-error/extraction
   curl -X POST -u <devuser>:<pass> http://localhost:8080/__dev/force-error/purge
   curl -X POST -u <devuser>:<pass> http://localhost:8080/__dev/force-error/controller
   ```
5. Verify in the dev Sentry dashboard within ≤ 30s:
   - All three events appear.
   - Each event's `environment` tag = `dev` (not empty, not `production`).
   - Each event's `release` tag = the current commit SHA (if running from a Docker image) or empty (if `./gradlew bootRun` from source — acceptable for local smoke).
   - `user.username` is absent on all three.
   - `request.url` for the controller event does not contain a calendar token (no calendar route involved here, but verify the URL field is present and scrubbed if applicable).
   - The async extraction event carries the extraction correlation ID as a tag.
6. Delete the three dev events from the dashboard after verification to keep the dev project clean.

### Success Criteria:

#### Automated Verification:

- Non-dev gating: `./gradlew test --tests com.example.app.observability.devtools.DevForceErrorControllerNonDevTest` passes (controller + both Dev*Override beans absent under default profile).
- Dev-profile bean activation *(test-only)*: a companion `DevForceErrorControllerDevTest` with `@ActiveProfiles("dev")` asserts the controller + `DevFailableLlmVisionClient` + `DevFailableSourceImagePurgeService` ARE registered. (Optional but cheap to add — same file structure.)

#### Manual Verification:

- Full smoke runbook executed locally against a dev Sentry project; all six runbook steps complete successfully.
- All three event types arrive in dev Sentry ≤ 30s after trigger.
- Each event carries `environment=dev` (fail-loud check — empty environment surfaces here, not in prod).

**Implementation Note**: After Phase 3 lands and the smoke runbook passes, pause before Phase 4.

---

## Phase 4: Ship + verification

### Overview

Production cutover. The earlier phases have already proven the safety net; this phase deploys it to prod, sets the prod DSN, and works through the embedded verification checklist. No new code — operator runbook + ticked checkboxes.

### Changes Required:

#### 1. `SENTRY_DSN` (prod) onboarding — operator step

**Intent**: Set the prod DSN as a Fly secret. One-time per environment; survives across deploys.

**Contract**: Run `flyctl secrets set SENTRY_DSN='<DSN_PROD>' --app ogarniacz`. Fly will restart the machine; Sentry starts emitting events on the restart.

#### 2. Production deploy (existing CI path)

**Intent**: Merge the Phase 1-3 changes to `main`; the existing `deploy.yml` triggers; the new image carries `SENTRY_RELEASE=<commit SHA>` and `SENTRY_ENVIRONMENT=production`; the SDK initializes with the prod DSN already in Fly secrets.

**Contract**: Merge to `main`. No CI changes — the existing workflow handles it. Wait for the deploy step in GitHub Actions to complete, then proceed to verification.

#### 3. Embed verification checklist into `change.md`

**File**: `context/changes/sentry-instrumentation/change.md`

**Intent**: Five unchecked items operator works through after the prod deploy completes. Lives in `change.md` so it's findable from the change folder and visible to anyone tracking ship state.

**Contract**: New `## Verification (Phase 4)` section in `change.md`. Items as specified by the user, with concrete triggers and grep targets so verification cannot be hand-waved:

```markdown
## Verification (Phase 4)

After first production deploy that includes Phase 1–3 changes:

- [ ] **Trigger a known 500.** Log in (or use an existing session); hit `GET /events/<nonexistent-id>` (any UUID-shape that does not exist in the DB) to trip [EventController](src/main/java/com/example/app/event/EventController.java) `Optional.orElseThrow()` → 500. Note the timestamp.
- [ ] **Event visible in prod Sentry ≤ 30s.** Open the prod project's Issues view. The event for the 500 above should appear within 30 seconds (Sentry's default ingestion latency budget). If it does not, check `flyctl logs` for SDK errors and verify `flyctl ssh console` → `env | grep SENTRY` shows non-empty DSN.
- [ ] **`release` tag matches deploy SHA.** Open the event; its `release` field should equal the full commit SHA of the deploy. Cross-check against `git log -1 --format=%H` on the deployed branch.
- [ ] **No PII in payload.** Click into the raw event JSON. Grep against the test-account email, the test-account iCal token, **and an Authorization Bearer fragment** (defense-in-depth empirical confirmation of the rule 8 / `send-default-pii=false` responsibility split):
  ```
  pbpaste | grep -i 'test-account-email@example.com'   # expect: no output
  pbpaste | grep -i '<test-account-ical-token>'         # expect: no output
  pbpaste | grep -iE 'Bearer [A-Za-z0-9_\-\.]{16,}'    # expect: no output (any unscrubbed Bearer token, header or body)
  ```
  (Paste the raw event JSON into clipboard first.) Any match means a scrubber rule regressed or the SDK flag is misconfigured; **stop the rollout and open a bug** instead of ticking the box.
- [ ] **Delete the verification event from Sentry.** Sentry → Issues → select the verification event → Delete Issue. Defense-in-depth: even though we expect the payload to be clean, deleting it removes any forgotten edge-case leak from the dashboard.
```

### Success Criteria:

#### Automated Verification:

- (None beyond what Phases 1-3 already exercised.) The CI deploy step is the only automated criterion: `deploy.yml` finishes green and `flyctl status` shows the new release version.

#### Manual Verification:

- All five Verification checkboxes in `change.md` ticked, each with a concrete observation in a deploy note (or commit message at archive time): "Event ID=<X> appeared at <T+Xs>; release tag matched; grep clean."
- If any checkbox fails: **do not tick it**. Open a follow-up issue, decide whether to roll back or roll forward with a fix, and document the resolution in `change.md` before marking the phase done.

**Implementation Note**: Phase 4 is the only phase whose success depends on human eyes on the Sentry dashboard. Do not mark it complete from CI signal alone.

---

## Testing Strategy

### Unit Tests:

- **`SentryConfigTest`** — 12 methods: 11 positive (one per rule, plus the URL+breadcrumb arms of rule 1, plus the extras+message arms of rules 6a/6b) and 1 negative for rule 9 (no false positive on unrelated SQL errors). Exercises the `BeforeSendCallback` rules directly against synthetic `SentryEvent` instances (rule 6b's test uses a real `DataBinder`-driven `MethodArgumentNotValidException`). No Spring context; pure unit tests.
- **`ErrorsOnlyEnforcementTest`** — `@SpringBootTest` boot-time assertion that `tracesSampleRate == 0.0`, `profileSessionSampleRate == 0.0`, `logs.enabled == false`. Catches accidental config bumps.
- **`SentryPropertyKeyPropagationTest`** — `@SpringBootTest` + `@TestPropertySource` overriding each errors-only key to a deliberate non-default; asserts the value reaches `SentryOptions`. Catches "key silently ignored" failures that `ErrorsOnlyEnforcementTest` alone cannot detect (SDK default and prod-config value both being 0.0 makes the value assertion vacuous).
- **`DevForceErrorControllerNonDevTest`** — `@SpringBootTest` with default profile, asserts the dev controller bean is NOT registered. Prevents accidental prod leakage of dev tooling.

### Integration Tests:

- **None new.** The Sentry integration is verified end-to-end by the Phase 3 dev smoke and Phase 4 prod verification checklist — both manual, both required, both more honest than a mock-based "integration" test that would not exercise real ingestion latency, scope-decorator propagation, or release-tag wiring through the build.

### Manual Testing Steps:

1. **Boot smoke (Phase 1):** Start the app with no `SENTRY_DSN`. Confirm clean startup, no transport.
2. **Dev smoke (Phase 3):** Run the six-step dev smoke runbook against a dev Sentry project. Verify all three error types arrive scrubbed with `environment=dev`.
3. **Prod verification (Phase 4):** Work through the five-item verification checklist in `change.md`.

## Performance Considerations

- **Logback appender overhead** is per-event, not per-log-call. With `minimum-event-level=error`, only ERROR-level logs go through Sentry transport. Healthy app baseline = 0 events/min; an extraction failure spike = a handful of events. Negligible impact on response time.
- **`SentryTaskDecorator` overhead** is per `@Async` task — context copy at task start, restore at end. Negligible for the project's 2-thread `extractionExecutor`.
- **`BeforeSendCallback` overhead** is per event. The 10 rules are O(small constants) per event: regex match, map lookup, string replace, narrow metadata predicates. Negligible.
- **Transport is async.** Sentry's SDK queues events to a background worker; failures (DSN unreachable, ingestion limit) do not block the request thread.

No expected performance regressions. The Phase 4 verification step's "event visible ≤ 30s" budget is Sentry's ingestion SLA, not our latency.

## Migration Notes

No data migration. No DB schema change. No backwards-compatibility shim — the change is purely additive (new deps, new config, new bean, new test files, new ARG/ENV in Dockerfile, new `[env]` block in fly.toml).

**Rollback**: revert the `main`-branch commit containing the changes; `deploy.yml` redeploys the prior image; `flyctl secrets unset SENTRY_DSN` if a clean rollback to no-Sentry state is desired. Image-level rollback via `flyctl releases rollback` also works since the SHA-tagged release is part of the image metadata.

## References

- Related research: [`context/changes/sentry-instrumentation/research.md`](research.md)
- Project foundation: [`context/foundation/prd.md`](../../foundation/prd.md), [`context/foundation/roadmap.md`](../../foundation/roadmap.md) §Done
- Similar wiring pattern (env-placeholder + profile override): [`spring.ai.openai.api-key=${OPENROUTER_API_KEY:...}`](../../../src/main/resources/application.properties) and [`StubLlmVisionClient @Profile("e2e")`](../../../src/test/java/com/example/app/llm/StubLlmVisionClient.java)
- Closest prior change: [`context/archive/2026-06-21-source-image-auto-purge/`](../../archive/2026-06-21-source-image-auto-purge/) — established the "log loud if the sweep stalls" contract that the Sentry Logback appender now amplifies cross-network.

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `.claude/skills/10x-plan/references/progress-format.md`.

### Phase 1: SDK + config + async wiring + BeforeSendCallback + 12 unit tests

#### Automated

- [x] 1.1 Compile + dependency resolution: `./gradlew build` passes — eb7ab11
- [x] 1.2 Errors-only enforcement: `./gradlew test --tests com.example.app.observability.ErrorsOnlyEnforcementTest` passes — eb7ab11
- [x] 1.2b Key-name propagation: `./gradlew test --tests com.example.app.observability.SentryPropertyKeyPropagationTest` passes (overrides each errors-only key to a non-default and asserts it reaches `SentryOptions`) — eb7ab11
- [x] 1.3 Scrubber rules: `./gradlew test --tests com.example.app.observability.SentryConfigTest` passes (all 12 methods green — 11 positive + 1 negative on rule 9) — eb7ab11
- [x] 1.4 Test suite as a whole: `./gradlew test` passes (no regressions from the `src/test/resources/application.properties` shadow file — adapted to a Gradle `test` task env override of `SENTRY_DSN`/`SENTRY_ENABLED`; shadow file dropped after it broke Spring AI's `api-key` resolution) — eb7ab11
- [x] 1.5 E2E suite still green: `cd e2e && npx playwright test --reporter=list` passes — eb7ab11

#### Manual

- [x] 1.6 Boot smoke with empty DSN: `./gradlew bootRun` starts cleanly; SDK-disabled log line visible — eb7ab11
- [x] 1.7 Boot smoke with DSN-but-disabled: `SENTRY_ENABLED=false SENTRY_DSN=<dummy> ./gradlew bootRun` starts cleanly; no transport — eb7ab11
- [x] 1.8 Test-shadow check: N/A — shadow file dropped in favor of Gradle `test` env override (`SENTRY_DSN=''`/`SENTRY_ENABLED=false` in `build.gradle`); developer shell vars cannot leak into test JVM — eb7ab11
- [x] 1.9 Runtime-check smoke: starting `./gradlew bootRun` with a deliberately bad `sentry.traces-sample-rate=0.1` fails with `IllegalStateException` from `SentryConfig` `@PostConstruct` — eb7ab11

### Phase 2: Release tag + deploy plumbing

#### Automated

- [x] 2.1 Docker image accepts the build arg: `docker build --build-arg SENTRY_RELEASE=test123 -t ogarniacz:test .` succeeds — 248b3c9
- [x] 2.2 Image carries the env var: `docker run --rm --entrypoint env ogarniacz:test | grep SENTRY_RELEASE` outputs `SENTRY_RELEASE=test123` — 248b3c9
- [x] 2.3 `fly.toml` parses: locally substituted with `python3 -c "import tomllib; tomllib.load(open('fly.toml','rb'))"` (`flyctl config validate` needs platform auth — full platform-side validation happens on first deploy via CI) — 248b3c9
- [x] 2.4 `deploy.yml` syntax check: locally substituted with a structural grep confirming `--build-arg SENTRY_RELEASE=${{ github.sha }}` is on the `flyctl deploy` line (`actionlint` not installed locally; GitHub Actions UI parses the workflow on push) — 248b3c9

#### Manual

- [x] 2.5 Stage-deploy dry run (optional): SKIPPED — no non-prod Fly app exists for this project; only `ogarniacz` (prod) is defined in `fly.toml`. The plan tags this step explicitly optional ("if a non-prod Fly app exists"). The Phase 4 prod deploy + verification covers the same observable (`env | grep SENTRY` on the live machine). — 248b3c9
- [x] 2.6 `flyctl secrets set SENTRY_DSN=<dummy>` triggers machine restart: DEFERRED to Phase 4 §1 — running with a dummy DSN first and then the real prod DSN is operator-churn (two machine restarts). Phase 4 §1's `flyctl secrets set SENTRY_DSN='<DSN_PROD>' --app ogarniacz` is the same operation with the real value; the deferred check (no Sentry-related startup errors in `flyctl logs`) folds into Phase 4 §1. — 248b3c9

### Phase 3: Dev `__dev/force-error/{type}` smoke endpoint

#### Automated

- [x] 3.1 Non-dev gating: `./gradlew test --tests com.example.app.observability.devtools.DevForceErrorControllerNonDevTest` passes (controller + both override beans + dev security overlay absent under default profile)
- [x] 3.2 (Optional) Dev-profile bean activation: `DevForceErrorControllerDevTest` with `@ActiveProfiles("dev")` asserts controller + `DevFailableLlmVisionClient` + `DevFailableSourceImagePurgeService` + `DevForceErrorSecurityConfig` ARE registered

#### Manual

- [x] 3.3 Smoke runbook step 1: dev Sentry project exists; `DSN_DEV` captured
- [x] 3.4 Smoke runbook step 2: app starts under `dev` profile with dev DSN + `SENTRY_ENVIRONMENT=dev`
- [x] 3.5 Smoke runbook steps 3–4: all three force-error endpoints trigger successfully
- [x] 3.6 Smoke runbook step 5: all three events arrive in dev Sentry ≤ 30s with `environment=dev` and scrubbed PII
- [x] 3.7 Smoke runbook step 6: dev events deleted from dashboard after verification

### Phase 4: Ship + verification

#### Automated

- [ ] 4.1 `deploy.yml` finishes green on `main` merge; `flyctl status` shows new release version

#### Manual

- [ ] 4.2 Trigger a known 500: `GET /events/<nonexistent-id>` → 500; timestamp noted
- [ ] 4.3 Event visible in prod Sentry ≤ 30s
- [ ] 4.4 `release` tag on event equals deploy SHA (cross-checked against `git log -1 --format=%H`)
- [ ] 4.5 No PII in payload: raw JSON grep clean against test-account email and test-account iCal token
- [ ] 4.6 Verification event deleted from Sentry dashboard
