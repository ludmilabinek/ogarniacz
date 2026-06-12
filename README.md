# Ogarniacz

Spring Boot 4.0.6 / Java 21 / Gradle web service that turns photos and
screenshots of kindergarten announcements into an iCalendar feed.

**Status:** scaffold — no business logic yet. Deployed to Fly.io (region
`fra`) with a Neon Postgres database (`eu-central-1`).

## Foundation docs

- [PRD](context/foundation/prd.md) — what the app must do
- [Tech stack](context/foundation/tech-stack.md) — Spring Boot 4 / Java 21 / Gradle, and why
- [Infrastructure](context/foundation/infrastructure.md) — Fly.io + Neon, alternatives considered
- [CLAUDE.md](CLAUDE.md) — agent instructions + gotchas (Boot 4 naming, security default-on, …)

## Quick start

```bash
./gradlew bootRun       # requires SPRING_DATASOURCE_URL / _USERNAME / _PASSWORD in env
./gradlew test
./gradlew build
```

Without those environment variables the app **will not start** — `data-jpa`
and `postgresql` are on the classpath with no embedded fallback. Details
in [CLAUDE.md](CLAUDE.md).

## Deployment

- [Deploy plan](context/deployment/deploy-plan.md) — full runbook for the first deploy
- [Cost modes](context/deployment/cost-modes.md) — **decision: which Fly Machine mode are you on right now?**
  (`stop` / `suspend` / `warm` — cost vs cold-start trade-off). Currently: `stop`.

A push to `main` triggers an auto-deploy to Fly via GitHub Actions
(`.github/workflows/`, commit [`fd27821`](https://github.com/ludmilabinek/ogarniacz/commit/fd27821)).

## 10xDevs

This project is built as part of the 10xDevs course using the skill chain
`/10x-shape → /10x-prd → /10x-tech-stack-selector → /10x-bootstrapper →
/10x-infra-research → Plan Mode deploy`. Full chain configuration and
artifacts live under `context/` — see [CLAUDE.md](CLAUDE.md).
