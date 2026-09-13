# Route-Denial Inventory — Plan Brief

> Full plan: `context/changes/testing-route-denial-inventory/plan.md`
> Research: `context/changes/testing-route-denial-inventory/research.md`

## What & Why

Two matcher lines carry the entire staff and admin surface, and nothing stops a new route being
added with no authorization decision at all. A hand-written checklist would rot exactly like the
tests it replaces, so this phase derives the route list from Spring's own handler mapping and
fails the build when a derived route is missing from an authored expectation table.

## Starting Point

Coverage is real but patchy and the patchiness is invisible: `GET /admin/users/new` has no denial
test, both logout routes are untested, several routes are covered on one method only. Worse,
`GET /reports/new` is denied for staff and anonymous with **no successful resident case** — so
broadening the rule to deny everyone leaves the suite green. Separately, research disproved the
test plan's model of risk #6: account state is not checked per request at all.

## Desired End State

Adding a route without an authorization decision fails the build. Every authenticated route has
an asserted outcome for anonymous, resident, staff and admin — allow cells included, so a
deny-everyone rule fails immediately. Both chains' expiry semantics are pinned, and a chain
configured without session management fails a test.

## Key Decisions Made

| Decision | Choice | Why | Source |
| --- | --- | --- | --- |
| Route list | Derived from `getHandlerMethods()` | A hand-maintained list rots like the tests it replaces | Research |
| Expected outcomes | Authored from the PRD | Authorization rules are not introspectable — deriving them would give a mirror test | Research |
| Filter-level routes | Named constant, hand-maintained | `POST /login`, `POST /logout`, `POST /api/auth/logout` never reach `DispatcherServlet` | Research |
| Completeness check | Fail-closed on the derived set | A warning nobody reads is how the current gaps arrived | Plan |
| Admin reads `/reports/**` | Reporter-scoped, same as anyone | PRD makes admin a superset of *staff*, and staff is never granted the resident surface | Plan |
| Table reach | Authenticated routes × four identities | `permitAll` cells would encode Boot's behaviour, not ours | Plan |
| Attribution | Assert allow cells too | Makes a deny-everyone rule fail by construction rather than by memory | Plan |
| Risk #6 scope | Per-chain semantics + CSRF × expiry + config guard | One filter denies every route, so a route sweep would be redundant copies | Research |
| Fixture extraction | `public abstract` base, six classes extend | Cache-neutral; follows the `TestcontainersConfig` precedent | Research |
| Context count | Stays at 2 | Collapsing to 1 would lose the only proof the app boots without MockMvc auto-config | Plan |

## Scope

**In scope:** shared integration-test base; authored route table; fail-closed completeness
assertion; parameterized allow/deny matrix over authenticated routes × four identities;
per-chain expiry semantics including the unasserted API body; CSRF × expired-session interaction;
chain-configuration guard; cookbook §6.2 and §6.6.

**Out of scope:** production code changes; a per-route sweep for the deactivated identity;
matrix rows for `permitAll`, actuator and static; tests for `POST /register` (input-contract,
rollout Phase 3); collapsing the suite to one Spring context; behavioural depth in allow cells.

## Architecture / Approach

The table is the artifact — authored from the PRD, holding an expected outcome per authenticated
route × identity. Two mechanisms consume it independently: a completeness assertion compares it
against the derived route set and fails on anything missing, and a parameterized matrix exercises
every cell. Because allow cells are asserted, attribution is structural rather than remembered.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Shared test base | One home for helpers four classes duplicate | Duplicating `@DynamicPropertySource` instead of inheriting it forks a second container-backed context |
| 2. Table + completeness | The anti-rot guard, before any expectations | Reading an empty method set as "no methods" silently skips Boot's error mappings |
| 3. Authorization matrix | Every authenticated cell, allow and deny | Allow cells on POST routes mutate state; cases need unique identifiers |
| 4. Account-state expiry | Per-chain semantics + config guard | The CSRF × expiry outcome is genuinely unknown and must be observed before it is asserted |
| 5. Cookbook + sync | §6.2, phase note, rollout status | Documentation dropped once the code half is done |

**Prerequisites:** Docker running; Phase 1 lands before 2–4.
**Estimated effort:** ~2 sessions across five phases.

## Open Risks & Assumptions

- The three filter-level routes are hand-maintained. A **fourth** would not be caught
  automatically — a residual gap the cookbook must state rather than paper over.
- Retiring superseded denial assertions in Phase 3 risks deleting one that asserted more than
  access. The rule is: keep anything asserting persisted state, a body, or ownership.
- The matrix authenticates per case, so wall-clock grows with case count.
- Mutation checks are manual. Nothing automated proves these tests would fail if the rules were
  removed.

## Success Criteria (Summary)

- A new route cannot be merged without an authorization decision.
- A rule that denies everyone fails a test, not just a rule that permits everyone.
- A chain added without session management fails a test, so deactivation cannot silently stop
  applying.
