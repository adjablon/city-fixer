# Auth and Role Scaffold — Plan Brief

> Full plan: `context/changes/auth-role-scaffold/plan.md`

## What & Why

Add authentication and role-based access control to CityFix — the foundation that every downstream slice depends on. Without this, there are no users, no roles, and no access control. The PRD requires that unauthenticated users have no access and that three roles (Resident, Staff, Admin) gate what each person can do. This slice delivers the full register → login → session → role-check loop.

## Starting Point

A minimal Spring Boot 4.0.6 scaffold: one entrypoint class, one health controller, no Spring Security dependency, no entities, no repositories, no frontend. PostgreSQL + JPA are declared in `pom.xml` but unused (no entities exist). CI/CD deploys to Azure on push to main. The `templates/` and `static/` directories are empty.

## Desired End State

A resident can register with email+password, log in, and see their profile via `/api/auth/me`. A Thymeleaf login/register UI works in the browser. Two Spring Security filter chains enforce auth: the API chain returns JSON 401 for unauthenticated requests, the web chain redirects to `/login`. The first admin account is seeded from environment variables on startup. Role annotations (`@PreAuthorize`) are enabled for downstream slices to use.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) |
| --- | --- | --- |
| Auth mechanism | Session-based (cookie) | Simplest setup with Spring Boot — no JWT complexity needed for a single-server MVP. |
| Role model | Enum column on User entity | PRD defines exactly three mutually exclusive roles — a join table is over-engineering. |
| Admin bootstrap | Env-var seed on startup | Follows the existing pattern of env-var config (DB creds); no credentials in source code. |
| OAuth | Deferred to post-MVP | Cuts significant scope; email+password satisfies FR-001's "email + password OR OAuth". |
| API style | REST JSON API | Clean separation; frontend can be built independently in S-01. |
| Password policy | 8+ characters, BCrypt | NIST recommends length over complexity; BCrypt's cost factor provides real security. |
| UI scope | Include Thymeleaf login/register pages | Makes F-01 end-to-end testable and sets up the template pattern for S-01/S-02. |
| Root path | Redirect to /login | PRD says "unauthenticated users: no access"; public landing page would conflict. |
| Security config | Two SecurityFilterChains | Standard Spring Security 7 pattern — API chain (JSON) and web chain (redirects) stay clean. |
| Testing depth | MockMvc integration tests | Catches auth misconfig and role enforcement bugs without needing a running database. |
| Login identifier | Email | One fewer field at registration; PRD already requires email. |
| /me endpoint | Included in F-01 | S-01 and S-02 need it immediately to render role-appropriate views. |

## Scope

**In scope:**
- Spring Security 7 + Thymeleaf dependencies
- User entity with Role enum (RESIDENT, STAFF, ADMIN), UserRepository
- Admin seed from env vars on startup
- Dual SecurityFilterChain (API JSON + web redirect)
- Registration (API + Thymeleaf), login (API + Thymeleaf), logout, /api/auth/me
- BCrypt password hashing, 8+ char validation
- MockMvc integration tests for auth flow and role enforcement

**Out of scope:**
- OAuth / social login
- Email verification, password reset
- Admin managing staff accounts (S-03)
- Report entities or map UI (S-01)
- Flyway/Liquibase migrations
- Rate limiting

## Architecture / Approach

Bottom-up build: User entity + Role enum → Spring Security config (two filter chains, UserDetailsService, BCrypt) → REST + Thymeleaf endpoints → integration tests. The API chain (`/api/**`) returns JSON and uses SPA-style CSRF (`.csrf(csrf -> csrf.spa())`). The web chain serves Thymeleaf pages with standard form CSRF. Registration always creates a RESIDENT; admin and staff accounts are seeded or managed via S-03.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Dependencies & User Entity | pom.xml deps, User/Role/Repository, admin seeder | Spring Security auto-config locks everything before Phase 2 configures it — expected, not a problem |
| 2. Security Config & Auth Service | Dual filter chains, UserDetailsService, JSON handlers, registration service | CSRF misconfiguration between API and web chains could cause silent 403s |
| 3. Auth Endpoints & Thymeleaf Pages | REST register/me endpoints, login/register HTML pages, logout | Thymeleaf + Spring Security integration (CSRF tokens, form actions) |
| 4. Tests & Verification | MockMvc integration tests, updated smoke test | Test dependency (`spring-boot-starter-security-test`) is a Boot 4 breaking change |

**Prerequisites:** Local PostgreSQL instance (or H2 for dev) for manual testing. Azure env vars for deployment.
**Estimated effort:** ~2-3 sessions across 4 phases.

## Open Risks & Assumptions

- Hibernate `ddl-auto=update` is used for table creation — acceptable for solo-dev MVP but should switch to Flyway before multi-developer work.
- Session storage is in-memory — fine for single-server MVP. If horizontal scaling is needed, add Spring Session + Redis.
- The CI pipeline currently runs with `-DskipTests` — Phase 4 tests will pass locally but won't run in CI until the pipeline is updated.

## Success Criteria (Summary)

- A resident can register, log in, and see their profile via both API and browser UI
- Unauthenticated requests to protected endpoints return 401 JSON (API) or redirect to login (web)
- All MockMvc tests pass: `./mvnw clean test`
