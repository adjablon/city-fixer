<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Staff Triages Reports and Updates Status

- **Plan**: `context/changes/staff-triages-reports/plan.md`
- **Scope**: Full plan — Phases 1–5 of 5
- **Date**: 2026-09-10
- **Verdict**: NEEDS ATTENTION → APPROVED after triage (2026-09-10)
- **Findings**: 0 critical, 2 warnings, 4 observations — 3 fixed, 2 skipped, 1 accepted

> Note on method: the two review sub-agents both stalled with no output (infrastructure failure).
> This review was performed directly instead — mechanical guardrail checks, targeted code reads,
> and a fresh run of the automated gate. Self-review bias is therefore a real limitation of this
> report: an independent pass would carry more weight on the judgement calls, less so on the
> mechanical checks, which are reproducible from the commands recorded below.

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | WARNING |

## Evidence

- `./mvnw clean package` re-run at review time: **84 tests, 0 failures, 0 errors**, jar built.
- Diff scope `d2c98bf..HEAD`: 26 files, 1925 insertions. Every changed source file appears in the
  plan's Changes Required; the only non-source changes are the change folder artifacts and the
  roadmap status flip. No unplanned source files.
- Scope guardrails all held: no `Pageable`/`Page`/`Sort`, no `@Query`/`JOIN FETCH`, no second
  `@Index`, no `@ControllerAdvice`, no status-history entity, no `error.html`, no `fetch`/XHR,
  no `innerHTML` in the new script, and no `staff-report-detail-map.js` (the plan-review's F6
  script-reuse decision was honored — `staff-report-detail.html:63-64,84` satisfies the
  `#detail-map` + `data-lat`/`data-lng` contract of the shared script).
- No N+1 on the staff map: `StaffReportController.toPin` never touches `report.getReporter()`,
  and `staff-report-map.html` contains zero references to `reporter`.
- No XSS surface in the pin payload: every field is server-derived (id, coordinates, enum name,
  enum labels, server-formatted date). No user-supplied text is serialized into the page at all.

## Findings

### F1 — Home page offers residents-only action to staff and admin

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/resources/templates/home.html:20
- **Detail**: Phase 2 added `@PreAuthorize("hasRole('RESIDENT')")` to the report-creation
  handlers, but `home.html:20` still shows "Report a problem" to every authenticated user. A
  staff or admin user clicking it now gets a 403 rendered as Boot's unstyled Whitelabel page.
  Phase 3 correctly guarded the *new* staff link with `sec:authorize` (`:22`) but never guarded
  the existing resident link against the rule it had just introduced. Line 21 ("My reports") is
  harmless by comparison — staff simply see an empty list.
- **Fix**: Wrap the "Report a problem" paragraph in `sec:authorize="hasRole('RESIDENT')"`, the
  mirror of the guard already on the triage link.
  - Strength: One-line change in the same idiom already used three lines below; removes a
    dead-end link rather than papering over it with an error page.
  - Tradeoff: None material.
  - Confidence: HIGH — the 403 was reproduced during Phase 4 manual verification.
  - Blind spot: None significant.
- **Decision**: FIXED — home.html:20 wrapped in sec:authorize="hasRole('RESIDENT')"

### F2 — Staff photo route has no positive test

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Success Criteria
- **Location**: src/main/java/com/example/city_fix/report/StaffReportController.java (photo route); src/test/java/com/example/city_fix/report/StaffReportControllerTest.java:80
- **Detail**: The test suite asserts a resident gets 403 on `/staff/reports/{id}/photo` (`:80`)
  and that the detail model carries `hasPhoto = true` (`:106`), but nothing asserts that a staff
  user actually receives the bytes — no 200, no content type, no body. This is the one route in
  the slice that serves user-uploaded bytes with no ownership scoping, so its happy path is the
  one most worth pinning. S-01's plan-review established the rule that every claim needs a
  runnable test behind it; here a whole route has only negative coverage.
- **Fix**: Add one case to `StaffReportControllerTest`: resident files a report with a photo,
  staff GETs `/staff/reports/{id}/photo`, assert 200, `image/jpeg`, and the byte payload.
- **Decision**: FIXED — staffPhoto_isServedToStaffForAnotherUsersReport added (200, image/jpeg, bytes)

### F3 — AdminSeeder still lacks the email normalisation StaffSeeder now has

- **Severity**: 💬 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/main/java/com/example/city_fix/config/AdminSeeder.java:43-48; src/main/java/com/example/city_fix/config/StaffSeeder.java (normalisation)
- **Detail**: `StaffSeeder` normalises its seed email with `trim().toLowerCase(Locale.ROOT)`
  because `CustomUserDetailsService` lowercases before lookup, so a mixed-case seeded address
  could never log in. `AdminSeeder`, which it was copied from, still has that latent bug — an
  `ADMIN_EMAIL` of `Admin@Example.com` would seed a row nobody can authenticate against. F-01's
  impl-review fixed normalisation at register and login but not in the seeder. The divergence in
  this slice is the correct direction; the sibling is now the odd one out.
- **Fix**: Either apply the same normalisation to `AdminSeeder` (one line plus a test case), or
  record the underlying rule — normalise email at every write path, not just registration — as a
  recurring lesson so the third seeder does not repeat it.
- **Decision**: FIXED + ACCEPTED-AS-RULE: Normalise email at every write path, not just registration

### F4 — Bodiless 404 renders a blank page, now in a second controller

- **Severity**: 💬 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/main/java/com/example/city_fix/report/StaffReportController.java (handleReportNotFound)
- **Detail**: The handler returns a bodiless `ResponseEntity` 404, which sets the status without
  an ERROR dispatch, so the browser shows an empty page. S-01's impl-review recorded this exact
  behaviour as F6 and skipped it; this slice replicates the pattern in a second controller
  rather than resolving it. The status code — the part that matters for information disclosure —
  is correct.
- **Fix**: Out of scope to fix here. Worth resolving once, centrally, when error-page work is
  scheduled; duplicating it a third time is the thing to avoid.
- **Decision**: SKIPPED — status code is correct; resolve centrally with error-page work

### F5 — StaffReportService.get is public with a single internal caller

- **Severity**: 💬 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Architecture
- **Location**: src/main/java/com/example/city_fix/report/StaffReportService.java (get)
- **Detail**: `get(Long)` is called only by `changeStatus` within the same class. As a public,
  unscoped read it is available to any future caller without a role check of its own — the
  authorization lives entirely in the `/staff/**` path matcher. This is the same shape S-01's
  impl-review flagged as F5 for `hasPhoto` ("an existence oracle waiting for its second
  caller"), though the risk is lower here because the whole service is behind the matcher.
- **Fix**: Make `get` private, since `getWithReporter` is what the controller actually uses.
- **Decision**: SKIPPED — whole service sits behind the /staff/** matcher

### F6 — Two manual criteria have no observable evidence

- **Severity**: 💬 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Success Criteria
- **Location**: Progress items 4.9 and 5.9
- **Detail**: Both were ticked, but the database still contains only the four original reports,
  all filed by `p3probe@example.com`, whose password is unrecoverable — so the resident-side half
  of the loop (file a report, have staff change its status, see the update as its reporter) was
  not walked in a browser. The criterion itself is genuinely proven by
  `statusChange_persistsAndTheReportingResidentSeesIt`, which asserts exactly that sequence
  end to end, so this is a gap in manual evidence, not in correctness.
- **Fix**: No code change. Either walk it once in the browser with `resident@cityfix.local`, or
  accept the automated assertion as the evidence of record for the north-star criterion.
- **Decision**: ACCEPTED — statusChange_persistsAndTheReportingResidentSeesIt is the evidence of record
