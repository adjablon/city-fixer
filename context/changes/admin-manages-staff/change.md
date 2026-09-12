---
change_id: admin-manages-staff
title: Admin manages office staff accounts
status: impl_reviewed
created: 2026-09-11
updated: 2026-09-12
archived_at: null
---

## Notes

Roadmap S-03 (final slice): admin can create and deactivate office staff accounts. PRD ref FR-002. Prerequisite F-01 only; parallel with S-01/S-02, both now done. Supersedes the staff.seed.* StaffSeeder bootstrap added in S-02. Two constraints found during recon: (1) User has no active/enabled field, so deactivation needs a schema change — and under ddl-auto=update a NOT NULL column on the existing users table will fail without a default (lessons.md); (2) CustomUserDetails overrides none of the UserDetails account-status methods, so they all default to true — flipping a flag would not actually block login until isEnabled() is wired. Also open from the PRD: how the first admin is provisioned (currently admin.seed.* env vars).
