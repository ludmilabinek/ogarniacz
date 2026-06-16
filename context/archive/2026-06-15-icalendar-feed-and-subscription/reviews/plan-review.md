<!-- PLAN-REVIEW-REPORT -->
# Plan Review: iCalendar Feed + Subscription (S-04)

- **Plan**: `context/changes/icalendar-feed-and-subscription/plan.md`
- **Mode**: Deep
- **Date**: 2026-06-15
- **Verdict**: REVISE
- **Findings**: 2 critical, 4 warnings, 1 observation

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| End-State Alignment | PASS |
| Lean Execution | PASS |
| Architectural Fitness | WARNING |
| Blind Spots | WARNING |
| Plan Completeness | FAIL |

## Grounding

7/7 paths ✓ (`Event.java`, `AppUser.java`, `AppUserRepository.java`, `SecurityConfig.java`, `EventReminder.java`, `layout.html`, `application.properties`), 5/5 symbols ✓ (`Event` shape, `AppUser` shape, `EventReminder.reminderFor`, `AppEventProperties` config record, existing `AppApplication.clock()` bean), brief↔plan ✓.

## Findings

### F1 — CalendarControllerTest #9 and #10 expect 404 where Spring Security yields 302

- **Severity**: ❌ CRITICAL
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Plan Completeness
- **Location**: Phase 2b — CalendarControllerTest tests 9 and 10 (`plan.md:338-340`)
- **Detail**: Tests `tokenWithWrongFileExtensionReturns404` and `tokenInPathOnlyAcceptsTokenCharset` issue ANONYMOUS GETs to `/calendar/<token>.txt` and `/calendar/foo/bar.ics`. Both paths fall outside the `.requestMatchers(GET, "/calendar/*.ics").permitAll()` carve-out and hit `.anyRequest().authenticated()` — Spring Security's `formLogin()` filter then redirects to `/login` with **302 status**. The controller never runs, so there is no 404. Confirmed via Spring Security 6.5 reference docs: in a single chain with no `securityMatcher`, the chain applies to all requests; unmatched + unauthenticated → form-login redirect. Existing `EventControllerTest.anonymousGetEventsNewRedirectsToLogin` (line 46-50) already pins this exact behavior.
- **Fix A ⭐ Recommended**: Change the assertion to `is3xxRedirection()` + `redirectedUrlPattern("/login*")`
  - Strength: Matches reality of the security configuration; mirrors the existing `anonymousGetEventsNewRedirectsToLogin` pattern (lessons "per-controller SpringBootTest layout"). The test still pins that the path doesn't reach the controller anonymously — just for the right reason.
  - Tradeoff: Loses the "path-traversal posture" framing; now reads as "non-`.ics` paths are not part of the feed carve-out", which is the actual property.
  - Confidence: HIGH — directly verified via Spring Security 6.5 reference.
  - Blind spot: None significant.
- **Fix B**: Authenticate the request, then assert 404
  - Strength: Tests the controller-level routing precision (path must literally end in `.ics`, path variable must be single-segment) independently of the security layer.
  - Tradeoff: Adds `with(user(...))` and a seeded `AppUser` to two tests that conceptually target the anonymous endpoint; muddies the test's premise.
  - Confidence: HIGH.
  - Blind spot: Requires a seeded user with a valid login email — extra fixture cost.
- **Decision**: FIXED via Fix A

### F2 — Phase 2a and Phase 2b Progress titles don't match plan-body headers

- **Severity**: ❌ CRITICAL
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: `plan.md:191, 286` (body) vs `plan.md:571, 584` (Progress)
- **Detail**: Body line 191: `## Phase 2a: IcalFeedWriter — RFC 5545 serialization (pure value transformer)` vs Progress line 571: `### Phase 2a: IcalFeedWriter — RFC 5545 serialization`. Body line 286: `## Phase 2b: CalendarController + security carve-out (integration)` vs Progress line 584: `### Phase 2b: CalendarController + security carve-out`. Per `references/progress-format.md` and the plan-review skill: "Each `## Phase N: <name>` in the plan body has a matching `### Phase N: <name>` in Progress." The skill marks any Progress↔Phase title mismatch as a CRITICAL finding.
- **Fix**: Drop the parenthetical suffixes from the body headers (preferred — Progress titles then match exactly) OR add the suffixes to the Progress headers. Mechanical edit.
- **Decision**: FIXED — dropped parentheticals from body headers

### F3 — Lazy-mint race not mitigated by claimed mechanism

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Blind Spots
- **Location**: `plan.md:157-158` (IcalSubscriptionService contract) + `plan-brief.md:93`
- **Detail**: Plan claims `@Transactional` + UNIQUE constraint protects `getOrCreateToken`: "a concurrent second call sees either a coherent saved row or contends on the unique constraint at most once." This is incorrect. The UNIQUE constraint catches **cross-user token collisions** (different user rows with the same token), not **lost-update on the same user's row**. Under default PostgreSQL READ_COMMITTED isolation, two concurrent `getOrCreateToken(sameUser)` calls can both read `icalToken == null`, both mint, both `UPDATE app_user SET ical_token = ? WHERE id = sameUserId`. The second commit silently overwrites the first. Both transactions succeed; the UNIQUE constraint never fires because the two new tokens differ. Whichever token the user copy-pasted from the first response now points to nothing (or to a stale `findByIcalToken` result if a feed poll caught the earlier value). Single-user MVP makes this unlikely (one parent, sequential browser nav), but the plan's rationale is misleading and the brief's Phase 3 "Key risk" row claims a mitigation that isn't real.
- **Fix A ⭐ Recommended**: Read the AppUser with PESSIMISTIC_WRITE inside the transaction
  - Strength: `appUserRepository.findById(user.getId(), LockModeType.PESSIMISTIC_WRITE)` → check token → mint → save. Row-level lock serializes the second call behind the first; second call observes the persisted token and returns it. Tiny change, MVP-appropriate.
  - Tradeoff: Slight lock contention on first-visit only (one row, one user, microseconds).
  - Confidence: HIGH — standard JPA pattern for read-modify-write under concurrency.
  - Blind spot: None significant — only affects the first GET.
- **Fix B**: Accept the race, fix the rationale
  - Strength: No code change; align prose with reality ("the race is unmitigated; MVP single-user context makes it acceptable until the rotation slice").
  - Tradeoff: Adds a debt note that resurfaces during S-05 or account-deletion work.
  - Confidence: HIGH.
  - Blind spot: Token-rotation slice may need to revisit this anyway.
- **Decision**: FIXED via Fix A (PESSIMISTIC_WRITE + findAndLockById on AppUserRepository; brief risk row corrected)

### F4 — `app.base-url` duplicates info already available from forward-headers config

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Architectural Fitness
- **Location**: Phase 3 §2 — `app.base-url` property (`plan.md:380-385`)
- **Detail**: `application.properties` already sets `server.forward-headers-strategy=framework` (line 22), which makes Spring's `ServletUriComponentsBuilder.fromCurrentContextPath()` honor Fly's `X-Forwarded-Proto`/`X-Forwarded-Host` and emit `https://ogarniacz.fly.dev` for production and `http://localhost:8080` for local — exactly what the plan needs, automatically, with no env-var wiring. Introducing `app.base-url` adds a parallel source of truth that must stay in sync with the actual request origin, and the plan-brief itself flags the misconfig risk ("would render `http://...` URLs in the UI").
- **Fix**: Build the URL with `ServletUriComponentsBuilder.fromCurrentContextPath().path("/calendar/").path(token).path(".ics").build().toUriString()`. Drop the `app.base-url` property, the `APP_BASE_URL` env var, and the AppProperties extension. SettingsController already has access to the current request via `RequestContextHolder`/method args.
  - Strength: One source of truth, automatic dev/prod correctness, fewer moving parts in Phase 3, no Fly env-var to remember on first deploy. Composes with the forward-headers strategy already wired by the bootstrap.
  - Tradeoff: URL is built per-request rather than once at app startup (negligible).
  - Confidence: HIGH — `forward-headers-strategy=framework` is the canonical Spring Boot recipe for this exact problem behind a reverse proxy.
  - Blind spot: If the settings page is ever rendered outside a request context (e.g. an async email job), the property approach would still be needed. No such call site exists today.
- **Decision**: FIXED — SettingsController uses ServletUriComponentsBuilder; app.base-url property + AppProperties extension dropped; brief risk row reframed

### F5 — Clock guidance is contradictory; existing bean is wrong for the use case

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Architectural Fitness
- **Location**: Phase 2b §1 step 2 (`plan.md:302`)
- **Detail**: Plan says: "Calls `eventRepository.findUpcomingByUser(user, LocalDate.now(clock))` — clock is `Clock.system(properties.timezone())` to keep 'today' timezone-aware. (Inject `Clock` via constructor for testability; default to `Clock.systemDefaultZone()` in production via a `@Bean` if not already wired.)" The two sentences contradict each other: first wants `Clock.system(Europe/Warsaw)` so "today" follows Warsaw midnight; second falls back to `Clock.systemDefaultZone()`, which on Fly's UTC JVM means "today" flips at UTC midnight — between 01:00 and 02:00 Warsaw, depending on DST. The existing `AppApplication.clock()` bean (line 20-23) is already wired as `Clock.systemDefaultZone()`. Naive autowiring picks up the wrong-zone bean. The window of user impact is narrow (late-night refresh of /settings, or feed poll between 01:00–02:00 Warsaw on an event-day boundary) but real.
- **Fix**: In `CalendarController`, derive `LocalDate.now(properties.timezone())` directly — no injected `Clock`. For testability, pass a `Clock` via a constructor parameter with a test override; production uses `Clock.system(properties.timezone())` via a dedicated bean OR via inline construction. Pick one and state it once.
  - Strength: Two-line decision, makes "today" deterministically Warsaw across server/test; no risk of accidentally inheriting the systemDefaultZone bean.
  - Tradeoff: Either adds a second @Bean (clutters AppApplication) or skips the @Bean and hardcodes ZoneId at the call site (slightly less testable).
  - Confidence: HIGH.
  - Blind spot: Existing Clock bean may be used elsewhere — quick grep before changing.
- **Decision**: FIXED via Variant C — fix the global Clock bean to `Clock.system(properties.timezone())` once; CalendarController consumes the (now zone-aware) bean without per-call ceremony. Also closes the parallel latent bug in AppController. Verified: AppController and OpenRouterLlmVisionClient consumers both prefer Warsaw "today"; LLM client test uses Clock.fixed and is unaffected. New Phase 2b §1 added; CalendarController step in §2 now references the bean directly.

### F6 — Upcoming-only rationale misstates standard subscribed-feed client behavior

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Blind Spots
- **Location**: `plan-brief.md:29` ("Feed event scope" row)
- **Detail**: Brief says: "Upcoming-only (reuse `findUpcomingByUser`) … clients cache past events locally on first poll so absence from later polls is harmless." For standards-compliant subscribed feeds (Apple Calendar, Outlook, Thunderbird, Google Calendar) the feed is the **source of truth** — events present yesterday but absent today are deleted from the local calendar. Parents who look at last week's "what to bring" in their iPhone calendar will find it gone the day after. The user-facing consequence may still be acceptable (it matches the in-app personal view, which already hides past events via `appHidesPastEventsForCurrentUser`). But the rationale given to the reader is wrong, which leaves a later contributor unsure whether to "fix the bug" (clients should cache! why aren't they?) or leave it.
- **Fix**: Replace the rationale with the actual property — the feed scope intentionally mirrors the in-app personal view ("past events disappear from both surfaces in lockstep"). If the decision is acceptable, add it to `What We're NOT Doing` so the next reader sees the choice was deliberate. Add a manual verification step in Phase 2b confirming the expected behavior (e.g. seed an event with `eventDate = today`, poll feed at T, advance clock past midnight, poll again, assert event is absent and that this is acceptable).
  - Strength: Plan now matches reality; future contributors don't try to "fix" an intentional design.
  - Tradeoff: Slightly longer brief; one extra manual verification step.
  - Confidence: HIGH — verified against the iCal subscription semantics in RFC 5545 and the documented behavior of Apple/Outlook/Thunderbird.
  - Blind spot: Parents may want to see what happened last week — separate UX question, out of S-04 scope.
- **Decision**: FIXED — brief rationale corrected; plan `What We're NOT Doing` adds an explicit "for now we don't preserve event history in the feed" bullet (framed as an open product question, not a permanent decision); Phase 2b adds manual verification step 2b.9 confirming past-event exclusion in real client behavior.

### F7 — Phase 2a test #5 (`valarmTriggerMatchesEventReminderOnRegularDay`) is a mirror test

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: `plan.md:251` (Phase 2a writer test #5)
- **Detail**: Assertion is `parsed VALARM trigger == EventReminder.reminderFor(event).toInstant()`. The writer is specified to delegate trigger computation to exactly that method — so the test passes by construction. It also matches the m3l2 lesson's "Mirror implementation" anti-pattern: "Assertion computes the expected value with the same logic as the tested code." The DST tests #6 and #7 hold the actual oracle (hard-coded UTC instants).
- **Fix**: Drop test #5, or replace its expected value with a hard-coded Instant for the regular (non-DST) day so it pulls weight independent of `EventReminder`.
- **Decision**: FIXED — replaced expected value with hard-coded `Instant.parse("2026-06-14T06:00:00Z")`; test now derives its oracle from the PRD rule, not the implementation. Renamed to `valarmTriggerOnRegularDayIsMorningOfDayBeforeAt08Warsaw` to reflect the behavior tested.
