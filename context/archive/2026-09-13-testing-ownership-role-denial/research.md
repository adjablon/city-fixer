---
date: 2026-09-13T20:58:55+0200
researcher: Adam Jabłoński
git_commit: dc74941744e149520ad425b243c6973cf6ccfad4
branch: main
repository: 10x-devs-project
topic: "Ground rollout Phase 1 of the test plan: ownership and role denial on report surfaces (risks #1 and #2)"
tags: [research, codebase, authorization, spring-security, report, testing]
status: complete
last_updated: 2026-09-13
last_updated_by: Adam Jabłoński
---

# Research: Ownership and role denial on report surfaces

**Date**: 2026-09-13T20:58:55+0200
**Researcher**: Adam Jabłoński
**Git Commit**: dc74941744e149520ad425b243c6973cf6ccfad4 (unpushed; local `file:line` references only, no GitHub permalinks)
**Branch**: main
**Repository**: 10x-devs-project

## Research Question

Ground rollout Phase 1 of `context/foundation/test-plan.md`. Verify risks #1 (a resident
reads a report they did not file) and #2 (a resident performs a staff-only status change):
find the real failure path, verify or correct the response guidance, locate existing tests,
identify the cheapest useful layer, and flag speculative risks or misleading evidence.

## Summary

**Risk #1 is already defended, by strong evidence. Risk #2 is genuinely undefended.** The
phase as scoped is roughly one third the size the test plan assumed, and the reason is
favourable: S-01 made ownership structural rather than checked, and pinned it with
real-filter-chain integration tests.

Four conclusions drive the plan:

1. **Ownership scoping is enforced inside the query**, not by a post-fetch check
   (`ReportRepository.java:12`, `findByIdAndReporterId`). Every resident read surface —
   detail, photo, `hasPhoto`, list — routes through a reporter-scoped finder. A foreign
   report and a non-existent report are not merely mapped to the same response; they are
   **indistinguishable by construction**, because one query decides both.

2. **Existing cross-owner tests are trustworthy.** `ReportWebControllerTest.java:209-229`
   registers and logs in two real residents through the real endpoints and asserts 404 on
   both detail and photo. No test in this repository mocks the security mechanism it
   verifies — see "The mock-lie premise is false here" below.

3. **`POST /staff/reports/{id}/status` has no denial test at any level.** The endpoint
   carries no `@PreAuthorize`; its sole protection is one matcher line
   (`SecurityConfig.java:114`). Narrowing that matcher to `GET` would open status changes
   to every authenticated resident **and the suite would stay green**. The `/admin/**`
   surface has exactly this test (`SecurityConfigTest.java:113-122`); `/staff/**` does not.

4. **A naive test for #2 would pass for the wrong reason.** CSRF is enabled on the web
   chain, so a resident POST without a token returns **403 — the same status as a role
   denial**. The test must send `.with(csrf())` and be paired with a positive control.

## Detailed Findings

### Risk #1 — the read path (COVERED; recommend re-rating)

Scoping is structural, and was a deliberate S-01 decision rather than an accident.

`src/main/java/com/example/city_fix/report/ReportRepository.java:12`

```java
Optional<Report> findByIdAndReporterId(Long id, Long reporterId);
```

`src/main/java/com/example/city_fix/report/ReportService.java:54-57`

```java
public Report getOwn(Long id, Long reporterId) {
    return reportRepository.findByIdAndReporterId(id, reporterId)
        .orElseThrow(() -> new ReportNotFoundException(id));
}
```

The photo route resolves ownership **before** loading bytes — ordering that was called out
explicitly in the S-01 plan (`ReportService.java:59-65`):

```java
public ReportPhoto getOwnPhoto(Long reportId, Long reporterId) {
    // Ownership is resolved before the photo is loaded: a foreign report must never
    // reach the byte-loading query.
    Report report = getOwn(reportId, reporterId);
    return reportPhotoRepository.findByReportId(report.getId())
        .orElseThrow(() -> new ReportNotFoundException(reportId));
}
```

Denial semantics, `ReportWebController.java:134-139`:

```java
@ExceptionHandler(ReportService.ReportNotFoundException.class)
public ResponseEntity<Void> handleReportNotFound() {
    // 404 rather than 403: another user's report must be indistinguishable from
    // one that does not exist.
    return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
}
```

Read surfaces enumerated — no surface was found that bypasses scoping:

| Route | Gate | Scoping |
|---|---|---|
| `GET /reports` | `anyRequest().authenticated()` | `findByReporterIdOrderByCreatedAtDesc` |
| `GET /reports/{id}` | `anyRequest().authenticated()` | `findByIdAndReporterId` → 404 |
| `GET /reports/{id}/photo` | `anyRequest().authenticated()` | `getOwn` first, then bytes → 404 |
| `GET /staff/reports*` | `/staff/**` → `hasAnyRole("STAFF","ADMIN")` | deliberately unscoped |

**No JSON/XHR endpoint feeds any map.** `report-map.js`, `report-detail-map.js` and
`staff-report-map.js` contain zero `fetch`/`XMLHttpRequest`/`axios` calls; all three read
server-rendered `data-` attributes. The staff bulk payload (`reportsJson`,
`StaffReportController.java:62,120-128`) carries id, coordinates, status, category and
timestamp — **no description and no reporter email** — and is reachable only under
`/staff/**`.

Identity used for scoping is the numeric user id via `@AuthenticationPrincipal
CustomUserDetails` (`ReportWebController.java:111-115`), never the email.

### Risk #2 — the status-change path (UNCOVERED; this is the phase)

`src/main/java/com/example/city_fix/report/StaffReportController.java:84-102`

```java
@PostMapping("/{id}/status")
public String changeStatus(@PathVariable Long id,
                           @Valid @ModelAttribute("statusChangeForm") StatusChangeForm statusChangeForm,
                           BindingResult bindingResult,
                           RedirectAttributes redirectAttributes) {
```

The handler takes **no `Authentication`, no `@AuthenticationPrincipal`, no `Principal`**,
never calls `SecurityContextHolder`, and contains no role expression. The service is
explicitly role-blind (`StaffReportService.java:7-11`: *"nothing in this class filters by
reporter"*). Method security **is** enabled (`@EnableMethodSecurity`,
`SecurityConfig.java:23`) but is used in only two places project-wide, both on the resident
side (`ReportWebController.java:55,63`).

The entire enforcement is one line, `SecurityConfig.java:114`:

```java
.requestMatchers("/staff/**").hasAnyRole("STAFF", "ADMIN")
```

This is correct today — the matcher is method-agnostic and sits above `anyRequest()`. The
exposure is that it is a **single point of failure with no test behind it for writes**.

No actor is recorded on a status change (`Report.java:111-115` writes only `status` and
`statusUpdatedAt`), so the persisted status is the only observable a denial test can assert
on. There is no audit trail to check.

### The mock-lie premise is false in this repository (corrects risk #3)

Risk #3 in the test plan assumes tests may mock the mechanism they verify. Grepping
`src/test/java` for `addFilters|MockBean|MockitoBean|springSecurity|standaloneSetup|
MockMvcBuilders|WithUserDetails` returns **zero hits**. There is no `@WebMvcTest` and no
`@DataJpaTest`. Every one of the six authorization-touching classes is `@SpringBootTest` +
bare `@AutoConfigureMockMvc` (filters on) against a real Postgres container, and
`AccountDeactivationTest.java:51-55` deliberately autowires the **real** `SessionRegistry`,
with a comment naming the lesson that produced it.

`lessons.md`'s recorded burn has already been paid for and structurally corrected. Risk #3
must therefore be re-framed for Phase 2 (see Recommended corrections).

Two identity mechanisms are in use, and the distinction constrains test design:

- `@WithMockUser(roles="X")` — real filter chain, but a synthetic `User` principal with
  **no database id**. Fine for matcher tests; cannot exercise owner-identity logic.
- Real login sessions via `POST /api/auth/register` + `/api/auth/login`, reusing the
  returned `MockHttpSession` — genuine `CustomUserDetails` with real ids. This is what
  makes the cross-owner assertions trustworthy.

### Existing coverage — what is proven, and how well

| Claim | Evidence | Quality |
|---|---|---|
| Resident A denied resident B's detail + photo | `ReportWebControllerTest.java:209-229`, two real sessions, 404 | **Strong** |
| Resident denied the staff **GET** surface | `StaffReportControllerTest.java:71-82`, real resident session | **Strong** |
| Staff denied report filing (`@PreAuthorize`) | `StaffReportControllerTest.java:188-203`, real STAFF session | **Strong** |
| Resident's list shows only own reports | `ReportWebControllerTest.java:176-190` | **Strong** |
| Staff/resident denied `/admin/**` reads **and writes** | `SecurityConfigTest.java:90-128` | **Good** (synthetic principal) |
| CSRF enforced on `/admin` write | `SecurityConfigTest.java:130-138` | **Good** |
| Service-level cross-owner exception mapping | `ReportServiceTest.java:126-151` | **Weak** — the stub is told to return `Optional.empty()`, so it proves mapping, not scoping |
| **Resident denied `POST /staff/reports/{id}/status`** | — | **ABSENT** |
| **CSRF enforced on the status POST** | — | **ABSENT** |

`admin-manages-staff` phase 5 ("cross-role and report-integrity tests") does **not** cover
report ownership: it asserts admin-route role gating, deactivation/eviction, and that a
deactivated resident's reports stay visible to staff
(`archive/2026-09-11-admin-manages-staff/plan.md:412-469`).

### Test infrastructure available for reuse

- `TestcontainersConfig.java` — one **static** `postgres:17` container shared by every
  extending class, started once per JVM. **Nothing is `@Transactional`**
  (`AccountDeactivationTest.java:313`: *"Nothing is transactional and the container is
  shared, so every test owns its email"*). New tests must use unique emails/descriptions
  and must never assume an empty database.
- `UserFixtures` is **package-private in `…user`** and therefore **not reachable from
  `…report`**. It builds an in-memory `User` with a reflected id and does not persist.
- **No shared role-aware user helper and no shared persisted-report helper exist.** Four
  classes re-implement them privately: `StaffReportControllerTest.java:214-225`
  (`authenticateAs(email, Role)`, `registerResident`), `:242-259` (`submitReport`,
  `findByDescription`), plus near-duplicates in `ReportWebControllerTest.java:250-290`,
  `AccountDeactivationTest.java:270-284,314-321`, `AdminUserControllerTest.java:319-322`.
- Docker is required for 7 of 18 test classes (container starts in a static initializer).
  Without it those classes fail at class-init; the pure-unit classes still pass.

**Cheapest useful layer for risk #2**: an integration test added to
`StaffReportControllerTest`, which already owns `authenticateAs(email, Role)`,
`submitReport`, `findByDescription` and `changeStatus`. No new fixture, no new container,
no new class.

## Code References

- `src/main/java/com/example/city_fix/report/ReportRepository.java:12` — the reporter-scoped finder that makes ownership structural
- `src/main/java/com/example/city_fix/report/ReportService.java:54-65` — `getOwn` / `getOwnPhoto`, ownership before byte load
- `src/main/java/com/example/city_fix/report/ReportWebController.java:134-139` — 404-not-403 handler with its rationale
- `src/main/java/com/example/city_fix/report/StaffReportController.java:84-102` — the untested status-change endpoint
- `src/main/java/com/example/city_fix/report/StaffReportService.java:47-59` — unrestricted transitions, same-status no-op
- `src/main/java/com/example/city_fix/config/SecurityConfig.java:114` — the single line protecting the whole staff surface
- `src/main/java/com/example/city_fix/config/SecurityConfig.java:23` — `@EnableMethodSecurity`, used only at `ReportWebController.java:55,63`
- `src/main/java/com/example/city_fix/auth/CustomUserDetails.java:40-43` — `ROLE_` prefix added here; all matchers use the `hasRole` family, no `hasAuthority` anywhere
- `src/test/java/com/example/city_fix/report/ReportWebControllerTest.java:209-229` — the trustworthy cross-owner tests
- `src/test/java/com/example/city_fix/config/SecurityConfigTest.java:113-122` — the `/admin/**` write-denial test that `/staff/**` lacks
- `src/test/java/com/example/city_fix/config/SecurityConfigTest.java:130-138` — the CSRF-absent analogue
- `src/test/java/com/example/city_fix/report/StaffReportControllerTest.java:205-225,242-259` — helpers the new tests should reuse

## Architecture Insights

- **Authorization is split by intent**: ownership is enforced in the persistence query
  (impossible to forget); role is enforced at the path matcher (impossible to forget
  per-method, but concentrated in one editable line). Each style fails differently —
  ownership fails only if someone swaps the finder; role fails if someone edits a matcher.
- **`ReportService` vs `StaffReportService` is a deliberate boundary**: reporter-scoped
  finders never become role-aware parameters. Crossing the ownership boundary is a named,
  separately gated surface (`StaffReportService.java:7-11`).
- **Denial semantics are conventionalised**: 404 for cross-owner resource reads
  (indistinguishability), 403 for role boundaries. Recorded in
  `archive/2026-09-03-resident-reports-problem/plan-brief.md:32` and
  `archive/2026-09-09-staff-triages-reports/plan-brief.md:30`.
- **No `@ControllerAdvice` by convention**; every handler is controller-scoped.
- **CSRF is enabled on the web chain and disabled only for `/api/auth/**`**
  (`SecurityConfig.java:80-83`). This makes 403 ambiguous in tests unless a token is sent.

## Historical Context (from prior changes)

- `archive/2026-09-03-resident-reports-problem/plan.md:71` — *"Ownership is enforced in the
  query, not after it … deliberately structural — the last slice's critical finding came
  from a check that was possible to omit."*
- `archive/2026-09-03-resident-reports-problem/plan-brief.md:32` — the 404 decision and its
  reason (*"404 avoids confirming the report exists"*).
- `archive/2026-09-03-resident-reports-problem/reviews/impl-review.md:95-107` — F5: `hasPhoto`
  was safe *"by caller discipline rather than by construction … an existence oracle waiting
  for its second caller."* **Fixed** by scoping it.
- `archive/2026-09-09-staff-triages-reports/plan.md:7` — **PRD Open Question #1 was resolved**:
  *"transitions are unrestricted — any status to any status."* Pinned by a test over all
  twelve ordered pairs including `RESOLVED → NEW`.
- `archive/2026-09-09-staff-triages-reports/reviews/impl-review.md:114-126` — F5: the unscoped
  public `get(Long)` accepted as-is, *"authorization lives entirely in the `/staff/**` path
  matcher."* This is precisely what risk #2 tests.
- `archive/2026-09-11-admin-manages-staff/reviews/impl-review.md:72-80` — F3: admin write
  routes had no authz test until review; narrowing the matcher to GET made the staff-POST
  case *"fail with 302, not 403 — i.e. the account was actually created by a STAFF user."*
  The same latent hole exists on `/staff/**` today.
- **Explicit absence**: no archived change ever discusses a resident performing a status
  change, and no decision exists about an **admin** reading the resident `/reports/**`
  surface.

## Related Research

None — no archived change contains a `research.md`. Decision records live in
`plan-brief.md` (decisions table), `plan.md` ("What We're NOT Doing", "Critical
Implementation Details") and `reviews/*.md`.

## Recommended corrections to the test plan

Per the post-research backport check in `/10x-test-plan`. None of these add file anchors to
§2.

1. **Risk #1 — re-rate likelihood High → Low, annotate as covered.** Research disproved the
   premise that the read path is undefended. The residual value is regression detection,
   which the existing tests already provide.
2. **Risk #3 — re-frame.** "Tests mock the security mechanism" is false for this repository.
   The real failure mode is **coverage by omission**: a route with no denial test at all
   (`/staff/**` writes) versus one with (`/admin/**` writes). Phase 2 should enumerate
   routes, not de-mock tests.
3. **Risk #2 — confirmed as stated**, and it is the only genuinely uncovered item in
   Phase 1.

## Open Questions

1. **Should Phase 1 be re-scoped to risk #2 only?** Risk #1 needs no new test. Continuing
   to "cover" it would add redundant copies — a named anti-pattern in the project's own
   rules. Owner: user. Blocking: no, but it changes the plan's size.
2. **What is an admin's intended access to the resident `/reports/**` surface?** Never
   decided in any archived change. Admin is folded into `hasAnyRole('STAFF','ADMIN')` for
   `/staff/**`, and blocked from filing by `@PreAuthorize("hasRole('RESIDENT')")`, but an
   admin's *reads* of `/reports/{id}` fall to `anyRequest().authenticated()` and are
   reporter-scoped — so an admin simply sees their own (empty) list. Probably correct;
   undecided. Owner: user. Blocking: no.
3. **`prd.md` is stale.** Open Question #1 is answered in the archive but still listed as
   open at `prd.md:142` and in the roadmap. A future agent reading the PRD as oracle would
   invent transition rules that the product deliberately does not have. Owner: user.
   Blocking: no, but it is a live oracle hazard. Out of scope for this change.
4. **Known open defect, twice skipped**: the bodiless `ResponseEntity` 404 sets a status
   without an ERROR dispatch, so the user sees a blank page with `Content-Length: 0`
   (`archive/2026-09-03-resident-reports-problem/reviews/impl-review.md:109-116` F6;
   re-skipped in S-02 F4). A test should pin the **status**, not the empty body as
   desirable. Owner: user. Blocking: no.
