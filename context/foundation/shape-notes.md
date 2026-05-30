---
project: "CityFix"
context_type: greenfield
created: 2026-05-18
updated: 2026-05-18
product_type: web-app
target_scale:
  users: medium
  qps: low
  data_volume: small
timeline_budget:
  mvp_weeks: 3
  hard_deadline: null
  after_hours_only: true
checkpoint:
  current_phase: 8
  phases_completed: [1, 2, 3, 4, 5, 6, 7]
  gray_areas_resolved:
    - topic: "pain category"
      decision: "missing capability + data trapped informally"
    - topic: "core insight"
      decision: "precise map-pinned location + transparent status workflow together"
    - topic: "primary persona"
      decision: "city resident (reporter)"
    - topic: "auth method"
      decision: "login (email + password or OAuth)"
    - topic: "role model"
      decision: "three roles: resident, office staff, admin"
    - topic: "FR-001 login friction"
      decision: "kept — identity is worth the friction for 'my reports' and role separation"
    - topic: "FR-002 admin UI scope"
      decision: "kept — city office needs self-service staff onboarding"
    - topic: "FR-003 map tech risk"
      decision: "kept — map pin is the core insight, libraries exist"
    - topic: "FR-006 staff view"
      decision: "refined — staff sees reports on a map view, not just a list"
    - topic: "FR-007 status workflow"
      decision: "expanded — new, in progress, resolved, rejected"
    - topic: "FR-008 categories"
      decision: "hardcoded for MVP — configurable admin UI deferred to post-MVP"
  frs_drafted: 9
  quality_check_status: accepted
---

## Vision & Problem Statement

No official channel exists for city residents to report infrastructure problems — potholes, broken streetlights, graffiti, trash, damaged signs. Reports that do happen through informal means (social media posts, phone calls to random office numbers) are unstructured, lose precise location, and have no tracking or accountability.

The insight: precise map-pinned location and transparent status workflow together create what neither alone delivers. The resident can report WHERE a problem is with accuracy; the city office can track WHAT HAPPENS after the report. The combination of geo-located reporting and visible status tracking is the product.

## User & Persona

### Primary persona

**City resident (reporter)** — a person who encounters a broken streetlight, pothole, graffiti, or other infrastructure issue while going about their day and wants to report it with precise location so it actually gets fixed. They have no existing official channel and currently either do nothing or resort to informal complaints with no follow-up.

### Secondary persona (noted, not MVP focus)

**City office staff (manager)** — the official who triages incoming reports, assigns them for resolution, and tracks status through to completion. They currently have no visibility into what infrastructure problems exist across the city.

## Access Control

**Auth method:** Login (email + password or OAuth).

**Roles:**

- **Resident** — can create reports (description, map pin, photo), view own reports, track status of own reports.
- **Office staff** — can view all reports, change report statuses, manage and triage reports.
- **Admin** — can manage office staff accounts, configure report categories, plus everything office staff can do.

**Unauthenticated users:** no access — login required to report or view reports.

## Success Criteria

### Primary

- A resident can file a geo-located report (map pin + description + category + optional photo) and later see a status update made by office staff. The full report-to-resolution loop works end-to-end.

### Secondary

- A resident returns to the app to check whether their report progressed — proves the status workflow drives engagement beyond the initial submission.

### Guardrails

- Location accuracy: a map pin must reflect the real problem location. A misplaced pin makes the report useless for dispatch.
- Report data privacy: residents' personal data and reports must not leak to other residents. Only staff and admin roles see all reports.

**Timeline budget:** 3 weeks after-hours work.

**Deferred to post-MVP:** geo-search (searching reports within distance X from a point) — nice-to-have, not part of first flow.

## User Stories

### US-01: Resident reports an infrastructure problem

- **Given** a logged-in resident viewing the city map
- **When** they place a pin on the map, fill in a description, select a category, optionally attach a photo, and submit the report
- **Then** the report is saved with the precise location and appears in their "my reports" list with status "new"

#### Acceptance Criteria
- Report must include: map pin location, description, category (required); photo (optional)
- After submission, the report appears immediately in the resident's "my reports" list
- Report status defaults to "new"

### US-02: Office staff updates a report status

- **Given** a logged-in office staff member viewing all submitted reports
- **When** they select a report and change its status (e.g. new → in progress → resolved)
- **Then** the status change is saved and visible to the resident who filed the report

#### Acceptance Criteria
- Staff can see all reports regardless of who filed them
- Status change is persisted and reflected in the resident's view of their report

## Functional Requirements

### Authentication & accounts
- FR-001: Resident can register and log in (email + password or OAuth). Priority: must-have
  > Socrates: Counter-argument considered: "Login friction kills adoption — residents encounter a problem in the moment and forcing registration means most won't bother." Resolution: kept; identity is worth the friction because 'my reports' and role separation require it. Adoption friction is a v2 optimization problem.

- FR-002: Admin can manage office staff accounts (create, deactivate). Priority: must-have
  > Socrates: Counter-argument considered: "Admin panel is MVP scope creep — seed staff accounts manually via script for v1." Resolution: kept; city office needs self-service staff onboarding from day one.

### Reporting
- FR-003: Resident can place a pin on a map to mark a problem location. Priority: must-have
  > Socrates: Counter-argument considered: "Map integration is the biggest tech risk — a simpler address text field would ship faster." Resolution: kept; precise map location IS the core product insight. Map libraries exist; this is not from scratch.

- FR-004: Resident can submit a report with description, category, and optional photo. Priority: must-have
  > Socrates: No counter-argument; it stands as written.

- FR-005: Resident can view a list of their own reports with current status. Priority: must-have
  > Socrates: Counter-argument considered: "Email/push notifications would be better than polling a list." Resolution: kept as list view only for MVP; notifications add infrastructure complexity (email/push). The list proves the feedback loop works; notifications are a v2 enhancement.

### Report management
- FR-006: Office staff can view all submitted reports on a map view. Priority: must-have
  > Socrates: Counter-argument considered: "Staff should see reports ON the map, not in a table — spatial context makes triage efficient." Resolution: refined; FR updated to specify map view for staff. Consistent with resident experience and enables spatial triage.

- FR-007: Office staff can change the status of a report (new → in progress → resolved → rejected). Priority: must-have
  > Socrates: Counter-argument considered: "Fixed linear workflow is too rigid — what about rejected, duplicate, cannot fix?" Resolution: refined; status set expanded to include 'rejected' for reports that aren't actionable (spam, duplicates, out of scope).

### Configuration
- FR-008: Report categories are hardcoded for MVP (pothole, streetlight, graffiti, trash, sign, other). Priority: must-have
  > Socrates: Counter-argument considered: "Hardcode categories for MVP — they rarely change and a config UI eats budget." Resolution: refined; categories hardcoded for MVP. Configurable admin UI deferred to post-MVP.

### Post-MVP
- FR-009: Resident can search reports within distance X from a chosen map point. Priority: nice-to-have
  > Socrates: Deferral confirmed; geo-search is useful but not part of the core report-to-resolution loop.

## Business Logic

CityFix routes geo-located infrastructure reports through a role-gated status workflow (new → in progress → resolved/rejected) where the map serves as the shared spatial context for both the resident who reports and the office staff who triages and resolves.

The workflow consumes: a map-pinned location, a text description, a category selection, and an optional photo — all provided by the resident at the moment they encounter a problem. Its output is a trackable report that moves through status states visible to both sides. The resident encounters the workflow as a submission form followed by a status trail on their "my reports" list; the office staff encounters it as a map of incoming reports they triage and advance through statuses.

The map is not decorative — it is the shared spatial reference that makes both reporting (resident pins a location) and triage (staff sees spatial clusters and priority areas) meaningful. Without the map, the workflow is a generic ticket system.

## Non-Functional Requirements

- Map interactions (pin placement, report loading, pan/zoom) and report submission must feel responsive — continuous visible feedback during any operation that takes longer than two seconds.
- The product must work on mobile browsers (residents report from the street on their phones) and desktop browsers (office staff manage reports from the office).

## Non-Goals

1. **No notifications (email/push/SMS)** — residents check status manually via "my reports" list. Notification infrastructure is post-MVP.
2. **No public report map for residents** — residents see only their own reports. A city-wide public transparency map is a later feature.
3. **No mobile-native app** — web app only. Mobile browser is the mobile experience; no iOS/Android native build.
4. **No analytics or reporting dashboard** — no charts, heatmaps, or statistics for the city office. They see the map and reports directly; analytics is post-MVP.
5. **No geo-search** — searching reports within distance X from a point is deferred (FR-009, nice-to-have).
6. **No configurable categories** — categories hardcoded for MVP (FR-008); admin configuration UI is post-MVP.

## Quality cross-check

All 5 elements present. No gaps. Status: accepted.
