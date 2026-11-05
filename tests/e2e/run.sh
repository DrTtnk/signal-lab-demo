#!/bin/sh
set -eu
cd "$(dirname "$0")/../.."
set -a
. ./tests/e2e/compose.env
set +a

compose() {
  docker compose --project-name signal-lab-e2e \
    --env-file "$PWD/tests/e2e/compose.env" -f "$PWD/docker/compose.yaml" "$@"
}

cleanup() {
  status=$?
  trap - EXIT
  cleanup_status=0
  compose down --volumes || cleanup_status=$?
  if [ "$status" -ne 0 ]; then
    exit "$status"
  fi
  exit "$cleanup_status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

compose down --volumes
(cd tools/fixtures && uv run --locked python -m signal_lab_fixtures ../../.work/e2e-fixtures)
docker/build.sh
compose up --no-build --wait --wait-timeout 120 -d
SIGNAL_LAB_COMPOSE_PROJECT=signal-lab-e2e \
  SIGNAL_LAB_COMPOSE_FILE="$PWD/docker/compose.yaml" \
  SIGNAL_LAB_ENV_FILE="$PWD/tests/e2e/compose.env" \
  WEB_BASE_URL="http://127.0.0.1:${WEB_PORT}" npm run test --workspace @signal-lab/e2e
