# Edit / Delete Accepted Events (S-03) — Plan Brief

> Full plan: `context/changes/edit-delete-accepted-events/plan.md`

## What & Why

Ship roadmap slice **S-03**: a logged-in parent can **edit any field** of an accepted event and **delete** an accepted event from the `/app` personal view, with both mutations propagating to the subscribed iCalendar feed at the next poll. Required by PRD FR-010, FR-011, and US-01 acceptance #4. The slice closes the post-acceptance lifecycle hole noted in S-02's brief and S-05's "out of scope" list — every other path (image extraction, manual entry, calendar subscription) was already shipping; without S-03, a fat-fingered title or a cancelled event is locked in until DB access. After S-03, "details emerge over time" (the FR-010 rationale) and "I accepted a duplicate" (the FR-011 rationale) become two-click recoveries.

## Starting Point

S-01 / S-02 / S-04 / S-05 have landed: the `Event` entity, `EventRepository.findUpcomingByUser`, the manual-create `EventController` (`GET /events/new` + `POST /events`), the iCalendar feed (`IcalFeedWriter` + `CalendarController`), and the image-extraction review flow. `EventForm` was explicitly designed in S-02 to be reusable for edit. `EventRepository` has no `findByIdAndUser` yet (`SourceImageRepository` is the precedent). `app.html` renders each event as a flat `<li>` with **zero action affordances**. The iCalendar feed reads the repository directly with no cache or ETag — an in-flight DB write is structurally visible on the next poll (`CalendarControllerTest.deletedEventDisappearsFromNextPoll` already pins this for delete). `IcalFeedWriter.java:89` builds the VEVENT UID from `event.id` — **stable across updates by construction** (Phase 0 finding, verified directly).

## Desired End State

Each row in `/app` shows two inline actions: **Edytuj** (link to `/events/{id}/edit`) and **Usuń** (CSRF-protected POST form with a button that opens a browser-native `confirm()` naming the event title via the XSS-safe `data-title` DOM-attribute pattern). The edit form mirrors `/events/new` with prefilled values and a Cancel link; submitting redirects to `/app` with the Polish flash `"Zapisano zmiany w wydarzeniu „<title>"."`. Confirming Usuń hard-deletes the row and redirects with `"Usunięto wydarzenie „<title>"."`. Both mutations propagate to the iCalendar feed: edits are rendered as VEVENT updates in place (stable UID), deletes drop the VEVENT entirely. Foreign-user and past-event URLs return 404; missing CSRF returns 403; both behaviors are pinned by MockMvc tests, including a dedicated **edit-to-past pinpoint test** that locks the deliberate validation-symmetry decision.

## Key Decisions Made

| Decision                                    | Choice                                                                                                        | Why (1 sentence)                                                                                                                                                                                                                       |
| ------------------------------------------- | ------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| HTTP shape                                  | POST-based routes (`GET /events/{id}/edit`, `POST /events/{id}`, `POST /events/{id}/delete`)                  | No `HiddenHttpMethodFilter` in the codebase; matches the existing `POST /events` + `POST /events/from-image/{id}/decisions` pattern; same CSRF auto-injection.                                                                          |
| Controller location                         | Extend existing `EventController` (not a new `EventEditController`)                                            | Spring MVC convention: one controller per resource, not per verb; avoids splitting `EventForm` binding across two classes and `@WebMvcTest` slices over the same resource.                                                              |
| Delete affordance shape                     | `<form method="post"><button>Usuń</button></form>` (NEVER `<a>` link)                                          | `<form>` carries CSRF; browsers prefetchers/crawlers will GET-fire `<a>` links — non-removable security floor.                                                                                                                          |
| Delete confirmation                         | Inline `<form>` + `onsubmit="return confirm(...)"` with `data-title` DOM-attribute (Thymeleaf-escaped)         | Native `confirm()` is recognized as a system dialog (heavier than a styled modal — better for a destructive action); `this.dataset.title` reads a DOM-decoded value, no JS interpolation context for XSS.                              |
| Edit / delete scope                         | Upcoming events only — `findUpcomingByUser` + controller-side `eventDate >= today` URL-guess guard            | Single visibility model; past events intentionally unreachable from UI (S-02 already filters `/app`); guard runs in the controller, not relying on UI invisibility for authorization.                                                  |
| 404, not 403, on miss                       | Foreign user / unknown id / past event → 404                                                                  | Mirrors `EventReviewService.applyDecisions` precedent (`crossUserIsolation` test); doesn't leak existence of foreign events via 403 vs 404 distinction.                                                                                  |
| Success redirect                            | PRG → `/app` with `successMessage` flash in Polish (`„title"`)                                                 | Matches S-05 review flow (`successMessage` slot, already rendered by `app.html:7` via auto-escaping `th:text`); refresh-safe; user sees the result in context.                                                                          |
| Edit-to-past validation                     | Allow past dates on edit (same as create — `EventForm` is shared, NO `@FutureOrPresent`)                       | Validation asymmetry between create and edit is worse than the edge case it would close; consequence (row vanishes from `/app`, ghost in DB) is explicitly recorded as a known limitation and pinned by a dedicated test.              |
| Browser-side `min=` floor                   | Add `min="<today-5y>"` to both `new.html` and `edit.html` (symmetric, server unchanged)                        | Catches typo'd 1925-style dates at the browser level; preserves backend validation symmetry; one-line touch per template; zero asymmetry.                                                                                              |
| UID stability under edit                    | Stable by construction — `event.id` is the JPA-managed UUID PK, immutable across updates                       | Phase 0 research finding at `IcalFeedWriter.java:89`; no fix in Phase 2, only a UID-stability test (Test B) alongside the title-propagation test (Test A) — two tests, two failure-mode-specific assertion messages.                    |
| List UI shape                               | Two inline actions per row, Polish labels (`Edytuj` / `Usuń`), 44×44 tap area, 8–12 px gap, non-alarming danger | Always-visible affordance (no hover on mobile); WCAG min tap target; non-alarming danger styling (red border + red text on white, not solid red fill) — daily mutation, not nuclear.                                                    |
| Hard delete                                 | No soft-delete, no audit log, no `deleted_at`, no `@Version`                                                   | Matches PRD §FR-011 deliberate stance for single-user MVP; future hardening (e.g. soft-delete) lands additively per the deploy-plan additive-only schema contract.                                                                       |
| Test slice                                  | Extend existing `EventControllerTest` (`@SpringBootTest`), `EventRepositoryTest` (`@DataJpaTest`)              | Per-controller-class layout is the project standard (`lessons.md`); the `@SpringBootTest` LLM-init tension (tracked in `llm-chatclient-fail-fast`) is a known shared issue, deferred to a separate refactor if it starts blocking.      |
| Closeout outputs                            | `lessons.md` symmetry rule + `test-plan.md` §6.X cookbook + `roadmap.md` S-03 status flip                       | Project convention from S-04 and S-05 closeout commits; keeps `roadmap.md` accurate during the review window before `/10x-archive` runs.                                                                                                |

## Scope

**In scope:**

- `EventRepository.findByIdAndUser` (mirrors `SourceImageRepository`)
- `EventController` extended with `GET /events/{id}/edit`, `POST /events/{id}`, `POST /events/{id}/delete` + private `findOwnUpcomingEvent` helper
- `events/edit.html` (new) + `events/new.html` (modified — `min` attribute)
- `app.html` row affordances + `templates/fragments/layout.html` CSS additions (`.row-action`, `.row-action--danger`)
- Tests: 3 repo cases, 11 + 5 controller cases (incl. edit-to-past pinpoint), 2 iCal propagation cases (A: title, B: UID stability)
- `lessons.md` validation-symmetry entry, `test-plan.md` §6.X cookbook backfill, `roadmap.md` S-03 status flip, `change.md` `done` flip

**Out of scope:**

- New entity, schema migration, or `Event` field changes
- `EventEditForm` or any `@FutureOrPresent`-only-on-edit asymmetry
- Soft-delete, audit log, edit history, optimistic locking (`@Version`)
- Bulk delete, multi-select, swipe-to-delete, icons
- Past-event recovery surface ("Show past events" toggle, `/events` list view)
- HTMX / JS framework / inline editing
- Revisiting S-04's `SEQUENCE=0` policy
- Source-image purge wiring (S-06 territory)

## Architecture / Approach

Existing entity (`Event`), existing repo (one new method), existing controller (three new handlers + one private helper), existing form DTO (`EventForm` — reused), existing CSS surface (one `<style>` block extended), existing flash slot (`successMessage`), existing iCal serialization (UNCHANGED — UID is already stable by construction). The slice is structurally a **paved-path CRUD extension** on top of S-02's foundation, with the only architectural novelty being the explicit `eventDate >= today` URL-guess guard in the controller (one line in a private helper, reused by three handlers).

```
GET  /events/{id}/edit      ─▶  EventController.edit()    ─▶ events/edit.html
POST /events/{id}            ─▶  EventController.update()  ─▶ 302 /app + flash
POST /events/{id}/delete     ─▶  EventController.delete()  ─▶ 302 /app + flash

GET /calendar/{token}.ics    ─▶  UNCHANGED — UID stable from event.id, no cache,
                                  edit ⇒ VEVENT update in place; delete ⇒ VEVENT drops
```

## Phases at a Glance

| Phase                                                                                       | What it delivers                                                                                                                                                       | Key risk                                                                                                                                                            |
| ------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1. Repo finder + edit endpoint + edit form + edit-to-past pinpoint test                     | `findByIdAndUser`, `findOwnUpcomingEvent` helper, GET/POST edit handlers, `events/edit.html` with Cancel, 14 new tests (3 repo + 11 controller incl. pinpoint contract) | `findByIdAndUser` user-scoping must hold under cross-user fixtures; pinpoint test must reach the actual contract (response body of follow-up GET `/app`, not just flash) |
| 2. Delete endpoint + `/app` row affordances + symmetric browser-`min` + iCal propagation    | POST delete handler, `app.html` row actions with `data-title` XSS-safe `confirm()`, CSS, `new.html` `min` parity, 5 new controller tests + 2 new `CalendarControllerTest` propagation cases (A + B) | XSS via inline JS string concatenation (mitigated by `dataset.title`); `confirm()` UX must be tap-friendly on mobile (44×44 + 8 px gap); UID test must extract the UID line cleanly to give a distinct failure message |
| 3. Closeout — `lessons.md`, `test-plan.md` §6 cookbook, `roadmap.md` S-03 flip, `change.md` `done` | New lesson on cross-endpoint validation symmetry; new cookbook subsection; roadmap and change.md status flipped to `done`                                              | No code risk — only the discipline of ensuring future reviewers see the validation-symmetry decision in `lessons.md` before they propose `@FutureOrPresent`           |

**Prerequisites:** S-01, S-02, S-04, S-05 done (all archived); local Postgres reachable via `SPRING_DATASOURCE_*`; an existing logged-in account with at least one upcoming event (or one created during manual verification).

**Estimated effort:** 2–3 evening sessions across 3 phases. Bulk is Phase 1's edit controller + 11 MockMvc cases; Phase 2 is mostly template + CSS + 7 new test cases; Phase 3 is documentation + state sync.

## Open Risks & Assumptions

- **Edit-to-past data ghost** — by deliberate decision, an edit moving `eventDate` to the past leaves an unreachable row in the DB (visible neither in `/app` nor in the iCal feed, since both filter `eventDate >= today`). Recovery requires DB access or a future `past-events-management` slice. Recorded as a known limitation in `lessons.md`; the pinpoint test locks the contract so any future reviewer adding `@FutureOrPresent` triggers the test and reads the decision.
- **LLM init coupling in tests** — `@SpringBootTest` boots `OpenRouterLlmVisionClient`; the in-flight `llm-chatclient-fail-fast` issue may surface here. Decision: accept the coupling for layout consistency; refactor to `@WebMvcTest` is a separate slice if the LLM-init flake starts blocking CI.
- **Two-tab edit-delete race** — if tab A is editing while tab B deletes the same event, tab A's POST hits 404 from `findByIdAndUser` (returns empty). No special "event no longer exists" flash; 404 page is the recovery surface. Conscious decision for single-user MVP scale.
- **Google Calendar polling lag** — edits propagate via the next poll (S-04 known limitation, up to 24–48 h for Google Calendar). Apple Calendar / Outlook / Thunderbird honor `REFRESH-INTERVAL`; Google Calendar ignores it. Manual verification step (#7) deliberately specifies a non-Google client.
- **`SEQUENCE=0` interaction with calendar-client update detection** — some clients use `DTSTAMP` (regenerated each render) rather than `SEQUENCE` to detect updates. S-03 does not touch this S-04 decision; the Test A propagation test verifies the rendered body content, which is the actual user-visible contract regardless of `SEQUENCE` policy.

## Success Criteria (Summary)

- A parent edits an accepted event's title via `/events/{id}/edit`, lands on `/app` with the Polish success flash; subscribed (non-Google) calendar shows the updated VEVENT (same UID, new summary) within one polling cycle.
- A parent deletes an accepted event via the row's **Usuń** button after the native `confirm()` dialog; lands on `/app` with the Polish success flash; subscribed calendar drops the VEVENT entirely within one polling cycle.
- A foreign user's event ID or a past event's URL returns 404 on edit or delete; a POST without CSRF returns 403; both behaviors locked by MockMvc tests, including the edit-to-past pinpoint test that turns red if someone later adds `@FutureOrPresent` to `EventForm`.
