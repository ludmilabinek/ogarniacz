# Image extraction + review/acceptance (S-05) Implementation Plan

## Overview

Wire the north-star slice (S-05) end-to-end: parent uploads a photo or screenshot of a kindergarten announcement; the existing `OpenRouterLlmVisionClient` extracts proposed events asynchronously; parent sees a review page with per-row accept/reject + inline edit; accepted proposals are promoted into the existing `app_event` table (which S-04's iCalendar feed already reads). The plan lands a separate `proposed_event` table so the load-bearing "AI proposes, human decides" guardrail — accepted events only flow to the calendar via `app_event` — is enforced by structure, not vigilance.

## Current State Analysis

`LlmVisionClient.extract(byte[], MimeType) → LlmExtractionResult` exists, is tested against a 10-fixture recorded harness, and has **no production caller today**. The blocking implementation `OpenRouterLlmVisionClient` is wired against OpenRouter via Spring AI 2.0.0-M6 with a 55 s read timeout and `max-retries=0` (`application.properties:36-37`). `LlmExtractionResult.ProposedEvent(date, time, title, requirements, notes)` matches `EventForm`'s validated shape exactly — promotion is a pure field copy, no adaptation.

The downstream pipeline that S-05 plugs into is finished and shipped: `Event` entity → `EventRepository.findUpcomingByUser` → `AppController` (personal view) → `CalendarController + IcalFeedWriter` (anonymous token-gated `.ics`). The `findUpcomingByUser` query already excludes other users' events (`EventRepositoryTest` proves it); the same query is the only path that emits to the iCalendar feed, so a `proposed_event` row that never reaches `app_event` cannot leak.

What is **not** in place: multipart configuration (Boot's 1 MB/10 MB defaults are too tight for phone photos), no `@EnableAsync`, no upload form or controller, no review page, no source-image storage, no proposed-event entity. CSRF is on by default; security permits anonymous `GET /calendar/*.ics` and routes everything else through `.anyRequest().authenticated()` — any new `/events/from-image/*` route inherits authenticated access with no config change.

## Desired End State

A logged-in parent, on `/events/from-image`, picks a JPEG/PNG/WEBP under 15 MB. Browser shows a two-stage progress UI (upload bar via XHR `progress`, then "extracting… 23 s") while the AJAX upload returns `{jobId, statusUrl}`; the page polls `statusUrl` until `DONE` and navigates to `/events/from-image/{imageId}/review`. The review page renders one row per `ProposedEvent` with editable fields (date, time, title, requirements, notes) and an accept/reject radio (default: accept). On Submit, accepted rows are validated and persisted as `Event` rows; rejected rows skip validation entirely; `source_image.resolved_at` is stamped; redirect to `/app` with a Polish flash ("Dodano X wydarzeń"). Within one polling cycle the parent's subscribed calendar shows the same events.

Verifiable from outside the code: (a) `GET /calendar/{token}.ics` for a user whose only proposals are `PENDING` returns an `.ics` with zero `VEVENT` blocks — the calendar-feed guardrail. (b) `LlmExtractionRecordedRegressionTest` still passes (the regression harness's per-field accuracy did not regress). (c) A real kindergarten announcement walked end-to-end produces at least one accepted event that appears in `/app` and in the `.ics` body.

### Key Discoveries:

- `LlmExtractionResult.ProposedEvent` fields are byte-compatible with `EventForm` — promotion is `new Event(user, p.date(), p.time(), p.title(), p.requirements(), p.notes())` (`src/main/java/com/example/app/llm/LlmExtractionResult.java:7`, `src/main/java/com/example/app/event/Event.java:57`).
- `OpenRouterLlmVisionClient` is fully blocking (`chatClient.prompt()...call().content()` in `src/main/java/com/example/app/llm/OpenRouterLlmVisionClient.java:77-93`) — Spring AI's `stream()` does not compose with the existing JSON-array regex-parse path. Run blocking under `@Async`, do not touch the client.
- No `spring.servlet.multipart.*` keys exist in `application.properties` today — Boot's 1 MB/10 MB default rejects phone photos before the controller runs. `MaxUploadSizeExceededException` fires from the multipart filter, **before** `@Valid`, so `BindingResult` is empty — handle via `@ControllerAdvice`.
- `LlmExtractionException` has 3 `Kind`s (`TIMEOUT`, `PROVIDER_ERROR`, `MALFORMED_RESPONSE`); `PROVIDER_ERROR` carries `httpStatus` + `providerMessage` strings. Empty result (`[]`) is a fourth state the taxonomy doesn't capture — surface as success-with-empty review page, not as a synthetic error.
- Per-controller `@SpringBootTest` test layout is the standard (`context/foundation/lessons.md` §"Per-controller `@SpringBootTest` class is the test layout standard"). `AppApplicationTests` stays a smoke test.
- `application.properties:34-37` documents the explicit "no retry — single shot" decision; do NOT flip `max-retries` to 1 to mask `TIMEOUT`.
- Thymeleaf `th:text` auto-escapes — XSS-safe for displaying error correlation IDs / messages without further sanitization.
- The lesson "Grade only what a user would call wrong" (lessons.md) implies: when manually walking E2E for verification, judge "did this work" against date/time/requirements, not title/notes phrasing.

## What We're NOT Doing

- **Not touching `OpenRouterLlmVisionClient`** — its retry/timeout/parse logic and exception taxonomy are F-01's contract, locked by the regression harness.
- **No clipboard-paste, no PDF, no streaming UI** — file picker only (PRD non-goals).
- **No HEIC support** — `accept="image/jpeg,image/png,image/webp"`; F-01's harness has no HEIC fixtures, model behaviour unknown.
- **No bulk "Accept all" / "Reject all" buttons** — per-row decision is the safety floor (FR-007 Socratic-resolution).
- **No edit/delete of accepted events from the review page** — that is S-03's surface (`edit-delete-accepted-events`).
- **No re-decision on already-decided rows from the review page** — once a `ProposedEvent` flips out of `PENDING`, the review form skips it silently on any second pass through the POST (browser back, duplicate submit, manual replay). The decision is by-design: the review page is a one-shot "promote-or-discard" UX, not a back-and-forth editor. The template (Phase 4a §4) reinforces this by hiding non-PENDING rows on re-render so the user cannot even attempt the no-op. To change an accepted event, go to `/app` (S-03). To re-evaluate a rejected proposal, upload the image again (acceptable at single-user MVP — the cost is one extra extraction).
- **No source-image purge on accept/reject** — S-06 owns that contract; this slice persists `source_image.resolved_at` so S-06 has a key to scan on.
- **No DB-backed extraction job** — in-memory `ConcurrentHashMap<UUID, JobStatus>` with 5-min TTL; restart mid-extraction strands the polling browser (acceptable single-user MVP risk).
- **No HTTP-status switch / per-error-kind Polish copy mapping** — single generic copy + correlation ID. Recorded as a deferred enhancement in Phase 4b.
- **No structured-logging framework, no metrics export** — `slf4j` log lines keyed by correlationId are enough at single-user MVP scale.
- **No token rotation / iCalendar URL revocation** — out of MVP (PRD §Access Control).

## Implementation Approach

The slice decomposes into six phases ordered so each lands as a working, testable increment:

1. **Persistence + guardrail** lands first because every subsequent phase persists to or reads from `source_image`/`proposed_event`. The calendar-feed guardrail integration test at this phase pins the property "PENDING proposal never appears in `/calendar/{token}.ics`" while the proposed-event surface is still tiny — easier to assert, easier to refactor against later.
2. **Multipart + upload form + sync POST** wires the user-visible upload (file picker, JS pre-flight, XHR upload-progress) without any async yet. The POST persists the image synchronously and redirects to a stub review page that just shows "image saved, extraction not wired yet". This phase ships the file-handling contract — size limits, MIME validation, Polish error copy, `@ControllerAdvice` — independent of LLM concerns.
3. **Async pipeline + status polling** replaces the sync POST with `{jobId, statusUrl}` + `@Async ExtractionService.run(imageId)` + in-memory `ExtractionJobRegistry`. Calls the existing blocking `LlmVisionClient.extract` from an `extractionExecutor` (core=2, max=2, queue=10) and persists N `proposed_event` rows on success. JS polls `GET /events/from-image/status/{jobId}` every 1.5 s.
4. **(a) Review happy path + transactional promotion**: `GET /events/from-image/{imageId}/review` renders proposals; `POST /events/from-image/{imageId}/decisions` walks the rows, validates only `action=ACCEPT` rows, promotes them into `app_event`, marks each `proposed_event.status` (`ACCEPTED`/`REJECTED`), stamps `source_image.resolved_at`, redirects to `/app`. All in one `@Transactional` method. **(b) Error/empty/retry** adds the three other render branches: `lastErrorKind` set → error page with correlation ID + Retry button; zero proposals + no error → empty-state page with two CTAs (manual entry, try another photo); plus `POST /events/from-image/{imageId}/retry` to re-kick extraction on the same `source_image`.
5. **E2E verification + harness re-run + cookbook**: walk 2–3 real announcements through, confirm calendar landing, re-run `LlmExtractionRecordedRegressionTest` (the per-field accuracy must not have regressed by this slice's wiring), and write the §6 cookbook entry in `test-plan.md`.

## Critical Implementation Details

**Timing & lifecycle.** `MaxUploadSizeExceededException` is thrown by the multipart resolver **before** the controller method runs, so `BindingResult` cannot carry it — it must be caught by a `@ControllerAdvice` that re-renders the upload form with a Polish error message via `RedirectAttributes.addFlashAttribute` (Thymeleaf still injects the CSRF token through `enctype="multipart/form-data"`, no special handling needed there). The `@Async` method **must** be on a different bean than its caller — Spring proxies don't intercept self-calls — so the controller calls a separate `@Service ExtractionService` whose `runAsync(...)` method is annotated.

**State sequencing.** The decisions POST has a single correctness ordering: persist the N `Event` rows → flip `proposed_event.status` for all rows → stamp `source_image.resolved_at` → commit. If `source_image.resolved_at` is stamped before the events are saved and the transaction is interrupted, an external `S-06` purge could lose the image while events are still being written. One `@Transactional` method on `EventReviewService` is the seam.

**Validation gate.** Bean Validation `@Valid` cannot conditionally skip rows. The decisions endpoint takes `@ModelAttribute EventReviewForm` **without** `@Valid` and validates manually per row inside the handler: iterate `form.decisions()`, skip any with `action == REJECT`, run a `Validator.validate(decision.fields(), groups…)` against the accept rows, and aggregate `ConstraintViolation`s into `BindingResult` before deciding to re-render. This is the only way to reject a hallucinated `2026-13-45` row.

## Phase 1: Persistence foundation + calendar-feed guardrail

### Overview

Land the two new entities, their repositories, the additive Flyway-free migration (handled by `ddl-auto=update` + deploy-plan additive-only policy), and the integration test that pins the calendar-feed guardrail before any upload surface exists.

### Changes Required:

#### 1. SourceImage entity

**File**: `src/main/java/com/example/app/event/SourceImage.java`

**Intent**: Persist the uploaded image bytes + MIME type for the duration of the parent's review pass, with enough metadata to support async-extraction status, retry, and S-06's future purge contract.

**Contract**: JPA `@Entity` `@Table(name="source_image")`. Fields: `UUID id` (`@GeneratedValue`); `@ManyToOne(LAZY, optional=false) AppUser user` (`@JoinColumn user_id`, indexed via `(user_id, resolved_at)`); `byte[] data` (`@Column(columnDefinition = "bytea")`, non-null — explicit `bytea`, not `@Lob`; `@Lob byte[]` on Hibernate 6 + PG lands on `oid`/Large Object storage which requires `lo_unlink` on delete and breaks the S-06 autopurge contract assumed in Performance Considerations §`bytea` column); `String mimeType` (≤32, non-null); `Instant createdAt` (set in `@PrePersist`); `Instant resolvedAt` (nullable — non-null once every proposal is decided); `String lastErrorKind` (nullable — name of `LlmExtractionException.Kind` when extraction failed); `String correlationId` (≤64, nullable — set on failure for the parent-visible "show this to dev" code). `@ManyToOne` follows the `Event.user` pattern in `src/main/java/com/example/app/event/Event.java:32-34`.

#### 2. ProposedEvent entity

**File**: `src/main/java/com/example/app/event/ProposedEvent.java`

**Intent**: Persist one row per extracted event, structurally identical to `EventForm`'s validated shape, linked back to the source image so S-06 can scan unresolved sets and so the review page can render them by image.

**Contract**: JPA `@Entity` `@Table(name="proposed_event")`. Fields: `UUID id`; `@ManyToOne(LAZY, optional=false) SourceImage sourceImage` (`@JoinColumn source_image_id`, indexed); `LocalDate eventDate` (non-null); `LocalTime eventTime` (nullable); `String title` (non-null, ≤200); `String requirements` (nullable, ≤2000); `String notes` (nullable, ≤2000); `@Enumerated(EnumType.STRING) ProposedEventStatus status` (non-null, default `PENDING` set via field initializer); `Instant createdAt` (`@PrePersist`); `Instant decidedAt` (nullable). Inner `enum ProposedEventStatus { PENDING, ACCEPTED, REJECTED }`. Construction mirrors `Event(user, date, time, title, requirements, notes)` — same ctor signature shape, swap `AppUser user` for `SourceImage sourceImage`.

#### 3. Repositories

**File**: `src/main/java/com/example/app/event/SourceImageRepository.java`, `ProposedEventRepository.java`

**Intent**: Bare CRUD plus the two queries the review page and S-06 need.

**Contract**: `SourceImageRepository extends JpaRepository<SourceImage, UUID>`; one named method `Optional<SourceImage> findByIdAndUser(UUID id, AppUser user)` for the review-page lookup (cross-user partition enforced at the query, not relied on at the controller). `ProposedEventRepository extends JpaRepository<ProposedEvent, UUID>`; one named method `List<ProposedEvent> findBySourceImageOrderByEventDateAscEventTimeAscNullsLast(SourceImage sourceImage)`.

#### 4. EventRepositoryTest — guardrail negative case

**File**: `src/test/java/com/example/app/event/EventRepositoryTest.java`

**Intent**: Add a test asserting that a `PENDING` `ProposedEvent` for user A does not appear in `findUpcomingByUser(userA, today)` output, mirroring the existing `findUpcomingByUserExcludesOtherUsersEvents` cross-user test.

**Contract**: New `@Test void findUpcomingByUserExcludesPendingProposedEvents()`. Persists user A + `Event` (date today+1) + `SourceImage` + `ProposedEvent` (date today+2, PENDING) — both attached to user A. Asserts `findUpcomingByUser(userA, today)` returns exactly the one `Event`, never the proposal. The proposal is structurally unreachable through this query because it lives in a different table; the test pins the property against a future refactor that might collapse them.

#### 5. CalendarController integration test — end-to-end guardrail

**File**: `src/test/java/com/example/app/event/CalendarControllerTest.java`

**Intent**: Pin the property at the HTTP level: a `GET /calendar/{token}.ics` for a user whose only events are `PENDING` proposals returns an `.ics` with zero `VEVENT` blocks.

**Contract**: New `@Test void icsFeedExcludesPendingProposedEvents()`. Persist user + `IcalSubscription` token + 1 `SourceImage` + 2 `PENDING` `ProposedEvent` rows for the user; assert the response body has Content-Type starting `text/calendar`, contains the `VCALENDAR` envelope, and `body.split("BEGIN:VEVENT").length - 1 == 0`. Mirror the existing test file's MockMvc setup pattern.

### Success Criteria:

#### Automated Verification:

- `./gradlew test --tests com.example.app.event.EventRepositoryTest` passes including the new `findUpcomingByUserExcludesPendingProposedEvents`
- `./gradlew test --tests com.example.app.event.CalendarControllerTest` passes including `icsFeedExcludesPendingProposedEvents`
- `./gradlew build` succeeds with `ddl-auto=update` creating `source_image` + `proposed_event` tables on first run

#### Manual Verification:

- After running the app once locally, `psql … -c '\d+ source_image \d+ proposed_event'` shows both tables with the expected columns + FK + index
- No regression in `LlmExtractionRecordedRegressionTest` (this phase touches no LLM code, but re-run is the safety check)

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the schema landed cleanly in the local Postgres before proceeding to Phase 2.

---

## Phase 2: Multipart config + upload form + sync POST persisting source_image

### Overview

Wire the upload form end-to-end as a sync POST with no async or LLM yet: parent picks a file, JS pre-flight runs, multipart filter respects the new 15 MB / 20 MB ceiling, `ImageUploadController` persists `SourceImage` and redirects to a stub review page that just confirms the image landed. This phase exists in isolation so the file-handling contract is shippable and reviewable without touching async code.

### Changes Required:

#### 1. Multipart configuration

**File**: `src/main/resources/application.properties`

**Intent**: Raise multipart limits enough to accept typical phone photos (≤15 MB) and panorama screenshots (a hair under 15 MB), with a small request-overhead headroom.

**Contract**: Append three keys with a section comment explaining the 15 MB ceiling (matches research §6 + lesson "tighter is better for security; loose enough for the use case"):
```
spring.servlet.multipart.max-file-size=15MB
spring.servlet.multipart.max-request-size=20MB
spring.servlet.multipart.file-size-threshold=2MB
```

#### 2. ImageUploadController — GET form, POST persist

**File**: `src/main/java/com/example/app/event/ImageUploadController.java`

**Intent**: Two routes — render the upload form, and accept the multipart POST. POST validates MIME (server-side magic-bytes via `MultipartFile.getContentType()` + filename extension fallback), persists the `SourceImage` row, redirects to a stub review URL. No async here; the next phase replaces the redirect with `{jobId, statusUrl}` JSON.

**Contract**: `@Controller` (the GET is a Thymeleaf view; the POST returns JSON via `@ResponseBody` on the method) with `GET /events/from-image` → `"events/from-image"` view (model: `imageUploadForm`); `POST /events/from-image` → consumes `multipart/form-data`, `@RequestParam("file") MultipartFile file`, `Authentication auth`, returns `ResponseEntity<UploadErrorResponse>` on failure / `ResponseEntity<UploadResponse>` on success (the success-shape lands fully in Phase 3 §4; in Phase 2 the success branch returns `200 OK` with `reviewUrl` only — a placeholder envelope that Phase 3 §4 expands). Validation rules and their error-envelope mapping match the error-code table in Phase 3 §4: `file.isEmpty()` → 422 `file.empty`; `contentType ∉ {image/jpeg, image/png, image/webp}` (with extension-name fallback when `contentType == null`) → 422 `file.wrongType`. On success, `appUserRepository.findByEmail(auth.getName())`, `new SourceImage(user, bytes, mimeType)`, `save`, return the JSON envelope (Phase 2: `{reviewUrl}`). Per the per-controller test layout standard, this is a new `@Controller` and gets its own `*ControllerTest` that covers both error envelopes and the 200 success shape.

#### 3. MaxUploadSizeExceededHandler

**File**: `src/main/java/com/example/app/event/MaxUploadSizeExceededHandler.java`

**Intent**: Handle the file-too-big case that fires from the multipart filter before `ImageUploadController` runs, so the user sees an actionable Polish message instead of a 500.

**Contract**: `@RestControllerAdvice(assignableTypes = ImageUploadController.class)` with one `@ExceptionHandler(MaxUploadSizeExceededException.class)`. Reads the configured max-file-size from `application.properties` via `@Value("${spring.servlet.multipart.max-file-size}")`. Returns `ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(new UploadErrorResponse(List.of(new UploadFieldError("file", "file.tooLarge", "Zdjęcie jest za duże. Maksimum to 15 MB. Zmniejsz rozdzielczość w aparacie albo zrób screenshot z mniejszą jakością."))))` — same JSON envelope as Phase 3 §4 defines, HTTP 413 (transport-layer "payload too large", not 422 which is reserved for semantic-validation failures). The `assignableTypes` filter restricts the handler to `ImageUploadController` so it doesn't silently capture other future multipart endpoints. No redirect, no flash attribute — the JS in `from-image.html` parses the JSON and renders the message inline under `#upload-error`.

#### 4. Upload form template

**File**: `src/main/resources/templates/events/from-image.html`

**Intent**: Mirror `events/new.html`'s `head(title)` + `topbar` + `auth-page` shape; render the multipart form with a JS pre-flight that checks size + MIME (with extension fallback) and shows an inline Polish error before submit; XHR uploads with `upload.onprogress` driving a progress bar.

**Contract**: Standard Thymeleaf header + topbar; `<main class="auth-page">`; `<form id="upload-form" th:action="@{/events/from-image}" method="post" enctype="multipart/form-data">`; `<input type="file" name="file" accept="image/jpeg,image/png,image/webp" required>`; a `<div id="upload-progress">` with two child stages (`#upload-bar`, `#extract-bar` — extract-bar starts hidden, Phase 3 will use it); a `<div id="upload-error">` for the JS pre-flight error AND for server-returned error-envelope messages; a CSRF hidden input is auto-injected by Thymeleaf. Polish copy throughout. **No** `${uploadError}` flash attribute is read — the always-JSON contract from Phase 3 §4 makes the multipart-too-big handler return JSON 413 instead of a redirect, so the JS handles every failure path uniformly. JS inlined in `<script>` at the bottom — no separate `.js` file yet (the codebase has none today). JS contract: on submit, read `file.size`; if `> 15 * 1024 * 1024` show inline error with computed MB and abort; check `file.type` against the whitelist, and if empty fall back to `name.toLowerCase().endsWith(".jpg"|".jpeg"|".png"|".webp")`; otherwise `XMLHttpRequest` with `upload.onprogress` driving the bar. On response: `xhr.status === 200` → parse JSON, `window.location = response.reviewUrl` (Phase 3 replaces this with polling on `statusUrl`); any non-2xx → parse `{ errors: [{ field, code, message }] }`, look up `code` in the inline Polish i18n map (keys: `file.empty`, `file.wrongType`, `file.tooLarge`, `csrf.invalid`; values are the canonical Polish copy), fall back to `errors[0].message` if `code` is unknown, render under `#upload-error`, clear the progress bar.

#### 5. Stub review page (placeholder, replaced in Phase 4a)

**File**: `src/main/resources/templates/events/review.html`

**Intent**: Confirm the upload landed and tell the developer the next phase wires extraction. Not user-facing in the final shape, but lets Phase 2 ship as a working slice.

**Contract**: Standard `head('Review proposed events')` + `topbar` + `<main class="app-page">`; renders `<p>` with `${sourceImageId}` and the placeholder copy *"Zdjęcie zapisane. Ekstrakcja w przygotowaniu (faza 3)."* + a link back to `/app`. **Create a new `EventReviewController` in Phase 2 holding only this stub `GET /events/from-image/{imageId}/review` handler** (don't add it to `ImageUploadController` — Phase 4a expands the same controller with the real GET + POST `/decisions` + POST `/retry`; landing the file in Phase 2 avoids a needless Phase 4a code move and keeps the per-controller test layout standard intact from the first commit).

#### 6. App menu link

**File**: `src/main/resources/templates/app.html`

**Intent**: Surface the new upload path next to the existing "Add event" CTA.

**Contract**: Add a second `<a th:href="@{/events/from-image}" class="primary-action">Dodaj z obrazka</a>` alongside the existing `Add event` link. No structural change to `app.html`; one line.

#### 7. ImageUploadControllerTest

**File**: `src/test/java/com/example/app/event/ImageUploadControllerTest.java`

**Intent**: New per-controller `@SpringBootTest` + `MockMvc`. Cover: GET renders form; POST with valid JPEG persists a `SourceImage` row and redirects to `/events/from-image/{id}/review`; POST with `text/plain` re-renders form with field error; POST exceeding multipart size triggers the `MaxUploadSizeExceededHandler` redirect with flash; CSRF token rejected on POST without `_csrf` (sanity).

**Contract**: Use `MockMvc.perform(multipart("/events/from-image").file(...))`. `@MockMvcTest` or `@SpringBootTest` + `@AutoConfigureMockMvc` — match what `EventControllerTest` does (per-controller `@SpringBootTest` is the standard per lessons.md). One small JPEG fixture (`src/test/resources/uploads/sample.jpg`, ~10 KB) shared across tests.

### Success Criteria:

#### Automated Verification:

- `./gradlew test --tests com.example.app.event.ImageUploadControllerTest` passes
- `./gradlew build` succeeds; `MaxUploadSizeExceededHandler` boot-scans cleanly
- No regression in `EventControllerTest` (the multipart filter must not interfere with existing `application/x-www-form-urlencoded` POSTs)

#### Manual Verification:

- Local app: `GET /events/from-image` shows the form; uploading a 3 MB JPEG redirects to the stub review page that displays the source-image UUID
- Local app: selecting a 16 MB image shows the Polish "za duże" error inline before submit (JS pre-flight) and as a flash on the form after submit (server-side fallback)
- Mobile Safari / Chrome iOS: the file picker filters to camera + photos correctly with the `accept` attribute (no native crash on the empty `file.type` fallback)
- DB: `select id, mime_type, length(data) from source_image` after a few uploads shows the right byte counts

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the upload UX, the size-limit copy, and the mobile file-picker behaviour all feel right before proceeding to Phase 3.

---

## Phase 3: Async extraction pipeline + status polling

### Overview

Replace Phase 2's sync POST with an async pipeline: the POST kicks off `ExtractionService.runAsync(imageId)` and returns `{jobId, statusUrl}` JSON. The async task calls the existing blocking `LlmVisionClient.extract(...)`, persists N `ProposedEvent` rows on success, and writes terminal state to an in-memory `ExtractionJobRegistry` keyed by `jobId`. The browser polls `GET /events/from-image/status/{jobId}` every 1.5 s and navigates to `/events/from-image/{imageId}/review` on `DONE`.

### Changes Required:

#### 1. @EnableAsync + ThreadPoolTaskExecutor bean

**File**: `src/main/java/com/example/app/AppApplication.java`

**Intent**: Enable Spring's async infrastructure and define an explicitly-named bounded executor so logs are readable and burst load (a second parent arriving) doesn't accidentally outgrow heap.

**Contract**: Add `@EnableAsync` at the class level. Add `@Bean("extractionExecutor") TaskExecutor extractionExecutor()` returning a `ThreadPoolTaskExecutor` with `corePoolSize=2`, `maxPoolSize=2`, `queueCapacity=10`, `threadNamePrefix="extraction-"`, `setWaitForTasksToCompleteOnShutdown(true)`, `setAwaitTerminationSeconds(60)` — graceful shutdown lets in-flight LLM calls finish (or timeout via the existing 55 s read timeout) before SIGTERM kills the JVM.

#### 2. ExtractionJobRegistry

**File**: `src/main/java/com/example/app/event/ExtractionJobRegistry.java`

**Intent**: In-memory `jobId → JobStatus` map with a TTL sweep so old completed jobs don't accumulate.

**Contract**: `@Component` holding a `ConcurrentHashMap<UUID, JobStatusEntry>`. Inner `record JobStatusEntry(UUID imageId, JobState state, Instant createdAt, Instant updatedAt, String errorKind, String correlationId)`. Inner `enum JobState { RUNNING, DONE, FAILED }`. Methods: `UUID register(UUID imageId)` returns a fresh `jobId` and stores `RUNNING`; `void markDone(UUID jobId)`, `void markFailed(UUID jobId, String errorKind, String correlationId)`; `Optional<JobStatusEntry> get(UUID jobId)`; `Optional<JobStatusEntry> findRunningByImageId(UUID imageId)` — values-scan returning the first entry where `imageId.equals(...) && state == RUNNING` (used by `EventReviewController.GET` to detect "extraction in flight" and render `review-running` instead of misleading the user with the empty page). A `@Scheduled(fixedDelay = 60_000) void sweep()` removes entries whose `updatedAt` is older than 5 min (TTL constant, named in code). Requires `@EnableScheduling` on `AppApplication`.

#### 3. ExtractionService

**File**: `src/main/java/com/example/app/event/ExtractionService.java`

**Intent**: The async seam — a separate `@Service` bean so Spring's `@Async` proxy intercepts the method (self-calls don't). Wraps the existing blocking `LlmVisionClient.extract` and translates result/exception to persistence + registry updates.

**Contract**: `@Service` with constructor-injected `LlmVisionClient`, `SourceImageRepository`, `ProposedEventRepository`, `ExtractionJobRegistry`. Public method `@Async("extractionExecutor") void runExtraction(UUID jobId, UUID imageId)`. Flow:
1. Load `SourceImage` by id (re-load inside the async thread; do not hold the controller-thread entity);
2. Try: call `llmVisionClient.extract(image.getData(), MimeType.valueOf(image.getMimeType()))`;
3. On success: for each `ProposedEvent` from the result, persist a new `ProposedEvent` JPA row with status `PENDING`; call `registry.markDone(jobId)`. Empty result list is treated as success (zero rows persisted) — review page handles the empty state.
4. On `LlmExtractionException`: set `sourceImage.lastErrorKind = ex.kind().name()` and generate a `correlationId = UUID.randomUUID().toString().substring(0,8)`; persist; log `ERROR` line `extraction_failed imageId={} correlationId={} kind={} httpStatus={} providerMessage={}` (slf4j); call `registry.markFailed(jobId, kind, correlationId)`.
5. On any other `RuntimeException` (this branch catches errors **around** the extract call — `SourceImage` reload failure, `ProposedEvent` persistence error, registry update failure — not the LLM call itself; `OpenRouterLlmVisionClient` already wraps every `RuntimeException` from the model path into `LlmExtractionException` at its top-level catch in `OpenRouterLlmVisionClient.java:116-121`, so step 4 covers the entire LLM-side surface): same path as `LlmExtractionException` with `errorKind = "UNEXPECTED"`.

#### 4. ImageUploadController POST returns JSON

**File**: `src/main/java/com/example/app/event/ImageUploadController.java`

**Intent**: Switch `POST /events/from-image` from `redirect:/events/from-image/{id}/review` to JSON `{jobId, statusUrl, reviewUrl}` so the JS layer can drive the two-stage progress UI.

**Contract**: Change return type from `String` to `ResponseEntity<UploadResponse>` (records: `UploadResponse(UUID jobId, String statusUrl, String reviewUrl)`). Sequence: persist `SourceImage` → `jobId = jobRegistry.register(imageId)` → `extractionService.runExtraction(jobId, imageId)` (returns immediately, runs on executor) → respond `200 OK` with `statusUrl = "/events/from-image/status/" + jobId`, `reviewUrl = "/events/from-image/" + imageId + "/review"`.

**Error response contract (always JSON; no redirect, no view re-render)**: every failure path on `POST /events/from-image` — validation, multipart size limit, CSRF — returns the same JSON envelope so the JS handler under `#upload-error` can render uniformly. Envelope:

```json
{ "errors": [ { "field": "<form field name or 'file'>", "code": "<stable code>", "message": "<server-side fallback Polish copy>" } ] }
```

Error-code table (MVP set):

| HTTP | code | when |
|------|---------------------|------|
| 422  | `file.empty`        | `MultipartFile.isEmpty()` |
| 422  | `file.wrongType`    | MIME ∉ {`image/jpeg`, `image/png`, `image/webp`} (with extension-name fallback when `contentType == null`) |
| 413  | `file.tooLarge`     | `MaxUploadSizeExceededException` (HTTP 413 = payload too large is the right transport semantic; 422 is reserved for semantic-validation failures) |
| 403  | `csrf.invalid`      | CSRF token missing/expired (Spring Security default) |

`message` is the server-side fallback Polish copy (e.g. *"Zdjęcie jest za duże. Maksimum to 15 MB."*). The frontend holds the canonical Polish strings in an inline i18n map keyed by `code` so future copy edits don't require a server change; if `code` is unknown to the map, the JS falls back to `message`. **Accepted limitation**: a parent with JS disabled would see the raw JSON envelope on failure — MVP is XHR-driven, progressive enhancement is out of scope and tracked as a future enhancement in `change.md` notes.

#### 5. ExtractionStatusController

**File**: `src/main/java/com/example/app/event/ExtractionStatusController.java`

**Intent**: Tiny JSON endpoint the JS polls; returns the current `JobStatusEntry` or 404 when the TTL has evicted.

**Contract**: `@RestController` with one `GET /events/from-image/status/{jobId}` → `ResponseEntity<StatusResponse>`. Lookup in `ExtractionJobRegistry`; `Optional.isEmpty()` → 404. Response record: `StatusResponse(JobState state, String reviewUrl, String errorKind, String correlationId, long elapsedMs)` — `reviewUrl` populated on `DONE` and `FAILED` (review page handles both branches), `null` on `RUNNING`; `elapsedMs` for the JS "extracting… 23 s" copy. Authenticated like every other `/events/*` endpoint; the controller verifies the underlying `SourceImage.user == auth.getName()` (cross-user partition — a leaked `jobId` cannot reveal another user's status, mirroring the `findByIdAndUser` pattern from Phase 1).

#### 6. Upload-form JS — two-stage progress + polling

**File**: `src/main/resources/templates/events/from-image.html`

**Intent**: Extend Phase 2's XHR with a second stage that polls the status URL after upload completes, with `setInterval(..., 1500)`. Show elapsed seconds in the copy.

**Contract**: Replace the post-upload `window.location` redirect with: parse JSON → store `statusUrl` + `reviewUrl` + `t0 = Date.now()` → start `setInterval(poll, 1500)` showing `#extract-bar` with text *"Wyciągam wydarzenia ze zdjęcia… {sec} s"`. `poll()` does `fetch(statusUrl)`; on 404 → stop, show *"Sesja wygasła. Wyślij zdjęcie ponownie."* + back link; on `{state: DONE | FAILED}` → `window.location = reviewUrl`. Server-driven navigation lets the review page handle the three render branches (success / error / empty) — JS does not interpret the result, the next page does.

#### 7. ExtractionServiceTest

**File**: `src/test/java/com/example/app/event/ExtractionServiceTest.java`

**Intent**: Hermetic test of the success + 3 failure branches using a stubbed `LlmVisionClient` (no real LLM call, no Spring context if achievable). Validates the field copy from `LlmExtractionResult.ProposedEvent` to `ProposedEvent` JPA row is faithful.

**Contract**: `@SpringBootTest` (per-test-class standard); `@MockitoBean LlmVisionClient`; stub four scenarios: success with 2 events → both rows persist `PENDING`, registry `DONE`; success with `[]` → zero rows, registry `DONE`; `LlmExtractionException.timeout()` → no `ProposedEvent` rows, `sourceImage.lastErrorKind == "TIMEOUT"`, registry `FAILED` with correlationId; `LlmExtractionException.provider(503, "upstream busy", null)` → `lastErrorKind == "PROVIDER_ERROR"`, log line contains "503" and "upstream busy" (assert via `OutputCaptureExtension`). The `MALFORMED_RESPONSE` branch reuses `LlmExtractionException.malformed`.

#### 8. ExtractionStatusControllerTest

**File**: `src/test/java/com/example/app/event/ExtractionStatusControllerTest.java`

**Intent**: Cover the four status branches + the cross-user partition.

**Contract**: `@SpringBootTest` + `MockMvc`. Tests: RUNNING returns 200 + `state=RUNNING`; DONE returns 200 + non-null `reviewUrl`; FAILED returns 200 + `errorKind` + `correlationId`; unknown jobId → 404; jobId belonging to user B but polled as user A → 404 (do NOT 403, identical to the unknown-jobId branch to avoid jobId enumeration).

### Success Criteria:

#### Automated Verification:

- `./gradlew test --tests com.example.app.event.ExtractionServiceTest` passes including all 4 scenarios
- `./gradlew test --tests com.example.app.event.ExtractionStatusControllerTest` passes including cross-user partition
- `./gradlew test --tests com.example.app.event.ImageUploadControllerTest` continues to pass after POST switches to JSON response
- `./gradlew build` succeeds — no regression in `LlmExtractionRecordedRegressionTest`

#### Manual Verification:

- Local app: upload a real photo of a Polish kindergarten announcement; the upload progress bar fills, then the extraction copy reads *"Wyciągam wydarzenia ze zdjęcia… 5 s"*, ticking; on DONE the browser lands on the stub review page (still placeholder in this phase) with the right `imageId` in the URL
- DB: `select * from proposed_event where source_image_id = ?` shows the extracted rows with `status='PENDING'`
- Force a timeout by lowering `spring.ai.openai.timeout=5s` locally; trigger an upload; status endpoint shows `FAILED`, `errorKind=TIMEOUT`, `correlationId` present; restore the property to `55s` after
- `fly logs` (or local `bootRun`) shows the structured `extraction_failed` line on the forced timeout
- Wait 6 minutes after a successful extraction, hit the status URL — receives `404` (TTL sweep works)

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the polling cadence feels right, the timeout copy lands well, and the LLM extraction itself still hits the harness's accuracy bar before proceeding to Phase 4a.

---

## Phase 4a: Review happy path — proposals present, decisions POST, transactional promotion

### Overview

Replace the stub `review.html` with the real review page that renders the `PENDING` proposals as a stacked form with per-row accept/reject + inline edit. Submitting promotes the accepted rows into `app_event` and stamps `source_image.resolved_at` — all in one transaction.

### Changes Required:

#### 1. EventReviewController

**File**: `src/main/java/com/example/app/event/EventReviewController.java`

**Intent**: Two routes: `GET .../review` renders the form (this phase: only the proposals-present branch; Phase 4b adds error + empty branches); `POST .../decisions` walks the rows and delegates promotion to `EventReviewService`.

**Contract**: `@Controller` — already created in Phase 2 §5 with just the stub `GET /events/from-image/{imageId}/review`. Phase 4a expands the existing GET to load `SourceImage` via `findByIdAndUser(imageId, currentUser)`; 404 if absent (cross-user partition); load `List<ProposedEvent>` via `findBySourceImageOrderBy…`; build `EventReviewForm` from the proposals; render `events/review` (the real template, no longer the Phase 2 stub copy). `POST /events/from-image/{imageId}/decisions` → consume `@ModelAttribute EventReviewForm form` **without** `@Valid`; manually validate each accept row (see "Critical Implementation Details"); on row-level errors aggregate into `BindingResult` and re-render with the user's edits preserved; on success call `eventReviewService.applyDecisions(imageId, currentUser, form)` and `redirect:/app` with `flash.successMessage = "Dodano " + acceptedCount + " wydarzeń."`.

#### 2. EventReviewForm + ProposedEventDecision

**File**: `src/main/java/com/example/app/event/EventReviewForm.java`

**Intent**: Backing form for the review page; one entry per `ProposedEvent` carrying the editable fields + the accept/reject action.

**Contract**: `class EventReviewForm` with `private List<ProposedEventDecision> decisions = new ArrayList<>()` and the standard getter/setter. Inner `static class ProposedEventDecision` with fields: `UUID proposedEventId`; `Action action` (enum `{ ACCEPT, REJECT }`, default `ACCEPT`); `@DateTimeFormat LocalDate eventDate`; `LocalTime eventTime`; `String title`; `String requirements`; `String notes`; `String status` (the current `ProposedEventStatus` of the underlying row — populated by the GET handler when building the form so the template can hide non-`PENDING` rows on re-render; not submitted by the user). Note: validation annotations (`@NotNull` etc.) are present on the editable decision fields for the manual-validate path to read groups from, but `@Valid` is **not** applied at the controller — see "Critical Implementation Details".

#### 3. EventReviewService

**File**: `src/main/java/com/example/app/event/EventReviewService.java`

**Intent**: The transactional seam that promotes accepted proposals to `Event` rows, marks proposals decided, and stamps `source_image.resolved_at` — all in one atomic step.

**Contract**: `@Service`. Public method `@Transactional int applyDecisions(UUID imageId, AppUser user, EventReviewForm form)` returns the count of accepted-and-promoted events. Flow (order matters — see "State sequencing" in Critical Implementation Details):
1. Load `SourceImage` via `findByIdAndUser(imageId, user)`; throw 404 if absent.
2. Load `ProposedEvent`s by source image; build a `Map<UUID, ProposedEvent>` for lookup.
3. For each `ProposedEventDecision` in the form: look up the `ProposedEvent`; if its `status != PENDING` skip (idempotent re-submit); if `action == ACCEPT`, construct `new Event(user, decision.eventDate, decision.eventTime, decision.title.trim(), trimOrNull(decision.requirements), trimOrNull(decision.notes))` and `eventRepository.save(event)`; flip `proposedEvent.status = ACCEPTED`, set `decidedAt`; if `action == REJECT`, flip `status = REJECTED`, set `decidedAt`.
4. After all rows processed: stamp `sourceImage.resolvedAt` **only if it is still null** — `if (sourceImage.resolvedAt == null) sourceImage.resolvedAt = Instant.now();`. The guard preserves the original first-decision timestamp on a replay (browser back + re-POST), which matters because S-06's purge contract scans on `resolved_at` (Phase 1 entity contract); without the guard a replay silently extends the image's retention window. Then `save`.
5. Return the accept count.

#### 4. Review template — happy path

**File**: `src/main/resources/templates/events/review.html`

**Intent**: Replace the Phase 2 stub with the real review page. Stacked list of rows; each row has accept/reject radios (default: accept) and the five editable fields (mirroring `events/new.html`'s field shape). One Submit at the bottom.

**Contract**: Standard `head('Sprawdź wydarzenia')` + `topbar` + `<main class="app-page">`. `<form th:action="@{|/events/from-image/${sourceImage.id}/decisions|}" method="post" th:object="${reviewForm}">` containing `<fieldset th:each="decision, iter : *{decisions}" th:if="${decision.status == 'PENDING'}">`: a `<radio>` group for `action` (values `ACCEPT` / `REJECT`, default `ACCEPT`); a hidden `proposedEventId`; the five labeled inputs mirroring `events/new.html` shape (`*{decisions[__${iter.index}__].eventDate}` etc.); per-field `<span class="field-error" th:if="...">` blocks. Bottom: single `<button type="submit">Zapisz wybór</button>` with Polish copy. CSS class additions go inline in the existing `fragments/layout.html :: head` `<style>` block.

**Re-render of already-decided rows**: the `th:if="${decision.status == 'PENDING'}"` filter hides any row whose `ProposedEvent.status` is no longer `PENDING` so the form has no way to attempt a (silently-skipped) re-decision via browser back — see "What We're NOT Doing" for the UX rationale. If every row has been decided (the form would be empty), render a small info banner instead: *"Decyzje dla tego zdjęcia zostały już zapisane. Aby edytować zaakceptowane wydarzenia, przejdź do listy wydarzeń."* with an `<a th:href="@{/app}">Przejdź do listy</a>` link. This means `reviewForm.decisions` must carry the `status` (or equivalent boolean) onto the view model — extend the existing model mapping accordingly.

#### 5. App.html flash banner

**File**: `src/main/resources/templates/app.html`

**Intent**: Surface the `successMessage` flash from the decisions POST redirect.

**Contract**: Add `<div class="flash-success" th:if="${successMessage != null}" th:text="${successMessage}"></div>` immediately inside `<main>`, before the existing greeting. Style declaration goes alongside other CSS in `fragments/layout.html :: head`.

#### 6. EventReviewServiceTest

**File**: `src/test/java/com/example/app/event/EventReviewServiceTest.java`

**Intent**: Cover the transactional promotion logic: mixed accept/reject, all-accept, all-reject, idempotent re-submit, cross-user 404, `sourceImage.resolvedAt` stamped exactly once.

**Contract**: `@SpringBootTest` (full context — the test exercises real JPA + transaction boundary). Test cases:
- `appliesMixedAcceptRejectAndPromotesAcceptedOnly`: 3 proposals, 2 accept + 1 reject → 2 `Event` rows persist, 2 proposals are `ACCEPTED`, 1 `REJECTED`, `sourceImage.resolvedAt` non-null;
- `idempotentSecondSubmit`: replay the same form a second time → no extra `Event` rows; counts unchanged; **`sourceImage.resolvedAt` equals the first-submit value** (the stamp guard preserves the original purge-clock anchor; without it, the replay would reset S-06's retention scan key);
- `throws404WhenImageBelongsToOtherUser`: persist `SourceImage` for user B; call service with user A → throws expected exception;
- `acceptedEventCopiesAllFieldsFromDecision`: assert each of the five fields propagates correctly; trim is applied to title/requirements/notes.

#### 7. EventReviewControllerTest

**File**: `src/test/java/com/example/app/event/EventReviewControllerTest.java`

**Intent**: Cover GET (renders form), POST happy path (redirect + flash), POST validation rejection (re-render with errors, edits preserved), POST with `action=REJECT` skipping validation on garbage date/title in that row.

**Contract**: `@SpringBootTest` + `MockMvc`. Tests:
- `getRendersFormWithProposals`;
- `postWithAllAcceptPromotesAndRedirectsToAppWithFlash`;
- `postWithInvalidTitleOnAcceptRowReRendersWithError`;
- `postWithInvalidDateOnRejectRowIsAccepted` — the load-bearing test: row with `action=REJECT` and `eventDate=null` must succeed; only accept rows are validated;
- `getAfterDecisionsRendersOnlyPendingRows` — persist a `SourceImage` with one ACCEPTED + one REJECTED + one PENDING row; GET should produce a model where only the PENDING row's fieldset is rendered (assert via Thymeleaf-rendered HTML or by inspecting `reviewForm.decisions` filtered visibility — proves the browser-back re-decision is structurally blocked, not just silently skipped);
- `getCrossUserReturns404`.

### Success Criteria:

#### Automated Verification:

- `./gradlew test --tests com.example.app.event.EventReviewServiceTest` passes including idempotent re-submit
- `./gradlew test --tests com.example.app.event.EventReviewControllerTest` passes including `postWithInvalidDateOnRejectRowIsAccepted` and `getAfterDecisionsRendersOnlyPendingRows`
- `./gradlew test` — full suite green; `EventRepositoryTest` and `CalendarControllerTest` guardrail tests still pass
- `./gradlew build` succeeds

#### Manual Verification:

- Local app: upload a real announcement → review page renders one row per extracted event → edit a date → accept some, reject others → Submit → land on `/app` with the Polish flash → the accepted events show in the personal view list
- `select * from app_event where user_id = ?` shows the promoted events with all fields intact
- `select status, decided_at from proposed_event where source_image_id = ?` shows ACCEPTED + REJECTED rows; no PENDING left
- `select resolved_at from source_image where id = ?` is non-null
- `GET /calendar/{token}.ics` returns an `.ics` with the new accepted events; no PENDING/REJECTED proposals appear

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the review-page UX feels right (especially the per-row form state under validation errors) before proceeding to Phase 4b.

---

## Phase 4b: Error / empty / retry states

### Overview

Extend `EventReviewController.GET .../review` to route to three additional templates based on `SourceImage` state: error (lastErrorKind set), empty (zero proposals + no error + not yet resolved), and the proposals-present happy path from 4a. Add `POST /events/from-image/{imageId}/retry` to re-kick extraction on a failed image.

### Changes Required:

#### 1. EventReviewController routing branches

**File**: `src/main/java/com/example/app/event/EventReviewController.java`

**Intent**: `GET .../review` picks the right template based on `SourceImage` state; one render branch per business case.

**Contract**: Replace the single `return "events/review"` from 4a with a switch:
- `sourceImage.lastErrorKind != null` AND `sourceImage.resolvedAt == null` → `"events/review-error"` (model: `sourceImage`, `correlationId`, `errorKind`);
- proposals list empty AND `lastErrorKind == null` AND `resolvedAt == null` AND `jobRegistry.findRunningByImageId(imageId).isPresent()` → `"events/review-running"` (model: `sourceImage`) — the request hit during an in-flight extraction (direct URL, browser-back, retry-poll race); without this branch the GET handler silently shows the empty page and lies to the user;
- proposals list empty AND `lastErrorKind == null` → `"events/review-empty"` (model: `sourceImage`);
- otherwise → `"events/review"` (the 4a happy path).
The branch ordering matters: error first (a failed extraction without proposals must show the error page, not the empty page), then running before empty (a still-extracting image must show the wait page, not the empty page), then empty, then happy path.

To support the running-branch lookup, add a small helper to `ExtractionJobRegistry`: `Optional<JobStatusEntry> findRunningByImageId(UUID imageId)` — scans `map.values()` for an entry where `imageId.equals(...)` AND `state == RUNNING` AND returns the first hit. At single-user MVP scale (ConcurrentHashMap with ≤ a few in-flight jobs) the scan cost is negligible.

#### 2. RetryExtractionController route

**File**: `src/main/java/com/example/app/event/EventReviewController.java` (same controller)

**Intent**: `POST /events/from-image/{imageId}/retry` clears the previous error state on `SourceImage`, registers a new `jobId`, kicks the async extraction again, and responds with `{jobId, statusUrl}` JSON so the upload form's JS pattern can reuse the same polling path.

**Contract**: New `@PostMapping("/events/from-image/{imageId}/retry") @ResponseBody ResponseEntity<UploadResponse>`. Load `SourceImage` via `findByIdAndUser`; clear `lastErrorKind` and `correlationId`; save; `jobId = jobRegistry.register(imageId)`; `extractionService.runExtraction(jobId, imageId)`; return the same `UploadResponse(jobId, statusUrl, reviewUrl)` shape Phase 3 introduced. The error template (#3) wires its Retry button to POST to this endpoint with the same XHR+polling JS pattern as the upload form — the JS lives inline in the template; the polling helper from Phase 3 should be extracted into a tiny shared `<script>` fragment in `fragments/layout.html` or inlined twice (acceptable at this scale; one extra inline copy < pulling in a JS bundle).

#### 3. Error template

**File**: `src/main/resources/templates/events/review-error.html`

**Intent**: Show a user-friendly Polish headline with the correlation ID for the operator-side log triage, plus a Retry button and a fallback link to manual entry.

**Contract**: Standard `head('Coś poszło nie tak')` + `topbar` + `<main class="auth-page">`. Headline: one of three Polish strings keyed by `errorKind`:
- `TIMEOUT` → *"Wyciąganie wydarzeń zajęło za długo. To może być chwilowa nieprawidłowość po stronie modelu — spróbuj ponownie."*
- `PROVIDER_ERROR` → *"Serwis AI tymczasowo nie odpowiada. Spróbuj ponownie za chwilę albo dodaj wydarzenie ręcznie."*
- `MALFORMED_RESPONSE` / `UNEXPECTED` → *"Nie udało się przetworzyć tego zdjęcia. Spróbuj ponownie, dodaj inne zdjęcie, albo wpisz wydarzenie ręcznie."*

Below: a single `<form method="post" th:action="@{|/events/from-image/${sourceImage.id}/retry|}">` with a `<button>Spróbuj ponownie</button>` (the form submits via the inline JS to get the polling shape) and a sibling `<a th:href="@{/events/new}">Wpisz ręcznie</a>` link. At the bottom in a small monospaced block: *"Kod referencyjny: <span th:text=\"${correlationId}\"></span>"* — `th:text` auto-escapes. JS at bottom replicates Phase 3's XHR+polling pattern.

**Deferred enhancement note**: a Polish copy switch on `httpStatus` (4xx = image rejected, 5xx = provider down) was considered and deferred. Today every PROVIDER_ERROR collapses to one copy; the correlation ID + `fly logs` grep is the triage path. Revisit when usage > 1 parent OR the same error class lands >3× in a week. Tracked in change.md notes after this phase ships.

#### 3b. Running template

**File**: `src/main/resources/templates/events/review-running.html`

**Intent**: When the GET handler hits a `SourceImage` whose extraction is still in flight (no error, no proposals yet, no `resolvedAt`, registry shows RUNNING), show a friendly "we're working on it" page that self-refreshes — so a direct-URL hit or browser-back lands on a self-healing screen instead of the misleading empty state.

**Contract**: Standard `head('Wyciągamy wydarzenia…')` + `topbar` + `<main class="auth-page">`. Polish copy: *"Wyciąganie wydarzeń trwa… odśwież stronę za chwilę albo poczekaj — zrobimy to za Ciebie."* Include `<meta http-equiv="refresh" content="3">` in `<head>` (or `<script>setTimeout(() => location.reload(), 3000)</script>` if the layout fragment makes `<meta>` injection awkward) — two lines of HTML that kill a class of "I had to F5 myself" tickets without any JS framework. No retry / no CTAs on this page — extraction is already running; the user just waits.

#### 4. Empty template

**File**: `src/main/resources/templates/events/review-empty.html`

**Intent**: When the model returns `[]` for a readable-but-irrelevant image, show a friendly empty state with two clear CTAs (manual entry, try another photo) — never frame this as an error, because it isn't.

**Contract**: Standard `head('Nie znaleziono wydarzeń')` + `topbar` + `<main class="auth-page">`. Polish copy: *"Nie znaleźliśmy wydarzeń na tym zdjęciu. Może to być zła jakość zdjęcia lub treść, której model nie rozpoznał jako wydarzenia."* Two `<a class="primary-action">` links side by side: `Spróbuj innego zdjęcia` → `/events/from-image`; `Wpisz ręcznie` → `/events/new`. No retry on the same `source_image` here — the assumption is the model already produced its best guess; a different photo is the lever.

#### 5. Status response on FAILED

**File**: `src/main/java/com/example/app/event/ExtractionStatusController.java`

**Intent**: Confirm the `reviewUrl` returned on `FAILED` already routes the JS to the error template (no change needed — Phase 3 set this up, but verify the wiring).

**Contract**: No code change; documentation-only check that `StatusResponse.reviewUrl` is populated for both `DONE` and `FAILED`. A small `@Test` is added to `ExtractionStatusControllerTest` asserting this.

#### 6. EventReviewControllerTest — branch coverage

**File**: `src/test/java/com/example/app/event/EventReviewControllerTest.java`

**Intent**: Extend the 4a tests with branch coverage of the three render paths and the retry endpoint.

**Contract**: Add tests:
- `getRendersErrorTemplateWhenLastErrorKindSet`: persist `SourceImage` with `lastErrorKind="TIMEOUT"`, `correlationId="abc12345"`; assert view name `events/review-error`, model has `errorKind` + `correlationId`;
- `getRendersRunningTemplateWhenExtractionInFlight`: persist a fresh `SourceImage` (no proposals, no error, no `resolvedAt`); register a RUNNING job via `ExtractionJobRegistry`; GET → view name `events/review-running` — proves the race window between retry POST and terminal markDone/markFailed no longer routes to the empty page;
- `getRendersEmptyTemplateWhenNoProposalsAndNoError`: persist resolved-but-empty `SourceImage` → view name `events/review-empty`;
- `postRetryClearsErrorAndKicksExtraction`: persist failed `SourceImage`; POST retry → response is JSON `{jobId, statusUrl, reviewUrl}`; `sourceImage.lastErrorKind` is null; `extractionService.runExtraction` was invoked (verify with a spy or `@MockitoBean`);
- `postRetryCrossUserReturns404`.

### Success Criteria:

#### Automated Verification:

- `./gradlew test --tests com.example.app.event.EventReviewControllerTest` passes including the five new branch tests (error, running, empty, retry happy, retry cross-user)
- `./gradlew test` — full suite green
- `./gradlew build` succeeds

#### Manual Verification:

- Force a timeout (lower `spring.ai.openai.timeout=3s`, upload a real photo) → land on `review-error.html` → click Retry → the polling JS reruns; if the model now responds in time the page navigates to the happy-path review
- During a real extraction (~20–30 s window), open the review URL directly in a second tab → `review-running.html` renders with the meta-refresh; after ~3 s the page reloads and (once extraction lands) routes to the happy/empty/error template
- Local app: upload a photo of a completely irrelevant image (e.g. a landscape photo, no announcement) → if the model returns `[]`, see the empty-state page with both CTAs
- Force `MALFORMED_RESPONSE` (temporarily break the parse step locally) → review-error template shows the generic copy + correlation ID
- `fly logs | grep correlationId-from-page` finds the structured `extraction_failed` line — triage path works end-to-end

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the three render branches each feel right and the retry path works on a real failure before proceeding to Phase 5.

---

## Phase 5: E2E manual verification + harness re-run + cookbook backfill

### Overview

This phase has no production code changes. It exists to walk the full S-05 slice end-to-end on real announcements, confirm no regression in the LLM accuracy harness, and capture the slice's testing pattern in the §6 cookbook so future slices can copy the shape.

### Changes Required:

#### 1. Walk 2–3 real announcement photos through the full slice

**File**: (manual — operator notebook entry under `context/changes/image-extraction-and-review-acceptance/`)

**Intent**: Validate the north-star outcome: a real photo → review page → accept → personal view → subscribed calendar within one polling cycle. Per lessons.md, judge "did this work" against date/time/requirements being correct, not title/notes phrasing.

**Contract**: Pick 2–3 representative announcements (one parent-chat screenshot, one corridor-note photo, one kindergarten-app screenshot, if available). For each: time the upload → extraction round trip; record proposals vs ground truth (count, field-level deltas on date/time/requirements); accept a subset; check `/app`; check `/calendar/{token}.ics`; subscribe in Google Calendar (or Apple Calendar — note the Google reminder-drop limitation from PRD §Known Limitations). Output: a short notes file `e2e-walk.md` in the change folder.

#### 2. Re-run LLM regression harness

**File**: (run-only — no code change)

**Intent**: Confirm S-05's wiring did not change the LLM call shape in a way that drifts the per-field accuracy on the 10-fixture set.

**Contract**: `./gradlew test --tests com.example.app.llm.LlmExtractionRecordedRegressionTest`. Read the divergence catalog (`LlmDivergenceCatalog.java`); if the recorded deltas widen, that's a signal. Expected: no change.

#### 3. test-plan.md §6 cookbook entry

**File**: `context/foundation/test-plan.md`

**Intent**: Capture the S-05 testing pattern so the next slice (S-03, S-06) inherits the shape: per-controller `@SpringBootTest`, hermetic service tests with `@MockitoBean LlmVisionClient`, calendar-feed guardrail at both repository and integration levels.

**Contract**: Append a new entry to §6 (the cookbook) under a heading like `## S-05: AI-extraction review + acceptance`. Capture: the manual-validation pattern for the decisions form, the dual-layer guardrail (repo test + ical integration test), the in-memory job registry's TTL test, the cross-user partition pattern for `jobId` lookup.

#### 4. Update lessons.md if surprises surfaced

**File**: `context/foundation/lessons.md`

**Intent**: If the implementation surfaced a rule worth carrying forward (e.g. "manually validate when @Valid can't conditionally skip rows", "in-memory job registry + scheduled sweep is acceptable at single-user MVP", etc.), capture it.

**Contract**: Only add an entry if a real surprise emerged. The bar is "would another implementer hit the same pitfall?" If yes, append a `## …` block matching the existing lessons.md format (Context / Problem / Rule / Applies to). If no surprise: skip this step entirely.

#### 5. Stamp change.md

**File**: `context/changes/image-extraction-and-review-acceptance/change.md`

**Intent**: Flip `status: planned → done` and bump `updated:`.

**Contract**: Edit frontmatter only.

### Success Criteria:

#### Automated Verification:

- `./gradlew test` — full suite green one final time
- `./gradlew test --tests com.example.app.llm.LlmExtractionRecordedRegressionTest` — per-field accuracy unchanged
- `./gradlew build` succeeds

#### Manual Verification:

- 2–3 real announcement photos walked successfully: at least 1 produces correct date+title+requirements on first extraction (the ≥80% bet at this fixture-set scale would need >5 trials, but the unit-of-1 walk catches regressions)
- Subscribed calendar shows the new event(s) after the client polls (Apple Calendar / Outlook / Thunderbird preferred to also confirm the reminder lands)
- After a real ~12 MB photo upload completes extraction, sample heap-used at peak with `jcmd <pid> GC.heap_info` (actuator is not on the classpath — this stays as a one-off measurement, not as recurring telemetry) and record the peak observed during extraction in §6 cookbook (baseline for the documented ~50 MB per-request floor — informs the first scale-up decision)
- Upload a 20+ MB JPEG → response is HTTP `413` with body `{"errors":[{"field":"file","code":"file.tooLarge",...}]}`, the Polish copy renders inline under `#upload-error`, no page reload, no flash redirect
- Upload a `.txt` (or any non-image MIME, e.g. `application/pdf`) → response is HTTP `422` with body `{"errors":[{"field":"file","code":"file.wrongType",...}]}`, the Polish copy renders inline under `#upload-error` (same DOM hook as the size-too-large case — proves the uniform error envelope across the size and validation paths)
- `test-plan.md` §6 cookbook entry written and reads cleanly to a developer who has not seen this slice
- `change.md` flipped to `status: done`

**Implementation Note**: This phase closes the slice. After verification, the change folder is ready for the archival step described in `/10x-archive`.

---

## Testing Strategy

### Unit Tests:

- `ExtractionServiceTest` — hermetic per-branch coverage of the four outcome paths from the LLM call: success-with-N, success-empty, `TIMEOUT`, `PROVIDER_ERROR`; verifies the `correlationId` is emitted on failure and that `sourceImage.lastErrorKind` flips correctly
- `EventReviewServiceTest` — the transactional promotion: mixed accept/reject, idempotent re-submit, cross-user partition, `resolvedAt` stamping, field-copy fidelity from `ProposedEventDecision` → `Event`
- `ExtractionJobRegistryTest` (small) — register/markDone/markFailed semantics + the TTL sweep evicts entries older than 5 min + `findRunningByImageId` returns the in-flight entry and an empty Optional once `markDone`/`markFailed` lands

### Integration Tests:

- `EventRepositoryTest.findUpcomingByUserExcludesPendingProposedEvents` — the calendar-feed guardrail at the repository level (Phase 1)
- `CalendarControllerTest.icsFeedExcludesPendingProposedEvents` — the same guardrail at the HTTP/ical-serialization level (Phase 1)
- `ImageUploadControllerTest` — multipart contract: valid upload, invalid MIME, oversize via `MaxUploadSizeExceededHandler`, CSRF gate
- `EventReviewControllerTest` — GET routing across 3 render branches (happy / error / empty); POST decisions with mixed validity; retry POST kicks a fresh extraction
- `ExtractionStatusControllerTest` — RUNNING/DONE/FAILED branches + cross-user partition + TTL miss → 404

### Manual Testing Steps:

1. Upload a real photo of a Polish kindergarten announcement; confirm the two-stage progress UI works (upload bar, then "extracting… N s")
2. Accept some proposals, reject others; confirm the personal view + `.ics` body update
3. Force `TIMEOUT` (lower `spring.ai.openai.timeout=3s` locally); confirm `review-error.html` displays the correlation ID; click Retry; confirm the polling JS works on the retry path
4. Force `[]` extraction (point the model at an irrelevant image); confirm `review-empty.html` shows both CTAs
5. Subscribe to `/calendar/{token}.ics` in Apple Calendar; confirm a newly accepted event surfaces within ~10 min, with the morning-of-day-before reminder
6. Wait 6 min after a successful extraction; hit the status endpoint; confirm 404 (TTL sweep)
7. Mobile Safari + Chrome iOS: confirm the file picker filters to JPEG/PNG/WEBP via `accept`; confirm the Polish size-error inline message renders correctly when picking a 20+ MB photo

## Performance Considerations

The async executor is bounded at `core=2, max=2, queue=10` — at single-user MVP scale this is comfortably oversized; the bound exists so that a runaway loop in extraction can't exhaust the 1 GB Fly Machine's heap. `byte[]` images of ~15 MB held in memory during extraction add real heap pressure under bursts. Each in-flight request retains several copies of the image bytes simultaneously: the `MultipartFile.getBytes()` copy on the controller thread (~15 MB), the `@Lob byte[]` held in the JPA persistence context for the duration of the upload transaction (~15 MB), and — once `OpenRouterLlmVisionClient` wraps the bytes in a `ByteArrayResource` (`OpenRouterLlmVisionClient.java:77-90`) — a base64-inlined string inside the Spring AI request body (~20 MB) plus Jackson + HTTP-client buffers. Realistic per-request floor is **~50 MB**, not 30 MB; with `max=2` concurrent extractions the worst-case in-flight image churn is **~100–140 MB**, which is survivable on a 1 GB Fly Machine (heap ~512 MB) at single-user MVP scale but will be the first knob to revisit on scale-up. (Spring AI may stream-chunk the request body in some configurations — unverified for the OpenRouter path; treat ~50 MB as the documented floor until Phase 5's baseline measurement confirms otherwise.)

The polling cadence is 1.5 s — fast enough to feel responsive on a 30 s typical extraction, slow enough that 30 polls/extraction × bytes-per-status-response stays under 1 KB/poll. The `ExtractionJobRegistry` is a `ConcurrentHashMap`; at single-user MVP scale a TTL-sweep every 60 s is comfortably cheap.

The `bytea` column in `source_image` adds WAL bloat proportional to upload count. At MVP scale (a handful of uploads per week, autopurged by S-06 once resolved), this is immaterial.

## Migration Notes

`ddl-auto=update` with `deploy-plan.md` §5.2's additive-only policy: the two new tables (`source_image`, `proposed_event`) are pure additions, no column drops, no type narrowing. The first `bootRun` after the entities ship will create both tables. There is no data migration — neither table has predecessor rows. The `app_event` table is unchanged.

If the slice ever needs to be rolled back, the rollback path is: revert the code; the orphan `source_image` and `proposed_event` rows are harmless (the iCalendar feed reads `app_event` only); a follow-up `DROP TABLE` can clean up in a quiet window if desired. There is no scenario where partial S-05 deploy corrupts existing accepted-event data, because the only write to `app_event` is the transactional promotion, which is a normal `INSERT` indistinguishable from the manual-entry path.

## References

- Related research: `context/changes/image-extraction-and-review-acceptance/research.md`
- F-01 OpenRouter wiring + exception taxonomy: `src/main/java/com/example/app/llm/OpenRouterLlmVisionClient.java:77-121`, `src/main/java/com/example/app/llm/LlmExtractionException.java`
- S-02 manual-entry canonical patterns to mirror: `src/main/java/com/example/app/event/EventController.java:33-53`, `src/main/resources/templates/events/new.html`
- S-04 calendar-feed contract to protect: `src/main/java/com/example/app/event/CalendarController.java`, `src/main/java/com/example/app/event/IcalFeedWriter.java`
- Per-controller test layout standard: `context/foundation/lessons.md` §"Per-controller `@SpringBootTest` class is the test layout standard"
- Grade-only-what-a-user-would-call-wrong lesson (informs manual verification judging): `context/foundation/lessons.md` §"Grade only what a user would call wrong…"

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Persistence foundation + calendar-feed guardrail

#### Automated

- [x] 1.1 `./gradlew test --tests com.example.app.event.EventRepositoryTest` passes including the new `findUpcomingByUserExcludesPendingProposedEvents` — 031be87
- [x] 1.2 `./gradlew test --tests com.example.app.event.CalendarControllerTest` passes including `icsFeedExcludesPendingProposedEvents` — 031be87
- [x] 1.3 `./gradlew build` succeeds with `ddl-auto=update` creating `source_image` + `proposed_event` tables on first run — 031be87

#### Manual

- [x] 1.4 After running the app once locally, `psql … -c '\d+ source_image \d+ proposed_event'` shows both tables with the expected columns + FK + index — 031be87
- [x] 1.5 No regression in `LlmExtractionRecordedRegressionTest` (this phase touches no LLM code, but re-run is the safety check) — 031be87

### Phase 2: Multipart config + upload form + sync POST persisting source_image

#### Automated

- [x] 2.1 `./gradlew test --tests com.example.app.event.ImageUploadControllerTest` passes — c989d85
- [x] 2.2 `./gradlew build` succeeds; `MaxUploadSizeExceededHandler` boot-scans cleanly — c989d85
- [x] 2.3 No regression in `EventControllerTest` (the multipart filter must not interfere with existing `application/x-www-form-urlencoded` POSTs) — c989d85

#### Manual

- [x] 2.4 Local app: `GET /events/from-image` shows the form; uploading a 3 MB JPEG redirects to the stub review page that displays the source-image UUID — c989d85
- [x] 2.5 Local app: selecting a 16 MB image shows the Polish "za duże" error inline before submit (JS pre-flight) and as a flash on the form after submit (server-side fallback) — c989d85
- [x] 2.6 Mobile Safari / Chrome iOS: the file picker filters to camera + photos correctly with the `accept` attribute (no native crash on the empty `file.type` fallback) — c989d85
- [x] 2.7 DB: `select id, mime_type, length(data) from source_image` after a few uploads shows the right byte counts — c989d85

### Phase 3: Async extraction pipeline + status polling

#### Automated

- [x] 3.1 `./gradlew test --tests com.example.app.event.ExtractionServiceTest` passes including all 4 scenarios — 44b13ac
- [x] 3.2 `./gradlew test --tests com.example.app.event.ExtractionStatusControllerTest` passes including cross-user partition — 44b13ac
- [x] 3.3 `./gradlew test --tests com.example.app.event.ImageUploadControllerTest` continues to pass after POST switches to JSON response — 44b13ac
- [x] 3.4 `./gradlew build` succeeds — no regression in `LlmExtractionRecordedRegressionTest` — 44b13ac

#### Manual

- [x] 3.5 Local app: upload a real photo of a Polish kindergarten announcement; the upload progress bar fills, then the extraction copy reads *"Wyciągam wydarzenia ze zdjęcia… 5 s"*, ticking; on DONE the browser lands on the stub review page (still placeholder in this phase) with the right `imageId` in the URL — 44b13ac
- [x] 3.6 DB: `select * from proposed_event where source_image_id = ?` shows the extracted rows with `status='PENDING'` — 44b13ac
- [x] 3.7 Force a timeout by lowering `spring.ai.openai.timeout=5s` locally; trigger an upload; status endpoint shows `FAILED`, `errorKind=TIMEOUT`, `correlationId` present; restore the property to `55s` after — 44b13ac
- [x] 3.8 `fly logs` (or local `bootRun`) shows the structured `extraction_failed` line on the forced timeout — 44b13ac
- [x] 3.9 Wait 6 minutes after a successful extraction, hit the status URL — receives `404` (TTL sweep works) — 44b13ac

### Phase 4a: Review happy path — proposals present, decisions POST, transactional promotion

#### Automated

- [x] 4a.1 `./gradlew test --tests com.example.app.event.EventReviewServiceTest` passes including idempotent re-submit — baf23e3
- [x] 4a.2 `./gradlew test --tests com.example.app.event.EventReviewControllerTest` passes including `postWithInvalidDateOnRejectRowIsAccepted` and `getAfterDecisionsRendersOnlyPendingRows` — baf23e3
- [x] 4a.3 `./gradlew test` — full suite green; `EventRepositoryTest` and `CalendarControllerTest` guardrail tests still pass — baf23e3
- [x] 4a.4 `./gradlew build` succeeds — baf23e3

#### Manual

- [x] 4a.5 Local app: upload a real announcement → review page renders one row per extracted event → edit a date → accept some, reject others → Submit → land on `/app` with the Polish flash → the accepted events show in the personal view list — baf23e3
- [x] 4a.6 `select * from app_event where user_id = ?` shows the promoted events with all fields intact — baf23e3
- [x] 4a.7 `select status, decided_at from proposed_event where source_image_id = ?` shows ACCEPTED + REJECTED rows; no PENDING left — baf23e3
- [x] 4a.8 `select resolved_at from source_image where id = ?` is non-null — baf23e3
- [x] 4a.9 `GET /calendar/{token}.ics` returns an `.ics` with the new accepted events; no PENDING/REJECTED proposals appear — baf23e3

### Phase 4b: Error / empty / retry states

#### Automated

- [x] 4b.1 `./gradlew test --tests com.example.app.event.EventReviewControllerTest` passes including the five new branch tests (error, running, empty, retry happy, retry cross-user) — fe845cf
- [x] 4b.2 `./gradlew test` — full suite green — fe845cf
- [x] 4b.3 `./gradlew build` succeeds — fe845cf

#### Manual

- [x] 4b.4 Force a timeout (lower `spring.ai.openai.timeout=3s`, upload a real photo) → land on `review-error.html` → click Retry → the polling JS reruns; if the model now responds in time the page navigates to the happy-path review — fe845cf
- [x] 4b.5 During a real extraction (~20–30 s window), open the review URL directly in a second tab → `review-running.html` renders with the meta-refresh; after ~3 s the page reloads and (once extraction lands) routes to the happy/empty/error template — fe845cf
- [x] 4b.6 Local app: upload a photo of a completely irrelevant image (e.g. a landscape photo, no announcement) → if the model returns `[]`, see the empty-state page with both CTAs — fe845cf
- [x] 4b.7 Force `MALFORMED_RESPONSE` (temporarily break the parse step locally) → review-error template shows the generic copy + correlation ID — fe845cf
- [x] 4b.8 `fly logs | grep correlationId-from-page` finds the structured `extraction_failed` line — triage path works end-to-end — fe845cf

### Phase 5: E2E manual verification + harness re-run + cookbook backfill

#### Automated

- [x] 5.1 `./gradlew test` — full suite green one final time
- [x] 5.2 `./gradlew test --tests com.example.app.llm.LlmExtractionRecordedRegressionTest` — per-field accuracy unchanged
- [x] 5.3 `./gradlew build` succeeds

#### Manual

- [x] 5.4 2–3 real announcement photos walked successfully: at least 1 produces correct date+title+requirements on first extraction (the ≥80% bet at this fixture-set scale would need >5 trials, but the unit-of-1 walk catches regressions)
- [x] 5.5 Subscribed calendar shows the new event(s) after the client polls (Apple Calendar / Outlook / Thunderbird preferred to also confirm the reminder lands)
- [x] 5.6 Peak heap-used during a real ~12 MB extraction sampled via `jcmd <pid> GC.heap_info` and recorded in §6 cookbook (one-off baseline for the documented ~50 MB per-request floor)
- [x] 5.7 Upload a 20+ MB JPEG → response is HTTP 413 with `code: "file.tooLarge"` envelope; Polish copy renders inline under `#upload-error`; no page reload, no flash redirect
- [x] 5.8 Upload a `.txt` (or any non-image MIME) → response is HTTP 422 with `code: "file.wrongType"` envelope; Polish copy renders inline under `#upload-error` via the same DOM hook (uniform error envelope across size and validation paths)
- [x] 5.9 `test-plan.md` §6 cookbook entry written and reads cleanly to a developer who has not seen this slice
- [x] 5.10 `change.md` flipped to `status: done`
