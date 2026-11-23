# ADR-0005: Identity and access model

- **Status:** Accepted
- **Date:** 2026-11-23

## Context

Connected systems already authenticate and authorize their own users. MimoRag must work with many systems and identity providers without coupling its core to any of them. Platform administration is a separate concern.

## Decision

- **No end-user login in MimoRag.** End-user authentication is owned by the connected systems.
- **Integration plane:**
  - **Service credential:** required on every call; it alone determines the tenant. M1 uses per-integration API keys, stored as SHA-256 hashes, rotatable and revocable.
  - **Signed user assertion:** a JWT, required when an operation acts for a user. In production, **RS256 or ES256 only**, verified against the integration's trusted **JWKS** endpoint, with strict `iss`, `aud`, `exp` (and lifetime cap) and signature checks. The assertion must belong to the **same** integration as the service credential. No HS* algorithms in production, and bare user-ID headers are never accepted.
  - **Development verifier:** separate, local/test profiles only, and not enableable in production configuration.
- **Edge only:** the `access` module is the only identity-provider-aware code. It produces an immutable `AccessContext` (integration, principal type, external user ID, opaque roles and groups, auth method, correlation ID).
- **Core modules** authorize on `AccessContext` and platform permissions only. Permission checks run in application services.
- **Service identities** for system-to-system and background work have narrow permissions, explicit tenant and KB boundaries, and audit logging, and they do not bypass RLS. Search or chat without a user is **disabled by default** and needs a per-KB opt-in.
- **Platform admin plane:** separate paths and filter chain. M1 uses a bootstrap admin API key; OIDC or a company identity provider comes later. Admins have **no default access to tenant content**. There is no platform reviewer role: review belongs to tenants ([ADR-0006](0006-tenant-owned-document-review.md)).

## Consequences

- New mechanisms (OAuth2 client credentials, mTLS, token exchange, a different identity provider) are new authenticator or verifier implementations. Core code does not change.
- Each integration must register its issuer, audience, JWKS URL and claim mapping before user-scoped features work.
- Connected systems that cannot sign RS256/ES256 tokens must add that capability before they can use user-scoped production features.
