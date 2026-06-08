<!-- PLAN-REVIEW-REPORT -->
# Plan Review: Manual Event Entry Implementation Plan

- **Plan**: `context/changes/manual-event-entry/plan.md`
- **Mode**: Deep
- **Date**: 2026-06-08
- **Verdict**: REVISE
- **Findings**: 2 critical, 2 warnings, 0 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| End-State Alignment | PASS |
| Lean Execution | PASS |
| Architectural Fitness | PASS |
| Blind Spots | FAIL |
| Plan Completeness | WARNING |

## Grounding

7/7 paths ✓ (`AppController.java`, `AppUser.java`, `SignupController.java`, `SignupForm.java`, `AppApplicationTests.java`, `app.html`, `signup.html`, `application.properties`, `fragments/layout.html`), 6/6 symbols ✓ (`findByEmail`, `Authentication.getName`, `userEmail` injected in topbar fragment, `persistentLoginsTableExists` pattern, `@SpringBootTest @AutoConfigureMockMvc @TestPropertySource` stack, `ddl-auto=update`), brief↔plan ✓.

No `docs/reference/contract-surfaces.md` present — opt-in convention; skipped silently.

## Findings

### F1 — Phase 3 will break existing S-01 test `getAppAuthenticatedShowsEmail`

- **Severity**: ❌ CRITICAL
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Blind Spots
- **Location**: Phase 3 — change #4 (`AppController#app` fetches events)
- **Detail**: Phase 3 swaps `AppController#app` from a bare `model.addAttribute("userEmail", auth.getName())` to a DB lookup: `appUserRepository.findByEmail(auth.getName()).orElseThrow()`. The plan justifies `orElseThrow()` with "Spring Security guarantees the user exists if Authentication is non-anonymous" — but this is false in tests using `.with(user(...))`, which mints a fake principal without seeding the DB. `AppApplicationTests#getAppAuthenticatedShowsEmail` (`AppApplicationTests.java:187-192`) does exactly that: `mvc.perform(get("/app").with(user("showmail@example.com")))` with no prior seed. After Phase 3, `findByEmail("showmail@example.com")` returns empty, `orElseThrow()` raises `NoSuchElementException`, the request 500s, and Phase 3 success criterion 3.2 (full suite green) fails. I traced every other `/app` path — `appShowsOwnEmailOnlyNotOtherUsersEmail` seeds alice + bob (✓); `rememberMeCookieReAuthenticatesAfterSessionEnds` seeds the user (✓); `anonymousGetAppRedirectsToLogin`, `logoutInvalidatesSessionAndRedirects`, `rootRedirectsAuthenticatedToApp` never reach `AppController#app` (✓). Only `getAppAuthenticatedShowsEmail` breaks.
- **Fix**: Add Phase 3 change #8 — "seed user in existing `getAppAuthenticatedShowsEmail`": insert `appUserRepository.save(new AppUser("showmail@example.com", passwordEncoder.encode("verylongpassword12")))` before the GET, mirroring `appShowsOwnEmailOnlyNotOtherUsersEmail` and `rememberMeCookieReAuthenticatesAfterSessionEnds`. Mention this in success criterion 3.2 so the implementer doesn't claim suite-green without touching it.
- **Decision**: FIXED

### F2 — DST fall-back assertion in `EventReminderTest` is wrong (+02:00 vs +01:00)

- **Severity**: ❌ CRITICAL
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Blind Spots
- **Location**: Phase 3 — change #3, test `reminderAcrossFallBackStaysAtZoneEight`
- **Detail**: Plan specifies: "event 2026-10-26 (day after fall-back Sunday 2026-10-25) → reminder `2026-10-25T08:00+02:00` (i.e. on the transition day, in CEST before the fall back at 03:00)". The reasoning is inverted. In Europe/Warsaw the fall-back HAPPENS at 03:00 CEST → 02:00 CET, meaning 08:00 local time is *after* the transition. Verified in a JDK java.time check: `LocalDateTime.of(2026,10,25,8,0).atZone(ZoneId.of("Europe/Warsaw"))` resolves to `2026-10-25T08:00+01:00[Europe/Warsaw]`, offset `+01:00`. If the implementer copies the plan's expected `+02:00` into the test, it FAILS with offset mismatch. The likely "fix" path is to weaken the assertion ("ignore offset, just check wall-clock"), which silently drops the DST safety net S-04 needs the helper to guarantee. The other transition test (`reminderAcrossSpringForwardStaysAtZoneEight`, Mar 29 → `+02:00`) is correct — verified the same way.
- **Fix**: Change the plan's expected value for `reminderAcrossFallBackStaysAtZoneEight` to `2026-10-25T08:00+01:00` and rewrite the parenthetical to "i.e. on the transition day, in CET because the fall-back at 03:00 has already happened". Keep the offset assertion in the test — the whole point of this test is to catch a UTC-instead-of-zone mistake, which only shows up as an offset diff.
- **Decision**: FIXED

### F3 — Phase 1 test method count is off-by-one / under-enumerated

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 1 — change #3 (`EventRepositoryTest`) + Success Criterion 1.1 + Progress 1.1
- **Detail**: The Contract description in Phase 1 §3 says "Prove three things at the data layer: (a) table exists, (b) `findUpcomingByUser` returns only the requested user's events, (c) ordering puts date-asc, then time-asc, with null times last". That's three assertions. But Success Criterion 1.1 and Progress 1.1 both say "passes (4 test methods)". The Assert: block then implicitly mixes user-partition with past-event exclusion. The implementer reading the progress checkbox literally will either write 3 methods (4-count claim wrong) or invent a 4th to match (drift).
- **Fix**: Replace the Contract description in Phase 1 §3 with an explicit enumeration of four test methods: `appEventTableExists` (DatabaseMetaData.getTables for APP_EVENT, mirroring `persistentLoginsTableExists`), `findUpcomingByUserExcludesOtherUsersEvents` (alice's call does NOT include bob's event), `findUpcomingByUserExcludesPastEvents` (alice's yesterday-event not returned), `findUpcomingByUserOrdersByDateAscThenTimeAscNullsLast` (three alice events return in expected order). Then Progress 1.1's "4 test methods" stays factually correct.
- **Decision**: FIXED

### F4 — `eventRepository.count() == 1` is fragile under shared `@SpringBootTest` context

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Blind Spots
- **Location**: Phase 2 — change #5 (`EventControllerTest`), test `postEventHappyPathRedirectsToApp`
- **Detail**: Plan specifies: "POST with all fields filled + `.with(csrf())` → 302 to `/app`; assert `eventRepository.count() == 1` and the row's `user.email == "alice@example.com"`". But `EventControllerTest` is annotated `@SpringBootTest` (mirroring `AppApplicationTests`), the H2 context is shared with no `@Transactional` or `@DirtiesContext`, and at least two other test methods in the same class also persist events: `postEventPastDateIsAccepted` (round-2 decision means past dates persist; the test asserts 302, so a row IS written) and the new `appHidesPastEventsForCurrentUser` (Phase 3 §7, seeds two events directly). JUnit5 makes no within-class ordering guarantees — if either of these runs before the happy path, `count() == 1` becomes `count() ≥ 2`. The test goes flaky and the implementer either disables it or adds an ad-hoc `deleteAll()`, neither of which actually catches the contract.
- **Fix**: Replace the count-based assertion with a targeted query, e.g. assert `eventRepository.findUpcomingByUser(alice, LocalDate.now())` contains an event with the expected title — the same JPQL finder Phase 1 ships, so no new query needed. Drop the `count() == 1` assertion entirely; it's not load-bearing for the contract (which is "happy path persists a row for THIS user with THESE fields").
- **Decision**: FIXED
