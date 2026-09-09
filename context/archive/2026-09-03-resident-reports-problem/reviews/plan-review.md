<!-- PLAN-REVIEW-REPORT -->
# Plan Review: Resident Submits a Geo-located Report

- **Plan**: context/changes/resident-reports-problem/plan.md
- **Mode**: Deep
- **Date**: 2026-09-04
- **Verdict**: REVISE → SOUND after triage (2026-09-06)
- **Findings**: 1 critical, 4 warnings, 2 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| End-State Alignment | PASS |
| Lean Execution | PASS |
| Architectural Fitness | PASS |
| Blind Spots | FAIL |
| Plan Completeness | WARNING |

## Grounding

15/15 paths ✓, 9/9 symbols ✓, brief↔plan ✓, Progress↔Phase ✓ (6/4/11/6/9 items match). Confirmed by code (javap on resolved binaries — Boot 4.0.6, Security 7.0.5, Tomcat 11.0.21, Hibernate 7.2.12): `@Lob byte[]` → `oid`, plain `byte[]` → `bytea` via `PostgreSQLDialect.columnType`.

## Findings

### F1 — Oversize upload yields a bare 403, not a readable message

- **Severity**: ❌ CRITICAL
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Blind Spots
- **Location**: Phase 1 §5 (multipart config) · Phase 3 manual 3.9 · Phase 5 automated 5.4 · Manual Testing step 5
- **Detail**: Plan sets `spring.servlet.multipart.max-file-size=2MB` and promises a >2 MB file is "rejected with a readable message, and the pin and description survive". Boot 4 attaches the `MultipartConfigElement` to the `DispatcherServlet`, so Tomcat enforces the cap. `CsrfFilter` resolves the token via `request.getParameter("_csrf")`; Tomcat's `Request.parseParts(false)` catches the `SizeException`, clears all parts and returns null parameters. `CsrfFilter` throws `MissingCsrfTokenException` → 403 Whitelabel (no `error.html` exists) before the controller, `PhotoValidator`, `BindingResult` or the redisplay path run. Secondary: bodies well over the cap can hit Tomcat's 2 MB `maxSwallowSize` → connection reset. Test 5.4 would pass on a 403 assertion while the promised UX is impossible.
- **Fix A ⭐ Recommended**: Decouple the DoS cap from the product rule — raise container caps (e.g. `max-file-size=10MB`, `max-request-size=11MB`) so `PhotoValidator` owns the 2 MB rule and the form re-renders with the pin intact; check `getSize()` before `getBytes()`; add a client-side size pre-check in `report-map.js` as UX only; note that > container cap still yields a generic error.
  - Strength: Delivers 3.9/5.4 as written; parts spool to disk (`fileSizeThreshold=0`), so B1 heap is bounded by the validator, not the cap.
  - Tradeoff: Up to ~10 MB per request buffered to App Service temp disk before rejection.
  - Confidence: HIGH — mechanism traced in `CsrfFilter` / `Request` bytecode.
  - Blind spot: App Service temp-disk quota not checked.
- **Fix B**: Keep the 2 MB container cap and downgrade the promise — client-side size check is the primary UX; rewrite 3.9 / 5.4 to "rejected (403), no row persisted"; drop "pin and description survive".
  - Strength: No config or memory change; leanest.
  - Tradeoff: Without JS (or on a stale page) a 2.1 MB photo gets a raw 403; Progress titles are immutable after review, so 3.9's title stays misleading.
  - Confidence: HIGH on behaviour; MED that the UX is acceptable.
  - Blind spot: None significant.
- **Decision**: FIXED (Fix A) — 2026-09-06

### F2 — @WithMockUser cannot drive the report controller

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 5 §1 (controller integration tests)
- **Detail**: Phase 5 allows `@WithMockUser` "where a persisted user is not required". `WithMockUserSecurityContextFactory` builds a plain `org.springframework.security.core.userdetails.User`; with `errorOnInvalidType=false`, `@AuthenticationPrincipal CustomUserDetails` resolves to null → NPE on `getId()` in every `/reports/**` route.
- **Fix**: Strike the `@WithMockUser` option. Authenticate via the real register+login session (`AuthControllerTest:124-138` — the session carries to the web chain) or `@WithUserDetails(value=…, setupBefore = TEST_EXECUTION)` after seeding the user.
- **Decision**: FIXED — 2026-09-06

### F3 — "Photo when present" must mean non-empty, not non-null

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 2 §1–2 · Phase 3 §4
- **Detail**: Browsers always send the file part for `<input type="file">`, with an empty body when nothing was chosen. `PhotoValidator` "rejects empty files" and the service "validates the photo when present" — read literally, every no-photo submit is rejected. Only the unit layer covers the no-photo path; no HTTP-layer case exists.
- **Fix**: Define "present" as `photo != null && !photo.isEmpty()` in Phase 3 §4; add "submit without photo → 302, row persisted, no photo row" to Phase 5's integration cases.
- **Decision**: FIXED — 2026-09-06

### F4 — ReportService.create has no way to obtain the User reporter

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 2 §2 · Phase 3 §4
- **Detail**: `Report.reporter` is a `@ManyToOne User`, but the controller holds a `CustomUserDetails` (id, email, role). The service contract says "takes … reporter" without a type and lists no `UserRepository` dependency — the implementer must guess between an extra `findById` round-trip and a reference proxy.
- **Fix**: `create(...)` takes `Long reporterId`; `ReportService` depends on `UserRepository` and uses `getReferenceById(reporterId)` (no query, valid for the FK write). Add the mock to the `ReportServiceTest` contract.
- **Decision**: FIXED — 2026-09-06

### F5 — Phase 3 automated item 3.2 has no test behind it

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 3 Success Criteria / Progress 3.2
- **Detail**: "`GET /css/leaflet.css` and `GET /js/leaflet.js` return 200 without authentication" is listed as Automated, but Phase 3's Changes name no test file, and `SecurityConfigTest` asserts only `/login`, `/register`, `/actuator/health` (today `/css/**` is 404 — empty `static/`). `/10x-implement` will look for a runnable check and find none.
- **Fix**: Add "`SecurityConfigTest`: two assertions for `/css/leaflet.css` and `/js/leaflet.js` → 200 unauthenticated" to Phase 3 Changes (Progress title 3.2 stays as-is).
- **Decision**: FIXED — 2026-09-06

### F6 — 404 rendering for the HTML detail route is unspecified

- **Severity**: ℹ️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 4 §1
- **Detail**: The local `@ExceptionHandler` must serve both an HTML page and a `byte[]` route. No `error.html` exists, so a bare 404 renders the Whitelabel page; a non-numeric `{id}` yields 400, not 404. Neither is wrong, but the implementer will pick something.
- **Fix**: State the handler returns `ResponseEntity.status(404).build()` for both routes (Whitelabel acceptable for MVP), and note that non-numeric ids → 400 is accepted.
- **Decision**: FIXED — 2026-09-06

### F7 — Cross-reference nits

- **Severity**: ℹ️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Critical Implementation Details · Phase 3 §6 · Phase 4 §2
- **Detail**: (a) CSRF/multipart section says "item 3.10"; the item is 3.8. (b) Phase 3 §6 calls `home.html:20` a "placeholder comment" — it is a `<p>`; `SecurityConfigTest:71-76` pins `GET /` → 200 under a plain `User` principal, so the edit must stay principal-agnostic (plan already keeps `sec:authentication="name"` — worth stating why). (c) `report-detail`'s read-only map needs an init script; only `report-map.js` (form page) is named.
- **Fix**: Correct (a) and (b) inline; name the detail-map script in Phase 4 §2 (inline `<script>` or `js/report-detail-map.js`).
- **Decision**: FIXED — 2026-09-06
