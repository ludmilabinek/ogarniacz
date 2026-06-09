# Follow-up fixes from impl-review (S-02 manual-event-entry)

Items deferred during triage on 2026-06-08. Address in a later slice or as a standalone hardening change.

## Reliability — Handle missing `AppUser` row on authenticated requests

**From**: F2 in `reviews/impl-review.md`.

**Locations**:
- `src/main/java/com/example/app/event/EventController.java:41`
- `src/main/java/com/example/app/web/AppController.java:40`

**Risk**: Both controllers call `appUserRepository.findByEmail(auth.getName()).orElseThrow()`. Once an account-deletion path lands (S-03 or later), a still-valid session/remember-me cookie will hit `NoSuchElementException` → HTTP 500 instead of forcing re-auth.

**Recommended remediation** (do this alongside whatever slice introduces user deletion):
1. Define a typed exception (e.g. `AuthenticatedUserMissingException`).
2. Replace `orElseThrow()` at all auth-to-entity sites with `orElseThrow(AuthenticatedUserMissingException::new)`.
3. Add a `@ControllerAdvice` / `@ExceptionHandler` that responds 401 + clears the session, instead of letting it bubble to 500.
4. Audit every controller that resolves an `AppUser` from `Authentication` — F2's blind-spot note flagged that not every call site was traced.

**Do not do now**: introducing the exception type + handler today adds scope this slice didn't plan for, with no present-day failure to fix (no delete-user path exists yet).

## Test layout — extract controller assertions from `AppApplicationTests` into per-controller classes

**From**: F5 in `reviews/impl-review.md`, codified as the new lesson "Per-controller `@SpringBootTest` class is the test layout standard" in `context/foundation/lessons.md`.

**Locations**: `src/test/java/com/example/app/AppApplicationTests.java` — currently ~25 controller assertions across auth/signup/login/app/logout/root + the event partition test (`appShowsUpcomingEventsForCurrentUserOnly`).

**Risk**: `AppApplicationTests` is now the deviant case under the rule. New slices will look at it as the precedent and keep folding controller tests in, defeating the per-controller split the rule mandates.

**Recommended remediation** (do this in a dedicated test-refactor slice — not bundled with feature work):
1. Create per-controller test classes mirroring `EventControllerTest`'s shape (`@SpringBootTest` + `@AutoConfigureMockMvc` + `@TestPropertySource(properties = "REMEMBER_ME_KEY=test-key-not-for-production")`):
   - `SignupControllerTest` — `getSignupPageIsPublic`, `signupHappyPathCreatesUserAndAutoLogsIn`, `signupDuplicateEmailRendersFieldError`, `signupMixedCaseEmailNormalizesToLowercase`, `signupShortPasswordRendersFieldError`.
   - `LoginControllerTest` — `getLoginPageIsPublic`, `loginHappyPath`, `loginMixedCaseEmailAuthenticates`, `loginBadPasswordShowsGenericError`, `seededUserCanAuthenticate`, `rememberMeCookieReAuthenticatesAfterSessionEnds`.
   - `AppControllerTest` — `anonymousGetAppRedirectsToLogin`, `getAppAuthenticatedShowsEmail`, `logoutInvalidatesSessionAndRedirects`, `appShowsOwnEmailOnlyNotOtherUsersEmail`.
   - `RootRedirectControllerTest` (or similar) — `rootRedirectsAnonymousToLogin`, `rootRedirectsAuthenticatedToApp`.
2. Move `appShowsUpcomingEventsForCurrentUserOnly` into `EventControllerTest`.
3. `actuatorHealthIsPublic` and `persistentLoginsTableExists` are infrastructure smoke tests — either keep in `AppApplicationTests` (extending the "contextLoads() only" rule slightly for infra smoke) or extract into a separate `InfrastructureSmokeTest`.
4. After extraction, `AppApplicationTests` should be `contextLoads()` only (or `contextLoads()` + infra smokes if you didn't extract).
5. Verify all extracted classes share identical config so Spring's context cache stays unfragmented; no new `@MockBean` / `@TestPropertySource` differences without justification.

**Do not do now**: this is a wide test-file refactor with no behavioral change — bundle it into a dedicated slice rather than mixing with feature work.
