#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
set -a
. ./.env
set +a

export KUBECONFIG="$PWD/.kube/config"
mkdir -p "$PWD/.kube"

profile=signal-lab
namespace=signal-lab
port="${K8S_WEB_PORT:-4174}"

kube() { kubectl --context "$profile" --namespace "$namespace" "$@"; }

case "${1:?usage: minikube.sh up|e2e|stop|down}" in
up)
  shift
  minikube start --profile "$profile" --driver docker --cpus 4 --memory 6g
  npm run fixtures
  minikube --profile "$profile" ssh -- sudo mkdir -p /data/signal-lab-fixtures
  for file in .fixtures/*; do
    minikube --profile "$profile" cp "$file" "/data/signal-lab-fixtures/$(basename "$file")"
  done
  for name in web application recordings; do
    id="$(docker image inspect --format '{{.Id}}' "signal-lab-$name:latest")"
    tag="$(printf '%.12s' "${id#sha256:}")"
    docker tag "signal-lab-$name:latest" "signal-lab-$name:$tag"
    minikube --profile "$profile" image load "signal-lab-$name:$tag"
    set -- "$@" --set-string "image.$name=signal-lab-$name:$tag"
  done
  helm upgrade --install signal-lab deploy/helm/signal-lab \
    --kube-context "$profile" --namespace "$namespace" --create-namespace \
    --wait --timeout 5m \
    --set-file databaseInit=docker/database/init.sh \
    --set-string "credentials.postgresDb=$POSTGRES_DB" \
    --set-string "credentials.postgresUser=$POSTGRES_USER" \
    --set-string "credentials.postgresPassword=$POSTGRES_PASSWORD" \
    --set-string "credentials.recordingsDbPassword=$RECORDINGS_DB_PASSWORD" \
    --set-string "credentials.analysisDbPassword=$ANALYSIS_DB_PASSWORD" \
    --set-string "credentials.rabbitmqUser=$RABBITMQ_USER" \
    --set-string "credentials.rabbitmqPassword=$RABBITMQ_PASSWORD" \
    "$@"
  ;;
e2e)
  kubectl --context "$profile" --namespace "$namespace" port-forward service/web "$port:8080" --address 127.0.0.1 &
  forward=$!
  status=0
  curl --silent --show-error --fail --retry 10 --retry-connrefused --retry-delay 1 \
    "http://127.0.0.1:$port/" >/dev/null &&
    WEB_BASE_URL="http://127.0.0.1:$port" npm run test:flow --workspace @signal-lab/e2e ||
    status=$?
  kill "$forward"
  wait "$forward" || true
  exit "$status"
  ;;
stop)
  minikube stop --profile "$profile"
  ;;
down)
  helm uninstall signal-lab --kube-context "$profile" --namespace "$namespace"
  minikube delete --profile "$profile"
  ;;
*)
  echo "usage: minikube.sh up|e2e|stop|down" >&2
  exit 64
  ;;
esac
