# Image extraction + review/acceptance (S-05) — Plan Brief

> Full plan: `context/changes/image-extraction-and-review-acceptance/plan.md`
> Research: `context/changes/image-extraction-and-review-acceptance/research.md`

## What & Why

S-05 is the **north-star slice**: parent uploads a photo or screenshot of a kindergarten announcement; AI-proposed events are reviewed, accepted, and appear in the parent's subscribed calendar via the existing iCalendar feed. It is the validation milestone — the smallest end-to-end slice whose successful delivery would prove the core product hypothesis (an AI-mediated bridge from unstructured input to the parent's trusted calendar). Everything in the roadmap is sequenced around proving this one works.

## Starting Point

F-01 (OpenRouter `LlmVisionClient`) and S-02 (`Event` entity + manual entry) and S-04 (iCalendar feed) are already shipped. `LlmVisionClient.extract(byte[], MimeType)` is fully tested and currently has **no production caller** — S-05 wires it up for the first time. `LlmExtractionResult.ProposedEvent` and `EventForm` have byte-compatible shapes, so promoting an accepted proposal is a pure field copy.

## Desired End State

A logged-in parent picks a JPEG/PNG/WEBP under 15 MB, watches a two-stage progress UI (upload bar, then "extracting… 23 s"), lands on a review page with one editable row per proposed event, accepts or rejects each (with edits), and sees them in `/app` and — within one polling cycle — in their subscribed calendar. Errors surface as Polish copy + correlation ID for triage; empty results route to two-CTA recovery (manual entry, try another photo); the calendar-feed guardrail (PENDING proposals never appear in `/calendar/{token}.ics`) is structurally enforced and pinned by two layers of tests.

## Key Decisions Made

| Decision                                | Choice                                                                                                       | Why (1 sentence)                                                                                                                                                                                                                                  | Source            |
| --------------------------------------- | ------------------------------------------------------------------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------- |
| Progress UX shape                       | AJAX upload + JSON `{jobId, statusUrl}` + polling every 1.5 s                                                | Releases the servlet thread immediately and keeps the existing blocking `LlmVisionClient` intact; SSE/stream would force touching the F-01 client.                                                                                                 | Research          |
| Source image storage                    | `bytea` in Neon Postgres (new `source_image` row)                                                            | Single source of truth, S-06 purge becomes one transactional DELETE, survives every restart/deploy; PRD §Persistence excludes in-memory or Fly-ephemeral-disk.                                                                                     | Research          |
| Proposed-event persistence              | Separate `proposed_event` table; promote to `app_event` on accept                                            | Structurally locks the "feed reads `app_event` only" guardrail — a `status` column on `Event` would put every `findUpcomingByUser` query one missing predicate away from leaking a proposal into the calendar.                                     | Research          |
| Job-status registry                     | In-memory `ConcurrentHashMap` + 5-min TTL + 404 on miss                                                      | Mid-extraction restart strands the polling browser; the cost (rare on Fly, single-user MVP) is well below the cost of a third entity + state machine.                                                                                              | Plan              |
| Empty extraction result (`[]`)          | Success-with-empty review page + two CTAs (manual entry, try another photo)                                  | The model worked — surfacing as error confuses; user noted some `[]` cases are actually bad photos, so "try another photo" CTA sits next to "wpisz ręcznie".                                                                                       | Plan              |
| Retry UX on `TIMEOUT`/`PROVIDER_ERROR`  | Keep `source_image`, surface dedicated error page with correlation ID + Retry button; no auto-retry          | Parent doesn't lose the upload; respects the explicit "no retry — single shot" decision in `application.properties:34`; correlation ID gives a triage path via `fly logs`.                                                                         | Plan              |
| Upload limits / accepted MIME           | 15 MB max-file-size / 20 MB max-request-size; JPEG/PNG/WEBP                                                  | Covers phone photos including panoramas per research; HEIC excluded (F-01 has no HEIC fixtures, model behaviour unknown).                                                                                                                          | Plan              |
| Review UI shape                         | Single form, per-row accept/reject radio (default: accept), inline edit fields, one Submit                   | Mirrors S-02's stacked form; one transactional POST; default-accept matches the "AI proposes, human decides" wedge.                                                                                                                               | Plan              |
| Validation on rejected rows             | Manually validate only `action=ACCEPT` rows; `@Valid` is **not** applied to the decisions form               | A hallucinated `2026-13-45` proposal must be rejectable without blocking submit; Bean Validation has no conditional-skip; manual `Validator` walk is the only path.                                                                                | Plan (user-added) |
| Decisions route + transactional commit  | `POST /events/from-image/{imageId}/decisions` (not `/events`); one `@Transactional` method does N inserts + `resolved_at` stamp + flash | Lets the controller signal S-06 ("image resolved") in the same commit as the promotions; redirect to `/app` with "Dodano X wydarzeń."                                                                                                              | Plan (user-added) |
| Client-side pre-flight                  | JS checks size + MIME (with extension fallback for iOS empty-`type`); separate XHR upload-progress bar       | Server-side magic-bytes stay the source of truth; LTE upload of a 14 MB photo is ~10 s — without a progress bar the parent sees an opaque ~70 s wait.                                                                                              | Plan (user-added) |
| Provider-error display                  | Generic Polish copy + correlation ID; raw `providerMessage` only in `fly logs`                               | Avoids dumping inscrutable English at the parent; correlation ID + log grep is the solo-dev triage path; HTTP-status-keyed copy is a deferred enhancement noted in Phase 4b.                                                                       | Plan (user-added) |
| Async executor                          | Explicit `ThreadPoolTaskExecutor` bean, core=2 / max=2 / queue=10, named "extraction-"                       | Predictable resource use on 1 GB Fly Machine; named threads make logs readable; bound caps heap pressure from in-flight 15 MB image bytes.                                                                                                         | Plan              |
| Calendar-feed guardrail tests           | Both: `@DataJpaTest` (repo) + integration `MockMvc` (ical body)                                              | One layer guards the SQL property; the other locks it at the HTTP/serialization layer where a feed-emit path could regress without changing the query.                                                                                             | Plan              |

## Scope

**In scope:**
- Two new entities (`SourceImage`, `ProposedEvent`) + repositories + additive schema (via `ddl-auto=update`)
- Multipart configuration (15 MB / 20 MB) + Polish `@ControllerAdvice` error handler
- Upload form with JS pre-flight + XHR upload-progress + two-stage extraction-status polling
- `@EnableAsync` + named bounded `extractionExecutor` bean
- In-memory `ExtractionJobRegistry` with 5-min TTL + `@Scheduled` sweep
- `ExtractionService` (async; calls existing blocking `LlmVisionClient`; persists proposals or stamps error + correlation ID)
- Review page (three render branches: happy / error / empty) + decisions POST with conditional validation
- Retry POST that clears error state and re-kicks extraction on the same image
- Calendar-feed guardrail tests (repo + integration)
- Per-controller `@SpringBootTest` for each new controller
- §6 cookbook entry + change.md status flip

**Out of scope:**
- Any change to `OpenRouterLlmVisionClient` or its config (F-01's contract is locked)
- HEIC support (no harness fixtures)
- Bulk Accept/Reject all buttons (FR-007: per-event safety floor)
- Source-image purge on accept/reject (S-06)
- Edit/delete of accepted events (S-03)
- DB-backed extraction job (in-memory is acceptable at this scale)
- HTTP-status-keyed Polish error copy (deferred enhancement; noted in Phase 4b)
- Structured-logging framework or metrics export

## Architecture / Approach

```
Browser ──[multipart POST]──►  ImageUploadController
                                      │
                                      ▼ persist
                                 SourceImage (bytea)
                                      │
                                      ▼ register jobId + @Async
                                 ExtractionService ──►  LlmVisionClient (existing, blocking)
                                      │                                │
                                      ▼ persist N rows             success ▶ ProposedEvent (PENDING)
                                 ProposedEvent (PENDING)             failure ▶ source_image.lastErrorKind + correlationId
                                      │                                │
                              ExtractionJobRegistry  ◄────────────────┘
                              (in-memory, TTL 5 min)
                                      ▲
                                      │ poll every 1.5 s
                              ExtractionStatusController  ──[200/404]──►  Browser JS
                                      │
                            (on DONE/FAILED, JS navigates to reviewUrl)
                                      ▼
                              EventReviewController.GET ──►  events/review            (proposals present)
                                                         ──►  events/review-error     (lastErrorKind set)
                                                         ──►  events/review-empty     (zero proposals, no error)
                                      │
                              POST /events/from-image/{imageId}/decisions
                                      │
                                      ▼ @Transactional
                              EventReviewService.applyDecisions:
                                  for each accept row → eventRepository.save(Event)
                                  for each row        → proposedEvent.status = ACCEPTED|REJECTED
                                  sourceImage.resolvedAt = now
                                      │
                                      ▼
                              GET /app (personal view + flash)   ◄── existing
                              GET /calendar/{token}.ics          ◄── existing (reads app_event ONLY — guardrail)
```

## Phases at a Glance

| Phase                                                                       | What it delivers                                                                                                                              | Key risk                                                                                                                          |
| --------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------- |
| 1. Persistence + guardrail                                                  | `SourceImage` + `ProposedEvent` entities + repos; `@DataJpaTest` and `CalendarControllerTest` lock the "PENDING never leaks" property         | The integration test must use the real ical4j writer; an over-mocked test would silently miss a feed-emit regression              |
| 2. Multipart + upload form + sync POST                                      | Upload contract end-to-end except async/LLM: multipart 15/20 MB, JS pre-flight, XHR progress, `@ControllerAdvice`, stub review page           | `MaxUploadSizeExceededException` fires BEFORE the controller — must handle via `@ControllerAdvice`, not `BindingResult`           |
| 3. Async pipeline + status polling                                          | `@Async` `ExtractionService` + `ExtractionJobRegistry` + status endpoint + two-stage progress JS                                              | `@Async` proxy fails on self-calls — Service must be a separate bean from the controller; also a leaked `jobId` must 404, not 403 |
| 4a. Review happy path                                                       | `events/review` + `POST decisions` + transactional promotion + flash                                                                          | Conditional validation: `@Valid` can't skip rejected rows; need manual `Validator.validate` only on accept rows                   |
| 4b. Error / empty / retry states                                            | `events/review-error` (Polish + correlationId), `events/review-empty` (two CTAs), `POST retry` re-kicks extraction                            | Branch ordering matters: error before empty, otherwise a failed-extraction-with-no-proposals shows the empty page                 |
| 5. E2E manual + harness re-run + cookbook                                   | Walk 2–3 real announcements; re-run regression harness; backfill §6 cookbook; flip `change.md` to `done`                                      | The accuracy harness re-run is the safety check — wiring changes that drift the LLM call shape are hard to spot otherwise         |

**Prerequisites:** F-01 (done), S-02 (done), S-04 (done); local Postgres reachable via `SPRING_DATASOURCE_*`; OpenRouter key in `OPENROUTER_API_KEY`.
**Estimated effort:** 3–4 evening sessions across 6 phases (Phase 1 + 2: 1 session each; Phase 3: 1 session; Phase 4a + 4b: 1 session each; Phase 5: half-session walkthrough + cookbook).

## Open Risks & Assumptions

- **In-memory job registry**: a mid-extraction Machine restart strands the polling browser. Mitigation: 5-min TTL + 404 → "extraction expired, retry." Acceptable at single-user MVP scale; revisit if it bites the second parent.
- **Timeout headroom**: `OpenRouterLlmVisionClient` is at 55 s with `max-retries=0`; PRD ceiling is 60 s. Slow model days will produce `TIMEOUT` more often than the harness suggests. The review-error page with Retry is the recovery surface; no auto-retry by design.
- **HEIC iPhone uploads** will be rejected by MIME whitelist. iPhone users with default "Most Compatible" off must change settings or screenshot; this is a known UX cost.
- **Empty extraction result (`[]`)** is a fourth state the F-01 exception taxonomy doesn't capture. Treated as success-with-empty here; if the model returns `[]` for readable announcements often, the empty-page CTAs ("try another photo") are the lever.
- **Deferred enhancement**: HTTP-status-keyed Polish copy on `PROVIDER_ERROR` (today every PROVIDER_ERROR collapses to one generic copy + correlation ID).

## Success Criteria (Summary)

- A real photo of a Polish kindergarten announcement → review page within 60 s → accept → event shows in `/app` immediately and in subscribed Apple Calendar within ~10 min, with the morning-of-day-before reminder
- An unreadable / model-erroring image → Polish error page with correlation ID; clicking Retry re-runs extraction without re-uploading
- A `PENDING` `ProposedEvent` is provably absent from `/calendar/{token}.ics` (locked by two layers of tests)
