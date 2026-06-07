---
change_id: openrouter-llm-client-wired
title: Wire OpenRouter vision-LLM client via Spring AI starter (F-01)
status: implemented
created: 2026-06-01
updated: 2026-06-07
archived_at: null
---

## Notes

F-01 z @context/foundation/roadmap.md

### Live verification (2026-06-07)

- **Default model changed**: `google/gemini-2.0-flash-001` (planned) → **`google/gemini-2.5-flash`** (committed). The 2.0-flash slug was retired from OpenRouter — a live call returned `HTTP 404: No endpoints found for google/gemini-2.0-flash-001` (the entire Gemini 2.0 line is gone from the catalog). `gemini-2.5-flash` is the verified replacement: vision-capable Flash tier, ~$1.55 per 1000 announcements, picked over `flash-lite` for robustness on poor-quality photos and over `3.5-flash` on cost (printed-text OCR gains marginal).
- **Smoke result**: `LlmVisionSmokeTest` PASS against real OpenRouter — `outcome=SUCCESS events=2`, 2.7 s wall (budget 55 s), `rawResponse` a clean JSON array matching the `ProposedEvent` contract (`date`=`YYYY-MM-DD`, `time`=`HH:MM`, `null` where absent), Polish text + `LocalDate`/`LocalTime` deserialized correctly.
- **Exception-translation table validated end-to-end**: the pre-fix 404 surfaced as `NotFoundException` → `Kind.PROVIDER_ERROR (HTTP 404)`, exactly per the design.
- **Fly secret rotated (operator-run)**: 3.4 `AI_PROVIDER_API_KEY` swapped from the placeholder to the live OpenRouter key (deploy-plan §7 irreversible gate cleared 2026-06-07).

### Runbook

- **Re-run the smoke after a model swap.** Edit `spring.ai.openai.chat.options.model` in `application.properties`. Export BOTH `OGARNIACZ_LIVE_SMOKE=true` AND `OPENROUTER_API_KEY=sk-or-…`. Run `./gradlew test --tests com.example.app.llm.LlmVisionSmokeTest --rerun-tasks --info`. Expected: passes within 55 s and prints a `LlmExtractionResult`.
- **Rotate the Fly secret.** `fly secrets set AI_PROVIDER_API_KEY='sk-or-…' -a ogarniacz`. Wait ~30 s for the Machine restart, then `fly logs -a ogarniacz | tail -50` and look for `Started AppApplication`.
- **Common failures.** `401` = key wrong/revoked (rotate). `404` on model = OpenRouter slug changed (`curl https://openrouter.ai/api/v1/models -H "Authorization: Bearer $OPENROUTER_API_KEY" | jq '.data[].id'`). `429` = rate-limit (wait or add credit). `SocketTimeoutException` after 55 s = model too slow (swap to a faster model or accept S-05 needs streaming UX).
