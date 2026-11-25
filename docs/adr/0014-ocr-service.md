# ADR-0014: OCR as a separate service with gated hybrid token recovery

- **Status:** Accepted (M2 slice 2d, decisions A–D and requirements 1–6 of the slice 2d plan)
- **Date:** 2026-11-24
- **Builds on:** ADR-0011 (job queue), ADR-0013 (native extraction), the slice 2a benchmark
  (`docs/benchmarks/`)

## Context

Native extraction (ADR-0013) classifies pages; pages classified `OCR_REQUIRED` (scans) and the image regions of
`MIXED` pages must go through OCR. The 2a benchmark selected the engine configuration: PaddleOCR PP-OCRv5 mobile
detection (longest side 1600) with the Arabic mobile recogniser, plus a **gated** English recogniser and Tesseract
that only *insert* Latin tokens and digits the Arabic recogniser dropped, never change Arabic text, and flag what
they cannot place. That stack is Python, needs about 1 GB of resident memory per model set, and is CPU-heavy, so it
must not run inside the API process.

## Decision

### A. A separate, independently deployable Python service (`ocr-service/`)

FastAPI + Uvicorn (one worker), image `mimorag-ocr:2d` (Python 3.11 slim, Tesseract `ara`+`eng`, models baked in
at build time, pinned lock file, user 10001, read-only root filesystem). The Java application talks to it only
through the `OcrProvider` port (`HttpOcrProvider`); nothing else in the application knows the engine.

### B. Its own job kind: `OCR_DOCUMENT`

When extraction leaves a version in `AWAITING_OCR` and OCR is enabled, the `OCR_DOCUMENT` job is enqueued **in the
same transaction** that completes extraction (a crash can never leave a version waiting for a job that does not
exist; `@Version` on the version row prevents a second job). It has its own worker pool (`mimorag.jobs.workers`,
default 1) and its own per-integration limit (`mimorag.jobs.kind-concurrency.OCR_DOCUMENT`, default 1), so one
tenant's 500-page scan cannot occupy the service or delay native extraction for others. Progress is reported through
the existing job API (`GET /ingestion-jobs/{id}` → `progress`), and the extraction job's view links to it
(`nextJobId`; the version view carries `ocrJobId`).

Version states: `AWAITING_OCR → OCR_RUNNING → OCR_COMPLETED`, or `OCR_FAILED` (retry →
`AWAITING_OCR`). With OCR disabled (the default) versions stay `AWAITING_OCR`.

### C. Java renders, the service never parses PDFs

PDFBox renders each page (or each image region of a `MIXED` page, with a 4 pt margin) to RGB PNG at 300 DPI; the DPI
is lowered automatically to stay under 40 M pixels (`RENDERED_AT_REDUCED_DPI` warning). The untrusted PDF stays in
the JVM that already parses it; the OCR service only ever decodes PNG.

Scan images are enlarged **bilinearly**: PDFBox enlarges images without a `/Interpolate` flag (almost all scans) by
nearest neighbour, which cost +1.59 CER points on noisy 200-DPI scans; bicubic fixed those but cost +2.51 points on
150-DPI scans. Bilinear matched the 2a baseline on every profile (measured, see "Measured results") at the default
rendering speed. The flag is set only in the in-memory document, never saved.

### Orientation (decisions B1 + follow-up, slice 2d gate)

In `auto` mode the page orientation classifier (`PP-LCNet_x1_0_doc_ori`) runs on **every** page; a page that reads
poorly (< 20 characters or mean confidence < 0.60) is also tried upside down even when the classifier says upright
(on PDFBox renderings it labelled 4 of 7 upside-down pages "0°" with scores near 0.56). A rotated reading replaces
the original only if it is clearly better (mean confidence + 0.05, at most a sixth fewer characters), which rejected
all 8 false classifier proposals on 28 upright test pages. Cost: about 22 ms per page for the classifier plus one
extra OCR pass on pages with a proposal or a poor reading.

**Known limitation:** a fully diacritised Arabic page (`ar_diacritics_amiri`) is read poorly in either orientation
(≈ 74 % CER upright), so its upside-down scan is not corrected. This is a recogniser limitation, accepted by the
product owner on 2026-11-25; the gate criterion was not changed.

### D. Tests: fake service in the normal suite, real engine opt-in

`FakeOcrServer` speaks contract version 1 with scripted outcomes; the HTTP client is also tested against responses
captured from the real image (`src/test/resources/ocr/`). The real engine runs in the opt-in suite
`./mvnw test -Pocr-it` (`OcrRealEngineAccuracyTest`), which enforces the accuracy criteria below.

## Communication contract (version 1)

`POST /v1/ocr`, body: the PNG bytes.

| Header | Meaning |
|---|---|
| `X-OCR-Token` (required) | service-to-service token, compared in constant time; every endpoint except health requires it |
| `Content-Type: image/png` (required) | anything else → 415 |
| `X-Request-Id` | `[A-Za-z0-9._-]{8,64}`, else the service generates one; the app sends `<correlation id>-p<page>[-r<region>]` |
| `X-OCR-Orientation` | `auto` (default) or `off` |
| `X-OCR-Profile` | `ar-en` (only profile in version 1) |

`200` response (abridged; full examples in `src/test/resources/ocr/*.response.json`):

```json
{
  "contractVersion": "1", "requestId": "…",
  "engine": {"pipeline": "gated-hybrid/2d.3", "paddleocr": "3.7.0", "recognition": "arabic_PP-OCRv5_mobile_rec", …},
  "image": {"width": 2482, "height": 3508},
  "orientation": {"checked": true, "applied": 180, "classifierConfidence": 0.681},
  "lines": [{
    "box": {"x": 120, "y": 340, "width": 1800, "height": 64}, "row": 0, "direction": "RTL",
    "confidence": 0.93, "originalText": "…", "proposedText": "… 15 …",
    "recoveredTokens": [{"token": "15", "offset": 12, "sources": ["tesseract", "paddle-en"], "anchor": "…"}],
    "unplacedTokens": [], "possiblyMissingTokens": [], "flags": ["TOKEN_RECOVERED"]
  }],
  "summary": {"lines": 9, "characters": 412, "meanConfidence": 0.91, "lowConfidenceLines": 1, …},
  "warnings": ["ORIENTATION_CORRECTED", "TOKEN_RECOVERED"],
  "timings": {"ocrMs": 3100, "gateMs": 400, "tesseractMs": 900, "orientationMs": 150, "totalMs": 4700}
}
```

Boxes are in pixels of the submitted image (after undoing any orientation correction); the application converts
them to page points. `originalText` is the Arabic recogniser's output, unchanged; `proposedText` may only add
tokens. Errors are RFC 9457-style `application/problem+json` with `errorCode` and `requestId`.

Other endpoints: `GET /health/live`, `GET /health/ready` (models loaded; no token, no details), `GET /v1/info`
(token; versions and limits).

### Response validation (application side)

A `200` is accepted only if: `contractVersion` is `"1"`; `image` equals the size that was sent; at most 2000 lines,
5000 characters per text and 64 recovered tokens per line; every box is finite and inside the image; confidence in
[0, 1]; direction, flags and warnings are known values; engine and timing metadata are small maps of scalars; and
**the Arabic letters of `proposedText` equal those of `originalText`** (recovery may never alter Arabic). The body is
read with a 5 MB cap. Unknown additional fields are ignored (additive changes stay within version 1); anything else
fails the page with `OCR_RESPONSE_INVALID`.

## Limits

| Where | Limit | Default |
|---|---|---|
| Service | body size / longest side / pixels / shortest side | 30 MB / 10 000 px / 40 M px / 16 px |
| Service | PNG header checked **before** decoding (decompression-bomb guard) | — |
| Service | OCR processes × threads, admission queue | 1 × 4, 2 waiting (more → 503 `SERVICE_BUSY`, `Retry-After: 5`) |
| Service | per-request time limit (worker killed and replaced) | 60 s |
| Service | worker recycled after | 500 requests |
| Container | memory / CPUs | 2 GB / 4 |
| Application | connect / request timeout | 2 s / 90 s |
| Application | rendering | 300 DPI, ≤ 40 M px, PNG ≤ 30 MB |
| Application | OCR workers per instance / running OCR jobs per integration | 1 / 1 |
| Application | engine attempts per page (HTTP 500) | 3 |

## Failure handling and retries

| Situation | Classified as | Effect |
|---|---|---|
| 400 / 413 (image refused) | page rejected | page stored `FAILED` with the service's code (e.g. `IMAGE_TOO_LARGE`); the job continues |
| 500 (engine error) | engine error | retried for the page up to 3 times, then page `FAILED` `OCR_ENGINE_ERROR` |
| 503 `OCR_TIMEOUT` (page took > 60 s) | engine error | as above, then page `FAILED` `OCR_TIMEOUT` |
| invalid `200` body | invalid response | page `FAILED` `OCR_RESPONSE_INVALID` (logged without text) |
| page cannot be rendered | render error | page `FAILED` `PAGE_RENDER_ERROR` |
| 503 `SERVICE_BUSY` / `NOT_READY`, connection refused, client timeout | unavailable | **job** transient failure → retried with the queue's backoff (attempts 3); after the last attempt the run and version become `FAILED` / `OCR_FAILED` and `POST /ingestion-jobs/{id}/retry` resumes it |
| 401 / 415 (token or client misconfigured) | configuration | **job** fails permanently (`OCR_CONFIGURATION_ERROR`), version `OCR_FAILED` |
| Knowledge Base archived, deleted, or upload permission revoked | revalidation | job `CANCELLED_REVALIDATION` before the next attempt |

**Idempotency and crash recovery.** One `ocr_run` per job (unique `job_id`). Every page is committed on its own,
together with the run's counters recounted from the table; `page_ocr` is unique per (run, page) and inserts use
`ON CONFLICT DO NOTHING`, so a resumed job (lease expired, retry) skips stored pages and even two workers racing after
a lost lease cannot store a page twice. The application role cannot `UPDATE`, `DELETE` or `TRUNCATE` `page_ocr`;
both tables have forced Row-Level Security. No database transaction is open while a page is rendered or recognised.

## Separation from approved content (human review)

OCR output is a proposal. It lives only in `page_ocr` with `reviewState: UNREVIEWED`, is returned in a separate
`ocr` object of the page API (`DOCUMENT_REVIEW` required), and never fills the page's `text`. Chunking, embedding,
indexing and answering will read only reviewed content (M3); an ArchUnit rule forbids `indexing`, `retrieval`,
`agent` and `tools` from depending on `ingestion` at all.

## Security requirements (production)

1. The service is reachable only from the application's private network: **no published port**, no ingress, no public
   load balancer. Network policy / security group allows only the application instances to reach port 8090.
2. Every request carries the service token (≥ 32 random characters, e.g. 48 bytes from a CSPRNG), stored in the
   platform secret store and injected as `OCR_TOKEN` / `MIMORAG_OCR_TOKEN`; rotate by deploying the new value to both
   sides (the service accepts one token in version 1, so rotation is a coordinated restart).
3. TLS between the application and the service when they do not share a host or a private overlay network (terminate
   at a sidecar or mesh; the token alone does not protect confidentiality).
4. Container hardening as in `docker-compose.yml`: read-only root filesystem, `tmpfs /tmp`, non-root user, all
   capabilities dropped, `no-new-privileges`, memory and CPU limits.
5. Logs never contain recognised text or images: only request id, sizes, line count, orientation and timings.
6. The application never follows redirects from the service, caps the response size, and validates every response.

## Development setup on Windows (development only)

Not a production pattern. The application runs on the host (IntelliJ or `mvnw`), the service in Docker Desktop:

- `docker compose --profile ocr up -d ocr` publishes the service on **`127.0.0.1:8090` only** (loopback; not
  reachable from the LAN). Set `MIMORAG_OCR_TOKEN` in `.env` (git-ignored) and
  `MIMORAG_OCR_ENABLED=true` for the application.
- Docker Desktop's WSL 2 backend forwards the loopback port to the Linux VM; do not change the binding to
  `0.0.0.0` (Windows Firewall rules for Docker would then expose it on the network).
- The token protects the endpoint even on the loopback interface (other local processes cannot use it without the
  token).

## Consequences

- OCR can be scaled, deployed and replaced independently; a different engine is a new `OcrProvider` or a new
  service behind the same contract.
- An extra container (~2.4 GB image, ~0.8–1.3 GB RSS for 1 × 4) is needed wherever OCR is enabled.
- OCR results are never used before human review, so M3 must provide the review flow before scanned documents can be
  indexed.
- The engine pipeline version (`engine.pipeline`) is stored per page, so pages can be re-run selectively when the
  pipeline changes.

## Measured results

See `docs/benchmarks/ocr-2d-results.md` (accuracy gate on the 2a corpus with PDFBox rendering, worker layout 1 × 4
vs 2 × 2, memory and throughput). Measured and estimated values are labelled there.
