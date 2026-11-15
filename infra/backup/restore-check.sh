#!/usr/bin/env bash
# Restore drill (ADR-0023): restores a dump into a fresh scratch database and checks that it is usable.
# Never point it at a live database: it drops and recreates the scratch database it is given.
#
#   PGHOST=... PGUSER=... PGPASSWORD=... ./restore-check.sh /backups/operator-20261115T020000Z.dump [scratch_db]
set -euo pipefail

dump="${1:?usage: restore-check.sh <dump-file> [scratch-database]}"
scratch="${2:-operator_restore_check}"

if [[ "${scratch}" == "operator" ]]; then
  echo "refusing to restore into the database named operator" >&2
  exit 2
fi
if [[ -f "${dump}.sha256" ]]; then
  sha256sum --check --status "${dump}.sha256"
fi

# The store role is cluster-wide (migration V23); a fresh cluster needs it before the grants can be restored.
psql --quiet --dbname=postgres --command="DO \$\$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'operator_app') THEN CREATE ROLE operator_app NOLOGIN; END IF; END \$\$"
psql --quiet --dbname=postgres --command="DROP DATABASE IF EXISTS \"${scratch}\""
psql --quiet --dbname=postgres --command="CREATE DATABASE \"${scratch}\""
pg_restore --exit-on-error --no-owner --dbname="${scratch}" "${dump}"

query() {
  psql --dbname="${scratch}" --tuples-only --no-align --command="$1"
}

failed_migrations="$(query "SELECT count(*) FROM flyway_schema_history WHERE NOT success")"
latest="$(query "SELECT max(installed_rank) || ' ' || max(version) FROM flyway_schema_history WHERE success")"
stores="$(query "SELECT count(*) FROM store")"
audit_from="$(query "SELECT coalesce(min(occurred_at)::text, 'empty') FROM audit_event")"
policies="$(query "SELECT count(*) FROM pg_policy")"
store_role="$(query "SELECT has_table_privilege('operator_app', 'public.store', 'SELECT')")"

echo "migrations: ${latest} (failed: ${failed_migrations})"
echo "stores: ${stores}; audit from: ${audit_from}; row-level security policies: ${policies}"
if [[ "${failed_migrations}" != "0" ]]; then
  echo "restore check FAILED: the dump has failed migrations" >&2
  exit 1
fi
if [[ "${policies}" == "0" ]]; then
  echo "restore check FAILED: no row-level security policies were restored" >&2
  exit 1
fi
if [[ "${store_role}" != "t" ]]; then
  echo "restore check FAILED: the grants of operator_app were not restored" >&2
  exit 1
fi
echo "restore check passed"
