# iCalendar Feed + Subscription (S-04) Implementation Plan

## Overview

S-04 lands the iCalendar feed and the per-user subscription surface. Accepted `Event` rows are served at a per-user unguessable URL as a standards-compliant `.ics` feed (RFC 5545) carrying the morning-of-day-before VALARM. A new settings page surfaces the URL with copy UX, short subscribe instructions for the four target clients, and an explicit note about Google Calendar's two client-side limitations. Token entropy is the only access control on the feed endpoint.

## Current State Analysis

The Event entity (`src/main/java/com/example/app/event/Event.java`) is already feed-ready: `UUID id` becomes the iCal `UID` local part; `LocalDate eventDate` + nullable `LocalTime eventTime` map cleanly onto `DTSTART;VALUE=DATE` vs `DTSTART;TZID=Europe/Warsaw:...`; `String title/requirements/notes` map onto `SUMMARY` + `DESCRIPTION`. The reminder is computed (not stored) by `EventReminder.reminderFor(event)` returning a `ZonedDateTime` — the iCal writer will reuse this directly so VALARM and the in-app reminder share one source of truth.

`AppUser` (`src/main/java/com/example/app/user/AppUser.java`) has no `ical_token` column yet — S-04 adds one. `AppUserRepository` needs one new finder (`findByIcalToken`). `SecurityConfig` (`src/main/java/com/example/app/config/SecurityConfig.java:67-90`) is a single `SecurityFilterChain` with form-login + CSRF + remember-me; the anonymous feed endpoint slots in cleanly with one `.permitAll()` line. No Flyway/Liquibase — schema evolves via `spring.jpa.hibernate.ddl-auto=update` so a new nullable column is a one-row no-op.

The topbar fragment (`src/main/resources/templates/fragments/layout.html:43-48`) currently holds only `${userEmail}` + logout button — no settings link yet. The per-controller `@SpringBootTest` test pattern is codified in `context/foundation/lessons.md` and exemplified by `EventControllerTest`; S-04 creates `CalendarControllerTest` and `SettingsControllerTest` following that pattern. `IcalFeedWriterTest` and `IcalTokenGeneratorTest` follow `EventReminderTest`'s pure-JUnit pattern.

The research doc (`context/changes/icalendar-feed-and-subscription/research.md`) closed the named open questions: ical4j 4.2.5 (BSD-3, ~30-50 LOC vs ~180-250 LOC hand-rolled, Context7-indexed); token = `SecureRandom → 24 bytes → Base64URL no-pad → 32 chars` (192 bits); single nullable column on `AppUser`; one `.permitAll()` line on the existing security chain. Two PRD-touching client gaps remain: Google polls subscribed feeds every 12-48h with no configurability, and Google silently ignores VALARM on subscribed feeds. Both are client-side and cannot be closed server-side.

## Desired End State

A logged-in parent visits `/settings`, sees their unique `https://<host>/calendar/<token>.ics` URL with a copy button and short subscribe instructions for Google/Apple/Outlook/Thunderbird, and a labelled note explaining Google Calendar's two limitations. Their accepted events appear at that URL as a standards-compliant iCalendar feed within one client poll, including the morning-of-day-before VALARM. Deleted events drop from the feed on the next poll. Cross-account isolation is enforced by token entropy alone — alice's token returns alice's events; a random non-issued token returns 404.

Verification:
- `./gradlew build` passes; `./gradlew test` passes; new tests are part of CI.
- `curl -i https://<host>/calendar/<known-token>.ics` returns `200 OK` with `Content-Type: text/calendar; charset=utf-8`, `Cache-Control: private, no-cache, max-age=0`, an RFC 5545-shaped body containing `BEGIN:VCALENDAR`/`PRODID:-//Ogarniacz//`/the seeded events' UIDs and a `VALARM` block.
- `curl -i https://<host>/calendar/$(openssl rand -base64 18 | tr -d '=' | tr '/+' '_-' | cut -c1-32).ics` returns `404` (NOT `401`, NOT `200` with empty body).
- A logged-in parent visits `/settings`, sees their URL, copies it into Apple Calendar, and within one refresh sees their next event with a morning-of-day-before reminder.
- `context/foundation/prd.md` carries a new `## Known Limitations` section; `context/foundation/test-plan.md` §6.5 carries the iCal feed cookbook entry.

### Key Discoveries:

- VALARM trigger should be **absolute UTC** (`TRIGGER;VALUE=DATE-TIME:YYYYMMDDTHHMMSSZ`) computed via `EventReminder.reminderFor(event).toInstant()`, NOT the relative `TRIGGER:-PT15H` from research §B.2. Two reasons: (1) research's `-PT15H` math assumed a 9 AM reminder; the config is `app.event.reminder.hour=8` so the relative offset would have to vary per event kind; (2) absolute trigger reuses `EventReminder` (already DST-tested by `EventReminderTest`) as the single source of truth — VALARM time = in-app reminder time by construction. The feed is re-rendered on every poll, so the "stale trigger after edit" concern that motivates relative triggers does not apply here.
- Spring Security DSL: `.permitAll()` rule for `/calendar/*.ics` MUST be placed **before** `.anyRequest().authenticated()` (first-match-wins per Spring Security 6.5 reference). Silently shadowed if ordered wrong — covered by a dedicated regression assertion in `CalendarControllerTest`.
- Token storage: column-level `@Column(unique = true, length = 32)` on `AppUser.icalToken`. PostgreSQL auto-backs UNIQUE with a B-tree index → `findByIcalToken` is O(log n) without an explicit `@Index`. Adding both `@Column(unique = true)` AND `@Index(unique = true)` would produce two indexes (Baeldung) — do not duplicate.
- Token lazy mint at first `/settings` visit, not at signup. Keeps the signup transaction free of `SecureRandom`; the migration concern reduces to a one-row no-op thanks to `ddl-auto=update` + lazy generation.
- Unknown token MUST return 404, NOT 401. 401 would distinguish "this token doesn't exist" from "this endpoint requires auth", leaking the existence of the token namespace. Both the "no such token" and "no such endpoint" cases return identical 404.
- Empty feed (valid user, zero accepted events) returns `200 OK` with a complete `VCALENDAR` envelope and zero `VEVENT` blocks. NOT `204`. Some clients (notably Outlook subscribed-feeds) treat non-200 as a transient fetch error and may unsubscribe after repeated occurrences.

## What We're NOT Doing

- **No token rotation / regeneration UI.** PRD §Out-of-MVP names this explicitly. Today's only revocation path remains account deletion. Forward-compat in the schema is preserved by single-column name (`ical_token`, not `current_ical_token`).
- **No `previous_ical_token` or `rotated_at` columns.** Pre-adding rotation columns without the rotation logic clutters the schema. Additive migration when rotation slice lands.
- **No image upload pipeline.** S-04 is independent of S-05; the feed reads from already-persisted `Event` rows.
- **No event edit/delete UI on `/settings`.** That's S-03 (`event-edit-and-delete`). S-04's deletion-propagation test deletes via `EventRepository` directly to assert the feed's freshness contract, not via UI.
- **No per-event configurable reminder timing.** PRD §Non-Goals names this — every event gets the morning-of-day-before reminder via `EventReminder`.
- **For now we do not preserve event history in the feed.** Feed scope intentionally mirrors the in-app personal view (`/app` already hides past events) — once an event's date is past Warsaw "today", it disappears from both surfaces in lockstep. Standards-compliant subscribed-feed clients (Apple/Outlook/Thunderbird/Google) treat the feed as the source of truth, so the past event is removed from the local calendar on the next poll. Parents looking at last week's "what to bring" will not find it. Whether to retain N days of history (e.g. include events with `eventDate >= today - 7d`) is an open product question, not a closed architectural decision — revisit if the UX gap is reported.
- **No `ETag` / `Last-Modified` conditional GET support.** Only Thunderbird honors them on subscribed paths; cost/benefit favors skipping until egress bandwidth is a concern.
- **No gzip compression** (`server.compression.*`). Feed bodies are tens of KB; savings are marginal.
- **No `webcal://` deep links or per-client one-click subscribe URLs** on `/settings`. Vendor-specific deep-link formats are brittle (Google's format has changed twice; new Outlook strips `webcal://`).
- **No UptimeRobot keyword-check target swap.** Lives in `deploy-plan.md` (noted in roadmap S-04 Unknowns), not here.
- **No PRD edits to the `## Calendar synchronization` FR-013 freshness wording.** The new `## Known Limitations` section carries the carve-out; the FR-013 text stays as-is so the contract is documented but not relaxed.
- **No CI gate flip for the iCal feed contract tests.** The new tests run in the existing `./gradlew test` suite; the `test-plan.md §5` gate row will move from `planned` to `required` mechanically once the suite is green (no separate plan step needed).

## Implementation Approach

Six phases. Phase 0 lands the PRD oracle the later test phases will cite. Phase 1 is additive persistence + the token generator. Phases 2a and 2b split the feed endpoint along its two oracles (RFC 5545 for the value transformer, HTTP / Spring Security DSL for the controller + carve-out) so each phase has one test layer and one assertion contract. Phase 3 wires the UI on top of the now-stable feed. Phase 4 backfills the cookbook entry pointing to the patterns the implementation actually used.

Each phase aligns 1:1 with a test-plan §3 phase or a documentation chore:
- Plan Phase 0 → standalone PRD edit (not a test-plan phase).
- Plan Phase 1 → standalone foundation, no test-plan phase.
- Plan Phase 2a → test-plan §3 Phase 2 (iCal feed serialization + freshness, risks #3, #6).
- Plan Phase 2b → test-plan §3 Phase 3 (iCal feed access control, risk #4).
- Plan Phase 3 → no test-plan phase (UI + lazy token mint).
- Plan Phase 4 → test-plan §6.5 cookbook backfill.

## Critical Implementation Details

- **VALARM trigger is absolute UTC computed via `EventReminder.reminderFor(event)`.** The writer takes `EventReminder` as a constructor dependency and emits `TRIGGER;VALUE=DATE-TIME:<UTC instant>Z` for each VALARM. This ties the feed's reminder time to the in-app reminder time by construction; DST correctness is inherited from `EventReminder` (covered by `EventReminderTest`). Phase 2a tests assert `parsed VALARM trigger == EventReminder.reminderFor(event).toInstant()` to make the dependency explicit.

- **Spring Security rule order is load-bearing.** `.requestMatchers(HttpMethod.GET, "/calendar/*.ics").permitAll()` MUST precede `.anyRequest().authenticated()` in `SecurityConfig.filterChain(...)`. Silently shadowed if ordered after — `CalendarControllerTest` carries one regression assertion (`anonymousFeedGetReturns200NotRedirect`) that fails closed if the rule moves.

- **Token generator uses default `SecureRandom`, NOT `SecureRandom.getInstanceStrong()` and NOT `ThreadLocalRandom`.** `getInstanceStrong()` blocks on `/dev/random` on Linux and can stall under low entropy at startup. The no-arg `SecureRandom` constructor selects `NativePRNG` against `/dev/urandom` on Linux — the right choice. `ThreadLocalRandom` is an LCG, predictable from seed, and would defeat the entropy floor.

- **Token mint is lazy on first `/settings` GET, not at signup or login.** `IcalSubscriptionService#getOrCreateToken(user)` is `@Transactional`: if `user.icalToken == null`, mint via `IcalTokenGenerator.next()`, set, save, return; else return existing. Existing users get their column populated the first time they open `/settings`.

- **Unknown token returns 404 with an empty body, not 401.** 401 would leak the existence of the token namespace endpoint. Both "the token doesn't exist in the DB" and "the URL pattern doesn't match a known endpoint" return identical 404. Implementation: `appUserRepository.findByIcalToken(token).orElseThrow(NotFoundException::new)` (or `ResponseStatusException(NOT_FOUND)`) at the controller boundary — symmetric with Spring's default 404 for unmatched routes.

## Phase 0: PRD Known Limitations amendment

### Overview

Land the PRD carve-out before any test cites it. Phase 2a's freshness/cadence tests and Phase 3's UI copy both treat this PRD section as their oracle.

### Changes Required:

#### 1. PRD known-limitations subsection

**File**: `context/foundation/prd.md`

**Intent**: Add a new `## Known Limitations` section between `## Persistence` (currently line 177) and `## Non-Goals` (currently line 181) documenting the two Google-Calendar gaps that S-04 cannot close server-side. The section is the oracle the implementation tests and the UI copy will cite. The existing `## Calendar synchronization` FR-013 freshness wording stays as-is — the carve-out lives in its own labelled section so the original contract remains visible.

**Contract**: New top-level heading `## Known Limitations` with one introductory sentence and two bullets:
1. Google Calendar polling cadence (12-24h typical, up to 48h outliers, no client control, hints ignored). Apple/Outlook/Thunderbird respect the contract.
2. Google Calendar silently drops VALARM on subscribed feeds. Apple/Outlook/Thunderbird honor it. Mitigation for Google users is to set a per-calendar default reminder in Google Calendar's own UI.

Both bullets must be phrased as **client-side constraints** (not Ogarniacz defects). The settings page UI copy in Phase 3 cites this section by name.

### Success Criteria:

#### Automated Verification:

- `grep -c "^## Known Limitations" context/foundation/prd.md` returns `1`.
- The section appears between `## Persistence` and `## Non-Goals` (i.e. `awk '/^## /' context/foundation/prd.md` shows that ordering).
- `./gradlew build` still passes (smoke — no test depends on PRD wording yet at this phase).

#### Manual Verification:

- The new section reads as PRD prose (not a changelog entry); user-facing language but matter-of-fact about both gaps.
- The S-04 UI copy in Phase 3 is consistent with these bullets.

**Implementation Note**: After completing this phase, pause for manual confirmation that the PRD wording is acceptable before Phase 1 starts. Subsequent phases cite this section as their oracle.

---

## Phase 1: Token + persistence foundation

### Overview

Additive persistence layer: ical4j dependency, `ical_token` column, repo finder, generator, lazy-mint service. No UI, no feed endpoint, no security change yet. Establishes the building blocks Phase 2a and 2b consume.

### Changes Required:

#### 1. ical4j dependency

**File**: `build.gradle`

**Intent**: Add ical4j 4.2.5 to the `implementation` configuration so the Phase 2a writer can use the library. JDK 21 + ical4j 4.x needs no JVM args.

**Contract**: One new line in the dependencies block: `implementation 'org.mnode.ical4j:ical4j:4.2.5'`. Transitive dependencies (`slf4j-api`, `commons-codec`, `commons-lang3`, `threeten-extra`) flow in via Gradle resolution.

#### 2. `AppUser.icalToken` column

**File**: `src/main/java/com/example/app/user/AppUser.java`

**Intent**: Add a nullable `String icalToken` JPA field with the UNIQUE constraint so the DB-side index backs `findByIcalToken` lookups in O(log n). Nullable because token mint is lazy on first `/settings` visit.

**Contract**: One new field annotated `@Column(name = "ical_token", unique = true, length = 32)`. Standard getter + setter (or package-private mutator if matching the entity's existing style). Do NOT also add a class-level `@Index(unique = true, columnList = "ical_token")` — that would produce two indexes for the same column (Baeldung).

#### 3. `AppUserRepository.findByIcalToken`

**File**: `src/main/java/com/example/app/user/AppUserRepository.java`

**Intent**: Add a Spring Data derived finder for the feed endpoint's token-to-user lookup, plus a row-locking finder used by the lazy-mint service to serialize concurrent first-visits.

**Contract**: Two new methods:
- `Optional<AppUser> findByIcalToken(String icalToken);` — Spring Data derives the JPQL automatically from the method name; no `@Query` annotation needed.
- `@Lock(LockModeType.PESSIMISTIC_WRITE) @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000")) Optional<AppUser> findAndLockById(Long id);` — pessimistic-write lock on the row, used by `IcalSubscriptionService.getOrCreateToken` to prevent lost-update under concurrent first-visits. 5 s lock timeout keeps a stuck transaction from wedging requests.

#### 4. `IcalTokenGenerator`

**File**: `src/main/java/com/example/app/user/IcalTokenGenerator.java` (new)

**Intent**: One small `@Component` (or `@Service`) whose `next()` method returns a 32-character Base64URL-no-padding string built from 24 bytes of `SecureRandom`. The class is the entropy boundary — all other code consumes its output.

**Contract**: One public method: `String next()`. Uses a single `SecureRandom` field (no-arg constructor — NOT `getInstanceStrong()`; NOT `ThreadLocalRandom`) and `Base64.getUrlEncoder().withoutPadding()`. Output matches `^[A-Za-z0-9_-]{32}$`. Thread-safe by virtue of `SecureRandom`'s documented thread-safety.

#### 5. `IcalSubscriptionService.getOrCreateToken`

**File**: `src/main/java/com/example/app/user/IcalSubscriptionService.java` (new)

**Intent**: Lazy-mint orchestrator. The settings controller calls `getOrCreateToken(authenticatedUser)` on every GET; first call mints + persists, subsequent calls return the existing token.

**Contract**: `@Service` with one public method: `@Transactional String getOrCreateToken(AppUser user)`. Re-reads the row inside the transaction with a row-level write lock: `appUserRepository.findById(user.getId(), LockModeType.PESSIMISTIC_WRITE)` (requires a new `@Lock(LockModeType.PESSIMISTIC_WRITE) Optional<AppUser> findAndLockById(Long id)` method on `AppUserRepository`, or an `EntityManager.find(AppUser.class, id, LockModeType.PESSIMISTIC_WRITE)` injection). If the locked row's `icalToken` is `null`, mints via the injected generator, sets on the entity, saves via the repository, returns the new token; else returns the existing token. The row-level lock serializes any concurrent second call behind the first — the second call observes the persisted token and returns it (no lost update). Note: `@Transactional` + the UNIQUE constraint alone do NOT prevent lost-update on the same user row under READ_COMMITTED; the explicit lock is what makes the lazy-mint safe. Lock contention is one row, first-visit only — negligible.

#### 6. Generator unit test

**File**: `src/test/java/com/example/app/user/IcalTokenGeneratorTest.java` (new)

**Intent**: Pure-JUnit regression catching the two most likely entropy/encoding bugs: (a) standard Base64 padding crept back in or the alphabet got substituted; (b) the RNG degraded to `Random` or a non-CSPRNG. The cardinality assertion catches both.

**Contract**: Three assertions over 1000 successive `next()` calls:
1. Every result matches `^[A-Za-z0-9_-]{32}$` (charset + length).
2. The `Set.of(...)` cardinality of the 1000 results is exactly 1000 (no duplicates — birthday-paradox math says collisions at 192 bits are vanishingly improbable; any duplicate is evidence of degenerate RNG).
3. (Optional sanity) The first character distribution across 10000 calls is not concentrated on one value (catches a stuck PRNG); skip if it makes the test slow.

Pure JUnit 5, no Spring context.

### Success Criteria:

#### Automated Verification:

- `./gradlew build` compiles after the dependency add.
- `./gradlew test --tests com.example.app.user.IcalTokenGeneratorTest` passes.
- `./gradlew test` (full suite) passes — no regressions in `AppApplicationTests`, `EventControllerTest`, etc.
- DB schema update is observable at startup (Hibernate logs `alter table app_user add column ical_token ...` on first `bootRun` against a non-fresh DB) — manual check, no test.

#### Manual Verification:

- A `./gradlew bootRun` against a freshly-migrated DB starts cleanly; no Hibernate errors about the new column.
- Inspection of `IcalTokenGenerator.next()` output via a one-off test or REPL shows 32-char strings in the `[A-Za-z0-9_-]` alphabet.

**Implementation Note**: After Phase 1 lands, pause for manual confirmation before Phase 2a. No user-visible behavior changes in Phase 1.

---

## Phase 2a: IcalFeedWriter — RFC 5545 serialization

### Overview

The pure-value half of the feed endpoint. Maps `(AppUser, List<Event>)` to an RFC 5545-compliant `VCALENDAR` string using ical4j. No Spring context, no HTTP, no security — just RFC 5545 as the oracle. Covers test-plan §3 Phase 2 (risks #3 freshness/deletion structure, #6 DST VALARM).

### Changes Required:

#### 1. `IcalFeedWriter`

**File**: `src/main/java/com/example/app/event/IcalFeedWriter.java` (new)

**Intent**: One small class that knows how to turn events into VCALENDAR bytes. Takes `EventReminder` and `AppEventProperties` (for the timezone on timed events) via constructor. Returns the serialized feed as a `String` so the controller in Phase 2b can stream it without committing to a particular HTTP machinery.

**Contract**: `@Component` (Spring-managed but trivially unit-testable by direct construction). One public method: `String write(AppUser user, List<Event> events)`.

The output's VCALENDAR envelope has these properties:
- `VERSION:2.0` (mandatory, RFC 5545 §3.7.4).
- `PRODID:-//Ogarniacz//Ogarniacz Feed 1.0//EN` (mandatory, RFC 5545 §3.7.3).
- `CALSCALE:GREGORIAN` (RFC 5545 §3.7.1, defaulted but conventionally emitted).
- `NAME:Ogarniacz` (RFC 7986 §5.1).
- `X-WR-CALNAME:Ogarniacz` (X-prop, honored by Apple + Outlook).
- `X-PUBLISHED-TTL:PT6H` (MS-OXCICAL, occasionally honored by Outlook).
- `REFRESH-INTERVAL;VALUE=DURATION:PT6H` (RFC 7986 §5.7).

Both `NAME` and `X-WR-CALNAME` carry the static string `"Ogarniacz"`, NOT a per-user label — confirmed in plan design (research §H.5 recommendation; user confirmed).

Each `VEVENT` has these properties:
- `UID:<event.id>@ogarniacz.fly.dev` (research §B.3 — globally unique + stable across edits, RFC 7986 §5.3 compliant since the host is public not user-identifying).
- `DTSTAMP:<UTC at render time>Z` (RFC 5545 §3.8.7.2).
- `DTSTART;VALUE=DATE:yyyyMMdd` when `event.eventTime` is null; `DTSTART;TZID=Europe/Warsaw:yyyyMMddTHHmmss` when non-null (timezone read from `AppEventProperties.timezone()`).
- `DTEND;VALUE=DATE:yyyyMMdd+1` for date-only events (exclusive end, RFC 5545 §3.6.1; emit explicitly for renderer-compat — research §D.5). For timed events, derive `DTEND` from `DTSTART` + a default duration (e.g. 1 hour) — flag this as a Phase 2a design call to confirm with the user; alternatively, omit `DTEND` for timed events and rely on RFC 5545's default 0-duration interpretation. **Decision: emit `DTEND;VALUE=DATE:next_day` for date-only events; omit `DTEND` for timed events (RFC-correct default).**
- `SUMMARY:<event.title>` with ical4j-provided TEXT escaping (RFC 5545 §3.3.11).
- `DESCRIPTION:<requirements + notes joined with "\n\n">` when either is present; omit the property entirely when both are null. Newlines become literal `\n` per RFC.
- `SEQUENCE:0` (forward-compat for S-03 edits; ical4j increments automatically when mutated — emitted as `0` on every fresh render).
- `STATUS:CONFIRMED` — optional but standard; emit for clarity.

Each `VEVENT` carries one `VALARM`:
- `ACTION:DISPLAY` (mandatory).
- `TRIGGER;VALUE=DATE-TIME:<UTC instant of EventReminder.reminderFor(event)>Z` — **absolute UTC trigger**, NOT relative; rationale in Critical Implementation Details above.
- `DESCRIPTION:Ogarniacz reminder` (mandatory when ACTION:DISPLAY).

Empty `events` list → envelope only, zero VEVENTs (NOT 204; this writer is value-only — HTTP status is Phase 2b's concern).

#### 2. Writer unit test

**File**: `src/test/java/com/example/app/event/IcalFeedWriterTest.java` (new)

**Intent**: Pure-JUnit regression covering the RFC 5545 contract. Parse the output back with ical4j's `CalendarBuilder` to assert structurally rather than matching brittle string fragments. Covers test-plan risks #3 (structure / freshness primitives — UID stability, empty envelope, deletion-by-omission) and #6 (DST VALARM time correctness).

**Contract**: Pure JUnit 5, no Spring context — directly instantiate `IcalFeedWriter` with a hand-built `EventReminder` and `AppEventProperties` (these are themselves test-friendly value classes). Test methods:

1. **`emptyEventsReturnsEnvelopeOnly`** — `write(user, List.of())` produces a parseable VCALENDAR with zero VEVENT components. Assert envelope properties (`VERSION`, `PRODID`, `NAME`, `X-WR-CALNAME`, `X-PUBLISHED-TTL`, `REFRESH-INTERVAL`) are present.

2. **`dateOnlyEventEmitsDtstartValueDateAndExplicitDtend`** — one event with `eventDate=2026-06-20`, `eventTime=null` → VEVENT has `DTSTART;VALUE=DATE:20260620` and `DTEND;VALUE=DATE:20260621`. No `TZID` parameter.

3. **`timedEventEmitsDtstartWithTzidEuropeWarsaw`** — one event with `eventDate=2026-06-20`, `eventTime=14:30` → VEVENT has `DTSTART;TZID=Europe/Warsaw:20260620T143000`. No `DTEND`.

4. **`uidIsEventIdAtOgarniaczFlyDevAndStableAcrossWrites`** — same event written twice → both renders share the same UID; UID format matches `^[0-9a-f-]{36}@ogarniacz\.fly\.dev$`.

5. **`valarmTriggerOnRegularDayIsMorningOfDayBeforeAt08Warsaw`** — event on 2026-06-15 (Monday in summer, well clear of any DST transition), `app.event.reminder.hour=8`. The reminder fires on 2026-06-14 at 08:00 Warsaw local = `2026-06-14T06:00:00Z` (CEST/UTC+02:00 in mid-June). Parse VALARM, extract `TRIGGER` value, assert it equals **`Instant.parse("2026-06-14T06:00:00Z")`** — the expected value is a hard-coded UTC instant derived from the PRD/spec rule "morning of day-before at `app.event.reminder.hour` Warsaw", NOT computed via `EventReminder.reminderFor(event)` (which would make the test mirror the implementation). This test is the regular-day counterpart to the DST tests #6 and #7 below.

6. **`valarmTriggerSurvivesSpringForward`** — event on 2026-03-30 (Monday after spring-forward Sunday 2026-03-29). The reminder fires on 2026-03-29 at 08:00 Warsaw local = 06:00 UTC (clocks already sprung at 02:00→03:00 that morning). Assert the parsed VALARM trigger equals `2026-03-29T06:00:00Z`.

7. **`valarmTriggerSurvivesFallBack`** — event on 2026-10-26 (Monday after fall-back Sunday 2026-10-25). The reminder fires on 2026-10-25 at 08:00 Warsaw local = 07:00 UTC (clocks already fell back at 03:00→02:00 that morning). Assert the parsed VALARM trigger equals `2026-10-25T07:00:00Z`.

8. **`summaryAndDescriptionHandlePolishDiacriticsAndNewlines`** — event with `title="Pasowanie — przynieś czapkę"`, `requirements="ą,ć,ę;ł\\ś"`. Parse output, extract `SUMMARY` and `DESCRIPTION`, assert decoded values equal the originals (smoke test against ical4j's UTF-8 folding + TEXT escaping). One single test — we trust ical4j for the rest but verify Polish diacritics specifically because that's our actual fixture surface.

9. **`descriptionOmittedWhenRequirementsAndNotesBothNull`** — event with both fields null → VEVENT has no `DESCRIPTION` property.

10. **`descriptionJoinsRequirementsAndNotesWithBlankLine`** — both present → `DESCRIPTION` contains both, separated by `\n\n`.

11. **`sequenceIsZeroOnFreshRender`** — one event → VEVENT carries `SEQUENCE:0`.

12. **`statusIsConfirmedOnEveryEvent`** — every emitted VEVENT carries `STATUS:CONFIRMED`.

DST test fixtures should use the real Europe/Warsaw transition dates for 2026; the test does NOT depend on the system clock (uses the writer's deterministic render path).

### Success Criteria:

#### Automated Verification:

- `./gradlew test --tests com.example.app.event.IcalFeedWriterTest` passes (all 12 methods).
- `./gradlew test` full suite passes.
- The writer produces parseable RFC 5545 (parsing the output with `ical4j`'s `CalendarBuilder` round-trips without error in every test).

#### Manual Verification:

- A one-off run that writes a small feed (e.g. via a temporary `@SpringBootTest` integration step or local REPL) and pastes it into [iCalendar Validator](https://icalendar.org/validator.html) or `ics-validator` returns "valid".
- An informal eye-test of the rendered output for a date-only event reads cleanly — Polish diacritics survive, line folding at 75 octets does not split UTF-8 multi-byte sequences.

**Implementation Note**: Phase 2a does not touch HTTP, Spring Security, or the database — pause after the writer + its test pass before Phase 2b connects it to the controller.

---

## Phase 2b: CalendarController + security carve-out

### Overview

Wire the Phase 2a writer to an HTTP endpoint. One new controller, one line change in `SecurityConfig`, full integration test surface covering test-plan §3 Phase 3 (risk #4 cross-account isolation, unknown-token 404) plus the cross-surface freshness/deletion-propagation checks (risk #3) that pure-unit cannot cover.

### Changes Required:

#### 1. Fix global `Clock` bean to honor `app.timezone`

**File**: `src/main/java/com/example/app/AppApplication.java`

**Intent**: The existing `clock()` bean returns `Clock.systemDefaultZone()`, which on Fly's UTC JVM means every consumer of the bean computes "today" against UTC midnight (01:00–02:00 Warsaw, DST-dependent). All current consumers — `AppController.findUpcomingByUser` (the in-app personal view), `OpenRouterLlmVisionClient` (the "today is X" hint passed to the LLM for date-resolution in photos), and the new `CalendarController` (this slice) — operate on parent-facing local dates and want Warsaw "today". Fix the bean here once; every consumer gets the right behavior without per-call ceremony. Side benefit: closes a parallel latent bug in `AppController` (existing UTC "today" rendered to /app would skip events for ~1 h after midnight Warsaw).

**Contract**: Change `clock()` to inject `AppEventProperties` and return `Clock.system(properties.timezone())`:
```java
@Bean
public Clock clock(AppEventProperties properties) {
    return Clock.system(properties.timezone());
}
```
`AppEventProperties` is already an `@EnableConfigurationProperties`-registered record on the application class, so the parameter injection is free. No new properties, no new beans, no `@Qualifier` games.

**Verification**: Add one test method to `AppApplicationTests` (or a new tiny `ClockBeanTest`) that autowires `Clock` and asserts `clock.getZone().equals(ZoneId.of("Europe/Warsaw"))`. Regression catches a future revert to `systemDefaultZone()`.

**Out of scope here**: Auditing every existing call site for UTC assumptions. `OpenRouterLlmVisionClientPromptTest` constructs its own `Clock.fixed(...)` rather than autowiring the bean, so it's unaffected. `AppController` already passes `LocalDate.now(clock)` through — a Warsaw-zone clock is strictly more correct for its "upcoming events" filter.

#### 2. `CalendarController`

**File**: `src/main/java/com/example/app/event/CalendarController.java` (new)

**Intent**: One `@GetMapping("/calendar/{token}.ics")` handler that resolves token-to-user via `AppUserRepository.findByIcalToken`, loads upcoming events via `EventRepository.findUpcomingByUser`, delegates serialization to `IcalFeedWriter`, and returns the body with the right MIME and Cache-Control headers. Anonymous endpoint — does NOT inject `Authentication`.

**Contract**: `@Controller` (or `@RestController` — either works since the return type is `ResponseEntity<String>`). The path template uses `{token}` followed by literal `.ics`. The handler:
1. Calls `appUserRepository.findByIcalToken(token).orElseThrow(...)` → throws `ResponseStatusException(HttpStatus.NOT_FOUND)` when the token doesn't match a user. Returns 404 with empty body (NOT 401 — see Critical Implementation Details).
2. Calls `eventRepository.findUpcomingByUser(user, LocalDate.now(clock))`. `clock` is the global `Clock` bean injected via constructor (same pattern as `AppController`). The bean is now zone-aware (see step 1 above), so `LocalDate.now(clock)` yields Warsaw "today" automatically — no per-controller zone handling, no `Clock.system(properties.timezone())` instantiation at the call site. Feed scope is upcoming-only (confirmed in plan design, research §H.1).
3. Calls `icalFeedWriter.write(user, events)` → String body.
4. Returns `ResponseEntity.ok()` with `Content-Type: text/calendar; charset=utf-8`, `Cache-Control: private, no-cache, max-age=0`, `Vary: Accept`. No `Content-Disposition` header — the URL is opened in a browser by users to copy, not downloaded.

#### 3. Security carve-out

**File**: `src/main/java/com/example/app/config/SecurityConfig.java`

**Intent**: One line addition inside `.authorizeHttpRequests(...)` permitting anonymous GET on `/calendar/*.ics`. **MUST be placed before** `.anyRequest().authenticated()` per Spring Security 6.5's first-match-wins semantics; silently shadowed if ordered wrong.

**Contract**: Insert `.requestMatchers(HttpMethod.GET, "/calendar/*.ics").permitAll()` as the first rule inside `.authorizeHttpRequests(auth -> auth ...)`, ahead of the existing `permitAll` list and `anyRequest().authenticated()`. Existing form-login, CSRF, remember-me, and security-context blocks remain unchanged. CSRF does not apply (GET-only safe method per RFC 7231 §4.2.1).

#### 4. `CalendarControllerTest`

**File**: `src/test/java/com/example/app/event/CalendarControllerTest.java` (new)

**Intent**: Integration test covering the four contracts the controller + security carve-out must satisfy: anonymous-GET-permitted, unknown-token-404, cross-user-isolation, and freshness-on-deletion. Uses the per-controller `@SpringBootTest` + `MockMvc` pattern codified in `context/foundation/lessons.md`.

**Contract**: `@SpringBootTest @AutoConfigureMockMvc @TestPropertySource(properties = "REMEMBER_ME_KEY=test-key-not-for-production")`. Test methods:

1. **`anonymousFeedGetReturns200NotRedirect`** — seed user with token `T`, GET `/calendar/{T}.ics` without `with(user(...))` (anonymous). Assert `status().isOk()`, NOT `status().is3xxRedirection()`. Pins the `.permitAll()` rule order — if a future SecurityConfig refactor moves the rule after `anyRequest().authenticated()`, this test fails.

2. **`feedReturnsCorrectMimeAndCacheControl`** — same seed. Assert `header().string("Content-Type", containsString("text/calendar"))` AND `containsString("charset=utf-8")`, `header().string("Cache-Control", "private, no-cache, max-age=0")`.

3. **`feedBodyContainsExpectedEventUids`** — seed user + 2 events. GET feed. Assert body contains `BEGIN:VCALENDAR`, `END:VCALENDAR`, `PRODID:-//Ogarniacz//`, both events' UIDs as `<uuid>@ogarniacz.fly.dev`.

4. **`unknownTokenReturns404NotUnauthorized`** — random 32-char Base64URL string that's NOT in the DB. Assert `status().isNotFound()`, NOT `status().isUnauthorized()`. Pins the existence-leak avoidance.

5. **`crossUserIsolationByToken`** — seed alice + bob, each with their own token and one event. GET alice's feed → body contains alice's event UID, NOT bob's. GET bob's feed → reverse. Mirrors `appShowsOwnEmailOnlyNotOtherUsersEmail` (S-01 partition template).

6. **`deletedEventDisappearsFromNextPoll`** — seed user + 2 events (E1, E2). First GET → body contains both UIDs. Delete E2 via `eventRepository.delete(E2)`. Second GET → body contains E1's UID, does NOT contain E2's UID. Pins freshness/deletion-propagation (risk #3).

7. **`pastEventsAreExcludedFromFeed`** — seed user + one event with `eventDate` in the past + one in the future. GET feed → body contains only the future event's UID. Confirms the upcoming-only scope decision.

8. **`emptyAcceptedEventsReturnsEnvelopeOnly`** — seed user with token but zero events. GET feed → `200 OK`, body contains `BEGIN:VCALENDAR` and `END:VCALENDAR`, body does NOT contain `BEGIN:VEVENT`. Confirms the "200 with empty envelope, NOT 204" design call.

9. **`tokenWithWrongFileExtensionRedirectsToLogin`** — anonymous GET `/calendar/<valid-token>.txt`. The path falls outside the `.requestMatchers(GET, "/calendar/*.ics").permitAll()` carve-out, so Spring Security's form-login filter redirects to `/login`. Assert `status().is3xxRedirection()` + `redirectedUrlPattern("/login*")`. Mirrors `EventControllerTest.anonymousGetEventsNewRedirectsToLogin`. Pins that non-`.ics` paths are NOT part of the feed carve-out.

10. **`tokenInPathOnlyAcceptsTokenCharsetRedirectsToLogin`** — anonymous GET `/calendar/foo/bar.ics`. `requestMatchers("/calendar/*.ics")` uses `*` (single segment) so `foo/bar` doesn't match the carve-out and Spring Security redirects to `/login`. Assert `status().is3xxRedirection()` + `redirectedUrlPattern("/login*")`. Pins that multi-segment paths under `/calendar/` are NOT part of the carve-out (path-traversal posture).

CSRF / `with(csrf())` not needed (GET). `with(user(...))` not used on these tests — the endpoint is anonymous by contract.

### Success Criteria:

#### Automated Verification:

- `./gradlew test --tests com.example.app.event.CalendarControllerTest` passes (all 10 methods).
- `./gradlew test` full suite passes — no regression in `EventControllerTest`, `AppApplicationTests`, `OpenRouterLlmVisionClientPromptTest` (form-login + remember-me + CSRF unaffected since the new rule is GET-scoped and path-scoped; LLM client test constructs its own `Clock.fixed`).
- Clock-bean regression: `AppApplicationTests` (or new `ClockBeanTest`) autowires `Clock` and asserts `clock.getZone().equals(ZoneId.of("Europe/Warsaw"))`.
- `./gradlew build` passes.
- Existing routes still respond as before: `/`, `/login`, `/signup`, `/events`, `/app` retain their pre-Phase-2b behavior (covered by existing tests).

#### Manual Verification:

- `./gradlew bootRun`, manually seed an event and a token in the local DB (psql / H2 console), `curl -i http://localhost:8080/calendar/<token>.ics` returns the feed.
- Paste the URL into Apple Calendar (or Thunderbird) — events appear; the morning-of-day-before reminder fires at 08:00 Warsaw local on the configured event date.
- `curl -i http://localhost:8080/calendar/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.ics` (32 chars, not in DB) returns `404`.
- **Past-event scope check**: seed two events — one with `eventDate = today (Warsaw)`, one with `eventDate = today - 1 day (Warsaw)`. `curl` the feed and confirm the past event's UID is absent from the body. Confirms the upcoming-only scope decision is what users will see in real clients (subscribed-feed clients then delete the past event from the local calendar on the next poll — this is the expected and accepted behavior for MVP per `What We're NOT Doing`).

**Implementation Note**: Pause after Phase 2b for manual verification with a real calendar client. The feed contract works on paper after the test suite passes, but the cross-client UX check is the most informative signal before Phase 3 builds UI on top.

---

## Phase 3: Settings page + topbar link

### Overview

User-visible surface. New `/settings` route, Thymeleaf template with copy UX + subscribe instructions + Google-limitations note (cites Phase 0's PRD section), and a topbar link. Lazy token mint happens here.

### Changes Required:

#### 1. `SettingsController`

**File**: `src/main/java/com/example/app/user/SettingsController.java` (new — colocated with `IcalSubscriptionService`)

**Intent**: One authenticated `@GetMapping("/settings")` handler that resolves the user, calls `IcalSubscriptionService.getOrCreateToken(user)`, constructs the full external feed URL, and renders the settings template.

**Contract**: `@Controller` with one method: `String settings(Authentication auth, Model model)`. Resolves user via `appUserRepository.findByEmail(auth.getName()).orElseThrow(...)` — same orElseThrow pattern as `EventController`, same "auth-to-entity F2 trade-off" the impl-review noted in S-02 (deferred to a future account-deletion slice). Calls `subscriptionService.getOrCreateToken(user)`. Builds the URL with `ServletUriComponentsBuilder.fromCurrentContextPath().path("/calendar/").path(token).path(".ics").build().toUriString()` — composes with the existing `server.forward-headers-strategy=framework` setting (`application.properties:22`) so the URL is `http://localhost:8080/calendar/<token>.ics` locally and `https://ogarniacz.fly.dev/calendar/<token>.ics` on Fly automatically (Fly's reverse proxy sends `X-Forwarded-Proto`/`X-Forwarded-Host`, which Spring honors). No `app.base-url` property, no `APP_BASE_URL` env var, no AppProperties extension. Adds `feedUrl`, `userEmail` to `model`. Returns `"settings"`.

#### 2. Settings Thymeleaf template

**File**: `src/main/resources/templates/settings.html` (new)

**Intent**: Renders the URL inside a readonly `<input>` (selectable by triple-click), a "Copy" button (Clipboard API + text-input-fallback), short "how to subscribe" blocks for the four target clients, and a labelled note about Google's two limitations citing the PRD §Known Limitations section.

**Contract**: Uses `<th:block th:replace="fragments/layout :: head('Settings')">` and `<header th:replace="fragments/layout :: topbar"></header>` for consistency with `app.html`. Page sections (in order):
1. **URL display**: `<input readonly th:value="${feedUrl}" id="feedUrl" />` + a `<button onclick="navigator.clipboard.writeText(document.getElementById('feedUrl').value)">Copy</button>` (no JS framework — inline `onclick` matches the existing CSP-permissive Thymeleaf style; if the project has a CSP policy by Phase 3 land time, fall back to a small script block in the page or move to a `js/` static file).
2. **How to subscribe** — four short paragraphs (one per client), each one sentence: where to paste the URL in Google Calendar (Settings → Add calendar → From URL), Apple Calendar (File → New Calendar Subscription), Outlook (Add calendar → Subscribe from web), Thunderbird (File → New → Calendar → On the Network).
3. **Known limitations note** — a labelled `<aside class="form-notice">` (reuse the existing notice style from `layout.html`) citing Phase 0's PRD §Known Limitations:
   > "Google Calendar limitations: Google polls subscribed calendars every 12-24 hours (sometimes up to 48). Google also doesn't show reminders embedded in subscribed calendars — set a per-calendar default reminder in Google Calendar settings to receive the morning-of-day-before reminder. Apple Calendar, Outlook, and Thunderbird honor both fully."
   Exact wording can be polished during implementation but must mention both gaps and the Google-side mitigation.

#### 3. Topbar link

**File**: `src/main/resources/templates/fragments/layout.html`

**Intent**: Add a `Settings` link to the topbar so the page is reachable. Sits to the left of (or above) the existing logout form.

**Contract**: Inside the `<header th:fragment="topbar" ...>` block, add `<a th:href="@{/settings}">Settings</a>` between `<span class="user-email">...</span>` and the logout `<form>`. Adjust the topbar's CSS minimally if needed (existing flex layout should handle one more child gracefully).

#### 4. `SettingsControllerTest`

**File**: `src/test/java/com/example/app/user/SettingsControllerTest.java` (new)

**Intent**: Integration test for the four contracts: anonymous → redirect to login, authenticated → 200 + URL present + URL contains the user's token, lazy mint persists, cross-user isolation.

**Contract**: `@SpringBootTest @AutoConfigureMockMvc @TestPropertySource(...)` matching the existing pattern. Test methods:

1. **`anonymousSettingsGetRedirectsToLogin`** — GET `/settings` without auth → 302 to `/login*`. Mirrors `EventControllerTest.anonymousGetEventsNewRedirectsToLogin`.

2. **`authenticatedGetRendersUrlWithUserToken`** — seed user with pre-set token `T`. GET `/settings` with `with(user(user.getEmail()))`. Assert body contains `T` AND `/calendar/` AND `.ics`.

3. **`firstVisitMintsTokenAndPersists`** — seed user with `icalToken == null`. GET `/settings` (authenticated). Assert response body contains some 32-char token; reload user from DB; assert `user.getIcalToken()` is now populated and matches the rendered string.

4. **`subsequentVisitsReuseSameToken`** — same user. GET twice. Assert both responses contain the same token; DB shows one mutation (subtle — verify by reading user before and after second GET; second GET should NOT change the token).

5. **`settingsShowsOwnTokenOnlyNotOtherUsersToken`** — seed alice + bob with two distinct tokens. GET `/settings` as alice. Assert body contains alice's token AND does NOT contain bob's token. Partition assertion (S-01 template).

6. **`settingsBodyMentionsGoogleLimitationsNote`** — sanity check on the UI copy. Assert body contains the substring "Google" and "subscribed" (loose match — exact wording is allowed to drift). Pins that the note exists; doesn't pin its exact wording.

### Success Criteria:

#### Automated Verification:

- `./gradlew test --tests com.example.app.user.SettingsControllerTest` passes (all 6 methods).
- `./gradlew test` full suite passes.
- `./gradlew build` passes.

#### Manual Verification:

- `./gradlew bootRun`, log in as the project author, click the new "Settings" link in the topbar. Page renders with the URL, copy button, the four subscribe blurbs, and the Google-limitations note.
- Click the Copy button. Clipboard contains the URL (verified by pasting in a text field).
- Visit the URL directly in the browser: feed contents render as text (not as a forced download, because no `Content-Disposition` header).
- Visit `/settings` a second time. URL is identical to the first visit (lazy mint stable).
- Log out, navigate to `/settings`. Redirected to `/login`.

**Implementation Note**: Pause after Phase 3 for manual verification of the UI on a real browser, including a copy-paste smoke test into Apple Calendar (or Thunderbird) end-to-end. This is the moment to validate the full user story US-03.

---

## Phase 4: test-plan §6.5 cookbook backfill

### Overview

Documentation chore. With Phases 0-3 landed, the patterns the iCal feed tests actually use are now stable and codified — backfill `test-plan.md §6.5` so the next contributor adding an iCal feed test has a template to follow. Solo phase, post-impl, no code.

### Changes Required:

#### 1. test-plan §6.5 cookbook entry

**File**: `context/foundation/test-plan.md`

**Intent**: Replace the existing `§6.5 TBD — see §3 Phase 2 + Phase 3` block with a real cookbook entry naming the test class layout, the assertions to mirror, the fixture-seeding pattern, and the DST date convention. Mirrors the shape of §6.1 (unit) and §6.2 (integration). Captures any rollout-phase notes in §6.6 if anything surprised the implementer (e.g. a folding edge case ical4j handled differently than expected).

**Contract**: Section structure mirroring §6.2 patterns:
- **Location**: `src/test/java/com/example/app/event/CalendarControllerTest.java` (integration) + `src/test/java/com/example/app/event/IcalFeedWriterTest.java` (pure unit).
- **Annotation stack**: integration uses the per-controller `@SpringBootTest` + `MockMvc` stack; unit uses pure JUnit 5.
- **Two-layer pattern**: serializer assertions go in `IcalFeedWriterTest` (parse output with ical4j `CalendarBuilder`, assert structurally — DST trigger times, VALARM presence, UID format, envelope properties). Endpoint assertions go in `CalendarControllerTest` (anonymous 200, unknown 404, cross-user partition, MIME + Cache-Control, deletion propagation E2E).
- **Reference tests**: explicit pointers to both new test classes.
- **DST fixture convention**: use real Europe/Warsaw transition dates (2026-03-29 spring-forward, 2026-10-25 fall-back). For tests asserting "the day after", pin to 2026-03-30 and 2026-10-26 respectively.
- **Seeding pattern**: integration tests seed via `appUserRepository.save(...)` + `eventRepository.save(...)` directly — no service layer involved for tests that only care about the feed-render contract.
- **Run locally**: `./gradlew test --tests com.example.app.event.IcalFeedWriterTest` (unit, milliseconds) and `./gradlew test --tests com.example.app.event.CalendarControllerTest` (integration, seconds).

Update §3 Phase 2 and Phase 3 status entries from `not started` to `complete` with the change-folder reference (`icalendar-feed-and-subscription`). Update §5 Quality Gates rows for "iCal feed contract tests" and "iCal feed access-control tests" from `planned`-implied to `required`.

If the implementation taught anything surprising (e.g. ical4j's TEXT-escaping diverged from expectations on a Polish-diacritic edge case; the empty-envelope round-trip required a non-obvious flag), append a 2-3 line note to §6.6 per the file's convention.

### Success Criteria:

#### Automated Verification:

- `grep -A2 "^### 6.5" context/foundation/test-plan.md` no longer contains the literal "TBD —".
- §3 Phase 2 and Phase 3 status columns read `complete` (not `not started`).
- §5 rows for iCal feed gates reference `required` (not `planned`).
- `./gradlew build` and `./gradlew test` still pass (sanity — doc edits don't break code).

#### Manual Verification:

- §6.5 reads like §6.1 and §6.2 in shape; a contributor can find the reference test, copy its annotation stack, and write a new iCal-feed test without reading the implementation source.
- §3 phase statuses are coherent with the rolled-out code: Phase 2 and Phase 3 (test-plan) are both complete; Phase 4 (upload pipeline) is still not started.

**Implementation Note**: Phase 4 has no test loop — the deliverable is documentation coherence. Land the edits, eyeball the diff, commit.

---

## Testing Strategy

### Unit Tests:

- `IcalTokenGeneratorTest` (Phase 1) — 1000-iteration charset + length + cardinality assertions. Catches encoding bugs and degenerate RNG.
- `IcalFeedWriterTest` (Phase 2a) — 12 methods parsing output via ical4j `CalendarBuilder`. Covers RFC 5545 envelope, VEVENT structure, VALARM absolute-trigger DST cases, Polish diacritics smoke test, empty envelope, UID stability.

### Integration Tests:

- `CalendarControllerTest` (Phase 2b) — 10 methods. Covers anonymous-200 (regression-pins `.permitAll()` order), unknown-404 (existence-leak avoidance), MIME + Cache-Control, cross-user partition (risk #4), deletion propagation E2E (risk #3), upcoming-only feed scope, empty-envelope HTTP response, path-traversal sanity.
- `SettingsControllerTest` (Phase 3) — 6 methods. Covers anonymous-redirect, authenticated-render, lazy-mint persistence, mint idempotency, cross-user partition, presence of the Google-limitations note copy.

### Manual Testing Steps:

1. After Phase 2b: `bootRun`, seed an event in the local DB, paste the feed URL into Apple Calendar (or Thunderbird) — events appear; the morning-of-day-before reminder shows up at 08:00 Warsaw local on a non-DST day.
2. After Phase 2b: paste the URL into Google Calendar — verify the events appear within the documented cadence; verify the reminder is silently absent (confirms the Google-limitation note is accurate). Optionally set a per-calendar default reminder in Google Calendar and verify it surfaces.
3. After Phase 3: log in via browser, click "Settings" in the topbar, copy the URL via the button, paste into a calendar client. Verify the round-trip end-to-end.
4. After Phase 3: visit `/settings` a second time, verify the same token is shown (lazy-mint idempotency at the UI layer).
5. After Phase 3: log out, navigate to `/settings`. Verify the redirect to `/login`.
6. (Optional, post-merge) Validate the rendered feed via [iCalendar Validator](https://icalendar.org/validator.html) for full RFC 5545 compliance — sanity check, not part of CI.

## Performance Considerations

No expected hotspots at MVP scale (single user, ≤ hundreds of events). The `findUpcomingByUser` query already uses the `ix_app_event_user_date` index. `findByIcalToken` rides PostgreSQL's auto-index on the UNIQUE column (O(log n)). ical4j serialization for hundreds of events is sub-millisecond. The feed endpoint is anonymous — no session loading, no SecurityContext propagation cost. Cache-Control disables shared caches but per-client polling is naturally throttled by the clients themselves (hourly at the fastest, daily at the slowest).

Future considerations (NOT this slice): if egress bandwidth becomes a concern, enable `server.compression.*` (free, marginal); if the per-client `ETag` / `Last-Modified` story matters, add conditional GET (~20 LOC), but only Thunderbird honors it on subscribed paths.

## Migration Notes

Additive only. `ical_token VARCHAR(32) UNIQUE NULL` on `app_user` is applied by `spring.jpa.hibernate.ddl-auto=update` on first startup post-deploy. Existing rows get a `NULL` column; the project author's row gets populated lazily the first time they visit `/settings`. No backfill code needed; no manual SQL needed.

Rollback path: drop the new column manually (`ALTER TABLE app_user DROP COLUMN ical_token`) if needed before further migrations land. `ddl-auto=update` does not drop columns automatically, so a forward-roll cleanly co-exists with old code that ignores the column.

## References

- Research: `context/changes/icalendar-feed-and-subscription/research.md`
- Test plan risks #3, #4, #6: `context/foundation/test-plan.md` §2 lines 48-51, §3 Phases 2+3
- PRD acceptance criteria: `context/foundation/prd.md` US-03 (lines 87-92), FR-012 (line 136), FR-013 (line 138), Access Control §iCalendar feed access (line 167)
- Roadmap S-04: `context/foundation/roadmap.md` lines 106-118
- Reference test patterns:
  - Pure JUnit + DST: `src/test/java/com/example/app/event/EventReminderTest.java`
  - Per-controller `@SpringBootTest`: `src/test/java/com/example/app/event/EventControllerTest.java`
  - Partition assertion: `src/test/java/com/example/app/AppApplicationTests.java` (`appShowsOwnEmailOnlyNotOtherUsersEmail`)
- ical4j 4.2.5 user guide (Context7): `/ical4j/ical4j-user-guide`
- RFC 5545 (iCalendar): https://datatracker.ietf.org/doc/html/rfc5545
- RFC 7986 (iCalendar new properties — `NAME`, `REFRESH-INTERVAL`): https://datatracker.ietf.org/doc/html/rfc7986

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 0: PRD Known Limitations amendment

#### Automated

- [x] 0.1 `grep -c "^## Known Limitations" context/foundation/prd.md` returns 1
- [x] 0.2 The section appears between `## Persistence` and `## Non-Goals`
- [x] 0.3 `./gradlew build` still passes

#### Manual

- [ ] 0.4 The new section reads as PRD prose (not a changelog entry)
- [ ] 0.5 The S-04 UI copy in Phase 3 is consistent with these bullets

### Phase 1: Token + persistence foundation

#### Automated

- [ ] 1.1 `./gradlew build` compiles after the dependency add
- [ ] 1.2 `./gradlew test --tests com.example.app.user.IcalTokenGeneratorTest` passes
- [ ] 1.3 `./gradlew test` full suite passes — no regressions
- [ ] 1.4 DB schema update observable at startup (Hibernate logs `alter table app_user add column ical_token`)

#### Manual

- [ ] 1.5 `./gradlew bootRun` against a freshly-migrated DB starts cleanly
- [ ] 1.6 `IcalTokenGenerator.next()` output is 32-char `[A-Za-z0-9_-]` (one-off check)

### Phase 2a: IcalFeedWriter — RFC 5545 serialization

#### Automated

- [ ] 2a.1 `./gradlew test --tests com.example.app.event.IcalFeedWriterTest` passes (all 12 methods)
- [ ] 2a.2 `./gradlew test` full suite passes
- [ ] 2a.3 Writer output parses cleanly via ical4j `CalendarBuilder` in every test

#### Manual

- [ ] 2a.4 Rendered feed validates on iCalendar Validator (one-off external check)
- [ ] 2a.5 Polish diacritics survive end-to-end in a sample render

### Phase 2b: CalendarController + security carve-out

#### Automated

- [ ] 2b.1 `./gradlew test --tests com.example.app.event.CalendarControllerTest` passes (all 10 methods)
- [ ] 2b.2 `./gradlew test` full suite passes — no regression in existing controller / security tests
- [ ] 2b.3 Clock-bean regression: autowired `Clock` reports `Europe/Warsaw` zone
- [ ] 2b.4 `./gradlew build` passes
- [ ] 2b.5 Existing routes (`/`, `/login`, `/signup`, `/events`, `/app`) retain pre-phase behavior

#### Manual

- [ ] 2b.6 `bootRun`, seed an event + token, `curl -i /calendar/<token>.ics` returns the feed
- [ ] 2b.7 Pasting the URL into Apple Calendar shows events with the morning reminder at 08:00 Warsaw
- [ ] 2b.8 `curl -i /calendar/<random-non-existent>.ics` returns 404
- [ ] 2b.9 Seed today + yesterday events; `curl` feed shows only today's UID (past-event scope confirmed)

### Phase 3: Settings page + topbar link

#### Automated

- [ ] 3.1 `./gradlew test --tests com.example.app.user.SettingsControllerTest` passes (all 6 methods)
- [ ] 3.2 `./gradlew test` full suite passes
- [ ] 3.3 `./gradlew build` passes

#### Manual

- [ ] 3.4 `bootRun`, log in, click Settings link, page renders correctly
- [ ] 3.5 Copy button populates the clipboard (verified by paste)
- [ ] 3.6 Visiting the URL in a browser renders as text (not a download)
- [ ] 3.7 Second `/settings` visit shows the same token (lazy-mint idempotency)
- [ ] 3.8 Anonymous `/settings` redirects to `/login`
- [ ] 3.9 End-to-end smoke: Settings → copy → paste into Apple Calendar/Thunderbird → events appear with reminder

### Phase 4: test-plan §6.5 cookbook backfill

#### Automated

- [ ] 4.1 `grep -A2 "^### 6.5" context/foundation/test-plan.md` no longer contains "TBD —"
- [ ] 4.2 §3 Phase 2 and Phase 3 status columns read `complete`
- [ ] 4.3 §5 rows for iCal feed gates reference `required`
- [ ] 4.4 `./gradlew build` and `./gradlew test` still pass

#### Manual

- [ ] 4.5 §6.5 shape matches §6.1 / §6.2 — a new contributor can follow it without reading impl source
- [ ] 4.6 §3 phase statuses are coherent with rolled-out code
