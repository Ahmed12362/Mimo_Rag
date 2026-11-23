# ADR-0009: JPA annotations allowed in domain classes

- **Status:** Accepted (amends the domain rule of [ADR-0001](0001-modular-monolith-and-module-boundaries.md))
- **Date:** 2026-11-23

## Context

ADR-0001 kept `domain` free of all framework imports, including JPA. That would have required a second, JPA-annotated class plus a mapper for every aggregate. M1 introduced the first real aggregates: `Integration`, `ApiKey`, `TrustConfiguration`, `KnowledgeBase` and `KnowledgeBaseRoleGrant`. Switching away from JPA is not a realistic requirement, and a mapping layer kept only for that would be an abstraction for a hypothetical future.

## Decision

- **Allowed:** domain classes may use `jakarta.persistence` mapping annotations (`@Entity`, `@Id`, `@Column`, `@Version`, …).
- **Still forbidden in `domain`:** Spring, Hibernate-specific APIs, Jackson, Servlet, and any dependency on `application`, `api` or `infrastructure` (enforced by `ArchitectureRulesTest`).
- **Domain classes keep behaviour and invariants:** factory methods, no public setters, guarded state changes. JPA only needs a `protected` no-arg constructor.
- **Persistence ports** are declared in `application` as narrow Spring Data `Repository<T, ID>` interfaces that list only the methods the use cases need.
- **Pre-tenant JDBC** (SECURITY DEFINER functions) lives in `infrastructure`.
- **Application views** (immutable records without secrets) are returned directly as API response bodies; request bodies remain API-layer records.

## Consequences

- Much less code, with one class per aggregate.
- The domain is coupled to Jakarta Persistence annotations, a stable standard, but not to Hibernate.
- Lazy loading is avoided by design: aggregates reference each other by ID, never by JPA associations.
