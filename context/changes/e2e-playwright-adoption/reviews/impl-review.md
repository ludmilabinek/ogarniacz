<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: E2E adoption for Risk #3 (commit 2fc9e7d)

- **Plan**: standalone — no `plan.md`. Plan-equivalent = `context/foundation/test-plan.md` §3 e2e row + §6.3 cookbook + Risk #3, `e2e/E2E_RULES.md`, `.claude/skills/10x-e2e/references/{e2e-anti-patterns,seed-test-pattern,e2e-quality-rules}.md`
- **Scope**: full commit (`2fc9e7d test(e2e): adopt Playwright for browser-only lifecycle risks (risk #3)`)
- **Date**: 2026-06-28
- **Verdict**: NEEDS ATTENTION → triaged 2026-06-28 (2 fixed · 4 skipped · 1 partially handled via doc edits)
- **Findings**: 0 critical · 2 warnings · 5 observations
- **Risk anchor under review**: test-plan §2 Risk #3 — accepted events flow through upload→poll→review→accept lifecycle to /app, rejected do not, rendered date/time matches submitted values. Strict assertion was verified via two-scenario deliberate-break (commented `eventRepository.save(event)` → red; `decision.getEventDate()` substituted with `LocalDate.now()` → red; looser title-only assertion would have passed the second break).

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | WARNING (F1 — Risk #3 anchor drift) |
| Scope Discipline | PASS |
| Safety & Quality | PASS |
| Architecture | PASS (bean-level stub, profile isolation, bootTestRun) |
| Pattern Consistency | WARNING (F2 — seed exemplar drift) |
| Success Criteria | PASS (deliberate-break VERIFY documented in commit body) |

## §6.3 doc-vs-code drift check

Verified each §6.3 claim against the commit:

- Reference test paths exact (`extraction-lifecycle-accept.spec.ts` + `seed.spec.ts`) — ✅
- "Playwright 1.61.1" matches `e2e/package.json` — ✅
- "webServer spawns `./gradlew bootTestRun --args='--spring.profiles.active=e2e'`" matches `playwright.config.ts:35` — ✅
- "CI gets `reuseExistingServer: false`" — matches `!process.env.CI` at `playwright.config.ts:38` — ✅
- Spring profile description (H2 in-memory, `ddl-auto=create-drop`, `MODE=PostgreSQL`, locale=en pinned, `schema-e2e.sql`) matches `application-e2e.properties` line-for-line — ✅
- `StubLlmVisionClient` described as `@Primary @Profile("e2e")` returning 3 deterministic events — matches the bean exactly — ✅
- "LLM-quality risk stays owned by `LlmExtractionRecordedRegressionTest`; do not re-test through Playwright" — spec's docblock repeats this — ✅
- "`auth.setup.ts` signs up a unique user per Playwright invocation, saves session cookie to `playwright/.auth/user.json`" — matches — ✅
- `E2E_RULES.md` cross-reference for anti-patterns + Ogarniacz conventions — file exists and content tracks the references — ✅
- **One drift, covered by F1 below**: §6.3 says the spec "pins risk #3 (accepted events flow to /app)", but §2's wording of Risk #3 is feed-centric ("missing from / stale in the parent's iCal feed"). §6.3's framing is internally consistent with what the spec asserts; it diverges from §2.

Otherwise §6.3 is a faithful description of what's in the commit.

## Findings

### F1 — Risk #3 anchor drift: spec asserts on /app only, never on /calendar/{token}.ics

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real anchor mismatch; pause to reconcile vocabulary
- **Dimension**: Plan Adherence
- **Location**:
  - `e2e/tests/extraction-lifecycle-accept.spec.ts:5-12` (spec docblock paraphrases Risk #3 to add "and the iCal feed")
  - `context/foundation/test-plan.md:48` (Risk #3 wording — feed-centric)
  - `context/foundation/test-plan.md:149` (§6.3 reference paraphrases down to "accepted events flow to /app")
- **Detail**: Risk #3 in §2 Risk Map is feed-centric: *"An accepted event is missing from / stale in the parent's iCal feed, or a deleted event still appears on the next poll — feed freshness or deletion propagation regresses."* The new spec asserts on /app HTML only — no GET against `/calendar/{token}.ics`. The spec docblock paraphrases Risk #3 to add "and the iCal feed", and §6.3 paraphrases it down to "accepted events flow to /app" — three different framings of the same risk number. The risk the spec actually protects (lifecycle promotion correctness from upload → /app, observable only in a real browser) is real and valuable, but it isn't what §2 calls "Risk #3" — that's Phase 2's integration-tests territory and is marked complete (`CalendarControllerTest` etc.). The spec is complementary to Phase 2, not a duplicate of it, but the anchor naming hides that. **Why it matters for protection**: if you treat this spec as "Risk #3 is now also E2E-covered", you may think the feed half is double-covered when in fact only the /app half is browser-tested. A regression where promotion writes the `Event` row but the feed serializer drops it would pass this spec.
- **Fix A ⭐ Recommended**: Expand Risk #3 wording in §2 to include the parent's /app view alongside the feed surface, OR split Risk #3 into #3a (feed) + #3b (/app view). Update §3 phase row + §6.3 reference text to use the new wording. No code change.
  - Strength: Aligns naming with reality. Phase 2 covers #3a, this spec covers #3b. Both halves now have a clear owner.
  - Tradeoff: Touches §2/§3/§6.3 wording — a doc edit that needs consistency across three sections.
  - Confidence: HIGH — three sections already drift on the same point.
  - Blind spot: Other phases that cite Risk #3 by number would need re-reading to confirm they meant the same half.
- **Fix B**: Add a feed-side assertion to the spec — after the inline cleanup, GET `/calendar/{token}.ics` with the seeded user's token and assert `acceptedSpotkanie`/`acceptedWarsztaty` appear and `rejectedWycieczka` doesn't. Closes the loop the docblock promises.
  - Strength: Spec becomes a true end-to-end on both surfaces.
  - Tradeoff: Couples the spec to the iCal token surface — needs the mint-token path during signup, and the assertion duplicates what Phase 2 integration already covers.
  - Confidence: MEDIUM — extra surface area for marginal added signal.
  - Blind spot: Whether the user object from `/signup` auto-mints a token or requires a settings page round-trip.
- **Decision**: FIXED via Fix A — expanded Risk #3 wording in `test-plan.md` §2 (Risk Map row + Risk Response Guidance row), updated §6.3 paraphrase to call out "/app half of Risk #3" with Phase 2 explicitly owning the feed half, aligned the spec docblock in `extraction-lifecycle-accept.spec.ts`, bumped §8 freshness ledger (§1–§5 reviewed: 2026-06-28).

### F2 — Seed exemplar (seed.spec.ts) drifts from its own docblock claims

- **Severity**: ⚠️ WARNING
- **Impact**: 🔬 HIGH — exemplar shapes every future spec; drift here propagates as anti-patterns in generated tests
- **Dimension**: Pattern Consistency
- **Location**: `e2e/tests/seed.spec.ts:9-12` (docblock) vs. `:18-32` (body)
- **Detail**: The docblock makes two claims the body does not back:
  1. *"full setup → action → assertion → cleanup in one block"* — but lines 22-31 add a Seed event with `Date.now()` suffix and never delete it. With `reuseExistingServer:true` locally (the default), H2 persists across runs in the same JVM and rows pile up. This is anti-pattern #5 from `e2e-anti-patterns.md` verbatim.
  2. *"Risk-tied assertion — the test name binds it to a concrete risk ... (here: #3, accepted event visible on /app + iCal feed)"* — but the test goes through `/events/new` (manual add), never crosses the extraction pipeline or the feed, and the assertion is just "title still visible after reload". Risk #3 is feed-centric per §2; the seed never touches the feed.

  **Why it matters**: every new E2E spec is supposed to be modeled on `seed.spec.ts` (`E2E_RULES.md` §2 first bullet: *"do not regenerate it"* + `seed-test-pattern.md` *"What you show is what you get"*). The lifecycle spec already got the cleanup right (lines 60-69 `afterEach` + lines 151-154 inline). But the next agent-generated spec that copies from `seed.spec.ts` inherits "no cleanup" and a vague risk anchor by default.
- **Fix ⭐ Recommended**: Add an `afterEach` (or inline tail) to `seed.spec.ts` that deletes the Seed event by title, and either re-anchor the spec to a closer-fit risk or call it out as "no risk anchor — exemplar of the four patterns only". Match the cleanup shape used in `extraction-lifecycle-accept.spec.ts` so the exemplar advertises the cleanup pattern, not just claims it.
  - Strength: Closes the docblock-vs-body gap directly; future generated specs inherit a correct exemplar.
  - Tradeoff: Minor — ~8 lines of cleanup, matches what `extraction-lifecycle-accept` already does.
  - Confidence: HIGH — the cleanup shape is already proven in the lifecycle spec; copying it here is mechanical.
  - Blind spot: None significant.
- **Decision**: FIXED — rewrote `seed.spec.ts`: added inline cleanup (delete by title + `toHaveCount(0)` assertion), added the auto-accept `dialog` handler, replaced the Risk #3 anchor with "no risk anchor — exemplar of the four patterns only", and renamed the 4th pattern from "Risk-tied assertion" to "Unique data + inline cleanup" so the docblock now matches what the body demonstrates.

### F3 — `reuseExistingServer: !process.env.CI` lets a stale `bootRun` win locally

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — documented in §6.3; port 8089 is e2e-specific so collision risk is small
- **Dimension**: Pattern Consistency / Reliability
- **Location**: `e2e/playwright.config.ts:38`
- **Detail**: If a developer ever has `./gradlew bootRun --args='--server.port=8089'` running (production profile, real Postgres, real OpenRouter), the Playwright suite silently reuses it. The stub never wins, the locale isn't pinned, `schema-e2e` isn't loaded — the test fails confusingly. Test-plan §6.3 warns about this in prose but the config doesn't enforce profile-on-startup verification.
- **Fix**: Hit a marker endpoint instead of `/actuator/health` for the readiness probe — e.g. an e2e-only `/actuator/info` field that exposes `spring.profiles.active`, and short-circuit if it isn't `"e2e"`. Or leave it: the §6.3 prose warning is probably enough at MVP scale.
- **Decision**: SKIPPED — §6.3 prose warning is enough at MVP scale; port 8089 is e2e-specific so collision risk is small.

### F4 — `waitForLoadState('networkidle')` inside afterEach cleanup is inconsistent with the main test's `expect(...).toHaveCount(0)` pattern

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW
- **Dimension**: Pattern Consistency
- **Location**: `e2e/tests/extraction-lifecycle-accept.spec.ts:68`
- **Detail**: The Playwright team discourages `networkidle` for navigation: it waits for an arbitrary 500 ms of network silence and can flake under server load or background polling. The main test body's inline cleanup at lines 151-154 already uses `expect(eventRow(title)).toHaveCount(0)` which web-first-retries against a concrete condition — that's the pattern the rest of this spec follows.
- **Fix**: Replace `await page.waitForLoadState('networkidle')` with `await expect(row).toHaveCount(0)` (the row being deleted), matching the main-test pattern.
- **Decision**: FIXED — refactored the afterEach to pre-collect leftover `E2E accepted*` titles via `allTextContents()`, then iterate with the same `getByRole('listitem').filter({ hasText: title })` + `expect(row).toHaveCount(0, { timeout: 10_000 })` shape used at lines 151-154. networkidle removed entirely.

### F5 — Duplicate `page.on('dialog', d => d.accept())` registration

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW
- **Dimension**: Pattern Consistency
- **Location**: `e2e/tests/extraction-lifecycle-accept.spec.ts:63` + `:80`
- **Detail**: Both `afterEach` (line 63) and the main test (line 80) register a dialog handler on the same per-test `page`. Each `confirm()` now fires two handlers; the second `.accept()` throws on an already-handled dialog and is swallowed by `.catch(() => {})`. Functionally fine today, but the redundant registration plus swallow-and-move-on makes future dialog logic harder to reason about.
- **Fix**: Register once in `afterEach` (covering both the test body's Usuń and the cleanup loop's Usuń) and drop the in-test registration, OR vice versa.
- **Decision**: SKIPPED — on closer look the two registrations actually serve different lifecycle scopes (the in-test handler covers dialogs raised by the inline cleanup at lines 151-154; the afterEach handler covers dialogs raised by the failure-path cleanup loop). Dropping either creates risk if Playwright's listener-lifecycle behavior shifts between versions; the redundant `.accept().catch()` is harmless defensive code.

### F6 — Upload fixture reaches into a production static asset

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW
- **Dimension**: Architecture
- **Location**: `e2e/tests/extraction-lifecycle-accept.spec.ts:56-57`
- **Detail**: `SAMPLE_PNG` points at `src/main/resources/static/img/logo-mark.png` — a production brand asset. If the asset is renamed, removed, or swapped for an SVG for product reasons, this test breaks for a reason unrelated to Risk #3. The stub returns deterministic events regardless of the bytes uploaded, so the test doesn't care what the image is — only that it's a valid PNG and ≤ `max-file-size`.
- **Fix**: Ship a dedicated tiny PNG under `e2e/fixtures/sample.png` and point `SAMPLE_PNG` there. Mirrors the `src/test/resources/uploads/sample.jpg` pattern §6.6 already uses on the Java side.
- **Decision**: SKIPPED — coupling accepted for now; revisit if `logo-mark.png` ever moves or the asset surface churns.

### F7 — `STUB_DATES` reimplements Spring's date formatter in JavaScript

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW
- **Dimension**: Pattern Consistency
- **Location**: `e2e/tests/extraction-lifecycle-accept.spec.ts:39-51`
- **Detail**: The `WEEKDAYS`/`MONTHS` arrays + `springDate()` helper compute `"Thu 15 Jan 2099"` so the JS side mirrors what `#temporals.format(date, 'EEE d MMM yyyy')` emits under `locale=en`. The dates are hard-coded in `StubLlmVisionClient` (15/20/25 January 2099). The Gregorian calendar is stable and `Locale.ENGLISH` symbols are stable, so the helper works — but it duplicates a contract that lives in Thymeleaf and adds a moving part that has to stay in sync with any locale change (a comma added by a future Boot upgrade, etc.). Hard-coding the three expected strings (`"Thu 15 Jan 2099"`, `"Tue 20 Jan 2099"`, etc.) collapses the brittleness to a single visible-string mismatch on format drift.
- **Fix**: Replace the helper + arrays + `STUB_DATES` computation with three literal strings tied to the stub's fixed dates. ~6 lines shorter, zero derivation.
- **Decision**: SKIPPED — current helper works because Gregorian + `Locale.ENGLISH` symbols are stable; brittleness is theoretical at this point.

## Closing notes

- The main lifecycle spec itself is solid: real boundaries kept real (Spring Security, multipart, polling JS, JPA, Thymeleaf), only the LLM stubbed at bean level; assertion strictness verified via the documented two-scenario deliberate-break; role-based locators throughout; `storageState` auth; `Date.now` uniqueness; `afterEach` cleanup. The deliberate-break log in the commit body is exactly the discipline the e2e skill prescribes.
- No critical findings (no security, no destructive paths, no false-pass-on-Risk-#3 assertion in the lifecycle spec).
- The two warnings cluster around naming/docs, not protection. F2 (seed exemplar) is the higher-impact one because it's the template every generated spec is modeled on — fixing it early prevents a class of regressions in future specs.
