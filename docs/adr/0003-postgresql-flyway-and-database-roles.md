# ADR-0003: PostgreSQL, Flyway and separated database roles

- **Status:** Accepted
- **Date:** 2026-11-23

## Context

We need relational storage, vector search (later) and strong tenant isolation. PostgreSQL's Row-Level Security does not apply to a table's owner (unless `FORCE` is used) or to roles with `BYPASSRLS`. If the application connected as the schema owner, RLS and least privilege would be weakened.

## Decision

- **Database:** PostgreSQL 17, using the `pgvector/pgvector:0.8.7-pg17` image in local development and tests, so M4 needs no second download.
- **Migrations:** Flyway owns all schema changes, and Hibernate runs with `ddl-auto: validate`.
- **Two roles:**
  - `mimorag_owner` owns the `mimorag` schema and runs Flyway;
  - `mimorag_app` is the runtime role: DML only, `NOSUPERUSER`, `NOBYPASSRLS`, no DDL, no access to `flyway_schema_history`.
- **Provisioning:** roles and the schema are created by `docker/postgres/initdb/01-create-roles.sh`. The **same script** is used by Docker Compose and Testcontainers.
- **Local port:** Docker Compose binds to `127.0.0.1:5433` by default, because the developer machine already runs PostgreSQL on 5432.

## Consequences

- RLS can be applied from M1 with a runtime role that cannot bypass it.
- A compromised application connection cannot alter the schema or the migration history.
- Production provisioning must create the equivalent roles (to be documented in M11 runbooks).
- Two database passwords must be managed instead of one.
