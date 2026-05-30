---
project: CityFix
researched_at: 2026-05-23
recommended_platform: Azure App Service
runner_up: Railway
context_type: mvp
tech_stack:
  language: Java
  framework: Spring Boot
  runtime: JVM (Maven)
---

## Recommendation

**Deploy on Azure App Service (Linux, Java SE).**

Azure App Service is the only shortlisted platform with first-class Java/Spring Boot support — deploy a fat JAR directly, no Docker required. The user's existing Azure familiarity eliminates onboarding friction, and Azure's co-located managed services (PostgreSQL Flexible Server, Blob Storage, Key Vault) satisfy the co-location preference in a single subscription. Three GA MCP servers (Azure Skills, MicrosoftDocs, Azure DevOps preview) give the agent structured access to both platform operations and official documentation. The cost floor is higher than Railway (~$25/month vs ~$10/month), but the native runtime support and familiar tooling justify the premium for a solo developer who already knows the ecosystem.

## Platform Comparison

### Scoring Matrix

| Platform | CLI-first | Managed / Serverless | Agent-readable docs | Stable deploy API | MCP / Integration | Weighted Score |
|---|---|---|---|---|---|---|
| **Azure App Service** | Pass | Pass | Pass | Pass | Pass (3 MCP servers) | **Top — familiarity + co-location boost** |
| **Railway** | Pass | Pass | Pass | Pass | Pass (official + Claude Code page) | **2nd — best DX, lowest cost** |
| **Render** | Pass | Pass | Pass (llms.txt) | Pass | Pass (20+ MCP tools) | **3rd — free tier, strong agent story** |
| **Fly.io** | Pass | Partial | Partial | Pass | Pass (flymcp GA) | **4th — expensive co-location, no free tier** |
| Cloudflare Workers | — | — | — | — | — | Dropped: no JVM runtime |
| Vercel | — | — | — | — | — | Dropped: no JVM runtime |
| Netlify | — | — | — | — | — | Dropped: no JVM runtime |

### Hard Filters Applied

- **JVM runtime required** (Java / Spring Boot / Maven): eliminated Cloudflare Workers (JS/WASM only), Vercel (Node.js serverless), and Netlify (Node.js serverless).
- **Persistent connections not required** (interview Q1 = No): no additional platforms filtered.

### Soft Weights Applied

- **Familiarity = Azure** (Q3): tie-breaker boost for Azure.
- **Co-location preferred** (Q5): Azure (full suite) > Railway (Postgres, MySQL, Redis, S3) > Render (Postgres, Redis; object storage alpha) > Fly.io (Managed Postgres at $38/month minimum).
- **Cost vs DX = roughly equal** (Q2): no strong penalty either way.
- **Single region** (Q4): no edge-native preference needed.

### Shortlisted Platforms

#### 1. Azure App Service (Recommended)

**Why it won:** Native Java/Spring Boot support — deploy a JAR directly without writing a Dockerfile. First-class `az` CLI covers deploy, log tail, config, and rollback (via slot swap on S1+). The user's stated Azure familiarity eliminates learning-curve risk on a 3-week timeline. Co-location story is the strongest: Azure SQL, PostgreSQL Flexible Server, Blob Storage, Redis Cache, Key Vault, and Service Bus are all GA and deployable in the same region with free intra-region traffic. Three MCP servers (Azure Skills GA, MicrosoftDocs GA, Azure DevOps preview) provide structured agent access. Docs are fully open-source as markdown on GitHub (MicrosoftDocs/azure-docs).

**Cost estimate:** B1 Linux (~$13/month) + PostgreSQL Flexible Server Burstable B1ms (~$12/month) = ~$25/month baseline. Blob Storage and Key Vault add negligible cost at MVP scale.

**Feature status (checked 2026-05-23):**

| Feature | Status |
|---|---|
| Java 8/11/17/21 on App Service Linux | GA |
| Spring Boot fat-JAR (Java SE runtime) | GA |
| `az webapp deploy` / log tail / config | GA |
| Maven plugin `azure-webapp-maven-plugin` | GA |
| Deployment slot swap (rollback) | GA — Standard S1+ only |
| WebSocket on App Service | GA |
| Always On | GA — B1+ only |
| Azure SQL / PostgreSQL / Blob / Service Bus | GA |
| Azure MCP Server (.mcpb bundle) | GA |
| MicrosoftDocs MCP Server | GA |
| Azure DevOps MCP Server | Public preview (2026-05-23) |

#### 2. Railway (Runner-up)

**Why it scored second:** Best developer experience among container PaaS options. Railpack auto-detects Java/Maven projects — push code and it builds. One-click PostgreSQL, MySQL, Redis, and MongoDB with automatic environment variable injection. Official MCP server (`@railway/mcp-server`, GA) with dedicated Claude Code integration page. Cheapest viable option: Hobby plan at $5/month covers a small Spring Boot app + co-located Postgres. The gap vs. Azure: no native Java runtime (Railpack beta or Docker required), less mature co-location story, and no user familiarity to leverage.

**Cost estimate:** Hobby plan $5/month base + ~$7.50/month compute (0.5 vCPU, 512 MB) + Postgres included in credit = $5–15/month total.

**Key caveat:** Railpack (the default auto-builder) is still in beta as of 2026-05-23. For production stability, pin a Dockerfile.

#### 3. Render (Third option)

**Why it scored third:** Strongest agent-readability story — publishes `llms.txt`, `llms-full.txt`, and a dedicated coding-agent support page. Official MCP server with 20+ tools (GA). Free tier with 750 compute-hours/month allows early development at zero cost. Co-located Postgres and Redis are GA. The gap vs. Azure and Railway: Docker required for Java (no native buildpack), 512 MB RAM on free/Starter tier is tight for Spring Boot (OOM risk without explicit JVM flags), object storage still in alpha, and free tier cold starts (30–60s) make it unusable for real traffic without upgrading to Starter ($7/month).

**Cost estimate:** Starter $7/month (to avoid cold starts) + Postgres free tier = $7/month minimum; Standard $25/month for comfortable Spring Boot memory headroom.

### Platforms Not Shortlisted

**Fly.io** scored 4th. Strong CLI and MCP server, but: Managed Postgres starts at $38/month (violates co-location preference at reasonable cost), free tier was removed in 2024, docs are not fully open-sourced for bulk agent access, and Docker is required. Total cost for compute + Postgres would be ~$45/month — the most expensive option for this MVP.

## Anti-Bias Cross-Check: Azure App Service

### Devil's Advocate — Weaknesses

1. **Complexity tax for a solo MVP.** Even a simple deploy requires creating a resource group, an App Service plan, and an App Service — three resources minimum, each with its own lifecycle and billing. Railway deploys in one command.
2. **No free-tier rollback.** Deployment slot swap requires Standard S1 tier (~$56/month). On Basic B1, rollback means manually re-deploying the previous JAR — slower and more error-prone.
3. **Higher cost floor.** B1 (~$13/month) + PostgreSQL Flexible Server (~$12/month) = ~$25/month baseline. Railway serves the same workload for $5–15/month.
4. **Portal gravity in docs.** Many Azure tutorials default to portal (GUI) instructions first. CLI equivalents exist but are sometimes buried in "Alternative: CLI" tabs, which can mislead an agent.
5. **Credential/IAM complexity.** Entra ID service principals, role assignments, and managed identities are over-engineered for a solo MVP. CI authentication requires more setup steps than competing platforms.

### Pre-Mortem — How This Could Fail

Six months later, the CityFix MVP on Azure was a cautionary tale. It started well — Spring Boot ran on B1, the JAR deployed cleanly. But the complexity accumulated quietly. The first weekend was spent wrestling with Entra ID service principal setup for GitHub Actions, when Railway would have been a one-click OAuth. PostgreSQL Flexible Server had no free tier, so $12/month ran even during weeks with zero development. When a production bug needed rollback, the developer discovered slot swap required S1 at $56/month — so they re-deployed the previous JAR manually, a 15-minute operation instead of a 2-second swap. The resource group accumulated orphaned resources from failed experiments (a test database, a spare App Service plan, a storage account) because Azure doesn't auto-clean, and the monthly bill crept to $45 unnoticed. The agent assisting with maintenance had to navigate Azure's deep resource hierarchy (subscription → resource group → plan → service → slots → settings), where a misconfigured setting in the wrong scope silently broke deployments. The MVP worked, but operational overhead consumed time that should have gone into features.

### Unknown Unknowns

- **Health check vs. startup time.** App Service has a startup health check that can restart your app before Spring Boot finishes initializing. You must set `WEBSITE_CONTAINER_START_TIME_LIMIT` to ~230s — this is absent from the quickstart guide.
- **Same-region networking is not private by default.** Without VNet integration, App Service connects to Azure PostgreSQL over the public internet even within the same region. Private connectivity requires extra configuration.
- **Log retention defaults are aggressive.** Application logs on App Service filesystem are limited to 12 hours or 35 MB. Yesterday's logs may already be gone. Log Analytics solves this but adds cost and configuration.
- **Maven plugin version churn.** The `azure-webapp-maven-plugin` releases frequently with breaking changes between major versions. Pin the version in your POM and don't blindly update.
- **"Always On" is B1+ only.** On the F1 free tier, the JVM is killed after ~20 minutes idle; the next request pays a 10–30 second cold start penalty.

## Operational Story

- **Preview deploys**: Azure App Service does not have automatic PR preview URLs like Vercel/Netlify. Use deployment slots (S1+ only) or deploy to a separate App Service on the free tier for staging. For B1, deploy to a `-staging` suffixed App Service manually or via GitHub Actions on PR branches.
- **Secrets**: Environment variables are set via `az webapp config appsettings set --settings KEY=VALUE`. Values are encrypted at rest and accessible only to the App Service runtime. For sensitive credentials, use Azure Key Vault with a Key Vault reference (`@Microsoft.KeyVault(SecretUri=...)`) in app settings. Rotation: update the secret in Key Vault; the app picks up the new value on next restart (or with Key Vault reference caching disabled).
- **Rollback**: On Standard S1+, `az webapp deployment slot swap --slot staging --target-slot production` swaps instantly (~2 seconds). On Basic B1, re-deploy the previous JAR: `az webapp deploy --src-path target/app-previous.jar --type jar`. Typical time-to-revert on B1: 2–5 minutes (upload + restart). Database migrations do not roll back automatically — plan backward-compatible migrations.
- **Approval**: Human-only actions: delete a resource group, rotate a primary database credential, change App Service plan tier, modify Entra ID service principals. Agent-safe actions: deploy a JAR, read logs, set non-secret app settings, restart the App Service.
- **Logs**: `az webapp log tail --name <app> --resource-group <rg>` streams live application logs. `az webapp log download` pulls filesystem logs as a zip. For structured log queries: `az monitor log-analytics query --workspace <id> --analytics-query "AppServiceConsoleLogs | where TimeGenerated > ago(1h)"` (requires Log Analytics workspace, additional setup).

## Risk Register

| Risk | Source | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| Spring Boot startup killed by health check before init completes | Unknown unknowns | Medium | High | Set `WEBSITE_CONTAINER_START_TIME_LIMIT=230` in app settings; configure custom health check path at `/actuator/health` |
| Orphaned Azure resources inflate monthly bill | Pre-mortem | Medium | Medium | Tag all resources with `project:cityfix`; review resource group monthly; set Azure Cost Management budget alert at $30/month |
| Rollback requires manual JAR re-deploy on B1 (no slot swap) | Devil's advocate | Medium | Medium | Keep the previous JAR artifact in CI (GitHub Actions artifact retention); document the re-deploy command in runbook. Upgrade to S1 if rollback frequency justifies cost |
| Database connection over public internet without VNet | Unknown unknowns | Low | Medium | Enable Azure PostgreSQL firewall rule to allow only App Service outbound IPs; configure VNet integration when moving beyond MVP |
| Application logs lost after 12 hours / 35 MB | Unknown unknowns | Medium | Low | Enable Azure Monitor Log Analytics for persistent log retention; accept filesystem-only logs during MVP and check logs same-day |
| Maven plugin breaking change on update | Unknown unknowns | Low | Medium | Pin `azure-webapp-maven-plugin` version in `pom.xml`; only update deliberately with changelog review |
| JVM heap oversized for B1 (1.75 GB RAM) causing OOM restarts | Research finding | Medium | High | Set `JAVA_OPTS=-Xms512m -Xmx1g -XX:MaxRAMPercentage=60` in app settings; monitor via `az monitor metrics list --metric "MemoryPercentage"` |
| Entra ID / service principal setup blocks CI pipeline | Devil's advocate | Medium | Medium | Use `az ad sp create-for-rbac --role contributor --scopes /subscriptions/<id>/resourceGroups/<rg>` with minimal scope; store credentials in GitHub Secrets; document the setup in CLAUDE.md |
| Monthly cost creeps beyond $25/month as services accumulate | Pre-mortem | Medium | Low | Set Azure Cost Management budget with email alert at $30; audit resource group quarterly; delete unused resources immediately |

## Getting Started

1. **Install the Azure CLI** (if not already available):
   ```bash
   brew install azure-cli   # macOS
   az login                 # authenticate with your Azure account
   ```

2. **Create the resource group and App Service plan**:
   ```bash
   az group create --name cityfix-rg --location westeurope
   az appservice plan create --name cityfix-plan --resource-group cityfix-rg \
     --sku B1 --is-linux
   ```

3. **Create the App Service with Java 21 runtime**:
   ```bash
   az webapp create --name cityfix-app --resource-group cityfix-rg \
     --plan cityfix-plan --runtime "JAVA:21-java21"
   az webapp config set --name cityfix-app --resource-group cityfix-rg \
     --always-on true
   ```

4. **Deploy the Spring Boot JAR**:
   ```bash
   ./mvnw clean package -DskipTests
   az webapp deploy --name cityfix-app --resource-group cityfix-rg \
     --src-path target/city-fix-0.0.1-SNAPSHOT.jar --type jar
   ```

5. **Provision PostgreSQL and wire the connection**:
   ```bash
   az postgres flexible-server create --name cityfix-db --resource-group cityfix-rg \
     --sku-name Standard_B1ms --tier Burstable --admin-user cityfixadmin \
     --admin-password '<strong-password>' --location westeurope
   az webapp config appsettings set --name cityfix-app --resource-group cityfix-rg \
     --settings SPRING_DATASOURCE_URL="jdbc:postgresql://cityfix-db.postgres.database.azure.com:5432/postgres" \
     SPRING_DATASOURCE_USERNAME="cityfixadmin" \
     SPRING_DATASOURCE_PASSWORD="<strong-password>" \
     JAVA_OPTS="-Xms512m -Xmx1g"
   ```

## Out of Scope

The following were not evaluated in this research:
- Docker image configuration
- CI/CD pipeline setup (GitHub Actions workflow)
- Production-scale architecture (multi-region, HA, DR)
- Azure Front Door / CDN configuration
- Azure Monitor / Application Insights full setup
