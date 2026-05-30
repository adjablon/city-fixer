---
bootstrapped_at: 2026-05-21T21:16:00Z
starter_id: spring
starter_name: Spring Boot
project_name: city-fix
language_family: java
package_manager: maven
cwd_strategy: subdir-then-move
bootstrapper_confidence: verified
phase_3_status: ok
audit_command: "null"
---

## Hand-off

```yaml
starter_id: spring
package_manager: maven
project_name: city-fix
hints:
  language_family: java
  team_size: solo
  deployment_target: azure
  ci_provider: github-actions
  ci_default_flow: auto-deploy-on-merge
  bootstrapper_confidence: verified
  path_taken: standard
  quality_override: false
  self_check_answers: null
  has_auth: true
  has_payments: false
  has_realtime: false
  has_ai: false
  has_background_jobs: false
```

### Why this stack

Solo developer building a geo-located infrastructure reporting web-app (CityFix) in 3 weeks of after-hours work, with auth as the only technology-forcing feature. Spring Boot is the recommended default for (web-app, java) and clears all four agent-friendly gates: explicit Java types, convention-based layout via Spring MVC and autoconfiguration, popular in Java training data, and thoroughly documented with versioned reference manuals. Auth maps to Spring Security out of the box. Deployment targets Azure; CI runs on GitHub Actions with auto-deploy-on-merge. Bootstrapper confidence is verified, so scaffolding will be smooth.

## Pre-scaffold verification

| Signal | Value | Severity | Notes |
| --- | --- | --- | --- |
| npm package | not run | n/a | non-JS starter; npm check skipped |
| GitHub repo | not run | n/a | docs_url (docs.spring.io) is not a GitHub URL; no pushed_at signal available |

## Scaffold log

**Resolved invocation**: `curl -s https://start.spring.io/starter.tgz -d dependencies=web,devtools -d type=maven-project -d javaVersion=21 -d groupId=com.example -d artifactId=city-fix | tar -xzf -`
**Strategy**: subdir-then-move
**Exit code**: 0
**Files moved**: 10
**Conflicts (.scaffold siblings)**: none
**.gitignore handling**: moved silently (absent in cwd)
**.bootstrap-scaffold cleanup**: deleted

## Post-scaffold audit

**Tool**: skipped — no built-in audit tool for java
**Recommended external tool**: OWASP Dependency-Check (`mvn org.owasp:dependency-check-maven:check`) or Snyk (`snyk test`) are common choices for Java/Maven projects.

## Hints recorded but not acted on

| Hint | Value |
| --- | --- |
| bootstrapper_confidence | verified |
| quality_override | false |
| path_taken | standard |
| self_check_answers | null |
| team_size | solo |
| deployment_target | azure |
| ci_provider | github-actions |
| ci_default_flow | auto-deploy-on-merge |
| has_auth | true |
| has_payments | false |
| has_realtime | false |
| has_ai | false |
| has_background_jobs | false |

## Next steps

Next: a future skill will set up agent context (CLAUDE.md, AGENTS.md). For now, your project is scaffolded and verified — happy hacking.

Useful manual steps in the meantime:
- `git init` (if you have not already) to start your own repo history.
- Review any `.scaffold` siblings the conflict policy created and decide which version of each file to keep.
- Address audit findings per your project's risk tolerance — the full breakdown is in this log.
