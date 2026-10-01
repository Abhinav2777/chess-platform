#!/usr/bin/env bash
# Deploys image chess-platform:<tag> to the kind cluster, in the order ADR-021 requires:
#   1. everything except the api/worker Deployments (namespace, config, secrets, dependencies,
#      Services, Ingress, PDB, HPA, and a fresh migrate Job)
#   2. wait for the migrate Job: complete -> continue; failed -> stop, nothing rolled
#   3. the api/worker Deployments (label chess.dev/gated=true) -> rolling update -> wait
# Usage: k8s/deploy.sh <tag>        e.g. k8s/deploy.sh "$(git rev-parse --short HEAD)"
set -euo pipefail
cd "$(dirname "$0")"
TAG=${1:?usage: deploy.sh <image tag>}
CLUSTER=chess
NS=chess
IMAGE="chess-platform:$TAG"

docker image inspect "$IMAGE" >/dev/null || { echo "no local image $IMAGE — build it first (SETUP.md)" >&2; exit 1; }
kind load docker-image --name "$CLUSTER" "$IMAGE"

rendered=$(mktemp)
trap 'rm -f "$rendered"' EXIT
kubectl kustomize overlays/kind | sed "s|image: chess-platform:local|image: $IMAGE|" > "$rendered"

# A Job's pod template is immutable: replace it, don't patch it.
kubectl -n "$NS" delete job migrate --ignore-not-found --wait=true

kubectl apply -f "$rendered" -l 'chess.dev/gated!=true'

echo "waiting for the migrate Job…"
for _ in $(seq 1 120); do
  if [[ "$(kubectl -n "$NS" get job migrate -o jsonpath='{.status.conditions[?(@.type=="Complete")].status}')" == "True" ]]; then
    echo "migrate: complete"; break
  fi
  if [[ "$(kubectl -n "$NS" get job migrate -o jsonpath='{.status.conditions[?(@.type=="Failed")].status}')" == "True" ]]; then
    echo "migrate: FAILED — nothing rolled out. kubectl -n $NS logs job/migrate" >&2; exit 1
  fi
  sleep 5
done
[[ "$(kubectl -n "$NS" get job migrate -o jsonpath='{.status.conditions[?(@.type=="Complete")].status}')" == "True" ]] \
  || { echo "migrate: timed out" >&2; exit 1; }

kubectl apply -f "$rendered" -l 'chess.dev/gated=true'
kubectl -n "$NS" rollout status deployment/api --timeout=300s
kubectl -n "$NS" rollout status deployment/worker --timeout=300s
echo "deployed $IMAGE — http://localhost"
