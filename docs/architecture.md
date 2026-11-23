# MimoRag — Architecture Baseline

> Status: **M0 baseline + M1** (2026-11-23). Scope and milestone status live in [roadmap.md](roadmap.md); decisions are recorded as ADRs in [adr/](adr/).
> Related: [security-architecture.md](security-architecture.md) · [workflows/document-review.md](workflows/document-review.md)

## 1. Context

MimoRag is a **generic, reusable RAG platform** used by our company systems (HRIS, CRM, ERP, …). Those systems:

- **push knowledge** (PDF documents first), which the platform extracts page by page, lets the owning company review and correct, then indexes;
- **push tool definitions**, which the platform validates, lets admins publish, and later lets the Agent invoke with platform-enforced authorization;
- **own their users**. They authenticate end users themselves and call MimoRag with a service credential plus, when needed, a signed user assertion.

The platform answers **only** from authorized, retrieved evidence and **abstains** when evidence is insufficient. It contains no HRIS-, CRM- or ERP-specific logic.

## 2. Technology baseline

| Concern | Choice | Notes |
|---|---|---|
| Language / runtime | Java 21 LTS (target), Spring Boot 4.2.0 | Developers may run a newer JDK; bytecode targets 21. |
| Persistence | PostgreSQL 17 (`pgvector/pgvector:0.8.7-pg17` image), Spring Data JPA, Flyway | `vector` extension enabled by a Flyway migration in M4, not before. |
| API docs | springdoc-openapi 3.2.1 | Enabled only in the `local` profile. |
| Security | Spring Security, stateless | Deny-by-default in M0; authentication in M1. |
| Tests | JUnit 5, AssertJ, MockMvc, Testcontainers 2, ArchUnit 1.5.1 | Integration tests run against real PostgreSQL with the real role model. |
| AI / OCR (later) | Own interfaces; Ollama, PaddleOCR (CPU) behind adapters | Spring AI may be used **inside adapters only**, stable releases only ([ADR-0002](adr/0002-provider-abstractions-and-stable-dependencies.md)). |
| Lombok | Not used | Records for DTOs; plain classes elsewhere. |

All dependencies are stable releases (no `-M`/`-RC`). Versions not managed by the Spring Boot BOM are pinned in `pom.xml` properties.

## 3. Deployment shape: modular monolith

One deployable Spring Boot application, with OCR and model runtimes as **separate local processes/containers** (PaddleOCR service, Ollama) reached over stable internal HTTP contracts. No microservices for their own sake ([ADR-0001](adr/0001-modular-monolith-and-module-boundaries.md)).

```text
             Connected systems (HRIS / CRM / ERP)            Platform admins
                         │ service credential                      │ admin credential
                         │ + signed user assertion (JWT)           │
                         ▼                                         ▼
┌──────────────────────────────── MimoRag (Spring Boot) ───────────────────────────────────┐
│ access ── authenticates callers, builds AccessContext (only IdP-aware module)            │
│                                                                                           │
│ integration   knowledgebase   ingestion   review   indexing   retrieval   tools   agent   │
│                                                                           evaluation      │
│ shared ── error format, correlation IDs, config checks, AccessContext & permissions      │
└───────────────┬──────────────────────────┬───────────────────────┬────────────────────────┘
                │ JDBC (app role, RLS)      │ HTTP                   │ HTTP
                ▼                           ▼                        ▼
        PostgreSQL + pgvector        PaddleOCR service (M2)     Ollama (M4/M5)
        + object storage (M2)
```

## 4. Module map

Root package: `com.mimocodes.mimorag`.

| Module | Responsibility | Milestone |
|---|---|---|
| `shared` | Error format (RFC 9457), correlation IDs, required-settings checks, `AccessContext` + `AccessContextHolder` + permission vocabulary, tenant-aware transaction manager (RLS settings), audit recorder, pagination, clock. **No business logic, depends on no feature module.** | M0, M1 |
| `access` | Edge adapters: Spring Security filter chains, integration API-key authentication, user-assertion (JWT) verification, admin authentication. Produces `AccessContext`. The **only** module that knows about identity providers, JWT libraries or Spring Security. | M0 (baseline), M1 |
| `integration` | Integrations (tenants), credential records (hashed), trust configuration (issuer, audience, JWKS URL, claim mapping), lifecycle. | M1 |
| `knowledgebase` | KBs, ownership, KB policies (access rules, review rules, service-identity search opt-in). | M1 |
| `ingestion` | Upload, file validation, original-file storage, extraction orchestration, `PdfTextExtractor`, `OcrProvider`, jobs. | M2 |
| `review` | Page review states, page revisions, submit validation, immutable approved snapshots. | M3 |
| `indexing` | Normalization, chunking, `EmbeddingProvider`, `VectorStore`, ingestion runs, active-version switch. Reads **only** approved snapshots. | M4 |
| `retrieval` | Query embedding, filtered retrieval, context assembly, `ChatModelProvider`, grounded answers, citations, abstention. | M5 |
| `tools` | Tool registry, validation, publish workflow, `ToolExecutor`, REST executor. | M6–M7 |
| `agent` | Tool-calling orchestration with budgets and provenance. | M8 |
| `evaluation` | Feedback, evaluation datasets and runs, metrics. | M9 |

### Layering inside a module

```text
api (controllers, request/response records)
  └─► application (use-case services, ports)
        └─► domain (plain Java: entities' behaviour, value objects, rules)
infrastructure (JPA repositories, HTTP clients, provider adapters) ──implements──► application/domain ports
```

### Rules enforced by `ArchitectureRulesTest` (ArchUnit)

1. No dependency cycles between top-level modules.
2. `shared` depends on no feature module (and not on `access`).
3. Core modules (`integration` … `evaluation`) do not depend on `access`, Spring Security or JWT libraries.
4. `..domain..` packages do not depend on Spring, Hibernate, Jackson or Servlet APIs. `jakarta.persistence` mapping annotations are allowed ([ADR-0009](adr/0009-jpa-annotations-in-domain.md)).
5. `..domain..` does not depend on `..application..`, `..api..` or `..infrastructure..`.
6. `..api..` does not depend on `..infrastructure..`; `..application..` does not depend on `..api..`.
7. `@RestController`s live in `..api..` (or `access`) and never use repositories directly.
8. `access` uses the `integration` module only through `integration.application`.
9. JOSE/JWT libraries (`com.nimbusds`, `spring-security-oauth2`) are used only inside `access`.
10. No field injection; no `java.util.logging`.
11. Only `access`, `shared.access`, `shared.persistence` and the job runner (`jobs`) may bind an access context (`AccessContextHolder.set/callAs/clear`). Binding decides which tenant the database sees.

Rules that target modules not yet implemented use `allowEmptyShould(true)`, so they start protecting each module the moment its first class appears.

**Persistence ports (M1):** each module declares narrow Spring Data `Repository<T, ID>` interfaces in its `application` package, listing only the methods its use cases need. Pre-tenant JDBC (the SECURITY DEFINER credential lookup) lives in `infrastructure`. Application views (immutable records without secrets) are returned directly as API response bodies.

## 5. Data and migrations

- **Schema:** a single `mimorag` schema. Flyway migrations live in `src/main/resources/db/migration`.
- **Database roles** are created by provisioning (`docker/postgres/initdb/01-create-roles.sh`), not by migrations ([ADR-0003](adr/0003-postgresql-flyway-and-database-roles.md)):
  - `mimorag_owner` owns the schema and runs Flyway (DDL).
  - `mimorag_app` is the runtime role: DML only, no DDL, `NOBYPASSRLS`, and no access to `flyway_schema_history`.
- Hibernate runs with `ddl-auto: validate`. It never changes the schema.
- From M1 onward, every tenant-owned table has `integration_id NOT NULL`, composite indexes starting with it, and Row-Level Security policies ([ADR-0004](adr/0004-tenant-isolation-and-row-level-security.md)).
- The same provisioning script is used by Docker Compose **and** by Testcontainers, so tests exercise the real privilege model.

## 6. API conventions

| Topic | Convention |
|---|---|
| Versioning | `/api/v1/...` (integration plane), `/api/v1/admin/...` (platform admin plane). |
| Errors | RFC 9457 `application/problem+json` with `type` (`urn:mimorag:problem:<code>`), `title`, `status`, `detail`, plus `errorCode`, `correlationId`, `timestamp`, and `violations[]` for validation failures ([ADR-0008](adr/0008-api-error-format-correlation-and-logging.md)). |
| Error hygiene | No stack traces, exception messages, SQL or class names in responses. Rejected input values are not echoed back. |
| Not found vs forbidden | A resource that is missing and one owned by another tenant both return **404**. Unauthenticated callers get 401 for every protected path, whether it exists or not. |
| IDs | UUIDs. No sequential identifiers exposed. |
| Validation | Bean Validation on request records at the boundary; domain rules re-checked in application services. |
| Pagination | `page`, `size` (with an enforced maximum), `sort`. |
| Long-running work | Returns `202 Accepted` with a job ID; status via polling (webhooks later). |
| Idempotency | `Idempotency-Key` header on non-idempotent creates where duplicates are harmful (uploads, catalog sync). Introduced with the first such endpoint. |
| Correlation | `X-Correlation-Id` accepted when it matches `[A-Za-z0-9._-]{8,64}`, otherwise generated. Always echoed in the response, the logs and error bodies, and stored on job records. |
| Documentation | OpenAPI via springdoc. Each milestone documents curl examples in `PROJECT_PROGRESS.md`. |

## 7. Configuration and secrets

- All connection settings and secrets come from **environment variables**. `application.yml` has **no** default passwords or URLs.
- **Local development:** the `local` profile imports a git-ignored `.env` file (template: `.env.example`, placeholders only). Docker Compose reads the same file.
- `RequiredSettingsVerifier` fails startup with a clear "APPLICATION FAILED TO START" report listing missing setting **names** (never values).
- Development authentication (e.g. the bootstrap admin key) is configuration-separated from production identity integration. Production integrations use RS256/ES256 JWTs verified against trusted JWKS endpoints ([ADR-0005](adr/0005-identity-and-access-model.md)).
- Paid or hosted providers are never enabled by default. There is no silent fallback from local to paid models.

## 8. Observability

- **Logs:** JSON (ECS format) by default; human-readable in the `local` profile. Every request carries `correlationId` in the MDC.
- **Never logged:** secrets, credentials, full document text, prompts, or tool payloads.
- **Health:**
  - `/actuator/health` shows component states (`db: UP/DOWN`) without internal details.
  - `/actuator/health/readiness` includes the database, so traffic stops when it is down.
  - `/actuator/health/liveness` excludes the database, so the JVM is not restarted for a database outage.
- **Exposure:** only `health` and `info` are exposed over HTTP. Metrics and Prometheus come with per-stage timers from M2, behind authentication.

## 9. Asynchronous processing (planned, M2)

Proposed in [ADR-0007](adr/0007-async-jobs-and-transactional-outbox.md), to be confirmed in M2:

- **Jobs:** a PostgreSQL-backed job table claimed with `FOR UPDATE SKIP LOCKED`, worked by bounded worker pools (separate pools for OCR, embedding and LLM calls).
- **Events:** a transactional outbox carries domain events between modules (`ExtractionCompleted`, `SnapshotApproved`, …).
- **Retries:** bounded, with backoff and recorded failure reasons; idempotency keys prevent duplicate work.
- **No message broker** in the MVP.

## 10. Performance strategy

- **Per-stage measurement:** Micrometer timers for extraction (native vs OCR, per page), embedding (per batch), retrieval, reranking, generation and tool execution. Each milestone reports p50/p95 and compares against the previous baseline in `PROJECT_PROGRESS.md`.
- **Hardware budget:** the development machine has 16 GB RAM, a 2 GB VRAM GPU and limited disk. We use small models and CPU OCR with bounded concurrency so OCR and LLM calls cannot starve the API. Disk space is checked before any large image or model download.
- **Order of work:** benchmark first, then optimize (e.g. the HNSW index, hybrid search, caching with tenant-aware keys).
- **Benchmark harness:** a repeatable harness under `benchmarks/` from M2, with results kept out of Git.

## 11. Testing strategy

| Layer | Tooling | Purpose |
|---|---|---|
| Unit | JUnit 5, AssertJ | Domain rules, validation, error mapping, parsing. |
| Web slice / contract | MockMvc (standalone or full context) | Error format, status codes, headers. |
| Integration | `@SpringBootTest` + Testcontainers PostgreSQL (real role script) | Migrations, privileges, RLS, repositories, security chains. |
| Architecture | ArchUnit | Module boundaries and layering. |
| Security | MockMvc + Testcontainers | Default-deny, cross-tenant access (every milestone from M1), injection cases (M2+). |
| Quality (M5+) | Evaluation datasets | Retrieval and answer metrics, abstention, citation accuracy. |
| Real HTTP | curl against the running app | Milestone demos; commands recorded in `PROJECT_PROGRESS.md`. |

Command: `./mvnw clean verify` (requires Docker for Testcontainers).
