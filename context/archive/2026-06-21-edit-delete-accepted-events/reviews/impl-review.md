<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Edit / Delete Accepted Events (S-03)

- **Plan**: context/changes/edit-delete-accepted-events/plan.md
- **Scope**: Full plan (Phases 1–3, all Progress boxes [x])
- **Date**: 2026-06-21
- **Verdict**: NEEDS ATTENTION
- **Findings**: 0 critical · 1 warning · 1 observation

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | WARNING |
| Scope Discipline | PASS |
| Safety & Quality | PASS |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

Automated success criteria: `./gradlew test --tests com.example.app.event.EventRepositoryTest --tests com.example.app.event.EventControllerTest --tests com.example.app.event.CalendarControllerTest` → BUILD SUCCESSFUL. `./gradlew build` → BUILD SUCCESSFUL. All Manual checkboxes in `## Progress` marked `[x]`.

## Findings

### F1 — Past-date soft-warn uses client clock instead of server `${todayIso}`

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Plan Adherence
- **Location**:
  - `src/main/java/com/example/app/event/EventController.java:41,51,83,97`
  - `src/main/resources/templates/events/new.html:9`
  - `src/main/resources/templates/events/edit.html:9`
- **Detail**: The plan specified a server-rendered `todayIso` model attribute (`LocalDate.now(clock).toString()`) and an onsubmit handler of the form `|return this.eventDate.value >= '${todayIso}' || confirm(...);|` — explicitly because the past-event guard uses the server `clock`, and the soft-warn should agree (and be test-stubbable via a fixed `Clock` bean). Plan refs: Phase 1 §3, Phase 2 §2, Phase 2 §3, "Critical Implementation Details → `EventForm` reused for both create and edit".

  Actual: `EventController` never adds `todayIso` to any model; both templates ship `onsubmit="return this.eventDate.value >= new Date().toISOString().slice(0,10) || confirm('Data jest w przeszłości — kontynuować?');"` — the browser's clock, not the server's.

  Why it matters: (a) client clock can drift or be set; the server filter still 404-blocks a real past date, but the soft-warn fires inconsistently with the server policy. (b) Tests `editFormCarriesPastDateSoftWarnOnsubmit` / `createFormCarriesPastDateSoftWarnOnsubmit` only substring-match `onsubmit="return this.eventDate.value >=` and the Polish confirm() literal — they don't pin the RHS, so this drift slipped past them. (c) The symmetry-lesson pinning works (both forms drifted the same way), but the symmetry is to the wrong contract.

- **Fix A ⭐ Recommended**: Land the planned `${todayIso}` shape — add `model.addAttribute("todayIso", LocalDate.now(clock).toString())` in `show()`, `create()` error branch, `edit()`, and `update()` error branch; replace `new Date().toISOString().slice(0,10)` with `'${todayIso}'` in both templates via Thymeleaf `||` literal substitution; tighten the two rendering tests to assert the `${today}` literal segment to prevent re-drift.
  - Strength: Matches plan; restores server-clock authority; makes tests stub-time-deterministic via a fixed `Clock` bean.
  - Tradeoff: ~6-line change across 3 files plus a small test tightening.
  - Confidence: HIGH — clock already injected; design is explicit in plan.
  - Blind spot: None significant.
- **Fix B**: Accept the client-clock implementation; update lessons.md / plan addendum to document the design change.
  - Strength: Zero code churn; soft-warn still fires in the dominant case.
  - Tradeoff: Drift in source of truth (server guard vs. browser warn); tests stay loose.
  - Confidence: MEDIUM — depends whether you value the test-time determinism the plan called out.
  - Blind spot: A future timezone-skewed-user flake report re-opens this.
- **Decision**: FIXED via Fix A (variant: `data-today` attribute + `this.dataset.today` instead of inline `'${todayIso}'` interpolation). Controller now seeds `todayIso` in show/create-error/edit/update-error; both templates carry `th:attr="data-today=${todayIso}"`; onsubmit reads `this.dataset.today`; render tests pin `data-today="<iso>"`, `this.dataset.today`, and assert `new Date()` is absent.

### F2 — change.md status is `implemented`, plan §3.4 said `done`

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: `context/changes/edit-delete-accepted-events/change.md:4`
- **Detail**: Phase 3 §4 contract: "Set `status: done` in the YAML frontmatter". Actual: `status: implemented`. Roadmap and test-plan correctly show S-03 as done; only the change.md status string lags.
- **Fix**: Flip `status: implemented` → `status: done` in `change.md`.
- **Decision**: SKIPPED

## PASS-shaped highlights (no action required)

- CSRF + XSS patterns: `data-title` + `dataset.title` on the delete confirm (no user-controlled JS-string interpolation); `th:text` only on flash; no `th:utext` anywhere; CSRF auto-injection verified by `postEventUpdateWithoutCsrfIs403` / `postEventDeleteWithoutCsrfIs403`.
- `@Transactional` on `update()` / `delete()` correctly leans on dirty-checking — no redundant `eventRepository.save(event)` on the managed entity. The slice is the canonical example of the lessons.md "explicit save() is redundant" rule.
- `findByIdAndUser` mirrors `SourceImageRepository.findByIdAndUser` 1:1; 404-not-403 collapse via `ResponseStatusException(NOT_FOUND)` mirrors `EventReviewController`.
- Per-controller test class layout followed (`EventControllerTest` extended, not split or folded into `AppApplicationTests`); per-scenario emails (`alice-edit-render@example.com`, …) honor the `@SpringBootTest` shared-context uniqueness constraint.
- Pinpoint contract test `postEventUpdateMovingDateToPastReturnsSuccessAndRowVanishesFromApp` is wired the way the plan demanded — adding `@FutureOrPresent` asymmetrically turns it red, surfacing the new lessons.md "validation symmetry" entry.
- Hard-delete is safe: `Event` has no inbound FK; iCal feed naturally drops the row on next poll (pinned by `deletedEventDisappearsFromNextPoll`).
- No scope creep: every changed source file is on the plan's file list.
