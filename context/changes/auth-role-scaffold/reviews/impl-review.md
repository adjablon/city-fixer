<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Auth and Role Scaffold

- **Plan**: context/changes/auth-role-scaffold/plan.md
- **Scope**: Phases 1–4 of 4 (full plan review)
- **Date**: 2026-08-25
- **Verdict**: REJECTED (1 critical security finding — fix is small and targeted)
- **Findings**: 1 critical, 6 warnings, 3 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | WARNING |
| Scope Discipline | WARNING |
| Safety & Quality | FAIL |
| Architecture | PASS |
| Pattern Consistency | WARNING |
| Success Criteria | WARNING |

## Verification evidence

- `./mvnw compile` — PASS
- `./mvnw clean package` (includes full test suite) — PASS: 19 tests, 0 failures, 0 errors. Note: first review run failed with "Could not find a valid Docker environment" (Rancher Desktop socket at `~/.rd/docker.sock`, not `/var/run/docker.sock`); after the user fixed the Docker environment, the suite passed.
- All 18 Progress checkboxes are `[x]` with commit SHAs. Manual items have observable supporting evidence in the diff (templates, tests, curl-verifiable endpoints) — no rubber-stamping detected, except the Phase 4 CI decision (see F7).

## Findings

### F1 — Session fixation not mitigated on JSON API login

- **Severity**: ❌ CRITICAL
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/com/example/city_fix/auth/AuthController.java:43-48
- **Detail**: The API login authenticates via `AuthenticationManager` in a controller and stores the security context into the existing session (`request.getSession(true)` reuses a pre-login session). Spring Security's built-in session-fixation protection (session id rotation on login) only runs for filter-based login (the web chain's formLogin), not manual controller authentication. An attacker who can plant a session cookie on the victim inherits an authenticated session after the victim logs in via the API. This is a side effect of the login-mechanism drift (F4) — the planned formLogin path would have rotated the session id automatically.
- **Fix**: Call `request.changeSessionId()` after successful authentication and before saving the security context; also build the context via `SecurityContextHolder.createEmptyContext()` instead of mutating the thread-local one.
- **Decision**: FIXED — session id rotation + empty context applied in AuthController.login

### F2 — Blanket CSRF exemption for /api/auth/** includes logout and all future endpoints

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: src/main/java/com/example/city_fix/config/SecurityConfig.java:53-56
- **Detail**: `.csrf(csrf -> csrf.spa().ignoringRequestMatchers("/api/auth/**"))` exempts not just login/register but also `POST /api/auth/logout` (any site can forcibly log the user out) and silently exempts every future endpoint added under `/api/auth/`. Login CSRF is also a real (if minor) attack the exemption enables. The plan specified only `csrf.spa()` — the exemption was added in d7a74bb to keep JSON-body login working outside formLogin.
- **Fix A ⭐ Recommended**: Narrow the exemption to exactly `/api/auth/login` and `/api/auth/register`.
  - Strength: Removes the logout-CSRF hole and the future-endpoint trap with a two-line change; login/register remain CSRF-free (they carry no ambient authority to abuse beyond enumeration).
  - Tradeoff: A conscious exemption list to maintain.
  - Confidence: HIGH — SecurityConfigTest already has CSRF tests to adjust and re-run.
  - Blind spot: None significant.
- **Fix B**: Drop the exemption entirely — clients read the `XSRF-TOKEN` cookie (set by `csrf.spa()`) and send `X-XSRF-TOKEN` on every mutating request including login.
  - Strength: Zero exemptions; uniform CSRF story for the whole API.
  - Tradeoff: Frontend must fetch a page/cookie before first POST; curl/Postman flows get more awkward; more test churn.
  - Confidence: MEDIUM — no JS frontend exists yet to validate the flow end-to-end.
  - Blind spot: Haven't verified how the future S-01 frontend will bootstrap the token.
- **Decision**: SKIPPED

### F3 — Email not normalized: case-variant duplicate accounts and login mismatch

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/com/example/city_fix/auth/AuthService.java:34,38 (also CustomUserDetailsService lookup)
- **Detail**: `existsByEmail`/`findByEmail` are case-sensitive. `Foo@Example.com` and `foo@example.com` register as two distinct users, and a user who registers with mixed case must log in with the exact same casing — duplicate identities plus a support trap.
- **Fix**: Normalize (`trim().toLowerCase(Locale.ROOT)`) in `AuthService.register()` and in `CustomUserDetailsService.loadUserByUsername()`.
- **Decision**: FIXED — normalization applied at registration and login lookup

### F4 — API login architecture drift: controller-based manual auth replaced planned formLogin + JSON handlers

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Plan Adherence
- **Location**: src/main/java/com/example/city_fix/auth/AuthController.java:33-61
- **Detail**: The plan specified `formLogin` with `loginProcessingUrl("/api/auth/login")` plus `AuthenticationSuccessHandler`/`AuthenticationFailureHandler`. The implementation instead hand-rolls login in the controller via `AuthenticationManager.authenticate` and manual session storage, adding an unplanned `AuthenticationManager` bean (SecurityConfig.java:83-86). Response contracts (200/401 JSON shapes) are honored, so this is intent-preserving mechanism drift — but it caused F1 (lost session-fixation protection) and F2 (CSRF exemption), the two security findings of this review.
- **Fix A ⭐ Recommended**: Keep the controller approach, apply the F1/F2 fixes, and document the mechanism swap as a plan addendum.
  - Strength: Preserves working, tested code; the JSON contract is met and 19 tests pass; addendum keeps the plan a truthful source for future reviews.
  - Tradeoff: The implementation must manually re-add protections formLogin gives for free (session rotation done in F1; be alert for others, e.g. concurrent-session control later).
  - Confidence: HIGH — the drift is already sanctioned in spirit by the plan-review fix commit d7a74bb.
  - Blind spot: Unknown future formLogin features (remember-me, session limits) will need manual wiring too.
- **Fix B**: Rework to the planned formLogin + JSON handlers.
  - Strength: Restores Spring Security's built-in login protections wholesale; F1 and most of F2 disappear structurally.
  - Tradeoff: formLogin consumes form-encoded bodies, not JSON — a custom filter or content negotiation is needed, which is its own complexity; discards working tested code.
  - Confidence: LOW — non-trivial rework for equivalent externally-visible behavior.
  - Blind spot: Effort not scoped; test churn across AuthControllerTest and SecurityConfigTest.
- **Decision**: SKIPPED

### F5 — AdminSeeder has zero test coverage; planned unit tests missing

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: N/A (missing file — no AdminSeeder test exists in src/test)
- **Detail**: The plan's Testing Strategy lists unit tests for `AdminSeeder` (seeds when no admin exists + env vars set; skips when admin exists or vars blank) — none exist. The planned `AuthService.register()` unit tests (valid/duplicate/short-password) also don't exist as unit tests, but are fully covered at integration level in AuthControllerTest, which is acceptable. The AdminSeeder gap is real: its idempotence logic (which already drifted once — accepted finding F4 of the plan review) is entirely unverified.
- **Fix**: Add `AdminSeederTest` unit tests with a mocked `UserRepository`/`PasswordEncoder` covering: seeds when absent + vars set; skips when email exists; skips when vars blank.
- **Decision**: FIXED — AdminSeederTest added (3 cases), passing

### F6 — Registration reveals account existence and echoes the email in the 409 message

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/com/example/city_fix/auth/AuthService.java:49-53 (surfaced at AuthController.java:74-75)
- **Detail**: The 409 response body contains "Email already registered: <email>", enabling user enumeration — undoing the care taken in login ("Invalid email or password"). Enumeration via registration is a common, often-accepted MVP tradeoff, but it should be a conscious decision; echoing the submitted email back in the message adds nothing.
- **Fix**: Drop the email echo from the exception message ("Email already registered"); consciously accept the remaining enumeration as MVP risk or defer a generic-response flow to post-MVP hardening.
- **Decision**: SKIPPED

### F7 — CI still skips tests; the plan's open CI decision was never recorded

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Success Criteria
- **Location**: .github/workflows/deploy.yml:24
- **Detail**: Phase 4's manual verification flagged: "currently `-DskipTests`, so decide whether to enable tests in CI as part of this phase or defer". The workflow still runs `./mvnw clean package -DskipTests -B` and no decision is recorded anywhere. The full Testcontainers suite (19 tests) exists but never gates a deploy.
- **Fix**: Remove `-DskipTests` from deploy.yml (GitHub-hosted runners provide Docker, so Testcontainers works) — or explicitly record the deferral in the plan/change notes.
- **Decision**: FIXED — `-DskipTests` removed; tests now gate the deploy

### F8 — API error-contract gaps: 403s return HTML; login only catches BadCredentialsException

- **Severity**: 💬 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/com/example/city_fix/config/SecurityConfig.java:34-42; AuthController.java:56
- **Detail**: The API chain customizes the 401 entry point but not the `AccessDeniedHandler`, so CSRF-rejected or role-denied `/api/**` requests get a default non-JSON 403. Similarly, login catches only `BadCredentialsException` — `DisabledException`, `LockedException`, or `InternalAuthenticationServiceException` (DB down) escape as a default 500 on a JSON endpoint.
- **Fix**: Add a JSON `accessDeniedHandler` to the API chain and broaden the login catch to `AuthenticationException` (infrastructure failures can map to JSON 500 via an `@ExceptionHandler`).
- **Decision**: SKIPPED

### F9 — Untyped Map<String,String> request/response DTOs instead of records + Bean Validation

- **Severity**: 💬 OBSERVATION
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Pattern Consistency
- **Location**: src/main/java/com/example/city_fix/auth/AuthController.java:34,51-55,64
- **Detail**: Request bodies and responses are raw maps — no compile-time contract, no Bean Validation (`@Valid`, `@Email`, `@Size` would replace the hand-rolled checks in AuthService), stringly-typed keys. This is the first controller in the project; S-01/S-02/S-03 controllers will copy whatever pattern it establishes. Strong candidate for a recurring lesson.
- **Fix**: Introduce records (`RegisterRequest(String email, String password)`, `UserResponse(Long id, String email, Role role)`) with Bean Validation annotations.
  - Strength: Establishes the typed-DTO pattern before three more slices copy the map style; deletes hand-rolled validation.
  - Tradeoff: Touches AuthService's validation contract and several tests now, for behavior that already works.
  - Confidence: HIGH — standard Spring Boot idiom, mechanical change.
  - Blind spot: None significant.
- **Decision**: FIXED + ACCEPTED-AS-RULE: Typed DTOs over raw maps in controllers — records (RegisterRequest/LoginRequest/UserResponse) + Bean Validation introduced; error contract preserved via @ExceptionHandler; lesson recorded in context/foundation/lessons.md

### F10 — Consolidated consistency and robustness nits

- **Severity**: 💬 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: multiple (see detail)
- **Detail**: (a) Testcontainers wiring drifted from the planned `@TestConfiguration` + `@ServiceConnection` + `@RestartScope` to an abstract base class with `@DynamicPropertySource` — works, but adds an unplanned `testcontainers-bom` and an apparently unused `org.testcontainers:junit-jupiter` dependency (pom.xml:100-104). (b) AdminSeeder uses `@Value` field injection while every other class is constructor-only (AdminSeeder.java:22-26). (c) `new ObjectMapper()` in SecurityConfig.java:23 bypasses the Spring-managed Jackson config. (d) `createdAt` uses `LocalDateTime.now()` (JVM-zone-dependent) — prefer `Instant` before more entities copy it (User.java:31-41). (e) AdminSeeder's check-then-insert can abort startup in multi-instance deploys — same race class already fixed in AuthService. (f) compose.yaml binds 5432 on 0.0.0.0 with trivial creds — dev-only, keep it from leaking to deployed profiles.
- **Fix**: Batch cleanup: drop unused junit-jupiter dep, constructor-inject seeder config, inject ObjectMapper, switch createdAt to Instant, catch DataIntegrityViolationException in seeder (logged WARN), bind compose to 127.0.0.1.
- **Decision**: FIXED — all six nits applied (Testcontainers base-class wiring itself kept as-is; only the unused dep dropped)

## Scope notes (not findings)

- `templates/home.html` is justified, not scope creep — it is the "minimal authenticated landing page" branch of Phase 2 item 6 and explicitly defers the map to S-01.
- "What We're NOT Doing" guardrails: CLEAN — no OAuth, email verification, password reset, admin deactivation, report entities, Flyway, or rate limiting found.
- Commit d7a74bb (plan-review fixes: CSRF, 409 race, email regex, tests) is sanctioned post-plan evolution mapped to recorded plan-review findings, not silent drift.
- AdminSeeder idempotence checks by seed email rather than "any ADMIN exists" — documented, accepted drift (plan-review F4).
