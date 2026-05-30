---
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
---

## Why this stack

Solo developer building a geo-located infrastructure reporting web-app (CityFix) in 3 weeks of after-hours work, with auth as the only technology-forcing feature. Spring Boot is the recommended default for (web-app, java) and clears all four agent-friendly gates: explicit Java types, convention-based layout via Spring MVC and autoconfiguration, popular in Java training data, and thoroughly documented with versioned reference manuals. Auth maps to Spring Security out of the box. Deployment targets Azure; CI runs on GitHub Actions with auto-deploy-on-merge. Bootstrapper confidence is verified, so scaffolding will be smooth.
