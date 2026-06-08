# Manual Event Entry — Implementation Plan

## Overview

Ship roadmap slice **S-02**: a logged-in parent can manually create an event (date, optional time, title, requirements, notes) and see it on their personal view at `/app`, ordered upcoming-first and scoped to their own account. The slice introduces the `Event` JPA entity and the `EventReminder` helper that S-04 (iCalendar feed) and S-05 (image extraction) will both reuse. The entity shape is locked against PRD FR-004 exactly — no speculative `status` or `source` columns; S-05 will add what it needs additively per `deploy-plan.md` §5.2.

## Current State Analysis

S-01 has landed. The codebase ships:

- **Auth + per-user scope**: `AppUser` JPA entity (`app_user` table, UUID PK, email + bcrypt hash), Spring Security 7.0.5 form-login with persistent remember-me. `Authentication` is injected directly into controller methods (`AppController.app` reads `auth.getName()` to look up the user's email).
- **Personal view shell**: `AppController#app` (`src/main/java/com/example/app/web/AppController.java:25`) renders `app.html`, which currently shows just `"No events yet. Manual entry coming in the next slice."`.
- **Form pattern**: `SignupController` + `SignupForm` + `signup.html` establish the canonical shape — `@Valid @ModelAttribute` form, `BindingResult` for inline errors, Thymeleaf `th:object` + `th:field` + `*{errors}`, POST/Redirect/GET on success.
- **Persistence**: `ddl-auto=update` (additive-only migrations per `deploy-plan.md` §5), Postgres in prod, H2 for tests, `application.properties` is the canonical config file (not `application.yml`).
- **Test pattern**: `@SpringBootTest` + `@AutoConfigureMockMvc` + `@TestPropertySource(properties = "REMEMBER_ME_KEY=test-key-not-for-production")` in `AppApplicationTests.java` (260 lines, includes the S-01 two-user partition test `appShowsOwnEmailOnlyNotOtherUsersEmail`).

What's missing for S-02:

- **No `Event` entity, repository, controller, form, or template.**
- **No `EventReminder` helper or `app.timezone` config.**
- **`AppController#app` does not fetch events** — the personal view is hard-coded empty.

## Desired End State

A logged-in parent navigates to `/app`, clicks "Add event", fills in date + title (plus optional time, requirements, notes), submits, and is redirected back to `/app` with the new event visible in the upcoming-events list (date asc, time asc nulls-last). Past events are hidden. Each event's reminder time (`event_date.minusDays(1)` at 08:00 `Europe/Warsaw`) is computable via `EventReminder.reminderFor(event)` — exercised by unit tests covering DST transitions; S-04 will consume the same helper for the iCalendar `VALARM` block.

The `Event` entity is the locked shape from PRD FR-004: `id, user_id (FK), event_date (LocalDate, NOT NULL), event_time (LocalTime, NULL), title (NOT NULL, ≤200), requirements (≤2000), notes (≤2000), created_at`. Spring Data JPA owns schema via `ddl-auto=update`; deploy adds the table additively against the live Neon DB.

A negative integration test asserts user A's `/app` never contains user B's event title — locking the partition contract for this entity, same shape as S-01's `appShowsOwnEmailOnlyNotOtherUsersEmail` test.

### Key Discoveries:

- **`app_event` table name** (not `event`) — mirrors the `app_user` convention from S-01 and sidesteps any reserved-word concerns across SQL dialects.
- **Spring Data derived queries cannot express `ORDER BY event_time ASC NULLS LAST`** — Hibernate JPA-derived method names don't support null-handling. The upcoming-events finder must use a `@Query` JPQL annotation with explicit `NULLS LAST` (works on Postgres + H2).
- **`Europe/Warsaw` has DST transitions** in March and October — `EventReminder` must operate in the configured zone, not UTC, so "morning of day before at 08:00" is wall-clock-correct on both sides of the transition.
- **`signup.html`'s `th:action="@{/signup}"` triggers Spring's `CsrfRequestDataValueProcessor` to auto-inject the CSRF hidden input**; the same pattern carries to `events/new.html`. A raw `action="/events"` would skip injection and return 403 on POST.
- **`AppController` accepts `Authentication auth` directly** as a method parameter — the new event fetch uses the same injection, looking up the `AppUser` by `auth.getName()` (which is the email).

## What We're NOT Doing

- **No edit or delete UI for events.** Per roadmap, S-03 ships FR-010 (edit) + FR-011 (delete). The form's `EventForm` DTO is designed to be reusable by S-03's edit page, but no edit route lands here.
- **No `status`, `source`, or `source_image_id` columns** on `Event`. S-05 (image extraction) will add what it needs additively. The roadmap risk note ("lock the shape against PRD FR-004's schema") is the explicit instruction.
- **No iCalendar serialization.** S-04 owns the `/calendar/<token>.ics` route. `EventReminder` ships here so the reminder semantic is locked and unit-tested, but no `VCALENDAR` / `VEVENT` / `VALARM` text is produced.
- **No per-event configurable reminder timing.** PRD §Non-Goals explicitly defer this to v2.
- **No past-event filtering toggle.** Past events are hidden; no "Show past events" link in this slice. S-03's lifecycle work can revisit.
- **No HTMX, no JS framework, no inline form on `/app`.** Server-rendered Thymeleaf POST/Redirect/GET, matching `SignupController`'s pattern exactly.
- **No clipboard-paste image input, no image upload of any kind.** That's S-05 (and a PRD §Non-Goal for MVP image-input modalities beyond file picker).
- **No new admin / role surface.** Every authenticated user is a parent; the existing `Authentication` shape covers this.

## Implementation Approach

Three tight phases mapped to the three layers that need new code:

1. **Phase 1 — Data layer**: `Event` entity (locked FR-004 shape) + `EventRepository` with a user-scoped, upcoming-only, JPQL-annotated finder + a JPA-slice test that proves the scoped query filters correctly and `ddl-auto=update` creates the table.
2. **Phase 2 — Manual entry form**: `EventForm` (Bean Validation), `EventController` (GET `/events/new`, POST `/events`), `events/new.html` (mirrors `signup.html`), and the `application.properties` additions for the project zone + reminder hour. Existing `app.html` empty-state stays in place; the form just persists rows.
3. **Phase 3 — Populated personal view + reminder helper + partition test**: `AppController#app` is rewired to fetch the user's upcoming events; `app.html` renders the list (falling back to the empty-state when zero); `EventReminder` helper + `AppEventProperties` config class land with DST/boundary unit tests; a cross-user partition integration test (mirroring S-01's `appShowsOwnEmailOnlyNotOtherUsersEmail`) locks the contract.

Each phase produces working, manually verifiable behaviour at its boundary — after Phase 1, the table exists and the repository works in tests; after Phase 2, a user can submit the form and land on `/app` (but `/app` still says "No events yet"); after Phase 3, the new event actually appears in the list.

## Critical Implementation Details

- **DST correctness of `EventReminder`**: the helper must compute `LocalDateTime` in `ZoneId.of("Europe/Warsaw")`, not in UTC or the JVM default. The unit test must exercise (a) a normal weekday, (b) the spring-forward Sunday in March (where the day before is 23 hours long), and (c) the fall-back Sunday in October (where the day before is 25 hours long) — for both, the reminder is still `event_date.minusDays(1) at 08:00` in the configured zone, which is what S-04 will emit as `DTSTART;TZID=Europe/Warsaw`.
- **`ORDER BY … NULLS LAST` on H2 + Postgres**: Spring Data derived queries cannot express `NULLS LAST`. Use a `@Query` JPQL annotation: `select e from Event e where e.user = :user and e.eventDate >= :today order by e.eventDate asc, e.eventTime asc nulls last`. Both Postgres and H2 accept the `nulls last` JPQL clause.
- **`Authentication.getName()` returns the email** (set by Spring Security's `UserDetails.username`, which `AppUserDetailsService` maps to `AppUser.email`). The new `AppController#app` fetch must look up the `AppUser` via `appUserRepository.findByEmail(auth.getName())` and pass `user` (or `user.getId()`) into the repository call.

## Phase 1: Event entity, repository, scoped finder

### Overview

Introduce the `Event` JPA entity with the locked FR-004 shape and the user-scoped upcoming-events JPQL finder. Verify `ddl-auto=update` creates the table on a fresh H2 boot, and that the finder filters by user and date correctly.

### Changes Required:

#### 1. `Event` JPA entity

**File**: `src/main/java/com/example/app/event/Event.java` (new)

**Intent**: The persistent shape for a parent's accepted event. Locked to PRD FR-004 — no `status`, no `source`, no `source_image_id`. S-05 will add columns additively against this same row when image extraction ships.

**Contract**: `@Entity @Table(name = "app_event", indexes = { @Index(name = "ix_app_event_user_date", columnList = "user_id,event_date") })`. Fields: `UUID id` (`@Id @GeneratedValue`), `AppUser user` (`@ManyToOne(fetch = LAZY) @JoinColumn(name = "user_id", nullable = false)`), `LocalDate eventDate` (`@Column(name = "event_date", nullable = false)`), `LocalTime eventTime` (`@Column(name = "event_time")`, nullable), `String title` (`@Column(nullable = false, length = 200)`), `String requirements` (`@Column(length = 2000)`, nullable), `String notes` (`@Column(length = 2000)`, nullable), `Instant createdAt` (`@Column(name = "created_at", nullable = false, updatable = false)`). `@PrePersist` stamps `createdAt = Instant.now()`. Mirrors `AppUser.java` for protected no-arg constructor + getters only (no setters; immutability follows the S-01 pattern, with the exception that S-03 will add setters when edit lands).

#### 2. `EventRepository`

**File**: `src/main/java/com/example/app/event/EventRepository.java` (new)

**Intent**: Persistence boundary for `Event`. Exposes one scoped, ordered, upcoming-only finder used by `AppController#app`; nothing else. The finder uses `@Query` because Spring Data derived methods can't express `NULLS LAST`.

**Contract**: `interface EventRepository extends JpaRepository<Event, UUID>`. Method:

```java
@Query("""
       select e from Event e
       where e.user = :user
         and e.eventDate >= :today
       order by e.eventDate asc, e.eventTime asc nulls last
       """)
List<Event> findUpcomingByUser(@Param("user") AppUser user, @Param("today") LocalDate today);
```

The `nulls last` clause is non-obvious enough that the snippet stays — every later reader will wonder why this isn't a derived method.

#### 3. JPA slice test for the scoped finder

**File**: `src/test/java/com/example/app/event/EventRepositoryTest.java` (new)

**Intent**: Prove four things at the data layer, one assertion per test method: (a) `ddl-auto=update` creates the `app_event` table on test boot, (b) `findUpcomingByUser` excludes other users' events, (c) `findUpcomingByUser` excludes past events, (d) ordering puts date-asc, then time-asc, with null times last on the same date.

**Contract**: `@DataJpaTest` + `@AutoConfigureTestDatabase(replace = NONE)` (the H2 testRuntimeOnly is already on the classpath, but we want JPA to use the same DataSource auto-config as the app — no manual `@ActiveProfiles`). Inject `EventRepository`, `AppUserRepository`, `TestEntityManager`. Four test methods:

- `appEventTableExists` — `DatabaseMetaData.getTables(null, null, "APP_EVENT", null)` returns a result row. Mirrors `AppApplicationTests#persistentLoginsTableExists`.
- `findUpcomingByUserExcludesOtherUsersEvents` — seed alice + bob, persist one event for each on a future date; assert alice's `findUpcomingByUser(alice, LocalDate.now())` does NOT contain bob's event.
- `findUpcomingByUserExcludesPastEvents` — seed alice, persist one event with `event_date = LocalDate.now().minusDays(1)`; assert `findUpcomingByUser(alice, LocalDate.now())` is empty.
- `findUpcomingByUserOrdersByDateAscThenTimeAscNullsLast` — seed alice, persist three events (today 09:00, today 14:00, tomorrow null-time); assert the returned list is exactly `[today 09:00, today 14:00, tomorrow null-time]` (date asc, then time asc with null last on the same date).

### Success Criteria:

#### Automated Verification:

- `./gradlew test --tests com.example.app.event.EventRepositoryTest` passes (4 test methods).
- `./gradlew test` keeps all existing S-01 + LLM tests green.
- `./gradlew build` succeeds (no compile / dependency / Hibernate-mapping errors).

#### Manual Verification:

- `./gradlew bootRun` against a live Postgres successfully starts and Hibernate logs the `create table app_event …` DDL.
- `psql` (or `flyctl postgres connect`) shows `\d app_event` with the FK to `app_user(id)` and the `ix_app_event_user_date` index.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation before proceeding.

---

## Phase 2: Manual entry form, controller, validation

### Overview

Ship the form route. After this phase, a logged-in parent can navigate to `/events/new`, fill out the form, submit, and be redirected to `/app` — where they will not yet see the event (Phase 3 wires the view). The DB persistence + validation pipeline is live and end-to-end.

### Changes Required:

#### 1. `EventForm` Bean Validation DTO

**File**: `src/main/java/com/example/app/event/EventForm.java` (new)

**Intent**: Capture the manual entry inputs with validation matching PRD FR-004 + the round-2 question decision (past dates allowed, title 1–200, requirements/notes max 2000). Designed to be reusable by S-03's edit form and S-05's review form — same fields, same rules.

**Contract**: POJO with getters + setters. Fields and annotations: `@NotNull LocalDate eventDate`, `LocalTime eventTime` (no annotation — optional), `@NotBlank @Size(max = 200) String title`, `@Size(max = 2000) String requirements` (nullable), `@Size(max = 2000) String notes` (nullable). No `@FutureOrPresent` on `eventDate` — past dates are allowed (round-2 decision). Match the `SignupForm.java:1` style: package-private fields exposed via standard getters/setters, no Lombok.

#### 2. `EventController`

**File**: `src/main/java/com/example/app/event/EventController.java` (new)

**Intent**: Two endpoints. GET `/events/new` renders the empty form; POST `/events` validates, persists, and POST/Redirect/GETs to `/app`. Looks up the current `AppUser` from `Authentication.getName()` to set `Event.user` before save. Mirrors `SignupController`'s shape minus the auto-login plumbing (the user is already authenticated here).

**Contract**: `@Controller`, constructor-injected `EventRepository` + `AppUserRepository`. Method shapes:

- `@GetMapping("/events/new") String show(Model model)` → seeds `eventForm` model attribute if absent, returns view name `"events/new"`.
- `@PostMapping("/events") String create(@Valid @ModelAttribute("eventForm") EventForm form, BindingResult result, Authentication auth)` → on validation errors returns `"events/new"`; otherwise looks up `AppUser` via `appUserRepository.findByEmail(auth.getName()).orElseThrow()`, constructs an `Event(user, form.getEventDate(), form.getEventTime(), form.getTitle().trim(), form.getRequirements(), form.getNotes())`, saves, returns `"redirect:/app"`.

`Event` will need either a setter-based constructor pattern or a builder; align with `AppUser`'s style — a public constructor that takes all required fields plus a `@PrePersist` for `createdAt`.

#### 3. `events/new.html` Thymeleaf template

**File**: `src/main/resources/templates/events/new.html` (new)

**Intent**: The form UI. Mirrors `signup.html` structure (head fragment, topbar, `<main class="auth-page">` for centered narrow layout) so it inherits S-01's styling. Five labelled inputs with inline `<span class="field-error">` errors via `*{errors}`.

**Contract**: `th:object="${eventForm}"`, `th:action="@{/events}"`, `method="post"`. Inputs: `<input type="date" th:field="*{eventDate}" required/>`, `<input type="time" th:field="*{eventTime}"/>` (no `required`), `<input type="text" th:field="*{title}" required maxlength="200"/>`, `<textarea th:field="*{requirements}" maxlength="2000" rows="3"></textarea>`, `<textarea th:field="*{notes}" maxlength="2000" rows="3"></textarea>`. Each input is followed by `<span class="field-error" th:if="${#fields.hasErrors('<field>')}" th:errors="*{<field>}"></span>` — pattern from `signup.html:9-14`. Submit button text: "Save event". Below the form: `<a th:href="@{/app}">Cancel</a>`.

#### 4. `application.properties` — project timezone + reminder hour

**File**: `src/main/resources/application.properties` (modify)

**Intent**: Pin the project zone (per round-2 decision: `Europe/Warsaw`, single-user MVP) and the reminder hour (08:00). Read by Phase 3's `AppEventProperties` + `EventReminder`. Defined here in Phase 2 so the keys are durable as soon as the form is live, even though the helper class lands in Phase 3.

**Contract**: Append two lines under a new `# Manual event entry (S-02)` comment header:

```
app.timezone=Europe/Warsaw
app.event.reminder.hour=8
```

#### 5. MockMvc tests for the form

**File**: `src/test/java/com/example/app/event/EventControllerTest.java` (new)

**Intent**: Exercise GET render, POST happy path, each validation failure case, the CSRF requirement, and the auth requirement. Reuses the `AppApplicationTests.java:35-37` annotation stack (`@SpringBootTest @AutoConfigureMockMvc @TestPropertySource(properties = "REMEMBER_ME_KEY=test-key-not-for-production")`).

**Contract**: Test methods:

- `anonymousGetEventsNewRedirectsToLogin` — `mvc.perform(get("/events/new"))` → 3xx to `/login`.
- `authenticatedGetEventsNewRenders` — with `.with(user("alice@example.com"))` → 200 + content contains `action="/events"` and `type="date"`.
- `postEventHappyPathRedirectsToApp` — seed `AppUser` (alice), POST with all fields filled + `.with(csrf())` → 302 to `/app`; assert `eventRepository.findUpcomingByUser(alice, LocalDate.now())` contains an event whose title equals the submitted title and whose `user.email == "alice@example.com"`. Do NOT use `count() == 1` — `@SpringBootTest` shares the H2 context across tests (no `@Transactional` / `@DirtiesContext`), JUnit5 makes no within-class ordering guarantees, and other tests in this class (`postEventPastDateIsAccepted`, `appHidesPastEventsForCurrentUser`) also persist events; a count-based assertion would go flaky.
- `postEventBlankTitleRendersFieldError` — POST with blank title + valid date + csrf → 200 + content contains the title-blank error message.
- `postEventMissingDateRendersFieldError` — POST without `eventDate` → 200 + field error.
- `postEventOversizeTitleRendersFieldError` — title 201 chars → 200 + size error.
- `postEventPastDateIsAccepted` — date = `LocalDate.now().minusDays(7)` → 302 (no validation error — round-2 decision).
- `postEventWithoutCsrfIs403` — POST without `.with(csrf())` → 403.

### Success Criteria:

#### Automated Verification:

- `./gradlew test --tests com.example.app.event.EventControllerTest` passes (8 test methods).
- `./gradlew test` keeps all S-01 + Phase 1 + LLM tests green.
- `./gradlew build` succeeds.

#### Manual Verification:

- `./gradlew bootRun`, log in, navigate to `/events/new`, fill in a date + title, submit; the browser lands on `/app` with HTTP 302 visible in DevTools and no error in the server log.
- A row appears in `app_event` (verify via `psql` or Spring Boot DevTools H2 console if used locally) with the correct `user_id` matching the logged-in user.
- Submitting the form with a blank title shows the inline error "must not be blank" (or the configured message) and does NOT navigate away.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation before proceeding.

---

## Phase 3: Populated personal view, reminder helper, partition test

### Overview

Wire the personal view to fetch and render the current user's upcoming events. Ship the `EventReminder` helper + `AppEventProperties` config class with DST-correct unit tests so S-04 inherits a tested reminder semantic. Lock the per-user data partition contract with a cross-user integration test mirroring S-01's `appShowsOwnEmailOnlyNotOtherUsersEmail`.

### Changes Required:

#### 1. `AppEventProperties` configuration class

**File**: `src/main/java/com/example/app/event/AppEventProperties.java` (new)

**Intent**: Bind `app.timezone` + `app.event.reminder.hour` to a typed class so `EventReminder` and any later consumer (S-04 iCal serializer) read structured config, not stringly-typed `@Value`s.

**Contract**: `@ConfigurationProperties(prefix = "app")` annotated record (or class) with `ZoneId timezone` and a nested `Reminder reminder` containing `int hour`. Spring Boot's `ZoneId` converter resolves `Europe/Warsaw` automatically. Register the class via `@EnableConfigurationProperties(AppEventProperties.class)` on `AppApplication.java` (or a dedicated `@Configuration` — match existing project convention, S-01 added beans via `SecurityConfig`).

**Addendum (2026-06-08, post-impl)**: Final shape is `AppEventProperties(ZoneId timezone, EventSettings event)` with `EventSettings(Reminder reminder)` and accessor chain `properties.event().reminder().hour()` — an extra nesting level beyond the sketch above is required so the Phase 2 property key `app.event.reminder.hour` binds correctly. The nested type was named `EventSettings` (not `Event`) to avoid shadowing the JPA `@Entity Event` in the same package; the §2 helper line therefore reads `properties.event().reminder().hour()` not `properties.reminder().hour()`.

#### 2. `EventReminder` helper

**File**: `src/main/java/com/example/app/event/EventReminder.java` (new)

**Intent**: Encapsulate the "morning of day before" rule in one place. S-04 will inject this and serialize the result into the iCalendar `VALARM` block. Returns a `ZonedDateTime` so callers can format with the original zone preserved (`DTSTART;TZID=Europe/Warsaw`).

**Contract**: `@Component`, constructor-injected `AppEventProperties`. One method: `ZonedDateTime reminderFor(Event event)` returning `event.getEventDate().minusDays(1).atTime(properties.reminder().hour(), 0).atZone(properties.timezone())`. No other public surface.

#### 3. `EventReminder` unit tests

**File**: `src/test/java/com/example/app/event/EventReminderTest.java` (new)

**Intent**: Lock the reminder semantic. Especially DST — `Europe/Warsaw` transitions on the last Sunday of March (spring forward) and the last Sunday of October (fall back). The reminder must always be "08:00 wall-clock in the project zone" regardless of which side of the transition the event sits.

**Contract**: Plain JUnit5 (no Spring context). Construct `AppEventProperties(ZoneId.of("Europe/Warsaw"), new Reminder(8))` manually, instantiate `EventReminder(properties)`, and assert against an `Event` built with `new Event(user, eventDate, eventTime, …)` (where `user` is a stub `AppUser` — no DB). Test methods:

- `reminderForOrdinaryDayIsEightAmDayBefore` — event 2026-09-15 → reminder `2026-09-14T08:00+02:00[Europe/Warsaw]`.
- `reminderForFirstOfMonthCrossesMonthBoundary` — event 2026-09-01 → reminder `2026-08-31T08:00+02:00`.
- `reminderForFirstOfYearCrossesYearBoundary` — event 2027-01-01 → reminder `2026-12-31T08:00+01:00` (note: zone offset is +01:00 because Dec is CET, not CEST).
- `reminderAcrossSpringForwardStaysAtZoneEight` — event 2026-03-30 (day after spring-forward Sunday 2026-03-29) → reminder `2026-03-29T08:00+02:00` (i.e. on the transition day, after the spring forward).
- `reminderAcrossFallBackStaysAtZoneEight` — event 2026-10-26 (day after fall-back Sunday 2026-10-25) → reminder `2026-10-25T08:00+01:00` (i.e. on the transition day, in CET because the fall-back at 03:00 has already happened — 08:00 wall-clock is on the +01:00 side).

Each assertion checks both the `LocalDateTime` and the `ZoneOffset` to lock DST-correctness.

#### 4. `AppController#app` fetches upcoming events

**File**: `src/main/java/com/example/app/web/AppController.java` (modify)

**Intent**: Replace the bare `model.addAttribute("userEmail", auth.getName())` with the same plus an `events` model attribute holding the user's upcoming events ordered upcoming-first. Looks up the user via `appUserRepository.findByEmail(auth.getName()).orElseThrow()` — `orElseThrow()` is safe because Spring Security guarantees the user exists if `Authentication` is non-anonymous.

**Contract**: Add constructor-injected `AppUserRepository` + `EventRepository`. In `app(Authentication auth, Model model)`: resolve `user`, call `eventRepository.findUpcomingByUser(user, LocalDate.now())`, add as model attribute `"events"`. Keep `userEmail` for the topbar fragment. Other methods (`index`, `login`) are unchanged.

#### 5. `app.html` renders the events list

**File**: `src/main/resources/templates/app.html` (modify)

**Intent**: Replace the hard-coded `<p class="empty-state">No events yet …</p>` with a Thymeleaf conditional: when `${events}` is non-empty, render an ordered list of events with date, time (if present), title, requirements, notes; otherwise show the empty-state. Add an `Add event` link to `/events/new` at the top of the main area, visible in both states.

**Contract**: Inside `<main class="app-page">`:

- Always: `<a th:href="@{/events/new}" class="primary-action">Add event</a>` (style stays simple — reuse existing CSS or add a small class).
- When events present: `<ul class="event-list"><li th:each="e : ${events}">…</li></ul>` where each `<li>` renders `${e.eventDate}` (formatted via `${#temporals.format(e.eventDate, 'EEE d MMM yyyy')}`), optional `${e.eventTime}` (only when non-null, formatted `'HH:mm'`), `<strong th:text="${e.title}"></strong>`, and conditionally `${e.requirements}` + `${e.notes}` when non-blank.
- When events empty: keep the existing `<p class="empty-state">No events yet. Add one above.</p>` text (lightly reworded since "Manual entry coming in the next slice" is no longer truthful).

Minor CSS additions to `fragments/layout.html` for `.event-list` and `.primary-action` (small inline tweaks, same `<style>` block — keeps the no-asset-pipeline pattern).

#### 6. Cross-user partition integration test

**File**: `src/test/java/com/example/app/AppApplicationTests.java` (modify — append one test method)

**Intent**: Lock the per-user partition contract for `Event`. Same shape as S-01's `appShowsOwnEmailOnlyNotOtherUsersEmail` (`AppApplicationTests.java:218-228`) — assert presence + absence simultaneously, so a regression that leaks user B's events into user A's view fails the assertion.

**Contract**: New `@Test void appShowsUpcomingEventsForCurrentUserOnly()`: seed `alice`, `bob`; persist an `Event` for `alice` with title `"alice-only-event-title"` and `event_date = LocalDate.now().plusDays(3)`; persist an `Event` for `bob` with title `"bob-only-event-title"` (any future date). Inject the new `EventRepository` autowire alongside the existing `AppUserRepository`. Perform `mvc.perform(get("/app").with(user(alice.getEmail()))).andExpect(content().string(containsString("alice-only-event-title"))).andExpect(content().string(not(containsString("bob-only-event-title"))))`. Mirrors the existing pattern exactly.

#### 7. MockMvc test for `AppController` model population

**File**: `src/test/java/com/example/app/event/EventControllerTest.java` (modify — append one method)

**Intent**: Lock the "past events hidden" behaviour at the controller layer. The repository test from Phase 1 already proves the JPQL filter; this test proves `AppController#app` calls the right finder.

**Contract**: New `@Test void appHidesPastEventsForCurrentUser()`: seed alice, persist two events for her — one with `event_date = LocalDate.now().minusDays(2)` and title `"past-event-title"`, one with `event_date = LocalDate.now().plusDays(2)` and title `"future-event-title"`. `mvc.perform(get("/app").with(user(alice.getEmail()))).andExpect(content().string(containsString("future-event-title"))).andExpect(content().string(not(containsString("past-event-title"))))`.

#### 8. Seed user in existing S-01 test `getAppAuthenticatedShowsEmail`

**File**: `src/test/java/com/example/app/AppApplicationTests.java` (modify — extend existing test at `AppApplicationTests.java:187-192`)

**Intent**: The S-01 test mints a fake principal via `.with(user("showmail@example.com"))` without seeding the DB. Once change #4 swaps `AppController#app` to `appUserRepository.findByEmail(auth.getName()).orElseThrow()`, the unseeded principal triggers `NoSuchElementException` and the request 500s — Success Criterion 3.2 would fail. Seed the user up-front so the existing assertion (response contains the email) continues to hold without softening `orElseThrow()`.

**Contract**: Inside `getAppAuthenticatedShowsEmail()`, before the `mvc.perform(...)` call, insert `appUserRepository.save(new AppUser("showmail@example.com", passwordEncoder.encode("verylongpassword12")))`. Mirrors the seed step in `appShowsOwnEmailOnlyNotOtherUsersEmail` and `rememberMeCookieReAuthenticatesAfterSessionEnds`.

### Success Criteria:

#### Automated Verification:

- `./gradlew test --tests com.example.app.event.EventReminderTest` passes (5 test methods, all DST/boundary cases).
- `./gradlew test --tests com.example.app.AppApplicationTests` passes — including the new `appShowsUpcomingEventsForCurrentUserOnly` test and the existing `getAppAuthenticatedShowsEmail` extended per change #8 (without the seed, it 500s once change #4 lands).
- `./gradlew test --tests com.example.app.event.EventControllerTest` includes the new `appHidesPastEventsForCurrentUser` test and passes.
- `./gradlew test` passes the full suite (S-01 originals + Phase 1 + Phase 2 + Phase 3 new tests).
- `./gradlew build` succeeds.

#### Manual Verification:

- `./gradlew bootRun`, log in, visit `/events/new`, add a future event with a date a few days out; on redirect to `/app` the event appears with date and title rendered.
- Add a second event for a different date; the list orders date-asc.
- Add an event for today with a specific time, then another for today without a time; the timed one appears before the untimed one (nulls-last on `event_time`).
- Sign up a second user in a private browser session; the second user's `/app` shows no events (not the first user's).
- Add an event with a past date (e.g. yesterday); the form accepts it, the row is in the DB, but `/app` does not show it.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation that the full S-02 slice works end-to-end in a browser before treating the slice as ready for `/10x-impl-review`.

---

## Testing Strategy

### Unit Tests:

- `EventReminderTest` — 5 cases: ordinary day, month boundary, year boundary, spring-forward DST transition, fall-back DST transition. Pure JUnit5, no Spring context.

### Integration Tests:

- `EventRepositoryTest` (`@DataJpaTest`) — 4 cases: table exists, scoped finder filters by user, past events excluded, ordering with null-time correctness.
- `EventControllerTest` (`@SpringBootTest @AutoConfigureMockMvc`) — 9 cases: anonymous redirect, authenticated GET render, POST happy path, blank title error, missing date error, oversize title error, past date accepted, CSRF requirement, `/app` past-event hidden.
- `AppApplicationTests` — 1 new case: `appShowsUpcomingEventsForCurrentUserOnly` (cross-user partition for `Event`).

### Manual Testing Steps:

1. `./gradlew bootRun` against local Postgres (`SPRING_DATASOURCE_URL` etc. set).
2. Sign up `alice@example.com` (if not seeded already), land on `/app` — verify empty-state shows the new "Add event" link.
3. Click "Add event" → `/events/new` renders the form.
4. Submit blank → inline errors on title + date; form does not navigate.
5. Submit with date 3 days out + title "Field trip — yellow shirt" + requirements "Yellow shirt, 5 PLN" + notes empty → 302 → `/app` → event visible at top of the list, formatted date.
6. Add a same-day event with `09:30` time and another without time → verify order: timed first, untimed second (nulls last).
7. Add an event for yesterday → not visible on `/app` (past hidden), but verify the row exists in DB via `psql`.
8. In a private browser window, sign up `bob@example.com` → `/app` empty for bob; alice's events are not visible.
9. `psql`: `SELECT id, user_id, event_date, event_time, title FROM app_event ORDER BY event_date;` — verify partition by user_id.

## Performance Considerations

Single-user MVP, low QPS, expected ≤ a few dozen events per user over the MVP lifetime. The `ix_app_event_user_date` index on `(user_id, event_date)` covers the only query the slice introduces. No N+1 risk — `Event` has one `@ManyToOne` (`user`) and the upcoming-events list does not dereference it (the controller already has `user` in hand). No caching needed. No pagination needed at this scale.

## Migration Notes

Hibernate `ddl-auto=update` creates `app_event` additively on first boot after deploy. The FK to `app_user(id)` is also added by Hibernate. Per `deploy-plan.md` §5.2, this is the supported migration shape for MVP — no column drops or type narrowing, both of which are non-issues for this slice (we only add a table + index + FK). No data backfill needed. Rollback shape: if S-02 is reverted before any rows are written, the `app_event` table can be dropped manually; if rows exist, dropping the table loses them — but at MVP scale that's acceptable.

`application.properties` adds two new keys (`app.timezone`, `app.event.reminder.hour`). Both have safe defaults baked into `AppEventProperties` (`Europe/Warsaw` and `8`) so an env that doesn't override still works.

## References

- PRD: `context/foundation/prd.md` — FR-003 (manual entry), FR-004 (event schema), FR-008 (reminder default), FR-009 (personal view), US-02 (manual-entry user story).
- Roadmap: `context/foundation/roadmap.md` — S-02 slice definition, including the risk note ("lock the shape against PRD FR-004's schema").
- S-01 plan: `context/changes/minimal-auth-and-empty-personal-view/plan.md` — auth + empty personal view, the foundation this slice builds on.
- S-01 code patterns:
  - `src/main/java/com/example/app/user/AppUser.java:1` — entity shape (UUID PK, package, `@PrePersist`).
  - `src/main/java/com/example/app/user/SignupController.java:52-91` — `@Valid @ModelAttribute` + `BindingResult` + POST/Redirect/GET pattern.
  - `src/main/java/com/example/app/user/SignupForm.java:1` — Bean Validation DTO shape.
  - `src/main/resources/templates/signup.html:7-16` — `th:object` + `th:field` + `*{errors}` template pattern.
  - `src/test/java/com/example/app/AppApplicationTests.java:218-228` — cross-user partition test shape.
- Lessons: `context/foundation/lessons.md` — "PRD-mandated capabilities must land in tech-stack.md as explicit fields" (applied here by locking `Event` schema to PRD FR-004 exactly).
- Deploy plan: `context/deployment/deploy-plan.md` §5.2 — additive-only migrations contract.

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Event entity, repository, scoped finder

#### Automated

- [x] 1.1 `./gradlew test --tests com.example.app.event.EventRepositoryTest` passes (4 test methods) — 0645ab6
- [x] 1.2 `./gradlew test` keeps all existing S-01 + LLM tests green — 0645ab6
- [x] 1.3 `./gradlew build` succeeds — 0645ab6

#### Manual

- [x] 1.4 `./gradlew bootRun` against live Postgres starts and Hibernate logs `create table app_event …` — 0645ab6
- [x] 1.5 `psql` shows `\d app_event` with the FK to `app_user(id)` and the `ix_app_event_user_date` index — 0645ab6

### Phase 2: Manual entry form, controller, validation

#### Automated

- [x] 2.1 `./gradlew test --tests com.example.app.event.EventControllerTest` passes (8 test methods) — 68d7ee3
- [x] 2.2 `./gradlew test` keeps all S-01 + Phase 1 + LLM tests green — 68d7ee3
- [x] 2.3 `./gradlew build` succeeds — 68d7ee3

#### Manual

- [x] 2.4 `bootRun`, log in, navigate to `/events/new`, submit a valid form; browser lands on `/app` with HTTP 302 — 68d7ee3
- [x] 2.5 A row appears in `app_event` with the correct `user_id` matching the logged-in user — 68d7ee3
- [x] 2.6 Submitting with a blank title shows the inline error and does NOT navigate away — 68d7ee3

### Phase 3: Populated personal view, reminder helper, partition test

#### Automated

- [x] 3.1 `./gradlew test --tests com.example.app.event.EventReminderTest` passes (5 test methods, all DST/boundary cases) — 72ff5b5
- [x] 3.2 `./gradlew test --tests com.example.app.AppApplicationTests` includes `appShowsUpcomingEventsForCurrentUserOnly` and passes — 72ff5b5
- [x] 3.3 `./gradlew test --tests com.example.app.event.EventControllerTest` includes `appHidesPastEventsForCurrentUser` and passes — 72ff5b5
- [x] 3.4 `./gradlew test` passes the full suite (S-01 + Phase 1 + Phase 2 + Phase 3) — 72ff5b5
- [x] 3.5 `./gradlew build` succeeds — 72ff5b5

#### Manual

- [x] 3.6 `bootRun`, log in, add a future event; on redirect to `/app` the event appears with date and title rendered — 72ff5b5
- [x] 3.7 Two events same date — timed one orders before untimed one (nulls-last on `event_time`) — 72ff5b5
- [x] 3.8 Sign up a second user; their `/app` shows no events (not the first user's) — 72ff5b5
- [x] 3.9 Add an event with a past date; form accepts it, the row is in the DB, but `/app` does not show it — 72ff5b5
