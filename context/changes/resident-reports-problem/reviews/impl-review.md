<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Resident Submits a Geo-located Report

- **Plan**: context/changes/resident-reports-problem/plan.md
- **Scope**: Phases 1–5 of 5 (full plan)
- **Date**: 2026-09-08
- **Verdict**: NEEDS ATTENTION → 5 fixed, 5 skipped (triaged 2026-09-09)
- **Findings**: 0 critical, 5 warnings, 5 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | WARNING |
| Scope Discipline | WARNING |
| Safety & Quality | WARNING |
| Architecture | WARNING |
| Pattern Consistency | WARNING |
| Success Criteria | PASS |

**No critical findings.** The three properties this slice exists to guarantee were verified at runtime, not merely read:

- **Ownership** — `GET /reports` issues one query against `reports` only; `/reports/{id}/photo` runs the ownership-scoped query *before* the query that selects `image_data`. Cross-owner and non-existent ids both return 404 with byte-identical empty bodies.
- **Schema** — `report_photos.image_data` is `bytea`, not `oid`; a real 2464-byte PNG round-trips with an identical SHA-256.
- **Upload safety** — magic-byte allowlist, `getSize()` before `getBytes()`, no server-side image decoding.

## Findings

### F1 — Photo IOException is swallowed without logging, and infra failure is shown to users as validation error

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality / Pattern Consistency
- **Location**: src/main/java/com/example/city_fix/report/ReportWebController.java:76
- **Detail**: `PhotoValidator.readBytes` (PhotoValidator.java:47) translates a genuine infrastructure `IOException` — temp-file or disk failure — into `InvalidPhotoException`, preserving the cause. The controller's catch at line 76 then renders `e.getMessage()` to the user and discards the exception entirely. Nothing is logged, so an operator sees no trace of a disk failure, and the resident is told their photo is invalid when it is not. The `report` package contains no `Logger` at all, while `AdminSeeder.java:18` establishes the SLF4J convention and the plan (line 42) explicitly named it as the pattern to follow. This also violates the project's global rule that a swallowed exception must be logged at WARN or higher with enough context to diagnose it.
- **Fix**: Add `private static final Logger log = LoggerFactory.getLogger(ReportWebController.class)`. Separate "not a valid image" (user error — no log) from "could not read the bytes" (infra error — log at WARN with the report/user context and show a generic message), either via a second exception type or a flag on the existing one.
  - Strength: Restores the project's logging convention and stops misreporting infrastructure failures as user error.
  - Tradeoff: Small — one new exception type or flag plus a logger.
  - Confidence: HIGH — the rule is explicit and the code path is unambiguous.
  - Blind spot: None significant.
- **Decision**: FIXED — new PhotoUnreadableException separates infra failure from user error; controller logs at WARN with reporter id and shows a generic message

### F2 — No index on `reports.reporter_id`; the plan asserts one exists

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence / Safety & Quality
- **Location**: src/main/java/com/example/city_fix/report/Report.java:43 · plan.md:424
- **Detail**: `@JoinColumn` emits a foreign-key constraint only. Hibernate adds no index, and PostgreSQL — unlike MySQL — does not index the referencing column of an FK automatically. Verified directly: `\d reports` lists only `reports_pkey`, and `EXPLAIN SELECT * FROM reports WHERE reporter_id = 1 ORDER BY created_at DESC` yields `Seq Scan on reports`. The plan's Performance Considerations state the query "is served fine by an index on `reporter_id`" — that index does not exist, so the plan's stated reasoning rests on a false premise. (`report_photos.report_id` is fine: `unique = true` produces a real unique index.)
- **Fix**: `@Table(name = "reports", indexes = @Index(name = "idx_reports_reporter_created", columnList = "reporter_id, created_at"))` — `ddl-auto=update` creates it on next boot.
  - Strength: One annotation; the composite covers both the filter and the sort of the only list query.
  - Tradeoff: None material at MVP volume; the value is that the plan's premise becomes true.
  - Confidence: HIGH — confirmed by `\d` and `EXPLAIN` against the running database.
  - Blind spot: None significant.
- **Decision**: FIXED — composite index @Index(reporter_id, created_at) added; verified created on a fresh schema. Note: ddl-auto=update does not retro-fit indexes onto an existing table, so pre-existing local databases need it created manually

### F3 — Photo bytes are read off the multipart part twice

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: src/main/java/com/example/city_fix/report/PhotoValidator.java:46 · src/main/java/com/example/city_fix/report/ReportService.java:73
- **Detail**: `validateAndDetectContentType` calls `photo.getBytes()`, then `ReportService.create` calls its own `readBytes(photo)` which calls `getBytes()` again. Each call re-reads the spooled temp file into a fresh array through a doubling `ByteArrayOutputStream`, so a 2 MB photo costs roughly 6 MB transient per call — about 12 MB per in-flight upload on a 1 GB heap. The two private `readBytes` helpers are also duplicated, with messages that have already drifted ("Photo could not be read." vs "Photo could not be read"). Secondary: the bytes validated are not provably the bytes persisted, though the temp file is stable so this is not exploitable in practice.
- **Fix A ⭐ Recommended**: Have `PhotoValidator` return what it validated — e.g. `record ValidatedPhoto(String contentType, byte[] data)` — and persist exactly those bytes; delete `ReportService.readBytes`.
  - Strength: Halves transient heap on the upload path, removes the duplicated helper, and closes the validate-vs-persist gap.
  - Tradeoff: Changes the validator's return type, so its unit tests need updating.
  - Confidence: HIGH — single call site in production code.
  - Blind spot: None significant.
- **Fix B**: Leave as is and accept the cost.
  - Strength: No change; 2 MB is small in absolute terms and uploads are rare at MVP scale.
  - Tradeoff: Keeps the duplicated helper and the drifted messages; the plan leans on bounded heap for B1 sizing.
  - Confidence: MEDIUM — depends on concurrent-upload volume, which is unmeasured.
  - Blind spot: No load testing has been done.
- **Decision**: FIXED via Fix A — PhotoValidator.validate() returns ValidatedPhoto(contentType, data); getBytes() called once; duplicate readBytes helper deleted

### F4 — Photo route re-sends the full blob on every render and has no concurrency bound

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: src/main/java/com/example/city_fix/report/ReportWebController.java:103-113
- **Detail**: `/reports/{id}/photo` materialises the whole `bytea` on heap and returns it as `ResponseEntity<byte[]>`. Verified against the running app: Spring Security's default `CacheControlHeadersWriter` stamps `Cache-Control: no-cache, no-store, max-age=0, must-revalidate` plus `Pragma: no-cache` on the response, so a browser refetches the entire 2 MB blob on every render of the detail page. Tomcat's default `max-threads` of 200 is not overridden. Worst-case concurrent photo serving therefore sits well past `-Xmx1g` — a more plausible heap-exhaustion vector than the upload path.
- **Fix A ⭐ Recommended**: Opt this one route out of the no-store default with `.eTag(...)` and `CacheControl.maxAge(...).cachePrivate()`, and cap `server.tomcat.threads.max` for the B1 instance.
  - Strength: Removes the repeat transfer entirely for the common case and bounds worst-case concurrency; both are config-level changes.
  - Tradeoff: Caching authenticated binary content needs `cachePrivate()` to stay correct; an ETag adds a little code.
  - Confidence: HIGH — headers observed directly on the live response.
  - Blind spot: Have not measured real concurrency; the 200-thread worst case is theoretical.
- **Fix B**: Defer to the Blob Storage migration the plan already anticipates.
  - Strength: Solves it structurally rather than tuning around it; the plan notes photo storage sits behind `ReportService` for exactly this move.
  - Tradeoff: Leaves the exposure in place until that work happens, and it is not scheduled.
  - Confidence: MEDIUM — depends on when Blob Storage lands.
  - Blind spot: Blob Storage is explicitly out of scope and unbudgeted (~$25/month ceiling).
- **Decision**: SKIPPED

### F5 — `hasPhoto` is the only read on an ownership-scoped service that is not itself scoped

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Architecture
- **Location**: src/main/java/com/example/city_fix/report/ReportService.java:67 · called at ReportWebController.java:98
- **Detail**: Every other read takes `(id, reporterId)` and scopes in the query — that structural guarantee is the plan's stated reason for the design ("the last slice's critical finding came from a check that was possible to omit"). `hasPhoto(Long reportId)` takes only a report id. It is safe today solely because line 96 calls `getOwn` first, i.e. by caller discipline rather than by construction — the exact property the plan set out to eliminate. As a public method it is an existence oracle waiting for its second caller. This is also the method added beyond the plan's four-method contract.
- **Fix**: Give it the sibling `(reportId, reporterId)` signature, or fold photo presence into what `getOwn` returns so no unscoped read exists on the service.
  - Strength: Restores "ownership is a property of the query" across the whole service surface; one call site to update.
  - Tradeoff: Either a second query parameter or a small return-type change.
  - Confidence: HIGH — one production caller.
  - Blind spot: None significant.
- **Decision**: FIXED — hasPhoto(reportId, reporterId) now resolves ownership itself; new unit test asserts a foreign reporter is rejected before the photo repository is touched. Costs one extra scoped lookup on the detail page

### F6 — 404 renders a blank page, not the Whitelabel page the plan expected

- **Severity**: ℹ️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: src/main/java/com/example/city_fix/report/ReportWebController.java:119
- **Detail**: The plan (line 321) states the handler returns `ResponseEntity.status(HttpStatus.NOT_FOUND).build()` and that "Boot's default `/error` (Whitelabel) rendering is acceptable for MVP". In practice a bodiless `ResponseEntity` sets the status without calling `sendError`, so no ERROR dispatch occurs and no page is rendered. Verified with a browser-style request (`Accept: text/html`): `404` with `Content-Length: 0` and an empty body. A resident following a stale link gets a blank page, not Whitelabel. The status code — the security-relevant part — is correct.
- **Fix**: Return a view name or a redirect with a flash message from the HTML branch, keeping `ResponseEntity` for the binary photo route.
- **Decision**: SKIPPED

### F7 — No test round-trips photo bytes for equality or at a realistic size

- **Severity**: ℹ️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Success Criteria
- **Location**: src/test/java/com/example/city_fix/report/ReportWebControllerTest.java:61,264
- **Detail**: `jpegBytes()` produces 64 bytes and the assertions only check `existsByReportId`. No automated test asserts that stored bytes equal submitted bytes, and none exceeds the 255-byte default `@Column` length — the boundary that would expose a truncating column type. Given the plan's own warning that the `bytea`/`oid` trap is unrecoverable under `ddl-auto=update`, this is the one failure mode with no regression guard. (Verified manually this session: a 2464-byte PNG round-tripped with an identical SHA-256 — but nothing protects that.)
- **Fix**: Assert `reportPhotoRepository.findByReportId(id).get().getImageData()` equals the submitted array, using a payload around 1 MB.
- **Decision**: FIXED — 1 MB random payload round-tripped with byte-equality asserted, past the 255-byte truncation boundary

### F8 — Report timestamps render in the server's zone, which is UTC in production

- **Severity**: ℹ️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Scope Discipline
- **Location**: src/main/java/com/example/city_fix/report/ReportWebController.java:30
- **Detail**: `CREATED_AT_FORMAT` binds to `ZoneId.systemDefault()` and is handed to the views. Confirmed the JVM zone is UTC in the container environment, so a Warsaw resident sees times one or two hours behind local. This was an unplanned addition (the plan named no date-formatting approach) taken because `thymeleaf-extras-java8time` is absent; it is declared in `change.md`.
- **Fix**: Render the timestamp in a fixed display zone, or emit an ISO instant and let the browser localise it.
- **Decision**: SKIPPED

### F9 — `@EnableMethodSecurity` is on with no role checks; staff and admin can file resident reports

- **Severity**: ℹ️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Architecture
- **Location**: src/main/java/com/example/city_fix/config/SecurityConfig.java:20 · report routes
- **Detail**: The PRD's access-control section grants report creation to Residents, but `/reports/**` is gated only by `authenticated()`, so STAFF and ADMIN can create reports too. Not a data leak — every read stays ownership-scoped, so a staff member sees only their own — and the plan did not ask for role checks here. Flagged because the role model is declared and unenforced across the whole codebase, and S-02 will need it.
- **Fix**: Decide in S-02 whether report creation is resident-only; if so, `@PreAuthorize("hasRole('RESIDENT')")` on the create path.
- **Decision**: SKIPPED — deferred to S-02, which introduces the staff role model

### F10 — Uploads above the 10 MB container cap yield a bare 403, and nothing tests it

- **Severity**: ℹ️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Success Criteria
- **Location**: src/main/resources/application.properties:9-13 · src/test/java/com/example/city_fix/report/ReportWebControllerTest.java:100
- **Detail**: The plan accepted this explicitly ("Uploads exceeding the container cap still produce a generic error; that is accepted"). Worth recording that it is untested: the oversize test uses `MockMultipartFile`, which bypasses the container limit entirely and exercises `PhotoValidator`, not Tomcat. So the 2–10 MB path is verified while the >10 MB path is asserted but unproven. (The 2–10 MB path was confirmed against real Tomcat this session: a 3 MB upload re-rendered the form with the pin and description intact.)
- **Fix**: Add `@ExceptionHandler(MaxUploadSizeExceededException.class)` returning the form with a readable message — cheap insurance that does not depend on the Tomcat claim being right.
- **Decision**: SKIPPED — plan explicitly accepted a generic error above the container cap

## Not flagged

Reviewed and deliberately not raised as findings:

- **`getLabel()` vs the plan's `label()`** — SpEL resolves `${category.label}` only through the JavaBean form; the plan made the label conditional on template need and the templates need it. Declared in `change.md`.
- **`getImageData()` returns the backing array** — deliberate and documented; a defensive copy of a 2 MB array on every read is a real cost against `-Xmx1g` for a theoretical benefit, since the single caller only streams it.
- **Empty-part guard sits in the service, not the controller** — the plan assigned it to the controller, but the invariant it protects (the validator never sees a browser's empty part) holds and is unit-tested.
- **`ddl-auto=update` with no migration tooling** — pre-existing, and "No Flyway/Liquibase migration" is an explicit item in the plan's What We're NOT Doing.
- **Roadmap S-01 still reads `in-progress`** — by design; `/10x-archive` owns the flip to `done`.
- **Reads lack `@Transactional(readOnly = true)`; no pagination on the list** — consistent with the existing codebase and with the plan's stated MVP volume assumptions.
