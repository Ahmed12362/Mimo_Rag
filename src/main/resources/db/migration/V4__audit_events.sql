-- M1: append-only audit trail. The application role may insert and read (within RLS) but never update or delete.

CREATE TABLE audit_event (
    id             uuid          PRIMARY KEY,
    integration_id uuid,
    principal_type varchar(30)   NOT NULL,
    principal_ref  varchar(300),
    action         varchar(80)   NOT NULL,
    target_type    varchar(60),
    target_id      varchar(100),
    outcome        varchar(20)   NOT NULL CHECK (outcome IN ('SUCCESS', 'DENIED', 'FAILURE')),
    correlation_id varchar(64),
    occurred_at    timestamptz   NOT NULL,
    details        jsonb         NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX audit_event_integration_time_idx ON audit_event (integration_id, occurred_at);

REVOKE UPDATE, DELETE, TRUNCATE ON audit_event FROM ${appRole};

ALTER TABLE audit_event ENABLE ROW LEVEL SECURITY;
CREATE POLICY audit_event_read ON audit_event FOR SELECT
    USING (integration_id = current_integration_id() OR is_platform_admin());
-- Events without a tenant (e.g. failed authentication) are allowed; tenant events must match the current tenant.
CREATE POLICY audit_event_append ON audit_event FOR INSERT
    WITH CHECK (integration_id IS NULL OR integration_id = current_integration_id() OR is_platform_admin());
