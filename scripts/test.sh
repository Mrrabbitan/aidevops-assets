#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
docker compose up -d --wait db
if ! docker compose exec -T db psql -U assets -d postgres -tAc "select 1 from pg_database where datname='assets_test'" | grep -q 1; then docker compose exec -T db createdb -U assets assets_test; fi
assets_test_password="$(docker compose exec -T db printenv POSTGRES_PASSWORD)"
docker run --rm --network aidevops-assets_default -e ASSETS_TEST_JDBC_URL=jdbc:postgresql://db:5432/assets_test -e ASSETS_TEST_JDBC_USER=assets -e ASSETS_TEST_JDBC_PASSWORD="${assets_test_password}" -v "$PWD:/workspace" -v aidevops-assets-maven:/root/.m2 -w /workspace maven:3.9.11-eclipse-temurin-21 mvn -B clean test package
cd frontend
npm ci
npm test
npm run build
