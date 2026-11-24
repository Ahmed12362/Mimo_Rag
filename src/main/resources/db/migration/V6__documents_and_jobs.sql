-- M2 slice 2b: documents, document versions and the PostgreSQL job queue (ADR-0011).
-- All three tables are tenant content: RLS ENABLED and FORCED, no platform-admin exception.

-- ---------------------------------------------------------------------------------------------------------------
-- Documents. A document is the stable identity of uploaded content inside one Knowledge Base; each upload of a
-- file is an immutable document_version (the original file is stored unchanged).
-- ---------------------------------------------------------------------------------------------------------------
CREATE TABLE document (
    id                 uuid          PRIMARY KEY,
    integration_id     uuid          NOT NULL,
    knowledge_base_id  uuid          NOT NULL,
    title              varchar(200)  NOT NULL,
    original_filename  varchar(255)  NOT NULL,
    current_version_id uuid,
    created_at         timestamptz   NOT NULL,
    created_by         varchar(300)  NOT NULL,
    updated_at         timestamptz   NOT NULL,
    version            bigint        NOT NULL,
    CONSTRAINT document_kb_fk FOREIGN KEY (knowledge_base_id, integration_id)
        REFERENCES knowledge_base (id, integration_id),
    CONSTRAINT document_id_integration_unique UNIQUE (id, integration_id)
);
CREATE INDEX document_listing_idx ON document (integration_id, knowledge_base_id, created_at, id);

CREATE TABLE document_version (
    id                uuid          PRIMARY KEY,
    integration_id    uuid          NOT NULL,
    document_id       uuid          NOT NULL,
    knowledge_base_id uuid          NOT NULL,
    version_number    integer       NOT NULL CHECK (version_number >= 1),
    storage_key       varchar(300)  NOT NULL,
    sha256            varchar(64)   NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    size_bytes        bigint        NOT NULL CHECK (size_bytes > 0),
    content_type      varchar(100)  NOT NULL,
    page_count        integer       CHECK (page_count IS NULL OR page_count >= 0),
    status            varchar(30)   NOT NULL CHECK (status IN ('UPLOADED', 'VALIDATING', 'VALIDATED',
                                                               'VALIDATION_FAILED')),
    failure_code      varchar(60),
    idempotency_key   varchar(200),
    extraction_job_id uuid          NOT NULL,
    uploaded_at       timestamptz   NOT NULL,
    uploaded_by       varchar(300)  NOT NULL,
    validated_at      timestamptz,
    updated_at        timestamptz   NOT NULL,
    version           bigint        NOT NULL,
    CONSTRAINT document_version_document_fk FOREIGN KEY (document_id, integration_id)
        REFERENCES document (id, integration_id),
    CONSTRAINT document_version_kb_fk FOREIGN KEY (knowledge_base_id, integration_id)
        REFERENCES knowledge_base (id, integration_id),
    CONSTRAINT document_version_number_unique UNIQUE (document_id, version_number),
    -- D-28: the same file uploaded again to the same Knowledge Base resolves to the existing document.
    CONSTRAINT document_version_content_unique UNIQUE (integration_id, knowledge_base_id, sha256),
    CONSTRAINT document_version_storage_key_unique UNIQUE (storage_key),
    CONSTRAINT document_version_failure_code_matches_status
        CHECK ((status = 'VALIDATION_FAILED') = (failure_code IS NOT NULL))
);
CREATE UNIQUE INDEX document_version_idempotency_idx ON document_version (integration_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
CREATE INDEX document_version_document_idx ON document_version (integration_id, document_id, version_number);

ALTER TABLE document ENABLE ROW LEVEL SECURITY;
ALTER TABLE document FORCE ROW LEVEL SECURITY;
CREATE POLICY document_tenant_isolation ON document
    USING (integration_id = current_integration_id())
    WITH CHECK (integration_id = current_integration_id());

ALTER TABLE document_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE document_version FORCE ROW LEVEL SECURITY;
CREATE POLICY document_version_tenant_isolation ON document_version
    USING (integration_id = current_integration_id())
    WITH CHECK (integration_id = current_integration_id());

-- ---------------------------------------------------------------------------------------------------------------
-- Job queue (ADR-0011): one table, no outbox. A job is enqueued in the same transaction as the business change.
-- context_snapshot holds the verified identity that enqueued it (ids, scopes, KB restriction; never secrets).
-- ---------------------------------------------------------------------------------------------------------------
CREATE TABLE job (
    id               uuid          PRIMARY KEY,
    integration_id   uuid          NOT NULL REFERENCES integration (id),
    kind             varchar(60)   NOT NULL CHECK (kind ~ '^[A-Z][A-Z0-9_]{1,59}$'),
    status           varchar(30)   NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'RETRY_SCHEDULED', 'SUCCEEDED',
                                                              'FAILED', 'CANCELLED_REVALIDATION')),
    payload          jsonb         NOT NULL,
    context_snapshot jsonb         NOT NULL,
    correlation_id   varchar(64),
    attempts         integer       NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    max_attempts     integer       NOT NULL CHECK (max_attempts BETWEEN 1 AND 20),
    run_after        timestamptz   NOT NULL,
    lease_owner      varchar(100),
    lease_expires_at timestamptz,
    error_code       varchar(60),
    retryable        boolean       NOT NULL DEFAULT true,
    created_at       timestamptz   NOT NULL,
    updated_at       timestamptz   NOT NULL,
    started_at       timestamptz,
    finished_at      timestamptz,
    CONSTRAINT job_lease_only_while_running CHECK ((status = 'RUNNING') = (lease_owner IS NOT NULL))
);
CREATE INDEX job_claim_idx ON job (kind, status, run_after, created_at);
CREATE INDEX job_tenant_status_idx ON job (integration_id, kind, status);

ALTER TABLE job ENABLE ROW LEVEL SECURITY;
ALTER TABLE job FORCE ROW LEVEL SECURITY;
CREATE POLICY job_tenant_isolation ON job
    USING (integration_id = current_integration_id())
    WITH CHECK (integration_id = current_integration_id());

-- Job history is kept; the runtime role can never delete jobs.
REVOKE DELETE, TRUNCATE ON job FROM ${appRole};

-- The worker functions below run as the schema owner (SECURITY DEFINER). Because RLS is FORCED, the owner needs its
-- own policy. The application never connects as the owner, so this policy is reachable only through these functions.
DO $$
BEGIN
    EXECUTE format('CREATE POLICY job_worker_functions ON job TO %I USING (true) WITH CHECK (true)', current_user);
END
$$;

-- Claims up to p_limit runnable jobs of the given kinds: queued or retry-due jobs, plus RUNNING jobs whose lease
-- expired and that still have attempts left (crash recovery). At most p_per_integration jobs of one kind run at a
-- time for the same integration, counting both jobs already running and jobs claimed in this same call, so one tenant
-- cannot occupy every worker. This is the ONLY cross-tenant query; it returns the stored context so the worker can
-- continue under the job's own tenant.
CREATE FUNCTION claim_jobs(p_kinds text[], p_limit integer, p_worker text, p_lease_seconds integer,
                           p_per_integration integer)
    RETURNS TABLE (id uuid, integration_id uuid, kind text, payload jsonb, context_snapshot jsonb,
                   correlation_id text, attempts integer, max_attempts integer)
    LANGUAGE sql VOLATILE SECURITY DEFINER
    SET search_path = mimorag, pg_temp
AS $$
    WITH eligible AS (
        SELECT j.id, j.run_after, j.created_at,
               row_number() OVER (PARTITION BY j.integration_id, j.kind ORDER BY j.run_after, j.created_at) AS rank,
               (SELECT count(*) FROM job r
                WHERE r.integration_id = j.integration_id AND r.kind = j.kind
                  AND r.status = 'RUNNING' AND r.lease_expires_at >= now()) AS running
        FROM job j
        WHERE j.kind = ANY (p_kinds)
          AND ((j.status IN ('QUEUED', 'RETRY_SCHEDULED') AND j.run_after <= now())
               OR (j.status = 'RUNNING' AND j.lease_expires_at < now() AND j.attempts < j.max_attempts))
    ),
    candidates AS (
        -- The runnable conditions are repeated here on purpose: PostgreSQL re-checks a row that was changed by a
        -- concurrent claim (after waiting for its lock) only against the conditions of the locking query itself.
        -- Without them, a job just claimed by another worker could be claimed a second time.
        SELECT j.id
        FROM job j JOIN eligible e ON e.id = j.id
        WHERE e.running + e.rank <= p_per_integration
          AND ((j.status IN ('QUEUED', 'RETRY_SCHEDULED') AND j.run_after <= now())
               OR (j.status = 'RUNNING' AND j.lease_expires_at < now() AND j.attempts < j.max_attempts))
        ORDER BY e.run_after, e.created_at
        LIMIT p_limit
        FOR UPDATE OF j SKIP LOCKED
    )
    UPDATE job j
    SET status = 'RUNNING', lease_owner = p_worker,
        lease_expires_at = now() + make_interval(secs => p_lease_seconds),
        attempts = j.attempts + 1, started_at = coalesce(j.started_at, now()), updated_at = now()
    FROM candidates c
    WHERE j.id = c.id
    RETURNING j.id, j.integration_id, j.kind, j.payload, j.context_snapshot, j.correlation_id, j.attempts,
              j.max_attempts
$$;

-- Extends the lease of a job this worker still holds (heartbeat). Returns false if the lease was lost.
CREATE FUNCTION extend_job_lease(p_job_id uuid, p_worker text, p_lease_seconds integer) RETURNS boolean
    LANGUAGE sql VOLATILE SECURITY DEFINER
    SET search_path = mimorag, pg_temp
AS $$
    WITH extended AS (
        UPDATE job SET lease_expires_at = now() + make_interval(secs => p_lease_seconds), updated_at = now()
        WHERE id = p_job_id AND lease_owner = p_worker AND status = 'RUNNING'
        RETURNING 1
    )
    SELECT exists (SELECT 1 FROM extended)
$$;

-- Jobs whose worker died on the last allowed attempt: mark them FAILED so they do not stay RUNNING forever.
-- Returns the affected jobs so the runner can let the owning module react (e.g. mark the document version failed).
CREATE FUNCTION fail_abandoned_jobs(p_kinds text[])
    RETURNS TABLE (id uuid, integration_id uuid, kind text, payload jsonb, context_snapshot jsonb,
                   correlation_id text)
    LANGUAGE sql VOLATILE SECURITY DEFINER
    SET search_path = mimorag, pg_temp
AS $$
    UPDATE job j
    SET status = 'FAILED', error_code = 'LEASE_EXPIRED', lease_owner = NULL, lease_expires_at = NULL,
        finished_at = now(), updated_at = now()
    WHERE j.kind = ANY (p_kinds) AND j.status = 'RUNNING' AND j.lease_expires_at < now()
      AND j.attempts >= j.max_attempts
    RETURNING j.id, j.integration_id, j.kind, j.payload, j.context_snapshot, j.correlation_id
$$;

REVOKE ALL ON FUNCTION claim_jobs(text[], integer, text, integer, integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION extend_job_lease(uuid, text, integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION fail_abandoned_jobs(text[]) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION claim_jobs(text[], integer, text, integer, integer) TO ${appRole};
GRANT EXECUTE ON FUNCTION extend_job_lease(uuid, text, integer) TO ${appRole};
GRANT EXECUTE ON FUNCTION fail_abandoned_jobs(text[]) TO ${appRole};
