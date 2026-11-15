#!/usr/bin/env bash
# Checks the Flyway migrations of the backend (ADR-0023, branch integration plan):
# - every version is used once;
# - compared with a base ref (the pull request's target branch), no migration of the base was changed, renamed or
#   removed, and every new migration has a higher version than the highest one of the base.
# Usage: scripts/check-migrations.sh [base-ref]
set -euo pipefail

dir="backend/src/main/resources/db/migration"
base="${1:-}"
cd "$(git rev-parse --show-toplevel)"

version() {
  local name
  name="$(basename "$1")"
  name="${name#V}"
  echo "${name%%__*}"
}

status=0
duplicates="$(for f in "$dir"/V*__*.sql; do version "$f"; done | sort | uniq -d)"
if [[ -n "$duplicates" ]]; then
  echo "Duplicate migration versions: $duplicates" >&2
  status=1
fi
for f in "$dir"/*.sql; do
  if [[ ! "$(basename "$f")" =~ ^V[0-9]+__[a-z0-9_]+\.sql$ ]]; then
    echo "Migration name does not follow V<version>__<description>.sql: $f" >&2
    status=1
  fi
done

if [[ -n "$base" ]]; then
  highest=0
  while IFS= read -r path; do
    [[ "$path" == *.sql ]] || continue
    v="$(version "$path")"
    (( v > highest )) && highest="$v"
    if [[ ! -f "$path" ]]; then
      echo "A migration of $base was removed or renamed: $path" >&2
      status=1
    elif ! git diff --quiet "$base" -- "$path"; then
      echo "A migration of $base was changed: $path (add a new migration instead)" >&2
      status=1
    fi
  done < <(git ls-tree -r --name-only "$base" -- "$dir")
  for f in "$dir"/V*__*.sql; do
    if ! git cat-file -e "$base:$f" 2>/dev/null && (( $(version "$f") <= highest )); then
      echo "New migration $f is not above the highest version of $base (V$highest)" >&2
      status=1
    fi
  done
fi

if (( status == 0 )); then
  echo "Migrations OK${base:+ against $base}"
fi
exit "$status"
