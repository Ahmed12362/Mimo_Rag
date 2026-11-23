# ADR-0012: API key lifecycle — bootstrap hand-over, self-service rotation, scope ceiling, KB restrictions

- **Status:** Accepted
- **Date:** 2026-11-23
- **Amends:** [ADR-0010](0010-credentials-and-database-enforced-isolation.md) (key issuance)

## Context

The M1 review found that a platform admin could issue a content key for any integration and use it on the
integration plane — effective access to tenant content, contradicting ADR-0005 (H1). Keys could also live forever
(Q-06), and every key reached every Knowledge Base of its integration (M6).

## Decision

### Separation of duties

| Actor | Can | Cannot |
|---|---|---|
| Platform admin | Register integrations and set their **scope ceiling** (`allowedScopes`); issue **bootstrap** keys; list key metadata; revoke any key (incident response) | Create a key that reaches tenant content |
| Integration (key with `KEY_ROTATE`) | Create, list and revoke **its own** keys within the ceiling | Exceed the ceiling, touch other integrations' keys |
| End user (relayed) | — | Manage keys (`KEY_ROTATE` is service-only) |

### Bootstrap keys

- Scope exactly `{KEY_ROTATE}`, expiry ≤ 24 h, **single use**: the first successful key creation revokes it in the
  same transaction (optimistic lock: of concurrent attempts exactly one succeeds, others get 409).
- Authenticates **only** for `POST /api/v1/integration/api-keys` and never with a user assertion; everywhere else it
  is treated as invalid (401). It can never reach tenant content.

### Standard keys

- Created by the integration: `POST /api/v1/integration/api-keys` (`name`, `scopes`, `validityDays` 1–365, default
  90, optional `knowledgeBaseIds`). Plaintext returned once.
- **Expiry is mandatory** (≤ 365 days). Several keys can be active at once, so rotation is overlap-based: create the
  successor, switch, revoke the old key (`DELETE /api/v1/integration/api-keys/{keyId}`).
- Responses carry `X-Api-Key-Expires-At` during the last 14 days of a key's life.

### Scope ceiling

- `integration.allowed_scopes` (admin-managed); `KEY_ROTATE` is always grantable and never stored there.
- Effective scopes at authentication = key scopes ∩ (ceiling ∪ `KEY_ROTATE`), so lowering the ceiling takes effect
  immediately for existing keys.

### Knowledge-Base-restricted keys

- Optional `knowledgeBaseIds`; stored in `integration_api_key_kb` with composite tenant foreign keys (another
  tenant's KB is indistinguishable from a non-existent one → 400).
- Enforced server-side for the service identity **and** relayed end users: KBs outside the restriction are
  invisible (404); restricted keys cannot create KBs (403); a restricted rotator can only create or manage keys
  restricted to a subset of its own KBs.

### Database enforcement

- CHECKs: `expires_at ≤ created_at + 365 days`; bootstrap keys `scopes = {KEY_ROTATE}` and ≤ 24 h; valid scope names.
- RLS: integrations insert/update only their **own STANDARD** keys; admins insert only **BOOTSTRAP** keys and may
  update (revoke) any; no deletes.
- Audit events: `BOOTSTRAP_KEY_ISSUED`, `BOOTSTRAP_KEY_CONSUMED`, `API_KEY_CREATED`, `API_KEY_REVOKED`,
  `INTEGRATION_ALLOWED_SCOPES_CHANGED`; every audit row records the authenticating `api_key_id`.

## Consequences

- An admin can still *revoke* or *suspend* (availability), and define what an integration may do (policy), but never
  holds a working content credential.
- Onboarding is a two-step hand-over (admin → bootstrap key → integration creates its key within 24 h).
- Integrations must automate rotation before keys expire (≤ 365 days).
- Residual risk: a compromised admin could lower or raise the ceiling and issue a bootstrap key that the attacker
  then exchanges. This is detectable in the audit trail (`BOOTSTRAP_KEY_ISSUED` + `BOOTSTRAP_KEY_CONSUMED` by an
  unexpected party) and is addressed fully only with named admin identities (Q-02).
