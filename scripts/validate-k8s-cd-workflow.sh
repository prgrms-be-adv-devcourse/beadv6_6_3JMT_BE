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
  'kubectl apply --dry-run=server -k "\$runtime_overlay"'
  'kubectl apply -k "\$runtime_overlay"'
  'snapshot_manifest_deployments'
  'track_manifest_deployment_changes'
  'ensure_settlement_cronjob'
  'settlement_cronjob="settlement-weekly"'
  'release_order=\('
  'snapshot_settlement_cronjob'
  'rollback_settlement_cronjob'
  'kubectl set image cronjob/'
  'kubectl get cronjob "\$settlement_cronjob"'
  'kubectl delete deployment/settlement-service'
  'kubectl delete service/settlement-service'
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
  'kubectl create job'
  '--from=cronjob/settlement-weekly'
  'ensure_package settlement-service'
  'kubectl apply --server-side --dry-run=server -k "$runtime_overlay"'
)

for pattern in "${forbidden_patterns[@]}"; do
  if grep -Fq -- "${pattern}" "${WORKFLOW}"; then
    fail "forbidden command: ${pattern}"
  fi
done

deployment_order_block="$(sed -n '/deployment_order=(/,/)/p' "${WORKFLOW}")"
config_consumers_block="$(sed -n '/config_consumers=(/,/)/p' "${WORKFLOW}")"
release_order_block="$(sed -n '/release_order=(/,/)/p' "${WORKFLOW}")"
application_manifests_block="$(sed -n '/^[[:space:]]*application_manifests:/,/^[[:space:]]*all_applications:/p' "${WORKFLOW}")"
initial_application_prepare_block="$(sed -n '/- name: 최초 애플리케이션 리소스 준비/,/- name: 애플리케이션 리소스와 이미지 배포/p' "${WORKFLOW}")"

if ! grep -Eq '^[[:space:]]+- \.github/workflows/cd-selfhosted-kubernetes\.yml$' <<< "${application_manifests_block}"; then
  fail "Kubernetes CD workflow changes must reconcile application manifests"
fi

if grep -Fq 'kubectl rollout status deployment/' <<< "${initial_application_prepare_block}"; then
  fail "initial application preparation must not block manifest recovery on an existing failed rollout"
fi

if grep -Eq '^[[:space:]]+settlement-service$' <<< "${deployment_order_block}"; then
  fail "settlement-service must not be managed as a Deployment"
fi

if grep -Eq '^[[:space:]]+settlement-service$' <<< "${config_consumers_block}"; then
  fail "settlement-service must not be restarted as a config consumer Deployment"
fi

if ! grep -Eq '^[[:space:]]+settlement-service$' <<< "${release_order_block}"; then
  fail "release_order must retain settlement-service image delivery"
fi

if grep -Eq '^  push:$' "${COMPOSE_WORKFLOW}"; then
  fail "Compose CD develop push trigger must stay disabled"
fi

if ! grep -Eq '^  # push:$' "${COMPOSE_WORKFLOW}"; then
  fail "Compose CD must preserve the disabled push trigger as a comment"
fi

echo "Kubernetes CD workflow validation passed."
