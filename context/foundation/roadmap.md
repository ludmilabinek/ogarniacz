---
project: Ogarniacz
version: 1
status: draft
created: 2026-05-25
updated: 2026-06-16
prd_version: 1
main_goal: speed
top_blocker: time
---

# Roadmap: Ogarniacz

> Derived from `context/foundation/prd.md` (v1) + auto-researched codebase baseline.
> Edit-in-place; archive when superseded.
> Slices below are listed in dependency order. The "At a glance" table is the index.

## Vision recap

A parent of a kindergarten-aged child receives obligations through unstructured channels (corridor notes, parent chats, kindergarten apps) and must hand-translate each one into a calendar event. Ogarniacz moves that extraction work onto the application: a photo or screenshot becomes a proposed list of editable events that the parent reviews and accepts, then appears in the parent's existing digital calendar via a one-time iCalendar subscription.

The **product wedge** — the one trait that, if removed, makes the product indistinguishable from generic note-taking apps and digital calendars — is the AI-assisted unstructured → structured translation that ends in the parent's already-trusted calendar without manual rewriting. Everything in this roadmap is sequenced around proving that wedge works on real announcements.

## North star

**S-05: Parent uploads an image of a real kindergarten announcement; AI-proposed events are reviewed, accepted, and appear in the parent's subscribed calendar via the existing iCalendar feed.** — This is the validation milestone (the smallest end-to-end slice whose successful delivery would prove the core product hypothesis from PRD §Vision; everything else only matters if this works). It is sequenced **after** S-04 so that on the day S-05 lands, the very next calendar poll surfaces the AI-extracted event in the parent's calendar — closing the full bridge from photo to calendar in one ship.

## At a glance

| ID    | Issue | Change ID                                    | Outcome (user can …)                                                                                                            | Prerequisites    | PRD refs                                       | Status   |
| ----- | ----- | -------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------- | ---------------- | ---------------------------------------------- | -------- |
| F-01  | [#1](https://github.com/ludmilabinek/ogarniacz/issues/1) | openrouter-llm-client-wired                  | (foundation) OpenRouter vision-LLM client is callable from the app and returns a structured response on a smoke test            | —                | FR-004, FR-005, NFR (extraction latency)       | done     |
| S-01  | [#2](https://github.com/ludmilabinek/ogarniacz/issues/2) | minimal-auth-and-empty-personal-view         | sign up, log in, log out, and land on an empty personal view scoped to their account                                            | —                | FR-001, FR-009 (partial), US-01 (login), §Access Control, NFR (zero cross-account leakage) | done     |
| S-02  | [#3](https://github.com/ludmilabinek/ogarniacz/issues/3) | manual-event-entry                           | manually create an event with date/time/title/requirements/notes; it appears in their personal view with the default reminder    | S-01             | FR-003, FR-008, FR-009, US-02                  | done     |
| S-04  | [#4](https://github.com/ludmilabinek/ogarniacz/issues/4) | icalendar-feed-and-subscription              | view + copy their unique iCalendar URL from settings; events from the personal view appear in their subscribed calendar         | S-02             | FR-012, FR-013, US-03, NFR (token entropy, feed freshness) | done     |
| S-05  | [#5](https://github.com/ludmilabinek/ogarniacz/issues/5) | image-extraction-and-review-acceptance       | upload an image, see AI-proposed events with editable fields, accept or reject each individually, see accepted ones in calendar | F-01, S-02       | FR-002, FR-004, FR-005, FR-006, FR-007, FR-008, US-01 (full) | proposed |
| S-03  | [#6](https://github.com/ludmilabinek/ogarniacz/issues/6) | edit-delete-accepted-events                  | edit or delete accepted events from the personal view; the change propagates to the iCalendar feed                              | S-02             | FR-010, FR-011, US-01 (lifecycle)              | proposed |
| S-06  | [#7](https://github.com/ludmilabinek/ogarniacz/issues/7) | source-image-auto-purge                      | (background) source images are auto-removed once every proposed event from them has been accepted or rejected                   | S-05             | NFR (image retention)                          | proposed |

## Streams

Navigation aid — groups items that share a Prerequisites chain. Canonical ordering still lives in the dependency graph below; this table is the proposed reading order across parallel tracks.

| Stream | Theme                                  | Chain                                  | Note                                                                                                              |
| ------ | -------------------------------------- | -------------------------------------- | ----------------------------------------------------------------------------------------------------------------- |
| A      | Account + manual-to-calendar path      | `S-01` → `S-02` → `S-04`               | Builds the boring-half bridge that the north star lands on top of: signed-in parent, manual entry, feed delivery. |
| B      | AI extraction wedge                    | `F-01` → `S-05` → `S-06`               | The wedge path; `S-05` joins Stream A at `S-02` (data path) and benefits from `S-04` being live (north-star realization). |
| C      | Accepted event lifecycle               | `S-03`                                 | Standalone slice that joins Stream A at `S-02`; off the critical path to the north star, sequenced after it.      |

## Baseline

What's already in place in the codebase as of 2026-05-25 (auto-researched + user-confirmed). Foundations below assume these are present and do NOT re-scaffold them.

- **Frontend:** absent — `src/main/resources/{static,templates}` exist but empty. No UI framework chosen yet (Thymeleaf vs. HTMX vs. minimal static HTML is `/10x-plan`'s call when S-01 starts).
- **Backend / API:** partial — Spring Boot 4.0.6 + Spring Web MVC + Spring Security + Spring Data JPA + Validation starters on classpath (`build.gradle`); `AppApplication.java` and `SecurityConfig.java` exist. No controllers beyond `/actuator/health` exposure; no REST endpoints.
- **Data:** partial — `spring-boot-starter-data-jpa` + PostgreSQL driver wired; Neon Postgres (`eu-central-1`) connected via `SPRING_DATASOURCE_*` Fly secrets; `ddl-auto=update`; H2 for tests. **No `@Entity` classes yet.** Per `context/deployment/deploy-plan.md` §5.2, migrations are additive-only for MVP.
- **Auth:** partial — `spring-boot-starter-security` on classpath; `SecurityConfig` permits `/actuator/health` and guards everything else with HTTP Basic against the Spring-generated admin password. **No signup/login/logout/session flow, no `User` entity, no password storage** — that's S-01.
- **Deploy / infra:** present — Fly.io (`fra`, 1 GB Machine, `auto_stop_machines=stop`) + Neon Postgres (`eu-central-1`) + GitHub Actions auto-deploy on push to `main`. `Dockerfile`, `.dockerignore`, `fly.toml`, `.github/workflows/deploy.yml` all in place per `context/deployment/deploy-plan.md`. **No foundation re-scaffolds this.**
- **Observability:** partial — `/actuator/health` exposed; UptimeRobot polls it (placeholder URL until S-04's feed ships, at which point monitor target switches to the feed per deploy-plan Phase I). No structured logging beyond stdout → `fly logs`; no error tracking; no metrics. PRD does not mandate more for MVP.
- **AI / LLM integration:** absent — `AI_PROVIDER_API_KEY` reserved as a Fly secret with a placeholder value (`pending-provider-selection`). **Provider chosen: OpenRouter** (OpenAI-compatible API → reachable via `spring-ai-openai-spring-boot-starter` pointed at `https://openrouter.ai/api/v1`). Spring AI starter not yet on classpath. That's F-01. Vision model not yet picked — F-01's blocking unknown.

## Foundations

### F-01: OpenRouter LLM client wired

- **Outcome:** (foundation) `spring-ai-openai-spring-boot-starter` is on the classpath, pointed at OpenRouter's OpenAI-compatible base URL, authenticated against the rotated `AI_PROVIDER_API_KEY` Fly secret. A smoke test (manual or `@Test`) sends a sample image to the chosen vision model and receives a parseable response within the PRD's 60-second ceiling.
- **Change ID:** openrouter-llm-client-wired
- **PRD refs:** FR-004, FR-005, NFR (extraction latency 30 s typical / 60 s ceiling)
- **Unlocks:** S-05 (the entire image-extraction slice — the wedge); also reduces the blocking unknown "does the LLM call from Spring AI to OpenRouter return a parseable structured response under our deployed config?" before S-05 commits to UI work on top of it.
- **Prerequisites:** —
- **Parallel with:** S-01 (no shared dependency; can be developed in parallel by two agent runs or two evening sessions)
- **Blockers:** —
- **Unknowns:**
  - Which OpenRouter vision model to default to (`anthropic/claude-3.5-sonnet`, `google/gemini-2.0-flash`, `openai/gpt-4o-mini`, others)? Owner: dev. Block: no — pick a default in F-01; the per-announcement accuracy bet is empirical and gets validated in S-05.
  - Does `spring-ai-openai-spring-boot-starter` accept a non-OpenAI `base-url` cleanly for OpenRouter, or does it require an alternative starter / a manual `ChatClient` bean? Owner: dev. Block: no — discovered and resolved inside F-01.
- **Risk:** Sequenced before S-05 so the wedge slice doesn't conflate "the UI is wrong" with "the LLM call is broken". With OpenRouter being newly chosen and Spring AI's first-class support being for OpenAI proper, validating the integration in isolation removes the most likely source of S-05 debugging time.
- **Status:** done

## Slices

### S-01: Minimal auth + empty personal view

- **Outcome:** parent can sign up with email and password, log in, log out, stay logged in across browser sessions, and land on an empty personal view scoped to their own account.
- **Change ID:** minimal-auth-and-empty-personal-view
- **PRD refs:** FR-001, FR-009 (the empty-state half), US-01 (login precondition), §Access Control (authentication + flat role + per-user partition), NFR (zero cross-account leakage)
- **Prerequisites:** —
- **Parallel with:** F-01
- **Blockers:** —
- **Unknowns:**
  - UI tech for forms + personal view shell (Thymeleaf vs. HTMX vs. static HTML + minimal JS)? Owner: dev (decide in `/10x-plan`). Block: no — implementation detail, not a roadmap call.
- **Risk:** Per-user data partition contract is introduced HERE and inherited by every subsequent slice. If S-01 ships with partition only enforced ad hoc, the NFR "zero cross-account leakage" silently breaks the first time a second user is added. The slice must include at least one negative test (user A cannot read user B's personal view) to lock the contract.
- **Status:** done

### S-02: Manual event entry

- **Outcome:** parent can manually create an event with date, optional time, title, requirements, and notes; on save it appears in the personal view, attributed to their account, with the default morning-of-day-before reminder attached.
- **Change ID:** manual-event-entry
- **PRD refs:** FR-003, FR-008, FR-009 (the populated-state half), US-02
- **Prerequisites:** S-01
- **Parallel with:** F-01 (only after S-01 is done)
- **Blockers:** —
- **Unknowns:** —
- **Risk:** This slice introduces the `Event` entity and the acceptance pipeline that S-05 reuses for AI-extracted events. If the entity shape diverges from what extraction needs (date as string vs. LocalDate, requirements as free text vs. structured list), S-05 pays the migration cost. Lock the shape against PRD FR-004's schema (date, time optional, title, requirements merged, notes) at this slice's design step.
- **Status:** done

### S-04: iCalendar feed + subscription

- **Outcome:** parent can view and copy their unique unguessable iCalendar URL from a settings screen; accepted events from the personal view are served at that URL as a standard iCalendar feed with the morning-of-day-before reminder; events deleted from the personal view no longer appear on the next poll.
- **Change ID:** icalendar-feed-and-subscription
- **PRD refs:** FR-012, FR-013, US-03, NFR (token entropy, feed freshness, deletion propagation)
- **Prerequisites:** S-02
- **Parallel with:** S-03 (both depend only on S-02; they touch different surfaces — feed serialization and lifecycle UI)
- **Blockers:** —
- **Unknowns:**
  - iCalendar library choice (ical4j vs. hand-rolled VCALENDAR generation)? Owner: dev (decide in `/10x-plan`). Block: no.
  - UptimeRobot keyword-check target switches from `/actuator/health` to the feed URL at the end of this slice (per `deploy-plan.md` Phase I). Owner: dev. Block: no.
- **Risk:** Sequenced **before** S-05 even though their code dependencies don't force this order. The rationale is north-star realization: when S-05 ships, the parent's calendar must surface the AI-extracted event on the next poll — that only happens if the feed is already live. Inverting the order means S-05 lands and the parent sees the extracted event only in the in-app personal view, not in their calendar, which downgrades the hypothesis test from "the bridge works" to "the AI works in our UI".
- **Status:** done

### S-05: Image extraction + review acceptance — NORTH STAR

- **Outcome:** parent uploads an image (camera capture or screenshot) of a kindergarten announcement; within 60 seconds they see a list of one or more AI-proposed events with editable fields; they can accept or reject each individually; accepted events flow into the personal view (S-02's pipeline) and into the parent's subscribed calendar via the iCalendar feed (S-04's pipeline). Unreadable images surface an actionable error with a retry / manual-entry path.
- **Change ID:** image-extraction-and-review-acceptance
- **PRD refs:** FR-002, FR-004, FR-005, FR-006, FR-007, FR-008, US-01 (full main path), NFR (extraction latency, continuous visible progress)
- **Prerequisites:** F-01, S-02
- **Parallel with:** S-04 (technically, if S-04 hasn't shipped yet — but speed bias keeps them sequential, see Risk)
- **Blockers:** —
- **Unknowns:**
  - Will the F-01-default vision model achieve ≥ 80 % correctness on real announcements (date + title + requirements correct on first extraction)? Owner: dev (empirical, measured during this slice on ~10 real announcements). Block: no — this IS the wedge's hypothesis test; the slice's acceptance criterion is the measurement.
  - What's the right prompt? Owner: dev (iterative inside this slice). Block: no.
  - Frontend pattern for "continuous visible progress" during the 30–60 s wait (streamed response, polling, server-sent events)? Owner: dev (decide in `/10x-plan`). Block: no.
- **Risk:** This is the validation milestone (the north star — see §North star above). The single largest empirical risk is the ≥ 80 % accuracy claim; F-01 having de-risked the integration plumbing means a low-accuracy result this slice produces is a model/prompt problem (resolvable by swapping model in F-01 and iterating), not a "is the bridge built right" problem. If accuracy stays below 80 % after exhausting OpenRouter's reasonable vision-model menu, that's a PRD-level finding to surface — not a slice that fails silently.
- **Status:** proposed

### S-03: Edit + delete accepted events

- **Outcome:** parent can edit any field of an accepted event from the personal view; the change propagates to the iCalendar feed at the next poll. Parent can hard-delete an accepted event from the personal view; the next feed poll no longer contains it.
- **Change ID:** edit-delete-accepted-events
- **PRD refs:** FR-010, FR-011, US-01 (lifecycle after acceptance)
- **Prerequisites:** S-02
- **Parallel with:** S-04 (technically — both depend only on S-02; in practice sequenced after S-05 per speed bias)
- **Blockers:** —
- **Unknowns:**
  - Should the iCalendar feed include a stable `UID` per event so external calendar clients update in place rather than duplicate on edit? Owner: dev (resolve when S-04 ships; reaffirm in S-03). Block: no.
- **Risk:** Off the critical path to the north star — sequenced AFTER S-05 because lifecycle polish doesn't move the wedge hypothesis. If the deadline pinches, S-03 is the most defensible slice to compress (a single-user MVP with hard-delete only is acceptable for 2–3 weeks of usage before edits become essential).
- **Status:** proposed

### S-06: Source image auto-purge

- **Outcome:** (background) once every proposed event from a given uploaded image has been accepted or rejected, the source image is automatically removed from operator-accessible storage.
- **Change ID:** source-image-auto-purge
- **PRD refs:** NFR (uploaded source images retention)
- **Prerequisites:** S-05
- **Parallel with:** S-03 (both post-north-star polish; no shared code path)
- **Blockers:** —
- **Unknowns:**
  - Where do uploaded images live between upload and purge (Fly Machine ephemeral disk vs. an attached `[mounts]` vs. external object storage)? Owner: dev (decide in `/10x-plan` for S-05's upload-handling slice; S-06 then implements the purge against whatever S-05 chose). Block: no.
- **Risk:** This is an NFR compliance slice, not a feature slice. If S-05 happens to land with images stored ephemerally on the Machine, the NFR is "accidentally satisfied" (every Machine restart purges everything), but the spec is "purge on event resolution" — those are different contracts. S-06 makes the contract explicit so a future move to persistent storage doesn't silently regress the retention guarantee.
- **Status:** proposed

## Backlog Handoff

| Roadmap ID | Issue | Change ID                                | Suggested issue title                                              | Ready for `/10x-plan` | Notes                                                                 |
| ---------- | ----- | ---------------------------------------- | ------------------------------------------------------------------ | --------------------- | --------------------------------------------------------------------- |
| F-01       | [#1](https://github.com/ludmilabinek/ogarniacz/issues/1) | openrouter-llm-client-wired              | Wire OpenRouter vision-LLM client via Spring AI starter            | yes                   | Parallel-eligible with S-01. Smoke test on a sample announcement.     |
| S-01       | [#2](https://github.com/ludmilabinek/ogarniacz/issues/2) | minimal-auth-and-empty-personal-view     | Minimal email+password auth + empty personal view                  | yes                   | Parallel-eligible with F-01.                                          |
| S-02       | [#3](https://github.com/ludmilabinek/ogarniacz/issues/3) | manual-event-entry                       | Manual event entry → personal view                                 | no                    | Blocked on S-01.                                                      |
| S-04       | [#4](https://github.com/ludmilabinek/ogarniacz/issues/4) | icalendar-feed-and-subscription          | iCalendar feed + settings URL                                      | no                    | Blocked on S-02. Sequenced before S-05 to enable north-star landing.  |
| S-05       | [#5](https://github.com/ludmilabinek/ogarniacz/issues/5) | image-extraction-and-review-acceptance   | Image extraction → review → accept (north star)                    | no                    | Blocked on F-01 + S-02. Acceptance criterion includes ≥ 80 % accuracy measurement. |
| S-03       | [#6](https://github.com/ludmilabinek/ogarniacz/issues/6) | edit-delete-accepted-events              | Edit + delete accepted events (lifecycle)                          | no                    | Blocked on S-02. Sequenced after S-05 per speed bias.                 |
| S-06       | [#7](https://github.com/ludmilabinek/ogarniacz/issues/7) | source-image-auto-purge                  | Auto-purge source images after event resolution                    | no                    | Blocked on S-05.                                                      |

## Open Roadmap Questions

_PRD's `## Open Questions` resolved during `/10x-shape` and `/10x-prd`. No cross-cutting roadmap questions surfaced during framing. Per-slice unknowns live in each slice's `Unknowns` field._

## Parked

Lifted from PRD §Non-Goals; no additional parking surfaced during framing (speed bias did not force any must-have FR out of MVP scope).

- **No native mobile app** — Why parked: PRD §Non-Goals. Web-only via mobile browser is the explicit shape; camera-via-browser plus normal upload covers the upload path.
- **No modules beyond kindergarten** — Why parked: PRD §Non-Goals. Voice notes, invoices, school, medical, administrative are deferred until the kindergarten flow is solid.
- **No custom OCR / in-house vision model** — Why parked: PRD §Non-Goals. F-01 delegates extraction to an off-the-shelf vision LLM via OpenRouter.
- **No event categorization (kindergarten / home / work tags)** — Why parked: PRD §Non-Goals. Every MVP event is implicitly kindergarten.
- **No real-time / push-based calendar sync** — Why parked: PRD §Non-Goals. iCalendar polling (S-04) is the only sync mechanism in MVP; direct push integration is the post-MVP next step.
- **No per-event configurable reminder timing** — Why parked: PRD §Non-Goals. Morning-of-day-before is fixed for MVP; per-event offsets deferred to v2.
- **No password reset, MFA, email verification, or account deletion in MVP** — Why parked: PRD §Non-Goals. Minimal signup + login + logout + session persistence only (S-01); the rest is added incrementally when the second user comes on board.
- **No clipboard-paste image input** — Why parked: PRD §Non-Goals. File picker only in MVP (S-05); clipboard-paste recorded as post-MVP next step.
- **No PDF announcement input** — Why parked: PRD §Non-Goals. Screenshot of the PDF is the acceptable workaround.
- **No file-based, in-memory, or client-side persistence as a substitute for a database** — Why parked: PRD §Non-Goals + §Persistence. Per-user partitioning, post-acceptance lifecycle, token-keyed feed lookup, and durability across redeploys all require a real database (Neon PostgreSQL, per `tech-stack.md`).
- **No iCalendar token rotation / revocation flow** — Why parked: PRD §Access Control "Out of MVP". Today's only path is account deletion + re-issue; a "rotate token" action is a non-blocking follow-up after the second user comes on board.

## Done

_Empty on first generation. `/10x-archive` appends here (and flips the matching item's `Status` to `done` in `## At a glance` and the item body) when a change whose `Change ID` matches a roadmap item is archived._

- **F-01: (foundation) OpenRouter vision-LLM client is callable from the app and returns a structured response on a smoke test** — Archived 2026-06-07 → `context/archive/2026-06-01-openrouter-llm-client-wired/`. Lesson: —.
- **S-02: parent can manually create an event with date, optional time, title, requirements, and notes; on save it appears in the personal view, attributed to their account, with the default morning-of-day-before reminder attached.** — Archived 2026-06-09 → `context/archive/2026-06-07-manual-event-entry/`. Lesson: —.
- **S-01: parent can sign up with email and password, log in, log out, stay logged in across browser sessions, and land on an empty personal view scoped to their own account.** — Archived 2026-06-09 → `context/archive/2026-05-26-minimal-auth-and-empty-personal-view/`. Lesson: —.
- **S-04: parent can view and copy their unique unguessable iCalendar URL from a settings screen; accepted events from the personal view are served at that URL as a standard iCalendar feed with the morning-of-day-before reminder; events deleted from the personal view no longer appear on the next poll.** — Archived 2026-06-16 → `context/archive/2026-06-15-icalendar-feed-and-subscription/`. Lesson: —.
