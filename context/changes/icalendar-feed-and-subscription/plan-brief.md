# iCalendar Feed + Subscription (S-04) — Plan Brief

> Full plan: `context/changes/icalendar-feed-and-subscription/plan.md`
> Research: `context/changes/icalendar-feed-and-subscription/research.md`

## What & Why

S-04 turns the parent's already-accepted `Event` rows into an RFC 5545 iCalendar feed served at a per-user unguessable URL, plus a settings page that displays + explains the URL. This is the bridge from "events live in Ogarniacz" to "events live in the parent's existing digital calendar" — the north-star UX the PRD pins as the load-bearing reason to ship before the LLM extraction slice (so when S-05 lands, the AI-extracted event actually surfaces in the parent's calendar on the next poll, not just in the in-app personal view).

## Starting Point

The Event entity from S-02 is already feed-ready: `UUID id` becomes the iCal UID, `LocalDate eventDate` + nullable `LocalTime eventTime` map to date-only / timed DTSTART, and `EventReminder.reminderFor(event)` returns a DST-correct `ZonedDateTime` for the morning-of-day-before reminder. `AppUser` has no `ical_token` column yet. `SecurityConfig` is a single chain with form-login + CSRF + remember-me — the anonymous feed endpoint slots in with one `.permitAll()` line.

## Desired End State

A logged-in parent visits `/settings`, sees their unique `https://<host>/calendar/<token>.ics` URL with a copy button and three short subscribe blurbs (Apple/Outlook/Thunderbird) + a labelled note about Google Calendar's two limitations. Their accepted events appear at that URL as a standards-compliant iCalendar feed within one client poll, with the morning-of-day-before VALARM honored by Apple/Outlook/Thunderbird. A random non-issued URL returns 404. Deletions propagate to subscribed calendars on the next poll.

## Key Decisions Made

| Decision                            | Choice                                                    | Why (1 sentence)                                                                                                                  | Source   |
| ----------------------------------- | --------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------- | -------- |
| iCalendar library                   | ical4j 4.2.5                                              | ~30-50 LOC vs ~180-250 hand-rolled; covers folding/escaping bug surface; Context7-indexed for agent fluency.                      | Research |
| Token spec                          | `SecureRandom → 24 bytes → Base64URL no-pad → 32 chars` (192 bits) | Comfortably above OWASP ASVS V3.2.2 / NIST 800-63B floors; double-click-selectable in browsers.                                   | Research |
| Token storage                       | Single nullable `ical_token` column on `AppUser`          | One-token-per-user matches PRD US-03; rotation slice stays additive; lookup is O(log n) via PG's auto-index on UNIQUE.            | Research |
| Token mint timing                   | Lazy on first `/settings` GET                             | Keeps signup transaction free of `SecureRandom`; migration reduces to no-op + one row populated on first visit.                   | Research |
| Security carve-out                  | One `.permitAll()` line on existing single chain          | No second `SecurityFilterChain` needed; rule placed before `anyRequest().authenticated()` (first-match-wins).                     | Research |
| Unknown token response              | 404 (NOT 401)                                             | Symmetric with the default 404 for unmatched routes; avoids leaking the existence of the token namespace endpoint.                | Research |
| VALARM trigger encoding             | Absolute UTC via `EventReminder.reminderFor(event)`       | Reuses already-DST-tested helper; VALARM time = in-app reminder time by construction; relative trigger benefit moot (re-rendered each poll). | Plan     |
| Feed event scope                    | Upcoming-only (reuse `findUpcomingByUser`)                | Mirrors the in-app personal view (`/app` already hides past events via `findUpcomingByUser` + `appHidesPastEventsForCurrentUser`) — past events disappear from both surfaces in lockstep. **Note**: subscribed-feed clients treat the feed as source of truth, so past events present yesterday will be deleted from the local calendar today; we accept this for MVP as a deliberate scope choice. History retention is an open product question (see scope exclusion). | Plan     |
| Settings page shape                 | Single `/settings` page, topbar link                      | Smallest surface; consistent with current single-page personal view; future settings drop in without restructuring.               | Plan     |
| Empty-feed response                 | 200 OK + empty VCALENDAR envelope                         | RFC-correct; Outlook may unsubscribe on repeated non-200s; keeps "unknown token = 404, valid empty = 200" contract clear.        | Plan     |
| Subscribe-screen content            | URL + copy + per-client instructions + Google note        | Pre-empts the two PRD-relevant Google gaps with copy where the user reads them.                                                   | Plan     |
| Google limitations handling         | Amend PRD `## Known Limitations` section + UI copy        | PRD becomes self-consistent; downstream readers don't trip over the gap; UI mitigation surfaces in context.                       | Plan     |
| Calendar display name               | Static "Ogarniacz" for all subscribers                    | Zero coupling between feed body and user identity (clean RFC 7986 §5.3 posture); deterministic to test.                          | Plan     |

## Scope

**In scope:**
- PRD `## Known Limitations` section
- `ical_token` column on `AppUser` + `findByIcalToken` repo finder
- `IcalTokenGenerator` (`SecureRandom`, 192-bit, Base64URL)
- `IcalSubscriptionService.getOrCreateToken` (lazy + `@Transactional`)
- `IcalFeedWriter` (ical4j → VCALENDAR string, pure value class)
- `CalendarController` GET `/calendar/{token}.ics` + security carve-out
- `SettingsController` + Thymeleaf template + topbar link
- `IcalFeedWriterTest`, `CalendarControllerTest`, `SettingsControllerTest`, `IcalTokenGeneratorTest`
- test-plan §6.5 cookbook entry + §3 phase status updates

**Out of scope:**
- Token rotation / regeneration UI (PRD §Out-of-MVP; rotation columns deliberately not pre-added)
- Event edit/delete UI (S-03's slice; deletion-propagation tested via repo direct)
- Per-client one-click subscribe deep links (`webcal://` etc.)
- `ETag` / `Last-Modified` conditional GET
- gzip compression
- UptimeRobot keyword-check target swap (lives in `deploy-plan.md`)
- Roadmap.md amendments (only PRD changes for known limitations)
- CI gate flips (status row update is mechanical, no plan step)

## Architecture / Approach

```
[browser → /settings (auth)] → SettingsController
                                    │
                                    ├── IcalSubscriptionService.getOrCreateToken(user)
                                    │       └── IcalTokenGenerator.next() (lazy mint)
                                    │
                                    └── render settings.html  ←  topbar link from layout.html

[calendar client → /calendar/{token}.ics (anonymous, GET)] → CalendarController
                                                                 │
                                                                 ├── AppUserRepository.findByIcalToken(token) → 404 if absent
                                                                 │
                                                                 ├── EventRepository.findUpcomingByUser(user, today)
                                                                 │
                                                                 └── IcalFeedWriter.write(user, events)
                                                                         ├── ical4j → VCALENDAR/VEVENT/VALARM
                                                                         └── EventReminder.reminderFor(event) → absolute UTC TRIGGER

[SecurityConfig.filterChain] += .requestMatchers(GET, "/calendar/*.ics").permitAll()
                                  (placed FIRST in authorizeHttpRequests)
```

Six phases, six independent oracles. Phase 0 lands the PRD carve-out so later phases can cite it. Phase 1 is additive persistence + the generator. Phase 2a is the pure-value writer (RFC 5545 oracle, pure-JUnit tests). Phase 2b is the HTTP endpoint + security carve-out (HTTP + Spring Security oracles, integration tests). Phase 3 is the settings UI on top of the now-stable feed. Phase 4 backfills the cookbook.

## Phases at a Glance

| Phase                                         | What it delivers                                                      | Key risk                                                                                              |
| --------------------------------------------- | --------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------- |
| 0. PRD `## Known Limitations`                 | PRD oracle for later test + UI phases                                 | Skipping the edit and writing UI copy / freshness tests against an aspirational FR-013                |
| 1. Token + persistence foundation             | ical4j dep, `ical_token` column, generator, lazy-mint service         | Degenerate RNG (caught by 1000-iter cardinality test); duplicate index from `@Column` + `@Index` mix  |
| 2a. `IcalFeedWriter` (pure value class)       | RFC 5545 serializer with DST-correct VALARM                           | VALARM time drift from `EventReminder` (caught by parsed-output equality assertions)                  |
| 2b. `CalendarController` + security carve-out | Anonymous feed endpoint with right MIME, Cache-Control, partitioning  | `.permitAll()` ordering regression (caught by explicit `NOT 302` assertion); 401-leak vs 404          |
| 3. Settings page + topbar link                | User-visible URL, copy UX, subscribe blurbs, Google-limitations note  | Lazy-mint lost-update under concurrent first visits (mitigated by PESSIMISTIC_WRITE row lock on `findAndLockById` inside the `@Transactional` boundary — `@Transactional` + UNIQUE alone do not prevent it under READ_COMMITTED) |
| 4. test-plan §6.5 cookbook backfill           | Documentation coherence; phase status updates                         | Skipping the doc chore — phase 4 is the highest-skip-risk phase, hence its own row                    |

**Prerequisites:** S-02 (Event entity + EventReminder helper) — landed. Java 21 + Spring Boot 4.0.6 — landed. PostgreSQL DataSource env vars wired for prod; H2 testRuntimeOnly for the test suite — landed.
**Estimated effort:** ~2-3 sessions (Phase 0 + Phase 1 in one; Phase 2a + 2b in one; Phase 3 + Phase 4 in one — or split further if any phase surfaces a surprise).

## Open Risks & Assumptions

- **Google Calendar's 12-48h polling cadence breaches PRD FR-013's "at most one day" wording for Google subscribers.** Cannot close server-side. Mitigation: Phase 0's `## Known Limitations` section + Phase 3's UI copy. Accepted residual risk.
- **Google Calendar silently drops VALARM on subscribed feeds.** Cannot close server-side. Same mitigation; Google users must set a per-calendar default reminder.
- **Token leaked URL grants read access indefinitely (no rotation in MVP).** PRD §Out-of-MVP accepts this. Rotation slice is additive and post-MVP.
- **`orElseThrow` at the auth-to-entity boundary in `SettingsController` returns 500 if the user row vanishes mid-session** — inherited from `EventController`'s S-02 pattern; impl-review F2 deferred the fix to a later account-deletion slice. S-04 does not re-solve this.
- **Base URL is derived per-request via `ServletUriComponentsBuilder.fromCurrentContextPath()` composed with the existing `server.forward-headers-strategy=framework` setting.** No `app.base-url` property and no env var to misconfigure — Fly's `X-Forwarded-Proto`/`X-Forwarded-Host` headers drive the scheme/host automatically. Phase 3 manual verification still confirms the rendered URL.

## Success Criteria (Summary)

- A parent visits `/settings`, copies the URL, pastes it into Apple Calendar / Outlook / Thunderbird, and within one refresh sees their next event with a morning-of-day-before reminder. Google subscribers see the events (with the documented cadence + reminder caveats).
- `curl` against `/calendar/<known-token>.ics` returns RFC 5545-compliant body with the right MIME and Cache-Control headers; against a random 32-char URL returns 404.
- All four new test classes (`IcalTokenGeneratorTest`, `IcalFeedWriterTest`, `CalendarControllerTest`, `SettingsControllerTest`) pass in CI; the full suite remains green.
