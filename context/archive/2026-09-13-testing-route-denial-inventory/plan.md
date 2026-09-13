# Route-Denial Inventory Implementation Plan

## Overview

Build an authorization inventory that cannot go stale: derive the route list from Spring's
handler mapping, author the expected outcomes from the PRD, and fail the build when a derived
route is missing from the table. Then cover account-state revocation as the configuration-shaped
risk research proved it to be, rather than the route-shaped one the test plan originally assumed.

This is rollout Phase 2 of `context/foundation/test-plan.md`, covering risks #3 and #6.

## Current State Analysis

**Risk #3.** Two matcher lines carry the entire staff and admin surface
(`SecurityConfig.java:114`, `:118`); `@PreAuthorize` exists in exactly two places project-wide,
both in `ReportWebController` (`:55`, `:63`). Coverage is real but patchy, and the patchiness is
invisible: `GET /admin/users/new` has no denial test, `POST /register` has no test of any kind,
both logout routes are untested, and several covered routes are covered on one method only.

Worse, a denial can be present and still prove nothing. `GET /reports/new` is denied for STAFF
(`StaffReportControllerTest.java:192-193`) and for anonymous
(`ReportWebControllerTest.java:237-239`), but **no test ever succeeds at it as a RESIDENT** —
broaden the rule to deny everyone and the suite stays green.

**Risk #6.** Account state is not evaluated per request. `CustomUserDetails.java:25` captures the
flag at login into an immutable snapshot; `isEnabled()` (`:61-63`) is consulted only inside
`authenticationManager.authenticate()`. Revoking a live session is an out-of-band eviction
(`UserSessionService.java:31-44`) that `ConcurrentSessionFilter` enforces ahead of every matcher.
So one mechanism denies every route by construction. What varies is the **chain**: the browser
chain redirects to `/login?expired` (`SecurityConfig.java:130-136`); the API chain answers 401
with a JSON body (`:87-99`) that no test asserts.

**Test infrastructure.** The suite builds 2 Spring contexts. Four classes privately re-implement
user and report setup, and `UserFixtures` is package-private in `…user`, unreachable from
`…report`. No `@ParameterizedTest` exists anywhere; `junit-jupiter-params` is on the classpath
(JUnit Jupiter 6.0.3).

## Desired End State

Adding a route without an authorization decision fails the build. Every authenticated route has
an asserted outcome for anonymous, resident, staff and admin — allow cells included, so a rule
that denies everyone fails immediately. The two chains' expiry semantics are pinned, and a chain
configured without session management fails a test.

Verify by the mutation checks in each phase's manual criteria.

### Key Discoveries:

- **Route lists are derivable; authorization rules are not.**
  `AbstractHandlerMethodMapping.getHandlerMethods()` returns the full map (verified against
  spring-webmvc 7.0.7). `RequestMatcherDelegatingAuthorizationManager` exposes only
  `authorize(...)` — no accessor for its matcher list. Expectations must therefore be authored
  from the PRD; deriving them from `SecurityConfig` would produce a mirror test.
- **Three routes are invisible to the derivation** — `POST /login` (form-login processing URL),
  `POST /logout`, `POST /api/auth/logout` — because they are filter-level and never reach
  `DispatcherServlet`. The handler map reports `/login` as GET-only while the app accepts POST.
- **An empty method set means *every* method**, not none. Boot's `BasicErrorController` mappings
  are the only ones so shaped here; read naively they would be silently skipped.
- **`/error` IS in the handler map** — `BasicErrorController` is a real `@Controller`. A wrong
  exclusion here would be invisible.
- **A `public abstract` base is cache-neutral.** `MergedContextConfiguration.equals` does not
  include the test class, and both `@SpringBootTest` and `@AutoConfigureMockMvc` are
  `@Inherited`. Two things would fork a third context: making the fixture a Spring bean, or
  **duplicating** `@DynamicPropertySource` rather than inheriting it —
  `DynamicPropertiesContextCustomizer` compares only the `Set<Method>`.
- **Nothing is transactional and the container is shared**
  (`AccountDeactivationTest.java:313`). Every case must own unique identifiers.
- **CSRF makes 403 ambiguous** on web-chain POSTs (test-plan §6.1). Denial cells on state-
  mutating routes must send a valid token.

## What We're NOT Doing

- **No production code changes.** The admin-reads decision (below) ratifies existing behaviour;
  it does not add a rule.
- **No per-route sweep for the deactivated identity.** Research proved one filter denies every
  route ahead of the matchers; N routes would be N−1 redundant copies.
- **No matrix rows for `permitAll` routes, actuator, or static resources.** They are asserted
  reachable where already covered, and remain inside the completeness check, but are not crossed
  with four identities — those cells would encode Boot's behaviour, not ours.
- **No tests for `POST /register`.** It is state-mutating and untested, but `permitAll`, so its
  risk is input-contract — rollout Phase 3's territory, not authorization.
- **No filter-chain internals beyond the one configuration assertion.** Asserting Spring's filter
  shapes broadly is brittle across upgrades.
- **Not collapsing the suite to one Spring context.** `CityFixApplicationTests` keeps its own
  context so `contextLoads()` still proves the app boots without MockMvc auto-configuration.
- **No behavioural depth in allow cells** — they assert access was granted; what the route
  actually returns stays in the existing focused tests.

## Implementation Approach

The table is the artifact. It is authored from the PRD access-control matrix and holds, per
authenticated route × identity, an expected outcome of allowed or denied. Two independent
mechanisms consume it:

1. A **completeness assertion** compares the derived route set against the table's keys and fails
   on any route the table does not mention. This is what makes the inventory self-updating.
2. A **parameterized matrix test** exercises every cell. Because allow cells are asserted too,
   a rule that denies everyone fails immediately — attribution is structural, not remembered.

The PRD matrix that authors the expectations:

| Capability | Resident | Staff | Admin | Anonymous |
|---|---|---|---|---|
| Create a report (`/reports/new`, `POST /reports`) | allow | deny | deny | deny |
| Own reports (`GET /reports`, `/reports/{id}`, `/photo`) | allow | allow, reporter-scoped | allow, reporter-scoped | deny |
| Triage surface (`/staff/**`) | deny | allow | allow | deny |
| Account management (`/admin/**`) | deny | deny | allow | deny |
| Root (`/`), `GET /api/auth/me` | allow | allow | allow | deny |

**The admin-reads decision**: an admin reading `/reports/**` gets the same reporter-scoped view as
anyone — normally empty, and 404 for another user's report. This follows the PRD independently of
the code: admin is a superset of *staff*, staff is never granted the resident surface, and
reporter-scoping makes the result empty by construction. The admin's real view of every report is
`/staff/reports`.

## Critical Implementation Details

**The context-caching trap.** The new base class must **inherit** `TestcontainersConfig`'s
`@DynamicPropertySource` method, never declare its own. `DynamicPropertiesContextCustomizer`
compares only the `Set<Method>`, so a duplicated method forks a second container-backed context
even though it produces identical property values against the same container. Equally, the
fixture must not become a Spring bean (`@TestConfiguration` / `@Import`) — that is the one design
choice that adds to the cache key.

**Denial-status ambiguity.** On the web chain an anonymous request redirects to `/login` while an
authenticated-but-forbidden one returns 403, and a missing CSRF token also returns 403. The table
must express the expected outcome precisely enough to distinguish these, and every state-mutating
cell must send a valid CSRF token so a 403 can only mean authorization.

## Phase 1: Shared integration-test base

### Overview

Extract the duplicated helpers onto a `public abstract` base so the later phases have role-aware
fixtures, without forking a Spring context.

### Changes Required:

#### 1. The base class

**File**: `src/test/java/com/example/city_fix/IntegrationTest.java` (new)

**Intent**: Give the six MockMvc test classes one place for the setup they currently duplicate,
reachable from both the `report` and `user` packages.

**Contract**: `public abstract class IntegrationTest extends TestcontainersConfig`, annotated
`@SpringBootTest` and `@AutoConfigureMockMvc`. Carries `@Autowired` fields for `MockMvc`,
`UserRepository`, `PasswordEncoder`, `ReportRepository`, and `protected` helpers covering:
authenticate as an arbitrary role, register and authenticate a resident, persist a user with an
active flag, submit a report, find a report by description, and produce JPEG bytes. It must
**not** declare a `@DynamicPropertySource` method — see Critical Implementation Details.

#### 2. The six extending classes

**Files**: `AuthControllerTest`, `SecurityConfigTest`, `ReportWebControllerTest`,
`StaffReportControllerTest`, `AccountDeactivationTest`, `AdminUserControllerTest`

**Intent**: Extend the base and delete the private helpers it now provides, keeping class-specific
ones (`changeStatus`, `allPagesHtml`, `countOccurrences`, `firstRowEmail`) in place.

**Contract**: Each drops `@SpringBootTest` / `@AutoConfigureMockMvc` and
`extends TestcontainersConfig`, extending `IntegrationTest` instead. Helpers that differ only in
defaults — the two `submitReport` variants — collapse onto the parameterised signature.

### Success Criteria:

#### Automated Verification:

- Full suite passes unchanged: `./mvnw test`
- No class still declares a duplicated helper: `grep -n "private.*jpegBytes\|private.*findByDescription" src/test/java/com/example/city_fix/report/*.java` returns nothing
- No new `@DynamicPropertySource`: `grep -rn "DynamicPropertySource" src/test/java` returns exactly one occurrence

#### Manual Verification:

- Spring context count is unchanged at 2 — run with `-Dlogging.level.org.springframework.test.context.cache=DEBUG` and confirm the cache reports 2 contexts and no misses beyond them.
- Test wall-clock time has not materially increased.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 2: Route table and completeness assertion

### Overview

Introduce the authored table and the guard that makes it impossible to add a route without an
authorization decision. No per-cell assertions yet — the protection lands first.

### Changes Required:

#### 1. The route table

**File**: `src/test/java/com/example/city_fix/config/RouteAuthorizationTable.java` (new)

**Intent**: Hold the authored expectation table — the oracle — separately from the tests that
consume it, so both the completeness assertion and the matrix read one source.

**Contract**: A `final class` with a `private` constructor exposing the authenticated route ×
identity expectations, plus a separately named constant for the three filter-level routes that
`getHandlerMethods()` cannot see. That constant carries a comment stating why they are
hand-maintained and that a fourth such route would not be caught automatically.

#### 2. The completeness assertion

**File**: `src/test/java/com/example/city_fix/config/RouteInventoryTest.java` (new)

**Intent**: Fail the build when the application exposes a route the table does not mention.

**Contract**: Extends `IntegrationTest`; autowires `RequestMappingHandlerMapping` (qualified by
bean name `requestMappingHandlerMapping`, since actuator's mapping is a sibling type). Derives
route × method pairs from `getHandlerMethods()` via `getPatternValues()` and
`getMethodsCondition().getMethods()`, expands an **empty** method set to all methods, and asserts
every derived pair is either present in the table or on an explicitly named exclusion list
(`permitAll` routes, `/error`). The failure message must name the offending route and say what to
do about it.

### Success Criteria:

#### Automated Verification:

- New test passes: `./mvnw test -Dtest=RouteInventoryTest`
- Full suite passes: `./mvnw test`

#### Manual Verification:

- Mutation: add a throwaway `@GetMapping("/scratch-route")` to any controller, confirm `RouteInventoryTest` fails and names that route, then remove it.
- Confirm the derived set actually contains `/error` (proving the derivation is not silently dropping no-method mappings).
- Read the failure message as someone who did not write it — it should say which route and what to do.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 3: The authorization matrix

### Overview

Exercise every cell of the table — allow and deny — so denials are attributable by construction.

### Changes Required:

#### 1. The matrix test

**File**: `src/test/java/com/example/city_fix/config/RouteAuthorizationMatrixTest.java` (new)

**Intent**: Assert the authored outcome for each authenticated route × identity, covering the
gaps research found and making a deny-everyone rule fail immediately.

**Contract**: Extends `IntegrationTest`. A `@ParameterizedTest` with `@MethodSource` over the
table — the suite's first, so it establishes the pattern. Each case builds the identity via the
base-class helpers (a real session per role; anonymous sends none), sends a valid CSRF token on
state-mutating routes, and asserts the expected outcome: allowed, forbidden, or redirected to
login. Allow cells assert only that access was granted. Cases owning mutating routes use unique
per-case identifiers, since the database is shared and non-transactional.

Routes needing an existing report resolve one through the base-class helpers per case rather than
sharing a fixture row, so cases stay independent.

#### 2. Retire superseded assertions

**Files**: `SecurityConfigTest`, `StaffReportControllerTest`, `ReportWebControllerTest`

**Intent**: Remove denial assertions the matrix now covers, so a single rule change does not fail
a dozen tests for one cause.

**Contract**: Delete only assertions whose route × identity × expectation is now a matrix cell.
Keep every test asserting a persisted side effect, a response body, or ownership semantics — the
matrix asserts access, not behaviour. Phase 1's three status-change tests stay: they assert
unchanged persisted state.

### Success Criteria:

#### Automated Verification:

- Matrix test passes: `./mvnw test -Dtest=RouteAuthorizationMatrixTest`
- Full suite passes: `./mvnw test`
- The matrix covers every authenticated route in the table: asserted inside the test itself, failing if a table row produced no case

#### Manual Verification:

- Mutation A: broaden `@PreAuthorize("hasRole('RESIDENT')")` on `/reports/new` to deny everyone; confirm the RESIDENT allow cell fails — the hole research found is now closed.
- Mutation B: delete the `/admin/**` matcher; confirm resident and staff deny cells fail.
- Mutation C: change `/staff/**` to `hasRole("STAFF")`; confirm the admin allow cells fail.
- Confirm no two mutations fail the same single cell, i.e. cells are not redundant copies.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 4: Account-state expiry

### Overview

Cover risk #6 as the configuration-shaped risk it is: per-chain semantics, the untested CSRF ×
expiry interaction, and a guard against a chain configured without expiry enforcement.

### Changes Required:

#### 1. Per-chain expiry semantics

**File**: `src/test/java/com/example/city_fix/user/AccountDeactivationTest.java`

**Intent**: Pin what each chain actually returns to an evicted session, including the API JSON
body that is currently unasserted.

**Contract**: Extend the existing API-chain test to assert the response body, not only the 401
status. The browser-chain redirect to `/login?expired` is already asserted and stays as is.

#### 2. The CSRF × expiry interaction

**File**: `src/test/java/com/example/city_fix/user/AccountDeactivationTest.java`

**Intent**: Establish what an evicted session meets when it POSTs on the browser chain — no test
performs any web-chain POST with an expired session, so the outcome is currently unverified.

**Contract**: A test performing a state-mutating web-chain POST with a valid CSRF token on an
evicted session, asserting the observed outcome and that no write occurred. **Observe the actual
behaviour before asserting it** — if the result contradicts the expectation, stop and raise it
rather than encoding whatever happens.

#### 3. The chain-configuration guard

**File**: `src/test/java/com/example/city_fix/config/SecurityConfigTest.java`

**Intent**: Make a future `SecurityFilterChain` added without a `sessionManagement` block fail a
test, which is the actual structural content of risk #6.

**Contract**: Autowire `FilterChainProxy`, iterate `getFilterChains()`, and assert every chain
includes `ConcurrentSessionFilter`. The failure message must state that a chain without session
management silently loses deactivation eviction for everything it serves.

### Success Criteria:

#### Automated Verification:

- Deactivation tests pass: `./mvnw test -Dtest=AccountDeactivationTest`
- Config test passes: `./mvnw test -Dtest=SecurityConfigTest`
- Full suite passes: `./mvnw test`

#### Manual Verification:

- Mutation: remove the `sessionManagement` block from the web chain; confirm the chain-configuration guard fails, then revert.
- Confirm the CSRF × expiry result was observed first and the assertion matches observed behaviour — record the outcome in the phase notes.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 5: Cookbook and rollout sync

### Overview

Record the patterns this phase establishes and close the rollout phase.

### Changes Required:

#### 1. Cookbook §6.2

**File**: `context/foundation/test-plan.md`

**Intent**: Replace the §6.2 placeholder with how to add a route to the inventory, so the next
author extends the table rather than writing a standalone test.

**Contract**: §6.2 names the table as the single source, states that adding a route means adding a
row (not a test), records that the three filter-level routes are hand-maintained and why, and
gives the run command. It must state the allow-cell rule: a denial is only meaningful beside a
permission that still works.

#### 2. Phase note and rollout status

**File**: `context/foundation/test-plan.md`

**Intent**: Record what this phase taught and mark the rollout phase complete.

**Contract**: §6.6 gains a note covering the derived-list-plus-authored-expectations split, the
three filter-level routes, the observed CSRF × expiry outcome, and that the fixture extraction
landed. §3 row 2 Status → `complete`; header date bumped.

### Success Criteria:

#### Automated Verification:

- No Phase-2 placeholder remains: `grep -n "TBD — see §3 Phase 2" context/foundation/test-plan.md` returns nothing
- §3 row 2 reads `complete`
- No test code blocks added: `grep -nE '^\s*```java' context/foundation/test-plan.md` returns nothing

#### Manual Verification:

- A reader following §6.2 would add a table row rather than a new test class.
- The §6.6 note states the residual gap honestly — a fourth filter-level route would not be caught.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful.

---

## Testing Strategy

### Unit Tests:

None. Every assertion here concerns framework wiring, which a mock would misrepresent — see
test-plan §6.5 and `lessons.md`.

### Integration Tests:

- Completeness: every derived route appears in the table or an explicit exclusion list.
- Matrix: each authenticated route × identity, allow and deny.
- Expiry: per-chain semantics, CSRF × expired session, chain configuration.

### Manual Testing Steps:

1. Run `./mvnw test` and confirm the suite is green.
2. Add a throwaway route; confirm `RouteInventoryTest` fails and names it; remove it.
3. Broaden `/reports/new` to deny everyone; confirm the RESIDENT allow cell fails; revert.
4. Delete the `/admin/**` matcher; confirm resident and staff deny cells fail; revert.
5. Narrow `/staff/**` to `hasRole("STAFF")`; confirm admin allow cells fail; revert.
6. Remove the web chain's `sessionManagement` block; confirm the configuration guard fails; revert.
7. Confirm the Spring context count is still 2.

## Performance Considerations

The matrix adds roughly 13 routes × 4 identities of MockMvc calls against an already-running
context. Each case authenticates, so wall-clock grows with case count; if it becomes noticeable,
cache sessions per role across cases rather than reducing coverage. The fixture extraction is
cache-neutral and adds no context builds.

## Migration Notes

None — test-only, no schema or environment impact, nothing to run outside the repo.

## References

- Related research: `context/changes/testing-route-denial-inventory/research.md`
- Prior phase: `context/archive/2026-09-13-testing-ownership-role-denial/research.md`
- Rollout phase definition: `context/foundation/test-plan.md` §3 Phase 2
- Attributability rule: `context/foundation/test-plan.md` §6.1
- Cross-package base precedent: `src/test/java/com/example/city_fix/TestcontainersConfig.java`
- Enforcement points under test: `src/main/java/com/example/city_fix/config/SecurityConfig.java:114,118,87-99,130-136`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Shared integration-test base

#### Automated

- [x] 1.1 Full suite passes unchanged: `./mvnw test` — 4f62bf8
- [x] 1.2 No class still declares a duplicated helper — 4f62bf8
- [x] 1.3 Exactly one `@DynamicPropertySource` remains in the test tree — 4f62bf8

#### Manual

- [x] 1.4 Spring context count is unchanged at 2 — 4f62bf8
- [x] 1.5 Test wall-clock time has not materially increased — 4f62bf8

### Phase 2: Route table and completeness assertion

#### Automated

- [x] 2.1 New test passes: `./mvnw test -Dtest=RouteInventoryTest` — a50737a
- [x] 2.2 Full suite passes: `./mvnw test` — a50737a

#### Manual

- [x] 2.3 A throwaway route makes `RouteInventoryTest` fail and names it — a50737a
- [x] 2.4 The derived set contains `/error` — a50737a
- [x] 2.5 The failure message is actionable to someone who did not write it — a50737a

### Phase 3: The authorization matrix

#### Automated

- [x] 3.1 Matrix test passes: `./mvnw test -Dtest=RouteAuthorizationMatrixTest` — b557c4a
- [x] 3.2 Full suite passes: `./mvnw test` — b557c4a
- [x] 3.3 Every table row produced a case — b557c4a

#### Manual

- [x] 3.4 Mutation A: `/reports/new` denying everyone fails the RESIDENT allow cell — b557c4a
- [x] 3.5 Mutation B: deleting the `/admin/**` matcher fails resident and staff deny cells — b557c4a
- [x] 3.6 Mutation C: `/staff/**` as `hasRole("STAFF")` fails the admin allow cells — b557c4a
- [x] 3.7 No two mutations fail the same single cell — b557c4a

### Phase 4: Account-state expiry

#### Automated

- [x] 4.1 Deactivation tests pass: `./mvnw test -Dtest=AccountDeactivationTest` — 726e9f6
- [x] 4.2 Config test passes: `./mvnw test -Dtest=SecurityConfigTest` — 726e9f6
- [x] 4.3 Full suite passes: `./mvnw test` — 726e9f6

#### Manual

- [x] 4.4 Removing the web chain's `sessionManagement` block fails the configuration guard — 726e9f6
- [x] 4.5 The CSRF × expiry outcome was observed before being asserted, and recorded — 726e9f6

### Phase 5: Cookbook and rollout sync

#### Automated

- [x] 5.1 No Phase-2 placeholder remains — 49f5342
- [x] 5.2 §3 row 2 reads `complete` — 49f5342
- [x] 5.3 No test code blocks added to the plan — 49f5342

#### Manual

- [x] 5.4 A reader following §6.2 would add a table row, not a new test class — 49f5342
- [x] 5.5 The §6.6 note states the residual filter-route gap honestly — 49f5342
