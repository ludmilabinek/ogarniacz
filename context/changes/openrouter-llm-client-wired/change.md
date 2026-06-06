---
change_id: openrouter-llm-client-wired
title: Wire OpenRouter vision-LLM client via Spring AI starter (F-01)
status: implementing
created: 2026-06-01
updated: 2026-06-06
archived_at: null
---

## Notes

F-01 z @context/foundation/roadmap.md

### Runbook

- **Re-run the smoke after a model swap.** Edit `spring.ai.openai.chat.options.model` in `application.properties`. Export BOTH `OGARNIACZ_LIVE_SMOKE=true` AND `OPENROUTER_API_KEY=sk-or-…`. Run `./gradlew test --tests com.example.app.llm.LlmVisionSmokeTest --rerun-tasks --info`. Expected: passes within 55 s and prints a `LlmExtractionResult`.
- **Rotate the Fly secret.** `fly secrets set AI_PROVIDER_API_KEY='sk-or-…' -a ogarniacz`. Wait ~30 s for the Machine restart, then `fly logs -a ogarniacz | tail -50` and look for `Started AppApplication`.
- **Common failures.** `401` = key wrong/revoked (rotate). `404` on model = OpenRouter slug changed (`curl https://openrouter.ai/api/v1/models -H "Authorization: Bearer $OPENROUTER_API_KEY" | jq '.data[].id'`). `429` = rate-limit (wait or add credit). `SocketTimeoutException` after 55 s = model too slow (swap to a faster model or accept S-05 needs streaming UX).
