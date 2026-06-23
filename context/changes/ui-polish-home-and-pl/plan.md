# UI polish — Home button + Polish-language consistency — Implementation Plan

## Overview

Unify the UI to a single language (Polish), add a brand-link "Ogarniacz" → `/app` in the topbar so every signed-in page has a one-click route to the event list, tighten button spacing and hover/focus states, and fix the Polish pluralization of the "Dodano N wydarzeń." flash message.

## Current State Analysis

The product is signed in by a Polish-speaking parent, but the UI mixes English and Polish unpredictably. Concrete findings (from a full template + controller sweep):

- **Topbar** ([fragments/layout.html:78-87](src/main/resources/templates/fragments/layout.html)): the only navigation is `Settings` and `Log out` (EN). The user email sits at the left as plain text. There is no link back to `/app` from `/settings`, `/events/new`, `/events/edit`, `/events/from-image`, or any of the four `/events/review*` views — the user either uses the browser back button, or relies on a page-local "Anuluj"/"Wróć do listy" link where it happens to exist.
- **EN-only templates**: [login.html](src/main/resources/templates/login.html), [signup.html](src/main/resources/templates/signup.html), [settings.html](src/main/resources/templates/settings.html) — every visible string is English.
- **Mixed templates**:
  - [app.html](src/main/resources/templates/app.html): `Signed in as`, `Add event`, `No events yet. Add one above.` (EN) sit next to `Dodaj z obrazka`, `Edytuj`, `Usuń` (PL). The two primary buttons render in a single `<p>` with no gap.
  - [events/new.html](src/main/resources/templates/events/new.html): EN labels (`Date`, `Time (optional)`, `Title`, `Requirements`, `Notes`, `Save event`, `Cancel`) with a PL `onsubmit` confirm.
  - [events/edit.html](src/main/resources/templates/events/edit.html): EN labels with PL buttons (`Zapisz zmiany`, `Anuluj`).
- **PL-only (no change)**: `events/from-image.html`, `events/review.html`, `events/review-empty.html`, `events/review-running.html`, `events/review-error.html`.
- **Validation messages emitted from Java (EN)**:
  - [SignupForm.java:15](src/main/java/com/example/app/user/SignupForm.java): `@Size(... message = "Password must be at least 12 characters.")`.
  - [SignupController.java:64,73](src/main/java/com/example/app/user/SignupController.java): `result.rejectValue("email", "duplicate", "Email already in use.");`.
- **Pluralization bug**: [EventReviewController.java:166-167](src/main/java/com/example/app/event/EventReviewController.java) emits `"Dodano " + accepted + " wydarzeń."` regardless of `accepted`. Polish needs three forms — `wydarzenie` (1), `wydarzenia` (2–4 except 12–14), `wydarzeń` (else, including 0 and 5+).
- **Tests asserting EN literals**:
  - [AppApplicationTests.java:135,155](src/test/java/com/example/app/AppApplicationTests.java): `containsString("Email already in use")`.
  - [AppApplicationTests.java:165](src/test/java/com/example/app/AppApplicationTests.java): `containsString("at least 12")`.
  - [SettingsControllerTest.java:113,114](src/test/java/com/example/app/user/SettingsControllerTest.java): `containsString("Google")`, `containsString("subscribed")`.
  - [EventReviewControllerTest.java:105](src/test/java/com/example/app/event/EventReviewControllerTest.java): `flash().attribute("successMessage", "Dodano 2 wydarzeń.")` — currently passes the buggy literal; will need to match the corrected form `"Dodano 2 wydarzenia."`.

Inline CSS lives entirely in [fragments/layout.html:7-94](src/main/resources/templates/fragments/layout.html). No `src/main/resources/static/` directory exists — keep styles where they are; do not introduce a static asset pipeline as part of this change.

## Desired End State

- Every visible string in signed-in and auth pages is Polish, using short imperative wording (`Ustawienia`, `Wyloguj`, `Dodaj wydarzenie`, `Dodaj ze zdjęcia`, `Edytuj`, `Usuń`).
- The topbar renders a left-aligned brand-link `Ogarniacz` that routes to `/app` from every signed-in page; the right side keeps email + `Ustawienia` + `Wyloguj`.
- Primary buttons on `/app` and elsewhere sit in a `.button-group` with consistent gap. **Interactive color is green** (brand alignment with the logo, decided mid-change): `.primary-action`, solid form-submit buttons, and all default links use `#1a7f37` (AA-safe with white text; the logo's lighter `#4FBA89` stays decorative in the imagery); hover `#15692e`; focus ring tint `#d2f4dd`. `.row-action` keeps the neutral `#f6f8fa` hover fill and `.row-action--danger` keeps `#ffebe9`.
- The "Dodano N wydarzeń." flash uses the correct Polish plural for every `N ∈ {0, 1, 2, 3, 4, 5, 11, 12, 14, 21, …}`.
- The create/edit forms (and `login`/`signup`, which share `form.stacked`) look finished: inputs and textareas share one bordered, rounded, focus-highlighted style; the submit button is a solid blue primary; the cancel link is a muted secondary.
- `./gradlew test` is green; no test asserts an EN literal that no longer ships.
- `./gradlew bootRun` (with the usual `SPRING_DATASOURCE_*` env vars) starts; a manual click-through of `/login → /signup → /login → /app → /settings → /events/new → /app → /events/from-image → /app` shows no EN string and a working brand-link on every page.

### Key Discoveries

- Topbar fragment is the single point that needs to change to give every signed-in page a Home affordance ([fragments/layout.html:78-87](src/main/resources/templates/fragments/layout.html)). Auth pages (`login`, `signup`) don't include the topbar today — Home doesn't apply there.
- Validation messages are inline strings on the field annotation and the controller's `rejectValue` call — not externalized — so the PL switch is a literal edit, not a `messages.properties` migration.
- The only test currently coupled to the buggy plural is [EventReviewControllerTest.java:105](src/test/java/com/example/app/event/EventReviewControllerTest.java); the `n=2` case is exactly the one whose fix is visible (`wydarzeń` → `wydarzenia`), so the assertion update is also a regression guard for the helper.
- "Dodaj z obrazka" already exists in the PL UI; the brief decision was to switch to the cleaner "Dodaj ze zdjęcia" everywhere (button label, page H1, page title, headers, JS user-facing strings). Update [events/from-image.html](src/main/resources/templates/events/from-image.html) too to keep the wording in sync — but do NOT rename URLs or controller mappings (`/events/from-image` stays).

## What We're NOT Doing

- No CSS asset pipeline. Styles stay inline in `fragments/layout.html`. _(Scope note: `src/main/resources/static/img/` was introduced mid-change to host the logo assets — see Phase 2 #9. This is image hosting, not a CSS/build pipeline.)_
- No design-token system, CSS variables refactor, or third-party CSS framework (Pico, Tailwind). That belongs to a separate change after `/10x-shape`.
- No `messages.properties` / Spring `LocaleResolver` setup. Strings stay hard-coded — the app is single-locale today, and adding i18n machinery is out of scope.
- No URL / route changes. `/events/from-image` stays under that path even though the visible label switches to "Dodaj ze zdjęcia".
- No edits to the already-PL templates (`events/review*.html`, `events/from-image.html` JS error copy, etc.) beyond the "obrazka → zdjęcia" wording sync.
- No changes to controller logic, security, persistence, or LLM code paths.

## Implementation Approach

Two phases, ordered so each can be verified independently:

1. **Phase 1 — Pluralization helper.** Introduce a small `Plurals` utility with `wydarzenia(int)` returning the correct noun form; switch `EventReviewController` to use it; update the one test that asserts the buggy literal. Smallest possible diff, fully covered by a new unit test on the helper.
2. **Phase 2 — Templates + topbar + visual polish + EN validation messages + test sweep.** One sweep across the EN/mixed templates, the topbar fragment, `SignupForm`/`SignupController` EN strings, and the three test files asserting EN literals. Done in one pass because the diff is tightly coupled — changing the validation message and changing the assertion that reads it have to ship together.

## Phase 1: Pluralization helper

### Overview

Add a tiny pure-function helper for Polish pluralization of "wydarzenie" and wire it into the only place that currently emits a count-based flash message.

### Changes Required:

#### 1. New helper class

**File**: `src/main/java/com/example/app/util/Plurals.java`

**Intent**: Provide a single static method that returns the correct Polish noun form for a non-negative count. Pure, side-effect-free, no Spring wiring.

**Contract**: `public static String wydarzenia(int n)` returning one of `"wydarzenie"`, `"wydarzenia"`, `"wydarzeń"`. The rule (standard Polish plural for masculine inanimate / neuter abstract): `n == 1` → `wydarzenie`; the units digit is 2, 3, or 4 AND the tens digit is not 1 → `wydarzenia`; else → `wydarzeń`. `n` is assumed non-negative (callers in this app only pass `accepted.size()`); throw `IllegalArgumentException` if `n < 0`.

#### 2. Helper unit test

**File**: `src/test/java/com/example/app/util/PluralsTest.java`

**Intent**: Lock the table-driven contract so the helper is regression-proof.

**Contract**: Parameterized JUnit 5 test (`@ParameterizedTest` + `@CsvSource`) covering at minimum: `0 → wydarzeń`, `1 → wydarzenie`, `2 → wydarzenia`, `4 → wydarzenia`, `5 → wydarzeń`, `11 → wydarzeń`, `12 → wydarzeń`, `14 → wydarzeń`, `21 → wydarzeń`, `22 → wydarzenia`, `25 → wydarzeń`, `102 → wydarzenia`, `112 → wydarzeń`. Plus a negative-input case asserting `IllegalArgumentException` for `n = -1`.

#### 3. EventReviewController flash message

**File**: `src/main/java/com/example/app/event/EventReviewController.java`

**Intent**: Use the helper so the flash message reads naturally for every `accepted` count.

**Contract**: Replace the literal `"Dodano " + accepted + " wydarzeń."` at line 166-167 with `"Dodano " + accepted + " " + Plurals.wydarzenia(accepted) + "."`. Add the matching import for `com.example.app.util.Plurals`.

#### 4. EventReviewControllerTest assertion

**File**: `src/test/java/com/example/app/event/EventReviewControllerTest.java`

**Intent**: Update the assertion at line 105 to match the corrected form. `accepted == 2` so the helper now returns `wydarzenia`.

**Contract**: `flash().attribute("successMessage", "Dodano 2 wydarzenia.")`. If other test cases in the file accept a different N, audit and update accordingly — but a `grep` shows only this one occurrence.

### Success Criteria:

#### Automated Verification:

- New `PluralsTest` passes: `./gradlew test --tests com.example.app.util.PluralsTest`
- `EventReviewControllerTest` still green: `./gradlew test --tests com.example.app.event.EventReviewControllerTest`
- Full build green: `./gradlew build`

#### Manual Verification:

- Boot the app, upload an image that yields ≥ 2 proposed events, accept all of them, and confirm the success banner reads "Dodano N wydarzenia." (or "wydarzeń" for 5+).

**Implementation Note**: After completing this phase and all automated verification passes, pause for the user to confirm the success-banner wording reads naturally before moving to Phase 2.

---

## Phase 2: Templates + topbar + visual polish + EN validation messages + test sweep

### Overview

One coordinated sweep across user-visible strings, the topbar layout, button spacing, and the EN-asserting tests. Diff is large by line count but mechanical: each template gets the same treatment (PL strings, brand-link in topbar, button-group container where multiple primary actions sit together).

### Changes Required:

#### 1. Topbar fragment + global styles

**File**: `src/main/resources/templates/fragments/layout.html`

**Intent**: Add a left-aligned brand-link `Ogarniacz` that routes to `/app`. Keep the user email + Ustawienia + Wyloguj on the right. Add `.button-group` and tighter `.primary-action` hover/focus styling. Translate `Settings` → `Ustawienia` and `Log out` → `Wyloguj` in the topbar fragment.

**Contract**:
- Topbar HTML layout (currently `fragments/layout.html:91-99`): the `<span class="user-email">` currently sits on the LEFT — it moves to the right side. Left side becomes `<a th:href="@{/app}" class="brand">Ogarniacz</a>`; right side becomes `<span class="user-email">` + Ustawienia link + Wyloguj form (email is pulled into the existing `.topbar-right` flex container so the brand-link can take the left slot). The fragment name (`topbar`) and its consumers in `app.html`, `settings.html`, `events/new.html`, `events/edit.html`, `events/from-image.html`, `events/review*.html` stay unchanged.
- New CSS rules in the `<style>` block:
  - `header.topbar .brand` — originally a text link; superseded by the image logo (see Phase 2 #9): `inline-flex`, `gap`, opacity-0.8 hover.
  - `.button-group` — `display: flex; gap: 0.75rem; flex-wrap: wrap; justify-content: center;` for `app.html`'s two primary actions and any future similar grouping.
  - `.primary-action:hover, .primary-action:focus-visible` — `background: #ddf4ff; outline: none;` (matches existing palette).
  - `.event-list .row-action:hover, .event-list .row-action:focus-visible` — `background: #f6f8fa; outline: none;`. Match the existing `.event-list`-prefixed specificity in this block (specificity 0,2,1) so the new rule actually wins; a bare `.row-action:hover` would lose to `.event-list .row-action`.
  - **Edit the existing rule at line 50** (`.event-list .row-action--danger:hover`) from `background: #fff5f5;` to `background: #ffebe9;` — replace in place, do not add a sibling `.row-action--danger:hover` (it would lose the specificity race and the delete-button hover would not change).

#### 2. Home page (app.html)

**File**: `src/main/resources/templates/app.html`

**Intent**: Translate visible strings to PL; wrap the two primary actions in `.button-group`; rename "Add event" / "Dodaj z obrazka" to the agreed labels.

**Contract**:
- Line 8 `Signed in as` → `Zalogowany jako`.
- Line 10 link text `Add event` → `Dodaj wydarzenie`; line 11 link text `Dodaj z obrazka` → `Dodaj ze zdjęcia`.
- Wrap both `<a>` elements in `<div class="button-group">…</div>` instead of a single `<p>`.
- Line 32 `No events yet. Add one above.` → `Nie masz jeszcze wydarzeń. Dodaj pierwsze powyżej.`

#### 3. Auth pages (login, signup)

**Files**: `src/main/resources/templates/login.html`, `src/main/resources/templates/signup.html`

**Intent**: Translate the auth surface to PL.

**Contract**:
- `login.html`: title arg `'Log in'` → `'Logowanie'`; `<h1>Log in</h1>` → `Logowanie`; error `Invalid email or password.` → `Nieprawidłowy email lub hasło.`; notice `You have been logged out.` → `Wylogowano.`; labels `Email` → `Email` (stays — single word), `Password` → `Hasło`; button `Log in` → `Zaloguj`; footer `No account? Sign up` → `Nie masz konta? Zarejestruj się`.
- `signup.html`: title arg `'Sign up'` → `'Rejestracja'`; `<h1>Sign up</h1>` → `Rejestracja`; label `Email` stays; label `Password (12+ characters)` → `Hasło (min. 12 znaków)`; button `Create account` → `Załóż konto`; footer `Already have an account? Log in` → `Masz już konto? Zaloguj się`.

#### 4. Settings page

**File**: `src/main/resources/templates/settings.html`

**Intent**: Translate the settings surface to PL while keeping the technical hint copy accurate.

**Contract**:
- Title `'Settings'` → `'Ustawienia'`.
- `<h1>Calendar subscription</h1>` → `Subskrypcja kalendarza`.
- Label `Your unique iCalendar URL` → `Twój unikalny adres iCalendar`.
- Button `Copy` → `Kopiuj`.
- Hint `Anyone with this URL can read your accepted events. Share with your spouse or co-parent only.` → `Każda osoba z tym adresem może czytać Twoje zaakceptowane wydarzenia. Udostępniaj tylko partnerowi lub współrodzicowi.`
- `<h2>How to subscribe</h2>` → `Jak subskrybować`.
- Per-client paragraphs — use these exact target strings (proper-noun client names stay; menu names match the Polish-locale UI of each client):
  - **Google Calendar:** `Ustawienia → Dodaj kalendarz → Z adresu URL — wklej powyższy adres.`
  - **Apple Calendar:** `Plik → Nowa subskrypcja kalendarza — wklej powyższy adres.`
  - **Outlook:** `Dodaj kalendarz → Subskrybuj z Internetu — wklej powyższy adres.`
  - **Thunderbird:** `Plik → Nowy → Kalendarz → W sieci — wklej powyższy adres.`
- Google-Calendar limitations `<aside>`: target text:
  > **Ograniczenia Google Calendar.** Google odpytuje subskrybowane kalendarze co 12–24 godziny (czasem do 48 godzin), bez możliwości przyspieszenia tego procesu po stronie klienta. Google nie pokazuje też przypomnień osadzonych w subskrybowanych kalendarzach — aby otrzymać przypomnienie rano dnia poprzedniego, ustaw domyślne przypomnienie dla danego kalendarza w samych ustawieniach Google Calendar. Apple Calendar, Outlook i Thunderbird w pełni respektują zarówno częstotliwość odświeżania, jak i osadzone przypomnienia.

#### 5. Event create + edit forms

**Files**: `src/main/resources/templates/events/new.html`, `src/main/resources/templates/events/edit.html`

**Intent**: Translate labels and the submit button on `new.html`; the `edit.html` buttons are already PL but its labels are EN. **Also polish the shared `form.stacked` styling** — the create/edit forms (which share a structure) looked unfinished after the translation-only pass: textareas were unstyled, inputs were borderless, and the submit button was a default gray.

**Contract**:
- Title args `'Add event'` / `'Edit event'` → `'Dodaj wydarzenie'` / `'Edytuj wydarzenie'`.
- `<h1>` text matches the title.
- Labels (same in both files): `Date` → `Data`; `Time (optional)` → `Godzina (opcjonalnie)`; `Title` → `Tytuł`; `Requirements` → `Wymagania`; `Notes` → `Notatki`.
- `new.html` submit button `Save event` → `Zapisz wydarzenie`; cancel link `Cancel` → `Anuluj`. (`edit.html` already has `Zapisz zmiany` / `Anuluj`.)
- The `Anuluj` link on `new.html`/`edit.html` (and `Wyślij`'s neighbor on `from-image.html`) is restyled as a secondary button (`.btn-secondary`) and placed in a `.form-actions` flex row beside the primary submit button, so cancel sits next to save/send. It stays an `<a>` → `/app` (no form submit); on `from-image.html` it moves inside the `<form>` to join the row.

**`form.stacked` visual polish** (in `fragments/layout.html` `<style>`, applies to `new`, `edit`, `login`, `signup`):
- `input` AND `textarea` get a shared rule: `border: 1px solid #d0d7de`, `border-radius: 6px`, `font: inherit`, `0.5rem 0.625rem` padding, `margin-top: 0.35rem` to separate field from label text. (Previously only `input` was styled — textareas fell back to browser defaults.)
- `textarea { resize: vertical; }`.
- Focus state on both: `border-color: #1a7f37` + `box-shadow: 0 0 0 3px #d2f4dd` (green palette).
- Labels get `font-weight: 500; font-size: 0.9375rem`.
- Submit button becomes a solid primary: `background: #1a7f37`, white text, `border: 1px solid #1a7f37`, hover/`:focus-visible` → `#15692e`. (Previously a default gray button.)
- New `.cancel-link` rule: muted `#57606a`, no underline, underline on hover/focus.

#### 6. "Dodaj z obrazka" → "Dodaj ze zdjęcia" wording sync

**File**: `src/main/resources/templates/events/from-image.html`

**Intent**: Switch the page title and H1 to match the new home-page button label. JS error copy already uses "zdjęcie" so it stays.

**Contract**: Title arg `'Dodaj z obrazka'` → `'Dodaj ze zdjęcia'`; `<h1>Dodaj z obrazka</h1>` → `Dodaj ze zdjęcia`. No other edits to this file.

#### 7. Validation message strings in Java

**Files**: `src/main/java/com/example/app/user/SignupForm.java`, `src/main/java/com/example/app/user/SignupController.java`

**Intent**: Move the two user-facing EN validation messages to PL.

**Contract**:
- `SignupForm.java:15`: `message = "Password must be at least 12 characters."` → `message = "Hasło musi mieć co najmniej 12 znaków."`.
- `SignupController.java:64,73`: both `"Email already in use."` → `"Email jest już używany."`.

#### 8. Test assertions updated to match new PL strings

**Files**: `src/test/java/com/example/app/AppApplicationTests.java`, `src/test/java/com/example/app/user/SettingsControllerTest.java`

**Intent**: Update the literal strings the tests `containsString` against so the suite stays green.

**Contract**:
- `AppApplicationTests.java:135,155`: `containsString("Email already in use")` → `containsString("Email jest już używany")`.
- `AppApplicationTests.java:165`: `containsString("at least 12")` → `containsString("co najmniej 12 znaków")`.
- `SettingsControllerTest.java:113,114`: `containsString("Google")` stays (proper noun, still present). `containsString("subscribed")` → `containsString("Jak subskrybować")` — anchor to the exact H2 wording so the test does not accidentally pass on the body string `subskrybowane kalendarze` if the H2 is renamed.

Run `grep -RIn '"Log in\|"Settings\|"Add event\|"Log out\|"Sign up\|"Signed in as\|"No events yet\|"Save event\|"Edit event\|"Calendar subscription\|"Cancel\|"Email already in use\|"at least 12\|"subscribed\|"How to subscribe' src/test` as the first move of this step to catch any assertion that was missed.

#### 9. Logo assets (added mid-change at user request)

**Files**: `src/main/resources/static/img/{logo-mark,logo-wordmark,logo-lockup}.png` (derived crops), `fragments/layout.html`, `login.html`, `signup.html`, `SecurityConfig.java`.

**Intent**: Replace the plain text brand-link with the brand logo, and add the full logo to the auth pages. The user supplied a 1254×1254 master (`ogarniacz-logo.png`); it was sliced into a transparent brain mark, a transparent "ogarniacz" wordmark (keeps the green dot over the "i"), and a transparent full lockup, then downscaled for web.

**Contract**:
- Topbar brand-link (`fragments/layout.html`): `Ogarniacz` text → `<img class="brand-mark">` (height 34px) + `<img class="brand-wordmark">` (height 19px) inside the `/app` link; link gets `aria-label`, wordmark img `alt=""`. `.brand` becomes `inline-flex` with `gap`; hover drops opacity to 0.8.
- Auth pages (`login.html`, `signup.html`): `<img class="auth-logo" src="/img/logo-lockup.png">` (height 96px, centered) above the `<h1>`.
- `SecurityConfig.java`: add `/img/**` to the `permitAll()` matcher list — the auth-page logo loads before authentication, so without this the image 302-redirects to `/login`.
- Asset generation is a one-off (PIL crop + LANCZOS downscale); the master `ogarniacz-logo.png` stays in the repo as source-of-truth.

### Success Criteria:

#### Automated Verification:

- `./gradlew test` is green (full suite).
- `grep -RIn '>Log in<\|>Settings<\|>Add event<\|>Log out<\|>Sign up<\|>Signed in as\|>No events yet\|>Save event<\|>Edit event<\|>Calendar subscription<\|>Cancel<' src/main/resources/templates/` returns nothing.
- `grep -RIn '"Email already in use\|"Password must be at least 12' src/main/java/` returns nothing.

#### Manual Verification:

- `./gradlew bootRun` (with `SPRING_DATASOURCE_*`) starts cleanly. Click-through `/login → /signup → /login → /app → /settings → /events/new → /app → /events/from-image → /app` and confirm:
  - Every visible string is Polish.
  - Brand-link "Ogarniacz" in the topbar routes to `/app` from `/settings`, `/events/new`, `/events/edit/{id}`, `/events/from-image`, and a `/events/review` page.
  - Two primary buttons on `/app` (`Dodaj wydarzenie`, `Dodaj ze zdjęcia`) sit side by side with consistent gap, not crammed in one `<p>`.
  - Hover on `.primary-action`, `.row-action`, and `.row-action--danger` shows a visible fill change; keyboard `Tab` lands a `:focus-visible` ring on each.
  - Trigger a validation error on `/signup` (e.g. submit short password, duplicate email) and confirm the inline error reads `Hasło musi mieć co najmniej 12 znaków.` / `Email jest już używany.`

**Implementation Note**: After completing this phase and all automated verification passes, pause for the user to do the click-through above and confirm the polish feels right before archiving the change.

---

## Testing Strategy

### Unit Tests

- New `PluralsTest`: parameterized cases pinning all three plural forms across the boundary numbers (0, 1, 2, 4, 5, 11, 12, 14, 21, 22, 25, 102, 112) plus the `IllegalArgumentException` case.

### Integration Tests

- Existing `EventReviewControllerTest` / `AppApplicationTests` / `SettingsControllerTest` updated to assert the new PL strings; no new integration tests added — this change does not alter routing, persistence, or auth behaviour.

### Manual Testing Steps

1. Sign up with a new email — confirm landing page + topbar are PL and brand-link works.
2. Log out, log in, log out — confirm `Wylogowano.` notice and `Zaloguj` button.
3. Sign up with an existing email — confirm `Email jest już używany.` inline error.
4. Sign up with a 5-char password — confirm `Hasło musi mieć co najmniej 12 znaków.` inline error.
5. Add an event manually — confirm form labels are PL, success flash reads `Zapisano zmiany w wydarzeniu „…".`
6. Upload an image, accept 1 proposed event — confirm flash reads `Dodano 1 wydarzenie.`
7. Upload another image, accept 2 — confirm `Dodano 2 wydarzenia.`
8. Upload another, accept 5 — confirm `Dodano 5 wydarzeń.`
9. From `/settings`, click the brand-link — should land on `/app`.

## Performance Considerations

No measurable performance impact. The `Plurals.wydarzenia` call is a single integer modulo + comparison; CSS hover rules add no layout work.

## Migration Notes

None. No schema changes, no data backfills, no config migrations. Templates and tests are the only edit surface beyond the small helper class.

## References

- Change: `context/changes/ui-polish-home-and-pl/change.md`
- Brief: `context/changes/ui-polish-home-and-pl/plan-brief.md`
- Existing topbar fragment: `src/main/resources/templates/fragments/layout.html:78-87`
- Existing flash-message bug: `src/main/java/com/example/app/event/EventReviewController.java:166-167`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Pluralization helper

#### Automated

- [x] 1.1 New `PluralsTest` passes: `./gradlew test --tests com.example.app.util.PluralsTest` — 62ad31e
- [x] 1.2 `EventReviewControllerTest` still green: `./gradlew test --tests com.example.app.event.EventReviewControllerTest` — 62ad31e
- [x] 1.3 Full build green: `./gradlew build` — 62ad31e

#### Manual

- [x] 1.4 Success banner reads "Dodano N wydarzenia." (or "wydarzeń" for 5+) after accepting events from an uploaded image — 62ad31e

### Phase 2: Templates + topbar + visual polish + EN validation messages + test sweep

#### Automated

- [x] 2.1 `./gradlew test` is green (full suite)
- [x] 2.2 No EN strings remain in templates (the `>Log in<|>Settings<|…` grep returns nothing)
- [x] 2.3 No EN validation messages remain in Java (the `"Email already in use"|"Password must be at least 12"` grep returns nothing)

#### Manual

- [x] 2.4 `./gradlew bootRun` starts cleanly and the full click-through (`/login → /signup → /login → /app → /settings → /events/new → /app → /events/from-image → /app`) shows only PL strings
- [x] 2.5 Brand-link "Ogarniacz" in the topbar routes to `/app` from every signed-in page
- [x] 2.6 Two primary buttons on `/app` sit side by side with consistent gap (`.button-group`)
- [x] 2.7 `.primary-action`, `.row-action`, `.row-action--danger` hover + `:focus-visible` states are visible
- [x] 2.8 `/signup` validation triggers PL error messages (`Hasło musi mieć co najmniej 12 znaków.`, `Email jest już używany.`)
- [x] 2.9 `/events/new` and `/events/{id}/edit` forms look finished: inputs + textareas share a bordered, rounded, focus-highlighted style; solid blue submit button; muted cancel link
- [x] 2.10 Topbar shows brain mark + "ogarniacz" wordmark as the `/app` brand-link; logo lockup renders on `/login` and `/signup` (loads pre-auth via `/img/**` permitAll)
- [x] 2.11 Interactive color is green (`#1a7f37`): primary actions, form-submit buttons, and links — verified against white-text contrast
- [x] 2.12 `Anuluj` is a secondary button (`.btn-secondary`) sitting in a `.form-actions` row beside the green submit button, vertically aligned, on `new`/`edit`/`from-image`
