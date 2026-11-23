-- M1: Knowledge Bases and their role-to-permission grants. Tenant content: RLS is ENABLED and FORCED, and there is
-- deliberately no platform-admin exception (admins have no default access to tenant content, ADR-0005).

CREATE TABLE knowledge_base (
    id                          uuid          PRIMARY KEY,
    integration_id              uuid          NOT NULL REFERENCES integration (id),
    code                        varchar(64)   NOT NULL CHECK (code ~ '^[a-z][a-z0-9-]{1,63}$'),
    name                        varchar(200)  NOT NULL,
    description                 varchar(2000),
    status                      varchar(20)   NOT NULL CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    allow_service_search        boolean       NOT NULL DEFAULT false,
    require_different_submitter boolean       NOT NULL DEFAULT false,
    created_at                  timestamptz   NOT NULL,
    created_by                  varchar(200)  NOT NULL,
    updated_at                  timestamptz   NOT NULL,
    version                     bigint        NOT NULL,
    CONSTRAINT knowledge_base_code_unique UNIQUE (integration_id, code),
    -- Target of composite foreign keys, so children can never point at another tenant's KB.
    CONSTRAINT knowledge_base_id_integration_unique UNIQUE (id, integration_id)
);
CREATE INDEX knowledge_base_listing_idx ON knowledge_base (integration_id, created_at, id);

ALTER TABLE knowledge_base ENABLE ROW LEVEL SECURITY;
ALTER TABLE knowledge_base FORCE ROW LEVEL SECURITY;
CREATE POLICY knowledge_base_tenant_isolation ON knowledge_base
    USING (integration_id = current_integration_id())
    WITH CHECK (integration_id = current_integration_id());

CREATE TABLE knowledge_base_role_grant (
    id                uuid          PRIMARY KEY,
    integration_id    uuid          NOT NULL,
    knowledge_base_id uuid          NOT NULL,
    role              varchar(200)  NOT NULL,
    permission        varchar(40)   NOT NULL CHECK (permission IN
                                        ('KB_READ', 'KB_MANAGE', 'DOCUMENT_UPLOAD', 'DOCUMENT_REVIEW', 'DOCUMENT_SUBMIT')),
    created_at        timestamptz   NOT NULL,
    CONSTRAINT knowledge_base_role_grant_kb_fk FOREIGN KEY (knowledge_base_id, integration_id)
        REFERENCES knowledge_base (id, integration_id) ON DELETE CASCADE,
    CONSTRAINT knowledge_base_role_grant_unique UNIQUE (knowledge_base_id, role, permission)
);
CREATE INDEX knowledge_base_role_grant_lookup_idx ON knowledge_base_role_grant (integration_id, role, knowledge_base_id);

ALTER TABLE knowledge_base_role_grant ENABLE ROW LEVEL SECURITY;
ALTER TABLE knowledge_base_role_grant FORCE ROW LEVEL SECURITY;
CREATE POLICY knowledge_base_role_grant_tenant_isolation ON knowledge_base_role_grant
    USING (integration_id = current_integration_id())
    WITH CHECK (integration_id = current_integration_id());
