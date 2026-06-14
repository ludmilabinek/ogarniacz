---
change_id: llm-chatclient-fail-fast
title: Fail fast when ChatClient.Builder is absent instead of returning null
status: open
created: 2026-06-14
updated: 2026-06-14
archived_at: null
---

## Notes

Follow-up surfaced by `/10x-impl-review` on `llm-prompt-year-resolution`
(see `context/changes/llm-prompt-year-resolution/reviews/impl-review.md` §F1).

### What's wrong today

`AppApplication.chatClient(...)` returns `null` when no `ChatClient.Builder`
bean is available:

```java
@Bean
public ChatClient chatClient(ObjectProvider<ChatClient.Builder> builderProvider) {
	ChatClient.Builder builder = builderProvider.getIfAvailable();
	return builder != null ? builder.build() : null;
}
```

This is safe today only because `OpenRouterLlmVisionClient` isn't pulled into
`@DataJpaTest` slices. The failure mode that matters: any future `@WebMvcTest`
(or similar) that imports the LLM component would NPE at the first `extract()`
call instead of failing at context load with a clear "missing bean" error.

### Why the obvious fix didn't work

The impl-review proposed `@ConditionalOnBean(ChatClient.Builder.class)`:

```java
@Bean
@ConditionalOnBean(ChatClient.Builder.class)
public ChatClient chatClient(ChatClient.Builder builder) {
	return builder.build();
}
```

Applying this broke **46 `@SpringBootTest` tests** (`NoSuchBeanDefinitionException:
ChatClient`). Root cause: `@ConditionalOnBean` is evaluated during bean-definition
processing, BEFORE Spring AI's autoconfig has registered `ChatClient.Builder`. So
the condition is false even in contexts where Builder is eventually available
(e.g. `LlmVisionClientTest` with a `@MockitoBean ChatModel`, which causes Spring AI
to provide a Builder later in the lifecycle). `@ConditionalOnBean` isn't the right
tool when the depended-on bean comes from autoconfig.

The fix was reverted; revert kept all tests green.

### Constraint set for whatever lands here

1. **Must keep `@DataJpaTest` (and any future slice tests on this app) loadable.**
   `EventRepositoryTest.java:21` is the only current `@DataJpaTest`; it relies on
   the bean method not blowing up when `ChatClient.Builder` is absent.
2. **Must keep `@SpringBootTest` tests passing.** Currently 46 of them — they get
   `ChatClient.Builder` from Spring AI autoconfig (triggered by `@MockitoBean
   ChatModel`).
3. **Must fail fast in production startup** if neither autoconfig nor a manual
   `ChatClient.Builder` is provided — i.e. don't silently inject a `null`
   `ChatClient` into `OpenRouterLlmVisionClient`.

### Candidate approaches to evaluate

- **Move the bean into a separate `@Configuration` class gated by a property
  (`spring.ai.openai.api-key`) or `@Profile`.** Slice tests skip the config
  entirely; prod loads it and fails clearly if Builder is missing. Aligns with
  F3's eventual `LlmConfig` migration.
- **Drop the @Bean entirely.** If `OpenRouterLlmVisionClient` accepts
  `ChatClient.Builder` (instead of a built `ChatClient`) and calls `.build()`
  in its constructor, the slice-test loadability question moves to "is
  `OpenRouterLlmVisionClient` in the slice's component scan?" — which is `no`
  for `@DataJpaTest`. Verify `@MockitoBean ChatModel` still causes autoconfig
  to provide Builder for `@SpringBootTest`s.
- **Stay on ObjectProvider but throw at construction time** if `OpenRouterLlmVisionClient`
  observes a null `ChatClient` — converts silent NPE-at-call into loud "missing
  bean" at context load. Smallest diff; preserves the slice-test escape hatch.

### Acceptance

- `./gradlew test` stays green (all 73 current tests).
- `./gradlew bootRun` without `OPENROUTER_API_KEY` fails at startup with a
  message that names `ChatClient.Builder` or `OPENROUTER_API_KEY`, NOT later
  at a `/extract` call site.
- A new test (`@WebMvcTest(SomeLlmController.class)` style) — or a hand-rolled
  context-load assertion — confirms that importing `OpenRouterLlmVisionClient`
  into a slice that lacks Builder produces a `BeanCreationException` at load,
  not an NPE at call.

### Why not now

The current null-return is safe today (no `@WebMvcTest` imports the LLM
component, no production deployment without `OPENROUTER_API_KEY`). The impl-review
graded this as WARNING / MEDIUM impact, not CRITICAL. Worth handling alongside the
F3 `LlmConfig` lift when a third unrelated `@Bean` joins `AppApplication`, so the
config split and the conditional both land in one architectural move.
