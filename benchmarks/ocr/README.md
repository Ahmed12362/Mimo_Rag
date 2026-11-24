# OCR benchmark (M2 slice 2a)

Development tool used to choose the production OCR configuration. It is **not** the production OCR service.
Results and the recommendation: [docs/benchmarks/ocr-benchmark-2a.md](../../docs/benchmarks/ocr-benchmark-2a.md).

## Reproduce

```bash
docker build -t mimorag-ocr-bench:2a benchmarks/ocr     # ~3.5 GB image (C: on the dev machine), models baked in
bash benchmarks/ocr/run_all.sh                          # full run, about 45 min on the dev laptop
bash benchmarks/ocr/run_all.sh pd_300_d1600             # or selected PaddleOCR configurations only
```

Disk: each rebuild of the pip layer leaves several GB of Docker build cache. Clear it afterwards with
`docker builder prune -f` (it never touches images or volumes).

| Script | Purpose |
|---|---|
| `generate_corpus.py`, `corpus_text.py` | Synthetic corpus and ground truth |
| `run_benchmark.py` | One PaddleOCR configuration over the corpus |
| `run_tesseract.py` | Tesseract 5 with the same metrics |
| `dual_rec.py` | Second pass with the English recogniser over Arabic lines (token recall, false flags, extra time) |
| `hybrid_rec.py` | PaddleOCR + Tesseract per line, ungated or gated by the English recogniser; reports line replacement and token insertion (preferred design) |
| `flag_report.py` | Review-flag precision per tier and missing-token coverage for a hybrid run |
| `summarize.py`, `per_page.py` | Markdown tables |
| `inspect_page.py` | Debug: raw boxes, texts and scores for one page |

Outputs go to `benchmarks/results/ocr/` (git-ignored): `corpus/` (PDFs + `manifest.json`) and `runs*/<config>.jsonl`.
The corpus is deterministic (fixed seed); regenerate it by deleting `corpus/`.

## Pinned versions

| Component | Version | Note |
|---|---|---|
| Base image | `python:3.11.11-slim-bookworm` | |
| paddleocr / paddlex | 3.7.0 / 3.7.2 | |
| paddlepaddle (CPU) | **3.2.2** | 3.3.1 crashes on CPU with oneDNN enabled (`ConvertPirAttribute2RuntimeAttribute not support pir::ArrayAttribute<pir::DoubleAttribute>`); without oneDNN it is ~15x slower |
| onnxruntime | 1.31.1 | Used through PaddleOCR's `engine="onnxruntime"` |
| weasyprint / pypdfium2 / rapidfuzz | 70.0 / 5.15.2 / 3.14.6 | Corpus rendering, rasterising, metrics |
| Tesseract (Debian bookworm) | 5.3.0, `tesseract-ocr-ara`, `tesseract-ocr-eng` | Comparison engine and hybrid second reader |
| Fonts (Debian) | `fonts-hosny-amiri`, `fonts-noto-core` | OFL |

Models (downloaded at build time, then runs use `--network none`): `PP-OCRv5_mobile_det`, `PP-OCRv5_server_det`,
`arabic_PP-OCRv5_mobile_rec`, `en_PP-OCRv5_mobile_rec`, plus the document and text-line orientation classifiers.

## Corpus

Synthetic only (original text, no real documents), defined in `corpus_text.py`: 9 A4 pages.

| Page | Script | Font, size | Tests |
|---|---|---|---|
| `ar_policy_amiri` | Arabic | Amiri 14 pt | Classical Naskh with stacked ligatures |
| `ar_sick_naskh` | Arabic | Noto Naskh 12 pt | Western and Eastern Arabic digits, dates, phone number |
| `ar_diacritics_amiri` | Arabic | Amiri 16 pt | Full diacritics (tashkeel) |
| `ar_hiring_sans10` | Arabic | Noto Sans Arabic 10 pt | Small sans-serif text |
| `ar_table_naskh` | Arabic | Noto Naskh 12 pt | RTL table |
| `en_leave_serif`, `en_expenses_sans10` | English | Noto Serif 12, Noto Sans 10 | Codes, amounts, e-mail |
| `mixed_ar_en_naskh` | Mixed | Noto Naskh 12 | Latin terms inside Arabic lines |
| `mixed_lines_sans` | Mixed | Noto Sans Arabic 12 | Alternating Arabic and English lines |

Each page exists as a digital PDF (`vector`, rasterised at the run DPI) and five image-only "scans":

| Profile | Scan DPI | Degradation |
|---|---|---|
| `clean300` | 300 | light noise, JPEG q85 |
| `noisy200` | 200 | 1.5° skew, blur, strong noise, salt-and-pepper, JPEG q55 |
| `lowres150` | 150 | blur, noise, JPEG q70 |
| `rotated300` | 300 | 3° skew |
| `flip180` | 300 | page upside down |

## Metrics

All text is compared after NFKC normalisation, tatweel removal and whitespace collapsing. Ground truth is in logical
Unicode order.

- **CER**: character edit distance / ground-truth length, over the whole page in our reading order (so it penalises
  both recognition and ordering errors).
- **CER without diacritics**: the same after removing Arabic diacritics from both sides.
- **Line CER**: order-free; each ground-truth line is matched to its closest recognised row (recognition quality only).
- **Reading order**: share of consecutive ground-truth lines whose matched rows are in the same order (1.0 = perfect).
- **WER**: word edit distance / ground-truth words.
- **Time**: wall time of `predict()` per page; **CPU**: process CPU time per page (all threads); cores busy = CPU/wall.
- **Peak memory**: `ru_maxrss` of the process (one configuration per container; swap disabled, 3 GB cap).

Reading order is our own simple layout step (`order_rows`): rows by vertical overlap, right-to-left within a row when
Arabic letters dominate the page. Multi-column layouts are out of scope for this benchmark. Each run also reports a
`v2l` variant that converts Arabic from visual to logical order; PaddleOCR 3.7 already returns logical order, so
`raw` is the relevant variant.
