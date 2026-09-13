# Ownership and Role Denial — Implementation Plan

## Overview

Close the single uncovered authorization hole in the test suite: nothing asserts that a
resident is refused `POST /staff/reports/{id}/status`. This is rollout Phase 1 of
`context/foundation/test-plan.md`, covering risks #1 and #2. Research established that
risk #1 is already defended and needs no new tests, so the work reduces to three test
methods in an existing class plus a cookbook update. No production code changes.

## Current State Analysis

The staff status-change endpoint is protected by exactly one line,
`src/main/java/com/example/city_fix/config/SecurityConfig.java:114`:

```java
.requestMatchers("/staff/**").hasAnyRole("STAFF", "ADMIN")
```

`StaffReportController` carries no `@PreAuthorize` (the class javadoc at
`StaffReportController.java:28-31` states the path matcher is deliberately the only gate),
the handler takes no `Authentication` parameter, and `StaffReportService` is explicitly
role-blind. Narrowing that matcher to `GET` would open status changes to every
authenticated resident **and the entire suite would stay green**.

The `/admin/**` surface has precisely this test — `SecurityConfigTest.java:113-122`, whose
comment reads: *"Narrowing /admin/** to GET would open account creation and deactivation to
staff, and without these two cases the suite would stay green."* `/staff/**` never received
the equivalent for writes.

What already exists and is trustworthy:

- `staffRoutes_forbiddenToResident_evenForTheirOwnReport` (`StaffReportControllerTest.java:70-82`)
  covers the three staff **GET** routes for a resident. The write is absent.
- `statusChange_persistsAndTheReportingResidentSeesIt` (`:132-155`) is the positive control:
  a STAFF session with a valid CSRF token succeeds and persists.
- Risk #1 is covered by `ReportWebControllerTest.java:209-229` (two real resident sessions,
  404 on detail and photo) plus list-scoping at `:176-190`.
- No test anywhere mocks the security mechanism it verifies — zero hits across
  `src/test/java` for `addFilters`, `@MockBean`, `@MockitoBean`, `standaloneSetup`,
  `@WebMvcTest`.

## Desired End State

`StaffReportControllerTest` contains three additional tests that make the following true:
deleting or narrowing `hasAnyRole("STAFF", "ADMIN")` at `SecurityConfig.java:114` fails the
build, disabling CSRF on the web chain fails the build, and dropping `ADMIN` from that
matcher fails the build. Verify by running the class and by the mutation checks in Phase 1's
manual criteria.

### Key Discoveries:

- **The CSRF trap.** CSRF is enabled on the web chain (`SecurityConfig.java:104-139` contains
  no `.csrf()` call; only the API chain configures it at `:80-83`). A resident POST *without*
  a token returns **403 — the same status as a role denial**. A denial test that omits
  `.with(csrf())` passes even with the role rule deleted. Every new denial test must send a
  valid token so the 403 is attributable to role alone.
- **`changeStatus` cannot be reused for denial.** The existing helper
  (`StaffReportControllerTest.java:205-211`) asserts `is3xxRedirection()` internally. Denial
  cases must call `mockMvc.perform(post(...))` directly.
- **No actor is recorded on a status change.** `Report.changeStatus`
  (`Report.java:111-115`) writes only `status` and `statusUpdatedAt`, so the persisted status
  is the only observable a denial test can assert against — there is no audit trail.
- **Transitions are unrestricted by design.** `StaffReportService.java:52-56` and
  `archive/2026-09-09-staff-triages-reports/plan.md:7` resolve PRD Open Question #1 as
  "any status to any status". No test in this phase may assert transition legality.
- **The container is shared and nothing is transactional**
  (`AccountDeactivationTest.java:313`). Every test owns unique emails and descriptions and
  must not assume an empty database.
- All required helpers already exist in the target class: `authenticateAs(email, Role)`
  (`:214-217`), `registerResident` (`:219-225`), `submitReport` (`:242-252`),
  `findByDescription` (`:254-259`). `csrf()`, `post`, `status` are already imported.

## What We're NOT Doing

- **No new tests for risk #1.** Research verified it is defended by query-level scoping and
  real-session cross-owner tests. Adding more would be the redundant-copies anti-pattern the
  test plan names. The test-plan §2 guidance was backported to say so.
- **No production code changes.** Not adding `@PreAuthorize` to `StaffReportController`;
  the single-gate design is a recorded, twice-reviewed decision
  (`archive/2026-09-09-staff-triages-reports/reviews/impl-review.md:114-126`). This phase
  tests the existing design, it does not change it.
- **No extraction of the duplicated test helpers.** Four classes privately re-implement user
  and report setup. Recorded for Phase 2's route inventory, which will need them everywhere.
- **No decision about an admin reading the resident `/reports/**` surface.** Never decided in
  any archived change; today an admin sees their own (empty) reporter-scoped list. This is a
  product question, not a test question — pinning it now would invent an oracle.
- **No anonymous-POST case.** Already proven for `/staff/**` GETs (`:84-93`) and in
  `SecurityConfigTest`.
- **No transition-legality assertions.** See Key Discoveries.
- **No fix for the bodiless-404 blank page** (open defect, twice skipped —
  `archive/2026-09-03-resident-reports-problem/reviews/impl-review.md:109-116` F6).

## Implementation Approach

Add three tests to `StaffReportControllerTest`, each targeting a distinct mutation of the
one line that protects the endpoint. The existing `statusChange_persistsAndTheReportingResidentSeesIt`
serves as the positive control, so the resident case differs from a known-passing request in
exactly one variable: the actor's role.

The three cases are chosen so that no two fail for the same cause:

| Test | Mutation it catches |
|---|---|
| resident + valid CSRF → 403, status unchanged | `hasAnyRole("STAFF","ADMIN")` deleted or narrowed to GET |
| staff + no CSRF → 403, status unchanged | CSRF disabled on the web chain |
| admin + valid CSRF → succeeds | `ADMIN` dropped from the matcher |

## Critical Implementation Details

**Attributable denial.** The resident case must send `.with(csrf())`. Without it the request
is rejected by the CSRF filter before authorization runs, the assertion still sees 403, and
the test passes with the role rule removed. The no-CSRF case exists precisely to prove the
two 403s have different causes: if someone later disables CSRF, that test fails while the
resident test keeps passing, and the pair remains interpretable.

## Phase 1: Status-change denial tests

### Overview

Three test methods in `StaffReportControllerTest` covering denial, denial-cause
disambiguation, and the ADMIN arm of the matcher.

### Changes Required:

#### 1. Resident is refused the status change

**File**: `src/test/java/com/example/city_fix/report/StaffReportControllerTest.java`

**Intent**: Prove a resident cannot change a report's status even on their own report, and
that the refusal leaves the persisted status untouched. This is the risk-#2 test and the
reason the phase exists.

**Contract**: New `@Test` method. A resident registers via `registerResident`, files a report
via `submitReport`, and POSTs to `/staff/reports/{id}/status` with their own session, a
`status` param, and `.with(csrf())`. Asserts `status().isForbidden()`, then re-reads the
report through `reportRepository` and asserts the status is still `ReportStatus.NEW` and
`getStatusUpdatedAt()` is still null. The CSRF token is load-bearing — see Critical
Implementation Details.

#### 2. Missing CSRF token is refused even for staff

**File**: `src/test/java/com/example/city_fix/report/StaffReportControllerTest.java`

**Intent**: Establish that a 403 on this route has two distinguishable causes, so the
resident test above cannot silently degrade into a CSRF test. Also pins CSRF enforcement on
the one state-mutating staff route, mirroring the `/admin/**` analogue at
`SecurityConfigTest.java:130-138`.

**Contract**: New `@Test` method. A STAFF session (via `authenticateAs`) POSTs a valid status
to the endpoint **without** `.with(csrf())`. Asserts `status().isForbidden()` and that the
persisted status is unchanged.

#### 3. Admin can change status

**File**: `src/test/java/com/example/city_fix/report/StaffReportControllerTest.java`

**Intent**: Pin the `ADMIN` arm of `hasAnyRole("STAFF", "ADMIN")`. The PRD grants admin
everything staff can do; dropping `ADMIN` from the matcher would break admin triage with no
test failing today.

**Contract**: New `@Test` method. An ADMIN session (via `authenticateAs(..., Role.ADMIN)`)
POSTs a status change with `.with(csrf())`. Asserts `is3xxRedirection()` and that the
persisted status reflects the change.

### Success Criteria:

#### Automated Verification:

- The three new tests pass: `./mvnw test -Dtest=StaffReportControllerTest`
- The full suite still passes: `./mvnw test`
- The project compiles: `./mvnw -q compile`

#### Manual Verification:

- Mutation check A: temporarily change `SecurityConfig.java:114` to `.requestMatchers(HttpMethod.GET, "/staff/**")`, confirm the resident test fails, then revert.
- Mutation check B: temporarily narrow the same matcher to `hasRole("STAFF")`, confirm the admin test fails, then revert.
- Mutation check C: temporarily add `.csrf(csrf -> csrf.disable())` to the web chain, confirm the no-CSRF test fails **and the resident test still passes**, then revert.
- Confirm each new test uses a unique email and report description, so repeated runs against the shared container do not collide.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 2: Cookbook and rollout sync

### Overview

Fill the test-plan cookbook sections this phase earns, and record the two items research
surfaced that belong outside this change.

### Changes Required:

#### 1. Cookbook entries

**File**: `context/foundation/test-plan.md`

**Intent**: Replace the §6.1 and §6.5 placeholders with the patterns this phase establishes,
so the next person adding an authorization test starts from the attributable-denial rule
rather than rediscovering the CSRF trap.

**Contract**: §6.1 ("Adding a cross-role access test") names the target class as the
reference, states the attributable-denial rule (valid CSRF token on denial tests, paired with
a positive control and a no-CSRF case), and gives the run command. §6.5 ("Choosing
integration over unit") records that this project's authorization evidence must come from the
real filter chain, citing the existing suite's total absence of security mocking. §6.6 gets a
two-to-three line note on what this phase found. No test code blocks — reference files by path.

#### 2. Carry-forward notes

**File**: `context/foundation/test-plan.md`

**Intent**: Record the duplicated test helpers as input to Phase 2's route inventory, since
that phase will need role-aware fixtures across packages.

**Contract**: A line in §6.6 naming the four classes that duplicate user/report setup and
stating that extraction belongs to the route-inventory phase.

#### 3. Rollout status

**File**: `context/foundation/test-plan.md`

**Intent**: Advance §3 Phase 1 to `complete` and bump the header date.

**Contract**: §3 row 1 Status cell → `complete`; "Last updated" line → today. Status
vocabulary is a parser literal — do not invent values.

### Success Criteria:

#### Automated Verification:

- No placeholder text remains in the two filled sections: `grep -n "TBD — see §3 Phase 1" context/foundation/test-plan.md` returns nothing
- §3 row 1 reads `complete`: `grep -n "Ownership and role denial" context/foundation/test-plan.md`
- No test code blocks were added to the plan: `grep -nE '^\s*```java' context/foundation/test-plan.md` returns nothing

#### Manual Verification:

- A reader unfamiliar with this change can follow §6.1 to add a new authorization test without rediscovering the CSRF ambiguity.
- §6.5 states the integration-over-unit rule in terms of framework wiring, consistent with `lessons.md`.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful.

---

## Testing Strategy

### Unit Tests:

None. The rule under test is framework wiring — a unit test would mock the mechanism being
verified, which is the failure mode `lessons.md` records.

### Integration Tests:

- Resident denied the status change, persisted status unchanged.
- Staff without CSRF denied, persisted status unchanged.
- Admin permitted, persisted status changed.

### Manual Testing Steps:

1. Run `./mvnw test -Dtest=StaffReportControllerTest` and confirm all tests pass.
2. Apply mutation check A (matcher narrowed to GET) and confirm the resident test fails.
3. Apply mutation check B (`hasRole("STAFF")`) and confirm the admin test fails.
4. Apply mutation check C (CSRF disabled) and confirm the no-CSRF test fails while the resident test passes.
5. Revert all three mutations and re-run the suite.

## Performance Considerations

None. Three additional methods on an already-running Spring context and shared container.
Docker remains required for this class, as for the other six integration classes.

## Migration Notes

None — test-only change, no schema or environment impact, nothing to run outside the repo.

## References

- Related research: `context/changes/testing-ownership-role-denial/research.md`
- Rollout phase definition: `context/foundation/test-plan.md` §3 Phase 1
- Pattern to mirror: `src/test/java/com/example/city_fix/config/SecurityConfigTest.java:113-138`
- Positive control: `src/test/java/com/example/city_fix/report/StaffReportControllerTest.java:132-155`
- Enforcement point under test: `src/main/java/com/example/city_fix/config/SecurityConfig.java:114`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Status-change denial tests

#### Automated

- [x] 1.1 The three new tests pass: `./mvnw test -Dtest=StaffReportControllerTest`
- [x] 1.2 The full suite still passes: `./mvnw test`
- [x] 1.3 The project compiles: `./mvnw -q compile`

#### Manual

- [x] 1.4 Mutation check A: matcher narrowed to GET makes the resident test fail
- [x] 1.5 Mutation check B: matcher narrowed to `hasRole("STAFF")` makes the admin test fail
- [x] 1.6 Mutation check C: CSRF disabled makes the no-CSRF test fail while the resident test still passes
- [x] 1.7 Each new test uses a unique email and report description

### Phase 2: Cookbook and rollout sync

#### Automated

- [ ] 2.1 No placeholder text remains in the two filled sections
- [ ] 2.2 §3 row 1 reads `complete`
- [ ] 2.3 No test code blocks were added to the plan

#### Manual

- [ ] 2.4 A new reader can follow §6.1 without rediscovering the CSRF ambiguity
- [ ] 2.5 §6.5 states the integration-over-unit rule consistently with `lessons.md`
