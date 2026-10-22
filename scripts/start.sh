#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
docker compose up -d --build
WEB_ADDRESS=$(docker compose port web 80)
IDENTITY_ADDRESS=$(docker compose port keycloak 8080)
ATTEMPT=0
while [ "$ATTEMPT" -lt 90 ]; do
  API_STATUS=$(curl -s --max-time 2 -o /dev/null -w '%{http_code}' "http://$WEB_ADDRESS/api/v1/auth/me" || true)
  if [ "$API_STATUS" = "401" ] && curl -fsS --max-time 2 "http://$IDENTITY_ADDRESS/realms/assets/.well-known/openid-configuration" >/dev/null 2>&1; then
    printf '\n资产台账：http://%s\n开发账号：admin / Admin-local-2026!；ops / Demo-local-2026!\n' "localhost:${WEB_ADDRESS##*:}"
    exit 0
  fi
  ATTEMPT=$((ATTEMPT + 1))
  sleep 2
done
printf '启动等待超时，请查看 docker compose logs api keycloak gateway。\n' >&2
exit 1
