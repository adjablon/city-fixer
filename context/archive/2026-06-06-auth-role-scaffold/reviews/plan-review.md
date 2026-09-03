<!-- PLAN-REVIEW-REPORT -->
# Plan Review: Auth and Role Scaffold (Post-Implementation)

- **Plan**: context/changes/auth-role-scaffold/plan.md
- **Mode**: Deep
- **Date**: 2026-06-07
- **Verdict**: SOUND
- **Findings**: 0 critical, 2 warnings, 2 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| End-State Alignment | PASS |
| Lean Execution | PASS |
| Architectural Fitness | WARNING |
| Blind Spots | WARNING |
| Plan Completeness | PASS |

## Grounding

17/17 paths ✓, brief↔plan ✓

## Findings

### F1 — CSRF not configured for future API endpoints

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Architectural Fitness
- **Location**: SecurityConfig.java — API chain CSRF config
- **Detail**: Plan specifies `.csrf(csrf -> csrf.spa())` for the API chain. Implementation uses `.csrf(csrf -> csrf.ignoringRequestMatchers("/api/auth/**"))`. Auth endpoints work, but future S-01 endpoints (POST /api/reports) will hit the default CSRF filter with no token mechanism — clients have no way to obtain a CSRF token, so all mutating API requests will get 403.
- **Fix**: Add `.csrf(csrf -> csrf.spa().ignoringRequestMatchers("/api/auth/**"))` before S-01 implementation. This restores the SPA CSRF token cookie while keeping auth endpoints exempt.
- **Decision**: FIXED — `.spa().ignoringRequestMatchers("/api/auth/**")` applied to SecurityConfig.java API chain. Verified with 3 new tests in SecurityConfigTest: `csrfEnforced_onNonAuthApiPath_returns403`, `csrfNotEnforced_onAuthApiPath`, `csrfSpaCookie_isSetOnResponse`. All 17 tests pass.

### F2 — Email uniqueness race produces 500 instead of 409

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Blind Spots
- **Location**: AuthService.java:33–37, AuthController.java:63–77
- **Detail**: existsByEmail() + save() has a TOCTOU race. The DB unique constraint prevents duplicates, but DataIntegrityViolationException is unhandled — concurrent duplicate registration returns 500 instead of 409. Low probability for MVP but easy to fix.
- **Fix**: Catch DataIntegrityViolationException in AuthController.register() and return 409.
- **Decision**: FIXED — DataIntegrityViolationException caught in AuthService.register() around save() and translated to EmailAlreadyExistsException (controller already maps it to 409; keeps HTTP mapping single-sourced). Verified with new unit test AuthServiceTest.registerWhenConcurrentDuplicateHitsUniqueConstraint_throwsEmailAlreadyExists — passes.

### F3 — Email format validation added but not tested

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Blind Spots
- **Location**: AuthService.java:27–29, AuthControllerTest.java
- **Detail**: Email format validation was added during Phase 3 but no test verifies that an invalid email (e.g. "notanemail") returns 400 with "Invalid email format". Add one test case.
- **Fix**: Add test case `registerWithInvalidEmail_returns400()` to AuthControllerTest.
- **Decision**: FIXED — registerWithInvalidEmail_returns400() added to AuthControllerTest; asserts 400 + "Invalid email format". Full AuthControllerTest suite (8 tests) passes. Note: on this machine Testcontainers needs `DOCKER_HOST=unix://$HOME/.rd/docker.sock` and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` (Rancher Desktop).

### F4 — Admin seeder checks by email, not by role

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Blind Spots
- **Location**: AdminSeeder.java:40
- **Detail**: Plan says "skips if any ADMIN role user already exists" but code checks existsByEmail(adminEmail). Changing the env var creates a second admin. Acceptable for solo-dev MVP.
- **Fix**: Either document this behavior or add existsByRole(Role) to UserRepository and check that instead.
- **Decision**: ACCEPTED — by-email check acceptable for solo-dev MVP; changing SEED_ADMIN_EMAIL intentionally creating a second admin is a known, tolerated behavior.
