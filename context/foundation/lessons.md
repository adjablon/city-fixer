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
