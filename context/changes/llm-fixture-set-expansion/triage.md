# Triage — seed fixture batch (9 announcements)

Curator: Ludmiła. Triaged: 2026-06-12.

Source batch: 9 pre-anonymized real kindergarten announcements supplied by the
curator, varied in source (poster photos, web layouts) and quality. PII screen
performed by curator before batch handoff; one in-triage finding (fixture 06)
was whited-out by curator during this triage session — see row notes.

## Per-fixture triage table

| ID | Category | Source format | Event count | Notable | PII status | Status |
|----|----------|---------------|-------------|---------|------------|--------|
| `02-wielkanoc-sniadanie` | clean (single, two-time) | poster photo | 1 event with 2 group times (8:30 + 9:00) | Tests whether model emits 1 event or splits into 2 per group | screened | ☐ image · ☐ expected.json · ☐ recorded · ☐ accept-list reviewed |
| `03-marzec-bez-godzin` | **ambiguous** | poster photo | 3 events (20 III, 31 III, 1 IV) | Most events lack explicit `HH:MM` times — tests missing-time-field handling | screened | ☐ image · ☐ expected.json · ☐ recorded · ☐ accept-list reviewed |
| `04-marzec-wazne-daty` | clean (multi) | poster photo | 5 events (2/3/5/12/19 III) | Varied `requirements` (yellow dress for Welsh day, sport gear, green for Patrick's) | screened | ☐ image · ☐ expected.json · ☐ recorded · ☐ accept-list reviewed |
| `05-zdjecia-dyplomowe` | clean (single) | poster photo | 1 event (26 maja, no time) | Conditional dress code (girls vs boys) in `requirements` | screened | ☐ image · ☐ expected.json · ☐ recorded · ☐ accept-list reviewed |
| `06-luty-wazne-daty` | clean (multi) | poster photo | 4 events (2/13/17/24 II) | Curator whited-out parent name line during triage (originally "pani Natalia - mama Antka i Adasia"); blank block at that position now | screened (post-triage edit) | ☐ image · ☐ expected.json · ☐ recorded · ☐ accept-list reviewed |
| `07-czerwiec-wazne-daty` | clean (multi, dense) | poster photo | 6+ events including year-end ceremony with per-group times | Densest fixture; tests model's robustness on rich layout | screened | ☐ image · ☐ expected.json · ☐ recorded · ☐ accept-list reviewed |
| `08-grzybobranie` | clean (single) | poster photo | 1 event (27 IX 2025, 10:00–12:00) | Rich `requirements` list (basket, weather-appropriate clothing, reusable picnic gear, zero-waste) | screened | ☐ image · ☐ expected.json · ☐ recorded · ☐ accept-list reviewed |
| `09-niepodleglosci-www` | clean (single) | **web layout (HTML)** | 1 event (6.11.2025, no explicit time) | Tests non-poster input format — webpage capture with `Wydarzenia / Important dates` styling | screened | ☐ image · ☐ expected.json · ☐ recorded · ☐ accept-list reviewed |
| `10-warsztaty-www` | clean (single) | **web layout (HTML)** | 1 event (15.05.2026, 9:00–12:00) | Web layout with explicit times + transport note ("tramwajem linii 9") | screened | ☐ image · ☐ expected.json · ☐ recorded · ☐ accept-list reviewed |

Category target per `src/test/resources/llm/fixtures/README.md`:
- ≥1 ambiguous → ✅ `03-marzec-bez-godzin`
- ≥1 FR-005-unreadable → ❌ **not in this batch** (see Follow-ups)

## Follow-ups (tracked, not in this change)

- **FR-005-unreadable fixture (11th).** None of the 9 batch announcements meet
  the "model should return `[]`" bar — all are clearly readable. When the curator
  next captures a truly unreadable real announcement (heavily smudged, severely
  cropped, illegible handwriting), add it as `11-...` and re-run the accuracy
  tally. Do **not** synthetically degrade a clean fixture — that violates the
  fixture sourcing policy ("real announcement").

## Phase 2 — per-fixture capture procedure

Authoritative procedure: `context/foundation/test-plan.md` §6.4. Summary:

1. Image is already in `src/test/resources/llm/fixtures/<id>/image.png` (done at
   triage time).
2. Curator writes `expected.json` by reading the image — **oracle rule**: the
   expected values come from human judgment of what the announcement says, never
   from any model output.
3. Capture: `OGARNIACZ_LIVE_SMOKE=true OGARNIACZ_RECORD_FIXTURES=true ./gradlew test --tests "*LiveRegression*"` — the live test atomically writes
   `recorded-response.json` + `recorded-meta.json`.
4. Verify: `./gradlew test --tests "*RecordedRegression*"` — see diff against
   `expected.json`.
5. Per divergence, curator decides: documented model limit (add to
   `KNOWN_DIVERGENCES`) or real-and-uncovered (leave it red as a finding).

## Phase 3 — accuracy tally

After all 9 fixtures have a recording and an accept-list decision:

- Count fixtures with **zero divergences** vs fixtures with **any divergences**.
- Ratio = first-extraction accuracy on this batch.
- **Target: ≥8/10** to provisionally meet PRD §Success Criteria primary.
- Sub-80% is **not** a harness failure — it's a signal for a separate
  prompt/model-tuning change.
