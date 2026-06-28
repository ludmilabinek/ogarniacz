# E2E Testing Rules

The rules an agent (or a human) must read before generating or editing any spec
under `e2e/tests/`. Two layers:

1. **Generic Playwright rules** — sourced from `.claude/skills/10x-e2e/references/e2e-quality-rules.md`.
2. **Ogarniacz-specific conventions** — the project-side bits the generic rules don't know.

When a new spec is written by the `/10x-e2e` skill, both layers shape it; the skill's `references/e2e-anti-patterns.md` is the REVIEW checklist.

---

## 1. Generic Playwright rules

- Use `getByRole`, `getByLabel`, `getByText` as primary locators. Fall back to `getByTestId` only when accessibility attributes are ambiguous.
- Never use CSS selectors, XPath, or DOM structure for locating elements.
- Each test must be independently runnable — no shared state between tests.
- Never use `page.waitForTimeout()`. Wait for specific conditions: `toBeVisible()`, `waitForURL()`, `waitForResponse()`.
- Assert the business outcome, not implementation details. The control question: *would this assertion fail if its `test-plan.md` risk materialized?*
- Use unique identifiers (e.g. `Date.now()` suffix) for test data to avoid collisions in parallel runs. Clean up in `afterEach`.
- Use `storageState` for authentication — never log in through UI in individual tests.
- Name the test after the risk: `test('flashcard data persists after page reload', ...)`, not `test('test 1', ...)`.

The five anti-patterns to review against: hallucinated assertion, brittle selector, shared state between tests, `waitForTimeout` instead of state, no cleanup. Full text in [.claude/skills/10x-e2e/references/e2e-anti-patterns.md](../.claude/skills/10x-e2e/references/e2e-anti-patterns.md).

## 2. Ogarniacz-specific conventions

- **Location:** specs under `e2e/tests/*.spec.ts`. One test per file. `seed.spec.ts` is the exemplar — model every new spec on its patterns; do not regenerate it.
- **Runner:** `cd e2e && npx playwright test [--grep ...] --reporter=list` (single-spec: `npx playwright test path/to.spec.ts`). The `webServer` block in `e2e/playwright.config.ts` spawns `./gradlew bootTestRun --args='--spring.profiles.active=e2e'`, so the suite cold-starts the app once and reuses it across specs.
- **Spring profile `e2e`** swaps `OpenRouterLlmVisionClient` for `StubLlmVisionClient` (`@Primary` under that profile) returning 3 deterministic events. This is for **lifecycle / UI risks only**. LLM-quality risk is owned by `LlmExtractionRecordedRegressionTest` — never re-test it through Playwright.
- **Locale is pinned to English** under the e2e profile (`spring.web.locale=en`, `spring.web.locale-resolver=fixed`) so `#temporals.format(date, 'EEE d MMM yyyy')` is deterministic across CI / JVM / browser `Accept-Language`. Specs assert on the formatted string; relying on locale defaults would be flaky.
- **Auth:** `auth.setup.ts` signs up a unique user via `/signup` and saves `storageState` to `playwright/.auth/user.json`. Every chromium-project test starts authenticated. **Do not log in through the UI in individual specs.**
- **CSRF:** Thymeleaf forms inject `_csrf` automatically — when you submit via `getByRole('button').click()` the token flows. `page.request.post(...)` from inside a test bypasses the form and must carry the token manually.
- **Polish UI labels:** locators use the rendered Polish copy — `getByRole('button', { name: 'Zapisz wydarzenie' })`, `getByLabel('Tytuł')`. The accessibility tree reflects the rendered Thymeleaf output; do not translate labels in tests.
- **Risk anchor:** every spec's name and provenance header must trace back to a `context/foundation/test-plan.md` risk (#1–#7). Specs without an anchor fail review.
- **Strict assertions on data flow:** when a spec covers a promotion / lifecycle risk, assert on the full data shape rendered to the user (title + date + time) not just title presence. A title-only assertion silently passes a date-corruption bug — confirmed by deliberate-break #2 in `extraction-lifecycle-accept.spec.ts`'s VERIFY step.

## 3. Workflow

To generate a new spec: invoke the `/10x-e2e` skill. It will:

1. Gate the risk (browser-level fit + feature presence + test absence).
2. PLAN — map the flow against the seed exemplar.
3. GENERATE — write the spec from seed + these rules.
4. REVIEW — check against the five anti-patterns; re-prompt by name.
5. VERIFY — run green, then deliberately break the production behavior and confirm RED.

Do not hand-write specs that skip these steps.
