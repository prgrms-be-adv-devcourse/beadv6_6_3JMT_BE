#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RELEASE_WORKFLOW="${ROOT_DIR}/.github/workflows/release-develop.yml"
APPLICATION_WORKFLOW="${ROOT_DIR}/.github/workflows/reusable-kubernetes-deploy.yml"
MANUAL_WORKFLOW="${ROOT_DIR}/.github/workflows/cd-selfhosted-kubernetes.yml"
DOCKER_WORKFLOW="${ROOT_DIR}/.github/workflows/reusable-docker-build.yml"
PR_WORKFLOW="${ROOT_DIR}/.github/workflows/ci.yml"
COMPOSE_WORKFLOW="${ROOT_DIR}/.github/workflows/cd-selfhosted-compose.yml"

fail() {
  echo "CI/CD workflow validation failed: $1" >&2
  exit 1
}

require_file() {
  [ -f "$1" ] || fail "missing ${1#"${ROOT_DIR}/"}"
}

require_pattern() {
  local file="$1"
  local pattern="$2"
  local contract="$3"

  grep -Eq -- "$pattern" "$file" || fail "$contract"
}

forbid_pattern() {
  local file="$1"
  local pattern="$2"
  local contract="$3"

  if grep -Eq -- "$pattern" "$file"; then
    fail "$contract"
  fi
}

for workflow in \
  "$RELEASE_WORKFLOW" \
  "$APPLICATION_WORKFLOW" \
  "$MANUAL_WORKFLOW" \
  "$DOCKER_WORKFLOW" \
  "$PR_WORKFLOW" \
  "$COMPOSE_WORKFLOW"; do
  require_file "$workflow"
done

# develop Release CI: affected tests -> aggregate Gate -> image publish -> digest manifest -> CD.
release_patterns=(
  '^name:[[:space:]]+Release - Develop$'
  '^[[:space:]]+push:$'
  'branches:[[:space:]]+\["develop"\]'
  '^[[:space:]]+planning:$'
  '^[[:space:]]+workflow_dispatch:$'
  'release-services:'
  'manifest-services:'
  '^[[:space:]]+build-and-test:$'
  '^[[:space:]]+ci-gate:$'
  '^    name:[[:space:]]+Release CI Gate$'
  '^[[:space:]]+publish-images:$'
  '^[[:space:]]+collect-release-manifest:$'
  '^[[:space:]]+deploy:$'
  'uses:[[:space:]]+\./\.github/workflows/reusable-build\.yml'
  'uses:[[:space:]]+\./\.github/workflows/reusable-docker-build\.yml'
  'uses:[[:space:]]+\./\.github/workflows/reusable-kubernetes-deploy\.yml'
  'run:[[:space:]]+bash scripts/plan-release-targets\.sh'
  '^[[:space:]]+- grpc/user/\*\*$'
  'test_matrix:'
  'image_matrix:'
  'manifest_matrix:'
  'deploy_matrix:'
  'config_consumer_matrix:'
  'apply_all_manifests:'
  'elk_changed:'
  'needs\.ci-gate\.result == '\''success'\'''
  'push-image:[[:space:]]+true'
  'publish-latest:[[:space:]]+false'
  'packages:[[:space:]]+write'
  'pattern:[[:space:]]+image-metadata-\*'
  'release_manifest:'
  'release-manifest:[[:space:]]+\$\{\{ needs\.collect-release-manifest\.outputs\.release_manifest \}\}'
  'manifest-matrix:[[:space:]]+\$\{\{ needs\.planning\.outputs\.manifest_matrix \}\}'
  'deploy-matrix:[[:space:]]+\$\{\{ needs\.planning\.outputs\.deploy_matrix \}\}'
  'config-consumer-matrix:[[:space:]]+\$\{\{ needs\.planning\.outputs\.config_consumer_matrix \}\}'
  'apply-all-manifests:'
  'manifest_ai:'
  'k8s/base/services/ai/\*\*'
  '^[[:space:]]+elk:$'
  'k8s/addons/elk/\*\*'
  'pipeline:'
  '^[[:space:]]+deploy-elk:$'
  'uses:[[:space:]]+\./\.github/workflows/cd-selfhosted-kubernetes\.yml'
)

for pattern in "${release_patterns[@]}"; do
  require_pattern "$RELEASE_WORKFLOW" "$pattern" "release workflow missing contract: $pattern"
done

forbid_pattern "$RELEASE_WORKFLOW" '^[[:space:]]+pull_request:' \
  "Release workflow must not run for pull requests"
forbid_pattern "$RELEASE_WORKFLOW" 'workflow_run:' \
  "Release and CD must remain in one needs DAG"
forbid_pattern "$RELEASE_WORKFLOW" ':latest' \
  "Kubernetes Release CI must not publish or deploy latest"

publish_block="$(sed -n '/^  publish-images:/,/^  collect-release-manifest:/p' "$RELEASE_WORKFLOW")"
grep -Eq '^[[:space:]]+- ci-gate$' <<< "$publish_block" ||
  fail "image publishing must depend on the aggregate CI Gate"

elk_deploy_block="$(sed -n '/^  deploy-elk:/,$p' "$RELEASE_WORKFLOW")"
elk_deploy_patterns=(
  'github\.event_name == '\''push'\'''
  'needs\.planning\.result == '\''success'\'''
  'needs\.planning\.outputs\.elk_changed == '\''true'\'''
  'needs\.ci-gate\.result == '\''success'\'''
  'needs\.collect-release-manifest\.result == '\''success'\'''
  'needs\.deploy\.result == '\''success'\'''
  'needs\.deploy\.result == '\''skipped'\'''
  'target:[[:space:]]+elk'
  'confirmation:[[:space:]]+DEPLOY'
)

for pattern in "${elk_deploy_patterns[@]}"; do
  grep -Eq -- "$pattern" <<< "$elk_deploy_block" ||
    fail "automatic ELK deploy missing release gate: $pattern"
done

# Reusable Docker publisher: full SHA trace tag plus immutable digest artifact.
docker_patterns=(
  '^[[:space:]]+ghcr-repository:$'
  '^[[:space:]]+image-tag:$'
  '^[[:space:]]+publish-latest:$'
  'image_tag="\$\{IMAGE_TAG_INPUT:-\$\{GITHUB_SHA\}\}"'
  '^[[:space:]]+id:[[:space:]]+build$'
  'IMAGE_DIGEST:[[:space:]]+\$\{\{ steps\.build\.outputs\.digest \}\}'
  '\^sha256:\[0-9a-f\]\{64\}\$'
  'immutable_ref="\$\{repository\}@\$\{IMAGE_DIGEST\}"'
  'name:[[:space:]]+image-metadata-\$\{\{ inputs\.module-name \}\}'
  'retention-days:[[:space:]]+1'
  'image_digest:'
  'immutable_ref:'
)

for pattern in "${docker_patterns[@]}"; do
  require_pattern "$DOCKER_WORKFLOW" "$pattern" "Docker workflow missing contract: $pattern"
done

forbid_pattern "$DOCKER_WORKFLOW" 'ecr-repository|aws-region|SHORT_SHA' \
  "Docker workflow must use GHCR naming and full SHA tags"

grep -Fq '[ "$PUSH_IMAGE" = "true" ] && [ "$PUBLISH_LATEST" = "true" ]' "$DOCKER_WORKFLOW" ||
  fail "latest must be opt-in and limited to pushed images"

# Reusable application CD: only immutable release inputs may introduce new images.
application_patterns=(
  '^name:[[:space:]]+Reusable Kubernetes Application Deploy$'
  '^[[:space:]]+workflow_call:$'
  '^[[:space:]]+release-manifest:$'
  '^[[:space:]]+manifest-matrix:$'
  '^[[:space:]]+deploy-matrix:$'
  '^[[:space:]]+config-consumer-matrix:$'
  '^[[:space:]]+apply-all-manifests:$'
  'Validate release inputs'
  'immutableRef'
  '\^sha256:\[0-9a-f\]\{64\}\$'
  'current_or_base_image\(\)'
  '\.\[\$service\]\.immutableRef'
  'digest:[[:space:]]+\$value'
  'render_application_overlay'
  'snapshot_workloads'
  'track_deployment_change'
  'apply_service_manifest'
  'app\.kubernetes\.io/name=\$service'
  'kubectl apply --dry-run=server'
  'kubectl apply -n "\$NAMESPACE" -l "\$selector" -f "\$rendered_manifest"'
  'dump_failure_diagnostics'
  'trap rollback ERR'
  'kubectl rollout undo deployment/'
  'kubectl set image cronjob/'
  'kubectl delete deployment/settlement-service'
  'kubectl delete service/settlement-service'
  '^[[:space:]]+ai-secret$'
  'NAMESPACE="\$NAMESPACE" bash scripts/validate-live-k8s-secret-keys\.sh k8s/base'
)

for pattern in "${application_patterns[@]}"; do
  require_pattern "$APPLICATION_WORKFLOW" "$pattern" "application CD missing contract: $pattern"
done

forbid_pattern "$APPLICATION_WORKFLOW" 'docker/build|push-image|packages:[[:space:]]+write|SHORT_SHA|short_sha|:latest' \
  "application CD must not build, push, or use mutable image references"
forbid_pattern "$APPLICATION_WORKFLOW" 'kubectl apply -k k8s/base/(storage|infrastructure)' \
  "application CD must not reconcile stateful infrastructure"
forbid_pattern "$APPLICATION_WORKFLOW" 'kubectl apply -k k8s/addons/nginx-ingress' \
  "application CD must not reconcile Ingress"
forbid_pattern "$APPLICATION_WORKFLOW" 'kubectl create job|--from=cronjob/settlement-weekly' \
  "application CD must not start settlement jobs"
forbid_pattern "$APPLICATION_WORKFLOW" 'application-manifests-changed|APPLICATION_MANIFESTS_CHANGED' \
  "application CD must use service-scoped manifest matrices"
forbid_pattern "$APPLICATION_WORKFLOW" 'kubectl apply -k "\$runtime_overlay"' \
  "application CD must not apply the full application overlay at once"

array_values() {
  local file="$1"
  local array_name="$2"

  sed -n "/^[[:space:]]*${array_name}=(/,/^[[:space:]]*)/p" "$file" |
    sed '1d;$d;s/^[[:space:]]*//;s/[[:space:]]*$//'
}

expected_release_order=$'config\ndiscovery\nuser-service\nproduct-service\norder-service\npayment-service\nsettlement-service\nadmin-service\nai-service\nnotification-service\napigateway'
expected_deployment_order=$'config\ndiscovery\nuser-service\nproduct-service\norder-service\npayment-service\nadmin-service\nai-service\nnotification-service\napigateway'
[ "$(array_values "$APPLICATION_WORKFLOW" release_order)" = "$expected_release_order" ] ||
  fail "release_order changed"
[ "$(array_values "$APPLICATION_WORKFLOW" deployment_order)" = "$expected_deployment_order" ] ||
  fail "deployment_order changed"

# The manual Kubernetes workflow supports infrastructure, Ingress, and ELK.
manual_patterns=(
  '^name:[[:space:]]+CD - Self-hosted Kubernetes$'
  '^[[:space:]]+workflow_call:$'
  '^[[:space:]]+workflow_dispatch:$'
  '^[[:space:]]+deploy-infrastructure:$'
  '^[[:space:]]+deploy-ingress:$'
  '^[[:space:]]+deploy-elk:$'
  '^[[:space:]]+- elk$'
  'inputs\.target == '\''elk'\'''
  'kubectl apply -k k8s/base/storage'
  'kubectl apply -k k8s/base/infrastructure'
  'kubectl apply -k k8s/addons/nginx-ingress'
  'kubectl apply -f k8s/overlays/ec2-kubeadm/gateway-ingress.yaml'
  'kubectl get namespace elk'
  'kubectl get secret logstash-http-credentials -n elk'
  'kubectl get secret kibana-encryption -n elk'
  'kubectl apply --server-side --dry-run=server -k k8s/addons/elk'
  'kubectl delete job application-logs-ilm-bootstrap application-logs-kibana-bootstrap -n elk --ignore-not-found'
  'kubectl delete job kibana-operations-dashboards-bootstrap -n elk --ignore-not-found'
  'kubectl apply --server-side -k k8s/addons/elk'
  'kubectl rollout restart deployment/logstash -n elk'
  'kubectl rollout restart daemonset/fluent-bit -n elk'
  'kubectl wait --for=condition=complete job/application-logs-ilm-bootstrap -n elk --timeout=10m'
  'kubectl wait --for=condition=complete job/application-logs-kibana-bootstrap -n elk --timeout=10m'
  'kubectl wait --for=condition=complete job/kibana-operations-dashboards-bootstrap -n elk --timeout=10m'
)

for pattern in "${manual_patterns[@]}"; do
  require_pattern "$MANUAL_WORKFLOW" "$pattern" "manual workflow missing contract: $pattern"
done

forbid_pattern "$MANUAL_WORKFLOW" '^[[:space:]]+push:' \
  "manual Kubernetes workflow must not run on develop push"
forbid_pattern "$MANUAL_WORKFLOW" 'packages:[[:space:]]+write|planning:|publish|build-and-push|deploy-applications|reusable-docker-build' \
  "manual Kubernetes workflow contains an automatic application responsibility"

# PR CI stays an independent, read-only build/test gate.
require_pattern "$PR_WORKFLOW" '^name:[[:space:]]+CI$' "PR CI name changed"
require_pattern "$PR_WORKFLOW" '^[[:space:]]+pull_request:$' "PR CI trigger missing"
require_pattern "$PR_WORKFLOW" '^[[:space:]]+- develop$' "PR CI develop target missing"
require_pattern "$PR_WORKFLOW" '^[[:space:]]+ci-gate:$' "PR CI Gate missing"
forbid_pattern "$PR_WORKFLOW" 'push-image:[[:space:]]+true|packages:[[:space:]]+write|reusable-kubernetes-deploy' \
  "PR CI must remain build/test only"

# Compose remains manual and is the only compatibility path that publishes latest.
forbid_pattern "$COMPOSE_WORKFLOW" '^  push:$' \
  "Compose develop push trigger must stay disabled"
require_pattern "$COMPOSE_WORKFLOW" '^  # push:$' \
  "Compose workflow must preserve the disabled push trigger comment"
require_pattern "$COMPOSE_WORKFLOW" 'publish-latest:[[:space:]]+true' \
  "manual Compose compatibility requires latest"

echo "CI/CD workflow validation passed."
