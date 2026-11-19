-- One-time setup of the local development database on a native PostgreSQL 16+ install.
-- Idempotent: safe to run again.
--
-- Run as a superuser with psql (default password is repointel_dev):
--   psql -U postgres -f scripts/db/create-dev-db.sql
-- Choose a different password (then set DB_PASSWORD to match):
--   psql -U postgres -v db_password=yourpassword -f scripts/db/create-dev-db.sql
--
-- Flyway creates the schema objects when the application starts; this script
-- only creates the role and the database.

\set ON_ERROR_STOP on

\if :{?db_password}
\else
  \set db_password repointel_dev
\endif

SELECT format('CREATE ROLE repointel LOGIN PASSWORD %L', :'db_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'repointel')
\gexec

SELECT 'CREATE DATABASE repointelligence OWNER repointel ENCODING ''UTF8'' TEMPLATE template0'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'repointelligence')
\gexec
