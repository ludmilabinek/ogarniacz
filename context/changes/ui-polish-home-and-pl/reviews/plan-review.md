<!-- PLAN-REVIEW-REPORT -->
# Plan Review: UI polish — Home button + Polish-language consistency

- **Plan**: `context/changes/ui-polish-home-and-pl/plan.md`
- **Mode**: Deep
- **Date**: 2026-06-23
- **Verdict**: REVISE
- **Findings**: 0 critical, 2 warnings, 2 observations

## Verdicts

| Dimension             | Verdict |
|-----------------------|---------|
| End-State Alignment   | PASS    |
| Lean Execution        | PASS    |
| Architectural Fitness | WARNING |
| Blind Spots           | PASS    |
| Plan Completeness     | WARNING |

## Grounding

14/14 file paths exist; 6/6 line-range probes match the plan's claims (topbar fragment at `fragments/layout.html:91-99`, `EventReviewController.java:166-167` flash literal, `SignupForm.java:15` `@Size` message, `SignupController.java:64,73` `rejectValue` calls, `EventReviewControllerTest.java:105` assertion, the three test files with EN literals). Brief↔plan consistent: phases, decisions, scope match.

## Findings

### F1 — Plan's new `.row-action--danger:hover` rule loses to existing higher-specificity rule

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Architectural Fitness
- **Location**: Phase 2 — #1 Topbar fragment + global styles (plan line 138)
- **Detail**: Plan adds `.row-action--danger:hover { background: #ffebe9; }` (specificity 0,1,1). But `fragments/layout.html:50` already has `.event-list .row-action--danger:hover { background: #fff5f5; }` (specificity 0,2,1). The delete button on `/app` is always rendered inside `<ul class="event-list">`, so the existing rule wins and the delete-button hover stays `#fff5f5`, not the planned `#ffebe9`. Phase 2 manual-verification bullet 2.7 ("hover/focus states are visible") will pass under both colors, masking that the styling did not change. The same risk applies to `.row-action:hover` (specificity 0,1,1) vs. the implicit specificity-0,2,1 `.event-list .row-action` selectors that style the non-hover state.
- **Fix A ⭐ Recommended**: Replace the existing rule, don't add a sibling
  - Strength: Single rule per state — no dead CSS; keeps the existing specificity convention used everywhere in this stylesheet.
  - Tradeoff: Slightly larger edit footprint in Phase 2 (touches one existing rule in addition to adding new ones).
  - Confidence: HIGH — selectors and current source-order are confirmed.
  - Blind spot: None significant.
  - Edit `layout.html:50` from `.event-list .row-action--danger:hover { background: #fff5f5; }` → `.event-list .row-action--danger:hover { background: #ffebe9; }`. Add the new `.row-action:hover, .row-action:focus-visible` rules with selector `.event-list .row-action:hover, .event-list .row-action:focus-visible` to match the prevailing specificity in this block.
- **Fix B**: Move the new rules outside `.event-list` and bump specificity
  - Strength: Keeps "new CSS at the end of the block" pattern.
  - Tradeoff: Either introduces an awkward `body`-prefixed selector or duplicates the scope choice already made at line 50.
  - Confidence: MEDIUM — works, but resulting CSS is less idiomatic for this codebase.
  - Blind spot: Future addition of a non-`.event-list` row-action would not inherit these styles unless selectors are reworked again.
- **Decision**: PENDING

### F2 — `.primary-action` hover color contradicts itself across plan

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Desired End State (plan line 34) vs. Phase 2 contract (plan line 138)
- **Detail**: Desired End State says: `.primary-action` carries a subtle hover/focus state matching the existing palette (`#1f6feb` borderline, `#f6f8fa` hover fill). Phase 2 contract says: `.primary-action:hover, .primary-action:focus-visible` — `background: #ddf4ff; outline: none;`. `#f6f8fa` is a neutral gray (already used for the topbar background); `#ddf4ff` is a blue tint. They are visibly different fills. Implementer has no signal which one is the agreed final color.
- **Fix**: Pick one and state it in both places. Recommend `#ddf4ff` — it matches the existing `.primary-action` blue palette (`#1f6feb` border + text) and gives a stronger hover signal than the topbar-background gray. Update line 34 to read `#ddf4ff`.
- **Decision**: PENDING

### F3 — Topbar contract says "right side keeps email" but email currently lives on the LEFT

- **Severity**: 🔍 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 2 — #1 Topbar fragment (plan line 133)
- **Detail**: Current `fragments/layout.html:91-99` has `<span class="user-email">` as a left-aligned sibling of `<div class="topbar-right">`. The plan's contract says "right side keeps `<span class="user-email">` + Ustawienia link + Wyloguj form" — implying the email already lives on the right. It does not; the email moves from left to right as part of this change. Same wording in Desired End State (line 33) and Current State Analysis (line 11) is correct ("user email sits at the left as plain text"). Only the Phase 2 contract muddles it.
- **Fix**: Rephrase the Phase 2 #1 contract bullet from "right side keeps user-email + …" to "right side becomes user-email + Ustawienia link + Wyloguj form (email moves from the left so the brand-link can take its place)". One-line edit; prevents the implementer from leaving the email on the left next to the new brand-link.
- **Decision**: PENDING

### F4 — `settings.html` limitations `<aside>` and per-client paragraphs under-specified

- **Severity**: 🔍 OBSERVATION
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Plan Completeness
- **Location**: Phase 2 — #4 Settings page (plan lines 175-176)
- **Detail**: Phase 2 #4 says "Adapt the same pattern for Apple/Outlook/Thunderbird" and "Google-Calendar limitations <aside>: translate the paragraph keeping the technical numbers (12–24 godziny, czasem do 48)." But the `<aside>` (settings.html:28-32) is a multi-sentence paragraph with proper-noun lists and "subscribed calendars" appearing twice. "Outlook → Subscribe from web → paste the URL" needs a real PL rendition ("Subskrybuj z internetu"? "Dodaj subskrypcję z sieci"?) that isn't specified. Two consequences: (1) Implementer drafts a translation, user pushes back on wording — second polish pass is likely. The "Implementation Note" pause at the end of Phase 2 catches this, but the cost is a re-edit after the click-through, not before. (2) The `SettingsControllerTest` assertion is anchored on `subskrybować`, which appears in both the H2 ("Jak subskrybować") and likely in the translated `<aside>` body. The assertion will be a weaker anchor than the plan implies (it could match a survival of the word in either place, including a clunky translation).
- **Fix**: Spell out the target Polish for the four per-client paragraphs (e.g. Outlook: "Ustawienia → Dodaj kalendarz → Subskrybuj z internetu — wklej powyższy adres.") and provide a target rendition for the limitations `<aside>` body. Move the `SettingsControllerTest` anchor to a string only the H2 carries (e.g. "Jak subskrybować") so the assertion is unambiguous.
- **Decision**: PENDING
