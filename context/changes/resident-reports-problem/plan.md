# Resident Submits a Geo-located Report — Implementation Plan

## Overview

Add a `report/` feature package that lets an authenticated user place a pin on a Leaflet map, submit a report with description, category and an optional photo (≤2 MB), and browse only their own reports through ownership-scoped list, detail and photo routes. This is roadmap slice **S-01**, the north-star slice, and it introduces three things the project has never had: a frontend layer, file upload, and ownership-scoped data access.

Covers PRD **US-01**, **FR-003**, **FR-004**, **FR-005**, **FR-008**.

## Current State Analysis

The auth scaffold (F-01) is complete and archived. What exists:

- **One entity**: `User` (`src/main/java/com/example/city_fix/user/User.java:13`) — `@Table(name = "users")`, `GenerationType.IDENTITY`, `@Enumerated(EnumType.STRING)` role, `Instant createdAt` stamped in the constructor, getters only, `protected User() {}` for JPA. No `updatedAt` anywhere, no JPA auditing, no `@PrePersist`.
- **One repository**: `UserRepository` (`user/UserRepository.java:6`) — plain interface extending `JpaRepository`, derived query methods only, zero `@Query` in the codebase.
- **Two controllers per feature**: `AuthController` (`@RestController`, `/api/auth`, returns `ResponseEntity<?>`) and `AuthWebController` (`@Controller`, returns view names, plain `@RequestParam` binding). Both delegate to a shared `AuthService`.
- **Two security filter chains** (`config/SecurityConfig.java:29,65`): `@Order(1)` matches `/api/**` with `csrf.spa()` and a JSON 401 entry point; `@Order(2)` handles everything else with `formLogin` (`usernameParameter("email")`, `defaultSuccessUrl("/", true)`).
- **Three standalone Thymeleaf templates** — no fragments, no layout dialect, no CSS framework; each page carries its own inline `<style>`. `home.html:20` contains the comment "The map view will replace it in S-01".
- **Empty `src/main/resources/static/`** despite `SecurityConfig:70` already permitting `/css/**` and `/js/**`.
- **Schema via `spring.jpa.hibernate.ddl-auto=update`** in both profiles (`application.properties:7`, `application-azure.properties:9`). No Flyway, no Liquibase, no `db/` directory.
- **Tests**: `@SpringBootTest @AutoConfigureMockMvc` extending `TestcontainersConfig` (real Postgres 17 container) for integration; `@ExtendWith(MockitoExtension.class)` with `@Mock`/`@InjectMocks` for units. AssertJ for assertions, method naming `scenario_expectedOutcome`. No `@MockBean`, no `@WebMvcTest`, no `@DataJpaTest`.
- **CI** (`.github/workflows/deploy.yml`): `./mvnw clean package -B` on push to `main` — tests do run; there is no PR trigger.

What is missing and must be built: the `Report` data model, any file-upload capability (`spring.servlet.multipart.*` is entirely absent), any JavaScript or CSS asset, and any authorization beyond `permitAll()`/`authenticated()` — `@EnableMethodSecurity` is switched on at `SecurityConfig:20` but there is not a single `hasRole`, `hasAuthority`, `@PreAuthorize` or `@Secured` in the codebase.

Constraints from `context/foundation/infrastructure.md`: Azure App Service **B1** (1.75 GB RAM, `-Xmx1g` advised at line 127), PostgreSQL Flexible Server **Burstable B1ms**, and a documented ~$25/month budget with a cost alert at $30 (line 129). The document never provisions Blob Storage and never states whether the App Service filesystem persists — so neither could be relied on here.

## Desired End State

A logged-in user visits `/reports/new`, sees a map centered on a configured default location, taps the map (or presses "Use my location") to place a pin, fills in a description, picks a category, optionally attaches a photo, and submits. They are redirected to `/reports`, where their report appears newest-first with status `new`. Clicking it opens `/reports/{id}`, showing the description, category, status, the photo if one was attached, and the pin on a small read-only map.

A second account, given the first account's report URL or photo URL directly, receives **404** — not 403, and not the content.

Verification: the full manual walkthrough in Phase 5, plus `./mvnw clean package` green with integration tests asserting cross-owner 404s, oversize rejection and wrong-type rejection.

### Key Discoveries:

- `SecurityConfig:69-73` permits exactly `/login`, `/register`, `/css/**`, `/js/**`, `/error`, `/actuator/health`, `/actuator/info`. Vendored assets **must** live under `/css/` and `/js/` — a `/vendor/**` or `/webjars/**` path would 401 before loading.
- `SecurityConfig:72` is `.anyRequest().authenticated()`, so **`/reports/**` is already protected with no SecurityConfig change at all**. Do not add rules for it.
- `home.html:23-25` proves the CSRF pattern: `<form th:action="@{/logout}" method="post">` with the token injected automatically by `thymeleaf-extras-springsecurity6`. No template in the repo hand-writes a `_csrf` input.
- `register.html:26` (`th:value="${email}"`) plus `AuthWebController:49` establish the error-redisplay pattern: re-render the same view with `error` and the submitted values as model attributes.
- `AuthService.EmailAlreadyExistsException` (`auth/AuthService.java:51-55`) is a `public static` nested class inside the service, referenced by controllers as `AuthService.EmailAlreadyExistsException`. New exceptions follow this shape — there is no `exception` package and no `@ControllerAdvice`.
- `AdminSeeder` (`config/AdminSeeder.java:18`) shows the SLF4J convention: `private static final Logger log = LoggerFactory.getLogger(X.class)`.
- Spring Boot **4.0.6** / Java **21**. Boot 4 package renames are live: `spring-boot-starter-webmvc`, Jackson 3 (`tools.jackson.databind`), and `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`. Boot 3 idioms will not compile.
- `spring-boot-starter-validation` is already present (`pom.xml:65`) — Bean Validation needs no new dependency.
- `lessons.md` rule "Typed DTOs over raw maps in controllers" was recorded from the auth impl-review (F9) with the explicit note that S-01 would inherit whatever pattern auth set.
- The auth impl-review was **REJECTED on first pass** over a single critical finding (F1, session fixation), whose root cause was F4 — silently drifting from the planned security mechanism. Its plan-brief also flagged `-DskipTests` as an open risk that went unactioned until review forced it (F7).

## What We're NOT Doing

- **No staff or admin view of reports** — S-02 owns the all-reports map and status transitions. This slice only ever writes status `NEW`.
- **No status transitions** and no transition-validation rules. PRD Open Question #1 (which transitions are legal) belongs to S-02.
- **No editing or deleting reports.** No FR covers it.
- **No public or city-wide report map** — PRD Non-Goal #2. Residents see only their own reports.
- **No notifications** — PRD Non-Goal #1.
- **No geo-search / radius filtering** — FR-009, PRD Non-Goal #5.
- **No configurable categories** — FR-008 fixes them for MVP; the enum is compiled in.
- **No PostGIS.** Plain numeric lat/lng columns; nothing in this slice queries by distance.
- **No Azure Blob Storage, no Azurite, no `azure-storage-blob` dependency.**
- **No server-side image resizing** and no image-processing library.
- **No REST/JSON reports API.** S-02 can add one when it needs one.
- **No `@ControllerAdvice`**, no global error handling refactor, and no change to the existing auth error contract.
- **No Flyway/Liquibase migration.** Out of scope here; `plan.md` for F-01 already noted it as a post-MVP consideration.
- **No CSS framework, no layout fragments, no frontend build step, no npm.**

## Implementation Approach

Bottom-up by layer, mirroring the 4-phase shape the auth slice proved, but split into five so the map integration — the acknowledged top technical risk — gets its own verification gate instead of riding along with the read-side views.

Photos are stored as `bytea` in a **separate `report_photos` table** so that no "my reports" query ever touches image bytes. Access to a photo goes through the same reporter-scoped query path as the report itself, so there is no code path that can serve one user's photo to another.

Ownership is enforced **in the query, not after it**: every read is `findByIdAndReporterId` / `findByReporterIdOrderByCreatedAtDesc`. A non-owner produces an empty `Optional`, which the controller turns into 404. This is deliberately structural — the last slice's critical finding came from a check that was possible to omit.

Submission uses the **web filter chain with a multipart Thymeleaf form**, not the `/api/**` chain. This buys CSRF correctness for free from the Thymeleaf security dialect and avoids hand-managing `XSRF-TOKEN` headers against `csrf.spa()` — novel security plumbing this slice does not need.

## Critical Implementation Details

**`byte[]` must not be annotated `@Lob` on PostgreSQL.** With Hibernate 6+ on Postgres, `@Lob byte[]` maps to `oid` (a large-object reference), not `bytea` — reads then require an active transaction and a LO manager, and the column type is wrong for this design. Declare the field as a plain `byte[]` and let it map to `bytea`. This is the single most likely silent mistake in Phase 1, which is why Phase 1's manual gate inspects the column type directly.

**CSRF and multipart ordering.** Spring Security's `CsrfFilter` reads the `_csrf` request parameter, but for a `multipart/form-data` POST the body is only parsed when the container is asked for a parameter. If the multipart POST returns **403 despite the Thymeleaf-injected token**, the cause is filter ordering, and the fix is either registering a `MultipartFilter` ahead of the security filter chain or carrying the token outside the body — do not "fix" it by exempting `/reports` from CSRF. Verify this specific case early in Phase 3 (item 3.8) rather than discovering it during Phase 5.

**Oversize uploads and CSRF.** The container multipart cap and the 2 MB product limit must not coincide. When Tomcat rejects a part for size during `getParameter()`, it clears every part and returns null parameters; `CsrfFilter` then finds no `_csrf` token and returns **403** before the controller is reached — no `BindingResult`, no `PhotoValidator`, no redisplay, so "pin and description survive" is impossible. The plan therefore sets the container cap at 10 MB (Phase 1) and enforces 2 MB in `PhotoValidator` (Phase 2), which checks `MultipartFile.getSize()` **before** calling `getBytes()` so an oversize part is never copied to the heap — Tomcat spools parts to temp disk (`fileSizeThreshold` default 0), so bounded heap on B1 is preserved. `report-map.js` adds a client-side size check as UX only; it is not a security control.

**A lazy inverse-side `@OneToOne` does not stay lazy.** Without bytecode enhancement, Hibernate eagerly loads the inverse (`mappedBy`) side of a to-one association, which would pull photo bytes into every report load and defeat the separate table. Therefore `ReportPhoto` owns the FK (`@OneToOne` + `@JoinColumn(name = "report_id")`) and `Report` holds **no** photo association at all. Photo presence is exposed to templates via a boolean the service computes, not via a mapped relationship.

**`ReportService.create` needs `@Transactional`** because it writes two rows (report, then photo). This is the project's first `@Transactional` — the codebase currently has none and relies on Spring Data's per-method transactions. Annotate it and keep the reason in a short comment, so an impl-review reads it as a considered choice rather than drift.

**Serving user-uploaded bytes.** Return the stored content type explicitly and rely on Spring Security's default `X-Content-Type-Options: nosniff` header (already active) so a browser cannot be talked into interpreting an image as script. Never echo the client-supplied filename into a response header without sanitizing it.

**Browser geolocation requires a secure context.** `navigator.geolocation` works on `localhost` and over HTTPS; App Service provides HTTPS. The permission-denied and timeout callbacks must both be handled — a silent failure leaves the user staring at an unmoved map.

## Phase 1: Data Model and Configuration

### Overview

Create the `report/` package with entities, enums and repositories, and add the multipart and map-center configuration. After this phase the schema exists and the application boots; nothing is reachable from a browser yet.

### Changes Required:

#### 1. Report entity

**File**: `src/main/java/com/example/city_fix/report/Report.java`

**Intent**: The core domain record — one row per problem a resident reports, carrying its location, description, category, status and reporter.

**Contract**: `@Entity`, `@Table(name = "reports")`. Fields: `Long id` (`@GeneratedValue(strategy = GenerationType.IDENTITY)`); `double latitude`, `double longitude` (both `nullable = false`); `String description` (`nullable = false`, `length = 2000`); `Category category` and `ReportStatus status`, both `@Enumerated(EnumType.STRING)` and `nullable = false`; `User reporter` as `@ManyToOne(optional = false, fetch = FetchType.LAZY)` with `@JoinColumn(name = "reporter_id")`; `Instant createdAt` (`nullable = false, updatable = false`). Follow `User` exactly: `protected Report() {}`, one all-args public constructor that stamps `createdAt = Instant.now()` and sets `status = ReportStatus.NEW`, getters only, no setters.

#### 2. Category and ReportStatus enums

**File**: `src/main/java/com/example/city_fix/report/Category.java`, `src/main/java/com/example/city_fix/report/ReportStatus.java`

**Intent**: Fix the six MVP categories in code per FR-008, and define the full four-state status set now so S-02 adds transitions without touching the enum, column or templates.

**Contract**: `Category` = `POTHOLE, STREETLIGHT, GRAFFITI, TRASH, SIGN, OTHER`. `ReportStatus` = `NEW, IN_PROGRESS, RESOLVED, REJECTED`. Plain enums with no methods, matching `Role`. Each gets a `label()` returning the human string ("Pothole", "In progress") only if the templates need it — otherwise leave them bare and format in the view.

#### 3. ReportPhoto entity

**File**: `src/main/java/com/example/city_fix/report/ReportPhoto.java`

**Intent**: Hold the optional image bytes in a table of their own so no report query ever loads them.

**Contract**: `@Entity`, `@Table(name = "report_photos")`. Fields: `Long id` (IDENTITY); `Report report` as the **owning** side — `@OneToOne(optional = false, fetch = FetchType.LAZY)` with `@JoinColumn(name = "report_id", unique = true)`; `String contentType` (`nullable = false`); `byte[] imageData` (`nullable = false`) — **declared without `@Lob`** so it maps to `bytea` (see Critical Implementation Details); `Instant createdAt`. Same constructor/getter discipline as `Report`.

#### 4. Repositories

**File**: `src/main/java/com/example/city_fix/report/ReportRepository.java`, `src/main/java/com/example/city_fix/report/ReportPhotoRepository.java`

**Intent**: Expose only reporter-scoped finders, so ownership is a property of the query rather than a check a caller can forget.

**Contract**: `ReportRepository extends JpaRepository<Report, Long>` with `List<Report> findByReporterIdOrderByCreatedAtDesc(Long reporterId)` and `Optional<Report> findByIdAndReporterId(Long id, Long reporterId)`. `ReportPhotoRepository extends JpaRepository<ReportPhoto, Long>` with `Optional<ReportPhoto> findByReportId(Long reportId)` and `boolean existsByReportId(Long reportId)`. Derived queries only; no `@Query`; no `@Repository` annotation. Do **not** add unscoped finders — S-02 adds its own when it needs them.

#### 5. Multipart and map configuration

**File**: `src/main/resources/application.properties`, `src/main/resources/application-azure.properties`

**Intent**: Cap upload size at the container boundary, and make the map's default center configurable per environment rather than baked into JavaScript.

**Contract**: Add `spring.servlet.multipart.max-file-size=10MB` and `spring.servlet.multipart.max-request-size=11MB`. These are **DoS bounds enforced by Tomcat**, deliberately well above the 2 MB product limit — the 2 MB rule belongs to `PhotoValidator` (Phase 2), the only place that can reject with a readable message. Do **not** set the container cap to 2 MB: a Tomcat-rejected multipart body loses its `_csrf` field and Spring Security answers 403 before the controller runs (see Critical Implementation Details). Uploads exceeding the container cap still produce a generic error; that is accepted. Add `cityfix.map.default-lat=52.2297`, `cityfix.map.default-lng=21.0122`, `cityfix.map.default-zoom=13`. Mirror all six into the `azure` profile, following how the datasource keys are duplicated there today.

### Success Criteria:

#### Automated Verification:

- Application compiles: `./mvnw compile`
- Application starts and Hibernate creates `reports` and `report_photos`
- Existing test suite still passes: `./mvnw test`

#### Manual Verification:

- `\d report_photos` in psql shows `image_data` as `bytea`, **not** `oid`
- `\d reports` shows `latitude`/`longitude` as `double precision`, `category`/`status` as `varchar`, and a FK on `reporter_id`
- Multipart and `cityfix.map.*` properties resolve at startup in both profiles

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase. Phase blocks use plain bullets — the corresponding `- [ ]` checkboxes for these items live in the `## Progress` section at the bottom of the plan.

---

## Phase 2: Service Layer, Photo Validation and Ownership Scoping

### Overview

All business logic and every security-relevant decision, behind unit tests. No HTTP surface yet.

### Changes Required:

#### 1. Photo validator

**File**: `src/main/java/com/example/city_fix/report/PhotoValidator.java`

**Intent**: Decide whether an uploaded file is an acceptable image based on its actual bytes, so a renamed executable cannot reach storage.

**Contract**: A `@Component` with one method taking the uploaded file and returning the detected content type, throwing `InvalidPhotoException` otherwise. Rejects: empty files, anything over 2 MB — checked via `getSize()` **before** `getBytes()`, because this validator, not the container cap, is the enforcement point for the product limit (see Critical Implementation Details) — and any file whose leading bytes match none of the three allowlisted signatures. The client-supplied filename and `Content-Type` are never trusted for the decision.

The signatures are the non-obvious part:

```
JPEG  FF D8 FF                          at offset 0
PNG   89 50 4E 47 0D 0A 1A 0A           at offset 0
WebP  52 49 46 46 ("RIFF") at 0  AND  57 45 42 50 ("WEBP") at 8
```

#### 2. Report service

**File**: `src/main/java/com/example/city_fix/report/ReportService.java`

**Intent**: Create reports for the authenticated user and read them back scoped to that user, so the ownership rule lives in one place and cannot be bypassed by a future caller.

**Contract**: `@Service`, constructor injection only; depends on `ReportRepository`, `ReportPhotoRepository`, `PhotoValidator` and `UserRepository`. Four methods:
- `create(...)` — takes location, description, category, `Long reporterId` and an optional uploaded file; resolves the reporter with `userRepository.getReferenceById(reporterId)` (a proxy — no query, sufficient for the FK write; the id comes from an authenticated principal, so it exists); validates the photo when present — "present" means a non-null, non-empty `MultipartFile`; an empty part is treated as no photo, not as an invalid one; persists the report and then the photo. Annotated `@Transactional` (two writes — see Critical Implementation Details). Returns the saved `Report`. Status is always `NEW`; the caller cannot choose it.
- `listOwn(Long reporterId)` — delegates to `findByReporterIdOrderByCreatedAtDesc`.
- `getOwn(Long id, Long reporterId)` — delegates to `findByIdAndReporterId`, throwing `ReportNotFoundException` on empty.
- `getOwnPhoto(Long reportId, Long reporterId)` — resolves the report through `getOwn` **first**, then loads the photo. Ordering matters: the ownership check must gate the byte load, never follow it.

Exceptions are `public static` nested classes extending `RuntimeException`, following `AuthService.EmailAlreadyExistsException`: `ReportNotFoundException`, `InvalidPhotoException`.

#### 3. Service and validator unit tests

**File**: `src/test/java/com/example/city_fix/report/ReportServiceTest.java`, `src/test/java/com/example/city_fix/report/PhotoValidatorTest.java`

**Intent**: Lock the security-relevant behavior at the unit level before any HTTP layer exists, so Phase 5's integration tests confirm wiring rather than discover logic bugs.

**Contract**: `@ExtendWith(MockitoExtension.class)` with `@Mock`/`@InjectMocks` (mock `UserRepository` too, stubbing `getReferenceById`), AssertJ assertions, `scenario_expectedOutcome` naming — matching `AuthServiceTest`. Cover: create stamps `NEW` and the reporter; create without a photo writes no photo row; `getOwn` for a foreign reporter id throws `ReportNotFoundException`; `getOwnPhoto` for a foreign reporter id throws before any photo repository call (assert the repository is never touched); validator accepts each of the three signatures and rejects a spoofed extension, an oversize file and an empty file.

### Success Criteria:

#### Automated Verification:

- Application compiles: `./mvnw compile`
- `ReportServiceTest` passes
- `PhotoValidatorTest` passes
- Full suite still green: `./mvnw test`

**Implementation Note**: This phase has no manual verification items — it adds no user-visible surface. Proceed to Phase 3 once the automated checks pass.

---

## Phase 3: Submission — Map Page and POST

### Overview

The riskiest phase, isolated: vendor the map library, build the pin-placement page, and make submission work end to end. Verified by inspecting persisted rows, since the read-side views arrive in Phase 4.

### Changes Required:

#### 1. Vendored Leaflet assets

**File**: `src/main/resources/static/css/leaflet.css`, `src/main/resources/static/js/leaflet.js`, plus Leaflet's marker images under `src/main/resources/static/css/images/`

**Intent**: Serve the map library from the application itself — no CDN, no API key, no runtime third-party dependency.

**Contract**: Leaflet 1.9.x distribution files. The paths are load-bearing for two reasons: `SecurityConfig:70` permits only `/css/**` and `/js/**`, and Leaflet's stylesheet references its marker icons at `images/` **relative to the CSS file** — so placing the CSS at `/css/leaflet.css` makes the icons resolve to `/css/images/…`, which the same rule already permits. Record the exact Leaflet version in a comment at the top of `report-map.js` so future upgrades are traceable without a package manifest.

#### 2. Map interaction script

**File**: `src/main/resources/static/js/report-map.js`

**Intent**: Initialize the map, let the user place and move a single pin, offer device geolocation, and keep the hidden form fields in sync so a plain form POST carries the coordinates.

**Contract**: Reads the default centre and zoom from `data-` attributes on the map container (populated server-side from `cityfix.map.*`) rather than hardcoding them. Exactly one marker exists at a time; a map click moves it or creates it. Writes `latitude`/`longitude` into hidden inputs on every marker move. A "Use my location" button calls `navigator.geolocation.getCurrentPosition` and handles **both** the success and the error/timeout callbacks, surfacing a visible message on denial. Disables the submit button while no pin is placed, so the required-coordinate rule is enforced before the round trip. On the file input's `change` event, checks `file.size` against 2 MB and shows the same readable message inline, blocking submit until a smaller file is chosen — a UX convenience only; `PhotoValidator` remains authoritative. Vanilla JS, no framework — consistent with `register.html`'s inline script.

#### 3. Report form record

**File**: `src/main/java/com/example/city_fix/report/ReportForm.java`

**Intent**: Give the submission a typed, validated contract instead of a handful of loose request parameters.

**Contract**: A record with `Double latitude`, `Double longitude`, `String description`, `Category category`, carrying Bean Validation annotations: `@NotNull` on all four, `@DecimalMin("-90")`/`@DecimalMax("90")` on latitude, `@DecimalMin("-180")`/`@DecimalMax("180")` on longitude, `@NotBlank` and `@Size(max = 2000)` on description. Bound with `@Valid @ModelAttribute`; the `MultipartFile` stays a separate optional controller parameter since its validation is custom.

This deliberately extends `lessons.md`'s typed-DTO rule to a form controller. `AuthWebController` uses loose `@RequestParam`, but that rule was recorded precisely because S-01 would inherit whatever auth established, and five fields with range constraints is where loose parameters stop being defensible. Note the choice in the change log so a reviewer reads it as intent.

#### 4. Report web controller — submission routes

**File**: `src/main/java/com/example/city_fix/report/ReportWebController.java`

**Intent**: Render the pin-placement page and accept the multipart submission.

**Contract**: `@Controller`, no class-level `@RequestMapping` (matching `AuthWebController`). `GET /reports/new` populates the map defaults into the model and returns view `report-new`. `POST /reports` takes `@Valid @ModelAttribute ReportForm`, `BindingResult`, an optional `MultipartFile photo` (`required = false`; treat the photo as **absent** when `photo == null || photo.isEmpty()` — browsers always send an empty part for an unselected file input, so the validator's empty-file rejection must never see it), and `@AuthenticationPrincipal CustomUserDetails` — passing `CustomUserDetails.getId()` as `reporterId`, never a request field. On success, redirect to `/reports`. On validation failure or `InvalidPhotoException`, re-render `report-new` with an `error` model attribute and the submitted values — including latitude and longitude, so the user's pin survives a rejected submit. Follows `register.html`'s redisplay pattern.

`CustomUserDetails` must expose the user's id for this; if it does not already, add a getter rather than re-querying by email.

#### 5. Submission template

**File**: `src/main/resources/templates/report-new.html`

**Intent**: The pin-placement and submission page.

**Contract**: Standalone document with inline `<style>`, reusing the existing visual vocabulary (`system-ui` stack, centered max-width container, `#2563eb` primary, `#fef2f2` error box). Links `/css/leaflet.css` and `/js/leaflet.js`, then `/js/report-map.js`. `<form th:action="@{/reports}" method="post" enctype="multipart/form-data">` — the CSRF token is injected automatically by the security dialect, as in `home.html:23`. Contains the map container with `data-` attributes for centre/zoom, hidden `latitude`/`longitude` inputs, a description textarea, a category `<select>` populated from the enum, a `<input type="file" name="photo" accept="image/jpeg,image/png,image/webp">`, and the "Use my location" button. Viewport meta tag and a map height in `vh` so it is usable at 375 px width. Displays OpenStreetMap attribution — required by the tile usage policy.

#### 6. Landing page link

**File**: `src/main/resources/templates/home.html`

**Intent**: Make the new flow reachable, replacing the placeholder that names this slice.

**Contract**: Replace the `:20` placeholder paragraph (`<p>This is a placeholder landing page…</p>`) with links to `/reports/new` and `/reports`. Leave the `sec:authentication="name"` greeting and the logout form untouched, and add no principal-typed expressions (e.g. `#authentication.principal.id`): `SecurityConfigTest:71-76` renders `/` under `@WithMockUser`, whose principal is a plain `User`, and must keep passing.

#### 7. Static asset security test

**File**: `src/test/java/com/example/city_fix/config/SecurityConfigTest.java`

**Intent**: Back automated item 3.2 with a runnable check — today no test touches `/css/**` or `/js/**`, and an unauthenticated request there returns 404 because `static/` is empty.

**Contract**: Two assertions alongside `publicWebPaths_accessibleWithoutAuth`: unauthenticated `GET /css/leaflet.css` and `GET /js/leaflet.js` each return 200. No SecurityConfig change; the `permitAll` rule at `SecurityConfig:70` already exists — this pins it.

### Success Criteria:

#### Automated Verification:

- Application compiles: `./mvnw compile`
- `GET /css/leaflet.css` and `GET /js/leaflet.js` return 200 without authentication
- Full suite still green: `./mvnw test`

#### Manual Verification:

- Map renders on `/reports/new` with tiles and visible OSM attribution
- Clicking the map places a pin and populates the hidden lat/lng inputs; clicking again moves it
- "Use my location" centres and places the pin; denying permission shows a readable message and leaves the map usable
- Submitting a valid report redirects to `/reports` and persists a row with correct coordinates, category and status `NEW`
- Multipart POST with a photo succeeds — **specifically confirming no 403** (see Critical Implementation Details)
- A file over 2 MB is rejected with a readable message, and the pin and description survive the failed submit
- A non-image renamed to `.jpg` is rejected
- Page is usable at 375 px viewport width

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 4: My Reports — List, Detail and Photo Serving

### Overview

The read side, all of it ownership-scoped.

### Changes Required:

#### 1. Read routes

**File**: `src/main/java/com/example/city_fix/report/ReportWebController.java`

**Intent**: Let the resident browse their own reports and view one in detail, including its photo.

**Contract**: Three additions. `GET /reports` → view `report-list` with the reporter's own reports newest-first. `GET /reports/{id}` → view `report-detail`, plus a flag for whether a photo exists. `GET /reports/{id}/photo` → `ResponseEntity<byte[]>` with the stored content type and `Content-Disposition: inline`; no client-supplied filename is echoed. All three resolve the reporter from `@AuthenticationPrincipal` and go through the scoped service methods. `ReportNotFoundException` maps to **404**, not 403 — a foreign report must be indistinguishable from a non-existent one. Handle it with a local `@ExceptionHandler` on this controller, following `AuthController:87`'s precedent of keeping handlers controller-scoped rather than introducing `@ControllerAdvice`. The handler returns `ResponseEntity.status(HttpStatus.NOT_FOUND).build()` for both the HTML and the photo route — Boot's default `/error` (Whitelabel) rendering is acceptable for MVP; no `error.html` is added. A non-numeric `{id}` yields Spring's default 400, which is accepted and not mapped to 404.

#### 2. List and detail templates

**File**: `src/main/resources/templates/report-list.html`, `src/main/resources/templates/report-detail.html`, `src/main/resources/static/js/report-detail-map.js`

**Intent**: Show the resident their reports and the full content of one.

**Contract**: Both standalone with inline `<style>`, matching the existing palette. `report-list` renders a table or card list — category, status badge, created date, truncated description — each linking to its detail page, with an empty state pointing at `/reports/new`. `report-detail` shows description, category, status, created timestamp, a small read-only Leaflet map (non-interactive, marker at the report's coordinates, initialised by `/js/report-detail-map.js` reading lat/lng from `data-` attributes — `report-map.js` is form-page-only), and the photo via `/reports/{id}/photo` — rendered conditionally with `th:if` so a report without a photo produces no broken image. Both stay legible at 375 px.

### Success Criteria:

#### Automated Verification:

- Application compiles: `./mvnw compile`
- Full suite still green: `./mvnw test`

#### Manual Verification:

- `/reports` lists the user's own reports newest-first with status and category, and shows an empty state for a fresh account
- `/reports/{id}` shows description, category, status and the pin on a read-only map
- An attached photo displays on the detail page; a report without one renders cleanly with no broken image
- Both pages are usable at 375 px viewport width

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 5: Integration Tests, Security Tests and Verification

### Overview

Assert at the HTTP layer what Phase 2 asserted at the unit layer, with the cross-owner cases explicit. CI runs `mvnw clean package`, so everything here gates deploys.

### Changes Required:

#### 1. Controller integration tests

**File**: `src/test/java/com/example/city_fix/report/ReportWebControllerTest.java`

**Intent**: Verify the full stack — multipart binding, validation, persistence, ownership scoping and status codes — against a real database.

**Contract**: `@SpringBootTest @AutoConfigureMockMvc` extending `TestcontainersConfig`, using the Boot 4 import `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`. Multipart posts via `MockMvcRequestBuilders.multipart`, with `.with(csrf())` from `spring-security-test`. Establish authenticated users through the real registration/login flow the way `AuthControllerTest:124-138` does (the `MockHttpSession` carries over to the web chain), or `@WithUserDetails(value = <email>, setupBefore = TestExecutionEvent.TEST_EXECUTION)` after seeding the user in `@BeforeEach`. **Do not use `@WithMockUser` on any `/reports/**` route**: it installs a plain `org.springframework.security.core.userdetails.User` principal, so `@AuthenticationPrincipal CustomUserDetails` resolves to `null`. The cross-owner cases need two genuinely persisted users either way. Cover: valid submit with a photo → 302 to `/reports` with a persisted row; submit **without** a photo (empty file part) → 302, row persisted, no `report_photos` row; missing coordinates → re-rendered form, no row; oversize file → rejected; spoofed content type → rejected; list shows only the caller's reports; detail and photo for the caller's own report → 200; **detail and photo for another user's report → 404**; unauthenticated `/reports/**` → redirect to `/login`. Naming `scenario_expectedOutcome`.

#### 2. Change log entry

**File**: `context/changes/resident-reports-problem/change.md`

**Intent**: Record the two deliberate convention deviations so the impl-review reads them as decisions, not drift — the failure mode that produced the last slice's critical finding.

**Contract**: Append notes covering the first `@Transactional` in the project and the typed `ReportForm` in a web controller where `AuthWebController` uses loose `@RequestParam`, each with its one-line rationale.

### Success Criteria:

#### Automated Verification:

- `ReportWebControllerTest` passes
- Cross-owner access returns 404 for both `/reports/{id}` and `/reports/{id}/photo`
- Unauthenticated access to `/reports/**` redirects to `/login`
- Oversize and wrong-type uploads are rejected at the HTTP layer
- Full suite passes: `./mvnw clean test`
- Application packages: `./mvnw clean package`

#### Manual Verification:

- End-to-end walkthrough: register → login → place pin → submit with photo → see it in `/reports` with status `new` → open detail → photo and pin visible
- A second account cannot reach the first account's report or photo by direct URL
- Flow works in a mobile browser at 375 px, including geolocation over HTTPS

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before the change goes to `/10x-impl-review`.

---

## Testing Strategy

### Unit Tests:

- `ReportService`: status forced to `NEW`; reporter taken from the principal; no photo row when no file; `getOwn`/`getOwnPhoto` reject foreign reporter ids — with `getOwnPhoto` asserted to reject **before** touching the photo repository
- `PhotoValidator`: each allowlisted signature accepted; spoofed extension, oversize file and empty file rejected

### Integration Tests:

- Submit → persist → list → detail → photo, as one authenticated journey
- Two persisted users, with every read route asserted to 404 across the ownership boundary
- Validation failures re-render the form without persisting
- Unauthenticated access redirects to `/login`

### Manual Testing Steps:

1. Log in, open `/reports/new`, confirm the map renders with attribution
2. Place a pin by clicking; move it; confirm submit is disabled until a pin exists
3. Press "Use my location", accept — pin moves to the device position; repeat with permission denied and confirm the message
4. Submit with a photo under 2 MB; confirm redirect, list entry with status `new`, and photo on the detail page
5. Attempt a >2 MB file and a renamed non-image; confirm both are rejected and the pin survives
6. Register a second account; request the first account's report and photo URLs directly; confirm 404 for both
7. Repeat the core flow in a mobile browser at 375 px

## Performance Considerations

`ReportRepository` never loads photo bytes — that is the entire reason `report_photos` is a separate table with the FK on the photo side. Keep it that way: adding a photo association to `Report` would reintroduce the problem invisibly.

The B1 instance has 1.75 GB RAM with `-Xmx1g` advised, so a 2 MB cap on individual uploads is a memory decision as much as a storage one. This is also why no server-side image decoding happens anywhere in this slice — image decode is the classic OOM vector on a box this size. The 10 MB container cap does not weaken this: Tomcat spools parts to disk, and `PhotoValidator` refuses to read bytes for any part over 2 MB.

Report volume at MVP scale (`data_volume: small`, `qps: low`) needs no indexing beyond the FK, and `findByReporterIdOrderByCreatedAtDesc` is served fine by an index on `reporter_id`. Revisit only if the reports table grows past tens of thousands of rows.

OpenStreetMap's public tile servers carry a usage policy: attribution is mandatory and heavy automated traffic is not permitted. Acceptable at MVP scale; a real tile provider becomes a post-MVP concern if traffic grows.

## Migration Notes

No data migration. Both tables are new, and `spring.jpa.hibernate.ddl-auto=update` creates them on first boot in both profiles — the same mechanism that created `users`. Nothing existing is altered, so the change is additive and a rollback is just a redeploy of the previous JAR; the unused tables can stay.

The `bytea`-vs-`oid` trap in Critical Implementation Details is the one thing `ddl-auto=update` will not save you from: once a column is created as `oid`, `update` mode will not convert it. If Phase 1's manual check finds `oid`, drop the table and let it be recreated before any data exists.

Photo storage sits behind `ReportService`, so a later move to Azure Blob Storage is a contained change — swap the persistence of the bytes and keep the `/reports/{id}/photo` route as the ownership-checked entry point.

## References

- Roadmap slice: `context/foundation/roadmap.md` → `### S-01: Resident submits a geo-located report`
- PRD: `context/foundation/prd.md` → US-01, FR-003, FR-004, FR-005, FR-008, Access Control
- Recurring rules: `context/foundation/lessons.md` → "Typed DTOs over raw maps in controllers"
- Infrastructure constraints: `context/foundation/infrastructure.md` (B1 sizing line 127, cost ceiling line 129)
- Prior slice plan and review: `context/archive/2026-06-06-auth-role-scaffold/plan.md`, `.../reviews/impl-review.md`
- Entity pattern: `src/main/java/com/example/city_fix/user/User.java:13-62`
- Service + nested exception pattern: `src/main/java/com/example/city_fix/auth/AuthService.java:25-55`
- Web controller + error redisplay: `src/main/java/com/example/city_fix/auth/AuthWebController.java:40-52`
- Controller-scoped exception handler: `src/main/java/com/example/city_fix/auth/AuthController.java:87-96`
- Static path allowlist: `src/main/java/com/example/city_fix/config/SecurityConfig.java:69-73`
- CSRF-in-form precedent: `src/main/resources/templates/home.html:23-25`
- Integration test pattern: `src/test/java/com/example/city_fix/auth/AuthControllerTest.java:18-20,124-138`
- Testcontainers base: `src/test/java/com/example/city_fix/TestcontainersConfig.java`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Data Model and Configuration

#### Automated

- [ ] 1.1 Application compiles: `./mvnw compile`
- [ ] 1.2 Application starts and Hibernate creates `reports` and `report_photos`
- [ ] 1.3 Existing test suite still passes: `./mvnw test`

#### Manual

- [ ] 1.4 `report_photos.image_data` is `bytea`, not `oid`
- [ ] 1.5 `reports` columns and `reporter_id` FK are correct
- [ ] 1.6 Multipart and `cityfix.map.*` properties resolve in both profiles

### Phase 2: Service Layer, Photo Validation and Ownership Scoping

#### Automated

- [ ] 2.1 Application compiles: `./mvnw compile`
- [ ] 2.2 `ReportServiceTest` passes
- [ ] 2.3 `PhotoValidatorTest` passes
- [ ] 2.4 Full suite still green: `./mvnw test`

### Phase 3: Submission — Map Page and POST

#### Automated

- [ ] 3.1 Application compiles: `./mvnw compile`
- [ ] 3.2 `GET /css/leaflet.css` and `GET /js/leaflet.js` return 200 unauthenticated
- [ ] 3.3 Full suite still green: `./mvnw test`

#### Manual

- [ ] 3.4 Map renders on `/reports/new` with tiles and OSM attribution
- [ ] 3.5 Clicking the map places and moves a single pin, populating hidden inputs
- [ ] 3.6 "Use my location" works; denial shows a readable message
- [ ] 3.7 Valid submit redirects and persists correct coordinates, category, status `NEW`
- [ ] 3.8 Multipart POST with a photo succeeds with no 403 (CSRF/multipart ordering)
- [ ] 3.9 File over 2 MB rejected; pin and description survive
- [ ] 3.10 Non-image renamed to `.jpg` rejected
- [ ] 3.11 Page usable at 375 px viewport width

### Phase 4: My Reports — List, Detail and Photo Serving

#### Automated

- [ ] 4.1 Application compiles: `./mvnw compile`
- [ ] 4.2 Full suite still green: `./mvnw test`

#### Manual

- [ ] 4.3 `/reports` lists own reports newest-first, with an empty state for a fresh account
- [ ] 4.4 `/reports/{id}` shows description, category, status and the pin on a read-only map
- [ ] 4.5 Photo displays when present; absent photo renders cleanly
- [ ] 4.6 Both pages usable at 375 px viewport width

### Phase 5: Integration Tests, Security Tests and Verification

#### Automated

- [ ] 5.1 `ReportWebControllerTest` passes
- [ ] 5.2 Cross-owner access returns 404 for `/reports/{id}` and `/reports/{id}/photo`
- [ ] 5.3 Unauthenticated `/reports/**` redirects to `/login`
- [ ] 5.4 Oversize and wrong-type uploads rejected at the HTTP layer
- [ ] 5.5 Full suite passes: `./mvnw clean test`
- [ ] 5.6 Application packages: `./mvnw clean package`

#### Manual

- [ ] 5.7 End-to-end walkthrough: register → login → pin → submit with photo → list → detail
- [ ] 5.8 Second account gets 404 on the first account's report and photo URLs
- [ ] 5.9 Flow works in a mobile browser at 375 px, geolocation over HTTPS
