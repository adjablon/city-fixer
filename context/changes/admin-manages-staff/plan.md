# Admin Manages Staff Accounts — Implementation Plan

## Overview

Roadmap slice S-03 (PRD FR-002, the final slice). Gives ADMIN a first-class account-management surface at `/admin/users`: create office-staff accounts, and deactivate or reactivate STAFF and RESIDENT accounts. Deactivation is a real revocation, not a label — it blocks future logins through `UserDetails.isEnabled()` **and** expires the account's live session through Spring Security's `SessionRegistry`.

The slice also retires the `staff.seed.*` bootstrap that S-02 introduced as a deliberate stopgap, and closes PRD Open Question #2 by keeping `admin.seed.*` as the admin-provisioning mechanism — and making it actually exist in the azure profile, where it is currently missing.

## Current State Analysis

- **`User` is immutable and has no activation state.** `src/main/java/com/example/city_fix/user/User.java:44-62` exposes getters only; the sole constructor is `User(email, password, role)`. There is no `active`/`enabled` column on the `users` table.
- **`CustomUserDetails` overrides none of the account-status methods.** `src/main/java/com/example/city_fix/auth/CustomUserDetails.java:37-50` implements only `getAuthorities`, `getPassword`, `getUsername`. `isEnabled()`, `isAccountNonLocked()`, `isAccountNonExpired()`, `isCredentialsNonExpired()` all inherit `default true`. A flag on the entity changes nothing about login until `isEnabled()` is wired.
- **There is no session infrastructure of any kind.** `SecurityConfig` has no `sessionManagement(...)` block on either chain; there is no `SessionRegistry`, no `HttpSessionEventPublisher`, no Spring Session dependency, and no `server.servlet.session.*` / `spring.session.*` property. Sessions are in-memory Tomcat `HttpSession` on framework defaults.
- **Two filter chains.** `src/main/java/com/example/city_fix/config/SecurityConfig.java:29` `apiSecurityFilterChain` `@Order(1)` on `securityMatcher("/api/**")`; `:65` `webSecurityFilterChain` `@Order(2)` catch-all with `formLogin`. Authorization is done by path matcher in one place, above `anyRequest()` — `:75` is the `/staff/**` precedent, and its comment states the convention.
- **Two different login paths.** The browser logs in through `formLogin` (`SecurityConfig:78-83`; `AuthWebController` serves only `GET /login`, so `POST /login` is the filter's). The API — and every integration test — logs in through the hand-rolled `POST /api/auth/login` at `AuthController.java:37-68`.
- **`AuthController.login` catches only `BadCredentialsException`** (`:62`). Any other `AuthenticationException` propagates out of the controller.
- **`AuthService.register` hard-codes `Role.RESIDENT`** (`:38`). No existing code path creates a STAFF user except `StaffSeeder`.
- **No admin surface exists.** No `/admin/**` matcher, no `AdminController`, no admin template. `UserRepository` has exactly `findByEmail` and `existsByEmail` — no `findByRole`, no paging.
- **`StaffSeeder` is explicitly short-lived.** Its javadoc names this change as the thing that supersedes it.
- **`admin.seed.*` is absent from `application-azure.properties`.** The azure profile declares only `staff.seed.*` (`:23-24`). Production has no admin-bootstrap mechanism at all today.
- **`ddl-auto=update` on both profiles** (`application.properties:7`, `application-azure.properties:9`) with a populated `users` table in local and azure environments.

## Desired End State

An ADMIN signs in, follows a "Manage accounts" link from the home page, and sees one table of every STAFF and RESIDENT account with email, role, status and creation date. They can create a staff account by entering an email and a password. They can deactivate any row, and reactivate a deactivated one. A user who is deactivated while logged in finds their next request has dropped them back at the login page, and cannot log in again — at the login form they see the project's existing generic "Invalid email or password." ADMIN rows are neither listed nor actionable, so no admin can lock out themselves or another admin.

Verification: the automated suite proves each of those claims, and `\d users` on the local database shows the new column present and backfilled. The change closes on local verification; applying the same backfill to azure is a deploy-time follow-up recorded in Migration Notes.

### Key Discoveries:

- **The `SessionRegistry` will not see API-created sessions without explicit work.** Spring Security populates the registry from `RegisterSessionAuthenticationStrategy`, which is invoked by the `formLogin` filter's `SessionAuthenticationStrategy`. `AuthController.java:37-68` authenticates manually and invokes no strategy at all, so sessions created through `POST /api/auth/login` would never be registered. Because that endpoint is how *every* integration test obtains a session (`report/StaffReportControllerTest.java:213-240`), eviction would look broken in tests while working in the browser. This is the exact follow-on risk F-01's impl review predicted: "manually re-add protections formLogin gives for free … be alert for others, e.g. concurrent-session control later".
- **`DisabledException` is not a `BadCredentialsException`.** `AbstractUserDetailsAuthenticationProvider`'s pre-authentication checks throw `DisabledException` when `isEnabled()` is false. `AuthController.java:62` catches only `BadCredentialsException`, so wiring `isEnabled()` without widening that catch turns a deactivated API login into a 500. The `formLogin` path needs no change — `SimpleUrlAuthenticationFailureHandler` handles every `AuthenticationException` alike and redirects to `/login?error`, which already renders the generic message (`AuthWebController.java:22-24`).
- **`ddl-auto=update` never alters an existing column and its failures are silent** (`context/foundation/lessons.md:12-17`). A `NOT NULL` column added to the populated `users` table fails; the column must arrive nullable and be backfilled deliberately, with the result verified against the live schema rather than inferred from a green boot.
- **Every `User` write path must normalise email** with `trim().toLowerCase(Locale.ROOT)` before the uniqueness check and before the write (`context/foundation/lessons.md:19-24`, which names "the admin-managed staff accounts coming in S-03" explicitly). `CustomUserDetailsService.java:21` lowercases at lookup, so a mixed-case row can never authenticate.
- **Matcher ordering is load-bearing.** `/admin/**` must sit above `anyRequest().authenticated()` in the web chain or it is dead code and every authenticated user reaches the admin surface (`SecurityConfig.java:72-74`).
- **Nav links must carry the same expression as the matcher.** S-02's impl review F1 flagged a home-page link that survived a new authorization rule and 403'd for some roles. The `sec:authorize` expression and the `requestMatchers` rule are added in the same commit and must read identically.
- **Templates are standalone.** No layout dialect, no fragments, no app stylesheet — every page repeats its own `<head>` and inline `<style>` reusing `system-ui`, a centred container and `#2563eb` (`templates/staff-report-map.html`, `templates/register.html`).
- **`@WithMockUser` is unusable on routes taking `@AuthenticationPrincipal CustomUserDetails`** — it builds a plain `User` and the principal resolves to null. Integration tests persist the row, log in through `POST /api/auth/login`, and reuse the returned `MockHttpSession`.
- **Test classes must end in `Test`.** Failsafe is not wired; a `*IT.java` class would silently never run.

## What We're NOT Doing

- **No admin self-service for ADMIN accounts.** Admins are not listed and cannot be created, deactivated or reactivated through the UI. Admin provisioning stays on `admin.seed.*` env vars.
- **No password reset, no forced password change, no "must change on first login" flag.** The admin sets the initial password and communicates it out-of-band.
- **No email or notification of any kind** — PRD Non-Goal #1.
- **No account deletion.** Deactivation is the only revocation; `Report.reporter` makes deletion unsafe.
- **No audit trail** — no `deactivated_at`, no "deactivated by" attribution.
- **No paging, search or sort on the account list.** One flat list at MVP data volume; the unbounded-growth limit is accepted and recorded below.
- **No changes to the staff triage surface from S-02.** Reports by deactivated residents stay exactly as they are, and the staff views are not told the reporter is inactive.
- **No Spring Session, no distributed session store.** The registry is in-memory and per-instance; see Migration Notes.
- **No `AccessDeniedHandler` or custom `error.html`.** A 403 rendering Boot's Whitelabel page is the established, accepted behaviour.
- **No refactor of `AdminSeeder`.** It keeps its current shape and its passing test.

## Implementation Approach

Build outward from the data, one mechanism at a time, so that each phase leaves the system in a coherent state and no phase mixes a security mechanism with a feature surface.

Phase 1 makes deactivation *mean* something — the column, the mutators, `isEnabled()`, and the exception handling that makes a disabled login look like a normal failed login on both paths. At the end of Phase 1 a row flipped by hand in psql can no longer log in.

Phase 2 adds the session-eviction mechanism on its own, including the explicit session registration that the hand-rolled API login requires. At the end of Phase 2 a row flipped by hand in psql is also thrown out of its live session. Keeping this separate from Phase 1 matters because it is the highest-risk part of the slice and the one with a documented history of drift.

Phase 3 adds the service and controller that let an admin do what psql could. Phase 4 adds the UI and retires the superseded bootstrap. Phase 5 puts an assertion behind every cross-role claim.

Deviating from the planned security mechanism mid-flight is what produced F-01's only CRITICAL finding. If `SessionRegistry` proves wrong during Phase 2, raise an addendum rather than improvising a replacement.

## Critical Implementation Details

**Timing & lifecycle — session registration on the API login path.** `RegisterSessionAuthenticationStrategy` is wired into the `formLogin` filter, not into `authenticationManager.authenticate(...)`. `AuthController.login` must therefore register its session with the `SessionRegistry` itself, immediately after `request.changeSessionId()` and before the response is built — registering before the id rotation would record the pre-rotation id and leave the real session invisible to eviction. The ordering constraint is the reason this is called out rather than left to the implementer.

**State sequencing — deactivate then evict, in that order.** The persisted flag is the durable guarantee and session expiry is the immediate one. Write and commit the flag first, then expire sessions. Reversing the order leaves a window in which the user is evicted but could log straight back in.

**Debug & observability.** `SessionRegistry.getAllSessions(principal, false)` keys on the principal object, so `CustomUserDetails` needs value-based `equals`/`hashCode` (currently inherited from `Object`) or the lookup silently returns an empty list and eviction becomes a no-op that logs nothing. This is the single most likely way for Phase 2 to appear to work while doing nothing.

---

## Phase 1: Deactivation flag and login blocking

### Overview

Adds activation state to the `User` entity and makes it enforce itself at authentication time. No user-visible surface yet and no admin can set the flag — it is flipped by hand in psql to verify. At the end of this phase a deactivated account cannot log in through either path.

### Changes Required:

#### 1. User entity

**File**: `src/main/java/com/example/city_fix/user/User.java`

**Intent**: Give the entity activation state and the two mutators that change it, breaking the current getters-only immutability in exactly one controlled place.

**Contract**: New field mapped to a **nullable** `active` boolean column — nullable is a hard requirement, not a preference, because `ddl-auto=update` cannot add a `NOT NULL` column to the populated `users` table. Expose `isActive()` treating `null` as active so rows predating the backfill behave correctly, plus `activate()` and `deactivate()`. The constructor leaves new users active. `createdAt` stays `updatable = false`.

#### 2. User entity tests

**File**: `src/test/java/com/example/city_fix/user/UserTest.java` (new)

**Intent**: Pin the null-means-active tolerance and the mutator behaviour, since both are easy to regress and neither is obvious from the field declaration.

**Contract**: Plain JUnit + AssertJ, no Spring context. Covers: a newly constructed user is active; `deactivate()` then `isActive()` is false; `activate()` restores it; a user whose field is null reads as active.

#### 3. CustomUserDetails — wire isEnabled

**File**: `src/main/java/com/example/city_fix/auth/CustomUserDetails.java`

**Intent**: Make the entity flag reach Spring Security, which is what actually blocks the login.

**Contract**: Capture the active state in the constructor alongside the existing fields and override `isEnabled()` to return it. Also add value-based `equals`/`hashCode` keyed on the user id — required by Phase 2's `SessionRegistry` lookup, added here because it belongs to this class. The other three status methods stay on their defaults.

#### 4. AuthController — handle the disabled login

**File**: `src/main/java/com/example/city_fix/auth/AuthController.java`

**Intent**: Stop a deactivated API login from becoming a 500, and keep its response indistinguishable from a wrong password.

**Contract**: Widen the `catch` at `:62` from `BadCredentialsException` to `AuthenticationException` so `DisabledException` is included, returning the unchanged 401 `{"message": "Invalid email or password"}`. A comment states that the generic body is deliberate — it keeps the endpoint from revealing which addresses exist but are disabled. The `formLogin` path needs no change.

#### 5. Schema backfill

**File**: `context/changes/admin-manages-staff/plan.md` (this document, Migration Notes) — no code file

**Intent**: `ddl-auto=update` adds the nullable column but leaves every existing row null. Record the statement and the verification, per `lessons.md:12-17`.

**Contract**: `UPDATE users SET active = true WHERE active IS NULL;` run against local and azure. Verification is `\d users` showing the column, plus a count of null rows returning zero. The code's null-means-active tolerance means a missed backfill is not a correctness failure, only an untidy one.

#### 6. Login-blocking tests (added during implementation)

**File**: `src/test/java/com/example/city_fix/user/AccountDeactivationTest.java` (new)

**Intent**: Turn this phase's riskiest manual check into permanent evidence. Added because no local CityFix database exists to verify against, and because S-02's impl review (F6) called out manual criteria ticked without observable evidence.

**Contract**: `@SpringBootTest @AutoConfigureMockMvc extends TestcontainersConfig`, the class Phase 5 later extends with eviction cases. Covers: a deactivated account is refused on the API path with 401 and the generic body — a 500 is the regression it exists to catch; refused on the form path with a redirect to `/login?error`; an active account still logs in (control, proving the flag is the cause); a reactivated account logs in again. Verified by negative control — reverting the widened catch in change 4 makes exactly one case fail.

> **Deviation from plan, approved by the user.** Manual items 1.5 and 1.6 required a local database that does not exist on this machine (`localhost:5432/cityfix` unreachable, no container, brew service stopped). Item 1.7 was automated as change 6 above; 1.5 and 1.6 moved to the deploy-time checklist in Migration Notes, where a real persistent database exists. Phase 1 therefore closes with no manual items.

### Success Criteria:

#### Automated Verification:

- Compiles: `./mvnw compile`
- Entity tests pass: `./mvnw test -Dtest=UserTest`
- Existing auth tests still pass: `./mvnw test -Dtest=AuthControllerTest,AuthServiceTest,SecurityConfigTest`
- Deactivation login-blocking tests pass: `./mvnw test -Dtest=AccountDeactivationTest`
- Full suite passes: `./mvnw test`

#### Manual Verification:

This phase has no manual verification items — its one behavioural claim is automated as change 6, and the two schema checks moved to the deploy-time checklist in Migration Notes for want of a local database. See the deviation note above.

**Implementation Note**: This phase closes on automated verification alone.

---

## Phase 2: Session eviction infrastructure

### Overview

Adds Spring Security's session registry so a deactivated account's live session dies on its next request, and closes the gap that the hand-rolled API login opens in it. The highest-risk phase in the slice: it is net-new cross-cutting security infrastructure, and F-01's only CRITICAL came from improvising in this exact area. Still no user-visible surface — verification is by flipping a row in psql.

### Changes Required:

#### 1. SecurityConfig — registry and session management

**File**: `src/main/java/com/example/city_fix/config/SecurityConfig.java`

**Intent**: Publish the beans that make session tracking possible and switch it on for both chains, so eviction works regardless of which one the victim is using.

**Contract**: A `SessionRegistry` bean (`SessionRegistryImpl`) and an `HttpSessionEventPublisher` bean — without the publisher, `sessionDestroyed` never fires and the registry leaks entries for every logout. Add `sessionManagement(...)` to **both** chains configuring `maximumSessions(-1)` against that registry: `-1` means unlimited concurrent sessions, so this is not concurrency control; it is the supported way to get `RegisterSessionAuthenticationStrategy` and `ConcurrentSessionFilter` installed. The web chain sets the expired-session URL to the login page so an evicted browser lands on the generic login form. A comment records that `-1` is deliberate and that the registry is in-memory and per-instance.

#### 2. AuthController — register the API session

**File**: `src/main/java/com/example/city_fix/auth/AuthController.java`

**Intent**: Close the gap described in Key Discoveries — manual authentication invokes no session-authentication strategy, so without this the registry never learns about API sessions and eviction silently no-ops in every integration test.

**Contract**: Inject the `SessionRegistry` and register the session against the authenticated principal after `request.changeSessionId()` and before building the response. Ordering is load-bearing: registering before the rotation records a session id that no longer exists. A comment states why this is here and names the `formLogin` strategy it is standing in for.

#### 3. Session eviction service

**File**: `src/main/java/com/example/city_fix/user/UserSessionService.java` (new)

**Intent**: Give the admin service one call that ends every live session for a user, so the eviction mechanism has a single owner rather than being inlined at a call site.

**Contract**: A `@Service` over `SessionRegistry` with one method taking the target user and expiring all of that principal's non-expired sessions. Looks sessions up via the registry's principal-keyed accessor — which is why Phase 1 added `equals`/`hashCode` to `CustomUserDetails`. Logs at info how many sessions were expired for which email; a zero count is normal (the user simply was not logged in), not a warning. The method is package-visible to the admin service where practical, so it does not become a public capability with no second caller — S-01 F5 and S-02 F5 both flagged that pattern.

#### 4. Session eviction tests

**File**: `src/test/java/com/example/city_fix/user/UserSessionServiceTest.java` (new)

**Intent**: Prove the expiry call reaches the right sessions without standing up a servlet container.

**Contract**: Mockito. Covers: all of a principal's sessions are expired; already-expired sessions are not re-expired; a principal with no sessions is a no-op that does not throw.

#### 5. Eviction integration tests (added during implementation)

**File**: `src/test/java/com/example/city_fix/user/AccountDeactivationTest.java`

**Intent**: Change 4's tests mock the registry, so they cannot detect the very gap this phase exists to close — a missing session registration. Only the real registry can.

**Contract**: Extends the Phase 1 class with: the API login path registers its session under the expected id; expiring a deactivated user's sessions ends a live API session (401 on the next request); the same for a live browser session (redirect to `/login?expired`, covering the web chain's separate `sessionManagement` block); `formLogin` registration still happens via `RegisterSessionAuthenticationStrategy`; evicting one user leaves another's session alone. Verified by negative control — deleting the `registerNewSession` call from change 2 fails exactly the registration and API-eviction cases, the second with `expected:<401> but was:<200>`, which is the silent no-op the plan predicted.

> **Deviation from plan, approved in Phase 1 for the same reason.** Manual items 2.5 and 2.7 are automated by change 5. Item 2.6 (logout leaves no stale registry entry) is **not automatable in this harness** — verified by probe during implementation: `HttpSessionEventPublisher` needs a real servlet container to fire `sessionDestroyed`, and `MockHttpSession.invalidate()` does not publish container lifecycle events, so a registry entry survives a MockMvc logout. A test pins that the listener bean is registered; the behaviour itself moves to the deploy-time checklist in Migration Notes.

### Success Criteria:

#### Automated Verification:

- Compiles: `./mvnw compile`
- Session service tests pass: `./mvnw test -Dtest=UserSessionServiceTest`
- Eviction integration tests pass: `./mvnw test -Dtest=AccountDeactivationTest`
- Existing auth and security tests still pass: `./mvnw test -Dtest=AuthControllerTest,SecurityConfigTest`
- Full suite passes: `./mvnw test`

#### Manual Verification:

This phase has no manual verification items — 2.5 and 2.7 are automated as change 5, and 2.6 is not observable through MockMvc and moved to the deploy-time checklist. See the deviation note above.

**Implementation Note**: This phase closes on automated verification alone.

---

## Phase 3: Admin service and controller

### Overview

Adds the server side of the admin surface — listing accounts, creating staff, and toggling activation — plus the `/admin/**` authorization rule. Routes exist and are gated, but there is no UI yet; verification is by curl or MockMvc.

### Changes Required:

#### 1. UserRepository — role-scoped lookup

**File**: `src/main/java/com/example/city_fix/user/UserRepository.java`

**Intent**: Let the service fetch exactly the accounts an admin may act on, rather than fetching everything and filtering in Java.

**Contract**: A derived query returning users whose role is in a given set, ordered by creation date. The ADMIN exclusion is expressed in the query so the "admins are never listed" rule holds at the data layer, not only in a template.

#### 2. Admin user service

**File**: `src/main/java/com/example/city_fix/user/AdminUserService.java` (new)

**Intent**: Own the three admin operations and, crucially, the rule that ADMIN rows are untouchable — enforced here rather than in the controller, so no future route can bypass it.

**Contract**: A `@Service` over `UserRepository`, `PasswordEncoder` and `UserSessionService` with three operations:
- *list* — all STAFF and RESIDENT accounts in creation order.
- *create staff* — normalises the email with `trim().toLowerCase(Locale.ROOT)` **before** the uniqueness check and before the write (`lessons.md:19-24`), encodes the password, saves with `Role.STAFF`, and catches `DataIntegrityViolationException` to rethrow as the existing `AuthService.EmailAlreadyExistsException` — the same race-handling shape `AuthService.java:43-48` already uses.
- *set active* — loads the target, **refuses an ADMIN target** with a dedicated exception, applies the flag, saves, and then (on deactivation only) calls `UserSessionService` to expire sessions. Order is load-bearing: persist first, evict second.
A nested exception type for the admin-target refusal, following the `AuthService.EmailAlreadyExistsException` precedent. Not-found gets its own nested exception.

#### 3. Admin view model

**File**: `src/main/java/com/example/city_fix/user/AccountRow.java` (new)

**Intent**: Hand the template a flat row rather than the entity, so the password hash never reaches the view layer.

**Contract**: A record carrying id, email, role label, active flag and creation instant, with a static factory from `User`. No password field.

#### 4. Create-staff form DTO

**File**: `src/main/java/com/example/city_fix/user/CreateStaffForm.java` (new)

**Intent**: Typed, validated request binding — `lessons.md:5-10` forbids raw `Map` DTOs in controllers.

**Contract**: A record with email and password carrying the same Bean Validation annotations as `RegisterRequest.java:8-16` — `@NotBlank` plus the project's email `@Pattern`, and `@NotNull` plus `@Size(min = 8)`. Reusing the identical constraints keeps admin-created and self-registered accounts held to one standard.

#### 5. Admin controller

**File**: `src/main/java/com/example/city_fix/user/AdminUserController.java` (new)

**Intent**: Expose the three operations as form-driven MVC routes following `StaffReportController`'s shape.

**Contract**: `@Controller @RequestMapping("/admin/users")` with a class javadoc stating — as `StaffReportController.java:28-31` does — that authorization is the `/admin/**` matcher in `SecurityConfig`, not an annotation here, so every future route on this controller is gated by construction. Routes: `GET` list; `GET /new` create form; `POST` create, re-rendering the form with errors on a binding failure and redirecting with a flash message on success; `POST /{id}/active` toggle, redirecting to the list with a flash message. All writes are POST-redirect-GET. A `DateTimeFormatter` is passed into the model because `thymeleaf-extras-java8time` is absent (`StaffReportController.java:38-40`). Controller-scoped `@ExceptionHandler`s for the not-found and admin-target exceptions — the project has no `@ControllerAdvice` and both existing controllers handle locally.

#### 6. SecurityConfig — the /admin/** matcher

**File**: `src/main/java/com/example/city_fix/config/SecurityConfig.java`

**Intent**: Gate the whole admin surface in one place.

**Contract**: `.requestMatchers("/admin/**").hasRole("ADMIN")` in the web chain, above `anyRequest()` and alongside the `/staff/**` rule. ADMIN only — unlike `/staff/**`, STAFF is deliberately excluded, and the comment says so to pre-empt the reasonable assumption that the two rules should mirror each other. CSRF stays on for this chain; the forms get their hidden field from `th:action`.

#### 7. Service tests

**File**: `src/test/java/com/example/city_fix/user/AdminUserServiceTest.java` (new)

**Intent**: Cover the business rules that the controller and template cannot be trusted to enforce.

**Contract**: Mockito with `ArgumentCaptor`. Covers: create-staff lowercases and trims a mixed-case email before both the existence check and the save; create-staff saves with `Role.STAFF` and an encoded password; a duplicate email raises `EmailAlreadyExistsException`; deactivating a user persists the flag *and* triggers session eviction; activating persists the flag and does *not* evict; an ADMIN target is refused for both directions and neither saves nor evicts; an unknown id raises not-found.

#### 8. `/admin/**` matcher tests (pulled forward from Phase 5)

**File**: `src/test/java/com/example/city_fix/config/SecurityConfigTest.java`

**Intent**: Automate this phase's manual authorization checks at the point the matcher lands, rather than leaving the rule unpinned for two phases.

**Contract**: Three cases mirroring the `/staff/**` precedent — `/admin/users` is 403 for a resident, 403 for a staff member, and redirects to `/login` when unauthenticated. The staff case is the load-bearing one: it is what catches someone "fixing" the matcher to mirror `/staff/**`. Redirect assertions use the relative `Location` form the existing tests use.

> **Deviation from plan, consistent with Phases 1-2.** Manual items 3.5-3.7 are automated by change 8. Item **3.8 (admin reaches the page) cannot pass yet** — the controller returns view names whose templates arrive in Phase 4, so an admin request would fail template resolution rather than render. It moves to Phase 4, where the templates make it meaningful. Phase 5's change 1 is correspondingly reduced to that one remaining case.

#### 9. Role labels

**File**: `src/main/java/com/example/city_fix/user/Role.java`

**Intent**: Give the account list a human-readable role column.

**Contract**: Adds a `label` field and `getLabel()` to the enum, following the `ReportStatus` / `Category` precedent. Purely additive — no existing behaviour reads it.

### Success Criteria:

#### Automated Verification:

- Compiles: `./mvnw compile`
- Service tests pass: `./mvnw test -Dtest=AdminUserServiceTest`
- Matcher and security config tests pass: `./mvnw test -Dtest=SecurityConfigTest`
- Full suite passes: `./mvnw test`

#### Manual Verification:

This phase has no manual verification items — 3.5-3.7 are automated as change 8, and 3.8 moves to Phase 4, which supplies the templates it needs. See the deviation note above.

**Implementation Note**: This phase closes on automated verification alone.

---

## Phase 4: Admin UI and supersession

### Overview

Puts a usable surface on Phase 3's routes, links it from the home page, and retires the `staff.seed.*` bootstrap this slice supersedes. Also fixes the azure profile's missing `admin.seed.*`, without which the admin surface would be unreachable in production. This is the first phase with user-visible output.

### Changes Required:

#### 1. Account list template

**File**: `src/main/resources/templates/admin-users.html` (new)

**Intent**: The admin's primary screen — every manageable account and its activation action.

**Contract**: Standalone Thymeleaf document with `xmlns:th` and `xmlns:sec`, its own inline `<style>` reusing the existing visual vocabulary (`system-ui`, centred container, `#2563eb` primary, the status-badge pattern from `report-list.html:39-56`). A table over the account rows showing email, role, status and created date; an empty state via `#lists.isEmpty`; one POST form per row for the activate/deactivate toggle with the button label reflecting the current state; flash-message and error regions matching `register.html:23`. A link to the create form and a link home. CSRF comes from `th:action` and is never hand-written.

#### 2. Create-staff form template

**File**: `src/main/resources/templates/admin-user-new.html` (new)

**Intent**: The staff-creation form.

**Contract**: Modelled directly on `register.html` — email and password inputs, `minlength="8"` mirroring the server rule, sticky email repopulation on a validation failure, field errors rendered from the binding result, `th:action` POSTing to the admin route. No confirm-password field: `register.html`'s is client-side only and unread by the server, so copying it would duplicate a known wart.

#### 3. Home page nav link

**File**: `src/main/resources/templates/home.html`

**Intent**: Make the surface reachable — and guard it with the same expression as the matcher so the two cannot drift.

**Contract**: A new paragraph linking to the admin list, wrapped in `sec:authorize="hasRole('ADMIN')"` — character-for-character the same authority as the `/admin/**` rule added in Phase 3. Placed alongside the existing `/staff/**`-guarded triage link. S-02's impl review F1 was an unguarded home link 403'ing after a new rule landed; this is the pre-emption.

#### 4. Remove StaffSeeder

**File**: `src/main/java/com/example/city_fix/config/StaffSeeder.java` (delete), `src/test/java/com/example/city_fix/config/StaffSeederTest.java` (delete)

**Intent**: Retire the bootstrap this slice supersedes. Its own javadoc names this change as its terminus; admin-created staff is now the real path, and leaving it behind keeps a second, no-longer-exercised writer of `User.role`.

**Contract**: Both files deleted outright. `AdminSeeder` and `AdminSeederTest` are deliberately untouched.

#### 5. Property cleanup

**File**: `src/main/resources/application.properties`, `src/main/resources/application-azure.properties`

**Intent**: Remove the dead staff-seed configuration and add the admin-seed configuration that production is missing.

**Contract**: Delete `staff.seed.email` / `staff.seed.password` and their comment from both profiles — leaving declared properties that nothing reads is worse than removing them. Add `admin.seed.email=${ADMIN_EMAIL:}` and `admin.seed.password=${ADMIN_PASSWORD:}` to the azure profile, mirroring `application.properties:25-26`; the empty defaults mean an unset env var seeds nothing, exactly as locally. A comment records that this is the admin-provisioning mechanism of record, closing PRD Open Question #2.

#### 6. Controller tests

**File**: `src/test/java/com/example/city_fix/user/AdminUserControllerTest.java` (new)

**Intent**: Cover the routes end-to-end against a real database, including the redirect-and-flash behaviour the templates depend on.

**Contract**: `@SpringBootTest @AutoConfigureMockMvc extends TestcontainersConfig`. Sessions obtained by persisting a user and logging in through `POST /api/auth/login`, reusing the returned `MockHttpSession` (`StaffReportControllerTest.java:213-240`); every write carries `.with(csrf())`. Unique emails per test — nothing is transactional and the container is shared. Covers: the list renders and contains a seeded staff and resident but no admin; creating a staff account redirects and the row is persisted with `Role.STAFF`; an invalid email or a short password re-renders the form with errors and persists nothing; a duplicate email is reported, not thrown; the toggle flips the flag and redirects; an ADMIN target is refused with 403; and one end-to-end walk of the whole surface — create staff, the new account logs in, deactivate, its live session is expired and re-login refused, reactivate, it logs in again.

Driven test-first via `/10x-tdd` for the template-dependent behaviour (nav guard, list render, form render, validation re-render). The create and toggle routes shipped in Phase 3 and redirect rather than render, so their tests are regression coverage over existing behaviour, marked as such in the file rather than presented as red-green cycles.

#### 7. `CreateStaffForm` trims the email before validation (found by a failing test)

**File**: `src/main/java/com/example/city_fix/user/CreateStaffForm.java`

**Intent**: Fix an inconsistency the end-to-end test exposed: Bean Validation runs on the raw request parameter, before the service normalises, and the shared `@Pattern` excludes whitespace. A pasted address with a trailing space was rejected as "Invalid email format".

**Contract**: The record's compact constructor trims the email so validation sees the normalised value. Without it this form is stricter than the `/register` form an admin already uses, which trims in `AuthService` before validating. Lowercasing stays in the service, which owns the write path. Approved by the user as a deviation from "constraints identical to `RegisterRequest`", which the plan had specified literally.

> **Deviation from plan, consistent with Phases 1-3.** Manual items 4.5-4.10 are all automated by change 6 — the nav guard across three roles, the list contents and admin exclusion, and the create/deactivate/reactivate flow end-to-end. Phase 4 therefore closes with no manual items.

### Success Criteria:

#### Automated Verification:

- Compiles: `./mvnw compile`
- Controller tests pass: `./mvnw test -Dtest=AdminUserControllerTest`
- No reference to the deleted seeder remains: `grep -r "StaffSeeder\|staff.seed" src/` returns nothing
- Full suite passes: `./mvnw test`

#### Manual Verification:

- As admin, the home page shows the "Manage accounts" link; as staff and as resident it does not
- The account list renders staff and residents with correct status badges, and no admin row appears
- Creating a staff account with a mixed-case email produces a row that can then log in — proving normalisation reached the new write path
- Deactivating a staff member who is logged in elsewhere drops that browser at the login page on its next request, and their re-login is rejected
- Reactivating that account restores login

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 5: Integration tests, security tests and verification

### Overview

Puts an assertion behind every cross-role and cross-mechanism claim this slice makes, then packages the change. The security cases are the point of this phase rather than a formality — this slice adds an authorization boundary and a revocation mechanism, and both are only as good as the tests pinning them.

### Changes Required:

#### 1. Admin route authorization tests

**File**: `src/test/java/com/example/city_fix/config/SecurityConfigTest.java`

**Intent**: Pin the `/admin/**` rule at the configuration level, not only through the controller — matching the existing `staffPath_forbiddenToResident` precedent at `:87-94`.

**Contract**: New cases asserting `/admin/users` is 403 for a resident, 403 for a staff member, a redirect to `/login` when unauthenticated, and reachable for an admin. The staff case is the important one: it is what would catch someone "fixing" the matcher to mirror `/staff/**`.

#### 2. Deactivation end-to-end tests

**File**: `src/test/java/com/example/city_fix/user/AccountDeactivationTest.java` (new)

**Intent**: Prove the whole revocation chain — the claim that gives this slice its value — rather than testing the flag and the eviction separately.

**Contract**: `@SpringBootTest @AutoConfigureMockMvc extends TestcontainersConfig`. Covers: a staff member with a live session is deactivated by an admin, and their *existing session* can no longer reach `/staff/reports`; the same account's fresh login attempt is rejected with the generic 401 and no 500; a deactivated resident cannot reach their own reports; reactivation restores both login and access; deactivating one user does not disturb another user's live session; an admin attempting to deactivate an ADMIN row is refused and that account keeps working.

#### 3. Report-integrity test

**File**: `src/test/java/com/example/city_fix/user/AccountDeactivationTest.java`

**Intent**: Pin the deliberate decision that deactivation does not touch reports, so a later change cannot quietly start hiding them.

**Contract**: A resident files a report, is deactivated, and staff can still see that report on the triage map and open its detail page with the reporter's email intact.

#### 4. Documentation

**File**: `CLAUDE.md`, `context/changes/admin-manages-staff/change.md`

**Intent**: Record the domain rules this slice establishes and close out the change.

**Contract**: `CLAUDE.md` gains the account-state rule (accounts are active or deactivated; deactivation blocks login and expires live sessions; admins are managed only through `admin.seed.*`). `change.md` moves to `status: implemented` with today's date.

### Success Criteria:

#### Automated Verification:

- Security tests pass: `./mvnw test -Dtest=SecurityConfigTest`
- Deactivation tests pass: `./mvnw test -Dtest=AccountDeactivationTest`
- Full suite passes: `./mvnw test`
- Package succeeds: `./mvnw clean package`

#### Manual Verification:

- A full manual pass of the primary flow locally: create a staff account, log in as them in a second browser, deactivate them, observe the eviction and the blocked re-login, then reactivate and observe restoration
- A report filed by a resident who is then deactivated is still visible to staff on the triage map and its detail page still shows the reporter's email
- The deploy-time follow-ups are recorded rather than performed: Migration Notes names the azure backfill statement and the `ADMIN_EMAIL` / `ADMIN_PASSWORD` requirement

**Implementation Note**: This is the final phase. The change closes on local verification — the azure backfill and admin-seed configuration are deploy-time follow-ups, not gates. After automated verification passes, confirm the manual pass before closing the change.

---

## Testing Strategy

### Unit Tests:

- `UserTest` — activation state, mutators, and the null-means-active tolerance for pre-backfill rows
- `UserSessionServiceTest` — session expiry reaches the right sessions; no-ops safely when there are none
- `AdminUserServiceTest` — email normalisation before both the existence check and the write; `Role.STAFF` and password encoding on create; duplicate-email handling; ADMIN-target refusal in both directions; deactivate persists *then* evicts; activate does not evict

### Integration Tests:

- `AdminUserControllerTest` — list contents and exclusions, create-staff happy path and validation failures, toggle behaviour, POST-redirect-GET and flash messages
- `SecurityConfigTest` additions — `/admin/**` forbidden to resident and to staff, redirect when unauthenticated, reachable for admin
- `AccountDeactivationTest` — the full revocation chain across both filter chains, isolation between users, ADMIN-target refusal, reactivation, and report integrity after the reporter is deactivated

Conventions: class names end in `Test` (Failsafe is not wired — a `*IT` class would silently never run). Methods read `scenario_expectedOutcome`. Integration tests are `@SpringBootTest @AutoConfigureMockMvc extends TestcontainersConfig` against real PostgreSQL 17; `@WithMockUser` is unusable where `@AuthenticationPrincipal CustomUserDetails` is resolved, so sessions come from `POST /api/auth/login`. Every write carries `.with(csrf())`. Nothing is transactional and the container is shared, so each test invents unique emails. Docker must be running.

### Manual Testing Steps:

1. Apply the backfill locally; confirm `\d users` shows `active` and no rows are null.
2. Log in as admin; confirm the "Manage accounts" link appears. Log in as staff and as resident; confirm it does not.
3. Open `/admin/users` as staff — expect a 403 Whitelabel page.
4. Create a staff account using a deliberately mixed-case email; confirm the row is lowercased and that the account can log in.
5. Log that staff account in using a second browser. As admin, deactivate it. In the staff browser, click any link — expect the login page.
6. Attempt to log that account back in — expect "Invalid email or password."
7. Reactivate it as admin; confirm login works again.
8. File a report as a resident, deactivate that resident, then confirm as staff that the report is still on the map and its detail page still shows the reporter email.
9. Deferred to deploy time, not part of closing this change: apply the backfill to the azure database and set `ADMIN_EMAIL` / `ADMIN_PASSWORD` on App Service, then repeat steps 4-7 there. See Migration Notes.

## Performance Considerations

The account list is a single unpaged query. At MVP data volume (PRD `target_scale.data_volume: small`) this is fine; the page grows linearly with resident registrations and will need paging or a search filter before the user base is large. Recorded as an accepted limit rather than a defect.

`SessionRegistryImpl` holds session metadata in heap, proportional to concurrent logged-in users — negligible at this scale. `HttpSessionEventPublisher` is what keeps it from leaking on logout; without that bean the map grows for the life of the process.

Wiring `isEnabled()` adds no query — the flag rides along on the `User` already loaded by `CustomUserDetailsService`.

## Migration Notes

**Schema.** `ddl-auto=update` adds the nullable `active` column to `users` on both profiles. It will not backfill. Run `UPDATE users SET active = true WHERE active IS NULL;` and verify with `\d users` plus a null count — locally as part of Phase 1, and against azure at deploy time. Per `lessons.md:12-17`, a green boot is not evidence the column arrived — check the live schema. The code's null-means-active tolerance means a forgotten backfill degrades tidiness, not correctness.

**Superseded configuration.** Environments currently setting `STAFF_EMAIL` / `STAFF_PASSWORD` stop having a staff account auto-seeded. Rows already seeded are unaffected and keep working; from here staff accounts come from the admin surface. The variables can be removed from App Service configuration at leisure.

**New production configuration.** `ADMIN_EMAIL` and `ADMIN_PASSWORD` must be set on App Service, which they are not today — without them the deployed app has no admin and the new surface is unreachable. This is the concrete answer to PRD Open Question #2.

**Deploy-time checklist (does not gate this change).** This change closes on local verification. Before or immediately after the first deploy that carries it: (1) set `ADMIN_EMAIL` / `ADMIN_PASSWORD` on App Service; (2) run the backfill against the azure database and verify with `\d users` plus `SELECT count(*) FROM users WHERE active IS NULL;` returning 0 — these are Phase 1's former manual items 1.5 and 1.6; (3) repeat manual testing steps 4-7 there, and confirm that repeated login/logout cycles leave no stale registry entries — Phase 2's former manual item 2.6, which needs a real servlet container; (4) optionally remove the now-unread `STAFF_EMAIL` / `STAFF_PASSWORD` variables. Until (1) and (2) are done, the admin surface exists in production but is unreachable.

**Single-instance constraint.** `SessionRegistryImpl` is in-memory and per-instance. On a scaled-out App Service plan, deactivating a user would only expire sessions held by the instance handling the request; other instances would keep theirs until the flag blocked them at next login on that instance. The deployment is single-instance today, so this is a documented boundary rather than a live defect — scaling out would require Spring Session JDBC, which was explicitly considered and deferred.

**Rollback.** Reverting the code leaves an unused nullable column, which is harmless. Dropping it is optional and safe.

## References

- Change identity: `context/changes/admin-manages-staff/change.md`
- Roadmap slice S-03: `context/foundation/roadmap.md`
- PRD FR-002 and Access Control: `context/foundation/prd.md:78`, `:119-129`
- Binding rules: `context/foundation/lessons.md:5-10` (typed DTOs), `:12-17` (ddl-auto verification), `:19-24` (email normalisation)
- Controller pattern: `src/main/java/com/example/city_fix/report/StaffReportController.java:28-31`
- Path-matcher convention: `src/main/java/com/example/city_fix/config/SecurityConfig.java:72-77`
- Superseded bootstrap: `src/main/java/com/example/city_fix/config/StaffSeeder.java:16-20`
- Test session idiom: `src/test/java/com/example/city_fix/report/StaffReportControllerTest.java:213-240`
- Security-drift precedent: `context/archive/2026-06-06-auth-role-scaffold/reviews/impl-review.md:35-37,74-79`
- Nav-guard precedent: `context/archive/2026-09-09-staff-triages-reports/reviews/impl-review.md:51-56`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Deactivation flag and login blocking

#### Automated

- [x] 1.1 Compiles: `./mvnw compile` — 759a2f1
- [x] 1.2 Entity tests pass: `./mvnw test -Dtest=UserTest` — 759a2f1
- [x] 1.3 Existing auth tests still pass: `./mvnw test -Dtest=AuthControllerTest,AuthServiceTest,SecurityConfigTest` — 759a2f1
- [x] 1.4 Full suite passes: `./mvnw test` — 759a2f1
- [x] 1.5 Deactivation login-blocking tests pass: `./mvnw test -Dtest=AccountDeactivationTest` — 759a2f1

#### Manual

_None — 1.7 automated as `AccountDeactivationTest`; the schema checks moved to the deploy-time checklist in Migration Notes._

### Phase 2: Session eviction infrastructure

#### Automated

- [x] 2.1 Compiles: `./mvnw compile` — ff1af53
- [x] 2.2 Session service tests pass: `./mvnw test -Dtest=UserSessionServiceTest` — ff1af53
- [x] 2.3 Existing auth and security tests still pass: `./mvnw test -Dtest=AuthControllerTest,SecurityConfigTest` — ff1af53
- [x] 2.4 Full suite passes: `./mvnw test` — ff1af53
- [x] 2.5 Eviction integration tests pass: `./mvnw test -Dtest=AccountDeactivationTest` — ff1af53

#### Manual

_None — 2.5 and 2.7 automated in `AccountDeactivationTest`; 2.6 is not observable through MockMvc and moved to the deploy-time checklist._

### Phase 3: Admin service and controller

#### Automated

- [x] 3.1 Compiles: `./mvnw compile` — 14ae0f0
- [x] 3.2 Service tests pass: `./mvnw test -Dtest=AdminUserServiceTest` — 14ae0f0
- [x] 3.3 Matcher and security config tests pass: `./mvnw test -Dtest=SecurityConfigTest` — 14ae0f0
- [x] 3.4 Full suite passes: `./mvnw test` — 14ae0f0

#### Manual

_None — 3.5-3.7 automated in `SecurityConfigTest`; 3.8 moved to Phase 4 as item 4.10, which supplies the templates it needs._

### Phase 4: Admin UI and supersession

#### Automated

- [x] 4.1 Compiles: `./mvnw compile`
- [x] 4.2 Controller tests pass: `./mvnw test -Dtest=AdminUserControllerTest`
- [x] 4.3 No reference to the deleted seeder remains: `grep -r "StaffSeeder\|staff.seed" src/` returns nothing
- [x] 4.4 Full suite passes: `./mvnw test`
- [x] 4.5 Nav guard, list contents and the end-to-end admin flow are automated in `AdminUserControllerTest`

#### Manual

_None — 4.5-4.10 automated in `AdminUserControllerTest`, including the create → login → deactivate → evict → reactivate walk._

### Phase 5: Integration tests, security tests and verification

#### Automated

- [ ] 5.1 Security tests pass: `./mvnw test -Dtest=SecurityConfigTest`
- [ ] 5.2 Deactivation tests pass: `./mvnw test -Dtest=AccountDeactivationTest`
- [ ] 5.3 Full suite passes: `./mvnw test`
- [ ] 5.4 Package succeeds: `./mvnw clean package`

#### Manual

- [ ] 5.5 Full manual pass of the create → login → deactivate → evict → reactivate flow locally
- [ ] 5.6 A report by a deactivated resident is still visible to staff with the reporter email intact
- [ ] 5.7 Deploy-time follow-ups (azure backfill, `ADMIN_EMAIL` / `ADMIN_PASSWORD`) are recorded in Migration Notes
