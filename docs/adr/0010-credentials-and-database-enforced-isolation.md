# ADR-0010: Credential formats and database-enforced isolation (M1 implementation)

- **Status:** Accepted
- **Date:** 2026-11-23
- **Implements:** [ADR-0004](0004-tenant-isolation-and-row-level-security.md), [ADR-0005](0005-identity-and-access-model.md)

## Context

M1 turns the identity and tenant-isolation design into code. Several concrete choices were needed: credential formats and headers, how authentication can find a key before the tenant is known (without weakening RLS), how tenant settings reach PostgreSQL, and how user permissions relate to API-key scopes.

## Decision

### Headers and credentials

| Header | Plane | Content |
|---|---|---|
| `X-Api-Key` | Integration | `mrk_<keyId>_<secret>`. `keyId` = 24 hex characters (public, used in audit); `secret` = 43 base64url characters (256 bits). Only SHA-256(secret) is stored, compared in constant time. A fast hash is sufficient because the secret is random, not a password. |
| `X-User-Assertion` | Integration | A JWT signed RS256 or ES256 by the same integration, verified against its trust configuration (JWKS URI; inline JWKS only in the `local` profile). Checks: `iss`, `aud`, required `exp`, `iat` and user-id claim, `nbf`, bounded skew, `exp − iat ≤` configured maximum. Maximum length 8 KB. |
| `X-Admin-Key` | Admin | Bootstrap admin key. Only its SHA-256 is configured (`MIMORAG_ADMIN_KEY_SHA256`). If unset, the admin plane is disabled. |

Each path-scoped filter chain recognises **only** its own header. Every failure produces the same 401 body; the reason is only logged and audited, together with the key ID (never secrets or tokens).

### Permissions

- **Service identity:** the API-key scopes.
- **End user:** the permissions granted to any of the user's roles on the KB, **intersected with the API-key scopes**. A narrowly scoped key therefore cannot be used to exercise broader user rights.
- `KB_MANAGE` implies `KB_READ`.
- A KB without any effective permission returns 404, exactly like a non-existent ID.
- Creating KBs is reserved for service identities with `KB_MANAGE`.

### Integration-plane URLs are tenant-implicit

`/api/v1/integration` and `/api/v1/knowledge-bases/**`: there is no integration ID in paths, so there is nothing to mismatch or probe.

### How the tenant reaches PostgreSQL

- `TenantAwareJpaTransactionManager` runs `set_config('app.integration_id', …, true)` and `set_config('app.access_plane', …, true)` at the start of every transaction, using `AccessContextHolder`.
- The settings are transaction-local, so they never leak to the next user of a pooled connection.
- With no context, both are empty, and tenant policies return **zero rows** (fail closed).

### RLS policy classes

- **Tenant content** (`knowledge_base`, `knowledge_base_role_grant`; later documents, chunks, …): `ENABLE` and `FORCE` RLS, with tenant equality in `USING` and `WITH CHECK`. **No admin exception.** Composite foreign keys `(id, integration_id)` stop children from pointing at another tenant's parent.
- **Platform tables** (`integration`, `integration_api_key`, `integration_trust_config`): an integration can read its own rows, and only the admin plane can write. `ENABLE` (not `FORCE`).
- **`audit_event`:** append-only (UPDATE, DELETE and TRUNCATE revoked); tenant- or admin-scoped reads; inserts without a tenant are allowed (failed authentication).

### Pre-tenant lookup

Two `SECURITY DEFINER` functions with a fixed `search_path`, executable only by the app role:
- `find_api_key_for_authentication(key_id)` returns exactly one key's verification data;
- `record_api_key_use(key_id, at)` updates the last-use time at most every 5 minutes.

RLS is never relaxed to make authentication work.

## What RLS does and does not protect against

- **Protects against** application defects: a forgotten tenant filter, a wrong ID from a path, a query run without context, or a write with a foreign `integration_id`.
- **Does not protect against** a fully compromised application process. The runtime role sets the session variables itself, so an attacker controlling the process could set them too. Defending against that requires process and credential hardening (M11), not RLS.

## Consequences

- Each transaction adds one small `set_config` round trip. Measured overall: authenticated reads take about 13 ms p50 locally (see `PROJECT_PROGRESS.md`).
- Background work (M2+) must set `AccessContextHolder` from a stored context snapshot before it touches data; otherwise it sees nothing.
- Connected systems must issue RS256/ES256 assertions and publish a JWKS endpoint for user-scoped features (Q-01).
