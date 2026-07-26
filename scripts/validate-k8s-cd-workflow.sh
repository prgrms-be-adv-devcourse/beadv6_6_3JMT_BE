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
  '^[[:space:]]+ai_service:$'
  'services_json=.*"ai-service"'
  'services\+=\("ai-service"\)'
  'ensure_package ai-service ai-service k8s/base/services/ai'
  '^[[:space:]]+ai-secret$'
  '^[[:space:]]+- name: ghcr\.io/prgrms-be-adv-devcourse/prompthub-ai-service$'
  '^[[:space:]]+newName: ghcr\.io/\$owner_lc/prompthub-ai-service$'
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
user_service_filter_block="$(sed -n '/^[[:space:]]*user_service:/,/^[[:space:]]*ai_service:/p' "${WORKFLOW}")"
ai_service_filter_block="$(sed -n '/^[[:space:]]*ai_service:/,/^[[:space:]]*product_service:/p' "${WORKFLOW}")"
application_manifests_block="$(sed -n '/^[[:space:]]*application_manifests:/,/^[[:space:]]*all_applications:/p' "${WORKFLOW}")"
initial_application_prepare_block="$(sed -n '/- name: 최초 애플리케이션 리소스 준비/,/- name: 애플리케이션 리소스와 이미지 배포/p' "${WORKFLOW}")"

array_values() {
  local array_name="$1"

  sed -n "/^[[:space:]]*${array_name}=(/,/^[[:space:]]*)/p" "${WORKFLOW}" |
    sed '1d;$d;s/^[[:space:]]*//;s/[[:space:]]*$//'
}

expected_release_order=$'config\ndiscovery\nuser-service\nproduct-service\norder-service\npayment-service\nsettlement-service\nadmin-service\nai-service\napigateway'
expected_deployment_order=$'config\ndiscovery\nuser-service\nproduct-service\norder-service\npayment-service\nadmin-service\nai-service\napigateway'
expected_config_consumers=$'user-service\nproduct-service\norder-service\npayment-service\nadmin-service\nai-service\napigateway'

if ! grep -Eq '^[[:space:]]+- grpc/user/\*\*$' <<< "${user_service_filter_block}"; then
  fail "user_service changes must include the shared User gRPC contract"
fi

if ! grep -Eq '^[[:space:]]+- ai-service/\*\*$' <<< "${ai_service_filter_block}" ||
  ! grep -Eq '^[[:space:]]+- grpc/user/\*\*$' <<< "${ai_service_filter_block}"; then
  fail "ai_service changes must include its module and the shared User gRPC contract"
fi

if [ "$(array_values release_order)" != "${expected_release_order}" ]; then
  fail "release_order must place ai-service after admin-service and before apigateway"
fi

if [ "$(array_values deployment_order)" != "${expected_deployment_order}" ]; then
  fail "deployment_order must place ai-service after admin-service and before apigateway"
fi

if [ "$(array_values config_consumers)" != "${expected_config_consumers}" ]; then
  fail "config_consumers must place ai-service after admin-service and before apigateway"
fi

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
