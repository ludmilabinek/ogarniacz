# Minimal Auth + Empty Personal View — Implementation Plan

## Overview

Ship roadmap slice **S-01**: a parent can sign up with email + password, log in, log out, stay logged in across browser sessions, and land on an empty per-account personal view at `/app`. Replace the placeholder HTTP-Basic `SecurityConfig` with form login + DB-backed remember-me, introduce the `AppUser` entity and the per-user data partition contract every later slice inherits, and lock that contract with a two-user negative integration test.

## Current State Analysis

- **Spring Boot 4.0.6 + Java 21 + Gradle** scaffold (see `build.gradle`). Web (`spring-boot-starter-webmvc`), security, data-jpa, validation, actuator, devtools starters present. PostgreSQL driver as runtime, H2 as test runtime.
- **Auth is placeholder only**: `src/main/java/com/example/app/config/SecurityConfig.java:9` defines a `SecurityFilterChain` that disables CSRF, permits `/actuator/health`, and guards everything else with HTTP Basic against Spring's auto-generated admin password. No `User` entity, no signup/login/logout flow, no password storage, no session persistence beyond default `HttpSession`.
- **Frontend is absent**: `src/main/resources/static/` and `src/main/resources/templates/` exist but are empty. No template engine on the classpath yet.
- **Persistence is wired but unused**: `spring.jpa.hibernate.ddl-auto=update` (`application.properties:14`) under an **additive-only migration** contract (deploy-plan §5 — no column drops, no type narrowing). Neon Postgres in prod via `SPRING_DATASOURCE_*` env vars; H2 in test, already in `testRuntimeOnly` per a prior bootstrap lesson.
- **Tests already pass**: `AppApplicationTests` exercises `contextLoads` and `actuatorHealthIsPublic` (`src/test/java/com/example/app/AppApplicationTests.java:20-26`) — both must remain green.
- **CSRF is currently off**: acceptable for HTTP Basic over JSON; needs to be re-enabled the moment browser forms appear.
- **Deploy posture**: Fly.io edge handles HTTPS termination; `auto_stop_machines=stop` means cold starts after idle (deploy-plan Phase I). Sessions held only in process memory would be wiped on every cold start — hence the persistent remember-me decision.

## Desired End State

- An anonymous visitor to `/` is redirected to `/login` (a custom Thymeleaf page). Submitting a sign-up form at `/signup` with a valid email and a 12+ character password creates an `AppUser` row (BCrypt-hashed), authenticates the user in the same request, and redirects to `/app`.
- `/app` renders a top bar with the logged-in user's email and a CSRF-protected "Log out" POST form, plus a centered empty-state message "No events yet. Manual entry coming in the next slice."
- Ticking "Remember me" on the login form sets a persistent token cookie backed by a `persistent_logins` row in Postgres. After the servlet session ends (Machine restart, browser closed, session expiry), presenting the cookie alone re-authenticates the user.
- Logging out invalidates the servlet session, deletes the remember-me cookie + row, and redirects to `/login?logout`.
- Two users (A and B) registered against the same instance see only their own state on `/app`: A's email appears in A's response, B's email never does. A `@SpringBootTest` enforces this invariant.

### Key Discoveries:

- **Boot 4 naming gotcha**: web starter is `spring-boot-starter-webmvc`; template engine starter is `spring-boot-starter-thymeleaf` (no version pin — managed by Boot BOM). Boot 3/2 snippets copied from the web use `spring-boot-starter-web` (`build.gradle:23`).
- **`user` is reserved in Postgres**: entity is named `AppUser` with `@Table(name = "app_user")` to avoid keyword quoting and the import collision with `org.springframework.security.core.userdetails.User`.
- **Remember-me table created via JPA, not Spring Security's bootstrap**: model `PersistentLogin` as a `@Entity` with the canonical Spring-Security schema (`series` PK, `username`, `token`, `last_used`). Hibernate's `ddl-auto=update` creates it once on first deploy; subsequent deploys are no-ops. Do not use `JdbcTokenRepositoryImpl.setCreateTableOnStartup(true)` — it issues a `CREATE TABLE` on every boot and conflicts with the additive-only migration contract.
- **Auto-login on signup needs explicit `SecurityContextHolder` population + `RememberMeServices.loginSuccess` invocation** inside the signup controller — otherwise the redirect to `/app` is followed by an anonymous request and bounces back to `/login`.
- **Logout must be a POST with CSRF token** (Spring Security default). A `<a href="/logout">` link will 404 or fail CSRF — use a `<form method="post" th:action="@{/logout}">` in the layout fragment.
- **Local dev cookies**: `server.servlet.session.cookie.secure=true` is the right default for prod (Fly edge HTTPS), but Tomcat won't send a Secure cookie over `http://localhost`. Document the dev workaround (`SERVER_SERVLET_SESSION_COOKIE_SECURE=false` env override) rather than profile files — keeps `application.properties` single-source.
- **`@SpringBootTest` against H2 works** because H2 is on `testRuntimeOnly` (`build.gradle:33`) — entities + JPA queries don't need PG-specific tooling for the slice's tests.

## What We're NOT Doing

- **No password reset / MFA / email verification / account deletion** — PRD §Access Control "Out of MVP".
- **No remember-me token rotation / revocation endpoint** — PRD names this as a non-blocking follow-up.
- **No event entity, no events UI** — that's S-02. `/app` is an empty-state placeholder in this slice.
- **No iCalendar feed / token issuance** — that's S-04. `AppUser` will gain an iCalendar token column later; not in this slice.
- **No Spring Session JDBC** — servlet `HttpSession` + DB-backed remember-me is sufficient for the PRD's "stay logged in" wording.
- **No password composition rules (digit, special char)** — modern guidance (NIST SP 800-63B) recommends length-only; 12-char minimum is the only rule.
- **No HTMX, no SPA, no JavaScript** beyond what Thymeleaf needs (which is none for this slice). HTMX can be layered onto Thymeleaf in S-05 when the streamed-progress UX is needed.
- **No admin role / role hierarchy** — flat role model per PRD; every authenticated user is implicitly a parent. Skip `@GrantedAuthority` configuration entirely and rely on `authenticated()` matchers.
- **No `@PreAuthorize` annotations** — partition is enforced at query time (every read scoped by `userId` in subsequent slices); method-level security is not needed in S-01 where the only authenticated endpoint is `/app` rendering the principal's own email.

## Implementation Approach

Three phases, each ending at a manually verifiable checkpoint:

1. **Auth backbone** — entity, repo, `UserDetailsService`, password encoder, persistent remember-me, rewritten `SecurityConfig` with form login defaults. End state: `./gradlew bootRun` boots; anonymous `/app` redirects to Spring Security's auto-rendered default login form; a manually-seeded user can log in.
2. **User flows + UI** — Thymeleaf templates (layout + login + signup + app), `SignupController` with auto-login, `AppController` for `/` redirect + `/app` empty view, swap `SecurityConfig` to `.loginPage("/login")`, inline validation errors. End state: a new user can sign up in the browser and end up on `/app` showing their email.
3. **Partition contract + remember-me verification** — two-user negative integration test, remember-me end-to-end test, full manual run-through.

## Critical Implementation Details

### State sequencing — auto-login on signup

The signup `POST` handler must, in order: (1) hash the password and persist the new `AppUser`; (2) build an `UsernamePasswordAuthenticationToken` against the persisted user via `AuthenticationManager.authenticate(...)` or by constructing the token from the loaded `UserDetails`; (3) call `SecurityContextHolder.getContext().setAuthentication(token)`; (4) if the form's "Remember me" was checked, invoke `RememberMeServices.loginSuccess(request, response, token)` so the persistent cookie is issued in the same response; (5) return a `redirect:/app` response. Reversing steps 3 and 5 (redirect before context) breaks auto-login — the next request is anonymous and bounces to `/login`.

### Timing & lifecycle — `persistent_logins` table creation

Hibernate's `ddl-auto=update` discovers the `PersistentLogin` `@Entity` on first deploy and emits `CREATE TABLE persistent_logins (...)` matching the columns Spring Security's `JdbcTokenRepositoryImpl` expects. Hibernate is **only** used for schema management; Spring Security accesses the table via JDBC at runtime through the same `DataSource`. Do not set `JdbcTokenRepositoryImpl.setCreateTableOnStartup(true)` — it issues unconditional `CREATE TABLE` and violates the additive-only contract on re-deploy.

### User experience spec — logout form

The "Log out" control in the layout fragment is a `<form th:action="@{/logout}" method="post">` with Thymeleaf's auto-injected CSRF hidden input, not a hyperlink. Spring Security's default `LogoutFilter` requires POST + CSRF; a `GET /logout` will 404 (when CSRF is enabled and Spring Security defaults are in force). The form button can be styled as a link but must POST.

**Auto-CSRF preconditions**: Thymeleaf injects the CSRF hidden input automatically **only** when the form uses `th:action="@{/...}"` (Spring's `CsrfRequestDataValueProcessor` hooks into `th:action`, not raw `action="..."`). Every form in `login.html`, `signup.html`, and `layout.html` must use `th:action`; a raw `action="/signup"` produces a 403 on POST. State this constraint inline in each template's contract.

### Email normalization — lowercase at boundary

Emails are stored and queried as **lowercase ASCII** to prevent the same person registering twice as `alice@example.com` and `Alice@Example.COM`. Normalization happens at two chokepoints and the entity is defensive:

1. `SignupController.signup(POST)` — before `existsByEmail` and `save`, call `form.setEmail(form.getEmail().toLowerCase(Locale.ROOT).trim())`. Do this **after** `@Valid` (so `@Email` runs against the user-supplied value first, giving back the original casing in error messages) but **before** any DB lookup.
2. `AppUserDetailsService.loadUserByUsername(String email)` — first line: `email = email.toLowerCase(Locale.ROOT).trim();` before `appUserRepository.findByEmail(email)`. This catches the login path uniformly — works for form login, remember-me re-auth, and any future programmatic auth.
3. `AppUser` constructor / static factory — defensive: `this.email = Objects.requireNonNull(email).toLowerCase(Locale.ROOT).trim();`. Even if a future caller skips controller-level normalization, the entity won't persist a non-normalized email.

The Postgres unique constraint on `email` then guarantees the invariant at the DB level. No `LOWER(email)` functional index needed — every value already arrives lowercase.

Use `Locale.ROOT` (not the default JVM locale) to avoid the Turkish-locale `İ → i̇` foot-gun. ASCII emails per RFC 5321 don't need Unicode normalization for MVP.

---

## Phase 1: Auth backbone (data layer + security config)

### Overview

Lay the foundation: `AppUser` entity, repository, `UserDetailsService`, password encoder, `PersistentLogin` entity for the remember-me table, and a rewritten `SecurityConfig` that uses form login defaults (Spring Security's built-in `/login` GET page) + DAO auth + persistent remember-me + CSRF on. No custom templates yet — Phase 1 ends with auth provably wired against Spring Security's default-rendered form.

### Changes Required:

#### 1. Add Thymeleaf to the build

**File**: `build.gradle`

**Intent**: Put Thymeleaf on the runtime classpath so Phase 2's controllers can render server-side templates. Adding in Phase 1 avoids a dependency change mid-phase later.

**Contract**: a new `implementation 'org.springframework.boot:spring-boot-starter-thymeleaf'` line in the `dependencies` block. Version managed by the Boot BOM — no version pin.

#### 2. `AppUser` entity

**File**: `src/main/java/com/example/app/user/AppUser.java` (new)

**Intent**: Domain user with stable identity, unique email as the login key, BCrypt-hashed password, and a creation timestamp for audit. Named `AppUser` to dodge Postgres reserved `user` and the import collision with `org.springframework.security.core.userdetails.User`.

**Contract**: `@Entity @Table(name = "app_user")`. Fields:
- `id`: `UUID`, primary key, `@GeneratedValue` (any strategy that yields a UUID on insert)
- `email`: `String`, `@Column(unique = true, nullable = false, length = 254)` — RFC 5321 max. **Stored lowercase** — see Critical Implementation Details §Email normalization.
- `passwordHash`: `String`, `@Column(nullable = false, length = 60)` — BCrypt output length
- `createdAt`: `Instant`, `@Column(nullable = false, updatable = false)`, set on `@PrePersist`

No setters for `email` or `passwordHash` in this slice (mutability lives in later slices if/when password change ships). Constructor or static factory takes `(String email, String passwordHash)` and **defensively lowercases + trims** the email via `Locale.ROOT` — last line of defense even if a caller forgets to normalize.

#### 3. `AppUserRepository`

**File**: `src/main/java/com/example/app/user/AppUserRepository.java` (new)

**Intent**: Spring Data JPA repository scoped to the only lookups needed in S-01: by `id` (inherited from `JpaRepository`) and by `email` for authentication.

**Contract**: `interface AppUserRepository extends JpaRepository<AppUser, UUID> { Optional<AppUser> findByEmail(String email); boolean existsByEmail(String email); }`. No custom write methods beyond inherited `save`.

#### 4. `AppUserDetailsService`

**File**: `src/main/java/com/example/app/user/AppUserDetailsService.java` (new)

**Intent**: Bridge `AppUserRepository` to Spring Security. Loads an `AppUser` by email and returns a Spring Security `UserDetails` carrying the BCrypt hash. Throws `UsernameNotFoundException` (with a generic message — the form layer converts it to "Invalid email or password" to avoid user enumeration).

**Contract**: `@Service`, implements `org.springframework.security.core.userdetails.UserDetailsService`. Single method `loadUserByUsername(String email)`:
1. Normalize: `email = email.toLowerCase(Locale.ROOT).trim();` — first line of the method, before any DB call. This makes login case-insensitive uniformly across form login and remember-me re-auth.
2. Lookup: `appUserRepository.findByEmail(email)` → if empty, throw `UsernameNotFoundException` (with a generic message — the form layer converts it to "Invalid email or password" to avoid user enumeration).
3. Return: `User.withUsername(appUser.getEmail()).password(appUser.getPasswordHash()).authorities(List.of()).build()`.

Empty authorities list is intentional — flat role model per PRD §Access Control, no roles in MVP.

#### 5. `PersistentLogin` entity (remember-me schema owner)

**File**: `src/main/java/com/example/app/user/PersistentLogin.java` (new)

**Intent**: JPA entity that exists **only** to drive Hibernate's `ddl-auto=update` into creating the `persistent_logins` table with the schema Spring Security's `JdbcTokenRepositoryImpl` reads/writes at runtime. The entity has no repository, no controller, no service. Hibernate manages schema; Spring Security does runtime JDBC against the same `DataSource`.

**Contract**: `@Entity @Table(name = "persistent_logins")`. Fields (column names must match Spring Security's expectations exactly):
- `series`: `String`, `@Id`, `@Column(name = "series", length = 64)`
- `username`: `String`, `@Column(name = "username", nullable = false, length = 64)`
- `token`: `String`, `@Column(name = "token", nullable = false, length = 64)`
- `lastUsed`: `Instant`, `@Column(name = "last_used", nullable = false)`

No constructors / methods beyond JPA's required no-arg constructor.

#### 6. `SecurityConfig` rewrite

**File**: `src/main/java/com/example/app/config/SecurityConfig.java`

**Intent**: Replace HTTP Basic + CSRF-disabled with form login (Spring Security's default `/login` page for Phase 1; swapped to custom in Phase 2) + DAO auth via `AppUserDetailsService` + persistent remember-me backed by `JdbcTokenRepositoryImpl` + CSRF on (default) + permit `/signup`, `/login`, `/actuator/health`, `/css/**`, `/js/**`, `/favicon.ico`. Everything else requires authentication.

**Contract**: One `@Configuration` class exporting three beans:
- `PasswordEncoder passwordEncoder()` returning `new BCryptPasswordEncoder()` (strength 10, Spring default).
- `PersistentTokenRepository persistentTokenRepository(DataSource ds)` returning a `JdbcTokenRepositoryImpl` with `setDataSource(ds)` and **`setCreateTableOnStartup(false)`** (table comes from JPA, see Critical Implementation Details).
- `SecurityFilterChain filterChain(HttpSecurity http, PersistentTokenRepository tokenRepository, UserDetailsService uds)` configured with:
  - `csrf` left at defaults (enabled)
  - `authorizeHttpRequests`: `permitAll` for `/`, `/signup`, `/login`, `/login?error`, `/login?logout`, `/actuator/health`, `/css/**`, `/js/**`, `/favicon.ico`, `/error`; `anyRequest().authenticated()`
  - `formLogin(Customizer.withDefaults())` — Phase 1 uses Spring Security's default form; Phase 2 swaps to `.loginPage("/login").defaultSuccessUrl("/app", false).failureUrl("/login?error")`
  - `logout(logout -> logout.logoutSuccessUrl("/login?logout"))`
  - `rememberMe(rm -> rm.tokenRepository(tokenRepository).userDetailsService(uds).tokenValiditySeconds(60 * 60 * 24 * 30).alwaysRemember(true))` — 30 days, **always on** per PRD §Access Control "Standard 'log in once, stay logged in' pattern" and FR-001 "remain logged in across browser sessions". The `alwaysRemember(true)` flag means every successful authentication (login + signup auto-login) issues the persistent cookie unconditionally — no opt-in checkbox.

No `httpBasic(...)` block. Drop the existing `.csrf(AbstractHttpConfigurer::disable)` call.

#### 7. Session + forward-headers config

**File**: `src/main/resources/application.properties`

**Intent**: Mark session and remember-me cookies HttpOnly + Secure + SameSite=Lax for production; honor `X-Forwarded-Proto` so Fly's edge HTTPS termination is reflected in cookie issuance. Document the local-dev override.

**Contract**: append four lines:
- `server.servlet.session.cookie.secure=true`
- `server.servlet.session.cookie.http-only=true`
- `server.servlet.session.cookie.same-site=lax`
- `server.forward-headers-strategy=framework`

Plus a comment immediately above noting: "For local dev over http://localhost, set `SERVER_SERVLET_SESSION_COOKIE_SECURE=false` in the env to allow cookies to round-trip; production runs behind Fly's HTTPS edge so Secure stays on by default."

#### 8. Phase 1 tests

**File**: `src/test/java/com/example/app/AppApplicationTests.java`

**Intent**: Verify the rewritten security wiring without depending on Phase 2's controllers/templates. Existing tests stay green; new tests cover anonymous redirect and that a seeded user authenticates against the configured DAO + password encoder.

**Contract**: keep existing `contextLoads` and `actuatorHealthIsPublic`. Add:
- `anonymousGetAppRedirectsToLogin` — `mvc.perform(get("/app"))` expects `status().is3xxRedirection()` and a `redirectedUrlPattern("**/login**")`.
- `seededUserCanAuthenticate` — uses `AppUserRepository` (autowired) to insert a user with a BCrypt hash matching a known plaintext, then `mvc.perform(formLogin("/login").user(...).password(...))` expects `authenticated()` and redirect to `/`. Requires `@AutoConfigureMockMvc` (already present) + autowired `AppUserRepository` + autowired `PasswordEncoder`.
- `persistentLoginsTableExists` — `@Autowired DataSource ds`; opens a connection and queries `DatabaseMetaData.getTables(null, null, "PERSISTENT_LOGINS", null)` (uppercase for H2's default identifier handling) to assert the table was created by Hibernate. Lightweight schema-presence smoke test; not a behavioral assertion.

Tests run against H2 via `testRuntimeOnly`. Use the existing `@SpringBootTest @AutoConfigureMockMvc` class shape — no new test slices needed.

### Success Criteria:

#### Automated Verification:

- `./gradlew build` succeeds
- `./gradlew test` passes (existing `contextLoads`, `actuatorHealthIsPublic` still green)
- `anonymousGetAppRedirectsToLogin` passes
- `seededUserCanAuthenticate` passes
- `persistentLoginsTableExists` passes

#### Manual Verification:

- `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` exported, `./gradlew bootRun` boots without errors on `:8080`
- `GET /` in a browser redirects to a login form (Spring Security's default-rendered one in Phase 1)
- `GET /actuator/health` returns `{"status":"UP"}` without authentication
- A user inserted via `psql` with a BCrypt-hashed password can log in via the default form. The default `formLogin` redirects to `/` (or a `SavedRequest`) — not `/app` — in Phase 1; that's fine, Phase 2 swaps the success URL.
- **Neon schema verification**: against the Neon dev DB, run `psql "$SPRING_DATASOURCE_URL" -c "\d persistent_logins"` and confirm column types: `series varchar(64) PRIMARY KEY, username varchar(64) NOT NULL, token varchar(64) NOT NULL, last_used timestamp(6) NOT NULL` (or `timestamp with time zone` — both are JDBC-readable). Likewise `\d app_user` should show `id uuid PRIMARY KEY, email varchar(254) UNIQUE NOT NULL, password_hash varchar(60) NOT NULL, created_at timestamp NOT NULL`. The H2-based `persistentLoginsTableExists` test only proves H2; Neon is where remember-me actually runs in production.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to Phase 2.

---

## Phase 2: User flows + UI (signup, login, /app, logout)

### Overview

Add the Thymeleaf templates and controllers that turn the Phase-1 plumbing into a usable flow: a custom login page, a signup page with auto-login on success and inline field errors, the empty-state `/app` view with the user's email in a layout header, the CSRF-protected logout form, and the `/` redirect logic. Switch `SecurityConfig` from default form to custom `/login` page.

### Changes Required:

#### 1. Base layout fragment

**File**: `src/main/resources/templates/fragments/layout.html` (new)

**Intent**: A single reusable Thymeleaf fragment carrying the page shell — `<head>` block, top bar with the logged-in user's email and the CSRF-protected logout POST form, a content slot, and a footer. Both `/app` and any future authenticated pages reuse it. Anonymous pages (`/login`, `/signup`) reuse only the `<head>` fragment, not the top bar.

**Contract**: defines `<head>`, `<header>`, and `<footer>` Thymeleaf fragments. The `<header>` fragment reads the current user's email from a Spring model attribute (`<span th:text="${userEmail}">`) populated by the controller — not from `sec:authentication`. The logout form is `<form th:action="@{/logout}" method="post"><button type="submit">Log out</button></form>`. No CSS framework; minimal `<style>` block in the head fragment is acceptable.

**Why model attribute, not `thymeleaf-extras-springsecurity6`**: Spring Boot 4.0.6 resolves Spring Security 7.0.5; the `-springsecurity6` Thymeleaf dialect was last updated for Spring Security 6.x package/class shape and may fail to bind on 7.x. The model-attribute approach has zero third-party-dialect risk, works on any Spring Security version, and only adds one line per authenticated controller.

#### 2. Login template

**File**: `src/main/resources/templates/login.html` (new)

**Intent**: Custom Thymeleaf form replacing Spring Security's default. POSTs to `/login` (Spring Security's `UsernamePasswordAuthenticationFilter` consumes it). Shows the generic error message when `?error` is in the query string, and a "Logged out" message when `?logout` is. **No "Remember me" checkbox** — persistent sessions are always on (see SecurityConfig `alwaysRemember(true)`).

**Contract**: form with `email` (mapped to `username`), `password`, and Thymeleaf's auto-injected CSRF hidden input. Error block shows "Invalid email or password." regardless of which field caused failure. POST target is `/login`. Reuses `<head>` from the layout fragment but no top bar (anonymous page).

#### 3. Signup template

**File**: `src/main/resources/templates/signup.html` (new)

**Intent**: Custom signup form. Renders inline field-specific error messages from the controller's `BindingResult` (Spring's standard form-validation surface), preserves the email input on re-render after a validation failure (never the password — always clears).

**Contract**: form with `email`, `password`, CSRF hidden input. Each input is followed by `<span th:if="${#fields.hasErrors('email')}" th:errors="*{email}">` (and same for `password`). POST target is `/signup`. Reuses `<head>` fragment only. **No remember-me checkbox** — always-on remember-me applies to signup auto-login the same way it applies to login.

#### 4. `/app` empty-state template

**File**: `src/main/resources/templates/app.html` (new)

**Intent**: The authenticated landing page. Uses the layout fragment's `<head>` + `<header>` (top bar with email + logout). Body is a centered empty-state block: "No events yet. Manual entry coming in the next slice."

**Contract**: includes the layout fragments via `th:replace`. Body shows the principal's email via `<span th:text="${userEmail}">` (model attribute populated by `AppController.app()`) — both for UX and so the Phase 3 partition test has something concrete to assert against.

#### 5. `SignupForm` DTO

**File**: `src/main/java/com/example/app/user/SignupForm.java` (new)

**Intent**: Backing bean for the signup form with jakarta-validation annotations. Kept separate from `AppUser` so the entity isn't bound directly to user input.

**Contract**: Java class (or record — either is fine) with two fields:
- `email`: `String`, `@NotBlank`, `@Email`, `@Size(max = 254)`
- `password`: `String`, `@NotBlank`, `@Size(min = 12, max = 100)`

Standard getters/setters (or record accessors). No `rememberMe` field — persistent sessions are always on at the SecurityConfig level.

#### 6. `SignupController`

**File**: `src/main/java/com/example/app/user/SignupController.java` (new)

**Intent**: Render the signup form, validate the submission, surface inline errors on failure, persist + auto-login + redirect on success. Auto-login state sequencing per Critical Implementation Details.

**Contract**: `@Controller` with two endpoints:
- `GET /signup` → returns `"signup"` (template name), populating the model with an empty `SignupForm`.
- `POST /signup` → `@Valid SignupForm form, BindingResult result, HttpServletRequest request, HttpServletResponse response`:
  - If `result.hasErrors()`, return `"signup"` (re-renders with errors — email keeps original casing for UX).
  - **Normalize email**: `form.setEmail(form.getEmail().toLowerCase(Locale.ROOT).trim());` — after `@Valid` runs, before any DB call.
  - Check `appUserRepository.existsByEmail(form.email)`; if true, add a field error (`result.rejectValue("email", "duplicate", "Email already in use.")`) and return `"signup"`.
  - Else hash the password via the autowired `PasswordEncoder` and attempt `appUserRepository.save(new AppUser(form.email, hashed))` inside a `try/catch (DataIntegrityViolationException dup)` — on catch, the unique constraint fired (race between `existsByEmail` and `save`); reject with the same field error and return `"signup"`. This catch is the second line of defense and ensures the user sees a clean form re-render instead of a 500.
  - Auto-login: build a `UsernamePasswordAuthenticationToken` for the saved user via `AppUserDetailsService.loadUserByUsername(form.email)`, set it on `SecurityContextHolder.getContext()`, and persist the security context via the **injected `SecurityContextRepository` bean** — not a hand-instantiated `HttpSessionSecurityContextRepository`. In Spring Security 7 the default chain is a `DelegatingSecurityContextRepository(RequestAttributeSecurityContextRepository, HttpSessionSecurityContextRepository)`; using the bean keeps both layers in sync. Then invoke `rememberMeServices.loginSuccess(request, response, token)` — unconditionally, because the configured `RememberMeServices` is `alwaysRemember(true)`.
  - Return `"redirect:/app"`.

Depends on: `AppUserRepository`, `PasswordEncoder`, `AppUserDetailsService`, `RememberMeServices` (Spring Security injects the configured instance), `SecurityContextRepository` (the configured delegating bean — autowire the interface, not the impl).

#### 7. `AppController`

**File**: `src/main/java/com/example/app/web/AppController.java` (new)

**Intent**: Handle `/` (redirect based on auth state) and `/app` (render the empty personal view).

**Contract**: `@Controller` with two endpoints:
- `GET /` → if the current `Authentication` is authenticated (not `AnonymousAuthenticationToken`), return `"redirect:/app"`; else `"redirect:/login"`. Implement with a Spring Security `@CurrentSecurityContext` parameter or by checking `SecurityContextHolder.getContext().getAuthentication()`.
- `GET /app` → takes an `Authentication auth` parameter (Spring MVC injects the current `Authentication` automatically) and a `Model model`; calls `model.addAttribute("userEmail", auth.getName())`; returns `"app"` (template name). The `userEmail` attribute is consumed by `app.html` and the layout header fragment.

#### 8. `SecurityConfig` switch to custom login page

**File**: `src/main/java/com/example/app/config/SecurityConfig.java` (modify)

**Intent**: Replace `formLogin(Customizer.withDefaults())` with the configured custom page now that the template exists.

**Contract**: `formLogin(login -> login.loginPage("/login").loginProcessingUrl("/login").defaultSuccessUrl("/app", false).failureUrl("/login?error").permitAll())`. The `/login` permit is already in `authorizeHttpRequests` from Phase 1. Second arg `false` means Spring Security honors `SavedRequest` if present (deep-link bounce-back after forced login) and falls back to `/app` only when there is no saved request — for S-01 the observable behavior is identical to `true` because no authenticated deep links exist yet, but the setting protects S-02+ from a deep-link regression.

#### 9. Phase 2 tests

**File**: `src/test/java/com/example/app/AppApplicationTests.java` (extend)

**Intent**: Cover the signup → auto-login → `/app` happy path, the validation failure cases, the login + logout flows, the `/` redirect, and the `/app` content surfaces the principal's email.

**Contract**: add the following test methods (all using the existing `MockMvc` + `@SpringBootTest @AutoConfigureMockMvc` shape):
- `getSignupPageIsPublic` — `GET /signup` → 200, body contains `<form` with action `/signup`.
- `signupHappyPathCreatesUserAndAutoLogsIn` — `POST /signup` with valid email + 12-char password + CSRF → 302 to `/app`; assert `appUserRepository.existsByEmail(...)` true; assert response carries a `JSESSIONID` cookie indicating an active session.
- `signupDuplicateEmailRendersFieldError` — pre-insert a user; `POST /signup` with the same email → 200, body contains "Email already in use" (or stable error key matched in the template).
- `signupMixedCaseEmailNormalizesToLowercase` — `POST /signup` with `Alice@Example.COM` + valid password → 302 to `/app`; assert `appUserRepository.findByEmail("alice@example.com").isPresent()` and `appUserRepository.findByEmail("Alice@Example.COM").isEmpty()`. Then `POST /signup` again with `ALICE@example.com` → 200, body contains the duplicate-email error. This locks the lowercase-at-boundary invariant from `SignupController` + `AppUser` constructor.
- `loginMixedCaseEmailAuthenticates` — seed user with email `alice@example.com`; `formLogin("/login").user("ALICE@Example.com").password(...)` → `authenticated()` and redirect to `/app`. Locks the lowercase normalization in `AppUserDetailsService.loadUserByUsername`.
- `signupShortPasswordRendersFieldError` — `POST /signup` with 8-char password → 200, body contains the password-size error message.
- `getLoginPageIsPublic` — `GET /login` → 200, body contains the form.
- `loginHappyPath` — seed a user; `formLogin("/login").user(...).password(...)` → `authenticated()` and redirect to `/app`.
- `loginBadPasswordShowsGenericError` — seed a user; `formLogin("/login").user(...).password("wrong")` → `unauthenticated()` and redirect to `/login?error`.
- `getAppAuthenticatedShowsEmail` — `mvc.perform(get("/app").with(user("alice@example.com")))` → 200, body contains `alice@example.com`.
- `logoutInvalidatesSessionAndRedirects` — log in, capture session, `POST /logout` with CSRF → 302 to `/login?logout`; assert subsequent `GET /app` returns 3xx to `/login` (session no longer authenticated).
- `rootRedirectsAnonymousToLogin` — `GET /`, anonymous → 302 to `/login`.
- `rootRedirectsAuthenticatedToApp` — `GET /` with `with(user(...))` → 302 to `/app`.

### Success Criteria:

#### Automated Verification:

- `./gradlew test` passes (all Phase 1 tests still green + all Phase 2 tests above)
- `getSignupPageIsPublic` passes
- `signupHappyPathCreatesUserAndAutoLogsIn` passes
- `signupDuplicateEmailRendersFieldError` passes
- `signupMixedCaseEmailNormalizesToLowercase` passes
- `signupShortPasswordRendersFieldError` passes
- `getLoginPageIsPublic` passes
- `loginHappyPath` passes
- `loginMixedCaseEmailAuthenticates` passes
- `loginBadPasswordShowsGenericError` passes
- `getAppAuthenticatedShowsEmail` passes
- `logoutInvalidatesSessionAndRedirects` passes
- `rootRedirectsAnonymousToLogin` passes
- `rootRedirectsAuthenticatedToApp` passes

#### Manual Verification:

- `./gradlew bootRun` (with `SERVER_SERVLET_SESSION_COOKIE_SECURE=false` env override for local) — visit `http://localhost:8080/`: redirected to `/login` showing the custom form
- Click "Sign up", fill a valid email + 12-char password, submit: lands on `/app` showing the email in the header and the empty-state message
- Click "Log out": session invalidated, redirected to `/login?logout` with the "Logged out" notice visible
- Log back in with the same credentials: lands on `/app` again
- Try signup with a 6-char password: page re-renders with the inline length error visible next to the password field
- Try login with the wrong password: redirected to `/login?error` with the generic "Invalid email or password." message

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to Phase 3.

---

## Phase 3: Partition contract + remember-me verification

### Overview

Lock the per-user partition contract (the load-bearing invariant every subsequent slice inherits) with a two-user negative integration test, and verify the persistent remember-me cookie round-trips correctly. End with a full manual run-through covering all PRD-named outcomes for S-01.

### Changes Required:

#### 1. Two-user partition test

**File**: `src/test/java/com/example/app/AppApplicationTests.java` (extend)

**Intent**: Encode the roadmap risk explicitly: register two users A and B, log in as A, fetch `/app`, assert A's email is in the body and B's email is not. The negative half — that B's email never appears — is what locks the partition.

**Contract**: add `appShowsOwnEmailOnlyNotOtherUsersEmail` test method. Body:
- Seed two `AppUser` rows via `AppUserRepository.save` with distinct emails (e.g., `alice@example.com`, `bob@example.com`) and any valid BCrypt hashes.
- `mvc.perform(get("/app").with(user("alice@example.com")))` → 200.
- Assert `andExpect(content().string(containsString("alice@example.com")))`.
- Assert `andExpect(content().string(not(containsString("bob@example.com"))))`.

This test will keep passing trivially in S-01 (empty-state body has nothing to leak) but will catch a regression the moment S-02 introduces user-scoped data and any future controller forgets to scope by `userId`. The pattern is the contract; later slices extend it.

#### 2. Remember-me end-to-end test

**File**: `src/test/java/com/example/app/AppApplicationTests.java` (extend)

**Intent**: Verify the full persistent-token loop: log in with remember-me checked, capture the cookie + assert a `persistent_logins` row exists, simulate session destruction by issuing a request that omits `JSESSIONID` but presents the remember-me cookie, and assert the request is re-authenticated (returns 200 on `/app` with the user's email).

**Contract**: add `rememberMeCookieReAuthenticatesAfterSessionEnds` test method. Body:
- Seed an `AppUser` (BCrypt-hashed password `correctHorseBatteryStaple`).
- `MvcResult login = mvc.perform(formLogin("/login").user("alice@example.com").password("correctHorseBatteryStaple")).andExpect(authenticated()).andReturn();` — no `remember-me` parameter; persistent cookie is issued unconditionally because SecurityConfig sets `alwaysRemember(true)`.
- Extract the `remember-me` cookie from `login.getResponse().getCookies()`. Assert it's non-null and has a non-empty value.
- Assert a row exists in `persistent_logins` (raw JDBC query against the autowired `DataSource`, `SELECT count(*) FROM persistent_logins WHERE username = 'alice@example.com'`).
- `mvc.perform(get("/app").cookie(rememberMeCookie))` — note: no `JSESSIONID`, only the remember-me cookie — `.andExpect(status().isOk()).andExpect(content().string(containsString("alice@example.com")))`.

This test is the only one in the slice that exercises the full persistence chain (cookie → DB → re-auth) and would catch a regression in the `JdbcTokenRepositoryImpl` ↔ `PersistentLogin` table-shape contract.

### Success Criteria:

#### Automated Verification:

- `./gradlew test` passes (all Phase 1 + Phase 2 tests still green + the two new tests)
- `appShowsOwnEmailOnlyNotOtherUsersEmail` passes
- `rememberMeCookieReAuthenticatesAfterSessionEnds` passes

#### Manual Verification:

- `./gradlew bootRun` against Neon (or local Postgres): sign up user A in browser profile 1, user B in profile 2 (or one Chrome window + one private/Firefox window). Confirm each `/app` shows only the respective email.
- In profile 1: log out, then log back in (no checkbox — persistent cookie is always issued). Close the browser entirely (not just the tab). Reopen, navigate to `http://localhost:8080/app`. Confirm you land on `/app` without being asked to log in.
- After the remember-me re-auth above, confirm a `persistent_logins` row for user A exists (`psql` against Neon dev DB or H2 console: `SELECT username, last_used FROM persistent_logins;`).
- Log out from `/app`: confirm the next `GET /app` is bounced to `/login` (cookie + session both gone).

**Implementation Note**: After completing this phase and all automated verification passes, pause for the final manual confirmation. This is the slice's ship gate.

---

## Testing Strategy

### Unit Tests:

S-01's primary risk is integration-shaped (security config wiring, auto-login state sequencing, partition contract), so the bulk of testing lives in `@SpringBootTest`. Two narrow unit-test cases worth considering if time allows (not blocking):

- `AppUserDetailsService.loadUserByUsername` returns the expected `UserDetails` for an existing email and throws `UsernameNotFoundException` for a missing one — covered transitively by `seededUserCanAuthenticate` and `loginBadPasswordShowsGenericError`, so an explicit unit test is duplicative.
- `SignupForm` validation — covered transitively by `signupShortPasswordRendersFieldError` and would only matter independently if we add many validation rules in v2.

### Integration Tests:

All Phase 1, 2, 3 tests above are `@SpringBootTest @AutoConfigureMockMvc` integration tests running against the H2 in-memory database (per `testRuntimeOnly` H2 dependency in `build.gradle:33`). They cover:

- Security config wiring (anonymous redirect, public endpoints, authenticated endpoints)
- Schema creation by Hibernate (`persistent_logins` table exists)
- Form login + DAO auth happy path and failure path
- Signup happy path with auto-login + auto-redirect
- Inline field error rendering (duplicate email, short password)
- Logout invalidation
- `/` redirect logic for both auth states
- Per-user partition contract (A vs B)
- Persistent remember-me round-trip

### Manual Testing Steps:

The Phase 3 Manual Verification block above is the canonical manual test plan. The single most important manual check is the **two-browser partition test** — `@SpringBootTest` proves the controller scopes by principal, but only a real browser session proves there's no template, session, or filter behavior that leaks across two concurrent real-world logins.

## Performance Considerations

S-01 is single-user, low-QPS by PRD definition. The only choices with performance implications:

- **BCrypt cost 10** — Spring default, ~80-100ms per hash on modern hardware. Login latency is acceptable; signup is rare. No tuning needed.
- **Persistent remember-me JDBC lookup** — one indexed SELECT on `persistent_logins.series` per remember-me-authenticated request. Free at our scale.
- **No N+1 risk** — `AppUserRepository.findByEmail` returns at most one row; no eager associations defined.

## Migration Notes

`spring.jpa.hibernate.ddl-auto=update` under the deploy-plan §5 additive-only contract. This slice adds two new tables — both additive, both safe:

- `app_user` — `id UUID PK, email VARCHAR(254) UNIQUE NOT NULL, password_hash VARCHAR(60) NOT NULL, created_at TIMESTAMP NOT NULL`. Generated by Hibernate on first deploy from the `AppUser` `@Entity`.
- `persistent_logins` — `series VARCHAR(64) PK, username VARCHAR(64) NOT NULL, token VARCHAR(64) NOT NULL, last_used TIMESTAMP NOT NULL`. Generated by Hibernate on first deploy from the `PersistentLogin` `@Entity`. Spring Security uses this table via JDBC at runtime.

**No data migration required** — both tables start empty, populated lazily as users sign up.

**Rollback**: dropping `app_user` and `persistent_logins` would lose all user data. For S-01 with one or two users, that's acceptable (re-signup is the recovery path). For future slices, the additive-only contract means rollback by drop is never the preferred path.

**Local dev workflow note**: cookies marked `Secure` won't round-trip over `http://localhost`. When running `./gradlew bootRun` locally, export `SERVER_SERVLET_SESSION_COOKIE_SECURE=false` to allow the session cookie to be sent back. Document this in the slice's commit message and (optionally) in CLAUDE.md's "Things easy to miss" section after this slice lands.

## References

- Roadmap slice S-01: `context/foundation/roadmap.md`
- PRD §Access Control, §Persistence, FR-001, FR-009: `context/foundation/prd.md`
- Tech-stack rationale: `context/foundation/tech-stack.md`
- Existing security wiring (to be replaced): `src/main/java/com/example/app/config/SecurityConfig.java:9`
- Existing tests (to be preserved): `src/test/java/com/example/app/AppApplicationTests.java:14-27`
- Bootstrap audit trail: `context/changes/bootstrap-verification/verification.md`
- Lesson on H2 test runtime (already applied): `context/foundation/lessons.md` (`Verify ./gradlew test post-bootstrap…`)
- Deploy-plan additive-only migration contract: `context/deployment/deploy-plan.md` §5

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Auth backbone (data layer + security config)

#### Automated

- [x] 1.1 ./gradlew build succeeds — 2d67975
- [x] 1.2 ./gradlew test passes (existing contextLoads, actuatorHealthIsPublic still green) — 2d67975
- [x] 1.3 anonymousGetAppRedirectsToLogin passes — 2d67975
- [x] 1.4 seededUserCanAuthenticate passes — 2d67975
- [x] 1.5 persistentLoginsTableExists passes — 2d67975

#### Manual

- [x] 1.6 SPRING_DATASOURCE_URL/USERNAME/PASSWORD exported, ./gradlew bootRun boots without errors on :8080 — 2d67975
- [x] 1.7 GET / in a browser redirects to a login form (Spring Security's default-rendered one in Phase 1) — 2d67975
- [x] 1.8 GET /actuator/health returns {"status":"UP"} without authentication — 2d67975
- [x] 1.9 A user inserted via psql with a BCrypt-hashed password can log in via the default form. Default formLogin redirects to / (or SavedRequest), not /app — Phase 2 swaps the success URL. — 2d67975
- [x] 1.10 Neon schema verification: psql "$SPRING_DATASOURCE_URL" -c "\d persistent_logins" and "\d app_user" show the expected column types (series/username/token varchar(64), last_used timestamp; id uuid, email varchar(254) UNIQUE, password_hash varchar(60), created_at timestamp) — 2d67975

### Phase 2: User flows + UI (signup, login, /app, logout)

#### Automated

- [x] 2.1 ./gradlew test passes (all Phase 1 tests still green + all Phase 2 tests below) — 6946c00
- [x] 2.2 getSignupPageIsPublic passes — 6946c00
- [x] 2.3 signupHappyPathCreatesUserAndAutoLogsIn passes — 6946c00
- [x] 2.4 signupDuplicateEmailRendersFieldError passes — 6946c00
- [x] 2.5 signupMixedCaseEmailNormalizesToLowercase passes — 6946c00
- [x] 2.6 signupShortPasswordRendersFieldError passes — 6946c00
- [x] 2.7 getLoginPageIsPublic passes — 6946c00
- [x] 2.8 loginHappyPath passes — 6946c00
- [x] 2.9 loginMixedCaseEmailAuthenticates passes — 6946c00
- [x] 2.10 loginBadPasswordShowsGenericError passes — 6946c00
- [x] 2.11 getAppAuthenticatedShowsEmail passes — 6946c00
- [x] 2.12 logoutInvalidatesSessionAndRedirects passes — 6946c00
- [x] 2.13 rootRedirectsAnonymousToLogin passes — 6946c00
- [x] 2.14 rootRedirectsAuthenticatedToApp passes — 6946c00

#### Manual

- [x] 2.15 ./gradlew bootRun (with SERVER_SERVLET_SESSION_COOKIE_SECURE=false env override for local) — visit http://localhost:8080/: redirected to /login showing the custom form — 6946c00
- [x] 2.16 Click "Sign up", fill a valid email + 12-char password, submit: lands on /app showing the email in the header and the empty-state message — 6946c00
- [x] 2.17 Click "Log out": session invalidated, redirected to /login?logout with the "Logged out" notice visible — 6946c00
- [x] 2.18 Log back in with the same credentials: lands on /app again — 6946c00
- [x] 2.19 Try signup with a 6-char password: page re-renders with the inline length error visible next to the password field — 6946c00
- [x] 2.20 Try login with the wrong password: redirected to /login?error with the generic "Invalid email or password." message — 6946c00
- [x] 2.21 Sign up with `Alice@Example.COM`, log out, log back in as `ALICE@example.com` — same account; `app_user` table shows `alice@example.com` as the only row — 6946c00

### Phase 3: Partition contract + remember-me verification

#### Automated

- [x] 3.1 ./gradlew test passes (all Phase 1 + Phase 2 tests still green + the two new tests) — 7ec0919
- [x] 3.2 appShowsOwnEmailOnlyNotOtherUsersEmail passes — 7ec0919
- [x] 3.3 rememberMeCookieReAuthenticatesAfterSessionEnds passes — 7ec0919

#### Manual

- [x] 3.4 ./gradlew bootRun against Neon (or local Postgres): sign up user A in browser profile 1, user B in profile 2 (or one Chrome window + one private/Firefox window). Confirm each /app shows only the respective email. — 7ec0919
- [x] 3.5 In profile 1: log out, then log back in (no checkbox — persistent cookie is always issued). Close the browser entirely (not just the tab). Reopen, navigate to http://localhost:8080/app. Confirm you land on /app without being asked to log in. — 7ec0919
- [x] 3.6 After the remember-me re-auth above, confirm a persistent_logins row for user A exists (psql against Neon dev DB or H2 console: SELECT username, last_used FROM persistent_logins;). — 7ec0919
- [x] 3.7 Log out from /app: confirm the next GET /app is bounced to /login (cookie + session both gone). — 7ec0919
