<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: UI polish — Home button + Polish-language consistency

- **Plan**: context/changes/ui-polish-home-and-pl/plan.md
- **Scope**: Full plan (Phase 1 + Phase 2)
- **Date**: 2026-06-23
- **Verdict**: NEEDS ATTENTION
- **Findings**: 0 critical · 3 warnings · 3 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | WARNING (auth-logo height drift) |
| Scope Discipline | PASS |
| Safety & Quality | WARNING (brand-link keyboard focus) |
| Architecture | PASS |
| Pattern Consistency | WARNING (row-action danger focus pairing) |
| Success Criteria | PASS (`./gradlew test` green; both guard greps empty) |

## Success criteria — automated verification log

- `./gradlew test` → `BUILD SUCCESSFUL`
- `grep -RIn '>Log in<|>Settings<|>Add event<|>Log out<|>Sign up<|>Signed in as|>No events yet|>Save event<|>Edit event<|>Calendar subscription<|>Cancel<' src/main/resources/templates/` → empty
- `grep -RIn '"Email already in use|"Password must be at least 12' src/main/java/` → empty

## Findings

### F1 — Brand-link `:focus-visible` strips outline without a strong replacement

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality (A11y)
- **Location**: src/main/resources/templates/fragments/layout.html:22-24
- **Detail**: A single rule pairs `:hover` and `:focus-visible` on the brand link with `opacity: 0.8; outline: none;`. The only keyboard signal is a 20% opacity drop on a small logo over a near-white topbar; hover and focus look identical. Plan Phase 2 §11 set a precedent (form inputs use `box-shadow: 0 0 0 3px #d2f4dd`) — the brand link is the only interactive element in the new code that skips it.
- **Fix**: Split the focus rule and add a green ring: `header.topbar .brand:focus-visible { outline: none; box-shadow: 0 0 0 3px #d2f4dd; }`. Matches form-input focus styling.
- **Decision**: FIXED

### F2 — Auth-logo height deviates from plan (128px vs 96px)

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: src/main/resources/templates/fragments/layout.html:33
- **Detail**: `main.auth-page .auth-logo { height: 128px; … }` — Plan Phase 2 §9 contract specifies `height 96px`. Implementation went 33% larger. No test pins this; nothing else broke. Deviation never recorded in plan or change.md.
- **Fix A ⭐ Recommended**: Update plan §9 to record "96px → 128px after visual review" as an addendum.
  - Strength: Preserves the implemented visual already signed off (manual checks 2.4/2.10 passed).
  - Tradeoff: Plan becomes a small moving target — one addendum line.
  - Confidence: HIGH — manual click-through was already approved.
  - Blind spot: None significant (single CSS rule, no functional impact).
- **Fix B**: Shrink to the planned 96px.
  - Strength: Plan stays source of truth verbatim.
  - Tradeoff: Visual change to approved auth pages; would warrant a re-screenshot.
  - Confidence: MED — depends on whether the larger logo was intentional.
  - Blind spot: Not clear if the size bump was conscious or a copy-paste slip.
- **Decision**: FIXED (Fix A — addendum in plan §9)

### F3 — `.row-action--danger:focus-visible` has no red-tint pairing

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/main/resources/templates/fragments/layout.html:67-73
- **Detail**: `.event-list .row-action:hover, .event-list .row-action:focus-visible { background: #f6f8fa; … }` pairs hover and focus for the standard row-action. The danger variant at line 73 only sets `:hover`. Keyboard focus on Delete falls through to the neutral grey rule, so mouse users see a red tint on Delete and keyboard users see grey. Inconsistent with the rest of the row-action set and with Manual check 2.7.
- **Fix**: Change line 73 to `.event-list .row-action--danger:hover, .event-list .row-action--danger:focus-visible { background: #ffebe9; }`.
- **Decision**: FIXED

### F4 — Brand-link image labelled twice (aria-label + non-empty alt)

- **Severity**: 🔎 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality (A11y)
- **Location**: src/main/resources/templates/fragments/layout.html:149-151
- **Detail**: The anchor has `aria-label="Ogarniacz — strona główna"`; the brand-mark has `alt="Ogarniacz"`; the wordmark correctly has `alt=""`. The aria-label provides the accessible name; the non-empty alt on the mark adds redundant content. Plan §9 specified `alt=""` on the wordmark (matched) but left the mark unspecified — implementation picked a non-empty alt.
- **Fix**: Set `alt=""` on `brand-mark` too — the anchor's aria-label already names the link.
- **Decision**: FIXED

### F5 — Plan claims `#1a7f37` is AA-safe with white text; actual ratio is AA Large only

- **Severity**: 🔎 OBSERVATION
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality (A11y)
- **Location**: Plan "Desired End State" + layout.html:39-44, 86-94
- **Detail**: `form.stacked button { background: #1a7f37; color: #fff; }` and `.primary-action { color: #1a7f37 }` on white both compute to ~4.38:1 contrast. WCAG 2.1 AA wants 4.5:1 for normal text and 3:1 for large text. Buttons render at inherited body font (no explicit `font-size`), so they're "normal" — just under AA. Plan claims "AA-safe with white text"; that claim is slightly inaccurate. Hover `#15692e` is ~6.9:1 (clears AA easily).
- **Fix A**: Swap base button background to `#15692e` (matches current hover, clears 4.5:1).
  - Strength: Eliminates the borderline ratio entirely; one color edit.
  - Tradeoff: Hover/base distinction collapses unless hover gets bumped to a different shade.
  - Confidence: MED — solves the math but changes the visual feel.
  - Blind spot: Whether the user-approved "green pivot" feel survives darker base.
- **Fix B**: Accept marginal ratio; update plan footnote from "AA-safe" to "AA Large only".
  - Strength: No visual change; preserves the approved feel.
  - Tradeoff: Documentation drift on accessibility claim.
  - Confidence: HIGH — most buttons render at default body size (≥16px) which qualifies as "large" in AA-Large bold-button cases; the actual fail surface is narrow.
  - Blind spot: A future contrast-stricter standard (WCAG 3 APCA) would still flag this.
- **Decision**: DISMISSED — finding's contrast math was wrong. Verified ratio of `#1a7f37` on white is 5.08:1, which clears WCAG 2.1 AA for normal text (4.5:1). Plan §"Desired End State" updated to record the verified value explicitly.

### F6 — PluralsTest contains 4 cases beyond the plan table

- **Severity**: 🔎 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Scope Discipline
- **Location**: src/test/java/com/example/app/util/PluralsTest.java:12-40
- **Detail**: Plan Phase 1 §2 mandated 13 cases (0,1,2,4,5,11,12,14,21,22,25,102,112). Implementation adds 3, 13, 23, 24. Strengthens the contract; harmless coverage extension.
- **Fix**: Accept as-is (no change needed).
- **Decision**: ACCEPTED — extra cases strengthen the contract harmlessly.
