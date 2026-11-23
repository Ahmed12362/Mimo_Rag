# MimoRag — Progress Update

**What it is:** one reusable AI knowledge engine that HRIS, CRM, ERP and future systems can plug into. Each system uploads its documents and gets AI answers based only on its own data. Everything runs locally, with no paid AI APIs.

## Done so far

### 1. Foundation (M0)
- Spring Boot 4 / Java 21 modular monolith, PostgreSQL with migrations, Docker setup.
- Secure by default: every endpoint is locked unless explicitly opened.
- Standard error format, request tracing, health checks, automated architecture rules.
- The roadmap and architecture decisions are documented in the repo.

### 2. Integrations and Knowledge Bases (M1)
- Each connected system gets its own secure API keys. Users stay logged in to their own system; we only verify the signed user token it sends.
- Systems create Knowledge Bases and map their own roles to permissions (read, upload, review, …).
- **Strict tenant isolation, enforced at both the application and the database level** (PostgreSQL Row-Level Security). HRIS can never see CRM's data, and our own admins can't read tenant content either.
- Full audit trail.

### 3. Security hardening (after an internal review)
- Admins can no longer create keys that access tenant data. They only issue a one-time, 24-hour onboarding key; each system then manages its own keys.
- Keys must expire (90 days by default, 365 at most) and can be limited to specific Knowledge Bases.
- Stronger handling of user tokens, plus safeguards that catch tenant-isolation mistakes automatically.

## Quality
- **187 automated tests pass**, covering security, cross-tenant isolation and failure cases.
- Every stage was also checked against the running app.
- Typical request time is about 13 ms locally.

## Decisions locked in
- **Job queue:** PostgreSQL (no RabbitMQ/Kafka for now).
- **Storage:** local file storage behind an interface (S3 can be added later).
- **AI stack:** fully local: PaddleOCR (OCR), bge-m3 (embeddings), Qwen3 via Ollama (chat).

## What I'm learning alongside the build
I'm researching how **multi-tenant systems** work in practice:
- how one platform serves several separate systems from a single codebase and database while keeping each one's data fully isolated;
- the trade-offs between isolation approaches (separate databases, separate schemas, or shared tables with a tenant ID).

I'm also studying **Row-Level Security (RLS) in PostgreSQL**, the mechanism we chose for tenant isolation:
- the database itself filters every query by tenant, so even an application bug can't leak another tenant's data;
- RLS policies, `FORCE ROW LEVEL SECURITY`, database role privileges, and safely setting the tenant per transaction when connections are pooled.

## Next (M2)
- PDF upload, with page-by-page text extraction and OCR for scanned Arabic/English pages, running as background jobs.
- Then human review of the extracted pages before anything is indexed.
