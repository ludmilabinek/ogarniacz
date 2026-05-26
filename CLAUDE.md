# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project: Ogarniacz

A Spring Boot 4.0.6 / Java 21 / Gradle web service that turns photos and screenshots of kindergarten announcements into iCalendar feed events. Currently **scaffold-only** — no business logic yet. The product spec is at `context/foundation/prd.md`; the stack-selection rationale is at `context/foundation/tech-stack.md`; the bootstrap audit trail (what was scaffolded, what was skipped, why) is at `context/changes/bootstrap-verification/verification.md`.

### Build, test, run

- `./gradlew build` — compile, test, assemble
- `./gradlew bootRun` — start the app on `:8080`. **Will fail to start** until `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` are set — `data-jpa` + `postgresql` are on the classpath with no embedded fallback (H2 is not on the runtime classpath).
- `./gradlew test` — full test suite
- `./gradlew test --tests com.example.app.AppApplicationTests` — single test class
- `./gradlew clean`

There is no separate lint task wired today; `./gradlew check` is the closest umbrella (currently just `test`).

### Things that are easy to miss

- **Spring Boot 4.x naming**: the web starter is `spring-boot-starter-webmvc`. Boot 3 and Boot 2 docs reference `spring-boot-starter-web` — adjust when copying snippets. The `*-test` starters follow the same pattern (`-webmvc-test`, `-validation-test`, etc.).
- **Security is on by default**: `spring-boot-starter-security` is in `build.gradle`, so every endpoint requires the auto-generated admin password Spring prints on startup. Configure a `SecurityFilterChain` (or remove the starter) before treating any endpoint as open.
- **Module name ≠ directory name**: `rootProject.name = 'app'`, `groupId = com.example`, so the Java package is `com.example.app` while the cwd is `ogarniacz`. If a `com.ogarniacz.*` layout matters for the project, refactor before code lands on top of the current shape.
- **No `git init`** yet — `/10x-bootstrapper` does not initialize one, deliberately. Run `git init` when ready.

### Context contracts (10xDevs chain artifacts)

The `context/` tree carries the project's living foundation docs and in-flight change log. Foundation docs live at the contract paths the chain skills expect:

- `context/foundation/prd.md` — what the app must do (consumed by `/10x-tech-stack-selector`, `/10x-infra-research`, milestone planning)
- `context/foundation/tech-stack.md` — stack pick + hand-off frontmatter (consumed by `/10x-bootstrapper`)
- `context/foundation/shape-notes.md` — discovery notes the PRD was built from
- `context/changes/bootstrap-verification/verification.md` — last bootstrap run audit trail

The chain framework that produced these artifacts is documented in the 10xDevs lesson block below — that block is managed by `@przeprogramowani/10x-cli` (between the `BEGIN`/`END` HTML comments) and gets rewritten when you run `10x-cli get`, so put any project-specific instructions **above** the BEGIN marker, not inside it.

---

<!-- BEGIN @przeprogramowani/10x-cli -->

## 10xDevs AI Toolkit — Module 1, Lesson 5

Pick a deployment platform and ship to production with the **infra chain**:

```
(/10x-init  →  /10x-shape  →  /10x-prd  →  /10x-tech-stack-selector  →  /10x-bootstrapper  →  /10x-agents-md  →  /10x-rule-review  →  /10x-lesson)  →  /10x-infra-research  →  Plan Mode deploy
```

The full Module 1 chain ships from Lessons 1–4 (re-included so you can fix any earlier contract mid-flight). `/10x-infra-research` is the lesson's main topic; the deploy step itself uses the host's built-in **Plan Mode** rather than a dedicated skill — the artifact (`context/deployment/deploy-plan.md`) is what carries forward.

### Task Router — Where to start

| Skill | Use it when |
| --- | --- |
| **Infrastructure (lesson focus)** | |
| `/10x-infra-research [path-to-tech-stack-or-prd]` | You have a `context/foundation/tech-stack.md` (and ideally a `prd.md`) and need to pick an MVP deployment platform. The skill loads the stack as a hard constraint, runs a 5-question developer interview (persistent connections, cost sensitivity, existing familiarity, global reach, co-location preference), spawns parallel subagent research across six candidate platforms, scores them Pass/Partial/Fail across the five agent-friendly criteria from `references/agent-friendly-criteria.md`, shortlists the top three, and runs a three-lens anti-bias cross-check on the leader (devil's advocate, pre-mortem, unknown unknowns) before writing `context/foundation/infrastructure.md`. Use AFTER `/10x-tech-stack-selector`, BEFORE `/10x-implement`. |
| **Deploy (host built-in, not a skill)** | |
| Plan Mode deploy | You have `infrastructure.md` + `tech-stack.md` and want a read-only plan reviewed before any mutation hits the platform. Activate the host's plan mode (Claude Code: `Shift+Tab` cycles default → auto-accept → plan; IDE: dedicated button) with the prompt "Wykonajmy pierwsze wdrożenie w oparciu o `@infrastructure.md`, zgodnie ze stackiem z `@tech-stack.md`". Read the plan, demand corrections, approve, then let the agent execute. The approved plan persists at `context/deployment/deploy-plan.md` so the next lesson's milestone planning can reference what's already deployed and which secrets are already wired. |
| **Re-run upstream if needed** | |
| `/10x-init` / `/10x-shape` / `/10x-prd` / `/10x-tech-stack-selector` / `/10x-bootstrapper` / `/10x-agents-md` / `/10x-rule-review` / `/10x-lesson` / `/10x-stack-assess` / `/10x-health-check` | Bundled so you can patch any earlier contract mid-flight. If the anti-bias cross-check forces a platform swap that pushes a stack-shaped decision (e.g. "this DB doesn't fit any platform we'd accept"), re-run `/10x-tech-stack-selector` to keep `tech-stack.md` and `infrastructure.md` aligned. |

### How the chain hands off

- `/10x-infra-research` reads `context/foundation/tech-stack.md` (language, framework, runtime, database) as **hard constraints** — platforms that can't run the stack are dropped before scoring. It also reads `context/foundation/prd.md` (scale, latency, uptime expectations) as **soft weights** when scoring. Both inputs are optional but strongly recommended; without them the skill proceeds but warns.
- The skill writes `context/foundation/infrastructure.md` as the third foundation contract: frontmatter (`project`, `researched_at`, `recommended_platform`, `runner_up`, `context_type`, `tech_stack`) plus a body covering recommendation, full platform comparison with scoring matrix, anti-bias findings, operational story (preview / secrets / rollback / approval / logs), and a risk register tying every entry back to the lens that surfaced it. On collision the skill prompts: overwrite, save as `infrastructure-v2.md`, or abort.
- Plan Mode reads `infrastructure.md` and `tech-stack.md` together. The agent emits a step-by-step plan covering automated steps it owns, manual setup gates (account creation, secret configuration), exact deploy commands (Pages vs Workers commands are NOT interchangeable on Cloudflare — the plan must specify), and verification steps. The plan is rejected/edited until it's right; only then does Plan Mode exit and execution begin. The approved plan lands at `context/deployment/deploy-plan.md` and is consumed downstream by milestone-planning skills as ground truth for "what's already deployed".

### What the lesson's skills capture (and what they do NOT)

- **`/10x-infra-research` captures**: platform shortlist scored against five agent-friendly criteria (CLI quality, managed/serverless degree, agent-readable docs, stable/scriptable deploy API, MCP or first-class agent integration), three anti-bias outputs on the leader (numbered weaknesses, 150–200-word failure narrative, 3–5 unknown-unknowns), an operational story with one concrete answer per axis (not categories), and a risk register where every row names its source lens (`Devil's advocate` / `Pre-mortem` / `Unknown unknowns` / `Research finding`). Status of every non-GA feature is captured inline (`beta` / `preview` / `region-limited` / `deprecated`) with the date the status was checked.
- **`/10x-infra-research` does NOT** build Docker images or write Dockerfiles, configure CI/CD pipelines, or plan beyond MVP scope (multi-region HA is explicitly out of scope). It does NOT decide for you — the user accepts, swaps to runner-up, or aborts after the cross-check, and that decision is recorded in the output.
- **Plan Mode** captures: an explicit human gate between "agent has a plan" and "agent mutates production". The artifact (`deploy-plan.md`) is the audit trail for "what was supposed to happen" when the live run goes sideways. Plan Mode does NOT replace `/10x-infra-research` (the platform decision must already be made — Plan Mode plans the deploy, it doesn't pick where to deploy).

### Scoring + cross-check (skill-internal)

`/10x-infra-research` scores platforms against five criteria — CLI-first, managed/serverless, agent-readable docs, stable scriptable deploy API, MCP/first-class integration — full definitions in `.claude/skills/10x-infra-research/references/agent-friendly-criteria.md` (reloaded at invocation). Hard filters apply before scoring (persistent-connection requirement, tech-stack runtime mismatch); interview answers reweight after.

Before writing `infrastructure.md` the skill runs three lenses on the leader (devil's advocate, pre-mortem, unknown unknowns). User then picks: proceed, swap to runner-up (re-run lenses), or swap to third place.

Operational posture (CLI vs MCP, scoped tokens, human-on-irreversibles) lands in `infrastructure.md` when it's written — not duplicated here.

### Foundation paths used by this lesson

- `context/foundation/tech-stack.md` — input (Lesson 2 hand-off, hard constraints)
- `context/foundation/prd.md` — input (Lesson 1 hand-off, soft weights)
- `context/foundation/infrastructure.md` — output (the third foundation contract)
- `context/deployment/deploy-plan.md` — output of Plan Mode deploy (audit trail of "what was supposed to happen")
- `context/foundation/lessons.md` — recurring rules & pitfalls (use `/10x-lesson` from Lesson 4 if you spot a class of agent failure during research or deploy)
- `docs/reference/contract-surfaces.md` — load-bearing names registry

### Universal language

The shipped skill carries no 10xDevs / cohort / certification references. The candidate platform list (Cloudflare, Vercel, Netlify, Fly.io, Railway, Render) is the starting research lens, not a recommendation set — the scoring + interview + cross-check pipeline is what's load-bearing, and a platform absent from the default list can be added by extending the research step. The five agent-friendly criteria are the artifact's true core; `/10x-infra-research` re-reads them from `references/agent-friendly-criteria.md` so they evolve as platforms do.

Skills must not write to `context/archive/`. Archived changes are immutable; if a resolved target path starts with `context/archive/`, abort with: "This change is archived. Open a new change with `/10x-new` instead."

<!-- END @przeprogramowani/10x-cli -->
