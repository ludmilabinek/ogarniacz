---
change_id: icalendar-feed-and-subscription
title: Icalendar feed and subscription
status: impl_reviewed
created: 2026-06-15
updated: 2026-06-16
archived_at: null
---

## Notes

<!-- Free-form notes for this change: links, ad-hoc context, decisions that don't belong in research/frame/plan. -->

## Follow-ups

- **Account-deletion slice: sweep all three `findByEmail(auth.getName()).orElseThrow()` sites at once.** From impl-review F3 — the `NoSuchElementException → 500` shape now exists at `AppController:43`, `EventController:41`, and `SettingsController:23`. The plan deferred the fix as the "auth-to-entity F2 trade-off". When the account-deletion slice lands, close the family in one move (e.g. private `requireUser(Authentication)` helper or `@ControllerAdvice @ExceptionHandler(NoSuchElementException.class)` redirecting to `/login`) instead of patching only the deletion-adjacent site.
