# ADR-0002: Own provider abstractions; stable dependencies only

- **Status:** Accepted
- **Date:** 2026-11-23

## Context

The initial project included Spring AI `2.1.0-M1` (a milestone release) and its pgvector starter. That starter auto-configures a vector store and may create its own table, which would bypass our migrations and our per-model/per-dimension index design. The RAG core must not depend on any AI framework or vendor. Development must be free and local first.

## Decision

- **Own interfaces:** the core depends on our interfaces: `EmbeddingProvider`, `ChatModelProvider`, `VectorStore`, `OcrProvider`, `PdfTextExtractor`, `ToolExecutor`, and a storage abstraction.
- **Spring AI:** may be used **only inside infrastructure adapters**, and only where it adds value.
- **No pre-release dependencies** (`-M`, `-RC`, snapshots) in the MVP. Spring AI and the pgvector starter were removed from `pom.xml` in M0.
- **pgvector:** introduced in M4 through our own Flyway migrations.
- **Local providers by default:** Ollama and local PaddleOCR. Paid or hosted providers need explicit approval, are clearly labelled, and are never a silent fallback.

## Consequences

- Swapping providers (local ↔ hosted, Ollama ↔ another runtime) touches only adapters and configuration.
- We write a little more adapter code than a framework-first approach would need.
- Every new provider must honour the interface contract (Liskov). Adapter integration tests verify this.
