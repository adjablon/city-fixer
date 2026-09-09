# Resident Submits a Geo-located Report — Plan Brief

> Full plan: `context/changes/resident-reports-problem/plan.md`

## What & Why

Roadmap slice **S-01**, the north star: a logged-in resident places a pin on a map, submits a report with description, category and an optional photo, and sees it in "my reports" with status `new`. Without this slice CityFix has authentication and nothing to authenticate for — and S-02 (staff triage) cannot start, because staff cannot triage reports that do not exist.

The PRD's own Socratic note calls map integration "the biggest tech risk" and keeps it anyway, because precise map location *is* the product insight (FR-003). This slice is where that risk gets paid down.

## Starting Point

The auth scaffold (F-01) is complete and archived: `User` + `Role`, two security filter chains, form login, three standalone Thymeleaf pages, and a real-Postgres Testcontainers test suite. Beyond that the cupboard is bare — one entity, one repository, an empty `static/` directory, no file-upload capability, and no authorization anywhere beyond `permitAll()` / `authenticated()`. `@EnableMethodSecurity` is switched on but entirely unused. `home.html` still carries the placeholder comment "The map view will replace it in S-01".

So this slice introduces three firsts: a frontend layer, file upload, and ownership-scoped data access.

## Desired End State

A resident opens `/reports/new`, taps the map (or presses "Use my location") to drop a pin, describes the problem, picks a category, optionally attaches a photo, and submits. They land on `/reports` where the report appears newest-first with status `new`, and `/reports/{id}` shows the description, status, the photo, and the pin on a small read-only map. A different account given that URL directly gets a 404 — not a 403, and not the content.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) |
| --- | --- | --- |
| Map library | Leaflet + OSM tiles, vendored into `static/` | No API key, no billing, no runtime third-party dependency, and heavily represented in training data — the project's own agent-friendly stack gates. |
| Photo storage | Postgres `bytea` in a separate `report_photos` table | Avoids provisioning Azure Blob Storage that `infrastructure.md` never actually designed, while the separate table keeps image bytes out of every "my reports" query. |
| Upload limits | 2 MB cap, JPEG/PNG/WebP validated by magic bytes | Bounds heap on a 1.75 GB B1 instance and closes the renamed-executable hole without trusting the client's filename or content type. |
| Submission transport | Multipart Thymeleaf form on the web filter chain | CSRF comes free from the security dialect, avoiding hand-managed `XSRF-TOKEN` headers against `csrf.spa()` on the riskiest slice. |
| Geolocation | Explicit "Use my location" button | Serves the report-from-the-street persona without a permission prompt on first paint. |
| View scope | List **and** detail page | Without a detail page the uploaded photo is never visible, making the most expensive feature in the slice unverifiable. |
| Create authorization | Any authenticated user | Adds no new authorization surface here; the project's first `hasRole` rule belongs to S-02/S-03. |
| Ownership enforcement | Reporter-scoped repository queries → 404 | Puts the filter inside the query so no code path can load another resident's data, and 404 avoids confirming the report exists. |
| Status model | All four values defined, only `NEW` reachable | S-02 adds transitions without touching the enum, the column, or the resident-facing templates. |
| Map default centre | `cityfix.map.*` properties (Warsaw default) | Retargeting the city becomes a config change, matching the `admin.seed.*` property precedent. |
| Test depth | Unit + integration, security cases explicit | Puts an assertion on every finding class the last impl-review flagged; CI runs `mvnw package`, so these gate deploys. |
| First scope cut | Photo upload | The largest block of work and the only PRD-optional field, so cutting it still leaves the map and the report loop intact. |

## Scope

**In scope:** `Report` / `ReportPhoto` entities with reporter-scoped repositories; six hardcoded categories; four-state status enum with only `NEW` written; Leaflet map with tap-to-place and device geolocation; multipart submission with magic-byte photo validation; ownership-scoped list, detail and photo routes; unit and integration tests including cross-owner 404s.

**Out of scope:** staff/admin views and status transitions (S-02); editing or deleting reports; public city-wide map; notifications; geo-search; configurable categories; PostGIS; Azure Blob Storage; server-side image resizing; any JSON reports API; Flyway/Liquibase; CSS framework, layout fragments, or a frontend build step.

## Architecture / Approach

New `com.example.city_fix.report` package following the established package-by-feature layout. `Report` holds coordinates, description, category, status and a `@ManyToOne` reporter; `ReportPhoto` owns the FK back to `Report` and holds the bytes, so `Report` has no photo association and report queries never drag images. `ReportService` is the only door: it forces status `NEW`, takes the reporter from the authenticated principal, and reads exclusively through `findByIdAndReporterId` / `findByReporterIdOrderByCreatedAtDesc`. `ReportWebController` renders Thymeleaf views on the existing web filter chain — no SecurityConfig change is needed, because `anyRequest().authenticated()` already covers `/reports/**`. Vendored Leaflet lives under `/css/` and `/js/`, the only static paths the security config permits.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Data model & config | Entities, enums, scoped repositories, multipart + map properties | `@Lob byte[]` maps to `oid` not `bytea` on Postgres — wrong column type, hard to reverse |
| 2. Service & validation | `ReportService`, `PhotoValidator`, unit tests | Ownership check ordering — the photo load must never precede it |
| 3. Submission (map + POST) | Vendored Leaflet, map JS, `/reports/new` + `POST /reports` | Multipart POST can 403 on CSRF despite a valid token, via filter ordering |
| 4. My reports (read side) | List, detail, photo-serving routes and templates | Serving user-uploaded bytes; a photo association would silently reintroduce eager loading |
| 5. Tests & verification | Integration + cross-owner security tests, packaging | Cross-owner leak is the finding class that got the last slice rejected |

**Prerequisites:** F-01 complete (done, archived). Docker running for Testcontainers — note the last review hit a Rancher Desktop socket path issue (`~/.rd/docker.sock`). A Leaflet 1.9.x distribution to vendor.
**Estimated effort:** ~3–4 sessions across 5 phases — larger than F-01's "~2–3 sessions across 4 phases" because this slice introduces the frontend layer and file upload at once.

## Open Risks & Assumptions

- **Assumed, not documented:** that App Service's filesystem should not be trusted for uploads. `infrastructure.md` documents only log retention (12 h / 35 MB) and never states general filesystem persistence — this is external platform knowledge, not a repo decision, and it is the reasoning behind choosing Postgres over local disk.
- **Postgres will carry image bytes on a Burstable B1ms.** Fine at `data_volume: small` with a 2 MB cap, but DB size and backup growth are the metric to watch; `ReportService` is the seam for a later move to Blob Storage.
- **OSM public tiles are not a production tile source.** Attribution is mandatory and heavy traffic is not permitted; acceptable at MVP scale, revisit if traffic grows.
- **Two deliberate convention deviations**, both to be recorded in `change.md` so the impl-review reads them as decisions rather than drift: the project's first `@Transactional` (`ReportService.create` writes two rows), and a typed `ReportForm` record where `AuthWebController` uses loose `@RequestParam`.
- **Process risk carried from F-01:** its critical finding was caused by silently drifting from the planned security mechanism, and an open risk flagged in its own brief went unactioned until review forced it. Both argue for raising an addendum rather than improvising if the plan proves wrong mid-flight.

## Success Criteria (Summary)

- A resident can file a geo-located report with a photo and immediately see it in "my reports" with status `new` — the first half of the PRD's primary success criterion, and the prerequisite for the second half.
- A second account cannot reach another resident's report or photo by direct URL, satisfying the PRD's report-privacy guardrail.
- `./mvnw clean package` is green with integration tests covering cross-owner 404s, oversize uploads and spoofed content types.
