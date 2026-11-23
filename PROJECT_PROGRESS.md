# Project Progress

Milestone scope and decisions: [docs/roadmap.md](docs/roadmap.md). This file records what was delivered, how it was verified, and the measured baselines.

| Milestone | Status | Branch |
|---|---|---|
| M0 — Architecture baseline | ✅ Done (commit `d963e59`) | `chore/m0-architecture-baseline` → `main` |
| M1 — Integrations, KBs, identity, RLS | ✅ Done (commit `5db21e9`) | `main` |
| M1 hardening — key lifecycle, KB-restricted keys, review fixes | ✅ Implemented and verified. **Awaiting approval and commit.** | `fix/m1-hardening` |
| M2 – M11 | Not started | — |

---

## M1 hardening — findings from the M1 review

**Date:** 2026-11-23 · **Decisions:** D-20 to D-25 · ADR-0011, ADR-0012 · **Migration:** V5 (V2–V4 untouched, already on `main`)

### Delivered (by review finding)

| Finding | Fix |
|---|---|
| **H1** Admin could mint content keys | Admins issue only bootstrap keys (`KEY_ROTATE`, 24 h, single use, authenticate only for `POST /api/v1/integration/api-keys`, never with user assertions). Integrations create, rotate and revoke their own keys within an admin-set scope ceiling (`allowedScopes`). The old admin `POST …/api-keys` is removed. |
| **Q-06** Optional expiry | Expiry is mandatory: default 90 days, maximum 365 days (code + DB CHECK). Overlapping keys; `X-Api-Key-Expires-At` header in the last 14 days. |
| **M6** Tenant-wide keys only | Optional `knowledgeBaseIds` restriction (composite tenant FK), enforced for services and relayed users. Restricted keys cannot create KBs or create broader keys. |
| **M1** Transaction-manager edge cases | Failure applying tenant settings rolls back and releases all thread-bound resources. Switching context inside an active transaction is rejected. |
| **M2** Public context binding | ArchUnit: only `access`, `shared.access`, `shared.persistence` and `jobs` may bind an access context. |
| **M5 / L3** JWKS robustness | Explicit 5 min cache and at most one re-fetch per 30 s for unknown key ids (Nimbus `JWKSourceBuilder`); 50 KB size limit; no redirects; RSA ≥ 2048 bits (remote and inline); unreachable JWKS → 503. |
| **M7** Relayed user not attributable to a key | `AccessContext.credential` carries the key; `audit_event.api_key_id` on every row. |
| **M8** No RLS guard for new tables | `RlsCoverageIntegrationTest`: every table must be classified; tenant tables must FORCE RLS; every RLS table needs a policy. |
| **L1** Race → 500 | `saveAndFlush` inside `try` for integration, KB, grants and key creation → 409. |
| **L2** DB round trip per request | Last-use recording throttled in memory (once per key per 5 min per JVM). |
| (found during testing) | The application clock now ticks in microseconds, matching PostgreSQL precision, so API timestamps equal stored values. |
| Environment | `.wslconfig` with `memory=6GB`, `processors=6`, `swap=2GB`, `autoMemoryReclaim=gradual` (Docker now reports 6 GB / 6 CPUs). Documented in `docs/local-environment.md`. |

### Automated tests — `./mvnw clean verify` → **BUILD SUCCESS, 187 tests, 0 failures** (M1: 147)

New or extended test classes:
- `BootstrapKeyRotationIntegrationTest` (11): hand-over, single use, concurrent use (exactly one success), no content access, ceiling, immediate ceiling reduction, expiry bounds and header, overlap rotation, scope requirement, cross-tenant revoke, audit.
- `KnowledgeBaseRestrictedKeyIntegrationTest` (6).
- `JwksRotationIntegrationTest` (5): rotation in, rotation out after cache expiry, unknown-kid burst bounded, outage → 503, weak key ignored.
- `TenantAwareJpaTransactionManagerIntegrationTest` (4), `RlsCoverageIntegrationTest` (2), `ApiKeyAuthenticatorTest` (3).
- Extended: `RowLevelSecurityIntegrationTest` (+2: key-table policies and DB CHECKs), `KnowledgeBaseApiIntegrationTest` (+1: concurrent duplicates), `UserAssertionIntegrationTest` (+1: audit attribution), `TrustConfigurationValidationTest` (+1: weak RSA), `KnowledgeBaseAccessPolicyTest` (+2), `ArchitectureRulesTest` (+1 rule).
- The timing and concurrency test classes were rerun twice more: stable.

### Real HTTP checks (local profile; V5 applied to the existing M1 demo database)

```text
POST /api/v1/admin/integrations (allowedScopes=[KB_READ,KB_MANAGE,DOCUMENT_UPLOAD])  → 201
POST /api/v1/admin/integrations/{id}/api-keys (old admin content-key endpoint)      → 405
POST /api/v1/admin/integrations/{id}/bootstrap-keys → 201 keyType BOOTSTRAP, scopes [KEY_ROTATE], expires +24h
GET  /api/v1/knowledge-bases with the bootstrap key                                  → 401
POST /api/v1/integration/api-keys with the bootstrap key → 201 STANDARD, expires +90d, createdBy api-key:<bootstrap>
     (response header X-Api-Key-Expires-At: <bootstrap expiry>)
Same bootstrap key again                                                             → 401 (consumed)
Scope TOOL_EXECUTE (outside the ceiling) → 400; validityDays=400 → 400
Key restricted to 'policies' → lists only 'policies'; X-Api-Key-Expires-At on a 10-day key; creating a KB → 403
Rotation: create successor → revoke old key → old key 401, new key 200
Existing M1 integrations after V5 → allowedScopes backfilled from their keys' scopes ([KB_READ, KB_MANAGE])
```

### Performance (same method as M1: sequential curl, n = 100 after 10 warm-up requests)

| Request path | p50 | p95 | M1 (p50 / p95) |
|---|---|---|---|
| `GET /actuator/health` | 8.6 ms | 10.5 ms | 8.4 / 12.6 ms |
| 401 without credentials | 3.6 ms | 4.6 ms | 3.1 / 4.1 ms |
| `GET /api/v1/integration` (API key) | 12.7 ms | 15.6 ms | 13.2 / 15.9 ms |
| `GET /api/v1/knowledge-bases/{id}` | 12.6 ms | 21.4 ms | 13.1 / 16.0 ms |
| `GET /api/v1/knowledge-bases` (list) | 12.8 ms | 16.8 ms | 13.3 / 16.8 ms |
| JVM working set | 303 MB | | 322 MB |

The differences are within measurement noise. The throttling saves one database round trip per request, but on this machine that's under 1 ms. One p95 outlier (21.4 ms) on a single path; p50 is unchanged.

### Implementation review

- **SOLID / boundaries:**
  - admin key operations (`ApiKeyService`) are separated from self-service ones (`SelfServiceKeyService`);
  - KB restrictions are enforced in one policy (`KnowledgeBaseAccessPolicy`) and the list queries;
  - `access` still depends only on `integration.application`;
  - no new module cycles.
- **Intentional choices:**
  - `integration_api_key_kb` is RLS-enabled but not FORCEd (the definer lookup must read it), and is classified as a credential table in the coverage test;
  - `KEY_ROTATE` is implicit in every ceiling (every integration must be able to rotate).
- **Technical debt:**
  - rate limiting is still open (M2);
  - an integration could in principle modify columns of its own key rows through SQL (RLS allows own-row updates; JPA marks them non-updatable). Database CHECKs bound expiry and scopes, and the effective scopes are clamped at authentication by the ceiling;
  - the Mockito JDK warning remains.

---

## M1 — Integration and Knowledge Base foundation (identity, RLS)

**Date:** 2026-11-23 · **Decisions:** D-16 to D-19 in `docs/roadmap.md` · ADR-0009, ADR-0010

### Delivered

- **Database (Flyway V2–V4):**
  - `integration`, `integration_api_key` (SHA-256 of the secret only), `integration_trust_config`;
  - `knowledge_base`, `knowledge_base_role_grant` (composite tenant FK);
  - `audit_event` (append-only);
  - RLS on every table: tenant content is FORCEd with no admin exception; platform tables are admin-write-only;
  - SECURITY DEFINER functions for the pre-tenant key lookup.
- **`shared`:**
  - `AccessContext` / `AccessContextHolder` / `Permission`;
  - `TenantAwareJpaTransactionManager`, which sets `app.integration_id` and `app.access_plane` per transaction;
  - `AuditRecorder` (records in the same transaction, or independently for denials);
  - `@CurrentAccess`, `PageQuery` / `PageResponse`, and new error types (404 / 403 / 409 / 400) in the RFC 9457 format.
- **`integration`:** register, list and suspend integrations; issue, list and revoke API keys; trust configuration with policy validation; `ApiKeyAuthenticator` (constant-time comparison, identical rejections).
- **`knowledgebase`:**
  - create, list, get and update KBs (optimistic locking; archive via status);
  - role grants with replace-set semantics;
  - `KnowledgeBaseAccessPolicy` (scopes for services; grants ∩ scopes for users; nothing for admins).
- **`access`:**
  - three path-scoped filter chains (admin / integration / default);
  - `X-Admin-Key`, `X-Api-Key` and `X-User-Assertion` authentication;
  - RS256/ES256 JWKS verifier with strict validators, a per-integration decoder cache, and JWKS fetching with timeouts and no redirects.
- **Dev tooling (test scope):** `DevAssertionTool` (generate keys, mint tokens).

### Acceptance criteria

| Criterion | Result | Evidence |
|---|---|---|
| Create an integration and multiple KBs | ✅ Pass | `KnowledgeBaseApiIntegrationTest`; curl demo (HRIS: 2 KBs, CRM: 1) |
| An integration cannot access another integration's KBs | ✅ Pass | `CrossTenantIsolationIntegrationTest` (get / patch / grants / list); `RowLevelSecurityIntegrationTest` (database level) |
| Invalid or unauthorized IDs do not leak data | ✅ Pass | Foreign and unknown IDs return byte-identical 404 bodies; malformed IDs → 400; all authentication failures return an identical 401 |
| Integration/KB lifecycle and ownership covered by integration tests | ✅ Pass | 147 tests total, listed below |

### Quality gates

| Gate | Result | Details |
|---|---|---|
| Functional | ✅ | curl demo below |
| Security | ✅ | Plane separation; API-key lifecycle (revoked, expired, suspended); 13 invalid user-assertion variants (HS256, `none`, issuer, audience, expired, lifetime, missing sub/exp, future iat, unpublished key, garbage, oversized, bad roles type); token signed by another integration rejected; https-only JWKS and no private keys; secrets never returned or logged |
| Cross-tenant isolation | ✅ | API-level and SQL-level tests, including "no tenant context ⇒ 0 rows" and `WITH CHECK` on cross-tenant writes |
| Performance | ✅ | See below |
| Error handling | ✅ | 400 validation, oversized page, 404, 403, 409 (duplicate code, stale version), 503 if credential verification cannot reach the database |
| Documentation | ✅ | Roadmap, architecture, security, ADR-0009/0010, README, this file |

### Automated tests — `./mvnw clean verify` → **BUILD SUCCESS, 147 tests, 0 failures**

| Test class | Tests | Covers |
|---|---|---|
| `ApiKeyCredentialTest` | 12 | Key format, uniqueness, hash comparison, malformed input, no secrets in `toString` |
| `KnowledgeBaseAccessPolicyTest` | 5 | Scopes, grants ∩ scopes, manage ⇒ read, admin has nothing |
| `TrustConfigurationValidationTest` | 10 | https only, no credentials in URI, inline keys policy, private-key rejection, algorithms, ranges, claim names |
| `AdminIntegrationApiIntegrationTest` | 12 | Registration, duplicate/invalid, paging limits, one-time plaintext and stored hash, plane separation, audit |
| `IntegrationAuthenticationIntegrationTest` | 12 | Valid, malformed, unknown, wrong secret, revoked, expired, suspended/reactivated, identical bodies, audit |
| `UserAssertionIntegrationTest` | 23 | RS256/ES256, grants, scope intersection, cross-integration tokens, 13 invalid variants, missing trust config, inline keys refused, audit |
| `KnowledgeBaseApiIntegrationTest` | 10 | Create/list/paginate/get/update/archive, permissions, optimistic locking, grants |
| `CrossTenantIsolationIntegrationTest` | 5 | Same codes in two tenants, indistinguishable 404, list isolation, no foreign writes |
| `RowLevelSecurityIntegrationTest` | 8 | Zero rows without context, own rows only, cross-tenant writes blocked, platform tables admin-write-only, admins have no content access, append-only audit, definer lookup |
| M0 suites (architecture rules now 13) | 50 | As in M0, plus new ArchUnit rules (domain layering, `access` → `integration.application` only, JOSE confined to `access`) |

### Real HTTP checks (local profile, Docker Compose PostgreSQL, migrations V2–V4 applied to the existing M0 database)

```text
POST /api/v1/admin/integrations (X-Admin-Key)            → 201 {"code":"hris-28269","status":"ACTIVE",...}
POST /api/v1/admin/integrations/{hris}/api-keys          → 201 {"key":{"keyId":"50c2…","scopes":["KB_READ","KB_MANAGE"],...},
                                                                "plaintextKey":"mrk_50c2…_<shown once>"}
POST /api/v1/knowledge-bases (HRIS key) ×2, (CRM key) ×1 → 201 … "createdBy":"api-key:50c2…","yourPermissions":["KB_MANAGE","KB_READ"]
GET  /api/v1/knowledge-bases (HRIS key)                  → totalItems 2: hr-policies, benefits
GET  /api/v1/knowledge-bases/{CRM kb} (HRIS key)         → 404 RESOURCE_NOT_FOUND
PUT  /api/v1/knowledge-bases/{hr-policies}/role-grants   → {"grants":[{"role":"hr-reader","permission":"KB_READ"}]}
PUT  /api/v1/admin/integrations/{hris}/trust-config      → 200 (inline JWKS from DevAssertionTool, local profile)
GET  /api/v1/knowledge-bases (key + assertion emp-1001, roles=[hr-reader]) → totalItems 1: hr-policies, yourPermissions ["KB_READ"]
GET  /api/v1/knowledge-bases/{hr-policies} (assertion emp-2002, roles=[sales]) → 404
HRIS assertion presented with the CRM key                → 401
Tampered assertion                                       → 401
DELETE …/api-keys/{keyId}, then reuse that key           → 200, then 401 AUTHENTICATION_REQUIRED
Admin key on /api/v1/knowledge-bases; CRM key on /api/v1/admin/integrations → 401; 401
```

Demo commands for reproduction:

```bash
./mvnw -q test-compile dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile=cp.txt
java -cp "$(cat cp.txt);target/test-classes;target/classes" com.mimocodes.mimorag.devtools.DevAssertionTool keygen ./.dev-issuer
java -cp "$(cat cp.txt);target/test-classes;target/classes" com.mimocodes.mimorag.devtools.DevAssertionTool \
     mint ./.dev-issuer https://hris.local mimorag emp-1001 hr-reader
```

(Use `:` instead of `;` as the classpath separator on Linux/macOS. Keep `.dev-issuer/` out of Git; it contains a private key.)

### Performance (sequential curl, n = 100 after 10 warm-up requests, local profile)

| Request path | p50 | p95 | M0 baseline (p50 / p95) |
|---|---|---|---|
| `GET /actuator/health` | 8.4 ms | 12.6 ms | 9.5 / 11.2 ms |
| 401 without credentials | 3.1 ms | 4.1 ms | 3.4 / 4.0 ms |
| `GET /api/v1/integration` (API key + 1 RLS transaction) | 13.2 ms | 15.9 ms | — |
| `GET /api/v1/knowledge-bases/{id}` (service) | 13.1 ms | 16.0 ms | — |
| `GET /api/v1/knowledge-bases` (list, service) | 13.3 ms | 16.8 ms | — |
| `GET /api/v1/knowledge-bases/{id}` (API key + JWT, warm key cache) | 20.3 ms | 24.8 ms | — |
| `GET /api/v1/knowledge-bases` (list, API key + JWT) | 20.0 ms | 24.7 ms | — |
| 401 for an unknown API key (lookup + dummy hash) | 8.8 ms | 12.8 ms | — |
| `GET /api/v1/admin/integrations` (page of 20) | 10.3 ms | 12.9 ms | — |
| Startup (V2–V4 migrations included) | 12.4 s | | 9.8 s |
| JVM working set after the run | 322 MB | | 254 MB |
| PostgreSQL container memory | 48 MiB | | 54 MiB |

**Analysis:**
- API-key authentication plus the tenant-scoped transaction adds about 10 ms over an unauthenticated request. That covers the credential lookup, `set_config`, the query, and the audit-free path.
- JWT verification adds about 7 ms (RSA signature check and the trust-configuration read).
- Health and unauthenticated paths are unchanged from M0.

### Implementation review

1. **Clean Code / SOLID:**
   - Thin controllers; use cases in application services that receive `AccessContext` explicitly.
   - Small policy classes: `KnowledgeBaseAccessPolicy`, `ApiKeyCredential`, the decoder factory.
   - `IntegrationAuthenticator` and `UserAssertionVerifier` are replaceable, and the core never sees tokens.
   - Ports are narrow repository interfaces.
2. **Intentional deviations:**
   - JPA annotations in `domain` (ADR-0009).
   - Application views are returned directly as response bodies.
   - `TrustConfigurationService` uses Jackson (not Nimbus) to sanity-check inline JWKS, because JOSE libraries are confined to `access`.
3. **Module dependencies:** `access → integration.application, shared`; `integration → shared`; `knowledgebase → shared`. No cycles (ArchUnit).
4. **Tests:** 147 tests, listed above.
5. **Technical debt / follow-ups:**
   - Rate limiting is not yet applied to authentication failures (M2+).
   - Unknown-key failures are logged but not audited, deliberately to avoid audit flooding. Revisit together with rate limiting.
   - The JWKS decoder cache is unbounded (one entry per integration, acceptable for now).
   - No `jti` replay cache yet; add with the first write operation that accepts user assertions.
   - The Mockito self-attach warning on JDK 24 remains.

### Known limitations

- No document upload, extraction or review yet (M2/M3).
- API keys may be issued without expiry (maximum 730 days when set). See Q-06.
- Q-01 is still open: connected systems must publish RS256/ES256 JWKS before user-scoped production use.

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
