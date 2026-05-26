---
bootstrapped_at: 2026-05-20T19:55:00Z
starter_id: spring
starter_name: Spring Boot
project_name: ogarniacz
language_family: java
package_manager: gradle
cwd_strategy: native-cwd
bootstrapper_confidence: verified
phase_3_status: ok
audit_command: null
---

## Hand-off

Source: `context/foundation/tech-stack.md`.

```yaml
starter_id: spring
package_manager: gradle
project_name: ogarniacz
hints:
  language_family: java
  team_size: solo
  deployment_target: self-host
  ci_provider: github-actions
  ci_default_flow: auto-deploy-on-merge
  bootstrapper_confidence: verified
  path_taken: standard
  quality_override: false
  self_check_answers: null
  has_auth: true
  has_payments: false
  has_realtime: false
  has_ai: true
  has_background_jobs: false
  database: postgresql      # extra key beyond schema; surfaced but not acted on
```

### Why this stack (verbatim)

Solo parent shipping a 3-week after-hours MVP that turns kindergarten
announcement images into iCalendar events. The PRD mandates server-side
database persistence, email+password authentication, and an external
image-to-event service — Spring Boot is the vetted default for
(web-app, java), bootstrapper confidence verified, and bundles DI +
Spring Web + Spring Security + Spring Data so auth, REST endpoints, and
relational persistence land without yak-shaving. Package manager
overridden from the card's Maven default to Gradle per user preference;
Spring Initializr supports both, so this is a per-project swap that the
bootstrapper handles by invoking `type=gradle-project`. Deployment is
self-host given small scale (single user, low QPS), with GitHub Actions
running auto-deploy-on-merge as the CI shape. The AI feature flag
captures the image-to-event extraction step (provider chosen post-
scaffold); payments, realtime, and background jobs are out of scope per
PRD non-goals — iCalendar sync is poll-on-demand, not push.

> Schema-divergence note: the hand-off body contains a second `## Database`
> heading beyond the canonical `## Why this stack` section. Bootstrapper
> records the divergence but does not act on it (body is human-facing).

## Pre-scaffold verification

| Signal      | Value                                                                     | Severity | Notes                                                                                       |
| ----------- | ------------------------------------------------------------------------- | -------- | ------------------------------------------------------------------------------------------- |
| npm package | not run                                                                   | n/a      | language_family is java, not js — no `create-*` CLI to query                                |
| GitHub repo | not run                                                                   | n/a      | card.docs_url is `https://docs.spring.io/spring-boot/`, not a `github.com/<owner>/<repo>` URL |

## Scaffold log

**Resolved invocation**: `curl -s https://start.spring.io/starter.tgz -d dependencies=web,security,data-jpa,postgresql,devtools,validation -d type=gradle-project -d javaVersion=21 -d groupId=com.example -d artifactId=app | tar -xzf -`

**Strategy**: native-cwd

**Wrapper applied**: `bash -c 'set -o pipefail; …'` so a silent curl failure cannot mask as a successful tar invocation.

**Exit code**: 0

**Pre-flight files-to-touch (estimated, pre-run)**: `.gitignore`, `HELP.md`, `build.gradle`, `settings.gradle`, `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`, `src/main/java/com/example/app/AppApplication.java`, `src/main/resources/application.properties`, `src/test/java/com/example/app/AppApplicationTests.java`.

**Files actually written by Initializr (post-run)**:

```
.gitattributes
.gitignore
HELP.md
build.gradle
gradle/wrapper/gradle-wrapper.jar
gradle/wrapper/gradle-wrapper.properties
gradlew
gradlew.bat
settings.gradle
src/main/java/com/example/app/AppApplication.java
src/main/resources/application.properties
src/test/java/com/example/app/AppApplicationTests.java
```

(`.gitattributes` was not in the pre-flight estimate but is shipped by current Initializr templates; no collision.)

**Pre-existing files / directories preserved**: `.DS_Store`, `.claude/`, `.idea/`, `CLAUDE.md`, `context/`. None overwritten — Initializr's output shape does not overlap with the cwd's pre-scaffold contents.

**Collisions (`.scaffold` siblings created)**: none.

**`.gitignore` handling**: moved silently — no pre-existing `.gitignore` in cwd before the scaffold.

**`.bootstrap-scaffold` cleanup**: n/a (native-cwd strategy does not create one).

## Post-scaffold audit

**Tool**: skipped — no built-in audit tool for java (`audit_commands[java]` is `null` in `bootstrapper-config.yaml`).

**Recommended external tool**: the OWASP Dependency-Check Gradle plugin (`org.owasp.dependencycheck`) — add as a Gradle plugin in `build.gradle` and run `./gradlew dependencyCheckAnalyze`. Alternatively, Snyk CLI (`snyk test --all-projects`) covers Gradle dependencies and integrates with GitHub Actions if you want CI-side scanning.

## Hints recorded but not acted on

| Hint                       | Value                                |
| -------------------------- | ------------------------------------ |
| bootstrapper_confidence    | verified                             |
| quality_override           | false                                |
| path_taken                 | standard                             |
| self_check_answers         | null                                 |
| team_size                  | solo                                 |
| deployment_target          | self-host                            |
| ci_provider                | github-actions                       |
| ci_default_flow            | auto-deploy-on-merge                 |
| has_auth                   | true                                 |
| has_payments               | false                                |
| has_realtime               | false                                |
| has_ai                     | true                                 |
| has_background_jobs        | false                                |
| database (extra-schema)    | postgresql                           |

## Next steps

Next: a future skill will set up agent context (CLAUDE.md, AGENTS.md). For now, your project is scaffolded and verified — happy hacking.

Useful manual steps in the meantime:

- `git init` (if you have not already) to start your own repo history. Spring Initializr does not initialize one for you.
- Run `./gradlew bootRun` to confirm the scaffold compiles and boots on port 8080 (it will fail to start without a Postgres connection — that's expected for the next step).
- Provision a local PostgreSQL via `docker compose` (per the hand-off's `## Database` section), set `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` in `application.properties` (or `.env`), and re-run `./gradlew bootRun`.
- Configure Spring Security minimum auth surface (signup / login / logout / session persistence per the PRD's FR-001) — Initializr ships the dependency but not the configuration.
- Add the GitHub Actions workflow (`.github/workflows/ci.yml`) for `auto-deploy-on-merge` once the self-host deployment target is wired (Dockerfile + remote host config). Bootstrapper does not generate CI files in v1.
- Address audit findings per your project's risk tolerance — install the OWASP Dependency-Check plugin in `build.gradle` to get an actual report (v1 bootstrapper has no Java audit command wired).
