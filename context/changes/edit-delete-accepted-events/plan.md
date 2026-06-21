# Edit / Delete Accepted Events (S-03) Implementation Plan

## Overview

Ship roadmap slice **S-03**: from the personal view at `/app`, a logged-in parent can **edit any field** of an accepted event and **delete** an accepted event, with both mutations propagating to the iCalendar feed at the next poll (PRD FR-010, FR-011, US-01 acceptance #4). No schema change; reuses the existing `Event` entity, `EventForm`, and `EventRepository`. Hard delete only (no soft-delete / audit / undo). Edit and delete are reachable only from `/app`'s upcoming-only list — past-event URLs blocked by a single `eventDate >= today` guard in the controller that mirrors the view filter. A browser-side `min=` floor on the date input in both create and edit forms catches typo'd 1925 dates without forking backend validation.

## Current State Analysis

- `Event` JPA entity (`src/main/java/com/example/app/event/Event.java`) carries everything S-03 needs: `id` (JPA-managed UUID PK, immutable across updates), `user`, `eventDate`, `eventTime`, `title`, `requirements`, `notes`, `createdAt`. No schema change required.
- `EventRepository` (`src/main/java/com/example/app/event/EventRepository.java`) exposes `findUpcomingByUser(user, today)`. There is no `findByIdAndUser` method yet — `SourceImageRepository.findByIdAndUser(UUID, AppUser): Optional<SourceImage>` (`src/main/java/com/example/app/event/SourceImageRepository.java`) is the direct precedent to mirror.
- `EventController` (`src/main/java/com/example/app/event/EventController.java`) owns `GET /events/new` + `POST /events` for manual creation. It is the canonical resource controller for `Event` — edit and delete extend this class (Spring MVC: one controller per resource, not per verb).
- `EventForm` (`src/main/java/com/example/app/event/EventForm.java`) was deliberately built in S-02 to be reusable here. It has the exact shape (`@NotNull eventDate`, optional `eventTime`, `@NotBlank @Size(max=200) title`, optional `@Size(max=2000)` requirements/notes) and **deliberately omits `@FutureOrPresent`** so past dates are accepted on create. S-03 inherits the same form binding for symmetry (see "Critical Implementation Details" below).
- `EventReviewController.java:157` is the canonical Polish flash pattern: `redirectAttributes.addFlashAttribute("successMessage", "Dodano " + accepted + " wydarzeń.")`, rendered in `app.html` (line 7) via `th:text` (HTML-escape-safe). S-03 adds two new flash strings in the same slot.
- `app.html` renders each event as a flat `<li>` (date, title, requirements, notes) — **zero action affordances exist today**. No edit/delete UI, no row-level CSS classes.
- iCalendar feed reads `EventRepository.findUpcomingByUser` directly with no cache, no ETag, no async; an in-flight DB write is structurally visible at the next feed poll. `CalendarControllerTest.deletedEventDisappearsFromNextPoll` already pins this property at the repository-deletion layer.
- **UID stability (Phase 0 finding):** `IcalFeedWriter.java:89` emits `vevent.add(new Uid(event.getId() + "@" + UID_HOST))`. `event.getId()` is the JPA-managed UUID PK — immutable across updates by construction. **An edit preserves the UID; calendar clients see it as an UPDATE, not a duplicate event.** Phase 2 carries a UID-stability test (Test B) to lock the property; no fix is required.
- **Test slice convention** (`context/foundation/lessons.md`): per-controller `@SpringBootTest @AutoConfigureMockMvc` class — `EventControllerTest` is the home for the new controller tests; `EventRepositoryTest` (`@DataJpaTest`) is the home for the new repository test. See "Critical Implementation Details → Test slice & isolation" for the deliberate handling of the `llm-chatclient-fail-fast` tension.

## Desired End State

A logged-in parent at `/app` sees each upcoming event with two inline actions: **Edytuj** (a link to `/events/{id}/edit`) and **Usuń** (a CSRF-protected `<form>`/`<button>` POST to `/events/{id}/delete` that opens a browser-native `confirm()` dialog naming the event title). Clicking **Edytuj** opens a pre-filled form mirroring `/events/new`; submitting it persists the changes via `POST /events/{id}` and redirects to `/app` with the Polish flash `"Zapisano zmiany w wydarzeniu „<title>”."`. Confirming the **Usuń** dialog hard-deletes the row and redirects to `/app` with `"Usunięto wydarzenie „<title>”."`. Both mutations propagate to the iCalendar feed at the next poll (existing `CalendarControllerTest` infrastructure verifies the property): edit updates the VEVENT in place (UID stable from `event.id`); delete removes the VEVENT entirely. A foreign-user or past-event URL returns 404 (not 403, mirroring `EventReviewService`'s pattern); a POST without CSRF returns 403; both behaviors are pinned by MockMvc tests.

### Key Discoveries:

- `IcalFeedWriter.java:89` builds UID from `event.id` — **stable across updates by construction** (Phase 0 research finding).
- `SourceImageRepository.findByIdAndUser` (`src/main/java/com/example/app/event/SourceImageRepository.java`) is the direct precedent for `EventRepository.findByIdAndUser`.
- `EventReviewService.applyDecisions` (lines 122-133 of its test) is the precedent for "404 on foreign-id ownership miss" — same `findByIdAndUser` + `orElseThrow(NOT_FOUND)` shape.
- `EventControllerTest` is the per-controller-class layout standard (`lessons.md` "Per-controller `@SpringBootTest` class is the test layout standard"). S-03 extends it, does not split.
- `templates/fragments/layout.html` lines 7–73 hold all CSS as inline `<style>`. New `.row-action` / `.row-action--danger` styles land there.
- `EventReviewController.java:157` is the canonical Polish-flash pattern (`successMessage` slot + plain Polish string + period).
- `EventForm` was designed in S-02 to be reusable here — the S-02 plan brief explicitly names this slice as the intended consumer.

## What We're NOT Doing

- **No new entity, no schema migration.** Reuses `Event` exactly as S-02 shipped it.
- **No `EventEditForm` / no `@FutureOrPresent` only on edit.** `EventForm` binds create and edit symmetrically (see Decisions). Adding `@FutureOrPresent` on edit alone introduces validation asymmetry and is rejected.
- **No soft-delete, no audit log, no `deleted_at` column, no edit history.** Hard delete; no `@Version` column; last-write-wins concurrent edits (single-user MVP).
- **No bulk delete, no multi-select.** Single-row delete only, symmetric with PRD FR-007's per-event safety floor on accept.
- **No undo / restore affordance.** A deleted event is recoverable only by manual re-creation or re-uploading the announcement (PRD §FR-011 Socratic note acknowledges this risk; mitigation is the `confirm()` dialog).
- **No icons (trash / pencil).** Polish text labels (`Edytuj` / `Usuń`) only — no SVG sprite, no localization complexity. Icons return on the table if/when multilingual support enters scope.
- **No swipe-to-delete on mobile.** Requires JS framework, breaks the "no JS framework" stance from S-02, introduces an interaction model inconsistent with the rest of the app.
- **No past-event recovery surface.** Past events are intentionally unreachable from `/app` (already filtered by `findUpcomingByUser`); URL-guessing a past `event.id` returns 404 by the controller guard. A separate change-id (`past-events-management` or similar) addresses recovery if the need surfaces in practice.
- **No "Show past events" toggle on `/app`.** Explicit non-goal per S-02 plan brief.
- **No HTMX / no JS framework / no inline editing.** Edit is always full-form, same template structure as create.
- **No revisiting S-04 SEQUENCE policy.** `IcalFeedWriter` emits `SEQUENCE=0` for every VEVENT; this is a locked S-04 decision (verified by `IcalFeedWriterTest`). Some calendar clients use `DTSTAMP` (regenerated on every feed render) rather than `SEQUENCE` for change detection. S-03 does not touch this.
- **No source-image purge on delete.** S-06's concern; `app_event` rows have no link to `source_image`.

## Implementation Approach

```
Browser ─(GET /app)──▶ AppController.app() ──▶ findUpcomingByUser ──▶ app.html
                                                                        │
                                              renders each <li> with: ◄─┘
                                                <a href="/events/{id}/edit">Edytuj</a>
                                                <form action="/events/{id}/delete" method="post">
                                                   <button onsubmit="return confirm(... ‘‘+ this.dataset.title +"’’?’)">Usuń</button>
                                                </form>

Browser ─(GET /events/{id}/edit)──▶ EventController.edit()
                                          │
                                          ▼ findOwnUpcomingEvent(id, user)
                                                │
                                                ├─ not found / not owned / past → 404
                                                ▼
                                          populate EventForm from Event
                                                │
                                                ▼
                                          events/edit.html (prefilled form, <a href="/app">Anuluj</a>)

Browser ─(POST /events/{id} + CSRF)──▶ EventController.update(@Valid EventForm, BindingResult, Authentication)
                                                │  ┌─ validation fails ─┐
                                                │  └─▶ events/edit.html (errors)
                                                ▼
                                          findOwnUpcomingEvent(id, user) ──▶ 404 if missing/past
                                                │
                                                ▼ @Transactional dirty-check
                                          event.setTitle/setDate/...  ──▶ implicit save on commit
                                                │
                                                ▼
                                          flash successMessage = "Zapisano zmiany w wydarzeniu „<title>”."
                                                │
                                                ▼ 302
                                          /app

Browser ─(POST /events/{id}/delete + CSRF)──▶ EventController.delete(Authentication)
                                                │
                                                ▼ findOwnUpcomingEvent(id, user) ──▶ 404 if missing/past
                                                │
                                                ▼ capture title before delete
                                                ▼ eventRepository.delete(event)
                                                │
                                                ▼
                                          flash successMessage = "Usunięto wydarzenie „<title>”."
                                                │
                                                ▼ 302
                                          /app

GET /calendar/{token}.ics  ──▶ unchanged — reads findUpcomingByUser; UID = event.id (stable across updates)
                                so an edit is rendered as a VEVENT update (same UID, new fields),
                                a delete drops the VEVENT entirely. No code change in CalendarController or IcalFeedWriter.
```

## Critical Implementation Details

### POST-based routes, not REST verbs

Spring MVC server-rendered forms cannot send PUT/DELETE without `HiddenHttpMethodFilter` (not enabled in this codebase) or a JS layer (rejected). Routes are: `GET /events/{id}/edit`, `POST /events/{id}` (save), `POST /events/{id}/delete` (delete). The delete affordance MUST be a `<form method="post"><button type="submit">Usuń</button></form>` — never an `<a>` link. Reasons: (1) `<form>` carries the CSRF token Spring Security auto-injects; (2) Chrome/Safari prefetchers and link crawlers will GET-fire `<a>` links, accidentally hitting `/events/{id}/delete` if it were a GET-safe URL or revealing the action endpoint to scanners. The button-not-link pattern is the same security floor REST conventions reach by other means — it's a non-removable requirement, not a "POST is good enough" compromise.

### `data-title` attribute pattern for the `confirm()` dialog (XSS-safe)

The delete button's `onsubmit` handler must not interpolate `event.title` into a JS string literal — a title like `'); alert(1); //` would execute as JS. Pattern: Thymeleaf renders `th:attr="data-title=${e.title}"` on the `<form>`, HTML-escaping the value. The inline handler reads `this.dataset.title` (DOM-decoded by the browser) and concatenates that into a plain JS string passed to `confirm()`. There is no JS interpolation context for the user-controlled value to escape — the dialog sees a literal Polish string. **Verify the flash messages (`successMessage`) are rendered by `th:text` (auto-escape) anywhere in the request path; `th:utext` anywhere would re-open the same XSS vector.** Current usage at `app.html:7` is `th:text` — confirmed safe.

### URL-guess past-event guard (controller, not view-only)

`findUpcomingByUser` hides past events from `/app`. The edit + delete handlers must NOT rely on "the user can't see the link" — they MUST independently filter `eventDate >= today` in the controller and return 404 (not 403, matching the existing `findByIdAndUser` + `orElseThrow(NOT_FOUND)` pattern in `EventReviewService`). Implementation: a single private helper `findOwnUpcomingEvent(UUID id, AppUser user): Event` that runs `eventRepository.findByIdAndUser(id, user).filter(e -> !e.getEventDate().isBefore(LocalDate.now(clock))).orElseThrow(() -> new ResponseStatusException(NOT_FOUND))`. Reused by all three handlers (GET edit, POST update, POST delete).

### `EventForm` reused for both create and edit — deliberate validation symmetry

S-03 binds the existing `EventForm` for edit. No `EventEditForm`, no `@FutureOrPresent` on edit. **Consequence (must be visible in the plan, not discovered in production):** an edit moving `eventDate` to the past returns 302 + flash `"Zapisano zmiany w wydarzeniu „<title>”."`, the row vanishes from `/app` (filter), and the URL-guess guard above blocks reaching the event again via the edit URL. The event still exists in the DB and still flows to the iCalendar feed (subject to `findUpcomingByUser` — which also filters past dates, so practically: the event becomes a ghost in the DB, invisible from both UI surfaces). Recovery requires DB access or a future `past-events-management` slice. **Mitigation (in this slice):** both `events/edit.html` and `events/new.html` carry a symmetric `onsubmit` soft-warn that fires only when the chosen `eventDate` is strictly before today — Polish prompt `"Data jest w przeszłości — kontynuować?"`. Symmetric across create and edit by design (preserves the validation-symmetry rule the slice defends); matches the XSS-safe shape of the delete `confirm()` (no user-controlled interpolation — the prompt is a static literal; the date comparison reads `this.eventDate.value`). A user confirming the dialog still hits the same server contract (302 + row vanishes); the pinpoint test in Phase 1 §5 locks that server contract end-to-end and stays red the day `@FutureOrPresent` is added asymmetrically. **The residual (typo'd past date with confirm dismissed) is documented in `lessons.md` as part of Phase 3; the in-slice soft-warn is the live mitigation, with a future hardening slice deferred unless a real-world typo surfaces.**

### Browser-side `min=` floor on the date input (symmetric across create + edit)

Both `events/new.html` and `events/edit.html` add `<input type="date" min="<today.minusYears(5).toString()>" ...>` via Thymeleaf model attribute `minDate` passed by `EventController`. This is a **browser-side guard only** — the server (`EventForm`) accepts every date, preserving create/edit validation symmetry. Browsers reject "1925-01-15" at form submission (most desktop + mobile browsers honor `min` on `type="date"`); the server never sees the invalid payload. Zero cost in test surface; zero asymmetry in backend rules.

### Test slice & isolation — known tension with `llm-chatclient-fail-fast`

`lessons.md` records "Per-controller `@SpringBootTest` class is the test layout standard". Existing `EventControllerTest` uses `@SpringBootTest @AutoConfigureMockMvc`. Extending it is the established convention. **Known fragility:** `@SpringBootTest` boots the full context, including `OpenRouterLlmVisionClient`, which has a null-ChatClient init issue tracked in change-id `llm-chatclient-fail-fast`. If that issue starts blocking the new edit/delete tests in CI, the recommended migration is a follow-up slice that refactors `EventControllerTest` to `@WebMvcTest(EventController.class)` with `@MockBean` for `EventRepository`, `AppUserRepository`, `Clock` — but **NOT in this slice**, because mixing test-slice annotations within one class is not possible and splitting the class violates the per-controller-class layout standard. Decision: extend the existing class with `@SpringBootTest`; accept the LLM-init coupling as a known shared issue. The `EventRepositoryTest` (`@DataJpaTest`) and `CalendarControllerTest` (`@SpringBootTest @AutoConfigureMockMvc`) extensions reuse the existing annotations of their respective files — no new pattern introduced.

### iCal propagation: two tests, two assertions

`CalendarControllerTest` already pins delete propagation (`deletedEventDisappearsFromNextPoll`). S-03 adds **two new tests, not one**: (A) `editedTitlePropagatesToFeed` — assert the new title appears in the feed body AND the old title does not; (B) `editPreservesUidInFeed` — assert the VEVENT's UID is identical before and after the edit (read the feed, find the line matching `UID:` + `event.id` + `@`, and confirm it appears exactly once both runs). Splitting prevents the "feed body doesn't contain new title" red from masking a UID change — the assertion messages are distinct, the debug paths are distinct.

## Phase 1: Repository finder + edit endpoint + edit form + edit-to-past pinpoint test

### Overview

Add the user-scoped `findByIdAndUser` finder on `EventRepository` and the `GET /events/{id}/edit` + `POST /events/{id}` endpoints on `EventController`. Ship the `events/edit.html` template with the same field shape as `events/new.html` plus a Cancel link. Pin the edit-to-past contract with a dedicated MockMvc test.

### Changes Required:

#### 1. `EventRepository` — add scoped finder

**File**: `src/main/java/com/example/app/event/EventRepository.java` (modify)

**Intent**: Provide the per-user lookup the edit/delete handlers will gate on. Mirrors the precedent set by `SourceImageRepository.findByIdAndUser` so the codebase reads consistently.

**Contract**: Add the method `Optional<Event> findByIdAndUser(UUID id, AppUser user);` (Spring Data derived query — no `@Query` needed). User-scoping is enforced at the query level — caller must pass the authenticated `AppUser`.

#### 2. `EventController` — extend with edit endpoints + helper

**File**: `src/main/java/com/example/app/event/EventController.java` (modify)

**Intent**: Add `GET /events/{id}/edit` (render prefilled form) and `POST /events/{id}` (validate + persist + flash + redirect). Both run through a shared private helper `findOwnUpcomingEvent(UUID id, AppUser user): Event` that combines `findByIdAndUser` + the `eventDate >= today` URL-guess guard + 404-on-miss. The helper is reused in Phase 2's delete handler. The existing `show()` / `create()` methods are unchanged. Inject `Clock` (already an existing bean used by `AppController`) for the `eventDate >= today` comparison so tests can stub time. Inject `RedirectAttributes` on the POST signature to set `successMessage`.

**Contract**: Method shapes:

- `private Event findOwnUpcomingEvent(UUID id, AppUser user)` — runs `eventRepository.findByIdAndUser(id, user).filter(e -> !e.getEventDate().isBefore(LocalDate.now(clock))).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND))`.
- `@GetMapping("/events/{id}/edit") String edit(@PathVariable UUID id, Authentication auth, Model model)` — looks up `AppUser` via `appUserRepository.findByEmail(auth.getName()).orElseThrow()`, calls `findOwnUpcomingEvent`, populates a new `EventForm` from the event fields, adds it to the model as `eventForm`, adds `eventId` model attribute (so the template can interpolate the action URL), adds `minDate` model attribute (`LocalDate.now(clock).minusYears(5).toString()`), adds `todayIso` model attribute (`LocalDate.now(clock).toString()` — used by the past-date soft-warn `onsubmit`), returns `"events/edit"`.
- `@PostMapping("/events/{id}") String update(@PathVariable UUID id, @Valid @ModelAttribute("eventForm") EventForm form, BindingResult result, Authentication auth, Model model, RedirectAttributes ra)` — on validation errors, populate `eventId` + `minDate` + `todayIso` on the model and return `"events/edit"`. Otherwise looks up `AppUser`, calls `findOwnUpcomingEvent`, mutates the entity's fields (`setEventDate`, `setEventTime`, `setTitle(form.getTitle().trim())`, `setRequirements`, `setNotes`). **Annotate `update()` with `@Transactional`** — dirty-checking requires an active write transaction (`EventController` is not `@Transactional` at class level; Spring Boot's OSIV default covers reads only, not writes). Mirrors the existing `EventReviewService.applyDecisions` pattern. Sets `ra.addFlashAttribute("successMessage", "Zapisano zmiany w wydarzeniu „" + event.getTitle() + "”.")`. Returns `"redirect:/app"`.

The `Event` entity may need new setters — add them in this phase if not already present (S-02 used a constructor-only style; setters are additive and don't break the existing pattern).

#### 3. `events/edit.html` Thymeleaf template

**File**: `src/main/resources/templates/events/edit.html` (new)

**Intent**: Mirror `events/new.html` structure (head fragment, topbar, `<main class="auth-page">` shell) with prefilled form values, the dynamic action URL, the `min` attribute on the date input, and an explicit `<a href="/app">Anuluj</a>` cancel link next to the submit button.

**Contract**: `th:object="${eventForm}"`, `th:action="@{/events/{id}(id=${eventId})}"`, `method="post"`. Same field stack as `new.html` (date, time, title, requirements, notes) with `*{errors}` inline field errors. Date input: `<input type="date" th:field="*{eventDate}" th:attr="min=${minDate}" required/>`. Submit button text: "Zapisz zmiany". After submit button: `<a th:href="@{/app}" class="cancel-link">Anuluj</a>`. CSRF token is auto-injected by Thymeleaf for `th:action` POST forms — no manual `<input type="hidden" name="_csrf">` needed. **Past-date soft-warn:** the surrounding `<form>` carries `th:attr="onsubmit=|return this.eventDate.value >= '${todayIso}' || confirm('Data jest w przeszłości — kontynuować?');|"` (Thymeleaf literal-substitution `||` so the single quotes around `${todayIso}` and the prompt survive verbatim). Fires only when the chosen date is strictly before today; the Polish prompt is a static literal (no user-controlled interpolation — XSS-safe by construction, same shape as the delete `confirm()`).

#### 4. `EventRepositoryTest` — new finder cases

**File**: `src/test/java/com/example/app/event/EventRepositoryTest.java` (extend)

**Intent**: Pin the `findByIdAndUser` user-scoping contract.

**Contract**: Add three `@DataJpaTest` cases:
- `findByIdAndUserReturnsEventForMatchingUser` — persist event for alice; `findByIdAndUser(event.getId(), alice)` is present.
- `findByIdAndUserReturnsEmptyForForeignUser` — persist event for alice; `findByIdAndUser(event.getId(), bob)` is empty.
- `findByIdAndUserReturnsEmptyForUnknownId` — `findByIdAndUser(UUID.randomUUID(), alice)` is empty.

#### 5. `EventControllerTest` — edit endpoint cases (incl. pinpoint test)

**File**: `src/test/java/com/example/app/event/EventControllerTest.java` (extend)

**Intent**: Cover GET render, POST happy path, validation re-render, every 404 surface (foreign user, past event, unknown id), the no-CSRF 403, and **the edit-to-past pinpoint test** that locks the Q6 decision.

**Contract**: Add MockMvc methods (all using `.with(user("alice@example.com"))` + `.with(csrf())` per existing pattern; seed fixtures with the existing `eventRepository.save(...)` style):

- `anonymousGetEventEditRedirectsToLogin` — GET → 3xx to `/login`.
- `authenticatedGetEventEditRendersPrefilledForm` — seed event for alice; GET → 200; content contains the event title, `th:action` URL with `id`, `min` attribute on date input.
- `editFormCarriesPastDateSoftWarnOnsubmit` — seed event for alice; GET → 200; response body contains both `onsubmit="return this.eventDate.value >=` AND `confirm('Data jest w przeszłości — kontynuować?')` (asserts the symmetric soft-warn is wired up; pairs with the create-form assertion in Phase 2 §6).
- `getEventEditForForeignUserReturns404` — seed event for bob; GET as alice → 404.
- `getEventEditForPastEventReturns404` — seed event with `eventDate = yesterday`; GET as alice → 404 (URL-guess guard).
- `getEventEditForUnknownIdReturns404` — GET random UUID as alice → 404.
- `postEventUpdateHappyPathRedirectsToAppWithFlash` — seed event for alice; POST with updated title + same date → 302 to `/app`; flash `successMessage` equals `"Zapisano zmiany w wydarzeniu „<new title>”."`; `eventRepository.findById(id)` shows the updated title.
- `postEventUpdateBlankTitleRendersFieldError` — POST blank title → 200; re-renders `events/edit` with `field-error` for title; `eventId` model attribute preserved.
- `postEventUpdateForForeignUserReturns404` — POST as alice for bob's event → 404.
- `postEventUpdateForPastEventReturns404` — POST as alice for past-dated own event → 404.
- `postEventUpdateWithoutCsrfIs403` — POST without `.with(csrf())` → 403.
- **`postEventUpdateMovingDateToPastReturnsSuccessAndRowVanishesFromApp` (the pinpoint contract test)** — seed event for alice with `eventDate = tomorrow`; POST with `eventDate = yesterday` + same title + csrf → 302 + flash; follow-up GET `/app` as alice → 200; assert the response body does NOT contain the event title. **The test exists to make the Q6 decision visible — adding `@FutureOrPresent` to `EventForm` later turns this red, forcing the future reviewer to read the decision.**

### Success Criteria:

#### Automated Verification:

- `./gradlew test --tests com.example.app.event.EventRepositoryTest` passes (existing + 3 new cases).
- `./gradlew test --tests com.example.app.event.EventControllerTest` passes (existing 10 cases + 12 new cases for edit — 11 endpoint cases + the `editFormCarriesPastDateSoftWarnOnsubmit` rendering assertion).
- `./gradlew test` keeps all S-01 / S-02 / S-04 / S-05 tests green.
- `./gradlew build` succeeds.

#### Manual Verification:

- `./gradlew bootRun`, log in, manually create an event via `/events/new`, return to `/app`, click "Edytuj" (which won't exist until Phase 2 — for this phase, navigate to `/events/{id}/edit` by URL), see the prefilled form, change the title, submit; lands on `/app` with the green flash `"Zapisano zmiany w wydarzeniu „<new title>”."` and the row reflecting the new title.
- On `/events/{id}/edit`, change the date to yesterday and submit: browser-native confirm() dialog opens with `"Data jest w przeszłości — kontynuować?"`. Cancel keeps the form intact; OK proceeds to the documented server contract (302 + flash + row vanishes from `/app`).
- Navigating to `/events/{random-uuid}/edit` returns 404.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human before proceeding to Phase 2.

---

## Phase 2: Delete endpoint + `/app` row affordances + symmetric browser-`min` + iCal propagation tests

### Overview

Add the `POST /events/{id}/delete` handler reusing the Phase 1 guard helper. Wire the row affordances on `/app` (Edytuj link + Usuń form with `data-title` `confirm()` + CSS). Apply the same `min=` browser-side floor to `events/new.html` for create/edit symmetry. Pin the iCalendar propagation contract with two new `CalendarControllerTest` cases (edited title propagates; UID stays stable).

### Changes Required:

#### 1. `EventController` — add delete endpoint

**File**: `src/main/java/com/example/app/event/EventController.java` (modify)

**Intent**: One more handler reusing the Phase 1 `findOwnUpcomingEvent` helper. Captures the title before delete to embed in the flash.

**Contract**: `@PostMapping("/events/{id}/delete") String delete(@PathVariable UUID id, Authentication auth, RedirectAttributes ra)` — looks up `AppUser`, calls `findOwnUpcomingEvent`, captures `title = event.getTitle()`, calls `eventRepository.delete(event)`, sets `ra.addFlashAttribute("successMessage", "Usunięto wydarzenie „" + title + "”.")`, returns `"redirect:/app"`. **Annotate `delete()` with `@Transactional`** — `eventRepository.delete(event)` requires an active write transaction (same reason as `update()` in Phase 1 §2: `EventController` is not class-level `@Transactional`; OSIV covers reads only).

#### 2. `EventController` — pass `minDate` to the `show()` (create) handler

**File**: `src/main/java/com/example/app/event/EventController.java` (modify)

**Intent**: Symmetric browser-side `min` floor on the create form, paired with the edit form's `min` from Phase 1. Server-side `EventForm` validation is untouched (no asymmetry between create and edit on the backend).

**Contract**: `show()` adds `model.addAttribute("minDate", LocalDate.now(clock).minusYears(5).toString())` AND `model.addAttribute("todayIso", LocalDate.now(clock).toString())` before returning `"events/new"`. The validation-failure branch of `create()` does the same. `todayIso` feeds the symmetric past-date soft-warn `onsubmit` added on `events/new.html` in §3.

#### 3. `events/new.html` — add `min` attribute on date input

**File**: `src/main/resources/templates/events/new.html` (modify)

**Intent**: Browser rejects "1925-01-15" at form submission; server accepts every date.

**Contract**: Change `<input type="date" th:field="*{eventDate}" required/>` to `<input type="date" th:field="*{eventDate}" th:attr="min=${minDate}" required/>`. Add the matching past-date soft-warn on the surrounding `<form>`, identical to Phase 1 §3 on `events/edit.html`: `th:attr="onsubmit=|return this.eventDate.value >= '${todayIso}' || confirm('Data jest w przeszłości — kontynuować?');|"`. Symmetric across create + edit by design (the validation-symmetry rule the slice defends).

#### 4. `app.html` — row affordances per event

**File**: `src/main/resources/templates/app.html` (modify)

**Intent**: Add Edytuj (link) + Usuń (form + button + native `confirm()`) per event row, with the `data-title` XSS-safe pattern. Preserve the existing empty-state when zero upcoming events.

**Contract**: Inside each `<li th:each="e : ${events}">`, after the existing `.event-when` / title / requirements / notes blocks, append:

```html
<div class="row-actions">
    <a th:href="@{/events/{id}/edit(id=${e.id})}" class="row-action">Edytuj</a>
    <form th:action="@{/events/{id}/delete(id=${e.id})}" method="post"
          class="row-action-form"
          th:attr="data-title=${e.title}"
          onsubmit="return confirm('Usunąć wydarzenie „' + this.dataset.title + '"?')">
        <button type="submit" class="row-action row-action--danger">Usuń</button>
    </form>
</div>
```

CSRF is auto-injected on the `<form th:action="…" method="post">` by Spring Security + Thymeleaf — no manual hidden input. Verify `SecurityConfig` has not disabled CSRF (S-05 didn't touch it; the carve-out for `/calendar/{token}.ics` is a path-allow, not a CSRF disable). The `confirm()` reads `this.dataset.title` (DOM-decoded value), never inline-interpolating the title into a JS literal.

#### 5. `templates/fragments/layout.html` — row-action CSS

**File**: `src/main/resources/templates/fragments/layout.html` (modify)

**Intent**: Tap-friendly, non-alarming affordance pair: 44×44 min tap area, 8–12 px gap between the two actions, danger style is a red text + red border on white (not a solid red fill). Empty-state visual integrity preserved (flash + empty-state must coexist without collision).

**Contract**: Append to the inline `<style>` block (after the `.event-list` rules):

```css
.event-list .row-actions {
    display: flex; gap: 0.75rem; margin-top: 0.75rem; align-items: center;
}
.event-list .row-action-form { margin: 0; }
.row-action {
    display: inline-flex; align-items: center; justify-content: center;
    min-width: 44px; min-height: 44px; padding: 0.5rem 0.9rem;
    border: 1px solid #d0d7de; border-radius: 6px;
    background: #ffffff; color: #1f2328;
    text-decoration: none; font-size: 0.9375rem; cursor: pointer;
}
.row-action--danger {
    border-color: #cf222e; color: #cf222e;
}
.row-action--danger:hover { background: #fff5f5; }
```

The danger variant deliberately uses red border + red text on a white background — recognizable as a destructive action without the visual urgency of a solid red CTA. Solid red fill is reserved for nuclear actions ("delete account"); this is a daily, recoverable-via-re-entry mutation.

#### 6. `EventControllerTest` — delete cases + app.html affordance rendering test

**File**: `src/test/java/com/example/app/event/EventControllerTest.java` (extend)

**Intent**: Pin the delete contract end-to-end (happy, 404 variants, 403 no-CSRF) and the visual contract (both affordances render on the rows).

**Contract**: Add MockMvc cases:

- `postEventDeleteHappyPathRedirectsToAppWithFlash` — seed event for alice; POST `.with(csrf())` → 302 to `/app`; flash `successMessage` equals `"Usunięto wydarzenie „<title>”."`; `eventRepository.findById(id)` is empty.
- `postEventDeleteForForeignUserReturns404` — seed event for bob; POST as alice → 404; bob's event still exists.
- `postEventDeleteForPastEventReturns404` — seed event with `eventDate = yesterday`; POST as alice → 404; event still exists.
- `postEventDeleteWithoutCsrfIs403` — POST without `.with(csrf())` → 403.
- `appShowsEditAndDeleteAffordancesPerRow` — seed two events for alice; GET `/app` → 200; assert response body contains `Edytuj` AND `Usuń` AND `action="/events/<id1>/delete"` AND `action="/events/<id2>/delete"` AND `href="/events/<id1>/edit"` AND `href="/events/<id2>/edit"`. (Visual integrity gets a manual check; this asserts structural presence.)
- `createFormCarriesPastDateSoftWarnOnsubmit` — GET `/events/new` → 200; response body contains both `onsubmit="return this.eventDate.value >=` AND `confirm('Data jest w przeszłości — kontynuować?')` (symmetric counterpart to Phase 1 §5's `editFormCarriesPastDateSoftWarnOnsubmit`; if either side is missing the soft-warn, exactly one of these two tests goes red, pinpointing the asymmetry).

#### 7. `CalendarControllerTest` — two iCal propagation tests

**File**: `src/test/java/com/example/app/event/CalendarControllerTest.java` (extend)

**Intent**: Lock the iCalendar-feed propagation contract for edit. Two separate tests so a UID regression and a title regression each surface with their own message.

**Contract**: Add two cases (reuse the existing per-user feed-fetch helper pattern):

- **Test A — `editedTitlePropagatesToFeed`**: seed event for alice with title "Wycieczka A"; GET `/calendar/{alice.token}.ics` → assert body contains `SUMMARY:Wycieczka A`. Then issue `POST /events/{id}` `.with(user("alice@example.com"))` `.with(csrf())` posting `eventDate`, `eventTime`, `title="Wycieczka B"`, `requirements`, `notes` (same field stack as `EventForm`). Assert **both** legs of the chain in the same test, distinct assertion messages so a failure pinpoints which side regressed: (a) **Controller→update leg** — the POST returns 302 with `Location: /app` AND flash `successMessage` equals `"Zapisano zmiany w wydarzeniu „Wycieczka B”."` (pinned by `MockMvcResultMatchers.flash().attribute(...)`); (b) **Persisted-state→feed leg** — follow-up GET `/calendar/{alice.token}.ics` body contains `SUMMARY:Wycieczka B` AND does NOT contain `SUMMARY:Wycieczka A`. The repo-direct alternative is **explicitly dropped**: it would only prove `IcalFeedWriter` re-reads on each render (already true since S-04 — no cache) and would silently pass a refactor that bypasses the controller-side `setTitle` while leaving a repo write intact. Two assertion chains, two regression surfaces, one test.
- **Test B — `editPreservesUidInFeed`**: seed event for alice (title "X"); GET feed; capture the `UID:<event.id>@ogarniacz.fly.dev` line into a variable. Update event title to "Y" via the repository; GET feed again; capture the UID line again; assert the two UID strings are equal (same `event.id`, same `@ogarniacz.fly.dev` suffix). Use grep-style extraction: `body.lines().filter(l -> l.startsWith("UID:")).findFirst()`. Two distinct assertion messages so a failure pinpoints "title didn't propagate" vs "UID changed".

### Success Criteria:

#### Automated Verification:

- `./gradlew test --tests com.example.app.event.EventControllerTest` passes (Phase 1's cases + 6 new cases — 5 delete-related + the `createFormCarriesPastDateSoftWarnOnsubmit` symmetric rendering assertion).
- `./gradlew test --tests com.example.app.event.CalendarControllerTest` passes (existing 12 cases + 2 new propagation tests).
- `./gradlew test` keeps all S-01 / S-02 / S-04 / S-05 tests green.
- `./gradlew build` succeeds.

#### Manual Verification:

- `./gradlew bootRun`, log in, create a couple of test events; on `/app`, each row shows **Edytuj** (link, white background, blue text per `.row-action` defaults) next to **Usuń** (red border, red text, white background — not alarming). Visual gap between them is comfortable (≥ 8 px); both are tappable on a phone-sized window.
- Click **Edytuj**: navigates to `/events/{id}/edit` with the form prefilled; **Anuluj** returns to `/app` without saving; submitting saves and lands on `/app` with green flash.
- Click **Usuń**: browser-native `confirm()` opens with the literal title in Polish ("Usunąć wydarzenie „<title>"?"); cancelling closes the dialog with no request fired; confirming POSTs and lands on `/app` with the success flash.
- Delete the last event on `/app`: empty-state ("No events yet. Add one above.") renders concurrently with the green flash; flash sits above the list area, empty-state in the list area — no visual overlap.
- In a second browser tab, delete an event; in the first tab, submit an edit form for that same event: response is 404 (URL-guess guard hit on the missing event), not a stale-data success.
- Subscribe the iCal feed in Apple Calendar (or refresh the existing subscription); edit an event's title; within the next poll, the calendar entry's summary updates in place (no duplicate event).

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation before proceeding to Phase 3.

---

## Phase 3: Closeout — `lessons.md`, test-plan cookbook backfill, roadmap sync, `change.md` flip

### Overview

Lock the two non-code outputs of the slice — the validation-symmetry lesson and the edit/delete lifecycle test pattern — and synchronize the project's status surfaces (`change.md`, `roadmap.md`). Mirrors the closeout pattern used by S-04 (commit `6edb0eb docs(icalendar-feed-and-subscription): test-plan section 6.5 cookbook backfill (p4)`) and S-05 (`90ddd7a chore(image-extraction-and-review-acceptance): close out plan (epilogue)`).

### Changes Required:

#### 1. `lessons.md` — validation symmetry across create + edit

**File**: `context/foundation/lessons.md` (modify, append-only)

**Intent**: Record the Q6 decision so a future reviewer doesn't propose "let's just add `@FutureOrPresent` to the edit form" without seeing the rationale.

**Contract**: Append a new H2 section after the last existing lesson, following the established format (Context / Problem / Rule / Applies to bullets):

- **Heading**: `## Validation rules on a shared form DTO must stay symmetric across create + edit; soft-warn on the edge case via a separate slice`
- **Context**: when adding edit endpoints that reuse a create-form DTO (`EventForm` here; the pattern recurs anywhere a multi-step CRUD reuses one Bean Validation DTO).
- **Problem**: the natural reaction to "edit can move `eventDate` to the past, and the URL-guess guard then blocks re-access" is to add `@FutureOrPresent` only on the edit handler (via a custom `BindingResult` rejection or a forked `EventEditForm`). The asymmetry hides itself: create still accepts past dates ("parents legitimately catch up retroactively" — S-02), but edit suddenly rejects them. A user who edits the time of a past-dated event (legitimate use case for the rare retroactive entry) is blocked, and the inconsistency surfaces as a "weird bug" rather than a deliberate rule.
- **Rule**: when an edit endpoint binds the same DTO as create, validation rules on date / state / domain fields MUST stay symmetric across both endpoints. The mitigation for "edit to past date" typos is a **soft-warn** (`"Data jest w przeszłości — kontynuować?"`) that lands on BOTH endpoints simultaneously — never `@FutureOrPresent` on one side. A browser-side `min=` attribute is an acceptable client-side floor on both endpoints because the server still accepts every date — no backend asymmetry. **Precedent landed in S-03:** the `onsubmit` confirm() in `events/new.html` and `events/edit.html` is symmetric by construction, pinned by two MockMvc assertions (`editFormCarriesPastDateSoftWarnOnsubmit` + `createFormCarriesPastDateSoftWarnOnsubmit`) so the day one form loses the soft-warn, exactly one test goes red and pinpoints the asymmetry. A residual gap remains (user confirms the dialog → row vanishes per the documented server contract); if that residual surfaces as a real-world incident, address it in a separate `past-events-management` slice — do NOT patch by adding `@FutureOrPresent` to one side.
- **Applies to**: plan-review, implement, impl-review (whenever a plan introduces edit/delete on an entity whose create form was deliberately permissive).

#### 2. `test-plan.md` §6 — cookbook backfill for the edit/delete lifecycle

**File**: `context/foundation/test-plan.md` (modify)

**Intent**: Add a §6.7 or §6.8 cookbook entry (next available number after the existing `### 6.6 Adding a test for the image-extraction + review flow (S-05)` block) documenting the test pattern S-03 just landed: `findByIdAndUser` repo case, MockMvc `EventControllerTest` cases for the four 404-eligible surfaces (foreign user, past event, unknown id, no-CSRF), the edit-to-past pinpoint test, and the two `CalendarControllerTest` propagation tests (Test A: title propagates; Test B: UID stable). Pattern entry is ~30 lines, modeled on `### 6.5 Adding an iCal feed test`.

**Contract**: Insert a new `### 6.X Adding a test for the accepted-event edit/delete lifecycle (S-03)` subsection. Body bullets describe (a) the test slice convention used (`@SpringBootTest @AutoConfigureMockMvc` for controllers; `@DataJpaTest` for repo) with the `llm-chatclient-fail-fast` tension acknowledged; (b) the 404-vs-403 contract on foreign / past / unknown id; (c) the CSRF assertion; (d) the edit-to-past pinpoint pattern (link to the rationale lesson added in §1 above); (e) the two-test propagation pattern for iCal updates (one test per failure mode); (f) a one-line "do not split the test class — extend the existing per-controller `EventControllerTest`" reminder.

#### 3. `roadmap.md` — flip S-03 status in §At a glance, §Slices, §Done

**File**: `context/foundation/roadmap.md` (modify)

**Intent**: Keep the roadmap as the single source of truth for "what ships next" — `/10x-archive` does this automatically when the change is archived, but the closeout commit lands BEFORE archival, so a manual sync prevents a stale roadmap during the review window.

**Contract**: Find the S-03 entry in (a) the §At a glance table (flip status column to `done`), (b) the §Slices section (flip the slice's status indicator and add the completion date `2026-06-21`), (c) the §Done section (append S-03 with one-line summary "Edit + delete of accepted events from `/app`; iCal propagation locked"). Three small edits, all additive except the status flips.

#### 4. `change.md` — flip status to `done`

**File**: `context/changes/edit-delete-accepted-events/change.md` (modify)

**Intent**: Mark the change closed in its identity file (`/10x-archive` runs a separate move, but the status flip happens at closeout).

**Contract**: Set `status: done` in the YAML frontmatter; set `updated: 2026-06-21`.

### Success Criteria:

#### Automated Verification:

- `./gradlew test` passes (no code changes in this phase; verifying the closeout didn't break anything).
- `./gradlew build` succeeds.
- Markdown lint of `lessons.md`, `test-plan.md`, `roadmap.md`, `change.md` succeeds (or, if no linter is wired, manual eyeball pass: no stray Markdown errors, consistent heading levels).

#### Manual Verification:

- `cat context/foundation/lessons.md` — new section appears at the bottom in the established format.
- `cat context/foundation/test-plan.md | grep -n "^### 6"` — new §6.X subsection is in numerical order; `### 6.7 Per-rollout-phase notes` was at line 289 in the pre-S-03 file (may shift), the new subsection precedes it or follows the existing S-XX-keyed pattern.
- `cat context/foundation/roadmap.md | grep -A 1 "S-03"` — every mention of S-03 reflects `done` status with the completion date.
- `cat context/changes/edit-delete-accepted-events/change.md` — `status: done`, `updated: 2026-06-21`.

**Implementation Note**: This phase has no manual UI verification — it's a documentation-and-state phase. After committing the closeout, run `/10x-archive edit-delete-accepted-events` (separate skill) to move the change folder to `context/archive/`.

---

## Testing Strategy

### Unit Tests:

- `EventReminderTest` — UNCHANGED (S-02's existing DST-aware unit tests still pass after S-03 lands).

### Integration Tests:

- `EventRepositoryTest` (`@DataJpaTest`) — new `findByIdAndUser` cases (matching, foreign user, unknown id).
- `EventControllerTest` (`@SpringBootTest @AutoConfigureMockMvc`) — extends with edit + delete cases (described in Phase 1 §5 and Phase 2 §6).
- `CalendarControllerTest` (`@SpringBootTest @AutoConfigureMockMvc`) — extends with two new propagation tests (Phase 2 §7).

### Manual Testing Steps:

1. Create two events via `/events/new` with distinct titles and one date in the next week each.
2. From `/app`, click **Edytuj** on the first event: form is prefilled; change the title; submit; lands on `/app` with green Polish flash, new title visible in the row, second event unchanged.
3. From `/app`, click **Anuluj** in edit form: lands on `/app` without persisting, original title intact.
4. From `/app`, click **Usuń** on the second event: native `confirm()` opens with the literal title; click Cancel — nothing happens; click OK — row disappears, green flash appears.
5. Delete the remaining event: row vanishes, empty-state ("No events yet. Add one above.") renders alongside the green flash without visual overlap.
6. Try direct-URL editing a past-dated event (seed via DB or via `eventDate = yesterday` POST `/events`): `/events/{id}/edit` returns 404.
7. Subscribe the iCal feed in Apple Calendar (or another non-Google client); add an event, accept the polling update; edit the title in Ogarniacz; verify the calendar entry updates in place (UID stable → same VEVENT, new summary) on the next poll.
8. Delete an event: verify the VEVENT disappears from the next poll's calendar.

## Performance Considerations

- `findByIdAndUser` runs as a single indexed query (the `Event` table has `ix_app_event_user_date` on `(user_id, event_date)`; the PK index covers `id`). The `eventDate >= today` filter is applied in-memory on a single fetched row — negligible cost.
- `delete()` on a single-row entity is O(1) at this scale; the iCalendar feed regenerates from scratch on every poll (no cache to invalidate) so propagation is immediate at the next client poll.
- `app.html` adds one `<div class="row-actions">` per row with two children — DOM cost is linear in the existing event count, but the upcoming-only filter keeps the rendered list small for the single-user MVP.

## Migration Notes

No schema migration. No data migration. `ddl-auto=update` adds nothing (no new columns or tables). No backfill required.

## References

- Roadmap slice S-03 (the entry this plan delivers): `context/foundation/roadmap.md`.
- PRD FR-010 / FR-011 / US-01 acceptance #4: `context/foundation/prd.md`.
- Mirrored test patterns: `src/test/java/com/example/app/event/EventControllerTest.java`, `src/test/java/com/example/app/event/CalendarControllerTest.java`.
- Precedent for `findByIdAndUser`: `src/main/java/com/example/app/event/SourceImageRepository.java`.
- Precedent for 404-on-foreign-id: `src/main/java/com/example/app/event/EventReviewService.java` + `EventReviewServiceTest.crossUserIsolation`.
- Phase 0 UID-stability finding: `src/main/java/com/example/app/event/IcalFeedWriter.java:89` — `event.getId() + "@" + UID_HOST`, UUID PK immutable across updates.
- Test slice tension: `context/changes/llm-chatclient-fail-fast/` (the in-flight change-id tracking the OpenRouter null-ChatClient init issue).
- S-02 plan (the entity + form S-03 reuses): `context/archive/2026-06-07-manual-event-entry/plan.md`.
- S-04 plan (iCalendar feed S-03 propagates through): `context/archive/2026-06-15-icalendar-feed-and-subscription/plan.md`.
- S-05 plan (the immediate predecessor; review/accept flow): `context/archive/2026-06-16-image-extraction-and-review-acceptance/plan.md`.

## Implementer notes (drift caught during plan review)

Four small items the plan-review surfaced. Each is a 5-second fix once spotted; flagging them up-front so the implementer doesn't spend 2 minutes triangulating.

- **Baseline test counts** — verified against the actual files: `EventControllerTest` has **10** existing `@Test` cases (not 9), `CalendarControllerTest` has **12** (not 11). All "existing N cases + M new cases" totals in Success Criteria and Progress already corrected. Implementer should re-grep before committing in case other slices land in parallel.
- **`EventController` constructor signature** — injecting `Clock` adds a third constructor parameter. Existing signature: `(EventRepository, AppUserRepository)`. New signature: `(EventRepository, AppUserRepository, Clock)`. `Clock` is already a registered bean (used by `AppController` — see "Current State Analysis" + Phase 1 §2 note); no new `@Bean` registration needed. Tests requiring deterministic time inject a fixed `Clock.fixed(Instant.parse("2026-06-21T10:00:00Z"), ZoneId.of("Europe/Warsaw"))` via `@TestConfiguration` override, the same pattern `AppControllerTest` already uses for its date-sensitive cases.
- **`Event` entity setter style** — `Event.java` currently has constructor + getters only (no setters); update handler needs setters. **Mirror the constructor's `Objects.requireNonNull` guards on the non-null fields** (`user`, `eventDate`, `title`) so the setters can't silently introduce nullability the constructor disallows. `eventTime`, `requirements`, `notes` are nullable per `EventForm`'s validation shape — their setters do not need `requireNonNull`.
- **Test fixture email pattern** — existing `EventControllerTest` cases use **per-scenario** emails (`alice-happy@example.com`, `alice-past@example.com`, …), not a shared `alice@example.com`. `@SpringBootTest` shares the application context across cases; a shared email would collide on the `app_user` email-uniqueness constraint between scenarios. New cases follow the same per-scenario convention (`alice-edit-happy@example.com`, `alice-edit-foreign@example.com`, `alice-edit-past@example.com`, `alice-edit-csrf@example.com`, `alice-delete-happy@example.com`, …). All `.with(user("alice@example.com"))` references in this plan's Phase 1 §5 / Phase 2 §6 must be expanded to the per-scenario shape during implementation.

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Repository finder + edit endpoint + edit form + edit-to-past pinpoint test

#### Automated

- [x] 1.1 `./gradlew test --tests com.example.app.event.EventRepositoryTest` passes (existing + 3 new cases) — 882474f
- [x] 1.2 `./gradlew test --tests com.example.app.event.EventControllerTest` passes (existing 10 cases + 12 new cases for edit) — 882474f
- [x] 1.3 `./gradlew test` keeps all S-01 / S-02 / S-04 / S-05 tests green — 882474f
- [x] 1.4 `./gradlew build` succeeds — 882474f

#### Manual

- [x] 1.5 `./gradlew bootRun`, log in, navigate directly to `/events/{id}/edit` for an existing event, see prefilled form, change title, submit, land on `/app` with green flash and updated row — 882474f
- [x] 1.6 Navigating to `/events/{random-uuid}/edit` returns 404 — 882474f

### Phase 2: Delete endpoint + `/app` row affordances + symmetric browser-`min` + iCal propagation tests

#### Automated

- [x] 2.1 `./gradlew test --tests com.example.app.event.EventControllerTest` passes (Phase 1's cases + 6 new cases — 5 delete-related + 1 symmetric soft-warn assertion) — 8d04691
- [x] 2.2 `./gradlew test --tests com.example.app.event.CalendarControllerTest` passes (existing 12 cases + 2 new propagation tests) — 8d04691
- [x] 2.3 `./gradlew test` keeps all S-01 / S-02 / S-04 / S-05 tests green — 8d04691
- [x] 2.4 `./gradlew build` succeeds — 8d04691

#### Manual

- [x] 2.5 On `/app`, each row shows **Edytuj** + **Usuń** with comfortable spacing and tap-friendly hit areas — 8d04691
- [x] 2.6 **Edytuj** → prefilled form; **Anuluj** returns to `/app` without saving; submit saves and flashes — 8d04691
- [x] 2.7 **Usuń** opens native `confirm()` with the literal Polish title; cancel does nothing; OK deletes and flashes — 8d04691
- [x] 2.8 Delete the last event: empty-state and green flash coexist without visual overlap — 8d04691
- [x] 2.9 Two-tab race: delete in tab B, submit edit for same event in tab A → 404 (URL-guess guard) — 8d04691
- [x] 2.10 iCal feed: edit title propagates as VEVENT update (same UID, new summary) in a non-Google client; delete removes the VEVENT entirely on the next poll — 8d04691

### Phase 3: Closeout — `lessons.md`, test-plan cookbook backfill, roadmap sync, `change.md` flip

#### Automated

- [x] 3.1 `./gradlew test` passes (no code changes; verifying closeout didn't break anything)
- [x] 3.2 `./gradlew build` succeeds

#### Manual

- [x] 3.3 `lessons.md` shows the new "Validation rules on a shared form DTO must stay symmetric…" section at the end
- [x] 3.4 `test-plan.md` §6.X cookbook entry exists in numerical order
- [x] 3.5 `roadmap.md` shows S-03 as `done` in §At a glance, §Slices, §Done (with completion date 2026-06-21)
- [x] 3.6 `change.md` shows `status: done` and `updated: 2026-06-21`
