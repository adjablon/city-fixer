---
change_id: resident-reports-problem
title: Resident reports problem
status: archived
created: 2026-09-03
updated: 2026-09-09
archived_at: 2026-09-09T11:39:05Z
---

## Notes

<!-- Free-form notes for this change: links, ad-hoc context, decisions that don't belong in research/frame/plan. -->

### Deliberate convention deviations

Recorded so an implementation review reads these as decisions rather than drift.

- **First `@Transactional` in the project** (`ReportService.create`). The codebase had none
  and relied on Spring Data's per-method transactions. `create` writes two rows — the report
  and, optionally, its photo — so an invalid or unreadable photo must not leave a report
  behind without it.
- **Typed `ReportForm` record in a web controller**, where `AuthWebController` binds loose
  `@RequestParam` values. `lessons.md`'s typed-DTO rule was recorded against REST controllers
  with the explicit note that S-01 would inherit whatever auth established; four fields with
  range constraints (lat/lng bounds, description length) is where loose parameters stop being
  defensible, so the rule is extended to form controllers here.

### Smaller judgement calls

- `Category` / `ReportStatus` expose `getLabel()` (JavaBean form, so Thymeleaf resolves
  `${category.label}`) rather than the plan's bare `label()`. The plan made the label
  conditional on template need; the category select and status badge both need it.
- `ReportService.hasPhoto(Long)` is a fifth method beyond the plan's four — it is the
  service-computed boolean that Critical Implementation Details requires in place of a
  mapped photo association on `Report`.
- `ReportPhoto.getImageData()` returns the backing array rather than a defensive copy:
  photos are capped at 2 MB and B1 runs with `-Xmx1g`, so copying on every read costs
  memory for no benefit.
- Report timestamps are formatted with a zone-bound `DateTimeFormatter` handed to the view,
  because `thymeleaf-extras-java8time` is not on the classpath and adding it for date
  display alone was out of scope.
