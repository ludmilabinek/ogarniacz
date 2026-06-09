<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Manual Event Entry (S-02)

- **Plan**: context/changes/manual-event-entry/plan.md
- **Scope**: All 3 phases (Phase 1 + Phase 2 + Phase 3)
- **Date**: 2026-06-08
- **Verdict**: NEEDS ATTENTION
- **Findings**: 0 critical · 3 warnings · 2 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | WARNING |
| Scope Discipline | PASS |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | WARNING |
| Success Criteria | PASS |

## Success Criteria Evidence

- `./gradlew test --rerun-tasks` → BUILD SUCCESSFUL (all phases' test classes executed; no failures).
- `./gradlew build` → BUILD SUCCESSFUL.
- All Progress checkboxes (Phase 1 1.1–1.5, Phase 2 2.1–2.6, Phase 3 3.1–3.9) are `[x]` with commit SHAs.

## Scope Evidence

- Plan files vs. diff: every planned file is in the diff. Only "extra" source change is `AppApplication.java`, which the plan explicitly requested (`@EnableConfigurationProperties(AppEventProperties.class)`).
- No unplanned controllers, entities, repositories, or templates.

## Findings

### F1 — Nested record `Event` in `AppEventProperties` shadows the JPA entity

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Pattern Consistency
- **Location**: src/main/java/com/example/app/event/AppEventProperties.java:8,15,19
- **Detail**: The properties record declares `public record Event(Reminder reminder)` inside `com.example.app.event`, the same package as the JPA `@Entity` `com.example.app.event.Event`. Inside this file the simple name `Event` resolves to the nested record (`new Event(new Reminder(8))` at line 15). Any future code in this file that needs the entity must use a fully qualified name, and IDE auto-import on a fresh hand will pick the wrong `Event`. Related to F4 (the plan's flat sketch would have avoided the shadow).
- **Fix**: Rename the nested record to a domain-distinct name (e.g. `EventSettings` or `Defaults`) so callers read `properties.eventSettings().reminder().hour()` and there is no shadowing in this package.
  - Strength: Removes the entity-vs-properties name clash inside the package without changing the property keys.
  - Tradeoff: Touches `EventReminder` and any test that constructs the properties — small, localized diff.
  - Confidence: HIGH — purely a rename within a tiny module.
  - Blind spot: None significant.
- **Decision**: FIXED — renamed nested record `Event` → `EventSettings`, kept field name `event` so YAML key `app.event.reminder.hour` is unchanged; updated `EventReminderTest` constructor.

### F2 — `orElseThrow()` at the auth-to-entity boundary returns 500 if the user row vanishes

- **Severity**: ⚠️ WARNING
- **Impact**: 🔬 HIGH — architectural stakes; think carefully before deciding
- **Dimension**: Safety & Quality (Reliability)
- **Location**: src/main/java/com/example/app/event/EventController.java:41 · src/main/java/com/example/app/web/AppController.java:40
- **Detail**: Both controllers call `appUserRepository.findByEmail(auth.getName()).orElseThrow()` inside authenticated requests. Today the row exists by construction. Once an account-deletion path lands (S-03 or later), a still-valid session/remember-me cookie will hit `NoSuchElementException` → HTTP 500 instead of forcing re-auth. The plan acknowledges no admin/delete-user surface ships in this slice, so this is forward-looking, not present-day broken.
- **Fix A ⭐ Recommended**: Defer — record as a known reliability risk
  - Strength: No delete-account path exists yet. Spending complexity budget now on an `@ExceptionHandler` + typed exception is premature for this slice; S-03 (edit/delete) is the right place to introduce the handler alongside the deletion path that creates the race.
  - Tradeoff: Risk drifts off the radar if not captured. Mitigate via `Record as lesson` or a follow-up note.
  - Confidence: HIGH — matches the plan's "What we're NOT doing" posture for non-MVP scope.
  - Blind spot: None significant.
- **Fix B**: Introduce a typed exception + `@ExceptionHandler` that 401s
  - Strength: Closes the window now; future-proofs against any path that deletes users.
  - Tradeoff: Adds scope this slice didn't plan for. Touches two controllers + new exception type + handler config.
  - Confidence: MEDIUM — straightforward, but unverified that other callers won't need similar treatment.
  - Blind spot: Haven't traced every controller that resolves an `AppUser` from `Authentication`.
- **Decision**: DEFERRED (Fix A) — queued in `follow-ups/review-fixes.md` to be picked up alongside the slice that adds an account-deletion path.

### F3 — Asymmetric `.trim()` applied only to title, not requirements/notes

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality (Data hygiene)
- **Location**: src/main/java/com/example/app/event/EventController.java:46
- **Detail**: `form.getTitle().trim()` is called when constructing the Event, but `requirements` and `notes` are persisted verbatim. A user pasting "  Permission slip  " into requirements stores leading/trailing whitespace, which then renders odd in `app.html`. Minor inconsistency in input normalization across fields.
- **Fix**: Trim all three text fields at construction (or drop the title trim and rely on `@NotBlank` alone) so input normalization is consistent across the form.
- **Decision**: FIXED — added `trimOrNull` helper in `EventController` and applied it to `requirements` and `notes` (title still uses `.trim()` directly because `@NotBlank` guarantees non-null).

### F4 — Plan vs. implementation: `AppEventProperties` structural sketch

- **Severity**: 🔍 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: context/changes/manual-event-entry/plan.md §Phase 3.1 · src/main/java/com/example/app/event/AppEventProperties.java
- **Detail**: The plan sketches `properties.reminder().hour()` (flat) but the property key shipped in Phase 2 is `app.event.reminder.hour=8`. To bind that key you need a nested `event.reminder.hour` structure — which is what the code does. The plan's sketch was internally contradictory; the implementation correctly resolves the contradiction by matching the property keys, not the prose. No real intent drift — but the plan is now slightly out of sync with the code.
- **Fix**: Add a one-line addendum to plan.md §Phase 3.1 noting that the actual shape is `properties.event().reminder().hour()` to match the property keys established in Phase 2.
- **Decision**: FIXED — appended an addendum block under §Phase 3.1 §1 documenting the nested `EventSettings event` shape, the `properties.event().reminder().hour()` accessor chain, and the rename rationale (avoiding the JPA `Event` shadow flagged in F1).

### F5 — `EventControllerTest` uses `@SpringBootTest` while sibling controllers were tested inside `AppApplicationTests`

- **Severity**: 🔍 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/test/java/com/example/app/event/EventControllerTest.java:28
- **Detail**: The sibling pattern in S-01 was to fold controller assertions into `AppApplicationTests` rather than spin up a dedicated `@SpringBootTest` class. The split is defensible (8 controller tests warrant their own class) and Boot's context cache absorbs most of the cost (same `@TestPropertySource` as `AppApplicationTests`). Calling it out so future slices choose deliberately.
- **Fix**: Accept as-is. If you want strict alignment, consider folding these into `AppApplicationTests` in a follow-up.
- **Decision**: ACCEPTED-AS-RULE (Per-controller `@SpringBootTest` class is the test layout standard) — rule recorded in `context/foundation/lessons.md`. The recorded rule **inverts** the finding's original framing: `EventControllerTest`'s split is now the standard; `AppApplicationTests` is the violator. Remediation deferred to a dedicated test-refactor slice; details queued in `follow-ups/review-fixes.md`.

## Lessons-as-Priors Check

- **"Verify `./gradlew test` post-bootstrap; if `DataSourceBeanCreationException` → add H2 test-runtime before first commit"** — HONORED. `build.gradle` declares `testRuntimeOnly 'com.h2database:h2'`; `@SpringBootTest` and `@DataJpaTest` boot without prod credentials.
- Other recorded lessons (starter-registry / Fly.io / PRD-mandated capabilities) do not apply to this change.

## Notes

- No CRITICAL findings. Strong overall hygiene: user-scoped query (no IDOR), CSRF enforced and tested, Bean Validation size caps matching DB columns, LAZY `@ManyToOne` never dereferenced in templates (no N+1), Thymeleaf `th:text` escapes user content (no XSS), no raw SQL (no injection), no secrets in source.
- Pattern compliance with the `user` package is strong (Objects.requireNonNull constructors, protected no-arg ctor, `@PrePersist`, getter-only style).
