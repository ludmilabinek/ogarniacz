<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Minimal Auth + Empty Personal View

- **Plan**: context/changes/minimal-auth-and-empty-personal-view/plan.md
- **Scope**: All 3 phases (S-01)
- **Date**: 2026-05-27
- **Verdict**: NEEDS ATTENTION
- **Findings**: 1 critical, 3 warnings, 2 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | WARNING |
| Scope Discipline | WARNING |
| Safety & Quality | FAIL |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

## Findings

### F1 — Hardcoded remember-me signing key committed to source

- **Severity**: ❌ CRITICAL
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: src/main/java/com/example/app/config/SecurityConfig.java:24
- **Detail**: `REMEMBER_ME_KEY = "ogarniacz-remember-me-key"` is a compile-time constant in VCS. For persistent-token remember-me this key signs the cookie HMAC; anyone with read access to GitHub can forge a valid remember-me cookie pair (series + token) and re-authenticate as any user — bypassing both the session and the logout path. The plan didn't specify a key (would have inherited Spring Security's random default), but exposing this bean explicitly forced the issue and the chosen answer leaks the key.
- **Fix A ⭐ Recommended**: Read from env, fail-fast in prod when unset
  - Strength: Standard Spring posture; matches how SPRING_DATASOURCE_* is already handled. Rotating the key just forces one re-login per user — persistent_logins rows are not invalidated (the stored token isn't HMACed by the key).
  - Tradeoff: Need to set REMEMBER_ME_KEY in Fly secrets before next deploy; missing var fails app boot (good — fail loud).
  - Confidence: HIGH — canonical Spring Security pattern.
  - Blind spot: None significant.
- **Fix B**: Drop the explicit RememberMeServices bean; configure inline via `.rememberMe(rm -> rm.key(env).tokenRepository(...).userDetailsService(...).tokenValiditySeconds(...).alwaysRemember(true))`
  - Strength: Matches the plan structure literally; one less bean.
  - Tradeoff: SignupController loses its injected RememberMeServices — would need to read it back from the filter chain (awkward) or duplicate the configuration.
  - Confidence: MEDIUM — Fix A is strictly less work.
  - Blind spot: None significant.
- **Decision**: FIXED in 7313523 via Fix A — SecurityConfig now reads `REMEMBER_ME_KEY` via constructor `@Value`; AppApplicationTests sets `REMEMBER_ME_KEY=test-key-not-for-production` via `@TestPropertySource`. **Action required before next prod deploy**: `flyctl secrets set REMEMBER_ME_KEY=$(openssl rand -hex 32)`.

### F2 — Signup auto-login outside any transaction boundary

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: src/main/java/com/example/app/user/SignupController.java:54-88
- **Detail**: `saveAndFlush` commits the row in its own tx; `loadUserByUsername` at line 78 re-reads in a separate tx. If anything between flush and re-read fails (DB flap, eviction, connection pool exhaustion), the user row is persisted but auto-login throws → 500. On retry the user hits the duplicate-email branch with no way to log in. Low probability at this scale; consequence is "newly-signed-up user is stranded" which is the worst UX bug a signup flow can have.
- **Fix A ⭐ Recommended**: Build UserDetails inline from the saved AppUser instead of re-reading
  - Strength: No extra DB call, no transactional concern. Inline the same `User.withUsername(...).password(...).authorities(List.of()).build()` that AppUserDetailsService returns.
  - Tradeoff: Duplicates 3 lines of AppUserDetailsService logic.
  - Confidence: HIGH — fewer moving parts, same semantics.
  - Blind spot: If AppUserDetailsService grows additional logic later (last-login update, role hydration), inline copy drifts. Worth a one-line comment pointing at it.
- **Fix B**: Wrap the auto-login block in try/catch and redirect to `/login?signupOk` on failure
  - Strength: Account is recoverable (user logs in manually).
  - Tradeoff: Two-step UX on a rare failure; need a new login-page banner string. More code than Fix A.
  - Confidence: MEDIUM — works but inferior to eliminating the re-read entirely.
  - Blind spot: None significant.
- **Decision**: FIXED in 7313523 via Fix A — SignupController now builds UserDetails inline from the saved AppUser (no re-read); UserDetailsService dependency dropped from the controller (still used by Spring Security for the login path).

### F3 — Remember-me cookie defaults: HttpOnly only, no Secure / SameSite

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/com/example/app/config/SecurityConfig.java:51-55 (application.properties:19-22 covers JSESSIONID only)
- **Detail**: `server.servlet.session.cookie.secure=true` applies to JSESSIONID, not to the persistent remember-me cookie. The remember-me cookie is the long-lived credential — losing it over plaintext is worse than losing the session cookie. Default `PersistentTokenBasedRememberMeServices` sets HttpOnly=true but NOT Secure. Fly's edge terminates HTTPS, but defense-in-depth matters when the cookie outlives the session by 30 days.
- **Fix**: Add `svc.setUseSecureCookie(true);` in the `rememberMeServices(...)` bean (SecurityConfig.java:55). If local-dev parity matters, gate via env var the same way the session-cookie comment block does.
- **Decision**: FIXED in 7313523 via Fix differently — bean method now reads `@Value("${server.servlet.session.cookie.secure:true}")` and calls `svc.setUseSecureCookie(...)`. Reuses the existing `SERVER_SERVLET_SESSION_COOKIE_SECURE` env var so one toggle flips both cookies.

### F4 — Unplanned GET /login mapping in AppController

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Scope Discipline
- **Location**: src/main/java/com/example/app/web/AppController.java:22-25
- **Detail**: `@GetMapping("/login")` returning `"login"` was not in the plan. Spring Security's `.loginPage("/login")` + Thymeleaf view resolution already serve the same template. The two handlers don't conflict (both resolve to the same view) but it's redundant and a future reader will wonder which path runs.
- **Fix**: Delete the `@GetMapping("/login")` method.
- **Decision**: DISMISSED — finding was wrong. In Spring Security 6+ / Boot 4, `.loginPage("/login")` disables `DefaultLoginPageGeneratingFilter`; the app MUST provide its own GET /login handler. No `WebMvcConfigurer.addViewControllers(...)` exists in the project, so `AppController.@GetMapping("/login")` is load-bearing, not redundant. Reviewer reasoning was incorrect.

### F5 — Plan-promised `footer` fragment never created

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: src/main/resources/templates/fragments/layout.html
- **Detail**: Plan §1 (line 231) said the layout fragment defines `<head>, <header>, and <footer> Thymeleaf fragments`. Actual file has `head` and `topbar` only. No template uses `footer` so there's no runtime impact; the plan contract is just unfulfilled.
- **Fix**: Either add an empty `footer` fragment for future use, or note in the plan epilogue that the footer was deemed unnecessary in S-01.
- **Decision**: SKIPPED — no runtime impact; defer until something actually needs a footer.

### F6 — AppController mixes Authentication-injection styles

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/main/java/com/example/app/web/AppController.java (index uses SecurityContextHolder static; app uses parameter)
- **Detail**: `index()` reads `SecurityContextHolder.getContext().getAuthentication()` directly while `app()` takes `Authentication` as a method parameter. Parameter injection is more testable (no static call to mock). Minor; works as-is.
- **Fix**: Optional — change `index()` to take `Authentication auth` as a method parameter for consistency.
- **Decision**: FIXED in 7313523 — `index()` now takes `Authentication auth` parameter; removed unused `SecurityContextHolder` import.

## What passed cleanly

- All 20 planned tests present and named exactly as specified
- Plan-mandated invariants honored: email lowercase at all 3 chokepoints with `Locale.ROOT`, CSRF on (default), no `httpBasic`, `setCreateTableOnStartup(false)`, `alwaysRemember(true)`, injected `SecurityContextRepository` (not hand-instantiated), empty authorities `List.of()`, `th:action` on every form, no `sec:authentication` in templates, no remember-me checkbox, session cookie posture + dev-override comment
- `SignupController` sequencing matches the Critical Implementation Details spec (validate → normalize → existsByEmail → save (try/catch dup) → set SecurityContext → saveContext → rememberMeServices.loginSuccess → redirect)
- `PersistentLogin` JPA entity correctly mirrors `JdbcTokenRepositoryImpl` schema (column names + lengths)
- Two-user partition test (`appShowsOwnEmailOnlyNotOtherUsersEmail`) and end-to-end remember-me test (`rememberMeCookieReAuthenticatesAfterSessionEnds`) both present and locking the S-01 contract
