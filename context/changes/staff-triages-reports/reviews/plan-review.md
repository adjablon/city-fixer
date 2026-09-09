<!-- PLAN-REVIEW-REPORT -->
# Plan Review: Staff Triages Reports and Updates Status

- **Plan**: `context/changes/staff-triages-reports/plan.md`
- **Mode**: Deep
- **Date**: 2026-09-09
- **Verdict**: REVISE → SOUND after triage (2026-09-09)
- **Findings**: 0 critical, 4 warnings, 5 observations — all 9 fixed

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| End-State Alignment | WARNING |
| Lean Execution | PASS |
| Architectural Fitness | PASS |
| Blind Spots | PASS |
| Plan Completeness | WARNING |

## Grounding

10/10 paths ✓, 2/2 symbols ✓, brief↔plan ✓, Progress↔Phase ✓ (6/3/9/9/9 items matched before triage; 6/3/7/11/9 after).

All five riskiest claims were verified against the code and came back CONFIRMED: `dateFormatter` is in the detail model (`ReportWebController.java:113`); `findWithReporterById` is a valid derived name; `@PreAuthorize("hasRole('RESIDENT')")` yields 403 for STAFF/ADMIN; the `/staff/**` matcher and the new annotation break no existing test; `ddl-auto=update` adds the nullable column. The findings below are in supporting references and unstated traps, not in the plan's substance.

## Findings

### F1 — Phase 3 automated criterion has no test behind it

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 3 — Success Criteria, item 3.3
- **Detail**: "GET /js/staff-report-map.js returns 200 without authentication" was an Automated item with no named test — S-01 plan-review F5's finding class. It also re-asserted a wildcard rule `SecurityConfigTest.java:53,56` already covers for `/css/leaflet.css` and `/js/leaflet.js`.
- **Fix A ⭐ Recommended**: Drop criterion 3.3 and its Progress item.
  - Strength: Removes an unbacked gate instead of adding a test that re-proves an existing rule.
  - Tradeoff: Phase 3 drops to two automated criteria.
  - Confidence: HIGH — existing assertions verified directly.
  - Blind spot: None significant.
- **Fix B**: Add a SecurityConfigTest assertion for the new script.
- **Decision**: FIXED via Fix A

### F2 — Phase 3 manual criteria can only pass after Phase 4

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: End-State Alignment
- **Location**: Phase 3 — Manual Verification, items 3.6 and 3.7
- **Detail**: 3.7 required "a working link to the detail page" before `/staff/reports/{id}` exists, and 3.6 required differing marker colours before anything could set a non-NEW status — the plan conceded this inline with "verifiable after Phase 4, or by a manual SQL update now". A gate that cannot be passed on its own terms gets waved through.
- **Fix**: Reword 3.7 to assert the popup href points at the detail route; move the working-link and colour checks into Phase 4's manual list.
- **Decision**: FIXED

### F3 — Jackson 3 classpath breaks the Phase 3 error-handling instruction

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Plan Completeness
- **Location**: Phase 3 — Changes Required #3
- **Detail**: The plan said to log and rethrow a Jackson serialization failure, written against Jackson 2. `SecurityConfig.java:3` imports `tools.jackson.databind.ObjectMapper` — Jackson 3, where `JsonProcessingException` no longer exists and serialization throws the unchecked `tools.jackson.core.JacksonException`. The Jackson 2 idiom will not compile.
- **Fix**: State the Jackson 3 import, that failures are unchecked, and that the catch is optional — `JacksonException` if kept for the log.
- **Decision**: FIXED

### F4 — Two incorrect line references

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 2 #3, Phase 3 #6
- **Detail**: `SecurityConfigTest.java:71-76` cited for the `@WithMockUser` "/" test, which is at `:80-85`; and `ReportWebController` `:53`/`:60` cited as the `@PreAuthorize` targets, which are signature lines — the annotations belong above `@GetMapping` (`:52`) and `@PostMapping` (`:59`). Both underlying constraints were correct.
- **Fix**: Correct both references.
- **Decision**: FIXED

### F5 — Second status-colour source of truth

- **Severity**: 💬 OBSERVATION
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Architectural Fitness
- **Location**: Phase 3 — Changes Required #4 and #5
- **Detail**: Marker colours became JS constants while the four statuses already have a CSS badge palette (`report-detail.html:15-18`, `report-list.html:26-29`). Two palettes for one enum drift, and the map legend would then disagree with the detail-page badge.
- **Fix A ⭐ Recommended**: Derive marker colours from the badge palette, with a comment naming the badge classes as the source of truth.
  - Strength: One visual vocabulary with an obvious home for future changes.
  - Tradeoff: The hex values are still duplicated into JS — now deliberately and documented.
  - Confidence: MEDIUM — both palettes verified; badge-colour contrast against map tiles not verified.
  - Blind spot: How the badge backgrounds read as map markers.
- **Fix B**: Drop colour-coding this slice; identical markers with status in the popup.
- **Decision**: FIXED via Fix A

### F6 — staff-report-detail-map.js duplicates an identical script

- **Severity**: 💬 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Lean Execution
- **Location**: Phase 4 — Changes Required #4
- **Detail**: The plan mandated a new ~32-line script functionally identical to `report-detail-map.js`, justified by the one-script-per-page convention. That convention exists for page-specific behaviour; a static asset is not a shared module, and the staff template can reference the existing file if it matches the `#detail-map` id (`report-detail-map.js:8`).
- **Fix**: Reuse `report-detail-map.js` and record the element-id contract; split a copy out only if the staff map diverges.
- **Decision**: FIXED

### F7 — Derived-query typos fail at startup, not compile

- **Severity**: 💬 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Blind Spots
- **Location**: Phase 2 — Changes Required #1
- **Detail**: `findWithReporterById` is valid, but a malformed derived name raises `PropertyReferenceException` at context startup and breaks every `@SpringBootTest` including `contextLoads`, while `./mvnw compile` stays green. Item 2.3 catches it; the plan did not warn about the failure mode.
- **Fix**: One sentence in Critical Implementation Details.
- **Decision**: FIXED

### F8 — 403 renders the Whitelabel page

- **Severity**: 💬 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Blind Spots
- **Location**: Phase 3 #1; manual steps in Phases 3 and 4; Testing Strategy step 9
- **Detail**: No `AccessDeniedHandler` and no `error.html` exist; `exceptionHandling` is configured only on the API chain (`SecurityConfig.java:38-46`). The 403 is correct but the browser body is Boot's unstyled Whitelabel page — the same surprise shape as S-01 impl-review F6 (the blank 404).
- **Fix**: Record the Whitelabel outcome in Critical Implementation Details, in the manual steps and in the brief's Open Risks.
- **Decision**: FIXED

### F9 — "ReportService is not touched" hides a new inbound coupling

- **Severity**: 💬 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Architectural Fitness
- **Location**: Implementation Approach; Phase 2 #2; Phase 4 #2; References
- **Detail**: The staff service and controller reuse `ReportService.ReportNotFoundException` (`ReportService.java:74-75`), so the staff surface depends on a nested type of the service described as untouched. The References section also omitted genuinely coupled files: `ReportPhotoRepository`, `ReportPhoto`, `ReportStatus`, `Category`, `User`/`UserRepository`/`Role`, `CustomUserDetails`, and `report-list.html`, which renders the status badge.
- **Fix**: State the coupling as deliberate — one not-found type for the report domain — and add the coupled files to References.
- **Decision**: FIXED

## Not raised

- **Concurrent status changes are last-write-wins** with no optimistic locking. The obvious and acceptable default for a single enum field at this scale.
- **Phase 4 has no automated criteria of its own**, deferring assertions to Phase 5. S-01 used the same shape.
