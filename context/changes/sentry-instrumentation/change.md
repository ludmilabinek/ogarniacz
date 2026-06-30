---
change_id: sentry-instrumentation
title: Sentry instrumentation
status: implementing
created: 2026-06-29
updated: 2026-06-30
archived_at: null
---

## Notes

<!-- Free-form notes for this change: links, ad-hoc context, decisions that don't belong in research/frame/plan. -->

### Phase 3 adaptation: `DevForceErrorSecurityConfig`

The plan's runbook hits `POST /__dev/force-error/{type}` with `curl -u <devuser>:<pass>`, but the project's `SecurityConfig` only enables `formLogin` (no `httpBasic()`) and leaves CSRF on. To keep the runbook working as written without touching production security, Phase 3 adds one extra file beyond the plan's contract: `DevForceErrorSecurityConfig` — a `@Profile("dev")`-gated `SecurityFilterChain` bean with `securityMatcher("/__dev/force-error/**")`, `httpBasic()` enabled, and CSRF disabled. Highest-precedence ordering ensures it wins over the default chain. The two dev profile tests (`DevForceErrorControllerNonDevTest`, `DevForceErrorControllerDevTest`) were extended to also assert presence/absence of this bean across profiles.

## Dev smoke runbook (Phase 3)

After Phase 3 lands, walk through this against a dev Sentry project before turning on prod.

1. Verify the dev Sentry project exists (Preconditions checklist in `plan.md`) and capture `DSN_DEV`.
2. Start the app under the dev profile with the dev DSN and a dev environment tag:
   ```bash
   SENTRY_DSN='<DSN_DEV>' \
   SENTRY_ENVIRONMENT=dev \
   SPRING_PROFILES_ACTIVE=dev \
   ./gradlew bootRun
   ```
3. Log in (any user) so the `extractionExecutor` async dispatch has a request scope to propagate.
4. Trigger each force-error endpoint:
   ```bash
   curl -X POST -u <devuser>:<pass> http://localhost:8080/__dev/force-error/extraction
   curl -X POST -u <devuser>:<pass> http://localhost:8080/__dev/force-error/purge
   curl -X POST -u <devuser>:<pass> http://localhost:8080/__dev/force-error/controller
   ```
5. Verify in the dev Sentry dashboard within ≤ 30s:
   - All three events appear.
   - Each event's `environment` tag = `dev` (not empty, not `production`).
   - Each event's `release` tag = the current commit SHA (if running from a Docker image) or empty (acceptable for `./gradlew bootRun` from source).
   - `user.username` is absent on all three.
   - `request.url` for the controller event does not contain a calendar token (no calendar route involved, but verify the URL field is present and scrubbed if applicable).
   - The async extraction event carries the extraction correlation ID as a tag.
6. Delete the three dev events from the dashboard after verification to keep the dev project clean.

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
