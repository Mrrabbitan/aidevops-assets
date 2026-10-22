#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
if [ "$#" -ne 1 ] || [ ! -r "$1" ]; then printf 'Usage: scripts/restore.sh BACKUP.dump\n' >&2; exit 2; fi
if [ "${CONFIRM_RESTORE:-}" != "yes" ]; then printf 'Restore replaces existing data. Set CONFIRM_RESTORE=yes after backing up.\n' >&2; exit 2; fi
docker compose stop api worker gateway web
docker compose exec -T db pg_restore -U assets -d assets --clean --if-exists --no-owner < "$1"
if [ -r "$1.keycloak" ]; then docker compose stop keycloak; docker compose exec -T db pg_restore -U assets -d keycloak --clean --if-exists --no-owner < "$1.keycloak"; docker compose up -d keycloak; fi
docker compose up -d api worker gateway web
