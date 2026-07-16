#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORKFLOW="${ROOT_DIR}/.github/workflows/cd-selfhosted-kubernetes.yml"
COMPOSE_WORKFLOW="${ROOT_DIR}/.github/workflows/cd-selfhosted-compose.yml"

fail() {
  echo "Kubernetes CD workflow validation failed: $1" >&2
  exit 1
}

[[ -f "${WORKFLOW}" ]] || fail "missing ${WORKFLOW#"${ROOT_DIR}/"}"

required_patterns=(
  '^name:[[:space:]]+CD - Self-hosted Kubernetes$'
  '^[[:space:]]+push:$'
  '^[[:space:]]+workflow_dispatch:$'
  "if: github.event_name == 'push'"
  'application_manifests_changed:'
  'steps\.changed\.outputs\.application_manifests_any_changed'
  '^[[:space:]]+- k8s/overlays/ec2-kubeadm/applications/\*\*$'
  '^[[:space:]]+- infrastructure$'
  '^[[:space:]]+- ingress$'
  '^[[:space:]]+- k8s/base/platform/\*\*$'
  '^[[:space:]]+- k8s/base/services/\*\*$'
  '^[[:space:]]+- k8s/base/gateway/\*\*$'
  '^[[:space:]]+- \.github/workflows/cd-selfhosted-kubernetes\.yml$'
  'kubectl apply -k k8s/base/storage'
  'kubectl apply -k k8s/base/infrastructure'
  'kubectl apply -k k8s/addons/nginx-ingress'
  'kubectl apply -f k8s/overlays/ec2-kubeadm/gateway-ingress.yaml'
  'kubectl apply --server-side --dry-run=server -k "\$runtime_overlay"'
  'kubectl apply -k "\$runtime_overlay"'
  'snapshot_manifest_deployments'
  'track_manifest_deployment_changes'
  'kubectl set image deployment/'
  'kubectl rollout status deployment/'
  'kubectl rollout undo deployment/'
  'kubectl delete deployment/'
)

for pattern in "${required_patterns[@]}"; do
  if ! grep -Eq -- "${pattern}" "${WORKFLOW}"; then
    fail "missing contract: ${pattern}"
  fi
done

forbidden_patterns=(
  'docker compose'
  'docker stop'
  'docker rm'
  'K8S_AUTO_DEPLOY_ENABLED'
  'kubectl apply -k k8s/overlays/ec2-kubeadm'
  '- k8s/base/infrastructure/**'
  '- k8s/base/storage/**'
  '- k8s/addons/nginx-ingress/**'
)

for pattern in "${forbidden_patterns[@]}"; do
  if grep -Fq -- "${pattern}" "${WORKFLOW}"; then
    fail "forbidden command: ${pattern}"
  fi
done

if grep -Eq '^  push:$' "${COMPOSE_WORKFLOW}"; then
  fail "Compose CD develop push trigger must stay disabled"
fi

if ! grep -Eq '^  # push:$' "${COMPOSE_WORKFLOW}"; then
  fail "Compose CD must preserve the disabled push trigger as a comment"
fi

echo "Kubernetes CD workflow validation passed."
