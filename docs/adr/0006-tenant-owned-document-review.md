# ADR-0006: Tenant-owned document review with immutable snapshots

- **Status:** Accepted
- **Date:** 2026-11-23

## Context

Extracted text, especially OCR output for Arabic, mixed-script or low-quality scans, contains errors in numbers, dates and names. The company that owns a document is the authority on its content. Reviewing and approving content is a product feature, not internal quality control.

## Decision

- **Pipeline:** upload → per-page extraction (native text first, OCR only when needed) → **tenant review** (page image beside editable text) → **submit** → **immutable approved snapshot** → ingestion (normalize, chunk, embed, index) → active version.
- **Original data is preserved:** original files and machine extractions are never modified. Human edits are append-only revisions.
- **Separate permissions:** `DOCUMENT_REVIEW` (edit) and `DOCUMENT_SUBMIT` (approve).
- **Optional per-KB different-submitter rule,** disabled by default.
- **Reading approved content:** `indexing` reads only approved snapshots. Ingestion is idempotent. A version becomes active only after ingestion succeeds, and the previous version stays active until then.
- **Versioning:** re-upload creates a new version and a new review round. A changed chunking profile or embedding model re-ingests the existing snapshot without re-review.
- **No platform reviewer role.** Platform admins have no default access to tenant content.
- **Review UI:** API-first in M3; an embeddable review UI as a separate milestone after M4, launched with short-lived review session tokens scoped to one tenant, one document and one user.

Full workflow: [workflows/document-review.md](../workflows/document-review.md).

## Consequences

- Retrieval quality and trust improve, and every answer traces back to company-approved text.
- Ingestion latency includes human review time; that is the intended trade-off.
- More storage is needed (originals, extraction runs, revisions, snapshots, page-image cache). Retention policies come in M11.
- M3 grows to include submit and snapshot. M4 is triggered by `SnapshotApproved` events.
