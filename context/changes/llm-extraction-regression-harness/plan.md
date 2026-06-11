# LLM Extraction Regression Harness — Implementation Plan

## Overview

Build the **LLM extraction regression harness** that closes risks #1
(confidently-wrong extraction) and #2 (silent prompt/model-swap
regression) per `context/foundation/test-plan.md` §3 Phase 1. The
harness is two `@SpringBootTest` classes — a live, env-gated variant
and a CI-default recorded-mock variant — sharing one fixture set and
one per-field tolerant diff predicate. No production code is touched;
the entire change is additive under `src/test/`.

## Current State Analysis

The LLM extraction surface (`LlmVisionClient` + `OpenRouterLlmVisionClient`
+ `LlmExtractionResult`) is shipped and exercised by two existing test
classes:

- `LlmVisionClientTest` (Spring context + `@MockitoBean ChatModel`,
  6 mock-backed cases for parser invariants and the exception
  envelope) at `src/test/java/com/example/app/llm/LlmVisionClientTest.java:29-153`
- `LlmVisionSmokeTest` (live, env-gated by `OGARNIACZ_LIVE_SMOKE` +
  `OPENROUTER_API_KEY`, single sample-announcement assertion) at
  `src/test/java/com/example/app/llm/LlmVisionSmokeTest.java:23-61`

Neither test grades semantic correctness against a human label.
`test-plan.md` §3 Phase 1 status is **not started**; §4 stack row "LLM
extraction regression suite" is **none yet**; §6.4 cookbook is **TBD**.
The single existing test fixture is
`src/test/resources/llm/sample-announcement.png` (≈ 62 KB).

User-locked constraints (2026-06-10, before this plan):

- Mock seam = `@MockitoBean ChatModel` (extend `LlmVisionClientTest`'s
  pattern); no WireMock, no HTTP-level recording.
- Fixture curation = out of scope for this change beyond a single
  **seed fixture (`01-sample`)**; full 8–10 fixture set is a follow-up
  by the curator (Ludmiła) once the harness is in place.
- Live variant = operator-gated; **not** wired into CI.
- Greedy `Pattern.compile("\\[.*\\]")` at
  `src/main/java/com/example/app/llm/OpenRouterLlmVisionClient.java:96`
  is documented as a **finding only** (see `change.md`); the regex is
  **not** fixed in this change.
- PII policy: curator guarantees real announcements in `fixtures/`
  carry no PII (child/teacher/parent names, kindergarten name,
  address, phone, handwritten signatures). The harness ships the
  policy in `fixtures/README.md`; it does **not** design an
  anonymization system or a two-tier fixture model.
- Per-field tolerant diff: `date` / `time` exact (LocalDate / LocalTime
  equality + null-match); `title` / `requirements` normalised via
  NFC + `Locale.ROOT` lower-case + whitespace-collapse + strip;
  `requirements` tolerates null vs empty/blank; `notes` not graded.

### Key Discoveries

- **`ChatResponse` helper is already documented**:
  `src/test/java/com/example/app/llm/LlmVisionClientTest.java:151-153` —
  must be lifted into a shared `LlmTestFixtures` (open question #5
  pre-resolved).
- **`getDefaultOptions()` `@BeforeEach` stub is mandatory** for any
  `@MockitoBean ChatModel` test:
  `src/test/java/com/example/app/llm/LlmVisionClientTest.java:41-44` —
  without it the `ChatClient` fluent builder NPEs before reaching
  `.call()`.
- **`rawResponse` is preserved on `LlmExtractionResult`**:
  `src/main/java/com/example/app/llm/LlmExtractionResult.java:9` —
  the live variant uses it verbatim as the recorded payload.
- **`BUDGET_MS = 55_000L` is the established per-call ceiling**:
  `src/test/java/com/example/app/llm/LlmVisionSmokeTest.java:28` —
  the live variant reuses it per fixture.
- **Spring AI 2.0.0-M6 publishes no test-utility for ChatModel
  stubbing** (context7 confirmed): manual `chatModel.call(...)` stub is
  the idiomatic path; the existing pattern is already correct.
- **`@MockitoBean` is the Boot 4 mock-bean primitive**: imported
  from `org.springframework.test.context.bean.override.mockito.MockitoBean`
  (`LlmVisionClientTest.java:26`), supersedes `@MockBean`.
- **Per-controller `@SpringBootTest` lesson does NOT constrain harness
  class naming** (`context/foundation/lessons.md:47-55`) — the harness
  classes are per-feature regression tests, not controller tests.

## Desired End State

After this plan ships:

- `src/test/java/com/example/app/llm/LlmTestFixtures.java` exists as
  a package-private helper class with the canonical
  `chatResponseOf(String)`, JSON fixture loaders, sidecar-metadata
  reader, NFC-norm function, and per-field tolerant diff predicate.
- `src/test/java/com/example/app/llm/LlmTestFixturesDiffTest.java`
  exercises the diff predicate as a pure JUnit unit test (no Spring).
- `src/test/java/com/example/app/llm/LlmExtractionRecordedRegressionTest.java`
  runs in CI by default. For every fixture under
  `src/test/resources/llm/fixtures/<id>/`, it stubs `ChatModel.call(...)`
  with the captured raw response and asserts the observed
  per-field-diff divergence set against the documented
  `KNOWN_DIVERGENCES` entry (clean match — zero divergences — if the
  fixture isn't listed). After this plan, the seed fixture
  `01-sample` is on disk as a documented-divergence fixture and the
  class is **enabled**, not vacuously passing.
- `src/test/java/com/example/app/llm/LlmExtractionLiveRegressionTest.java`
  is env-gated by `OGARNIACZ_LIVE_SMOKE=true` + `OPENROUTER_API_KEY`.
  When `recorded-response.json` is missing AND
  `OGARNIACZ_RECORD_FIXTURES=true`, it atomically writes the captured
  raw response and a `recorded-meta.json` sidecar; when the recording
  exists, it grades against `expected.json` under the same diff
  predicate; `BUDGET_MS = 55_000L` per fixture. **Never** overwrites
  an existing `recorded-response.json` — re-recording requires
  `git rm` first.
- `src/test/resources/llm/fixtures/01-sample/` holds **all four
  files** (`image.png`, `expected.json`, `recorded-response.json`,
  `recorded-meta.json`).
- `src/test/resources/llm/fixtures/README.md` defines the PII policy
  (curator-owned), the fixture sourcing policy (≥ 1 ambiguous + ≥ 1
  unreadable in the eventual 8–10 set), the JSON schemas for
  `expected.json` and `recorded-meta.json`, and the
  *recordings-from-model, labels-from-human* rule.
- `context/foundation/test-plan.md` §3 Phase 1 row reflects
  `in progress` with the change folder linked; §4 row points at the
  shipped classes; §6.4 cookbook is filled in with concrete steps.
- `change.md` carries a **Findings** section noting the greedy-regex
  observation at `OpenRouterLlmVisionClient.java:96` as a separate
  follow-up.

### Verification

- `./gradlew test --tests com.example.app.llm.LlmTestFixturesDiffTest`
  passes (Phase 1 proof).
- `./gradlew test --tests com.example.app.llm.LlmExtractionRecordedRegressionTest`
  passes with the `01-sample` fixture (Phase 2 proof).
- `OGARNIACZ_LIVE_SMOKE=true OPENROUTER_API_KEY=sk-or-… ./gradlew test --tests com.example.app.llm.LlmExtractionLiveRegressionTest`
  passes with the `01-sample` fixture in grading mode (Phase 3 proof).
- Re-running Phase 3 with `OGARNIACZ_RECORD_FIXTURES=true` against an
  *additional* hand-curated fixture (no existing recording) writes a
  fresh `recorded-response.json` + `recorded-meta.json` atomically;
  re-running again with the recording in place does **not**
  overwrite (Phase 3 safety check).
- `./gradlew test` passes end-to-end (full suite, recorded-mock
  variant enabled, live variant skipped by env-gate, smoke skipped
  by env-gate).
- `context/foundation/test-plan.md` §3 row shows `in progress` +
  change folder; §6.4 cookbook is no longer "TBD" (Phase 4 proof).

## What We're NOT Doing

- **Not curating 8–10 fixtures.** Only the seed `01-sample` lands
  here; the curator expands the set incrementally after the harness
  is in place. The plan does not specify which announcements get
  curated.
- **Not designing fixture anonymisation, redaction, or a two-tier
  fixture model.** PII responsibility sits with the curator;
  `fixtures/README.md` codifies the policy and stops there.
- **Not wiring the live variant into CI.** It is operator-run only,
  same gate as `LlmVisionSmokeTest`. A separate scheduled GitHub
  Actions job is out of scope.
- **Not fixing the greedy regex** at
  `OpenRouterLlmVisionClient.java:96`. It is documented as a finding
  in `change.md`; a fix is a separate change.
- **Not extracting `chatResponseOf` to a `src/main/` test-utilities
  module.** It stays in `src/test/java/com/example/app/llm/` —
  no cross-module coupling.
- **Not introducing WireMock, LangSmith, Promptfoo, or any hosted
  eval platform.** `test-plan.md` §7 negative-space (re-evaluate at
  ~30 fixtures).
- **Not exercising the `LlmExtractionException` envelope.**
  `LlmVisionClientTest` already covers the three `Kind`s; the harness
  *asserts* no exception is raised on any fixture (open question #4)
  but does not test the exception types themselves.
- **Not changing `LlmVisionSmokeTest`.** The harness uses a separate
  copy of `sample-announcement.png` so the smoke test remains
  untouched and operationally distinct.
- **Not adding edit-distance (Levenshtein) tolerance on title /
  requirements.** NFC + lower + whitespace-collapse is the cap; any
  expansion comes back through `/10x-research`.
- **Not capturing the recording commit SHA at test runtime.** The
  `recorded-meta.json` sidecar holds `model` + `recordedAt` (ISO
  timestamp) only; commit history is recoverable via
  `git log -1 -- recorded-response.json` (open question #3).
- **Not deriving `expected.json` from any model output, ever.** The
  rule is *recordings come from the model; labels come from a human
  reading the image* — codified in `fixtures/README.md` and §6.4.

## Implementation Approach

Four phases, each producing a single coherent unit. Phase 1 ships the
shared helper, the JSON schemas (via `fixtures/README.md`), and a
**complete seed fixture (`01-sample`)** so Phases 2 and 3 have
real behaviour to TDD against — not a meta-test of "empty directory
disables the class". Phase 2 ships the recorded-mock harness; its
first red test is `01-sample` failing the diff before
`LlmExtractionRecordedRegressionTest` exists. Phase 3 ships the live
variant with recording mode; its first red test is the grading-mode
diff on `01-sample` against the existing recording. Phase 4 closes
the loop with cookbook content, `test-plan.md` status, and the
greedy-regex finding.

The seed fixture in Phase 1 is bootstrapped manually: copy
`sample-announcement.png` into `fixtures/01-sample/image.png`,
run the existing `LlmVisionSmokeTest` live once and copy its raw
response from stdout into `fixtures/01-sample/recorded-response.json`,
hand-write `recorded-meta.json` with the model name and ISO
timestamp, and hand-fill `expected.json` from reading the image.
This is the only manual capture in the project; from Phase 3 onward,
the live variant's recording mode automates the same operation for
all new fixtures.

## Critical Implementation Details

### State sequencing

The seed fixture is the **input** for Phase 2 and Phase 3, not their
output. Phase 1's automated check (the diff predicate's pure unit
test) does **not** depend on `fixtures/01-sample/` existing; the
manual verification step at the end of Phase 1 does. Phase 2 cannot
start until `fixtures/01-sample/` has all four files committed.

### Debug & observability

Failure messages from the diff predicate carry **fixture id + field +
both raw values + which rule fired**, never the normalised strings.
Normalised strings tempt a reader into copying them back into
`expected.json`, which would convert a real regression into a silent
oracle drift. The recorded-mock variant additionally appends the
fixture's `recorded-meta.json` model name to its failure message so
the operator sees which model produced the captured response.

---

## Phase 1: Shared helper, scaffold, seed fixture

### Overview

Land the shared `LlmTestFixtures` helper, the empty-then-seeded
`src/test/resources/llm/fixtures/` directory with PII + sourcing
policy in its `README.md`, and the complete `01-sample` seed fixture
on disk so Phases 2 and 3 have real behaviour to test against. Also
ship `LlmTestFixturesDiffTest` (pure JUnit) as a self-contained
proof that the per-field tolerant diff matches the spec from
`research.md` §6.

### Changes Required

#### 1. Shared test helper

**File**: `src/test/java/com/example/app/llm/LlmTestFixtures.java` (new)

**Intent**: One place for every reusable test utility the harness
needs — `chatResponseOf` (lifted from `LlmVisionClientTest`),
fixture-directory enumeration via classpath, image/expected/recording
loaders, the sidecar metadata reader, the NFC-norm function, and the
per-field tolerant diff predicate. Package-private; no
`src/main/` exposure.

**Contract**:

- `static ChatResponse chatResponseOf(String content)` — verbatim
  port of the helper at `LlmVisionClientTest.java:151-153`.
- `static List<Path> listFixtures()` — resolve
  `classpath:llm/fixtures/` to a `Path` (via
  `LlmTestFixtures.class.getResource("/llm/fixtures").toURI()`),
  list immediate subdirectories sorted lexicographically; empty
  list when the dir is empty. The returned paths point into
  `build/resources/test/...` (the test classpath) — use them for
  reads only.
- `static boolean fixturesAreEmpty()` — convenience predicate over
  `listFixtures()`.
- `static Path sourceFixtureDir(Path classpathFixtureDir)` —
  return `Paths.get("src/test/resources/llm/fixtures").resolve(classpathFixtureDir.getFileName().toString())`.
  Source-tree mirror of a classpath fixture path; the live variant's
  recording mode uses this as its write target so the captured files
  land in the source tree (visible to `git status`) instead of in
  the build copy (wiped by `./gradlew clean`). Relies on Gradle's
  CWD being the project root, which is true for `./gradlew test`.
- `static byte[] loadImage(Path fixtureDir)` — `Files.readAllBytes(fixtureDir.resolve("image.png"))`.
- `static String loadRecordedRaw(Path fixtureDir)` — read
  `recorded-response.json` as UTF-8 string (raw — may include
  markdown fences or prose, the production parser handles it).
- `static List<LlmExtractionResult.ProposedEvent> loadExpected(Path fixtureDir, ObjectMapper objectMapper)` —
  deserialize `expected.json` envelope
  `{ "events": [ ... ] }` into the production `ProposedEvent` record
  type. Uses the same Jackson `ObjectMapper` Spring autowires.
- `record FixtureMeta(String model, String recordedAt)` — sidecar
  shape; `recordedAt` stored as ISO-8601 string (no `Instant` type at
  the contract; the field is informational only).
- `static FixtureMeta loadMeta(Path fixtureDir, ObjectMapper objectMapper)` —
  deserialize `recorded-meta.json`.
- `static String norm(String s)` —
  `Normalizer.normalize(s, Normalizer.Form.NFC).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip()`;
  returns `""` for null input.
- `record DiffResult(boolean match, String field, String expectedValue, String actualValue, String reason)` —
  one per pair, structured failure surface.
- `static DiffResult diff(ProposedEvent expected, ProposedEvent actual)` —
  per-field predicate per `research.md` §6:
  date exact, time exact + null-match, title via `norm(...).equals(norm(...))`,
  requirements via `norm(coalesce(...)).equals(norm(coalesce(...)))`
  with null/blank tolerance, notes NOT compared; returns the first
  failing field's `DiffResult` or `DiffResult.match()` if all pass.
- `static List<ProposedEvent> canonicalSort(List<ProposedEvent> events)` —
  return a new list sorted by `(date ASC, time ASC with null first,
  title ASC under norm(...))`. The harness calls this on **both** sides
  before the pairwise diff so a live-mode response that reproduces the
  same events in a different order is not falsely flagged as a
  regression. Pure function; never mutates the input.

#### 2. Lift `chatResponseOf` from existing test

**File**: `src/test/java/com/example/app/llm/LlmVisionClientTest.java`

**Intent**: Remove the local `chatResponseOf` helper at lines 151-153
and update its callers to use `LlmTestFixtures.chatResponseOf(...)`.
Keeps the helper single-sourced from Phase 1 onward.

**Contract**: Replace the static helper definition with a static
import or qualified reference. Test bodies that currently call
`chatResponseOf(...)` swap to `LlmTestFixtures.chatResponseOf(...)`.
No behaviour change; existing 6 test methods still pass.

#### 3. Diff predicate unit test

**File**: `src/test/java/com/example/app/llm/LlmTestFixturesDiffTest.java` (new)

**Intent**: Pure JUnit 5 (no Spring context) exercise of `diff(...)`
covering every branch of the per-field spec. This is the Phase 1
*proof* the helper works; it does **not** touch
`src/test/resources/llm/fixtures/` and is independent of Phase 2/3.

**Contract**: Test methods cover:

- All-exact match (same date, same time, identical title/requirements,
  null notes) → `match=true`.
- Title differing only by diacritic case (`Pasowanie` vs
  `pasowanie`) → `match=true`.
- Title differing only by whitespace (`Wycieczka  do  ZOO` vs
  `Wycieczka do ZOO`) → `match=true`.
- Title differing on a real character → `match=false`, `field="title"`.
- Date mismatch by one day → `match=false`, `field="date"`.
- Time `null` on expected + `null` on actual → `match=true`.
- Time `null` on expected + value on actual → `match=false`,
  `field="time"`.
- Requirements `null` vs empty string → `match=true`.
- Requirements `null` vs blank string `"   "` → `match=true`.
- Notes differing → `match=true` (not graded).
- `canonicalSort` on a list with two events in date-descending order
  returns them in date-ascending order; null-time event sorts before
  same-date timed event; title is the final tie-breaker under `norm`.
- `canonicalSort` is pure — the input list is not mutated, and a
  second call on the returned list is a no-op (idempotence).

### Changes Required (continued)

#### 4. PII + sourcing policy README

**File**: `src/test/resources/llm/fixtures/README.md` (new)

**Intent**: Codify the curator-owned PII guarantee, the eventual
target shape of the fixture set, the JSON schemas for `expected.json`
and `recorded-meta.json`, and the *recordings-from-model,
labels-from-human* rule. Read by any future contributor adding a
fixture.

**Contract**: Sections:

- **Fixture sourcing policy** — curator (Ludmiła) is the only
  fixture author for the MVP; target shape is 8–10 fixtures with at
  least one ambiguous case and at least one FR-005-unreadable case;
  re-evaluate the harness pattern at ~30 fixtures per
  `test-plan.md` §7.
- **PII policy** — real announcements MUST NOT contain child /
  teacher / parent names, kindergarten name, address, phone, or
  handwritten signatures; curator's responsibility, enforced by
  hand before commit; no automated redaction.
- **Directory layout** — `fixtures/<id>/{image.png, expected.json,
  recorded-response.json, recorded-meta.json}`; id is two-digit
  ordinal + kebab-case description (e.g. `01-sample`, `02-pasowanie`).
- **`expected.json` schema** — envelope `{ "events": [ { "date":
  "YYYY-MM-DD", "time": "HH:MM" | null, "title": string,
  "requirements": string | null } ] }`; `notes` intentionally
  omitted (not graded). **Array order is informational, not
  load-bearing**: the diff predicate sorts both sides by
  `(date, time-null-first, title)` before comparison. Curators
  may write events in whatever order reads naturally from the
  image; the harness will not flag order drift between live
  responses and the label.
- **`recorded-meta.json` schema** — `{ "model": string, "recordedAt":
  string (ISO-8601) }`.
- **`recorded-response.json` rule** — raw model output, captured by
  the live variant's recording mode (or by hand for the seed); may
  include markdown fences or prose around the JSON; **never** edited
  by hand; re-recording requires `git rm` first.
- **The oracle rule** — recordings come from the model; labels come
  from a human reading the image. Filling `expected.json` from any
  model output (including the current recording) is the documented
  failure mode #4 from `research.md` §8 — do not do it.

#### 5. Seed fixture `01-sample` — files

**Files** (all new under `src/test/resources/llm/fixtures/01-sample/`):

- `image.png` — **binary copy** of `src/test/resources/llm/sample-announcement.png`.
  `LlmVisionSmokeTest` keeps reading its original path; no edit to
  the smoke test.
- `expected.json` — hand-filled by the curator from reading the
  image. Envelope `{ "events": [ ... ] }`, `notes` field omitted per
  schema.
- `recorded-response.json` — captured manually (see Manual
  Verification below); the raw response from one
  `LlmVisionSmokeTest` live invocation against
  `google/gemini-2.5-flash`. Copy **only** the lines strictly
  between the `raw response:` header line and the trailing
  `=====================================` delimiter that
  `LlmVisionSmokeTest:45-54` prints — no header, no delimiter, no
  leading/trailing blank line. A copy that includes the `raw response:`
  header still parses (the greedy regex at
  `OpenRouterLlmVisionClient.java:96` matches the JSON array
  regardless) and would silently survive Phase 2's diff, then
  diverge from the live response on regeneration — the classic
  hard-to-spot seed defect.
- `recorded-meta.json` — hand-written; `model` matches
  `src/main/resources/application.properties:32` at recording time
  (`google/gemini-2.5-flash`), `recordedAt` is the ISO-8601 timestamp
  of the manual capture.

**Intent**: Bootstrap the fixture set with one complete entry so
Phase 2's red test is a real diff, not a meta-test of empty-directory
handling.

**Contract**: All four files committed in one commit; `git status`
clean for `fixtures/01-sample/` before Phase 2 starts.

### Success Criteria

#### Automated Verification

- `./gradlew test --tests com.example.app.llm.LlmTestFixturesDiffTest`
  passes.
- `./gradlew test --tests com.example.app.llm.LlmVisionClientTest`
  still passes after the `chatResponseOf` lift.
- `./gradlew build` succeeds (no compile errors anywhere).

#### Manual Verification

- `src/test/resources/llm/fixtures/01-sample/` contains exactly four
  files: `image.png`, `expected.json`, `recorded-response.json`,
  `recorded-meta.json`.
- The curator has visually compared `expected.json` against the
  contents of `image.png` — date, time (or null), title, requirements
  are correct per the announcement.
- `recorded-response.json` is the raw stdout block from a successful
  `OGARNIACZ_LIVE_SMOKE=true OPENROUTER_API_KEY=sk-or-… ./gradlew test --tests com.example.app.llm.LlmVisionSmokeTest --info`
  run. Copy only the lines strictly between the `raw response:`
  header and the closing `=====================================`
  delimiter (`LlmVisionSmokeTest:45-54`) — no header line, no
  delimiter line, no extra leading/trailing blank line.
- `recorded-meta.json` `model` field equals
  `google/gemini-2.5-flash` and `recordedAt` is a valid ISO-8601
  timestamp.
- `src/test/resources/llm/fixtures/README.md` has all six sections
  from change #4 above.
- `LlmVisionSmokeTest` is **untouched** — `git diff HEAD~1 -- src/test/java/com/example/app/llm/LlmVisionSmokeTest.java`
  is empty.

**Implementation Note**: After completing this phase and all
automated verification passes, pause here for manual confirmation
from the human (curator) that the four `01-sample` files are correct
before proceeding to Phase 2. Phase 2's red test fails dishonestly
if `expected.json` was filled in from the recording.

---

## Phase 2: Recorded-mock harness class

### Overview

Ship `LlmExtractionRecordedRegressionTest`. The behavioural assertion
is: "for each fixture, the set of per-field-diff divergences between
`expected.json` and the parsed recorded response equals the curator-
documented entry in the test class's `KNOWN_DIVERGENCES` map. A fixture
absent from the map is asserted to produce zero divergences (clean
match)."

The seed `01-sample` lands as a **documented-divergence** fixture, not
a clean match (curator note 2026-06-11: the synthetic seed image is not
representative of production-style ogłoszenia; the model condenses
polite filler on event 1 and routes free-text on event 2 into `notes`
instead of `requirements`). The clean-match path will be exercised
by fixtures `02+` as the curator collects real announcements. The
documented-divergence shape is preserved in the test class via the
`KNOWN_DIVERGENCES` constant so an extra divergence flags a parser /
diff regression and a missing divergence flags recording drift — both
load-bearing signals once Phase 3's live recording re-runs against the
same fixture.

### Changes Required

#### 1. Recorded-mock test class

**File**: `src/test/java/com/example/app/llm/LlmExtractionRecordedRegressionTest.java` (new)

**Intent**: One `@SpringBootTest` class that, for every fixture in
`src/test/resources/llm/fixtures/`, stubs the autowired `ChatModel`
to return the fixture's captured raw response and asserts that
`LlmVisionClient.extract(...)` produces events matching
`expected.json` under the per-field tolerant diff. Runs in CI by
default; `@DisabledIf("fixturesAreEmpty")` is the safety net for
hypothetical future emptiness (in this change, `01-sample` keeps it
enabled).

**Contract**:

- Class annotations: `@SpringBootTest`,
  `@TestPropertySource(properties = "REMEMBER_ME_KEY=test-key-not-for-production")`,
  `@DisabledIf(value = "fixturesAreEmpty", disabledReason = "no fixtures wired in src/test/resources/llm/fixtures/")`.
- Injected: `@Autowired LlmVisionClient llmVisionClient`,
  `@Autowired ObjectMapper objectMapper`, `@MockitoBean ChatModel chatModel`.
- `@BeforeEach void stubDefaultOptions()` — verbatim from
  `LlmVisionClientTest.java:41-44`.
- `@ParameterizedTest(name = "fixture {0}") @MethodSource("fixtures") void recordedExtractionDivergencesMatchKnown(Path fixtureDir)`:
  1. `byte[] image = LlmTestFixtures.loadImage(fixtureDir);`
  2. `String recorded = LlmTestFixtures.loadRecordedRaw(fixtureDir);`
  3. `when(chatModel.call(any(Prompt.class))).thenReturn(LlmTestFixtures.chatResponseOf(recorded));`
  4. `LlmExtractionResult result = assertDoesNotThrow(() -> llmVisionClient.extract(image, MimeTypeUtils.IMAGE_PNG), "fixture %s raised LlmExtractionException".formatted(fixtureDir.getFileName()));` — open question #4 (no exception is an explicit assertion, not implicit).
  5. `List<ProposedEvent> expected = LlmTestFixtures.canonicalSort(LlmTestFixtures.loadExpected(fixtureDir, objectMapper));`
     `List<ProposedEvent> actual = LlmTestFixtures.canonicalSort(result.proposedEvents());`
     (Both sides sorted by `(date, time-null-first, title)` so a
     different emission order is not a false regression — file order
     in `expected.json` is informational, not load-bearing.)
  6. Assert `actual.size() == expected.size()` with a failure
     message naming the fixture and the two counts.
  7. For each pair `(expected[i], actual[i])`, run
     `LlmTestFixtures.diff(...)`. Each non-matching pair contributes a
     `KnownDivergence(title, field, reason)` to `observed`, where
     `title` comes from `expected[i]` (the human label — stable across
     model swaps). Build a parallel `details` list carrying the
     fixture-id + field + both raw values + reason for the failure
     surface.
  8. Look up `documented = KNOWN_DIVERGENCES.getOrDefault(fixtureId, List.of())`
     and assert `observed` equals `documented` as a multiset (AssertJ
     `containsExactlyInAnyOrderElementsOf`). The `as(...)` clause carries
     fixture id + model (from `LlmTestFixtures.loadMeta(...)`) +
     observed details so extra/missing divergences surface with both
     raw values, not just the record fields.
- The `KNOWN_DIVERGENCES` constant is a `Map<String, List<KnownDivergence>>`
  literal in the test class. `KnownDivergence` is a nested `private record
  (String title, String field, String reason)`. The seed entry for
  `01-sample`:
  - `("Wycieczka do ZOO", "requirements", "requirements-norm-mismatch")` —
    model strips "Prosimy o przygotowanie:" polite filler.
  - `("Festyn rodzinny", "requirements", "requirements-norm-mismatch")` —
    model routes the body text into `notes` instead of `requirements`.
  Fixtures absent from the map default to `List.of()` (clean match
  asserted). Adding a fixture without a `KNOWN_DIVERGENCES` entry
  expresses "the curator expects this one to match cleanly".
- `static Stream<Path> fixtures() { return LlmTestFixtures.listFixtures().stream(); }`
- `static boolean fixturesAreEmpty() { return LlmTestFixtures.fixturesAreEmpty(); }`

### Success Criteria

#### Automated Verification

- `./gradlew test --tests com.example.app.llm.LlmExtractionRecordedRegressionTest`
  passes — `01-sample`'s observed divergence set equals its
  `KNOWN_DIVERGENCES` entry (event "Wycieczka do ZOO" +
  event "Festyn rodzinny", both `field=requirements
  reason=requirements-norm-mismatch`).
- `./gradlew test` (full suite) passes — no regressions in
  `LlmVisionClientTest`, `LlmVisionSmokeTest` (skipped by env-gate),
  `LlmTestFixturesDiffTest`, or any other test class.

#### Manual Verification

- Deliberate-break check: hand-edit `fixtures/01-sample/expected.json`
  so the diff surfaces a **new** divergence on one event (e.g. change
  event 1's date by one day so `diff()` returns `field=date
  reason=date-mismatch` for that pair, shadowing the documented
  `requirements` divergence). Re-run and confirm the failure message
  names the fixture id + model + the unexpected divergence (extra:
  `Wycieczka do ZOO/date/date-mismatch`) AND the missing one
  (`Wycieczka do ZOO/requirements/requirements-norm-mismatch`). Revert
  the edit before proceeding.
- Confirm the parameterised test runs exactly once for `01-sample`
  (display name shows `fixture <path-to-01-sample>`); no other
  iterations.

**Implementation Note**: After completing this phase and all
automated verification passes, pause here for manual confirmation
from the human that the deliberate-break check above produced an
informative failure message before proceeding to Phase 3. A silent
or unclear failure is itself a regression of the diff predicate.

---

## Phase 3: Live variant + recording mode

### Overview

Ship `LlmExtractionLiveRegressionTest`. Env-gated by
`OGARNIACZ_LIVE_SMOKE=true` + `OPENROUTER_API_KEY` (same pattern as
`LlmVisionSmokeTest:25`). For each fixture: real OpenRouter call;
under `BUDGET_MS = 55_000L`; branch on `recorded-response.json`
existence **in the source tree** (`src/test/resources/llm/fixtures/<id>/`,
not the classpath copy) — grading mode (diff vs `expected.json`) when
present; recording mode (atomic write into the source tree + sidecar)
when missing AND `OGARNIACZ_RECORD_FIXTURES=true`; loud failure
otherwise. **Never** overwrites an existing recording.

**Grading-mode assertion inherits the Phase 2 pivot.** "Run the same
diff loop as Phase 2" below means the divergence-set assertion against
`KNOWN_DIVERGENCES[fixtureId]` introduced in Phase 2, NOT a direct
"first failing field aborts" diff. Two consequences flow from this and
are intentional:

- A *clean* model improvement on a documented-divergence fixture
  (e.g. a future model swap that correctly extracts `requirements`
  for `01-sample`) surfaces as a "missing divergence" failure, not
  a silent pass. That failure is the curator's signal to re-evaluate
  the fixture: either remove the entry from `KNOWN_DIVERGENCES`
  (the fixture is now a clean-match case) or revise it (the model
  drift is different from the documented one). This is by design —
  per the 2026-06-11 pivot direction, drift between recordings and
  documented divergences is a load-bearing signal, not noise.
- A model *regression* (the live response disagrees with
  `expected.json` on a NEW field) surfaces as an "extra divergence"
  failure with the diff details on the failure surface, exactly as
  Phase 2's deliberate-break manual check exercises.

### Changes Required

#### 1. Live variant test class

**File**: `src/test/java/com/example/app/llm/LlmExtractionLiveRegressionTest.java` (new)

**Intent**: Operator-run regression against real OpenRouter. Two
modes (grading + recording) selected at runtime by env vars; one
parameterised test method drives both. Never wired into CI.

**Contract**:

- Class annotations: `@SpringBootTest`,
  `@TestPropertySource(properties = "REMEMBER_ME_KEY=test-key-not-for-production")`,
  `@EnabledIfEnvironmentVariable(named = "OGARNIACZ_LIVE_SMOKE", matches = "true")`,
  `@DisabledIf(value = "fixturesAreEmpty", disabledReason = "no fixtures wired")`.
- Injected: `@Autowired LlmVisionClient llmVisionClient`,
  `@Autowired ObjectMapper objectMapper`,
  `@Value("${spring.ai.openai.chat.options.model}") String configuredModel`.
- `private static final long BUDGET_MS = 55_000L;` — verbatim from
  `LlmVisionSmokeTest.java:28`.
- `@ParameterizedTest(name = "fixture {0}") @MethodSource("fixtures") void liveExtractionMatchesExpected(Path fixtureDir)`:
  1. `byte[] image = LlmTestFixtures.loadImage(fixtureDir);`
  2. `long start = System.nanoTime();`
  3. `LlmExtractionResult result = assertDoesNotThrow(() -> llmVisionClient.extract(image, MimeTypeUtils.IMAGE_PNG), "fixture %s raised LlmExtractionException".formatted(fixtureDir.getFileName()));`
  4. `long wallMs = (System.nanoTime() - start) / 1_000_000L;`
  5. `assertThat(wallMs).as("fixture %s exceeded live budget".formatted(fixtureDir.getFileName())).isLessThanOrEqualTo(BUDGET_MS);`
  6. `Path sourceFixtureDir = LlmTestFixtures.sourceFixtureDir(fixtureDir);`
     `Path recordedInSource = sourceFixtureDir.resolve("recorded-response.json");`
     (Existence and writes target the source tree, **not** the classpath
     copy. A leftover recording in `build/resources/test/` from a stale
     pre-fix run must not shadow a source-tree absence.)
  7. Branch (recording mode is the **only** writing branch):
     - `Files.exists(recordedInSource)` → load the recorded raw via
       `LlmTestFixtures.loadRecordedRaw(fixtureDir)` (classpath is fine —
       `processTestResources` has already copied the source file into
       `build/resources/test/`), load expected via
       `LlmTestFixtures.loadExpected(...)`, run the same diff loop as
       Phase 2; failure message identical shape, with `model` from
       `LlmTestFixtures.loadMeta(...)`.
     - `!Files.exists(recordedInSource)` AND
       `"true".equals(System.getenv("OGARNIACZ_RECORD_FIXTURES"))`:
       call `writeRecordingAtomically(sourceFixtureDir, result, configuredModel)`
       and pass (recording-mode assertion is "rawResponse is non-blank
       and contains a `[`", documenting that the captured payload at
       least looks JSON-array-shaped).
       **Note on KNOWN_DIVERGENCES**: writing a fresh
       `recorded-response.json` does NOT touch the test class's
       `KNOWN_DIVERGENCES` constant. The curator must re-run the
       recorded-mock variant (Phase 2 class) after recording lands;
       if the divergence set has shifted, update the constant
       per `fixtures/README.md` §Fixture categories (add for
       documented divergence; remove for clean match). Phase 3
       cannot do this automatically — `KNOWN_DIVERGENCES` is the
       curator's accept-list, not a derived property.
     - Otherwise (missing recording + flag unset):
       `fail("fixture %s has no recording at %s; rerun with OGARNIACZ_RECORD_FIXTURES=true to capture".formatted(fixtureDir.getFileName(), recordedInSource))`.
- `private void writeRecordingAtomically(Path sourceFixtureDir, LlmExtractionResult result, String model) throws IOException`
  (instance method so the autowired `objectMapper` is in scope):
  - `Files.createDirectories(sourceFixtureDir);` (idempotent; the
    fixture directory exists for `01-sample` but a fresh throwaway
    fixture used during recording-mode verification may not have a
    pre-created source dir).
  - `Path responseTmp = Files.createTempFile(sourceFixtureDir, "recording-", ".json.tmp");`
  - `Files.writeString(responseTmp, result.rawResponse(), StandardCharsets.UTF_8);`
  - `Files.move(responseTmp, sourceFixtureDir.resolve("recorded-response.json"), StandardCopyOption.ATOMIC_MOVE);`
  - Build the sidecar payload via the autowired `ObjectMapper`:
    `String metaJson = objectMapper.writeValueAsString(new LlmTestFixtures.FixtureMeta(model, Instant.now().toString()));`
    Write `metaJson` to a `metaTmp` inside `sourceFixtureDir`,
    atomic-move to `recorded-meta.json`. Uses the same serializer
    that `LlmTestFixtures.loadMeta(...)` reads with — round-trip
    parity by construction; safely escapes any model name.
  - The function never overwrites `recorded-response.json` because
    its caller has already gated on `Files.exists(...) == false`;
    there is no second-level guard inside the writer.
  - Writes always land in `src/test/resources/...` (source tree), so a
    `git status` after recording surfaces the new files. The classpath
    copy in `build/resources/test/` is regenerated by
    `processTestResources` on the next test run.

### Success Criteria

#### Automated Verification

- `./gradlew test` (no env vars) — `LlmExtractionLiveRegressionTest`
  is **skipped** by `@EnabledIfEnvironmentVariable`; full suite
  green.

#### Manual Verification

- `OGARNIACZ_LIVE_SMOKE=true OPENROUTER_API_KEY=sk-or-… ./gradlew test --tests com.example.app.llm.LlmExtractionLiveRegressionTest --info`
  — `01-sample` runs in grading mode, completes under 55 s, diff
  passes. Stdout shows the parameterised test name as
  `fixture 01-sample`.
- Create a temporary throwaway fixture in the source tree:
  `src/test/resources/llm/fixtures/99-throwaway/{image.png, expected.json}`
  (no `recorded-response.json`). Run the live variant **without**
  `OGARNIACZ_RECORD_FIXTURES`: the test fails for `99-throwaway`
  with the documented "no recording at
  `src/test/resources/llm/fixtures/99-throwaway/recorded-response.json`;
  rerun with `OGARNIACZ_RECORD_FIXTURES=true`" message. Run **with**
  `OGARNIACZ_RECORD_FIXTURES=true`: the test passes, and
  `src/test/resources/llm/fixtures/99-throwaway/recorded-response.json` +
  `recorded-meta.json` appear **in the source tree** —
  `git status` lists them as untracked. Re-run again (still with
  the flag set): the test passes in grading mode (since the source
  recording now exists) without ever overwriting the freshly-written
  files. **Then `rm -rf src/test/resources/llm/fixtures/99-throwaway/`
  before committing.**
- `git status` is clean for `fixtures/01-sample/` after every
  manual verification step above — the live variant never modifies
  an existing fixture's files.

**Implementation Note**: After completing this phase and all
verification passes, pause here for manual confirmation from the
human that the recording-mode atomic-write + never-overwrite
behaviour is exactly as described before proceeding to Phase 4.
This is the load-bearing safety property of the harness.

---

## Phase 4: Cookbook, test-plan sync, regex finding

### Overview

Close the loop. Fill in `test-plan.md` §6.4 with concrete cookbook
content; flip §3 Phase 1 status from `not started` to `in progress`
and link the change folder; update §4 row "LLM extraction regression
suite" to point at the shipped classes; add a **Findings** section to
`change.md` documenting the greedy-regex observation as a separate
follow-up.

### Changes Required

#### 1. Cookbook section 6.4

**File**: `context/foundation/test-plan.md`

**Intent**: Replace the `TBD` body of §6.4 ("Adding an LLM extraction
test") with concrete steps a future contributor can follow without
reading `research.md` or `plan.md`. The Phase 4 §3 reference (call-
boundary failure modes) remains TBD until its phase ships.

**Contract**: §6.4 grows three sub-points:

- **Adding a regression fixture** — directory layout under
  `src/test/resources/llm/fixtures/<id>/`; the four files; how to
  hand-fill `expected.json` from the image; how to capture
  `recorded-response.json` (run the live variant with
  `OGARNIACZ_LIVE_SMOKE=true` + `OGARNIACZ_RECORD_FIXTURES=true`,
  flags must be set *together* — never set `RECORD_FIXTURES` without
  `LIVE_SMOKE`); the *recordings-from-model, labels-from-human* rule;
  and the post-capture acceptance step: after Phase 3 writes a fresh
  `recorded-response.json`, re-run
  `LlmExtractionRecordedRegressionTest` once; if it reports
  divergences, follow `fixtures/README.md` §Fixture categories to
  decide whether to add a `KNOWN_DIVERGENCES` entry (documented
  divergence) or re-record / re-label (clean match). A fixture
  without an entry asserts zero divergences.
- **Adding a parser invariant test** — extend `LlmVisionClientTest`
  with a mock-backed case; reference the existing fence-stripping /
  malformed-JSON cases at `LlmVisionClientTest.java:67-95, 138-149`.
- **The greedy regex caveat** — pointer to the Findings section in
  `change.md` so a contributor adding a chatty-model fixture knows
  the failure may be parser-level, not model-level.

The section header changes from `TBD — see §3 Phase 1 (real-fixture
regression harness) and §3 Phase 4 (call-boundary failure modes).
Existing LlmVisionClientTest shows how to mock at ChatModel for
parser / error-translation tests; the fixture-oracle pattern lands
in Phase 1.` to the body above; the Phase 4 reference becomes "Call-
boundary failure modes (TIMEOUT, FR-005 user-visible error) land in
§3 Phase 4."

#### 2. Phase 1 row status + change folder

**File**: `context/foundation/test-plan.md` (§3 Phase 1 row)

**Intent**: Reflect that the harness ships in this change while
curator-driven fixture expansion (target 8–10 with ≥ 1 ambiguous + ≥
1 unreadable) is still pending — the rollout phase is therefore
`in progress`, not `done`.

**Contract**: Change the Status column from `not started` to
`in progress`; change the Change folder column from `—` to
`llm-extraction-regression-harness`.

#### 3. Stack table row update

**File**: `context/foundation/test-plan.md` (§4 row "LLM extraction
regression suite")

**Intent**: Replace `none yet — see §3 Phase 1` with a concrete
pointer to the shipped classes and helper.

**Contract**: Tool column reads
`LlmExtraction{Recorded,Live}RegressionTest + LlmTestFixtures helper`;
Notes column reads `harness shipped in
context/changes/llm-extraction-regression-harness/; fixture set
expansion ongoing per `fixtures/README.md` sourcing policy;
intentionally lean (no LangSmith / Promptfoo / hosted eval platform
per §7)`.

#### 4. Findings section in change.md

**File**: `context/changes/llm-extraction-regression-harness/change.md`

**Intent**: Document the greedy-regex observation surfaced during
research (`research.md` §8 failure mode 1) as a finding belonging to
this change but NOT fixed by it. Anchored to the file:line so a
future contributor (or `/10x-frame` invocation) can pick it up as a
follow-up.

**Contract**: Append a `## Findings` section with one entry:

- **File**: `src/main/java/com/example/app/llm/OpenRouterLlmVisionClient.java:96`
- **Symptom**: `Pattern.compile("\\[.*\\]", Pattern.DOTALL)` is
  greedy; a model that emits prose containing `[` before the JSON
  array (e.g. "Found events [list below]: [{...}]") yields a regex
  match starting at the wrong `[`, so Jackson rejects the captured
  payload with `Kind.MALFORMED_RESPONSE`.
- **Why it surfaces in this change**: the harness's recorded-mock
  variant runs ALL fixtures through the regex against captured raw
  responses; the moment one such chatty-preamble fixture lands, the
  regex fragility goes from theoretical to reproducible.
- **Why it is NOT fixed here**: scoped to a separate change per
  user direction 2026-06-10. Likely fix: tighten the regex to
  non-greedy + anchor on the JSON-array boundary, or use the
  Jackson streaming parser to locate the first array token.
- **Hand-off**: open a fresh `/10x-frame` or `/10x-new` for the
  fix when a fixture in production traffic triggers it.

### Success Criteria

#### Automated Verification

- `./gradlew test` (full suite) passes — no test changes in Phase 4.
- `grep -c "TBD — see §3 Phase 1" context/foundation/test-plan.md`
  returns `0` (the §3 Phase 1 TBD reference is gone from §6.4).
- `grep -F "in progress" context/foundation/test-plan.md | grep -F "LLM extraction regression harness"`
  matches one line (the §3 row is updated).
- `grep -F "## Findings" context/changes/llm-extraction-regression-harness/change.md`
  matches.

#### Manual Verification

- A fresh reader who opens `test-plan.md` § 6.4 can add a new fixture
  to the harness without consulting `plan.md` or `research.md`.
- `change.md` Findings entry names the file:line, the symptom, and
  the hand-off path; no implementation-level prescription is baked
  in (a future fix may diverge from any suggested approach).

**Implementation Note**: This is the final phase. After it passes,
the harness is ready for the curator to expand the fixture set.
There is no further `/10x-implement` invocation required for this
change.

---

## Testing Strategy

### Unit Tests

- `LlmTestFixturesDiffTest` — pure JUnit 5, no Spring context, no
  fixtures on disk. Exercises every branch of the per-field tolerant
  diff. Failure here means the diff predicate regressed; failure here
  also blocks Phase 2 and Phase 3.

### Integration Tests

- `LlmExtractionRecordedRegressionTest` — `@SpringBootTest` with
  `@MockitoBean ChatModel`. Reads `fixtures/01-sample/` (and any
  future fixture); failure means EITHER the parser/diff regressed
  (recorded-mock variant cannot fail because of model drift, only
  because of code drift) OR `expected.json` was edited without
  re-running the live variant.
- `LlmExtractionLiveRegressionTest` — `@SpringBootTest` with no mock,
  env-gated. Operator-run. Grading mode failure means the live model
  drifted; recording mode failure means atomic-write semantics broke.

### Manual Testing Steps

Phase-by-phase manual steps appear in each phase's Manual
Verification block above. The cross-phase manual flow:

1. After Phase 1: confirm seed fixture is complete and correct.
2. After Phase 2: deliberately break + revert to confirm failure
   message shape.
3. After Phase 3: throwaway fixture + recording-mode round-trip,
   then `git rm` the throwaway.
4. After Phase 4: cold-read § 6.4 cookbook and add a hypothetical
   fixture in your head; if any step is unclear, Phase 4 is not
   done.

## Performance Considerations

- The recorded-mock variant is fast (no network); even at 30
  fixtures it adds well under a second to `./gradlew test` because
  the Spring context cache reuses the same context the rest of the
  suite uses.
- The live variant is slow (one OpenRouter round-trip per fixture);
  `BUDGET_MS = 55_000L` per fixture caps each call, so a 10-fixture
  set is bounded at ~9 minutes of wall time. This is the operator's
  cost, not CI's.

## Migration Notes

- The seed-fixture `image.png` is a binary copy of
  `sample-announcement.png`, **not** a move. `LlmVisionSmokeTest`
  remains operationally distinct and unchanged. The duplication is
  intentional (≈ 62 KB).
- The `chatResponseOf` helper is lifted, not duplicated;
  `LlmVisionClientTest` is the only existing caller and is updated
  in Phase 1.
- No database migration. No production code changes. No secrets or
  env-var changes (the new `OGARNIACZ_RECORD_FIXTURES` flag is
  operator-set on demand, not a permanent secret).

## References

- Research: `context/changes/llm-extraction-regression-harness/research.md`
- Test plan rollout phase row: `context/foundation/test-plan.md:74`
- Test plan stack row: `context/foundation/test-plan.md:91`
- Test plan cookbook stub: `context/foundation/test-plan.md:146-148`
- Test plan §7 lean-stack constraint: `context/foundation/test-plan.md:164-165`
- Similar live env-gated pattern:
  `src/test/java/com/example/app/llm/LlmVisionSmokeTest.java:25-60`
- Similar `@MockitoBean ChatModel` pattern:
  `src/test/java/com/example/app/llm/LlmVisionClientTest.java:29-153`
- F-01 archive (exception envelope, dual-env-gate rationale, 55 s
  budget): `context/archive/2026-06-01-openrouter-llm-client-wired/plan.md:13,55,72-77,257`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>`
> when a step lands. Do not rename step titles. See
> `references/progress-format.md`.

### Phase 1: Shared helper, scaffold, seed fixture

#### Automated

- [x] 1.1 `./gradlew test --tests com.example.app.llm.LlmTestFixturesDiffTest` passes — a0fab8e
- [x] 1.2 `./gradlew test --tests com.example.app.llm.LlmVisionClientTest` still passes after the `chatResponseOf` lift — a0fab8e
- [x] 1.3 `./gradlew build` succeeds — a0fab8e

#### Manual

- [x] 1.4 `src/test/resources/llm/fixtures/01-sample/` contains exactly four files: `image.png`, `expected.json`, `recorded-response.json`, `recorded-meta.json` — a0fab8e
- [x] 1.5 Curator has visually compared `expected.json` against `image.png` — date / time / title / requirements are correct — a0fab8e
- [x] 1.6 `recorded-response.json` is the raw stdout block from a successful `LlmVisionSmokeTest` live run, copied verbatim — a0fab8e
- [x] 1.7 `recorded-meta.json` `model` equals `google/gemini-2.5-flash` and `recordedAt` is a valid ISO-8601 timestamp — a0fab8e
- [x] 1.8 `src/test/resources/llm/fixtures/README.md` has all six sections from change #4 — a0fab8e
- [x] 1.9 `LlmVisionSmokeTest` is untouched (`git diff HEAD~1 -- src/test/java/com/example/app/llm/LlmVisionSmokeTest.java` is empty) — a0fab8e

### Phase 2: Recorded-mock harness class

#### Automated

- [x] 2.1 `./gradlew test --tests com.example.app.llm.LlmExtractionRecordedRegressionTest` passes (observed divergence set equals `KNOWN_DIVERGENCES["01-sample"]`) — 1323e74
- [x] 2.2 `./gradlew test` (full suite) passes — 1323e74

#### Manual

- [x] 2.3 Deliberate-break check: edit `fixtures/01-sample/expected.json` to add a new divergence (e.g. change a date); re-run; confirm failure message names fixture id + model + extra divergence + missing documented divergence on the affected event; revert — 1323e74
- [x] 2.4 Parameterised test display name shows the `01-sample` fixture path (single iteration) — 1323e74

### Phase 3: Live variant + recording mode

#### Automated

- [x] 3.1 `./gradlew test` (no env vars) skips `LlmExtractionLiveRegressionTest`; full suite green — 189f5aa

#### Manual

- [x] 3.2 `OGARNIACZ_LIVE_SMOKE=true OPENROUTER_API_KEY=… ./gradlew test --tests com.example.app.llm.LlmExtractionLiveRegressionTest --info` — `01-sample` runs in grading mode, under 55 s, diff passes — 189f5aa
- [x] 3.3 Throwaway fixture `99-throwaway` with no recording + no `OGARNIACZ_RECORD_FIXTURES` → test fails with the documented "no recording; rerun with `OGARNIACZ_RECORD_FIXTURES=true`" message — 189f5aa
- [x] 3.4 Same throwaway + `OGARNIACZ_RECORD_FIXTURES=true` → test passes; `recorded-response.json` + `recorded-meta.json` appear on disk — 189f5aa
- [x] 3.5 Third run with the recording in place + flag still set → test passes in grading mode; recording files are NOT overwritten — 189f5aa
- [x] 3.6 `git rm` the throwaway `99-throwaway` directory; `git status` clean for `01-sample/` — 189f5aa

### Phase 4: Cookbook, test-plan sync, regex finding

#### Automated

- [x] 4.1 `./gradlew test` (full suite) passes — no test changes in Phase 4
- [x] 4.2 `grep -c "TBD — see §3 Phase 1" context/foundation/test-plan.md` returns `0`
- [x] 4.3 `grep -F "in progress" context/foundation/test-plan.md | grep -F "LLM extraction regression harness"` matches one line
- [x] 4.4 `grep -F "## Findings" context/changes/llm-extraction-regression-harness/change.md` matches

#### Manual

- [x] 4.5 Cold-read `test-plan.md` §6.4 — a fresh reader can add a new fixture without consulting `plan.md` or `research.md`
- [x] 4.6 `change.md` Findings entry names file:line + symptom + hand-off path; no implementation-level prescription is baked in
