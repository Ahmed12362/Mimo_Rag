-- M2 slice 2c: native text extraction. Per-page results are read-only; runs record progress and can resume.

-- ---------------------------------------------------------------------------------------------------------------
-- Document version: extraction states
-- ---------------------------------------------------------------------------------------------------------------
ALTER TABLE document_version DROP CONSTRAINT document_version_status_check;
ALTER TABLE document_version ADD CONSTRAINT document_version_status_check CHECK (status IN (
    'UPLOADED', 'VALIDATING', 'VALIDATED', 'VALIDATION_FAILED',
    'EXTRACTING', 'EXTRACTED', 'AWAITING_OCR', 'EXTRACTION_FAILED'));
ALTER TABLE document_version DROP CONSTRAINT document_version_failure_code_matches_status;
ALTER TABLE document_version ADD CONSTRAINT document_version_failure_code_matches_status
    CHECK ((status IN ('VALIDATION_FAILED', 'EXTRACTION_FAILED')) = (failure_code IS NOT NULL));
-- Target of composite foreign keys (children can never point at another tenant's version).
ALTER TABLE document_version ADD CONSTRAINT document_version_id_integration_unique UNIQUE (id, integration_id);

-- ---------------------------------------------------------------------------------------------------------------
-- Extraction run: one per job. A resumed or retried job continues the same run (unique job_id).
-- Counters are recomputed from the committed page rows after every batch.
-- ---------------------------------------------------------------------------------------------------------------
CREATE TABLE extraction_run (
    id                  uuid         PRIMARY KEY,
    integration_id      uuid         NOT NULL,
    document_version_id uuid         NOT NULL,
    job_id              uuid         NOT NULL,
    engine              varchar(60)  NOT NULL,
    engine_version      varchar(60)  NOT NULL,
    status              varchar(20)  NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    pages_total         integer      NOT NULL CHECK (pages_total >= 0),
    pages_done          integer      NOT NULL CHECK (pages_done >= 0),
    pages_native        integer      NOT NULL CHECK (pages_native >= 0),
    pages_ocr_required  integer      NOT NULL CHECK (pages_ocr_required >= 0),
    pages_mixed         integer      NOT NULL CHECK (pages_mixed >= 0),
    pages_empty         integer      NOT NULL CHECK (pages_empty >= 0),
    pages_failed        integer      NOT NULL CHECK (pages_failed >= 0),
    failure_code        varchar(60),
    started_at          timestamptz  NOT NULL,
    finished_at         timestamptz,
    updated_at          timestamptz  NOT NULL,
    version             bigint       NOT NULL,
    CONSTRAINT extraction_run_version_fk FOREIGN KEY (document_version_id, integration_id)
        REFERENCES document_version (id, integration_id),
    CONSTRAINT extraction_run_job_unique UNIQUE (job_id),
    CONSTRAINT extraction_run_id_integration_unique UNIQUE (id, integration_id),
    CONSTRAINT extraction_run_progress CHECK (pages_done <= pages_total),
    CONSTRAINT extraction_run_failure_code_matches_status CHECK ((status = 'FAILED') = (failure_code IS NOT NULL))
);
CREATE INDEX extraction_run_version_idx ON extraction_run (integration_id, document_version_id, started_at);

ALTER TABLE extraction_run ENABLE ROW LEVEL SECURITY;
ALTER TABLE extraction_run FORCE ROW LEVEL SECURITY;
CREATE POLICY extraction_run_tenant_isolation ON extraction_run
    USING (integration_id = current_integration_id())
    WITH CHECK (integration_id = current_integration_id());

-- ---------------------------------------------------------------------------------------------------------------
-- Page extraction: machine output per page, READ-ONLY (workflow: PageExtraction is never modified; human edits are
-- separate revisions, M3). native_text is always kept as extracted; result_text is set only when it is trusted.
-- ---------------------------------------------------------------------------------------------------------------
CREATE TABLE page_extraction (
    id                  uuid         PRIMARY KEY,
    integration_id      uuid         NOT NULL,
    extraction_run_id   uuid         NOT NULL,
    page_number         integer      NOT NULL CHECK (page_number >= 1),
    page_class          varchar(20)  NOT NULL CHECK (page_class IN ('NATIVE_TEXT', 'OCR_REQUIRED', 'MIXED', 'EMPTY',
                                                                     'FAILED')),
    native_text         text         NOT NULL CHECK (length(native_text) <= 200000),
    native_text_trusted boolean      NOT NULL,
    result_text         text,
    scripts             text[]       NOT NULL,
    warnings            text[]       NOT NULL,
    quality             jsonb        NOT NULL,
    lines               jsonb        NOT NULL,
    images              jsonb        NOT NULL,
    image_coverage      real         NOT NULL CHECK (image_coverage BETWEEN 0 AND 1),
    page_width          real         NOT NULL,
    page_height         real         NOT NULL,
    rotation            integer      NOT NULL CHECK (rotation IN (0, 90, 180, 270)),
    duration_ms         integer      NOT NULL CHECK (duration_ms >= 0),
    created_at          timestamptz  NOT NULL,
    CONSTRAINT page_extraction_run_fk FOREIGN KEY (extraction_run_id, integration_id)
        REFERENCES extraction_run (id, integration_id),
    -- Crash recovery and concurrent workers can never store the same page twice.
    CONSTRAINT page_extraction_page_unique UNIQUE (extraction_run_id, page_number),
    -- Untrusted native text is never offered as the page result.
    CONSTRAINT page_extraction_result_is_trusted CHECK (result_text IS NULL OR native_text_trusted)
);

ALTER TABLE page_extraction ENABLE ROW LEVEL SECURITY;
ALTER TABLE page_extraction FORCE ROW LEVEL SECURITY;
CREATE POLICY page_extraction_tenant_isolation ON page_extraction
    USING (integration_id = current_integration_id())
    WITH CHECK (integration_id = current_integration_id());

REVOKE UPDATE, DELETE, TRUNCATE ON page_extraction FROM ${appRole};
REVOKE DELETE, TRUNCATE ON extraction_run FROM ${appRole};
