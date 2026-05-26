---
project: Ogarniacz
version: 1
status: draft
created: 2026-05-18
context_type: greenfield
product_type: web-app
target_scale:
  users: small
  qps: low
  data_volume: small
timeline_budget:
  mvp_weeks: 3
  hard_deadline: 2026-06-15
  after_hours_only: true
---

# Ogarniacz — Product Requirements Document

## Vision & Problem Statement

A parent of a kindergarten-aged child receives obligations through many unstructured channels: notes pinned on the corridor wall, posts in parent Messenger / WhatsApp groups, screenshots circulating between parents, the kindergarten's own website, posts in the LiveKid app. Each announcement must be read, interpreted ("which date does this imply, what must I bring, how should the child be dressed, at what time"), and transferred by hand into the parent's digital calendar. When done correctly the cost is 3–5 minutes per announcement; far more often the obligation lands in the parent's head and is forgotten — yellow shirt missed on Tuesday, 5 PLN for the puppet show not packed, sandwich for the field trip absent on Friday. The failure mode is concrete and recurring.

Existing tools fail the parent at three points: digital calendars cannot ingest photos or screenshots and demand already-structured input; kindergarten apps such as LiveKid publish announcements but do not turn them into calendar events with date, time, and required action; note-taking apps capture the photo but leave the unstructured → structured translation entirely to the parent. The bottleneck is not the calendar — it is the cognitive load of turning a paragraph (or a blurry photo) into a date plus an action plus a list of items to bring. Ogarniacz moves the extraction work onto the application: a photo or screenshot becomes a proposed list of editable events that the parent reviews and accepts, then appears in the parent's existing digital calendar via a one-time iCalendar subscription. The parent's calendar remains the trusted system of record; Ogarniacz is the bridge from unstructured input to calendar event.

## User & Persona

**Primary persona — Kindergarten parent (initial sole user: the project author).**

- **Role:** Parent of a kindergarten-aged child.
- **Context:** Already uses a digital calendar (in the MVP user's case, Google Calendar; the product's iCalendar feed works with any compatible client) as the trusted single source of truth for work meetings, family obligations, doctor visits, and personal commitments. Wants child-related obligations in the same place — without manual rewriting.
- **Devices / channel:** Encounters announcements both at the kindergarten in person (phone camera → photo of the wall note) and digitally (screenshots from parent group chats, the kindergarten's website, or the LiveKid app, taken on the phone).
- **Moment they reach for Ogarniacz:** The instant they encounter an announcement in any channel — they pull out their phone, capture a photo or take a screenshot, upload it, accept the extracted events, and trust that they will land in the calendar with no further action required.

### Secondary persona (post-MVP)

Other parents of kindergarten-aged children. Explicitly out of MVP scope, but the data and authentication models should not preclude expansion.

## Success Criteria

### Primary

- A photo or screenshot of a real kindergarten announcement, when uploaded, produces a list of proposed events whose **date, title, and requirements (what to bring + dress code) are correct on first extraction in ≥ 80% of representative real announcements** — the parent fixes details but never has to re-type the event from scratch. Time, when specified, should also be correct.
- After uploading, the parent can **review, edit, accept, and have the event in their digital calendar within 1 minute of work** (excluding the asynchronous iCalendar polling latency).
- After a **one-time** iCalendar subscription setup in the parent's calendar client, every subsequently accepted event appears in that calendar with no further action from the parent — within at most one day (the client's polling cadence).

### Secondary

- A spouse or co-parent who pastes the same iCalendar URL into their own calendar client sees the same events appear, without the project author having to re-enter or re-share anything per event.
- Manual event entry (no image) produces an accepted event with the same downstream behaviour as the image path — i.e. it lands in the iCalendar feed identically and surfaces in subscribed calendars identically.

### Guardrails

- **No event ever lands in the parent's view or the iCalendar feed without explicit per-event acceptance.** This is non-negotiable — AI proposes, the parent decides. A regression here makes the calendar untrustworthy.
- Unreadable images produce a clear, actionable error ("could not read this image — try a better photo or add manually") — never silently-empty results, never garbage events.
- The iCalendar feed URL is unguessable in practice (sufficient token entropy). A third party who has not been given the URL cannot enumerate it.
- The parent's own data (announcements, extracted events, accepted events) is never visible to any other authenticated user (currently none, but the partitioning is enforced from day one so the product scales to multi-parent without a privacy regression).

## User Stories

### US-01: Parent extracts events from an announcement and lands them in their calendar

- **Given** a logged-in parent who has already pasted their unique iCalendar URL into their digital calendar client (one-time setup)
- **When** they upload a photo or screenshot of a kindergarten announcement
- **Then** they are shown a list of one or more proposed events, each with date, time (optional — most events are date-only), title, requirements (what to bring + dress code), and notes
- **And** the parent can edit any field of any proposed event
- **And** the parent can accept or reject each proposed event individually
- **And** each accepted event appears in their Ogarniacz personal view immediately
- **And** each accepted event appears in their subscribed digital calendar within the client's polling cadence (at most ~1 day)
- **And** each accepted event carries a reminder set for the morning of the day before

#### Acceptance Criteria

- A real kindergarten announcement (photo or screenshot from a parent chat, the kindergarten app, or the kindergarten's website) produces at least one event with correct date and title.
- An unreadable image (badly blurred, severely cropped, or fully glared) produces a clear error message — never an empty list silently, never garbage events.
- A rejected proposed event leaves no trace in the iCalendar feed or personal view.
- After deleting an accepted event from the personal view, the next poll of the iCalendar feed no longer contains it.

### US-02: Parent adds an event manually

- **Given** a logged-in parent
- **When** they choose "add event manually" (no image required)
- **Then** they see the same form fields as the review screen, all empty and editable
- **And** they can fill in date, time (optional), title, requirements, and notes
- **And** on save, the event lands in their personal view and iCalendar feed with the same morning-of-day-before reminder as image-extracted events

### US-03: Parent (and spouse) set up the calendar subscription once

- **Given** a parent who has just signed up
- **When** they visit the settings screen and copy their unique iCalendar URL
- **Then** pasting that URL into their digital calendar client's "subscribe by URL" option (in Google Calendar, this is found under "Other calendars → From URL") causes the client to begin polling Ogarniacz
- **And** the same URL pasted by the spouse into a different calendar produces the same events in the spouse's view
- **And** no further per-event sharing or re-import is required for either parent

## Functional Requirements

### Authentication

- FR-001: User can authenticate with email and password — sign up, log in, log out, and remain logged in across browser sessions until explicit logout or natural session expiry. Password reset, multi-factor authentication, email verification, and account deletion are explicitly NOT in MVP and are deferred to subsequent stages. Priority: must-have
  > Socratic: Counter-arguments considered: "auth is premature for a single-user MVP — a local profile would deliver the same value" and "email+password is a footgun vs passwordless/OAuth". Resolution: kept, but scoped to minimal MVP (signup + login + logout + session persistence). Auth is mandated by the final-project criteria; the minimal shape is the least-cost way to satisfy that mandate while still future-proofing for multi-parent expansion.

### Announcement intake

- FR-002: User can upload an image (phone-camera capture or screenshot from device storage) of a kindergarten announcement for processing. Priority: must-have
  > Socratic: Counter-arguments considered: clipboard-paste as an alternative entry path; PDF support for announcements from the kindergarten website. Resolution: stands for MVP. Clipboard-paste is recorded as the next development step (post-MVP) — strong UX win, but not blocking. PDFs are out of scope; screenshot of the PDF is an acceptable workaround.
- FR-003: User can manually create an event without uploading an image, using the same form as the review/edit screen. Priority: must-have
  > Socratic: Counter-argument considered: "manual entry duplicates an external calendar and dilutes the AI-extraction value prop". Resolution: kept. Manual entry covers a real, recurring use case — details emerge over time (a teacher mentions a field trip Monday, confirms the time Wednesday) — and the form already exists from the review screen, so marginal cost is near zero.

### Extraction

- FR-004: After image upload, the user is shown a list of proposed events extracted from the image. Each proposed event carries: date, time (optional — most kindergarten events are date-only; only field trips and similar have specific start times), title, requirements (merged "what to bring" + dress code), and notes. Priority: must-have
  > Socratic: Counter-argument considered: "dress code and 'what to bring' rarely co-occur — combine into one 'requirements' field". Resolution: ACCEPTED. Schema revised to: date, time (optional), title, requirements (combined), notes. Time is explicitly optional after user clarification that date-only is the dominant case.
- FR-005: User can recover from an unreadable image — the system shows a clear, actionable error message and offers a retry or a manual-entry path. Priority: must-have
  > Socratic: Counter-argument considered: "binary 'unreadable' is too coarse — degraded extraction is more common than total failure; surface partial results with strong uncertainty markers". Resolution: stands. Uncertainty-marker UI was removed in this same round (quality control collapsed to 2-level), so the user reviews all extracted fields equally. "Unreadable" is a binary fallback state — extraction either produced fields (parent reviews them all) or did not (parent retries with a better photo or switches to manual entry).

### Review and acceptance

- FR-006: User can edit any field of a proposed event before accepting it. Priority: must-have
  > Socratic: Counter-arguments considered: "lock high-confidence fields read-only" and "collapse pre-acceptance edit into post-acceptance edit and skip the review/accept step". Resolution: stands. The review-then-accept gate is the load-bearing "AI proposes, human decides" guardrail; collapsing it loses the trust property that makes the calendar reliable.
- FR-007: User can accept or reject each proposed event individually. Priority: must-have
  > Socratic: Counter-arguments considered: bulk "Accept all" action; inverted default (events accepted unless rejected). Resolution: stands. Per-event explicit accept/reject is the safety floor; bulk-accept can be a v2 ergonomics improvement once trust in the extraction is established.
- FR-008: Every accepted event automatically carries a reminder set for the **morning of the day before** (default, not user-configurable in MVP). Priority: must-have
  > Socratic: Counter-argument considered: "day-before is wrong for early-morning events — by Tuesday morning, the yellow-shirt reminder is too late". Resolution: REVISED. Default is the *morning* of the day before (not just "day-before"). User rationale: most kindergarten events are date-only, and morning-of-day-before gives a full day to shop, do laundry, prepare. Per-event configurable timing remains deferred to v2.

### Personal view & lifecycle

- FR-009: User can see all their accepted events in a personal list view inside Ogarniacz. Priority: must-have
  > Socratic: Counter-argument considered: "personal view duplicates the external calendar — skip it; users open their calendar instead". Resolution: stands. The personal view is the surface where edit and delete actions live (FR-010, FR-011); without it, post-acceptance lifecycle has no UI home.
- FR-010: User can edit any field of an accepted event from the personal view; the change propagates to the iCalendar feed. Priority: must-have
  > Socratic: Counter-argument considered: "edit-after-accept introduces feed cache sync complexity; treat accepted events as immutable, force delete + re-create for mistakes". Resolution: stands. The "details emerge over time" use case is real and recurring; immutability would push parents back into manual rewriting.
- FR-011: User can delete an accepted event from the personal view; the deletion propagates to the iCalendar feed. Priority: must-have
  > Socratic: Counter-argument considered: "hard-delete is dangerous — accidental delete loses the event from the external calendar permanently; soft-delete / archive is safer". Resolution: stands for MVP. Hard delete is acceptable at this scale (single user, recoverable by re-uploading the announcement); soft-delete is a deferable hardening step.

### Calendar synchronization

- FR-012: User can view and copy their unique, unguessable iCalendar feed URL from a settings screen. Priority: must-have
  > Socratic: Counter-argument considered: "showing the raw URL exposes the token to over-the-shoulder attacks; a QR code is safer". Resolution: stands. Over-the-shoulder risk is low for a single-user MVP, and the parent has to paste the URL into their calendar client's "subscribe by URL" field anyway.
- FR-013: The iCalendar feed URL, when polled by a calendar client, returns the user's currently-accepted events (including the morning-of-day-before reminder) as a standard iCalendar feed. Priority: must-have
  > Socratic: Counter-argument considered: "polling is slow — push-based sync directly from the product to the calendar would give near-instant updates". Resolution: stands for MVP. Direct push-based calendar integration is noted as the next development step (post-MVP). For now, iCalendar subscription is preferred because it works with any standards-compliant calendar client (not just one vendor) and avoids per-vendor authorization and quota management. The "within ~1 day" polling latency is an explicit guardrail, not a regression.

## Non-Functional Requirements

- After a parent uploads an image, the proposed-events list becomes visible within **30 seconds in typical cases and within 60 seconds at the ceiling**. The parent sees continuous visible feedback (not a frozen screen) throughout the wait.
- Uploaded source images are retained only as long as needed to complete the parent's review: once every proposed event from a given image has been accepted or rejected, the source image is automatically removed from operator-accessible storage.
- A parent's uploaded images, extracted events, and accepted events are never visible to any other authenticated user (zero cross-account leakage, enforced from day one).
- A third party who has not been given a parent's unique iCalendar URL cannot access that parent's calendar feed; the URL's token entropy is sufficient to make enumeration infeasible in practice.
- The product is usable on the latest two major versions of the four mainstream browsers (Safari, Chrome, Firefox, Edge) on both desktop and mobile form factors.
- An accepted event appears in the parent's subscribed digital calendar within the calendar client's polling window — in practice several hours, ceiling at most one day after acceptance.
- A deleted accepted event no longer appears in the iCalendar feed at the next poll; the deletion propagates with the same latency as additions.

## Business Logic

**Ogarniacz turns an uploaded image (or a manual entry) of a kindergarten announcement into one or more proposed structured events that the parent reviews and accepts; accepted events are delivered to the parent's existing digital calendar via a one-time iCalendar subscription, each carrying a reminder for the morning of the day before.**

The rule consumes three kinds of input from the parent's perspective: (a) an image — a photo of an announcement or a screenshot from a digital channel (parent chats, the kindergarten's website, the LiveKid app) — uploaded through the browser; or (b) the same fields entered manually when no image is available; and in either case (c) the parent's per-event acceptance, rejection, and any edits made during review. The rule's output is twofold: a persisted list of accepted events visible in the parent's personal Ogarniacz view, and the same set of events surfaced as an iCalendar feed at the parent's unique URL, where any subscribed calendar client sees them as native events with a reminder set for the morning of the day before.

The parent encounters the rule at three moments. First, immediately after upload, they see a list of proposed events with fields filled in to the best of the extraction's ability — each event individually editable, individually acceptable, and individually rejectable. Second, on accepting an event, it appears immediately in the personal Ogarniacz view. Third, asynchronously, the same event appears in the parent's subscribed calendar at the next polling cycle (within hours, at most one day). Rejected events disappear without trace; the source image is removed once all proposed events from that image have been resolved (accepted or rejected). Manual entries follow the same downstream behaviour: an event the parent typed has the same shape, the same default reminder, and the same calendar landing as an event extracted from an image.

The rule's failure modes are explicitly handled: if the image cannot be read at all, the parent sees an actionable error and a manual-entry path; if the image is readable but extraction is imperfect, every field remains editable, so a wrong date or a missing item is a fix-and-accept rather than a re-upload.

## Access Control

**Authentication.** Email + password login, minimal MVP scope. One account per parent. The MVP has exactly one account (the project author), but the authentication surface is built from day one so adding more parents later requires no rewrite. Standard "log in once, stay logged in" pattern — no need to re-authenticate per session beyond normal session expiry. Authentication is required because it is part of the final-project criteria; the minimal shape (signup + login + logout + session persistence only) is the lowest-cost way to satisfy that requirement.

**Authorization.** Flat role model: every authenticated user is a *parent*. A parent can only see and modify their own announcements, extracted events, and accepted events. There is no admin role and no shared-workspace concept in the MVP — each parent's data is fully partitioned.

**iCalendar feed access.** Each account is issued a unique iCalendar URL containing an unguessable token. Subscribing to the feed does NOT require login — any compatible calendar client polls the URL anonymously, and the token entropy is the only access control. The same URL can be shared with a spouse (or co-parent) by passing them the link; both subscribe to the same single feed, so events appear once in each shared calendar — never duplicated. There is no second user account for the spouse.

**Out of MVP** (recorded so the lack of these features is intentional, not an oversight):

- Password reset flow via email. For a single-user MVP, the project author can reset their own password through operator-side means if absolutely necessary. A self-service reset flow is added when the second user is onboarded.
- Email verification on signup.
- Multi-factor authentication.
- Account deletion / data export. Important for any future expansion to other users (and for GDPR), but not required in the single-user MVP.
- Token rotation / revocation for the iCalendar URL. If the parent ever needs to revoke the URL (e.g. after a leaked link), today's only path is account deletion and re-issue. Adding a "rotate token" action is a small, non-blocking follow-up.

## Persistence

All parent-scoped data — accounts and credentials, uploaded source images (until purge), extracted-event proposals, accepted events, the per-user iCalendar token — is stored in a **server-side database**. File-based, in-memory, or client-side persistence is explicitly out of scope: each of the load-bearing behaviours (cross-account partitioning, post-acceptance edit/delete with iCalendar-feed consistency, token-keyed feed lookup, surviving restarts and redeploys) requires durable transactional storage. The specific database engine is chosen during stack selection; the requirement at the PRD level is "a database, not a file."

## Non-Goals

- **No native mobile app** — Ogarniacz is web-only, served via the mobile browser. The seed makes this explicit: the website works on a phone (camera-via-browser for photos, normal upload for screenshots). Pinned to prevent a mid-build pivot to a native iOS / Android port.
- **No modules beyond kindergarten** — voice notes, invoices, school, medical, and administrative announcements are deferred to future modules. The first module is intentionally narrow; expanding it before the kindergarten flow is solid is a known greenfield trap.
- **No custom OCR or vision model trained in-house** — extraction is delegated to an external image-to-event service (specific provider to be chosen during stack selection). Training, fine-tuning, or self-hosting a vision model is out of scope; off-the-shelf services handle typical kindergarten announcements well enough for MVP.
- **No event categorization (kindergarten / home / work tags)** — every event in MVP is implicitly a kindergarten event. Tagging, filtering, and category-based views are explicitly excluded; the seed names this as out of scope.
- **No real-time / push-based calendar sync** — iCalendar polling is the only sync mechanism in MVP. Direct push-based calendar integration is the next development step, not part of MVP.
- **No per-event configurable reminder timing** — every accepted event gets the morning-of-the-day-before reminder by default. User-configurable per-event reminder offsets are deferred to v2.
- **No password reset, MFA, email verification, or account deletion in MVP** — minimal authentication (signup + login + logout + session persistence) only; the rest is added incrementally when the second user comes on board.
- **No clipboard-paste image input in MVP** — file picker only. Clipboard-paste recorded as the next post-MVP development step.
- **No PDF announcement input** — workaround is to screenshot the PDF and upload as image.
- **No file-based, in-memory, or client-side persistence as a substitute for a database** — flat-file stores (JSON on disk, SQLite-as-file shipped with the binary), `localStorage`-only state, or in-process maps that disappear on restart are explicitly excluded. See *Persistence*.

## Open Questions

_All discovery-phase open questions were resolved during `/10x-shape`. The "mostly correct" first-extraction threshold is fixed at ≥ 80% on representative real kindergarten announcements (see Success Criteria → Primary)._

No open questions remain at this time.
