# MimoRag OCR service

Arabic-first OCR for scanned PDF pages, deployed separately from the MimoRag application. The application renders
pages to PNG (PDFBox) and calls this service; the service never sees PDFs. Design, contract, limits, failure
handling and security requirements: [ADR-0014](../docs/adr/0014-ocr-service.md).

## Pipeline (`gated-hybrid/2d.3`)

1. PaddleOCR PP-OCRv5 mobile detection (longest side 1600) + Arabic mobile recognition → `originalText` per line.
2. **Gate:** lines containing Arabic are re-read by the English mobile recogniser; when that read contains Latin
   tokens or digits missing from the Arabic read, Tesseract (`ara+eng`, `psm 7`) reads the line too.
3. **Merge (insert only):** a token both secondary readers agree on (similarity ≥ 0.5) is inserted into
   `proposedText` at a uniquely anchored position (`TOKEN_RECOVERED`). Tokens that cannot be placed are listed
   (`TOKEN_MISSING_UNPLACED`); hints with no agreement are listed (`TOKEN_POSSIBLY_MISSING`). Arabic text is never
   changed (checked again by the application).
4. **Orientation:** on every page (`X-OCR-Orientation: auto`), the
   `PP-LCNet_x1_0_doc_ori` classifier proposes a rotation, and a page that reads poorly (< 20 characters or mean
   confidence < 0.60) is also tried upside down; a rotated reading is kept only if clearly better
   (mean confidence at least 0.05 higher and no more than a sixth fewer characters). Boxes are returned in the submitted image's coordinates.
5. Lines are put in reading order (rows by vertical position; right-to-left when Arabic dominates).

Models are downloaded at **build time** (`fetch_models.py`) and baked into the image; the container needs no
internet access.

## Run (development)

```bash
# from the repository root; MIMORAG_OCR_TOKEN (>= 32 characters) must be set in .env
docker compose --profile ocr build ocr        # builds the runtime stage: mimorag-ocr:2d (~2.4 GB)
docker compose --profile ocr up -d ocr        # published on 127.0.0.1:8090 only
curl -s http://127.0.0.1:8090/health/ready
```

Then start the application with `MIMORAG_OCR_ENABLED=true` and the same `MIMORAG_OCR_TOKEN`. Windows/Docker
Desktop notes and the production requirements are in ADR-0014 (they differ on purpose).

## Configuration (environment)

| Variable | Default | Meaning |
|---|---|---|
| `OCR_TOKEN` | — (required, ≥ 32 chars) | service-to-service token (`X-OCR-Token`) |
| `OCR_PROCESSES` / `OCR_THREADS` | 1 / 4 | OCR worker processes × CPU threads each (each process loads its own models) |
| `OCR_QUEUE_SIZE` | 2 | requests that may wait for a free process; more → 503 `SERVICE_BUSY` |
| `OCR_TIMEOUT_SECONDS` | 60 | per request; the worker is killed and replaced → 503 `OCR_TIMEOUT` |
| `OCR_MAX_BODY_BYTES` | 31457280 | body limit → 413 |
| `OCR_MAX_SIDE` / `OCR_MAX_PIXELS` / `OCR_MIN_SIDE` | 10000 / 40000000 / 16 | checked from the PNG header before decoding |
| `OCR_RECYCLE_AFTER` | 500 | a worker process is replaced after this many requests |
| `OCR_LOW_CONFIDENCE` | 0.80 | lines below are flagged `LOW_CONFIDENCE` |

## Tests

```bash
docker build --target test -t mimorag-ocr:test ocr-service   # unit + API tests (fake worker, no models)
```

The real engine is exercised from the Java side: `./mvnw test -Pocr-it` (accuracy gate against the 2a corpus).

## Dependencies

`requirements.txt` lists the direct pins; `requirements.lock.txt` is the full frozen set installed with
`--no-deps`. PaddlePaddle stays at 3.2.2 (3.3.x crashes with oneDNN on the benchmark CPUs; see the 2a results).
