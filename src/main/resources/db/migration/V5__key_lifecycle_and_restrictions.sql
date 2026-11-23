-- M1 hardening (ADR-0012): bootstrap keys and self-service rotation, mandatory key expiry, per-integration scope
-- ceiling, Knowledge-Base-restricted keys, and API-key attribution in the audit trail.

-- ---------------------------------------------------------------------------------------------------------------
-- Scope ceiling: admins define what an integration may grant to its own keys, without ever holding such a key.
-- ---------------------------------------------------------------------------------------------------------------
ALTER TABLE integration ADD COLUMN allowed_scopes text[] NOT NULL DEFAULT ARRAY[]::text[];

-- Existing (development) integrations keep the scopes their keys already use.
UPDATE integration i
SET allowed_scopes = coalesce((SELECT array_agg(DISTINCT s ORDER BY s)
                               FROM integration_api_key k, unnest(k.scopes) s
                               WHERE k.integration_id = i.id), ARRAY[]::text[]);

ALTER TABLE integration ALTER COLUMN allowed_scopes DROP DEFAULT;
ALTER TABLE integration ADD CONSTRAINT integration_allowed_scopes_valid CHECK (allowed_scopes <@ ARRAY[
    'KB_READ', 'KB_MANAGE', 'DOCUMENT_UPLOAD', 'DOCUMENT_REVIEW', 'DOCUMENT_SUBMIT', 'TOOL_EXECUTE',
    'TOOL_CATALOG_MANAGE']::text[]);

-- ---------------------------------------------------------------------------------------------------------------
-- Key lifecycle: key type, mandatory expiry (max 365 days), creator/revoker attribution.
-- ---------------------------------------------------------------------------------------------------------------
ALTER TABLE integration_api_key ADD COLUMN key_type varchar(20) NOT NULL DEFAULT 'STANDARD';
ALTER TABLE integration_api_key ALTER COLUMN key_type DROP DEFAULT;
ALTER TABLE integration_api_key ADD COLUMN created_by varchar(300) NOT NULL DEFAULT 'platform-admin';
ALTER TABLE integration_api_key ALTER COLUMN created_by DROP DEFAULT;
ALTER TABLE integration_api_key ADD COLUMN revoked_by varchar(300);

UPDATE integration_api_key SET expires_at = created_at + interval '90 days' WHERE expires_at IS NULL;
UPDATE integration_api_key SET expires_at = created_at + interval '365 days'
WHERE expires_at > created_at + interval '365 days';
ALTER TABLE integration_api_key ALTER COLUMN expires_at SET NOT NULL;

ALTER TABLE integration_api_key
    ADD CONSTRAINT integration_api_key_type_valid CHECK (key_type IN ('BOOTSTRAP', 'STANDARD')),
    ADD CONSTRAINT integration_api_key_expiry_bounds
        CHECK (expires_at > created_at AND expires_at <= created_at + interval '365 days'),
    ADD CONSTRAINT integration_api_key_scopes_valid CHECK (cardinality(scopes) > 0 AND scopes <@ ARRAY[
        'KB_READ', 'KB_MANAGE', 'DOCUMENT_UPLOAD', 'DOCUMENT_REVIEW', 'DOCUMENT_SUBMIT', 'TOOL_EXECUTE',
        'TOOL_CATALOG_MANAGE', 'KEY_ROTATE']::text[]),
    -- A bootstrap key can only rotate, and lives at most 24 hours.
    ADD CONSTRAINT integration_api_key_bootstrap_rules CHECK (key_type <> 'BOOTSTRAP'
        OR (scopes = ARRAY['KEY_ROTATE']::text[] AND expires_at <= created_at + interval '24 hours')),
    -- Target of composite foreign keys (restrictions must belong to the key's own tenant).
    ADD CONSTRAINT integration_api_key_id_integration_unique UNIQUE (id, integration_id);

-- Integrations now manage their own keys; admins may only create bootstrap keys (but may revoke any key).
DROP POLICY integration_api_key_visibility ON integration_api_key;
CREATE POLICY integration_api_key_read ON integration_api_key FOR SELECT
    USING (integration_id = current_integration_id() OR is_platform_admin());
CREATE POLICY integration_api_key_insert ON integration_api_key FOR INSERT
    WITH CHECK ((integration_id = current_integration_id() AND key_type = 'STANDARD')
                OR (is_platform_admin() AND key_type = 'BOOTSTRAP'));
CREATE POLICY integration_api_key_update ON integration_api_key FOR UPDATE
    USING (integration_id = current_integration_id() OR is_platform_admin())
    WITH CHECK (integration_id = current_integration_id() OR is_platform_admin());

-- ---------------------------------------------------------------------------------------------------------------
-- Knowledge-Base-restricted keys. Credential metadata: RLS enabled but not FORCEd, so the SECURITY DEFINER
-- authentication lookup can read it before the tenant is known.
-- ---------------------------------------------------------------------------------------------------------------
CREATE TABLE integration_api_key_kb (
    api_key_id        uuid NOT NULL,
    integration_id    uuid NOT NULL,
    knowledge_base_id uuid NOT NULL,
    PRIMARY KEY (api_key_id, knowledge_base_id),
    CONSTRAINT integration_api_key_kb_key_fk FOREIGN KEY (api_key_id, integration_id)
        REFERENCES integration_api_key (id, integration_id) ON DELETE CASCADE,
    CONSTRAINT integration_api_key_kb_kb_fk FOREIGN KEY (knowledge_base_id, integration_id)
        REFERENCES knowledge_base (id, integration_id) ON DELETE CASCADE
);
CREATE INDEX integration_api_key_kb_kb_idx ON integration_api_key_kb (integration_id, knowledge_base_id);

ALTER TABLE integration_api_key_kb ENABLE ROW LEVEL SECURITY;
CREATE POLICY integration_api_key_kb_read ON integration_api_key_kb FOR SELECT
    USING (integration_id = current_integration_id() OR is_platform_admin());
CREATE POLICY integration_api_key_kb_insert ON integration_api_key_kb FOR INSERT
    WITH CHECK (integration_id = current_integration_id());

-- ---------------------------------------------------------------------------------------------------------------
-- Authentication lookup now also returns key type, the integration's scope ceiling and the KB restriction.
-- ---------------------------------------------------------------------------------------------------------------
DROP FUNCTION find_api_key_for_authentication(text);
CREATE FUNCTION find_api_key_for_authentication(p_key_id text)
    RETURNS TABLE (integration_id     uuid,
                   secret_hash        text,
                   key_status         text,
                   key_type           text,
                   expires_at         timestamptz,
                   scopes             text[],
                   allowed_scopes     text[],
                   integration_status text,
                   knowledge_base_ids uuid[])
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = mimorag, pg_temp
AS $$
    SELECT k.integration_id, k.secret_hash, k.status, k.key_type, k.expires_at, k.scopes, i.allowed_scopes,
           i.status,
           (SELECT array_agg(r.knowledge_base_id ORDER BY r.knowledge_base_id)
            FROM integration_api_key_kb r
            WHERE r.api_key_id = k.id)
    FROM integration_api_key k
             JOIN integration i ON i.id = k.integration_id
    WHERE k.key_id = p_key_id
$$;
REVOKE ALL ON FUNCTION find_api_key_for_authentication(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION find_api_key_for_authentication(text) TO ${appRole};

-- ---------------------------------------------------------------------------------------------------------------
-- Audit: which API key authenticated (or relayed a user for) the action.
-- ---------------------------------------------------------------------------------------------------------------
ALTER TABLE audit_event ADD COLUMN api_key_id varchar(32);
