---
change_id: testing-ownership-role-denial
title: "Ownership and role denial: residents cannot read or act on others' reports"
status: archived
created: 2026-09-13
updated: 2026-09-13
archived_at: 2026-09-13T19:22:35Z
---

## Notes

Rollout Phase 1 of `context/foundation/test-plan.md` — "Ownership and role denial".

**Risks covered**

- **#1** — A resident opens a report they did not file and sees its description, photo, and reporter identity. (High impact × High likelihood.)
- **#2** — A resident advances a report's status, an action the PRD reserves for office staff. (High impact × Medium likelihood.)

**Test types planned:** integration.

**Risk response intent**

- **#1** — Prove that resident B requesting resident A's report is denied, and that the denial leaks no signal the report exists. Challenge the assumption that a 200 for the owner implies a denial for the non-owner, and that a list scoped by reporter means the detail view is scoped too. Avoid happy-path-only coverage.
- **#2** — Prove that a resident's status-change request is refused and the persisted status is unchanged afterwards. Challenge the assumption that hiding the control in the template equals denying the request. Avoid asserting absent UI markup instead of a refused request plus an unchanged row.

**Project constraint** (`context/foundation/lessons.md`): never mock the mechanism under verification. This codebase has already shipped a green suite over a no-op security wiring — mock the collaborators of the thing under test, not the framework behaviour being asserted.
