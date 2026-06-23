# UI polish — Home button + Polish-language consistency — Plan Brief

> Full plan: `context/changes/ui-polish-home-and-pl/plan.md`

## What & Why

Unify the UI to Polish, add a brand-link "Ogarniacz" → `/app` in the topbar so every signed-in page has a one-click route home, and fix the broken Polish plural in the "Dodano N wydarzeń." flash message. Today the product mixes EN and PL unpredictably (e.g. `Settings` / `Log out` next to `Dodaj z obrazka` / `Edytuj` / `Usuń`), there is no way back to the event list without using the browser back button, and the flash message uses the wrong noun form for every count except `N = 0` or `N ≥ 5`.

## Starting Point

All seven roadmap items (F-01, S-01..S-06) shipped and were archived; the MVP is functionally complete. The UI ships as Thymeleaf templates with a single inline `<style>` block in `fragments/layout.html`. EN-only templates: `login`, `signup`, `settings`. Mixed: `app`, `events/new`, `events/edit`. PL-only (correct already): `events/from-image`, all `events/review*`. EN validation messages live as literals in `SignupForm.java` and `SignupController.java`. No `static/` asset pipeline exists.

## Desired End State

Every visible string in the signed-in and auth surfaces is Polish. The topbar has a left-aligned brand-link "Ogarniacz" routing to `/app` on every signed-in page; the right side stays email + Ustawienia + Wyloguj. Primary buttons on `/app` sit in a `.button-group` with consistent gap and hover/focus states. The "Dodano N wydarzeń." flash uses the correct plural via a small `Plurals.wydarzenia(int)` helper. `./gradlew test` is green; no test asserts an EN literal that no longer ships.

## Key Decisions Made

| Decision                              | Choice                                                              | Why (1 sentence)                                                            | Source |
| ------------------------------------- | ------------------------------------------------------------------- | --------------------------------------------------------------------------- | ------ |
| Home navigation pattern               | Brand-link "Ogarniacz" → `/app` on the topbar's left                | Industry-standard idiom; one affordance, no redundant icons.                | Plan   |
| Wording style                         | Natural PL short imperatives (`Ustawienia`, `Wyloguj`, `Dodaj …`)   | Matches PL web-app convention (Allegro, mBank); concise.                    | Plan   |
| Image-button label                    | `Dodaj ze zdjęcia` (replacing `Dodaj z obrazka`)                    | "Zdjęcie" is more precise than "obrazek" and matches the upload-form JS copy. | Plan   |
| Pluralization fix                     | Small `Plurals` helper with one unit test, no i18n machinery        | Single call site; full `messages.properties` migration is overkill.         | Plan   |
| Polish scope                          | Medium — topbar + brand + button-group + hover/focus states         | Adds noticeable polish without touching the underlying inline-CSS layout.   | Plan   |
| Test-assertion update strategy        | Scan + fix in Phase 2 alongside the template/validation edits       | Keeps the green-on-green discipline; one commit = coherent change.          | Plan   |
| i18n machinery                        | None — strings stay hard-coded                                      | App is single-locale; `LocaleResolver` + `messages.properties` is out of scope. | Plan   |
| `static/` asset pipeline              | None — styles stay inline in `fragments/layout.html`                | This change is "polish", not a CSS architecture overhaul.                   | Plan   |

## Scope

**In scope:**
- Translate all EN/mixed templates to PL (`login`, `signup`, `settings`, `app`, `events/new`, `events/edit`, topbar fragment, `events/from-image` title-only).
- Add `<a class="brand">Ogarniacz</a>` → `/app` to the topbar fragment.
- Tighten button spacing on `/app` (`.button-group`) and add `:hover` / `:focus-visible` rules for `.primary-action`, `.row-action`, `.row-action--danger`.
- New `com.example.app.util.Plurals` helper + unit test; wire into `EventReviewController` flash message.
- Translate two EN validation messages in `SignupForm.java` + `SignupController.java`.
- Update three test files asserting EN literals (`AppApplicationTests`, `SettingsControllerTest`, `EventReviewControllerTest`).

**Out of scope:**
- `messages.properties` / `LocaleResolver` / any i18n framework setup.
- Introducing `src/main/resources/static/` or moving CSS out of `fragments/layout.html`.
- CSS variable / design-token system; third-party CSS frameworks.
- Route or URL renames (`/events/from-image` stays, even though the label changes).
- Already-PL templates beyond the "obrazka → zdjęcia" wording sync.

## Architecture / Approach

Two phases. **Phase 1** ships `Plurals` + its test + the one-line `EventReviewController` change + the one-line test update — fully verifiable in isolation. **Phase 2** sweeps the templates, topbar, CSS additions, the two Java validation strings, and the three test files in one coordinated pass because the validation-string changes are tightly coupled to the test assertions that read them.

## Phases at a Glance

| Phase                                                                              | What it delivers                                                                                              | Key risk                                                                                                              |
| ---------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------- |
| 1. Pluralization helper                                                            | `Plurals.wydarzenia(int)` + unit test + flash-message wiring + `EventReviewControllerTest` assertion update   | None — pure function with table-driven test.                                                                          |
| 2. Templates + topbar + visual polish + EN validation messages + test sweep        | All PL strings, brand-link, button-group, hover/focus states, PL validation messages, green test suite        | Missing an EN literal in a template or test that the grep didn't catch — mitigated by the success-criteria grep gates. |

**Prerequisites:** Phase 1 must land before Phase 2 so the test sweep in Phase 2 doesn't re-touch `EventReviewControllerTest` for two separate reasons.
**Estimated effort:** 1 evening across both phases (helper + 8 templates + 2 controller-side strings + 3 test files).

## Open Risks & Assumptions

- A test file outside the grep list could assert an EN literal not enumerated here. Mitigation: Phase 2 opens with a broader `grep -RIn '…' src/test/` sweep before editing.
- The `subscribed` assertion in `SettingsControllerTest.java:114` is replaced with `subskrybować`, which assumes the H2 translation reads `Jak subskrybować`. If the wording drifts to `Jak się zapisać`, update both in lockstep.
- The brand-link "Ogarniacz" wording is product-naming; if the project renames the product later, this becomes one of the renaming touch points.

## Success Criteria (Summary)

- A signed-in parent never sees an English string from `/login` through `/app` through `/settings` through `/events/*`.
- From any signed-in page, one click on the topbar's brand-link lands on `/app`.
- "Dodano N wydarzeń." flash reads naturally for `N ∈ {1, 2, 3, 4, 5, 11, 12, 21, 22, …}`.
