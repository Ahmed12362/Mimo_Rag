-- Baseline migration. Establishes Flyway history in the `mimorag` schema.
-- The schema itself and the database roles are created by infrastructure provisioning
-- (docker/postgres/initdb for local development and tests), not by application migrations.
-- Business tables arrive from M1 onward; every tenant-owned table will carry integration_id and RLS policies.

COMMENT ON SCHEMA mimorag IS 'MimoRag platform schema. Owned by the migration owner role; the application role has DML-only access.';

-- Default privileges grant the application role DML on every table the owner creates. Migration history is the
-- exception: the runtime role must not be able to read or tamper with it.
REVOKE ALL ON TABLE flyway_schema_history FROM ${appRole};
