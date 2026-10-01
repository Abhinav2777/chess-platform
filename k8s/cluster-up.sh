#!/usr/bin/env bash
# Creates the kind cluster and installs the add-ons (ingress-nginx, metrics-server). Idempotent.
# Needs: docker, kind, kubectl. See SETUP.md "Kubernetes (kind)".
set -euo pipefail
cd "$(dirname "$0")"
CLUSTER=chess

# Add-on images pinned BY DIGEST: pulled on the host (Docker verifies the digest), tagged, and
# loaded — kind nodes cannot pull on this machine, and `kind load` does not carry digests.
# Keep in step with addons/kustomization.yaml.
ADDON_IMAGES=(
  "registry.k8s.io/ingress-nginx/controller:v1.15.1@sha256:594ceea76b01c592858f803f9ff4d2cb40542cae2060410b2c95f75907d659e1"
  "registry.k8s.io/ingress-nginx/kube-webhook-certgen:v1.6.9@sha256:01038e7de14b78d702d2849c3aad72fd25903c4765af63cf16aa3398f5d5f2dd"
  "registry.k8s.io/metrics-server/metrics-server:v0.9.0"
)
DEPENDENCY_IMAGES=(postgres:16-alpine valkey/valkey:8-alpine softwaremill/elasticmq-native:1.7.1)

if ! kind get clusters | grep -qx "$CLUSTER"; then
  kind create cluster --config kind-cluster.yaml
fi

for ref in "${ADDON_IMAGES[@]}"; do
  tagged=${ref%@*}
  docker image inspect "$tagged" >/dev/null 2>&1 || { docker pull "$ref" && docker tag "$ref" "$tagged"; }
  kind load docker-image --name "$CLUSTER" "$tagged"
done
for image in "${DEPENDENCY_IMAGES[@]}"; do
  docker image inspect "$image" >/dev/null 2>&1 || docker pull "$image"
  kind load docker-image --name "$CLUSTER" "$image"
done

kubectl apply -k addons
kubectl -n ingress-nginx wait --for=condition=available deployment/ingress-nginx-controller --timeout=180s
kubectl -n kube-system wait --for=condition=available deployment/metrics-server --timeout=180s
echo "cluster ready: http://localhost (after deploy.sh)"
