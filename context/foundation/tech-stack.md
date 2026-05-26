---
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
  database: postgresql
---

## Why this stack

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

## Database

PostgreSQL is the relational engine for both development and production.
The PRD mandates server-side persistence for users, parsed events, and
iCalendar feeds, and Spring Data JPA on the application side leaves the
choice between Postgres, MySQL, and embedded engines like H2 or SQLite.
Postgres wins on three axes that matter for this MVP: it is the most
common production pairing with Spring Boot (largest pool of agent-
readable docs, examples, and Stack Overflow answers, which keeps an AI
assistant unblocked during the 3-week build), it ships a first-class
Docker image that drops into the self-host target with one
`docker compose` service and no driver gymnastics, and it handles the
data shapes the PRD implies (timestamped events, JSON columns for raw
image-extraction payloads if needed, full-text search on announcement
text later) without a schema rewrite. H2 is retained only as the
in-memory engine for the Spring test slice; SQLite is rejected because
JPA support is community-grade and concurrent writes from the
image-extraction path would force a migration anyway. Connection details
land in `application.yml` via env vars (`SPRING_DATASOURCE_URL`,
`_USERNAME`, `_PASSWORD`) so the same artifact runs against a local
container in dev and a managed/self-hosted Postgres in prod without code
changes.
