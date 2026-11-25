-- M2 slice 2d: OCR results. Machine output, read-only, never approved content (review comes in M3).

-- ---------------------------------------------------------------------------------------------------------------
-- Document version: OCR states and the OCR job that serves it
-- ---------------------------------------------------------------------------------------------------------------
ALTER TABLE document_version DROP CONSTRAINT document_version_status_check;
ALTER TABLE document_version ADD CONSTRAINT document_version_status_check CHECK (status IN (
    'UPLOADED', 'VALIDATING', 'VALIDATED', 'VALIDATION_FAILED',
    'EXTRACTING', 'EXTRACTED', 'AWAITING_OCR', 'EXTRACTION_FAILED',
    'OCR_RUNNING', 'OCR_COMPLETED', 'OCR_FAILED'));
ALTER TABLE document_version DROP CONSTRAINT document_version_failure_code_matches_status;
ALTER TABLE document_version ADD CONSTRAINT document_version_failure_code_matches_status
    CHECK ((status IN ('VALIDATION_FAILED', 'EXTRACTION_FAILED', 'OCR_FAILED')) = (failure_code IS NOT NULL));
ALTER TABLE document_version ADD COLUMN ocr_job_id uuid;
CREATE UNIQUE INDEX document_version_ocr_job_idx ON document_version (integration_id, ocr_job_id)
    WHERE ocr_job_id IS NOT NULL;

-- ---------------------------------------------------------------------------------------------------------------
-- OCR run: one per OCR_DOCUMENT job; a retried or resumed job continues the same run (unique job_id).
-- ---------------------------------------------------------------------------------------------------------------
CREATE TABLE ocr_run (
    id                  uuid         PRIMARY KEY,
    integration_id      uuid         NOT NULL,
    document_version_id uuid         NOT NULL,
    extraction_run_id   uuid         NOT NULL,
    job_id              uuid         NOT NULL,
    status              varchar(20)  NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    pages_total         integer      NOT NULL CHECK (pages_total >= 0),
    pages_done          integer      NOT NULL CHECK (pages_done >= 0),
    pages_failed        integer      NOT NULL CHECK (pages_failed >= 0),
    failure_code        varchar(60),
    started_at          timestamptz  NOT NULL,
    finished_at         timestamptz,
    updated_at          timestamptz  NOT NULL,
    version             bigint       NOT NULL,
    CONSTRAINT ocr_run_version_fk FOREIGN KEY (document_version_id, integration_id)
        REFERENCES document_version (id, integration_id),
    CONSTRAINT ocr_run_extraction_fk FOREIGN KEY (extraction_run_id, integration_id)
        REFERENCES extraction_run (id, integration_id),
    CONSTRAINT ocr_run_job_unique UNIQUE (job_id),
    CONSTRAINT ocr_run_id_integration_unique UNIQUE (id, integration_id),
    CONSTRAINT ocr_run_progress CHECK (pages_done <= pages_total AND pages_failed <= pages_done),
    CONSTRAINT ocr_run_failure_code_matches_status CHECK ((status = 'FAILED') = (failure_code IS NOT NULL))
);
CREATE INDEX ocr_run_version_idx ON ocr_run (integration_id, document_version_id, started_at);

ALTER TABLE ocr_run ENABLE ROW LEVEL SECURITY;
ALTER TABLE ocr_run FORCE ROW LEVEL SECURITY;
CREATE POLICY ocr_run_tenant_isolation ON ocr_run
    USING (integration_id = current_integration_id())
    WITH CHECK (integration_id = current_integration_id());

-- ---------------------------------------------------------------------------------------------------------------
-- Page OCR: one row per run and page, READ-ONLY. original_text is the Arabic OCR exactly as recognised;
-- proposed_text adds only recovered Latin/digit tokens (gated hybrid). Both are unreviewed machine output.
-- ---------------------------------------------------------------------------------------------------------------
CREATE TABLE page_ocr (
    id                      uuid         PRIMARY KEY,
    integration_id          uuid         NOT NULL,
    ocr_run_id              uuid         NOT NULL,
    page_number             integer      NOT NULL CHECK (page_number >= 1),
    status                  varchar(20)  NOT NULL CHECK (status IN ('COMPLETED', 'FAILED')),
    mode                    varchar(20)  NOT NULL CHECK (mode IN ('PAGE', 'REGIONS')),
    render_dpi              integer      NOT NULL CHECK (render_dpi BETWEEN 50 AND 600),
    image_width             integer      NOT NULL CHECK (image_width >= 0),
    image_height            integer      NOT NULL CHECK (image_height >= 0),
    orientation_applied     integer      NOT NULL CHECK (orientation_applied IN (0, 90, 180, 270)),
    original_text           text         NOT NULL CHECK (length(original_text) <= 200000),
    proposed_text           text         NOT NULL CHECK (length(proposed_text) <= 200000),
    lines                   jsonb        NOT NULL,
    regions                 jsonb        NOT NULL,
    mean_confidence         real         NOT NULL CHECK (mean_confidence BETWEEN 0 AND 1),
    low_confidence_lines    integer      NOT NULL CHECK (low_confidence_lines >= 0),
    recovered_tokens        integer      NOT NULL CHECK (recovered_tokens >= 0),
    unplaced_tokens         integer      NOT NULL CHECK (unplaced_tokens >= 0),
    possibly_missing_tokens integer      NOT NULL CHECK (possibly_missing_tokens >= 0),
    dropped_overlapping     integer      NOT NULL CHECK (dropped_overlapping >= 0),
    flags                   text[]       NOT NULL,
    warnings                text[]       NOT NULL,
    error_code              varchar(60),
    engine                  jsonb        NOT NULL,
    timings                 jsonb        NOT NULL,
    duration_ms             integer      NOT NULL CHECK (duration_ms >= 0),
    created_at              timestamptz  NOT NULL,
    CONSTRAINT page_ocr_run_fk FOREIGN KEY (ocr_run_id, integration_id) REFERENCES ocr_run (id, integration_id),
    -- Retries, crash recovery and concurrent workers can never store the same page twice.
    CONSTRAINT page_ocr_page_unique UNIQUE (ocr_run_id, page_number),
    CONSTRAINT page_ocr_error_code_matches_status CHECK ((status = 'FAILED') = (error_code IS NOT NULL))
);

ALTER TABLE page_ocr ENABLE ROW LEVEL SECURITY;
ALTER TABLE page_ocr FORCE ROW LEVEL SECURITY;
CREATE POLICY page_ocr_tenant_isolation ON page_ocr
    USING (integration_id = current_integration_id())
    WITH CHECK (integration_id = current_integration_id());

REVOKE UPDATE, DELETE, TRUNCATE ON page_ocr FROM ${appRole};
REVOKE DELETE, TRUNCATE ON ocr_run FROM ${appRole};
