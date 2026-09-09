# Staff Triages Reports and Updates Status — Implementation Plan

## Overview

Roadmap slice **S-02**, the north star. Office staff get a map of every submitted report and can change any report's status to any other status; the resident who filed it sees the new status and when it changed. Completing this slice closes the report-to-resolution loop end to end and satisfies the PRD's primary Success Criterion.

Covers PRD **US-02**, **FR-006** (staff view all reports on a map), **FR-007** (staff change report status). Resolves PRD Open Question #1: **transitions are unrestricted — any status to any status.**

## Current State Analysis

F-01 (auth) and S-01 (resident reporting) are complete and archived. S-01 was deliberately built to hand this slice a clean runway: all four `ReportStatus` values exist with display labels (`ReportStatus.java:3-18`), badge CSS for `in_progress` / `resolved` / `rejected` is already written in both resident templates and never exercised (`report-list.html:26-29`, `report-detail.html:15-18`), and `CustomUserDetails.java:39` already emits `ROLE_`-prefixed authorities so `hasRole('STAFF')` works unmodified.

What is missing is everything on the staff side, and four absences shape this plan:

- **`Report` has no mutator.** `status` is assigned once in the constructor (`Report.java:61`) and the class exposes getters only (`Report.java:65-95`), by the entity convention S-01 established. There is no `updatedAt` column and no status history.
- **Every read on `ReportService` is reporter-scoped by construction** — `listOwn`, `getOwn`, `getOwnPhoto`, `hasPhoto` (`ReportService.java:50,54,59,67`), each taking a `reporterId`. This is deliberate and structural: S-01's plan states ownership is enforced *in the query, not after it*, because F-01's critical finding came from a check that was possible to omit. `ReportRepository` has only the two scoped finders (`ReportRepository.java:9,11`), and S-01's plan explicitly reserved unscoped finders for this slice.
- **No STAFF account can exist.** `AuthService.register` hardcodes `Role.RESIDENT` (`AuthService.java:40`) and `AdminSeeder` only ever creates `Role.ADMIN` (`AdminSeeder.java:48`). `Role.STAFF` (`Role.java:5`) appears nowhere else in the repository. This blocks manual verification *and* the integration tests, which authenticate through the real register + login flow.
- **No authorization rule exists anywhere.** `@EnableMethodSecurity` has been switched on since F-01 (`SecurityConfig.java:20`) with zero `hasRole`, `hasAuthority`, `@PreAuthorize` or `@Secured` in `src/main`. The web chain is `anyRequest().authenticated()` (`SecurityConfig.java:72`). This slice lands the project's first role rule.

There is also no precedent for moving a *collection* into JavaScript: S-01 passes a single pin through `data-*` attributes read via `element.dataset` (`report-detail-map.js:13-14`), and the project contains no JSON endpoint, no `fetch`, and no `th:inline="javascript"`.

## Desired End State

A user with role STAFF (or ADMIN) logs in and follows a "Triage reports" link from the home page to `/staff/reports`, where every submitted report appears as a coloured marker on a Leaflet map — colour by status, so open work is visible at a glance. Clicking a marker opens a popup with the category, the current status, the submission date and a link to `/staff/reports/{id}`. The staff detail page shows the description, the category, the reporter's email, the photo when one exists, the pin on a read-only map, and a status form. Submitting the form changes the status — any value to any other value — and returns to the same page with a confirmation message and a refreshed "Status updated" timestamp.

The resident who filed that report opens `/reports/{id}` and sees the new status badge and the date it changed. A resident who navigates to any `/staff/**` URL gets 403. A staff or admin user who tries to file a report gets 403.

Verification: the manual steps in Testing Strategy, plus `./mvnw clean test` and `./mvnw clean package` green with the new cross-role integration tests.

### Key Discoveries:

- **`Report` exposes no setter and no `updatedAt`** (`Report.java:52-95`) — the status transition needs a domain method on the entity, which is this project's first entity mutator.
- **`ReportStatus` is complete and labelled** (`ReportStatus.java:3-18`) and badge CSS for all four values already exists (`report-list.html:26-29`) — no enum, column or resident-template change is needed for the statuses themselves.
- **`ROLE_` prefixing is already correct** (`CustomUserDetails.java:39`), so `hasRole('STAFF')` and `sec:authorize` work with no auth changes.
- **`/staff/**` is not covered by any existing rule** — the web chain's permit list is `/login`, `/register`, `/css/**`, `/js/**`, `/error`, `/actuator/health`, `/actuator/info` (`SecurityConfig.java:70-71`), then `anyRequest().authenticated()`. A new matcher is required, and it must sit *before* `anyRequest()`.
- **Leaflet is vendored at `/css/leaflet.css` and `/js/leaflet.js`** (`SecurityConfig.java:70` permits exactly `/css/**` and `/js/**`); the stylesheet resolves its marker icons relative to itself, so asset paths must not move.
- **One init script per page is the established pattern** (`report-map.js`, `report-detail-map.js`) — the staff map gets a third script, not a shared one.
- **`@WithMockUser` is unusable on any route taking `@AuthenticationPrincipal CustomUserDetails`** (S-01 plan-review F2) — it builds a plain `User` and the principal resolves to null. Staff tests must persist a real STAFF user.
- **Failsafe is not wired** — surefire only. A class named `*IT.java` would silently never run; every new test class must end in `Test`.
- **`ddl-auto=update` is the schema mechanism in both profiles** (`application.properties:7`, `application-azure.properties:9`). It reliably adds a new *nullable* column to an existing table, but never retro-fits an index (`lessons.md:12-17`).

## What We're NOT Doing

- **No status transition rules.** Any status to any status, per the answered PRD Open Question #1. No workflow validation, no terminal states. FR-007's arrow notation in `prd.md:95` is a description of the common path, not a constraint.
- **No status history table and no "changed by" column.** Only `statusUpdatedAt` is recorded. An audit trail is not in any requirement.
- **No pagination, filtering, sorting or search on the staff map.** All reports load, newest first — consistent with the no-pagination baseline the S-01 impl-review explicitly blessed at `data_volume: small`. Geo-search stays deferred (FR-009, Non-Goal #5).
- **No new database index.** The all-reports query is a sequential scan plus sort at MVP volume; this plan does not claim otherwise (the failure mode of S-01's impl-review F2).
- **No notifications.** The resident learns of a change by visiting their report — PRD Non-Goal #1.
- **No admin UI for staff accounts.** `staff.seed.*` is a bootstrap, not a feature; S-03 (`admin-manages-staff`) owns account management and will supersede it.
- **No editing or deleting reports**, no report reassignment, no staff notes or comments.
- **No public city-wide map for residents** — PRD Non-Goal #2. The all-reports map is staff-only.
- **No analytics, heatmap or statistics view** — PRD Non-Goal #4, directly adjacent to a staff map and explicitly out.
- **No REST/JSON API.** The pin payload is rendered into the page; no endpoint is added, and the `/api/**` chain is untouched.
- **No timezone fix and no photo-caching fix.** S-01 impl-review F8 and F4 stay skipped by decision (see Open Risks in the brief).
- **No `@ControllerAdvice`**, no error-page work, no CSS framework, no layout fragments, no frontend build step.
- **No change to the resident submission flow** beyond the resident-only authorization rule and the added "Status updated" line on the detail page.

## Implementation Approach

Bottom-up by layer, in five phases, mirroring S-01's shape and reserving a phase for the map so the slice's most novel integration gets its own verification gate.

The organising decision is that **staff get their own surface**: `StaffReportController` on `/staff/**`, backed by a separate `StaffReportService` with its own unscoped repository finders. `ReportService` and its reporter-scoped methods are not touched, not parameterised and not made role-aware. The resident surface therefore keeps the property S-01 exists to prove — that no code path can load another resident's report — while the staff surface is gated once, at the path level, in `SecurityConfig`. One coupling is deliberate: the staff service and controller reuse `ReportService.ReportNotFoundException` rather than declaring a second not-found type, so the report domain keeps exactly one. That is an inbound dependency on `ReportService`, not a change to it — its methods and their scoping are untouched.

The pin collection reaches JavaScript as a JSON string that the controller builds with Spring's injected `ObjectMapper` and the template writes into a `data-reports` attribute on the map container. This extends the existing server → `data-*` → `dataset` flow rather than introducing the project's first `fetch`, keeps the CSRF and JSON-error plumbing of the `/api/**` chain out of the north-star slice, and puts the payload in an attribute context, which Thymeleaf escapes. The payload deliberately carries no user-supplied text.

## Critical Implementation Details

**The security matcher must grant ADMIN as well as STAFF.** PRD Access Control defines Admin as "can manage office staff accounts, **plus everything office staff can do**". A `hasRole("STAFF")` matcher would lock the admin out of triage. Use `hasAnyRole("STAFF", "ADMIN")`, and the same expression in the `sec:authorize` nav guard, so the two never drift.

**Matcher ordering is load-bearing.** `SecurityConfig.java:72` ends the web chain with `anyRequest().authenticated()`. The `/staff/**` rule must be registered before it; placed after, it is dead and every authenticated resident reaches the staff map.

**`statusUpdatedAt` is nullable for a reason and every render must handle null.** `ddl-auto=update` adds the column to the existing `reports` table but backfills nothing, so every report created before this slice has `statusUpdatedAt = NULL` forever. Templates must not assume a value. This is the one schema change here that `update` *does* apply reliably — unlike an index, which it silently skips (`lessons.md:12-17`).

**A repository naming error fails at startup, not at compile.** Derived finders are validated when Spring Data builds the repository proxy, so a malformed name raises `PropertyReferenceException` during context startup and takes down every `@SpringBootTest`, `contextLoads` included, while `./mvnw compile` stays green. `findWithReporterById` is valid — Spring Data ignores the subject between `find` and `By`, and `With` is not a reserved token — but Phase 2's suite run, not its compile step, is the gate that proves it.

**A 403 shows Boot's Whitelabel page.** No `AccessDeniedHandler` and no `error.html` exist anywhere, and `exceptionHandling` is configured only on the API chain (`SecurityConfig.java:38-46`). The status code is correct and `/error` is permitted so the forward is not blocked, but the body a tester sees in a browser is unstyled. Expected, not a defect — error-page work stays out of scope.

**The pin payload must never reach the DOM as HTML.** The staff map script builds popup content with `textContent` and DOM node creation, never `innerHTML`. The payload carries no description precisely so a mistake here cannot become stored XSS, but the rule holds regardless.

**Do not add a photo association to `Report`.** Without bytecode enhancement Hibernate eagerly loads the inverse side of a to-one, which would pull image bytes into the all-reports query — on an unbounded list, on a 1 GB heap. Photo presence stays a service-computed boolean, as S-01 established.

**A same-status submission must not write.** With unrestricted transitions the form can post the value the report already has; the service returns without touching the row so `statusUpdatedAt` is not churned, and the controller reports "unchanged" rather than "updated".

---

## Phase 1: Data Model, Status Transition and Staff Seeding

### Overview

Gives `Report` the ability to change status and record when, and makes a STAFF account possible for the first time. Nothing is reachable from a browser after this phase; it exists so that phases 2–5, and every integration test, have something to work with.

### Changes Required:

#### 1. Status transition on the entity

**File**: `src/main/java/com/example/city_fix/report/Report.java`

**Intent**: Add the project's first entity mutator so a report's status can change after creation, and record when it last changed. Keeping the transition on the entity rather than exposing a raw setter preserves the "no setters" convention while giving the change a name and a single place to stamp the timestamp.

**Contract**: New nullable field `Instant statusUpdatedAt`, mapped `@Column(name = "status_updated_at")` with no `nullable = false` and no `updatable = false`; getter `getStatusUpdatedAt()`. New method `public void changeStatus(ReportStatus newStatus)` which rejects null, assigns `status`, and sets `statusUpdatedAt = Instant.now()`. No transition validation — any value is accepted from any current value. Do not add a setter for `status`, and do not touch `createdAt`.

#### 2. Staff account seeding

**File**: `src/main/java/com/example/city_fix/config/StaffSeeder.java` (new)

**Intent**: Allow a STAFF account to exist before S-03 builds admin-managed staff accounts, so the feature can be used in a browser and exercised by tests. Deliberately a sibling of `AdminSeeder` rather than a refactor of it — the duplication is small, the class is short-lived, and leaving `AdminSeeder` untouched keeps its passing test honest.

**Contract**: `@Component implements ApplicationRunner`, mirroring `AdminSeeder.java:16-56` exactly in shape — constructor `@Value("${staff.seed.email:}")` / `@Value("${staff.seed.password:}")` plus `UserRepository` and `PasswordEncoder`; skip silently at `debug` level when either property is blank; skip at `info` when `existsByEmail`; otherwise save `new User(email, encoder.encode(password), Role.STAFF)` and log at `info` without the password; catch `DataIntegrityViolationException` and log at `warn`. Email is normalised with `trim().toLowerCase(Locale.ROOT)` to match `CustomUserDetailsService.java:21`.

#### 3. Seed and map properties

**File**: `src/main/resources/application.properties`, `src/main/resources/application-azure.properties`

**Intent**: Declare the staff seed properties in both profiles so the Azure deployment can set them as app settings, following the `admin.seed.*` precedent.

**Contract**: `staff.seed.email=${STAFF_EMAIL:}` and `staff.seed.password=${STAFF_PASSWORD:}` in both files, placed beside the existing `admin.seed.*` block (`application.properties:25-26`). Defaults stay empty so an unset environment seeds nothing.

#### 4. Unit tests for the new behaviour

**File**: `src/test/java/com/example/city_fix/report/ReportTest.java` (new), `src/test/java/com/example/city_fix/config/StaffSeederTest.java` (new)

**Intent**: Cover the entity's first mutator and the new seeder, matching the depth `AdminSeederTest` set for the admin path.

**Contract**: `ReportTest` — a new report has status `NEW` and a null `statusUpdatedAt`; `changeStatus` sets both the status and a non-null timestamp; every one of the twelve ordered pairs of distinct statuses is accepted, including `RESOLVED → NEW`; null is rejected. `StaffSeederTest` — mirrors `AdminSeederTest.java:20-31` with a hand-rolled factory: seeds when configured, skips when the account exists, skips when either property is blank; assert the persisted role is `STAFF` via `ArgumentCaptor`. Plain JUnit for `ReportTest`, Mockito for `StaffSeederTest`. Both class names end in `Test` — failsafe is not wired, so any other suffix never runs.

### Success Criteria:

#### Automated Verification:

- Application compiles: `./mvnw compile`
- `ReportTest` and `StaffSeederTest` pass: `./mvnw test -Dtest='ReportTest,StaffSeederTest'`
- Full suite still passes: `./mvnw test`

#### Manual Verification:

- With `STAFF_EMAIL` and `STAFF_PASSWORD` set, the application starts and logs the staff account being seeded; restarting logs the skip instead of creating a duplicate.
- `\d reports` in psql shows a nullable `status_updated_at` column, and existing rows have it NULL.
- With both properties unset, startup logs nothing about staff seeding and creates no user.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase. Phase blocks use plain bullets — the corresponding `- [ ]` checkboxes for these items live in the `## Progress` section at the bottom of the plan.

---

## Phase 2: Staff Read and Transition Service

### Overview

Adds the project's first unscoped queries and the service that performs a status change, plus the resident-only rule on report creation. Kept in a separate service so `ReportService`'s invariant — every read is reporter-scoped — survives this slice untouched. Still nothing user-visible.

### Changes Required:

#### 1. Unscoped and fetch-joined finders

**File**: `src/main/java/com/example/city_fix/report/ReportRepository.java`

**Intent**: Give the staff side the two reads it needs — all reports newest-first for the map, and one report with its reporter loaded for the detail page — without weakening the existing scoped finders.

**Contract**: Add `List<Report> findAllByOrderByCreatedAtDesc()` and `Optional<Report> findWithReporterById(Long id)`, the latter annotated `@EntityGraph(attributePaths = "reporter")` so the detail page can show the reporter's email without a lazy-load-per-row surprise. This is the project's first `@EntityGraph`; derived query names otherwise, no `@Query`. Leave `findByReporterIdOrderByCreatedAtDesc` and `findByIdAndReporterId` exactly as they are.

#### 2. Staff report service

**File**: `src/main/java/com/example/city_fix/report/StaffReportService.java` (new)

**Intent**: Hold every read that crosses the ownership boundary, so that unscoped access is a deliberate, separately named, role-gated surface rather than a parameter on the resident service.

**Contract**: `@Service`, constructor-injected `ReportRepository` and `ReportPhotoRepository`. Methods: `List<Report> listAll()`; `Report get(Long id)` and `Report getWithReporter(Long id)`, both throwing `ReportService.ReportNotFoundException` when absent; `boolean hasPhoto(Long reportId)`; `ReportPhoto getPhoto(Long reportId)`; and `StatusChangeResult changeStatus(Long reportId, ReportStatus newStatus)`.

`changeStatus` is `@Transactional` (`org.springframework.transaction.annotation.Transactional`) with a one-line comment naming the reason — it is a read-modify-write and the mutation must be dirty-checked inside one transaction. It loads the report, returns `StatusChangeResult.UNCHANGED` without writing when the requested status equals the current one, and otherwise calls `Report.changeStatus` and returns `CHANGED`. Reuse the existing nested `ReportNotFoundException` rather than declaring a second not-found type. `StatusChangeResult` is a nested enum on this service, mirroring the nested-value-carrier precedent of `PhotoValidator.ValidatedPhoto`.

#### 3. Resident-only report creation

**File**: `src/main/java/com/example/city_fix/report/ReportWebController.java`

**Intent**: Close S-01 impl-review F9, which was skipped with the decision explicitly deferred to this slice: `@EnableMethodSecurity` is on and unused, and staff and admin can currently file resident reports even though no requirement grants them that.

**Contract**: `@PreAuthorize("hasRole('RESIDENT')")` on `newReportPage` and `createReport` only — the annotations go above `@GetMapping` (`:52`) and `@PostMapping` (`:59`), not on the signature lines. The project's first `@PreAuthorize`. Leave the list, detail and photo handlers unannotated — a staff member simply has no reports of their own — and change nothing in `SecurityConfig` for `/reports/**`, which `anyRequest().authenticated()` already covers.

#### 4. Unit tests for the staff service

**File**: `src/test/java/com/example/city_fix/report/StaffReportServiceTest.java` (new)

**Intent**: Pin the transition semantics and the not-found behaviour before any controller exists.

**Contract**: Mockito, `@ExtendWith(MockitoExtension.class)`, private builder for a `Report` in the style of `ReportServiceTest.java:163-165`. Cases: `listAll` delegates to the unscoped finder; `get` and `getWithReporter` throw `ReportNotFoundException` on an empty `Optional`; `changeStatus` to a different status mutates the entity and returns `CHANGED`; `changeStatus` to the current status returns `UNCHANGED` and leaves `statusUpdatedAt` null; `changeStatus` on a missing id throws; `getPhoto` and `hasPhoto` resolve without any reporter argument.

### Success Criteria:

#### Automated Verification:

- Application compiles: `./mvnw compile`
- `StaffReportServiceTest` passes: `./mvnw test -Dtest=StaffReportServiceTest`
- Full suite still passes: `./mvnw test`

**Implementation Note**: This phase has no manual verification items — it adds no user-visible surface. Proceed to Phase 3 once the automated checks pass.

---

## Phase 3: Staff Map Page

### Overview

The first staff-visible screen and the first authorization rule in the project: `/staff/reports` renders every report as a status-coloured marker on a Leaflet map, reachable only by STAFF and ADMIN. The slice's most novel integration — a collection of points serialized into the page — is isolated here so it gets its own verification gate.

### Changes Required:

#### 1. Role-gated path

**File**: `src/main/java/com/example/city_fix/config/SecurityConfig.java`

**Intent**: Gate the whole staff surface once, at the path level, so no future staff route can be added without protection.

**Contract**: In `webSecurityFilterChain` (`:67-85`), add `.requestMatchers("/staff/**").hasAnyRole("STAFF", "ADMIN")` **before** `.anyRequest().authenticated()` (`:72`). ADMIN is included because PRD Access Control grants admin everything staff can do. Do not touch the API chain, the permit list, formLogin or logout.

#### 2. Pin payload

**File**: `src/main/java/com/example/city_fix/report/ReportPin.java` (new)

**Intent**: A minimal serialization shape for the map, carrying no user-supplied text so nothing in the payload can become injected content.

**Contract**: `record ReportPin(Long id, double latitude, double longitude, String status, String statusLabel, String categoryLabel, String createdAt)`. `status` is the raw enum name, used by the script to pick a marker colour; `statusLabel` and `categoryLabel` are the display strings from `getLabel()`; `createdAt` is pre-formatted server-side with the controller's existing formatter. No description, no reporter, no photo flag.

#### 3. Staff controller and map route

**File**: `src/main/java/com/example/city_fix/report/StaffReportController.java` (new)

**Intent**: Serve the staff map, converting reports into pins and handing the JSON to the view.

**Contract**: `@Controller @RequestMapping("/staff/reports")`. Constructor-injected `StaffReportService` and Spring's `ObjectMapper` — inject it, never `new ObjectMapper()` (F-01 impl-review F10c) — plus the three `cityfix.map.*` values as `@Value` constructor parameters, matching `ReportWebController.java:42-50`. Its own `CREATED_AT_FORMAT` constant in the same shape as `ReportWebController.java:32-35`.

`GET /staff/reports` → view `staff-report-map`, model carrying `reportsJson` (the serialized `List<ReportPin>`), `reportCount`, and the three map defaults. A Jackson serialization failure is an infrastructure error. Note the classpath: `ObjectMapper` here is **Jackson 3** (`tools.jackson.databind`, `SecurityConfig.java:3`), whose serialization failures are the unchecked `tools.jackson.core.JacksonException` — there is no checked `JsonProcessingException`, and the Jackson 2 idiom will not compile. Either let it propagate, or catch `JacksonException` solely to log at `warn` with context and rethrow. Never render it as a user message (S-01 impl-review F1).

#### 4. Staff map template

**File**: `src/main/resources/templates/staff-report-map.html` (new)

**Intent**: The staff map page, standalone in the project's established style.

**Contract**: Standalone document with its own inline `<style>` reusing the existing visual vocabulary (`system-ui`, centred container, `#2563eb` primary), `xmlns:th` and `xmlns:sec`. Leaflet CSS at `/css/leaflet.css` in the head, `/js/leaflet.js` then `/js/staff-report-map.js` at the end of the body. Map container div carries `data-reports`, `data-default-lat`, `data-default-lng`, `data-default-zoom`. Shows the report count and an empty-state message when there are none. A status colour legend using the same palette. Link back to `/`.

#### 5. Staff map script

**File**: `src/main/resources/static/js/staff-report-map.js` (new)

**Intent**: The third page-specific map script; renders many markers rather than placing one.

**Contract**: IIFE with `'use strict'`, opening comment naming the vendored Leaflet version, matching `report-map.js:1-4`. Reads `data-reports` from `dataset` and `JSON.parse`s it; reads the three map defaults the same way. Renders one `L.circleMarker` per pin with fill colour chosen from a status-keyed constant map whose values are copied from the existing status badge palette (`report-detail.html:15-18`) and carry a comment naming those badge classes as the source of truth, so the map and the badges cannot drift into two palettes, and a popup built with `document.createElement` and `textContent` — never `innerHTML` — containing the category label, status label, date and an anchor to `/staff/reports/{id}`. Fits bounds to the markers when there is at least one, otherwise centres on the configured default. Same OSM tile URL and attribution string as `report-map.js:27-30`.

#### 6. Home page entry point

**File**: `src/main/resources/templates/home.html`

**Intent**: Give staff a way in; residents must not see the link.

**Contract**: A "Triage reports" link to `/staff/reports` wrapped in `sec:authorize="hasAnyRole('STAFF','ADMIN')"` — the same expression as the matcher, so the two cannot drift. The `sec` namespace is already declared (`home.html:2`). Do not introduce principal-typed expressions: `SecurityConfigTest.java:80-85` renders `/` under `@WithMockUser`, whose principal is a plain `User`, and must keep passing.

### Success Criteria:

#### Automated Verification:

- Application compiles: `./mvnw compile`
- Full suite still passes: `./mvnw test`

#### Manual Verification:

- Signed in as the seeded staff account, the home page shows "Triage reports"; signed in as a resident it does not.
- `/staff/reports` renders the map with a marker for every existing report; a resident navigating there gets 403, shown as Boot's Whitelabel page.
- A popup shows the category, the status and the date, and its link points at `/staff/reports/{id}` — the target page itself arrives in Phase 4.
- With no reports in the database the page renders the empty state rather than a broken map.
- The map is usable at a 375 px viewport width.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 4: Staff Detail and Status Change

### Overview

The half of the slice that closes the loop: staff open one report, see who filed it and what it looks like, change its status, and the resident sees the result. Introduces the project's first flash attribute.

### Changes Required:

#### 1. Status form DTO

**File**: `src/main/java/com/example/city_fix/report/StatusChangeForm.java` (new)

**Intent**: A validated record rather than a loose `@RequestParam`, per the typed-DTO rule in `lessons.md:5-10` as S-01 extended it to form controllers.

**Contract**: `record StatusChangeForm(@NotNull(message = "...") ReportStatus status)` with a user-facing message on the constraint. Spring converts the submitted string to the enum; an unparseable value produces a binding error, handled the same way `ReportWebController.java:143-150` handles one.

#### 2. Detail and status-change routes

**File**: `src/main/java/com/example/city_fix/report/StaffReportController.java`

**Intent**: Serve the staff detail view and perform the transition, using post-redirect-get so a refresh cannot re-submit.

**Contract**: Three additions. `GET /staff/reports/{id}` → view `staff-report-detail`, model carrying the report loaded via `getWithReporter`, the reporter's email, a `hasPhoto` boolean, the `ReportStatus.values()` list for the select, an empty `StatusChangeForm`, the date formatter and the pin coordinates. `POST /staff/reports/{id}/status` taking `@Valid @ModelAttribute StatusChangeForm`, `BindingResult` and `RedirectAttributes`; on success it redirects to `/staff/reports/{id}` with a flash attribute reporting either "Status updated to X" or "Status unchanged", driven by the service's `StatusChangeResult`; on a binding error it redirects back with an error flash. `GET /staff/reports/{id}/photo` returning `ResponseEntity<byte[]>` exactly as `ReportWebController.java:117-127` does, but unscoped. An `@ExceptionHandler(ReportService.ReportNotFoundException.class)` returning 404 — controller-scoped, no `@ControllerAdvice`, following `ReportWebController.java:130-134`.

This is the project's first use of `RedirectAttributes`; note it as a deliberate deviation in Phase 5.

#### 3. Staff detail template

**File**: `src/main/resources/templates/staff-report-detail.html` (new)

**Intent**: The triage screen.

**Contract**: Standalone document in the established style, with the four status badge classes copied from `report-detail.html:15-18`. Shows category, current status badge, submitted date, `statusUpdatedAt` when non-null, reporter email, description, the photo via `/staff/reports/{id}/photo` when present, and a read-only map. Status form posts to `/staff/reports/{id}/status` with a `select` over the four statuses, defaulting to the current one; the CSRF field comes from the Thymeleaf security dialect automatically — never hand-written. Renders the flash message when present. Links back to `/staff/reports`.

#### 4. Staff detail map

**File**: `src/main/resources/templates/staff-report-detail.html` (no new script)

**Intent**: Reuse the existing read-only map script instead of duplicating thirty identical lines. The one-script-per-page convention exists for page-specific behaviour; this behaviour is identical, and a static asset is not a shared module.

**Contract**: The staff detail template references `/js/report-detail-map.js` and must satisfy the element contract that script binds to — the `#detail-map` id (`report-detail-map.js:8`) and the `data-lat` / `data-lng` attributes. Do not modify `report-detail-map.js`. Split a staff copy out only if the staff detail map later needs different behaviour.

#### 5. Resident sees the update

**File**: `src/main/resources/templates/report-detail.html`

**Intent**: Make "the resident sees the update" concrete beyond the badge, which is the second half of the PRD's primary Success Criterion.

**Contract**: Add a "Status updated" line rendered only when `statusUpdatedAt` is non-null, formatted with the `dateFormatter` already in the model. Existing reports have a null value and must render unchanged. No change to `report-list.html`, whose badge already reflects the new status.

### Success Criteria:

#### Automated Verification:

- Application compiles: `./mvnw compile`
- Full suite still passes: `./mvnw test`

#### Manual Verification:

- The popup link on the staff map opens the detail page for that report.
- As staff, opening a report from a map popup shows the description, category, reporter email, photo when one exists and the pin.
- Changing the status redirects back to the same page with a confirmation, the new badge and a fresh "Status updated" timestamp.
- Re-submitting the status the report already has reports "unchanged" and leaves the timestamp untouched.
- Every transition works, including `RESOLVED → NEW` and `NEW → REJECTED`.
- Back on `/staff/reports`, marker colours now differ, and they match the status badge colours on the detail page.
- The resident who filed the report sees the new badge in "my reports" and the "Status updated" line on the detail page; a report never touched by staff shows no such line.
- A resident requesting `/staff/reports/{id}` or `/staff/reports/{id}/photo` gets 403, including for their own report.
- The detail page is usable at a 375 px viewport width.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 5: Integration Tests, Security Tests and Verification

### Overview

Puts an assertion behind every cross-role claim this slice makes, then packages. The authorization surface is new to the project, so the security cases are the point of this phase rather than a formality.

### Changes Required:

#### 1. Staff integration and security tests

**File**: `src/test/java/com/example/city_fix/report/StaffReportControllerTest.java` (new)

**Intent**: Prove the staff surface works and that the role boundary holds in both directions.

**Contract**: `@SpringBootTest @AutoConfigureMockMvc extends TestcontainersConfig`, matching `ReportWebControllerTest.java:26-28`. A STAFF user cannot be created through the API, so the fixture persists one directly via the autowired `UserRepository` and `PasswordEncoder`, then logs in through `POST /api/auth/login` and reuses the resulting `MockHttpSession`, following the session-extraction helper at `ReportWebControllerTest.java:250-267`. `@WithMockUser` must not be used on these routes.

Cases: staff sees reports filed by other users on `/staff/reports`, and the rendered `data-reports` contains their ids; a resident gets 403 on the staff map, the staff detail page and the staff photo route, including for a report they own; an unauthenticated request redirects to `/login`; a status change persists and the reporting resident then sees the new status on their own detail page; a same-status submission leaves `statusUpdatedAt` unchanged; a missing report id returns 404 for staff; an ADMIN user reaches the staff map; a STAFF user gets 403 from `GET /reports/new` and `POST /reports`. Every write uses `.with(csrf())`. Follow the unique-data isolation convention — nothing is transactional and the container is shared, so invent unique emails and descriptions per test.

#### 2. Security config assertions

**File**: `src/test/java/com/example/city_fix/config/SecurityConfigTest.java`

**Intent**: Assert the new matcher at the config level, not only through the controller, so the ordering constraint stays pinned.

**Contract**: Add cases that `/staff/reports` is denied to an authenticated resident and unauthenticated access redirects to login. Keep the existing `@WithMockUser(roles = "RESIDENT")` cases untouched.

#### 3. Record deliberate deviations

**File**: `context/changes/staff-triages-reports/change.md`

**Intent**: Record this slice's convention firsts so the implementation review reads them as decisions rather than drift — the failure mode that produced F-01's critical finding.

**Contract**: A "Deliberate convention deviations" section in the same shape as the S-01 change file, covering: the first mutator on an entity (`Report.changeStatus`, chosen over a raw setter); the first `@PreAuthorize` and the first `hasRole` matcher; the first `@EntityGraph`; the first `RedirectAttributes` flash; `StaffSeeder` duplicating `AdminSeeder` rather than refactoring it; and the accepted decision that `statusUpdatedAt` renders through the same server-zone formatter as `createdAt`, reproducing the skipped S-01 impl-review F8 rather than fixing it in this slice.

### Success Criteria:

#### Automated Verification:

- Full suite passes: `./mvnw clean test`
- `StaffReportControllerTest` passes: `./mvnw test -Dtest=StaffReportControllerTest`
- A resident is denied on every `/staff/**` route, asserted in both the controller test and `SecurityConfigTest`
- A STAFF user is denied on the report create path
- A status change made by staff is visible on the reporting resident's detail page, asserted end to end
- Application packages: `./mvnw clean package`

#### Manual Verification:

- The deviations section in `change.md` matches what was actually built.
- `\d reports` confirms `status_updated_at` exists on the local database, and reports created before this slice still render correctly with a null value.
- The full flow works in a browser end to end: resident files a report, staff triages it from the map, resident sees the new status.

**Implementation Note**: After completing this phase and all automated verification passes, pause for manual confirmation from the human before the change goes to `/10x-impl-review`.

---

## Testing Strategy

### Unit Tests:

- `ReportTest` — the status transition itself: initial state, timestamp stamping, all twelve distinct ordered transitions, null rejection.
- `StaffReportServiceTest` — unscoped reads delegate correctly, not-found throws, changed vs unchanged transition results, no reporter argument anywhere.
- `StaffSeederTest` — seeds with the STAFF role, skips when the account exists, skips when unconfigured.

### Integration Tests:

- `StaffReportControllerTest` — staff sees other users' reports; resident 403 on every staff route including for their own report; admin reaches the staff surface; staff blocked from creating reports; status change persists and surfaces on the resident's page; same-status submission is a no-op; 404 for a missing id; unauthenticated redirect.
- `SecurityConfigTest` — `/staff/**` denied to residents and to anonymous users.

### Manual Testing Steps:

1. Set `STAFF_EMAIL` / `STAFF_PASSWORD`, start the app, and confirm the staff account is seeded once and skipped on restart.
2. As a resident, file two reports at different locations.
3. Log in as staff, confirm "Triage reports" appears on the home page, and open `/staff/reports`.
4. Confirm both reports appear as markers, and that a popup links through to the detail page.
5. Change one report to "In progress" and the other to "Rejected"; confirm the confirmation message, the badge and the timestamp each time.
6. Re-submit the same status on one of them and confirm it reports "unchanged" with an unmoved timestamp.
7. Return to `/staff/reports` and confirm the marker colours now differ.
8. Log back in as the resident and confirm both reports show their new statuses and the "Status updated" line, and that a third, untouched report shows no such line.
9. As the resident, request `/staff/reports` directly and confirm 403; attempt `GET /reports/new` as staff and confirm 403. Both render Boot's Whitelabel page — expected, not a defect.
10. Repeat steps 3–5 at a 375 px viewport width.

## Performance Considerations

The staff map loads every report in one unscoped query and serializes all of them into the page. At `data_volume: small` with `qps: low` this is the same trade the S-01 impl-review explicitly accepted for the resident list, and it keeps the slice free of pagination and filtering. **No index is added, and none exists that serves this query** — `findAllByOrderByCreatedAtDesc` is a sequential scan plus a sort, and the only index on the table is `idx_reports_reporter_created`, which does not help an unfiltered ordering. This is stated rather than assumed because S-01's impl-review F2 found the previous plan resting on an index that did not exist. Revisit when the table approaches tens of thousands of rows; the natural first steps are a status filter and an index on `(status, created_at)`, both explicitly out of scope here.

The payload stays lean by carrying no description and no reporter — seven small fields per report. Photo bytes are never loaded by the map path: `Report` has no photo association and must not gain one. `@EntityGraph` on the detail finder keeps reporter loading to a single join rather than a lazy fetch inside the view.

The unbounded photo re-send that S-01's impl-review flagged (F4, skipped) is inherited unchanged. The staff detail page adds one more place a photo can be requested, but it serves one photo per page view, as the resident detail page already does.

## Migration Notes

One schema change: a nullable `status_updated_at` column on `reports`. Under `ddl-auto=update` this is the case the mechanism handles reliably — a new nullable column is added to an existing table on startup — so no manual DDL is required on either the local database or Azure. Nothing is backfilled: every report created before this slice keeps `status_updated_at = NULL` permanently, and every template that renders it must tolerate null. Verify with `\d reports` rather than trusting a green boot (`lessons.md:12-17`).

No index is added, so the silent-skip failure mode that lesson describes does not apply here. No data migration, no backfill script, and no change to `report_photos` or `users`.

Rollback is a redeploy of the previous JAR; the added column is nullable and unread by the previous version, so it can be left in place.

## References

- Roadmap slice: `context/foundation/roadmap.md:88-99`
- PRD: `context/foundation/prd.md:62-70` (US-02), `:92` (FR-006), `:95` (FR-007), `:118-126` (Access Control), `:145` (Open Question #1, resolved here)
- Recurring rules: `context/foundation/lessons.md:5-10` (typed DTOs), `:12-17` (ddl-auto schema drift)
- Prior slice plan and reviews: `context/archive/2026-09-03-resident-reports-problem/plan.md`, `.../reviews/impl-review.md` (F1 logging, F2 index, F4 photo caching, F8 timezone, F9 deferred authorization)
- Entity and enum: `src/main/java/com/example/city_fix/report/Report.java:52-95`, `.../ReportStatus.java:3-18`
- Scoped service to leave untouched: `src/main/java/com/example/city_fix/report/ReportService.java:50-72`
- Controller patterns to follow: `src/main/java/com/example/city_fix/report/ReportWebController.java:32-35,42-50,117-134,143-150`
- Security config: `src/main/java/com/example/city_fix/config/SecurityConfig.java:20,67-85`
- Seeder precedent: `src/main/java/com/example/city_fix/config/AdminSeeder.java:16-56`
- Map script precedents: `src/main/resources/static/js/report-map.js:1-4,27-30`, `.../report-detail-map.js:13-31`
- Test fixture precedent: `src/test/java/com/example/city_fix/report/ReportWebControllerTest.java:250-267,285-290`
- Coupled but unmodified: `ReportService.java:74-75` (the reused not-found type), `ReportPhotoRepository.java`, `ReportPhoto.java`, `ReportStatus.java`, `Category.java`, `user/User.java`, `user/UserRepository.java`, `user/Role.java:5`, `auth/CustomUserDetails.java:39`, and `templates/report-list.html:26-29,49-50` — the resident list renders the status badge and must be re-checked visually once statuses other than NEW exist

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Data Model, Status Transition and Staff Seeding

#### Automated

- [x] 1.1 Application compiles — 6b017ba
- [x] 1.2 ReportTest and StaffSeederTest pass — 6b017ba
- [x] 1.3 Full suite still passes — 6b017ba

#### Manual

- [x] 1.4 Staff account seeded once, skipped on restart — 6b017ba
- [x] 1.5 status_updated_at exists and is nullable; existing rows NULL — 6b017ba
- [x] 1.6 Unset properties seed nothing — 6b017ba

### Phase 2: Staff Read and Transition Service

#### Automated

- [x] 2.1 Application compiles — 3c61098
- [x] 2.2 StaffReportServiceTest passes — 3c61098
- [x] 2.3 Full suite still passes — 3c61098

### Phase 3: Staff Map Page

#### Automated

- [x] 3.1 Application compiles
- [x] 3.2 Full suite still passes

#### Manual

- [x] 3.3 Triage link visible to staff, hidden from residents
- [x] 3.4 Staff map renders all reports; resident gets 403
- [x] 3.5 Popup shows category, status and date; link points at the detail route
- [x] 3.6 Empty state renders with no reports
- [x] 3.7 Map usable at 375 px

### Phase 4: Staff Detail and Status Change

#### Automated

- [ ] 4.1 Application compiles
- [ ] 4.2 Full suite still passes

#### Manual

- [ ] 4.3 Popup link on the staff map opens the detail page
- [ ] 4.4 Detail page shows description, category, reporter email, photo and pin
- [ ] 4.5 Status change confirms, updates badge and timestamp
- [ ] 4.6 Same-status submission reports unchanged, timestamp untouched
- [ ] 4.7 All transitions work including RESOLVED to NEW
- [ ] 4.8 Marker colours on the staff map differ once statuses differ
- [ ] 4.9 Resident sees new badge and Status updated line; untouched report shows none
- [ ] 4.10 Resident gets 403 on staff detail and photo routes
- [ ] 4.11 Detail page usable at 375 px

### Phase 5: Integration Tests, Security Tests and Verification

#### Automated

- [ ] 5.1 Full suite passes with clean test
- [ ] 5.2 StaffReportControllerTest passes
- [ ] 5.3 Resident denied on every staff route in both test classes
- [ ] 5.4 STAFF denied on the report create path
- [ ] 5.5 Status change visible to the reporting resident, asserted end to end
- [ ] 5.6 Application packages

#### Manual

- [ ] 5.7 Deviations section in change.md matches what was built
- [ ] 5.8 status_updated_at confirmed on the local database; pre-existing reports render
- [ ] 5.9 Full browser flow works end to end
