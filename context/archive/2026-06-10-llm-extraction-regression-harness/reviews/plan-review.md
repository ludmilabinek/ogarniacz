<!-- PLAN-REVIEW-REPORT -->
# Plan Review: LLM Extraction Regression Harness

- **Plan**: `context/changes/llm-extraction-regression-harness/plan.md`
- **Mode**: Deep
- **Date**: 2026-06-11
- **Verdict**: REVISE
- **Findings**: 1 critical, 3 warnings, 1 observation

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| End-State Alignment | PASS |
| Lean Execution | WARNING |
| Architectural Fitness | WARNING |
| Blind Spots | FAIL |
| Plan Completeness | WARNING |

## Grounding

5/5 paths ✓, 8/8 symbols ✓, brief↔plan ✓

Verified during review:
- `LlmVisionClientTest.java:26` — `MockitoBean` import ✓
- `LlmVisionClientTest.java:36-37` — `@MockitoBean ChatModel chatModel` ✓
- `LlmVisionClientTest.java:41-44` — `getDefaultOptions()` `@BeforeEach` stub ✓
- `LlmVisionClientTest.java:151-153` — `chatResponseOf(String)` helper ✓
- `LlmVisionSmokeTest.java:25` — `@EnabledIfEnvironmentVariable(OGARNIACZ_LIVE_SMOKE)` ✓
- `LlmVisionSmokeTest.java:28` — `BUDGET_MS = 55_000L` ✓
- `LlmVisionSmokeTest.java:36-38` — `ClassPathResource("llm/sample-announcement.png")` ✓
- `OpenRouterLlmVisionClient.java:96` — `Pattern.compile("\\[.*\\]", Pattern.DOTALL)` (greedy regex finding) ✓
- `LlmExtractionResult.java:7-10` — `rawResponse` field ✓
- `application.properties:32` — `spring.ai.openai.chat.options.model=google/gemini-2.5-flash` ✓

## Findings

### F1 — Recording mode writes to build/, not src/

- **Severity**: ❌ CRITICAL
- **Impact**: 🔬 HIGH — architectural stakes; think carefully before deciding
- **Dimension**: Blind Spots
- **Location**: Phase 1 #1 (`listFixtures`), Phase 3 #1 (`writeRecordingAtomically`)
- **Detail**: The plan resolves the fixture directory via `LlmTestFixtures.class.getResource("/llm/fixtures").toURI()`. Under Gradle, the test classpath is `build/resources/test/`, not `src/test/resources/`. `processTestResources` copies the source tree into the build dir, and the test runs against that copy. Phase 3's atomic write therefore lands at `build/resources/test/llm/fixtures/<id>/recorded-response.json` — *not* in `src/test/resources/llm/fixtures/<id>/`. Consequences a user will hit: (a) after recording-mode runs, `git status` shows no new files in `src/test/resources/` — the user thinks recording silently failed; (b) the newly captured fixture is wiped by the next `./gradlew clean`; (c) the Phase 3 manual verification step ("files appear on disk") will pass against the build copy but mislead the operator. The recording-mode workflow — the load-bearing automation the plan introduces — does not produce a commit-able artifact.
- **Fix A ⭐ Recommended**: Use a source-tree path for writes
  - Strength: Gradle runs `./gradlew test` with CWD = project root. `Paths.get("src/test/resources/llm/fixtures")` resolves deterministically; reads still use the classpath URL so CI / IDE classpath lookup keeps working unchanged.
  - Tradeoff: Two paths to keep in sync (read = classpath, write = source). Worth one helper method named explicitly.
  - Confidence: HIGH — the recording-only write path is the only place that needs the source location; everything else stays classpath-resolved.
  - Blind spot: IDE test runs may use a different CWD; the recording path is operator-run via Gradle in practice, but worth a one-line note in `fixtures/README.md`.
- **Fix B**: Pass the source dir as a system property from build.gradle
  - Strength: No hard-coded path string in the test; `build.gradle` drives it via `systemProperty 'ogarniacz.fixtures.dir', file('src/test/resources/llm/fixtures').absolutePath`.
  - Tradeoff: Adds a build.gradle change to a "no production code" plan and a new contract surface to remember.
  - Confidence: MEDIUM — works; heavier than the problem warrants.
  - Blind spot: A contributor running tests via IDE without Gradle bypasses the property; the helper needs a fallback.
- **Decision**: FIXED via Fix A (added `sourceFixtureDir(Path)` helper to Phase 1 #1; Phase 3 #1 now checks existence and writes against the source tree path; manual verification updated to reference `src/test/resources/...` paths).

### F2 — Event order in per-fixture diff is unspecified

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Blind Spots
- **Location**: Phase 2 #1 step 6-7 (diff loop)
- **Detail**: Phase 2's parameterised test pairs `result.proposedEvents()` with `expected.json` events **by index** ("for each pair, run `LlmTestFixtures.diff(...)`"). The plan does not state whether the model is guaranteed to emit events in a stable order, whether `expected.json` events should match the recorded raw order, or what happens if a future model returns the same events reshuffled. The recorded-mock variant is robust here because the raw response is frozen — order stays identical. But the live variant in grading mode can perfectly reproduce the events and still fail the diff because indices misalign. This silently inverts risk #1's signal: a *correct* extraction would surface as a "regression".
- **Fix**: Sort both sides by `(date, time-null-first, title)` before the pairwise diff. Document the canonical order in `fixtures/README.md` §"expected.json schema" so a curator knows the file order is informational, not load-bearing.
- **Decision**: FIXED (added `canonicalSort(List<ProposedEvent>)` to Phase 1 #1 contract; Phase 2 #1 sorts both sides before pairwise diff; Phase 3 inherits via "same diff loop as Phase 2"; Phase 1 #3 unit test gains canonicalSort cases; Phase 1 #5 README schema notes array order is informational).

### F3 — "Copy verbatim from raw response section" is ambiguous

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 1 #6 + Phase 1 Manual Verification step 3
- **Detail**: `LlmVisionSmokeTest:45-54` prints `raw response:` then `<content>` then `=====================================`. The plan instructs "copied verbatim from the 'raw response:' section" without saying whether to include the `raw response:` header line or the trailing `===` delimiter. A copy that grabs too much (header line included) produces a recording whose first chars are "raw response:", which the greedy regex at `OpenRouterLlmVisionClient.java:96` will still parse, masking the mistake — until a future curator regenerates and sees a different result. This is exactly the kind of low-visibility seed-fixture defect that infects every downstream diff.
- **Fix**: Make the README + Phase 1 step explicit: "Copy the lines between (but not including) `raw response:` and the closing `=========` delimiter. The file should contain only the raw model output — no header line, no trailing delimiter, no leading or trailing blank line."
- **Decision**: FIXED (Phase 1 #6 + Manual Verification step 3 now explicitly bound the copy to lines strictly between the `raw response:` header and the `=========` delimiter; noted that an over-inclusive copy survives the diff silently and only surfaces on regeneration).

### F4 — Hand-rolled JSON in `writeRecordingAtomically`

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Architectural Fitness
- **Location**: Phase 3 #1 (`writeRecordingAtomically` helper)
- **Detail**: The plan builds `recorded-meta.json` as a hand-concatenated string ("no Jackson round-trip required for two fields"). The harness already autowires `ObjectMapper` (used everywhere else, including `LlmTestFixtures.loadMeta`), so hand-rolling produces an inconsistent pattern with no upside. It's also fragile if `configuredModel` ever contains a `"` or `\` (unlikely today with `google/gemini-2.5-flash`, but no guard against tomorrow's choice).
- **Fix**: `objectMapper.writeValueAsString(new FixtureMeta(model, Instant.now().toString()))`. Same atomic-move semantics; one consistent serializer; zero new contract surface.
- **Decision**: FIXED (writer is now an instance method so autowired `objectMapper` is in scope; sidecar built via `objectMapper.writeValueAsString(new FixtureMeta(...))`, matching `loadMeta(...)` round-trip).

### F5 — `.gitkeep` is redundant

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Lean Execution
- **Location**: Phase 1 #4 (fixture directory scaffold)
- **Detail**: Phase 1 commits `fixtures/.gitkeep`, `fixtures/README.md`, and the complete `01-sample/` seed in the same phase. The directory becomes non-empty the moment `README.md` lands, so `.gitkeep` exists only if Phase 1's commits are sub-divided. The plan does not require sub-commits.
- **Fix**: Drop step 4. Land README + seed in one commit; the directory is committable without a placeholder.
- **Decision**: FIXED (removed Phase 1 #4 `.gitkeep` step; renumbered subsequent steps 5→4 (README), 6→5 (seed); fixed two cross-references that pointed to "change #5").
