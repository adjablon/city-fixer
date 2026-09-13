<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Route-Denial Inventory

- **Plan**: context/changes/testing-route-denial-inventory/plan.md
- **Scope**: Phases 1–5 of 5 (full plan)
- **Date**: 2026-09-13
- **Verdict**: NEEDS ATTENTION (all findings since fixed)
- **Findings**: 0 critical, 6 warnings, 4 observations (all fixed)

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | WARNING |
| Scope Discipline | PASS |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | WARNING |
| Success Criteria | PASS |

## Findings

### F1 — Retirement performed in one file of the three the plan named

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Plan Adherence
- **Location**: src/test/java/com/example/city_fix/report/ReportWebControllerTest.java:219, src/test/java/com/example/city_fix/report/StaffReportControllerTest.java:59,163
- **Detail**: Phase 3 listed three files under "Retire superseded assertions". Only SecurityConfigTest was touched. Three fully redundant denial tests remained — four anonymous redirects on the report routes, two on the staff routes, and the staff-cannot-file pair — all of which are matrix cells asserting no body, state, or ownership. This is the duplicate-failure problem Phase 3 existed to remove.
- **Fix**: Delete the three methods; the matrix covers every assertion they made.
- **Decision**: FIXED

### F2 — Actuator sits outside the completeness check, undocumented

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: src/test/java/com/example/city_fix/config/RouteInventoryTest.java:32
- **Detail**: Qualifying by requestMappingHandlerMapping (correct per research) excludes WebMvcEndpointHandlerMapping, so actuator endpoints were invisible to the completeness assertion. Widening management.endpoints.web.exposure.include would expose endpoints with the build green. The plan's own "What We're NOT Doing" claimed actuator "remains inside the completeness check"; it did not. The three filter-level routes were named honestly as a blind spot; this one was not.
- **Fix**: Autowire the actuator handler mapping as a second derivation source and record the four currently exposed endpoints in a named constant, so a newly exposed endpoint fails the build.
- **Decision**: FIXED

### F3 — A redirect without a Location header passes as GRANTED

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/test/java/com/example/city_fix/config/RouteAuthorizationMatrixTest.java:86
- **Detail**: The GRANTED branch guarded its redirect check on `location != null`, so a 3xx with no Location header skipped the check silently and counted as authorized.
- **Fix**: Drop the null guard and assert the header is present.
- **Decision**: FIXED

### F4 — everyAuthenticatedRouteInTheTableProducesCases contains a tautological assertion

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/test/java/com/example/city_fix/config/RouteAuthorizationMatrixTest.java:99-102
- **Detail**: authenticatedRoutes() is derived from EXPECTATIONS, so asserting that every route in it appears in expectations() is a mathematical identity that can never fail. It does not do what its comment claims. It is vacuous a second way: an emptied table makes allSatisfy pass over an empty set, and the following hasSize(0 * 4) passes too. The sibling hasSize check is the only real guard.
- **Fix**: Replace the allSatisfy block with a lower bound on table size, keeping the hasSize(routes * identities) check.
- **Decision**: FIXED

### F5 — No reverse check: a deleted route leaves four green cells asserting nothing

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: src/test/java/com/example/city_fix/config/RouteInventoryTest.java:36-53
- **Detail**: The completeness assertion is one-directional — derived minus table must be empty, but nothing asserts table minus derived is empty. Delete a route from a controller and all four of its cells stay green: anonymous still redirects to login via the catch-all, and the other three identities get 404, which GRANTED accepts. The table can accumulate rows for routes that no longer exist while appearing to prove authorization on them. This matters because §6.2 tells future authors the table is the single source to edit.
- **Fix**: Add a reverse assertion that every route in the table still exists in the derived set.
- **Decision**: FIXED

### F6 — Cookbook §6.4 is stale and now instructs the duplication this change removed

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Plan Adherence
- **Location**: context/foundation/test-plan.md §6.4
- **Detail**: §6.4 still reads "Extend TestcontainersConfig ... Annotate @SpringBootTest plus a bare @AutoConfigureMockMvc" and names StaffReportControllerTest as the reference test — which no longer does either. IntegrationTest appears nowhere outside its own javadoc. The Phase 5 commit updated §6.2 but not §6.4, so the next author will follow the stale instruction and re-implement the fixtures the extraction just removed — exactly the duplication §6.6's carried-forward note set out to end.
- **Fix**: Rewrite §6.4 to name IntegrationTest, keep the unique-identifier and no-fixed-row-count rules, and add the non-obvious constraint that a second @DynamicPropertySource forks a context for identical properties.
- **Decision**: FIXED

### F7 — GRANTED accepts a 5xx

- **Severity**: 📝 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/test/java/com/example/city_fix/config/RouteAuthorizationMatrixTest.java:83-85
- **Detail**: isNotIn(401, 403) is satisfied by a 500. If a handler began throwing on every request, its allow cells stay green. A 5xx is not a handler outcome, it is a broken one.
- **Fix**: Add .isLessThan(500) to the GRANTED assertion.
- **Decision**: FIXED

### F8 — LOGIN_REDIRECT is weaker than the assertion it replaced

- **Severity**: 📝 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/test/java/com/example/city_fix/config/RouteAuthorizationMatrixTest.java:73-78
- **Detail**: The deleted tests asserted Location equals "/login". The matrix asserts contains("/login"), which would also accept /login?expired or /loginXyz. A regression sending unauthenticated users to the expired-session URL would pass.
- **Fix**: Assert endsWith("/login") — the entry point emits no query string.
- **Decision**: FIXED

### F9 — Seven unused imports left by the extraction and the retirement

- **Severity**: 📝 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: SecurityConfigTest.java:11,15; AdminUserControllerTest.java:10; ReportWebControllerTest.java:7,16; RouteAuthorizationMatrixTest.java:6; RouteInventoryTest.java:8
- **Detail**: Dead imports orphaned when helpers moved to the base and when denial tests moved to the matrix. Compiles, but it is residue. Note the count may have shifted after the F1 retirement.
- **Fix**: Remove them.
- **Decision**: FIXED

### F10 — Static session state leaks past the class, and a javadoc overclaims

- **Severity**: 📝 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/test/java/com/example/city_fix/config/RouteAuthorizationMatrixTest.java:29-32,40-42
- **Detail**: Three static MockHttpSession fields are never invalidated, so they stay in the in-memory SessionRegistry for the rest of the JVM run. Benign today (sessions are keyed by distinct user ids, and parallel execution is confirmed off) but it is state outliving the class. Separately, the class javadoc claims "no case writes a row to the shared database" — true of the requests, but sessionFor writes three users rows.
- **Fix**: Correct the javadoc clause; optionally switch to @TestInstance(PER_CLASS) with instance fields to remove the statics.
- **Decision**: FIXED

## Triage note

All ten findings were fixed in one pass after the review.

During verification of F4, a `git checkout --` used to revert a deliberate mutation on
`RouteAuthorizationTable.java` silently discarded the uncommitted F2 fix on the same file
(the actuator constant and the blind-spot javadoc). The loss surfaced as a compile error on
the next build and was re-applied. The mutation-revert technique is safe only on files with
no uncommitted work; where a file carries both, save and restore the content instead of
reverting through git.
