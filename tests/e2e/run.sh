#!/bin/sh
set -eu
cd "$(dirname "$0")/../.."
set -a
. ./.env
set +a
npm run fixtures
compose="docker compose --env-file .env -f docker/compose.yaml"

test_status=0
$compose down --volumes &&
  docker/build.sh &&
  $compose up --wait --wait-timeout 120 -d &&
  SIGNAL_LAB_COMPOSE_FILE="$PWD/docker/compose.yaml" SIGNAL_LAB_ENV_FILE="$PWD/.env" \
    WEB_BASE_URL="http://127.0.0.1:${WEB_PORT}" npm run test --workspace @signal-lab/e2e ||
  test_status=$?

down_status=0
$compose down || down_status=$?

if [ "$test_status" -ne 0 ]; then
  exit "$test_status"
fi
exit "$down_status"
