<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: iCalendar Feed + Subscription (S-04)

- **Plan**: `context/changes/icalendar-feed-and-subscription/plan.md`
- **Scope**: All phases (0 / 1 / 2a / 2b / 3 / 4)
- **Date**: 2026-06-15
- **Verdict**: APPROVED
- **Findings**: 0 critical · 1 warning · 7 observations

Drift sweep: clean MATCH across all 19 implementation files. No missing items, no scope creep. The `IcalFeedWriter` VTIMEZONE block (emitted only when a timed event exists) is a justified addition — RFC 5545 §3.6.5 requires it whenever a DTSTART references a TZID.

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | PASS |
| Architecture | PASS |
| Pattern Consistency | WARNING |
| Success Criteria | PASS |

## Findings

### F1 — Fourth sibling integration-test class repeats the user-seed recipe

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Pattern Consistency (lessons.md "When lifting a test helper, sweep sibling `@BeforeEach` / setup blocks for duplication too")
- **Location**: `src/test/java/com/example/app/event/CalendarControllerTest.java:189`, `src/test/java/com/example/app/user/SettingsControllerTest.java:118` (siblings: `AppApplicationTests`, `EventControllerTest`)
- **Detail**: S-04 adds two new `@SpringBootTest` classes. Both seed users via `appUserRepository.save(new AppUser(email, passwordEncoder.encode("verylongpassword12")))` — the same five-token recipe already duplicated three classes deep. With `CalendarControllerTest` + `SettingsControllerTest`, the duplication is now in four sibling integration-test classes that share the `@TestPropertySource`, the password encoder injection, and the seed shape. The "lift on the second sibling, scan the whole fixture surface" rule (`lessons.md` §"When lifting a test helper, sweep sibling `@BeforeEach`/setup blocks for duplication too") applies here cleanly.
- **Fix A ⭐ Recommended**: Lift now in a follow-up
  - Approach: Add `IntegrationTestSupport` (or `UserTestFixtures`) with `saveUser(repo, encoder, email)` and `seedUserWithToken(repo, service, encoder, email)`; replace the four sites in one pass.
  - Strength: Matches the lesson exactly — a second `*Test` family is the trigger. Cheaper than waiting for a fifth. Mirrors the existing `LlmTestFixtures` lift.
  - Tradeoff: One small new test-only helper class; ~20-line edit across four files. No production code change.
  - Confidence: HIGH — the same shape already exists for the LLM family.
  - Blind spot: None significant.
- **Fix B**: Defer until the next sibling appears
  - Approach: Document the duplication in the change's follow-ups log; lift when a 5th `@SpringBootTest` class lands.
  - Strength: Zero edit cost today.
  - Tradeoff: Lesson explicitly anticipates this anti-pattern; every additional sibling adds another N-way round of the same fix.
  - Confidence: MEDIUM — defers a known lesson violation.
  - Blind spot: When a 5th sibling lands it'll feel "just like the rest" and the lift never happens.
- **Decision**: FIXED via Fix A — `UserTestFixtures` (`saveUser`, `seedUserWithToken`) added at `src/test/java/com/example/app/testsupport/UserTestFixtures.java`; literal-recipe sites swept across `AppApplicationTests`, `EventControllerTest`, `CalendarControllerTest`, `SettingsControllerTest`. Login-test sites that pass a parameterized password were intentionally left alone.

### F2 — Unknown-token 404 returns the Whitelabel error page, not "empty body"

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence (minor — security goal met)
- **Location**: `src/main/java/com/example/app/event/CalendarController.java:46`
- **Detail**: `throw new ResponseStatusException(HttpStatus.NOT_FOUND)` bubbles to Spring's `BasicErrorController` and renders the Whitelabel HTML error page. The plan ("Critical Implementation Details" + Phase 2b §1.1 + "Desired End State" criterion 2) specifies an empty body so unmatched-route 404 and unknown-token 404 are byte-identical. They are still observationally symmetric (both go through the same Whitelabel renderer), so the existence-leak guarantee holds — but the body is not "empty" as written. The test asserts only status.
- **Fix**: Either accept the divergence (Whitelabel + unmatched-route 404 are byte-identical, so existence-leak goal is met regardless) or return `ResponseEntity.notFound().build()` from the controller.
- **Decision**: FIXED — controller now returns `ResponseEntity.notFound().build()` on unknown token; `ResponseStatusException` import removed.

### F3 — `SettingsController.findByEmail(...).orElseThrow()` returns 500 on missing row

- **Severity**: 💡 OBSERVATION
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Reliability (sibling-pattern compliant; plan defers fix)
- **Location**: `src/main/java/com/example/app/user/SettingsController.java:23`
- **Detail**: Same `findByEmail(auth.getName()).orElseThrow()` shape as `AppController:43` and `EventController:41` — bare `NoSuchElementException` → 500 when the authenticated row vanishes mid-session. Plan explicitly defers ("auth-to-entity F2 trade-off, deferred to account-deletion slice") but the surface area now grows from two to three sites; the eventual fix should sweep all three at once.
- **Fix**: Accept as-is for this slice (matches existing controllers). When the deferred fix lands, sweep all three sites — a private `requireUser(Authentication)` helper or a `@ControllerAdvice @ExceptionHandler(NoSuchElementException.class)` that redirects to `/login` closes the whole family in one move.
- **Decision**: ACCEPTED — kept as-is for this slice; sweep reminder migrated from review into `change.md` → `## Follow-ups` so it survives review cleanup and is picked up when the account-deletion slice lands.

### F4 — `Vary: Accept` header is set but unasserted

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Test coverage
- **Location**: `src/main/java/com/example/app/event/CalendarController.java:55`, `src/test/java/com/example/app/event/CalendarControllerTest.java:60-68`
- **Detail**: `feedReturnsCorrectMimeAndCacheControl` asserts `Content-Type` and `Cache-Control` but not `Vary`. A future refactor that drops the `Vary` header will pass tests silently.
- **Fix**: Add `.andExpect(header().string("Vary", "Accept"))` to `feedReturnsCorrectMimeAndCacheControl`.
- **Decision**: FIXED — assertion appended to `feedReturnsCorrectMimeAndCacheControl`.

### F5 — `IcalFeedWriter` rebuilds the `TimeZoneRegistry` per request for timed events

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Performance (negligible at MVP scale, noted for fairness)
- **Location**: `src/main/java/com/example/app/event/IcalFeedWriter.java:65`
- **Detail**: `TimeZoneRegistryFactory.getInstance().createRegistry()` runs on every render with at least one timed event. Sub-millisecond at MVP scale, but redundant work per request.
- **Fix**: Hoist to a `private final TimeZoneRegistry registry = TimeZoneRegistryFactory.getInstance().createRegistry();` field on the writer.
- **Decision**: FIXED — `timeZoneRegistry` is now a `private final` field constructed once at instantiation; the `if (timed event)` branch reuses it.

### F6 — `IcalSubscriptionService` calls `save()` on a managed entity

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Style
- **Location**: `src/main/java/com/example/app/user/IcalSubscriptionService.java:27`
- **Detail**: Inside `@Transactional`, `locked` is a managed JPA entity. Hibernate's dirty checking would UPDATE on flush without the explicit `appUserRepository.save(locked)`. The call is harmless but non-idiomatic.
- **Fix**: Either remove the `save()` call (let dirty checking flush) or keep it and add a comment for readers who don't expect implicit flush.
- **Decision**: FIXED — redundant `appUserRepository.save(locked)` removed; dirty checking flushes the token on commit.

### F7 — Settings page inline `onclick` is CSP-incompatible (forward-compat only)

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Security (forward-compat)
- **Location**: `src/main/resources/templates/settings.html:13-14`
- **Detail**: Inline `onclick="navigator.clipboard.writeText(...)"` works today — no XSS surface, since `feedUrl` is `th:value`-encoded and the token charset is `[A-Za-z0-9_-]{32}`. The concern is forward-compat: a future `Content-Security-Policy: script-src 'self'` will silently break the Copy button. The plan acknowledges this explicitly.
- **Fix**: Accept as-is per plan. When CSP lands, move the handler to an inline `<script>` block or `src/main/resources/static/js/`.
- **Decision**: ACCEPTED — plan-aligned; CSP migration will revisit.

### F8 — "AndNewlines" test name unfulfilled by fixture

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Test quality
- **Location**: `src/test/java/com/example/app/event/IcalFeedWriterTest.java:148`
- **Detail**: `summaryAndDescriptionHandlePolishDiacriticsAndNewlines` asserts diacritics round-trip but has no `\n` in title or requirements. The newline path is exercised by `descriptionJoinsRequirementsAndNotesWithBlankLine` (the `\n\n` joiner) but not by in-field newlines that ical4j must escape.
- **Fix**: Either drop "AndNewlines" from the test name or add a `\n` to `requirements` (e.g. `"ą,ć,ę;\nł\\ś"`) and assert the decoded value preserves it.
- **Decision**: FIXED — `requirements` fixture now contains an embedded `\n`; the existing decoded-value assertion exercises ical4j's wire-escape/decode round-trip.
