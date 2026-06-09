# Minimal Auth + Empty Personal View — Plan Brief

> Full plan: `context/changes/minimal-auth-and-empty-personal-view/plan.md`

## What & Why

Ship roadmap slice **S-01**: a parent can sign up with email + password, log in, log out, stay logged in across browser sessions, and land on an empty per-account personal view. The slice is required because PRD FR-001 mandates email+password auth as a non-negotiable MVP capability, and because every subsequent slice (S-02 manual entry, S-04 iCalendar feed, S-05 image extraction) inherits the per-user data partition contract introduced here. If partition silently breaks in S-01, the "zero cross-account leakage" NFR fails the moment a second parent is added.

## Starting Point

Spring Boot 4.0.6 / Java 21 / Gradle scaffold with security + data-jpa + validation + webmvc starters on the classpath; Neon Postgres wired in prod, H2 in tests. `SecurityConfig.java` currently disables CSRF and forces HTTP Basic against Spring's auto-generated admin password — a placeholder that must be replaced. No `User` entity, no signup/login/logout flow, no template engine, no controllers beyond `/actuator/health` exposure. `ddl-auto=update` under an additive-only migration contract (deploy-plan §5).

## Desired End State

Anonymous visitors to `/` are redirected to a custom Thymeleaf `/login` page. A new user signs up at `/signup` (12-char minimum password, BCrypt-hashed), is auto-logged-in on success, and lands on `/app` showing a top bar with their email + a CSRF-protected logout button, plus a centered empty-state message ("No events yet. Manual entry coming in the next slice."). Every successful login and signup auto-login issues a 30-day persistent token cookie (always-on, no opt-in checkbox) backed by a `persistent_logins` row in Postgres; closing the browser and returning still finds the user logged in. A two-user negative integration test enforces that user A's `/app` never shows user B's email.

## Key Decisions Made

| Decision | Choice | Why | Source |
| --- | --- | --- | --- |
| UI rendering | Thymeleaf server-rendered | Zero new ecosystem, integrates with Spring Security form-login + CSRF out of the box; smallest blast radius for a solo 3-week MVP. HTMX/SSE can be layered on later for S-05's streamed-progress UX. | Plan |
| Session persistence | Servlet `HttpSession` + DB-backed remember-me (`JdbcTokenRepositoryImpl`) | PRD requires "stay logged in across browser sessions"; remember-me is the canonical Spring fit and survives Fly auto-stop because tokens live in Neon. Spring Session JDBC is overkill at single-user / low-QPS. | Plan |
| Security posture | CSRF on, Secure + HttpOnly + SameSite=Lax cookies, HTTPS via Fly edge | Re-enables Spring Security defaults that the placeholder config disabled; aligns with PRD §Access Control. Local dev uses `SERVER_SERVLET_SESSION_COOKIE_SECURE=false` env override. | Plan |
| Signup UX + password rules | Auto-login on signup; 12-char minimum, BCrypt cost 10 | Minimal MVP scope literally — one-step onboarding for the sole user; 12-char length is modern OWASP/NIST guidance (no composition rules — they push users toward predictable patterns). | Plan |
| Validation error UX | Inline field errors on signup, generic "Invalid email or password" on login | Signup wants actionable errors per PRD's "clear, actionable" guardrail; login resists user enumeration by hiding which field was wrong. | Plan |
| Partition test shape | Two-user negative integration test now, querying-time scoping later | Roadmap explicitly demands this slice include a negative test; the empty view has the user's email in the header, giving the assertion a real target. | Plan (roadmap risk) |
| Empty view + routes | `/`, `/signup`, `/login`, `/logout`, `/app` with header (email + logout) + empty-state hint | Header gives the partition test something to assert against; layout fragment is reusable in every later slice; copy primes S-02. | Plan |
| Schema | Java `AppUser` → table `app_user`; `PersistentLogin` → `persistent_logins` | Avoids Postgres reserved keyword `user` and the import collision with Spring Security's `org.springframework.security.core.userdetails.User`. JPA owns schema creation via `ddl-auto=update`; Spring Security uses JDBC at runtime. | Plan |

## Scope

**In scope:**
- `AppUser` entity, repository, `UserDetailsService`, BCrypt `PasswordEncoder`
- `PersistentLogin` entity (JPA owns schema; Spring Security uses JDBC at runtime)
- `SecurityConfig` rewrite: form login, DAO auth, persistent remember-me, CSRF on, secure cookies
- Thymeleaf templates: layout fragment, `login.html`, `signup.html`, `app.html`
- `SignupController` (with auto-login state sequencing), `AppController` (`/` redirect + `/app` render)
- Inline validation errors on signup, generic message on login
- Integration tests: signup happy path, signup validation failures, login happy + failure, logout, `/` redirect, `/app` shows email, two-user partition, remember-me end-to-end round-trip

**Out of scope:**
- Password reset / MFA / email verification / account deletion (PRD §Access Control "Out of MVP")
- Remember-me token rotation/revocation endpoint
- `Event` entity and any UI showing events (S-02)
- iCalendar feed and token issuance (S-04)
- Spring Session JDBC, HTMX, SPA / JavaScript beyond static templates
- Admin role / role hierarchy / `@PreAuthorize`
- Composition password rules (digit/special)

## Architecture / Approach

```
Browser ──(form POST + CSRF)──▶ SignupController / Spring Security UsernamePasswordAuthenticationFilter
                                          │
                                          ▼
                              AppUserDetailsService ─▶ AppUserRepository ─▶ Postgres/H2 (app_user)
                                          │
                                          ▼
                              SecurityContextHolder + HttpSession (in-process)
                                          │
                                          ▼ (if "remember me" checked)
                              RememberMeServices ─▶ JdbcTokenRepositoryImpl ─▶ Postgres/H2 (persistent_logins)

GET /app ─▶ AppController ─▶ Model { userEmail = auth.getName() } ─▶ Thymeleaf app.html
```

Hibernate `ddl-auto=update` creates `app_user` and `persistent_logins` from `@Entity` definitions on first deploy (additive-only). Spring Security's `JdbcTokenRepositoryImpl` reads/writes `persistent_logins` directly via JDBC at runtime — no `JpaRepository` involved. Auto-login on signup explicitly populates `SecurityContextHolder` + invokes `RememberMeServices.loginSuccess` before returning the `redirect:/app` response.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Auth backbone | Entity, repo, `UserDetailsService`, encoder, `PersistentLogin` schema; `SecurityConfig` rewrite with form-login defaults + persistent remember-me + CSRF on. Anonymous → 302 to Spring Security's default login form; a seeded user can authenticate. | `persistent_logins` schema must match Spring Security's expected column shapes exactly — otherwise remember-me writes fail at runtime with cryptic JDBC errors. |
| 2. User flows + UI | Thymeleaf templates (layout + login + signup + app), `SignupController` with auto-login state sequencing, `AppController` with `/` redirect, swap `SecurityConfig` to `.loginPage("/login")`, inline validation errors. End: new user can sign up in browser and land on `/app`. | Auto-login state sequencing is non-obvious — getting it wrong silently bounces signups back to `/login`. Plan calls this out in Critical Implementation Details. |
| 3. Partition contract + remember-me verification | Two-user negative integration test, persistent-cookie end-to-end round-trip test, manual run-through in two browser profiles. | If the partition test asserts only on rendered email (not on data scope), regressions in S-02+ where actual data is leaked could pass. Pattern (assert presence + absence) extends to richer assertions in later slices. |

**Prerequisites:** none (S-01 is roadmap-ready). Local Postgres or live Neon DB needed for `bootRun`-based manual verification; H2 covers all automated tests.

**Estimated effort:** ~2–3 evening sessions across 3 phases for a solo dev familiar with Spring Security; the bulk is Phase 2's controller + template + auto-login plumbing.

## Open Risks & Assumptions

- **Spring Security version pinned at 7.0.5** (verified via `./gradlew dependencies --configuration runtimeClasspath`). `JdbcTokenRepositoryImpl`, `PersistentTokenBasedRememberMeServices`, `RememberMeConfigurer` DSL methods (`tokenRepository / userDetailsService / tokenValiditySeconds / alwaysRemember`), and `DefaultLoginPageGeneratingFilter` all present in `spring-security-web-7.0.5.jar` with identical signatures to 6.x. Plan compiles on 7.x without architectural change.
- **No `thymeleaf-extras-springsecurity6` dialect needed** — `app.html` and the layout header use `model.addAttribute("userEmail", ...)` populated by the controller, not `sec:authentication`. The dialect was last released for Spring Security 6.x and may fail to bind on 7.x; plan deliberately avoids it (`plan.md` §Base layout fragment).
- **`SERVER_SERVLET_SESSION_COOKIE_SECURE=false` in local dev** is the documented workaround for HTTP-only `localhost` testing; if the dev wants HTTPS locally, the override is unnecessary. Plan documents this in Migration Notes.
- **CSRF auto-injection requires `th:action`**: Thymeleaf's `CsrfRequestDataValueProcessor` only injects the CSRF hidden input on `th:action="@{...}"` — a raw `action="/signup"` skips injection and POST returns 403. Each template's contract spells this out.
- **Race between `existsByEmail` and `save`** is caught at the controller level: `SignupController` wraps `save` in `try/catch (DataIntegrityViolationException)` and re-renders the form with the duplicate-email field error. The Postgres unique constraint on `email` is the authoritative invariant; the `existsByEmail` check is the UX optimization.
- **Email case-insensitivity** is enforced at three layers (defense in depth): controller normalizes before DB lookup, `AppUserDetailsService.loadUserByUsername` normalizes before lookup, and `AppUser` constructor defensively lowercases via `Locale.ROOT.trim()`. Same person cannot create separate `alice@example.com` and `Alice@Example.COM` accounts.

## Success Criteria (Summary)

- A new user can sign up (email + 12-char password) in a browser, land on `/app` showing their email and the empty-state hint, log out, log back in, and tick "Remember me" to stay logged in across browser restarts.
- Two users registered against the same instance never see each other's email on `/app` — enforced by an automated integration test.
- All existing tests (`contextLoads`, `actuatorHealthIsPublic`) remain green; full `./gradlew test` passes including the 17 new automated tests across phases 1–3.
