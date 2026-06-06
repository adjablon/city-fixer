<!-- PLAN-REVIEW-REPORT -->
# Plan Review: Auth and Role Scaffold

- **Plan**: context/changes/auth-role-scaffold/plan.md
- **Mode**: Deep
- **Date**: 2026-06-06
- **Verdict**: REVISE → SOUND (after fixes)
- **Findings**: 1 critical, 2 warnings, 1 observation

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| End-State Alignment | WARNING |
| Lean Execution | PASS |
| Architectural Fitness | WARNING |
| Blind Spots | WARNING |
| Plan Completeness | WARNING |

## Grounding

8/8 paths verified, 3/3 symbols verified, brief↔plan consistent. Deep verification: 4 Spring Security 7 / Boot 4 framework claims checked (3 confirmed, 1 flagged). Progress↔Phase: all 4 phases + 21 steps match.

## Findings

### F1 — formLogin not configured for email-based authentication

- **Severity**: ❌ CRITICAL
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: End-State Alignment / Plan Completeness
- **Location**: Phase 2 — Security Configuration (item 5) + Phase 3 — Login/Register templates
- **Detail**: Spring Security's formLogin reads `request.getParameter("username")` by default. Phase 3's templates specify "email + password fields", implying `<input name="email">`. Without `.usernameParameter("email")` on both formLogin configurations, login silently fails with 401.
- **Fix**: Add `.usernameParameter("email")` to both formLogin configurations in Phase 2.
- **Decision**: ACCEPTED — user noted the login form is defined in Phase 3; the implementer will align form field names with security config during template implementation.

### F2 — CustomUserDetailsService contract doesn't carry user ID

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Architectural Fitness
- **Location**: Phase 2 (CustomUserDetailsService) → Phase 3 (/me endpoint)
- **Detail**: Phase 2 originally specified standard UserDetails (username + authorities, no ID). Phase 3's /me endpoint returns {id, email, role}. The bridge was missing — S-01 and S-02 also need the current user's ID.
- **Fix A ⭐ Recommended**: Create a custom UserDetails wrapper in Phase 2.
- **Fix B**: Query UserRepository.findByEmail(principal.getName()) in controllers.
- **Decision**: FIXED via Fix A — added `CustomUserDetails` wrapper class (Phase 2, item 1) and updated `CustomUserDetailsService` (item 2) to return it.

### F3 — thymeleaf-extras-springsecurity6 artifact likely wrong for SS7

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Blind Spots
- **Location**: Phase 1 — Maven dependencies (item 1)
- **Detail**: The upstream GitHub repo (thymeleaf/thymeleaf-extras-springsecurity) was archived April 2026. The dialect was likely absorbed into Thymeleaf core or the Boot 4 starter. Other framework claims verified clean: `spring-boot-starter-security-test` ✓, `.csrf(csrf -> csrf.spa())` ✓, `PathPatternRequestMatcher` ✓.
- **Fix**: Added note to Phase 1 item 1 to verify artifact name against the Boot 4.0.6 BOM at implementation time.
- **Decision**: FIXED — verification note added to plan.

### F4 — No local development database dependency or setup

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 1 — Local dev properties (item 6)
- **Detail**: Plan mentioned "H2 or local PostgreSQL" without specifying. No Docker Compose or setup instructions existed.
- **Fix**: Added Docker Compose with PostgreSQL to Phase 1 (new item 6) and updated local dev properties (item 7) to point at the Docker instance.
- **Decision**: FIXED — local dev uses PostgreSQL via Docker Compose.