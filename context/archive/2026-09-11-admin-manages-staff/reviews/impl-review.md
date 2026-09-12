<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Admin Manages Staff Accounts

- **Plan**: `context/changes/admin-manages-staff/plan.md`
- **Scope**: Full plan — Phases 1-5 of 5
- **Date**: 2026-09-12
- **Verdict**: NEEDS ATTENTION
- **Findings**: 0 critical, 6 warnings, 4 observations
- **Reviewer note**: This change was planned and implemented by the same agent that ran this review. Findings were gathered by two independent sub-agents reading the code without the plan's own narrative, and the load-bearing claims were re-verified empirically (negative controls, behaviour probes) rather than taken on trust.

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | WARNING |
| Scope Discipline | WARNING |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | WARNING |
| Success Criteria | WARNING |

All automated criteria were re-run independently: 129/129 tests pass, `./mvnw clean package` builds the jar, and `grep -rn "StaffSeeder\|staff.seed" src/` is clean. No CRITICAL safety defect was found; four specific attacks were probed and all correctly refused (STAFF reaching admin routes, admin deactivating an admin or themselves, CSRF omission, XSS in the new templates).

## Findings

### F1 — `AdminUserService.setActive` lacks `@Transactional`, unlike its direct sibling

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality / Pattern Consistency
- **Location**: `src/main/java/com/example/city_fix/user/AdminUserService.java:79-101`
- **Detail**: `findById` and `save` run in two separate transactions, so the entity is detached between them and `save()` becomes a `merge()` issuing a full-row `UPDATE` from values read earlier. Two admins toggling concurrently is last-write-wins, and a concurrent write to any other column of that user row is silently lost. The direct sibling `StaffReportService.changeStatus` (`:47-49`) carries `@Transactional` with a comment stating this exact reasoning for the identical read-modify-write shape — verified.
- **Fix A ⭐ Recommended**: Add `@Transactional` to `setActive`, drop the explicit `save`, let dirty checking flush the single changed column.
  - Strength: Restores the sibling pattern verbatim; narrows the UPDATE to one column; removes the lost-update window.
  - Tradeoff: `expireSessions` then runs inside the transaction, so a rollback after eviction would evict sessions for a deactivation that never happened.
  - Confidence: HIGH — the sibling proves the pattern works in this codebase.
  - Blind spot: Whether any caller relies on the current non-transactional commit timing (none exists today).
- **Fix B**: Add `@Transactional` plus `registerSynchronization(afterCommit)` for the eviction.
  - Strength: Strictly correct ordering — sessions die only after the flag is durably committed.
  - Tradeoff: More machinery than any current code in this project uses; harder to read.
  - Confidence: MEDIUM — correct, but no precedent here to pattern-match against.
  - Blind spot: Interaction with the existing `inOrder` test, which would need rework.
- **Decision**: FIXED via Fix A — `@Transactional` added with the sibling's rationale comment, explicit `save` dropped. The two unit tests that verified `save()` was *called* failed and were rewritten to assert behaviour (flag state, eviction, and that no detached merge occurs); both integration suites stayed green throughout, which is what proved dirty checking persists the flag.

### F2 — No human has ever exercised this feature; every manual gate was eliminated

- **Severity**: ⚠️ WARNING
- **Impact**: 🔬 HIGH — architectural stakes; think carefully before deciding
- **Dimension**: Success Criteria
- **Location**: `context/changes/admin-manages-staff/plan.md` — all five `#### Manual` sections
- **Detail**: The plan shipped with 13 manual verification items. All five phases now read `_None_`. Each individual conversion was reasoned and user-approved, but the aggregate was never weighed: the app was never started, no browser ever rendered these templates, and no real database ever received the schema change. Compounding this, `TestcontainersConfig` starts a **fresh** Postgres per run, so Hibernate *creates* `users` with the `active` column and never *alters* a populated table. The single most emphasised constraint in the plan — that the column must be nullable because `ddl-auto=update` cannot add `NOT NULL` to a populated table (`lessons.md:12-17`) — therefore has **zero test coverage and cannot have any in this harness**. Marking it `@Column(nullable = false)` would keep all 129 tests green and fail only in production.
- **Fix A ⭐ Recommended**: Before archiving, do one real manual pass against a local Postgres that already holds rows — boot, verify `\d users`, run the backfill, walk create → deactivate → evict → reactivate in a browser.
  - Strength: Closes the one gap no test in this harness can close, and exercises the templates' actual rendering, which MockMvc content assertions only approximate.
  - Tradeoff: Requires provisioning a local database that does not currently exist on this machine.
  - Confidence: HIGH — the failure mode is documented in `lessons.md` from a prior slice.
  - Blind spot: Even a local pass does not exercise the azure profile.
- **Fix B**: Accept, and promote the deploy-time checklist into a real tracked task.
  - Strength: No environment setup; makes the residual risk visible where it will actually be seen.
  - Tradeoff: The first real exercise of this feature is then in production.
  - Confidence: MEDIUM — depends entirely on the checklist being honoured.
  - Blind spot: Nothing enforces the checklist today.
- **Decision**: FIXED via Fix A — a real manual pass was performed against a local Postgres 17 container holding a pre-change `users` table (no `active` column) with existing rows. Results, all previously unverified:
  - `ddl-auto=update` **did** add `active boolean` (nullable) to the populated table, and existing rows came back `NULL` exactly as the plan predicted. This is the constraint no test in this harness can reach.
  - A `NULL`-active legacy row logged in successfully, proving null-means-active against a genuine pre-migration row rather than a reflection fixture.
  - The backfill took the null count 2 → 0.
  - `ADMIN_EMAIL=Admin@Example.COM` seeded as `admin@example.com`, confirming the normalisation lesson holds at the real config boundary.
  - Browser-form walk: admin sees "Manage accounts"; the list renders resident + staff with role labels and Active/Deactivated badges and **no admin row**; creating `"  New.Staff@Example.COM  "` stored `new.staff@example.com` and that account logged in; deactivating it dropped its **live** session to `/login?expired` on the very next request and refused re-login with the generic 401; reactivation restored access.
  - STAFF received 403 on `/admin/users` and the nav link was absent from their rendered home page.
  - A report filed before its reporter's deactivation stayed visible on the staff map and detail page with the reporter email intact.
  - Environment torn down afterwards (app stopped, container removed).

### F3 — Admin write routes have no authorization or CSRF test

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: `src/test/java/com/example/city_fix/config/SecurityConfigTest.java:89-108`
- **Detail**: Coverage exists only for `GET /admin/users`. Nothing tests `POST /admin/users` or `POST /admin/users/{id}/active` for a non-admin, and nothing tests CSRF enforcement on either (every admin POST test passes `.with(csrf())`). **Probed and confirmed the code is correct today**: STAFF POST to both routes returns 403, and an admin POST without a CSRF token returns 403. This is a test gap, not a live vulnerability — but authorization here is purely matcher-based with no `@PreAuthorize` fallback by deliberate design, so a future edit narrowing the matcher to GET, or adding `/admin/**` to a CSRF ignore list, would open account creation and deactivation to STAFF with the whole suite staying green.
- **Fix**: Add three tests — STAFF POST to each write route expecting 403, and an admin POST without `csrf()` expecting 403.
- **Decision**: FIXED — `adminWriteRoutes_forbiddenToStaff` and `adminWriteRoute_withoutCsrfToken_isRejected` added to `SecurityConfigTest`. Verified by negative control: narrowing the matcher to `HttpMethod.GET` makes the staff-POST case fail with **302, not 403** — i.e. the account was actually created by a STAFF user. The test catches the exact escalation it was written for.

### F4 — `UserSessionServiceTest`'s central assertion is vacuous and the rest mirrors the implementation

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: `src/test/java/com/example/city_fix/user/UserSessionServiceTest.java:41-54`
- **Detail**: The fixture is a transient `User` whose `id` is null, and `CustomUserDetails.equals` compares **only** `id`. So `isEqualTo(new CustomUserDetails(user))` reduces to `Objects.equals(null, null)` — any `CustomUserDetails` built from any id-less user satisfies it. The comment above it claims the key "must be a `CustomUserDetails` equal to the one stored at login"; the assertion does not prove that. Separately, the lookup is stubbed with `any()` as the principal matcher, so a bug looking up the *wrong* principal still returns the stubbed sessions and the expiry assertions still pass. Principal-equality at lookup time is precisely the failure this mechanism risks, and a mocked registry is structurally incapable of catching it. The real coverage lives in `AccountDeactivationTest` against the actual registry.
- **Fix A ⭐ Recommended**: Give the fixture a real id, tighten the stub to an exact principal, and add the negative case (a different id must not match).
  - Strength: Makes the equality contract the whole eviction mechanism rests on actually tested.
  - Tradeoff: Needs the reflection helper promoted to a shared fixture.
  - Confidence: HIGH — the defect is provable by inspection.
  - Blind spot: None significant.
  - **APPLIED**: fixture now carries a real id via a new shared `UserFixtures` helper (also adopted by `AdminUserServiceTest`, replacing its private copy); stubs matched on the exact principal instead of `any()`; a negative assertion added that a different user's principal is never looked up. Verified by negative control — simulating a wrong-principal lookup now fails all three tests with `PotentialStubbingProblem`, where the old version passed. The class uses strict stubs, which is what makes that guardrail fire.
- **Fix B**: Delete the file and rely on `AccountDeactivationTest`.
  - Strength: The integration tests are strictly stronger; removes tests that restate a three-line loop.
  - Tradeoff: Loses fast feedback on the service in isolation.
  - Confidence: MEDIUM — depends on keeping the integration suite fast enough to run often.
  - Blind spot: None significant.
- **Decision**: FIXED via Fix A — see APPLIED note above.

### F5 — Nothing asserts the stored `active` value; verified by negative control

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: `src/main/java/com/example/city_fix/user/User.java:43-49`
- **Detail**: **Empirically verified**: deleting `this.active = true;` from the constructor entirely leaves all 129 tests passing. Every "account is active" assertion goes through `isActive()`, which returns true when the field is null, so no test distinguishes `active = TRUE` from `active = NULL` — not even the integration ones, because the row round-trips through Postgres as null and still reads as active. Harmless today (null-means-active is deliberate), but it means the column's stored value is unverified, and a future `NOT NULL` tightening has nothing guarding it.
- **Fix**: Assert the field rather than the accessor — reflectively in the unit test, or via a direct column query in one integration test.
- **Decision**: FIXED — `UserTest.newUser_isActive` now asserts the reflective field, and `AdminUserControllerTest` asserts the Postgres column directly via `JdbcTemplate` rather than `isActive()`. Proven by re-running the original negative control: deleting `this.active = true;` from the constructor, which previously left all 129 tests green, now fails both new assertions.

### F6 — The deactivation flash message promises more than the mechanism can deliver

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: `src/main/java/com/example/city_fix/user/AdminUserController.java:85`
- **Detail**: The message reads *"Account deactivated — any open session has been ended."* Two ways that is false. (1) `SessionRegistryImpl` is in-memory and per-instance, so on a scaled-out App Service eviction only reaches sessions held by the instance serving the POST — honestly documented in `SecurityConfig.java:38-41` and in Migration Notes, but the UI states it unconditionally. (2) A login already in flight can pass `isEnabled()` before the flag is written and register its session after the eviction sweep completes, leaving a live session that no later sweep will touch. Both are narrow; the UI copy is what turns them into a broken promise rather than a documented limit.
- **Fix A ⭐ Recommended**: Soften the message to "Account deactivated — they can no longer sign in," which is true unconditionally.
  - Strength: One-line change; the claim becomes accurate under every deployment topology and both race orderings.
  - Tradeoff: Loses the reassurance that the live session died, which is usually true.
  - Confidence: HIGH — no behaviour changes, only the claim.
  - Blind spot: None significant.
- **Fix B**: Add a filter revalidating the principal's `active` flag against the DB on authenticated requests.
  - Strength: Makes eviction instance-independent and closes the in-flight race — the only fix that survives a scale-out.
  - Tradeoff: A DB read per authenticated request, and a hand-rolled security mechanism, which is the drift class that caused F-01's only CRITICAL.
  - Confidence: MEDIUM — correct in principle; cost and drift risk are real.
  - Blind spot: Performance impact at this app's request volume is unmeasured.
- **Decision**: FIXED via Fix A — message softened to "Account deactivated — they can no longer sign in." with a comment stating that eviction is best-effort and why the weaker claim is the honest one. Fix B (per-request revalidation) remains the option if the deployment ever scales out; the in-memory registry limit is already recorded in Migration Notes and `CLAUDE.md`.

### F7 — Unplanned API-chain expired-session strategy

- **Severity**: 📝 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Scope Discipline
- **Location**: `src/main/java/com/example/city_fix/config/SecurityConfig.java:90-98`
- **Detail**: The Phase 2 contract described only the web chain's `expiredUrl`. The API chain also received a custom `expiredSessionStrategy` emitting 401 JSON. It is the right call — a redirect on `/api/**` would be wrong, and it matches the chain's existing entry point — but it is net-new security behaviour on a filter chain that no planned change described, added without an addendum.
- **Fix**: Record it as an addendum in the plan so the next review reads the plan as ground truth.
- **Decision**: FIXED — a `## Addenda (post-implementation review)` section was added to the plan covering this (A1) and the other four post-plan behaviours (A2-A5).

### F8 — Account list is unbounded and loads full entities including password hashes

- **Severity**: 📝 OBSERVATION
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: `src/main/java/com/example/city_fix/user/AdminUserService.java:43-47`
- **Detail**: `findByRoleInOrderByCreatedAtAsc` loads every resident and staff row as full `User` entities — bcrypt hashes included — and renders them all into one HTML table. The residents table is the one guaranteed to grow without bound in a city-resident app. `AccountRow` correctly excludes the hash at the view layer, but it is fetched from the database first. The plan recorded the unpaged list as an accepted MVP limit; it did not consider that hashes are loaded.
- **Fix**: Take a `Pageable` and add a Spring Data projection so the hash never leaves the database.
- **Decision**: FIXED — `UserRepository.findByRoleIn(Collection, Pageable)` now returns `Page<AccountSummary>`, a closed projection selecting five columns and no password hash. The service returns `Page<AccountRow>`; the controller pages at 25 with a `createdAt`+`id` sort so the ordering is total. The template gained a pager. A new test seeds 30 accounts and asserts exactly 25 rows on page 1, a Next link, a Previous link on page 2, and non-overlapping pages. Fixing this also exposed that `accountList_rendersManageableAccountsAndNeverAnAdminRow` silently depended on its rows landing on page 1 of a shared container — it now walks every page, which makes its admin-exclusion assertion stronger than before (no admin row on ANY page).

### F9 — Email normalisation is now duplicated across four sites, plus a fifth in the controller

- **Severity**: 📝 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: `src/main/java/com/example/city_fix/user/AdminUserController.java:72`
- **Detail**: `AuthService`, `AdminUserService`, `AdminSeeder` and `CustomUserDetailsService` each independently call `trim().toLowerCase(Locale.ROOT)`, and the email regex is now duplicated between `AuthService.EMAIL_PATTERN` and `CreateStaffForm`. This change added a fifth site: the controller re-derives the normalised email for its flash message instead of reading the `User` that `createStaff` returns — the return value is discarded at `:63`. `lessons.md:19-24` already records this as a recurring trap; the change added to it rather than resolving it.
- **Fix**: Use the returned `User`'s email for the flash message, and extract a shared `normalise` helper plus one regex constant.
- **Decision**: PARTIALLY FIXED — the controller now reads the email off the persisted entity instead of re-deriving it, removing the fifth normalisation site and the risk of reporting an address different from the stored one. The broader consolidation (a shared `normalise` helper and one regex constant across `AuthService`, `AdminUserService`, `AdminSeeder`, `CustomUserDetailsService`, `RegisterRequest`, `CreateStaffForm`) was **not** done — it touches code outside this change and belongs in its own refactor. `lessons.md:19-24` already carries the rule.

### F10 — Two half-finished assertions and a locally-only record of the domain rules

- **Severity**: 📝 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: `src/test/java/com/example/city_fix/user/AdminUserControllerTest.java:173-186`
- **Detail**: Three small gaps between contract and code. (1) Phase 5's contract says a refused ADMIN deactivation leaves "that account keeps working" — the test asserts the 403 and the flag, but never that the admin can still authenticate. (2) `createStaff_withATakenEmail` never asserts the pre-existing account was left untouched, so a bug overwriting its role while still reporting "already in use" would pass. (3) `CLAUDE.md` turned out to be gitignored, so the `### Account state` domain rules exist only in this checkout; two *process* lessons reached the tracked `lessons.md`, but the *domain* rules (persist-then-evict, null-means-active, admins-never-managed-here) did not.
- **Fix**: Complete both assertions, and move the domain rules into a tracked file.
- **Decision**: PARTIALLY FIXED — both assertions completed: the ADMIN-refusal test now logs that account in afterwards to prove it still works, and the taken-email test asserts the pre-existing row kept `Role.RESIDENT`. The domain rules remain in the gitignored `CLAUDE.md`; the two process lessons are tracked in `lessons.md`, and moving the domain rules to a tracked home is left open.
