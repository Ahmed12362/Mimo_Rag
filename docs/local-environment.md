# Local Development Environment — Resources and Settings

> Development-only settings for the reference machine (Windows 10, 16 GB RAM, i7-6820HQ 4C/8T, Quadro M1000M 2 GB).
> None of these values is a production resource decision.

## Where things run

| Component | Runs in | Memory (typical / peak) | Milestone |
|---|---|---|---|
| PostgreSQL 17 + pgvector | Docker (WSL2) | 0.1 / 0.5 GB | M0 |
| Testcontainers PostgreSQL (during `mvnw verify`) | Docker (WSL2) | +0.3 GB each, up to 2 at once | M0 |
| PaddleOCR sidecar (1 worker, CPU) | Docker (WSL2) | 0.8 / 1.5 GB | M2 |
| MimoRag (JVM) | Windows | 0.3 / 1 GB (`-Xmx1g` recommended) | M0 |
| Ollama: bge-m3 embeddings | **Windows, native** | ~1.3 GB | M4 |
| Ollama: Qwen3-4B (Q4_K_M, 4k context) | **Windows, native** | ~3.5–4 GB | M5 |

Ollama runs natively so the models are **outside** the WSL memory cap and can optionally use the GPU.

## WSL2 / Docker Desktop limit — `C:\Users\<you>\.wslconfig`

Current setting (applied 2026-11-23):

```ini
[wsl2]
memory=6GB          # ceiling for Docker only (Postgres, OCR, Testcontainers); WSL uses less in practice
processors=6        # leaves 2 of 8 threads for Windows, IDE and Ollama during OCR-heavy runs
swap=2GB            # swap file lives on C:, which is short on space

[experimental]
autoMemoryReclaim=gradual   # return cached memory to Windows (verify on Windows 10 by watching vmmem)
```

- **Change:** edit `memory=` and run `wsl --shutdown` (Docker Desktop restarts; containers with
  `restart: unless-stopped` come back; data in volumes is kept).
- **Remove:** delete the file and run `wsl --shutdown` (default cap is 50 % of RAM = 8 GB).
- **Why 6 GB:** Docker peak is ~3.2 GB (OCR + test containers); 4 GB has too little headroom, 8 GB can squeeze the
  Windows side (LLM, IDE) if WSL does not hand back cached memory.
- **If `vmmem` stays near the cap** (reclaim not working on Windows 10): lower to `memory=5GB` or restart Docker
  periodically.

## Ollama settings (from M4)

| Variable | Value | Why |
|---|---|---|
| `OLLAMA_MODELS` | `D:\ollama-models` | Keep model files off the nearly full C: drive |
| `OLLAMA_MAX_LOADED_MODELS` | `1` | Only one model resident (saves ~1.3 GB); switching costs a few seconds |
| `OLLAMA_KEEP_ALIVE` | `5m` | Unload idle models |
| `num_ctx` (per request) | `4096` | KV-cache memory grows with context length |

## Data root (from M2)

| Setting | Value | Notes |
|---|---|---|
| `MIMORAG_INGESTION_STORAGE_ROOT` | `D:/mimorag-data` | Outside the repository and Docker; originals (since 2b), page cache (2e). Default `data/storage` (git-ignored). |
| `MIMORAG_PAGE_CACHE_MAX_MB` | `1024` | Rendered page images; LRU eviction down to 80 % when exceeded; never counts toward tenant quotas |

## Disk budget (re-checked before every large download)

| Stage | Download / growth | Location |
|---|---|---|
| M2 | OCR sidecar image ~2–2.6 GB; data ~1.5 GB | C: (Docker), D: (data) |
| M4 | Ollama ~1–3 GB; bge-m3 1.2 GB (+0.6 GB benchmark challenger, deleted after) | C: (Ollama), D: (models) |
| M5 | Qwen3-4B 2.5 GB, reranker ~1 GB (+ temporary benchmark candidates up to ~8 GB) | D: |

Free space at the last check (2026-11-23): C: 12.3 GB, D: 8.6 GB. At least 15 GB free on D: is recommended before
the M5 model benchmark.
