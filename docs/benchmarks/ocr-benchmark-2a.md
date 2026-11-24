# OCR technical reference: benchmark and gated hybrid (M2 slice 2a)

| | |
|---|---|
| Date | 2026-11-24 |
| Branch | `feature/m2-pdf-upload-extraction` |
| Status | Benchmark complete. **Gated hybrid approved as the preferred candidate** (decisions in §9). **Not production-ready:** the items in §8 must be validated first. |
| Reproduction | §10, plus [benchmarks/ocr/README.md](../../benchmarks/ocr/README.md) |

> **How to read this document.** Every number in §§4–6 was **measured** on a small synthetic corpus, on one laptop,
> under the conditions in §3. **These numbers are not production guarantees.** Real documents (photocopies, phone
> photos, other fonts and layouts) will behave differently. Statements about the future are labelled **Expected** or
> **Validation target**.

## Contents

1. [Summary](#1-summary)
2. [Engines, models and versions](#2-engines-models-and-versions)
3. [Benchmark method](#3-benchmark-method)
4. [Results: single engines and configurations](#4-results-single-engines-and-configurations)
5. [The Latin/digit-token problem and the hybrid architectures](#5-the-latindigit-token-problem-and-the-hybrid-architectures)
6. [Results: hybrid strategies](#6-results-hybrid-strategies)
7. [Selected configuration and trade-offs](#7-selected-configuration-and-trade-offs)
8. [Limitations and validation targets](#8-limitations-and-validation-targets)
9. [Decisions](#9-decisions)
10. [Reproduction](#10-reproduction)

---

## 1. Summary

### Findings

- **The PaddleOCR Arabic model silently drops Latin words and Western digits inside Arabic lines.** It still reports
  confidence 0.89–0.96 for those lines. Only 35% of Latin/digit tokens on Arabic and mixed pages (IDs, dates, phone
  numbers, codes, amounts) survive. Confidence scores therefore **cannot** detect this loss.
- **Tesseract 5 keeps those tokens, but is weaker on almost everything else:**
  - noisy scans: 25.5% vs 15.9% error on poor scans;
  - tables, which it reads down each column;
  - upside-down pages.
- **The gated hybrid recovers most of the lost tokens without touching Arabic text.** It combines both engines, and
  any token it cannot recover safely is flagged for human review.

### Measured result of the preferred design (gated hybrid with token insertion)

| Metric | PaddleOCR alone | Gated hybrid |
|---|---|---|
| Latin/digit tokens kept (good scans · poor scans) | 34% · 35% | 58% · 64% |
| Error rate on Arabic and mixed pages (good · poor) | 11.6% · 15.9% | 9.8% · 12.6% |

Guarantees and costs of the gated hybrid:

- **0 false insertions, 0 duplicate tokens,** and PaddleOCR's Arabic text unchanged on all 35 page images.
- **Missing tokens still surface:** of the tokens still missing after the merge, 80% are pointed to by a review flag.
  "Confirmed missing" flags were 100% precise (12 of 12).
- **Extra time:** about +1.1 s per Arabic page, roughly doubling the OCR time for Arabic pages. English pages cost
  nothing extra.

### Other findings

- **The detection input cap is the most important performance setting.** PaddleOCR 3.7 runs detection on the full
  page image by default. Capping it at 1600 px makes it **5× faster** (1.05 s vs 5.05 s per page) and uses **40% less
  memory** (1.3 GB vs 2.2 GB), and it is also *more* accurate.
- **Fully diacritised Arabic fails in every engine** (about 70% error). These pages need human review.
- **The orientation classifiers are needed for upside-down pages, but hurt upright pages** when used on every page.
- **PaddlePaddle 3.3.1 cannot be used on CPU.** The selected configuration uses 3.2.2.
- **OCR output never goes straight to RAG ingestion.** Human review and approval sit between OCR and every
  downstream step (§7.4). OCR quality metrics measure how much review work there will be; they do not replace review.

---

## 2. Engines, models and versions

### 2.1 Runtime versions (pinned in `benchmarks/ocr/Dockerfile`)

| Component | Version | Role |
|---|---|---|
| Base image | `python:3.11.11-slim-bookworm` | |
| PaddleOCR | 3.7.0 | OCR pipeline API |
| PaddleX | 3.7.2 | Model runtime used by PaddleOCR |
| PaddlePaddle (CPU) | **3.2.2** | Inference engine, with oneDNN (MKL-DNN) acceleration |
| ONNX Runtime | 1.31.1 | Alternative inference engine (`engine="onnxruntime"`) |
| Tesseract | 5.3.0 (Debian bookworm), `tesseract-ocr-ara`, `tesseract-ocr-eng` | Second engine |
| pypdfium2 | 5.15.2 | PDF rasterisation |
| WeasyPrint | 70.0 | Corpus generation (Pango/HarfBuzz Arabic shaping) |
| rapidfuzz | 3.14.6 | Edit-distance metrics |

**PaddlePaddle 3.3.1 was rejected.** On CPU with oneDNN enabled, it fails with `NotImplementedError:
ConvertPirAttribute2RuntimeAttribute not support [pir::ArrayAttribute<pir::DoubleAttribute>]`. With oneDNN disabled,
a page took 20–30 s instead of about 1.3 s. 3.2.2 has the same accuracy and works.

### 2.2 Models

| Model | Type | Notes |
|---|---|---|
| `PP-OCRv5_mobile_det` | Text detection (finds line boxes) | Lightweight; **selected** |
| `PP-OCRv5_server_det` | Text detection | Larger and more accurate on poor scans; 7× slower |
| `arabic_PP-OCRv5_mobile_rec` | Text recognition (reads one line box) | Official Arabic model; reads Arabic and English; **selected** |
| `en_PP-OCRv5_mobile_rec` | Text recognition | English only; **used as the gate** in the hybrid |
| `PP-LCNet_x1_0_doc_ori`, `PP-LCNet_x1_0_textline_ori` | Page and line orientation classifiers | See §4.4 |

The community fine-tune `arabic_PP-OCRv6_small_rec` was **not** tested: it is not an official release.

### 2.3 How the engines differ

| | PaddleOCR + Arabic model | PaddleOCR + English model | Tesseract 5 `ara+eng` |
|---|---|---|---|
| Architecture | Deep-learning detector (line boxes) + CTC line recogniser | Same detector, English recogniser | LSTM line recogniser with classic page layout analysis |
| Arabic text | Good; best on noise | Cannot read Arabic | Fair; degrades fast with noise |
| English text | Excellent (0% on our English pages) | Excellent | Good; 8.5% error on noisy English |
| Latin/digit runs **inside Arabic lines** | **Dropped silently** at high confidence | Reads them (and outputs junk for the Arabic part) | **Reads them** |
| Output order | Logical Unicode order | Logical | Logical, but adds LRM/RLM direction marks |
| Tables | Row by row (correct) | — | Column by column (wrong order) |
| Upside-down pages | Needs the orientation classifier | Needs the orientation classifier | Fails |
| Confidence scores | Per line | Per line | Not used here |
| Memory and CPU | About 1.3 GB, about 4 CPU-s per page | Similar | **About 0.1 GB**, about 2 CPU-s per page |

### 2.4 PaddlePaddle vs ONNX Runtime

Both ran the same mobile detection and Arabic recognition models at 300 DPI with a 1600 px detection cap.

| | PaddlePaddle 3.2.2 (oneDNN) | ONNX Runtime 1.31.1 |
|---|---|---|
| Accuracy | Identical | Identical |
| Wall time per page (mean / p95) | **1.05 / 1.26 s** | 1.54 / 1.96 s |
| CPU per page | 4.2 s | 4.6 s |
| Peak memory | 1.29 GB | 1.18 GB |

PaddlePaddle is preferred. ONNX Runtime remains a fallback if image size or the PaddlePaddle dependency becomes a
problem. **Not measured:** whether PaddleOCR can run with ONNX Runtime *without* PaddlePaddle installed, and the
resulting image size.

---

## 3. Benchmark method

### 3.1 Dataset (synthetic only; original text, no real documents)

There are 9 A4 pages, defined in `benchmarks/ocr/corpus_text.py`:

| Page | Script | Font, size | What it tests |
|---|---|---|---|
| `ar_policy_amiri` | Arabic | Amiri 14 pt | Classical Naskh with stacked ligatures |
| `ar_sick_naskh` | Arabic | Noto Naskh Arabic 12 pt | Western and Eastern Arabic digits, phone number, date |
| `ar_diacritics_amiri` | Arabic | Amiri 16 pt | Fully diacritised text (tashkeel) |
| `ar_hiring_sans10` | Arabic | Noto Sans Arabic 10 pt | Small sans-serif text |
| `ar_table_naskh` | Arabic | Noto Naskh Arabic 12 pt | Right-to-left table |
| `en_leave_serif` | English | Noto Serif 12 pt | Plain English |
| `en_expenses_sans10` | English | Noto Sans 10 pt | Codes, amounts, e-mail address |
| `mixed_ar_en_naskh` | Mixed | Noto Naskh Arabic 12 pt | Latin terms, codes and e-mail **inside** Arabic lines |
| `mixed_lines_sans` | Mixed | Noto Sans Arabic 12 pt | Alternating Arabic and English lines |

Each page exists in six forms (54 page images in total):

| Profile | Description | Group in this document |
|---|---|---|
| `vector` | The digital PDF rasterised at the run DPI | Good |
| `clean300` | Scan at 300 DPI, light noise, JPEG quality 85 | Good |
| `rotated300` | Scan at 300 DPI, 3° skew | Good |
| `noisy200` | Scan at 200 DPI, 1.5° skew, blur, strong noise, salt-and-pepper, JPEG quality 55 | Poor |
| `lowres150` | Scan at 150 DPI, blur, noise, JPEG quality 70 | Poor |
| `flip180` | Scan at 300 DPI, page upside down | Separate pass (§4.4) |

The ground truth is the exact source text of each line, in logical Unicode order. The corpus is regenerated
deterministically (fixed random seed).

### 3.2 Metrics

All text is compared after NFKC normalisation, tatweel removal and whitespace collapsing.

| Metric | Definition |
|---|---|
| **Error rate (CER)** | Character edit distance ÷ ground-truth length, over the whole page in our reading order. It penalises recognition **and** ordering errors. |
| CER without diacritics | The same, after removing Arabic diacritics on both sides |
| Line CER | Order-free: each ground-truth line matched to its closest recognised line |
| Reading order | Share of consecutive ground-truth lines whose matches are in the same order (1.0 = perfect) |
| **Token recall** | Share of ground-truth Latin/digit tokens (e.g. `HRIS`, `1500`, `2026/01/01`, `hr-support@example.com`, `POL-2026-021`) present in the output. Eastern and Western digits are treated as equal. |
| False insertion | A token the hybrid inserted that is not in the ground truth |
| Review-flag precision | Share of flags that point to a real missing token (exact match, substring, or ≤ 34% edit distance) |
| Wall time / CPU | Per page, for OCR only (rendering reported separately). CPU is process CPU time across all threads. |
| Peak memory | `ru_maxrss` of the process; one configuration per container |

"Arabic and mixed pages" in the tables means the 7 Arabic and mixed pages **excluding** the diacritics page, which is
reported separately because it fails in every configuration and would hide the differences.

### 3.3 Environment

- **Machine:** Intel i7-6820HQ (4 cores / 8 threads, 2016 laptop), 16 GB RAM, Windows 10 with Docker Desktop (WSL2,
  6 GB / 6 CPU limit).
- **Each run:** its own container with `--cpus 4 --memory 3g --memory-swap 3g --network none` (no swap, so memory
  numbers are honest), 4 inference threads unless stated.
- **Background load:** other applications (IDE, PostgreSQL) were running. Treat times as **relative**, not absolute.

---

## 4. Results: single engines and configurations

### 4.1 Accuracy (error rate; Arabic and mixed pages, excluding the diacritics page)

| Configuration | Good scans | Poor scans | Diacritics page (all scans) |
|---|---|---|---|
| `pd_300_d1600_serverdet`: larger detection model, cap 1600 | **11.0%** | **13.3%** | 73.1% |
| `pd_200_d1600`: render at 200 DPI | 11.2% | 16.6% | 74.5% |
| **`pd_300_d1600`: mobile detection, 300 DPI, cap 1600 (base of the selection)** | 11.6% | 15.9% | 73.8% |
| `onnx_300_d1600`: same, on ONNX Runtime | 11.6% | 15.9% | 73.8% |
| `pd_300_d1200`: cap 1200 | 11.6% | 18.0% | 75.4% |
| `pd_300_d2400`: cap 2400 | 12.0% | 24.5% | 71.3% |
| `pd_300_dfull`: no cap (library default) | 14.7% | 23.2% | 74.1% |
| `tess_300_psm3`: Tesseract only | 17.1% | 25.5% | 83.0% |
| `pd_300_d1600_orient`: orientation classifiers always on | 18.3% | 32.5% | 81.7% |

**English pages:**
- Every PaddleOCR configuration: 0.0% error on all non-rotated profiles.
- Tesseract: 0% on good scans, 8.5% on noisy scans.
- `en_PP-OCRv5_mobile_rec` on mixed pages: about 64% error, because it cannot read Arabic.

**The Arabic model reads English well (0% error), so one recogniser is enough as the base.**

### 4.2 Per-page detail (error rate, good / poor scans)

| Page | PaddleOCR `pd_300_d1600` | Larger detection model | Tesseract | Dominant error |
|---|---|---|---|---|
| Amiri 14 pt | 6–12% / 12–29% | 7–9% / 12–13% | 8–19% / 6–59% | Stacked ligatures |
| Naskh with digits | 12% / 12–19% | 11–12% / 12–13% | 5–8% / 7–27% | PaddleOCR drops `15`, `30`, `0225551234`, `2026/01/01` |
| Sans 10 pt | 1.8% / 4–23% | 2% / 2–4% | 0–2% / 2–28% | Noise on small text |
| RTL table | 5–9% / 6–7% | 7–10% / 8–9% | 45–64% / 8–76% | Tesseract reads by column |
| Mixed, Latin inside Arabic lines | 31–35% / 33% | 30–32% / 37–38% | 6–17% / 8–61% | PaddleOCR drops `HRIS`, `Grade C`, `VPN`, `POL-…` |
| Mixed, alternating lines | 5.7% / 5.7% | 5.7% / 5.7% | 10–17% / 10–15% | PaddleOCR moves the final period to the start of English lines on RTL pages |
| Fully diacritised | 66–73% / 78–81% | 70–75% / 72–76% | 74–100% / 77–90% | Diacritics and letters lost |

### 4.3 Speed, CPU and memory (OCR only; rendering takes 0.1–0.2 s per page in addition)

| Configuration | Wall time per page, mean / p95 | CPU per page | Cores busy | Peak memory |
|---|---|---|---|---|
| `pd_300_d1200` | 0.71 / 1.03 s | 2.8 s | 4.0 | 1.16 GB |
| **`pd_300_d1600`** | **1.05 / 1.26 s** | **4.2 s** | 4.0 | **1.29 GB** |
| `pd_300_d1600_t2`: 2 threads | 1.29 / 1.60 s | **2.7 s** | 2.1 | 1.29 GB |
| `pd_200_d1600` | 1.17 / 1.57 s | 4.7 s | 4.0 | 1.15 GB |
| `pd_300_d1600_orient` | 1.18 / 1.50 s | 4.7 s | 4.0 | 1.25 GB |
| `onnx_300_d1600` | 1.54 / 1.96 s | 4.6 s | 3.0 | 1.18 GB |
| `pd_300_d2400` | 2.14 / 2.52 s | 8.5 s | 4.0 | 1.68 GB |
| `pd_300_dfull`: library default | 5.05 / 6.34 s | 19.7 s | 3.9 | 2.16 GB |
| `pd_300_d1600_serverdet` | 7.26 / 8.15 s | 29.0 s | 4.0 | 2.37 GB |
| `tess_300_psm3` | 1.15 / 1.62 s | 2.1 s | 1.8 | **0.10 GB** |

Model loading takes 2–4 s, once per service start.

**Why the detection cap matters:**
- At 300 DPI an A4 page is 2480×3508 px, and PaddleOCR 3.7 runs detection on the full image by default.
- Downscaling the *detection* input to 1600 px is faster, uses less memory, and is more accurate: at full resolution
  the detector split Amiri's stacked ligatures into fragments.
- Recognition still reads crops of the full-resolution image.

### 4.4 Orientation

| Page set | Classifiers off | Classifiers on |
|---|---|---|
| Upright pages (good / poor scans) | 11.6% / 15.9% | 18.3% / 32.5% |
| Upside-down Arabic pages | 75–87% | Same as upright (e.g. Sans 1.8%, table 6.8%, Amiri 8.2%) |
| Upside-down English pages | 97% | 0.0% |
| 3° skew | No loss | — |

The classifiers make upright pages worse. On the upright Naskh page, the document classifier treated the page as
upside down and the line order came out fully reversed. On Amiri pages, the line classifier flipped lines. Tesseract
cannot handle upside-down pages at all (86% error).

### 4.5 Other measured observations

- **PaddleOCR 3.7 returns Arabic in logical order.** No visual-to-logical conversion is needed (with conversion, the
  error is about 79%).
- **Tesseract inserts LRM/RLM/ALM direction marks** (U+200E, U+200F, U+061C). These are engine artefacts: remove them
  before the hidden-character and bidi warnings run, or every page would be flagged.
- **Built-in PDF text has the same weak spot.** pdfium returned `C Grade` for `Grade C` and moved the tanween in
  `يومًا`, even though the digital PDF had correct Unicode text. This matters for slice 2c.

---

## 5. The Latin/digit-token problem and the hybrid architectures

### 5.1 The problem

On the same line image, the two PaddleOCR models produce:

```text
Ground truth : يتم احتساب البدل وفق جدول Grade C بقيمة 1500 جنيه.
Arabic model : يتم احتساب البدل وفق جدول بقيمة جنيه          (confidence 0.93)
English model: 1500 Grad                                       (confidence 0.63)
```

- **Detection is correct:** one box covers the whole line.
- **The Arabic recogniser skips the left-to-right runs** inside a right-to-left line, while reporting high confidence.
- **Pure English lines are read perfectly,** so the failure is specific to mixed-direction lines.

### 5.2 Pipeline overview

```text
page image (300 DPI)
   │
   ├─► detection: PP-OCRv5_mobile_det on a copy downscaled to ≤1600 px ──► line boxes (in full-resolution coordinates)
   │
   └─► for each line box: crop from the FULL-resolution image
          │
          ├─► Arabic recogniser ──► arabicText, confidence                 [kept unchanged as the original output]
          │
          └─► only if arabicText contains Arabic letters:
                 STEP 1  gate: English recogniser on the same crop ──► latinTokens
                         hinted = latinTokens − tokens(arabicText), keeping tokens of ≥ 2 characters
                         hinted empty → stop (no further cost for this line)
                 STEP 2  Tesseract psm 7 (single line), ara+eng, on the same crop ──► tessText (direction marks removed)
                 STEP 3  agreement check:
                         recovered = (tokens(tessText) − tokens(arabicText)) ∩ latinTokens   ← both engines must see it
                         arabicAgreement = similarity(Arabic letters of arabicText, Arabic letters of tessText)
                         accept only if recovered ≠ ∅ AND arabicAgreement ≥ 0.5
                 STEP 4  merge (see 5.3) ──► mergedText + recovery details + review flags
```

### 5.3 Merge strategies

| | Line replacement (benchmark variant) | **Token insertion (preferred design)** |
|---|---|---|
| What changes | The whole PaddleOCR line is replaced by Tesseract's line | **Only** the recovered tokens are added; every character of the PaddleOCR line stays |
| Arabic text | Taken from Tesseract (may differ from PaddleOCR) | **Unchanged** (checked automatically, see below) |
| Meets "never silently replace Arabic" | ❌ No | ✅ Yes |

**How a token is placed (token insertion):**

1. **Skip duplicates.** If the token is already in the line (Western/Eastern digits equal), it is not inserted again.
2. **Find anchors.** In Tesseract's line, take the Arabic word immediately before the token and the one immediately
   after it.
3. **Match an anchor to the PaddleOCR line.** Diacritics and tatweel are ignored. The anchor must match **exactly one**
   word, either exactly or within 1 edit for words of 4 letters or more. Insert after the "before" anchor; otherwise
   before the "after" anchor.
4. **Refuse when unsure.** If no anchor matches exactly one word, the token is **not inserted**. It becomes a
   **"confirmed missing, unplaced"** review flag.
5. **Insert only the token.** When Tesseract's word also contains Arabic letters, only the token itself is inserted,
   never Tesseract's Arabic letters.
6. **Check the result.** After merging, the Arabic letters of the merged line must equal those of the PaddleOCR line,
   and every inserted token must be present intact. If either check fails, the whole line falls back to the PaddleOCR
   text and all its tokens become review flags.

### 5.4 Review flags

| Flag | Raised when | Measured precision |
|---|---|---|
| **Confirmed missing, unplaced** | Both secondary engines saw the token, but it could not be placed | **100%** (12 of 12) |
| **Possibly missing** | The English pass saw a token that is still absent after merging | 43% (35 of 82) |

The "possibly missing" flag is noisy, because the English model reads some Arabic letter shapes as junk Latin (`ill`,
`ww`, `al`), mostly on the diacritics and table pages. Even so, it points to 80% of the tokens that would otherwise be
silently lost. It should be shown as a **line-level hint**, not as a proposed value.

### 5.5 What production must store per line (design input for slices 2c/2d)

| Field | Content |
|---|---|
| `originalText` | PaddleOCR Arabic output, unchanged |
| `confidence` | Arabic recogniser confidence |
| `proposedText` | Line after token insertion; equals `originalText` when nothing was inserted |
| `recoveredTokens` | For each token: text, position, sources (`tesseract`, `paddle-en`), anchor used |
| `reviewFlags` | `TOKEN_RECOVERED` (inserted, verify), `TOKEN_MISSING_UNPLACED`, `TOKEN_POSSIBLY_MISSING`, `LOW_CONFIDENCE` |
| `bbox` | Line box, so the review UI can highlight the line on the page image |

The reviewer sees both texts and the flags, and the approved text comes from the human review step (§7.4).

---

## 6. Results: hybrid strategies

All rows below were measured on the 35 Arabic and mixed page images (diacritics page included in the averages unless
stated).

### 6.1 Overview

| Strategy | Token recall | Average error | Extra time per Arabic page | Arabic text changed? | Side effects |
|---|---|---|---|---|---|
| PaddleOCR Arabic only | 35% | 21.9% | — | — | Silent token loss |
| + English pass, flag only (`dual_rec.py`) | 35% kept + 41 points flagged (76% surfaced) | 21.9% | +30% | No | 1.95 false flags per page |
| + Tesseract on every Arabic line, line replacement, agreement ≥ 0.7 | 68% | 18.8% | +185% | **Yes** | Accepted invented Latin text: Amiri pages 6% → 11% |
| + Tesseract on every Arabic line, line replacement, agreement ≥ 0.5 | 76% | 18.7% | +169% | **Yes** | Same |
| Gated, line replacement | 72% | 17.9% | +96% | **Yes** | No regression on pure Arabic pages |
| **Gated, token insertion (preferred)** | **60%** | **19.9%** | **+101%** | **No** | **0 false insertions, 0 duplicates** |

### 6.2 Preferred design vs base, by scan quality (Arabic and mixed pages, diacritics page excluded from error rate)

| | PaddleOCR alone | Gated + line replacement | **Gated + token insertion** |
|---|---|---|---|
| Error rate, good scans | 11.6% | 7.2% | **9.8%** |
| Error rate, poor scans | 15.9% | 10.5% | **12.6%** |
| Token recall, good scans | 34% | 68% | **58%** |
| Token recall, poor scans | 35% | 78% | **64%** |
| Diacritics page error | 73.8% | 73.8% | 73.8% |

Token insertion recovers fewer tokens than line replacement, because it only adds tokens confirmed by **both**
secondary engines and placeable with certainty. In exchange, it never alters Arabic text and never inserts a wrong
token. The tokens it does not insert are flagged instead (§5.4).

### 6.3 Insertion details (35 page images)

| Measure | Value |
|---|---|
| Tokens inserted | 33 |
| False insertions (not in the ground truth) | **0** |
| Duplicates skipped | 0 (none occurred; the rule is in place) |
| Confirmed but unplaced (flagged instead) | 12 |
| Pages where the Arabic text stayed identical | **35 of 35** |
| Ground-truth tokens still missing after merge | 49, of which 39 (80%) have a review flag and **10 (20%) have none** |

### 6.4 Cost

| | Value |
|---|---|
| Base PaddleOCR time on these pages | 1.13 s mean |
| Second stage (English pass + Tesseract on gated lines) | 1.14 s mean, 1.96 s max |
| Pages without Arabic | No second stage |
| Peak memory | 1.19 GB (Tesseract runs as a separate process, about 0.1 GB) |

Tesseract was called with 1 thread, once per gated line, sequentially. Batching or parallel calls could reduce the
time; this was **not measured**.

---

## 7. Selected configuration and trade-offs

### 7.1 Preferred candidate (approved for implementation; not production-ready)

| Setting | Value |
|---|---|
| Engine | PaddleOCR 3.7.0 on PaddlePaddle 3.2.2 (CPU, oneDNN) |
| Detection | `PP-OCRv5_mobile_det`, `text_det_limit_type=max`, `text_det_limit_side_len=1600` |
| Recognition | `arabic_PP-OCRv5_mobile_rec`, on crops of the **full-resolution** page image |
| Rendering | 300 DPI |
| Orientation | Classifiers **off** by default; used only for pages that appear rotated (design to be validated in 2d) |
| Token recovery | English-model gate, then Tesseract 5 `ara+eng` psm 7 on gated lines; agreement ≥ 0.5; **token insertion only** |
| Flags | `TOKEN_RECOVERED`, `TOKEN_MISSING_UNPLACED`, `TOKEN_POSSIBLY_MISSING`, `LOW_CONFIDENCE` |
| Workers | Start with 1 worker × 4 threads; compare with 2 × 2 before finalising (§8) |

### 7.2 Why this configuration

- **Data completeness is the priority for HR documents.** The base model loses about two-thirds of IDs, dates, phone
  numbers and amounts inside Arabic lines, without warning. The hybrid recovers most of them and flags most of the
  rest.
- **Arabic text stays exactly what PaddleOCR read.** PaddleOCR is the best engine for Arabic on noisy scans and tables,
  so its output is never overwritten by a weaker engine.
- **Detection capped at 1600 px** is the best measured balance of accuracy, speed and memory.
- **Mobile detection instead of the larger model.** The larger model is better on poor scans (13.3% vs 15.9%) but 7×
  slower and needs 2.4 GB; it can become an optional "high-accuracy re-run" later.

### 7.3 Trade-offs accepted

| Trade-off | Effect | Mitigation |
|---|---|---|
| About 2× OCR time on Arabic pages | Measured: about 2.3 s instead of 1.1 s per Arabic page | Runs in the background; jobs never block the API (M2 design) |
| Fewer tokens recovered than line replacement (60% vs 72%) | Some tokens are flagged instead of filled in | Human review (§7.4) |
| Noisy "possibly missing" flags (43% precision) | Extra reviewer attention | Line-level hint, ranked below confirmed flags |
| Two engines in the OCR service | Larger image and more dependencies | One isolated OCR service, independently deployable |

### 7.4 Human review before RAG ingestion (architectural requirement)

```text
PDF/image upload → OCR extraction → HUMAN REVIEW & APPROVAL → text processing → chunking → embedding → indexing
```

- **Nothing reaches the RAG pipeline without review.** OCR results, including `proposedText`, are review input only.
- **The reviewer sees** the page image, the original OCR text, the proposed text with inserted tokens highlighted, the
  confidence and the flags. They can correct the text, with special attention to employee IDs, dates, phone numbers
  and financial values.
- **Originals stay unchanged** and corrections are tracked; only approved content moves on (milestones M3 and M4).
- OCR accuracy and the review flags reduce and focus review work. **Review is a safeguard, not a substitute for OCR
  quality, and OCR metrics are not a substitute for review.**

---

## 8. Limitations and validation targets

### 8.1 Known limitations (measured)

| Area | Measured behaviour | Consequence |
|---|---|---|
| Fully diacritised Arabic | About 70% error in every engine and configuration; confidence 0.36–0.49 | Pages need full manual review; the low-confidence flag catches them |
| Noisy and low-resolution scans | Error rises from 11.6% to 15.9% (base) or from 9.8% to 12.6% (hybrid); small fonts suffer most (Sans 10 pt up to 23%) | Expect more review work; the larger detection model is an option |
| Tables | PaddleOCR reads row by row correctly (5–9%); digits inside cells are partly lost (only 25% → 50% recovered) | Review cell values |
| Rotated pages | Upside-down pages are unreadable without the classifiers; the classifiers on every page hurt upright pages | Conditional orientation (to be validated) |
| Silent misses | 10 of 49 still-missing tokens (20%) had **no** flag | Reviewers must still check numbers and IDs on mixed pages |
| Punctuation | Final period moved to the start of English lines on RTL pages | Minor; visible in review |

### 8.2 Validation targets before production (not yet measured)

| Item | Target / question | When |
|---|---|---|
| Conditional orientation | Rotated pages recovered to within 1 point of upright accuracy, **without** degrading upright pages | Slice 2d |
| Workers and threads | Compare 1 × 4 with 2 × 2: pages per minute, peak memory, CPU use, and behaviour with several documents uploaded at once, while the other services (PostgreSQL, JVM, IDE, later Ollama) run | Slice 2d |
| Production OCR image | Size and memory with only the selected models | Slice 2d |
| Real documents | Repeat the measurements on authorised real HR documents (photocopies, phone photos, other fonts, multi-column) | Before production |
| Flag usefulness | Precision of "possibly missing" flags on real documents; reduce noise if it is under 50% | After M3 review UI is used |

**Expected throughput** (an estimate from the measurements above, **not** measured end to end): about 1.2 s per
English page and 2.3–2.5 s per Arabic page, including rendering, with 1 worker. A 500-page scanned Arabic document
would take about 20 minutes, in the background.

---

## 9. Decisions

Recorded on 2026-11-24 after review:

| # | Decision |
|---|---|
| 1 | **Gated hybrid approved** as the preferred OCR candidate. Keep the original Arabic OCR output; never silently replace uncertain Arabic content; flag unresolved, conflicting or possibly missing tokens for review; no duplicate or wrongly merged tokens. |
| 2 | **Detection cap 1600 px and 300 DPI approved.** Recognition uses crops of the full-resolution image. |
| 3 | **Orientation: conditional only.** Do not enable the classifiers on every page; validate in slice 2d before it is production-ready. |
| 4 | **Start with 1 worker × 4 threads.** Benchmark against 2 × 2 (throughput, peak memory, CPU, concurrent uploads) before finalising. |
| 5 | **Human review and approval are mandatory** between OCR and any RAG processing. |
| 6 | The OCR pipeline is **modular and independently deployable** from connected systems such as HRIS. Per-page results, confidence scores and review flags are preserved. |

---

## 10. Reproduction

```bash
docker build -t mimorag-ocr-bench:2a benchmarks/ocr   # about 3.5 GB; all models baked in, runs need no network
bash benchmarks/ocr/run_all.sh                        # full run: corpus, all configurations, Tesseract, hybrids, summaries
docker builder prune -f                               # optional: clears build cache after rebuilds (images and volumes untouched)
```

- **Outputs:** `benchmarks/results/ocr/` (git-ignored); the corpus is regenerated deterministically.
- **Scripts and options:** [benchmarks/ocr/README.md](../../benchmarks/ocr/README.md).
- **Run time:** about 45 minutes on the reference laptop.

| Results in this document | Produced by |
|---|---|
| §4.1–4.3 | `run_benchmark.py` configurations in `run_all.sh` (`runs/`) |
| §4.4 | `flip180_orient_{off,on}` (`runs_flip/`) |
| Tesseract | `run_tesseract.py` (`runs_tess/`) |
| §6, English-pass row | `dual_rec.py` (`runs_dual/`) |
| §6, hybrid rows | `hybrid_rec.py` with `--agree 0.7`, `--agree 0.5` and `--agree 0.5 --gate en` (`runs_hybrid/`) |
| Flag precision and missing-token coverage (§5.4, §6.3) | `flag_report.py` on `runs_hybrid/hybrid_agree50_gateen.jsonl` |
