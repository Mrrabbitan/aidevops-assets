#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
umask 077
mkdir -p backups
BACKUP_FILE="backups/assets-$(date +%Y%m%d-%H%M%S).dump"
docker compose exec -T db pg_dump -U assets -Fc assets > "$BACKUP_FILE"
docker compose exec -T db pg_dump -U assets -Fc keycloak > "$BACKUP_FILE.keycloak"
printf '%s\n' "$BACKUP_FILE"
