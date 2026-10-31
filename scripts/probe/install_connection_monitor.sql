-- Run as the database administrator in the target database:
-- gsql -d chaoslab -v monitor_user=chaos_fault -f install_connection_monitor.sql
-- Grants no SYSADMIN role and exposes only the thread-pool configuration string.
\set ON_ERROR_STOP on
BEGIN;
CREATE SCHEMA IF NOT EXISTS dbchaos_monitor;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_catalog.pg_namespace n
               WHERE n.nspname = 'dbchaos_monitor'
                 AND n.nspowner <> (SELECT oid FROM pg_catalog.pg_roles WHERE rolname = CURRENT_USER)) THEN
        RAISE EXCEPTION 'dbchaos_monitor must be owned by the installing administrator';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid=p.pronamespace
               WHERE n.nspname='dbchaos_monitor' AND p.proname='thread_pool_attr'
                 AND p.proowner <> (SELECT oid FROM pg_catalog.pg_roles WHERE rolname = CURRENT_USER)) THEN
        RAISE EXCEPTION 'refusing to replace a function owned by another role';
    END IF;
END;
$$;
REVOKE ALL ON SCHEMA dbchaos_monitor FROM PUBLIC;
CREATE OR REPLACE FUNCTION dbchaos_monitor.thread_pool_attr() RETURNS text
LANGUAGE SQL SECURITY DEFINER
AS $$ SELECT pg_catalog.current_setting('thread_pool_attr') $$;
REVOKE ALL ON FUNCTION dbchaos_monitor.thread_pool_attr() FROM PUBLIC;
GRANT USAGE ON SCHEMA dbchaos_monitor TO :"monitor_user";
GRANT EXECUTE ON FUNCTION dbchaos_monitor.thread_pool_attr() TO :"monitor_user";
COMMIT;
