---
change_id: testing-route-denial-inventory
title: "Route-denial inventory: every authenticated route and method asserts denial"
status: implemented
created: 2026-09-13
updated: 2026-09-13
archived_at: null
---

## Notes

Rollout Phase 2 of `context/foundation/test-plan.md` — "Route-denial inventory".

**Risks covered**

- **#3** — An access rule is changed, a route is silently opened, and the suite stays green because no test ever asserted denial on that route. (High impact × High likelihood.)
- **#6** — A route added after the revocation work skips the account-state check, and a deactivated account keeps working through it. (High impact × Low likelihood.)

**Test types planned:** integration.

**Risk response intent**

- **#3** — Prove that loosening or removing an access rule makes a test fail, for every authenticated route **and every HTTP method on it**, not only the remembered ones. Challenge the assumption that existing green security tests cover all routes, and that covering a route's GET covers its POST. Do **not** challenge the suite's honesty — Phase 1 research found no mocked security anywhere, so extend the existing real-chain tests rather than rebuilding them. Avoid ambiguous denial: asserting a status that a missing CSRF token would also produce, so the test would pass with the rule deleted.
- **#6** — Prove that a deactivated account is denied on every authenticated route, using the same enumeration as #3. Challenge the assumption that `isEnabled()` returning false is sufficient, and that a login-level test covers routes reached by an already-live session. Avoid accepting a mocked test as evidence for framework wiring.

**Carried forward from Phase 1** (`context/foundation/test-plan.md` §6.6): four test classes privately re-implement user and report setup — `StaffReportControllerTest`, `ReportWebControllerTest`, `AccountDeactivationTest`, `AdminUserControllerTest` — and `UserFixtures` is package-private in the `user` package, unreachable from `report`. This phase needs role-aware fixtures across packages, so it is the moment to extract them.

**Binding constraint established in Phase 1** (§6.1): CSRF is enabled on the web chain, so any denial test on a state-mutating route must send a valid CSRF token and be paired with a token-less case, or its 403 is unattributable. This matters more here than in Phase 1 — an enumeration sweeping every route and method will hit many state-mutating POSTs at once.
