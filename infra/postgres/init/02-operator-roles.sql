-- ADR-0022 point 1: the database roles of the application, created by the administrator before the first migration.
-- Local development and tests use this script with these fixed passwords. In staging and production the administrator
-- runs the same statements with passwords from the secret store (runbooks: Bootstrapping the database roles).
--
--   operator_migrator  owns the schema; used only by the migration step (ADR-0023).
--   operator_runtime   the API and the worker; owns nothing, no BYPASSRLS, subject to every policy.
--   operator_system    NOLOGIN; owns the reviewed SECURITY DEFINER functions for cross-store questions.
--   operator_app       NOLOGIN; the store role of migration V23, kept only so that V23 applies unchanged.

CREATE ROLE operator_migrator LOGIN PASSWORD 'operator_migrator' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
CREATE ROLE operator_runtime LOGIN PASSWORD 'operator_runtime' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
CREATE ROLE operator_system NOLOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
CREATE ROLE operator_app NOLOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;

-- The migrator may hand objects to these roles (SET) without gaining their rights (INHERIT FALSE).
GRANT operator_system TO operator_migrator WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;
GRANT operator_app TO operator_migrator WITH ADMIN TRUE, INHERIT FALSE, SET TRUE;

DO $$
BEGIN
    EXECUTE format('ALTER DATABASE %I OWNER TO operator_migrator', current_database());
END
$$;
ALTER SCHEMA public OWNER TO operator_migrator;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
