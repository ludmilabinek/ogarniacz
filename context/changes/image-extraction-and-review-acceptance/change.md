---
change_id: image-extraction-and-review-acceptance
title: Image extraction and review acceptance
status: impl_reviewed
created: 2026-06-16
updated: 2026-06-21
archived_at: null
---

## Notes

- **Phase 5 bug found + fixed pre-commit (2026-06-21, 9db819f):** `MaxUploadSizeExceededHandler.hasSizeLimitCause` matched only `FileSizeLimitExceededException` (per-file limit). A 22 MB request triggered `SizeLimitExceededException` (per-request total limit, sibling subclass of `SizeException`) and slipped past the filter onto Spring's default English-stacktrace JSON. Fix: match on the abstract `SizeException` base — covers both subclasses and future ones. `ImageUploadOversizeIntegrationTest` now pins both branches (16 MB → per-file, 22 MB → per-request).
- **Phase 5 heap-floor drift (2026-06-21):** measured peak ≈ 517 MB used during a single 12 MB JPEG extraction (delta ≈ 350 MB over baseline) — the plan's ~50 MB estimate was 7× low. With `extractionExecutor max=2`, two concurrent extractions push ~700 MB and are not survivable on the 1 GB Fly Machine. Follow-up before any scale-up: profile Spring AI's request-body buffering at `OpenRouterLlmVisionClient.java:77-90`, then choose between dropping `max=1`, streaming the base64, or bumping the machine class. Recorded in `context/foundation/test-plan.md` §6.7 Phase 4 note as the new baseline.
