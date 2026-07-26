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
  'application_manifests_changed:'
  'needs\.ci-gate\.result == '\''success'\'''
  'push-image:[[:space:]]+true'
  'publish-latest:[[:space:]]+false'
  'packages:[[:space:]]+write'
  'pattern:[[:space:]]+image-metadata-\*'
  'release_manifest:'
  'release-manifest:[[:space:]]+\$\{\{ needs\.collect-release-manifest\.outputs\.release_manifest \}\}'
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
  '^[[:space:]]+application-manifests-changed:$'
  'Validate immutable release manifest'
  'immutableRef'
  '\^sha256:\[0-9a-f\]\{64\}\$'
  'current_or_base_image\(\)'
  '\.\[\$service\]\.immutableRef'
  'digest:[[:space:]]+\$value'
  'snapshot_manifest_deployments'
  'track_manifest_deployment_changes'
  'rollback_deployments'
  'kubectl rollout undo deployment/'
  'ensure_settlement_cronjob'
  'snapshot_settlement_cronjob'
  'rollback_settlement_cronjob'
  'kubectl set image cronjob/'
  'kubectl delete deployment/settlement-service'
  'kubectl delete service/settlement-service'
  'kubectl apply --dry-run=server -k "\$runtime_overlay"'
  'kubectl apply -k "\$runtime_overlay"'
  '^[[:space:]]+ai-secret$'
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

array_values() {
  local file="$1"
  local array_name="$2"

  sed -n "/^[[:space:]]*${array_name}=(/,/^[[:space:]]*)/p" "$file" |
    sed '1d;$d;s/^[[:space:]]*//;s/[[:space:]]*$//'
}

expected_release_order=$'config\ndiscovery\nuser-service\nproduct-service\norder-service\npayment-service\nsettlement-service\nadmin-service\nai-service\napigateway'
expected_deployment_order=$'config\ndiscovery\nuser-service\nproduct-service\norder-service\npayment-service\nadmin-service\nai-service\napigateway'
expected_config_consumers=$'user-service\nproduct-service\norder-service\npayment-service\nadmin-service\nai-service\napigateway'

[ "$(array_values "$APPLICATION_WORKFLOW" release_order)" = "$expected_release_order" ] ||
  fail "release_order changed"
[ "$(array_values "$APPLICATION_WORKFLOW" deployment_order)" = "$expected_deployment_order" ] ||
  fail "deployment_order changed"
[ "$(array_values "$APPLICATION_WORKFLOW" config_consumers)" = "$expected_config_consumers" ] ||
  fail "config_consumers changed"

# The old Kubernetes workflow is now manual infrastructure and Ingress only.
manual_patterns=(
  '^name:[[:space:]]+CD - Self-hosted Kubernetes$'
  '^[[:space:]]+workflow_dispatch:$'
  '^[[:space:]]+deploy-infrastructure:$'
  '^[[:space:]]+deploy-ingress:$'
  'kubectl apply -k k8s/base/storage'
  'kubectl apply -k k8s/base/infrastructure'
  'kubectl apply -k k8s/addons/nginx-ingress'
  'kubectl apply -f k8s/overlays/ec2-kubeadm/gateway-ingress.yaml'
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
