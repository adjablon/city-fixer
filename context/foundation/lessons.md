# Lessons Learned

> Append-only register of recurring rules and patterns. Re-read at start by /10x-frame, /10x-research, /10x-plan, /10x-plan-review, /10x-implement, /10x-impl-review.

## Typed DTOs over raw maps in controllers

- **Context**: src/main/java/com/example/city_fix/auth/AuthController.java:34,64 — the project's first REST controller (auth-role-scaffold impl review, F9)
- **Problem**: Request bodies and responses were raw `Map<String,String>` — no compile-time contract, no Bean Validation (`@Valid`/`@Email`/`@Size` would replace hand-rolled checks in AuthService), stringly-typed keys. As the first controller in the project, S-01/S-02/S-03 controllers will copy whatever pattern it establishes.
- **Rule**: Controllers use Java records for request/response bodies with Bean Validation annotations; never raw Map DTOs.
- **Applies to**: All REST controllers — `@RequestBody` inputs and response payloads.
