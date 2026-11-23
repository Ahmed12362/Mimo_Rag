# ADR-0007: Async jobs and transactional outbox in PostgreSQL

- **Status:** Proposed. To be confirmed or revised at the start of M2.
- **Date:** 2026-11-23

## Context

Extraction, OCR, embedding and indexing are long-running. Steps must survive crashes, be retryable without duplication, and stay tenant-scoped. The development machine has limited RAM and disk, so an extra broker container is a cost.

## Decision (proposed)

- **Job table:** a PostgreSQL job table (type, tenant, payload reference, state, attempts, next-run time, failure reason, correlation ID). Workers claim jobs with `SELECT … FOR UPDATE SKIP LOCKED`.
- **Bounded worker pools** per workload: OCR, embedding, LLM. They are configurable and sized for the 16 GB machine.
- **Transactional outbox** for domain events (`ExtractionCompleted`, `SnapshotApproved`, …), written in the same transaction as the state change and dispatched by a poller.
- **Retries and idempotency:** retries with exponential backoff and a maximum number of attempts. Idempotency keys on job creation.
- **No external message broker** in the MVP.

## Consequences

- No new infrastructure, and job state changes are transactionally consistent.
- Throughput is bounded by PostgreSQL. That is acceptable for the MVP, and will be revisited if load tests (M11) show contention.
- Workers must set the RLS tenant context before touching data.
