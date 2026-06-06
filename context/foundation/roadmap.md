---
project: "CityFix"
version: 1
status: draft
created: 2026-06-04
updated: 2026-06-04
prd_version: 1
main_goal: speed
top_blocker: time
---

# Roadmap: CityFix

> Derived from `context/foundation/prd.md` (v1) + auto-researched codebase baseline.
> Edit-in-place; archive when superseded.
> Slices below are listed in dependency order. The "At a glance" table is the index.

## Vision recap

City residents have no official channel to report infrastructure problems — potholes, broken streetlights, graffiti, damaged signs. Informal reports through social media or phone calls lose precise location and have no tracking. CityFix combines geo-located map-pin reporting with a transparent status workflow (new → in progress → resolved/rejected) so residents can report WHERE a problem is and city staff can track WHAT HAPPENS after the report.

## North star

**S-02: Staff triages reports and updates status** — completing this slice proves the full report-to-resolution loop end-to-end (resident submits a map-pinned report → staff changes its status → resident sees the update). This is the primary Success Criterion and validates the core hypothesis — the bet that combining map-pinned location with a transparent status workflow creates value neither delivers alone (PRD §Vision).

> The north star is the smallest end-to-end slice whose successful delivery proves the core product works — placed as early as prerequisites allow because everything else only matters if this works.

## At a glance

| ID   | Change ID                | Outcome (user can …)                                                                          | Prerequisites | PRD refs                              | Status   |
| ---- | ------------------------ | --------------------------------------------------------------------------------------------- | ------------- | ------------------------------------- | -------- |
| F-01 | auth-role-scaffold       | (foundation) Auth with role-based access; residents can register and log in                    | —             | FR-001, Access Control                | ready    |
| S-01 | resident-reports-problem | Place a map pin, submit a report with description/category/photo, and see it in "my reports"  | F-01          | US-01, FR-003, FR-004, FR-005, FR-008 | proposed |
| S-02 | staff-triages-reports    | View all reports on a map and change their status; resident sees the update                    | F-01, S-01    | US-02, FR-006, FR-007                 | proposed |
| S-03 | admin-manages-staff      | Create and deactivate office staff accounts                                                    | F-01          | FR-002                                | proposed |

## Streams

Navigation aid — groups items that share a Prerequisites chain. Canonical ordering still lives in the dependency graph below; this table is the proposed reading order across parallel tracks.

| Stream | Theme      | Chain                     | Note                                                                          |
| ------ | ---------- | ------------------------- | ----------------------------------------------------------------------------- |
| A      | Core loop  | `F-01` → `S-01` → `S-02` | All must-have FRs for the report-to-resolution loop; speed goal drives priority |
| B      | Admin      | `S-03`                    | Joins Stream A at `F-01`; parallel with core loop                              |

## Baseline

What's already in place in the codebase as of 2026-06-04 (auto-researched + user-confirmed).
Foundations below assume these are present and do NOT re-scaffold them.

- **Frontend:** absent — empty `templates/` and `static/` dirs, no UI framework, no `package.json`
- **Backend / API:** partial — Spring Boot 4.0.6 entrypoint (`CityFixApplication.java`) + `HealthController` only; no domain controllers, services, or repositories
- **Data:** partial — PostgreSQL driver + Spring Data JPA declared in `pom.xml`, Hibernate configured in `application-azure.properties`; no entity classes or migrations
- **Auth:** absent — no Spring Security, no user/role entities, no session/token handling
- **Deploy / infra:** partial — GitHub Actions CI/CD (`.github/workflows/deploy.yml`) + `application-azure.properties` present; Azure App Service provisioned and healthy; no Dockerfile or IaC
- **Observability:** partial — Spring Boot Actuator with `/health` and `/info` exposed; no custom logging, metrics, or error tracking

## Foundations

### F-01: Auth and role scaffold

- **Outcome:** (foundation) Auth with role-based access configured; residents can register and log in; role model (resident, staff, admin) in place.
- **Change ID:** auth-role-scaffold
- **PRD refs:** FR-001, Access Control
- **Unlocks:** S-01, S-02, S-03
- **Prerequisites:** —
- **Parallel with:** —
- **Blockers:** —
- **Unknowns:**
  - How is the first admin account provisioned (seed script, env var, manual insert)? — Owner: user. Block: no.
- **Risk:** Sequenced first because every route requires authentication (PRD: "Unauthenticated users: no access"). Spring Security configuration complexity could eat time on a tight 3-week budget.
- **Status:** ready

## Slices

### S-01: Resident submits a geo-located report

- **Outcome:** Resident can place a pin on a map, fill in a description, select a category, optionally attach a photo, submit the report, and see it in "my reports" with status "new".
- **Change ID:** resident-reports-problem
- **PRD refs:** US-01, FR-003, FR-004, FR-005, FR-008
- **Prerequisites:** F-01
- **Parallel with:** S-03
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Sequenced immediately after auth because it carries the core tech risk — map library integration is the acknowledged biggest technical challenge (FR-003 Socratic: "Map integration is the biggest tech risk"). Also introduces the frontend layer (absent in baseline) and the report data model.
- **Status:** proposed

### S-02: Staff triages reports and updates status

- **Outcome:** Office staff can view all submitted reports on a map view and change a report's status (new → in progress → resolved/rejected); the status change is visible to the resident who filed the report.
- **Change ID:** staff-triages-reports
- **PRD refs:** US-02, FR-006, FR-007
- **Prerequisites:** F-01, S-01
- **Parallel with:** S-03
- **Blockers:** —
- **Unknowns:**
  - What are the valid status transitions — can a report go from "new" directly to "rejected", or must it pass through "in progress" first? — Owner: user. Block: no (default to free transitions if unresolved).
- **Risk:** Sequenced after S-01 because staff cannot triage reports that don't exist. Completes the north star — the full report-to-resolution loop. Staff map view reuses the map component from S-01 but may need different interaction patterns (viewing all reports vs placing a single pin).
- **Status:** proposed

### S-03: Admin manages staff accounts

- **Outcome:** Admin can create and deactivate office staff accounts.
- **Change ID:** admin-manages-staff
- **PRD refs:** FR-002
- **Prerequisites:** F-01
- **Parallel with:** S-01, S-02
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Sequenced last — independent of the core report-to-resolution loop and parallelizable with S-01/S-02. Standard CRUD; lowest technical risk. First candidate to defer if time runs short, without affecting core product validation.
- **Status:** proposed

## Backlog Handoff

| Roadmap ID | Change ID                | Suggested issue title                                  | Ready for `/10x-plan` | Notes                              |
| ---------- | ------------------------ | ------------------------------------------------------ | --------------------- | ---------------------------------- |
| F-01       | auth-role-scaffold       | Auth and role scaffold (registration, login, roles)    | yes                   | Run `/10x-plan auth-role-scaffold` |
| S-01       | resident-reports-problem | Resident submits a geo-located report                  | no                    | Depends on F-01                    |
| S-02       | staff-triages-reports    | Staff triages reports and updates status               | no                    | Depends on F-01, S-01              |
| S-03       | admin-manages-staff      | Admin manages staff accounts                           | no                    | Depends on F-01                    |

## Open Roadmap Questions

1. **What are the valid status transitions?** Can a report go from "new" directly to "rejected"? Or must it pass through "in progress" first? The four statuses are defined (new, in progress, resolved, rejected) but the allowed transitions between them are not. — Owner: user. Affects: S-02. Non-blocking — defaults to free transitions if unresolved.
2. **How is the first admin account provisioned?** The admin role manages office staff accounts (FR-002), but the system needs a bootstrap mechanism for the initial admin. — Owner: user. Affects: F-01. Non-blocking — can be resolved during implementation.

## Parked

- **Notifications (email/push/SMS)** — Why parked: PRD §Non-Goals #1. Residents check status manually via "my reports" list.
- **Public report map for residents** — Why parked: PRD §Non-Goals #2. Residents see only their own reports.
- **Mobile-native app** — Why parked: PRD §Non-Goals #3. Mobile browser is the mobile experience.
- **Analytics or reporting dashboard** — Why parked: PRD §Non-Goals #4. Staff see the map and reports directly.
- **Geo-search (FR-009)** — Why parked: PRD §Non-Goals #5. Nice-to-have, not part of core loop.
- **Configurable categories** — Why parked: PRD §Non-Goals #6. Categories hardcoded for MVP (FR-008).

## Done

(Empty on first generation. `/10x-archive` appends entries here when a change is archived.)