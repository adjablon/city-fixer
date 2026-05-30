# Deployment Plan: CityFix to Azure App Service

## Context

CityFix is a Spring Boot 4.0.6 / Java 21 / Maven web app (minimal scaffold — only the entry point class exists). The infrastructure research (`context/foundation/infrastructure.md`) selected **Azure App Service (Linux, Java SE)** with **PostgreSQL Flexible Server** in `westeurope`. This plan covers the full first deployment: Azure resource provisioning, database setup, application config changes, first deploy, CI/CD pipeline, and post-deployment hardening.

**Correction found during research:** `infrastructure.md` uses `WEBSITE_CONTAINER_START_TIME_LIMIT` (singular) — the correct Azure setting is `WEBSITES_CONTAINER_START_TIME_LIMIT` (plural). This plan uses the corrected name.

**Current project state:**
- Git repo: `adjablon/city-fixer` on GitHub (remote `origin` configured, nothing pushed yet)
- No database dependencies in `pom.xml` (only `spring-boot-starter-webmvc`)
- No CI/CD, no Docker, no Azure config
- `application.properties` has only `spring.application.name=city-fix`

---

## Subscription Scope

All Azure CLI commands in this plan are locked to a single subscription. **Fill in the placeholder below before executing any phase.**

```
AZURE_SUBSCRIPTION_ID=5559456a-f30a-4505-a478-a3b978daabb7
```

> **Safety:** Every `az` command includes `--subscription $AZURE_SUBSCRIPTION_ID`. This prevents accidental resource creation in other subscriptions. The service principal (Phase 6) is also scoped exclusively to this subscription's resource group.

---

## Phase 0: Prerequisites & Pre-flight

- [x] **0.1** Verify Java 21: `java -version`
- [x] **0.2** Verify Maven wrapper: `./mvnw --version`
- [x] **0.3** Verify the app builds: `./mvnw clean package -DskipTests`
- [x] **0.4** Verify the app starts locally: `java -jar target/city-fix-0.0.1-SNAPSHOT.jar` (expect Spring Boot banner, Ctrl+C to stop)
- [x] **0.5** Install Azure CLI if missing: `brew install azure-cli`
- [x] **0.6** Login to Azure and lock to the target subscription:
  ```bash
  az login
  AZURE_SUBSCRIPTION_ID=5559456a-f30a-4505-a478-a3b978daabb7
  az account set --subscription $AZURE_SUBSCRIPTION_ID
  az account show --output table
  # Verify the correct subscription name and ID are displayed
  ```
- [x] **0.7** GitHub repository `adjablon/city-fixer` exists with remote `origin` configured, but nothing has been pushed yet. Push the initial commit:
  ```bash
  git add .
  git commit -m "Initial commit: CityFix scaffold"
  git push -u origin main
  ```

> **Gate:** All checks pass. `az account show` displays the correct subscription. GitHub repo exists with a remote.

---

## Phase 1: Azure Resource Provisioning

- [x] **1.1** Create resource group with tags:
  ```bash
  az group create --name cityfix-rg --location westeurope \
    --subscription $AZURE_SUBSCRIPTION_ID \
    --tags project=cityfix environment=production managed-by=cli
  ```

- [x] **1.2** Create App Service plan (B1 Linux):
  ```bash
  az appservice plan create --name cityfix-plan --resource-group cityfix-rg \
    --subscription $AZURE_SUBSCRIPTION_ID \
    --sku B1 --is-linux --tags project=cityfix
  ```

- [x] **1.3** Create App Service with Java 21:
  ```bash
  az webapp create --name cityfix-app-aj --resource-group cityfix-rg \
    --subscription $AZURE_SUBSCRIPTION_ID \
    --plan cityfix-plan --runtime "JAVA:21-java21" --tags project=cityfix
  ```
  > **Edge case — name conflict:** App Service names are globally unique. If `cityfix-app-aj` is taken, use `cityfix-app-aj-<suffix>` and update all subsequent commands. Check with: `nslookup cityfix-app-aj.azurewebsites.net` — if it resolves, the name is taken.

- [x] **1.4** Configure Always On + JVM settings + startup timeout:
  ```bash
  az webapp config set --name cityfix-app-aj --resource-group cityfix-rg \
    --subscription $AZURE_SUBSCRIPTION_ID --always-on true

  az webapp config appsettings set --name cityfix-app-aj --resource-group cityfix-rg \
    --subscription $AZURE_SUBSCRIPTION_ID \
    --settings \
      WEBSITES_CONTAINER_START_TIME_LIMIT=300 \
      JAVA_OPTS="-Xms512m -Xmx1g -XX:MaxRAMPercentage=60"
  ```

- [x] **1.5** Verify: `curl -s -o /dev/null -w "%{http_code}" https://cityfix-app-aj.azurewebsites.net` returns `200` or `404` (default placeholder).

> **Gate:** App Service exists and responds.

---

## Phase 2: Database Provisioning

- [x] **2.1** Generate a strong admin password:
  ```bash
  openssl rand -base64 24
  ```
  > Store securely. Avoid `'`, `"`, `\`, `$`, `` ` `` in password (regenerate if present).

- [x] **2.2** Create PostgreSQL Flexible Server (B1ms):
  ```bash
  az postgres flexible-server create --name cityfix-db --resource-group cityfix-rg \
    --subscription $AZURE_SUBSCRIPTION_ID \
    --location westeurope --sku-name Standard_B1ms --tier Burstable \
    --admin-user cityfixadmin --admin-password '<PASSWORD>' \
    --storage-size 32 --version 16 --tags project=cityfix --yes
  ```
  > Takes 3–5 minutes. Edge case — name conflict: same global uniqueness rule as App Service.

- [x] **2.3** Configure firewall — allow Azure services + local dev IP:
  ```bash
  az postgres flexible-server firewall-rule create --name cityfix-db \
    --resource-group cityfix-rg --subscription $AZURE_SUBSCRIPTION_ID \
    --rule-name AllowAzureServices \
    --start-ip-address 0.0.0.0 --end-ip-address 0.0.0.0

  MY_IP=$(curl -s https://ifconfig.me)
  az postgres flexible-server firewall-rule create --name cityfix-db \
    --resource-group cityfix-rg --subscription $AZURE_SUBSCRIPTION_ID \
    --rule-name AllowLocalDev \
    --start-ip-address $MY_IP --end-ip-address $MY_IP
  ```
  > **Security note:** `AllowAzureServices` (0.0.0.0) allows any Azure service to attempt connection — credentials still required. Acceptable for MVP; switch to VNet integration post-MVP.

- [x] **2.4** Create the application database:
  ```bash
  az postgres flexible-server db create --server-name cityfix-db \
    --resource-group cityfix-rg --subscription $AZURE_SUBSCRIPTION_ID \
    --database-name cityfix
  ```

- [x] **2.5** Verify connectivity (optional, requires `psql`):
  ```bash
  psql "host=cityfix-db.postgres.database.azure.com port=5432 dbname=cityfix \
    user=cityfixadmin password=<PASSWORD> sslmode=require"
  ```
  > **Edge case — SSL:** Azure PostgreSQL enforces SSL by default. All JDBC URLs must include `?sslmode=require`.

> **Gate:** DB server in `Ready` state. `cityfix` database exists. Local `psql` connects.

---

## Phase 3: Application Configuration

Files to modify/create:

### 3.1 — Add dependencies to `pom.xml`

Add inside `<dependencies>` block (after `spring-boot-starter-webmvc`):

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

### 3.2 — Create a root health endpoint

Create `src/main/java/com/example/city_fix/HealthController.java`:

```java
package com.example.city_fix;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    @GetMapping("/")
    public String root() {
        return "CityFix is running";
    }
}
```

### 3.3 — Update `application.properties`

```properties
spring.application.name=city-fix

# Actuator
management.endpoints.web.exposure.include=health,info
management.endpoint.health.show-details=when-authorized
```

### 3.4 — Create `application-azure.properties` (new file)

```properties
# Database — values injected via App Service env vars
spring.datasource.url=${SPRING_DATASOURCE_URL}
spring.datasource.username=${SPRING_DATASOURCE_USERNAME}
spring.datasource.password=${SPRING_DATASOURCE_PASSWORD}
spring.datasource.driver-class-name=org.postgresql.Driver

# JPA
spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=false

# Azure App Service expects port 80
server.port=80
```

### 3.5 — Wire database secrets in App Service

```bash
az webapp config appsettings set --name cityfix-app-aj --resource-group cityfix-rg \
  --subscription $AZURE_SUBSCRIPTION_ID \
  --settings \
    SPRING_PROFILES_ACTIVE=azure \
    SPRING_DATASOURCE_URL="jdbc:postgresql://cityfix-db.postgres.database.azure.com:5432/cityfix?sslmode=require" \
    SPRING_DATASOURCE_USERNAME="cityfixadmin" \
    SPRING_DATASOURCE_PASSWORD="<PASSWORD>"
```

### 3.6 — Verify build

```bash
./mvnw clean package -DskipTests
```

> **Edge case — JVM OOM on B1:** B1 has 1.75 GB RAM. JAVA_OPTS caps heap at 1 GB. If OOM occurs, reduce to `-Xms256m -Xmx768m`. Monitor: `az monitor metrics list --resource <id> --metric "MemoryPercentage"`.
>
> **Edge case — startup timeout:** If Spring Boot takes > 300s (unlikely for this scaffold, possible later with Flyway + entity scanning), increase: `az webapp config appsettings set ... --settings WEBSITES_CONTAINER_START_TIME_LIMIT=600`.

> **Gate:** `./mvnw clean package -DskipTests` succeeds. App settings configured in Azure.

---

## Phase 4: First Deployment

- [x] **4.1** Build the fat JAR:
  ```bash
  ./mvnw clean package -DskipTests
  ```

- [x] **4.2** Deploy via CLI:
  ```bash
  az webapp deploy --name cityfix-app-aj --resource-group cityfix-rg \
    --subscription $AZURE_SUBSCRIPTION_ID \
    --src-path target/city-fix-0.0.1-SNAPSHOT.jar --type jar --async false
  ```
  > JAR is auto-renamed to `app.jar` on the server. Takes 1–3 minutes.

- [x] **4.3** Enable logging and watch startup:
  ```bash
  az webapp log config --name cityfix-app-aj --resource-group cityfix-rg \
    --subscription $AZURE_SUBSCRIPTION_ID \
    --application-logging filesystem --level information \
    --docker-container-logging filesystem

  az webapp log tail --name cityfix-app-aj --resource-group cityfix-rg \
    --subscription $AZURE_SUBSCRIPTION_ID
  ```
  > Expect: `Started CityFixApplication in X seconds`

> **Troubleshooting — common log patterns:**
> | Log pattern | Cause | Fix |
> |---|---|---|
> | `OutOfMemoryError` | JVM heap too large for B1 | Reduce `-Xmx` in JAVA_OPTS |
> | `PSQLException: Connection refused` | Firewall blocks App Service | Re-check firewall rules (Phase 2.3) |
> | `FATAL: password authentication failed` | Wrong credentials in app settings | Re-set `SPRING_DATASOURCE_PASSWORD` |
> | `UnknownHostException: cityfix-db...` | DNS not propagated yet | Wait 2–3 minutes, restart app |
> | App starts on 8080, Azure expects 80 | `azure` profile not active | Verify `SPRING_PROFILES_ACTIVE=azure` |

> **Gate:** Logs show `Started CityFixApplication`. No crash loops.

---

## Phase 5: Smoke Test

- [x] **5.1** Hit root URL:
  ```bash
  curl -s https://cityfix-app-aj.azurewebsites.net/
  # Expected: "CityFix is running"
  ```

- [x] **5.2** Check actuator health:
  ```bash
  curl -s https://cityfix-app-aj.azurewebsites.net/actuator/health
  # Expected: {"status":"UP"}
  ```

- [x] **5.3** Verify DB connectivity in health response — db component should show `UP` with PostgreSQL details.

- [x] **5.4** Audit all app settings:
  ```bash
  az webapp config appsettings list --name cityfix-app-aj --resource-group cityfix-rg \
    --subscription $AZURE_SUBSCRIPTION_ID --output table
  ```
  Expected: `SPRING_PROFILES_ACTIVE`, `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, `JAVA_OPTS`, `WEBSITES_CONTAINER_START_TIME_LIMIT` — all present and correct.

> **Edge case — 502/503:** App still starting. Wait 60–90s, retry. If persistent > 5 min: `az webapp restart --name cityfix-app-aj --resource-group cityfix-rg --subscription $AZURE_SUBSCRIPTION_ID` and re-check logs.

> **Gate:** Root returns 200. Actuator health returns UP. DB component healthy.

---

## Phase 6: CI/CD Pipeline (GitHub Actions)

- [x] **6.1** ~~Service principal~~ — Entra ID permissions not available. Using **publish profile** instead (no admin required).
  ```bash
  az webapp deployment list-publishing-profiles --name cityfix-app-aj \
    --resource-group cityfix-rg --subscription $AZURE_SUBSCRIPTION_ID --xml > /tmp/publish-profile.xml
  ```

- [x] **6.2** Store publish profile as GitHub Secret `AZURE_WEBAPP_PUBLISH_PROFILE`:
  ```bash
  gh secret set AZURE_WEBAPP_PUBLISH_PROFILE -R adjablon/city-fixer < /tmp/publish-profile.xml
  rm /tmp/publish-profile.xml
  ```

- [x] **6.3** Create `.github/workflows/deploy.yml`:
  ```yaml
  name: Build and Deploy to Azure

  on:
    push:
      branches: [main]
    workflow_dispatch:

  permissions:
    contents: read

  jobs:
    build-and-deploy:
      runs-on: ubuntu-latest
      steps:
        - uses: actions/checkout@v4

        - uses: actions/setup-java@v4
          with:
            java-version: '21'
            distribution: 'temurin'
            cache: 'maven'

        - name: Build with Maven
          run: ./mvnw clean package -DskipTests -B

        - name: Upload artifact for rollback
          uses: actions/upload-artifact@v4
          with:
            name: city-fix-jar
            path: target/city-fix-0.0.1-SNAPSHOT.jar
            retention-days: 30

        - name: Deploy to Azure App Service
          uses: azure/webapps-deploy@v3
          with:
            app-name: cityfix-app-aj
            publish-profile: ${{ secrets.AZURE_WEBAPP_PUBLISH_PROFILE }}
            package: target/city-fix-0.0.1-SNAPSHOT.jar
  ```

- [x] **6.4** Commit and push:
  ```bash
  git add pom.xml src/ .github/
  git commit -m "First deployment: add DB deps, actuator, Azure profile, CI/CD pipeline"
  git push origin main
  ```

- [x] **6.5** Verify workflow succeeds: `gh run watch`

- [x] **6.6** Verify app after CI deploy:
  ```bash
  curl -s https://cityfix-app-aj.azurewebsites.net/actuator/health
  ```

> **Gate:** Push to `main` triggers green CI run. App healthy after automated deploy.

---

## Phase 7: Post-Deployment Hardening

- [x] **7.1** Verify all resources are tagged: `az resource list --resource-group cityfix-rg --subscription $AZURE_SUBSCRIPTION_ID --output table`
- [x] **7.2** Audit for orphaned resources — expect only: `cityfix-plan`, `cityfix-app-aj`, `cityfix-db`
- [x] **7.4** Enable filesystem logging (already done in 4.3, verify it persists)
- [x] **7.5** Document cleanup commands for future teardown:
  ```bash
  # 1. Delete all Azure resources (App Service, Plan, PostgreSQL)
  az group delete --name cityfix-rg --subscription $AZURE_SUBSCRIPTION_ID --yes --no-wait

  # 2. Delete GitHub Secrets (optional — harmless if left)
  gh secret delete AZURE_WEBAPP_PUBLISH_USER
  gh secret delete AZURE_WEBAPP_PUBLISH_PASS
  ```

> **Gate:** All resources tagged. No orphans. Rollback procedure documented (re-run previous CI workflow or manual `az webapp deploy` with old JAR).

---

## Risk Register (from infrastructure.md, cross-referenced to plan phases)

| Risk | Mitigation | Phase |
|---|---|---|
| Startup killed by health check | `WEBSITES_CONTAINER_START_TIME_LIMIT=300` (corrected name) | 1.4 |
| Orphaned resources inflate bill | Tags + Phase 7 audit | 1.1, 7.2 |
| No slot swap on B1 | CI artifacts retained 30 days; `gh run rerun` for rollback | 6.3 |
| DB over public internet | Firewall + SSL `sslmode=require`; VNet post-MVP | 2.3, 3.4 |
| Logs lost after 12h | Filesystem logging; check same-day; Log Analytics post-MVP | 4.3 |
| JVM OOM on B1 (1.75 GB) | `JAVA_OPTS` caps heap at 1 GB | 1.4 |
| Entra ID blocks CI auth | Used basic auth (Kudu API) instead of service principal; required enabling basic auth on App Service | 6.1 |
| Cost creep | Quarterly resource group audit | 7.2 |

---

## Quick Reference

| Item | Value |
|---|---|
| Subscription ID | `5559456a-f30a-4505-a478-a3b978daabb7` |
| Resource group | `cityfix-rg` |
| App Service plan | `cityfix-plan` (B1 Linux) |
| App Service | `cityfix-app-aj` |
| App URL | `https://cityfix-app-aj.azurewebsites.net` |
| DB server | `cityfix-db` (`cityfix-db.postgres.database.azure.com`) |
| DB name | `cityfix` |
| DB admin | `cityfixadmin` |
| Region | `westeurope` |
| Runtime | `JAVA:21-java21` |
| Spring profile | `azure` |
| GitHub repo | `adjablon/city-fixer` |
| Monthly cost est. | ~$25 (B1 $13 + B1ms $12) |

## Files Modified/Created

| File | Action | Phase |
|---|---|---|
| `pom.xml` | Add JPA, PostgreSQL, Actuator deps | 3.1 |
| `src/main/java/com/example/city_fix/HealthController.java` | Create | 3.2 |
| `src/main/resources/application.properties` | Update (add actuator config) | 3.3 |
| `src/main/resources/application-azure.properties` | Create (DB + port config) | 3.4 |
| `.github/workflows/deploy.yml` | Create (CI/CD pipeline) | 6.3 |

## Verification (end-to-end)

1. `curl https://cityfix-app-aj.azurewebsites.net/` returns `"CityFix is running"`
2. `curl https://cityfix-app-aj.azurewebsites.net/actuator/health` returns `{"status":"UP"}` with DB component healthy
3. Push a trivial change to `main` — GitHub Actions builds and deploys automatically
4. `az resource list --resource-group cityfix-rg` shows exactly 3 resources, all tagged
