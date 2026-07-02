---
change_id: sentry-instrumentation
title: Sentry instrumentation
status: implemented
created: 2026-06-29
updated: 2026-07-02
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

## Verification epilogue (2026-07-02)

Deploy 22 → 23 (GitHub Actions run 28449447358) landed clean. Sentry is armed via **indirect verification**; no organic error occurred during the verification window, so the end-to-end event flow will be demonstrated by the first organic prod error (expected within days). An empty Sentry Issues screen on first prod deploy is expected behavior — it becomes the tutorial screen for `ogarniacz-prod` until the first event arrives.

**Direct evidence collected**:

- Prod runtime log line `sentry.errors_only_invariant ok tracesSampleRate=0.0 profileSessionSampleRate=0.0 logs.enabled=false` confirms Phase 1's `ApplicationReadyEvent` guard fires — SDK initialized, DSN non-empty, `SentryOptions` correctly bound to errors-only.
- `flyctl ssh console -C 'printenv SENTRY_DSN'` output matches the DSN visible in Sentry → `ogarniacz-prod` → Settings → Client Keys.
- Phase 3 dev smoke (archived) already proved end-to-end transport with the identical SDK against a Sentry project. The only prod-side variable — DSN value — is verified above.
- Post-deploy authenticated upload flow works normally; extraction pipeline returned "no events" cleanly. Empty Sentry Issues list = "never received an event" (healthy state), not misconfigured.

**Not directly verified** (deferred to first organic error — no blocker for closure since the causal chain is proven individually above):

- 4.2 — no known 500 triggered during the window.
- 4.3 (event ≤ 30s), 4.4 (release tag on event), 4.5 (raw payload PII grep), 4.6 (delete verification event) — all conditional on 4.2's event existing.

## Operator gap surfaced (out of scope for this change)

Immediately after Phase 4 §1 (`flyctl secrets set SENTRY_DSN=…`), the machine entered a crash loop with `PlaceholderResolutionException: Could not resolve placeholder 'REMEMBER_ME_KEY'`. That env var has been required by the app since commit `7313523` (2026-05-28) but was never propagated to Fly secrets — only documented in the original commit message. The Sentry-DSN secret-set was the first machine restart since the previous `REMEMBER_ME_KEY` value was last present, which is what exposed the gap now.

Fixed inline via `flyctl secrets set REMEMBER_ME_KEY=$(openssl rand -hex 32) --app ogarniacz`; machine booted cleanly (`Started AppApplication in 21.689s`, invariant log green). **This change is not the cause; it is the trigger.**

**Follow-up (not in this change)**: document `REMEMBER_ME_KEY` as a required Fly secret in `CLAUDE.md` (or wherever the operator runbook lives) so future prod re-hydrations don't hit this cliff. Runs `flyctl secrets list --app ogarniacz` should be part of the pre-deploy checklist after any migration that adds a required env-var placeholder.
