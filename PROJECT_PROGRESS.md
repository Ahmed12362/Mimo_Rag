# Project Progress

Milestone scope and decisions: [docs/roadmap.md](docs/roadmap.md). This file records what was delivered, how it was verified, and the measured baselines.

| Milestone | Status | Branch |
|---|---|---|
| M0 — Architecture baseline | ✅ Done (commit `d963e59`) | `chore/m0-architecture-baseline` → `main` |
| M1 — Integrations, KBs, identity, RLS | ✅ Done (commit `5db21e9`) | `main` |
| M1 hardening — key lifecycle, KB-restricted keys, review fixes | ✅ Done (commits `fa683c7`, `87c8105`) | `fix/m1-hardening` → `main` |
| M2 slice 2a — OCR benchmark | ✅ Done (commit `4f897f1`); report: [docs/benchmarks/ocr-benchmark-2a.md](docs/benchmarks/ocr-benchmark-2a.md) | `feature/m2-pdf-upload-extraction` |
| M2 slice 2b — upload, storage, job queue | ✅ Done (committed on the feature branch) | `feature/m2-pdf-upload-extraction` |
| M2 slice 2c — native text extraction | ✅ Done (commit `20c69da`) | `feature/m2c-native-extraction` |
| M2 slice 2d — OCR service | ✅ Done, awaiting commit approval; results: [docs/benchmarks/ocr-2d-results.md](docs/benchmarks/ocr-2d-results.md) | `feature/m2d-ocr` |
| M2 slice 2e, M3 – M11 | Not started | — |

---

## M2 slice 2d — OCR service

**Date:** 2026-11-24/25 · **Decisions:** D-42 to D-47 (2d decisions A–D, requirements 1–6, A1/B1 follow-up) ·
ADR-0014 · **Migration:** V8

### Delivered

- **OCR service** (`ocr-service/`, image `mimorag-ocr:2d`, 2.41 GB): FastAPI, one OCR worker process × 4 threads
  (spawned, killed on time-out, recycled after 500 requests), bounded admission (503 + `Retry-After`), PNG header
  limits before decoding, service token on every request, problem+json errors, no text in logs; gated hybrid pipeline
  `gated-hybrid/2d.3` (insert-only token recovery, flags, orientation on every page with upside-down retry).
- **Application:** `OCR_DOCUMENT` job enqueued atomically with the extraction result; per-kind worker pool and
  per-integration limit (1); PDFBox rendering at 300 DPI with bilinear image scaling (pages, or image regions of
  `MIXED` pages); `HttpOcrProvider` (no redirects, 5 MB response cap, HTTP/1.1) and `OcrResponseValidator`; per-page
  commits, resume without duplicates; OCR lines overlapping native text dropped on mixed pages; version states
  `OCR_RUNNING`, `OCR_COMPLETED`, `OCR_FAILED` (retryable).
- **Database (V8):** `ocr_run`, `page_ocr` (insert-only for the runtime role, unique per run and page, forced RLS).
- **API:** page detail gains a separate `ocr` object (`reviewState: UNREVIEWED`, never `text`); extraction summary gains
  `ocr`; job view gains `nextJobId`, version view `ocrJobId`; OCR jobs visible and retryable through the job API.
- **Config:** `MIMORAG_OCR_ENABLED` (default false), `MIMORAG_OCR_BASE_URL`, `MIMORAG_OCR_TOKEN`; compose profile `ocr`
  (loopback port, read-only root, non-root, no capabilities, 2 GB / 4 CPUs).
- **Docs:** ADR-0014 (contract, limits, failure handling, retries, production security vs development networking),
  `ocr-service/README.md`, results report, README, architecture, security architecture, roadmap, Postman (2.9).

### Acceptance criteria

| # | Criterion | Result | Evidence |
|---|---|---|---|
| 1 | Independently deployable, decoupled service | ✅ | separate image and compose profile; only `OcrProvider` knows HTTP; contract v1 tested against captured real responses |
| 2 | Strict limits (size, pixels, time, concurrency) | ✅ | ADR-0014 limits table; `test_png.py`, `test_api.py`, `HttpOcrProviderTest` |
| 3 | Responses validated; confidence, engine, warnings, metadata kept | ✅ | `OcrResponseValidator`; `OcrIntegrationTest` (invalid answers fail only their page) |
| 4 | Idempotent retries, crash recovery, no duplicates | ✅ | `crashedOcrResumesAfterTheStoredPagesWithoutDuplicates`, concurrent duplicate insert → 1 row, retry after `OCR_FAILED` |
| 5 | OCR kept apart from approved content | ✅ | `text` stays null, `UNREVIEWED`, read-only rows, ArchUnit rule for RAG modules |
| 6 | Contract and failure handling documented | ✅ | ADR-0014 |
| 7 | Accuracy: every profile ≤ +1.5 points vs 2a (hard) | ✅ | vector +0.72, clean300 +0.55, lowres150 −0.06, noisy200 −0.51, rotated300 −0.65 |
| 8 | Orientation: upright no worse; upside-down within 1 point of upright | ⚠️ upright ✅; upside-down 20.10 % vs 19.60 % — **known limitation accepted 2026-11-25** (diacritised page; other six pages 9.0 % vs 9.4 % upright) | ocr-2d-results.md §1 |
| 9 | 1 × 4 vs 2 × 2 measured; default stays 1 × 4 | ✅ | 8.5 vs 8.4 pages/min; 1 140 vs 1 963 MiB |

### Automated tests — `./mvnw test` → **BUILD SUCCESS, 337 tests, 0 failures** (2c: 314)

New: `OcrIntegrationTest` (11, against `FakeOcrServer`), `HttpOcrProviderTest` (10, incl. 3 captured real responses),
`OcrDisabledHandOffIntegrationTest` (1), one ArchUnit rule; Python service: 39 tests. Two 2c assertions now accept
`AWAITING_OCR`/`OCR_RUNNING`/`OCR_COMPLETED` because OCR is enabled in the suite (planned behaviour change). Opt-in:
`-Pocr-it` (accuracy gate) and `-Pocr-bench` (throughput).

**Defects found before commit:** (1) the Java contract expected `width`/`height` at the top level while the service
returns `image` — every real page would have failed validation (now covered by captured responses); (2) a service-side
page time-out failed the whole job (now that page only); (3) JDK client attempted an HTTP/2 upgrade on every request;
(4) PDFBox nearest-neighbour enlargement of scans broke the accuracy gate (bilinear adopted); (5) the orientation
classifier misses upside-down PDFBox renderings (upside-down retry added); (6) the gate's own metric ordered rotated
pages in the wrong frame (fixed; thresholds unchanged).

### Measurements (measured, not estimated)

| Measurement | Value |
|---|---|
| OCR throughput, 1 × 4, 2 pages in flight | 8.5 pages/min |
| OCR container peak memory, 1 × 4 / 2 × 2 | 1 140 / 1 963 MiB |
| API p95 while OCR runs | 102 ms (1 × 4) |
| Rendering per page (PDFBox, bilinear) | ≈ 210 ms |
| Orientation classifier per page | mean 22 ms, max 45 ms |
| Orientation overhead incl. extra OCR passes | +647 ms per page (+24 %), single run |

### Known limitations

- Fully diacritised upside-down pages are not corrected (recogniser limitation; accepted).
- OCR results cannot be corrected or approved yet (M3).
- Versions that reached `AWAITING_OCR` while OCR was disabled are not enqueued automatically when OCR is enabled later.
- The service accepts one token; rotation needs a coordinated restart.

---

## M2 slice 2c — native text extraction

**Date:** 2026-11-24 · **Decisions:** D-37 to D-41 (2c decisions a–e) · ADR-0013 · **Migration:** V7

### Delivered

- **Line builder** (`LineBuilder`, pure Java): lines from glyph positions; marks attached by stream order; spaces and
  tab cell breaks from gaps; paragraph direction from alignment; visual-to-logical order with left-to-right runs kept.
- **Page analysis:** `TextQuality` (broken characters, unmapped glyphs, presentation forms, reversed Arabic, letter
  share, bidi and zero-width characters) and `PageClassifier` (`NATIVE_TEXT`, `OCR_REQUIRED`, `MIXED`, `EMPTY`,
  `FAILED`; 12 warnings).
- **PDFBox adapter:** `GlyphCollector` (render mode, fill colour incl. registered colour operators, off-page),
  `ImageRegionCollector` (image boxes in rotated top-left page coordinates, filled shapes),
  `PdfBoxNativeTextExtractor` (resource cache off, temp-file cache, per-page timeout with reopen).
- **Extraction service:** one run per job, batches of 20 pages committed with the run's counters, resume after the
  highest committed page, failed pages isolated; the job continues from validation into extraction.
- **Database (V7):** `extraction_run`, `page_extraction` (insert-only for the runtime role, unique per run and page,
  `result_text` only when trusted), new version states `EXTRACTING`, `EXTRACTED`, `AWAITING_OCR`,
  `EXTRACTION_FAILED`.
- **API:** `GET /documents/{id}/extraction`, `GET /documents/{id}/pages`, `GET /documents/{id}/pages/{n}`; job status
  gains `progress`.
- **Fixtures:** 9 synthetic digital PDFs + ground truth (124 KB) in `src/test/resources/corpus/synthetic/digital/`.
- **Docs:** ADR-0013, README, architecture, security architecture, workflow, roadmap (D-37 to D-41), Postman (2.6–2.8).

### Acceptance criteria (all measured)

| # | Criterion | Result | Evidence |
|---|---|---|---|
| 1 | Error rate on the fixtures: English, Naskh, Sans 0%; mixed ≤ 2%; table ≤ 10%; Amiri ≤ 5%; diacritics reported | ✅ | `NativeExtractionAccuracyTest` and the same over HTTP: Amiri, table, Latin-in-Arabic **0.00%**, alternating lines **1.04%**, diacritics **1.38%** (PDFBox alone: 22.3%, 53.2%, 14.0%, 7.8%, 72.4%) |
| 2 | Classification of every class; unreliable text → `OCR_REQUIRED` and not exposed | ✅ | `PageClassifierTest` (15), `PdfBoxNativeTextExtractorTest` (7); HTTP: 43 MB scan → `OCR_REQUIRED`, `text: null` |
| 3 | Hidden content flagged, text kept | ✅ | Invisible, white, tiny, off-page, bidi, zero-width: unit, PDF-level and HTTP |
| 4 | 500 pages: flat memory, API within 2×, resume without duplicates | ✅ | See measurements; `crashedExtractionResumesAfterTheLastCommittedPageWithoutDuplicates`, concurrent duplicate insert → 1 row |
| 5 | Page text needs `DOCUMENT_REVIEW`; read-only rows; identical 404s; RLS coverage | ✅ | `NativeExtractionIntegrationTest` (upload-only: 200 summary, 403 pages; other tenant 404; UPDATE/DELETE → permission denied); `RlsCoverageIntegrationTest` |
| 6 | Quality gates | ✅ | 314 tests, real HTTP checks, docs, Postman |

### Automated tests — `./mvnw clean verify` → **BUILD SUCCESS, 314 tests, 0 failures** (2b: 267)

New: `LineBuilderTest` (14), `PageClassifierTest` (15), `PdfBoxNativeTextExtractorTest` (7),
`NativeExtractionAccuracyTest` (1, 8 page targets), `NativeExtractionIntegrationTest` (10). Three 2b assertions changed
from `VALIDATED` to `EXTRACTED`, because the job now continues into extraction (planned behaviour change, not a
weaker test).

**Defects found by these tests and fixed before commit:** (1) reads outside a transaction saw no rows under RLS
(the isolation worked; the code was wrong); (2) white text was never detected because PDFBox's text engine ignores
colour operators; (3) a short page of broken characters passed as trusted text (the check now applies from 5
characters); (4) page direction by letter count was wrong on an Arabic page with more Latin letters (now by alignment).

### Real HTTP checks (local profile; V7 applied to the existing database with 2b data)

```text
ar_policy_amiri     → EXTRACTED, NATIVE_TEXT, CER 0.00%
mixed_ar_en_naskh   → EXTRACTED, NATIVE_TEXT, CER 0.00%, warnings [MIXED_DIRECTION_LINE]
ar_table_naskh      → EXTRACTED, NATIVE_TEXT, CER 0.00%
mixed_lines_sans    → EXTRACTED, NATIVE_TEXT, CER 1.04%, warnings [MIXED_DIRECTION_LINE]
ar_diacritics_amiri → EXTRACTED, NATIVE_TEXT, CER 1.38%
43 MB image page    → AWAITING_OCR, OCR_REQUIRED, text null, image coverage 1.00
upload-only key     → /extraction 200, /pages 403, /pages/1 403
500-page text PDF   → 202 in 74 ms; EXTRACTED; progress 500/500; listing totalItems 500
```

### Measurements (local laptop; measured, not estimated)

| Measurement | Value |
|---|---|
| 500-page text PDF (40 lines per page): upload → extracted | 7.2 s (14.5 ms per page, validation included) |
| Per-page extraction time (stored `duration_ms`, 500 pages) | p50 6 ms · p95 17 ms · max 107 ms |
| `GET /knowledge-bases/{id}` idle vs during extraction | p95 15.5 ms vs 16.7 ms (max 32 ms, n = 235) |
| Heap after full GC, before vs after a second 500-page extraction | 54 MB vs 54 MB (nothing retained per page) |
| Peak heap (includes uncollected garbage): 5-page vs 500-page document | 82 MB vs 113 MB |
| Temporary files left after the run | 0 |

### Implementation review

1. **Clean Code / SOLID:** the PDF library only collects facts; interpretation lives in small pure classes
   (`LineBuilder`, `TextQuality`, `PageClassifier`) with their own tests; `KnowledgeBaseGate` removes a duplicated
   access check shared by the document and page queries.
2. **Intentional choices:** page rows via JDBC (jsonb, arrays, `ON CONFLICT`), runs via JPA; tab characters separate
   table cells (scored as spaces); line boxes stored as character offsets into the native text.
3. **Module dependencies:** unchanged (`ingestion → knowledgebase.application, jobs.application, shared`).
4. **Limitations (measured or known):**
   - one remaining ambiguous case (1.04%): a full-width English line on an Arabic page puts its final period first;
   - multi-column layouts on one baseline read across columns (tab-separated);
   - thresholds tuned on synthetic pages; must be checked on authorised real documents;
   - producers that write right-to-left text with marks in unusual stream orders were not available to test.
5. **Not measured:** extraction of real HR documents, photocopies or phone photos (no authorised samples yet).

---

## M2 slice 2b — upload, storage and job queue

**Date:** 2026-11-24 · **Decisions:** D-26 to D-29, D-35, D-36 · ADR-0011 (implementation notes) · **Migration:** V6

### Delivered

- **Database (V6):** `document`, `document_version` and `job` (all FORCE RLS, composite tenant foreign keys); worker
  functions `claim_jobs` (SKIP LOCKED, lease reclaim, per-integration fairness), `extend_job_lease`,
  `fail_abandoned_jobs`; the runtime role cannot delete jobs.
- **`jobs` module (new):** `JobQueue` (enqueue joins the caller's transaction), `JobLeaseStore`, `JobRunner`
  (bounded pool per job kind, heartbeats, credential re-validation, scope narrowing, retries with backoff,
  cancellation, crash sweep, graceful shutdown), `JobHandler`, `JobFailure` (permanent, transient, revalidation).
- **`ingestion` module (new):**
  - `POST /api/v1/knowledge-bases/{kbId}/documents` (202 / 200 duplicate);
  - `GET /api/v1/knowledge-bases/{kbId}/documents`, `GET /api/v1/documents/{id}`;
  - `GET /api/v1/ingestion-jobs/{id}`, `POST /api/v1/ingestion-jobs/{id}/retry`;
  - local storage (UUID keys, atomic move, read-only originals, stale temp cleanup);
  - PDFBox inspector (encrypted, corrupt, empty, page limit, 60 s time limit, temp-file-only cache);
  - `Idempotency-Key`, duplicate detection (D-28), quotas (D-29), file name sanitising, audit events.
- **Shared:** error codes `UPLOAD_QUEUE_FULL` (429 + `Retry-After`) and `STORAGE_QUOTA_EXCEEDED` (413); 415 for
  content that is not a PDF.
- **Integration:** `ApiKeyAuthenticator.revalidate(keyId)`, so jobs re-check credentials with the same rules.
- **Configuration:** typed `mimorag.ingestion.*` and `mimorag.jobs.*` settings with defaults; multipart limits are
  derived from them (spooled to disk from the first byte); `MIMORAG_INGESTION_STORAGE_ROOT` in `application.yml` and
  `.env.example`; Maven clean forced so read-only test files do not block `mvn clean` on Windows.
- **Docs:** README (upload API, settings), architecture (module map, ArchUnit rules 12–14, async processing),
  security architecture (implemented controls), ADR-0011 implementation notes, local environment, roadmap (status,
  D-26 to D-36, Q-04, Q-07), Postman collection [docs/postman/](docs/postman/).

### Acceptance criteria

| # | Criterion | Result | Evidence |
|---|---|---|---|
| 1 | Valid PDF → 202 with ids; job reaches `VALIDATED` with the page count; 1 MB p95 < 1 s | ✅ | `DocumentUploadIntegrationTest`; HTTP: Arabic PDF and 300-page PDF validated; 1 MB upload p95 90 ms |
| 2 | Non-blocking: response before validation; GET p95 within 2× baseline under 5 × 43 MB uploads; heap independent of file size | ✅ | Upload returns `UPLOADED`/202 before the job runs; GET p95 12.4 ms under load vs 14.3 ms idle; heap max 71 MB (43 MB uploads) vs 75 MB (20 KB uploads) |
| 3 | Rejections with clear codes | ✅ | 415 not a PDF (also when named `.pdf`); 413 over 50 MB; empty file → **400** (see deviations); worker codes `PDF_ENCRYPTED` (incl. owner-only), `PDF_CORRUPT`, `PDF_EMPTY`, `PDF_TOO_MANY_PAGES` |
| 4 | Duplicates and idempotency, including concurrent | ✅ | Same file → 200 `DUPLICATE`; 4 concurrent identical uploads → 1 document; key replay → `REPLAYED`; key reuse with other content → 409 |
| 5 | Queue: no double claims, lease reclaim, retries, revalidation cancel, safe retry | ✅ | `JobQueueIntegrationTest` (10), `JobRunnerTest` (13), `IngestionJobLifecycleIntegrationTest` (revoked key, archived KB) |
| 6 | Quotas: 21st unfinished job → 429 + `Retry-After`; storage → 413 | ✅ | `IngestionJobLifecycleIntegrationTest` |
| 7 | Tenant isolation, KB-restricted keys, RLS | ✅ | `DocumentIsolationIntegrationTest` (identical 404 bodies), `RlsCoverageIntegrationTest`, no rows without tenant context |
| 8 | Storage safety | ✅ | `LocalDocumentStorageTest` (traversal, forged handles, read-only, cleanup); temp and spool folders empty after the HTTP run |
| 9 | Quality gates | ✅ | See below |

### Automated tests — `./mvnw clean verify` → **BUILD SUCCESS, 267 tests, 0 failures** (2a end: 187)

New: `DocumentUploadIntegrationTest` (15), `DocumentIsolationIntegrationTest` (5),
`IngestionJobLifecycleIntegrationTest` (4), `JobQueueIntegrationTest` (10, the concurrency test repeated 5 times with
6 workers and 100 jobs), `JobRunnerTest` (13), `JobPropertiesBindingTest` (3), `PdfBoxInspectorTest` (8),
`LocalDocumentStorageTest` (9), `UntrustedTextTest` (6); `ArchitectureRulesTest` +3 rules; RLS classification of the
new tables. The full suite was run three times: stable.

**Defects found by these tests and fixed before commit:** (1) a job could be claimed twice under concurrency (see
ADR-0011 notes); (2) one claim call could give one tenant every free worker; (3) a failed job could briefly show its
document as still validating (final state and reaction now commit together).

### Real HTTP checks (local profile, Docker Compose PostgreSQL; V6 applied to the existing M1 database)

```text
POST Arabic PDF (benchmark corpus, title in Arabic)    → 202 CREATED in 0.20 s → job SUCCEEDED, VALIDATED, pages=1
POST 300-page PDF                                      → 202 in 0.06 s → SUCCEEDED, pages=300
POST 43 MB PDF                                         → 202 in 1.37 s → SUCCEEDED, pages=1
POST 501-page PDF                                      → job FAILED PDF_TOO_MANY_PAGES (attempts=1, retryable=false)
POST encrypted PDF                                     → job FAILED PDF_ENCRYPTED; POST …/retry → 409
POST HTML file named .pdf                              → 415 UNSUPPORTED_MEDIA_TYPE
POST same Arabic PDF again                             → 200 DUPLICATE duplicateOf=<first document>
POST with Idempotency-Key twice / then other file      → 202 REPLAYED (same job) / 409
Other integration: GET document, GET job, list KB, upload into KB → 404, 404, 404, 404
Arabic file name and title sent as UTF-8 multipart    → stored and returned unchanged
```

### Performance (local, sequential unless stated)

| Measurement | p50 | p95 | Note |
|---|---|---|---|
| `GET /api/v1/knowledge-bases/{id}`, idle | 11.9 ms | 14.3 ms | M1: 13.1 / 16.0 ms |
| Same, during 5 concurrent 43 MB uploads and their jobs | 9.2 ms | 12.4 ms | n = 1000; max 66 ms |
| Upload 20 KB | 58.7 ms | 67.4 ms | |
| Upload 1 MB | 69.0 ms | 90.1 ms | |
| Upload 43 MB | 1.01 s | 1.56 s | |
| Upload 43 MB, 5 concurrent | 9.1 s | 9.7 s | Disk-bound (each byte is written twice, see debt) |
| Job queued → validated, 43 MB, 5 concurrent | 1.43 s | 2.42 s | Includes SHA-256 re-check and PDFBox open |
| Heap used (jstat), 20 KB vs 43 MB uploads | 75 MB vs 71 MB max | | Does not grow with file size |
| Startup (V6 applied) | 13.6 s | | M1: 12.4 s |

### Implementation review

1. **Clean Code / SOLID:**
   - Small, single-purpose classes: upload use case, query service, job handler, storage, inspector, runner.
   - Interfaces exist only at real variation points (`DocumentStorage`, `PdfInspector`, `JobQueue`/`JobLeaseStore`,
     `JobHandler`).
   - The domain enforces state transitions (`DocumentVersion`).
2. **Intentional deviations:**
   - An empty file returns **400** `VALIDATION_FAILED`, not 413 as written in the plan's criterion 3; 413 means "too
     large".
   - Job timestamps use the database clock (all workers agree on leases); other tables use the application clock.
   - A small `application.yml` addition (`mimorag.ingestion.storage-root` placeholder), so `.env` can set it.
3. **Module dependencies:** `ingestion → knowledgebase.application, jobs.application, shared`;
   `jobs → integration.application, shared`. No cycles (ArchUnit).
4. **Tests:** 80 new; commands and results above.
5. **Technical debt / follow-ups:**
   - Each upload is written to disk twice (servlet multipart spool, then our hashing copy). Streaming the part
     directly would halve disk I/O for large files.
   - A PDFBox parse that hits the 60 s limit may keep its thread running in the background until it finishes.
   - The queue quota is checked before the insert, so parallel uploads can exceed it by a few.
   - Demo uploads from the HTTP check (about 476 MB) remain in `D:\mimorag-data` and the local database.

### Open items

- **Q-07 `jti` replay protection** for user assertions on write operations (uploads accept assertions): decided
  2026-11-24 to add it as a small slice **before M3** (review edits).

---

## M1 hardening — findings from the M1 review

**Date:** 2026-11-23 · **Decisions:** D-20 to D-25 · ADR-0011, ADR-0012 · **Migration:** V5 (V2–V4 untouched, already on `main`)

### Delivered (by review finding)

| Finding | Fix |
|---|---|
| **H1** Admin could mint content keys | Admins issue only bootstrap keys (`KEY_ROTATE`, 24 h, single use, authenticate only for `POST /api/v1/integration/api-keys`, never with user assertions). Integrations create, rotate and revoke their own keys within an admin-set scope ceiling (`allowedScopes`). The old admin `POST …/api-keys` is removed. |
| **Q-06** Optional expiry | Expiry is mandatory: default 90 days, maximum 365 days (code + DB CHECK). Overlapping keys; `X-Api-Key-Expires-At` header in the last 14 days. |
| **M6** Tenant-wide keys only | Optional `knowledgeBaseIds` restriction (composite tenant FK), enforced for services and relayed users. Restricted keys cannot create KBs or create broader keys. |
| **M1** Transaction-manager edge cases | Failure applying tenant settings rolls back and releases all thread-bound resources. Switching context inside an active transaction is rejected. |
| **M2** Public context binding | ArchUnit: only `access`, `shared.access`, `shared.persistence` and `jobs` may bind an access context. |
| **M5 / L3** JWKS robustness | Explicit 5 min cache and at most two re-fetches per 30 s for unknown key ids (Nimbus allows two per interval) (Nimbus `JWKSourceBuilder`); 50 KB size limit; no redirects; RSA ≥ 2048 bits (remote and inline); unreachable JWKS → 503. |
| **M7** Relayed user not attributable to a key | `AccessContext.credential` carries the key; `audit_event.api_key_id` on every row. |
| **M8** No RLS guard for new tables | `RlsCoverageIntegrationTest`: every table must be classified; tenant tables must FORCE RLS; every RLS table needs a policy. |
| **L1** Race → 500 | `saveAndFlush` inside `try` for integration, KB, grants and key creation → 409. |
| **L2** DB round trip per request | Last-use recording throttled in memory (once per key per 5 min per JVM). |
| (found during testing) | The application clock now ticks in microseconds, matching PostgreSQL precision, so API timestamps equal stored values. |
| Environment | `.wslconfig` with `memory=6GB`, `processors=6`, `swap=2GB`, `autoMemoryReclaim=gradual` (Docker now reports 6 GB / 6 CPUs). Documented in `docs/local-environment.md`. |

### Automated tests — `./mvnw clean verify` → **BUILD SUCCESS, 187 tests, 0 failures** (M1: 147)

New or extended test classes:
- `BootstrapKeyRotationIntegrationTest` (11): hand-over, single use, concurrent use (exactly one success), no content access, ceiling, immediate ceiling reduction, expiry bounds and header, overlap rotation, scope requirement, cross-tenant revoke, audit.
- `KnowledgeBaseRestrictedKeyIntegrationTest` (6).
- `JwksRotationIntegrationTest` (5): rotation in, rotation out after cache expiry, unknown-kid burst bounded, outage → 503, weak key ignored.
- `TenantAwareJpaTransactionManagerIntegrationTest` (4), `RlsCoverageIntegrationTest` (2), `ApiKeyAuthenticatorTest` (3).
- Extended: `RowLevelSecurityIntegrationTest` (+2: key-table policies and DB CHECKs), `KnowledgeBaseApiIntegrationTest` (+1: concurrent duplicates), `UserAssertionIntegrationTest` (+1: audit attribution), `TrustConfigurationValidationTest` (+1: weak RSA), `KnowledgeBaseAccessPolicyTest` (+2), `ArchitectureRulesTest` (+1 rule).
- The timing and concurrency test classes were rerun twice more: stable.

### Real HTTP checks (local profile; V5 applied to the existing M1 demo database)

```text
POST /api/v1/admin/integrations (allowedScopes=[KB_READ,KB_MANAGE,DOCUMENT_UPLOAD])  → 201
POST /api/v1/admin/integrations/{id}/api-keys (old admin content-key endpoint)      → 405
POST /api/v1/admin/integrations/{id}/bootstrap-keys → 201 keyType BOOTSTRAP, scopes [KEY_ROTATE], expires +24h
GET  /api/v1/knowledge-bases with the bootstrap key                                  → 401
POST /api/v1/integration/api-keys with the bootstrap key → 201 STANDARD, expires +90d, createdBy api-key:<bootstrap>
     (response header X-Api-Key-Expires-At: <bootstrap expiry>)
Same bootstrap key again                                                             → 401 (consumed)
Scope TOOL_EXECUTE (outside the ceiling) → 400; validityDays=400 → 400
Key restricted to 'policies' → lists only 'policies'; X-Api-Key-Expires-At on a 10-day key; creating a KB → 403
Rotation: create successor → revoke old key → old key 401, new key 200
Existing M1 integrations after V5 → allowedScopes backfilled from their keys' scopes ([KB_READ, KB_MANAGE])
```

### Performance (same method as M1: sequential curl, n = 100 after 10 warm-up requests)

| Request path | p50 | p95 | M1 (p50 / p95) |
|---|---|---|---|
| `GET /actuator/health` | 8.6 ms | 10.5 ms | 8.4 / 12.6 ms |
| 401 without credentials | 3.6 ms | 4.6 ms | 3.1 / 4.1 ms |
| `GET /api/v1/integration` (API key) | 12.7 ms | 15.6 ms | 13.2 / 15.9 ms |
| `GET /api/v1/knowledge-bases/{id}` | 12.6 ms | 21.4 ms | 13.1 / 16.0 ms |
| `GET /api/v1/knowledge-bases` (list) | 12.8 ms | 16.8 ms | 13.3 / 16.8 ms |
| JVM working set | 303 MB | | 322 MB |

The differences are within measurement noise. The throttling saves one database round trip per request, but on this machine that's under 1 ms. One p95 outlier (21.4 ms) on a single path; p50 is unchanged.

### Implementation review

- **SOLID / boundaries:**
  - admin key operations (`ApiKeyService`) are separated from self-service ones (`SelfServiceKeyService`);
  - KB restrictions are enforced in one policy (`KnowledgeBaseAccessPolicy`) and the list queries;
  - `access` still depends only on `integration.application`;
  - no new module cycles.
- **Intentional choices:**
  - `integration_api_key_kb` is RLS-enabled but not FORCEd (the definer lookup must read it), and is classified as a credential table in the coverage test;
  - `KEY_ROTATE` is implicit in every ceiling (every integration must be able to rotate).
- **Technical debt:**
  - rate limiting is still open (M2);
  - an integration could in principle modify columns of its own key rows through SQL (RLS allows own-row updates; JPA marks them non-updatable). Database CHECKs bound expiry and scopes, and the effective scopes are clamped at authentication by the ceiling;
  - the Mockito JDK warning remains.

---

## M1 — Integration and Knowledge Base foundation (identity, RLS)

**Date:** 2026-11-23 · **Decisions:** D-16 to D-19 in `docs/roadmap.md` · ADR-0009, ADR-0010

### Delivered

- **Database (Flyway V2–V4):**
  - `integration`, `integration_api_key` (SHA-256 of the secret only), `integration_trust_config`;
  - `knowledge_base`, `knowledge_base_role_grant` (composite tenant FK);
  - `audit_event` (append-only);
  - RLS on every table: tenant content is FORCEd with no admin exception; platform tables are admin-write-only;
  - SECURITY DEFINER functions for the pre-tenant key lookup.
- **`shared`:**
  - `AccessContext` / `AccessContextHolder` / `Permission`;
  - `TenantAwareJpaTransactionManager`, which sets `app.integration_id` and `app.access_plane` per transaction;
  - `AuditRecorder` (records in the same transaction, or independently for denials);
  - `@CurrentAccess`, `PageQuery` / `PageResponse`, and new error types (404 / 403 / 409 / 400) in the RFC 9457 format.
- **`integration`:** register, list and suspend integrations; issue, list and revoke API keys; trust configuration with policy validation; `ApiKeyAuthenticator` (constant-time comparison, identical rejections).
- **`knowledgebase`:**
  - create, list, get and update KBs (optimistic locking; archive via status);
  - role grants with replace-set semantics;
  - `KnowledgeBaseAccessPolicy` (scopes for services; grants ∩ scopes for users; nothing for admins).
- **`access`:**
  - three path-scoped filter chains (admin / integration / default);
  - `X-Admin-Key`, `X-Api-Key` and `X-User-Assertion` authentication;
  - RS256/ES256 JWKS verifier with strict validators, a per-integration decoder cache, and JWKS fetching with timeouts and no redirects.
- **Dev tooling (test scope):** `DevAssertionTool` (generate keys, mint tokens).

### Acceptance criteria

| Criterion | Result | Evidence |
|---|---|---|
| Create an integration and multiple KBs | ✅ Pass | `KnowledgeBaseApiIntegrationTest`; curl demo (HRIS: 2 KBs, CRM: 1) |
| An integration cannot access another integration's KBs | ✅ Pass | `CrossTenantIsolationIntegrationTest` (get / patch / grants / list); `RowLevelSecurityIntegrationTest` (database level) |
| Invalid or unauthorized IDs do not leak data | ✅ Pass | Foreign and unknown IDs return byte-identical 404 bodies; malformed IDs → 400; all authentication failures return an identical 401 |
| Integration/KB lifecycle and ownership covered by integration tests | ✅ Pass | 147 tests total, listed below |

### Quality gates

| Gate | Result | Details |
|---|---|---|
| Functional | ✅ | curl demo below |
| Security | ✅ | Plane separation; API-key lifecycle (revoked, expired, suspended); 13 invalid user-assertion variants (HS256, `none`, issuer, audience, expired, lifetime, missing sub/exp, future iat, unpublished key, garbage, oversized, bad roles type); token signed by another integration rejected; https-only JWKS and no private keys; secrets never returned or logged |
| Cross-tenant isolation | ✅ | API-level and SQL-level tests, including "no tenant context ⇒ 0 rows" and `WITH CHECK` on cross-tenant writes |
| Performance | ✅ | See below |
| Error handling | ✅ | 400 validation, oversized page, 404, 403, 409 (duplicate code, stale version), 503 if credential verification cannot reach the database |
| Documentation | ✅ | Roadmap, architecture, security, ADR-0009/0010, README, this file |

### Automated tests — `./mvnw clean verify` → **BUILD SUCCESS, 147 tests, 0 failures**

| Test class | Tests | Covers |
|---|---|---|
| `ApiKeyCredentialTest` | 12 | Key format, uniqueness, hash comparison, malformed input, no secrets in `toString` |
| `KnowledgeBaseAccessPolicyTest` | 5 | Scopes, grants ∩ scopes, manage ⇒ read, admin has nothing |
| `TrustConfigurationValidationTest` | 10 | https only, no credentials in URI, inline keys policy, private-key rejection, algorithms, ranges, claim names |
| `AdminIntegrationApiIntegrationTest` | 12 | Registration, duplicate/invalid, paging limits, one-time plaintext and stored hash, plane separation, audit |
| `IntegrationAuthenticationIntegrationTest` | 12 | Valid, malformed, unknown, wrong secret, revoked, expired, suspended/reactivated, identical bodies, audit |
| `UserAssertionIntegrationTest` | 23 | RS256/ES256, grants, scope intersection, cross-integration tokens, 13 invalid variants, missing trust config, inline keys refused, audit |
| `KnowledgeBaseApiIntegrationTest` | 10 | Create/list/paginate/get/update/archive, permissions, optimistic locking, grants |
| `CrossTenantIsolationIntegrationTest` | 5 | Same codes in two tenants, indistinguishable 404, list isolation, no foreign writes |
| `RowLevelSecurityIntegrationTest` | 8 | Zero rows without context, own rows only, cross-tenant writes blocked, platform tables admin-write-only, admins have no content access, append-only audit, definer lookup |
| M0 suites (architecture rules now 13) | 50 | As in M0, plus new ArchUnit rules (domain layering, `access` → `integration.application` only, JOSE confined to `access`) |

### Real HTTP checks (local profile, Docker Compose PostgreSQL, migrations V2–V4 applied to the existing M0 database)

```text
POST /api/v1/admin/integrations (X-Admin-Key)            → 201 {"code":"hris-28269","status":"ACTIVE",...}
POST /api/v1/admin/integrations/{hris}/api-keys          → 201 {"key":{"keyId":"50c2…","scopes":["KB_READ","KB_MANAGE"],...},
                                                                "plaintextKey":"mrk_50c2…_<shown once>"}
POST /api/v1/knowledge-bases (HRIS key) ×2, (CRM key) ×1 → 201 … "createdBy":"api-key:50c2…","yourPermissions":["KB_MANAGE","KB_READ"]
GET  /api/v1/knowledge-bases (HRIS key)                  → totalItems 2: hr-policies, benefits
GET  /api/v1/knowledge-bases/{CRM kb} (HRIS key)         → 404 RESOURCE_NOT_FOUND
PUT  /api/v1/knowledge-bases/{hr-policies}/role-grants   → {"grants":[{"role":"hr-reader","permission":"KB_READ"}]}
PUT  /api/v1/admin/integrations/{hris}/trust-config      → 200 (inline JWKS from DevAssertionTool, local profile)
GET  /api/v1/knowledge-bases (key + assertion emp-1001, roles=[hr-reader]) → totalItems 1: hr-policies, yourPermissions ["KB_READ"]
GET  /api/v1/knowledge-bases/{hr-policies} (assertion emp-2002, roles=[sales]) → 404
HRIS assertion presented with the CRM key                → 401
Tampered assertion                                       → 401
DELETE …/api-keys/{keyId}, then reuse that key           → 200, then 401 AUTHENTICATION_REQUIRED
Admin key on /api/v1/knowledge-bases; CRM key on /api/v1/admin/integrations → 401; 401
```

Demo commands for reproduction:

```bash
./mvnw -q test-compile dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile=cp.txt
java -cp "$(cat cp.txt);target/test-classes;target/classes" com.mimocodes.mimorag.devtools.DevAssertionTool keygen ./.dev-issuer
java -cp "$(cat cp.txt);target/test-classes;target/classes" com.mimocodes.mimorag.devtools.DevAssertionTool \
     mint ./.dev-issuer https://hris.local mimorag emp-1001 hr-reader
```

(Use `:` instead of `;` as the classpath separator on Linux/macOS. Keep `.dev-issuer/` out of Git; it contains a private key.)

### Performance (sequential curl, n = 100 after 10 warm-up requests, local profile)

| Request path | p50 | p95 | M0 baseline (p50 / p95) |
|---|---|---|---|
| `GET /actuator/health` | 8.4 ms | 12.6 ms | 9.5 / 11.2 ms |
| 401 without credentials | 3.1 ms | 4.1 ms | 3.4 / 4.0 ms |
| `GET /api/v1/integration` (API key + 1 RLS transaction) | 13.2 ms | 15.9 ms | — |
| `GET /api/v1/knowledge-bases/{id}` (service) | 13.1 ms | 16.0 ms | — |
| `GET /api/v1/knowledge-bases` (list, service) | 13.3 ms | 16.8 ms | — |
| `GET /api/v1/knowledge-bases/{id}` (API key + JWT, warm key cache) | 20.3 ms | 24.8 ms | — |
| `GET /api/v1/knowledge-bases` (list, API key + JWT) | 20.0 ms | 24.7 ms | — |
| 401 for an unknown API key (lookup + dummy hash) | 8.8 ms | 12.8 ms | — |
| `GET /api/v1/admin/integrations` (page of 20) | 10.3 ms | 12.9 ms | — |
| Startup (V2–V4 migrations included) | 12.4 s | | 9.8 s |
| JVM working set after the run | 322 MB | | 254 MB |
| PostgreSQL container memory | 48 MiB | | 54 MiB |

**Analysis:**
- API-key authentication plus the tenant-scoped transaction adds about 10 ms over an unauthenticated request. That covers the credential lookup, `set_config`, the query, and the audit-free path.
- JWT verification adds about 7 ms (RSA signature check and the trust-configuration read).
- Health and unauthenticated paths are unchanged from M0.

### Implementation review

1. **Clean Code / SOLID:**
   - Thin controllers; use cases in application services that receive `AccessContext` explicitly.
   - Small policy classes: `KnowledgeBaseAccessPolicy`, `ApiKeyCredential`, the decoder factory.
   - `IntegrationAuthenticator` and `UserAssertionVerifier` are replaceable, and the core never sees tokens.
   - Ports are narrow repository interfaces.
2. **Intentional deviations:**
   - JPA annotations in `domain` (ADR-0009).
   - Application views are returned directly as response bodies.
   - `TrustConfigurationService` uses Jackson (not Nimbus) to sanity-check inline JWKS, because JOSE libraries are confined to `access`.
3. **Module dependencies:** `access → integration.application, shared`; `integration → shared`; `knowledgebase → shared`. No cycles (ArchUnit).
4. **Tests:** 147 tests, listed above.
5. **Technical debt / follow-ups:**
   - Rate limiting is not yet applied to authentication failures (M2+).
   - Unknown-key failures are logged but not audited, deliberately to avoid audit flooding. Revisit together with rate limiting.
   - The JWKS decoder cache is unbounded (one entry per integration, acceptable for now).
   - No `jti` replay cache yet; add with the first write operation that accepts user assertions.
   - The Mockito self-attach warning on JDK 24 remains.

### Known limitations

- No document upload, extraction or review yet (M2/M3).
- API keys may be issued without expiry (maximum 730 days when set). See Q-06.
- Q-01 is still open: connected systems must publish RS256/ES256 JWKS before user-scoped production use.

---

## M0 — Repository inspection and architecture baseline

**Date:** 2026-11-23 · **Environment:** Windows 10, JDK 24 (target 21), Docker 28.3.2, 16 GB RAM, Quadro M1000M 2 GB VRAM

### Delivered

- **Repository and build:**
  - Git initialized, with `.gitignore` covering secrets, local data, PDFs and benchmark results, and `.gitattributes` fixing line endings for `.sh` and `.cmd`.
  - Root package renamed to `com.mimocodes.mimorag`.
  - `pom.xml` cleaned up. Removed: Spring AI `2.1.0-M1`, the pgvector starter and Lombok. Added: Actuator, Spring Security, Flyway (with its PostgreSQL module), springdoc 3.2.1, Testcontainers 2 and ArchUnit 1.5.1. Only stable releases are used.
- **Local environment:**
  - `docker-compose.yml` runs PostgreSQL 17 (`pgvector/pgvector:0.8.7-pg17`) bound to `127.0.0.1:5433`, with a health check and a 1 GB memory limit.
  - `.env.example` holds placeholders only; the `local` profile imports the git-ignored `.env`.
  - The database role script (`mimorag_owner` / `mimorag_app`) is shared by Docker Compose and Testcontainers.
- **Baseline code:**
  - RFC 9457 error format (`ApiProblems`, `ApiErrorCode`, `GlobalExceptionHandler`), also used for security errors.
  - Correlation-ID filter (validated, echoed, added to the logging context).
  - Deny-by-default stateless security, with only `health` and `info` public.
  - Startup check for required settings, with a clear failure report.
  - OpenAPI metadata.
  - Flyway `V1__baseline` (schema comment; application role locked out of migration history).
  - Health endpoint with liveness and readiness probes; readiness includes the database.
- **Architecture tests:** ArchUnit rules for module boundaries and layering (see `docs/architecture.md` §4).
- **Docs:** `docs/roadmap.md` (moved from Downloads; now includes status, decision log, Git workflow and open questions), `docs/architecture.md`, `docs/security-architecture.md`, `docs/workflows/document-review.md`, ADR-0001 to ADR-0008, `README.md`, and this file.
- **Removed:** Spring Initializr's `HELP.md` and the empty `static/` and `templates/` folders.

### Acceptance criteria

| Criterion | Result | Evidence |
|---|---|---|
| Application starts from a clean checkout using documented steps | ✅ Pass | README steps followed: `.env` from `.env.example`, `docker compose up -d`, jar run with `local` profile. Started in 9.8 s. Flyway applied `V1` as `mimorag_owner`. |
| Health endpoint reports application and database dependencies accurately | ✅ Pass | `db: UP` while running. After `docker compose stop postgres`: HTTP 503, `db: DOWN`, readiness 503, liveness 200. Recovered to `UP` automatically after restart. Automated in `HealthWhenDatabaseDownIntegrationTest`. |
| No secrets are committed | ✅ Pass | `.env` is git-ignored (`git check-ignore` confirmed). `.env.example` has placeholders only. Test credentials are throwaway values for ephemeral containers. No default passwords in `application.yml`. |
| Architecture decisions and open questions are documented | ✅ Pass | `docs/roadmap.md` §0.2 (decision log) and §0.4 (open questions); ADRs 0001–0008. |

### Quality gates

| Gate | Result | Details |
|---|---|---|
| Functional | ✅ | curl checks below |
| Security | ✅ | Deny-by-default (8 paths, including `/actuator/env`, `/actuator/heapdump` and `/v3/api-docs`); identical 401 for existing and unknown paths; no `Set-Cookie`; security headers; no internals in errors; correlation-ID injection rejected; least-privilege database role. |
| Cross-tenant isolation | ➖ N/A in M0 | No tenant data yet. The privilege model required for RLS is verified (`mimorag_app`: not superuser, `NOBYPASSRLS`, no DDL). Isolation tests start in M1. |
| Performance baseline | ✅ | See below |
| Error handling | ✅ | Validation (400 with sorted violations, values not echoed), malformed JSON, 405, 415, unexpected 500, database unavailable (503), missing configuration (clear startup failure). |
| Documentation | ✅ | README, architecture, security, workflow, ADRs, roadmap, this file. |

### Automated tests — `./mvnw clean verify` → **BUILD SUCCESS, 47 tests, 0 failures**

| Test class | Tests | Covers |
|---|---|---|
| `ArchitectureRulesTest` | 10 | Module cycles, shared-kernel independence, core modules free of auth mechanics, framework-free domain, layering, controller rules, no field injection, no JUL |
| `CorrelationIdFilterTest` | 8 | Generation, propagation, rejection of malformed or injected IDs, MDC cleanup on failure |
| `GlobalExceptionHandlerTest` | 5 | Validation format, malformed JSON, 500 hygiene, 405, 415 |
| `RequiredSettingsVerifierTest` | 3 | Missing settings reported by name only; failure analysis |
| `SecurityBaselineIntegrationTest` | 12 | Deny-by-default, indistinguishable 401s, no cookies, correlation propagation, headers |
| `HealthEndpointIntegrationTest` | 4 | Health, readiness, liveness, no anonymous details |
| `HealthWhenDatabaseDownIntegrationTest` | 1 | DOWN/503 on database outage; liveness stays UP |
| `DatabaseRolesIntegrationTest` | 4 | Runtime role privileges; Flyway applied by owner; no DDL; no access to migration history |

### Real HTTP checks (application running with `local` profile against Docker Compose PostgreSQL)

```text
$ curl -i http://localhost:8080/actuator/health
HTTP/1.1 200
X-Correlation-Id: 2cc0e098-f9a9-4b17-8db6-d86132a863d8
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
Cache-Control: no-cache, no-store, max-age=0, must-revalidate
{"components":{"db":{"status":"UP"},"diskSpace":{"status":"UP"},"livenessState":{"status":"UP"},
 "ping":{"status":"UP"},"readinessState":{"status":"UP"},"ssl":{"status":"UP"}},
 "groups":["liveness","readiness"],"status":"UP"}

$ curl http://localhost:8080/actuator/health/readiness
{"components":{"db":{"status":"UP"},"readinessState":{"status":"UP"}},"status":"UP"}

$ curl -i -H "X-Correlation-Id: demo-curl-0001" http://localhost:8080/api/v1/integrations
HTTP/1.1 401
X-Correlation-Id: demo-curl-0001
Content-Type: application/problem+json
{"type":"urn:mimorag:problem:authentication-required","title":"Unauthorized","status":401,
 "detail":"Valid credentials are required to access this resource.","errorCode":"AUTHENTICATION_REQUIRED",
 "correlationId":"demo-curl-0001","timestamp":"2026-11-23T11:20:16.901441800Z"}

$ curl -o /dev/null -w "%{http_code}" http://localhost:8080/actuator/env          → 401
$ curl -D - -H "X-Correlation-Id: bad id <x>" .../actuator/health                → X-Correlation-Id: <new UUID>
$ curl http://localhost:8080/v3/api-docs (local profile)                         → OpenAPI 3.1 document

# Database outage
$ docker compose stop postgres
$ curl http://localhost:8080/actuator/health   → HTTP 503 in 3.0 s, "db":{"status":"DOWN"}, "status":"DOWN"
  readiness → 503 · liveness → 200
$ docker compose start postgres
$ curl http://localhost:8080/actuator/health   → HTTP 200, "status":"UP"

# Missing configuration
$ java -jar target/mimorag-0.0.1-SNAPSHOT.jar
APPLICATION FAILED TO START
Description: MimoRag cannot start because these required settings are not set:
             MIMORAG_DB_URL, MIMORAG_DB_APP_PASSWORD, MIMORAG_DB_OWNER_PASSWORD
Action: Provide them as environment variables. For local development, copy .env.example to .env ...
```

### Performance baseline (M0)

Measured on the developer machine, local profile, warm JVM, sequential curl requests (n = 100 after 10 warm-up requests):

| Metric | Value |
|---|---|
| Application startup (`Started MimoRagApplication`) | 9.8 s |
| `GET /actuator/health` (includes DB check) | p50 9.5 ms · p95 11.2 ms · max 16.5 ms |
| Denied request (401, security filter only) | p50 3.4 ms · p95 4.0 ms |
| JVM working set at idle after the requests | 254 MB, 51 threads |
| PostgreSQL container memory at idle | 54 MiB (limit 1 GiB) |
| Health response time when database is down | 3.0 s (bounded by Hikari `connection-timeout: 3000`) |
| Application jar size | 61 MB |
| Full test suite (`clean verify`) | about 1–2 min (container start dominates) |

These are the reference numbers for later milestones.

### Implementation review

1. **Clean Code / SOLID:**
   - Small single-purpose classes: the filter, the problem builder, the error-code mapping, the security error handler and the settings verifier.
   - The controller advice only maps exceptions.
   - Security mechanics are isolated in `access`. Shared cross-cutting code lives in `shared`, which depends on no feature module.
   - Configuration is externalized; there are no magic values in code (error codes are an enum and the ID pattern is a named constant).
2. **Intentional deviations:**
   - `access.web.ProblemDetailSecurityErrorHandler` serializes `ProblemDetail` via a `Map`, because security filters run outside Spring MVC's message converters.
   - The ERROR dispatcher type is permitted so that container error pages are not replaced by 401s.
3. **Module dependencies:** `access → shared`. `shared` depends on nothing internal. No cycles (ArchUnit-verified).
4. **Tests:** 47 tests, listed above. Command: `./mvnw clean verify`.
5. **Technical debt / follow-ups:**
   - Mockito prints a JDK self-attach warning on JDK 24. It is harmless; configure Mockito as a Java agent when convenient.
   - The JPA-out-of-domain ArchUnit rule will be revisited with real entities in M1 (see ADR-0001).
   - Metrics endpoints are not exposed until authenticated admin access exists (M1+).
   - Swagger UI and OpenAPI are enabled only in the `local` profile.

### Known limitations

- No authentication yet: every business path returns 401 by design until M1.
- `main` has no commits yet. The first commit will be on `chore/m0-architecture-baseline`, pending approval.
- Docker storage remains on C: (about 15 GB free). Reassess before M2 (Q-04).
