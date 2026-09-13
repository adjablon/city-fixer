# Ownership and Role Denial — Plan Brief

> Full plan: `context/changes/testing-ownership-role-denial/plan.md`
> Research: `context/changes/testing-ownership-role-denial/research.md`

## What & Why

Nothing in the suite asserts that a resident is refused `POST /staff/reports/{id}/status`.
The endpoint is protected by one line in `SecurityConfig` and carries no annotation, so
narrowing that matcher to `GET` would hand every authenticated resident the ability to change
any report's status — with the build still green. This is rollout Phase 1 of the test plan.

## Starting Point

Research found the suite in better shape than the test plan assumed. Report ownership is
enforced inside the persistence query rather than by a post-fetch check, a foreign report is
structurally indistinguishable from a missing one, and real-session cross-owner tests already
exist. No test anywhere mocks the security mechanism it verifies. The one genuine hole is
writes on the staff surface: `/admin/**` has a write-denial test, `/staff/**` does not.

## Desired End State

Three tests in `StaffReportControllerTest` such that deleting the role rule, dropping `ADMIN`
from it, or disabling CSRF each fails the build. The test plan's cookbook carries the
attributable-denial pattern so the next authorization test starts from it.

## Key Decisions Made

| Decision | Choice | Why | Source |
| --- | --- | --- | --- |
| Risk #1 coverage | Add nothing | Already defended by query-level scoping and real-session tests; more would be redundant copies | Research |
| Risk #2 coverage | Three cases | Each catches a different mutation: role rule, CSRF, the ADMIN arm | Plan |
| Denial attribution | Valid CSRF token on denial tests | Without it a 403 is ambiguous and the test passes with the role rule deleted | Research |
| Placement | `StaffReportControllerTest` only | Owns the endpoint, the helpers, and the positive control; a `SecurityConfigTest` mirror would fail for the same cause | Plan |
| Production changes | None | The single-gate design is a recorded, twice-reviewed decision | Research |
| Helper duplication | Note, don't extract | Refactoring four green classes mid-phase is churn; Phase 2 needs them anyway | Plan |
| Admin reads `/reports/**` | Out of scope | Never decided; pinning undecided behaviour would invent an oracle | Plan |
| Transition legality | Never assert it | Resolved as unrestricted in S-02; asserting rules would test a policy the product rejected | Research |

## Scope

**In scope:**
- Resident with valid CSRF → 403, persisted status unchanged
- Staff without CSRF → 403, persisted status unchanged
- Admin with valid CSRF → succeeds
- Test-plan §6.1, §6.5, §6.6 cookbook entries and §3 status

**Out of scope:**
- Any new risk-#1 test; any production code change; helper extraction; the anonymous-POST
  case; admin's read access to `/reports/**`; transition-legality assertions; the known
  bodiless-404 blank-page defect

## Architecture / Approach

The existing `statusChange_persistsAndTheReportingResidentSeesIt` is the positive control, so
the resident case differs from a known-passing request in exactly one variable — the actor's
role. The no-CSRF case exists so the two 403s have provably different causes: disable CSRF
later and that test fails while the resident test keeps passing, leaving the pair
interpretable rather than silently degraded.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Status-change denial tests | Three tests in one existing class | A denial test that omits the CSRF token passes for the wrong reason |
| 2. Cookbook and rollout sync | §6 patterns filled, §3 advanced to complete | Documentation quietly dropped because the code half is done |

**Prerequisites:** Docker running (this class extends `TestcontainersConfig`).
**Estimated effort:** One short session; three test methods and a documentation pass.

## Open Risks & Assumptions

- Assumes the single-gate matcher design stays as-is. If `@PreAuthorize` is later added to
  the staff controller, these tests still pass but no longer pin the matcher alone.
- The shared, non-transactional container means new tests must own unique emails and
  descriptions; a collision would surface as a confusing unrelated failure.
- Mutation checks are manual. Nothing automated proves these tests would fail if the rule
  were removed — that verification depends on someone running Phase 1's manual steps.

## Success Criteria (Summary)

- A resident cannot change any report's status, and the refusal is provably about their role
  rather than about a missing CSRF token.
- Admin triage keeps working, and would fail loudly if the matcher stopped granting it.
- The next person adding an authorization test finds the pattern in the cookbook.
