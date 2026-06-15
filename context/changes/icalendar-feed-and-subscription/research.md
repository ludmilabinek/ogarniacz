---
date: 2026-06-15T12:00:00+02:00
researcher: Ludmiła Drzewiecka
git_commit: d97a61b30982335e23fb7cf295698f4d61741573
branch: main
repository: ludmilabinek/ogarniacz
topic: "S-04 iCalendar feed + subscription — full pre-plan research"
tags: [research, codebase, icalendar, ical4j, spring-security, token-entropy, calendar-clients, s-04]
status: complete
last_updated: 2026-06-15
last_updated_by: Ludmiła Drzewiecka
---

# Research: S-04 iCalendar feed + subscription — full pre-plan research

**Date**: 2026-06-15 (Europe/Warsaw)
**Researcher**: Ludmiła Drzewiecka
**Git Commit**: d97a61b30982335e23fb7cf295698f4d61741573
**Branch**: main
**Repository**: ludmilabinek/ogarniacz

## Research Question

Prepare every input `/10x-plan` will need to plan S-04 (iCalendar feed + subscription) — the roadmap slice that lets a parent view + copy a unique unguessable iCalendar URL from a settings screen and have their accepted events delivered to any subscribed calendar with the morning-of-day-before VALARM. Scope chosen by the user via /10x-research scoping prompt: **Full S-04 prep** — covers iCal library + RFC 5545 compliance, the token model (entropy, storage, lookup), Spring Security anonymous carve-out for the feed endpoint, the existing Event entity + S-02 persistence shape, calendar-client polling/caching expectations, and reusable test patterns.

## Summary

S-04 lands on a **solid, well-conventioned foundation**. The Event entity from S-02 (`Event.java`, UUID id, `LocalDate eventDate`, nullable `LocalTime eventTime`, `String title/requirements/notes`, FK `@ManyToOne AppUser user`) already carries everything the feed serializer needs; the morning-of-day-before reminder is computed at view-time by `EventReminder` (zone-aware via `app.timezone` + `app.event.reminder.hour=8`) rather than stored on the entity, which is exactly the shape the VALARM serialization wants (relative `TRIGGER:-PT15H` from a date-only `DTSTART;VALUE=DATE`). The existing `SecurityConfig` is a single `SecurityFilterChain` with form-login + CSRF + remember-me; the upcoming anonymous feed endpoint slots in cleanly with one `.requestMatchers(HttpMethod.GET, "/calendar/*.ics").permitAll()` line placed **before** `anyRequest().authenticated()`. Test conventions are explicit (per-controller `@SpringBootTest`, `with(user(…))` + `with(csrf())` helpers, partition assertions from `appShowsOwnEmailOnlyNotOtherUsersEmail`) — S-04's two test phases (`test-plan.md` §3 Phase 2 + Phase 3) plug into the existing test infrastructure with zero new fixtures.

The roadmap's named open question — **ical4j vs hand-rolled VCALENDAR** — resolves to **ical4j 4.2.5** (`org.mnode.ical4j:ical4j:4.2.5`, BSD-3, actively maintained, Context7-indexed with 371 doc snippets at High reputation, 4 small transitive deps). ical4j costs ~30-50 LOC in the codebase vs ~180-250 LOC for hand-rolled, and the hand-rolled bug surface (CRLF terminators, line-folding at 75 *octets*, escaping `,;\` and newline in TEXT values, UTF-8 multi-byte boundary corner cases) is exactly the class of "client X silently drops events" failure the PRD's "RFC 5545 compliant, works with Google/Apple/Outlook/Thunderbird" NFR demands we avoid. The **unguessable URL token** resolves to `SecureRandom → 24 bytes → Base64URL no padding → 32 chars` (192 bits, comfortably above OWASP ASVS V3.2.2 and NIST 800-63B floors), stored as a single nullable `ical_token VARCHAR(32) UNIQUE` column on `AppUser`, populated lazily on first settings-screen access. Migration is a one-row no-op thanks to lazy generation + `ddl-auto=update`.

Two **PRD-touching risks** must land in the plan's risk register: (1) **Google Calendar polls subscribed feeds every 12-24 h, sometimes up to 48 h, and exposes no configuration knob** — Google subscribers may breach the PRD's "ceiling at most one day" freshness contract; this is a client constraint Ogarniacz cannot close server-side. (2) **Google Calendar effectively ignores VALARM in subscribed feeds** (community-documented, not in primary vendor docs) — the morning-of-day-before reminder fires on Apple/Outlook/Thunderbird but silently drops for Google users; they must set a per-calendar default reminder in Google Calendar's UI. The feed itself remains RFC-correct; the gap is at the client layer. Both should surface in the PRD as known limitations under S-04 and in user-facing subscribe-screen copy.

## Detailed Findings

### A. Live codebase inventory

#### A.1 Event entity & persistence (S-02 surface)

`src/main/java/com/example/app/event/Event.java:1-104` — Event JPA entity:

| Field | Java type | JPA / validation | Notes |
|---|---|---|---|
| `id` | `UUID` | `@Id @GeneratedValue` | PK. The UUID becomes the iCal `UID`'s local part — see §B.3. |
| `user` | `AppUser` | `@ManyToOne(fetch = LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false)` | Mandatory FK. Lazy-loaded. |
| `eventDate` | `LocalDate` | `@Column(name = "event_date", nullable = false)` | SQL DATE. Required. |
| `eventTime` | `LocalTime` | `@Column(name = "event_time")` | SQL TIME, **nullable** — date-only is the dominant case (PRD FR-004). |
| `title` | `String` | `@Column(nullable = false, length = 200)` | Required. Max 200. |
| `requirements` | `String` | `@Column(length = 2000)` | Nullable. Max 2000. Merged "what to bring" + dress code per PRD FR-004. |
| `notes` | `String` | `@Column(length = 2000)` | Nullable. Max 2000. |
| `createdAt` | `Instant` | `@Column(name = "created_at", nullable = false, updatable = false)` set via `@PrePersist` | Audit timestamp. |

Table indexes: `@Table(name = "app_event", indexes = { @Index(name = "ix_app_event_user_date", columnList = "user_id,event_date") })`.

**Default reminder is NOT stored on the entity.** It is computed at view/export time by `EventReminder` (separate helper class) which returns a `ZonedDateTime` of `event_date.minusDays(1)` at the configured wall-clock hour (`app.event.reminder.hour=8`) in the configured zone (`app.timezone=Europe/Warsaw`). For the VALARM serialization in §B, this means the trigger is **always relative to `DTSTART`**, never an absolute timestamp — exactly the shape that survives Spring DST transitions cleanly.

`src/main/java/com/example/app/event/EventRepository.java:1-23` — Spring Data repository with one custom finder:

```java
@Query("""
        select e from Event e
        where e.user = :user
          and e.eventDate >= :today
        order by e.eventDate asc, e.eventTime asc nulls last
        """)
List<Event> findUpcomingByUser(@Param("user") AppUser user, @Param("today") LocalDate today);
```

JPQL `nulls last` works on both Postgres (prod) and H2 (test). Spring Data derived methods cannot express null-handling, hence the explicit `@Query`. **The feed query should follow this same shape** — find the user via token, then call `findUpcomingByUser` (or a `findAllByUser` variant if the feed should also include past events; see §H Open Questions).

#### A.2 EventController & view layer (S-02 surface)

`src/main/java/com/example/app/event/EventController.java:1-58`:

- **GET `/events/new`** (`:25-31`) — renders form template, seeds empty `EventForm` model attribute if absent.
- **POST `/events`** (`:33-53`) — `@Valid @ModelAttribute("eventForm") EventForm form, BindingResult result, Authentication auth`. Resolves user via `appUserRepository.findByEmail(auth.getName()).orElseThrow()`. On validation error: re-renders `events/new`. On success: persists via `eventRepository.save(...)`, redirects to `/app` (POST/Redirect/GET).

**Auth pattern S-04 inherits**: `Authentication auth` method parameter (Spring Security injects it); `auth.getName()` returns the user's email. **For S-04's anonymous feed endpoint**, this pattern does NOT apply — the endpoint resolves the user via the token in the URL, not via session.

Thymeleaf templates touched by event flows (`src/main/resources/templates/`):
- `events/new.html` (34 lines) — form, `th:action="@{/events}"` (Thymeleaf auto-injects CSRF via `CsrfRequestDataValueProcessor`), inline field errors via `#fields.hasErrors('field')`.
- `app.html` (23 lines) — personal view, "Add event" button to `/events/new`, event list (`<ul class="event-list">`), empty state.
- `fragments/layout.html` (50 lines) — shared `head(title)` + `topbar` fragments. The `topbar` already includes a logout POST form; **S-04 will need a Settings link added here** that points to a new `/settings` route (the URL display screen).

#### A.3 User + SecurityConfig (S-01 surface)

`src/main/java/com/example/app/user/AppUser.java:1-62` — User entity:

| Field | Java type | JPA / validation | Notes |
|---|---|---|---|
| `id` | `UUID` | `@Id @GeneratedValue` | PK. |
| `email` | `String` | `@Column(unique = true, nullable = false, length = 254)` | RFC 5321 max. Lowercased + trimmed in constructor. |
| `passwordHash` | `String` | `@Column(name = "password_hash", nullable = false, length = 60)` | BCrypt (60 chars exactly). |
| `createdAt` | `Instant` | `@Column(name = "created_at", nullable = false, updatable = false)` set via `@PrePersist` | Audit. |

**S-04 will add `ical_token`** here. See §C.2 for the exact column spec.

`src/main/java/com/example/app/user/AppUserRepository.java` — `JpaRepository<AppUser, UUID>` with `findByEmail(String)` and `existsByEmail(String)`. **S-04 adds `Optional<AppUser> findByIcalToken(String)`** — UNIQUE column lookup, O(log n) via PostgreSQL's auto-index for UNIQUE constraints.

`src/main/java/com/example/app/config/SecurityConfig.java:67-90` — the `SecurityFilterChain` bean verbatim:

```java
@Bean
public SecurityFilterChain filterChain(HttpSecurity http,
                                       RememberMeServices rememberMeServices,
                                       SecurityContextRepository securityContextRepository) throws Exception {
  http
      .authorizeHttpRequests(auth -> auth
          .requestMatchers("/", "/signup", "/login", "/actuator/health",
              "/css/**", "/js/**", "/favicon.ico", "/error").permitAll()
          .anyRequest().authenticated()
      )
      .formLogin(login -> login
          .loginPage("/login")
          .loginProcessingUrl("/login")
          .defaultSuccessUrl("/app", false)
          .failureUrl("/login?error")
          .permitAll()
      )
      .logout(logout -> logout.logoutSuccessUrl("/login?logout"))
      .rememberMe(rm -> rm
          .rememberMeServices(rememberMeServices)
          .key(rememberMeKey)
      )
      .securityContext(sc -> sc.securityContextRepository(securityContextRepository));
  return http.build();
}
```

**S-04 modification**: one line inserted at the top of `authorizeHttpRequests` — see §C.3.

#### A.4 Test layout & reusable fixtures

`src/test/java/com/example/app/`:

| Test class | Annotation stack | Reusable for S-04? |
|---|---|---|
| `AppApplicationTests` | `@SpringBootTest @AutoConfigureMockMvc @TestPropertySource(properties = "REMEMBER_ME_KEY=test-key-not-for-production")` | Pattern for full-context auth + partition tests. **Two-user partition assertion `appShowsOwnEmailOnlyNotOtherUsersEmail` is the template for S-04 Phase 3.** |
| `EventRepositoryTest` | `@DataJpaTest @AutoConfigureTestDatabase(replace = NONE)` | Pattern for repository-layer tests. **Reused for `findByIcalToken` if a unit-only entropy/ordering test is added there.** |
| `EventControllerTest` | `@SpringBootTest @AutoConfigureMockMvc @TestPropertySource(...)` | **Template for the upcoming `CalendarControllerTest`** (anonymous GET assertion, content-type assertion, cross-user partition). |
| `EventReminderTest` | Pure JUnit 5 (no Spring context) | Pattern for value-class tests with DST coverage. **Reused conceptually for `IcalFeedWriterTest` if an isolated test of the serializer is added** (VALARM trigger across DST). |
| LLM family (`LlmTestFixtures`, `LlmVisionClientTest`, etc.) | Various; uses `@MockitoBean ChatModel` | Not relevant to S-04 (zero coupling to LLM package). |

Auth helpers in use everywhere: `.with(user("email"))` (mocks an authenticated principal), `.with(csrf())` (required for POSTs), `formLogin("/login").user(email).password(password)` (full login flow). **S-04's tests need NONE of these for the feed endpoint** — anonymous GET is the contract.

H2 fallback: `testRuntimeOnly 'com.h2database:h2'` in `build.gradle:37`. Confirms the lesson from `lessons.md` ("Verify `./gradlew test` post-bootstrap; if `DataSourceBeanCreationException` → add H2 test-runtime before first commit") was applied and is still load-bearing.

#### A.5 build.gradle + application.properties

`build.gradle` highlights for S-04:

```gradle
implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
implementation 'org.springframework.boot:spring-boot-starter-security'
implementation 'org.springframework.boot:spring-boot-starter-validation'
implementation 'org.springframework.boot:spring-boot-starter-webmvc'
implementation 'org.springframework.boot:spring-boot-starter-thymeleaf'
implementation 'org.springframework.boot:spring-boot-starter-actuator'
// ... S-04 will add:
// implementation 'org.mnode.ical4j:ical4j:4.2.5'
```

Spring Boot 4.0.6 — note: web starter is `spring-boot-starter-webmvc` (not `-web`), test starters follow the same pattern. Java 21 toolchain. **No Flyway / Liquibase** — schema evolves via `spring.jpa.hibernate.ddl-auto=update`; S-04 stays additive (one column on `app_user`).

`src/main/resources/application.properties:1-41` relevant slices:

```properties
spring.jpa.hibernate.ddl-auto=update
server.servlet.session.cookie.secure=true
server.servlet.session.cookie.http-only=true
server.servlet.session.cookie.same-site=lax
server.forward-headers-strategy=framework
app.timezone=Europe/Warsaw
app.event.reminder.hour=8
```

- `server.forward-headers-strategy=framework` (line 22) — already honors `X-Forwarded-Proto` from Fly's edge; HSTS will emit on production responses without further config.
- `server.servlet.session.cookie.secure=true` (line 19) — overridable to `false` for local dev via env var. S-04 does NOT need to touch cookies (feed is bearer-token via URL, not cookie-based).
- `app.timezone` + `app.event.reminder.hour` — already drive `EventReminder`. **The feed's VALARM serialization will derive from these same properties** — single source of truth for "morning of day before" semantics.
- No profile-based split. All env-specific config flows through env vars (`SPRING_DATASOURCE_*`, `REMEMBER_ME_KEY`, `OPENROUTER_API_KEY`).

#### A.6 LLM package — one-paragraph note

`src/main/java/com/example/app/llm/` contains `LlmVisionClient` interface, `OpenRouterLlmVisionClient` implementation (Spring AI `ChatClient` wrapper), `LlmExtractionResult` record, `LlmExtractionException` types, JSON parsing helpers. **S-04 has zero coupling to this package.** The feed endpoint works against persisted `Event` entities that have already been accepted by the parent (currently via manual entry; future via image extraction in S-05) — it does not invoke the LLM. Confirmed independent surface.

#### A.7 Archive insights — conventions S-04 must respect

From `context/archive/2026-05-26-minimal-auth-and-empty-personal-view/` (S-01) and `context/archive/2026-06-07-manual-event-entry/` (S-02):

- **Email normalization** is enforced at three chokepoints (`SignupController`, `AppUserDetailsService`, `AppUser` constructor) using `Locale.ROOT` (no Unicode normalization for ASCII). S-04 does not touch emails — note for future planning.
- **CSRF is mandatory** for any form endpoint; Thymeleaf `th:action="@{/...}"` triggers auto-injection. **S-04's feed endpoint is GET-only and anonymous, so CSRF does not apply.** A future "regenerate token" UI POST would require CSRF — out of MVP scope.
- **Auto-login pattern** (used by signup): flush row → build `UsernamePasswordAuthenticationToken` from saved entity inline (do NOT re-read via `UserDetailsService`) → `SecurityContextHolder.getContext().setAuthentication(...)` → `RememberMeServices.loginSuccess(...)`. S-04 does not auto-login any flow; pattern noted for future planning.
- **Per-controller `@SpringBootTest` class is the test layout standard** (codified as lesson; see `context/foundation/lessons.md`). **S-04 creates `CalendarControllerTest`** — does not extend `AppApplicationTests`.
- **`orElseThrow()` at auth-to-entity boundary returns 500 if the user row vanishes** — S-02 impl-review F2 flagged this and deferred the fix to a later slice (account deletion). S-04 inherits the same trade-off: a token-to-user lookup that returns `Optional.empty()` should **return 404** (not 500, not 401 — see §C.4 for the "no user-existence leak" rationale).
- **Test layout: `appShowsOwnEmailOnlyNotOtherUsersEmail` partition assertion pattern** (S-01) is the direct template for S-04 Phase 3's cross-account isolation test: seed two users with two tokens, assert that alice's token returns alice's events only.
- **Asymmetric `.trim()` lesson** (S-02 impl-review F3): the `trimOrNull` helper applies to all text fields. S-04 has no user-supplied text on the feed-render path (token is alphanumeric only, no whitespace possible), so this lesson is informational.

#### A.8 Test-plan obligations touching S-04

`context/foundation/test-plan.md` §2 Risk Map flags three risks directly belonging to S-04:

- **Risk #3** (line 48-49): *"feed freshness or deletion propagation regresses"* — feed query, token lookup, UID stability across edits. Impact High, Likelihood Medium. Source: PRD FR-013 + NFR. Required assertions: accepted events appear in feed; deleted events disappear; rejected/unaccepted never appear; **freshness is observable per request, not per deploy**. Cheapest layer: integration (MockMvc / WebTestClient against `/calendar/<token>.ics` with seeded fixtures).
- **Risk #4** (line 49): *"cross-account leakage via iCal token"* — token entropy, isolation by token, non-issued tokens return 404. Impact High, Likelihood Medium. Required assertions: alice's token returns alice's events only; random non-issued token returns 404 (not 200-empty); generator entropy asserted at unit boundary. Cheapest layer: integration cross-user + classic unit on the generator.
- **Risk #6** (line 51): *"reminder fires at wrong wall-clock time on DST day in iCal output"* — VALARM/VTIMEZONE serialization, even though `EventReminder` itself is right. Required assertion: feed VALARM for an event on the day after spring-forward / fall-back resolves to 08:00 wall-clock in `Europe/Warsaw`. Cheapest layer: integration test parametrized by DST date.

`context/foundation/test-plan.md` §3 Phased Rollout has two phases mapped to S-04, both `not started`:

| Phase | Goal | Risks | Test types |
|---|---|---|---|
| Phase 2 | iCal feed serialization + freshness | #3, #6 | integration (MockMvc/WebTestClient against feed endpoint) |
| Phase 3 | iCal feed access control (abuse lens) | #4 | integration cross-user MockMvc + classic unit on the generator |

**S-04's plan should align its phases to these two test-plan phases.** §6.5 of the test-plan ("Adding an iCal feed test") is currently `TBD` and points to §3 Phases 2+3 — the cookbook entry should be backfilled as the implementation lands.

### B. iCalendar library + RFC 5545 compliance

#### B.1 Library decision — ical4j 4.2.5

**Verdict: use ical4j 4.2.5** (`org.mnode.ical4j:ical4j:4.2.5`).

| Dimension | ical4j 4.2.5 | biweekly 0.6.8 | Hand-rolled |
|---|---|---|---|
| Maven coord | `org.mnode.ical4j:ical4j:4.2.5` | `net.sf.biweekly:biweekly:0.6.8` | — |
| Last release | Apr 2026 | Jan 2024 (last formal); sporadic commits | n/a |
| License | BSD-3-Clause | BSD-2-Clause | n/a |
| GitHub activity | 836★, active `develop` (3.7k commits) | 347★, 9-14-mo gaps | n/a |
| Java baseline | 11+ (4.x uses `java.time`) | 1.6+ | 21 here |
| Transitive deps | `slf4j-api`, `commons-codec`, `commons-lang3`, `threeten-extra` | "Few external deps" — bundles its own | None |
| Context7 indexing | High reputation, **371 snippets** at `/ical4j/ical4j-user-guide` | Not indexed | n/a |
| Expected LOC for our writer | **30-50 LOC** | ~80-100 LOC | **180-250 LOC** |

ical4j wins on LOC, agent-friendliness (Context7 fluency), and risk surface. Hand-rolled gotchas it avoids:
- **CRLF terminators** (RFC 5545 §3.1) — `\r\n` mandatory; LF-only files cause Apple Calendar to silently drop events and Outlook to reject the file.
- **Line folding at 75 octets** (not characters) with `CRLF + SPACE` continuation. UTF-8 multi-byte sequences (Polish `ą`, `ł`, `ś` are 2 bytes; emoji 4) require backing up to a continuation-byte boundary. Single most common hand-roll bug.
- **TEXT escaping** (RFC 5545 §3.3.11) — backslash precedes `,` `;` `\` and newline (which becomes literal `\n`).
- **Mandatory headers** — `BEGIN:VCALENDAR / VERSION:2.0 / PRODID / END:VCALENDAR`. `CALSCALE:GREGORIAN` is defaulted but conventionally emitted.

Dependency line for `build.gradle`:

```gradle
implementation 'org.mnode.ical4j:ical4j:4.2.5'
```

No JVM args needed (ical4j 4.x uses `java.time`, not legacy `SimpleDateFormat`; JDK 21 defaults to CLDR — see [JEP 252](https://openjdk.org/jeps/252)).

#### B.2 RFC 5545 essentials for the writer

**VCALENDAR envelope** (cite to [RFC 5545](https://datatracker.ietf.org/doc/html/rfc5545) and [RFC 7986](https://datatracker.ietf.org/doc/html/rfc7986)):

| Property | Value type | RFC | Notes |
|---|---|---|---|
| `VERSION` | TEXT `2.0` | 5545 §3.7.4 | Mandatory. |
| `PRODID` | TEXT | 5545 §3.7.3 | Mandatory. Form: `-//Ogarniacz//Ogarniacz Feed 1.0//EN`. |
| `CALSCALE` | TEXT `GREGORIAN` | 5545 §3.7.1 | Defaulted; emit for older clients. |
| `X-WR-CALNAME` | TEXT (X-prop) | non-standard | Honored by Apple + Outlook; ignored by Google + Thunderbird. |
| `NAME` | TEXT | 7986 §5.1 | Standards-track replacement for X-WR-CALNAME. **Emit both** for safety. |
| `X-PUBLISHED-TTL` | DURATION | non-standard (MS-OXCICAL) | Historically honored by Outlook; harmless elsewhere. |
| `REFRESH-INTERVAL;VALUE=DURATION` | DURATION | 7986 §5.7 | Standards-track polling hint. Emit alongside `X-PUBLISHED-TTL`. |

**VEVENT body**:

| Property | Value type | RFC | Notes for S-04 |
|---|---|---|---|
| `UID` | TEXT | 5545 §3.8.4.7 | Globally unique + stable across edits. **Form `<event.id>@ogarniacz.fly.dev`** — see §B.3. |
| `DTSTAMP` | DATE-TIME, UTC | 5545 §3.8.7.2 | Time the ICS was generated, NOT event time. Suffix `Z`. |
| `DTSTART` | DATE or DATE-TIME | 5545 §3.8.2.4 | `DTSTART;VALUE=DATE:20260620` for date-only; `DTSTART;TZID=Europe/Warsaw:20260620T080000` for timed. |
| `DTEND` | DATE or DATE-TIME | 5545 §3.8.2.2 | **For date-only, emit explicitly** as exclusive end: `DTEND;VALUE=DATE:20260621` for a single-day event on the 20th. Avoids edge-case renderer bugs. |
| `SUMMARY` | TEXT | 5545 §3.8.1.12 | Event title; subject to §3.3.11 escaping. |
| `DESCRIPTION` | TEXT | 5545 §3.8.1.5 | Used for `requirements` + `notes` concatenated. Newlines become literal `\n`. |
| `LOCATION` | TEXT | 5545 §3.8.1.7 | Optional (cardinality 0..1). Omit in MVP — no location field on Event entity. |

**VALARM — "morning of day before"** (RFC 5545 §3.6.6):

```
BEGIN:VALARM
ACTION:DISPLAY
TRIGGER:-PT15H
DESCRIPTION:Ogarniacz reminder
END:VALARM
```

Rationale for `TRIGGER:-PT15H` (relative, 15 hours before DTSTART):
- For `DTSTART;VALUE=DATE:20260620`, RFC 5545 §3.3.4 treats floating dates as local midnight on the client — `00:00` on June 20 minus 15 hours = **June 19, 09:00 local**. That is exactly "morning of the day before".
- Survives event edits (DTSTART change → trigger shifts with it).
- No timezone to embed → no DST bug surface.
- Alternative — absolute `TRIGGER;VALUE=DATE-TIME:20260619T070000Z` — requires recomputing on every render and breaks if the client re-emits the event.

`ACTION:DISPLAY` + `TRIGGER` are mandatory in VALARM; `DESCRIPTION` is mandatory when `ACTION:DISPLAY`. Spec ([RFC 9074](https://datatracker.ietf.org/doc/html/rfc9074) extends but does not change this baseline).

**Line endings + folding + MIME**: ical4j handles these correctly out of the box (one of the strongest arguments against hand-rolling).

MIME type: `text/calendar; charset=utf-8` ([RFC 5545 §8.1 IANA registration](https://www.iana.org/assignments/media-types/text/calendar)).

#### B.3 UID strategy — forward-compat with S-03

Form: **`<event.id>@ogarniacz.fly.dev`** where `event.id` is the existing `UUID` column on `Event`.

- Globally unique (UUID space) and stable (entity PK never changes).
- Satisfies [RFC 7986 §5.3](https://datatracker.ietf.org/doc/html/rfc7986#section-5.3) ("UID values MUST NOT include any data that might identify a user, host, domain, or any other security-sensitive information") — `ogarniacz.fly.dev` is the public host, not user-identifying.
- Deterministic from the entity → S-03's edit path needs no new column.
- S-03's edit MUST NOT change `event.id`; only mutable fields. Locked here.
- S-03's delete drops the event from the feed; client removes it on next poll (one polling cycle of lag, as PRD anticipates).

`SEQUENCE` is conventionally emitted as `SEQUENCE:0` initially and incremented on edit (RFC 5545 §3.8.7.4). In practice, all four target clients update events in place on UID match alone even without SEQUENCE — but emitting it is the safe extra mile. ical4j increments it automatically when you mutate a Calendar's properties; not load-bearing in S-04 (no edit path yet), but the writer should output `SEQUENCE:0` on every event for forward-compat.

#### B.4 HTTP response shape

| Header | Value | Rationale |
|---|---|---|
| `Content-Type` | `text/calendar; charset=utf-8` | RFC 5545 §8.1; without the charset, Polish diacritics get mangled by transports that downgrade to ASCII. |
| `Content-Disposition` | **omit** | `attachment; filename=…` triggers a browser download, which is the wrong UX for a parent who opens the URL in a browser to copy it. The calendar's display name comes from `X-WR-CALNAME`/`NAME`, not from the filename. |
| `Cache-Control` | `private, no-cache, max-age=0` | Per-user URL is effectively a bearer token; shared/CDN caches MUST NOT store. `no-cache` forces revalidation. |
| `Vary` | `Accept` | Defensive — prevents content-negotiation cache poisoning if a future variant lands. |
| `ETag` / `Last-Modified` | **skip in V1** | Only Thunderbird reliably honors conditional GETs on subscribed paths; Google + Outlook subscribed fetchers largely ignore them. Cost/benefit favors skipping until egress bandwidth becomes a concern. |
| `Strict-Transport-Security` | (auto, from Spring Security defaults) | Spring Security emits HSTS on HTTPS responses; `server.forward-headers-strategy=framework` already lets Spring see Fly's edge TLS via `X-Forwarded-Proto`. |
| gzip | optional, low priority | Spring Boot supports it via `server.compression.*`; feed bodies are small (tens of KB) and savings are marginal. Skip in V1. |

#### B.5 ical4j-specific gotchas (V1)

- **No JVM args** required on JDK 21 with ical4j 4.x.
- **Validator strictness** matters only when *parsing*. The writer doesn't need to call `calendar.validate()` (and shouldn't routinely); let the output speak. Compatibility hints exist for parsing (e.g., `KEY_RELAXED_VALIDATION`) but are not needed for producing.
- **Thread safety**: instantiate `CalendarOutputter` per request (effectively stateless after construction; safer than sharing). `CalendarBuilder` (parser) is not relevant — we don't parse.
- **Logging**: silence with `logging.level.net.fortuna.ical4j=WARN` if startup TZ-registry loading is noisy. No impact at single-user MVP scale.

### C. Token entropy + Spring Security carve-out

#### C.1 Token entropy & generation

**Concrete spec: `SecureRandom → 24 bytes → Base64URL no padding → 32 chars` (192 bits).**

Citations:
- [OWASP ASVS V3.2.2](https://github.com/OWASP/ASVS/blob/master/4.0/en/0x12-V3-Session-management.md) — *"at least 64 bits of entropy"* (floor, for session tokens that ride alongside separate auth).
- [NIST SP 800-63B §5.1](https://pages.nist.gov/800-63-3/sp800-63b.html) — *"at least 64 bits in length, generated using an approved random bit generator"*.
- [OWASP Session Management Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html) — 128-bit recommended for tokens not handled by an end-user with separate auth.

For Ogarniacz, the token IS the authentication channel — bearer-style, stable forever, lives in user calendar exports. Bumping one notch above 128 bits → 192 bits. Margins:

| Bytes | Bits | Base64URL chars |
|------:|-----:|----------------:|
| 16 | 128 | 22 |
| **24** | **192** | **32** |
| 32 | 256 | 43 |

24 bytes / 32 chars is the right balance: comfortably above any cited floor, doesn't bloat URL length, double-click-selectable in browsers/Slack/WhatsApp.

URL shape: `https://ogarniacz.fly.dev/calendar/AbCdEfGhIjKlMnOpQrStUvWxYz012345.ics` (~75 chars total — well under any 2 KB URL ceiling).

Reference snippet (illustrative — not production code):

```java
private static final SecureRandom RNG = new SecureRandom();
private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();

static String newIcalToken() {
    byte[] buf = new byte[24]; // 192 bits
    RNG.nextBytes(buf);
    return ENC.encodeToString(buf); // 32 chars, [A-Za-z0-9_-]
}
```

Notes:
- `SecureRandom` (no-arg constructor), NOT `ThreadLocalRandom` (LCG, predictable from seed).
- NOT `SecureRandom.getInstanceStrong()` — blocks on `/dev/random` on Linux; can stall under low entropy. Default `SecureRandom` on Linux uses `NativePRNG` against `/dev/urandom`, which is the right choice.
- `Base64URL` (RFC 4648 §5) — alphabet `[A-Za-z0-9_-]`, all RFC 3986 "unreserved" → no percent-encoding gymnastics.

**Brute-force resistance at 192 bits**: probability per guess ≈ 1.6 × 10⁻⁵⁸. At an absurd 1 billion req/s, expected time to one hit ≈ 10⁴⁹ years (universe age ≈ 1.4 × 10¹⁰ yr). Online enumeration is structurally impossible. Application-level rate limiting (Bucket4j etc.) is not present today and **not load-bearing at this entropy**; flag for the plan as nice-to-have, not blocking.

#### C.2 Storage pattern

**Single nullable column on `AppUser`**: `ical_token VARCHAR(32) UNIQUE NULL`, populated lazily on first settings-screen access.

JPA annotation:

```java
@Column(name = "ical_token", unique = true, length = 32)
private String icalToken;
```

- Column-level `unique = true` → Hibernate generates a UNIQUE constraint; PostgreSQL automatically backs it with a B-tree index ([source: pgsql-general list confirmation](https://www.postgresql.org/message-id/38C506F3.DD9F64ED%40lsil.com)). O(log n) lookup, no extra `@Index` needed.
- **Do NOT** combine `@Column(unique = true)` with `@Index(... unique = true)` for the same column — produces two indexes ([Baeldung: Defining Unique Constraints in JPA](https://www.baeldung.com/jpa-unique-constraints)).
- Nullable because of lazy generation. The migration concern is solved by lazy: existing users get a NULL column on next `ddl-auto=update` cycle, fill it themselves by visiting the settings screen once.

Decision rationale — column-on-AppUser vs separate `IcalSubscription` table:

| Aspect | Column on `AppUser` (chosen) | Separate `ical_subscription` table |
|---|---|---|
| Lookup join cost | Zero — `WHERE ical_token = ?` hits `app_user` index | One JOIN per feed poll |
| Migration churn | One additive `ALTER TABLE ADD COLUMN` | New table + FK + index + entity + repo |
| Rotation slice (post-MVP) | Add `previous_ical_token` + `rotated_at` — additive | Add `rotated_at` + maybe `status` enum |
| "One user → many feeds" pivot | Forced refactor (not on any roadmap slice) | Already supports it |

PRD US-03 explicitly says **the spouse shares the same URL** — never a second URL. Rotation is post-MVP. "One user → many feeds" is not in any planned slice. Single column wins on churn.

**Token-generation timing**: lazy, on first settings-screen visit. Reasoning:
- US-03's flow is *"Given a parent who has just signed up, When they visit the settings screen and copy their unique iCalendar URL, Then..."* — the token must exist by the time settings renders, **not before**.
- Lazy keeps the signup transaction clean (no `SecureRandom` in the registration path), and the one already-shipped user in production fills their own token by visiting `/settings` once. Zero migration code.
- Concrete shape: `IcalSubscriptionService#getOrCreateToken(AppUser user)`, `@Transactional`, called from the settings controller's GET handler.

**Migration option chosen**: nullable column + lazy population. The other two options (NOT NULL with hand-written DDL backfill, or `@PrePersist`-on-load listener) are overkill for the single-user production state and add moving parts that have no payoff.

#### C.3 Anonymous endpoint Spring Security 6 DSL

**Single `SecurityFilterChain`** — keep the existing chain, add one line.

```java
http
    .authorizeHttpRequests(auth -> auth
        // NEW — anonymous calendar feed; MUST precede anyRequest().authenticated()
        .requestMatchers(HttpMethod.GET, "/calendar/*.ics").permitAll()
        // existing allowlist
        .requestMatchers("/", "/signup", "/login", "/actuator/health",
                          "/css/**", "/js/**", "/favicon.ico", "/error").permitAll()
        .anyRequest().authenticated()
    )
    // formLogin, rememberMe, securityContext unchanged
    ;
```

Pattern correctness:
- `requestMatchers("/calendar/*.ics")` uses `MvcRequestMatcher` when `HandlerMappingIntrospector` is on the classpath (default for `spring-boot-starter-webmvc`). `*` matches one path segment — `/calendar/AbC123.ics` matches; `/calendar/foo/bar.ics` does not.
- `HttpMethod.GET` overload pins the rule to GET, leaving the path namespace clean for any future POST (none planned).

Ordering rule — **first match wins** ([Spring Security 6.5 reference: Authorize HttpServletRequests](https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html)). **`/calendar/*.ics` MUST come before `anyRequest().authenticated()`**, or the rule is silently shadowed. Plan must flag this as a regression risk worth one test (assert anonymous GET returns 200, not 302/401).

**CSRF**: Spring Security's default CSRF token requirement applies only to state-changing methods (RFC 7231 §4.2.1 "safe methods"). GET-only feed endpoint is unaffected. No `.csrf().ignoringRequestMatchers(...)` needed.

**Form-login interference**: `.permitAll()` short-circuits `ExceptionTranslationFilter`, so unauthenticated requests proceed to the controller and are NOT 302'd to `/login`. Contract to assert in the integration test:

```java
mvc.perform(get("/calendar/{token}.ics", validToken))
   .andExpect(status().isOk())
   .andExpect(header().string("Content-Type", containsString("text/calendar")));
// must NOT 302
```

**Single chain vs two chains**: single chain wins on simplicity. The premise for splitting (form-login could leak in) doesn't hold once `.permitAll()` is configured. Split only if a future slice genuinely needs different auth machinery — not the case here.

#### C.4 Leak / enumeration risk

- **URL pasted in a public chat**: PRD-accepted residual risk. Rotation is post-MVP. The plan's risk register should state this explicitly: *"Accepted residual risk: a leaked URL grants read access to the user's accepted events indefinitely. Mitigation deferred to the rotation slice (post-MVP)."*
- **Unknown token → 404**, NOT 401. Returning 401 would distinguish "this token doesn't exist" from "this endpoint doesn't exist", leaking the existence of the token-namespace endpoint. **Both states must return 404 with an empty/generic body.**
- **Logging**: the token must not appear in persistent logs. Tomcat access logs are off by default in this project (no `server.tomcat.accesslog.enabled=true`), so this is dormant. If access logging is later enabled, configure the pattern to omit `%U` for `/calendar/**`, or use a servlet filter to mask the token segment. Application-level loggers should log `path=/calendar/<redacted>.ics`, never the raw path.
- **Cache-Control: private, no-cache, max-age=0** — prevents shared HTTP caches (CDNs, corporate proxies) from holding the body. Spring Security's default headers handle this for authenticated UI responses; the anonymous feed endpoint should set it explicitly on the controller because Spring Security's defaults may not apply uniformly to a `permitAll()` endpoint. Verify in the integration test.
- **HTTPS-only**: `server.servlet.session.cookie.secure=true` + `server.forward-headers-strategy=framework` already correctly classify Fly-edge traffic as HTTPS. Spring Security emits HSTS on HTTPS responses by default ([Spring Security 6.5 default security headers](https://docs.spring.io/spring-security/reference/6.5/features/exploits/headers.html)). No change needed; verify HSTS appears in the integration test.

#### C.5 Rotation forward-compat (do NOT implement in S-04)

- Single column named **`ical_token`** (not `current_ical_token`). The latter would prejudice the design and signal a missing `previous_ical_token`.
- The rotation slice (post-MVP) adds `previous_ical_token VARCHAR(32) NULL` + `rotated_at TIMESTAMP NULL`, and the lookup becomes `WHERE ical_token = ? OR previous_ical_token = ?` with grace-window logic. Additive only — satisfies `application.properties:13`'s additive-only invariant.
- **Do NOT pre-add `previous_ical_token` or `rotated_at` in S-04.** Rotation needs more than columns (UI to trigger, grace-window service, optional mailer) — pre-adding columns without the logic clutters the schema. YAGNI.

#### C.6 Verification suggestions

Per the per-controller `@SpringBootTest` standard, `CalendarControllerTest` would carry all three of these in one class (matching the pattern from `EventControllerTest`):

- **Unit-level — token format regression**: 1000 successive `IcalTokenGenerator#next()` calls. Every result matches `^[A-Za-z0-9_-]{32}$`. Set cardinality is 1000 (no duplicates). Catches encoding bugs (padding crept back in, standard Base64 alphabet substituted) and degenerate RNG (`Random` instead of `SecureRandom`).
- **Integration — anonymous GET**: with a seeded `AppUser` carrying a known token, unauthenticated `MockMvc` GET to `/calendar/{token}.ics` returns 200, `Content-Type: text/calendar`, body contains `BEGIN:VCALENDAR`. Assertion: **NOT 302** (asserts `.permitAll()` short-circuits form-login).
- **Negative — unknown token returns 404**: GET to `/calendar/<32-char-random-not-in-DB>.ics` returns 404 with empty/generic body. Assertion: **NOT 401** (avoids existence leak).
- **Partition (cross-user)**: seed alice + bob with two tokens. Alice's token returns alice's events only. Mirrors `appShowsOwnEmailOnlyNotOtherUsersEmail` (S-01 partition assertion template).
- **DST-day VALARM** (Risk #6): seed an event on the day after spring-forward / fall-back. Parse the rendered feed's VALARM. Assert it resolves to 08:00 wall-clock in `Europe/Warsaw`.

### D. Calendar-client polling + caching behavior

#### D.1 Polling cadence per client

| Client | Default refresh | User-configurable? | Verdict vs PRD "ceiling at most one day" |
|---|---|---|---|
| **Google Calendar** | Not officially published; community-observed **12-24 h, sometimes up to 48 h** | **No UI control** | **Marginal.** Documented outliers up to 48 h breach the PRD ceiling. Not configurable. Ignores `REFRESH-INTERVAL` / `X-PUBLISHED-TTL`. |
| **Apple iCloud Calendar** | User-selectable; **default is "Manual"** until set; presets: 5 min / 15 min / hourly / daily / weekly | Yes (Get Info → Auto-refresh) | **Depends on user.** "Every day" or "Manually" → just barely; hourly → easily under contract. |
| **Outlook.com / OWA / M365** | Outlook.com ≈ 3 h, OWA ≈ 6 h, ">24 h possible" | No | **Yes on paper**, tight on outliers. |
| **Thunderbird** | "Every 30 minutes" once configured; presets: Manual / 30 min / 60 min | Yes (calendar Properties) | **Yes.** Well inside contract. |

Sources: [Google: gene1wood gist](https://gist.github.com/gene1wood/02ed0d36f62d791518e452f55344240d), [ryadel.com](https://www.ryadel.com/en/google-calendar-force-update-refresh-subscribed-calendar-ics/), [Teamup KB](https://calendar.teamup.com/kb/what-you-need-to-know-about-icalendar-feeds/), [usecarly.com](https://www.usecarly.com/blog/google-calendar-ics-refresh-rate/) · [Apple Support: Refresh calendars on Mac](https://support.apple.com/guide/calendar/refresh-calendars-icl1024/mac) · [Microsoft Support: Import or subscribe to a calendar in Outlook.com or OWA](https://support.microsoft.com/en-us/office/import-or-subscribe-to-a-calendar-in-outlook-com-or-outlook-on-the-web-cff1429c-5af6-41ec-a5b4-74f2c278e98c) · [mozilla.support.thunderbird group](https://groups.google.com/g/mozilla.support.thunderbird/c/9-zRsrdgT1s).

**PRD risk to surface**: Google's 48-h tail breaches the PRD's "ceiling at most one day" guarantee. Not closeable server-side. Either accept residual risk (recommend) and document, or widen the PRD ceiling to "best-effort one day" for Google subscribers.

#### D.2 HTTP semantics

Net practical reading: do not architect S-04 around `Cache-Control`/`ETag` honoring. They save bandwidth on Thunderbird, occasionally Apple, and are essentially placebo for Google and Outlook subscribed-feed fetchers ([W3C archive: Karl Dubost open letter to calendar developers](https://lists.w3.org/Archives/Public/www-archive/2013Aug/0021.html); [Bitsight: Hidden cyber threats of calendar subscriptions](https://www.bitsight.com/blog/hidden-dangers-calendar-subscriptions-4-million-devices-risk)). MIME `text/calendar; charset=utf-8` is required across all four.

#### D.3 UID-keyed in-place updates and deletion

For all four clients, same UID + changed `SUMMARY`/`DTSTART` → in-place update (UID is the iCalendar identity primitive, RFC 5545 §3.8.4.7). UID disappears from next poll → event removed. Deletion lag = client polling lag (§D.1).

Apple Calendar has a [known edge case](https://developer.apple.com/forums/thread/734647) where it can attach a second UID on top of a non-RFC-compliant UID; our `<uuid>@ogarniacz.fly.dev` form is RFC-compliant and avoids this.

Conclusion: PRD's deletion guarantee holds iff (a) UIDs are stable across the event's lifetime and (b) deleted events are *physically absent* from the next ICS body (NOT `STATUS:CANCELLED` — that path requires iTIP mail with `METHOD:CANCEL`, not subscription feeds).

#### D.4 VALARM in subscribed feeds — Google is the gap

| Client | VALARM honored on subscribe? |
|---|---|
| Google Calendar | **No** — silently dropped. Subscribed-calendar reminders are not surfaced; users must set per-calendar default reminders in Google Calendar's own settings. Community-documented, vendor-silent. ([Microsoft Learn troubleshoot](https://learn.microsoft.com/en-us/outlook/troubleshoot/calendaring/no-meeting-reminder-for-google-calendar-invites), [Google Calendar Community](https://support.google.com/calendar/thread/22955190/how-can-i-get-notifications-from-a-subscribed-calendar?hl=en)) |
| Apple Calendar | **Yes** by default; per-calendar toggle exists. |
| Outlook | **Yes**, with friction (subscribed calendars are read-only — users can't add their own reminders to compensate). |
| Thunderbird | **Yes** for `ACTION:DISPLAY` (the kind we emit). |

**PRD risk to surface**: a Google-subscribing parent gets events but NO morning-of-day-before reminder. The feed remains RFC-correct; the gap is at the client. **Mitigation in S-04 is user-facing copy on the subscribe screen**:

> "Reminders work in Apple Calendar, Outlook, and Thunderbird. Google Calendar does not show reminders for subscribed calendars — set a default reminder for this calendar in Google Calendar settings."

Decide in the PRD whether this changes the S-04 acceptance criterion (it should at least be a known limitation note).

#### D.5 Date-only events

All four clients render `DTSTART;VALUE=DATE` as all-day events. All tolerate missing DTEND (RFC 5545 §3.6.1 — defaults to 1-day duration), but historical third-party renderers (Plone, Drupal Calendar) broke on missing DTEND. **Recommend emitting both `DTSTART;VALUE=DATE:20260620` AND `DTEND;VALUE=DATE:20260621`** (exclusive end) for maximum compatibility. Cost: two extra lines per VEVENT.

#### D.6 MIME / Content-Disposition / URL-extension

- **URL must end in `.ics`** — Outlook + iOS Calendar discriminate by extension when choosing "import once" vs "subscribe with refresh".
- `webcal://` URI scheme is a UX nicety (clickable subscribe trigger across Apple/classic Outlook/Thunderbird); new Outlook strips it. Emit `https://...feed.ics`; both forms work.
- `X-WR-CALNAME` honored by Apple + Outlook; Google + Thunderbird ignore (Bugzilla 168176 open since 2002 for Thunderbird). RFC 7986 `NAME` is the standards-track replacement; client adoption uneven. **Emit both** — costs nothing.
- `X-PUBLISHED-TTL:PT6H` + `REFRESH-INTERVAL;VALUE=DURATION:PT6H` — emit both as polling hints. Mostly ignored, but free and occasionally honored by Outlook.

#### D.7 `CalendarController` MUST / SHOULD / MAY list

- **MUST** `Content-Type: text/calendar; charset=utf-8`.
- **MUST** URL path ends in `.ics`.
- **MUST** stable per-event UID (`<uuid>@ogarniacz.fly.dev`), monotonic `SEQUENCE`.
- **MUST** delete by omission (NOT `STATUS:CANCELLED`).
- **MUST** date-only events as `DTSTART;VALUE=DATE` + `DTEND;VALUE=DATE+1`.
- **SHOULD** `X-WR-CALNAME` + `NAME` with the same human-readable label (e.g. "Ogarniacz").
- **SHOULD** `X-PUBLISHED-TTL:PT6H` + `REFRESH-INTERVAL;VALUE=DURATION:PT6H`.
- **SHOULD** `Cache-Control: private, no-cache, max-age=0` set on the controller.
- **MAY** gzip via `server.compression.*` — low cost, low benefit, skip in V1.
- **MAY** ETag / Last-Modified — only Thunderbird benefits; skip in V1.
- **WARN (user copy)**: Google ignores subscribed-calendar reminders → set a Google-side default reminder.
- **WARN**: Google polling cadence may breach PRD's one-day ceiling.

## Code References

(All file paths are at git commit [`d97a61b`](https://github.com/ludmilabinek/ogarniacz/tree/d97a61b30982335e23fb7cf295698f4d61741573).)

- [`src/main/java/com/example/app/event/Event.java`](https://github.com/ludmilabinek/ogarniacz/blob/d97a61b30982335e23fb7cf295698f4d61741573/src/main/java/com/example/app/event/Event.java) — Event JPA entity (UUID id, `@ManyToOne AppUser`, LocalDate/LocalTime/String fields). PK becomes the iCal UID local part.
- [`src/main/java/com/example/app/event/EventRepository.java`](https://github.com/ludmilabinek/ogarniacz/blob/d97a61b30982335e23fb7cf295698f4d61741573/src/main/java/com/example/app/event/EventRepository.java) — `findUpcomingByUser` JPQL pattern. Feed query follows the same shape.
- [`src/main/java/com/example/app/event/EventController.java:25-53`](https://github.com/ludmilabinek/ogarniacz/blob/d97a61b30982335e23fb7cf295698f4d61741573/src/main/java/com/example/app/event/EventController.java#L25) — `Authentication auth` injection pattern for *authenticated* endpoints (does NOT apply to feed endpoint; feed resolves user via token).
- [`src/main/java/com/example/app/user/AppUser.java`](https://github.com/ludmilabinek/ogarniacz/blob/d97a61b30982335e23fb7cf295698f4d61741573/src/main/java/com/example/app/user/AppUser.java) — carrier entity for the new `ical_token` column.
- [`src/main/java/com/example/app/user/AppUserRepository.java`](https://github.com/ludmilabinek/ogarniacz/blob/d97a61b30982335e23fb7cf295698f4d61741573/src/main/java/com/example/app/user/AppUserRepository.java) — needs `Optional<AppUser> findByIcalToken(String)`.
- [`src/main/java/com/example/app/config/SecurityConfig.java:67-90`](https://github.com/ludmilabinek/ogarniacz/blob/d97a61b30982335e23fb7cf295698f4d61741573/src/main/java/com/example/app/config/SecurityConfig.java#L67) — single `SecurityFilterChain` to extend with `.requestMatchers(HttpMethod.GET, "/calendar/*.ics").permitAll()`.
- [`src/main/resources/application.properties`](https://github.com/ludmilabinek/ogarniacz/blob/d97a61b30982335e23fb7cf295698f4d61741573/src/main/resources/application.properties) — `ddl-auto=update`, `app.timezone=Europe/Warsaw`, `app.event.reminder.hour=8`, `server.forward-headers-strategy=framework`. No changes needed for S-04.
- [`build.gradle`](https://github.com/ludmilabinek/ogarniacz/blob/d97a61b30982335e23fb7cf295698f4d61741573/build.gradle) — add `implementation 'org.mnode.ical4j:ical4j:4.2.5'`.
- [`src/test/java/com/example/app/AppApplicationTests.java`](https://github.com/ludmilabinek/ogarniacz/blob/d97a61b30982335e23fb7cf295698f4d61741573/src/test/java/com/example/app/AppApplicationTests.java) — partition assertion `appShowsOwnEmailOnlyNotOtherUsersEmail` is the template for S-04 Phase 3 cross-account test.
- [`src/test/java/com/example/app/event/EventControllerTest.java`](https://github.com/ludmilabinek/ogarniacz/blob/d97a61b30982335e23fb7cf295698f4d61741573/src/test/java/com/example/app/event/EventControllerTest.java) — template for `CalendarControllerTest` (annotation stack, MockMvc patterns).
- [`src/test/java/com/example/app/event/EventReminderTest.java`](https://github.com/ludmilabinek/ogarniacz/blob/d97a61b30982335e23fb7cf295698f4d61741573/src/test/java/com/example/app/event/EventReminderTest.java) — DST coverage pattern (spring-forward + fall-back) — Risk #6 template.

## Architecture Insights

- **The Event entity is already feed-ready.** No schema change to `app_event`; the feed serializer reads directly from the existing fields. The morning-of-day-before reminder being *computed* rather than *stored* is exactly the right shape — VALARM uses a relative TRIGGER, so no extra column is needed.
- **The token belongs to the user, not to a subscription.** Single column on `AppUser` (one-token-per-user, matching PRD US-03's "spouse pastes the same URL"). Forward-compat with rotation is preserved by additive migration.
- **Anonymous endpoint slots into the existing chain.** No second `SecurityFilterChain`, no CSRF gymnastics, no session work — one `.permitAll()` line + a controller. The smallest possible change to the security posture.
- **Existing test infrastructure carries S-04 with no new fixtures.** Per-controller `@SpringBootTest` class, `with(user(…))` / `with(csrf())` helpers for authenticated tests, partition assertion pattern, DST-coverage pattern in pure JUnit — all in place. S-04 adds one new test class (`CalendarControllerTest`) and reuses everything else.
- **Two PRD-relevant client gaps that S-04 cannot close**: Google polling cadence (12-24h, up to 48h) and Google's VALARM-ignored-on-subscribed-feeds behavior. Both are client constraints, both should land in the PRD as known limitations + user-facing copy on the subscribe screen.

## Historical Context (from prior changes)

- **`context/archive/2026-05-26-minimal-auth-and-empty-personal-view/plan.md`** (S-01) — established email-normalization convention (3 chokepoints, Locale.ROOT), CSRF enforcement, persistent remember-me. S-04 inherits the security posture; no convention conflicts.
- **`context/archive/2026-05-26-minimal-auth-and-empty-personal-view/reviews/`** (S-01 impl-review) — F1 (REMEMBER_ME_KEY from env, fail-fast), F3 (secure cookie from env). Same env-driven config pattern applies to any future S-04 secret (none in S-04 itself; the iCal token is stored in DB, not in env).
- **`context/archive/2026-06-07-manual-event-entry/plan.md`** (S-02) — established the Event shape that the feed reads from. The `@Query` with `nulls last` ordering is the precedent for the feed-render query if order matters in the feed (it doesn't — `VEVENT` order is not significant to subscribers — but the same pattern applies if we add `findAllByUser` later).
- **`context/archive/2026-06-07-manual-event-entry/reviews/`** (S-02 impl-review) — F2 (`orElseThrow` at auth boundary returns 500) is *the same shape* as S-04's token-to-user lookup, but the resolution is different: S-04 returns 404, not 500 or 401, because token-not-found is the expected "unknown subscriber" outcome (not a server fault, not an auth failure).
- **`context/foundation/lessons.md`** — "Per-controller `@SpringBootTest` test class is the test layout standard" (codified after S-02) — S-04 creates `CalendarControllerTest` as its own class. "Verify `./gradlew test` post-bootstrap; H2 testRuntimeOnly" — already in place; ical4j touches no JDBC so H2 stays untouched.

## Related Research

No prior `research.md` artifacts exist for S-04 in the archive. The closest related context is:

- `context/foundation/test-plan.md` — §2 Risks #3, #4, #6 are S-04's risk register. §3 Phases 2+3 are S-04's test phases (currently `not started`).
- `context/foundation/roadmap.md` §S-04 — the slice definition with the named unknowns this research closes.

## Open Questions

A small residue that belongs in the plan-design conversation, not before:

1. **Does the feed include past events, or only upcoming?** The PRD doesn't explicitly say. `EventRepository.findUpcomingByUser` already filters past events from the personal view; the feed could either reuse that or expose all events. Calendar clients typically expect "what's coming up" — but a user looking back at "did Tuesday's field-trip event ever ship?" wants past events too. Decide in the plan. (Hint: subscribers store events locally once seen, so "past events disappearing from the feed" is harmless; the calendar still shows them. Recommend feed includes only upcoming — same query as the personal view.)
2. **Settings page route + UI.** The PRD just says "a settings screen where the URL is visible and copyable." Decide: `/settings`? `/settings/calendar`? Inline on `/app`? (Recommend `/settings`, single page in MVP, link from `topbar` fragment.)
3. **What does the feed return when `ical_token` is NULL on the user (lazy not yet populated)?** Two options: (a) the lookup `findByIcalToken(t)` simply doesn't match → 404 (preferred, no leak); (b) settings page triggers token generation on first GET, so by the time a third party tries a token-shaped URL, every existing user has one. Recommend (a) + ensure the settings GET handler triggers generation on first view.
4. **UptimeRobot target swap** (deploy-plan Phase I item, noted in roadmap S-04 Unknowns): switch monitoring from `/actuator/health` to a known feed URL (e.g. a sentinel user's feed). The mechanics belong in `deploy-plan.md`, not the application-code plan. Note it in the plan's "post-merge ops" section.
5. **`X-WR-CALNAME` / `NAME` per-user value?** Static "Ogarniacz" for all subscribers, or `"Ogarniacz — <user-email-local-part>"`? (Recommend static "Ogarniacz" in MVP. The label appears in the user's own calendar — they know which is theirs. Per-user labels would couple the feed body to user-identifying data, mild RFC 7986 §5.3 friction.)
6. **PRD update for known limitations?** The Google VALARM gap and the Google polling-ceiling gap are PRD-touching findings. Decide whether to amend PRD §S-04 acceptance criteria, or to capture as "known limitations" notes in onboarding copy. Both are valid; depends on how strictly the user wants to read the PRD.
