# MimoRag

A generic, multi-tenant RAG platform for our company systems (HRIS, CRM, ERP, …). Connected systems push documents and tool definitions. The platform extracts documents page by page, lets the owning company review and approve the content, indexes only approved content, and answers strictly from authorized evidence.

- Roadmap, status and decisions: [docs/roadmap.md](docs/roadmap.md)
- Architecture: [docs/architecture.md](docs/architecture.md) · Security: [docs/security-architecture.md](docs/security-architecture.md)
- Document review workflow: [docs/workflows/document-review.md](docs/workflows/document-review.md) · ADRs: [docs/adr/](docs/adr/)
- Progress and acceptance results: [PROJECT_PROGRESS.md](PROJECT_PROGRESS.md)

## Prerequisites

| Tool | Version |
|---|---|
| JDK | 21 or newer (the project targets Java 21) |
| Docker | Docker Desktop / Engine with Compose v2 (needed for local PostgreSQL and for the integration tests) |
| Disk | About 0.5 GB for the PostgreSQL image in M0. Check free space before later milestones add OCR and model images. |

No paid accounts or API keys are needed.

## Run locally

```bash
# 1. Create your local settings (git-ignored) and replace every change-me value
cp .env.example .env

# 2. Start PostgreSQL (loopback only, port 5433 by default)
docker compose up -d
docker compose ps            # wait for "healthy"

# 3. Run the application with the local profile (reads ./.env)
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
#    or: ./mvnw -DskipTests package && java -jar target/mimorag-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
```

On Windows PowerShell, use `Copy-Item .env.example .env` and `.\mvnw.cmd`.

The first database start runs `docker/postgres/initdb/01-create-roles.sh`. It creates the `mimorag` schema, the `mimorag_owner` role (migrations) and the `mimorag_app` role (runtime: DML only, no RLS bypass). If you change those passwords later, recreate the volume with `docker compose down -v`. **That deletes local data.**

## Check it

```bash
curl -i http://localhost:8080/actuator/health            # 200, components incl. db: UP
curl -i http://localhost:8080/actuator/health/readiness  # includes db
curl -i http://localhost:8080/api/v1/anything            # 401 application/problem+json (deny-by-default)
open  http://localhost:8080/swagger-ui.html              # local profile only
```

## Use the API (M1)

```bash
# Admin key: generate once, keep the key secret, put only its SHA-256 into .env (MIMORAG_ADMIN_KEY_SHA256)
ADMIN_KEY=$(openssl rand -base64 32 | tr -d '=+/'); printf '%s' "$ADMIN_KEY" | sha256sum

# 1. Register an integration (admin plane)
curl -X POST localhost:8080/api/v1/admin/integrations -H "X-Admin-Key: $ADMIN_KEY" \
  -H 'Content-Type: application/json' -d '{"code":"hris","name":"HRIS"}'

# 2. Issue an API key for it. The plaintext key is returned ONCE.
curl -X POST localhost:8080/api/v1/admin/integrations/<integrationId>/api-keys -H "X-Admin-Key: $ADMIN_KEY" \
  -H 'Content-Type: application/json' -d '{"name":"hris-backend","scopes":["KB_MANAGE","KB_READ"]}'

# 3. As the integration: create and list Knowledge Bases (the tenant comes from the key)
curl -X POST localhost:8080/api/v1/knowledge-bases -H "X-Api-Key: mrk_..." \
  -H 'Content-Type: application/json' -d '{"code":"hr-policies","name":"HR Policies"}'
curl localhost:8080/api/v1/knowledge-bases -H "X-Api-Key: mrk_..."

# 4. On behalf of an end user: add a signed user assertion (RS256/ES256 JWT from the integration)
curl localhost:8080/api/v1/knowledge-bases -H "X-Api-Key: mrk_..." -H "X-User-Assertion: eyJ..."
```

User assertions are verified against the integration's trust configuration
(`PUT /api/v1/admin/integrations/{id}/trust-config`). In production that is an `https` JWKS URI. The `local` profile also accepts
inline public keys. For manual testing, `src/test/java/.../devtools/DevAssertionTool` can generate a key pair and mint
tokens (see `PROJECT_PROGRESS.md`, M1).

## Test

```bash
./mvnw clean verify     # unit, architecture and Testcontainers integration tests (Docker required)
```

## Configuration

All settings come from environment variables. In the `local` profile they can also come from `./.env`. The application refuses to start, listing the missing **names**, if a required setting is absent.

| Variable | Required | Purpose |
|---|---|---|
| `MIMORAG_DB_URL` | yes | JDBC URL, e.g. `jdbc:postgresql://localhost:5433/mimorag` |
| `MIMORAG_DB_APP_PASSWORD` | yes | Password of the runtime role `mimorag_app` |
| `MIMORAG_DB_OWNER_PASSWORD` | yes | Password of the migration role `mimorag_owner` |
| `MIMORAG_DB_APP_USER` / `MIMORAG_DB_OWNER_USER` | no | Role names (default `mimorag_app` / `mimorag_owner`) |
| `MIMORAG_DB_POOL_SIZE` | no | Connection pool size (default 10) |
| `MIMORAG_ADMIN_KEY_SHA256` | no | Hex SHA-256 of the platform-admin key. Empty disables the admin API. |
| `MIMORAG_DB_PORT`, `POSTGRES_*` | local only | Docker Compose container settings |

Never commit `.env` or real secrets.
