# ADR-0004: Tenant isolation with Row-Level Security

- **Status:** Accepted
- **Date:** 2026-11-23

## Context

HRIS, CRM, ERP and future integrations must be fully isolated. Isolation must cover documents, pages, chunks, vectors, tools, executions, conversations, caches and background jobs. A single forgotten `WHERE integration_id = ?` must not leak data.

## Decision

Defense in depth:

1. **Tenant source:** the tenant (`integration_id`) comes only from the verified service credential ([ADR-0005](0005-identity-and-access-model.md)). It never comes from paths, bodies, headers or the LLM.
2. **Application:** services and repositories take the tenant from `AccessContext`. There is no tenant-less lookup. A resource from another tenant returns 404.
3. **Database:** every tenant-owned table has `integration_id NOT NULL`, composite indexes led by it, and **RLS policies** that compare it with `current_setting('app.integration_id')`. The setting is applied with `SET LOCAL` in every transaction, from M1 onward.
4. **Storage, caches, vectors, jobs and events** all carry and enforce the tenant.
5. **Tests:** cross-tenant tests in every milestone, including "no tenant set ⇒ zero rows".

## Consequences

- Platform-wide admin operations (e.g. listing integrations) work only on non-tenant tables. Admin actions on tenant data set the tenant explicitly and are audited.
- A small per-transaction cost for `SET LOCAL`, to be measured in M1.
- Connection pooling stays safe, because `SET LOCAL` is scoped to the transaction.
