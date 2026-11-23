# Generic RAG Platform — Project Plan & Milestones

> **Purpose:** Build a reusable, standalone RAG platform that can integrate with multiple systems developed by our team (HRIS, ERP, CRM, and future applications) without coupling the RAG core to any system's business domain.
>
> **Working mode for the AI coding assistant:** This document is the project roadmap and **single source of truth** for milestone scope, status, architectural decisions and pending questions. Work milestone by milestone. Before implementing each milestone, inspect the current repository, present a concise implementation plan and any architectural decisions, then **wait for explicit approval**. Do not silently approve decisions or implement future milestones. Never commit, merge or push without explicit approval (see §0.3).

---

## 0. Status, decisions and open questions

_Last updated: 2026-11-23._

### 0.1 Milestone status

| Milestone | Status |
|---|---|
| M0 — Repository inspection and architecture baseline | ✅ Done (committed `d963e59`; `main` created at this commit) |
| M1 — Integration and Knowledge Base foundation (identity, RLS) | ✅ Done (`5db21e9` on `main`) |
| M1 hardening — key lifecycle, KB-restricted keys, review fixes | **Implemented on `fix/m1-hardening`. Awaiting approval and commit.** |
| M2 — PDF upload and extraction | Not started (reassess Docker disk usage first) |
| M3 — Review, submit and approved snapshot (API-first) | Not started |
| M4 — Chunking, embeddings, indexing | Not started |
| M4b — Embeddable review UI | Not started (after M4) |
| M5 – M11 | Not started |

Detailed acceptance results: [`PROJECT_PROGRESS.md`](../PROJECT_PROGRESS.md).

### 0.2 Decision log

| # | Decision | Record |
|---|---|---|
| D-01 | Modular monolith; package `com.mimocodes.mimorag`; module boundaries enforced with ArchUnit | [ADR-0001](adr/0001-modular-monolith-and-module-boundaries.md) |
| D-02 | Own `EmbeddingProvider` / `ChatModelProvider` / `VectorStore` / `OcrProvider` interfaces. Spring AI only inside adapters. No pre-release dependencies. | [ADR-0002](adr/0002-provider-abstractions-and-stable-dependencies.md) |
| D-03 | Java 21 LTS target. Spring Boot 4.2.0. Records for DTOs, plain classes elsewhere, no Lombok. | [architecture.md](architecture.md) |
| D-04 | PostgreSQL + Flyway. Separate owner/app roles. pgvector starter removed; the `vector` extension arrives in M4 through our own migrations. | [ADR-0003](adr/0003-postgresql-flyway-and-database-roles.md) |
| D-05 | Tenant isolation in layers, including PostgreSQL RLS from M1 | [ADR-0004](adr/0004-tenant-isolation-and-row-level-security.md) |
| D-06 | Connected systems own end-user authentication. MimoRag verifies a service credential (hashed per-integration API keys) plus a signed user assertion (RS256/ES256 via JWKS only in production). `access` is the only identity-provider-aware module. Separate admin plane with a bootstrap admin key. Dev auth kept separate from production. | [ADR-0005](adr/0005-identity-and-access-model.md) |
| D-07 | Search or chat without a user: disabled by default, per-KB opt-in. Service identities are narrowly scoped, audited and never bypass RLS. | [ADR-0005](adr/0005-identity-and-access-model.md) |
| D-08 | Document review is a tenant-owned core feature with an immutable approved snapshot before ingestion. No platform reviewer role. Separate `DOCUMENT_REVIEW` / `DOCUMENT_SUBMIT` permissions. Optional per-KB different-submitter rule (off by default). | [ADR-0006](adr/0006-tenant-owned-document-review.md) |
| D-09 | Review UI: API-first in M3, embeddable UI as a separate milestone after M4, using short-lived review session tokens (one tenant, one document, one user; replay-protected) | [workflows/document-review.md](workflows/document-review.md) |
| D-10 | Async jobs in PostgreSQL, no broker, **no outbox table** (the job table is the outbox); `JobQueue` interface keeps a broker possible later | [ADR-0011](adr/0011-postgresql-job-queue-without-outbox.md) |
| D-11 | RFC 9457 errors with `errorCode` and `correlationId`; JSON logs; no internals in responses | [ADR-0008](adr/0008-api-error-format-correlation-and-logging.md) |
| D-12 | Development machine: 16 GB RAM, 2 GB VRAM. Lightweight local models, CPU PaddleOCR, benchmark before choosing. Check free disk space before any large download. Docker storage stays on C: for M0 and is reassessed before M2. | — |
| D-13 | OCR corpus: synthetic Arabic/English PDFs (scanned, digital, mixed). Real company documents only with authorization, and never committed. | — |
| D-14 | English for all technical documentation and progress reports | — |
| D-15 | Security and performance are first-class requirements in every milestone | [security-architecture.md](security-architecture.md) |
| D-16 | JPA (`jakarta.persistence`) annotations are allowed in `domain`; nothing else framework-specific | [ADR-0009](adr/0009-jpa-annotations-in-domain.md) |
| D-17 | User-assertion verification is implemented fully in M1 (RS256/ES256, JWKS, strict claims), even before Q-01 is answered | [ADR-0010](adr/0010-credentials-and-database-enforced-isolation.md) |
| D-18 | Integration-plane URLs are tenant-implicit (`/api/v1/knowledge-bases`, `/api/v1/integration`); no integration ID in paths | [ADR-0010](adr/0010-credentials-and-database-enforced-isolation.md) |
| D-19 | Credential headers: `X-Api-Key`, `X-User-Assertion`, `X-Admin-Key`. An end user's effective permissions are their KB role grants **intersected** with the API key's scopes. | [ADR-0010](adr/0010-credentials-and-database-enforced-isolation.md) |
| D-20 | Admins issue only single-use, 24-hour, `KEY_ROTATE`-only **bootstrap keys**; integrations create, rotate and revoke their own keys within an admin-defined **scope ceiling** | [ADR-0012](adr/0012-api-key-lifecycle-and-restrictions.md) |
| D-21 | API key expiry is mandatory: default 90 days, maximum 365; overlapping keys during rotation; expiry warning header in the last 14 days | [ADR-0012](adr/0012-api-key-lifecycle-and-restrictions.md) |
| D-22 | Optional **KB-restricted API keys**, enforced server-side for services and relayed users | [ADR-0012](adr/0012-api-key-lifecycle-and-restrictions.md) |
| D-23 | Local document storage behind a `DocumentStorage` interface (`LocalDocumentStorage`, root `D:\mimorag-data`); S3-compatible later without pipeline changes | M2 plan |
| D-24 | Fully local AI stack during development (PaddleOCR, bge-m3, Qwen3-4B via Ollama on Windows, optional bge-reranker-v2-m3); no hosted inference APIs | [local-environment.md](local-environment.md) |
| D-25 | WSL2 memory cap 6 GB (temporary development setting) | [local-environment.md](local-environment.md) |

### 0.3 Git workflow

- One branch per feature or meaningful unit of work (`feature/…`, `fix/…`, `chore/…`), with focused changes and conventional commit messages (`feat: …`, `fix: …`, `chore: …`, `docs: …`).
- **Never commit, merge, push or touch remotes without explicit approval.** Before every commit, show (1) a summary, (2) the proposed message and (3) the file list.
- No remote is configured. Ask before adding one.

### 0.4 Open questions

| # | Question | Needed by |
|---|---|---|
| Q-01 | Can each connected system (HRIS, CRM, ERP) issue RS256/ES256 user assertions and publish a JWKS endpoint? Which issuer and audience values will they use? (The verifier is ready since M1.) | Before user-scoped production use |
| Q-02 | Long-term admin identity: OIDC through Keycloak or the company identity provider? | Before production |
| ~~Q-03~~ | ✅ Decided: PostgreSQL job queue, no broker, no outbox table (D-10) | — |
| Q-04 | Docker storage location and disk budget for PaddleOCR image, models and page-image cache | Start of M2 |
| Q-05 | Is time-boxed, audited platform support access to tenant content ever needed? | Before production |
| ~~Q-06~~ | ✅ Decided: mandatory expiry, 90-day default, 365-day maximum (D-21) | — |

---

## 1. Product vision

We are building a generic RAG platform with two independent integration paths:

1. **Knowledge ingestion (ETL):** Each connected system can send static documents and knowledge sources (PDFs initially). The platform extracts content page by page, uses OCR only when needed, allows a human to review/correct extracted content, and indexes approved content for retrieval.
2. **Tool integration:** Each connected system sends the definitions of the business tools it wants to expose during integration. The platform validates and registers these definitions. At runtime, the Agent can invoke only the tools that are enabled and authorized for the current integration/user/context.

The RAG core must not contain HRIS-, ERP-, or CRM-specific business logic.

> **Guiding architectural principle:** We are not building a chatbot that answers any question. We are building an enterprise system that answers **only within the user's permissions and the data available to them**, and that **refuses to answer when it lacks sufficient evidence**.

### Core principles

- Generic core; domain-specific logic remains inside the connected system.
- Clear module boundaries and protocol/provider abstractions.
- API-first: every meaningful milestone must expose testable endpoints.
- Async processing for long-running ingestion and indexing jobs.
- Human review before extracted content becomes searchable.
- Tenant/integration isolation must be enforced from the beginning, even if the first deployment is internal.
- Never trust an LLM-generated tenant, integration, user, or authorization identifier.
- Never allow arbitrary SQL or arbitrary endpoint execution generated by the LLM.
- Treat all retrieved documents, user input, and tool output as untrusted data, never as system instructions.
- Knowledge answers must be grounded in retrieved, authorized context; abstain when evidence is insufficient.
- Prefer measurable quality tests over “the endpoint returned 200”.
- Keep original files and extraction versions so content can be audited and reprocessed.

---

## 2. Scope and assumptions

### In scope

- Multiple connected systems.
- Multiple Knowledge Bases (KBs) per connected system.
- PDF upload and page-level extraction.
- Native PDF text extraction before OCR.
- Selective OCR for scanned/image pages and mixed pages.
- Arabic and English documents, including mixed Arabic/English pages.
- Page-level review, correction, and approval.
- Chunking, embeddings, vector indexing, retrieval, citations, and RAG answers.
- Tool registration initiated by the connected system.
- REST tool execution as the first supported protocol.
- MCP client support as a later extension, not an MVP dependency.
- Feedback and evaluation foundations.

### Not in the initial scope

- Fine-tuning an LLM.
- Training our own OCR model.
- Supporting every document format from day one.
- Arbitrary customer-provided SQL.
- Automatically executing every discovered tool.
- Building a complex visual workflow editor before the core APIs work.

### Architectural assumptions to validate before implementation

The coding assistant must inspect the repository and propose a stack consistent with existing project conventions. If this is a new repository, propose (but do not silently impose) a modular backend architecture. A reasonable default is:

- Backend: Java 21+ and Spring Boot.
- Database: PostgreSQL.
- Vector search: pgvector initially, behind a `VectorStore` abstraction.
- Object storage: local filesystem for development, an object-storage abstraction for production.
- OCR: PaddleOCR service, preferably isolated from the Java application.
- Local model runtime: Ollama during development.
- API documentation: OpenAPI.
- Integration tests: Testcontainers where appropriate.

Do not introduce microservices merely for the sake of microservices. Keep the first version deployable as a modular monolith, with OCR/model runtimes as external processes/services where their ecosystems make that practical.

---

## 3. High-level architecture

```text
Connected Systems
  |
  |-- Integration setup / credentials
  |-- Static documents (PDF initially)
  |-- Tool definitions + schemas
  |
  v
Generic RAG Platform
  |
  +-- Integration Management
  |     +-- System registration
  |     +-- Integration credentials
  |     +-- KB ownership / access
  |
  +-- Ingestion Pipeline
  |     +-- Upload & file validation
  |     +-- PDF page rendering/classification
  |     +-- Native text extraction
  |     +-- Selective OCR
  |     +-- Page extraction results
  |     +-- Human review & approval
  |     +-- Chunking
  |     +-- Embedding
  |     +-- Vector indexing
  |
  +-- RAG Runtime
  |     +-- Authentication / authorization
  |     +-- Query processing
  |     +-- Retrieval (metadata filters + vector search)
  |     +-- Optional hybrid retrieval / reranking
  |     +-- Context assembly
  |     +-- LLM answer + citations
  |
  +-- Tool Integration
  |     +-- Tool registration API
  |     +-- Tool registry
  |     +-- Schema validation
  |     +-- Tool authorization
  |     +-- Tool dispatcher
  |     +-- REST executor (MVP)
  |     +-- MCP client adapter (later)
  |
  +-- Feedback & Evaluation
        +-- User feedback
        +-- Evaluation datasets
        +-- Retrieval / answer metrics
        +-- Regression tests
```

### Important separation

- **Static knowledge** belongs in the Knowledge Base and vector/search index.
- **Live and changing business data** (e.g. current leave balance, invoice status, attendance today) must be retrieved through tools at request time, not embedded as static chunks.
- The RAG engine should depend on generic interfaces such as `EmbeddingProvider`, `ChatModelProvider`, `DocumentExtractor`, `OcrProvider`, `VectorStore`, and `ToolExecutor`, not on specific vendor SDKs or business systems.

---

## 4. Integration contract

A connected system is registered as an integration. An integration may own multiple Knowledge Bases and may register multiple tools.

### 4.0 Identity and trust (decided — see [ADR-0005](adr/0005-identity-and-access-model.md) and [security-architecture.md](security-architecture.md))

- **End-user authentication belongs to the connected system.** MimoRag never logs users in.
- **Every call carries the integration's service credential.** That credential alone determines the tenant.
- **User-scoped calls also carry a signed user assertion:** a short-lived RS256/ES256 JWT issued by the connected system and verified against its registered JWKS endpoint (issuer, audience, expiry, signature). It must be bound to the same integration.
- **`access` turns verified credentials into an `AccessContext`.** Core modules authorize only on that context, and RLS uses its integration ID.
- **Platform administration is a separate plane** (`/api/v1/admin/**`). It starts with a bootstrap admin key, and admins have no default access to tenant content.
- **Integration registration (`POST /api/v1/admin/integrations`) is admin-only,** as are issuing keys and configuring trust settings.

### 4.1 Static data / document ingestion

The connected system can upload documents to a selected KB through the platform API. Initially, support PDF. Later, add DOCX, images, HTML, and other formats through extractor adapters.

The upload API should return a document ID and an asynchronous processing job ID. The caller can poll job status or receive a webhook when processing reaches a reviewable state.

### 4.2 Tool registration — push model

**Decision:** The connected system sends its tool definitions to the RAG platform during integration setup or whenever its tool catalog changes.

The RAG platform does not need to understand the business meaning of `get_employee_leave_balance` or `get_invoice_status`. It stores and validates a generic contract.

A tool definition should include at least:

```json
{
  "name": "get_employee_leave_balance",
  "description": "Returns the authenticated employee's current leave balance.",
  "version": "1.0",
  "inputSchema": {
    "type": "object",
    "properties": {
      "leaveType": {
        "type": "string",
        "description": "Leave category"
      }
    },
    "required": ["leaveType"],
    "additionalProperties": false
  },
  "outputSchema": {
    "type": "object",
    "properties": {
      "leaveType": { "type": "string" },
      "remainingDays": { "type": "number" }
    }
  },
  "execution": {
    "protocol": "REST",
    "method": "GET",
    "path": "/api/v1/me/leave-balances"
  },
  "security": {
    "access": "USER_SCOPED",
    "risk": "READ_ONLY",
    "requiresConfirmation": false
  }
}
```

This is an illustrative contract, not a final schema. Review and refine it before implementation.

**Important design rules:**

- Do not let the tool definition contain arbitrary executable code.
- Do not accept an arbitrary full URL from the LLM. The integration owns a trusted base URL; tool paths are validated relative paths.
- Store credentials securely; store only a secret reference in ordinary database records.
- Validate names, descriptions, JSON Schemas, methods, paths, and output limits.
- Version tool definitions and support safe updates/deactivation.
- Admins explicitly enable/publish tools after registration.
- Scope tools to an integration and, where needed, to a KB, assistant, user role, or conversation.
- The platform must not expose every registered tool to every Agent.
- Connected systems remain responsible for enforcing their own business authorization.
- Define how the platform propagates identity: delegated user token where possible, or a restricted service identity with explicit user context and authorization checks.
- Treat tool descriptions and returned data as untrusted input; they must not override system instructions.

### 4.3 Tool runtime

The Agent selects a registered tool by name and provides arguments. The platform:

1. Resolves the tool from the authenticated integration/context (never from an LLM-supplied integration ID).
2. Checks whether the tool is enabled and authorized for this user/context.
3. Validates arguments against the registered JSON Schema.
4. Executes it through the relevant adapter.
5. Applies timeouts, rate limits, response-size limits, and output validation.
6. Audits the call and returns a normalized result to the Agent.

The first adapter is REST. Keep a generic `ToolExecutor` interface so an MCP executor can be added later without changing the Agent or registry.

---

## 5. Milestone roadmap

Every milestone must be independently testable. Do not start the next milestone until the current milestone's acceptance criteria **and the Quality Gates in [Section 9](#9-how-the-coding-assistant-must-work)** pass, and the user approves moving on.

### M0 — Repository inspection and architecture baseline

**Goal:** Agree on the technical baseline before writing business code.

Tasks:
- Inspect the repository, build files, existing conventions, and runtime environment.
- Identify existing code that can be reused.
- Propose module/package boundaries.
- Propose database and migration strategy.
- Propose local development setup (Docker Compose if appropriate).
- Define API error format, validation conventions, logging, correlation IDs, and configuration/secrets strategy.
- Write/update `docs/architecture.md` and `PROJECT_PROGRESS.md`.

Deliverables:
- Approved architecture and module map.
- Local environment starts successfully.
- Health endpoint.

Test endpoints:
- `GET /actuator/health` (or project-equivalent).

Acceptance criteria:
- Application starts from a clean checkout using documented steps.
- Health endpoint reports application/database dependencies accurately.
- No secrets are committed.
- Architecture decisions and open questions are documented.

**Stop for approval.**

### M1 — Integration and Knowledge Base foundation

**Goal:** Represent connected systems and their Knowledge Bases without any HRIS/ERP-specific code.

Tasks:
- Create integration registration and lifecycle.
- Create KB CRUD and ownership relationship.
- Enforce that a KB belongs to an integration.
- Add authorization checks and tenant/integration scoping to all queries.
- Add migrations, DTOs, validation, and API documentation.
- Implement the `access` module: integration API-key authentication (hashed keys, rotation, revocation), a separate admin plane with the bootstrap admin key, and `AccessContext`.
- Store per-integration trust configuration (issuer, audience, JWKS URL, claim mapping), and implement the RS256/ES256 user-assertion verifier fully (decision D-17).
- Enable PostgreSQL RLS on tenant tables, with `set_config('app.integration_id', …, true)` per transaction.
- Add KB policies: role-to-permission mapping, "search without user" opt-in (off), different-submitter rule (off).

Implemented endpoints (tenant-implicit integration plane, decision D-18; `/admin` = platform admin plane):
- `POST /api/v1/admin/integrations`, `GET /api/v1/admin/integrations`, `GET …/{integrationId}`, `PATCH …/{integrationId}/status`
- `POST /api/v1/admin/integrations/{integrationId}/bootstrap-keys` (hardening: replaces admin content-key issuance), `PUT …/allowed-scopes`, `GET …/api-keys`, `DELETE …/api-keys/{keyId}`
- `POST/GET /api/v1/integration/api-keys`, `DELETE /api/v1/integration/api-keys/{keyId}` (self-service keys, hardening)
- `PUT /api/v1/admin/integrations/{integrationId}/trust-config`, `GET …/trust-config`
- `GET /api/v1/integration` (the caller's own integration)
- `POST /api/v1/knowledge-bases`, `GET /api/v1/knowledge-bases`
- `GET /api/v1/knowledge-bases/{knowledgeBaseId}`, `PATCH /api/v1/knowledge-bases/{knowledgeBaseId}`
- `GET /api/v1/knowledge-bases/{knowledgeBaseId}/role-grants`, `PUT …/role-grants`

Acceptance criteria:
- Create an integration and multiple KBs.
- An integration cannot access another integration's KBs.
- Invalid or unauthorized IDs do not leak data.
- Integration/KB lifecycle and ownership are covered by integration tests.

**Stop for approval.**

### M2 — PDF upload and extraction (first visible ETL milestone)

**Goal:** Upload PDFs and get extracted page content back, without embeddings or RAG yet.

Tasks:
- Implement secure PDF upload and storage.
- Validate file type using content, not only extension.
- Enforce file-size, page-count, and upload limits.
- Create document and processing-job records.
- Process asynchronously.
- Render PDF pages when needed.
- For each page, determine whether it contains usable native text, image/scanned content, or a mixture.
- Extract native PDF text first.
- Invoke OCR only for pages/regions that need it.
- Preserve page order and page boundaries.
- Return extraction method, language/script guess, confidence/quality signals where available, and warnings.
- Do not silently discard headers, footers, tables, or repeated content at this stage. Preserve source text; cleaning can be a later, reviewable transformation.
- Model `Document` → `DocumentVersion` → `ExtractionRun` → `PageExtraction` (read-only), as in [workflows/document-review.md](workflows/document-review.md).
- Serve rendered page images through an authorized endpoint, rendered on demand and cached with a size cap.
- Flag hidden or invisible text and bidi override characters as page warnings.
- Publish `ExtractionCompleted` through the outbox (confirm ADR-0007 first).
- Before starting: reassess Docker disk usage and the PaddleOCR image size (Q-04).

Suggested endpoints:
- `POST /api/v1/knowledge-bases/{kbId}/documents` — multipart upload; returns `documentId` and `jobId`.
- `GET /api/v1/documents/{documentId}/pages/{pageNumber}/image` — rendered page image.
- `GET /api/v1/ingestion-jobs/{jobId}` — status and progress.
- `GET /api/v1/documents/{documentId}/pages` — page-level extraction results.
- `GET /api/v1/documents/{documentId}/extraction` — full extraction result.

Example response shape (illustrative):
```json
{
  "documentId": "doc-123",
  "status": "EXTRACTION_COMPLETED",
  "pages": [
    {
      "pageNumber": 1,
      "extractionMethod": "NATIVE_TEXT",
      "detectedScripts": ["ARABIC", "LATIN"],
      "text": "Extracted page content...",
      "quality": {
        "textLength": 850,
        "ocrRequired": false,
        "warnings": []
      }
    },
    {
      "pageNumber": 2,
      "extractionMethod": "OCR",
      "detectedScripts": ["ARABIC"],
      "text": "Extracted OCR content...",
      "quality": {
        "ocrRequired": true,
        "warnings": ["LOW_CONFIDENCE_REGION"]
      }
    }
  ]
}
```

Acceptance criteria:
- Upload a text-based PDF: native extraction works and OCR is not unnecessarily called.
- Upload a scanned Arabic PDF: OCR runs and Arabic text is returned in correct reading order as far as the engine supports.
- Upload a scanned English PDF: English text is returned.
- Upload a mixed Arabic/English PDF: both scripts are retained; no assumption that one language applies to the whole file.
- Upload a mixed PDF with native-text and scanned pages: OCR is invoked only on pages/regions that need it.
- Corrupt, encrypted, oversized, and unsupported files fail with clear errors.
- Extraction can be retried safely; duplicate requests do not create uncontrolled duplicate jobs.
- Extraction latency is measured per page and per method (native vs OCR).
- A PDF containing hidden instructions (e.g. white or tiny text) is extracted as plain content and flagged for review where detectable; it is never treated as an instruction.

**This is the first major demo:** upload a PDF, poll the job, then call the pages endpoint and inspect the extracted content.

**Stop for approval.**

### M3 — Review, submit and approved snapshot (API-first)

**Goal:** Let the **owning company** inspect and correct extracted content page by page, then submit it. Submission creates an immutable approved snapshot, which is the only input to ingestion. Full workflow: [workflows/document-review.md](workflows/document-review.md).

Tasks:
- Add page review states: `PENDING_REVIEW`, `FLAGGED`, `REVIEWED`, `EXCLUDED`.
- Expose the original extraction and the current (corrected) text separately. Store edits as append-only `PageRevision`s.
- Allow page-level correction, with optimistic locking (`If-Match`) and edit size limits.
- Track reviewer (external user ID), timestamp, and content version. Audit every edit, exclusion and submission.
- Enforce separate permissions: `DOCUMENT_REVIEW` (edit) and `DOCUMENT_SUBMIT` (submit).
- Submit validation: every page `REVIEWED` or `EXCLUDED`, nothing `FLAGGED`, and the optional per-KB different-submitter rule (off by default).
- On submit, create an immutable `ApprovedSnapshot` and publish `SnapshotApproved`.
- Allow reprocessing without overwriting approved history.
- API-first: connected systems build their own review screens. Review logic stays independent of any frontend.
- Do not index unapproved content.

Suggested endpoints:
- `GET /api/v1/documents/{documentId}/review`
- `GET /api/v1/documents/{documentId}/pages/{pageNumber}`
- `PUT /api/v1/documents/{documentId}/pages/{pageNumber}/text`
- `POST /api/v1/documents/{documentId}/pages/{pageNumber}/review` (reviewed or excluded)
- `POST /api/v1/documents/{documentId}/submit`

Acceptance criteria:
- A user of the owning integration can retrieve all pages and their review states. Other integrations get 404.
- A user can correct a page without losing the original extraction.
- Concurrent edits are detected, not silently overwritten.
- Submission is rejected while pages are pending or flagged.
- Review and submission are auditable. Edit and submit permissions are enforced separately.
- The snapshot is immutable. Unapproved, excluded or draft content cannot be indexed.
- Reprocessing creates a new extraction version and does not silently erase user corrections.

**Stop for approval.**

### M4 — Chunking, embeddings, and indexing

**Goal:** Convert approved content into searchable chunks.

Tasks:
- Implement configurable chunking profiles.
- Preserve page/document/section relationships.
- Add metadata: integration ID, KB ID, document ID, page number, section, language, version, approval state.
- Implement `EmbeddingProvider` abstraction.
- Implement batch embedding with retry, rate/concurrency controls, and dimension validation.
- Implement `VectorStore` abstraction and initial pgvector implementation (if approved).
- Make indexing idempotent and version-aware.
- Support document re-indexing and deletion.
- Never mix vectors from different embedding models/dimensions in the same index without an explicit migration strategy.
- Trigger ingestion only from `SnapshotApproved`. Read approved snapshots only, never drafts.
- Run normalization/cleaning after approval as a recorded, repeatable step. Never alter approved text.
- Track `IngestionRun`s, keyed idempotently by snapshot, chunking profile and embedding model, with bounded retries and failure reasons.
- Switch the active version atomically, only after success. The previous version stays searchable until then. Re-ingest an existing snapshot without re-review.

Suggested endpoints:
- `POST /api/v1/documents/{documentId}/index`
- `GET /api/v1/documents/{documentId}/index-status`
- `GET /api/v1/knowledge-bases/{kbId}/stats`
- `DELETE /api/v1/documents/{documentId}/index`

Acceptance criteria:
- Only approved pages are chunked and indexed.
- Every chunk can be traced to its source document and page.
- Embedding dimensions are validated.
- Repeated indexing does not create duplicate active chunks.
- Deleting or replacing a document removes/invalidates its old searchable version.
- Tests prove strict integration/KB filtering.

**Stop for approval.**

### M4b — Embeddable review UI (after M4)

**Goal:** Provide one reusable review interface that HRIS, CRM, ERP and future systems can embed, instead of each building its own.

Tasks:
- Build a frontend for page navigation, a page image beside editable text, flags, exclusions and submission, on top of the M3 review API (no review logic in the frontend).
- Add a review session token exchange. The connected system's backend presents its service credential and user assertion and receives a token that is short-lived, scoped to one tenant, one document and one user, limited to review permissions, replay-protected (`jti`), bound to the allowed embedding origin, and revoked on submit.
- Add a CSP, `frame-ancestors` per integration, and CORS restricted to registered origins.
- Support RTL/LTR rendering for Arabic, English and mixed text.

Acceptance criteria:
- A sample host application embeds the UI and completes a review-and-submit cycle.
- Tokens expire, cannot be replayed, and cannot reach another document, user or tenant.
- The UI cannot be embedded from unregistered origins.

**Stop for approval.**

### M5 — Retrieval API and baseline RAG answers

**Goal:** Prove retrieval quality independently before adding complex Agent behavior.

Tasks:
- Implement query embedding through the same compatible embedding model used for indexed content.
- Implement metadata-filtered vector retrieval.
- Add a retrieval-only endpoint that returns chunks, scores, and source metadata.
- Add context assembly and LLM answer generation.
- Return citations to document/page/chunk.
- Add a strict “not enough evidence” response behavior, triggered by missing evidence or low retrieval confidence.
- Add answer validation that checks each claim is supported by the retrieved sources.
- Keep retrieved context clearly separated from system instructions in the prompt.
- Preserve the user's language where possible; support Arabic, English, and mixed-language queries.
- Keep retrieval independent from the Tool Integration module.

Suggested endpoints:
- `POST /api/v1/knowledge-bases/{kbId}/search`
- `POST /api/v1/knowledge-bases/{kbId}/chat`
- `GET /api/v1/conversations/{conversationId}` (if conversation persistence is included)

Acceptance criteria:
- Retrieval returns only chunks from the requested authorized KB/integration.
- Answers cite their supporting document and page.
- No relevant evidence produces a safe abstention rather than fabricated facts.
- The LLM's general knowledge is never used as a substitute for system data.
- Unsupported claims are caught by answer validation.
- A document containing injected instructions does not change system behavior or user permissions.
- Retrieval and generation latency are measured separately.
- Arabic query against Arabic content and English query against English content are tested.
- Cross-language retrieval (Arabic query against English content and vice versa) is measured, not assumed.
- Retrieval endpoint can be tested without invoking an LLM.

**Stop for approval.**

### M6 — Tool registration API (push model)

**Goal:** Let a connected system register/update its tools during integration.

Tasks:
- Finalize canonical Tool Definition schema.
- Add tool registration, versioning, activation/deactivation, and listing.
- Validate JSON Schema and execution configuration.
- Associate tools with the authenticated integration.
- Add admin enable/publish step.
- Add tool permissions, risk classification, and confirmation policy.
- Store secrets outside ordinary tool-definition JSON.
- Add audit events for registration and updates.

Suggested endpoints:
- `PUT /api/v1/integrations/{integrationId}/tools` — bulk upsert/synchronize tool catalog.
- `POST /api/v1/integrations/{integrationId}/tools` — register one tool if useful.
- `GET /api/v1/integrations/{integrationId}/tools`
- `GET /api/v1/tools/{toolId}`
- `PATCH /api/v1/tools/{toolId}/status`

Acceptance criteria:
- A sample external system can send a tool catalog and receive validation results.
- Duplicate registration is idempotent when the same stable tool key/version is supplied.
- Invalid schemas and unsafe execution configurations are rejected.
- One integration cannot register tools under another integration.
- Disabled/unpublished tools are not exposed to the Agent.
- Tool definitions are treated as data, never executable code.

**Stop for approval.**

### M7 — REST tool execution and security

**Goal:** Execute registered tools safely through REST.

Tasks:
- Implement generic `ToolExecutor` interface and REST executor.
- Resolve relative paths against a trusted integration base URL.
- Support configured authentication (e.g. OAuth2 client credentials or delegated token); do not store raw secrets in tool rows.
- Validate arguments and normalize outputs.
- Add timeouts, retries only where safe, rate limits, response-size limits, and circuit-breaking where justified.
- Add SSRF protections, outbound host allowlists, and redirect restrictions.
- Add read-only vs write/destructive operation policies.
- Require explicit confirmation for sensitive write actions.
- Audit caller, integration, tool, timing, status, and safe result metadata (avoid logging secrets or sensitive payloads).
- Ensure connected systems perform their own authorization and business-rule checks.

Suggested endpoints:
- `POST /api/v1/tools/{toolId}/test` — admin-only connection/test execution using safe test inputs.
- `POST /api/v1/tools/{toolId}/execute` — protected diagnostic endpoint; never expose as an unrestricted public execution endpoint.
- `GET /api/v1/tool-executions/{executionId}` — execution status/result if asynchronous execution is needed.

Acceptance criteria:
- A test integration exposes a harmless read-only REST endpoint and the platform invokes it successfully.
- Invalid arguments are rejected before the outbound call.
- Unauthorized users cannot execute tools.
- A tool cannot call arbitrary hosts or escape its configured base URL.
- Timeouts and oversized responses are handled.
- Write tools cannot execute without the configured confirmation/authorization path.
- Secrets never appear in API responses or logs.

**Stop for approval.**

### M8 — Agent tool calling

**Goal:** Let the RAG Agent decide when a registered tool is relevant and call it through the dispatcher.

Tasks:
- Expose only the authorized, enabled, context-relevant tools to the model.
- Convert canonical tool definitions into the model provider's function/tool schema.
- Validate model-produced arguments again before execution.
- Add maximum tool-call count, total execution time, and token budgets.
- Prevent infinite tool-call loops.
- Distinguish static KB evidence from live tool results.
- Return tool-derived facts with clear provenance.
- Do not allow tool output to override system instructions or permissions.
- Handle tool errors without hallucinating success.

Suggested endpoint:
- `POST /api/v1/assistants/{assistantId}/chat` or the approved chat endpoint with tool use enabled.

Acceptance criteria:
- Agent answers a static knowledge question using retrieval without unnecessary tool calls.
- Agent invokes the correct tool for a live-data question.
- Agent does not invoke a tool the current user is not authorized to use.
- Agent does not invent tool results when execution fails.
- Tool-call count and execution-time limits are enforced.
- Tests include prompt-injection attempts inside documents and tool output.

**Stop for approval.**

### M9 — Feedback and evaluation

**Goal:** Measure quality and use feedback to improve the system safely.

Tasks:
- Add thumbs-up/down and optional feedback reason/comment.
- Store feedback with conversation, answer, citations, model/provider versions, and retrieval trace.
- Build a small evaluation dataset with Arabic, English, and mixed-language questions.
- Evaluate retrieval and answer quality separately.
- Add regression tests for tenant/integration isolation and tool authorization.
- Track latency, token usage, embedding usage, OCR processing time, failures, and cost where applicable.
- Treat feedback as a signal for review; do not automatically fine-tune or rewrite the Knowledge Base from user feedback.

Suggested endpoints:
- `POST /api/v1/answers/{answerId}/feedback`
- `GET /api/v1/admin/evaluations/summary`
- `POST /api/v1/admin/evaluations/runs`

Metrics to consider:
- Retrieval: Recall@K, Precision@K, MRR, nDCG@K, Hit@K.
- Answer: correctness, required-fact coverage, citation precision, groundedness, answer-language match, abstention correctness.
- Tools: tool-selection accuracy, argument validity, authorization rejection rate, execution success rate.
- Operations: p50/p95 latency, OCR failure rate, ingestion throughput, model token usage.

Acceptance criteria:
- Feedback is persisted and traceable.
- A fixed evaluation set can be rerun after changes.
- Regressions are visible.
- Feedback does not silently alter source documents, embeddings, permissions, or model weights.

**Stop for approval.**

### M10 — MCP adapter and additional providers (optional extension)

**Goal:** Add interoperability without changing the generic Tool Registry or Agent.

Tasks:
- Implement MCP client adapter behind the same `ToolExecutor` abstraction.
- Connect to an approved MCP server.
- Discover tools and map their schemas into the canonical tool definition.
- Require explicit admin selection/approval before publishing discovered tools.
- Apply the same authorization, validation, timeout, output, and audit policies as REST.
- Consider MCP Resources/Prompts only if a concrete use case needs them.

Acceptance criteria:
- A sample MCP server can be connected and its selected tools can be invoked.
- The Agent uses REST and MCP tools through the same internal dispatcher.
- MCP tools cannot bypass platform permissions or security controls.

**Stop for approval.**

### M11 — Production hardening

**Goal:** Make the platform reliable and deployable.

Tasks:
- Review tenant/integration isolation across every table, cache, object, job, vector query, and conversation.
- Add quotas and backpressure for uploads, OCR, embeddings, and tool calls.
- Add observability, structured logs, metrics, tracing, and alerting.
- Add retention/deletion policies and document reprocessing strategy.
- Add backup/restore and migration procedures.
- Review file parsing and OCR sandboxing.
- Add load tests and failure/retry tests.
- Document deployment, configuration, provider setup, and operational runbooks.

Acceptance criteria:
- Security review completed.
- Load and failure tests meet agreed targets.
- Data deletion and re-indexing are verified.
- Deployment can be reproduced from documentation.

---

## 6. OCR strategy — Arabic, English, and mixed documents

### Page-level pipeline

Do not choose one OCR model for an entire PDF. A single PDF can contain native text, scanned pages, images, tables, Arabic, English, and mixed-language content.

For each page:

1. Attempt native text extraction.
2. Assess extracted text quality (empty/near-empty text, suspicious character ratio, text density, extraction errors, etc.).
3. If native text is usable, keep it and avoid OCR unless image regions contain additional meaningful text.
4. If the page is scanned/image-based or native extraction is poor, render the page and run OCR.
5. Detect script/language at page or region level where practical.
6. For mixed Arabic/English content, use a multilingual/mixed-script-capable recognition strategy or route regions by script only if the routing is reliable.
7. Preserve block order, bounding boxes, and confidence where available.
8. Return extraction metadata and warnings for human review.

### PaddleOCR candidate

PaddleOCR is a strong candidate for a local OCR service. The current PaddleOCR documentation includes PP-OCRv6 for a number of languages, but its listed PP-OCRv6 language set does not include Arabic. For Arabic, evaluate the PP-OCRv5 Arabic recognition model (`arabic_PP-OCRv5_mobile_rec`), which supports Arabic and English, alongside the English-specific PP-OCRv5 model (`en_PP-OCRv5_mobile_rec`). Do not assume one model is best for every mixed-script page; verify Arabic/English accuracy and reading order using our own corpus. Also check the exact PaddleOCR version and model compatibility before pinning dependencies.

Start with a benchmark comparing:
- PP-OCRv5 Arabic recognition model.
- PP-OCRv5 English recognition model.
- The relevant multilingual/mixed-script pipeline.
- Native PDF extraction for digital PDFs.

Do not hardcode “Arabic page => Arabic model, English page => English model” until measured. For a page containing both languages, page-level language detection may be insufficient. Region-level routing or a model that handles both scripts may be better.

Keep OCR behind an `OcrProvider` interface and expose OCR as a separate local service/process if that simplifies Python/Paddle dependencies and deployment. The Java platform should communicate through a stable internal HTTP contract.

### OCR test corpus

Create a small, versioned corpus containing:
- Digital Arabic PDF.
- Scanned Arabic PDF.
- Digital English PDF.
- Scanned English PDF.
- Mixed Arabic/English page.
- PDF with both digital-text and scanned pages.
- Tables, headers/footers, low-resolution scans, rotated pages, and noisy images.

Measure character/word error rate where ground truth is available, reading-order quality, table preservation, latency, CPU/RAM/GPU usage, and failure rate.

---

## 7. Local LLM and embedding strategy for development

Use provider abstractions so local development models can be replaced later without changing RAG business logic.

### Ollama

Ollama can run local models and expose an HTTP API on the local machine. Local requests do **not** require an API key. It also provides an OpenAI-compatible API; some client libraries require a placeholder API-key value even though the local Ollama server ignores it.

Development examples:
- Chat API: `http://localhost:11434/api/chat`
- Embeddings API: `http://localhost:11434/api/embed`
- OpenAI-compatible base URL: `http://localhost:11434/v1`

Keep the model name, base URL, context length, timeout, and generation settings configurable. Never hardcode a developer's local URL into production configuration.

### Embedding candidates

Benchmark at least:
- `bge-m3`: multilingual, supports 100+ languages, and is available through Ollama.
- `qwen3-embedding:0.6b`: smaller multilingual option; Qwen3 Embedding supports 100+ languages, including Arabic varieties and English.
- Optionally a larger Qwen3 embedding size if local hardware permits.

Choose using a representative Arabic/English retrieval benchmark, not model popularity alone. Record vector dimensions and lock the index to a model/version. Changing the embedding model requires re-embedding and a migration plan.

### Chat model candidates

Start with an Ollama-served model that fits the developer machine. Candidate families include Qwen3 and other tool-capable multilingual models. Select the actual size only after checking available RAM/VRAM and measuring:
- Arabic and Egyptian Arabic response quality.
- English response quality.
- Mixed-language handling.
- Tool/function calling reliability.
- Structured output/schema adherence.
- Latency and memory usage.

A local model being free to run does not mean inference is costless: it uses local compute, RAM/VRAM, and power. Model licenses must be checked before redistribution or commercial deployment.

---

## 8. Cross-cutting engineering requirements

### 8.1 Security and AI safety

#### Prompt injection defense

- Treat all content from documents, user input, and tool output as **untrusted data**, never as system instructions.
- Keep system instructions strictly separated from retrieved context (distinct prompt sections/roles, clear delimiters).
- Documents and tool output must never change system policies or user permissions.
- Test both direct and indirect prompt injection, including instructions hidden inside PDF files (white text, tiny fonts, metadata, OCR'd images).

#### Context-grounded answers

- Knowledge answers must be based on trusted, authorized retrieved context.
- If the information is not found, or retrieval confidence is low, the system abstains and states that the information is not available.
- Never use the LLM's general knowledge as a substitute for system data.
- Add answer validation to check that the answer is supported by the retrieved sources.
- Keep references to every source used in the answer (document, page, chunk).

#### Tool security

- The LLM never determines `tenant_id` or `integration_id` and cannot change user permissions; identity is derived from authenticated credentials only.
- No arbitrary SQL, arbitrary URLs, or model-supplied code execution.
- Every tool has a defined input schema and permission policy.
- Write, delete, and other sensitive operations require confirmation according to system policy.
- Verify the user's authorization in the source system before executing an operation.
- Protect against SSRF and oversized tool outputs.

#### Multi-tenant isolation

- Full isolation between HRIS, CRM, ERP, and every other integration.
- Isolation applies to documents, pages, chunks, vectors, tools, executions, conversations, caches, and background jobs.
- Enforce authorization at service/query boundaries, not only in controllers.
- Cross-tenant access tests are required in every milestone.

#### Additional security

- Protection against SQL injection, XSS, and insecure file uploads.
- File type validation (by content, not extension) and file size/page-count limits.
- Protection against malicious PDFs and decompression/resource exhaustion.
- Rate limiting, authentication, and authorization on all endpoints.
- Secrets live outside the code, in a secret manager or encrypted secret store.
- Never log secrets or sensitive personal data; redact where necessary.

### 8.2 Performance and scalability

- Measure latency for each stage separately:
  - document extraction / OCR;
  - embedding generation;
  - retrieval / reranking;
  - LLM generation;
  - tool execution.
- Use async processing for ingestion, OCR, and embedding.
- Batch embedding requests instead of one request per chunk.
- Add appropriate database indexes and verify query plans.
- Optimize vector search with HNSW (or an alternative) only after benchmarking.
- Evaluate hybrid search, measuring the impact of each strategy on both quality and speed.
- Cache suitable results using tenant-aware cache keys.
- Apply timeouts, bounded retries, and circuit breakers to external services.
- Control concurrent requests, queue sizes, and token limits.
- Monitor memory, CPU, and disk usage, especially with local models and OCR.
- Run load and stress tests before production.

### 8.3 Reliability

- Long-running ingestion and OCR are asynchronous.
- Jobs have explicit states, retry policies, and failure reasons.
- Use idempotency keys or stable job/document version identifiers where appropriate.
- Retries must not duplicate chunks or execute unsafe write tools twice.
- Add timeouts and bounded concurrency for external model/OCR/tool calls.

### 8.4 Observability

Every request/job should have a correlation ID. Capture:
- document ID, job ID, page number, extraction method;
- provider/model/version;
- embedding dimension;
- retrieval trace and selected source IDs;
- tool ID, execution ID, duration, and status;
- safe error codes and retry count.

Do not log raw secrets. Avoid logging full document or tool payloads by default.

### 8.5 API conventions

- Version APIs (`/api/v1`).
- Use DTOs, validation, consistent error responses, and pagination.
- Document endpoints with OpenAPI.
- Use asynchronous job IDs for expensive operations.
- Include example requests/responses and curl examples in milestone documentation.

---

## 9. How the coding assistant must work

For **each** milestone:

1. Inspect the relevant existing code and docs first.
2. Explain the milestone goal and proposed design in a short plan.
3. List files/modules likely to change.
4. Identify risks, assumptions, and decisions that need approval.
5. **Wait for explicit user approval before implementation.**
6. Implement only the approved milestone; do not start future milestones.
7. Run relevant unit, integration, architecture, and build tests.
8. Start the application/services and test the milestone endpoints using real HTTP requests where possible.
9. Report exact commands, endpoints, sample payloads, test results, and known limitations.
10. Update `PROJECT_PROGRESS.md` and mark acceptance criteria as passed/failed.
11. Stop and ask for approval before moving to the next milestone.

### Definition of Done for every milestone

A milestone is not complete merely because code compiles. It is complete only when:
- Acceptance criteria are demonstrated.
- Automated tests pass.
- Relevant endpoints are exercised.
- API documentation/examples are updated.
- Security and failure paths are tested where relevant.
- Progress documentation is updated.
- Remaining limitations are explicit.

### Quality gates

Every milestone must pass all of the following before moving to the next one:

1. **Functional tests** — acceptance criteria demonstrated with real requests.
2. **Security tests** — authentication, authorization, input validation, and prompt injection where relevant.
3. **Cross-tenant isolation tests** — no data, tool, or cache leakage between integrations.
4. **Performance measurements** — per-stage latency recorded and compared with the previous baseline.
5. **Error handling tests** — invalid input, timeouts, unavailable dependencies, and retries.
6. **Documentation** — API docs/examples updated and `PROJECT_PROGRESS.md` reflects passed/failed criteria.

---

## 10. Mandatory engineering standards — Clean Code, SOLID, and maintainability

These standards apply to **every milestone**, not only during a final refactoring pass.

### Clean Code

- Use clear, intention-revealing names for classes, methods, variables, endpoints, and configuration.
- Keep methods focused and reasonably small; avoid deeply nested conditionals and large “god” services.
- Avoid duplication; extract shared behavior only when it represents a real reusable concept.
- Prefer explicit control flow and readable code over clever abstractions.
- Keep controllers thin: HTTP concerns in controllers, business rules in application/domain services, persistence concerns in repositories/adapters.
- Use DTOs for API requests/responses; do not expose persistence entities directly.
- Validate input at system boundaries and return consistent, meaningful errors.
- Avoid magic strings/numbers; use enums or named configuration where appropriate.
- Do not add comments that merely repeat the code. Document non-obvious decisions and trade-offs.

### SOLID and dependency direction

- **S — Single Responsibility:** each class/module should have one clear reason to change.
- **O — Open/Closed:** add providers/connectors through implementations of stable interfaces rather than editing the RAG core for every new provider or system.
- **L — Liskov Substitution:** implementations of provider/connector interfaces must honor the same behavioral contracts.
- **I — Interface Segregation:** prefer small, focused interfaces over large interfaces with unrelated methods.
- **D — Dependency Inversion:** core business logic depends on abstractions; infrastructure adapters implement those abstractions.
- Keep domain/application logic independent of Spring-specific or vendor-specific details where practical.
- Avoid circular dependencies between modules.
- Do not introduce abstractions solely for hypothetical future use; add them where there is a real variation point (LLM, embeddings, OCR, vector store, tool protocol, storage).

### Testing and quality gates

- Add unit tests for business rules and validation.
- Add integration tests for database, provider adapters, HTTP endpoints, and security boundaries.
- Add architecture tests where useful to enforce module dependency rules.
- Test failure paths, timeouts, retries, invalid input, and authorization—not only happy paths.
- Do not weaken/delete tests just to make a build pass. Explain any changed test expectation.
- Before marking a milestone complete, run formatting/static analysis (if configured), the full relevant test suite, and a clean build.
- If a design violates SOLID or creates unnecessary coupling, stop and explain the trade-off before proceeding.

### Required implementation review

At the end of each milestone, report:
1. How the implementation follows Clean Code and SOLID.
2. Any intentional deviations and why they are justified.
3. Module dependency changes.
4. Test coverage added and commands/results.
5. Technical debt introduced, if any, with a reason and follow-up plan.

---

## 11. Development-time cost policy — free and local first

During development, prefer **free, locally runnable, open-weight/open-source components**. Do not introduce a paid hosted API or require a paid API key unless the user explicitly approves it.

### Default development configuration

- Chat LLM: Ollama local model.
- Embeddings: Ollama local embedding model.
- OCR: local PaddleOCR service.
- Database/vector search: local PostgreSQL + pgvector through Docker.
- File storage: local development storage behind a storage interface.
- No hosted inference or paid OCR API in the default development path.

### Ollama authentication clarification

The local Ollama server at `http://localhost:11434` does **not** require an API key. Download a model and call the local server.

If a client library insists on an `apiKey` field for OpenAI compatibility, a placeholder such as `ollama` may be supplied because the local server ignores it. This is not a real secret and must not be confused with a hosted Ollama API key.

Hosted/cloud inference at `https://ollama.com/api` or `https://ollama.com/v1` is a separate option and requires an API key. Do not use it by default during development.

### Configuration requirements

- Configure provider, base URL, model name, dimensions, timeout, and generation settings through environment variables/application configuration.
- Provide a safe `.env.example` containing placeholders/defaults only; never commit real secrets.
- Make local and hosted providers selectable through configuration without changing business logic.
- Clearly label any provider or model that may incur charges.
- Do not silently fall back from a local model to a paid cloud model.
- Add startup/health diagnostics that clearly report a missing local service or unavailable model without leaking secrets.

### Model and license checks

“Free to download” does not automatically mean unrestricted commercial use. Before production or redistribution, verify the model and library licenses, acceptable-use terms, hardware requirements, and deployment constraints. Record the chosen model name, version/revision, license, and embedding dimensions.

### Local-first acceptance test

A developer should be able to clone the repository, start the documented local dependencies, download the selected models, configure local environment variables, and run the application without creating any paid-provider account or API key.

---

## 12. First action

> **Status (2026-11-23):** M0 is done. M1 is implemented on branch `feature/m1-integration-kb-foundation` and awaiting approval. See [`PROJECT_PROGRESS.md`](../PROJECT_PROGRESS.md). The original instructions below are kept for reference.

Start with **M0 only**.

Inspect the repository and report:
- Current stack and project structure.
- Existing reusable components.
- Proposed module/package boundaries.
- Database/storage/model/OCR runtime recommendations.
- Initial API conventions.
- Risks and open decisions.

Do not implement M1 or later. Do not create the entire application in one pass. Present the M0 plan and wait for approval.
