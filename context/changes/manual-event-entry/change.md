---
change_id: manual-event-entry
title: Manual event entry
status: implementing
created: 2026-06-07
updated: 2026-06-08
archived_at: null
---

## Notes

Implements roadmap slice **S-02** ([`context/foundation/roadmap.md`](../../foundation/roadmap.md)) — manual creation of an event (date, optional time, title, requirements, notes) landing on the parent's per-user personal view at `/app`. Introduces the `Event` JPA entity (locked to PRD FR-004) and the `EventReminder` helper that S-04 (iCalendar feed) and S-05 (image extraction) will both reuse.
