# Workflow — Document Upload, Page Review, Submission and Ingestion

> Status: upload, validation and native extraction **implemented** (M2 slices 2b–2c, 2026-11-24); OCR in 2d; review,
> submit and snapshot in M3; ingestion in M4; embeddable review UI after M4.
> Decision: [ADR-0006](../adr/0006-tenant-owned-document-review.md).

## 1. Purpose

Page-by-page review is a **core product feature** of the platform, not an internal OCR quality check. The company that owns a document decides exactly what the RAG engine is allowed to know from it: it sees each original page next to the extracted text, corrects mistakes, and submits. **Nothing becomes searchable until the company submits it, and nothing is marked indexed until ingestion succeeds.**

## 2. Example

An HRIS tenant uploads a 10-page "HR Policies" PDF to its "Policies" KB:

1. MimoRag stores the original file unchanged and processes the 10 pages independently. Pages 1–8 have native text; pages 9–10 are scanned and go through OCR.
2. An HR reviewer, authenticated by HRIS and calling MimoRag with a user assertion that maps to `DOCUMENT_REVIEW`, steps through the pages. Each page image is shown beside its editable text. They fix an OCR error in a leave entitlement number on page 9 and exclude the blank page 10.
3. A user with `DOCUMENT_SUBMIT` submits the document. MimoRag checks that every page is reviewed or excluded, freezes an **immutable approved snapshot**, and starts ingestion.
4. After chunking, embedding and indexing succeed, this version becomes the **active** searchable version.

## 3. Lifecycle

```text
Upload ──► Extraction ──► Review (draft) ──► Submit ──► Approved snapshot ──► Ingestion ──► Indexed
 keep the   per page:      page image +       validate    immutable            normalize,     active
 original   native or OCR  editable text      the rules                        chunk, embed,  version
                                                                                index
```

### 3.1 Document version states

```text
UPLOADED ─► EXTRACTING ─► IN_REVIEW ─► SUBMITTED ─► INGESTING ─► INDEXED ─► SUPERSEDED
                │                                       │
                ▼                                       ▼
        EXTRACTION_FAILED (retryable)          INGESTION_FAILED (retryable;
                                                previous version stays active)
```

### 3.2 Page review states

| State | Meaning | Set by |
|---|---|---|
| `PENDING_REVIEW` | Not yet reviewed | System, after extraction |
| `FLAGGED` | The system found a concern (low OCR confidence, hidden or invisible text, bidi override characters, extraction warning) | System |
| `REVIEWED` | Accepted as-is, or edited and accepted | Reviewer |
| `EXCLUDED` | Deliberately left out of the index (cover, blank or irrelevant page) | Reviewer |

A `FLAGGED` page must be resolved explicitly (reviewed or excluded) before submission.

## 4. Data model (conceptual)

Every record is tenant-scoped (`integration_id`, protected by RLS) and is either append-only or immutable after creation.

| Record | Description | Mutability |
|---|---|---|
| `Document` | Logical document in a KB, with a stable ID across versions | Metadata only |
| `DocumentVersion` | One uploaded file: storage key, SHA-256, size, page count, uploader | Immutable |
| `ExtractionRun` | One extraction pass over a version: engine/model versions, timings | Immutable; reprocessing creates a new run |
| `PageExtraction` | Machine output per page: class (`NATIVE_TEXT` / `OCR_REQUIRED` / `MIXED` / `EMPTY` / `FAILED`), native text (always kept), trusted result text, line boxes, image regions, detected scripts, quality signals, warnings. Implemented in 2c as `page_extraction` (one row per run and page; insert-only) with `extraction_run` | **Read-only** |
| `PageRevision` | A human edit: new text, author (external user ID), timestamp, base (extraction or previous revision) | Append-only; never overwrites the extraction |
| `PageReviewState` | Current state per page, with an optimistic-lock version | Mutable while the version is `IN_REVIEW` |
| `ApprovedSnapshot` | Created on submit: for each page, the approved text (revision or extraction), its content hash and exclusions; plus submitter and timestamp | **Immutable** |
| `IngestionRun` | Indexing of one snapshot with one chunking profile and one embedding model: status, attempts, failure reason, timings | Status transitions only |

## 5. Rules

### 5.1 Review

- The original extraction is never modified. Edits are stored as revisions, and the API exposes `originalText` and `currentText` separately.
- Concurrent editing is detected with optimistic locking (`version` / `If-Match`), so edits are never silently lost.
- Edits have size limits. They are normalized to Unicode NFC, and invisible or bidi-control characters are **flagged**, never silently removed.
- Every edit, exclusion and state change is audited.

### 5.2 Submit

- Every page must be `REVIEWED` or `EXCLUDED`, and none may remain `FLAGGED`.
- Editing content (`DOCUMENT_REVIEW`) and submitting (`DOCUMENT_SUBMIT`) are **separate permissions**, which allows richer approval workflows later.
- **Optional per-KB setting:** when enabled, the submitter must differ from the authors of the page edits. It is disabled by default. When enabled, a different user with `DOCUMENT_SUBMIT` must submit.
- Submission locks the draft and freezes exactly the approved revisions. Later changes start a new review round on a new version or draft, which produces a new snapshot.

### 5.3 Ingestion

- `indexing` consumes only `ApprovedSnapshot`s, through a read-only port. It has no access to drafts.
- Cleaning and normalization run **after** approval. They are derived, recorded and repeatable; the approved text itself is never altered.
- Ingestion is idempotent, keyed by `(snapshotId, chunkingProfile, embeddingModel)`. Retries never create duplicate active chunks.
- **Active-version switch:**
  - the previous version stays searchable while the new one is ingested;
  - the switch happens in one transaction, **only after** ingestion completes;
  - the old chunks are then invalidated or removed;
  - if ingestion fails, the previous version stays active and the new version shows `INGESTION_FAILED` with a reason.
- **Re-ingestion without re-review:** a new chunking profile or embedding model creates a new `IngestionRun` from the **same snapshot**.

## 6. Module interaction

```text
ingestion ──(ExtractionCompleted)──► review ──(SnapshotApproved)──► indexing
   │  PdfTextExtractor / OcrProvider         │ owns revisions,            │ reads snapshots only
   │  stores originals and page images       │ states, snapshots          │ EmbeddingProvider / VectorStore
```

- Events go through a **transactional outbox** ([ADR-0007](../adr/0007-async-jobs-and-transactional-outbox.md)), so a crash cannot lose a step.
- OCR, PDF extraction, review and ingestion are decoupled behind interfaces and events. None of them calls another's internals.

## 7. Security and tenancy

- Every endpoint requires the integration service credential **and** a user assertion with the relevant permission. Platform admins have **no** default access to tenant document content ([security-architecture.md §2.7](../security-architecture.md)).
- Page images and original files are served only through authorized endpoints that check tenant, KB and permission. A document from another tenant returns 404.
- Approved text is still **untrusted data** for the LLM (prompt-injection defense applies).
- Background extraction and ingestion jobs carry the tenant, set the RLS context, and re-check that the integration and KB are still active.

## 8. Performance and storage

- Page images are rendered on demand at bounded resolution, then cached with a size cap and eviction (disk space is limited on the development machine).
- Page lists and review payloads are paginated. Large page texts are fetched per page.
- Per-page extraction latency is recorded by method (native vs OCR). Ingestion records time per stage (normalize, chunk, embed, index).

## 9. Review UI delivery (decided)

| Phase | Delivery |
|---|---|
| M3 | **API-first.** Connected systems can build their own review screens on the review API. |
| After M4 (separate milestone) | **Embeddable review UI**, reusable across HRIS, CRM and ERP. Launched with a short-lived review session token scoped to one tenant, one document and one user, with replay protection ([security-architecture.md §2.8](../security-architecture.md)). |

Review business logic, authorization and validation live in the `review` module and are independent of any frontend.

## 10. Indicative endpoints (to be finalized in M2/M3)

| Method and path | Purpose | Permission |
|---|---|---|
| `POST /api/v1/knowledge-bases/{kbId}/documents` | Upload a new document or a new version | `DOCUMENT_UPLOAD` |
| `GET /api/v1/ingestion-jobs/{jobId}` | Job status | `DOCUMENT_UPLOAD` / `DOCUMENT_REVIEW` |
| `GET /api/v1/documents/{documentId}/review` | Review summary: pages, states, flags | `DOCUMENT_REVIEW` |
| `GET /api/v1/documents/{documentId}/pages/{n}` | Original extraction and current text | `DOCUMENT_REVIEW` |
| `GET /api/v1/documents/{documentId}/pages/{n}/image` | Rendered page image | `DOCUMENT_REVIEW` |
| `PUT /api/v1/documents/{documentId}/pages/{n}/text` | Save an edit (new revision; `If-Match`) | `DOCUMENT_REVIEW` |
| `POST /api/v1/documents/{documentId}/pages/{n}/review` | Mark reviewed or excluded | `DOCUMENT_REVIEW` |
| `POST /api/v1/documents/{documentId}/submit` | Validate and create the snapshot; start ingestion | `DOCUMENT_SUBMIT` |
| `GET /api/v1/documents/{documentId}/index-status` | Ingestion and active-version status | `KB_READ` |
