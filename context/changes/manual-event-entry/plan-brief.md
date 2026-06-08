# Manual Event Entry — Plan Brief

> Full plan: `context/changes/manual-event-entry/plan.md`

## What & Why

Ship roadmap slice **S-02**: a logged-in parent can manually create an event (date, optional time, title, requirements, notes) and see it on their personal view at `/app`. The slice is required because PRD FR-003 mandates the manual-entry path as a must-have MVP capability — but more importantly, it introduces the `Event` JPA entity and the `EventReminder` helper that **S-04 (iCalendar feed)** and **S-05 (image extraction)** will both reuse. If the entity shape diverges from PRD FR-004 here, S-05 pays the migration cost; if the reminder semantic isn't locked here, S-04 has to make the call later.

## Starting Point

S-01 has landed: `AppUser` entity, Spring Security 7.0.5 form-login + persistent remember-me, `AppController` rendering `app.html` with a hard-coded `"No events yet"` empty-state. Form pattern is established by `SignupController` + `SignupForm` + `signup.html` (`@Valid @ModelAttribute` + `BindingResult` + Thymeleaf `th:object`/`th:field`/POST-Redirect-GET). Persistence runs on Postgres in prod and H2 in tests with `ddl-auto=update` under an additive-only migration contract (`deploy-plan.md` §5.2). No `Event` entity, no manual-entry route, no reminder helper exist yet.

## Desired End State

A logged-in parent clicks "Add event" on `/app`, navigates to `/events/new`, fills in date + title (plus optional time, requirements, notes), submits, and is redirected back to `/app` with the new event visible in the upcoming-events list (date asc, time asc nulls-last). Past events are hidden by default. The `EventReminder.reminderFor(event)` helper returns `event.eventDate.minusDays(1) at 08:00 Europe/Warsaw` and is unit-tested across DST transitions so S-04 can serialize it into a `VALARM` block without re-deriving the rule. A cross-user partition integration test asserts user A's `/app` never contains user B's event title.

## Key Decisions Made

| Decision | Choice | Why | Source |
| --- | --- | --- | --- |
| `Event` entity columns | Minimal — `id, user_id, event_date (LocalDate), event_time (LocalTime, null), title, requirements, notes, created_at` | Locks PRD FR-004 exactly; no speculative `status` / `source` columns. S-05 adds what it needs additively per `deploy-plan.md` §5.2. Roadmap risk note explicitly mandates this lock. | Plan |
| Reminder model | Compute at serialization time — no DB column | Reminder is fixed by PRD ("morning of day before"); per-event timing is a v2 deferral. No column whose value is derivable. Helper centralizes the rule for S-04 to inherit. | Plan |
| Timezone | Wall-clock in `Europe/Warsaw` via `app.timezone` config | Single-user MVP, user is in Poland. Date-only events (the dominant case per PRD FR-004) need wall-clock semantics; UTC `Instant` makes "this Tuesday" ambiguous across DST. Per-user `timezone` field is the post-MVP path. | Plan |
| Personal view ordering | Upcoming-first ascending; past hidden | Matches the parent's question ("what's coming up?"). Trivial JPQL query. S-03 lifecycle work can add a "Show past events" toggle later. | Plan |
| Form route | Separate `/events/new` page | Mirrors `SignupController` shape exactly (POST/Redirect/GET, `BindingResult`, inline errors); no JS introduced; reusable as the shell for S-05's review form and S-03's edit form. | Plan |
| Validation | PRD-shape only; past dates allowed | Matches PRD FR-003 ("same form fields as the review screen, all empty and editable"). Past dates accepted because parents legitimately catch up on retroactive announcements; S-03's edit path fixes typos. | Plan |
| Save UX | POST/Redirect/GET to `/app` | Refresh-safe; user sees their action's effect in context; canonical Spring MVC pattern used by `SignupController`. | Plan |
| Table name | `app_event` | Mirrors `app_user` from S-01; sidesteps any reserved-word concerns across SQL dialects. | Plan |
| Ordering implementation | `@Query` JPQL with explicit `NULLS LAST` | Spring Data derived method names cannot express null-handling on `event_time`. Both Postgres and H2 accept the `nulls last` JPQL clause. | Plan |

## Scope

**In scope:**

- `Event` JPA entity at the PRD FR-004 shape with `@ManyToOne` to `AppUser` and an index on `(user_id, event_date)`
- `EventRepository` with `findUpcomingByUser(user, today)` JPQL query
- `EventForm` Bean Validation DTO; `EventController` for GET `/events/new` and POST `/events`; `events/new.html` template
- `AppController#app` rewired to fetch the user's upcoming events; `app.html` updated to render the list (with the existing empty-state preserved as fallback)
- `EventReminder` helper + `AppEventProperties` config class with `app.timezone` + `app.event.reminder.hour`
- Tests: JPA-slice repository test, MockMvc form tests (8 cases), `EventReminder` DST/boundary unit tests (5 cases), cross-user partition integration test on `/app`

**Out of scope:**

- Edit / delete of accepted events (S-03)
- iCalendar feed serialization or token issuance (S-04)
- Image upload, AI extraction, proposed-events review UI (S-05)
- `status` / `source` / `source_image_id` columns on `Event` (added additively by S-05)
- Per-event configurable reminder timing (v2 per PRD §Non-Goals)
- HTMX / JS framework / inline form / modal patterns
- "Show past events" toggle (S-03's lifecycle work can revisit)
- Per-user timezone field (single-user MVP keeps a project zone)

## Architecture / Approach

```
Browser ─(GET /events/new)──▶ EventController.show()
                                  └─▶ events/new.html (empty EventForm)

Browser ─(POST /events + CSRF)─▶ EventController.create(@Valid EventForm, BindingResult, Authentication)
                                          │  ┌─ validation fails ─┐
                                          │  └─▶ events/new.html (errors)
                                          ▼
                                  AppUserRepository.findByEmail(auth.getName())
                                          │
                                          ▼
                                  new Event(user, date, time, title, reqs, notes)
                                          │
                                          ▼
                                  EventRepository.save(event)  ──▶  Postgres / H2 (app_event)
                                          │
                                          ▼
                                  302 ─▶ /app

Browser ─(GET /app)──▶ AppController.app(Authentication, Model)
                          │
                          ▼
                  EventRepository.findUpcomingByUser(user, LocalDate.now())
                          │
                          ▼
                  app.html  (renders ${events} list + "Add event" link;
                             falls back to empty-state when zero)

S-04 will later inject EventReminder.reminderFor(event) when serializing
the iCalendar feed — no S-02 code needs to change for that.
```

Hibernate `ddl-auto=update` creates `app_event` (with FK + index) on first boot after deploy. The `EventReminder` helper + `AppEventProperties` config class live as Spring beans, ready for S-04 to autowire.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Event entity, repository, scoped finder | `Event` entity (locked FR-004 shape), `EventRepository` with JPQL `findUpcomingByUser`, JPA-slice test that proves table creation + user-scoped filtering + nulls-last ordering | Spring Data derived queries can't express `NULLS LAST`; must use `@Query` JPQL. Verified portable across Postgres + H2. |
| 2. Manual entry form, controller, validation | `EventForm` Bean Validation DTO, `EventController` with GET `/events/new` + POST `/events`, `events/new.html` template, `application.properties` adds `app.timezone` + `app.event.reminder.hour`. End: a user can POST a valid form and be redirected to `/app` (which still says "No events yet" — Phase 3 wires the view). | Forgetting `th:action="@{/events}"` (raw `action="…"`) skips Spring's CSRF auto-injection and POST returns 403 — same gotcha S-01's signup form handled. |
| 3. Populated personal view, reminder helper, partition test | `AppController#app` fetches events; `app.html` renders the list; `EventReminder` + `AppEventProperties` ship with 5 DST-aware unit tests; cross-user partition integration test mirrors S-01's. End: full S-02 slice working in a browser. | DST transitions in `Europe/Warsaw` (March + October Sundays) must be exercised in the reminder unit tests, otherwise S-04 inherits a quietly-wrong reminder semantic. |

**Prerequisites:** S-01 has landed (`AppUser`, security config, Thymeleaf templates, H2 testRuntimeOnly). Live Postgres needed for manual verification; H2 covers all automated tests.

**Estimated effort:** ~2–3 evening sessions across 3 phases for a solo dev. Bulk is Phase 2's form plumbing + Phase 3's reminder DST tests.

## Open Risks & Assumptions

- **`event_time` nulls-last ordering** is implemented via `@Query("… order by e.eventDate asc, e.eventTime asc nulls last")`. Verified to work on both Postgres and H2 — H2 accepts `NULLS LAST` in JPQL queries by translating to its native SQL syntax.
- **`@ManyToOne(fetch = LAZY)` on `Event.user`** keeps the upcoming-events query from triggering an N+1 (the controller already has `user` in hand; the template doesn't dereference `event.getUser()`).
- **`AppEventProperties.timezone` defaults to `Europe/Warsaw`** in the `@ConfigurationProperties` class; the `application.properties` entry is the explicit override, but if removed the app still works. Same for `reminder.hour` default `8`.
- **Past dates allowed**: a typo'd 1925-01-15 lands a real wrong-decade event with no warning. The PRD's edit path (FR-010, in S-03) will be the recovery mechanism. Not adding `@FutureOrPresent` is a deliberate decision, not an oversight.
- **`AppUser` `password_hash` length is 60** (BCrypt fixed-length per `AppUser.java:27`); `Event` `title` length 200 and free-text fields at 2000 are picked from PRD shape with margin. Hibernate may emit `varchar(200)` and `varchar(2000)`; Postgres + H2 both fine.

## Success Criteria (Summary)

- A new event submitted via `/events/new` lands in `app_event` (correct `user_id`) and appears on the submitting user's `/app` after redirect.
- A two-user negative integration test (`appShowsUpcomingEventsForCurrentUserOnly`) confirms user A's `/app` never contains user B's event title.
- `EventReminder.reminderFor(event)` returns wall-clock `08:00 Europe/Warsaw` on the day before the event, on both sides of the March and October DST transitions, verified by unit tests.
