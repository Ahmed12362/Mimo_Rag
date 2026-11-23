# Project Progress

Milestone scope and decisions: [docs/roadmap.md](docs/roadmap.md). This file records what was delivered, how it was verified, and the measured baselines.

| Milestone | Status | Branch |
|---|---|---|
| M0 — Architecture baseline | ✅ Implemented and verified. **Awaiting approval and commit.** | `chore/m0-architecture-baseline` |
| M1 – M11 | Not started | — |

---

## M0 — Repository inspection and architecture baseline

**Date:** 2026-11-23 · **Environment:** Windows 10, JDK 24 (target 21), Docker 28.3.2, 16 GB RAM, Quadro M1000M 2 GB VRAM

### Delivered

- **Repository and build:**
  - Git initialized, with `.gitignore` covering secrets, local data, PDFs and benchmark results, and `.gitattributes` fixing line endings for `.sh` and `.cmd`.
  - Root package renamed to `com.mimocodes.mimorag`.
  - `pom.xml` cleaned up. Removed: Spring AI `2.1.0-M1`, the pgvector starter and Lombok. Added: Actuator, Spring Security, Flyway (with its PostgreSQL module), springdoc 3.2.1, Testcontainers 2 and ArchUnit 1.5.1. Only stable releases are used.
- **Local environment:**
  - `docker-compose.yml` runs PostgreSQL 17 (`pgvector/pgvector:0.8.7-pg17`) bound to `127.0.0.1:5433`, with a health check and a 1 GB memory limit.
  - `.env.example` holds placeholders only; the `local` profile imports the git-ignored `.env`.
  - The database role script (`mimorag_owner` / `mimorag_app`) is shared by Docker Compose and Testcontainers.
- **Baseline code:**
  - RFC 9457 error format (`ApiProblems`, `ApiErrorCode`, `GlobalExceptionHandler`), also used for security errors.
  - Correlation-ID filter (validated, echoed, added to the logging context).
  - Deny-by-default stateless security, with only `health` and `info` public.
  - Startup check for required settings, with a clear failure report.
  - OpenAPI metadata.
  - Flyway `V1__baseline` (schema comment; application role locked out of migration history).
  - Health endpoint with liveness and readiness probes; readiness includes the database.
- **Architecture tests:** ArchUnit rules for module boundaries and layering (see `docs/architecture.md` §4).
- **Docs:** `docs/roadmap.md` (moved from Downloads; now includes status, decision log, Git workflow and open questions), `docs/architecture.md`, `docs/security-architecture.md`, `docs/workflows/document-review.md`, ADR-0001 to ADR-0008, `README.md`, and this file.
- **Removed:** Spring Initializr's `HELP.md` and the empty `static/` and `templates/` folders.

### Acceptance criteria

| Criterion | Result | Evidence |
|---|---|---|
| Application starts from a clean checkout using documented steps | ✅ Pass | README steps followed: `.env` from `.env.example`, `docker compose up -d`, jar run with `local` profile. Started in 9.8 s. Flyway applied `V1` as `mimorag_owner`. |
| Health endpoint reports application and database dependencies accurately | ✅ Pass | `db: UP` while running. After `docker compose stop postgres`: HTTP 503, `db: DOWN`, readiness 503, liveness 200. Recovered to `UP` automatically after restart. Automated in `HealthWhenDatabaseDownIntegrationTest`. |
| No secrets are committed | ✅ Pass | `.env` is git-ignored (`git check-ignore` confirmed). `.env.example` has placeholders only. Test credentials are throwaway values for ephemeral containers. No default passwords in `application.yml`. |
| Architecture decisions and open questions are documented | ✅ Pass | `docs/roadmap.md` §0.2 (decision log) and §0.4 (open questions); ADRs 0001–0008. |

### Quality gates

| Gate | Result | Details |
|---|---|---|
| Functional | ✅ | curl checks below |
| Security | ✅ | Deny-by-default (8 paths, including `/actuator/env`, `/actuator/heapdump` and `/v3/api-docs`); identical 401 for existing and unknown paths; no `Set-Cookie`; security headers; no internals in errors; correlation-ID injection rejected; least-privilege database role. |
| Cross-tenant isolation | ➖ N/A in M0 | No tenant data yet. The privilege model required for RLS is verified (`mimorag_app`: not superuser, `NOBYPASSRLS`, no DDL). Isolation tests start in M1. |
| Performance baseline | ✅ | See below |
| Error handling | ✅ | Validation (400 with sorted violations, values not echoed), malformed JSON, 405, 415, unexpected 500, database unavailable (503), missing configuration (clear startup failure). |
| Documentation | ✅ | README, architecture, security, workflow, ADRs, roadmap, this file. |

### Automated tests — `./mvnw clean verify` → **BUILD SUCCESS, 47 tests, 0 failures**

| Test class | Tests | Covers |
|---|---|---|
| `ArchitectureRulesTest` | 10 | Module cycles, shared-kernel independence, core modules free of auth mechanics, framework-free domain, layering, controller rules, no field injection, no JUL |
| `CorrelationIdFilterTest` | 8 | Generation, propagation, rejection of malformed or injected IDs, MDC cleanup on failure |
| `GlobalExceptionHandlerTest` | 5 | Validation format, malformed JSON, 500 hygiene, 405, 415 |
| `RequiredSettingsVerifierTest` | 3 | Missing settings reported by name only; failure analysis |
| `SecurityBaselineIntegrationTest` | 12 | Deny-by-default, indistinguishable 401s, no cookies, correlation propagation, headers |
| `HealthEndpointIntegrationTest` | 4 | Health, readiness, liveness, no anonymous details |
| `HealthWhenDatabaseDownIntegrationTest` | 1 | DOWN/503 on database outage; liveness stays UP |
| `DatabaseRolesIntegrationTest` | 4 | Runtime role privileges; Flyway applied by owner; no DDL; no access to migration history |

### Real HTTP checks (application running with `local` profile against Docker Compose PostgreSQL)

```text
$ curl -i http://localhost:8080/actuator/health
HTTP/1.1 200
X-Correlation-Id: 2cc0e098-f9a9-4b17-8db6-d86132a863d8
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
Cache-Control: no-cache, no-store, max-age=0, must-revalidate
{"components":{"db":{"status":"UP"},"diskSpace":{"status":"UP"},"livenessState":{"status":"UP"},
 "ping":{"status":"UP"},"readinessState":{"status":"UP"},"ssl":{"status":"UP"}},
 "groups":["liveness","readiness"],"status":"UP"}

$ curl http://localhost:8080/actuator/health/readiness
{"components":{"db":{"status":"UP"},"readinessState":{"status":"UP"}},"status":"UP"}

$ curl -i -H "X-Correlation-Id: demo-curl-0001" http://localhost:8080/api/v1/integrations
HTTP/1.1 401
X-Correlation-Id: demo-curl-0001
Content-Type: application/problem+json
{"type":"urn:mimorag:problem:authentication-required","title":"Unauthorized","status":401,
 "detail":"Valid credentials are required to access this resource.","errorCode":"AUTHENTICATION_REQUIRED",
 "correlationId":"demo-curl-0001","timestamp":"2026-11-23T11:20:16.901441800Z"}

$ curl -o /dev/null -w "%{http_code}" http://localhost:8080/actuator/env          → 401
$ curl -D - -H "X-Correlation-Id: bad id <x>" .../actuator/health                → X-Correlation-Id: <new UUID>
$ curl http://localhost:8080/v3/api-docs (local profile)                         → OpenAPI 3.1 document

# Database outage
$ docker compose stop postgres
$ curl http://localhost:8080/actuator/health   → HTTP 503 in 3.0 s, "db":{"status":"DOWN"}, "status":"DOWN"
  readiness → 503 · liveness → 200
$ docker compose start postgres
$ curl http://localhost:8080/actuator/health   → HTTP 200, "status":"UP"

# Missing configuration
$ java -jar target/mimorag-0.0.1-SNAPSHOT.jar
APPLICATION FAILED TO START
Description: MimoRag cannot start because these required settings are not set:
             MIMORAG_DB_URL, MIMORAG_DB_APP_PASSWORD, MIMORAG_DB_OWNER_PASSWORD
Action: Provide them as environment variables. For local development, copy .env.example to .env ...
```

### Performance baseline (M0)

Measured on the developer machine, local profile, warm JVM, sequential curl requests (n = 100 after 10 warm-up requests):

| Metric | Value |
|---|---|
| Application startup (`Started MimoRagApplication`) | 9.8 s |
| `GET /actuator/health` (includes DB check) | p50 9.5 ms · p95 11.2 ms · max 16.5 ms |
| Denied request (401, security filter only) | p50 3.4 ms · p95 4.0 ms |
| JVM working set at idle after the requests | 254 MB, 51 threads |
| PostgreSQL container memory at idle | 54 MiB (limit 1 GiB) |
| Health response time when database is down | 3.0 s (bounded by Hikari `connection-timeout: 3000`) |
| Application jar size | 61 MB |
| Full test suite (`clean verify`) | about 1–2 min (container start dominates) |

These are the reference numbers for later milestones.

### Implementation review

1. **Clean Code / SOLID:**
   - Small single-purpose classes: the filter, the problem builder, the error-code mapping, the security error handler and the settings verifier.
   - The controller advice only maps exceptions.
   - Security mechanics are isolated in `access`. Shared cross-cutting code lives in `shared`, which depends on no feature module.
   - Configuration is externalized; there are no magic values in code (error codes are an enum and the ID pattern is a named constant).
2. **Intentional deviations:**
   - `access.web.ProblemDetailSecurityErrorHandler` serializes `ProblemDetail` via a `Map`, because security filters run outside Spring MVC's message converters.
   - The ERROR dispatcher type is permitted so that container error pages are not replaced by 401s.
3. **Module dependencies:** `access → shared`. `shared` depends on nothing internal. No cycles (ArchUnit-verified).
4. **Tests:** 47 tests, listed above. Command: `./mvnw clean verify`.
5. **Technical debt / follow-ups:**
   - Mockito prints a JDK self-attach warning on JDK 24. It is harmless; configure Mockito as a Java agent when convenient.
   - The JPA-out-of-domain ArchUnit rule will be revisited with real entities in M1 (see ADR-0001).
   - Metrics endpoints are not exposed until authenticated admin access exists (M1+).
   - Swagger UI and OpenAPI are enabled only in the `local` profile.

### Known limitations

- No authentication yet: every business path returns 401 by design until M1.
- `main` has no commits yet. The first commit will be on `chore/m0-architecture-baseline`, pending approval.
- Docker storage remains on C: (about 15 GB free). Reassess before M2 (Q-04).
