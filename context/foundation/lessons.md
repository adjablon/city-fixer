# Lessons Learned

> Append-only register of recurring rules and patterns. Re-read at start by /10x-frame, /10x-research, /10x-plan, /10x-plan-review, /10x-implement, /10x-impl-review.

## Typed DTOs over raw maps in controllers

- **Context**: src/main/java/com/example/city_fix/auth/AuthController.java:34,64 — the project's first REST controller (auth-role-scaffold impl review, F9)
- **Problem**: Request bodies and responses were raw `Map<String,String>` — no compile-time contract, no Bean Validation (`@Valid`/`@Email`/`@Size` would replace hand-rolled checks in AuthService), stringly-typed keys. As the first controller in the project, S-01/S-02/S-03 controllers will copy whatever pattern it establishes.
- **Rule**: Controllers use Java records for request/response bodies with Bean Validation annotations; never raw Map DTOs.
- **Applies to**: All REST controllers — `@RequestBody` inputs and response payloads.

## Verify schema changes reach existing databases under ddl-auto=update

- **Context**: Any change that adds or alters a JPA mapping while `spring.jpa.hibernate.ddl-auto=update` is the schema mechanism (both profiles today) — new indexes, column type changes, constraint changes.
- **Problem**: `update` only creates missing tables and columns. It never alters an existing column's type, and it does not retro-fit indexes onto a table that already exists. In S-01 the `@Index` on `reports(reporter_id, created_at)` was created on fresh schemas but silently skipped on the already-existing local database; separately, a `@Lob byte[]` column created as `oid` can never be converted to `bytea` by `update`. Both failures are silent — the application boots green and the wrong schema persists.
- **Rule**: Never assume a mapping change reaches an existing database. When adding an index, changing a column type, or altering a constraint under `ddl-auto=update`, verify against the live schema (`\d <table>`, `pg_indexes`) rather than trusting a green boot, and state in the plan whether existing environments need a manual DDL statement or a table drop.
- **Applies to**: plan, plan-review, implement, impl-review

## Normalise email at every write path, not just registration

- **Context**: `src/main/java/com/example/city_fix/config/AdminSeeder.java:43-48` versus `src/main/java/com/example/city_fix/config/StaffSeeder.java` (staff-triages-reports impl review, F3). F-01's impl review already fixed normalisation at register and login (its F3), but the fix was applied per-call-site rather than to the concept.
- **Problem**: `CustomUserDetailsService` lowercases the submitted email before lookup, so any row written with a mixed-case address can never be authenticated against. Registration and login normalise; `AdminSeeder` does not. An `ADMIN_EMAIL` of `Admin@Example.com` seeds an account nobody can log into, and the failure is silent — startup logs a successful seed. S-02 added `StaffSeeder` with normalisation, which makes the sibling the odd one out rather than resolving the class of bug.
- **Rule**: Every code path that persists a `User` normalises the email with `trim().toLowerCase(Locale.ROOT)` before the uniqueness check and before the write — seeders and admin-created accounts included, not just self-registration. When a normalisation bug is found at one call site, check every other writer of the same field in the same change.
- **Applies to**: Any write of `User.email` — registration, seeders, and the admin-managed staff accounts coming in S-03. Also relevant to plan and impl review: a new writer of an existing field should be checked against how that field is read.

## A revocation flag only revokes once every enforcement point is wired

- **Context**: `src/main/java/com/example/city_fix/auth/CustomUserDetails.java` and `src/main/java/com/example/city_fix/config/SecurityConfig.java` (admin-manages-staff, S-03). `User.active` was added to let an admin deactivate an account.
- **Problem**: A persisted flag enforces nothing on its own, and every missing enforcement point fails *silently* — the flag flips, the UI looks right, and access continues. Three distinct wirings were needed and each was invisible when absent: `CustomUserDetails` overrode none of the `UserDetails` status methods, so `isEnabled()` had to be implemented before a flipped flag blocked any login; `DisabledException` is not a `BadCredentialsException`, so the API's narrow catch turned a deactivated login into a 500 rather than a 401; and `SessionRegistry` is populated by `RegisterSessionAuthenticationStrategy`, which `formLogin` runs but the hand-rolled `POST /api/auth/login` does not — so sessions created through the API were invisible to eviction and `expireSessions` was a no-op that logged success. The last one passed every mocked unit test and only failed against the real registry.
- **Rule**: When adding a flag that is supposed to deny something, enumerate every path that grants it and prove each one denies — with a test that fails before the wiring exists. Never accept a mocked test as evidence for a framework-integration point: mock the collaborators of the thing under test, not the mechanism you are trying to verify. When a framework applies a behaviour automatically on one code path, check whether a hand-rolled sibling path re-applies it (F-01 lost session-fixation protection the same way, and its review predicted exactly this recurrence).
- **Applies to**: Any deny-flag, permission bit, or revocation mechanism — plan, implement, and impl review. Also a prompt to prefer an integration test over a unit test whenever the assertion is about framework wiring rather than our own logic.

## Validation runs before normalisation, so constraints see the raw input

- **Context**: `src/main/java/com/example/city_fix/user/CreateStaffForm.java` (admin-manages-staff, S-03 phase 4), found by a failing end-to-end test.
- **Problem**: Bean Validation runs on the bound request value, before any service-layer normalisation. The project's shared email `@Pattern` is `^[^@\s]+@[^@\s]+\.[^@\s]+$`, which excludes whitespace, so an address pasted with a trailing space was rejected as "Invalid email format" and never reached `trim().toLowerCase()`. The admin form was therefore stricter than the `/register` form an admin already uses, which trims in `AuthService` *before* validating. Copying a DTO's constraints verbatim copied this mismatch along with them.
- **Rule**: When a field is normalised before use, normalise it before validation too — in the record's compact constructor or a binder — so the constraint judges the value that will actually be stored. Reusing another DTO's constraints means inheriting where in the pipeline they run, not just the annotations.
- **Applies to**: Any `@Valid` form or request DTO whose field the service later trims, lowercases, or canonicalises.

## Deploy-time steps outlive the change that created them — keep them here, not in the plan

- **Context**: `context/archive/2026-09-11-admin-manages-staff/plan.md` §Migration Notes (admin-manages-staff, S-03). The change recorded four steps that had to run against the deployed environment, then archived — sealing them into a read-only folder no skill will write to.
- **Problem**: A plan's Migration Notes are the natural place to *discover* a deploy-time step and the worst place to *track* one. `/10x-archive` makes the folder immutable, so the checklist stops being actionable exactly when the change is "done" and the steps are still outstanding. Worse, the failure is silent and delayed: the code ships, CI is green, and the feature is simply unreachable in production until someone remembers. S-03 shipped an admin surface that no admin could log into, because `admin.seed.*` had never been set on App Service.
- **Rule**: A change that needs a step run outside the repo — an env var, a manual DDL statement, a one-off backfill, a platform setting — copies that step into this file before `/10x-archive` runs, with the concrete command and how to verify it. Delete the entry once it has been performed. Archiving a change does not perform its deploy steps, and a plan is not a task tracker.
- **Applies to**: plan (state deploy-time steps explicitly), impl review (check they have a live home), archive (copy them out before sealing the folder).

## A green deploy step proves nothing; only a probe of the running app does

- **Context**: `.github/workflows/deploy.yml` — the publish step was a bare `curl` POST to Kudu's
  `/api/publish`, with no status check and nothing afterwards.
- **Problem**: `curl` exits 0 on any HTTP status it can connect to, so the step reported success
  on a `401` for four months. Every run was green while production kept serving the pre-auth
  May baseline — `/login`, `/register` and `/reports` all answered 404, `AdminSeeder` was not in
  the running build, and the `ADMIN_EMAIL` / `ADMIN_PASSWORD` settings were read by nobody. The
  cause compounded the invisibility: the site publishing user is `$cityfix-app-aj`, and a
  `secrets.*` reference is substituted into the run script *textually*, so bash expanded
  `$cityfix` as an undefined variable and sent `-aj:<password>`. The resulting 401 was
  indistinguishable from a stale secret and survived a credential rotation.
- **Rule**: three parts, and the third is the one that actually catches this class of bug.
  (a) A deploy step must fail on a non-2xx and print the response body and status — never rely on
  the exit code of a bare `curl`. (b) Any secret that might contain a shell metacharacter reaches
  the shell through step `env:`, never through an expression interpolated into the script.
  (c) A deploy is not verified until something requests a route from the *running* app that only
  the new build can serve. A publish returning 200 proves the upload was accepted, not that the
  process restarted onto it.
- **Applies to**: any CI/CD deploy step; any workflow referencing a secret inside a `run:` block;
  plan and impl review whenever a change's success depends on something outside the repo.

### Outstanding — CityFix production (delete each line once done)

Production first served the real application on 2026-09-14; before that the deploy had been
failing silently since May, so several of these were unreachable rather than undone.

- [ ] **Confirm the admin can actually sign in.** `AdminSeeder` logged
      `Admin account seeded for 'admin@cityfix.example'` at 2026-09-14T18:28:58Z, so the row
      exists with the password from `ADMIN_PASSWORD`. Closing this needs a real login plus
      `/admin/users` loading. Note the address is a non-routable `.example` domain and the seeder
      skips when the email already exists, so changing it now requires a direct database edit.
- [ ] **Confirm the `active` column and check whether the backfill is needed.** `\d users` should
      show `active | boolean`. The backfill
      (`UPDATE users SET active = true WHERE active IS NULL;`) is very likely a no-op here: the
      azure database had no `users` table until 2026-09-14, so Hibernate created it from the
      current mapping rather than migrating an existing one, and `User`'s constructor sets
      `active = true`. Verify rather than assume — `SELECT count(*) FROM users WHERE active IS NULL;`
      should return 0.
- [ ] **Confirm logout leaves no stale session-registry entry** against the real container. Now
      genuinely testable for the first time: `HttpSessionEventPublisher` needs container lifecycle
      events MockMvc cannot fire, and the suite only asserts the listener bean is registered.
- [ ] **Enable `httpsOnly`** on the App Service. It is currently `false`, so the site accepts
      plain HTTP and admin credentials could travel unencrypted:
      `az webapp update -g cityfix-rg -n cityfix-app-aj --set httpsOnly=true`.

Done and removed: the `admin.seed.*` settings are in place; the schema hazard from
`ddl-auto=update` did not apply because the table was created rather than migrated; and the
`STAFF_EMAIL` / `STAFF_PASSWORD` cleanup was moot — those settings were never present on the app.

> Single-instance constraint: `SessionRegistryImpl` is in-memory and per-instance, so deactivation only evicts sessions held by the instance serving the request. Correct on the current single-instance plan; scaling out requires a shared session store (Spring Session JDBC was considered and deferred).
