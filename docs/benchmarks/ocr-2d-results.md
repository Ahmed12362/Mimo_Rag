# OCR slice 2d — accuracy gate and worker layout (measured)

- **Date:** 2026-11-24/25 · **Pipeline:** `gated-hybrid/2d.3` (image `mimorag-ocr:2d`) · **Decisions:** ADR-0014
- **Machine:** development laptop (16 GB RAM, Docker Desktop WSL 2 VM with 6 CPUs); OCR container limited to 4 CPUs
- Every number below is **measured** unless marked *estimated*. Reproduce with `./mvnw test -Pocr-it` and
  `./mvnw test -Pocr-bench` (see the test classes for options).

## 1. Accuracy gate (`OcrRealEngineAccuracyTest`)

The 2a corpus (7 Arabic and mixed pages × vector + 5 scan profiles) is rendered by the application's
`PdfBoxPageRenderer` at 300 DPI and recognised by the real service through `HttpOcrProvider`. Scoring is a port of the
2a harness (`normalise`, `order_rows`, `cer`); before each run the port re-scores the stored 2a rows and must
reproduce the stored 2a CER (it does, to 0.0005). Rows are ordered in the upright frame, as in 2a.

**Criteria (unchanged throughout):** per profile at most +1.5 CER points above the 2a gated-hybrid baseline;
orientation `auto` never worse than `off` on upright pages; upside-down pages within 1 point of upright `clean300`.

### Final result (bilinear rendering, orientation on every page + upside-down retry)

| Profile | 2a baseline | 2d | Δ points | Criterion |
|---|---|---|---|---|
| vector | 17.59 % | 18.31 % | +0.72 | ✅ |
| clean300 | 18.05 % | 18.60 % | +0.55 | ✅ |
| lowres150 | 19.07 % | 19.01 % | −0.06 | ✅ |
| noisy200 | 25.29 % | 24.78 % | −0.51 | ✅ |
| rotated300 | 19.42 % | 18.77 % | −0.65 | ✅ |
| `auto` vs `off` on upright pages | — | identical on every profile | — | ✅ |
| flip180 vs clean300 + 1 | — | 20.10 % vs 19.60 % | +1.50 | ❌ by 0.50, accepted as a known limitation (below) |

Per page — 2d CER % (2a baseline in brackets; ↻ = orientation corrected):

| Page | vector | clean300 | lowres150 | noisy200 | rotated300 | flip180 |
|---|---|---|---|---|---|---|
| ar_diacritics_amiri | 68.7 (66.4) | 73.7 (70.0) | 78.8 (81.1) | 73.7 (78.3) | 75.6 (73.3) | 86.6 |
| ar_hiring_sans10 | 1.8 (1.8) | 1.8 (1.8) | 3.0 (3.6) | 24.6 (22.9) | 1.8 (1.8) | 2.1 ↻ |
| ar_policy_amiri | 7.5 (6.4) | 5.0 (10.9) | 16.1 (12.3) | 28.9 (28.6) | 11.1 (11.8) | 7.0 ↻ |
| ar_sick_naskh | 10.0 (9.7) | 12.4 (9.4) | 8.5 (11.5) | 16.0 (10.9) | 6.3 (9.4) | 10.9 ↻ |
| ar_table_naskh | 7.4 (7.4) | 7.9 (5.3) | 7.4 (4.7) | 7.4 (7.4) | 8.4 (8.9) | 6.8 ↻ |
| mixed_ar_en_naskh | 27.2 (25.9) | 23.7 (23.2) | 13.6 (16.7) | 19.7 (23.2) | 22.4 (25.0) | 21.5 ↻ |
| mixed_lines_sans | 5.7 (5.7) | 5.7 (5.7) | 5.7 (3.6) | 3.1 (5.7) | 5.7 (5.7) | 5.7 ↻ |

**Known limitation (accepted 2026-11-25):** the fully diacritised Amiri page is read poorly in either orientation
(≈ 74 % CER upright, rotated reading not "clearly better"), so its upside-down scan stays uncorrected. The six other
upside-down pages average 9.0 % against 9.4 % for the same pages upright. The gate still reports this criterion as
failed; it was not changed.

### How we got there (all runs on the same corpus and criteria)

| Run | Rendering | Orientation | lowres150 | noisy200 | flip180 | Notes |
|---|---|---|---|---|---|---|
| 1 | PDFBox default | conditional | +1.27 | **+1.59** | 78.2 %* | *metric bug: rows ordered in the uncorrected frame |
| 2 | PDFBox default | conditional | +1.27 | **+1.59** | 51.2 % | metric fixed |
| diag. | pdfium (2a renderer) | conditional | ±0.00 | ±0.00 | 20.0 % | service reproduces 2a exactly → regression is rendering |
| 3 (A1+B1) | PDFBox bicubic | classifier on every page | **+2.51** | +0.22 | 51.2 % | bicubic fixes noisy, hurts 150 DPI |
| 4 (final) | PDFBox **bilinear** | every page + upside-down retry | −0.06 | −0.51 | 20.1 % | adopted |

**Rendering.** PDFBox enlarges images without `/Interpolate` (almost every scan) by nearest neighbour. Mean absolute
pixel difference from pdfium on noisy200: default 4.01, bicubic 3.50, bilinear 2.90, 2× supersampling 3.66;
lowres150: 1.28 / 1.26 / 1.20 / 1.28. Render time per page: default ≈ 220 ms, bicubic ≈ 560 ms, bilinear ≈ 210 ms,
supersampling ≈ 1 000–1 200 ms. Bilinear was then confirmed by OCR CER (noisy200 24.78 %, lowres150 19.01 %).

**Orientation.** Page classifier (`PP-LCNet_x1_0_doc_ori`) results:

| | pdfium renderings | PDFBox renderings |
|---|---|---|
| upside-down pages labelled 180° | 6 / 7 (scores 0.68–0.91) | 3 / 7 (four labelled 0° at 0.56–0.57) |
| upright pages labelled rotated (false proposals) | 8 / 28 | — |
| false proposals accepted by the "clearly better" check | 0 | 0 |
| classifier time per page | mean 22 ms, max 45 ms | — |

Because the classifier misses upside-down PDFBox renderings, a page that reads poorly is also tried upside down; the
"clearly better" check accepted all six recoverable pages (confidence ≈ 0.44 → 0.89–0.97) and rejected every false
proposal on upright pages.

**Orientation overhead.** Classifier ≈ 22 ms per page; the real cost is one extra OCR pass on pages with a false
proposal (29 % of the upright test pages) or a poor reading. On identical images: +647 ms per page (+24 %) on
average (single run; timing noise of several hundred ms between runs).

## 2. Worker layout: 1 × 4 vs 2 × 2 (`OcrThroughputBenchmarkTest`)

One 50-page scan plus three concurrent 5-page uploads from three other integrations (65 OCR pages), through the real
application (upload, extraction, rendering, OCR jobs) with 2 OCR workers in the application, so the service always had
two pages in flight. Memory and CPU from `docker stats` every 2 s; API latency from an in-process document listing
every 250 ms while OCR ran.

| | **1 × 4 (default)** | 2 × 2 |
|---|---|---|
| Container memory limit | 2 GB | 3 GB (temporary, for this run only) |
| OCR throughput | **8.5 pages/min** (65 pages in 7.63 min) | 8.4 pages/min (7.70 min) |
| Peak container memory | **1 140 MiB** | 1 963 MiB (+72 %, not 2×) |
| Mean container CPU | 240 % | 161 % |
| 50-page document | 407 s | 400 s |
| 5-page documents (concurrent) | 44 s, 50 s, 81 s | 56 s, 62 s, 65 s |
| API latency during OCR p50 / p95 / max | 15 / 102 / 5 113 ms | 14 / 80 / 606 ms |

**Decision: keep 1 × 4.** Throughput is equal within measurement noise, and 2 × 2 needs 72 % more memory (it would
not fit the 2 GB limit). The 5.1 s API maximum in the 1 × 4 run is a single sample whose cause was not isolated
(one run each); p95 stayed near 100 ms in both. The 3 GB container was removed after the run; the permanent configuration is unchanged.

## 3. End-to-end HTTP check (application jar, dev database migrated to V8, OCR container)

Three scanned pages uploaded through the real API: every extraction handed over to an `OCR_DOCUMENT` job
(`nextJobId`), every OCR job succeeded with progress 1/1 and version `OCR_COMPLETED`; `text` stayed `null`, the `ocr`
object was `UNREVIEWED`, the upside-down page was corrected (`ORIENTATION_CORRECTED`, 180°), digits and Latin tokens
were recovered (`TOKEN_RECOVERED`); another integration got 404 for the OCR job and the page; the OCR port was bound to
`127.0.0.1` only and answered 401 without the token. Latencies in this check (6–48 s per page, including a 48 s first
request) were taken while the host had 0.9 GB of 15.9 GB RAM free and are **not** representative; the uncontended
gate run's first request after container start took 2.3 s.
