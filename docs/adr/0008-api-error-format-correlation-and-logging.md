# ADR-0008: API error format, correlation IDs and logging

- **Status:** Accepted
- **Date:** 2026-11-23

## Context

Multiple connected systems will call the API. They need one predictable error contract, and support needs to trace a request across logs, jobs and systems. Error responses and logs must not leak internals, secrets or document content.

## Decision

- **Error format:** RFC 9457 Problem Details (`application/problem+json`) for every error, including those raised by security filters. The body has `type` = `urn:mimorag:problem:<error-code>`, `title`, `status` and `detail`, plus:
  - `errorCode`: a stable enum (`ApiErrorCode`) that clients branch on;
  - `correlationId`;
  - `timestamp`;
  - `violations[]` (`field`, `message`) for validation failures, without echoing rejected values.
- **Never in error responses:** stack traces, exception messages from internals, SQL or class names.
- **Correlation IDs:** clients may send `X-Correlation-Id`. It is accepted only if it matches `[A-Za-z0-9._-]{8,64}`; otherwise a UUID is generated. The ID is set before security runs, echoed in every response, stored in the logging MDC and, later, on jobs and audit records.
- **Logging:**
  - structured JSON (ECS) by default, human-readable in the `local` profile;
  - secrets, credentials, full document text, prompts and tool payloads are never logged.

## Consequences

- Clients get one error parser for all endpoints.
- Unauthenticated callers cannot tell whether a protected path exists, because both cases return an identical 401 body.
- Log injection through correlation IDs is prevented by the validation pattern.
