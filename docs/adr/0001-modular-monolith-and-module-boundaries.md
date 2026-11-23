# ADR-0001: Modular monolith and module boundaries

- **Status:** Accepted. The domain/JPA rule was amended by [ADR-0009](0009-jpa-annotations-in-domain.md).
- **Date:** 2026-11-23

## Context

The platform has several distinct capabilities: integrations, KBs, ingestion, review, indexing, retrieval, tools, agent and evaluation. Early on, a small team builds them on a single developer machine. Microservices would add network, deployment and consistency overhead without a current scaling need. OCR (Python/Paddle) and model runtimes (Ollama) have their own ecosystems.

## Decision

- **One deployable:** a single Spring Boot application (modular monolith), root package `com.mimocodes.mimorag`.
- **Modules:** `shared`, `access`, `integration`, `knowledgebase`, `ingestion`, `review`, `indexing`, `retrieval`, `tools`, `agent`, `evaluation`.
- **Layers inside each module:** `api` → `application` → `domain`, with `infrastructure` implementing ports.
- **Communication between modules:** through `application` interfaces or published domain events, never through another module's internals.
- **External processes:** OCR and model runtimes run as separate processes behind internal HTTP contracts.
- **Enforcement:** ArchUnit (`ArchitectureRulesTest`), not Spring Modulith, for now.

## Consequences

- One build, one deployment, transactional consistency inside one database.
- Boundaries are enforced by tests. A violation fails the build.
- The `domain` layer must stay free of Spring and JPA, so JPA entities live in `infrastructure` and need mapping. This will be revisited with real entities in M1 and documented in a new ADR if changed.
- A module can later be extracted into a service if load or ownership requires it, because its dependencies are already explicit.
