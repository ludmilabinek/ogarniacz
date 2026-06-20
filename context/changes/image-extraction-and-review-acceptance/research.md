---
date: 2026-06-16T12:00:00+02:00
researcher: Ludmiła Drzewiecka
git_commit: c6b120d466bde24eed52e7c55ebd2ee43d9245be
branch: main
repository: ogarniacz
topic: "S-05 — Image extraction + review/acceptance (north star): existing extraction subsystem, Event pipeline, UI patterns, and the three planning unknowns (progress UX, image storage, proposed-event persistence)"
tags: [research, codebase, s-05, image-extraction, llm-vision, proposed-events, review-acceptance, north-star]
status: complete
last_updated: 2026-06-16
last_updated_by: Ludmiła Drzewiecka
---

# Research: Image extraction + review/acceptance (S-05, north star)

**Date**: 2026-06-16T12:00:00+02:00
**Researcher**: Ludmiła Drzewiecka
**Git Commit**: c6b120d466bde24eed52e7c55ebd2ee43d9245be
**Branch**: main
**Repository**: ogarniacz

## Research Question

S-05 (`image-extraction-and-review-acceptance`) is the north-star slice from `context/foundation/roadmap.md`: parent uploads an image of a kindergarten announcement, sees AI-proposed events with editable fields, accepts or rejects each individually, and accepted ones flow into the existing personal view + iCalendar feed within 60 s.

Prerequisites F-01 (OpenRouter vision-LLM client) and S-02 (manual event entry) are already shipped and archived; S-04 (iCalendar feed) is shipped as of 2026-06-16. The research goal is to surface, in one place, what already exists, what S-05 must add, and three explicit planning unknowns named in the roadmap:

1. Frontend pattern for "continuous visible progress" during the 30–60 s extraction wait.
2. Where uploaded source images live between upload and S-06's auto-purge.
3. (Implicit, derived from PRD partial-resolution semantics) Where "proposed events" live between extraction and accept/reject.

## Summary

**What already works.** F-01 shipped more than a smoke test: the codebase has a fully functional `LlmVisionClient` interface (`extract(byte[], MimeType) → LlmExtractionResult`) implemented against OpenRouter via Spring AI 2.0.0-M6, a 10-fixture recorded-regression harness, a divergence catalog tracking known per-field deltas, and a typed exception model (`TIMEOUT` / `PROVIDER_ERROR` / `MALFORMED_RESPONSE`) that maps cleanly to PRD FR-005's "actionable error" requirement. **Crucially, no production code calls `LlmVisionClient` today** — it is wired into Spring AI's `ChatClient` autoconfig but only exercised by tests. S-05 wires it up for the first time.

**What S-05 must add.** A two-stage flow on top of the existing Spring MVC + Thymeleaf + per-controller-test conventions: (a) image upload → call `LlmVisionClient.extract()` → persist N proposed events; (b) review page → per-event accept/reject + inline edit → accepted ones promoted into the existing `Event` table that S-02 built and S-04 reads.

**The three unknowns resolve cleanly toward a single low-complexity combination** when measured against PRD guardrails, the 3-week solo MVP budget, and the load-bearing "AI proposes, human decides" non-negotiable:

- **Progress UX:** AJAX submit + status polling on a separate endpoint. Releases the servlet thread, doesn't touch the existing blocking LLM client, no Reactor/SSE/JS-event-loop novelty introduced. The existing `chatClient.prompt()...call().content()` shape is *fully blocking* and cannot be turned into a streamed response without a parallel `streamExtract()` method — out of scope for MVP.
- **Source image storage:** `bytea` in Neon Postgres, owned by a new `source_image` row. The PRD §Persistence guardrail explicitly excludes ephemeral/in-memory stores. S-06's purge contract becomes one transactional `DELETE` keyed on resolved-state. Fly's Machine filesystem is ephemeral by default and `auto_stop_machines=stop` would wipe the disk mid-review.
- **Proposed-event persistence:** a separate `proposed_event` JPA entity (NOT a `status` column on `Event`) with FK to `source_image`. Promoting an accepted proposal copies its fields into a new `Event` row. The separation locks the calendar guardrail — `findUpcomingByUser` reads from `app_event` only, structurally unable to leak a proposal into the feed. PRD partial-resolution semantics (parent accepts some, closes browser, returns later) require durability; session-bound and in-memory options are disqualified.

**Risks named for planning.** (1) The async progress-status registry is in-memory; a mid-extraction restart strands the polling browser. Cheap mitigation: TTL + "extraction expired, retry" 404 path. (2) Every code path that emits to the iCalendar feed must continue to read from `app_event`. Lock with an integration test that asserts a `PENDING` proposal never appears in `findUpcomingByUser` output, mirroring the cross-user partition test from S-01. (3) The current OpenRouter client has a hard 55 s read timeout (`application.properties:36`) and `max-retries=0` — at the upper edge of PRD's 60 s ceiling. If the model is slow, S-05 sees `LlmExtractionException.TIMEOUT` more often than the harness suggests; the review page's error path must surface this without losing the upload.

## Detailed Findings

### 1. LLM extraction subsystem (already shipped by F-01 + follow-ons)

The subsystem lives at `src/main/java/com/example/app/llm/`. The thin interface S-05 will call:

- `LlmVisionClient.java` — single-method seam: `LlmExtractionResult extract(byte[] image, MimeType mimeType) throws LlmExtractionException`. The Javadoc names callers' responsibility: image downscaling and boundary validation happen at the call site, not in the client. **S-05 is the first call site.**

- `OpenRouterLlmVisionClient.java` — `@Component` implementation:
  - Builds the prompt from a literal `SYSTEM_TEMPLATE` (~25 lines, with `{today}` and `{language}=Polish` runtime substitution via `.param()`); user message is a fixed `USER_HINT` + image attached as `ByteArrayResource` via `.media(mimeType, resource)`.
  - Calls `chatClient.prompt()...call().content()` — **fully blocking**.
  - Parses the JSON array out of the response (regex strips any markdown fences / preamble) and Jackson-binds to `List<ProposedEvent>`.
  - Translates SDK exceptions into `LlmExtractionException` of the right `Kind`.
  - Configuration lives in `application.properties:28-40`: `base-url=https://openrouter.ai/api/v1`, `model=google/gemini-2.5-flash`, `timeout=55s`, `max-retries=0`, `temperature=0.2`, plus OpenRouter attribution headers via `spring.ai.openai.custom-headers.*`.

- `LlmExtractionResult.java` — the contract S-05 turns into proposed events:
  ```java
  public record LlmExtractionResult(
      List<ProposedEvent> proposedEvents,
      String rawResponse,
      long latencyMillis) {

      public record ProposedEvent(
          LocalDate date,           // required, YYYY-MM-DD
          LocalTime time,           // nullable, HH:MM
          String title,             // required
          String requirements,      // nullable
          String notes              // nullable
      ) {}
  }
  ```
  **Schema matches `EventForm` exactly** (date, optional time, title, requirements, notes), so no field-shape adaptation is needed when promoting an accepted proposal to an `Event`.

- `LlmExtractionException.java` — three-Kind taxonomy:
  - `TIMEOUT` — I/O read timeout (PRD: surface as "request took too long, try again")
  - `PROVIDER_ERROR(httpStatus, providerMessage)` — 4xx/5xx from OpenRouter (PRD: actionable error with provider's message)
  - `MALFORMED_RESPONSE` — Jackson parse failure (PRD: "could not read this image — try a better photo or add manually", per FR-005's "unreadable" binary state)

- Regression harness (test-only): `LlmExtractionRecordedRegressionTest` / `LlmExtractionLiveRegressionTest` / `LlmTestFixtures` / `LlmDivergenceCatalog`, with 10 fixture folders under `src/test/resources/llm/fixtures/` (e.g. `01-sample/`, `06-luty-wazne-daty/`). Each fixture has `image.png`, `expected.json`, `recorded-response.json`, `recorded-meta.json`. `LlmTestFixtures.diff()` grades only `date`, `time`, `requirements` per the lesson "Grade only what a user would call wrong" — `title` and `notes` are excluded from the regression metric.

- In-flight: `context/changes/llm-chatclient-fail-fast/change.md` (status: open) — an impl-review follow-up to make `AppApplication.chatClient(...)` fail fast instead of returning `null` when `ChatClient.Builder` is absent. Independent of S-05's logic; could land before, after, or in parallel.

### 2. Event persistence + iCalendar feed pipeline (already shipped by S-02 + S-04)

The pipeline that S-05 plugs into lives at `src/main/java/com/example/app/event/`:

- `Event.java` — JPA `@Entity`, `@Table(name="app_event")` with composite index `(user_id, event_date)`. Fields: `UUID id` (`@GeneratedValue`), `@ManyToOne(LAZY) AppUser user` (FK, non-null), `LocalDate eventDate` (non-null), `LocalTime eventTime` (nullable — all-day if null), `String title` (non-null, ≤200), `String requirements` (nullable, ≤2000), `String notes` (nullable, ≤2000), `Instant createdAt` (set in `@PrePersist`). No `status` column today.

- `EventForm.java` — `@NotNull @DateTimeFormat eventDate`, optional `LocalTime eventTime`, `@NotBlank @Size(max=200) title`, `@Size(max=2000) requirements`, `@Size(max=2000) notes`. **Field-shape parity with `LlmExtractionResult.ProposedEvent`** — confirmed in the synthesis above.

- `EventRepository.java` — single user-scoped query:
  ```java
  @Query("""
      select e from Event e
      where e.user = :user
        and e.eventDate >= :today
      order by e.eventDate asc, e.eventTime asc nulls last
      """)
  List<Event> findUpcomingByUser(@Param("user") AppUser user, @Param("today") LocalDate today);
  ```
  Negative test in `EventRepositoryTest.findUpcomingByUserExcludesOtherUsersEvents` proves cross-user isolation.

- `EventController.java` — `GET /events/new` renders form; `POST /events` validates, resolves `AppUser` from `Authentication.getName()`, constructs `Event`, `eventRepository.save(...)`, `redirect:/app`. No service layer.

- `AppController.java` (`src/main/java/com/example/app/web/`) — `GET /app` renders the personal view from `eventRepository.findUpcomingByUser(user, today)`.

- `CalendarController.java` — `GET /calendar/{token}.ics`, token-gated anonymous endpoint; resolves `AppUser` by `icalToken`, calls `IcalFeedWriter.write(user, events)` with the same `findUpcomingByUser` query. `Cache-Control: private, no-cache`. 404 on unknown token.

- `IcalFeedWriter.java` — ical4j-based VEVENT serializer. All-day vs timed event handled by branching on `eventTime == null`. Each VEVENT carries `UID = {event.id}@{UID_HOST}`, `SEQUENCE=0`, `STATUS=CONFIRMED`, optional `DESCRIPTION` (merge of `requirements\n\nnotes`), and a `VALARM` from `EventReminder`.

- `EventReminder.java` + `AppEventProperties.java` — reminder computed at feed-write time: `event.getEventDate().minusDays(1).atTime(reminder.hour, 0).atZone(timezone)`. Defaults: `Europe/Warsaw`, `hour=8`. Not persisted per-event.

- `IcalSubscriptionService.java` / `SettingsController.java` (under `user/`) + `IcalTokenGenerator.java` — produce the unguessable token surfaced on `settings.html`.

**Synthesis answer (where "Accept" hooks in).** The promotion is `new Event(user, proposed.date, proposed.time, proposed.title, proposed.requirements, proposed.notes)` followed by `eventRepository.save(event)` — exactly the shape of `EventController.create`. A thin service (e.g. `EventReviewService`) is the natural seam for the accept/reject loop; direct repository calls also fit the codebase style.

**Calendar guardrail.** The feed reads from `app_event` only. As long as proposed events live in a separate table (Section 4 below), no pending proposal can leak.

### 3. UI / controller / template conventions

The codebase has a small, consistent Thymeleaf + Spring MVC pattern:

- **Form-binding canonical example**: `EventController.java:33-53` — `@Valid @ModelAttribute("eventForm") EventForm form, BindingResult result, Authentication auth`. On `result.hasErrors()`, return the same view name (no flash attributes anywhere); on success, `redirect:/app`. Error display in templates: `th:if="${#fields.hasErrors('field')}"` + `th:errors="*{field}"`.
- **Layout & fragments**: `templates/fragments/layout.html` exposes two named fragments only — `head(title)` (sets `<meta>`, `<title>`, inlines all global CSS in one `<style>` block) and `topbar` (signed-in topbar with email, Settings link, logout form). Pages use `<head th:replace="~{fragments/layout :: head('Page Title')}">` and `<header th:replace="~{fragments/layout :: topbar}">`. Three CSS hook classes on `<main>`: `auth-page`, `app-page`, `settings-page`.
- **CSRF**: enabled by default — `SecurityConfig.java:67-95` does **not** call `.csrf(c -> c.disable())`. Thymeleaf auto-injects `_csrf` for any `<form method="post" th:action="@{...}">`.
- **SecurityConfig endpoints**: `permitAll` on `GET /calendar/*.ics`, `/`, `/signup`, `/login`, `/actuator/health`, `/css/**`, `/js/**`, `/favicon.ico`, `/error`. Everything else → `.anyRequest().authenticated()`. **Any new POST under `/events/*` or `/upload` falls through to authenticated** with no config change.
- **Multipart**: **not configured today.** No `spring.servlet.multipart.*` keys in `application.properties`; no controller takes `MultipartFile`. Spring Boot's defaults are `max-file-size=1MB` / `max-request-size=10MB` — too tight for phone photos per the `LlmVisionClient.java` Javadoc (typical 2–5 MB). S-05 must raise both limits and add a `@ControllerAdvice` for `MaxUploadSizeExceededException` (it fires before the handler runs, so `BindingResult` won't carry it).

**Synthesis (smallest UI shape mirroring the manual-entry triplet):**
- `event/ImageUploadController.java` — `GET /events/from-image` (upload form), `POST /events/from-image` (consume `MultipartFile`, kick off extraction, return jobId), `POST /events/from-image/review` (accept/reject batch).
- `event/ImageUploadForm.java` — `@NotNull MultipartFile file` plus controller-level MIME/size check (Bean Validation has no built-in `MultipartFile` validator).
- `event/EventReviewForm.java` — `List<ProposedEventDecision>` where each item carries `boolean accept` + the editable fields. This is the new piece with no direct analogue in the manual flow.
- `templates/events/from-image.html` — mirrors `events/new.html` (`head('From image')`, `topbar`, `auth-page` class), `<form ... enctype="multipart/form-data">`.
- `templates/events/review.html` — stacked list of proposed events with per-row accept checkbox + editable fields, single submit.

### 4. Spring AI 2.0.0-M6 multimodal (external docs)

Context7 (`/spring-projects/spring-ai/v2.0.0-m6`) confirms:
- Multimodal user message: `.user(u -> u.text("...").media(MimeType, Resource))`. `Media` constructor is `(MimeType, Resource)` in 2.0 — **not** `(MimeType, URL)` as in 1.x. `MultipartFile` is bridged via `ByteArrayResource` / `InputStreamResource`.
- Structured output options:
  - High-level: `.call().entity(Class)` or `.call().entity(new ParameterizedTypeReference<List<T>>(){})` — wraps `BeanOutputConverter` and injects the `{format}` instruction automatically.
  - Low-level: `new BeanOutputConverter<>(typeRef)` + `.param("format", converter.getFormat())` + manual `converter.convert(rawString)`. Required when streaming or when you want to keep custom prompt control.
- Streaming: `chatClient.prompt()...stream().content()` returns `Flux<String>`. Composes with WebFlux naturally; in Spring MVC, requires bridging to `SseEmitter`/`ResponseBodyEmitter` and is incompatible with the project's blocking JSON-parsing strategy (you can't `readValue` a JSON array mid-stream).
- OpenAI starter base-url override: `spring.ai.openai.base-url` + `spring.ai.openai.api-key` + `spring.ai.openai.chat.options.model`, with `spring.ai.openai.custom-headers.*` for provider-specific headers — **already correctly wired for OpenRouter** in `application.properties`.

**Recommendation:** Do **not** touch `OpenRouterLlmVisionClient`. The hand-rolled JSON regex + Jackson + typed exception path is robust and tested. Switching to `.entity()` would simplify the happy path but lose the regex preamble-strip and the structured error taxonomy.

### 5. Continuous-progress UX (Unknown #1) — trade-offs

| Option | Pros | Cons | Cost (3-wk MVP) |
|---|---|---|---|
| A. Sync submit + spinner | Zero infra; mirrors `EventController`. | Servlet thread pinned 30–60 s per upload. Browser-level "page unresponsive" risk on mobile. Arguably violates PRD's "continuous visible feedback" spirit. | ~0.5 day |
| **B. AJAX + polling on status endpoint (recommended)** | Releases servlet thread immediately; works behind every proxy; auto-stop-friendly (in-flight request counts as activity); easy "elapsed 23 s" copy. | Requires `@EnableAsync` + an in-memory `Map<UUID, JobStatus>`. Two race conditions to handle: polling before async start, GC policy after `DONE`. | ~1–1.5 days |
| C. SSE via `SseEmitter` | True progress signal; native Spring MVC; releases servlet thread. | `EventSource` only does `GET`, so upload still needs the in-memory registry. Front-end gains an `EventSource` lifecycle. No precedent in this codebase. | ~1.5–2 days |
| D. Streamed LLM via Spring AI `stream()` | Real token-throughput signal. | **Does not compose with the current blocking client.** Requires parallel `streamExtract()` method + Reactor bridging into MVC + reworked parsing. Highest novelty + risk. | ~2.5–3 days |

The existing `OpenRouterLlmVisionClient.extract()` is fully blocking; only Option D forces touching it. B and C keep it intact and run it on an async executor.

### 6. Source image storage (Unknown #2) — trade-offs

| Option | Pros | Cons | Disposition |
|---|---|---|---|
| A. Ephemeral Machine disk (`/tmp/uploads/...`) | Trivial. | Fly wipes the root FS on every restart; `auto_stop_machines=stop` triggers after ~5 min idle. Mid-review restart leaves DB rows pointing at missing files. | **Disqualified** — silently violates the retention guarantee in both directions. |
| B. Fly `[mounts]` volume | Survives restarts. Fast local IO. | Pins Machine to one VM; new operational dimension (sizing, alerts); S-06 must coordinate two stores; manual recovery on volume detach is solo-hostile. | Viable but heavyweight. |
| **C. `bytea` in Neon Postgres (recommended)** | Single source of truth; S-06 purge = one transactional `DELETE`; survives every restart/deploy/region failover; covered by Neon backups; aligns with PRD §Persistence guardrail; reads cleanly into Spring AI's `ByteArrayResource`. | Slight WAL bloat (immaterial at single-user scale); some teams reflexively dislike DB BLOBs (bias doesn't apply at MVP scale). | **Recommended.** |
| D. External object storage (Tigris / S3) | Industry pattern; scales. | New SDK, secret, account, lifecycle policy, failure mode. Out of proportion at MVP scale. | Yak-shaving. |
| E. In-process memory only | Zero storage cost. | Excluded by PRD §Persistence; collides with the "image outlives a single review pass" implication of the partial-resolution semantics. | **Disqualified.** |

S-06 friendliness: C ≫ B > D ≫ A, E.

### 7. Proposed-event persistence (Unknown #3) — trade-offs

Multi-hour pause + multi-row data + 1:1 relationship to source image. PRD's partial-resolution semantics (some accepted, some never resolved) require durability across logout / browser close / Machine restart.

| Option | Pros | Cons | Disposition |
|---|---|---|---|
| A. HTTP session attribute | Zero schema change. | Lost on logout / session timeout / pod restart. Violates partial-resolution semantics. `HttpSessionMutex` serializes async dispatch — interacts poorly with B/C from Unknown #1. | **Wrong shape.** |
| B. URL/query-string encoded | Stateless server. | Large encoded payload; mutating one event re-submits all; ergonomically wrong. | **Wrong shape.** |
| C. In-memory `Map<UUID, ProposedEventList>` | Survives logout. | Lost on Machine restart; excluded by PRD §Persistence. | **Disqualified.** |
| **D. DB-backed `ProposedEvent` entity (recommended)** | Survives everything; partition free via FK; image purge keys cleanly off `WHERE source_image_id=? AND status='PENDING'` count = 0; matches FR-004 schema. | Additive migration (fits `ddl-auto=update` + additive-only policy from `deploy-plan.md`). | **Recommended.** |

**Two sub-shapes under D:**
1. *Same table, add `status` column to `Event`.* Single source of truth. But every existing `findUpcomingByUser` query now needs `AND status='ACCEPTED'`; forgetting it once = a proposal leaks into the feed = the load-bearing "AI proposes, human decides" guardrail breaks. Highest possible regression cost.
2. *Separate `proposed_event` table; on accept, promote/copy into `app_event`.* `app_event` remains "accepted only" by construction. S-02 / S-04 queries unchanged. Cost: one entity + one repo + a 5-line promotion service method. **Strongly safer.**

### 8. Recommended combination (synthesis)

> **Progress UX: Option B (AJAX + status polling).** **Image storage: Option C (`bytea` in Neon).** **Proposed-event persistence: Option D, separate `proposed_event` table promoted to `app_event` on accept.**

The shape:
1. Parent posts to `POST /events/from-image` (multipart, `@RequestPart MultipartFile`).
2. Controller stores image as `bytea` in a new `source_image` row, kicks off `@Async extractor.run(imageId)` (which calls the existing blocking `OpenRouterLlmVisionClient.extract()` unchanged), returns `{jobId, statusUrl}`.
3. Browser polls `GET /events/from-image/status/{jobId}` every ~1.5 s; renders elapsed-time copy.
4. Async task on completion: inserts N `proposed_event` rows linked to `source_image`, writes `DONE → reviewUrl=/events/from-image/{imageId}/review` into the job map.
5. Browser navigates to `GET /events/from-image/{imageId}/review` — a normal Thymeleaf page rendering pending proposals.
6. `POST /events/from-image/{imageId}/review` accepts/rejects batch: accept = promote to `app_event` + mark `ACCEPTED`; reject = mark `REJECTED`. Redirect to `/app`.
7. S-06's purge contract (future): `DELETE FROM source_image WHERE id NOT IN (SELECT DISTINCT source_image_id FROM proposed_event WHERE status='PENDING')`.

Biggest risks called out for `/10x-plan`:
- **Job-registry restart fragility.** In-memory `jobId → status` strands polling browsers on Fly restart. Mitigation: TTL + `404 → "extraction expired, retry"`.
- **Guardrail regression risk.** Every code path emitting to the feed must read from `app_event`. Lock with an integration test asserting `PENDING` proposals never appear in `findUpcomingByUser` output (mirrors S-01's cross-user partition test pattern).
- **Timeout headroom.** Existing `OpenRouterLlmVisionClient` is at 55 s read timeout + `max-retries=0` with PRD ceiling at 60 s. The review path must surface `LlmExtractionException.TIMEOUT` without losing the upload — likely keep the `source_image` row and offer a "retry extraction" action.

## Code References

- `src/main/java/com/example/app/llm/LlmVisionClient.java` — `extract(byte[], MimeType) → LlmExtractionResult` interface; the seam S-05 will call for the first time.
- `src/main/java/com/example/app/llm/OpenRouterLlmVisionClient.java:77-93` — Spring AI multimodal call shape (`ByteArrayResource` + `.media(MimeType, resource)`) and blocking `call().content()` + Jackson parse.
- `src/main/java/com/example/app/llm/OpenRouterLlmVisionClient.java:98-121` — SDK exception → `LlmExtractionException` mapping (TIMEOUT / PROVIDER_ERROR / MALFORMED_RESPONSE).
- `src/main/java/com/example/app/llm/LlmExtractionResult.java` — `ProposedEvent(date, time, title, requirements, notes)` record; field-shape parity with `EventForm`.
- `src/main/java/com/example/app/llm/LlmExtractionException.java` — typed `Kind` taxonomy; spine of PRD FR-005 error UX.
- `src/main/java/com/example/app/event/Event.java` — `@Entity` `app_event`; the table S-05 writes to on accept.
- `src/main/java/com/example/app/event/EventRepository.java` — `findUpcomingByUser(user, today)` — the only feed/personal-view read path; must continue to read from `app_event` only.
- `src/main/java/com/example/app/event/EventForm.java:11-29` — canonical validated form POJO; structurally identical to `ProposedEvent`.
- `src/main/java/com/example/app/event/EventController.java:33-53` — canonical `@Valid @ModelAttribute + BindingResult + redirect:/app` pattern; the analogue for S-05's controllers.
- `src/main/java/com/example/app/event/CalendarController.java` — `GET /calendar/{token}.ics`; reads `findUpcomingByUser` — the path the guardrail test must lock.
- `src/main/java/com/example/app/event/IcalFeedWriter.java` — VEVENT/VALARM serialization; unchanged by S-05 (works on `Event`).
- `src/main/java/com/example/app/event/EventReminder.java` — `eventDate.minusDays(1).atTime(reminder.hour, 0)`; reminder is computed at feed-write time, not persisted.
- `src/main/java/com/example/app/event/AppEventProperties.java` — `app.timezone`, `app.event.reminder.hour`.
- `src/main/java/com/example/app/web/AppController.java` — `GET /app` personal view; reads `findUpcomingByUser`.
- `src/main/java/com/example/app/config/SecurityConfig.java:67-95` — CSRF enabled by default; `anyRequest().authenticated()` covers any new POST under `/events/*`.
- `src/main/resources/application.properties:28-40` — Spring AI / OpenRouter wiring (correct as-is); **no `spring.servlet.multipart.*` keys today.**
- `src/main/resources/templates/fragments/layout.html` — only `head(title)` and `topbar` fragments; replace via `th:replace="~{fragments/layout :: head('From image')}"`.
- `src/main/resources/templates/events/new.html` — form template to mirror for `events/from-image.html`.
- `src/test/java/com/example/app/event/EventRepositoryTest.java` — `findUpcomingByUserExcludesOtherUsersEvents`; pattern to mirror for the "proposals never leak to feed" guardrail test.
- `src/test/java/com/example/app/llm/LlmTestFixtures.java` — `diff()` grades `date`, `time`, `requirements` only (per the "grade only what a user would call wrong" lesson).
- `src/test/java/com/example/app/llm/LlmDivergenceCatalog.java` — known per-fixture deltas; carries the accuracy baseline.
- `build.gradle:21-37` — Spring AI BOM `2.0.0-M6`, OpenAI starter, ical4j, Thymeleaf, security, validation, webmvc; H2 only as `testRuntimeOnly`.

## Architecture Insights

- **No service layer convention.** Controllers call repositories directly (`EventController` → `EventRepository.save`). A thin `EventReviewService` is the natural seam for the multi-step accept/promote flow, but not strictly required.
- **Per-controller `@SpringBootTest` test layout** is the standard (see lessons.md). S-05 will add `ImageUploadControllerTest`; `AppApplicationTests` stays a `contextLoads()` smoke only.
- **Additive-only migrations** (per `deploy-plan.md` §5.2 referenced from `roadmap.md`) — new `source_image` and `proposed_event` tables are additive; safe with `ddl-auto=update`.
- **Single Machine, single replica** (Fly `fra`, 1 GB) — in-memory state for transient registries (job status map) is acceptable when guarded by TTL + 404 fallback; no cross-replica coordination needed.
- **CSRF is on; no flash attributes.** S-05 follows the same `<form method="post" th:action="@{...}">` convention; no special handling needed for the multipart form (Thymeleaf still injects the CSRF token alongside `enctype="multipart/form-data"`).
- **PRD non-goal: no clipboard-paste, no PDF, no streaming.** S-05's upload is file-picker-only (`<input type="file" accept="image/*" capture="environment">` for mobile camera capture is acceptable; the camera-via-browser path is what the PRD names).

## Historical Context (from prior changes)

- `context/archive/2026-06-01-openrouter-llm-client-wired/plan.md` — F-01's plan; established the `LlmVisionClient` + `OpenRouterLlmVisionClient` + `LlmExtractionResult` + `LlmExtractionException` quartet and the 55 s timeout / 0 retries configuration. S-05 inherits those decisions; renegotiating them is out of scope here.
- `context/archive/2026-06-07-manual-event-entry/plan.md` — S-02's plan; locked the `Event` entity shape against FR-004 (date / optional time / title / requirements / notes), the `EventForm` validation, and the `EventController` redirect pattern. S-05 reuses every one of those decisions.
- `context/archive/2026-06-15-icalendar-feed-and-subscription/plan.md` — S-04's plan and `research.md` (single artifact present in that change folder); established the `CalendarController` + `IcalFeedWriter` + token-gated anon endpoint. The "feed reads from `app_event` only" property comes from this slice and is the guardrail the proposed-event-table-separation choice locks.
- `context/archive/2026-06-10-llm-extraction-regression-harness/` + `2026-06-12-llm-fixture-set-expansion/` + `2026-06-12-llm-prompt-year-resolution/` + `2026-06-13-llm-diff-title-tier/` — the regression-harness lineage. Bottom line for S-05: the harness exists, the divergence catalog is current, and the ≥80 % accuracy hypothesis (FR-004 / north-star claim) has a measurement surface ready. The slice's empirical question — *"is the F-01 default model ≥80 % on real announcements?"* — can be answered by running `LlmExtractionRecordedRegressionTest` against the current 10-fixture set rather than designing a new measurement.
- `context/changes/llm-chatclient-fail-fast/change.md` (open, in-flight) — `AppApplication.chatClient(...)` currently returns `null` instead of failing fast when no `ChatClient.Builder` bean is present. Independent of S-05's logic; landing order does not matter, but if it lands first, S-05's startup story is cleaner.

## Related Research

- `context/archive/2026-06-15-icalendar-feed-and-subscription/research.md` — feed contract groundwork; complements the "feed reads `app_event` only" property cited above.
- (F-01's archive does not include a `research.md`; the planning lived in `plan-brief.md` + `plan.md`.)
- (S-02's archive does not include a `research.md`.)

## Open Questions

1. **Job-status registry shape.** In-memory `ConcurrentHashMap<UUID, JobStatus>` with a 5-min TTL is the recommended baseline. Is there a future case (mid-extraction Machine restart, deploy mid-call) where a parent expects the job to survive that beats the cost of a `job_status` DB column? Surface in `/10x-plan` if it changes the entity count.
2. **Async executor configuration.** Default Spring `@Async` thread pool vs an explicit `TaskExecutor` bean with bounded pool size (e.g. 2 concurrent extractions, queue beyond that). At single-user MVP scale either works; explicit naming will help when the second parent comes on board.
3. **Upload size cap.** Phone photos are 2–5 MB typically per `LlmVisionClient.java` Javadoc, but a panorama can push 12 MB. Recommended limits: `max-file-size=15MB`, `max-request-size=20MB`. Should the upload form pre-validate client-side (HTML `accept="image/*"` + JS size guard) or only server-side? Plan-time call.
4. **Image MIME whitelist.** PRD says "photo or screenshot"; in practice JPEG / PNG / WEBP / HEIC. Spring AI's `MimeType` accepts arbitrary strings; OpenRouter / the chosen model defines what it actually accepts. List should be derived from the model's documented support, not from a guess — F-01's smoke-test history may already have the answer.
5. **Retry UX.** When `LlmExtractionException.TIMEOUT` fires, the `source_image` row exists but no `proposed_event` rows. Surface "extraction failed, retry" or auto-retry once? The latter would mean introducing `max-retries=1` at the Spring AI level, which currently is `0` per `application.properties:37`. Plan-time call; affects the typed exception → UX mapping.
6. **Empty extraction result.** If the model returns `[]` (valid JSON, zero events) for a readable but irrelevant image, that's not `MALFORMED_RESPONSE` but it's also not actionable. PRD FR-005 implies "could not read" — but "read but found no events" is a third state the taxonomy doesn't capture today. Plan-time call: surface as success-with-empty (offer manual-entry path) or as a synthetic error.
