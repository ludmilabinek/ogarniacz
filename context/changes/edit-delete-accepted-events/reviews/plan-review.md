<!-- PLAN-REVIEW-REPORT -->
# Plan Review: Edit / Delete Accepted Events (S-03)

- **Plan**: `context/changes/edit-delete-accepted-events/plan.md`
- **Mode**: Deep
- **Date**: 2026-06-21
- **Verdict**: REVISE → **SOUND after triage (2026-06-21)** — all 5 findings addressed in plan.md
- **Findings**: 0 critical · 2 warnings · 3 observations (all FIXED)

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| End-State Alignment | WARNING |
| Lean Execution | PASS |
| Architectural Fitness | WARNING |
| Blind Spots | PASS |
| Plan Completeness | WARNING |

## Grounding

15/15 paths ✓, 7/7 symbols ✓ (`Event`, `EventRepository`, `EventController`, `EventForm`, `IcalFeedWriter:89`, `SourceImageRepository.findByIdAndUser`, `EventReviewService.applyDecisions`), brief↔plan ✓. Lessons primers loaded; `docs/reference/contract-surfaces.md` absent (silently skipped).

## Findings

### F1 — Edit-to-past data-loss path has no in-scope recovery surface

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: End-State Alignment
- **Location**: Critical Implementation Details §5 ("EventForm reused"), §6 ("Browser-side min= floor"), Phase 1 §5 pinpoint test
- **Detail**: Today is 2026-06-21. Browser `min=` floor is `today.minusYears(5)` = 2021-06-21, deliberately symmetric with `/events/new`. A parent editing "2026-12-25" and fat-fingering "2025-12-25" passes both the browser `min=` and the (intentionally permissive) server-side validation — the success flash fires, the row vanishes from `/app` (filtered by `findUpcomingByUser`), and the URL-guess guard now returns 404 if the user tries to recover via the edit URL. The event still lives in the DB but is invisible from BOTH UI surfaces (the row list AND the iCal feed) and from every endpoint S-03 ships. Recovery requires DB access or a hypothetical `past-events-management` slice that is NOT on the roadmap. From the parent's perspective this is silent data loss on a near-past typo — strictly worse than before edit existed (at least the bad row would still be on `/app`). The plan documents this as a "known limitation" and pins it with a test, but documentation is not mitigation: the user sees a green success flash, not a "row will disappear" warning.
- **Fix A ⭐ Recommended**: Soft-warn on past dates in BOTH templates (one-shot mitigation, kept symmetric)
  - Strength: Preserves the create/edit validation symmetry that the plan defends (no `@FutureOrPresent` asymmetry). Closes the data-loss surface without forking the form DTO. Symmetric client-side warn = no test-surface explosion; the pinpoint test still locks the contract.
  - Tradeoff: Adds ~5 lines of inline JS (one `onsubmit` confirm per form) to both templates; mixes a JS pattern into a slice that otherwise stays JS-free (the `confirm()` for delete is the only precedent — this matches that pattern exactly).
  - Confidence: HIGH — the `confirm()`-on-submit pattern is already coming in for delete; this is the same pattern, same XSS-safe shape.
  - Blind spot: Test surface for the new confirm needs one MockMvc assertion per template; estimate +2 tests.
- **Fix B**: Accept the gap; add a third manual-verification step + lessons.md update
  - Strength: Zero code change; preserves the "test catches future regressions" stance.
  - Tradeoff: The data-loss path is real, not theoretical — first-real-user test will likely surface it within weeks. Deferring the fix costs more than landing it now (have to revisit the symmetry rationale in lessons.md anyway).
  - Confidence: MEDIUM — the deferral is internally consistent with the plan's stated philosophy, but the cost asymmetry is real.
  - Blind spot: No data on how often parents typo year-month-day in date inputs; could be a non-issue at single-user MVP scale.
- **Decision**: FIXED via Fix A — symmetric `onsubmit` confirm() added to both `events/new.html` and `events/edit.html`; `todayIso` model attribute added in EventController.edit/update/show/create branches; two new MockMvc rendering assertions (`editFormCarriesPastDateSoftWarnOnsubmit` + `createFormCarriesPastDateSoftWarnOnsubmit`); test counts updated in Phase 1 §5/Success/Progress and Phase 2 §6/Success/Progress; lessons.md rule reframed from "future slice" to "shipped in S-03 with paired rendering tests".

### F2 — Java string literal for success flashes is malformed (won't compile as written)

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 1 §2 (line 159, update handler), Phase 2 §1 (line 234, delete handler), and indirectly in Phase 1 §5 / Phase 2 §6 test-assertion contracts (lines 195, 310)
- **Detail**: The plan specifies the flash as `ra.addFlashAttribute("successMessage", "Zapisano zmiany w wydarzeniu „" + event.getTitle() + ""."`)`. Bytes verified via hexdump: opening Polish quote is `„` (U+201E, e2 80 9e) inside a Java string literal — fine. But the closing fragment `+ ""."` is an empty string concatenation `""` followed by stray `.` and `"`. Two problems: (1) the trailing `""."` does not parse as Java — `+ ""` is a concat with the empty string, then `.` is an orphan token; (2) the intent is clearly to close with a Polish-style ASCII straight `"` + period, i.e. `+ "\"."` (escaped quote inside the literal) or `+ "”."` (using U+201D right curly quote). The same broken pattern appears in matching `assertEquals` test contracts (Phase 1 §5 line 195 and Phase 2 §6 line 310): if the implementer copy-pastes, both production code and its assertion fail to compile. Once they fix one, they must mirror the choice (escaped ASCII vs U+201D) on the other side or the assertion equality won't hold.
- **Fix**: Replace `+ ""."` with `+ "\"."` (escaped ASCII closing quote) in BOTH handler contracts AND BOTH test assertions; close the `addFlashAttribute` call with the `)` that is currently missing. Symmetric on update and delete.
- **Decision**: FIXED differently — user chose `„ … ”` (U+201E + U+201D, no escape) instead of escaped ASCII. Applied to: Phase 1 §2 update handler (`+ "”.")`), Phase 1 §5 `postEventUpdateHappyPathRedirectsToAppWithFlash` assert contract, Phase 2 §1 delete handler (`+ "”.")`), Phase 2 §6 `postEventDeleteHappyPathRedirectsToAppWithFlash` assert contract; plus prose normalization at lines 21 (Desired End State, both flashes — also fixed Polish typo `wydarzeniu`→`wydarzenie` on the delete side), 80 + 93 (ASCII-art flow), 119 (§5 consequence), 214 (Phase 1 manual verification). All flashes now consistently `„<title>”.` shape, `addFlashAttribute(` calls closed.

### F3 — Test A "title propagates to feed" offers a repo-direct path that bypasses the S-03 contract

- **Severity**: 💡 OBSERVATION
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Architectural Fitness
- **Location**: Phase 2 §7 (Test A — `editedTitlePropagatesToFeed`)
- **Detail**: The contract says "update event title to 'Wycieczka B' via the repository (or via POST /events/{id} + csrf — pick the simpler path)". The two paths are NOT equivalent. Repository-direct only proves `IcalFeedWriter` re-reads the latest DB state on each render — a property that already holds since S-04 (no cache); the test adds zero S-03-specific signal. Via POST /events/{id} + csrf proves the full S-03 chain — controller → `findOwnUpcomingEvent` → field mutation → @Transactional flush → next feed render — actually propagates. This is the contract S-03 is shipping. If a future refactor breaks the controller-side update path but leaves the repository write intact (e.g. someone "optimizes" by skipping the `setTitle` call), the repo-direct version of Test A passes; the via-POST version fails.
- **Fix**: Require Test A to use POST /events/{id} + csrf (drop the "or via repository" alternative). Two lines of test setup vs. one — the "simplicity" cost is trivial and the signal value is the whole point.
- **Decision**: FIXED + extended — Test A in Phase 2 §7 now requires POST `/events/{id}` + csrf AND asserts BOTH legs of the chain with distinct messages: (a) controller→update leg (302 to `/app` + flash `successMessage` equals `„Zapisano zmiany w wydarzeniu „Wycieczka B”."`) (b) persisted-state→feed leg (GET `/calendar/{token}.ics` contains `SUMMARY:Wycieczka B` AND NOT `SUMMARY:Wycieczka A`). Repo-direct alternative explicitly dropped. Closes the "test passes because controller didn't throw" loophole the user flagged.

### F4 — Ambiguous `@Transactional` instruction in the update() contract

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 1 §2 (line 159, update handler contract)
- **Detail**: The contract says "relies on `@Transactional` dirty-checking (or adds `@Transactional` on the method if not already class-level)". `EventController` is NOT `@Transactional` at class level (verified). Without a method-level annotation, dirty-checking has no active transaction to flush to — OSIV (Spring Boot default) keeps the EntityManager open for reads, but writes require an active tx. The "or" makes it sound optional; it isn't. Self-correcting: `postEventUpdateHappyPathRedirectsToAppWithFlash` (Phase 1 §5) asserts the new title is persisted, so a missing `@Transactional` fails the test immediately. But the contract should not lean on the test to catch a correctness gap.
- **Fix**: Replace the "or" with a directive: "add `@Transactional` to update() (and to delete() in Phase 2 §1 — `eventRepository.delete(event)` has the same requirement)". Matches the existing `EventReviewService.applyDecisions` pattern.
- **Decision**: FIXED — Phase 1 §2 update() contract now reads: "Annotate `update()` with `@Transactional` — dirty-checking requires an active write transaction (`EventController` is not `@Transactional` at class level; Spring Boot's OSIV default covers reads only, not writes). Mirrors `EventReviewService.applyDecisions`." Phase 2 §1 delete() contract appended: "Annotate `delete()` with `@Transactional` — `eventRepository.delete(event)` requires an active write transaction (same reason as `update()`)."

### F5 — Minor plan-completeness drift (four small items, batched)

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 1 §2, Phase 1/2 success-criteria, Phase 1 §5
- **Detail**: Four small drifts the implementer will hit but easily fix:
  - (a) Baseline test counts off by one. Plan says "existing 9 cases" for `EventControllerTest` — actual is 10 `@Test`s. Plan says "existing 11 cases" for `CalendarControllerTest` — actual is 12. New-test counts are right; the +N totals in success criteria will mismatch.
  - (b) Implicit `EventController` constructor signature change. Phase 1 §2 says "Inject `Clock`" but doesn't note this means adding a constructor parameter (existing signature is `(EventRepository, AppUserRepository)`). One-line edit; would compile-fail otherwise.
  - (c) `Event` entity setter style unspecified. Plan correctly notes setters are missing (verified: `Event.java` has constructor + getters only). The existing constructor uses `Objects.requireNonNull` for non-null fields — should setters mirror? The plan leaves this to the implementer.
  - (d) Test fixture pattern mismatch. Plan writes `.with(user("alice@example.com"))` throughout — but existing tests use unique per-test emails like `"alice-happy@example.com"`, `"alice-past@example.com"` (verified in `EventControllerTest.java`). A shared `"alice@example.com"` across 11+ new tests will collide in the shared `@SpringBootTest` context. The implementer needs to follow the existing per-test-unique pattern.
- **Fix**: One-paragraph addendum in the plan covering all four. Each is a 5-second implementer fix once spotted; together they're 2 minutes of confusion if not flagged.
- **Decision**: FIXED — new "Implementer notes (drift caught during plan review)" section inserted immediately before `## Progress`, covering all four items. Additionally: baseline test counts already corrected in-place at Phase 1 §5 Success (`existing 10 cases + 12 new`), Phase 2 §7 Success (`existing 12 cases + 2 new`), and the matching Progress lines (1.2, 2.2). Constructor note specifies the new third parameter; setter note directs implementer to mirror constructor's `Objects.requireNonNull` on the non-null fields (`user`, `eventDate`, `title`); test-email note expands the `.with(user("alice@example.com"))` shorthand to the per-scenario convention (`alice-edit-happy@…`, `alice-delete-happy@…`, …) so the shared `@SpringBootTest` context doesn't collide on the email unique constraint.
