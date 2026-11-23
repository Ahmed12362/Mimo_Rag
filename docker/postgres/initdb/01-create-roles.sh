#!/bin/bash
# Runs once, on first initialization of an empty Postgres data directory (docker-entrypoint-initdb.d).
# Also reused verbatim by the Testcontainers integration tests, so local, test and CI share one role model:
#   mimorag_owner - owns the `mimorag` schema and runs Flyway migrations (DDL)
#   mimorag_app   - runtime role: DML only, no DDL, NOBYPASSRLS (required for Row-Level Security from M1)
set -euo pipefail

: "${MIMORAG_DB_OWNER_PASSWORD:?MIMORAG_DB_OWNER_PASSWORD must be set}"
: "${MIMORAG_DB_APP_PASSWORD:?MIMORAG_DB_APP_PASSWORD must be set}"

psql -v ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  -v db_name="$POSTGRES_DB" \
  -v owner_password="$MIMORAG_DB_OWNER_PASSWORD" \
  -v app_password="$MIMORAG_DB_APP_PASSWORD" <<'EOSQL'
CREATE ROLE mimorag_owner LOGIN PASSWORD :'owner_password'
  NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
CREATE ROLE mimorag_app LOGIN PASSWORD :'app_password'
  NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;

REVOKE ALL ON DATABASE :"db_name" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"db_name" TO mimorag_owner, mimorag_app;
REVOKE ALL ON SCHEMA public FROM PUBLIC;

CREATE SCHEMA mimorag AUTHORIZATION mimorag_owner;
GRANT USAGE ON SCHEMA mimorag TO mimorag_app;

ALTER DEFAULT PRIVILEGES FOR ROLE mimorag_owner IN SCHEMA mimorag
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO mimorag_app;
ALTER DEFAULT PRIVILEGES FOR ROLE mimorag_owner IN SCHEMA mimorag
  GRANT USAGE, SELECT ON SEQUENCES TO mimorag_app;

ALTER ROLE mimorag_owner SET search_path = mimorag;
ALTER ROLE mimorag_app SET search_path = mimorag;
EOSQL
