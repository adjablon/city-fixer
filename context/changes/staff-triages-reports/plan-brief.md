# Staff Triages Reports and Updates Status — Plan Brief

> Full plan: `context/changes/staff-triages-reports/plan.md`

## What & Why

Roadmap slice **S-02**, the north star. Office staff get a map of every submitted report and can change any report's status; the resident who filed it sees the new status and when it changed. This closes the report-to-resolution loop and proves the PRD's core bet — that map-pinned location plus a transparent status workflow creates value neither delivers alone. Covers **US-02**, **FR-006**, **FR-007**.

## Starting Point

F-01 and S-01 are archived and done, and S-01 was built to hand this slice a runway: all four `ReportStatus` values exist with labels, badge CSS for the three unreached statuses is already written, and `ROLE_` prefixing already makes `hasRole('STAFF')` work. What is missing is the staff side entirely — and four specific absences shape the work: `Report` has no mutator and no `updatedAt`, every repository finder is reporter-scoped by design, no STAFF account can be created at all, and the codebase contains no authorization rule of any kind despite `@EnableMethodSecurity` having been on since F-01.

## Desired End State

A staff user follows a "Triage reports" link to `/staff/reports` and sees every report as a status-coloured marker on a Leaflet map. Clicking through to a report shows the description, reporter, photo and pin, plus a status form; changing the status returns to the page with a confirmation and a fresh "Status updated" timestamp. The resident who filed it opens their own report and sees the new badge and that date. A resident who reaches for any `/staff/**` URL gets 403, and staff can no longer file reports.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) |
| --- | --- | --- |
| Status transitions | Any status to any status | Resolves PRD Open Question #1 with the documented default; FR-007's arrow notation describes the common path, not a constraint. |
| Staff surface | Separate `/staff/**` pages and a separate `StaffReportService` | Keeps `ReportService`'s "every read is reporter-scoped" guarantee structurally intact instead of making it depend on a role branch. |
| Map data transport | Server-rendered JSON in a `data-reports` attribute | Extends the existing server → `data-*` → `dataset` flow rather than introducing the project's first `fetch` plus the `/api` chain's known CSRF and error-format gaps on the north-star slice. |
| Pin payload | No description, no reporter | Keeps user-supplied text out of the JSON embedded in the page and keeps an unbounded payload small. |
| Status change UX | Form on the staff detail page, post-redirect-get with a flash message | CSRF comes free from the Thymeleaf dialect, and one canonical place beats injecting per-report forms into JS-built popups. |
| Audit trail | Nullable `statusUpdatedAt` only | Makes "the resident sees the update" concrete beyond a badge; a nullable column is the one change `ddl-auto=update` applies reliably. |
| Same-status submit | No-op, reported as "unchanged" | With unrestricted transitions the form can post the current value; not writing keeps the timestamp meaningful. |
| Staff accounts | `staff.seed.*` properties, mirroring `admin.seed.*` | Nothing can create a STAFF user today, which blocks both browser verification and the tests; S-03 supersedes this. |
| Reporter identity | Email on the staff detail page only, via `@EntityGraph` | Gives triage context without putting personal data in the map payload or N+1-ing an unbounded list. |
| Denial semantics | 403 from a `hasAnyRole('STAFF','ADMIN')` path matcher | Correct for a role boundary and impossible to forget per-method; ADMIN is included because the PRD grants admin everything staff can do. |
| Map volume | All reports, newest first, unbounded, no new index | Matches the no-pagination baseline the last impl-review blessed at `data_volume: small`, and the plan states the missing index rather than assuming one. |
| Inherited findings | Close F9 (resident-only creation) only | The last review deferred that decision to this slice by name; the timezone and photo-caching findings stay skipped. |

## Scope

**In scope:** `Report.changeStatus` plus a nullable `statusUpdatedAt`; `staff.seed.*` seeding; unscoped and fetch-joined finders; `StaffReportService`; the first `hasRole` matcher and the first `@PreAuthorize`; `/staff/reports` map with a status-coloured marker per report; `/staff/reports/{id}` detail with reporter, photo and status form; a staff photo route; the resident-side "Status updated" line; unit tests plus cross-role integration and security tests.

**Out of scope:** transition rules, status history and "changed by"; pagination, filtering, sorting, search and geo-search; any new index; notifications; admin management of staff accounts (S-03); editing or deleting reports; a public resident-facing map; analytics; any JSON API; the inherited timezone and photo-caching findings; `@ControllerAdvice`, error pages, CSS frameworks, layout fragments and frontend build tooling.

## Architecture / Approach

Staff get their own vertical slice through the existing layers. `StaffReportService` holds every read that crosses the ownership boundary, backed by two new finders on the shared `ReportRepository`; `ReportService` is not touched, not parameterised and not made role-aware. `StaffReportController` serves `/staff/**` on the existing web filter chain, so CSRF and form login come free, and the whole surface is gated by one matcher in `SecurityConfig` placed ahead of `anyRequest().authenticated()`. The map payload is built in the controller as a list of `ReportPin` records, serialized with Spring's injected `ObjectMapper`, written into a `data-reports` attribute, and read by a third page-specific Leaflet script that renders one circle marker per pin with popups built via `textContent`.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Data model, transition and seeding | `changeStatus` + `statusUpdatedAt`, `staff.seed.*` | A schema change under `ddl-auto=update`; nullable is the safe case, but pre-existing rows stay NULL forever |
| 2. Staff read and transition service | Unscoped finders, `StaffReportService`, resident-only creation | Relaxing ownership scoping without eroding the resident surface's structural guarantee |
| 3. Staff map page | `/staff/reports`, first `hasRole` matcher, pin payload, third map script | Matcher placed after `anyRequest()` is silently dead; a collection serialized into the page is a new pattern |
| 4. Staff detail and status change | Detail view, status form, PRG + first flash, resident-visible timestamp | First `RedirectAttributes` and first `@EntityGraph`, on the page that closes the loop |
| 5. Tests and verification | Cross-role integration and security tests, packaging | `@WithMockUser` cannot drive these routes; the STAFF fixture must be a real persisted user |

**Prerequisites:** F-01 and S-01 complete (both archived). Docker running for Testcontainers — the Rancher Desktop socket path (`~/.rd/docker.sock`) has broken this twice before. `STAFF_EMAIL` / `STAFF_PASSWORD` set locally for manual verification.
**Estimated effort:** ~2–3 sessions across 5 phases — smaller than S-01's 3–4 because no new layer is introduced; the map, the templates and the test harness all follow patterns that now exist.

## Open Risks & Assumptions

- **`statusUpdatedAt` ships with a known display bug.** It renders through the same server-zone formatter as `createdAt`, so a Warsaw user sees UTC — S-01 impl-review F8, deliberately still skipped. Consistently wrong rather than newly wrong, and still a single-place fix.
- **The staff map payload is unbounded by decision.** Fine at MVP volume; the first symptom of growth will be page weight, and the natural response is a status filter plus a `(status, created_at)` index, both out of scope here.
- **The unbounded photo re-send (F4, skipped) gains one more entry point.** The staff detail page serves one photo per view, like the resident page, so the exposure is widened but not multiplied.
- **A denied request shows Boot's Whitelabel page.** No `AccessDeniedHandler` and no error page exist anywhere, so the 403 a resident gets on `/staff/**` is correct but unstyled. Error-page work stays out of scope; the plan records this so it is not mistaken for a defect during verification.
- **`StaffSeeder` duplicates `AdminSeeder` rather than refactoring it.** A deliberate small cost: the class is short-lived, and leaving `AdminSeeder` untouched keeps its passing test honest.
- **The precedent that F-01's critical finding came from silently drifting from the planned security mechanism holds here too** — this slice lands the project's first authorization rules, so raise an addendum rather than improvising if the plan proves wrong mid-flight.

## Success Criteria (Summary)

- A staff user sees every report on a map and can move any report to any status, with the change persisted and confirmed.
- The resident who filed a report sees the new status and the date it changed, completing the PRD's primary Success Criterion end to end.
- `./mvnw clean test` and `./mvnw clean package` are green with tests asserting that residents get 403 on every staff route, that staff cannot file reports, and that a staff status change surfaces on the reporter's own page.
