---
date: 2026-09-13T21:35:44+0200
researcher: Adam Jabłoński
git_commit: 8b22cf3005a8e118f81d143bfb114055be8b8aba
branch: main
repository: 10x-devs-project
topic: "Ground rollout Phase 2 of the test plan: route-denial inventory (risks #3 and #6)"
tags: [research, codebase, authorization, spring-security, session-management, testing, route-inventory]
status: complete
last_updated: 2026-09-13
last_updated_by: Adam Jabłoński
---

# Research: Route-denial inventory

**Date**: 2026-09-13T21:35:44+0200
**Researcher**: Adam Jabłoński
**Git Commit**: 8b22cf3005a8e118f81d143bfb114055be8b8aba (unpushed — local `file:line` references, no permalinks)
**Branch**: main
**Repository**: 10x-devs-project

## Research Question

Ground rollout Phase 2 of `context/foundation/test-plan.md`. Verify risks #3 (an access rule is
changed, a route is silently opened, and the suite stays green because no test asserted denial
on that route) and #6 (a route added after the revocation work skips the account-state check).
Establish the enumerable route inventory, which routes assert denial and for which methods,
and whether a denial status is attributable to the rule under test.

## Summary

**Risk #3 is confirmed and is bigger than the test plan framed it. Risk #6 is real but is not a
route-enumeration problem at all** — the test plan's guidance for it ("proven by the same
enumeration as #3") rests on a false premise and must be re-framed before planning.

Five findings drive the plan:

1. **The route inventory can be auto-derived**, so it cannot rot the way a hand-maintained list
   would. `RequestMappingHandlerMapping.getHandlerMethods()` yields all 20 application mappings.
   But it **misses three filter-level routes** — `POST /login`, `POST /logout`,
   `POST /api/auth/logout` — which never reach `DispatcherServlet`. The first is the dangerous
   one: the handler map says `/login` is GET-only while the deployed app accepts POST there.

2. **The authorization rules are deliberately not introspectable**, which forces the right
   design. Expected outcomes must be hand-written from the PRD; only the route *list* is
   derived. Deriving expectations from `SecurityConfig` would produce a mirror test.

3. **The protection worth building is a completeness assertion**: fail the build when the
   derived route set contains anything absent from the hand-written expectation table. That is
   what makes a new unprotected route impossible to merge — the actual content of risk #3.

4. **A denial can be worthless even when present.** `GET /reports/new` is denied for STAFF and
   anonymous, but no test ever succeeds at it as a RESIDENT — broaden the rule to deny everyone
   and both denial tests stay green. This is a second attributability failure mode alongside the
   CSRF one Phase 1 found, and the test plan's response guidance does not mention it.

5. **Account state is not enforced per request.** It is checked only inside
   `authenticationManager.authenticate()`, against an immutable snapshot taken at login, plus an
   out-of-band eviction that flips an in-memory flag. `ConcurrentSessionFilter` then rejects the
   session *before* any URL matcher runs — one mechanism, chain-wide. Enumerating N routes for a
   deactivated identity would produce N−1 redundant copies.

Separately, the fixture extraction carried forward from Phase 1 is **free** if done one way and
**costs a whole extra container-backed Spring context** if done another. The difference is
precise and is documented below.

## Detailed Findings

### The complete route inventory

20 declared handler mappings across 6 controllers, plus framework routes.

| Path | Method | Chain | Authorization rule |
|---|---|---|---|
| `/` | GET | web | `anyRequest().authenticated()` |
| `/login` | GET | web | `permitAll()` |
| `/login` | **POST** | web | form-login filter — **not a handler method** |
| `/register` | GET, **POST** | web | `permitAll()` + CSRF on POST |
| `/logout` | **POST** | web | `LogoutFilter` — **not a handler method** |
| `/reports/new` | GET | web | `authenticated()` + `@PreAuthorize("hasRole('RESIDENT')")` |
| `/reports` | POST | web | `authenticated()` + `@PreAuthorize("hasRole('RESIDENT')")` |
| `/reports` | GET | web | `anyRequest().authenticated()` |
| `/reports/{id}`, `/reports/{id}/photo` | GET | web | `authenticated()`; ownership in the query |
| `/staff/reports`, `/staff/reports/{id}`, `/staff/reports/{id}/photo` | GET | web | `/staff/**` → `hasAnyRole("STAFF","ADMIN")` |
| `/staff/reports/{id}/status` | POST | web | same matcher + CSRF |
| `/admin/users` | GET, POST | web | `/admin/**` → `hasRole("ADMIN")` |
| `/admin/users/new` | GET | web | same |
| `/admin/users/{id}/active` | POST | web | same + CSRF |
| `/api/auth/login`, `/api/auth/register` | POST | api | `permitAll()`, CSRF ignored |
| `/api/auth/me` | GET | api | `anyRequest().authenticated()` |
| `/api/auth/logout` | **POST** | api | `LogoutFilter` — **not a handler method** |
| `/error` | all methods | web | `permitAll()` |
| `/actuator/health`, `/actuator/info` | GET | web | `permitAll()` |
| `/actuator`, `/actuator/health/{*path}` | GET | web | falls to `anyRequest().authenticated()` |
| `/css/**`, `/js/**` | GET | web | `permitAll()` |

`@PreAuthorize` exists in exactly two places project-wide, both in `ReportWebController`
(`:55`, `:63`). `StaffReportController` and `AdminUserController` carry none — their protection
is entirely `SecurityConfig.java:114` and `:118`.

Chain assignment: the `@Order(1)` chain has `securityMatcher("/api/**")`; everything else
(including `/error`, actuator, static) falls to the `@Order(2)` chain.

### Auto-derivation: what works, what is missed

Verified against the jars on the classpath (Spring Framework 7.0.7, Spring Security 7.0.5),
not assumed from older API shapes.

```
public abstract class AbstractHandlerMethodMapping<T> ... {
  public java.util.Map<T, org.springframework.web.method.HandlerMethod> getHandlerMethods();
```

`RequestMappingInfo` exposes `getPatternValues()` → `Set<String>` and
`getMethodsCondition().getMethods()` → `Set<RequestMethod>`.

Three traps for a data-driven test:

- **An empty method set means "matches every HTTP method"**, not "no methods". Boot's
  `BasicErrorController` mappings are the only ones in this codebase with no method restriction;
  read naively they would be silently skipped.
- **`/error` IS in the map.** `BasicErrorController` is a real `@Controller`. A test that
  assumes otherwise would carry a wrong exclusion.
- **Three routes are invisible to the map** because they are filter-level:
  `POST /login` (form-login processing URL), `POST /logout`, `POST /api/auth/logout`. Actuator
  lives in a *different* `HandlerMapping` bean (`WebMvcEndpointHandlerMapping`, a sibling type,
  not a subtype), and static resources are not handler methods at all.

**The authorization rules cannot be enumerated.**
`RequestMatcherDelegatingAuthorizationManager`'s entire public surface is `authorize(...)` and
`builder()` — no `getMappings()`, no iterator. Reading the configured matchers would require
reflection into a private field. `FilterChainProxy.getFilterChains()` and
`DefaultSecurityFilterChain.getRequestMatcher()` *do* let a test determine which chain claims a
path, which is useful for asserting chain assignment.

This limitation is desirable: it forces the expectation table to come from the PRD rather than
from the configuration under test.

### Existing denial coverage — the gaps

Complete gap list, from an audit of all 18 test classes:

**Denial with no working permission counterpart** (the failure mode the test plan misses):

- `GET /reports/new` — denied for STAFF (`StaffReportControllerTest.java:192-193`) and anonymous
  (`ReportWebControllerTest.java:237-239`). **No test ever performs a successful
  `GET /reports/new` as a RESIDENT.** Broadening `@PreAuthorize` to deny everyone leaves the
  suite green.

**Routes with no denial test at all:**

- `GET /admin/users/new` — permission test only (`AdminUserControllerTest.java:139-145`).
- `POST /register` (web) — **no test of any kind**: no denial, no permission, no CSRF. It is
  state-mutating and sits under `permitAll`.
- `POST /logout`, `POST /api/auth/logout` — no test.
- `GET /actuator/info` — untested (`/actuator/health` has a permit test).

**Methods uncovered on otherwise-covered routes:**

| Route | Uncovered |
|---|---|
| `POST /staff/reports/{id}/status` | anonymous |
| `GET /staff/reports/{id}/photo` | anonymous (the unauthenticated list at `StaffReportControllerTest.java:84-93` covers the other two staff GETs and omits this one) |
| `POST /admin/users` | anonymous, RESIDENT, missing-CSRF |
| `POST /admin/users/{id}/active` | anonymous, RESIDENT |
| `POST /reports` | anonymous, ADMIN, missing-CSRF |
| `GET /reports/new` | ADMIN |

**CSRF dimension:** only two state-mutating routes assert refusal without a token —
`POST /staff/reports/{id}/status` (`StaffReportControllerTest.java:225-241`) and
`POST /admin/users/{id}/active` (`SecurityConfigTest.java:130-138`). `POST /reports` passes
`.with(csrf())` at all ten call sites and nothing proves the token is required.

**Two structural weaknesses:**

1. Every `/admin/**` denial except one uses a synthetic `@WithMockUser` principal — no database
   id, no `active` flag — so none can detect a deactivated-account bypass on the admin surface.
   `/staff/**` and `/reports/**` are the reverse, nearly all real sessions.
2. Only three denial tests assert that a write did **not** happen
   (`StaffReportControllerTest.java:220-222`, `:238-240`, `AdminUserControllerTest.java:250-256`).
   The rest assert a status code alone, so a handler returning 403 *after* writing would pass.

### Risk #6 is not a route-enumeration problem

Role enforcement is per-request: `AuthorizationFilter` evaluates the matchers on every request.
Account state is not in that path at all.

`CustomUserDetails.java:25` captures the flag once, at principal construction:

```java
this.active = user.isActive();
```

The class is immutable and never refreshed. `isEnabled()` (`:61-63`) is consulted only by the
`AuthenticationProvider`'s pre-authentication checks — i.e. only inside
`authenticationManager.authenticate()`, which runs at login and nowhere else. There is no
`@PreAuthorize` on enabled-ness anywhere, and `.anyRequest().authenticated()` is satisfied by
any non-anonymous `Authentication` regardless of the flag.

Revocation of a *live* session is entirely out-of-band: `UserSessionService.expireSessions`
(`UserSessionService.java:31-44`) calls `session.expireNow()`, flipping a boolean in the
in-memory registry. `ConcurrentSessionFilter` reads that flag and rejects the request **before
any URL matcher, controller or handler runs**.

Consequences for the plan:

- **A per-route sweep for a deactivated identity is vacuous.** One mechanism denies every route
  by construction; N−1 of those tests would be redundant copies.
- **What does vary is the chain.** Web → 302 `/login?expired`
  (`SecurityConfig.java:130-136`); `/api/**` → 401 with `{"message":"Authentication required"}`
  (`:87-99`). The existing test asserts only the status (`AccountDeactivationTest.java:161`),
  never the body.
- **CSRF × expiry on a web POST is entirely untested.** No test performs any POST on the web
  chain with an evicted session, so which filter answers first is unverified.
- **The real structural risk** is a future `SecurityFilterChain` added without a
  `sessionManagement` block: it installs no `ConcurrentSessionFilter`, and eviction silently
  stops applying to everything that chain serves. This is the honest #6 analogue of #3 — a
  configuration-shaped gap, not a route-shaped one.

Routes currently covered for a deactivated identity holding an evicted session: exactly three —
`GET /api/auth/me` (401), `GET /` (302), `GET /reports` (302). Nothing covers a deactivated
identity on `/staff/**` or `/admin/**` except one case in
`AdminUserControllerTest.java:287-289`.

Note also that most deactivation tests bypass the real user journey: they call `deactivate()` +
`save()` + `expireSessions()` directly rather than going through `POST /admin/users/{id}/active`.

`AdminUserService.java:26-31` gates *which rows* may be deactivated (`RESIDENT`, `STAFF` only —
never `ADMIN`), but beyond that gate deactivation is role-blind; the response an evicted session
sees depends on the chain, never on the role.

### Fixture extraction: free one way, expensive the other

The suite builds **2** Spring contexts today. `MergedContextConfiguration.equals` (verified in
spring-test 7.0.9 bytecode) compares `locations`, `classes`, `contextInitializerClasses`,
`activeProfiles`, `propertySourceDescriptors`, `propertySourceProperties`, `contextCustomizers`,
`contextLoader`, `parent` — and **not** the test class. All seven Spring tests resolve to the
same `{CityFixApplication.class}`, no profiles, no `@TestPropertySource`, no bean overrides.

The only fork is `ImportsContextCustomizer`: six classes carry `@AutoConfigureMockMvc`,
`CityFixApplicationTests` does not. So that one empty `contextLoads()` test already costs a
second full Spring refresh.

**Cache-neutral (free):**

- A shared `public abstract` base class carrying `@SpringBootTest @AutoConfigureMockMvc`, with
  the six classes extending it. Both annotations are `@Inherited` and the class hierarchy is not
  part of the key.
- Putting helpers on that base as instance methods with `@Autowired` fields. Injection
  contributes nothing to the key, and all four needed beans
  (`MockMvc`, `UserRepository`, `PasswordEncoder`, `ReportRepository`) are already singletons.
- A Spring-free static helper class anywhere under `src/test/java`.

**Forks a new context (costs a full extra refresh):**

- **Making the fixture a Spring bean** — `@TestConfiguration` or `@Import` adds to `classes`.
  This is the single design choice that can turn a free extraction into a third
  container-backed context build.
- **Duplicating the `@DynamicPropertySource` method** instead of inheriting it.
  `DynamicPropertiesContextCustomizer` compares only the `Set<Method>`, so a new base declaring
  its own `configureProperties` forks from classes still inheriting
  `TestcontainersConfig.configureProperties` — even though both produce identical values against
  the same container. This is the trap.
- Applying any of `@ActiveProfiles`, `@TestPropertySource`, `@MockitoBean` to a subset.

A side benefit is available: if `CityFixApplicationTests` also extended the new base, the suite
would drop to **1** context — at the cost of `contextLoads()` no longer proving the app starts
without MockMvc auto-configuration.

**Constraints any fixture must respect.** Nothing is `@Transactional`; no `@Sql`, no
`@Rollback`, no `@DirtiesContext`, no `@TestMethodOrder` anywhere. Every row written survives
the whole run across all six classes. Collision avoidance today is manual discipline — unique
hand-chosen emails per call site, unique report descriptions scanned via
`findAll().stream().filter(...)`, and `System.nanoTime()` where a count matters. A fixture that
defaults an email would break that silently on its second call. Note also
`AdminUserControllerTest.java:100`, which asserts exactly 25 `/active` occurrences on page 0 — a
fixture that seeds rows interacts with it.

**Where it goes.** `com.example.city_fix` (root test package) and `public` — Java has no
package-hierarchy visibility. Both precedents already exist: `TestcontainersConfig` is a
`public abstract` cross-package base, and `UserFixtures` is the package-scoped, Spring-free
static-helper shape. The Spring-dependent helpers follow the first; pure data like `jpegBytes()`
follows the second, or becomes `protected static` on the base.

**The duplication itself.** Byte-identical across classes: `jpegBytes()`,
`findByDescription(String)`, `credentials(String)` (concat form), `login(String)`, and the
`PASSWORD` constant. Differing on one axis only: `submitReport` (staff's version is the
parameterised one with lat/lng/category fixed), and the user-row writers
(`persist` / `persistUser` / `authenticateAs` differ in return type and a deactivate branch).
Genuinely distinct and not extraction candidates: `changeStatus`, `allPagesHtml`,
`countOccurrences`, `firstRowEmail`.

### Mechanics

`junit-jupiter-params` is on the test classpath transitively (**JUnit Jupiter 6.0.3** — major
version 6, so parameterized display-name and argument-conversion details differ from Jupiter 5).
No `@ParameterizedTest` exists anywhere in the suite; this phase would introduce the first.

## Code References

- `src/main/java/com/example/city_fix/config/SecurityConfig.java:114,118` — the two role matchers that carry the whole staff and admin surface
- `src/main/java/com/example/city_fix/config/SecurityConfig.java:87-99,130-136` — the two chains' divergent expired-session responses
- `src/main/java/com/example/city_fix/config/SecurityConfig.java:32-41` — in-memory, per-instance `SessionRegistryImpl`
- `src/main/java/com/example/city_fix/auth/CustomUserDetails.java:25,61-63` — the account-state snapshot and the only overridden status method
- `src/main/java/com/example/city_fix/user/UserSessionService.java:31-44` — out-of-band eviction
- `src/main/java/com/example/city_fix/user/AdminUserService.java:26-31,82-104` — which rows may be deactivated, and the eviction call
- `src/main/java/com/example/city_fix/report/ReportWebController.java:55,63` — the only two `@PreAuthorize` in the project
- `src/main/java/com/example/city_fix/auth/AuthWebController.java:40-52` — `POST /register`, entirely untested
- `src/test/java/com/example/city_fix/TestcontainersConfig.java` — the `public abstract` cross-package precedent
- `src/test/java/com/example/city_fix/user/UserFixtures.java` — the package-scoped static-helper precedent
- `src/test/java/com/example/city_fix/report/StaffReportControllerTest.java:270-274` — the only helper producing a real session for an arbitrary role
- `src/test/java/com/example/city_fix/user/AccountDeactivationTest.java:313-321` — the only deactivated-user constructor
- `src/test/java/com/example/city_fix/user/AdminUserControllerTest.java:100,111-124` — the page-size assertion and the accumulation workaround

## Architecture Insights

- **Two enforcement styles with different failure modes.** Ownership is enforced inside the
  persistence query (impossible to forget); roles at the path matcher (impossible to forget
  per-method, but concentrated in two editable lines); account state only at authentication plus
  an out-of-band sweep (per-request enforcement absent by design). A test strategy that treats
  all three the same will over-test two of them and under-test the third.
- **The framework gives route discovery but withholds rule discovery.** That asymmetry is the
  single most useful fact for this phase: it makes the safe design (derived list, authored
  expectations) also the only practical one.
- **Filter-level routes are structurally invisible** to handler-based introspection. Any
  inventory that claims completeness has to name them explicitly.
- **The suite's order-independence is discipline, not declaration.** Nothing enforces the unique
  identifier convention; a shared fixture would inherit sole responsibility for it.

## Historical Context (from prior changes)

- `context/archive/2026-09-13-testing-ownership-role-denial/research.md` — Phase 1. Established
  that no test in this suite mocks security, that ownership is query-scoped, and the CSRF
  attributability rule now in test-plan §6.1.
- `context/archive/2026-09-11-admin-manages-staff/reviews/impl-review.md:72-80` — F3: admin write
  routes had no authorization test until review; narrowing the matcher to GET made the staff-POST
  case "fail with 302, not 403 — i.e. the account was actually created by a STAFF user."
- `context/archive/2026-09-09-staff-triages-reports/reviews/impl-review.md:114-126` — F5: the
  unscoped public `get(Long)` accepted as-is because "the authorization lives entirely in the
  `/staff/**` path matcher."
- `context/archive/2026-09-11-admin-manages-staff/plan.md:35` — "`/admin/**` must sit above
  `anyRequest().authenticated()` … or it is dead code and every authenticated user reaches the
  admin surface." Matcher ordering is recorded twice as a single point of failure.
- `context/foundation/lessons.md` §"A revocation flag only revokes once every enforcement point
  is wired" — the origin of risk #6, and the reason its enforcement points must be enumerated
  rather than assumed.

## Related Research

- `context/archive/2026-09-13-testing-ownership-role-denial/research.md` — the immediately prior
  rollout phase; its route and enforcement findings are extended rather than repeated here.

## Recommended corrections to the test plan

Per the post-research backport check. Neither adds file anchors to §2.

1. **Risk #6 — re-frame the response guidance.** "A deactivated account is denied on every
   authenticated route, proven by the same enumeration as #3" rests on a false premise: account
   state is not enforced per route, so the enumeration would be N−1 redundant copies. Replace
   with: prove the two chains' expiry semantics including the API body, prove the untested
   CSRF × expiry interaction on a web POST, and guard against a chain configured without session
   management. Its "cheapest layer" of "integration, reusing Phase 2's route inventory" should
   become a small number of targeted integration tests plus a configuration assertion.
2. **Risk #3 — extend "must challenge".** Add: *that a denial test proves anything on its own* —
   a denial with no working permission counterpart passes equally when the rule denies everyone.
   `GET /reports/new` is in exactly that state today. This is a second attributability failure
   mode alongside the CSRF one already recorded.

## Open Questions

1. **How far should the expectation table reach?** The derived inventory covers 20 handler
   routes; adding the 3 filter routes, actuator and static brings it to ~27 rows × 4 identities.
   Covering every cell is likely over-testing. A defensible cut is: every authenticated route ×
   the identities the PRD grants and denies, with `permitAll` routes asserted as reachable only.
   Owner: user. Blocking: no, but it sizes the phase.
2. **What is an admin's intended access to the resident `/reports/**` surface?** Unresolved for
   the second phase running. Phase 1 could leave it out of scope; an exhaustive table cannot —
   every cell needs an expected value. Owner: user. Blocking: **yes** for the expectation table.
3. **Does `POST /register` belong to this phase?** It is state-mutating with no test of any kind,
   but it is `permitAll`, so its risk is input-contract (rollout Phase 3), not authorization.
   Owner: user. Blocking: no.
4. **Should the fixture extraction also collapse the suite to one Spring context** by having
   `CityFixApplicationTests` extend the new base? It is a real saving, at the cost of losing the
   only assertion that the app boots without MockMvc auto-configuration. Owner: user.
   Blocking: no.
5. **`prd.md` is still stale** — Open Question #1 was resolved in S-02 but remains listed as open
   at `prd.md:142`. Carried over unaddressed from Phase 1. Owner: user. Out of scope here.
