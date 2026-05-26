---
project: ogarniacz
researched_at: 2026-05-21
recommended_platform: Fly.io
runner_up: Hetzner Cloud
context_type: mvp
tech_stack:
  language: Java 21
  framework: Spring Boot 4.0.6
  runtime: JVM (Eclipse Temurin)
  database: PostgreSQL (external — Neon free tier, EU region)
---

## Recommendation

**Deploy on Fly.io** — a single `shared-cpu-1x` Machine sized to **1024MB RAM** in the Frankfurt (`fra`) region, **co-located with the Neon free-tier Postgres project in `eu-central-1` (Frankfurt)**. Realistic all-in cost: **~$7/month** (~$6.80 Fly compute + $0 Neon free tier), with mature `flyctl` CLI for deterministic deploys, rollbacks, and log tails — all driveable from GitHub Actions per the `auto-deploy-on-merge` flow already in `tech-stack.md`. The 1GB RAM tier (up from the 512MB minimum) was selected after the anti-bias cross-check surfaced realistic JVM-headroom risk for the Spring Boot 4 + Spring Security + Spring Data JPA + Spring AI classpath under image-extraction load. Co-locating Fly compute and Neon DB in Frankfurt cuts per-request DB-roundtrip latency by ~25–100 ms vs running compute in Amsterdam (`ams`) — same price, single-line `fly.toml` change.

This decision rests on two interview answers: **cost is the top priority** (Fly.io @ 1GB lands at ~$7/mo vs. Railway's realistic $20–30/mo and Render's $14/mo floor) and **external Postgres is acceptable** (which unlocks Neon free tier and avoids Fly Managed Postgres's $38/mo basic plan — the cheapest path on Fly that doesn't blow the cost constraint).

## Platform Comparison

| Platform | CLI-first | Managed / Serverless | Agent-readable docs | Stable deploy API | MCP / integration | Realistic monthly cost (24/7 + Postgres, EU) |
|---|---|---|---|---|---|---|
| **Cloudflare Workers + Pages** | — | — | — | — | — | **Filtered** — V8 isolates only, no JVM runtime |
| **Vercel** | — | — | — | — | — | **Filtered** — Node/Python/Go functions only, no JVM |
| **Netlify** | — | — | — | — | — | **Filtered** — Node/Go functions only, no JVM |
| **Fly.io** | Pass | Partial | Pass | Pass | Partial | **~$3–7** (shared-cpu-1x 512MB ~$3.36 / 1GB ~$6.80) + Neon free |
| **Railway** | Pass | Pass | Pass | Pass | Pass | **~$20–30** ($5 Hobby floor + ~$10/GB-RAM/mo + Postgres) |
| **Render** | Pass | Partial | Pass | Pass | Pass | **~$14** (Starter web $7 + Starter Postgres $7; free tier OOMs Spring Boot 4) |
| **Koyeb** | Pass | Pass | Partial | Pass | Partial | **~$0** (free Frankfurt 512MB + Neon external; free Postgres 5h/mo is unusable) |
| **Hetzner Cloud** | Pass | **Fail** | Partial | Pass | Partial | **~€4** (CX23 2 vCPU/4GB + Postgres-in-Docker on same VPS) |

### Shortlisted Platforms

#### 1. Fly.io (Recommended)

Won on the cost-vs-managed trade-off. `flyctl` is GA, scripts cleanly, returns deterministic exit codes — every operation an agent needs (`fly deploy`, `fly logs`, `fly secrets set`, `fly releases list`, `fly machine restart`) is non-interactive. Docs source live as markdown on `github.com/superfly/docs` (no `llms.txt`, but full repo is agent-scrapeable). EU regions `ams` and `fra` are GA. Spring Boot fat-JAR ships via a Dockerfile or `./gradlew bootBuildImage` — well-trodden path with abundant 2024–2025 community examples. The two Partial scores (managed / MCP) are real but manageable: Dockerfile authoring is one evening of work, and `fly mcp server` being experimental matters less when `flyctl` itself is fully agent-driveable through bash. Pairs cleanly with Neon free-tier Postgres for an MVP cost floor of ~$7/mo at the recommended 1GB tier.

#### 2. Hetzner Cloud

Won on absolute cost (~€4/mo CX23 with Postgres-in-Docker on the same VPS) and on alignment with the `deployment_target: self-host` hint already recorded in `tech-stack.md`. EU-native (Falkenstein/Nuremberg ~25–35ms RTT from Warsaw). `hcloud` CLI is mature (v1.64.1, 104 releases) and the Terraform provider is GA. The disqualifying gap is **Fail on managed/serverless**: the developer (or agent) owns OS patching, TLS renewal (Caddy auto-renews via Let's Encrypt, but you configure it), Postgres backups (`pg_dump` cron to S3/Storage Box), monitoring, and firewall rules. Time-to-first-deploy from zero is ~1–2 hours, with ongoing per-week ops overhead that a solo parent on a 3-week after-hours sprint cannot reliably budget. Picked as runner-up because if Fly.io's MCP or cost stories regress, Hetzner is the principled fallback that matches the original stack intent.

#### 3. Koyeb

Won on the $0 absolute floor (free Web Service in Frankfurt + external Neon free Postgres). Two real gaps push it to third: (a) the free Koyeb Postgres is only **5 compute-hours per month** — effectively unusable for a 24/7 iCalendar feed service, forcing an external DB anyway; (b) the official MCP server is in **beta** with `secrets/volumes/domains` flagged as planned, and Koyeb's docs cover Spring Boot with Maven examples (no Gradle, no Boot 4) — non-trivial documentation risk for the 3-week deadline. The strategic acquisition by Mistral AI (Feb 2026) adds a soft uncertainty about platform direction. Viable if cost is *the* hard constraint and the dev is willing to author the missing Gradle/Boot 4 Dockerfile path themselves.

## Anti-Bias Cross-Check: Fly.io

### Devil's Advocate — Weaknesses

1. **The 512MB floor is too tight for Spring Boot 4 + Spring AI.** Community OOM evidence exists even on "basic" Spring Boot apps at 256MB. The full classpath here (Web MVC + Security + Data JPA + AI client) plus image-multipart and LLM JSON payloads will press 512MB hard. *Mitigated by selecting 1024MB.*
2. **Cheap path is permanently coupled to Neon, not Fly.** Fly Managed Postgres's $38/mo basic plan blows the cost constraint; the recommendation rests on external Neon. The lock-in is to the Postgres provider, not the compute platform — meaning DB-side issues (free-tier ceilings, region defaults, pricing changes) sit outside Fly's control surface.
3. **`fly mcp server` is experimental.** The agent-first posture of the project depends on MCP maturity; Fly's MCP could break with any `flyctl` release. Compare to Railway/Render where MCP is GA. `flyctl` itself is fully scriptable through bash, so this is a soft degradation, not a blocker.
4. **Dockerfile authoring is on the developer.** Unlike Railway's Railpack (auto-detects Gradle + Java 21), Fly requires either a hand-written multi-stage Temurin Dockerfile or `./gradlew bootBuildImage`. At least one evening of JVM memory tuning (`-XX:MaxRAMPercentage`, `-Xss`, heap dumps on Machine restarts) goes into the schedule.
5. **Trial-account friction can cause silent feed downtime.** Fly removed the free tier in October 2024; new orgs get a 2-VM-hour / 7-day trial before requiring a card. If billing is forgotten in week one, the Machine stops, the iCalendar feed 404s — and Google Calendar will keep showing yesterday's cached events silently.

### Pre-Mortem — How This Could Fail

It is late November 2026. The MVP shipped on June 14 (one day before deadline). For three months everything worked. In September the kindergarten started using a new announcement format with denser text — the LLM extraction prompt now pulls back ~40% larger JSON payloads, and the Spring AI client buffers them in heap. The 512MB Fly Machine (had the 1GB upgrade been skipped) starts OOM-killing once a week. The user, juggling work and child-care, doesn't notice for two days because the iCalendar feed keeps returning the *last successfully cached* events to the calendar client — no error surface. Three real kindergarten events fall into the void. They upgrade to 1GB; that's fine until October, when Neon's free tier hits its 100 CU-hour ceiling because of a daily background re-extraction job; the DB goes read-only mid-month, accepted-event writes fail in the JPA layer (logged but not surfaced), and *new* events stop appearing in the calendar. The user concludes "this doesn't work" and falls back to manual re-typing. The MVP shipped on time but failed the trust criterion — because the failure mode was invisible through the iCalendar layer.

### Unknown Unknowns

- **iCalendar client behavior under feed failures is benign-looking.** Google Calendar caches feeds and shows the last successful poll's events even on 5xx. Fly Machine OOMs and Neon read-only states produce *silent* failures from the user's perspective — yesterday's events look like today's events. A separate uptime monitor (UptimeRobot, Better Stack) on the feed URL is mandatory, not just `/actuator/health`.
- **`auto_stop_machines = true` will eat the PRD's 60s extraction ceiling.** Cold-starting a JVM (10–15s) + Spring context (5–10s) + Hibernate first-connection (2–3s) can consume 20–30s *before the image even reaches the LLM*. Keep `min_machines_running = 1` and accept the $6.80/mo for 1GB — auto-stop is the wrong optimization for this UX.
- **Default `[deploy] strategy` is `immediate` for single-machine apps.** Every commit produces a brief 503 window. With auto-deploy-on-merge, the iCalendar polling cadence can land in that window. Switch to `strategy = "rolling"` once you have ≥2 Machines, or schedule deploys to a quiet hour for now.
- **Neon free tier defaults to US East, not EU.** Solo dev under deadline pressure is exactly when this defaulting goes unnoticed — child-data lives in `us-east-2` while the app runs in `ams`. Explicitly select `eu-central-1` (Frankfurt) at Neon project creation. GDPR implications are real even for a single-user MVP.
- **Spring Boot 4.0.6 is brand new (2026).** Most Fly + Spring Boot community blog posts and Dockerfile examples are Boot 3.x. Several APIs (security DSL, `WebMvcConfigurer` defaults) changed in Boot 4. Don't paste 2024-era Spring Boot Dockerfiles verbatim — verify against the Boot 4 migration guide.

## Operational Story

- **Preview deploys**: Fly doesn't have built-in PR-preview environments like Vercel. For an MVP at this scale, **skip them** — develop on a local Postgres + `./gradlew bootRun`, push to `main`, let GitHub Actions invoke `fly deploy` (auto-deploy-on-merge per `tech-stack.md`). If preview becomes necessary, the pattern is one Fly app per PR via `fly apps create ogarniacz-pr-{NUMBER}` in a workflow on `pull_request` events.
- **Secrets**: Stored in Fly's encrypted secrets store via `fly secrets set SPRING_DATASOURCE_URL=... SPRING_DATASOURCE_USERNAME=... SPRING_DATASOURCE_PASSWORD=... NEON_URL=... AI_PROVIDER_API_KEY=...`. Available to the Machine as env vars at runtime. CI uses a single `FLY_API_TOKEN` stored in GitHub Actions repo secrets (scope: deploy-only, generated via `fly tokens create deploy -a ogarniacz`). Rotation: re-run `fly tokens create deploy`, update GitHub secret, revoke old via `fly tokens revoke`.
- **Rollback**: `fly releases list` → identify previous version → `fly releases rollback <version>`. Time-to-revert: ~30–60s for the rollback Machine to come up. **Caveat**: DB schema migrations run by Spring Boot on startup do NOT roll back automatically. If a release ships a destructive schema change, rolling back compute without rolling back the DB will produce runtime errors. Use additive-only migrations for the MVP (no column drops, no type narrowing).
- **Approval**: Agent may perform unattended: `fly deploy`, `fly logs`, `fly machine restart`, `fly secrets set` for non-rotating values. **Human required for**: rotating the production `FLY_API_TOKEN`, upgrading the Machine class (cost impact), running `fly machine destroy`, scaling `min_machines_running = 0` (silent-outage risk), changing the Neon connection string (DB cutover).
- **Logs**: `fly logs -a ogarniacz` streams live; `fly logs --since 1h` for retrospective. Logs are not retained beyond ~24h on the free CLI tier — for the iCalendar trust criterion, ship app logs to an external sink (`fly machine ssh` + `journalctl` is not it; use a SLF4J appender to stdout, then Fly forwards to Logflare or Better Stack). For the MVP, `fly logs` is sufficient; promote when the second user comes on board.

## Risk Register

| Risk | Source | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| JVM OOM on Spring Boot 4 + Spring AI under image-extraction load | Devil's advocate (#1), Pre-mortem | M | H (silent feed failures) | Start at 1GB Machine, not 512MB. Set `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError` so OOMs fail loud, not silent. Run a 50-extraction load test before declaring MVP done. |
| Silent iCalendar feed failure masked by client-side caching | Unknown unknowns | M | H (loss of user trust — the load-bearing PRD guardrail) | External uptime monitor (UptimeRobot free tier) hitting the feed URL every 5 min, alerting on non-200 or empty `BEGIN:VCALENDAR` body. |
| JVM cold start consumes PRD's 60s extraction ceiling | Unknown unknowns | M | M (UX feels broken) | `min_machines_running = 1` in `fly.toml`. Do NOT enable `auto_stop_machines = true`. Accept $6.80/mo as the cost of warm JVM. |
| Neon free-tier ceiling (100 CU-h / 0.5GB) hit mid-month | Pre-mortem | L (single user) → M (post-MVP) | H (DB read-only, write failures) | Pin Neon to `eu-central-1` at project creation. Set a Neon usage alert at 80% via Neon Console. Budget plan: $19/mo Neon Launch tier as the upgrade path before second user onboards. |
| Fly trial expires before billing card added | Devil's advocate (#5) | L | H (Machine stops, feed 404) | Add billing card on day 1, before deploying anything beyond `fly launch --no-deploy`. Set **Fly Organization → Spending Limits to a $20/mo hard cap** (boxes-in runaway cost from a misconfigured Machine scale-up). Configure a **billing alert at $10/mo** as an early-warning before the cap trips. Ensure the Fly account email points to an inbox **that is actually checked** — Fly's only notification channel for trial-expiry, billing failures, and cap-hit shutdowns is email; a forgotten inbox turns the cap into the same silent-outage failure mode the cap was supposed to prevent. |
| `fly mcp server` experimental status — breaks during a `flyctl` upgrade | Devil's advocate (#3) | M | L (degrades agent ops to bash-only) | Pin `flyctl` version in CI. Do not rely on MCP for any irreversible operation — keep humans on `fly machine destroy` and `fly tokens revoke`. |
| Boot 4 + Fly examples mismatch (most blog posts are Boot 3.x) | Unknown unknowns | M | M (debugging time burn) | Use Spring Boot 4 migration guide as source of truth for Dockerfile, not 2024-era community Fly templates. Validate the Dockerfile against `./gradlew bootBuildImage` output first. |
| DB schema rollback divergence after Spring Boot auto-migration | Research finding (operational story) | L | H (rollback produces runtime errors) | Additive-only Flyway/Liquibase migrations for MVP. No destructive changes (drops, type narrowing) until a backup/restore drill is verified. |
| GDPR — Neon defaults to US East at signup | Unknown unknowns | M | H (regulatory + trust) | At Neon project creation, explicitly pick `eu-central-1`. Record region in `application.yml` comment as the canonical hint for the next deploy. |
| Auto-deploy lands in iCalendar poll window (503 during `immediate` strategy) | Unknown unknowns | L (single user, low QPS) | L | Accept for MVP. Switch to `strategy = "rolling"` once min_machines ≥ 2 (post-MVP). |

## Getting Started

These commands assume the project is at `/Users/ludmiladrzewiecka/workspace/10xdevsOgarniacz` and the Spring Boot Gradle scaffold from `/10x-bootstrapper` is in place.

1. **Install `flyctl` and sign up.** `brew install flyctl` (macOS) followed by `fly auth signup`. **Add a billing card immediately** — the trial window (2 VM-hours / 7 days) is too short to wait on. Generate a deploy-scoped token for GitHub Actions: `fly tokens create deploy -a ogarniacz` (run after Step 3).

2. **Create the Neon project in the EU region.** Sign up at `console.neon.tech`, create a project explicitly in `eu-central-1` (Frankfurt) — do NOT accept the US East default. Copy the pooled connection string; that's what Spring will use as `SPRING_DATASOURCE_URL`.

3. **Author a Dockerfile** (Fly has no native Spring Boot detection). Multi-stage with `eclipse-temurin:21-jdk` for build + `eclipse-temurin:21-jre` for runtime; copy the fat JAR from `build/libs/`. Validate locally: `./gradlew bootJar && docker build -t ogarniacz:local . && docker run --rm -p 8080:8080 ogarniacz:local`. Confirm the container boots under 1GB before pushing to Fly. **Verify against Spring Boot 4.0.6's actual artifact name** — the `bootJar` task output filename includes the project version and may differ from Boot 3.x templates.

4. **Run `fly launch --no-deploy`** in the project root. Pick `fra` (Frankfurt) as the primary region to co-locate with the Neon Postgres created in Step 2 — this saves ~25–100 ms per DB round-trip vs `ams` and matters most for JPA-heavy request paths (event list, acceptance writes). Accept the suggested app name `ogarniacz` (or pick another). The generated `fly.toml` will need three edits before the first deploy: set `primary_region = "fra"`, set `[[vm]] memory = "1gb"`, set `[http_service] auto_stop_machines = false` and `min_machines_running = 1`, and confirm `internal_port = 8080` matches Spring's default.

5. **Set secrets and deploy.**
   ```
   fly secrets set \
     SPRING_DATASOURCE_URL="<neon pooled URL>" \
     SPRING_DATASOURCE_USERNAME="<neon user>" \
     SPRING_DATASOURCE_PASSWORD="<neon password>" \
     AI_PROVIDER_API_KEY="<extraction provider key>"
   fly deploy
   ```
   Confirm with `fly logs` that Spring Boot finished context init and bound `:8080`. Hit `https://ogarniacz.fly.dev/actuator/health` (after configuring Spring Security to allow it) to verify.

6. **Wire GitHub Actions auto-deploy-on-merge.** Add `FLY_API_TOKEN` (the deploy-scoped token from step 1) as a repo secret. The workflow step is `superfly/flyctl-actions/setup-flyctl@master` followed by `flyctl deploy --remote-only`. Per `tech-stack.md`'s `ci_default_flow: auto-deploy-on-merge`, gate this on `push` to `main` only.

## Out of Scope

The following were not evaluated in this research:
- Docker image hardening, multi-stage build optimization, layer caching beyond what `eclipse-temurin:21-jre` provides by default.
- CI/CD pipeline structure beyond the single auto-deploy-on-merge step (test job ordering, branch protection, required checks — defer to `/10x-implement` and milestone planning).
- Production-scale architecture: multi-region failover, HA Postgres, read replicas, DR runbooks, SLA commitments. The PRD scopes the MVP to single user / low QPS / small data volume; these concerns are explicitly deferred.
- Object storage for uploaded images: the PRD's NFR says source images are purged after every proposed event from that image is accepted/rejected, so local Machine ephemeral disk is sufficient for the MVP. Persistent object storage (Cloudflare R2, Backblaze B2) becomes relevant only if the retention policy changes.
- Email/SMTP for future password-reset flow (out of MVP per PRD §Access Control).
