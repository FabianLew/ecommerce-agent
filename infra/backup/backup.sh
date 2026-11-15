#!/usr/bin/env bash
# Logical backup of the operator database (ADR-0023): a custom-format dump plus its SHA-256.
# The managed database's point-in-time recovery is the primary backup; this dump is the independent daily copy
# that the restore drill (restore-check.sh) proves usable. Uploading it to EU object storage is the platform's job.
#
#   PGHOST=... PGUSER=... PGPASSWORD=... PGDATABASE=operator ./backup.sh /backups
set -euo pipefail

target_dir="${1:?usage: backup.sh <target-directory>}"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
file="${target_dir}/operator-${stamp}.dump"

mkdir -p "${target_dir}"
# Grants are kept: the store role operator_app needs them after a restore (ADR-0022).
pg_dump --format=custom --compress=6 --no-owner --file="${file}"
sha256sum "${file}" > "${file}.sha256"
echo "${file}"
