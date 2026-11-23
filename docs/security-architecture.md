# MimoRag — Security Architecture

> Status: **design baseline** (M0, 2026-11-23), **identity and tenant isolation implemented in M1** (see §8). Everything else is implemented in the milestone named next to it. M1 implementation details: [ADR-0010](adr/0010-credentials-and-database-enforced-isolation.md).
> Decisions: [ADR-0004](adr/0004-tenant-isolation-and-row-level-security.md), [ADR-0005](adr/0005-identity-and-access-model.md), [ADR-0006](adr/0006-tenant-owned-document-review.md).

Guiding principle: **the platform answers only within the caller's permissions and the data available to them, and refuses when evidence is insufficient.** Authorization is always decided by platform code, never by an LLM.

---

## 1. Trust boundaries

```text
 Untrusted ─────────────────────────────────────────────────────────────────────────────
   • HTTP input from any caller            • uploaded files (PDF bytes, metadata, hidden text)
   • extracted and human-edited text       • tool definitions and tool output
   • LLM output (tool choice, arguments, answers)
 Trusted only after verification ───────────────────────────────────────────────────────
   • integration identity  ← verified service credential
   • end-user identity     ← verified signed assertion bound to that integration
   • admin identity        ← verified admin credential (separate plane)
 Trusted ───────────────────────────────────────────────────────────────────────────────
   • platform code, configuration, system prompts, database policies (RLS)
```

## 2. Identity and access model (M1)

### 2.1 Two planes

| | Integration plane | Platform admin plane |
|---|---|---|
| Who | Connected systems (HRIS, CRM, ERP, …) acting for themselves or on behalf of their users | MimoRag platform administrators |
| Paths | `/api/v1/**` (excluding `/api/v1/admin/**`) | `/api/v1/admin/**` |
| Credentials | Integration service credential (+ optional/required user assertion) | Admin credential |
| Roles / permissions | Opaque integration roles mapped to platform permissions per KB | `PLATFORM_ADMIN` |
| Cross-acceptance | Admin credentials rejected | Integration credentials rejected |

Each plane is a separate, path-scoped Spring Security filter chain.

### 2.2 End-user authentication is owned by the connected systems

MimoRag **does not** log users in, store passwords, manage sessions or issue user tokens. The model:

1. The connected system authenticates its user with its own mechanism.
2. It calls MimoRag with:
   - a **service credential** that proves *which integration* is calling. This is required on every call and **alone determines the tenant**;
   - a **signed user assertion** (JWT) that states *on whose behalf* the call is made, when the operation requires a user.
3. `access` verifies both and builds an immutable `AccessContext`.
4. Core modules authorize using `AccessContext` only. They never see tokens, keys, issuers or HRIS concepts.
5. The connected system **remains responsible for its own business authorization** (for example, whether an employee may see a given leave balance). MimoRag enforces *platform* authorization: tenant, KB, document, tool and permission.

### 2.3 Integration service credentials

- **M1: API keys.** Format `mrk_<keyId>_<secret>`, where the secret is at least 256 bits of CSPRNG output.
- **Storage:** `keyId`, a SHA-256 hash of the secret, `last4`, status, creation and expiry time, and last use. The secret is shown once, at issuance. Comparison is constant-time.
- **Lifecycle (M1 hardening, [ADR-0012](adr/0012-api-key-lifecycle-and-restrictions.md)):**
  - admins issue only **bootstrap keys** (scope `KEY_ROTATE` only, at most 24 hours, single use), which authenticate solely for creating the integration's first own key;
  - integrations create, rotate and revoke **their own** keys within an admin-defined **scope ceiling**;
  - expiry is mandatory (default 90 days, maximum 365), several keys can be active at once, and responses warn in the last 14 days;
  - keys can be restricted to specific Knowledge Bases;
  - admins can revoke any key, but can never hold a working content key.
- **Later:** OAuth2 client credentials and mTLS as additional `IntegrationAuthenticator` implementations. Core code does not change.

### 2.4 Signed user assertions

- **Production:** **RS256 or ES256 only**, verified against the integration's **trusted JWKS endpoint** (HTTPS, cached with bounded TTL, key rotation supported).
- **Required claims and checks:**
  - `iss` must equal the integration's registered issuer exactly;
  - `aud` must contain the platform audience registered for that integration;
  - `exp` must be present, and the token's lifetime must not exceed the configured maximum (e.g. 5 minutes);
  - `iat` and `nbf` are honoured, with bounded clock skew;
  - `sub` is the external user ID;
  - `alg=none` and HS* algorithms are rejected.
- **Binding:** the assertion must verify against keys registered to **the same integration** that authenticated with the service credential. A CRM-signed token presented with HRIS credentials is rejected.
- **Claim mapping:** each integration configures where to read user ID, roles and groups from. The values are **opaque strings** to the platform.
- **Replay:** tokens are short-lived. For write operations a `jti` replay cache can be enabled per integration.
- **Development:** a separate development-only verifier (e.g. locally generated keys) exists only in the `local`/`test` profiles and **cannot be enabled in production configuration**.
- **Never accepted:** a bare user-ID header, or a user ID inside a request body.

### 2.5 AccessContext

```java
record AccessContext(
    UUID integrationId,                // tenant: only from the verified service credential
    PrincipalType principalType,       // INTEGRATION_SERVICE | END_USER | PLATFORM_ADMIN
    Optional<String> externalUserId,   // from the verified assertion
    Set<String> roles, Set<String> groups,  // opaque, via the integration's claim mapping
    AuthMethod authMethod,             // API_KEY, JWT_JWKS, ADMIN_KEY, ...
    String correlationId)
```

- Built only by `access`. **The LLM can never create or modify it.**
- Permission checks happen in **application services**, not only in controllers. Planned permissions: `KB_READ`, `KB_MANAGE`, `DOCUMENT_UPLOAD`, `DOCUMENT_REVIEW`, `DOCUMENT_SUBMIT`, `TOOL_EXECUTE`, `TOOL_CATALOG_MANAGE`.
- KB policies map opaque integration roles to permissions (e.g. "`hr-policy-editors` → `DOCUMENT_REVIEW`").
- **Effective permissions of an end user = KB role grants ∩ API-key scopes** (M1, [ADR-0010](adr/0010-credentials-and-database-enforced-isolation.md)). A narrowly scoped key therefore cannot carry broader user rights.

### 2.6 Service identities (system-to-system and background work)

- These are dedicated identities with **narrow permissions** and explicit tenant and KB boundaries.
- They never bypass tenant isolation or RLS, and every action is audited.
- Searching or chatting **without a user assertion** is **disabled by default**. It needs an explicit per-KB opt-in.
- Background jobs store a snapshot of the `AccessContext` when created, run with the same tenant settings, and re-check that the integration and KB are still active and permitted before executing.

### 2.7 Platform administration

- **M1:** a bootstrap admin API key from an environment variable, compared by hash in constant time and never logged.
- **Later:** OIDC or a company identity provider behind `AdminAuthenticator`.
- **No default access to tenant content.** Platform admins manage integrations, keys, trust settings, tool publishing and evaluations; they **cannot read or edit tenant documents** by default. A time-boxed, audited support-access mode may be added later if needed.

### 2.8 Future: embeddable review UI session tokens (after M4)

The connected system's backend exchanges its credentials plus a user assertion for a **review session token**:

- short-lived (minutes);
- scoped to **one tenant, one document and one authenticated user**;
- limited to review permissions, with an unguessable `jti` and replay protection;
- bound to the allowed embedding origin;
- revoked when the document is submitted.

The review business logic, authorization and validation remain in the `review` module, independent of the frontend.

## 3. Tenant isolation (M1 onward, in every milestone)

Isolation is enforced in layers, so a single bug does not leak data.

| Layer | Mechanism |
|---|---|
| Identity | Tenant comes only from the verified service credential. It never comes from a path, body, header or the LLM. |
| Application | Repository and service methods take the tenant from `AccessContext`. There is no "find by ID" without a tenant. Path IDs from another tenant return 404. |
| Database | `integration_id NOT NULL` on every tenant-owned table, with composite indexes starting with it. **Row-Level Security** policies use `current_setting('app.integration_id')`, set with `SET LOCAL` in every transaction. The runtime role is not the table owner and has `NOBYPASSRLS`. Tests prove that a query with no tenant set returns **zero rows**. |
| Storage | Object keys are `integration/{id}/kb/{id}/document/{id}/version/{id}/…`. Files are served only through authorized endpoints. |
| Vectors | Every vector query carries mandatory integration and KB filters (and RLS on the chunk tables). |
| Caches | Every cache key includes the integration ID (and KB/user where relevant). |
| Jobs and events | Jobs, outbox events and ingestion runs carry `integration_id`. Workers set the RLS context before touching data. |
| Tools | Tools belong to one integration. Executions resolve the base URL and credentials from that integration only. |
| Conversations and feedback | Scoped to integration and user. |
| Tests | Cross-tenant tests are required in every milestone's quality gate. |

## 4. AI safety

### 4.1 Prompt injection (direct and indirect)

- **Prompt structure:** system instructions are fixed in code and configuration, and kept in the system role. Retrieved chunks and tool output go in a separate, clearly delimited **data** section, labelled as untrusted quoted material.
- **Approved content is still data.** Content a company approved in review is still untrusted: a reviewer could paste instructions into a page.
- **Detection at extraction (M2):** we flag white, tiny or off-page text, hidden layers, suspicious metadata, invisible Unicode and **bidirectional override characters**, which matter for mixed Arabic/English text. Flagged pages need explicit human resolution.
- **No authority from content.** Documents and tool output can never change policies, permissions, tool availability, tenant or user.
- **Tests:** injection tests are part of the quality gates in M2 (extraction), M5 (answers) and M8 (tools).

### 4.2 Grounding and abstention (M5)

- Answers use **only** retrieved, authorized context. The LLM's general knowledge is never a substitute for system data.
- **Abstention triggers:** no evidence, or retrieval scores below a calibrated threshold.
- **Validation:** answers are checked claim by claim against the cited sources; unsupported claims cause rejection or abstention.
- **Citations:** every answer carries document, page and chunk citations.
- **Language:** the answer uses the user's language when possible, without fabricating translations of facts.

### 4.3 Tools (M6–M8)

- **Tool definitions are data,** never code. They are validated (name, description, JSON Schema, method, relative path, output limits) and versioned, and admins must explicitly publish them.
- **Exposure to the model:** only tools that are published, enabled and authorized for the current user and context.
- **Before execution:** arguments are validated against the schema again. The **platform** checks authorization, independently of the LLM's choice.
- **Outbound calls:** go only to the integration's trusted base URL plus a validated relative path. There is SSRF protection (allowlist, no private or link-local IPs unless explicitly configured, no redirects), plus timeouts, response-size limits and output validation.
- **Write and destructive tools:** need explicit confirmation according to policy. Retries are never automatic for non-idempotent writes.
- **Budgets:** maximum tool calls per turn, total execution time and token budgets, with loop prevention.
- **Failures:** tool errors are reported as errors. The Agent must not invent results.

## 5. Input, file and resource protection

- **Validation:** Bean Validation at the boundary, size limits on every text field and request, and pagination limits.
- **Uploads (M2):**
  - file type is checked by content (magic bytes plus a parser check), not by extension;
  - size, page-count and resolution limits apply;
  - encrypted and corrupt files are rejected;
  - parser time and memory are bounded, with protection against decompression bombs;
  - OCR runs in a separate container.
- **Rate limits and quotas** per integration and per user: uploads, OCR pages, embeddings, chat turns, tool calls (M2 onward; full quotas in M11).
- **SQL injection:** parameterized queries only. The LLM never produces SQL.
- **XSS:** the API returns JSON only, with `X-Content-Type-Options: nosniff` and `X-Frame-Options: DENY`. The review UI milestone adds a CSP and controlled embedding (`frame-ancestors`).

## 6. Secrets

- Secrets come from environment variables or a secret manager, never from the repository. Database records hold only **secret references** or hashes.
- `.env` is git-ignored. `.env.example` contains placeholders only.
- Secrets never appear in logs, API responses, error bodies, health output or audit payloads.

## 7. Audit

These events are audited, as append-only records with integration, principal, action, target, outcome, correlation ID and timestamp, and without sensitive payloads:

- credential and trust-configuration changes;
- KB policy changes;
- uploads, page edits, exclusions, submissions and re-ingestion;
- tool registration, publishing and execution;
- admin actions.

## 8. Implemented in M0

| Control | Where | Verified by |
|---|---|---|
| Deny-by-default; only `health` and `info` public | `access.web.SecurityConfiguration` | `SecurityBaselineIntegrationTest` |
| Stateless, no CSRF/session/cookies, no generated default user | same, plus `application.yml` | `SecurityBaselineIntegrationTest` |
| 401 identical for existing and non-existing protected paths | same | `SecurityBaselineIntegrationTest` |
| Security headers (nosniff, frame DENY, no-store) | Spring Security defaults | `SecurityBaselineIntegrationTest` |
| Error responses without internals or echoed input | `shared.web.GlobalExceptionHandler` | `GlobalExceptionHandlerTest` |
| Correlation IDs validated against injection | `shared.observability.CorrelationIdFilter` | `CorrelationIdFilterTest` |
| Least-privilege database role (no DDL, `NOBYPASSRLS`, no access to migration history) | `docker/postgres/initdb`, `V1__baseline.sql` | `DatabaseRolesIntegrationTest` |
| No secrets in repo; fail fast on missing settings, reporting names only | `.gitignore`, `.env.example`, `RequiredSettingsVerifier` | `RequiredSettingsVerifierTest` |
| Health shows no internal details anonymously | `application.yml` | `HealthEndpointIntegrationTest` |

### Implemented in M1

| Control | Where | Verified by |
|---|---|---|
| Separate admin and integration planes; each recognises only its own credential | `access.web.SecurityConfiguration` | `AdminIntegrationApiIntegrationTest` |
| Admin key stored only as SHA-256, constant-time comparison; plane disabled when unset | `AdminKeyAuthenticationFilter`, `AccessSecurityProperties` | `AdminIntegrationApiIntegrationTest` |
| API keys: 256-bit secrets, only the hash stored, plaintext shown once, revocation, expiry, suspension | `integration.domain.ApiKeyCredential`, `ApiKeyAuthenticator` | `ApiKeyCredentialTest`, `IntegrationAuthenticationIntegrationTest` |
| Identical 401 for every authentication failure; reason only in logs and audit | `IntegrationAuthenticationFilter` | `IntegrationAuthenticationIntegrationTest` |
| Pre-tenant key lookup without relaxing RLS (SECURITY DEFINER) | `V2__integrations_and_credentials.sql` | `RowLevelSecurityIntegrationTest` |
| User assertions: RS256/ES256 only, JWKS from the same integration, strict claims, lifetime cap, size limit | `access.assertion.*` | `UserAssertionIntegrationTest` (13 invalid-token variants) |
| Trust configuration: https JWKS only in production, no private keys, inline keys only in local | `TrustConfigurationService` | `TrustConfigurationValidationTest` |
| Tenant from credential only; tenant-implicit URLs; foreign IDs return the same 404 as unknown IDs | `KnowledgeBaseService` | `CrossTenantIsolationIntegrationTest` |
| User permissions = KB role grants ∩ API-key scopes; admins have no tenant content access | `KnowledgeBaseAccessPolicy` | `KnowledgeBaseAccessPolicyTest`, `UserAssertionIntegrationTest` |
| PostgreSQL RLS: no context ⇒ zero rows; `WITH CHECK` blocks cross-tenant writes; composite tenant FKs | `V3__knowledge_bases.sql`, `TenantAwareJpaTransactionManager` | `RowLevelSecurityIntegrationTest` |
| Append-only audit trail (UPDATE/DELETE revoked) for admin actions, KB changes, denials, auth failures | `V4__audit_events.sql`, `JdbcAuditRecorder` | `AdminIntegrationApiIntegrationTest`, `RowLevelSecurityIntegrationTest` |
| Optimistic locking on KB updates | `KnowledgeBase` `@Version` | `KnowledgeBaseApiIntegrationTest` |

### Implemented in the M1 hardening pass

| Control | Where | Verified by |
|---|---|---|
| Admins cannot create content keys; bootstrap keys are rotation-only, 24 h, single use (concurrent use: exactly one succeeds) | `ApiKeyService`, `SelfServiceKeyService`, `IntegrationAuthenticationFilter`, V5 CHECKs and RLS policies | `BootstrapKeyRotationIntegrationTest`, `RowLevelSecurityIntegrationTest` |
| Scope ceiling per integration, applied at every authentication | `ApiKeyAuthenticator`, `Integration.grantableScopes` | `ApiKeyAuthenticatorTest`, `BootstrapKeyRotationIntegrationTest` |
| Mandatory key expiry (≤ 365 days) enforced in code and database; expiry warning header | `ApiKey`, V5 | `BootstrapKeyRotationIntegrationTest`, `RowLevelSecurityIntegrationTest` |
| KB-restricted keys for services and relayed users; no escalation through rotation | `KnowledgeBaseAccessPolicy`, `SelfServiceKeyService`, `integration_api_key_kb` | `KnowledgeBaseRestrictedKeyIntegrationTest`, `KnowledgeBaseAccessPolicyTest` |
| Context cannot switch inside a transaction; failed tenant setup releases all resources | `AccessContextHolder`, `TenantAwareJpaTransactionManager` | `TenantAwareJpaTransactionManagerIntegrationTest` |
| Only `access`, `shared.persistence` and the job runner may bind an access context | ArchUnit | `ArchitectureRulesTest` |
| Every table classified; tenant tables FORCE RLS; every RLS table has policies | Database guard | `RlsCoverageIntegrationTest` |
| Audit rows name the authenticating (or relaying) API key | `JdbcAuditRecorder`, `audit_event.api_key_id` | `UserAssertionIntegrationTest`, `BootstrapKeyRotationIntegrationTest` |
| JWKS: bounded cache (5 min), at most one re-fetch per 30 s for unknown key ids, size limit, no redirects, RSA ≥ 2048 bits, outage → 503 | `UserAssertionDecoderFactory`, `UserAssertionProperties` | `JwksRotationIntegrationTest`, `TrustConfigurationValidationTest` |
| Concurrent duplicate creates return 409, not 500 | `saveAndFlush` in services | `KnowledgeBaseApiIntegrationTest` |

**Not yet implemented (planned):** rate limiting and quotas (M2), `jti` replay cache for write operations (M2, with uploads), admin OIDC login (Q-02), audit read API.
