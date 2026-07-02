# Sentry instrumentation (errors-only) — Plan Brief

> Full plan: `context/changes/sentry-instrumentation/plan.md`
> Research: `context/changes/sentry-instrumentation/research.md`

## What & Why

Adopt Sentry for **errors-only** observability on Ogarniacz (Spring Boot 4 / Java 21 / Fly.io) so that uncaught controller exceptions and `log.error(...)` calls from async services and `@Scheduled` sweeps reach a centralized dashboard instead of disappearing into `fly logs`. No performance tracing, no logs ingestion, no profiling. The wiring is small; the hard work is preventing PII (child names, parent emails, iCal tokens, photo bytes) from leaving the JVM unfiltered.

## Starting Point

The codebase has no error tracking today — only `/actuator/health` (DB indicator disabled to avoid Neon idle drain) and UptimeRobot polling it. Four async/scheduled spots silently fail by design: `ExtractionService.runExtraction()`, `SourceImagePurgeScheduler.sweep()`, `ExtractionJobRegistry.sweep()`, and the pre-try DB lookup. All four already funnel through `log.error(...)`, which means the Sentry Logback appender captures them without service-code surgery. Deploy pipeline (`.github/workflows/deploy.yml` → `flyctl deploy --remote-only`) and `Dockerfile` / `fly.toml` are clean addition surfaces.

## Desired End State

Every `log.error(...)` becomes a Sentry event tagged with the deploy's commit SHA and `environment=production`; 7 named scrubber rules strip PII (iCal token, user email, photo bytes, extracted content, LLM raw response, persistent_logins email, Authorization headers) before transit; `errors-only` invariant is enforced by config and a boot-time `SentryOptions` assertion (not trusted to starter defaults); a permanent dev `__dev/force-error/{type}` endpoint under `@Profile("dev")` lets us smoke errors against a dev Sentry project without contaminating prod.

## Key Decisions Made

| Decision                              | Choice                                                              | Why (1 sentence)                                                                                              | Source   |
| ------------------------------------- | ------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------- | -------- |
| SDK choice                            | `io.sentry:sentry-spring-boot-4` (8.x) + `sentry-logback`           | Official Spring Boot 4 starter; Logback appender is the load-bearing async/scheduled capture mechanism        | Research |
| iCal token URL scrubbing              | Regex-redact the token segment in `BeforeSendCallback`              | Keeps controller-level grouping intact; surgical, low blast radius                                            | Plan     |
| Minimum log level → Sentry event      | `ERROR` only                                                        | Matches the existing async/scheduled error contract; quiet inbox                                              | Plan     |
| `SentryTaskDecorator` wiring          | On `extractionExecutor` only; skip `@Scheduled`                     | Async LLM failures benefit from request-scope breadcrumbs; scheduled tasks have no inbound scope to propagate | Plan     |
| `sentry.release` source               | Build-arg from CI (commit SHA) via Docker `--build-arg`             | Avoids Fly-secret churn on every deploy; immutable per image                                                  | Plan     |
| `sentry.environment` deployment       | `fly.toml [env]` (NOT a Fly secret)                                 | It's config, not credential; belongs in version-controlled infra                                              | Plan     |
| `sentry.environment` config default   | Empty (`${SENTRY_ENVIRONMENT:}`), not `production`                  | Fail-loud — local dev with prod DSN ships events with empty env, visibly wrong, surfaces the misconfiguration | Plan     |
| Errors-only invariant enforcement     | Explicit zero-out keys + boot-time `SentryOptions` assertion        | Config contract, not starter-default trust                                                                    | Plan     |
| Test-classpath defense                | `src/test/resources/application.properties` (NO profile suffix)     | `@SpringBootTest` doesn't auto-activate `test` profile; same-name file shadow is the canonical mechanism      | Plan     |
| Verification rigor                    | All three: unit tests + dev smoke + prod verification checklist     | Each layer catches a different failure class — scrubber regression, wiring regression, prod-config drift      | Plan     |
| `BeforeSendCallback` and Phase 1      | Both ship together; no Phase-1-without-scrubber state               | Invariant: no live DSN until scrubber is wired and tested; eliminates an unsafe LGTM window                   | Plan     |
| Dev smoke endpoint persistence        | Keep as permanent dev tooling (not delete-after-smoke)              | Reproducible verification on future SDK upgrades, scrubber changes, incident drills                           | Plan     |

## Scope

**In scope:**
- `sentry-spring-boot-4` + `sentry-logback` on the classpath
- 11-key errors-only config block in `application.properties`
- `BeforeSendCallback` bean with 7 named scrubber rules + matching unit tests
- `SentryTaskDecorator` bean wired onto `extractionExecutor`
- Boot-time `SentryOptions` assertion enforcing errors-only invariant
- Test-classpath shadow `application.properties` + e2e profile kill-switch
- Dockerfile `ARG SENTRY_RELEASE` + ENV pass-through
- `deploy.yml` `--build-arg SENTRY_RELEASE=${{ github.sha }}`
- `fly.toml [env] SENTRY_ENVIRONMENT = "production"`
- `flyctl secrets set SENTRY_DSN` (one-time, documented)
- Dev `POST /__dev/force-error/{type}` endpoint + non-dev profile regression test
- Embedded prod verification checklist in `change.md`

**Out of scope:**
- Performance tracing, logs ingestion, profiling, OTel agent
- Source-context upload (`io.sentry.jvm.gradle` plugin)
- Rewriting `ExtractionService` / `SourceImagePurgeScheduler` catch blocks
- `AsyncUncaughtExceptionHandler` bean
- Bumping `MaxUploadSizeExceededHandler` to log.warn
- `groupId` rename from `com.example.app`

## Architecture / Approach

```
        ┌──────────────────────────────────────────────────────┐
        │  Spring Boot 4 app (com.example.app)                 │
        │                                                       │
        │  Controllers ─┐                                       │
        │  @Async ──────┤  log.error(...)  ── SLF4J ─┐          │
        │  @Scheduled ──┘                            │          │
        │                                            ▼          │
        │                              sentry-logback appender  │
        │                                            │          │
        │                                            ▼          │
        │                              BeforeSendCallback (7 rules) ── ◯ DSN empty? ── drop
        │                                            │                                 │
        │                                            ▼                                 ▼
        │                              Sentry transport (async) ────► sentry.io/<project>
        └──────────────────────────────────────────────────────┘
                                       ▲
                                       │  SENTRY_RELEASE = $github.sha (build-arg)
                                       │  SENTRY_ENVIRONMENT = "production" (fly.toml)
                                       │  SENTRY_DSN = secret (flyctl secrets set)
```

The Logback appender is the single capture mechanism for all error paths (controller, async, scheduled). `BeforeSendCallback` is the single PII chokepoint. `SentryTaskDecorator` carries request scope onto `extractionExecutor` threads. Three categorical deploy mechanisms (build-arg / `[env]` / secret) keep release tag, environment config, and credential cleanly separated.

## Phases at a Glance

| Phase                                                                                       | What it delivers                                                                                                                        | Key risk                                                                                                             |
| ------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------- |
| 1. SDK + config + async wiring + `BeforeSendCallback` + 7 unit tests                        | Sentry on classpath, errors-only enforced, scrubber wired & proven by tests — safe to enable DSN any time after                         | Test-shadow file accidentally hides main `application.properties` properties; mitigated by Success Criterion 1.4/1.8 |
| 2. Release tag + deploy plumbing (Dockerfile ARG, deploy.yml --build-arg, fly.toml `[env]`) | Commit SHA tags every Sentry event; `SENTRY_ENVIRONMENT=production` lives in git-controlled config                                      | Forgetting `[env]` block in future cleanup PR breaks environment tagging silently — flagged in Plan                  |
| 3. Dev `__dev/force-error/{type}` smoke endpoint                                            | Permanent dev tooling for reproducible Sentry smoke; regression test prevents prod leakage                                              | None significant — endpoint gated by `@Profile("dev")` + regression test asserts non-registration                    |
| 4. Ship + verification                                                                      | Prod cutover via existing CI; 5-item verification checklist in `change.md` ticked with concrete observations                            | Scrubber regression discovered post-deploy → checklist explicitly says "do not tick, open bug, decide roll-back"     |

**Prerequisites:**
- ✅ `.github/workflows/deploy.yml`, `fly.toml`, `Dockerfile` clean addition surfaces (verified)
- ⏳ Sentry org + dev project + prod project created (DSN_DEV, DSN_PROD captured) — gates Phase 4 only; Phases 1–3 can land before

**Estimated effort:** ~3 sessions across 4 phases. Phase 1 is the bulk (deps + config + bean + 12 unit tests for the scrubber + 2 boot-level tests for errors-only / key-name propagation + `@PostConstruct` runtime check + lessons.md entry). Phases 2–4 are mostly one-line changes + checklist execution.

## Open Risks & Assumptions

- **~~`sentry.profile-session-sample-rate` vs `sentry.profiles-sample-rate` key name~~** *(resolved by plan-review F5 fix)*: canonical 8.x key name remains unconfirmed in Context7's surfaced snippets, but Phase 1 §8b (`SentryPropertyKeyPropagationTest`) sets each errors-only key to a deliberate non-default and asserts it reaches `SentryOptions` — catches "key silently ignored" without depending on documentation precision. If a future SDK upgrade renames the key, this test fails fast.
- **~~`send-default-pii=false` Java semantics~~** *(resolved by plan-review F8 fix — rationale split, not empirical wait)*: instead of waiting for empirical verification, the plan now codifies a clean responsibility split between the SDK flag and scrubber rule 8:
  - **SDK flag `send-default-pii=false`** handles **inbound request metadata** auto-attachment (IP, Principal username, cookie headers, Authorization header on `request.headers`).
  - **Scrubber rule 8** handles **body-content** Authorization/Bearer leaks (e.g., an OpenAI SDK exception cause embedding the outbound request snapshot into its message; a future contributor's `log.error("…", request.getHeaders())` flattening headers into the log line).
  - The two surfaces do not overlap — neither subsumes the other.
  - Phase 3 dev smoke + Phase 4 prod verification keep the empirical Bearer-grep check as a final defense-in-depth confirmation (not as the resolution of the open risk).
- **Test-classpath `application.properties` shadow** may unintentionally hide properties from main `application.properties`. Phase 1 Success Criterion 1.4/1.8 verifies. Fallback: switch to `@TestPropertySource` on a base test class.
- **Sentry-side preconditions** (org, dev project, prod project) are not verifiable from the repo. Plan's Preconditions checklist gates Phase 4 start; Phases 1–3 can proceed without.

## Success Criteria (Summary)

- An unhandled controller exception in production reaches the Sentry dashboard ≤ 30s after it fires, tagged with the deploy commit SHA and `environment=production`, with zero PII in the payload (verified by raw-JSON grep against test-account email and iCal token).
- Future scrubber regression is caught at unit-test time (one of the 12 named `SentryConfigTest` methods fails) before merge — not at incident time after a leak.
- An accidental future bump of `sentry.traces-sample-rate` from `0.0` is caught at boot by `ErrorsOnlyEnforcementTest` before deploy — not by a surprise bill.
