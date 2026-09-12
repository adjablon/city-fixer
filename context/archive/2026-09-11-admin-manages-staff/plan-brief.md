# Admin Manages Staff Accounts — Plan Brief

> Full plan: `context/changes/admin-manages-staff/plan.md`

## What & Why

Roadmap slice S-03, the final slice (PRD FR-002). Gives ADMIN a surface at `/admin/users` to create office-staff accounts and to deactivate or reactivate STAFF and RESIDENT accounts. Deactivation is a real revocation: it blocks future logins *and* expires the account's live session. Until now the only way to get a staff account was the `staff.seed.*` environment-variable bootstrap S-02 added as an explicit stopgap.

## Starting Point

`User` is an immutable entity with no activation state, and `CustomUserDetails` overrides none of the `UserDetails` account-status methods — so a flag alone would not block anything. There is no session infrastructure at all: no `sessionManagement()` block on either filter chain, no `SessionRegistry`, no Spring Session. No `/admin/**` route, controller or template exists. `AuthService.register` hard-codes `Role.RESIDENT`, so `StaffSeeder` is currently the only way a STAFF row comes into being.

## Desired End State

An admin follows a "Manage accounts" link from the home page to a table of every staff and resident account, creates staff accounts with an email and a password, and toggles any row's activation. A user deactivated mid-session finds their next request has returned them to the login page and cannot log back in. Admin accounts are neither listed nor actionable, so admin self-lockout is structurally impossible.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) |
| --- | --- | --- |
| Session termination | `SessionRegistry` + `expireNow()` | Spring's own mechanism — no per-request DB cost, and staying on the framework path avoids the drift that produced F-01's only CRITICAL. |
| Activation state | Nullable `active` boolean, null means active | `ddl-auto=update` cannot add a `NOT NULL` column to the populated `users` table; nullable plus a deliberate backfill works on fresh and existing schemas alike. |
| Who can be deactivated | STAFF and RESIDENT; never ADMIN | Delivers FR-002 plus resident deactivation while making self-lockout and admin-vs-admin lockout impossible — no last-admin guard needed. |
| Reactivation | Yes, a toggle | Costs almost nothing once the flag exists; without it a misclick is unrecoverable, since the unique email blocks re-creating the account. |
| Initial password | Admin types it, hands it over | No email exists (PRD Non-Goal #1); a forced-change flow would add a second cross-cutting mechanism to an already-HIGH slice. |
| `StaffSeeder` | Deleted with its test and properties | Its javadoc names this change as its terminus; leaving it keeps a second, unexercised writer of `User.role`. |
| Admin provisioning | Unchanged (`admin.seed.*`), added to the azure profile | Keeping the mechanism only works if it exists in prod — today the azure profile declares only `staff.seed.*`, so prod has no admin at all. |
| Account list | One flat list of all non-admin users | Matches the authorization scope, one query at MVP data volume; unbounded growth is an accepted, recorded limit. |
| Denied UX | Generic message at login and on eviction | Reuses the existing login surface and keeps the form from revealing which addresses exist but are disabled. |
| Reports by deactivated residents | Untouched, still visible to staff | A pothole does not stop existing because its reporter was disabled; avoids touching the S-02 triage surface. |
| Schema migration | Nullable column, explicit backfill, psql verification | `lessons.md` requires it — `ddl-auto=update` fails silently and a green boot is not evidence. |

## Scope

**In scope:** activation state on `User`; `isEnabled()` wiring; `DisabledException` handling on both login paths; `SessionRegistry` infrastructure across both filter chains; session registration for the hand-rolled API login; `/admin/users` service, controller and templates; the `/admin/**` matcher; home-page nav link; `StaffSeeder` removal; `admin.seed.*` in the azure profile; cross-role security tests.

**Out of scope:** admin self-service for ADMIN accounts; password reset or forced change; notifications; account deletion; audit trail; paging or search; changes to the S-02 triage surface; Spring Session or any distributed store; a custom 403 page; refactoring `AdminSeeder`.

## Architecture / Approach

`User.active` → `CustomUserDetails.isEnabled()` blocks authentication at the provider. `AdminUserService` owns the three operations and the ADMIN-refusal rule, persisting the flag first and then calling `UserSessionService`, which expires the principal's sessions through a `SessionRegistry` published in `SecurityConfig` and kept clean by an `HttpSessionEventPublisher`. `AdminUserController` at `/admin/users` is form-driven POST-redirect-GET, gated entirely by the `/admin/**` path matcher rather than annotations, following `StaffReportController`'s precedent.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Deactivation flag and login blocking | A deactivated row cannot log in through either path | The `NOT NULL` trap on the populated table; `DisabledException` 500ing the API login |
| 2. Session eviction infrastructure | A deactivated row is thrown out of its live session | Highest-risk phase — API sessions are invisible to the registry without explicit registration |
| 3. Admin service and controller | The three operations as gated routes | Matcher ordering; the ADMIN-refusal rule must live in the service, not the template |
| 4. Admin UI and supersession | Usable screens, nav link, seeder retired | A nav link drifting from the matcher (S-02 F1 repeat) |
| 5. Integration and security tests | An assertion behind every cross-role claim | Shared non-transactional container demands unique fixtures |

**Prerequisites:** F-01 (done), plus Docker running for Testcontainers.
**Closing bar:** local verification. The azure backfill and `ADMIN_EMAIL` / `ADMIN_PASSWORD` configuration are deploy-time follow-ups on a checklist in Migration Notes, not gates on this change.
**Estimated effort:** ~2-3 sessions across 5 phases.

## Open Risks & Assumptions

- **`SessionRegistry` is in-memory and per-instance.** Correct on the current single-instance App Service plan; scaling out would leave other instances' sessions alive until next login. Documented boundary, not a live defect — Spring Session JDBC was considered and deferred.
- **The API-login registration gap is the likeliest source of a silent failure.** `RegisterSessionAuthenticationStrategy` runs for `formLogin` but not for `AuthController`'s manual authentication, and that endpoint is how every integration test gets a session — eviction would look broken in tests while working in the browser.
- **`SessionRegistry` lookups key on the principal object**, so `CustomUserDetails` needs value-based `equals`/`hashCode` or eviction becomes a silent no-op.
- **Security-mechanism drift is this project's documented #1 risk.** If `SessionRegistry` proves wrong mid-flight, raise an addendum rather than improvising.
- **The account list is unpaged** and grows with resident registrations.
- **Production stays unreachable until the deploy-time checklist runs.** The admin surface ships without an admin in azure until `ADMIN_EMAIL` / `ADMIN_PASSWORD` are set and the backfill is applied — deliberate, since the change closes locally, but it means "done" does not yet mean "usable in prod".
- **Assumption:** the admin communicating a new staff password out-of-band is acceptable for MVP.

## Success Criteria (Summary)

- An admin can create a staff account and that person can log in; an admin can deactivate a staff or resident account and cannot touch an admin account.
- A deactivated user is ejected from their live session on the next request and is refused at the login form with the ordinary generic message.
- Reactivation fully restores access, and reports filed by a deactivated resident remain visible to staff.
