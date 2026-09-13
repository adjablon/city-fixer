# Test Plan

> Phased test rollout for this project. Strategy is frozen at the top
> (§1–§5); cookbook patterns at the bottom (§6) fill in as phases ship.
> Read before writing any new test.
>
> Refresh: re-run `/10x-test-plan --refresh` when stale (see §8).
>
> Last updated: 2026-09-13

## 1. Strategy

Tests follow three non-negotiable principles for this project:

1. **Cost × signal.** The cheapest test that gives a real signal for the
   risk wins. Do not promote to e2e because e2e "feels safer." Do not put a
   vision model on top of a deterministic visual diff that already catches
   the regression.
2. **User concerns are first-class evidence.** Risks anchored in "the team
   is worried about X, and the failure would surface somewhere in \<area\>"
   carry the same weight as PRD lines or hot-spot data.
3. **Risks are scenarios, not code locations.** This plan documents *what
   could fail* and *why we believe it's likely* — drawn from documents,
   interview, and codebase *signal* (churn, structure, test base). It does
   NOT claim to know which line owns the failure. That knowledge is
   produced by `/10x-research` during each rollout phase. If the plan and
   research disagree about where the failure lives, research is the
   ground truth.

Project-specific corollary to #1: this codebase has already been burned by a
mocked test that passed while the real framework wiring was a no-op
(`lessons.md` §"A revocation flag only revokes once every enforcement point
is wired"). Mock the collaborators of the thing under test; never mock the
mechanism you are trying to verify.

Hot-spot scope used for likelihood weighting: `src/main/java`,
`src/main/resources/templates`, `src/main/resources/static/js`,
`src/test/java`. Excluded: `target/`, `context/`, `.github/`, vendored
`leaflet.js`.

## 2. Risk Map

The top failure scenarios this project must protect against, ordered by
risk = impact × likelihood. Risks are failure scenarios in user / business
terms, not test names. The Source column cites the *evidence that surfaced
this risk* — never a specific file as "where the failure lives" (that is
research's job, see §1 principle #3).

| # | Risk (failure scenario) | Impact | Likelihood | Source (evidence — not anchor) |
|---|---|---|---|---|
| 1 | A resident opens a report they did not file and sees its description, photo, and reporter identity | High | Low | interview Q1; PRD §Success Criteria guardrail "residents' personal data and reports must not leak to other residents"; PRD §Access Control "Resident — view own reports"; PRD §Non-Goals #2; `archive/2026-09-03-resident-reports-problem/plan-brief.md` — ownership scoping and 404 semantics decided there; Phase 1 research 2026-09-13 — re-rated from High, see note below |
| 2 | A resident advances a report's status, performing an action the PRD reserves for office staff | High | Medium | PRD FR-007; PRD §Access Control; interview Q1 (write face); hot-spot dir `src/main/java/com/example/city_fix/report/` — 23 commits/30d |
| 3 | An access rule is changed, a route is silently opened, and the suite stays green because no test ever asserted denial on that route | High | High | interview Q2 (team burned by a related failure); `lessons.md` §"A revocation flag only revokes once every enforcement point is wired"; `archive/2026-09-11-admin-manages-staff/reviews/impl-review.md` F3 — write routes had no authorization test until review; Phase 1 research 2026-09-13 — re-framed from a mocking premise, see note below |
| 4 | A report persists with a missing, out-of-range, or transposed coordinate; the pin is wrong and the report is undispatchable | High | Medium | PRD §Success Criteria guardrail "a misplaced pin makes the report useless for dispatch"; PRD §Business Logic "the map is not decorative"; interview Q3; hot-spot dirs `src/main/resources/static/js/` and `src/main/java/com/example/city_fix/report/` — 23 commits/30d |
| 5 | An oversized or wrong-type photo payload is accepted server-side and reaches storage | Medium | Medium | PRD FR-004; `lessons.md` §"Verify schema changes reach existing databases under ddl-auto=update"; abuse lens — resource abuse and untrusted input |
| 6 | A configuration change removes session-expiry enforcement, and a deactivated account keeps working through an already-live session | High | Low | `lessons.md` §"A revocation flag only revokes once every enforcement point is wired" (three silent gaps already found); `CLAUDE.md` §Account state; hot-spot dir `src/main/java/com/example/city_fix/user/` — 14 commits/30d; Phase 2 research 2026-09-13 — re-worded from a per-route premise, see note below |

Abuse lens coverage: authorization/access → #1, #2, #3, #6; untrusted input
and server-side validation parity → #4, #5; resource abuse → #5; PII
leakage → #1 (reporter identity disclosed to a non-owner).

Risk #6 is High impact × Low likelihood: the deactivation path shipped
recently with dedicated coverage. It is kept because the failure mode is a
*future* route omitting the check, and because Phase 2's route inventory
defends it at near-zero marginal cost — not because the shipped path is
suspected.

**Backported from Phase 1 research, 2026-09-13** (see
`context/changes/testing-ownership-role-denial/research.md`). Row order and
numbering are unchanged so §3 references stay valid; risk #1 no longer sorts
where it sits.

- **Risk #1 re-rated High → Low likelihood.** Research disproved the premise
  that the read path is undefended: ownership is enforced inside the
  persistence query on every resident read surface, a foreign report is
  structurally indistinguishable from a missing one, and real-session
  cross-owner tests already exist. The residual exposure is regression on a
  *future* read surface, which the existing tests largely already cover.
- **Risk #3 re-framed.** The original wording assumed tests might mock the
  security mechanism. That is false for this repository — no security
  collaborator is mocked anywhere, and every authorization test runs the real
  filter chain against a real database. The live failure mode is narrower and
  more mundane: a route, or one HTTP method on a route, that no test ever
  asserts denial for. Phase 2 enumerates routes; it does not de-mock tests.
- **Risk #2 confirmed as written** — and found to be the only genuinely
  uncovered item in Phase 1.

**Backported from Phase 2 research, 2026-09-13** (see
`context/changes/testing-route-denial-inventory/research.md`).

- **Risk #6 re-worded and its response guidance replaced.** The original
  scenario assumed a route could "skip the account-state check", and the
  guidance proposed proving denial "on every authenticated route, by the same
  enumeration as #3". Research disproved the premise: account state is not
  evaluated per route at all. It is read once, at authentication, against a
  snapshot that is never refreshed; revoking a live session is an out-of-band
  eviction that a single filter enforces ahead of every matcher and handler.
  A per-route sweep for a deactivated identity would therefore produce one
  test per route that all fail for the same single cause — the redundant-copies
  anti-pattern this plan names. What genuinely varies is the chain, not the
  route, so the guidance now points at per-chain expiry semantics, the untested
  interaction between CSRF and an expired session on a browser-chain POST, and
  a configuration assertion that no chain can be added without expiry
  enforcement.
- **Not backported**: research also recommended extending risk #3's "must
  challenge" cell to cover a denial test that has no working permission
  counterpart, which passes equally when the rule denies everyone. Left for a
  later decision; the finding is recorded in the Phase 2 research document.
- **§3 Phase 2's goal cell was also corrected**, at the user's explicit
  direction. It previously read "or skipping the account-state check … across
  the enumerated route set", carrying the same per-route premise. It now names
  the two distinct protections the phase must deliver: a derived route set for
  risk #3, and per-chain expiry enforcement for risk #6. Only the Goal cell
  changed; the phase name, risks, test types, status and change folder are
  untouched.

### Risk Response Guidance

| Risk | What would prove protection | Must challenge | Context `/10x-research` must ground | Likely cheapest layer | Anti-pattern to avoid |
|---|---|---|---|---|---|
| #1 | Resident B requesting resident A's report is denied, and the denial leaks no signal that the report exists | **Already satisfied** — research verified query-level scoping on every read surface and real-session cross-owner tests. Challenge instead any claim that a *new* read surface inherits the scoping automatically | Grounded 2026-09-13: scoping lives in the repository finder, not a post-fetch check; denial is 404 by recorded decision, not 403 | none — existing integration coverage stands | **redundant copies**: adding further cross-owner tests duplicates what exists and buys no new regression |
| #2 | A resident's status-change request is refused and the persisted status is unchanged afterwards | That hiding the control in the template equals denying the request | Where the staff-only rule is enforced, and whether it survives a direct request that bypasses the UI | integration | asserting absent UI markup instead of a refused request plus an unchanged persisted row |
| #3 | Loosening or removing an access rule makes a test fail — for every authenticated route **and every HTTP method on it**, not only the remembered ones | That existing green security tests cover all routes; that covering a route's GET covers its POST. Do **not** challenge the suite's honesty — research found no mocked security anywhere | The enumerable route inventory, which routes assert *denial*, and for which methods; plus whether a denial status is attributable to the rule under test rather than to CSRF | integration; the suite already exercises the real chain, so extend it rather than rebuild it | **ambiguous denial**: asserting a status that a missing CSRF token would also produce, so the test passes with the rule deleted |
| #4 | A request carrying null, out-of-range (abs(lat) > 90, abs(lng) > 180), or transposed coordinates is rejected and no row is written | That the browser constrains what the server receives; that a map click is the only way a coordinate arrives | Where coordinates are validated, whether validation runs before or after normalisation (see `lessons.md`), and what the database column itself permits | unit for the range rule; integration where a database constraint is the real enforcer | mirroring the implementation's own bounds check — derive the expected bound from WGS-84, not from the code |
| #5 | An oversized or wrong-type upload is refused before persistence, against a limit derived from a stated requirement rather than the current constant | That a passing validator means the request path enforces it; that the framework multipart limit and the validator agree | The effective limit at each layer (multipart config, validator, column type) and which one fires first | unit for the rule; integration for the request path | copying the production constant into the assertion, which green-lights whatever the constant becomes |
| #6 | An evicted session is refused with the correct semantics on **each chain** — the browser chain redirecting to the expired-login page, the API chain answering 401 with its documented JSON body — and a chain configured without session management fails a test | That account state is enforced per route — **it is not**: one filter denies every route by construction, so a per-route sweep is redundant copies. Also that `isEnabled()` returning false covers an already-live session; it does not | Grounded 2026-09-13: account state is read only inside authentication, against a snapshot captured at login; live-session revocation is an out-of-band eviction that a single filter enforces ahead of every matcher | a small number of targeted integration tests — one per chain, plus the untested interaction between CSRF and an expired session on a browser-chain POST — and one configuration assertion that every chain installs the expiry filter | **redundant copies**: one denial test per route for the deactivated identity, all failing for the same single cause |

## 3. Phased Rollout

Each row is a discrete rollout phase that will open its own change folder
via `/10x-new`. Status moves left-to-right through the values below; the
orchestrator updates Status as artifacts appear on disk.

| # | Phase name | Goal (one line) | Risks covered | Test types | Status | Change folder |
|---|---|---|---|---|---|---|
| 1 | Ownership and role denial | Prove a resident is denied another resident's report and every staff-only action, through the real filter chain | #1, #2 | integration | complete | `context/changes/testing-ownership-role-denial/` |
| 2 | Route-denial inventory | Make loosening any access rule fail a test across the derived route set, and make removing session-expiry enforcement fail a test on each chain | #3, #6 | integration | complete | `context/changes/testing-route-denial-inventory/` |
| 3 | Input contract at the server boundary | Reject invalid coordinates and abusive photo payloads server-side, independent of what the browser sends | #4, #5 | unit + integration | not started | — |
| 4 | Quality-gates wiring | Make the test step explicit in CI and add coverage visibility over the modules phases 1–3 touched | cross-cutting | gates | not started | — |

## 4. Stack

The classic test base for this project. AI-native tools (if any) carry a
`checked:` date so future readers can see which lines need re-verification.

| Layer | Tool | Version | Notes |
|---|---|---|---|
| unit + integration | JUnit 5 (spring-boot-starter-test) | Spring Boot 4.0.6 | 17 test files / ~2 584 LOC; integration-weighted (7 `@SpringBootTest`, 6 `@AutoConfigureMockMvc`) |
| security testing | Spring Security Test | managed by starter | Available; §2 risk #3 turns on using it against the real filter chain rather than as a mock |
| database | Testcontainers PostgreSQL | 1.21.4 | `TestcontainersConfig` wired; requires Docker locally and in CI |
| coverage | none yet | — | none yet — see §3 Phase 4 |
| mutation testing | none yet | — | Not planned. Reconsider only if Phase 4 coverage proves misleading |
| JS unit | none yet | — | Deliberate — see §7 |
| e2e / browser | none yet | — | Deliberate — see §7 |
| accessibility | none | — | Out of scope for MVP |

**Stack grounding tools (current session):**
- Docs: Context7 (`query-docs`) — available; use for Spring Boot 4 / Spring Security test APIs and Testcontainers version-specific setup before recommending an API; checked: 2026-09-13
- Search: none — no Exa.ai or web-search MCP exposed in this session; checked: 2026-09-13
- Runtime/browser: none — Playwright MCP not available in current session. A `claude-in-chrome` skill exists but is interactive-only and is not a CI layer; checked: 2026-09-13
- Provider/platform: SonarQube MCP available (coverage and quality-gate inspection — relevant to §3 Phase 4); Atlassian MCP available; GitHub MCP failed to connect (HTTP 401) this session, so use the `gh` CLI for GitHub operations; checked: 2026-09-13

## 5. Quality Gates

The full set of gates that must pass before a change reaches production.
"Required after §3 Phase N" means the gate is enforced once that rollout
phase lands; before that, the gate is planned.

| Gate | Where | Required? | Catches |
|---|---|---|---|
| compile + package | local + CI (`mvnw clean package`) | required (wired today) | syntactic and type drift |
| unit + integration | local + CI | required (wired today, implicitly via `package`) | logic regressions |
| explicit CI test step with published results | CI on push to `main` | required after §3 Phase 4 | a silenced or skipped suite going unnoticed |
| coverage report over auth, user, and report modules | CI on push to `main` | required after §3 Phase 4 | untested new surface in the highest-churn areas |
| pre-prod smoke on the deployed App Service | between merge and prod | optional | environment-specific failures that Testcontainers cannot reproduce (see `lessons.md` outstanding production checklist) |

## 6. Cookbook Patterns

How to add new tests in this project. Each sub-section is filled in once
the relevant rollout phase ships; before that, the sub-section reads
"TBD — see §3 Phase N."

### 6.1 Adding a cross-role access test

- **Location**: the controller test that already owns the route, beside its
  positive control — not a separate authorization test class.
- **Reference tests**: `src/test/java/com/example/city_fix/report/StaffReportControllerTest.java`
  (role denial on a write, real login sessions) and
  `src/test/java/com/example/city_fix/config/SecurityConfigTest.java` (matcher-level
  denial with `@WithMockUser`).
- **Attributable denial — the rule that matters.** CSRF is enabled on the web chain,
  so a POST without a token returns 403, exactly like a role denial. A denial test
  that omits the token passes even when the access rule is deleted. Every denial test
  on a state-mutating route therefore sends a valid CSRF token, and is accompanied by
  a token-less case so the two causes of 403 stay distinguishable.
- **Assert the side effect, not only the status.** A refused write must also leave
  persisted state unchanged; status code alone does not prove the handler never ran.
- **Pair every denial with a permission case.** Cover each arm of a multi-role matcher
  (`hasAnyRole("STAFF", "ADMIN")` needs both) or a narrowing of one arm passes unnoticed.
- **Identity**: use a real login session when the assertion depends on *who* the user
  is (ownership, user id). `@WithMockUser` injects a principal with no database id and
  suits matcher-level checks only.
- **Prove it is load-bearing.** Before considering a denial test done, break the rule
  it defends and watch the test fail. A test never observed failing is an assumption.
- **Run locally**: `./mvnw test -Dtest=StaffReportControllerTest` (needs Docker).

### 6.2 Adding a route to the denial inventory

- **Adding a route means adding a table row, not writing a test.** The expectation
  table is the single source; the matrix and the completeness assertion both read it.
  A new route with no row fails the build, and the failure message says so.
- **Location**: the table and its two consuming tests live together in the config test
  package. Reference tests: the completeness assertion and the parameterised matrix.
- **Author the expected outcome from the PRD, never from the security configuration.**
  Spring exposes no way to enumerate configured authorization rules — deliberately
  checked, there is no accessor — so the expectations must be written by hand. Reading
  them off the configuration would produce a mirror that ratifies whatever that
  configuration says, including a mistake.
- **Give every identity a value, including the ones that may.** A denial test proves
  nothing beside a permission that no longer works: a rule that denies *everyone*
  passes a suite of denial-only tests. Allow cells are what make a denial attributable.
- **Send a valid CSRF token on every state-mutating cell.** Without one the request is
  refused by the CSRF filter with the same 403 a role denial produces, and the case
  would pass with the access rule deleted. See §6.1.
- **Target an id that does not exist and send no body.** An authorized request then
  reaches its handler and stops, which keeps the assertion about authorization and
  stops the matrix writing rows to a database that is shared and never rolled back.
- **Three routes cannot be derived and are listed by hand**: the form-login POST and
  the two logout POSTs are handled by filters and never reach the dispatcher. A fourth
  such route would not be caught automatically — nothing in the framework enumerates
  them. That limitation is real; do not assume the completeness assertion covers it.
- **Run locally**: the config test package, or the whole suite. Needs Docker.

### 6.3 Adding a server-side input-contract test

TBD — see §3 Phase 3 for the coordinate-range and upload-limit patterns,
including which layer is the real enforcer.

### 6.4 Adding an integration test that needs the database

- **Extend `IntegrationTest`** (`src/test/java/com/example/city_fix/`). It already carries
  `@SpringBootTest` and a bare `@AutoConfigureMockMvc`, extends `TestcontainersConfig`,
  and provides the shared setup — authenticate as any role, register a resident, persist a
  user with an active flag, submit a report, find one by description. Do not re-implement
  these privately; four classes used to, and removing that duplication is why the base
  exists.
- **Never declare a second `@DynamicPropertySource`.** The customizer Spring builds from
  one compares only the set of methods, so a duplicate forks an entire extra application
  context even though it produces identical properties against the same container. The
  inherited `TestcontainersConfig` method must stay the only one in the tree.
- **Never pass `addFilters = false`** — it disables the security chain and silently voids
  any authorization assertion in the class.
- **The database is shared and nothing is transactional.** Rows persist across tests
  within a run. Every test must own unique identifiers (emails, report descriptions)
  and must never assume an empty table or a fixed row count.
- **Docker is required** for every class extending this config. Without it they fail
  at class-init with `ExceptionInInitializerError`; the pure-unit classes still pass,
  so a partial green run is not a green suite.
- **Reference test**: any class extending `IntegrationTest`; the report and user controller
  tests are the fullest examples.
- **Run locally**: `./mvnw test`.

### 6.5 Choosing integration over unit

The deciding question: **is the assertion about our own logic, or about framework
wiring?** Framework wiring is never proven by a mock.

- **Our own logic** — a calculation, a validation rule, a state transition — is unit
  territory. Mock the collaborators and assert the behaviour.
- **Framework wiring** — an access rule, a filter, a session mechanism, a database
  constraint, a cascade — must be exercised for real. Mocking the mechanism you intend
  to verify produces a test that passes while the mechanism is broken, which is the
  failure recorded in `lessons.md` §"A revocation flag only revokes once every
  enforcement point is wired".
- **This project holds the line**: no test mocks a security collaborator, no test uses
  `addFilters = false`, and every authorization assertion runs the real chain against a
  real database. Keep it that way.
- **A unit test can mirror an integration one and still be weak.** A service-level test
  whose repository stub is told to return an empty result asserts the exception mapping,
  not the scoping — the integration test is what proves the rule.

### 6.6 Per-rollout-phase notes

**Phase 1 — Ownership and role denial (2026-09-13).** Research found risk #1 already
defended: report ownership is enforced inside the persistence query, so a foreign
report is structurally indistinguishable from a missing one, and real-session
cross-owner tests already existed. No new tests were written for it. The one real gap
was writes on the staff surface — `/admin/**` had a write-denial test, `/staff/**` did
not — and that gap was closed with three tests.

The phase's most useful finding was that the obvious version of the denial test would
have been worthless: without a CSRF token the 403 comes from the CSRF filter, not the
access rule, and the test would have passed with the rule deleted. Mutation checks
confirmed each test fails for its own cause. One honest caveat: a blanket removal of
`ADMIN` from the matcher is also caught by the pre-existing `staffMap_isReachableByAdmin`,
so the new admin test specifically defends a method-scoped narrowing that keeps admin's
GET but drops its POST.

**Carried forward to Phase 2 (route-denial inventory).** Four test classes privately
re-implement user and report setup — `StaffReportControllerTest`, `ReportWebControllerTest`,
`AccountDeactivationTest`, `AdminUserControllerTest`. `UserFixtures` is package-private in
the `user` package and unreachable from `report`. The route inventory will need role-aware
fixtures everywhere, which is the moment to extract them; doing it earlier would have meant
refactoring four passing classes for no behavioural signal.

**Phase 2 — Route-denial inventory (2026-09-13).** Research reshaped both risks before any
test was written. Risk #3 grew: the suite's problem was not only missing denials but a denial
that proved nothing — `GET /reports/new` was refused for staff and anonymous with no
successful resident case, so denying *everyone* would have kept the suite green. Risk #6
shrank: account state is not evaluated per request at all, so the per-route sweep the plan
originally called for would have been one test per route all failing for the same single
cause.

The design question was whether a route list can avoid rotting. It can, halfway: Spring's
handler mapping yields the routes, but its authorization rules are not introspectable — which
is fortunate, because it forces the expectations to be authored from the PRD instead of read
back from the configuration under test. The completeness assertion over the derived set is
what actually protects: a route added without a decision fails the build. Three filter-level
routes stay hand-listed and a fourth would slip through; that gap is named rather than papered
over.

Two things were observed rather than reasoned about. `CsrfFilter` runs **ahead of**
`ConcurrentSessionFilter`, so on the browser chain a missing token answers 403 and masks
session expiry entirely — the assertion for that case was written only after probing it.
And the fixture extraction was verified cache-neutral: the suite still builds two Spring
contexts, because the base inherits the existing `@DynamicPropertySource` rather than
declaring its own.

**Carried forward.** `POST /register` still has no test of any kind. It is state-mutating and
public, so its risk is input-contract rather than authorization — rollout Phase 3's territory.

## 7. What We Deliberately Don't Test

Exclusions agreed during the rollout (Phase 2 interview, Q5) and during
brief synthesis. Future contributors should respect these unless the
underlying assumption changes.

- **Thymeleaf template rendering and HTML markup assertions** — a wrong
  render is visible immediately, and markup assertions break constantly
  while catching little. Re-evaluate if a template starts carrying an
  access decision rather than only displaying one. (Source: Phase 2
  interview Q5.)
- **The Leaflet library itself** — vendored third-party code; the library
  is its own test. Only our glue could be worth testing, and see the next
  bullet. (Source: Phase 2 interview Q5.)
- **Browser-level pin-placement interaction** — proving that a click lands
  a marker where the user clicked would mean bootstrapping a JavaScript
  test stack and a browser driver into a Maven project for roughly 8 KB of
  glue, with no Playwright MCP available. The server-side coordinate
  contract (risk #4) carries the guardrail instead. Re-evaluate if the map
  glue grows beyond display and single-pin capture, or if the project gains
  a browser automation layer for another reason. (Source: Phase 2 interview
  Q3, split during brief synthesis.)
- **Re-testing the shipped account-deactivation path** — already covered
  directly. Phase 2 defends it against *future* routes instead; adding more
  tests over the existing path buys coverage, not signal. (Source: brief
  synthesis, challenger pass.)
- **Mutation testing as a routine gate** — not planned. Reconsider only if
  Phase 4 coverage numbers turn out to be misleading for a specific module.

## 8. Freshness Ledger

- Strategy (§1–§5) last reviewed: 2026-09-13
- Stack versions last verified: 2026-09-13
- AI-native tool references last verified: 2026-09-13

Refresh (`/10x-test-plan --refresh`) when:

- a new top-3 risk surfaces from the roadmap or archive,
- a recommended tool's `checked:` date is older than three months,
- the project's tech stack changes (new framework, new test runner),
- §7 negative-space no longer matches what the team believes.
