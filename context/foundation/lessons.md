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
