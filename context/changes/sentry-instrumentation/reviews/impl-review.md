<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Sentry instrumentation (errors-only)

- **Plan**: context/changes/sentry-instrumentation/plan.md
- **Scope**: All 4 phases (Phase 1–4)
- **Date**: 2026-07-02
- **Verdict**: APPROVED
- **Findings**: 0 critical, 0 warnings, 4 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | PASS |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

## Automated verification

`./gradlew test --tests com.example.app.observability.ErrorsOnlyEnforcementTest --tests com.example.app.observability.SentryPropertyKeyPropagationTest --tests com.example.app.observability.SentryConfigTest --tests com.example.app.observability.devtools.DevForceErrorControllerNonDevTest --tests com.example.app.observability.devtools.DevForceErrorControllerDevTest` — **BUILD SUCCESSFUL**. 12 scrubber methods (11 positive + 1 negative on rule 9) plus 4 boot-level tests all green.

## Scope check (plan-file list vs actual diff)

Files touched across `eb7ab11..HEAD` (5 commits):

- Planned + present: `build.gradle`, `src/main/resources/application.properties`, `src/test/resources/application-e2e.properties`, `src/main/java/com/example/app/observability/SentryConfig.java`, `src/main/java/com/example/app/AppApplication.java`, `context/foundation/lessons.md`, `src/test/java/com/example/app/observability/SentryConfigTest.java`, `src/test/java/com/example/app/observability/ErrorsOnlyEnforcementTest.java`, `src/test/java/com/example/app/observability/SentryPropertyKeyPropagationTest.java`, `Dockerfile`, `.github/workflows/deploy.yml`, `fly.toml`, `src/main/java/com/example/app/observability/devtools/DevForceErrorController.java`, `src/main/java/com/example/app/observability/devtools/DevFailableLlmVisionClient.java`, `src/main/java/com/example/app/observability/devtools/DevFailableSourceImagePurgeService.java`, `src/test/java/com/example/app/observability/devtools/DevForceErrorControllerNonDevTest.java`, `context/changes/sentry-instrumentation/change.md`.
- Planned-optional, present: `src/test/java/com/example/app/observability/devtools/DevForceErrorControllerDevTest.java` (plan §3 "Optional but cheap"; Progress 3.2 delivered).
- Planned but adapted (documented in change.md Progress 1.4/1.8): `src/test/resources/application.properties` shadow was dropped in favor of a Gradle `test`-task env override in `build.gradle` after the shadow broke Spring AI's `api-key` resolution.
- Extra file, documented in change.md "Phase 3 adaptation" section: `src/main/java/com/example/app/observability/devtools/DevForceErrorSecurityConfig.java` (`@Profile("dev")` `SecurityFilterChain` with `securityMatcher("/__dev/force-error/**")`, `httpBasic()` enabled, CSRF disabled, `@Order(HIGHEST_PRECEDENCE)`). Needed because production `SecurityConfig` uses `formLogin` only; the plan's runbook curl calls require httpBasic. Both dev-profile tests were extended to cover its presence/absence.

No file present in the plan is missing from the diff. No unplanned files exist outside the one above, which is documented.

## Findings

### F1 — Rule 2 also nulls `user.email` (beyond plan spec)

- **Severity**: OBSERVATION
- **Impact**: LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: `src/main/java/com/example/app/observability/SentryConfig.java:180-182`
- **Detail**: Plan §5 rule 2 says "set `user.setUsername(null)`; keep `user.id`". Impl also nulls `user.email` — benign strengthening (email is another PII surface if the SDK ever attaches it via a future integration). Existing test `scrubsUserEmailFromUserUsername()` still passes.
- **Fix**: No change needed — record the strengthening as an accepted deviation.
- **Decision**: ACCEPTED — benign strengthening, kept as-is.

### F2 — Rule 9 also redacts `SentryException.value` (beyond plan wording)

- **Severity**: OBSERVATION
- **Impact**: LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: `src/main/java/com/example/app/observability/SentryConfig.java:290-295`
- **Detail**: Plan §5 rule 9 says "replace the entire message/formatted with `[REDACTED SQL ROW DATA]`". Impl additionally overwrites the matching `SentryException.value` with the same marker when it contains `proposed_event`. Defense-in-depth against JDBC/Hibernate variants that render the row snippet into `ex.value` rather than the log message. Rule 9's negative test still passes — exception TYPE gate is preserved.
- **Fix**: No change needed — record the strengthening.
- **Decision**: ACCEPTED — defense-in-depth for JDBC/Hibernate variants, kept as-is.

### F3 — Runtime invariant gates on `sentry.enabled` / DSN state, not profile name

- **Severity**: OBSERVATION
- **Impact**: LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: `src/main/java/com/example/app/observability/SentryConfig.java:75-84`
- **Detail**: Plan §5 says "Gate the runtime check by profile to skip under `test`/`e2e`". Impl gates on `!sentry.enabled OR sentry.dsn.isBlank()` — semantically equivalent (test/e2e both set `sentry.enabled=false`; local-dev without a DSN skipped naturally). Arguably cleaner: adding a new profile that also disables Sentry needs no code change.
- **Fix**: No change needed — mechanism is arguably cleaner than the plan's proposal.
- **Decision**: ACCEPTED — state-based gate preferred over profile-based, kept as-is.

### F4 — `DevForceErrorController` dispatches via `@Async` proxy, not explicit `extractionExecutor.execute(...)`

- **Severity**: OBSERVATION
- **Impact**: LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: `src/main/java/com/example/app/observability/devtools/DevForceErrorController.java`
- **Detail**: Plan §3.1 step 3 says "Dispatch through the real async executor: `extractionExecutor.execute(() -> extractionService.runExtraction(...))`". Impl calls `extractionService.runExtraction(...)` directly, relying on `@Async("extractionExecutor")` on `runExtraction` to dispatch through the same executor via Spring's proxy. Same worker thread, same `SentryTaskDecorator` scope propagation, same log line signature. Also uses a single `@PostMapping("/{type}")` + switch over three explicit mappings — identical HTTP contract.
- **Fix**: No change needed — semantically equivalent.
- **Decision**: ACCEPTED — @Async proxy dispatch is equivalent to explicit executor.execute, kept as-is.

## Notable healthy signals

- Automated: all 5 Sentry-related test classes green.
- The one EXTRA file (`DevForceErrorSecurityConfig.java`) is documented in change.md's "Phase 3 adaptation" section — profile-gated, path-scoped `securityMatcher`, `@Order(HIGHEST_PRECEDENCE)`; pinned by the Dev/NonDev test-pair.
- `BeforeSendCallback` never returns `null` (plan-critical: `null` would drop events). Null-safe walks on request, user, extras, exceptions, breadcrumbs, message.
- Two `Dev*` `@Primary @Profile("dev")` wrappers self-reset `failNextCall` correctly, mirroring `StubLlmVisionClient`'s e2e idiom.
- Rule 9 negative test (`doesNotScrubUnrelatedSqlException`) preserved — exception TYPE metadata gate holds.
- Phase 4's indirect verification (`INDIRECT`/`DEFERRED` on 4.2–4.6) is honestly annotated in the Progress checklist and rationalized in change.md's "Verification epilogue" — no rubber-stamping.

## Findings deliberately NOT raised (safety-agent candidates dismissed against plan)

Several candidate findings were dismissed on close read against the plan:

- Rule 8 not covering `event.request.headers`: plan §Key Discoveries explicitly documents the responsibility split — `send-default-pii=false` handles inbound request-metadata Authorization; rule 8 handles body-content only. Plan-authorized division of labor.
- URL regex `/calendar/[^./]+\.ics` refusing dotted tokens: plan §5 rule 1 specifies this exact regex verbatim. Plan-conformant.
- `ApplicationReadyEvent` timing vs `@PostConstruct`: plan §5 explicitly permits either ("a `@PostConstruct` method (or `ApplicationReadyEvent` listener if it executes after Sentry options finish binding)"). Plan-authorized.
- Empty-DSN default `${SENTRY_DSN:}`: plan §1 rule 2 specifies this exact placeholder verbatim. Plan-conformant.
- Dev endpoint's throwaway `SourceImage` not cleaned up: plan §3.1 step 6 explicitly says "left with `lastErrorKind` set; it gets swept by the next `SourceImagePurgeScheduler.sweep()` — no manual cleanup needed". Plan-authorized.
