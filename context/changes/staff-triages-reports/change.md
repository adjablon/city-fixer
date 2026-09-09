---
change_id: staff-triages-reports
title: Staff triages reports and updates status
status: implementing
created: 2026-09-09
updated: 2026-09-09
archived_at: null
---

## Notes

Roadmap S-02 (north star): office staff view all submitted reports on a map view and change a report's status (new -> in progress -> resolved/rejected); the status change is visible to the resident who filed the report. PRD refs US-02, FR-006, FR-007. Open roadmap question to resolve during planning: which status transitions are valid (default to free transitions if unresolved).

### Deliberate convention deviations

Recorded so an implementation review reads these as decisions rather than drift.

- **The first mutator on an entity** (`Report.changeStatus`). Every entity in the project is
  constructor-plus-getters with no setters. A status transition needs a write, so it is given
  a named domain method that also stamps `statusUpdatedAt`, rather than a bare `setStatus`
  that would let a caller change the status without recording when.
- **The project's first authorization rules**, both landing here: a `/staff/**` path matcher
  in `SecurityConfig` and `@PreAuthorize("hasRole('RESIDENT')")` on the two report-creation
  handlers. `@EnableMethodSecurity` had been switched on and unused since F-01. The matcher
  grants `hasAnyRole("STAFF", "ADMIN")` because the PRD gives admin everything staff can do —
  a `hasRole("STAFF")` rule would have locked admins out of triage.
- **The first `@EntityGraph`** (`ReportRepository.findWithReporterById`). The repository had
  only plain derived finders. The staff detail page renders the reporter's email and
  `Report.reporter` is LAZY, so the fetch join is explicit rather than left to open-in-view.
- **The first `RedirectAttributes` flash attribute.** The codebase had no post-redirect-get
  success path at all — errors were re-rendered onto the same view. A status change must not
  be re-submittable by refresh, so it redirects and carries its confirmation as a flash.
- **`StaffSeeder` duplicates `AdminSeeder` rather than refactoring it.** The class is
  short-lived — S-03 (`admin-manages-staff`) replaces it — and leaving `AdminSeeder` untouched
  keeps its existing test honest. It does deliberately *diverge* in one respect: it normalises
  the seed email with `trim().toLowerCase(Locale.ROOT)`, because `CustomUserDetailsService`
  lowercases before lookup, so a mixed-case seeded address could never log in. `AdminSeeder`
  still has that latent bug; fixing it is out of this slice's scope.

### Smaller judgement calls

- `statusUpdatedAt` renders through the same zone-bound formatter as `createdAt`, so it
  reproduces the S-01 impl-review's skipped F8 (server zone, UTC in production). Accepted
  during planning: consistently wrong rather than newly wrong, and still a single-place fix.
- `StaffReportService.changeStatus` relies on dirty checking inside its transaction instead of
  calling `save`. `StaffReportServiceTest` asserts `save` is never invoked, so the reliance is
  pinned rather than incidental.
- A same-status submission is a no-op that returns `UNCHANGED` and does not touch
  `statusUpdatedAt`. With transitions unrestricted the form can post the current value, and
  writing anyway would make the timestamp meaningless.
- `ReportPin` carries no description and no reporter, and `createdAt` is pre-formatted
  server-side. The payload is unbounded and embedded in the page, so user-supplied text is
  kept out of it entirely; popups are built with `textContent`, never `innerHTML`.
- Report-to-pin mapping lives in `StaffReportController`. The project has no mapper layer and
  introducing one for seven fields was not warranted.
- The staff detail page reuses `/js/report-detail-map.js` rather than getting its own copy,
  under the `#detail-map` element contract. This departs from the one-script-per-page habit,
  which exists for page-specific behaviour; this behaviour is identical.
- Marker colours in `staff-report-map.js` are copied from the status badge palette, with a
  comment in each file naming the other, so the map and the badges cannot drift apart.
