# Triage — seed fixture batch (9 announcements)

Curator: Ludmiła. Triaged + processed: 2026-06-12.

Source batch: 9 pre-anonymized real kindergarten announcements supplied by the
curator, varied in source (poster photos, web layouts) and quality. PII screen
performed by curator before batch handoff; one in-triage finding (fixture 06)
was whited-out by curator during this triage session — see row notes.

## Per-fixture triage table

| ID | Category | Source format | Events (oracle) | Status | Divergences after capture |
|----|----------|---------------|------------------|--------|----------------------------|
| `02-wielkanoc-sniadanie` | clean (single, two-time) | poster photo | 2 (one per group time) | ✅ done | 2× `requirements-norm-mismatch` — model puts group label in `requirements` instead of dress code |
| `03-marzec-bez-godzin` | **ambiguous** (no times) | poster photo | 5 (incl. 1 IV repeated from fixture 02) | ✅ done | 5× `date-mismatch` — model emits 2024 instead of oracle year 2026 (**year-resolution prompt bug, [[task_199a06fd]]**) |
| `04-marzec-wazne-daty` | clean (multi) | poster photo | 5 | ✅ done | 5× `date-mismatch` — 2023 instead of 2026 |
| `05-zdjecia-dyplomowe` | clean (single) | poster photo | 1 (revised down from 2 after capture) | ✅ done | 1× `date-mismatch` — 2025 instead of 2026. After capture, curator agreed the model's reading of "one event with spectacle as supporting context" was defensible and revised oracle from 2 events to 1 |
| `06-luty-wazne-daty` | clean (multi) | poster photo | 5 | ✅ done | 5× `date-mismatch` — 2023 instead of 2026. Curator whited-out a parent-name line ("pani Natalia - mama Antka i Adasia") during triage; the blank block did not confuse extraction |
| `07-czerwiec-wazne-daty` | clean (multi, dense) | poster photo | 9 (oracle) vs 10 (model) | ⚠️ disabled | event-count mismatch — model emits a redundant umbrella entry for 19 VI in addition to the 3 per-group time slots. **No accept-list slot** for count mismatches today; fixture is in `DISABLED_FIXTURES` until [[task_199a06fd]] also tightens the don't-emit-umbrella behaviour |
| `08-grzybobranie` | clean (single, rich requirements) | poster photo | 1 | ✅ done | 1× `requirements-norm-mismatch` — model's phrasing of the equipment list differs from oracle (also caught a curator misread of "Leśniczówce Łęsko"/"wjazdem" — the poster actually says "Leśnictwie Łękno"/"wigwam", `notes` corrected post-capture) |
| `09-niepodleglosci-www` | clean (single) | **web layout (HTML)** | 1 | ✅ **clean match** | none — was 1× `title-norm-mismatch` (Polish typographic „..." vs ASCII `"..."`), curator fixed expected.json to use the same „..." the poster uses |
| `10-warsztaty-www` | clean (single) | **web layout (HTML)** | 1 | ✅ done | 1× `time-mismatch` — model emits `null` for time despite poster saying "Wyjście o g.9.00"; prompt likely doesn't pull embedded times |

Category target per `src/test/resources/llm/fixtures/README.md`:
- ≥1 ambiguous → ✅ `03-marzec-bez-godzin`
- ≥1 FR-005-unreadable → ❌ **not in this batch** (see Follow-ups)

## Accuracy tally (PRD §Success Criteria primary)

PRD definition: "date, title, and requirements correct on first extraction in ≥80% of representative real announcements". Any divergence — even an accept-listed one — means the parent has to fix something, so by the strict reading it does not count as first-extraction correct.

| | Fixtures in batch | Of which **clean** (zero divergences) | Of which **measurable** (not disabled) | First-extraction accuracy |
|---|---|---|---|---|
| New batch (02-10) | 9 | **1** (`09-niepodleglosci-www`) | 8 (07 disabled) | **1/8 ≈ 12.5%** |
| Including 01-sample | 10 | 1 | 9 | **1/9 ≈ 11.1%** |

PRD §Success Criteria primary target: **≥80%**. Current baseline: ~11–12%. **Below target.**

Dominant cause: year-resolution failure (model picks an arbitrary past year — 2023/2024/2025 — when the poster does not state a year). This single bug accounts for **16 of 22** documented divergences (~73%). The spawned [[task_199a06fd]] ("Add year-resolution rule to LLM extraction prompt") targets this.

Estimated post-prompt-fix accuracy (back-of-envelope): if the year fix resolves all `date-mismatch` entries, the clean-match fixtures would be:
- `02` still red (requirements-mismatch — unrelated to year)
- `03, 04, 05, 06` would become clean (year was their only issue) → **+4**
- `08` still red (requirements-mismatch — unrelated)
- `09` clean
- `10` still red (time-mismatch — unrelated)
- `07` would need recapture and accept-list review

→ post-prompt-fix accuracy estimate: **5/8 ≈ 62.5%** (still below PRD target). Closing the gap to 80% would need a second pass on the prompt — likely around requirements-field semantics and embedded-time extraction.

## Follow-ups (tracked, not in this change)

- **FR-005-unreadable fixture (11th).** None of the 9 batch announcements meet
  the "model should return `[]`" bar — all are clearly readable. When the curator
  next captures a truly unreadable real announcement (heavily smudged, severely
  cropped, illegible handwriting), add it as `11-...` and re-run the accuracy
  tally. Do **not** synthetically degrade a clean fixture — that violates the
  fixture sourcing policy ("real announcement").

- **Prompt year-resolution rule** — already spawned as [[task_199a06fd]]. After it
  lands and the curator re-records, prune all `date-mismatch` entries from
  `KNOWN_DIVERGENCES` and re-evaluate the count-mismatch on `07-czerwiec-wazne-daty`
  (re-enable from `DISABLED_FIXTURES` if the umbrella-event behaviour goes away).

- **Field-confusion: groups vs dress code.** Fixture 02's divergence shows the
  model puts target-group labels ("Dla Wrzosów i Eukaliptusów") in the
  `requirements` field instead of the dress code ("Prosimy o odświętny ubiór").
  Worth raising in the prompt-fix change as a second rule: `requirements` is for
  what the parent needs to bring or wear, not who the event is for. The current
  schema relegates groups to `notes` (which the diff does not compare).

- **Embedded time extraction.** Fixture 10 carries "Wyjście o g.9:00" in the body
  text but the model leaves `time = null`. Either the prompt should be explicit
  about pulling embedded start times, or the curator accepts time-null as
  documented behaviour for body-text-time announcements.

- **Diff coverage of `notes` is currently zero.** Discovered while drafting this
  batch — `LlmTestFixtures.diff(...)` only compares date/time/title/requirements.
  Anything we put in `notes` is informational only. Either add to the diff (catch
  notes drift as a regression signal) or remove the field from `ProposedEvent`
  (vestigial). Tracked as a soft follow-up, not blocking this change.

## Addendum (2026-06-13): Baseline under relaxed title diff

The `llm-diff-title-tier` change (see [context/changes/llm-diff-title-tier/](../llm-diff-title-tier/)) shipped a narrow rule change: `LlmTestFixtures.diff()` no longer grades `title`. The diff now compares `date` → `time` → `requirements` and treats any title difference as informational, matching how the harness has always treated `notes`. Title still lives in `expected.json`, in `KnownDivergence` rows for failure-message readability, and in `canonicalSort` as a tertiary tie-breaker for deterministic ordering — only the grading assertion is gone.

The new baseline is numerically identical to the one in the table above: **1/9 ≈ 11.1%** including `01-sample`, **1/8 ≈ 12.5%** for the new batch alone. Two independent reasons make this unchanged-by-construction:

1. `KNOWN_DIVERGENCES` had **zero** rows with `field: "title"` before the change (verified empirically), so no fixture moved from "documented divergence" to "clean match" or vice versa.
2. `diff()` short-circuits on the first mismatch. On the **16 fixtures-events with `date-mismatch`** (the year-resolution bug, 03/04/05/06), the title check **never ran** — so any title divergences on those fixtures were invisible to the metric from day one. Relaxing a check that never executed cannot move the score.

Landing this change **before** [[task_199a06fd]] (`llm-prompt-year-resolution`) is what keeps that second condition true. Once the year fix flips those 16 entries to a clean `date`, the title comparison would have started running on them for the first time — potentially exposing previously-masked title divergences and dirtying the year-fix attribution. Doing the title relaxation first means the next change is measured against an honest baseline: any lift after the year fix lands is the year fix's, not a side-effect of newly-visible title noise.
