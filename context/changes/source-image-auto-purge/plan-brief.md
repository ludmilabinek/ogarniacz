# Source Image Auto-Purge (S-06) — Plan Brief

> Full plan: `context/changes/source-image-auto-purge/plan.md`
> Research: `context/changes/source-image-auto-purge/research.md`

## What & Why

Make the PRD NFR retention contract (line 144: "once every proposed event from a given image has been accepted or rejected, the source image is automatically removed from operator-accessible storage") enforceable in code. Today nothing actually purges; the `SourceImage.resolvedAt` stamp added by S-05 is a scan-key placeholder that, used alone, both leaks (success-empty extractions, partial-submit residue) and races (fresh upload, retry path). S-06 closes the contract with a scheduled sweep behind a 3-clause eligibility predicate and `ON DELETE CASCADE` on the `ProposedEvent` FK.

## Starting Point

`SourceImage` is `bytea`-backed (an S-05 decision specifically to make purge a one-line DELETE). `EventReviewService.applyDecisions` stamps `resolvedAt` on the first decision with a null guard. `ExtractionService.runExtraction` creates `PENDING` proposals on success, stamps `lastErrorKind` + `correlationId` on failure, but **does nothing** in the success-with-empty-list branch. `@EnableScheduling` is on; one untested `@Scheduled` precedent exists (`ExtractionJobRegistry.sweep`). `test-plan.md §3` has no image-retention phase and `§6.9` does not exist. No Flyway — schema evolves via `ddl-auto=update`.

## Desired End State

A `SourceImage` (and its `ProposedEvent` children, via DB cascade) disappears within ~10 minutes of becoming eligible: either the user POSTs their final decision (State D) **or** an extraction succeeds with an empty event list (State F, after Phase 2's stamp). The audit trail (failed extractions, correlationIds) lives in slf4j logs, not in surviving rows. A `GET /events/from-image/{id}/review` on a purged image returns plain `404` — indistinguishable from never-existed and wrong-user — by design.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
|---|---|---|---|
| Eligibility predicate | `NOT EXISTS (PENDING) AND lastErrorKind IS NULL AND resolvedAt IS NOT NULL` (3-clause) | 2-clause leaked States A/F and let the fresh-upload + retry races purge live images; the third clause closes both without inheriting the State-E bug (because clause 1 alone excludes State E). | Plan (refined after a user-caught State-C → success-empty miscount) |
| Storage mechanism | DELETE `SourceImage` row + `ON DELETE CASCADE` on `ProposedEvent.source_image_id` | Audit goes to slf4j logs (S-05 correlationId pattern); the schema models payload, not lifecycle; `data` stays `NOT NULL`. | Plan |
| Trigger | Scheduled sweep only — no inline-in-`applyDecisions` purge | Single contract owns the predicate; sync inline misses States C/F entirely; sweep cadence is a tuning parameter, not a correctness one. | Plan |
| Stamping `resolvedAt` on success-empty extraction | Yes — `ExtractionService.runExtraction` stamps in the success branch, symmetric with `applyDecisions` (null guard, write-once invariant) | Without this, success-empty images leak forever — the leak became visible only after upgrading to the 3-clause predicate; surfaced by the user during phase review. | Plan |
| Retry race | Out of scope for S-06 — closed automatically by clause 3 (`resolvedAt IS NULL` during retry); hygiene cleanup of `EventReviewController.retry` goes to follow-up slice `event-review-retry-cleanup` | S-06 is NFR compliance, not a refactor opportunity; cleanup belongs in its own micro-slice. | Plan |
| Race UX on purged image | Plain 404 — no `IMAGE_GONE` synthetic, no tombstone | Tombstone reintroduces the audit-row coupling we rejected and adds a whole entity to round off an edge-case copy; review URL is a transient process-flow URL, not a stable deep link. | Plan |
| Sweep cadence + observability | `@Scheduled(fixedDelay = 600_000L)`, INFO log only when `purged_count > 0`, DEBUG per row, ERROR with stacktrace on throw; no Micrometer, no `next_run_at` field | Cadence covers a typical review session in one cycle; silent on no-ops keeps 140 daily INFO logs from drowning out signal; Micrometer is its own observability slice. | Plan |
| Orphan classes B + abandoned-A | Out of scope — deferred to a follow-up `source-image-orphan-ttl` slice | Each is its own policy call (TTL value not in PRD); main S-06 stays minimal and defensible; orphan slice planned with real volume signal after a month. | Plan |
| Migration delivery | `@OnDelete` + `@Index` annotations + an operator-run `migration.sql` (idempotent) — no Flyway introduction | `ddl-auto=update` does NOT alter existing FK constraints; introducing Flyway here breaks test-plan §7 parity and expands the slice. | Plan |

## Scope

**In scope:**
- Composite index `(source_image_id, status)` on `proposed_event`
- `ON DELETE CASCADE` on the existing FK (annotation + operator-run SQL)
- `ExtractionService.runExtraction` stamps `resolvedAt` on the success branch (covers success-empty + non-empty)
- `SourceImagePurgeService.purgeEligible()` with 3-clause JPQL DELETE
- `SourceImagePurgeScheduler` with `@Scheduled` + logging contract
- `application.properties` knob `app.event.source-image.purge.interval-ms`
- `EventReviewController.review` JavaDoc documenting the post-purge 404 contract + one regression test
- `test-plan.md §3` Phase 5 row + `§6.9` cookbook entry (closeout)

**Out of scope:**
- State B (extraction-failed) and abandoned-A orphan handling → follow-up `source-image-orphan-ttl`
- `EventReviewController.retry` hygiene cleanup → follow-up `event-review-retry-cleanup`
- Retrofitting `ExtractionJobRegistry.sweep` with the new logging shape → 1-file micro-change
- Micrometer / Prometheus counters on purge events → separate observability slice
- `lastErrorKind` move into `ExtractionService.runExtraction`'s transaction (defense-in-depth alternative) → not needed with clause 3
- Tombstone / `IMAGE_GONE` synthetic response
- Flyway / Liquibase introduction
- `data` column nullability change (mechanism is DELETE row, not NULL blob)

## Architecture / Approach

```
ExtractionService.runExtraction
  ├─ success branch (N > 0)   ──┐
  │                              ├─ stamp image.resolvedAt (null-guarded)
  └─ success-empty branch (N=0) ─┘
              │
              └──> SourceImage.resolvedAt = Instant.now(clock)
                                                │
                                                ▼
                       SourceImagePurgeScheduler @Scheduled(fixedDelay=600s)
                                                │
                                                ▼
                       SourceImagePurgeService.purgeEligible()
                                                │
                                                ▼
                  JPQL DELETE FROM SourceImage WHERE
                       NOT EXISTS (PENDING)
                   AND lastErrorKind IS NULL
                   AND resolvedAt IS NOT NULL
                                                │
                                                ▼
                              DB-level ON DELETE CASCADE
                                                │
                                                ▼
                              ProposedEvent children removed
```

`EventReviewController.review` is untouched in code shape — purged images already collapse to `404` via the existing `findByIdAndUser` miss path; this slice just documents that as the contract and pins it with a test.

## Phases at a Glance

| Phase | What it delivers | Key risk |
|---|---|---|
| 1. Schema migration | `(source_image_id, status)` covering index + `ON DELETE CASCADE` on FK (annotations + operator SQL) | `ddl-auto=update` silently does NOT apply the CASCADE flip to existing constraints; mitigation = the operator-run `migration.sql` is the source of truth for non-test environments |
| 2. Close the success-empty leak | `ExtractionService.runExtraction` stamps `resolvedAt` on success (both branches), symmetric with `applyDecisions` | Future reviewer questions the null guard — answered in plan prose: write-once invariant explicit at the second write site |
| 3. Purge service | `SourceImagePurgeService.purgeEligible()` + 8 truth-table integration tests including the F/F′ regression-guard split | Predicate complexity makes a copy-paste error in one clause silent — the F′ test specifically pins Phase 2's stamping as load-bearing |
| 4. @Scheduled sweep + logging | `SourceImagePurgeScheduler`, knob, logging contract, deterministic `@Scheduled` test pattern (§6.9 anchor) | First-of-its-kind `@Scheduled` test in the repo — pattern correctness is the main risk; mitigated by driving the scheduled method directly (already endorsed at `test-plan.md:267`) |
| 5. 404 post-purge contract | JavaDoc on `EventReviewController.review` + one regression test | Smallest phase; main risk is the JavaDoc drifting from the test — pinned by the test's exact assertion text matching the contract |

**Prerequisites:** S-05 (`image-extraction-and-review-acceptance`) shipped — provides `SourceImage`, `ProposedEvent`, `ExtractionService`, and the `resolvedAt` stamp seed.

**Estimated effort:** ~3 sessions across 5 phases. Phase 1 and Phase 5 are short; Phase 3 and Phase 4 carry the bulk of the test surface.

## Open Risks & Assumptions

- **Assumption**: `ProposedEvent.Status` is a JPA-comparable enum (or string column with comparable semantics in JPQL). The implementer confirms in Phase 3 — if the comparison shape differs, the JPQL clause adjusts but the predicate stays unchanged.
- **Assumption**: H2 honors `@OnDelete(action = OnDeleteAction.CASCADE)` at schema-creation time. The Phase 1 cascade test (`sourceImageRepository.delete(image)` → child gone) verifies this on the actual test runtime.
- **Risk**: The single-threaded default `TaskScheduler` shared with `ExtractionJobRegistry.sweep`. If the purge predicate ever expands to scan a much larger candidate set (post-MVP), latency could queue the registry sweep. Currently sub-millisecond at MVP scale; flag if predicate grows.
- **Risk**: The Neon migration is operator-run, not automated. A missed migration step ships Phase 3 to production with no cascade — sweep would fail with FK violation. The Phase 1 manual verification step gates this explicitly.

## Success Criteria (Summary)

- A user who decides every proposed event from an uploaded image sees the corresponding `SourceImage` row gone within ≤10 min (or the configured cadence)
- An image whose LLM extraction produced an empty event list is purged on the same cadence (no manual review needed)
- An image with any `PENDING` proposal remaining (whether from incomplete review or in-flight extraction) is **never** purged
- Operator can see, from slf4j logs alone, when sweeps happen, how many rows they touched, and any failures (no Micrometer / Prometheus required at MVP)
