---
change_id: llm-diff-title-tier
title: Relax fixture diff to stop asserting on title format
status: implementing
created: 2026-06-13
updated: 2026-06-13
archived_at: null
---

## Notes

Relax fixture diff to stop asserting on title format, so accuracy measures what the user values (date, optional time, requirements). Title becomes informational-only (kept in expected.json, not asserted). Lands BEFORE llm-prompt-year-resolution so the year-fix lift is measured against an honest baseline.
