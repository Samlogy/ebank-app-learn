-- Prepares the "ebank" database for dynamic credentials. Run as the PostgreSQL superuser
-- (POSTGRES_USER) by enable-dynamic-db.sh, with VAULT_ADMIN_PASSWORD in the environment.
-- Safe to run again: it only creates what is missing and resets vault_admin's password.
--
--   ebank_app    NOLOGIN. Owns the schema objects. Every user Vault creates is a member of it and
--                switches to it at login (ALTER ROLE ... SET role), so Flyway and Hibernate act as
--                ebank_app and new tables are owned by it, whichever user ran the migration.
--   vault_admin  LOGIN, CREATEROLE, ADMIN of ebank_app: what Vault uses to create and drop those
--                users. Vault changes its password right after (rotate-root): nobody else knows it.
\set ON_ERROR_STOP on
\getenv vault_password VAULT_ADMIN_PASSWORD
SET client_min_messages = warning;

SELECT NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'ebank_app') AS create_app \gset
\if :create_app
CREATE ROLE ebank_app NOLOGIN;
\endif
SELECT NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'vault_admin') AS create_admin \gset
\if :create_admin
CREATE ROLE vault_admin;
\endif
ALTER ROLE vault_admin WITH LOGIN CREATEROLE NOSUPERUSER NOCREATEDB PASSWORD :'vault_password';
-- ADMIN OPTION: needed (PostgreSQL 16+) to create users IN ROLE ebank_app. pg_signal_backend: to
-- close the sessions of a user whose lease is revoked.
GRANT ebank_app TO vault_admin WITH ADMIN OPTION;
GRANT pg_signal_backend TO vault_admin;

GRANT CONNECT, TEMPORARY ON DATABASE ebank TO ebank_app;
GRANT USAGE, CREATE ON SCHEMA public TO ebank_app;

-- Objects created so far by the static user (Flyway ran as POSTGRES_USER) move to ebank_app.
DO $$
DECLARE
  obj record;
BEGIN
  -- Tables and views. A table's identity and serial sequences follow it.
  FOR obj IN
    SELECT format('%I.%I', n.nspname, c.relname) AS name,
           CASE c.relkind WHEN 'v' THEN 'VIEW' WHEN 'm' THEN 'MATERIALIZED VIEW' ELSE 'TABLE' END AS kind
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p', 'v', 'm')
      AND c.relowner <> 'ebank_app'::regrole
  LOOP
    EXECUTE format('ALTER %s %s OWNER TO ebank_app', obj.kind, obj.name);
  END LOOP;
  -- Standalone sequences.
  FOR obj IN
    SELECT format('%I.%I', n.nspname, c.relname) AS name
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND c.relkind = 'S' AND c.relowner <> 'ebank_app'::regrole
      AND NOT EXISTS (SELECT FROM pg_depend d WHERE d.objid = c.oid AND d.deptype IN ('a', 'i'))
  LOOP
    EXECUTE format('ALTER SEQUENCE %s OWNER TO ebank_app', obj.name);
  END LOOP;
  -- Functions, enums and domains, if migrations add some.
  FOR obj IN
    SELECT p.oid::regprocedure::text AS name
    FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = 'public' AND p.proowner <> 'ebank_app'::regrole
  LOOP
    EXECUTE format('ALTER ROUTINE %s OWNER TO ebank_app', obj.name);
  END LOOP;
  FOR obj IN
    SELECT format('%I.%I', n.nspname, t.typname) AS name
    FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace
    WHERE n.nspname = 'public' AND t.typtype IN ('e', 'd') AND t.typowner <> 'ebank_app'::regrole
  LOOP
    EXECUTE format('ALTER TYPE %s OWNER TO ebank_app', obj.name);
  END LOOP;
END
$$;
