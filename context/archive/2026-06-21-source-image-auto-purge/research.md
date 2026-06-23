---
date: 2026-06-21T15:52:42+0200
researcher: Ludmiła Drzewiecka
git_commit: bd65dc4d5999f39225cb3f251e74649ad498ae94
branch: main
repository: ludmilabinek/ogarniacz
topic: "S-06 source-image auto-purge — current lifecycle, purge anchor, oracle ambiguities, testing surface"
tags: [research, codebase, source-image, purge, NFR, retention, scheduling, jpa]
status: complete
last_updated: 2026-06-21
last_updated_by: Ludmiła Drzewiecka
---

# Research: S-06 source-image auto-purge — current lifecycle, purge anchor, oracle ambiguities, testing surface

**Date**: 2026-06-21T15:52:42+0200
**Researcher**: Ludmiła Drzewiecka
**Git Commit**: bd65dc4d5999f39225cb3f251e74649ad498ae94
**Branch**: main
**Repository**: ludmilabinek/ogarniacz

## Research Question

Roadmap slice S-06 (`source-image-auto-purge`, `context/foundation/roadmap.md:148-159`): "once every proposed event from a given uploaded image has been accepted or rejected, the source image is automatically removed from operator-accessible storage" (PRD NFR, `context/foundation/prd.md:144`). Map the current `SourceImage` lifecycle, surface every purge-anchor design decision S-05 laid down, identify oracle ambiguities the plan must resolve, and brief the testing surface — so `/10x-plan` can choose anchor, trigger, and mechanism with eyes open.

## Summary

The slice is an **NFR-compliance** slice; the feature surface is small but the oracle has three live ambiguities that the plan must close before code is written.

1. **Storage shape is settled.** Source images are stored as `bytea` on the `SourceImage` JPA entity (`src/main/java/com/example/app/event/SourceImage.java:34`) — a deliberate S-05 pick, made specifically to enable "one transactional DELETE" in S-06 (rejecting `@Lob` which would have required `lo_unlink`). No filesystem, no S3, no Fly volumes. No image-download endpoint exists; only `ExtractionService.runExtraction` and `EventReviewController.retry` re-read `image.getData()`.
2. **Purge anchor (`resolvedAt: Instant`) is in place but its current semantics LIE for the orphan classes the NFR cares about.** `EventReviewService.applyDecisions` stamps `resolvedAt = now` after the **first** decision, not after **all** proposals are resolved. The browser UX renders only PENDING rows and forces a radio per row — so happy-path covers all — but the server does NOT verify that the submitted decision set covers every PENDING proposal. Three orphan classes exist that current code never marks purgeable: extraction-failed (`lastErrorKind != null`, no proposals, `resolvedAt = null`), abandoned-after-extraction (`resolvedAt = null`, N PENDING), and partial-submit residue (`resolvedAt != null`, some PENDING remain).
3. **The PRD's "every proposed event … accepted or rejected" oracle translates to `COUNT(proposed_event WHERE status='PENDING' AND source_image_id = image.id) = 0`** — not `resolvedAt != null`. The S-05 archive named exactly this query (`context/archive/2026-06-16-image-extraction-and-review-acceptance/research.md:202`) as the canonical S-06 contract; the stamped column was added in addition to it, not in lieu of it.
4. **Trigger and mechanism are unresolved.** S-05's archive endorses a transactional DELETE; the codebase already has `@EnableScheduling` (`AppApplication.java:18`) with one existing `@Scheduled` user (`ExtractionJobRegistry.sweep`, untested). Synchronous-inline (inside `applyDecisions`) vs. scheduled-sweep vs. hybrid is a plan-time pick. Likewise DELETE-row vs. NULL-the-blob — both satisfy the literal NFR; (a) DELETE is what the archive recommends and what bytea-not-Lob optimizes for, (b) NULL preserves the `correlationId`/`createdAt`/`lastErrorKind` audit trail at the cost of one `nullable=false → nullable=true` migration.
5. **No test exercises a `@Scheduled` method anywhere in the repo, and `test-plan.md` §3 has no phase for image retention.** S-06 must establish the scheduled-method-test pattern (the cheapest shape — direct method call in `@SpringBootTest` — is already endorsed at `test-plan.md:267` but never realized). The slice must also add a §3 Phase 5 + §6.9 cookbook entry; image retention is not on the current four-phase rollout.

## Detailed Findings

### Current SourceImage lifecycle (the live codebase)

The entity (`src/main/java/com/example/app/event/SourceImage.java:19-50`) carries everything the purge predicate could possibly need:

- `id` UUID; `user` `@ManyToOne(optional=false)`; `data` `bytea` `NOT NULL`; `mimeType` (32); `createdAt` (immutable, set by `@PrePersist` at `:62-66`); **`resolvedAt: Instant` nullable** (`:43-44`); `lastErrorKind: String(32)` nullable; `correlationId: String(64)` nullable.
- **Index already in place**: `ix_source_image_user_resolved` on `(user_id, resolved_at)` (`:22`) — pre-positioned for the purge scan.

The five lifecycle stages (file:line refs):

1. **Upload** — `ImageUploadController.upload` (`event/ImageUploadController.java:52-79`) validates MIME (`:62-102`), constructs `new SourceImage(user, file.getBytes(), mimeType)`, persists at `:70-71`, fires `extractionService.runExtraction(jobId, saved.getId())` async at `:74`. Initial state: `resolvedAt=null`, `lastErrorKind=null`, no `ProposedEvent` children.
2. **Extraction success** — `ExtractionService.runExtraction` (`event/ExtractionService.java:43`, `@Async("extractionExecutor")`) reads `image.getData()` (`:55`), persists N `ProposedEvent` rows status=PENDING (`:56-58`), `jobRegistry.markDone(jobId)` (`:60`). **`SourceImage` is not touched on success** — no `resolvedAt` stamp, no save.
3. **Extraction failure** — `runExtraction` catch clauses (`:61-81`) stamp `image.setLastErrorKind(...)` + `setCorrelationId(...)` and call `sourceImageRepository.save(image)` (`:65`, `:77`); zero `ProposedEvent` rows are created. Image remains alive because `EventReviewController.retry` (`:114-132`) will clear those fields and re-invoke extraction.
4. **Review submission** — `EventReviewService.applyDecisions` (`event/EventReviewService.java:37-83`, `@Transactional`) iterates submitted decisions, promotes ACCEPT → `Event` and flips PENDING → ACCEPTED/REJECTED with `decidedAt = Instant.now(clock)`, then **unconditionally** stamps `image.setResolvedAt(now)` if `getResolvedAt()` was null (`:78-80`). Dirty-checking flushes on commit — no `save(image)` call (per `lessons.md:80-85`).
5. **Post-review** — `Event` rows carry copies of the proposal fields but have **no FK to `SourceImage`** (`event/Event.java`, no `source_image_id` column). S-03 (`edit-delete-accepted-events`) explicitly noted this: `context/archive/2026-06-21-edit-delete-accepted-events/plan.md:46` — "*`app_event` rows have no link to `source_image`*". Deleting `SourceImage` cannot orphan `Event`.

**FK cascades**: zero. `ProposedEvent.sourceImage` (`event/ProposedEvent.java:39-41`) is `@ManyToOne(optional=false)`; no `cascade`, no `orphanRemoval`, no `@OnDelete`. A raw `DELETE FROM source_image` fails FK if children remain. Index on the FK side: `ix_proposed_event_source_image` on `source_image_id` (`ProposedEvent.java:24-26`).

**Image data readers in production**: exactly one — `ExtractionService:55`. Templates pass only `sourceImage.id` for URL building (`review.html:20`, `review-error.html:16`); there is no `@GetMapping` that serves `image.data`. So setting `data = null` after resolution is operationally safe — no other code path will hit a NullPointerException.

**Scheduling surface**: `AppApplication.java:17-18` carries `@EnableAsync` + `@EnableScheduling`. The only existing `@Scheduled` task is `ExtractionJobRegistry.sweep()` (`event/ExtractionJobRegistry.java:81-85`, `fixedDelay = 60_000L`) which sweeps an in-memory map — no DB I/O. Default Spring `TaskScheduler` (single-threaded). Adding a second `@Scheduled` method has zero infra cost; the only friction is that a slow purge query would share the same thread as the registry sweep — worth flagging for the plan, not a blocker at MVP scale.

### Purge anchor: `resolvedAt` is correct on the happy path and lies on three orphan branches

The truth table for "purgeable" under PRD line 144 ("once every proposed event from a given image has been accepted or rejected"):

| State | `resolvedAt` | `lastErrorKind` | Proposals | Image still useful? | Purgeable by NFR? | Marked purgeable by current code? |
|---|---|---|---|---|---|---|
| A. Upload, extraction in flight | `null` | `null` | none | yes — `ExtractionService:55` re-reads | no | n/a (column lies "no") |
| B. Extraction failed | `null` | set | none | yes — `retry()` re-reads | **ambiguous** (no proposals → vacuously "all decided"? PRD silent) | no |
| C. Abandoned after extraction | `null` | `null` | N × PENDING | no | no | no |
| D. Full review submitted | set | `null` | all ACCEPTED/REJECTED | no | **yes** | yes (single happy case) |
| E. Partial review residue | set | `null` | mixed PENDING + decided | yes (parent will revisit) | **no** | **yes — column lies "purgeable"** |

**State E is reachable in principle.** The browser UX renders only PENDING rows (`templates/events/review.html:25-26`, `th:if="${decision.status == 'PENDING'}"`) with mandatory ACCEPT/REJECT radios (no "decide later" option), so a happy-path POST does cover all. But `EventReviewForm.decisions` has no `@Size(min=…)` / `@NotEmpty` (`EventReviewForm.java:21-29`), and `EventReviewController.submitDecisions` (`:134-159`) + `validateAcceptRows` (`:180-200`) never load the PENDING set from the DB to verify every PENDING ID is represented in the form. `EventReviewService.applyDecisions` (`:51-72`) iterates only the *submitted* list — missing PENDING rows survive untouched. A hand-crafted POST omitting some PENDING IDs reaches State E; the `resolvedAt` guard then locks the wrong timestamp.

**The cleaner oracle is the one S-05 archived but did not implement**: `COUNT(proposed_event WHERE source_image_id=image.id AND status='PENDING') = 0` (`context/archive/2026-06-16-image-extraction-and-review-acceptance/research.md:202`). It correctly excludes State C (PENDING > 0) and State E (PENDING > 0) and admits State D (PENDING = 0). It does NOT decide State B (extraction-failed, zero proposals) — that's a separate policy call: "vacuously satisfied" → purge immediately on first failure, or "needs explicit operator decision" → leave the audit trail and require a separate orphan-TTL rule.

States A and B both have `image.data` re-read paths, so they MUST be excluded from any purge predicate regardless of how the COUNT(PENDING) question lands.

### What S-05 deliberately laid down for S-06

The S-05 archive (`context/archive/2026-06-16-image-extraction-and-review-acceptance/`) is unusually explicit. Key anchors:

- **Anchor choice rationale** (`plan-brief.md:23`, `plan.md:75-78`): "*explicit `bytea`, not `@Lob`; `@Lob byte[]` on Hibernate 6 + PG lands on `oid`/Large Object storage which requires `lo_unlink` on delete and breaks the S-06 autopurge contract*." S-06's mechanism choice (DELETE vs. NULL) inherits this decision — `bytea` makes both cheap; `@Lob` would have made DELETE expensive.
- **`resolvedAt` stamped in the same transaction as promotions** (`plan-brief.md:31`, `plan.md:60`): the seam between "decisions committed" and "image marked resolved" was eliminated by design — there is no two-step commit where one half could fail.
- **Replay guard** (`plan.md:366`, `EventReviewService.java:74-80`): `if (image.getResolvedAt() == null) image.setResolvedAt(now)` was added because plan-review F4 (`reviews/plan-review.md:69-77`) flagged that a re-POST would reset the purge clock and silently extend retention. This is regression-locked by `EventReviewServiceTest.idempotentSecondSubmitDoesNotDuplicateEventsAndPreservesResolvedAt` (`EventReviewServiceTest.java:88-119`, with the explicit comment "*resolvedAt is anchored on first decision so S-06's purge clock is stable*" at `:117`).
- **Documented out-of-scope in S-05** (`plan.md:40`): "*No source-image purge on accept/reject — S-06 owns that contract; this slice persists `source_image.resolved_at` so S-06 has a key to scan on.*" Repeated in `plan-brief.md:57` and `change.md:13` (post-ship).
- **Open orphan question for S-06** (`reviews/impl-review.md:69-76`, F4): "*Revisit if S-06 (or any future flow) can delete `SourceImage` rows while extraction is in flight — at that point either surface a synthetic `errorKind=IMAGE_GONE` via the status response or document the 404 contract on the GET handler.*" S-06's purge IS this future flow; the plan must decide the 404-or-IMAGE_GONE question.
- **Heap-floor warning** (`change.md:13`): one 12 MB JPEG extraction holds ~350 MB; two concurrent ~700 MB — not survivable on the 1 GB Fly Machine. Cadence of any purge (and the population it sweeps) matters: an aggressive sweep that runs every 5 minutes vs. once a day affects how many unresolved images accumulate during retry storms.

S-03 (`edit-delete-accepted-events`) deliberately did not touch the image lifecycle — `app_event` rows have no FK to `source_image`, so edit/delete on the personal view affects no `SourceImage` state.

### What "removed from operator-accessible storage" can concretely mean

The literal NFR wording (PRD `:144`) admits two implementations that both satisfy it; both are cheap given `bytea` storage:

| Option | Mechanics | Notes |
|---|---|---|
| **(a) DELETE the SourceImage row** | `proposedEventRepository.deleteBySourceImage(image)` then `sourceImageRepository.delete(image)`; or one JPQL `DELETE FROM SourceImage WHERE …` with an explicit child cleanup. **No FK cascade exists**, so children must go first or schema must add `ON DELETE CASCADE`. | Archive's recommendation. Loses `correlationId`/`createdAt`/`resolvedAt` audit. Aligns with the canonical query at `archive/.../research.md:202`. |
| **(b) NULL the `data` column, keep the row** | `image.setData(null)` inside a `@Transactional` service; preserves every other column. | Requires migration: `SourceImage.data` is `nullable = false` (`SourceImage.java:34`). Preserves the operational audit trail (when did extraction fail, what correlation ID, when was the image resolved). One-line entity change + one schema-evolution check. |

Both satisfy "removed from operator-accessible storage". (a) is "row no longer exists"; (b) is "row exists, blob does not". The PRD is silent; the operator-audit value tilt is toward (b), and the storage-cost-floor tilt is toward (a). Pick deliberately in `/10x-plan`.

### Trigger options

| Trigger | Pros | Cons |
|---|---|---|
| **Synchronous, inside `applyDecisions`** | Zero scheduler concerns; happens before the parent's redirect lands; tests against `applyDecisions` already exist. | Couples the purge contract to the review-submit path — misses States B and C entirely. Adds work inside a `@Transactional` that the lesson at `lessons.md:80-85` warns about (conditional writes need conditional saves; here the conditional is "all proposals decided"). |
| **Scheduled sweep** | Single contract, owns the predicate, picks up B + C + D + E uniformly. Matches `archive/.../research.md:202`'s canonical query shape. | First-of-its-kind in this codebase (`ExtractionJobRegistry.sweep` is the only precedent, and is untested). Adds the "drive `@Scheduled` deterministically" test pattern as new work. |
| **Hybrid (inline best-effort + sweep safety net)** | Fast response on happy path; sweep catches everything else. | Two code paths to keep aligned; two tests' worth of regression surface. Premature unless the latency of "purge happens at next sweep cycle" turns out to be unacceptable — PRD does not specify a max purge latency. |

The cleanest single-contract pick is the **scheduled sweep with `COUNT(PENDING)=0`** anchor — it handles every state by construction, and the cadence is a tuning parameter not a correctness parameter.

### Testing surface

`context/foundation/test-plan.md` exists. §3 lists four phases (LLM regression harness, iCal feed serialization, iCal feed access control, upload pipeline + lifecycle boundary) — none cover image retention (`test-plan.md:74-77`). S-06 must either add a Phase 5 or document the gap.

`§6` cookbook has no entry for blob-purge assertions or `@Scheduled` testing — the parenthetical at `test-plan.md:267` ("*the TTL sweep can be exercised via a `@SpringBootTest` that drives the scheduled method directly*") endorses a shape that was never written. S-06 establishes §6.9.

**Clock injection is universal in production** (`AppApplication.java:26-29` binds `Clock.system(properties.timezone())`; consumers at `EventReviewService.java:25,30`, `ExtractionJobRegistry.java:42`, `CalendarController.java:27`, `EventController.java:28`, `AppController.java:20`, `OpenRouterLlmVisionClient.java:57`, `ExtractionStatusController.java:44`). Only `OpenRouterLlmVisionClientPromptTest.java:32-36` overrides via hand-construction + `Clock.fixed(...)`; there is **no `@MockitoBean Clock`** anywhere. The purge service must take `Clock` constructor-injected; the deterministic-time pattern for `@SpringBootTest` is unprecedented and worth a one-sentence plan note.

**Cost × signal for likely S-06 tests** (matches CLAUDE.md two-layer guidance):

| Assertion | Layer | Why |
|---|---|---|
| Eligibility predicate (`status='PENDING'` count → 0 AND `data` not yet null) selects the right rows | **Integration** (`@SpringBootTest` + H2) | The predicate is SQL/JPQL-shaped — index usage, nullable `bytea` handling, FK joins; a stub repo lies about exactly those. |
| Purge is idempotent (second invocation no-ops) | **Integration** | Same shape as `EventReviewServiceTest.idempotentSecondSubmit…`. Cheap. |
| Partial-review (State E reachable via hand-crafted POST) does NOT purge | **Integration** | The negative half of the predicate. Test must derive expected from the NFR oracle (`COUNT(PENDING)=0`), not from the existing `resolvedAt`-only branch — otherwise it mirror-tests the bug. |
| Full ACCEPT submit → blob removed (whichever mechanism) | **Integration** | Direct PRD line 144 contract test; copy `appliesMixedAcceptRejectAndPromotesAcceptedOnly` shape. |
| Failed-extraction image (State B) — policy TBD: leave alone, or orphan-TTL, or purge-with-blob | **Integration once policy is set** | Currently unspecified by PRD; plan must pick before this test has an oracle. |
| Cross-account "safety" of the sweep | **Skip or hermetic** | A global sweep iterates by predicate, not by user — cross-account framing only applies if the design intentionally per-user scopes the sweep. If it does not (likely), this test reduces to redundant scaffolding. |
| Inline-purge-shares-`applyDecisions`-transaction | **Premature** | The test has no home until inline vs. sweep is decided. |

**Mutation testing**: not configured in `build.gradle`. The CLAUDE.md note about selective Stryker/PIT is aspirational. S-06 is not the slice to introduce it — its likely predicate is two-clause and well-covered by the hand-written tests above. Flag if a third clause lands (e.g. orphan-TTL fallback).

## Code References

Production code (lifecycle, anchor, candidate sites):
- `src/main/java/com/example/app/event/SourceImage.java:19-50` — entity; `resolvedAt` field, `(user_id, resolved_at)` index, `data` nullable=false (touchpoint for option (b))
- `src/main/java/com/example/app/event/SourceImageRepository.java:9-12` — `findByIdAndUser` + JpaRepository defaults
- `src/main/java/com/example/app/event/ProposedEvent.java:39-41` — `@ManyToOne(optional=false)` to SourceImage, no cascade; `ix_proposed_event_source_image` at `:24-26`
- `src/main/java/com/example/app/event/ProposedEventRepository.java:10-21` — `findBySourceImageOrderByEventDateAscEventTimeAscNullsLast`
- `src/main/java/com/example/app/event/Event.java` — NO source-image link (confirmed)
- `src/main/java/com/example/app/event/ImageUploadController.java:52-79` — upload entry, image creation
- `src/main/java/com/example/app/event/ExtractionService.java:43-81` — async extraction; only post-upload reader of `image.data` (`:55`); failure branches set `lastErrorKind`/`correlationId`
- `src/main/java/com/example/app/event/EventReviewService.java:37-83` — `@Transactional applyDecisions`; **the anchor write at `:78-80`** with the replay guard and the explicit "no `save(image)`" comment
- `src/main/java/com/example/app/event/EventReviewController.java:64-72` — JavaDoc with explicit "revisit on S-06" hook for the 404/IMAGE_GONE question
- `src/main/java/com/example/app/event/EventReviewForm.java:21-29` — no `@Size(min=…)`/`@NotEmpty` on `decisions` — server-side gap that lets State E exist
- `src/main/resources/templates/events/review.html:25-29` — PENDING-only rendering with mandatory ACCEPT/REJECT
- `src/main/java/com/example/app/event/ExtractionJobRegistry.java:81-85` — the one existing `@Scheduled` precedent (untested)
- `src/main/java/com/example/app/AppApplication.java:17-29` — `@EnableScheduling` + `@EnableAsync` + `Clock` bean

Tests (regression locks already in place that S-06 must not break):
- `src/test/java/com/example/app/event/EventReviewServiceTest.java:88-119` — `idempotentSecondSubmitDoesNotDuplicateEventsAndPreservesResolvedAt` with the load-bearing comment about S-06 at `:117`
- `src/test/java/com/example/app/event/EventReviewServiceTest.java:48-85` — the shape for any new state-of-`SourceImage` assertion test
- `src/test/java/com/example/app/event/ExtractionServiceTest.java:116-177` — every failure-branch test (TIMEOUT, PROVIDER_ERROR, MALFORMED_RESPONSE, UNEXPECTED) pinning State-B fields
- `src/test/java/com/example/app/event/EventReviewControllerTest.java:229-241` — running-template branch, the "no proposals, no error, no resolvedAt" State-A check
- `src/test/java/com/example/app/llm/OpenRouterLlmVisionClientPromptTest.java:32-36` — only `Clock.fixed` precedent (hand-construction, not `@MockitoBean`)
- `src/test/java/com/example/app/AppApplicationTests.java:62,73` — pins `Clock` bean zone to `Europe/Warsaw`; regression guard

## Architecture Insights

- **bytea-not-`@Lob` was an S-06-driven decision.** The team picked the wire format with the cheapest purge in mind, exactly because S-06's contract is "row+blob gone in one transaction". S-06 should not silently re-litigate this — the cheapest mechanism (DELETE row or NULL blob) is meant to be a single transactional write.
- **Promote-then-stamp is the seam-elimination pattern.** S-05 chose to stamp `resolvedAt` in the same `@Transactional` commit as the proposal flips, so there is no half-committed state ("events accepted but image still owns proposals"). S-06 must respect this — if the purge is inline, it must run inside the same transaction; if it's scheduled, the predicate must tolerate the (vanishingly small) window where a commit happened but the sweep hasn't yet run.
- **Lesson from S-05 impl-review (`lessons.md:80-85`): inside `@Transactional`, don't `save()` a managed entity; conditional state changes need conditional saves.** Born in `applyDecisions` exactly. If S-06 adds an inline-purge inside `applyDecisions`, it must guard *both* the state mutation and any (rarely needed) explicit save call symmetrically — and prefer dirty-checking + JPQL `UPDATE`/`DELETE` over imperative `save`.
- **The "stable UID across edits" contract on `Event`** (S-03) is decoupled from purge — `Event` has no `source_image_id`, so neither row deletion nor blob nulling can break feed UIDs. This is a deliberate decoupling the team can rely on; S-06 does not need to reason about feed serialization.
- **No image-download endpoint = blob is purely raw material.** The architecture treats the blob as a one-time extraction input. This makes (b) NULL-the-blob a defensible posture (the rest of the row's metadata is still useful for operator forensics).
- **The `Clock` bean is the time abstraction.** Every time-sensitive site goes through it. The purge service inherits this convention; the deterministic-time test pattern needs to be set up once (in `@TestConfiguration` or `@MockitoBean`) and then reused.

## Historical Context (from prior changes)

- `context/archive/2026-06-16-image-extraction-and-review-acceptance/research.md:166-174` — the bytea-vs-mounts-vs-S3 trade table; bytea ranked best for S-06 purge.
- `context/archive/2026-06-16-image-extraction-and-review-acceptance/research.md:202` — the canonical S-06 query: `DELETE FROM source_image WHERE id NOT IN (SELECT DISTINCT source_image_id FROM proposed_event WHERE status='PENDING')`. Note the orphan-class behaviour: an image with zero proposals (State B) is *vacuously* purgeable under this query — confirm with PRD.
- `context/archive/2026-06-16-image-extraction-and-review-acceptance/plan.md:40` — "*No source-image purge on accept/reject — S-06 owns that contract; this slice persists `source_image.resolved_at` so S-06 has a key to scan on.*"
- `context/archive/2026-06-16-image-extraction-and-review-acceptance/plan.md:78` — bytea-not-`@Lob` rationale referencing the S-06 purge contract by name.
- `context/archive/2026-06-16-image-extraction-and-review-acceptance/plan.md:366` — the replay guard implementation, with reference to S-06's purge-clock anchor.
- `context/archive/2026-06-16-image-extraction-and-review-acceptance/reviews/plan-review.md:69-77` — F4: "replay resets the purge clock" → resolved by null-guard.
- `context/archive/2026-06-16-image-extraction-and-review-acceptance/reviews/impl-review.md:69-76` — F4 (open for S-06): the extraction-in-flight + delete race; the IMAGE_GONE-or-document-404 decision is explicitly punted here.
- `context/archive/2026-06-16-image-extraction-and-review-acceptance/change.md:13` — heap-floor concern (12 MB JPEG → ~350 MB extraction heap; not survivable at 2× concurrency on 1 GB Fly Machine). Cadence-relevant.
- `context/archive/2026-06-21-edit-delete-accepted-events/plan.md:46` — confirms `Event` rows have no link to `source_image`; S-06 does not need to reason about edit/delete feedback.
- `context/foundation/lessons.md:80-85` — "*Inside `@Transactional` on a managed JPA entity, the explicit `save()` is redundant — conditional state changes deserve a conditional save.*" Born from S-05 impl-review F3, applies to any new `@Transactional` purge service.
- `context/foundation/lessons.md:47-55` — per-controller `@SpringBootTest` test layout; if S-06 introduces a controller surface (unlikely), follow the convention.
- `context/foundation/lessons.md:88-92` — validation symmetry across create/edit (S-03). Not directly applicable to S-06 (no DTO sharing).

## Related Research

- `context/archive/2026-06-16-image-extraction-and-review-acceptance/research.md` — the S-05 storage-location trade study; the canonical purge query is recorded here.
- `context/archive/2026-06-15-icalendar-feed-and-subscription/` — iCal feed shape; not affected by S-06 (Event-side only).

## Open Questions

Three for `/10x-plan` to resolve before code is written:

1. **Anchor.** `resolvedAt != null` (S-05's stamp, simpler scan, MISSES States B/C, LIES for State E) vs. `COUNT(proposed_event WHERE status='PENDING' AND source_image_id=image.id) = 0` (S-05 archive's canonical query, handles States C/D/E uniformly, makes State B vacuously eligible) vs. hybrid `(resolvedAt != null OR (lastErrorKind != null AND created_at < now - X))` for explicit orphan handling. Recommendation: **COUNT(PENDING)=0** as the core predicate; if State-B images should not auto-purge, add an explicit `AND lastErrorKind IS NULL` clause and surface the orphan policy as a separate decision.
2. **Trigger.** Synchronous-inline (only handles State D) vs. scheduled sweep (handles all states with a single predicate) vs. hybrid. Recommendation: **scheduled sweep**; the cost of establishing the `@Scheduled` test pattern (estimated one §6.9 cookbook entry + one test) is lower than the cost of two parallel code paths.
3. **Mechanism.** DELETE row (archive's pick; loses correlationId/createdAt/resolvedAt audit; requires deleting ProposedEvent children first or `ON DELETE CASCADE`) vs. NULL blob (preserves audit; requires `data` `nullable=false → true` migration). Recommendation: **NULL blob** if operator audit is valuable (correlationId is already used for incident triage in `ExtractionService`); DELETE row otherwise.

One PRD clarification:

4. **State B (extraction-failed, no proposals): purge or hold?** PRD line 144 says "every proposed event from a given image has been accepted or rejected" — vacuously satisfied with zero proposals, so a literal reading purges. But a parent may retry via `EventReviewController.retry` (which re-reads `image.data`), and purging the blob breaks retry. The least-surprising posture is **hold State-B images until either (i) a successful retry resolves them through State C/D/E, or (ii) an explicit orphan TTL elapses**. Surface in the plan as a deliberate scope decision (in scope: orphan-TTL with a sane default; out of scope: leave them forever).

One race condition explicit hand-off from S-05 impl-review F4:

5. **Extraction-in-flight + purge race.** If a sweep deletes a SourceImage that the polling browser later requests via `EventReviewController.review`, the response is a generic 404 today. S-05's impl-review explicitly named this the moment S-06 should decide: `errorKind=IMAGE_GONE` synthetic response, or just document the 404 as the contract. Cheap to do either; document the choice in the plan.
