# Architecture Decision Records

Each ADR records one decision: its context, the decision, and its consequences. ADRs are never rewritten after acceptance. A changed decision gets a new ADR that supersedes the old one.

| ADR | Title | Status |
|---|---|---|
| [0001](0001-modular-monolith-and-module-boundaries.md) | Modular monolith and module boundaries | Accepted |
| [0002](0002-provider-abstractions-and-stable-dependencies.md) | Own provider abstractions; stable dependencies only | Accepted |
| [0003](0003-postgresql-flyway-and-database-roles.md) | PostgreSQL, Flyway and separated database roles | Accepted |
| [0004](0004-tenant-isolation-and-row-level-security.md) | Tenant isolation with Row-Level Security | Accepted |
| [0005](0005-identity-and-access-model.md) | Identity and access model | Accepted |
| [0006](0006-tenant-owned-document-review.md) | Tenant-owned document review with immutable snapshots | Accepted |
| [0007](0007-async-jobs-and-transactional-outbox.md) | Async jobs and transactional outbox in PostgreSQL | Superseded by 0011 |
| [0008](0008-api-error-format-correlation-and-logging.md) | API error format, correlation IDs and logging | Accepted |
| [0009](0009-jpa-annotations-in-domain.md) | JPA annotations allowed in domain classes | Accepted (amends 0001) |
| [0010](0010-credentials-and-database-enforced-isolation.md) | Credential formats and database-enforced isolation | Accepted (issuance amended by 0012) |
| [0011](0011-postgresql-job-queue-without-outbox.md) | PostgreSQL job queue without an outbox table | Accepted (implemented in M2) |
| [0012](0012-api-key-lifecycle-and-restrictions.md) | API key lifecycle: bootstrap hand-over, self-service rotation, scope ceiling, KB restrictions | Accepted |
| [0013](0013-native-text-extraction-line-builder.md) | Native PDF text through our own right-to-left-aware line builder | Accepted (M2 slice 2c) |

Template: `# ADR-NNNN: Title` → **Status**, **Date**, **Context**, **Decision**, **Consequences**.
