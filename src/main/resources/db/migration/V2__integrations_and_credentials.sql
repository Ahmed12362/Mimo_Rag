-- M1: integrations (tenants), integration API keys, user-assertion trust configuration, RLS helpers.
-- See ADR-0004 (tenant isolation), ADR-0005 (identity model) and ADR-0010 (credentials and RLS details).

-- ---------------------------------------------------------------------------------------------------------------
-- RLS helper functions. Settings are written per transaction (set_config(..., is_local => true)) by the
-- application's transaction manager. An unset or empty tenant yields NULL, so tenant policies match zero rows.
-- ---------------------------------------------------------------------------------------------------------------
CREATE FUNCTION current_integration_id() RETURNS uuid
    LANGUAGE sql STABLE
AS $$ SELECT NULLIF(current_setting('app.integration_id', true), '')::uuid $$;

CREATE FUNCTION is_platform_admin() RETURNS boolean
    LANGUAGE sql STABLE
AS $$ SELECT coalesce(current_setting('app.access_plane', true), '') = 'PLATFORM_ADMIN' $$;

-- ---------------------------------------------------------------------------------------------------------------
-- Integrations. The integration row is the tenant itself; only platform admins can create or modify it.
-- ---------------------------------------------------------------------------------------------------------------
CREATE TABLE integration (
    id          uuid          PRIMARY KEY,
    code        varchar(64)   NOT NULL UNIQUE CHECK (code ~ '^[a-z][a-z0-9-]{1,63}$'),
    name        varchar(200)  NOT NULL,
    description varchar(2000),
    status      varchar(20)   NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    created_at  timestamptz   NOT NULL,
    updated_at  timestamptz   NOT NULL,
    version     bigint        NOT NULL
);

ALTER TABLE integration ENABLE ROW LEVEL SECURITY;
CREATE POLICY integration_visibility ON integration
    USING (id = current_integration_id() OR is_platform_admin())
    WITH CHECK (is_platform_admin());

-- ---------------------------------------------------------------------------------------------------------------
-- Integration API keys: format mrk_<key_id>_<secret>. Only the SHA-256 of the secret is stored.
-- ---------------------------------------------------------------------------------------------------------------
CREATE TABLE integration_api_key (
    id             uuid          PRIMARY KEY,
    integration_id uuid          NOT NULL REFERENCES integration (id),
    key_id         varchar(32)   NOT NULL UNIQUE CHECK (key_id ~ '^[a-f0-9]{24}$'),
    secret_hash    varchar(64)   NOT NULL CHECK (secret_hash ~ '^[a-f0-9]{64}$'),
    last4          varchar(4)    NOT NULL,
    name           varchar(100)  NOT NULL,
    scopes         text[]        NOT NULL,
    status         varchar(20)   NOT NULL CHECK (status IN ('ACTIVE', 'REVOKED')),
    expires_at     timestamptz,
    created_at     timestamptz   NOT NULL,
    revoked_at     timestamptz,
    last_used_at   timestamptz,
    version        bigint        NOT NULL
);
CREATE INDEX integration_api_key_integration_idx ON integration_api_key (integration_id, created_at);

ALTER TABLE integration_api_key ENABLE ROW LEVEL SECURITY;
CREATE POLICY integration_api_key_visibility ON integration_api_key
    USING (integration_id = current_integration_id() OR is_platform_admin())
    WITH CHECK (is_platform_admin());

-- ---------------------------------------------------------------------------------------------------------------
-- Trust configuration for signed user assertions (one per integration).
-- inline_jwks is a development-only alternative to jwks_uri; the application refuses it unless explicitly enabled.
-- ---------------------------------------------------------------------------------------------------------------
CREATE TABLE integration_trust_config (
    integration_id             uuid          PRIMARY KEY REFERENCES integration (id),
    issuer                     varchar(500)  NOT NULL,
    audience                   varchar(500)  NOT NULL,
    jwks_uri                   varchar(2000),
    inline_jwks                text,
    allowed_algorithms         text[]        NOT NULL,
    max_token_lifetime_seconds integer       NOT NULL CHECK (max_token_lifetime_seconds BETWEEN 30 AND 3600),
    clock_skew_seconds         integer       NOT NULL CHECK (clock_skew_seconds BETWEEN 0 AND 300),
    claim_user_id              varchar(100)  NOT NULL,
    claim_roles                varchar(100),
    claim_groups               varchar(100),
    updated_at                 timestamptz   NOT NULL,
    version                    bigint        NOT NULL,
    CONSTRAINT integration_trust_config_one_key_source CHECK ((jwks_uri IS NULL) <> (inline_jwks IS NULL)),
    CONSTRAINT integration_trust_config_algorithms CHECK (allowed_algorithms <@ ARRAY['RS256', 'ES256']::text[]
        AND cardinality(allowed_algorithms) > 0)
);

ALTER TABLE integration_trust_config ENABLE ROW LEVEL SECURITY;
CREATE POLICY integration_trust_config_visibility ON integration_trust_config
    USING (integration_id = current_integration_id() OR is_platform_admin())
    WITH CHECK (is_platform_admin());

-- ---------------------------------------------------------------------------------------------------------------
-- Pre-tenant credential lookup. Authentication must find a key before the tenant is known; instead of weakening
-- RLS, these SECURITY DEFINER functions expose exactly one key's verification data (or touch its last-use time).
-- They run as the schema owner, which RLS does not restrict on these (non-FORCEd) platform tables.
-- ---------------------------------------------------------------------------------------------------------------
CREATE FUNCTION find_api_key_for_authentication(p_key_id text)
    RETURNS TABLE (integration_id     uuid,
                   secret_hash        text,
                   key_status         text,
                   expires_at         timestamptz,
                   scopes             text[],
                   integration_status text)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = mimorag, pg_temp
AS $$
    SELECT k.integration_id, k.secret_hash, k.status, k.expires_at, k.scopes, i.status
    FROM integration_api_key k
             JOIN integration i ON i.id = k.integration_id
    WHERE k.key_id = p_key_id
$$;

CREATE FUNCTION record_api_key_use(p_key_id text, p_used_at timestamptz) RETURNS void
    LANGUAGE sql VOLATILE SECURITY DEFINER
    SET search_path = mimorag, pg_temp
AS $$
    UPDATE integration_api_key
    SET last_used_at = p_used_at
    WHERE key_id = p_key_id
      AND (last_used_at IS NULL OR last_used_at < p_used_at - interval '5 minutes')
$$;

REVOKE ALL ON FUNCTION find_api_key_for_authentication(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION record_api_key_use(text, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION find_api_key_for_authentication(text) TO ${appRole};
GRANT EXECUTE ON FUNCTION record_api_key_use(text, timestamptz) TO ${appRole};
