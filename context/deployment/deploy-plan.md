# Ogarniacz — First Deployment Plan (Fly.io + Neon Postgres)

> **For agentic workers:** This is a deploy runbook, not a feature plan. Each phase below has explicit approval levels — `unattended`, `requires human confirm`. Do not collapse phases. Do not skip human gates on irreversible operations.

**Goal:** Ship the first production deployment of Ogarniacz (Spring Boot 4.0.6 / Java 21) to Fly.io `fra` with Neon Postgres in `eu-central-1`, exactly per `context/foundation/infrastructure.md`.

**Sources of truth (load-bearing):**
- `context/foundation/infrastructure.md` (researched 2026-05-21) — platform pick, sizing, risk register
- `context/foundation/tech-stack.md` — stack constraints
- `CLAUDE.md` — Spring Boot 4 naming gotchas, package layout, security default-on

**Decisions confirmed in this planning session:**
1. Spring AI starter wiring is **deferred** — first deploy ships without it. `AI_PROVIDER_API_KEY` is reserved as a Fly secret with a placeholder value to lock the name; real key + starter wiring land in a follow-up change.
2. Neon connection uses the **pooled** URL with `?prepareThreshold=0&preparedStatementCacheQueries=0` appended. This matches infrastructure.md's pooled recommendation while neutralizing Hibernate's prepared-statement-cache collision against pgbouncer transaction mode.

**Snapshot caveat:** Every Fly billing/trial/region fact in this plan was taken from `infrastructure.md` (`researched_at: 2026-05-21`). Phase 2.1 includes a mandatory live verification against `fly.io/pricing` before the human commits a card.

---

## Context

`context/changes/bootstrap-verification/verification.md` confirms the Spring Boot scaffold was generated `2026-05-20` with `git init` **deliberately skipped** and no CI / Dockerfile / Fly config produced. `build.gradle` declares Boot 4.0.6, Java 21 toolchain, starters `webmvc`, `security`, `data-jpa`, `validation`, `devtools`, `postgresql` driver, JUnit. **Not present** in `build.gradle`: `spring-boot-starter-actuator`, any `spring-ai-*`. **Not present** in `src/main/java/com/example/app/`: any `SecurityFilterChain` bean. `application.properties` contains only `spring.application.name=app`. `rootProject.name = 'app'`, so Fly's `fly launch` will suggest `app` — the plan explicitly overrides to `ogarniacz`.

The first deployment must close all of those gaps in the right order: actuator on the classpath before the smoke test can hit `/actuator/health`; `SecurityFilterChain` permitting that endpoint before the smoke test returns 200 instead of the auto-generated-password 401; Dockerfile validated locally under the 1 GB ceiling before any byte hits Fly; secrets set before the first `fly deploy` so Spring Boot doesn't crash on `SPRING_DATASOURCE_URL` resolution.

---

## 1. Pre-flight checklist — what's already done

Inspected at planning time (2026-05-21). `[x]` = present in repo; `[ ]` = pending.

- [x] Spring Boot 4.0.6 scaffold exists at `/Users/ludmiladrzewiecka/workspace/10xdevsOgarniacz`
- [x] `build.gradle` declares Java 21 toolchain + Boot 4.0.6 plugin
- [x] Starters present: `spring-boot-starter-webmvc`, `-security`, `-data-jpa`, `-validation`, `-devtools` + `postgresql` runtime driver
- [x] `AppApplication.java` exists at `src/main/java/com/example/app/AppApplication.java`
- [x] `AppApplicationTests.contextLoads()` exists
- [x] `.gitignore` covers Java/Gradle/IDE (STS, IntelliJ, NetBeans, VS Code)
- [x] `context/foundation/infrastructure.md` + `tech-stack.md` are committed-context (foundation contracts)
- [ ] `spring-boot-starter-actuator` added to `build.gradle` (**required for `/actuator/health`**)
- [ ] `SecurityFilterChain` bean exists exposing `/actuator/health` and requiring auth elsewhere
- [ ] `application.properties` exposes actuator health endpoint
- [ ] `Dockerfile` authored (multi-stage temurin 21 jdk → jre, JVM mem flags)
- [ ] `.dockerignore` authored
- [ ] `fly.toml` committed with `primary_region = "fra"`, `[[vm]] memory = "1gb"`, `auto_stop_machines = false`, `min_machines_running = 1`, `internal_port = 8080`
- [ ] `.git` initialized
- [ ] GitHub remote configured (empty repo created in section 2.4)
- [ ] `.github/workflows/deploy.yml` authored with pinned `superfly/flyctl-actions/setup-flyctl@v1.5`
- [ ] Fly account exists, billing card on file, account email verified (daily-read inbox), bank-side transaction alert set on the card, weekly calendar reminder for dashboard check (Fly does NOT offer in-platform caps or alerts; `fly.toml` resource config + bank alert + manual review is the actual safety net)
- [ ] Neon account exists, project created in `eu-central-1` (Postgres 17, no Auth/Branching/AI add-ons), account email is a daily-read inbox (Neon free tier auto-emails near limit; no custom threshold available)
- [ ] AI provider API key obtained (deferred — placeholder secret set in this deploy; real wiring lands later)
- [ ] External uptime monitor account ready (UptimeRobot or Better Stack) — monitor configured post-deploy in Phase I

---

## 2. Manual setup gates — human-only steps

Each step must be completed by the human before the next agent phase. Gate phrases are literal — the agent will wait for them verbatim.

### 2.1 Fly.io signup + best-available cost controls ⚠️
- **Reality check (verified against Fly docs 2026-05-21):** Fly.io **does NOT offer hard spending caps OR billing alerts**, contrary to what infrastructure.md's snapshot assumed. Fly's own docs state: *"Free allowances don't cap your bill... there's no soft ceiling. If you go over, we'll bill you."* and *"We don't support billing alerts (yet), so budget accordingly."* This means the original plan ask of `$20 hard cap + $10 alert` is unfulfillable in Fly's UI. The mitigations below are what's actually available.
- **Action:** Sign up at `fly.io` (or use existing account), **then open `https://fly.io/pricing` in a browser and verify the current `shared-cpu-1x` 1 GB pricing matches infrastructure.md's snapshot (`shared-cpu-1x` 1 GB ≈ $6.80/mo). If it differs significantly, pause and refresh the infra research.** Add a billing card if not already attached. Then do the three things that actually work as cost controls:
  1. **Verify the account email is an inbox you read daily.** Go to `Account` (top-right avatar) → `Settings`; confirm email shows a "verified" badge. If not, Fly mailed a confirmation link at signup — check inbox/spam. This is the only channel Fly uses to reach you about anything (trial-expiry, suspended Machine, etc.).
  2. **Set a per-transaction alert at your bank** on the card attached to Fly — e.g. "SMS me on any transaction ≥ $10 from merchant `FLY.IO`". Most banks support this in their app/online portal. This is your *de facto* $10 alert, since Fly itself won't send one.
  3. **Treat `fly.toml` resource config as the de facto cap.** With `memory = "1gb"`, `min_machines_running = 1`, `auto_stop_machines = false` (all already in Phase D.2), the *physical maximum* this deployment can charge is ~$7/mo for compute + bandwidth. Without manual scaling actions, the bill cannot run away on its own. The "cap" is the resource ceiling in the file, not a clickable setting.
  4. **Add a recurring calendar reminder** (weekly, e.g. every Monday) — *"check fly.io dashboard → current month to date bill"*. This is the monitoring loop Fly explicitly recommends.
- **Values to record:** none (Fly account email + org slug already known to human)
- **Gate condition:** Agent waits until human confirms either form: `"Fly email verified, bank-side alert set, weekly dashboard reminder added"` OR (equivalent stronger form, when the card itself has a hard balance limit) `"Fly email verified, card has hard balance limit, transaction alerts set"`. The card-balance-limit variant is actually a stronger control than what Fly would offer platform-side — a declined charge produces a Machine suspension that Fly notifies about via the verified email channel, closing the same loop more decisively.
- **Why:** Mitigates risk register row #5 (trial expiry → silent Machine stop → feed 404) **as well as the unbounded-bill failure mode**. Since Fly doesn't expose a clickable cap, the safety net is composed of: verified-inbox (catches Fly's emails about trial/suspended state) + bank-side transaction alert (catches actual charges) + `fly.toml`-as-cap (limits what can be charged in the first place) + weekly dashboard check (catches drift before it compounds). This closes the silent-outage AND silent-overage failure modes together, given the platform's actual capabilities.

### 2.2 Neon Postgres project in EU + pooled connection string ⚠️
- **Action:** Sign up at `console.neon.tech`. Create a project named `ogarniacz`. **At project creation, explicitly select region `eu-central-1` (Frankfurt). Do NOT accept the US East default.** **Select Postgres major version 17** (matches local validation in Phase C.3; one less variable when chasing "works locally, fails in Fly" bugs). **Do NOT enable Neon Auth, Neon Branching, or Neon AI add-ons** — real auth is out of scope per §8, and the additive-only-migrations rule in §5.2 assumes the application is the sole schema writer; Neon Auth would create a `neon_auth` schema that JPA's `ddl-auto=update` would see and potentially mishandle. Once provisioned, open the connection-string panel and copy the **pooled** connection string. **Append the query params `?prepareThreshold=0&preparedStatementCacheQueries=0`** to the URL — these disable Hibernate's prepared-statement cache and avoid the `prepared statement S_X already exists` failure mode under pgbouncer transaction mode. **Free-tier usage alert: Neon does NOT expose configurable user-defined thresholds on the free plan.** Instead, Neon automatically emails the account address at ~75–80% and 100% of the compute-hour ceiling. The actionable equivalent is therefore: **confirm the Neon account email is an inbox you read daily** — that's what actually closes the risk register row, not a clickable threshold setting.
- **Values to record:**
  - `SPRING_DATASOURCE_URL` = `<pooled JDBC URL>?prepareThreshold=0&preparedStatementCacheQueries=0`
  - `SPRING_DATASOURCE_USERNAME` = `<neon role>`
  - `SPRING_DATASOURCE_PASSWORD` = `<neon password>`
- **Gate condition:** Agent waits until human confirms: `"Neon ready in eu-central-1, pooled URL with prepareThreshold=0"`
- **Why:** Mitigates risk register "Neon free-tier ceiling" row (80% alert) + GDPR row (`eu-central-1` explicit pick) + the silent-prepared-statement-failure mode that pooled mode introduces.

### 2.3 AI provider API key (deferred wiring, secret reserved)
- **Action:** Spring AI starter is not yet on the classpath — provider selection is intentionally out of scope for first deploy. To reserve the secret name now, record a placeholder value `AI_PROVIDER_API_KEY=pending-provider-selection`. When the provider is picked (next change), Phase F's `fly secrets set` step will be re-run with the real value — same command, same name, no infrastructure rework.
- **Values to record:** `AI_PROVIDER_API_KEY=pending-provider-selection`
- **Gate condition:** Agent waits until human confirms: `"AI key placeholder noted"`
- **Why:** Reserves the secret name so the eventual rotation is a single `fly secrets set` re-run with no `fly.toml` changes.

### 2.4 GitHub: create empty repo ⚠️
- **Action:** In GitHub web UI (or `gh repo create ludmilabinek/ogarniacz --private --confirm` if `gh` CLI is set up locally), create an **empty private repo** named `ogarniacz`. Do not initialize with README, .gitignore, or license — the local repo already has files to push.
- **Values to record:** SSH or HTTPS remote URL, e.g., `git@github.com:ludmilabinek/ogarniacz.git`
- **Gate condition:** Agent waits until human confirms: `"GitHub repo created, remote is git@github.com:ludmilabinek/ogarniacz.git"` (substitute actual URL)
- **Why:** Phase A needs a real remote to `git remote add` and `git push -u`. Creating the repo first avoids the agent guessing.

### 2.5 External uptime monitor account (configured post-deploy)
- **Action:** Create a free account at UptimeRobot (`uptimerobot.com`) or Better Stack (`betterstack.com/uptime`). Do **not** configure the monitor yet — the feed URL doesn't exist until Phase G succeeds. Defer monitor wiring to Phase I.
- **Values to record:** login credentials (kept by human; not handed to agent)
- **Gate condition:** Agent waits until human confirms: `"Uptime monitor account ready"`
- **Why:** Risk register's "Silent iCalendar feed failure" row depends on this. Account ready now → Phase I is one-touch.

---

## 3. Agent-automated setup — commands and order

All commands run in `/Users/ludmiladrzewiecka/workspace/10xdevsOgarniacz` unless noted. Each phase lists its approval level and the infrastructure.md gate that authorizes it.

### Phase A — `git init` + initial commit + remote

**Approval:** `unattended` (git init/add/commit/push to a brand-new remote is reversible by deleting the GitHub repo).
**Authorized by:** CLAUDE.md "No `git init` yet — `/10x-bootstrapper` does not initialize one, deliberately. Run `git init` when ready."

A.1 Initialize repo:
```bash
cd /Users/ludmiladrzewiecka/workspace/10xdevsOgarniacz
git init -b main
```
Expected: `Initialized empty Git repository in .../.git/`.

A.2 Confirm `.gitignore` is adequate (it was bootstrapped). The Spring Initializr `.gitignore` already covers `build/`, `.gradle/`, IDE folders. Add three lines for Fly + Docker artifacts:
```bash
cat >> .gitignore <<'EOF'

# Fly + Docker
.fly/
*.log
heapdump.hprof
EOF
```
Expected: no output; verify with `tail -5 .gitignore`.

A.3 Initial commit:
```bash
git add .
git status   # human can eyeball the staged set
git commit -m "chore: initial commit of bootstrapped Spring Boot 4 scaffold"
```
Expected: one commit listing scaffold + context files.

A.4 Wire remote and push:
```bash
git remote add origin <REMOTE_URL_FROM_2.4>
git push -u origin main
```
Expected: branch tracking set up.

**Failure handling:** if `git push` fails with "remote rejected" because the GitHub repo wasn't actually empty, abort and have the human reset the GitHub repo. Do not `--force` push.

### Phase B — add `spring-boot-starter-actuator` + author Dockerfile + .dockerignore

**Approval:** `unattended` (local file edits, no network mutation).
**Authorized by:** infrastructure.md Getting Started step 3; risk register #1 (JVM OOM mitigation via flags).

B.1 Add `spring-boot-starter-actuator` to `build.gradle`:

Edit `build.gradle`, inside the `dependencies { }` block, add **after** the existing `spring-boot-starter-webmvc` line:
```gradle
	implementation 'org.springframework.boot:spring-boot-starter-actuator'
```
Verify with `./gradlew dependencies --configuration runtimeClasspath | grep actuator`. Expected: `spring-boot-starter-actuator` appears.

B.2 Update `application.properties` to expose the health endpoint (Spring Boot 4 actuator does **not** expose `/actuator/health` over HTTP by default):

Append to `src/main/resources/application.properties`:
```properties
management.endpoints.web.exposure.include=health
management.endpoint.health.access=read-only
# UptimeRobot (Phase I) polls /actuator/health every 5 min. With the default DataSourceHealthIndicator
# enabled, each poll runs SELECT 1 on Neon — preventing Neon's 5-min idle auto-suspend and burning
# the free-tier compute-hour ceiling (~190h/mc) in ~1 week. Disable so health stays app-only while
# /actuator/health is still the placeholder monitor target. When the iCalendar feed ships and
# replaces /actuator/health as the canary URL (Phase I I.1), the feed query itself touches DB,
# so this knob becomes moot — that's the moment to revisit Neon plan tier vs. monitor interval.
management.health.db.enabled=false
# Schema migrations are additive-only for MVP. See deploy plan §5: no column drops,
# no type narrowing — rollback compatibility depends on it.
spring.jpa.hibernate.ddl-auto=update
```

B.3 Create `Dockerfile` at repo root. Multi-stage, temurin 21 jdk for build, temurin 21 jre for runtime, JVM flags from risk register #1. Predicted bootJar filename is `app-0.0.1-SNAPSHOT.jar` based on `rootProject.name='app'` and `version='0.0.1-SNAPSHOT'` in `build.gradle` — verify in Phase C before relying on this name.

Create `/Users/ludmiladrzewiecka/workspace/10xdevsOgarniacz/Dockerfile`:
```dockerfile
# syntax=docker/dockerfile:1.7

# ---- build stage ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
COPY src src
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon bootJar

# ---- runtime stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app
# Boot 4 bootJar default name follows rootProject.name + version; verify in Phase C.
COPY --from=build /workspace/build/libs/app-0.0.1-SNAPSHOT.jar /app/app.jar
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

B.4 Create `.dockerignore` at repo root:
```
.git
.gradle
build
.idea
*.iml
.vscode
heapdump.hprof
context
docs
*.md
```

B.5 Add H2 as test-runtime dependency so `@SpringBootTest`-based tests can boot a context without production DB credentials. The scaffolded `AppApplicationTests.contextLoads()` currently fails on `DataSourceBeanCreationException` because `data-jpa` + `postgresql` driver are on the classpath with no embedded fallback. Add to `build.gradle` inside the `dependencies { }` block:
```gradle
	testRuntimeOnly 'com.h2database:h2'
```
Verify with `./gradlew test` — expected: `BUILD SUCCESSFUL`, `AppApplicationTests.contextLoads()` passes (Hibernate creates schema in H2 under the hood).

B.6 Commit:
```bash
git add build.gradle src/main/resources/application.properties Dockerfile .dockerignore
git commit -m "build: add actuator starter, Dockerfile, JVM mem flags, H2 test runtime"
```

### Phase C — local Dockerfile validation under 1 GB

**Approval:** `requires human confirm` before advancing to Phase D.
**Authorized by:** infrastructure.md Getting Started step 3 ("Confirm the container boots under 1GB before pushing to Fly").

C.1 Build the fat JAR and inspect the actual filename:
```bash
./gradlew bootJar
ls -lh build/libs/
```
Expected: a single file `app-0.0.1-SNAPSHOT.jar`. **If the filename differs**, update `Dockerfile`'s `COPY --from=build` line to match and rebuild.

C.2 Build the image:
```bash
docker build -t ogarniacz:local .
```
Expected: image built; final layer reports `Successfully tagged ogarniacz:local`.

C.3 **Start a local Postgres in a second terminal — do NOT use the production Neon credentials for local validation.** Reason: keep production credentials out of shell history; `ddl-auto=update` against a throwaway local DB also prevents accidental schema creation on production Neon from a developer laptop. Run:
```bash
docker run --rm -d --name ogarniacz-pg \
  -e POSTGRES_PASSWORD=local-dev-only \
  -e POSTGRES_DB=ogarniacz \
  -p 5432:5432 \
  postgres:17
```
Postgres 17 matches the Neon project version selected in §2.2 — same major across local and prod removes one variable from any "works locally, fails in Fly" investigation. Wait ~5 seconds for Postgres to accept connections, then verify:
```bash
docker logs ogarniacz-pg 2>&1 | grep "ready to accept connections"
```
Expected: at least one line `database system is ready to accept connections`.

C.3.1 Run the app container with **local** DB env vars (NOT Neon's) under a hard 1 GB cap:
```bash
docker run --rm -m 1g -p 8080:8080 \
  -e SPRING_DATASOURCE_URL='jdbc:postgresql://host.docker.internal:5432/ogarniacz' \
  -e SPRING_DATASOURCE_USERNAME=postgres \
  -e SPRING_DATASOURCE_PASSWORD=local-dev-only \
  --name ogarniacz-local ogarniacz:local
```
Expected: Spring context starts, `Tomcat started on port 8080`, no `OutOfMemoryError`. The JVM should report `Max heap ≈ 768 MB` given `MaxRAMPercentage=75` of the 1 GB cgroup limit. The pgbouncer prepared-statement collision mode does not apply here (local Postgres is not behind pgbouncer) — that path is verified in Phase G against real Neon logs.

C.4 In a second terminal, smoke `/actuator/health`. **It will return 401 at this point** because Spring Security is still default-locked-down; Phase E adds the `SecurityFilterChain`. For now confirm only that:
```bash
curl -i http://localhost:8080/actuator/health
```
returns `HTTP/1.1 401` with `WWW-Authenticate: Basic ...` — that proves the endpoint exists and Spring Security is actively guarding it (not a 404, which would mean actuator never registered).

C.5 Cleanup — stop both containers:
```bash
# Ctrl-C in terminal 1 to stop the app, then:
docker stop ogarniacz-pg
```
Expected: both containers removed (each was started with `--rm`).

**Human confirm gate:** Agent waits until human confirms: `"Local image boots clean under 1 GB and actuator endpoint reached"`.

**Failure handling:** OOM under 1 GB → drop `MaxRAMPercentage` to `65` and retry; if still OOM, escalate to human (likely a classpath problem, not memory). App container can't reach Postgres (`Connection refused` / `host.docker.internal` unresolved) → on Linux substitute `--add-host=host.docker.internal:host-gateway` to the app `docker run`; on macOS/Windows Docker Desktop the hostname resolves out of the box.

### Phase D — `fly launch --no-deploy` + edit `fly.toml`

**Approval:** `unattended` (no resource created beyond a Fly app shell; can be destroyed with `fly apps destroy` if name is wrong).
**Authorized by:** infrastructure.md Getting Started step 4.

D.1 Run launch in non-interactive mode where possible, but the human needs to confirm the app-name override since `rootProject.name='app'` will produce a default suggestion of `app`:
```bash
fly launch --no-deploy --name ogarniacz --region fra --no-github
```
Expected: `fly.toml` written at repo root, app `ogarniacz` reserved in Fly's global namespace.

⚠️ **App name reservation is global and effectively irreversible.** If `ogarniacz` is taken, fall back to a scoped name (e.g., `ogarniacz-ludmilabinek`) and update infrastructure.md cross-references — do not silently rename mid-deploy.

D.2 Edit the generated `fly.toml`. The file ships with Fly's auto-detected defaults; overwrite the relevant blocks to:
```toml
app = "ogarniacz"
primary_region = "fra"

# MVP: 1 machine only, no HA peer. Accepts ~5s downtime on deploy.
# To re-enable HA later: `flyctl scale count 2` and switch strategy to "rolling".

[build]

[deploy]
  strategy = "immediate"

[http_service]
  internal_port = 8080
  force_https = true
  auto_stop_machines = false
  auto_start_machines = true
  min_machines_running = 1
  processes = ["app"]

[[vm]]
  size = "shared-cpu-1x"
  memory = "1gb"
  cpu_kind = "shared"
  cpus = 1
```
Key non-defaults vs Fly's auto-generated file:
- `primary_region = "fra"` (not `ams`) — co-locate with Neon `eu-central-1`
- `memory = "1gb"` (not the 512 MB default) — risk register #1
- `auto_stop_machines = false` + `min_machines_running = 1` — keep JVM warm; cold-start budget would otherwise eat the 60s PRD extraction ceiling
- `[deploy] strategy = "immediate"` — single-machine MVP; rolling deploy makes no sense with 1 machine. Accept ~5s downtime per release. Switch to `"rolling"` when `flyctl scale count ≥ 2`.

⚠️ **HA peer caveat:** Despite `min_machines_running = 1`, Fly's `fly launch` and first `fly deploy` may auto-create a second Machine as an HA peer (this is Fly's default since ~2024). If you don't want HA, run `flyctl scale count 1 -a ogarniacz` after first deploy — verification §4.3 catches this.

D.3 Commit:
```bash
git add fly.toml
git commit -m "deploy: fly.toml with fra region, 1GB Machine, min_machines=1"
git push
```

### Phase E — `SecurityFilterChain` exposing `/actuator/health`

**Approval:** `unattended` (code-only change, covered by tests in Phase G smoke).
**Authorized by:** CLAUDE.md "Security is on by default ... Configure a `SecurityFilterChain` (or remove the starter) before treating any endpoint as open."

E.1 Create `src/main/java/com/example/app/config/SecurityConfig.java`:
```java
package com.example.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http
        .csrf(AbstractHttpConfigurer::disable)
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/actuator/health").permitAll()
            .anyRequest().authenticated()
        )
        .httpBasic(httpBasic -> {});
    return http;
  }
}
```
Note: `csrf().disable()` is acceptable here because no authenticated endpoints exist yet; revisit when real REST endpoints land. `httpBasic` keeps the Spring-default admin password path alive for any other endpoint until real auth ships (PRD requires email+password — out of scope for first deploy).

E.2 Extend `AppApplicationTests` with a MockMvc assertion that `/actuator/health` is public. This locks in the `permitAll` contract so a future refactor of `SecurityConfig` cannot silently break the health gate that the entire risk register hangs on.

Replace `src/test/java/com/example/app/AppApplicationTests.java` with:
```java
package com.example.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AppApplicationTests {

	@Autowired
	MockMvc mvc;

	@Test
	void contextLoads() {
	}

	@Test
	void actuatorHealthIsPublic() throws Exception {
		mvc.perform(get("/actuator/health")).andExpect(status().isOk());
	}

}
```
Verify with `./gradlew test`. Expected: `BUILD SUCCESSFUL`, two tests pass. (H2 from Phase B.5 provides the DataSource; the embedded MVC context is wired by `@AutoConfigureMockMvc`.)

E.3 Commit:
```bash
git add src/main/java/com/example/app/config/SecurityConfig.java src/test/java/com/example/app/AppApplicationTests.java
git commit -m "feat(security): permit /actuator/health, require auth elsewhere; test the contract"
git push
```

### Phase F — `fly secrets set` ⚠️

**Approval:** `requires human confirm` before running (secret values land in the agent's transcript; the agent should not log them after the fact).
**Authorized by:** infrastructure.md Getting Started step 5 and Operational Story "Secrets".

F.1 The human reads each secret value into the command below from the records made in Section 2. The agent does NOT echo these values back in subsequent messages.
```bash
fly secrets set \
  SPRING_DATASOURCE_URL='<2.2 pooled URL with prepareThreshold=0>' \
  SPRING_DATASOURCE_USERNAME='<2.2 username>' \
  SPRING_DATASOURCE_PASSWORD='<2.2 password>' \
  AI_PROVIDER_API_KEY='pending-provider-selection' \
  -a ogarniacz
```
Expected: Fly stages the secrets; if a Machine is already running it gets restarted. (This is the first deploy, so no Machine yet — Fly will just store the secrets.)

F.2 Verify the secret names (not values) landed:
```bash
fly secrets list -a ogarniacz
```
Expected: four secrets listed: `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, `AI_PROVIDER_API_KEY`.

**Failure handling:** typo in a name → re-run with the correct name; the old typo'd name stays in the secret store (delete with `fly secrets unset <name> -a ogarniacz`). Wrong DB password → Phase G will fail with `Hibernate FATAL: password authentication failed`; rotate on the Neon side and re-run F.1.

### Phase G — first `fly deploy` ⚠️

**Approval:** `requires human confirm` before running (first production mutation; JPA `spring.jpa.hibernate.ddl-auto=update` will create schema on first connect — schema state becomes effectively immutable from this point under the additive-only-migrations rule in §5).
**Authorized by:** infrastructure.md Getting Started step 5.

G.1 Deploy:
```bash
fly deploy -a ogarniacz --remote-only
```
Expected: build remote on Fly's builders, image pushed, Machine in `fra` starts. `--remote-only` keeps the build off the local laptop (matches what CI will do in Phase H).

G.2 Stream logs in a second terminal until startup completes:
```bash
fly logs -a ogarniacz
```
Expected within ~60s of `Machine started`:
- `Started AppApplication in <N> seconds`
- `Tomcat started on port(s): 8080 (http)`
- No `OutOfMemoryError`
- No `prepared statement S_X already exists` (validates 2.2 worked)
- Hibernate logs schema creation for any `@Entity` classes (there are none yet — that's fine)

G.3 Smoke `/actuator/health`:
```bash
curl -i https://ogarniacz.fly.dev/actuator/health
```
Expected: `HTTP/2 200` with body `{"status":"UP"}`.

Note: with `management.health.db.enabled=false` from Phase B.2, this `UP` does NOT validate that the app actually reached Neon — it only proves the app process is up and `SecurityFilterChain` permits the endpoint. **DB reachability is validated implicitly by Phase G.2 logs**: if Hikari can't open a connection to Neon (wrong password, wrong URL, region unreachable), the app fails to start and "Started AppApplication" never appears in `fly logs`. That's the actual DB-reachability gate.

G.4 **Human confirm gate:** Agent waits until human confirms: `"First deploy green, health UP"`.

**Failure handling:**
- Machine OOM at boot → roll back via §5 and increase memory in `fly.toml` to `2gb`; redeploy.
- `FATAL: password authentication failed` → wrong Neon password in F.1; re-run F.1 and `fly machine restart <id> -a ogarniacz`.
- Health 401 → `SecurityFilterChain` wasn't picked up; verify Phase E commit landed and rebuild.

### Phase H — GitHub Actions auto-deploy-on-merge

**Approval:** `unattended` for file creation; `requires human confirm` for `fly tokens create deploy`.
**Authorized by:** `tech-stack.md` (`ci_default_flow: auto-deploy-on-merge`) + infrastructure.md Operational Story (deploy-scoped token).

H.1 Generate the deploy-scoped token (the human runs this and pastes the result into the GitHub secret UI — agent should not store the token):
```bash
fly tokens create deploy -a ogarniacz
```
The human copies the token, then in GitHub: `Settings → Secrets and variables → Actions → New repository secret`, name = `FLY_API_TOKEN`, value = pasted token.

H.2 Author `.github/workflows/deploy.yml`:
```yaml
name: Deploy to Fly.io

on:
  push:
    branches: [main]

concurrency:
  group: fly-deploy-main
  cancel-in-progress: false

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21

      - run: ./gradlew test

      - uses: superfly/flyctl-actions/setup-flyctl@1.5

      - run: flyctl deploy --remote-only
        env:
          FLY_API_TOKEN: ${{ secrets.FLY_API_TOKEN }}
```
Note: `superfly/flyctl-actions/setup-flyctl@1.5` is the pinned tag (not `@master`) — pin updates land via a deliberate dependency-bump PR, not silent supply-chain drift. The tag has no `v` prefix; using `@v1.5` will fail with "tag not found" because the upstream action only publishes bare numeric tags.

H.3 Commit and push — this triggers the first CI run end-to-end:
```bash
git add .github/workflows/deploy.yml
git commit -m "ci: auto-deploy to Fly on push to main"
git push
```
Expected: GitHub Actions run starts; ~3–5 minutes later `flyctl deploy --remote-only` succeeds; `fly releases list -a ogarniacz` shows a new release one number higher than Phase G's.

**Failure handling:** CI fails on `FLY_API_TOKEN` not found → secret name typo in GitHub UI; fix and re-run the workflow. CI fails on `flyctl deploy` permission → the token wasn't deploy-scoped or was scoped to a different app; regenerate with the correct `-a ogarniacz` flag.

### Phase I — external uptime monitor

**Approval:** `requires human confirm` (the human is the one with the UptimeRobot/Better Stack login).
**Authorized by:** risk register row "Silent iCalendar feed failure masked by client-side caching".

I.1 In UptimeRobot or Better Stack, create an **HTTP keyword monitor** (not a plain HTTP monitor — plain monitors only check status code, and Google Calendar will happily render cached events even when the feed returns a 200 with garbage body):
- URL: the iCalendar feed endpoint (TBD when the feature ships — for now, use `https://ogarniacz.fly.dev/actuator/health` as a placeholder and update when the feed URL exists).
- Interval: **5 minutes**.
- Alert condition: status code != 200 **OR** body keyword `BEGIN:VCALENDAR` missing (once feed exists; for now use `UP` for the health placeholder).
- Alert channels: email to the inbox confirmed in 2.1, plus optionally a phone notification.

I.2 **Human confirm gate:** Agent waits until human confirms: `"Uptime monitor green for 15 minutes"`.

---

## 4. Verification & smoke tests (post-deploy)

Run all of these after Phase H succeeds. They form the "first deploy is real" acceptance bundle.

| # | Check | Command / endpoint | Expected | If it fails |
|---|---|---|---|---|
| 4.1 | App health | `curl -i https://ogarniacz.fly.dev/actuator/health` | `HTTP/2 200` + `{"status":"UP"}` | Rollback per §5 |
| 4.2 | Feed URL (placeholder until feed feature ships) | `curl -i https://ogarniacz.fly.dev/<feed-path>` | 404 expected at this stage (feed not implemented yet) — confirm app is reachable but feature absent | If 5xx, app instability — investigate logs |
| 4.3 | Machine state | `fly status -a ogarniacz` | 1 Machine, region `fra`, state `started` | If `stopped`, `auto_stop_machines` wasn't disabled — re-edit `fly.toml` and redeploy. If **2 Machines** appear: Fly auto-created an HA peer despite `min_machines_running = 1` (default behavior on first launch). For MVP, run `flyctl scale count 1 -a ogarniacz` to remove the peer. |
| 4.4 | No JVM OOM | `fly logs --since 5m -a ogarniacz \| grep -i 'OutOfMemoryError'` | Empty | Memory tuning failed — escalate per §5 |
| 4.5 | No Hibernate pgbouncer collision | `fly logs --since 5m -a ogarniacz \| grep -i 'prepared statement.*already exists'` | Empty | `?prepareThreshold=0&preparedStatementCacheQueries=0` not actually appended in F.1 — re-run F.1 |
| 4.6 | Neon DB reached | Open Neon Console → project `ogarniacz` → Monitoring tab | Non-zero compute hours in the last 30 min | App may be running but not hitting DB — confirm `SPRING_DATASOURCE_URL` is the pooled host |
| 4.7 | Uptime monitor green | UptimeRobot/Better Stack dashboard | "Up" for ≥15 min continuous | Monitor misconfigured — re-do Phase I |
| 4.8 | CI end-to-end | Trigger a no-op commit (`git commit --allow-empty -m "ci: smoke"; git push`) | GitHub Actions run completes green; `fly releases list -a ogarniacz` shows new release | Token scope or workflow YAML — re-do Phase H.1/H.2 |
| 4.9 | Region co-location | Neon project region = `eu-central-1` AND `fly status` region = `fra` | Both EU | One is in the wrong region — destructive to fix; document and accept latency for MVP if both green |

---

## 5. Rollback plan

### 5.1 Application rollback (fast, ~30–60s)
```bash
fly releases list -a ogarniacz
fly releases rollback <previous-version> -a ogarniacz
```
Use when a freshly deployed release has a runtime regression (OOM, 5xx spike, broken endpoint) but the previous release was healthy. Roll back, then investigate on a branch.

### 5.2 DB schema rollback caveat (from infrastructure.md Operational Story)
Spring Boot's JPA auto-DDL (`spring.jpa.hibernate.ddl-auto=update`) does **not** roll back schema changes when application rollback runs. If a release shipped a schema change that the previous release doesn't tolerate, rolling back app code without rolling back DB schema will produce runtime errors (missing column, wrong type).

**Enforcement for MVP:** Migrations are **additive-only** — no column drops, no type narrowing, no constraint additions that would fail on existing rows. This rule is documented inline in `application.properties` (Phase B.2 comment) so the next person who touches the entity layer sees it.

When destructive schema changes become necessary (post-MVP), introduce Flyway or Liquibase with explicit `down` scripts and a backup/restore drill before deploying — the current `ddl-auto=update` posture is for low-volume MVP only.

### 5.3 Full teardown (last resort, irreversible) ⚠️
- `fly apps destroy ogarniacz` — releases the global Fly app name (someone else can claim it).
- Neon Console → project `ogarniacz` → Settings → Delete project — destroys data.
- GitHub: archive or delete the repo manually.
- All three are human-only and require explicit confirmation. Never automate.

---

## 6. Risk register cross-check

Each row from `context/foundation/infrastructure.md` § "Risk Register" mapped to the section that mitigates it.

| Risk register row | Mitigated by | Notes |
|---|---|---|
| JVM OOM on Spring Boot 4 + Spring AI under image-extraction load | Phase B.3 JVM flags (`MaxRAMPercentage=75`, `ExitOnOutOfMemoryError`); Phase D 1 GB Machine; verification 4.4 | OOM-as-fail-fast (not silent). Heap-dump-on-OOM intentionally NOT enabled — Fly Machine `/tmp` is ephemeral and `[mounts]` is out of scope for MVP, so dumps would be lost on restart. Post-mortem stays log-based via `fly logs`. Load test under 50 extractions is **deferred** until extraction endpoint exists. |
| Silent iCalendar feed failure masked by client-side caching | Phase I keyword monitor; verification 4.7 | Keyword check on `BEGIN:VCALENDAR` is the key — plain HTTP monitor would miss the failure mode. Currently watching `/actuator/health`; switch to feed URL when feature ships. |
| JVM cold start consumes PRD's 60s extraction ceiling | Phase D `auto_stop_machines = false`, `min_machines_running = 1`; verification 4.3 | $6.80/mo cost accepted as the cost of warm JVM. |
| Neon free-tier ceiling hit mid-month | Phase 2.2 — Neon free tier sends automatic ~75-80% and 100% emails (no custom threshold configurable); mitigation is "Neon account email = daily-read inbox". Phase B.2 also disables `DataSourceHealthIndicator` so the UptimeRobot 5-min health poll does NOT keep Neon's compute awake — without this knob the free tier would be exhausted in ~6-8 days from monitor traffic alone. | Upgrade path to $19/mo Neon Launch documented in infrastructure.md as the post-MVP step. Not mitigated by this deploy — **monitor-only**. When the iCalendar feed ships and replaces `/actuator/health` as the canary, feed queries will touch DB on every poll — that's the moment to either lengthen monitor interval to ≥10 min or upgrade Neon plan. |
| Fly trial expires before billing card added; unbounded bill | Phase 2.1 card on file + verified Fly email (daily-read inbox) + bank-side per-transaction alert + `fly.toml` resource ceiling as de facto cap (1 GB Machine × 1 = ~$7/mc max) + weekly manual dashboard review | **Partially closed.** Fly does NOT offer in-platform hard caps or billing alerts (verified against fly.io/docs/about/cost-management 2026-05-21). The composite mitigation is what's available given the platform — no automated mechanism prevents charges exceeding a threshold, only resource ceilings + bank alerts + vigilance. |
| `fly mcp server` experimental status | This plan uses `flyctl` via bash only — no MCP dependency | Fully closed. |
| Boot 4 + Fly examples mismatch (most blog posts are Boot 3.x) | Phase C.1 JAR filename verification; Phase E uses Boot 4 SecurityFilterChain idiom (not WebSecurityConfigurerAdapter) | Closed by explicit verification at each potential mismatch point. |
| DB schema rollback divergence after Spring Boot auto-migration | §5.2 additive-only rule documented in `application.properties`; verification 4.5 detects active divergence | Closed for MVP; revisit when destructive changes are needed. |
| GDPR — Neon defaults to US East at signup | Phase 2.2 explicit region pick + verification 4.9 | Closed. |
| Auto-deploy lands in iCalendar poll window (503 during `immediate` strategy) | **Deferred** — accepted for single-user MVP per infrastructure.md "Auto-deploy lands in iCalendar poll window" row. `fly.toml` now sets `[deploy] strategy = "immediate"` explicitly to acknowledge the trade-off. | Switch to `strategy = "rolling"` when `flyctl scale count ≥ 2` (post-MVP). **Monitor-only.** |
| Fly auto-creates HA peer on first launch despite `min_machines_running = 1` | Verification §4.3 detects 2-Machine state; remediation = `flyctl scale count 1 -a ogarniacz`. `fly.toml` D.2 caveat documents the behavior so future re-deploys / `fly launch` runs don't silently regenerate the peer. | Fly's default since ~2024. Closed by detection + documented remediation. |

**Explicit gaps (not mitigated by this plan):**
- Neon free-tier ceiling — alert-only; no automated upgrade path.
- Deploy-into-poll-window 503 — accepted by infrastructure.md as low-impact for single-user MVP.
- Load test under 50 extractions — deferred until extraction endpoint exists.
- **Fly billing has no platform-side hard cap or alert** — best-available controls are `fly.toml` resource ceiling + bank-side transaction alert + manual weekly dashboard review (see §2.1). A burst of unwanted traffic, an accidental `fly scale memory 8gb`, or a Fly pricing change can still produce a bill higher than expected; only the bank alert catches the actual charge, after the fact. **infrastructure.md and the original version of this plan assumed cap+alert features that Fly does not actually offer** — flagged here so it doesn't drift back into assumptions when the plan is revisited.

---

## 7. Irreversible operations — explicit callout

Each `⚠️ IRREVERSIBLE` below cannot be undone with a single rollback command. The human must eyeball each one on plan review.

1. ⚠️ **IRREVERSIBLE — Fly app name reservation** (Phase D.1). `fly apps create ogarniacz` (or `fly launch --name ogarniacz`) reserves the name in Fly's global namespace. If the name is wrong, `fly apps destroy` releases it — but if someone else grabs it in between, you can't get it back.
2. ⚠️ **IRREVERSIBLE — Neon project region** (Phase 2.2). Region is set at project creation and cannot be changed. Wrong region = delete + recreate the project (data loss).
3. ⚠️ **IRREVERSIBLE — First production `fly deploy` schema creation** (Phase G.1). `spring.jpa.hibernate.ddl-auto=update` will create whatever schema the entities require on the production Neon DB on first connect from the deployed Machine. Phase C validation runs against an **ephemeral local Postgres** (thrown away by `docker stop` in C.5), so it does not constrain production schema — Phase G.1 is the true point of no return. Going forward from G.1, only additive migrations are safe (§5.2).
4. ⚠️ **IRREVERSIBLE — `fly secrets set`** (Phase F.1). Secret values are not retrievable after `set` (Fly stores hashed/encrypted). Rotation requires updating both the secret and the source-of-truth (Neon password, AI provider portal).
5. ⚠️ **IRREVERSIBLE — `fly tokens create deploy`** (Phase H.1). The token is a production credential from the moment it's generated. If it leaks (pasted into the wrong channel, screenshotted, committed to a public repo), `fly tokens revoke <id>` is the only remediation — and the window between leak and revoke is exploitable.
6. ⚠️ **IRREVERSIBLE — Fly billing card on file** (Phase 2.1). Adding a card is reversible (remove it), but the $20/mo cap and email-alert wiring are the only safety net against runaway cost. If the cap or alert is misconfigured, the next bill is whatever it is.

---

## 8. Out of scope (do NOT do in this deploy)

Echoed from `infrastructure.md` § "Out of Scope" so the plan does not scope-creep during execution:

- **PR preview environments.** Skip per infrastructure.md Operational Story — MVP at this scale doesn't justify the workflow complexity.
- **Multi-region failover, HA Postgres, read replicas, DR runbooks, SLA commitments.** PRD scopes MVP to single user / low QPS.
- **Object storage** (Cloudflare R2, Backblaze B2). PRD says source images are purged after every proposed event is accepted/rejected — local Machine ephemeral disk is sufficient until retention policy changes.
- **SMTP / email service** for future password-reset flow. PRD § Access Control puts it out of MVP scope.
- **Docker image hardening, multi-stage build optimization, layer caching beyond `eclipse-temurin:21-jre` defaults.**
- **CI/CD pipeline structure beyond the single `auto-deploy-on-merge` step** (test job ordering, branch protection, required checks). Defer to `/10x-implement` and milestone planning.
- **Spring AI starter and provider integration.** Decided in Phase 2.3 — secret name reserved, real wiring deferred to a follow-up change.
- **Real auth (email + password) per PRD.** Phase E only exposes `/actuator/health`; everything else stays guarded by Spring Security's auto-generated admin password until real auth ships.
- **Flyway / Liquibase migrations.** First deploy relies on `spring.jpa.hibernate.ddl-auto=update`. Adopt a real migration tool when destructive changes become necessary post-MVP.
- **Load test under 50 extractions.** Deferred until extraction endpoint exists.

---

## Output artifact

On plan approval (after Plan Mode exits), the approved version of this file should be copied to `context/changes/deploy-plan.md` per `CLAUDE.md` Module 1 Lesson 5 contract — that path is the long-term audit trail for "what was supposed to happen" when production goes sideways months later.

## Verification of the plan itself (before execution)

Before kicking off Phase A, the human should verify:
- [ ] Section 2 gate phrases are exact strings the human will use literally.
- [ ] Section 3 phase order is intact (B before C, C before D, E before G).
- [ ] Every `⚠️ IRREVERSIBLE` callout in §7 is acknowledged.
- [ ] The `researched_at: 2026-05-21` snapshot in infrastructure.md is still current — if more than ~30 days old at execution time, re-run `/10x-infra-research` first.
- [ ] The two contradictions with infrastructure.md are accepted intentionally: (a) pooled URL **with `prepareThreshold=0`** instead of pooled-as-is; (b) `ddl-auto=update` for MVP (infrastructure.md leaves migration tooling unspecified — this plan picks the lightest option and constrains it with the additive-only rule).
