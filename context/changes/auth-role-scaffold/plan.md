# Auth and Role Scaffold Implementation Plan

## Overview

Add Spring Security 7 to CityFix with session-based authentication, a three-role enum model (RESIDENT, STAFF, ADMIN), email+password registration and login, and role-based access control. Delivered as REST JSON API endpoints (`/api/auth/**`) plus Thymeleaf login/register pages. This is foundation F-01 — every downstream slice (S-01 resident reports, S-02 staff triage, S-03 admin management) depends on it.

## Current State Analysis

The project is a minimal Spring Boot 4.0.6 scaffold with two Java classes (`CityFixApplication.java`, `HealthController.java`), one smoke test, PostgreSQL + JPA declared but no entities, and a working CI/CD pipeline to Azure. There is no Spring Security dependency, no user model, no authentication, and no frontend. The `templates/` and `static/` directories exist but are empty.

## Desired End State

After this plan is complete:

- A `User` entity with email, hashed password, and a `Role` enum (RESIDENT, STAFF, ADMIN) is persisted in PostgreSQL via JPA (Hibernate auto-DDL).
- Residents can register at `POST /api/auth/register` (JSON) or via a Thymeleaf form at `/register`.
- Users can log in at `POST /api/auth/login` (JSON, returns session cookie) or via a Thymeleaf form at `/login`.
- `GET /api/auth/me` returns the authenticated user's email, role, and id.
- Two `SecurityFilterChain` beans enforce: API chain returns JSON 401 for unauthenticated requests; web chain redirects to `/login`. Role annotations (`@PreAuthorize`) are enabled for downstream use.
- The first admin account is seeded on startup from `ADMIN_EMAIL` / `ADMIN_PASSWORD` environment variables.
- `GET /` redirects to `/login` (unauthenticated) or a future dashboard (authenticated).
- MockMvc integration tests verify registration, login, role enforcement, and unauthorized access.

**Verification**: Run `./mvnw clean test` — all tests pass. Start the app locally with a PostgreSQL instance, visit `/register`, create an account, log in at `/login`, and confirm `GET /api/auth/me` returns the user's profile. Confirm unauthenticated `GET /api/auth/me` returns 401 JSON.

### Key Discoveries:

- Spring Boot 4.0.6 ships Spring Security 7.0.x — lambda DSL is mandatory, `SecurityFilterChain` is the config pattern, `PathPatternRequestMatcher` replaces Ant/Mvc matchers.
- Spring Security 7 enforces CSRF for API endpoints by default. The `.csrf(csrf -> csrf.spa())` convenience method handles SPA-style CSRF (writes `XSRF-TOKEN` cookie readable by JS, resolves via `SpaCsrfTokenRequestHandler`).
- Test dependency is `spring-boot-starter-security-test` (not `spring-security-test` — breaking change from Boot 3.x).
- `hibernate.ddl-auto=update` in `application-azure.properties` means entities auto-create tables — no migration files needed for MVP.
- Package is `com.example.city_fix` (underscore, not hyphen).

## What We're NOT Doing

- **OAuth / social login** — deferred to post-MVP per decision. Email+password only.
- **Email verification** — not required by PRD. Registration is immediate.
- **Password reset / forgot password** — not in PRD scope for F-01.
- **Account deactivation by admin** — that's S-03 (admin manages staff). F-01 only seeds the first admin.
- **Report entities or map UI** — that's S-01.
- **Flyway/Liquibase migrations** — Hibernate auto-DDL (`update`) is sufficient for MVP with a single developer.
- **Rate limiting on auth endpoints** — post-MVP hardening.

## Implementation Approach

Build bottom-up: data model first (User entity + Role enum + repository), then security wiring (Spring Security config, UserDetailsService, password encoder), then the endpoints and pages that use them, and finally tests that verify the whole stack. Each phase produces a testable increment.

## Critical Implementation Details

**CSRF with dual chains**: The API chain must use `.csrf(csrf -> csrf.spa())` so the frontend can read the `XSRF-TOKEN` cookie and send it back as `X-XSRF-TOKEN` header on mutating requests. The web chain (Thymeleaf) uses Spring Security's default CSRF handling (hidden form field). Getting this wrong means all POST requests return 403.

**Spring Security 7 test dependency**: Tests that use `@WithMockUser` or `SecurityMockMvcRequestPostProcessors` require `spring-boot-starter-security-test`, not the older `spring-security-test`. Using the wrong artifact compiles but fails at runtime.

---

## Phase 1: Dependencies and User Entity

### Overview

Add Spring Security and Thymeleaf dependencies, create the User entity with Role enum, UserRepository, and the admin seed mechanism. After this phase, the data model is in place and the first admin account is created on startup.

### Changes Required:

#### 1. Maven dependencies

**File**: `pom.xml`

**Intent**: Add Spring Security, Thymeleaf, and their test counterparts so the project can authenticate users and render server-side pages.

**Contract**: Add four dependencies — `spring-boot-starter-security`, `spring-boot-starter-thymeleaf`, `thymeleaf-extras-springsecurity6` (for Thymeleaf security dialect — `sec:authorize` attributes — **verify artifact name against the Boot 4.0.6 BOM**: the upstream repo was archived April 2026 and the dialect may have been absorbed into Thymeleaf core or the starter; if so, drop this explicit dependency), and `spring-boot-starter-security-test` (test scope).

#### 2. Role enum

**File**: `src/main/java/com/example/city_fix/user/Role.java`

**Intent**: Define the three application roles as a Java enum. Used as the `role` column type on the User entity and mapped to Spring Security authorities.

**Contract**: Enum `Role` with values `RESIDENT`, `STAFF`, `ADMIN`.

#### 3. User entity

**File**: `src/main/java/com/example/city_fix/user/User.java`

**Intent**: JPA entity representing an authenticated user. Stores credentials and role. Email is the login identifier (unique constraint). Password is stored as a BCrypt hash.

**Contract**: `@Entity` class with fields: `Long id` (generated), `String email` (unique, not null), `String password` (not null, BCrypt hash), `Role role` (not null, `@Enumerated(STRING)`), `LocalDateTime createdAt`. Table name: `users` (avoid the reserved word `user` in PostgreSQL).

#### 4. User repository

**File**: `src/main/java/com/example/city_fix/user/UserRepository.java`

**Intent**: Spring Data JPA repository for User. Provides the query needed by UserDetailsService (find by email) and by registration (check email uniqueness).

**Contract**: `JpaRepository<User, Long>` with `Optional<User> findByEmail(String email)` and `boolean existsByEmail(String email)`.

#### 5. Admin seeder

**File**: `src/main/java/com/example/city_fix/config/AdminSeeder.java`

**Intent**: On application startup, check if an admin user exists. If not, and if `ADMIN_EMAIL` / `ADMIN_PASSWORD` env vars are set, create the admin account. Idempotent — skips if any ADMIN role user already exists. Must not log the password.

**Contract**: `ApplicationRunner` bean. Reads `admin.seed.email` and `admin.seed.password` properties (mapped from env vars via `application.properties`). Uses `PasswordEncoder` to hash the password before saving.

#### 6. Docker Compose for local PostgreSQL

**File**: `compose.yaml`

**Intent**: Provide a zero-config local PostgreSQL instance so developers can run `docker compose up -d` and then `./mvnw spring-boot:run` without installing PostgreSQL locally.

**Contract**: Single `postgres` service — image `postgres:17`, port `5432:5432`, env vars `POSTGRES_DB=cityfix`, `POSTGRES_USER=cityfix`, `POSTGRES_PASSWORD=cityfix`, volume for data persistence.

#### 7. Local dev properties

**File**: `src/main/resources/application.properties`

**Intent**: Add local development database configuration (pointing to the Docker PostgreSQL) and admin seed property mappings so the app can run locally without Azure env vars.

**Contract**: Add `spring.datasource.url=jdbc:postgresql://localhost:5432/cityfix`, `spring.datasource.username=cityfix`, `spring.datasource.password=cityfix`, `spring.jpa.hibernate.ddl-auto=update`. Add `admin.seed.email=${ADMIN_EMAIL:}`, `admin.seed.password=${ADMIN_PASSWORD:}` (empty defaults — seeder skips when blank). Keep existing actuator config.

### Success Criteria:

#### Automated Verification:

- Application compiles: `./mvnw compile`
- Application starts with Spring Security on the classpath (default security auto-config will lock everything down — that's expected and will be configured in Phase 2)

#### Manual Verification:

- With `ADMIN_EMAIL` and `ADMIN_PASSWORD` env vars set, the admin user is created on first startup (verify via logs or DB query)
- Without the env vars, the seeder skips silently (no error, no stack trace)

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 2: Security Configuration and Auth Service

### Overview

Configure Spring Security with two filter chains (API + web), implement `UserDetailsService`, `PasswordEncoder`, JSON auth handlers, and the registration service. After this phase, the security layer is fully wired — login and registration work at the API level.

### Changes Required:

#### 1. Custom UserDetails wrapper

**File**: `src/main/java/com/example/city_fix/auth/CustomUserDetails.java`

**Intent**: Wrap the User entity in a `UserDetails` implementation so that the authenticated user's `id`, `email`, and `role` are available via `@AuthenticationPrincipal` in any controller — without an extra DB query. S-01 and S-02 controllers will need the user's ID to associate reports.

**Contract**: Implements `UserDetails`. Constructor takes a `User` entity. Exposes `getId()`, `getEmail()`, `getRole()`. `getUsername()` returns the email. `getAuthorities()` returns `ROLE_<role.name()>`. `getPassword()` returns the hashed password.

#### 2. UserDetailsService implementation

**File**: `src/main/java/com/example/city_fix/auth/CustomUserDetailsService.java`

**Intent**: Bridge between Spring Security's authentication and the User entity. Loads a user by email and wraps it in `CustomUserDetails`.

**Contract**: Implements `UserDetailsService`. `loadUserByUsername(String email)` queries `UserRepository.findByEmail()`, throws `UsernameNotFoundException` if not found, returns a `CustomUserDetails` wrapping the found User entity.

#### 3. Auth service (registration)

**File**: `src/main/java/com/example/city_fix/auth/AuthService.java`

**Intent**: Business logic for user registration. Validates input, checks email uniqueness, hashes password, creates the user with RESIDENT role.

**Contract**: `register(String email, String password)` — throws if email is blank, password is under 8 characters, or email already exists. Returns the created User. All new registrations get `Role.RESIDENT`.

#### 4. JSON authentication handlers

**File**: `src/main/java/com/example/city_fix/config/SecurityConfig.java` (inner classes or separate files — implementer's choice)

**Intent**: Custom handlers that return JSON responses instead of HTTP redirects for the API filter chain. Three handlers needed: success (200 + user JSON), failure (401 + error JSON), and entry point (401 + "authentication required" JSON).

**Contract**:
- `AuthenticationSuccessHandler` — writes 200 JSON with `email`, `role`, and `id` fields.
- `AuthenticationFailureHandler` — writes 401 JSON with `message` field.
- `AuthenticationEntryPoint` — writes 401 JSON with `message` field. Used when an unauthenticated request hits a protected API endpoint.

#### 5. Security configuration

**File**: `src/main/java/com/example/city_fix/config/SecurityConfig.java`

**Intent**: Define the two `SecurityFilterChain` beans, `PasswordEncoder`, and enable method-level security (`@PreAuthorize`).

**Contract**:
- `@Order(1)` API chain: `securityMatcher("/api/**")`. Permits `/api/auth/register` and `/api/auth/login`. Protects everything else. Uses `formLogin` with `loginProcessingUrl("/api/auth/login")` and the JSON handlers. CSRF via `.csrf(csrf -> csrf.spa())`. Custom `AuthenticationEntryPoint` for JSON 401. Session creation: `IF_REQUIRED`. Logout at `POST /api/auth/logout`.
- `@Order(2)` web chain: no `securityMatcher` (catch-all). Permits `/login`, `/register`, `/css/**`, `/js/**`, actuator health. Uses `formLogin` with `loginPage("/login")` and `defaultSuccessUrl("/", true)`. Standard CSRF (Thymeleaf hidden field). Logout redirects to `/login?logout`.
- `PasswordEncoder` bean: `BCryptPasswordEncoder`.
- `@EnableMethodSecurity` on the config class.

#### 6. Remove/update HealthController

**File**: `src/main/java/com/example/city_fix/HealthController.java`

**Intent**: Replace the root endpoint. Authenticated GET `/` redirects to a placeholder dashboard (or simply shows "logged in" for now — S-01 will replace this with the map). Unauthenticated GET `/` is caught by the web security chain and redirected to `/login`.

**Contract**: Change the `@GetMapping("/")` to redirect to `/login` (or return a minimal authenticated landing page). Actuator `/health` stays public for Azure probes.

### Success Criteria:

#### Automated Verification:

- Application compiles: `./mvnw compile`
- Application starts without errors: `./mvnw spring-boot:run` (with local DB)

#### Manual Verification:

- `POST /api/auth/login` with valid credentials returns 200 JSON with user details and sets `JSESSIONID` cookie
- `POST /api/auth/login` with invalid credentials returns 401 JSON
- `GET /api/auth/me` without a session returns 401 JSON (not a redirect)
- `GET /` without a session redirects to `/login`
- Actuator `GET /actuator/health` is accessible without authentication

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 3: Auth Endpoints and Thymeleaf Pages

### Overview

Add the REST registration endpoint, the `/api/auth/me` endpoint, Thymeleaf login and register pages, and a logout flow. After this phase, a user can register, log in, see their profile, and log out — both via API and browser.

### Changes Required:

#### 1. Auth REST controller

**File**: `src/main/java/com/example/city_fix/auth/AuthController.java`

**Intent**: REST endpoints for registration and current-user lookup. Login is handled by Spring Security's `formLogin` filter (configured in Phase 2), not by a custom controller method.

**Contract**:
- `POST /api/auth/register` — accepts JSON `{email, password}`, calls `AuthService.register()`, returns 201 with `{id, email, role}`. Returns 400 on validation failure, 409 if email exists.
- `GET /api/auth/me` — returns 200 with `{id, email, role}` for the authenticated user. Spring Security handles 401 for unauthenticated requests (via the entry point configured in Phase 2).

#### 2. Auth web controller (Thymeleaf)

**File**: `src/main/java/com/example/city_fix/auth/AuthWebController.java`

**Intent**: Serve the Thymeleaf login and registration pages. Handle the registration form submission (POST) with redirect-after-POST.

**Contract**:
- `GET /login` — renders `login.html` template. Accepts optional `?error` and `?logout` query params for flash messages.
- `GET /register` — renders `register.html` template.
- `POST /register` — accepts form data (email + password), calls `AuthService.register()`, redirects to `/login?registered` on success. On failure, re-renders `register.html` with an error message.

#### 3. Login page template

**File**: `src/main/resources/templates/login.html`

**Intent**: Server-rendered login form that POSTs to Spring Security's login processing URL. Shows flash messages for errors, successful logout, and successful registration.

**Contract**: Thymeleaf template with email + password fields, CSRF token (auto-injected by Thymeleaf + Spring Security), form action pointing to the web chain's login processing URL. Link to `/register`. Minimal inline CSS (no external framework needed for F-01).

#### 4. Registration page template

**File**: `src/main/resources/templates/register.html`

**Intent**: Server-rendered registration form with email, password, and password confirmation fields.

**Contract**: Thymeleaf template with email + password + confirm-password fields, CSRF token, form action `POST /register`. Client-side password match check. Link to `/login`. Shows validation errors returned by the controller.

### Success Criteria:

#### Automated Verification:

- Application compiles: `./mvnw compile`
- Application starts cleanly: `./mvnw spring-boot:run`

#### Manual Verification:

- Visit `/register` in a browser, fill in email + password, submit — redirected to `/login?registered` with success message
- Visit `/login`, enter credentials, submit — redirected to `/` (authenticated landing)
- `GET /api/auth/me` with session cookie returns the user's profile JSON
- `POST /api/auth/register` with curl/Postman creates a new user and returns 201 JSON
- `POST /api/auth/register` with an existing email returns 409
- `POST /api/auth/register` with password < 8 chars returns 400
- Logout works from both web UI and API

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 4: Tests and Verification

### Overview

Add MockMvc integration tests covering registration, login, role enforcement, and unauthorized access. Update the existing smoke test to work with Spring Security on the classpath. After this phase, the auth scaffold is fully verified and the CI pipeline passes.

### Changes Required:

#### 1. Auth API integration tests

**File**: `src/test/java/com/example/city_fix/auth/AuthControllerTest.java`

**Intent**: Test the REST auth endpoints with MockMvc. Verify registration creates a user, login returns a session, invalid credentials are rejected, and `/api/auth/me` returns the current user or 401.

**Contract**: `@WebMvcTest(AuthController.class)` (or `@SpringBootTest` with `@AutoConfigureMockMvc` if service layer wiring is needed). Test cases:
- Register with valid data → 201
- Register with duplicate email → 409
- Register with short password → 400
- Login with valid credentials → 200 + session
- Login with wrong password → 401
- `/api/auth/me` authenticated → 200 with user data
- `/api/auth/me` unauthenticated → 401

#### 2. Security enforcement tests

**File**: `src/test/java/com/example/city_fix/config/SecurityConfigTest.java`

**Intent**: Verify that the security chains enforce role-based access correctly. Protected API endpoints reject unauthenticated requests with 401 JSON (not redirect). Public endpoints (register, login, actuator health) are accessible.

**Contract**: `@SpringBootTest` with `@AutoConfigureMockMvc`. Use `@WithMockUser(roles = "RESIDENT")`, `@WithMockUser(roles = "ADMIN")`, and unauthenticated requests to verify:
- Public paths (`/api/auth/register`, `/api/auth/login`, `/login`, `/register`, `/actuator/health`) are accessible without auth
- Protected API paths return 401 JSON when unauthenticated
- Web paths redirect to `/login` when unauthenticated

#### 3. Update existing smoke test

**File**: `src/test/java/com/example/city_fix/CityFixApplicationTests.java`

**Intent**: The existing `contextLoads` test may need adjustment now that Spring Security is on the classpath (security auto-config changes the application context). Ensure it still passes.

**Contract**: If the test fails due to security auto-config, add the necessary test properties or mock beans. The test should verify that the full application context loads successfully with security configured.

### Success Criteria:

#### Automated Verification:

- All tests pass: `./mvnw clean test`
- Application compiles and packages: `./mvnw clean package`

#### Manual Verification:

- CI pipeline passes on push (tests run in `./mvnw clean package` in deploy.yml — note: currently `-DskipTests`, so decide whether to enable tests in CI as part of this phase or defer)
- Full end-to-end walkthrough: register → login → check /api/auth/me → logout → verify 401

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Testing Strategy

### Unit Tests:

- `AuthService.register()` — valid input creates user, duplicate email throws, short password throws
- `AdminSeeder` — seeds when no admin exists and env vars are set, skips when admin exists or env vars are blank

### Integration Tests:

- Full auth flow via MockMvc: register → login → access protected endpoint → logout → verify 401
- Role enforcement: RESIDENT cannot access ADMIN-only endpoints (prepared for S-03)
- CSRF enforcement: mutating API requests without XSRF-TOKEN header are rejected with 403

### Manual Testing Steps:

1. Start app locally with PostgreSQL and `ADMIN_EMAIL`/`ADMIN_PASSWORD` set
2. Verify admin user was created (check logs or DB)
3. Open browser, navigate to `/register`, create a resident account
4. Navigate to `/login`, log in with the new account
5. Open browser devtools, verify `JSESSIONID` cookie is set
6. Hit `GET /api/auth/me` — verify JSON response with user profile
7. Hit `POST /api/auth/logout` — verify session is invalidated
8. Hit `GET /api/auth/me` again — verify 401 JSON response
9. Log in as admin (seeded credentials) and verify admin role in `/api/auth/me` response

## Performance Considerations

No performance concerns for F-01. BCrypt hashing has a deliberate cost factor (default strength 10, ~100ms per hash) which is appropriate for auth — it's a feature, not a bug. Session storage is in-memory (Spring default) which is fine for a single-server MVP.

## Migration Notes

No data migration needed. This is the first entity in the system. Hibernate `ddl-auto=update` will create the `users` table on first startup. When the project grows beyond MVP, consider switching to Flyway for versioned migrations.

## References

- Roadmap item: `context/foundation/roadmap.md` — F-01
- PRD: `context/foundation/prd.md` — FR-001, FR-002, Access Control
- Tech stack: `context/foundation/tech-stack.md` — Spring Boot, `has_auth: true`
- Spring Security 7 docs: `https://docs.spring.io/spring-security/reference/`
- Spring Security 7 CSRF SPA: `https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Dependencies and User Entity

#### Automated

- [x] 1.1 Application compiles with new dependencies: `./mvnw compile` — 4485944
- [x] 1.2 Application starts with Spring Security on classpath — 4485944

#### Manual

- [x] 1.3 Admin user seeded on startup with ADMIN_EMAIL/ADMIN_PASSWORD env vars — 4485944
- [x] 1.4 Seeder skips silently without env vars — 4485944

### Phase 2: Security Configuration and Auth Service

#### Automated

- [x] 2.1 Application compiles: `./mvnw compile`
- [x] 2.2 Application starts without errors: `./mvnw spring-boot:run`

#### Manual

- [x] 2.3 POST /api/auth/login with valid creds returns 200 JSON + JSESSIONID
- [x] 2.4 POST /api/auth/login with invalid creds returns 401 JSON
- [x] 2.5 GET /api/auth/me without session returns 401 JSON
- [x] 2.6 GET / without session redirects to /login
- [x] 2.7 GET /actuator/health accessible without auth

### Phase 3: Auth Endpoints and Thymeleaf Pages

#### Automated

- [ ] 3.1 Application compiles: `./mvnw compile`
- [ ] 3.2 Application starts cleanly: `./mvnw spring-boot:run`

#### Manual

- [ ] 3.3 Browser registration flow works (register → redirect to login)
- [ ] 3.4 Browser login flow works (login → authenticated landing)
- [ ] 3.5 GET /api/auth/me with session returns user profile JSON
- [ ] 3.6 POST /api/auth/register via curl creates user (201)
- [ ] 3.7 POST /api/auth/register with duplicate email returns 409
- [ ] 3.8 POST /api/auth/register with short password returns 400
- [ ] 3.9 Logout works from both web UI and API

### Phase 4: Tests and Verification

#### Automated

- [ ] 4.1 All tests pass: `./mvnw clean test`
- [ ] 4.2 Application packages: `./mvnw clean package`

#### Manual

- [ ] 4.3 Full end-to-end walkthrough: register → login → /api/auth/me → logout → 401
