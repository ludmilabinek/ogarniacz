# Source Image Auto-Purge (S-06) Implementation Plan

## Overview

NFR compliance slice (PRD line 144). Make "once every proposed event from a given uploaded image has been accepted or rejected, the source image is automatically removed from operator-accessible storage" enforceable. Implementation: a `@Scheduled` sweep deletes `SourceImage` rows that match a 3-clause eligibility predicate; FK gets `ON DELETE CASCADE` so the surviving `ProposedEvent` children fall with the parent in one commit. Establishes the first deterministic `@Scheduled` test pattern in the repo and a new `test-plan.md §6.9` cookbook entry.

## Current State Analysis

- `SourceImage` is `bytea`-backed (`SourceImage.java:34`) — an S-05 decision specifically to keep the purge a single transactional row delete, not `lo_unlink`.
- `EventReviewService.applyDecisions` (`EventReviewService.java:78-80`) stamps `resolvedAt` once on the first decision with a `null` guard. This was put there as S-06's scan key. It is correct for State D (full review submitted) and lies for several other states (see §Truth table below).
- `ExtractionService.runExtraction` (`ExtractionService.java:43-82`) creates `PENDING` proposals on success, stamps `lastErrorKind` + `correlationId` on failure. **Success-with-empty branch (LLM returns `[]`) does not stamp anything on the image today** — leaves `resolvedAt = null` with zero proposals.
- `EventReviewController.retry` (`EventReviewController.java:114-132`) clears `lastErrorKind` + `correlationId` and saves before kicking the async extraction. Today this is its own transaction, separate from `runExtraction`.
- `ProposedEvent.sourceImage` is `@ManyToOne(optional=false)` with no cascade (`ProposedEvent.java:39-41`). The single existing index on the FK is `ix_proposed_event_source_image` over `(source_image_id)`.
- `Event` has no FK to `SourceImage` — purge cannot orphan promoted events or affect feed UIDs (`archive/2026-06-21-edit-delete-accepted-events/plan.md:46`).
- `@EnableScheduling` is on (`AppApplication.java:18`). One existing `@Scheduled` precedent — `ExtractionJobRegistry.sweep()` — has zero tests and zero error handling, untested in the repo.
- `test-plan.md §3` lists four rollout phases; image retention is not among them. `§6.9` does not exist.
- DB migrations: no Flyway/Liquibase. Schema evolves through Hibernate `ddl-auto=update` (test-plan §7); H2 tests boot a fresh schema each run.

## Desired End State

- A `SourceImage` row (and its `ProposedEvent` children) is deleted within the configured sweep cadence after the last live activity on it — either the user POSTs their final decision (State D) **or** extraction succeeds with an empty event list (formerly success-empty, now stamped). The audit trail (failed extractions, correlationIds) lives in slf4j logs, not in the row.
- Sweep runs every 10 minutes by default (`@Scheduled(fixedDelay = 600_000L)`), tunable via `app.event.source-image.purge.interval-ms`.
- Logging contract emits a structured INFO line on each sweep that purged at least one row, DEBUG per row, ERROR with stacktrace on any thrown from inside `@Scheduled`. Silent no-op cycles by design.
- Eligibility predicate is 3-clause, defended below: `NOT EXISTS (PENDING) AND lastErrorKind IS NULL AND resolvedAt IS NOT NULL`.
- `EventReviewController.review` GET on a purged image returns `404` via the existing `findByIdAndUser` miss path — the post-purge contract is documented in JavaDoc and pinned by one regression test.

### Key Discoveries

- **Truth table after this plan lands** (see also `research.md:63-69`):

  | State | resolvedAt | lastErrorKind | PENDING rows | NFR purgeable? | Predicate matches? | Notes |
  |---|---|---|---|---|---|---|
  | A — Upload, extraction in flight | null | null | none | no | **no** (resolvedAt null) | window protected by clause 3 |
  | B — Extraction failed | null | set | none | n/a (deferred) | **no** (lastErrorKind set) | out of scope, follow-up slice |
  | C — Abandoned after extraction | null | null | N PENDING | no | **no** (PENDING exists) | clause 1 |
  | D — Full review submitted | set | null | all decided | yes | **yes** | main path |
  | E — Partial review residue | set | null | mixed | no | **no** (PENDING exists) | clause 1 catches the State-E bug |
  | F — Success-empty (LLM returned `[]`) **after Phase 2** | set | null | none | yes | **yes** | enabled by Phase 2 stamp |
  | F′ — Success-empty (hypothetical, **unstamped**) | null | null | none | yes | **no** (resolvedAt null) | regression guard for predicate clause 3 (`resolvedAt IS NOT NULL`), not for Phase 2's stamp — see test 2.1 |
  | abandoned-A — extraction silently never ran | null | null | none | n/a (deferred) | **no** (resolvedAt null) | out of scope, follow-up slice |

- **Why `resolvedAt IS NOT NULL` does not inherit the State-E bug from S-05:** State E has `resolvedAt != null` and `PENDING > 0`. Clause 1 (`NOT EXISTS PENDING`) excludes it. The State-E bug only occurred when `resolvedAt != null` was the **sole** purge criterion — here it's an `AND` alongside clause 1, so the bug cannot reappear.
- **DB index strategy**: add a composite `ix_proposed_event_source_image_status` over `(source_image_id, status)`. The existing single-column index stays as-is — Postgres can serve `findBySourceImage…` queries from the composite index's prefix, but removing the old index here would expand the plan's surface beyond NFR compliance.
- **No Flyway in repo.** The schema delta (new index + `ON DELETE CASCADE` on existing FK) ships as `@Index` + `@OnDelete` annotations on `ProposedEvent`. `ddl-auto=update` does NOT alter existing constraints; the CASCADE flip must be applied manually to any non-test database. The plan ships explicit SQL for operators (`Migration Notes` below) — H2 tests boot a fresh schema so they observe the annotation directly.

## What We're NOT Doing

- **Orphan classes B (extraction-failed) and abandoned-A (silent never-ran) stay alive forever after this slice ships.** The main sweep predicate deliberately excludes both. A follow-up slice `source-image-orphan-ttl` (post-MVP, planned with measured volume signal after a month of usage) owns those. The plan records this gap explicitly so a future reviewer sees it was a decision, not an oversight.
- **No tombstone / `IMAGE_GONE` synthetic response.** Purged images collapse to plain HTTP 404 in `EventReviewController.review` — indistinguishable from never-existed and wrong-user. Rationale + accepted tradeoff captured verbatim in Phase 5.
- **No `lastErrorKind`/`correlationId` move from `EventReviewController.retry` into `ExtractionService.runExtraction`.** With clause 3 (`resolvedAt IS NOT NULL`), the retry race is closed by the predicate — retry leaves `resolvedAt = null`, predicate fails, no purge. The hygiene cleanup of `retry()` (a conditional state change without a conditional save) is a follow-up `event-review-retry-cleanup` micro-slice, not part of this NFR-compliance slice.
- **No retrofitting `ExtractionJobRegistry.sweep` to the new logging pattern.** Separate 1-file micro-change. S-06 establishes the canonical scheduled-sweep logging shape; the existing sweep's silent-no-logging design is grandfathered into the follow-up.
- **No Micrometer / Prometheus counter on purge events.** First metric in repo is not a one-liner — it's a dependency + binder config + Prometheus-vs-actuator-only decision. Separate observability slice.
- **No `data` column nullability change.** Schema models payload, not lifecycle. Mechanism is DELETE row, not NULL blob.
- **No Flyway introduction.** The `ddl-auto=update` parity that test-plan §7 names as a known-watch carries forward; the operator runs the one-off ALTER for the CASCADE flip per Migration Notes.

## Implementation Approach

Strict ordering of phases gates the contract:

1. **Phase 1** — Schema first (the predicate can't be tested without `ON DELETE CASCADE` and the composite index).
2. **Phase 2** — Close the success-empty leak in `ExtractionService` before the purge service ever runs. Independent of the predicate; one method, one assertion.
3. **Phase 3** — Purge service with the 3-clause JPQL DELETE. State-by-state truth-table tests give the predicate its formal proof. Hermetic for predicate logic; integration for cascade behavior.
4. **Phase 4** — Wire `@Scheduled` + logging contract. Establishes the `§6.9` deterministic test pattern by driving the scheduled method directly in a `@SpringBootTest`.
5. **Phase 5** — `EventReviewController.review` 404 post-purge contract: JavaDoc + one regression test.

Closeout (after Phase 5): append `test-plan.md §3` Phase 5 row, write `§6.9` cookbook entry, flip `change.md` status to `implementing` (per local convention — `/10x-implement` owns this transition; planning leaves it `open`).

## Critical Implementation Details

- **Predicate clause 3 (`resolvedAt IS NOT NULL`) extends `resolvedAt`'s semantic** from "user POSTed at least one decision" to "review lifecycle complete — nothing more to do here". Phase 2 makes the success-empty extraction also stamp it, which is the symmetric move that makes the broader semantic honest. The null guard in Phase 2 (`if (image.getResolvedAt() == null) image.setResolvedAt(now)`) is symmetric with `EventReviewService.applyDecisions:78-80` — `resolvedAt` is a write-once invariant, and no current code path can set it twice, but the guard makes the invariant explicit so a future reader doesn't have to grep both call sites to verify it.
- **`@OnDelete(action = OnDeleteAction.CASCADE)` on an existing `@ManyToOne` is a Hibernate-DDL-generation hint, not runtime behavior.** With `ddl-auto=update`, Hibernate will NOT drop+re-add the existing FK constraint to enforce the new cascade. Tests boot H2 with a fresh schema → they observe the cascade. The deployed Postgres on Neon does not until the manual ALTER ships (Migration Notes). The Phase 1 verification step calls this out explicitly.
- **`@Scheduled` runs on Spring's single-threaded default `TaskScheduler`** (no `TaskSchedulerBuilder` config in the repo). A slow purge would queue `ExtractionJobRegistry.sweep`. With `fixedDelay` (not `fixedRate`) the new sweep waits the interval from the previous one's completion → no overlap. The predicate hits a composite index over a small table; expected duration in MVP scale is sub-millisecond.

## Phase 1: Schema migration — composite index + ON DELETE CASCADE

### Overview

Add a composite covering index `(source_image_id, status)` on `proposed_event` and convert the existing `proposed_event.source_image_id` FK to `ON DELETE CASCADE`. JPA annotations on `ProposedEvent`; explicit SQL for production rollout.

### Changes Required:

#### 1. ProposedEvent entity — index + cascade annotations

**File**: `src/main/java/com/example/app/event/ProposedEvent.java`

**Intent**: Declare the new composite index alongside the existing one; mark the `@ManyToOne` FK with Hibernate's `@OnDelete(action = OnDeleteAction.CASCADE)` so fresh-schema environments (H2 tests, future Flyway baseline) carry the cascade by construction.

**Contract**: `@Table.indexes` gains `@Index(name = "ix_proposed_event_source_image_status", columnList = "source_image_id, status")`. The existing `ix_proposed_event_source_image` stays. On the `sourceImage` field, add `@org.hibernate.annotations.OnDelete(action = org.hibernate.annotations.OnDeleteAction.CASCADE)`.

#### 2. Operator-facing migration SQL

**File**: `context/changes/source-image-auto-purge/migration.sql` (new, plan-folder-scoped — not a Flyway resource)

**Intent**: Concrete SQL the operator runs once on each non-test environment (currently Neon). Idempotent shape so a re-run is safe.

**Contract**: Two statements: (a) `CREATE INDEX IF NOT EXISTS ix_proposed_event_source_image_status ON proposed_event (source_image_id, status);` (b) the FK swap: locate the existing FK name via `\d proposed_event`, then `ALTER TABLE proposed_event DROP CONSTRAINT <fk_name>; ALTER TABLE proposed_event ADD CONSTRAINT fk_proposed_event_source_image FOREIGN KEY (source_image_id) REFERENCES source_image(id) ON DELETE CASCADE;`. Add a comment header noting the test-environment path (H2 ddl-auto picks it up automatically) and that re-running is safe.

### Success Criteria:

#### Automated Verification:

- `./gradlew build` compiles with the new annotations
- New `SourceImageRepositoryTest` (file's first test) seeds a `SourceImage` + `ProposedEvent` pair, calls `sourceImageRepository.delete(image)`, asserts the child `ProposedEvent` is also gone (proves H2 honors the cascade annotation) — `./gradlew test --tests com.example.app.event.SourceImageRepositoryTest`

#### Manual Verification:

- Operator runs `migration.sql` against the Neon DB before deploying Phase 3+; `\d proposed_event` shows `ON DELETE CASCADE` on the FK and lists `ix_proposed_event_source_image_status`

**Implementation Note**: After Phase 1's automated verification passes, pause for the operator to confirm the Neon migration before Phase 3 lands in production.

---

## Phase 2: Close the success-empty leak (ExtractionService)

### Overview

`ExtractionService.runExtraction` (`ExtractionService.java:43-82`) currently does not touch `SourceImage.resolvedAt` on the success path. When the LLM returns an empty event list, the image ends up `{resolvedAt=null, lastErrorKind=null, 0 PENDING}` — invisible to the 3-clause predicate. Stamp `resolvedAt` in the success branch, symmetric with `EventReviewService.applyDecisions:78-80`.

### Changes Required:

#### 1. ExtractionService — stamp resolvedAt on success

**File**: `src/main/java/com/example/app/event/ExtractionService.java`

**Intent**: After the `for` loop that persists `ProposedEvent` rows in the success branch (`:56-58`), conditionally stamp `image.resolvedAt` if it's still null and **explicitly call `sourceImageRepository.save(image)`** — mirroring the same method's failure-branch pattern (`ExtractionService.java:65, :77`). `runExtraction` is `@Async` with no `@Transactional`, so dirty-checking has no commit to flush against; the explicit save is load-bearing. lessons.md:80-85's "no save() inside a `@Transactional` service method" rule does NOT apply here — it's scoped to transactional contexts, which this method is not. The null guard preserves `resolvedAt` as a write-once invariant: even though no current code path can set it twice, the guard makes the invariant explicit at the second write site without forcing a reader to grep both `applyDecisions` and `runExtraction` to verify it. Both the state mutation and the save are conditional, so the asymmetry lessons.md warns against (conditional state + unconditional save) is avoided. Inject `Clock` via constructor (already a Spring-managed bean, used at `EventReviewService.java:25,30`) — required for the symmetric pattern and deterministic-time tests.

**Contract**: New constructor parameter `Clock clock`; method body addition immediately after the `for` loop in the success branch: `if (image.getResolvedAt() == null) { image.setResolvedAt(Instant.now(clock)); sourceImageRepository.save(image); }`. The failure branches (`LlmExtractionException`, `RuntimeException` catches) remain unchanged — by predicate design, failed extractions are not stamped.

#### 2. FixedClockTestConfig — shared deterministic-Clock test support

**File**: `src/test/java/com/example/app/testsupport/FixedClockTestConfig.java` (new)

**Intent**: Provide a deterministic `Clock` for any `@SpringBootTest` that needs to assert against `Instant.now(clock)`. Used in Phase 2 (`ExtractionServiceTest`) and Phase 4 (`SourceImagePurgeSchedulerTest`). Chosen over `@MockitoBean Clock` because `@MockitoBean` rebuilds the application context per test class (lessons.md:47-55 fragmentation) and propagates to every other Clock consumer in the context (`EventReviewService`, `ExtractionJobRegistry`, `CalendarController`) — many of which assert on real time. A `@TestConfiguration` `@Primary` bean is opt-in (`@Import(FixedClockTestConfig.class)`) so non-opted-in tests keep the real Clock, and it doesn't churn the context cache the way per-test `@MockitoBean` does. Also chosen over a between-instants assertion because S-06's correctness — and the §6.9 cookbook entry — wants exact equality so the test fails loudly if `resolvedAt` is stamped by accident.

**Contract**: A `@TestConfiguration` class exposing `@Bean @Primary Clock fixedClock()` returning `Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)`. One `public static final Instant FIXED_INSTANT = Instant.parse("2026-01-15T12:00:00Z")` constant tests assert against. Tests opt in with `@Import(FixedClockTestConfig.class)` on the class annotation. Seeds the `§6.9` cookbook entry on deterministic Clock + `@Scheduled` pattern.

### Success Criteria:

#### Automated Verification:

- New `ExtractionServiceTest` case: `@Import(FixedClockTestConfig.class)` on the class; given an image with `resolvedAt=null`, `LlmVisionClient` mocked to return `LlmExtractionResult` with empty list, after `runExtraction` returns, the image's `resolvedAt` equals `FixedClockTestConfig.FIXED_INSTANT`
- Existing `ExtractionServiceTest` failure-branch cases (`TIMEOUT`, `PROVIDER_ERROR`, `MALFORMED_RESPONSE`, `UNEXPECTED`) still pass and verify `resolvedAt` is **not** stamped on failure paths
- New case asserts non-empty success (N proposals) also stamps `resolvedAt` equal to `FixedClockTestConfig.FIXED_INSTANT` — symmetric with empty-success
- `./gradlew test --tests com.example.app.event.ExtractionServiceTest`

#### Manual Verification:

- None — pure backend lifecycle change with no UI surface

---

## Phase 3: Source-image purge service — 3-clause predicate

### Overview

New service `SourceImagePurgeService` exposes `purgeEligible(): int`. Implementation is a single `@Modifying` JPQL DELETE on the `SourceImage` repository; the `ON DELETE CASCADE` from Phase 1 carries the `ProposedEvent` children with it. Per-state truth-table tests give the predicate its formal proof.

### Changes Required:

#### 1. SourceImageRepository — purge query method

**File**: `src/main/java/com/example/app/event/SourceImageRepository.java`

**Intent**: Add a `@Modifying @Query` method that runs the 3-clause JPQL DELETE in a single statement. Return type `int` reflects rows affected.

**Contract**: New method `int purgeEligible();` annotated with `@Modifying` and the JPQL: `DELETE FROM SourceImage si WHERE NOT EXISTS (SELECT 1 FROM ProposedEvent pe WHERE pe.sourceImage = si AND pe.status = com.example.app.event.ProposedEvent.ProposedEventStatus.PENDING) AND si.lastErrorKind IS NULL AND si.resolvedAt IS NOT NULL`. (Enum is `ProposedEvent.ProposedEventStatus` per `ProposedEvent.java:29-33`; research notes the column is a string-32, but JPA compares the enum directly at the JPQL layer.)

#### 2. SourceImagePurgeService — service wrapper

**File**: `src/main/java/com/example/app/event/SourceImagePurgeService.java` (new)

**Intent**: A thin `@Service` `@Transactional` wrapper around the repository's `purgeEligible()`. Wraps the DELETE in an explicit transaction so the cascade fires atomically; returns the affected-row count for the scheduler to log.

**Contract**: One method `@Transactional public int purgeEligible()`. No other state, no other side effects in the service — logging lives in Phase 4's scheduler wrapper, not here.

#### 3. Truth-table integration tests

**File**: `src/test/java/com/example/app/event/SourceImagePurgeServiceTest.java` (new)

**Intent**: One `@SpringBootTest` class. Each `@Test` seeds one specific state from the §Truth table above and asserts the post-`purgeEligible()` outcome. Includes the `success_empty_with_phase_2_stamping → purge` main case AND the explicit `hypothetical_unstamped_success_empty → keep` regression guard — the second case forces the test to construct an image identical to a success-empty one but with `resolvedAt` manually set to `null`, pinning JPQL predicate clause 3 (`resolvedAt IS NOT NULL`) as load-bearing — without this clause, never-stamped images (legitimate failures, manual seeds, in-flight extractions) would be eligible for purge. Phase 2's stamp itself is regression-guarded separately by test 2.1.

**Contract**: Eight `@Test` methods covering States A, B, C, D, E, F (stamped), F′ (unstamped regression guard), and abandoned-A. Each test seeds one `SourceImage` + the relevant number of `ProposedEvent` children in the target state, calls `service.purgeEligible()`, then asserts via `sourceImageRepository.findById(...)` whether the image survived. The `ON DELETE CASCADE` is also asserted in the State-D case via `proposedEventRepository.findBySourceImage(...)`.

### Success Criteria:

#### Automated Verification:

- All eight truth-table tests pass
- `./gradlew test --tests com.example.app.event.SourceImagePurgeServiceTest`
- `./gradlew build` passes (no regression in surrounding service wiring)

#### Manual Verification:

- None — service is exercised purely by tests; the scheduler in Phase 4 is what makes it operationally visible

---

## Phase 4: @Scheduled sweep + logging contract

### Overview

Wire a `@Scheduled` invocation of `SourceImagePurgeService.purgeEligible()`, with the exact logging contract specified in design. Establish the deterministic `@Scheduled` test pattern — drive the scheduled method directly in a `@SpringBootTest` — which becomes the `§6.9` cookbook entry.

### Changes Required:

#### 1. SourceImagePurgeScheduler — @Scheduled entry point

**File**: `src/main/java/com/example/app/event/SourceImagePurgeScheduler.java` (new)

**Intent**: A small `@Component` that holds the `@Scheduled` annotation, calls `service.purgeEligible()`, and emits the logging contract. Separated from `SourceImagePurgeService` so the "when" (scheduling) is decoupled from the "what" (predicate) — the service stays callable from tests without any scheduler concerns.

**Contract**: One method annotated `@Scheduled(fixedDelayString = "${app.event.source-image.purge.interval-ms:600000}")`. Body shape (logging is load-bearing — captured verbatim):
- Capture start time via `Clock` (constructor-injected, same bean as Phase 2)
- `int purgedCount = service.purgeEligible();` wrapped in try/catch
- On success **with `purgedCount > 0`**: emit one `log.info("source_image_purge_sweep purged_count={} duration_ms={}", purgedCount, durationMs)`
- On success with `purgedCount == 0`: emit nothing (silent no-op cycles by design)
- On exception thrown: `log.error("source_image_purge_sweep_failed", ex)` with the full stacktrace — without this, a silently-stuck sweep would be invisible until disk fills

Per-row forensics (which ids vanished and when) are explicitly out of scope for this slice — deferred to the observability slice listed in §"What We're NOT Doing". The bulk DELETE stays a single statement; `purgedCount` is the operationally meaningful signal.

#### 2. application.properties — cadence knob

**File**: `src/main/resources/application.properties`

**Intent**: Document the knob with its default value so an operator scanning the file sees the contract without grep'ing the code.

**Contract**: One line: `app.event.source-image.purge.interval-ms=600000` with a comment `# S-06 source-image auto-purge: fixedDelay between sweep cycles (ms). 10 min default; lower at storage-pressure risk, see context/changes/source-image-auto-purge/plan.md`.

#### 3. SourceImagePurgeSchedulerTest — drive-the-scheduled-method pattern

**File**: `src/test/java/com/example/app/event/SourceImagePurgeSchedulerTest.java` (new)

**Intent**: Pin the deterministic `@Scheduled` test pattern that `§6.9` will codify. The test seeds two purgeable + two non-purgeable images, calls `scheduler.<method>()` directly (no scheduler thread involved), asserts the surviving repository state AND captures the INFO log via a `ListAppender`-style Logback fixture, asserting the log line shape. Reuses `FixedClockTestConfig` from Phase 2 via `@Import(FixedClockTestConfig.class)` so `duration_ms` in the INFO line is asserted against a known delta (the test does NOT advance the clock between start and end, so `duration_ms=0`).

**Contract**: Three test methods — (a) seed-and-call asserts `purged_count = 2` and that the INFO line carries `purged_count=2 duration_ms=0`; (b) zero-eligible case asserts no INFO emitted; (c) service-throws case (via `@MockitoBean` on the service) asserts ERROR with stacktrace emitted.

### Success Criteria:

#### Automated Verification:

- Three scheduler test methods pass
- `./gradlew test --tests com.example.app.event.SourceImagePurgeSchedulerTest`
- `./gradlew test` whole-suite passes — verifies no context-cache pollution from the new `@Import(FixedClockTestConfig.class)` + `@MockitoBean` service (note: the test config is opt-in `@Import`, not a global `@MockitoBean Clock`, so the context cache fragments only for tests that import it — same scope as `UserTestFixtures` consumers)
- `./gradlew bootRun` (manual smoke) — observe a single INFO line every 10 minutes only when a purge happened; verify silence on no-op cycles

#### Manual Verification:

- Run `./gradlew bootRun` with `app.event.source-image.purge.interval-ms=60000` (1 minute) for a quick smoke; trigger a manual review-submit to create a State-D image; within 1 minute observe the INFO line in the console; no logs on idle cycles

---

## Phase 5: 404 post-purge contract on EventReviewController

### Overview

Document the post-purge contract on `EventReviewController.review` and pin it with one regression test. Zero new code paths — purged images collapse to `404` via the existing `findByIdAndUser` miss branch.

### Changes Required:

#### 1. EventReviewController — JavaDoc on the `review` handler

**File**: `src/main/java/com/example/app/event/EventReviewController.java`

**Intent**: REPLACE the stale "revisit when S-06" sub-bullet in the existing JavaDoc on `review` (currently at `EventReviewController.java:67-71`, written before S-06 had decided its post-purge contract) with the verbatim 404 contract below. This fold-in (not a second JavaDoc block bolted on top) keeps the file from carrying two contradictory paragraphs after this slice lands. The surrounding JavaDoc structure on `review` stays — only the obsolete sub-bullet is rewritten. Text agreed with the user; lives here so a reviewer doesn't need to grep `context/`.

**Contract**: Replace the existing "revisit when S-06 purge ..." sub-bullet (and any directly-coupled prose around it) with a sub-section reading exactly:

> **Post-purge contract on `GET /events/from-image/{id}/review`**: a purged image is indistinguishable from never-existed / wrong-user / unknown-id — all three collapse to HTTP 404 via `findByIdAndUser().orElseThrow(NOT_FOUND)`. This is intentional: introducing a tombstone / `IMAGE_GONE` branch would reintroduce the audit-row coupling rejected in S-06 plan Q2 and add a table whose only purpose is to soften an edge-case copy. Test coverage: `EventReviewControllerTest#getReviewAfterPurgeReturns404` purges an image and asserts the GET returns 404; reuses the existing 404-on-wrong-user test as the contract anchor.
>
> **Accepted tradeoff**: the review URL is a transient process-flow URL (`/events/from-image/{uuid}/review`), not a stable product deep-link; bookmarking it is outside the intended use case. A soft-copy 404 (the global error page) covers the rare bookmark-after-purge case at lower cost than per-controller logic.

#### 2. EventReviewControllerTest — post-purge 404 regression test

**File**: `src/test/java/com/example/app/event/EventReviewControllerTest.java`

**Intent**: Extend the existing test class (per-controller layout standard — see lessons.md:47-55). One new `@Test getReviewAfterPurgeReturns404` seeds a State-D image, runs the purge service inline, then issues `GET /events/from-image/{id}/review` and asserts `status().isNotFound()`. Mirrors the existing 404-on-wrong-user case shape.

**Contract**: One additional `@Test` method. Reuses the per-scenario email pattern from `EventControllerTest` (`alice-review-postpurge@example.com`) to avoid colliding with sibling tests in the `@SpringBootTest` context cache.

### Success Criteria:

#### Automated Verification:

- New `getReviewAfterPurgeReturns404` test passes
- `./gradlew test --tests com.example.app.event.EventReviewControllerTest` passes (full class — no regression on existing cases)

#### Manual Verification:

- None — the contract IS the JavaDoc + the test; no UI surface

---

## Testing Strategy

### Unit Tests:

- None new at the pure-JUnit layer; predicate logic is JPQL-shaped and only meaningful against a real schema (H2 in tests).

### Integration Tests:

- `SourceImagePurgeServiceTest` (Phase 3) — eight truth-table cases; F pins the main success-empty purge path, F′ pins predicate clause 3 (`resolvedAt IS NOT NULL`) as load-bearing (Phase 2's stamp itself is regression-guarded by test 2.1)
- `SourceImagePurgeSchedulerTest` (Phase 4) — three cases pinning the logging contract (INFO-only-on-purge, silent-on-noop, ERROR-on-throw)
- `ExtractionServiceTest` extensions (Phase 2) — success-empty and non-empty-success both stamp `resolvedAt`; failure branches do not
- `EventReviewControllerTest` extension (Phase 5) — one post-purge 404 case
- New `SourceImageRepositoryTest` (Phase 1) — file's first test; verifies cascade deletes children

### Manual Testing Steps:

1. Run `./gradlew bootRun` with `app.event.source-image.purge.interval-ms=60000`
2. Upload an image, submit decisions for every proposal (State D)
3. Wait one sweep cycle (≤60s)
4. Observe single INFO log line: `source_image_purge_sweep purged_count=1 duration_ms=<n>`
5. Verify via psql/h2 console that the `source_image` row is gone AND the `proposed_event` children are gone (CASCADE)
6. Trigger a State-E scenario (hand-crafted POST omitting one PENDING) — verify the image survives the next sweep cycle (clause 1 catches it)
7. Verify silence on the next two cycles where no purgeable images exist

## Performance Considerations

- The predicate hits a composite index over `(source_image_id, status)` from Phase 1. At MVP scale (<1000 `source_image` rows expected over the first year), a sub-millisecond DELETE.
- Sweep runs on Spring's single-threaded default `TaskScheduler`. `fixedDelay` (not `fixedRate`) means the next invocation waits the configured interval from the previous one's **completion**, so a slow sweep cannot queue invocations — at worst it delays `ExtractionJobRegistry.sweep` by the sweep duration.

## Migration Notes

The `proposed_event.source_image_id` FK change (adding `ON DELETE CASCADE`) and the new composite index ship as JPA annotations in Phase 1, but `ddl-auto=update` does NOT alter existing constraints. Operators MUST run the SQL in `context/changes/source-image-auto-purge/migration.sql` against each non-test environment (currently Neon) before Phase 3+ deploys. The SQL is idempotent (`CREATE INDEX IF NOT EXISTS` + a guarded constraint swap). H2 tests boot a fresh schema each run, so the test environment picks up the cascade from the annotation directly.

The `application.properties` knob `app.event.source-image.purge.interval-ms` defaults to `600000` (10 min). Operators can lower it temporarily during incident response (e.g. storage pressure) without code change.

## References

- Related research: `context/changes/source-image-auto-purge/research.md`
- PRD line 144: `context/foundation/prd.md:144`
- Roadmap S-06: `context/foundation/roadmap.md:148-159`
- S-05 archive — anchor rationale, bytea-vs-Lob decision: `context/archive/2026-06-16-image-extraction-and-review-acceptance/plan.md:75-78`, `:366`
- S-05 archive — canonical S-06 query shape: `context/archive/2026-06-16-image-extraction-and-review-acceptance/research.md:202`
- S-05 impl-review F4 (race hand-off): `context/archive/2026-06-16-image-extraction-and-review-acceptance/reviews/impl-review.md:69-76`
- S-03 — `Event` has no FK to `SourceImage`: `context/archive/2026-06-21-edit-delete-accepted-events/plan.md:46`
- lessons.md `@Transactional` save semantics: `context/foundation/lessons.md:80-85`
- test-plan.md — `§3` Phase 5 to be added; `§6.9` cookbook entry to be added; `§7` `ddl-auto=update` parity note: `context/foundation/test-plan.md:74-77`, `:267`, `§7`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Schema migration — composite index + ON DELETE CASCADE

#### Automated

- [ ] 1.1 ./gradlew build compiles with the new annotations
- [ ] 1.2 New SourceImageRepositoryTest (file's first test) seeds a SourceImage + ProposedEvent pair, calls sourceImageRepository.delete(image), asserts the child ProposedEvent is also gone (proves H2 honors the cascade annotation) — ./gradlew test --tests com.example.app.event.SourceImageRepositoryTest

#### Manual

- [ ] 1.3 Operator runs migration.sql against the Neon DB before deploying Phase 3+; \d proposed_event shows ON DELETE CASCADE on the FK and lists ix_proposed_event_source_image_status

### Phase 2: Close the success-empty leak (ExtractionService)

#### Automated

- [ ] 2.1 New ExtractionServiceTest case: @Import(FixedClockTestConfig.class) on the class; given an image with resolvedAt=null, LlmVisionClient mocked to return LlmExtractionResult with empty list, after runExtraction returns, the image's resolvedAt equals FixedClockTestConfig.FIXED_INSTANT
- [ ] 2.2 Existing ExtractionServiceTest failure-branch cases (TIMEOUT, PROVIDER_ERROR, MALFORMED_RESPONSE, UNEXPECTED) still pass and verify resolvedAt is not stamped on failure paths
- [ ] 2.3 New case asserts non-empty success (N proposals) also stamps resolvedAt equal to FixedClockTestConfig.FIXED_INSTANT — symmetric with empty-success
- [ ] 2.4 ./gradlew test --tests com.example.app.event.ExtractionServiceTest

### Phase 3: Source-image purge service — 3-clause predicate

#### Automated

- [ ] 3.1 All eight truth-table tests pass
- [ ] 3.2 ./gradlew test --tests com.example.app.event.SourceImagePurgeServiceTest
- [ ] 3.3 ./gradlew build passes (no regression in surrounding service wiring)

### Phase 4: @Scheduled sweep + logging contract

#### Automated

- [ ] 4.1 Three scheduler test methods pass
- [ ] 4.2 ./gradlew test --tests com.example.app.event.SourceImagePurgeSchedulerTest
- [ ] 4.3 ./gradlew test whole-suite passes — verifies no context-cache pollution from the new @Import(FixedClockTestConfig.class) + @MockitoBean service (the test config is opt-in @Import, not a global @MockitoBean Clock)
- [ ] 4.4 ./gradlew bootRun (manual smoke) — observe a single INFO line every 10 minutes only when a purge happened; verify silence on no-op cycles

#### Manual

- [ ] 4.5 Run ./gradlew bootRun with app.event.source-image.purge.interval-ms=60000 (1 minute) for a quick smoke; trigger a manual review-submit to create a State-D image; within 1 minute observe the INFO line in the console; no logs on idle cycles

### Phase 5: 404 post-purge contract on EventReviewController

#### Automated

- [ ] 5.1 New getReviewAfterPurgeReturns404 test passes
- [ ] 5.2 ./gradlew test --tests com.example.app.event.EventReviewControllerTest passes (full class — no regression on existing cases)
