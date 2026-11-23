# ADR-0011: PostgreSQL job queue without an outbox table

- **Status:** Accepted (supersedes [ADR-0007](0007-async-jobs-and-transactional-outbox.md)). Implemented in M2.
- **Date:** 2026-11-23

## Context

Extraction, OCR, embedding and indexing are long-running and must survive crashes, retry safely, and stay
tenant-scoped. ADR-0007 proposed a job table **plus** a transactional outbox. An outbox solves exactly one problem:
committing a database change and publishing a message to *another system* (a broker, a webhook) atomically. No
RabbitMQ or Kafka is wanted at this stage.

## Analysis: is an outbox needed?

| Flow | Where the "message" goes | Atomic without an outbox? |
|---|---|---|
| Upload → extraction | `ingestion_job` row, inserted in the same transaction as `document`/`document_version` | **Yes** |
| Extraction → next stage (later indexing) | Worker updates state and inserts the next job in one transaction | **Yes** |
| Notify a connected system (webhook, later) | Modelled as another job type (`DELIVER_WEBHOOK`) in the same table | **Yes** |

Every hand-off stays inside one PostgreSQL database, so **the job table already is the outbox**. A separate outbox
table would add a relay process and a second source of truth without a concrete reliability gain.

## Decision

- **One `job` table, no outbox.**
- **Claiming:** a SECURITY DEFINER function `claim_jobs(kinds, limit, lease)` with `FOR UPDATE SKIP LOCKED`. It is
  the only cross-tenant query and returns job id, tenant and the stored context snapshot.
- **Execution:** handlers run inside `AccessContextHolder.callAs(snapshot)` (a job-runner package allowed by the
  ArchUnit context-binding rule), so all business data access is ordinary RLS-scoped access.
- **Context snapshot:** integration, principal type and reference, relaying API key id, scopes, KB restriction. No
  tokens or secrets.
- **Re-validation before execution:** integration active, KB active, relaying key not revoked/expired; otherwise the
  job ends as `CANCELLED_REVALIDATION`.
- **Lease and timeout:** expired leases are reclaimed; heartbeats extend long work.
- **Retries:** bounded attempts, exponential backoff with jitter, safe error codes.
- **Idempotency:** unique `(integration_id, idempotency_key)`.
- **Bounded worker pools per job kind:** OCR 1, extraction 2 by default; configurable.
- **Polling:** 1–2 s idle poll; LISTEN/NOTIFY is an optional later optimisation.

## Keeping a broker possible later

Business code depends on a `JobQueue` interface (`enqueue` joins the caller's transaction, `claim`, `complete`,
`fail`, `extendLease`) and on `JobHandler<T>` per job kind. `PostgresJobQueue` is the only implementation. Moving to
RabbitMQ/Kafka means a new `JobQueue` implementation; **that** is the point where a transactional outbox becomes
necessary (database change + broker publish), and it would be introduced then, without changing handlers.

## Consequences

- No extra infrastructure; job state changes are transactionally consistent with business data.
- Throughput is bounded by PostgreSQL; acceptable for the MVP, revisited with load tests (M11).
- The claim function is a deliberate, reviewed exception to "RLS everywhere", covered by the RLS-coverage test.
