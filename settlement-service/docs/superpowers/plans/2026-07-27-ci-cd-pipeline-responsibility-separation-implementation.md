# CI/CD Pipeline Responsibility Separation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `develop` 머지 커밋의 영향 모듈만 검증·발행하고, 변경된 서비스의 이미지와 Kubernetes 매니페스트만 순차 배포하며 최신 `develop` 선택 서비스 재배포를 지원한다.

**Architecture:** 기존 PR `ci.yml`은 그대로 둔다. `release-develop.yml`이 코드·Config·서비스별 매니페스트 변경을 서로 다른 matrix로 계산하고, 검증된 immutable image와 대상 matrix를 `reusable-kubernetes-deploy.yml`에 전달한다. reusable CD는 전체 applications overlay를 한 번에 적용하지 않고 서비스별 runtime package를 release order로 적용·확인한다.

**Tech Stack:** GitHub Actions reusable workflows, Bash 3.2+, jq, Docker Buildx, GHCR, Kubernetes, Kustomize

## Global Constraints

- 연결 이슈는 `#584 (이슈)`이고 작업 브랜치는 `feat/#584-ci-cd-pipeline-separation`이다.
- 기존 `.github/workflows/ci.yml`의 PR trigger, 권한, 변경 감지와 CI Gate 동작을 수정하지 않는다.
- `.github/workflows/ci-main.yml`은 GHCR 입력명만 바꾸고 검증 정책은 유지한다.
- Release CI는 영향 모듈 전체 테스트가 성공한 뒤에만 이미지를 발행한다.
- Kubernetes 배포 입력은 tag를 다시 조합하지 않고 Docker build 결과의 `immutableRef`를 사용한다.
- Release CI는 `latest`를 발행하지 않는다. 수동 Compose rollback 호출만 `publish-latest: true`를 사용한다.
- `grpc/user/**` 변경은 `user-service`와 `ai-service`를 함께 처리한다.
- 애플리케이션 매니페스트만 바뀌면 새 이미지를 만들지 않고 현재 workload 이미지를 보존한다.
- 서비스별 매니페스트 변경은 해당 서비스만 적용한다. 공통 overlay 변경도 workload를 하나씩 순차 적용한다.
- pipeline 파일만 바뀌면 애플리케이션 이미지를 발행하거나 Kubernetes workload를 변경하지 않는다.
- 수동 Release는 최신 `develop`만 사용하고 `RELEASE` 확인 문자열과 허용된 서비스 목록을 검증한다.
- `settlement-service`는 Deployment가 아니라 `CronJob/settlement-weekly` 이미지로 관리한다.
- 상태 저장 인프라와 Ingress는 `workflow_dispatch`와 확인 문자열 `DEPLOY`를 유지한다.
- 기존 미커밋 변경 4개는 각 task의 stage와 commit에서 제외한다.
- workflow와 validator는 저장소 루트의 담당 범위 밖 파일이다. 사용자가 `#584 (이슈)` 구현 범위로 변경을 승인했다.

---

## Current Implementation Baseline

Task 1~7은 `71d04557 (커밋)`의 부모까지 PR 588 브랜치에 구현되어 있다. 보완 실행은 Task 1~7을
반복하지 않고 Task 8부터 시작한다. 기존 미커밋 4개와 `71d04557 (커밋)`의 설계 문서는 구현
커밋에 섞지 않는다.

## File Structure

### Create

- `scripts/plan-release-targets.sh`: changed-files 결과를 test/image matrix와 deploy 여부로 변환
- `scripts/test-plan-release-targets.sh`: 단일 모듈, 공통 변경, gRPC 공유 계약, 매니페스트, 문서 변경 회귀 테스트
- `.github/workflows/release-develop.yml`: `develop` push의 Release CI와 CD orchestration
- `.github/workflows/reusable-kubernetes-deploy.yml`: 애플리케이션 배포, 이미지 보존과 rollback

### Modify

- `.github/workflows/reusable-docker-build.yml`: GHCR 명명, 전체 SHA tag, digest metadata artifact, Compose용 조건부 `latest`
- `.github/workflows/ci-main.yml`: `ecr-repository` 호출명을 `ghcr-repository`로 변경
- `.github/workflows/cd-selfhosted-compose.yml`: GHCR 입력명 변경과 `publish-latest: true`
- `.github/workflows/cd-selfhosted-kubernetes.yml`: 자동 애플리케이션 job 제거, 수동 인프라·Ingress만 유지
- `scripts/validate-k8s-cd-workflow.sh`: 분리된 Release CI, reusable CD, manual CD 계약 검증
- `k8s/README.md`: 새 자동 배포 흐름과 수동 경계
- `docs/architecture/kubernetes.md`: Release CI, release manifest와 digest 배포 계약
- `settlement-service/docs/architecture/deployment-ci-cd.md`: 현재 Kubernetes 기준 전체 CI/CD 설명

## Shared Interfaces

### `scripts/plan-release-targets.sh`

Consumes boolean environment variables ending in `_CHANGED` plus validated manual service inputs and writes
these compact JSON/string outputs to `$GITHUB_OUTPUT`:

```text
test_matrix=["service-a","service-b"]
image_matrix=["service-a","service-b"]
manifest_matrix=["service-a"]
deploy_matrix=["service-a","service-b"]
config_consumer_matrix=["service-a"]
apply_all_manifests=true|false
deploy=true|false
```

### Docker metadata artifact

Each pushed service uploads `image-metadata-payment-service`-style artifact names containing a
`payment-service.json`-style file:

```json
{
  "service": "payment-service",
  "imageUri": "ghcr.io/prgrms-be-adv-devcourse/prompthub-payment-service:0123456789abcdef0123456789abcdef01234567",
  "digest": "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
  "immutableRef": "ghcr.io/prgrms-be-adv-devcourse/prompthub-payment-service@sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
}
```

### `reusable-kubernetes-deploy.yml`

Consumes:

```yaml
release-manifest:
  type: string
manifest-matrix:
  type: string
deploy-matrix:
  type: string
config-consumer-matrix:
  type: string
apply-all-manifests:
  type: boolean
```

`release-manifest` is a compact object keyed by service:

```json
{
  "payment-service": {
    "service": "payment-service",
    "imageUri": "ghcr.io/prgrms-be-adv-devcourse/prompthub-payment-service:0123456789abcdef0123456789abcdef01234567",
    "digest": "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    "immutableRef": "ghcr.io/prgrms-be-adv-devcourse/prompthub-payment-service@sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
  }
}
```

---

### Task 1: Release 대상 계산을 테스트 가능한 스크립트로 분리

**Files:**
- Create: `scripts/test-plan-release-targets.sh`
- Create: `scripts/plan-release-targets.sh`

**Interfaces:**
- Consumes: `USER_SERVICE_CHANGED`, `AI_SERVICE_CHANGED`, `PRODUCT_SERVICE_CHANGED`, `PAYMENT_SERVICE_CHANGED`, `ORDER_SERVICE_CHANGED`, `SETTLEMENT_SERVICE_CHANGED`, `ADMIN_SERVICE_CHANGED`, `APIGATEWAY_CHANGED`, `CONFIG_CHANGED`, `DISCOVERY_CHANGED`, `SHARED_BUILD_CHANGED`, `APPLICATION_MANIFESTS_CHANGED`
- Produces: `test_matrix`, `image_matrix`, `deploy`, `application_manifests_changed` in `$GITHUB_OUTPUT`

- [ ] **Step 1: Write the failing shell contract test**

Create `scripts/test-plan-release-targets.sh` with executable mode and these cases:

```bash
#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PLANNER="${ROOT_DIR}/scripts/plan-release-targets.sh"

fail() {
  echo "Release target planner test failed: $1" >&2
  exit 1
}

read_output() {
  local file="$1"
  local key="$2"
  sed -n "s/^${key}=//p" "$file"
}

run_case() {
  local name="$1"
  local expected_test="$2"
  local expected_image="$3"
  local expected_deploy="$4"
  local expected_manifests="$5"
  shift 5

  local output
  output="$(mktemp)"
  trap 'rm -f "$output"' RETURN

  env GITHUB_OUTPUT="$output" "$@" bash "$PLANNER"

  [ "$(read_output "$output" test_matrix)" = "$expected_test" ] ||
    fail "$name test_matrix"
  [ "$(read_output "$output" image_matrix)" = "$expected_image" ] ||
    fail "$name image_matrix"
  [ "$(read_output "$output" deploy)" = "$expected_deploy" ] ||
    fail "$name deploy"
  [ "$(read_output "$output" application_manifests_changed)" = "$expected_manifests" ] ||
    fail "$name application_manifests_changed"

  rm -f "$output"
  trap - RETURN
}

run_case \
  "settlement only" \
  '["settlement-service"]' \
  '["settlement-service"]' \
  true false \
  SETTLEMENT_SERVICE_CHANGED=true

run_case \
  "shared user grpc" \
  '["user-service","ai-service"]' \
  '["user-service","ai-service"]' \
  true false \
  USER_SERVICE_CHANGED=true AI_SERVICE_CHANGED=true

all_services='["config","discovery","user-service","product-service","order-service","payment-service","settlement-service","admin-service","ai-service","apigateway"]'
run_case \
  "shared build" \
  "$all_services" "$all_services" true false \
  SHARED_BUILD_CHANGED=true

run_case \
  "manifests only" \
  '[]' '[]' true true \
  APPLICATION_MANIFESTS_CHANGED=true

run_case \
  "docs only" \
  '[]' '[]' false false

echo "Release target planner tests passed."
```

- [ ] **Step 2: Run the test and verify the missing planner failure**

Run:

```bash
bash scripts/test-plan-release-targets.sh
```

Expected: non-zero exit because `scripts/plan-release-targets.sh` does not exist.

- [ ] **Step 3: Implement the planner**

Create `scripts/plan-release-targets.sh` with executable mode:

```bash
#!/usr/bin/env bash

set -euo pipefail

: "${GITHUB_OUTPUT:?GITHUB_OUTPUT is required}"

is_true() {
  [ "${1:-false}" = "true" ]
}

services=()

add_service() {
  local candidate="$1"
  local service
  for service in "${services[@]:-}"; do
    [ "$service" = "$candidate" ] && return
  done
  services+=("$candidate")
}

if is_true "${SHARED_BUILD_CHANGED:-false}"; then
  services=(
    config
    discovery
    user-service
    product-service
    order-service
    payment-service
    settlement-service
    admin-service
    ai-service
    apigateway
  )
else
  is_true "${CONFIG_CHANGED:-false}" && add_service config
  is_true "${DISCOVERY_CHANGED:-false}" && add_service discovery
  is_true "${USER_SERVICE_CHANGED:-false}" && add_service user-service
  is_true "${PRODUCT_SERVICE_CHANGED:-false}" && add_service product-service
  is_true "${ORDER_SERVICE_CHANGED:-false}" && add_service order-service
  is_true "${PAYMENT_SERVICE_CHANGED:-false}" && add_service payment-service
  is_true "${SETTLEMENT_SERVICE_CHANGED:-false}" && add_service settlement-service
  is_true "${ADMIN_SERVICE_CHANGED:-false}" && add_service admin-service
  is_true "${AI_SERVICE_CHANGED:-false}" && add_service ai-service
  is_true "${APIGATEWAY_CHANGED:-false}" && add_service apigateway
fi

if [ ${#services[@]} -eq 0 ]; then
  matrix='[]'
else
  matrix="$(printf '%s\n' "${services[@]}" | jq -R . | jq -s -c .)"
fi

manifests_changed="${APPLICATION_MANIFESTS_CHANGED:-false}"
deploy=false
if [ "$matrix" != '[]' ] || is_true "$manifests_changed"; then
  deploy=true
fi

{
  echo "test_matrix=$matrix"
  echo "image_matrix=$matrix"
  echo "deploy=$deploy"
  echo "application_manifests_changed=$manifests_changed"
} >> "$GITHUB_OUTPUT"
```

- [ ] **Step 4: Run planner tests and shell syntax checks**

Run:

```bash
bash -n scripts/plan-release-targets.sh
bash -n scripts/test-plan-release-targets.sh
bash scripts/test-plan-release-targets.sh
```

Expected: all commands exit 0 and print `Release target planner tests passed.`

- [ ] **Step 5: Commit the planner contract**

```bash
git add scripts/plan-release-targets.sh scripts/test-plan-release-targets.sh
git commit -m "feat: Release 대상 계산 스크립트 추가"
```

---

### Task 2: Docker workflow를 GHCR artifact 발행기로 변경

**Files:**
- Modify: `.github/workflows/reusable-docker-build.yml:4-94`
- Modify: `.github/workflows/ci-main.yml:184-307`
- Modify: `.github/workflows/cd-selfhosted-compose.yml:132-145`
- Modify: `.github/workflows/cd-selfhosted-kubernetes.yml:137-151`
- Modify: `scripts/validate-k8s-cd-workflow.sh`

**Interfaces:**
- Consumes: `ghcr-repository`, `image-tag`, `push-image`, `publish-latest`
- Produces: workflow outputs `image_uri`, `image_digest`, `immutable_ref`; pushed builds also upload `image-metadata-${{ inputs.module-name }}`

- [ ] **Step 1: Add failing Docker workflow assertions**

Extend `scripts/validate-k8s-cd-workflow.sh` with:

```bash
DOCKER_WORKFLOW="${ROOT_DIR}/.github/workflows/reusable-docker-build.yml"

docker_required_patterns=(
  '^[[:space:]]+ghcr-repository:$'
  '^[[:space:]]+image-tag:$'
  '^[[:space:]]+publish-latest:$'
  'image_digest:'
  'immutable_ref:'
  'id: build'
  'steps\.build\.outputs\.digest'
  'actions/upload-artifact@v4'
  'image-metadata-\$\{\{ inputs\.module-name \}\}'
)

for pattern in "${docker_required_patterns[@]}"; do
  grep -Eq -- "$pattern" "$DOCKER_WORKFLOW" ||
    fail "missing Docker publish contract: $pattern"
done
```

Also add:

```bash
if grep -Fq 'ecr-repository' "$DOCKER_WORKFLOW"; then
  fail "Docker workflow must use ghcr-repository"
fi
```

- [ ] **Step 2: Run the validator and verify it fails**

Run:

```bash
bash scripts/validate-k8s-cd-workflow.sh
```

Expected: non-zero exit at the first missing `ghcr-repository` contract.

- [ ] **Step 3: Replace the Docker workflow interface**

In `.github/workflows/reusable-docker-build.yml`, remove `ecr-repository` and `aws-region`; add:

```yaml
      ghcr-repository:
        required: false
        type: string
        default: ''
      image-tag:
        required: false
        type: string
        default: ''
      push-image:
        required: false
        type: boolean
        default: false
      publish-latest:
        required: false
        type: boolean
        default: false
```

Expose the called-workflow outputs:

```yaml
    outputs:
      image_uri:
        description: Built Docker image URI or local image tag
        value: ${{ jobs.docker_build.outputs.image_uri }}
      image_digest:
        description: Pushed OCI image digest
        value: ${{ jobs.docker_build.outputs.image_digest }}
      immutable_ref:
        description: Pushed OCI image repository and digest
        value: ${{ jobs.docker_build.outputs.immutable_ref }}
```

Expose the job outputs:

```yaml
    outputs:
      image_uri: ${{ steps.image.outputs.image_uri }}
      image_digest: ${{ steps.build.outputs.digest }}
      immutable_ref: ${{ steps.metadata.outputs.immutable_ref }}
```

Set the image repository, full tag and optional Compose tag in one step:

```bash
tag="${{ inputs.image-tag }}"
tag="${tag:-$GITHUB_SHA}"

if [ "${{ inputs.push-image }}" = "true" ]; then
  repository="${{ inputs.ghcr-repository }}"
  [ -n "$repository" ] || {
    echo "ghcr-repository is required when push-image is true" >&2
    exit 1
  }

  owner_lc="$(printf '%s' "$GITHUB_REPOSITORY_OWNER" | tr '[:upper:]' '[:lower:]')"
  image_repository="ghcr.io/$owner_lc/$repository"
  image_uri="$image_repository:$tag"
else
  image_repository="${{ inputs.module-name }}"
  image_uri="$image_repository:$tag"
fi

{
  echo "image_repository=$image_repository"
  echo "image_uri=$image_uri"
  echo "tags<<EOF"
  echo "$image_uri"
  if [ "${{ inputs.push-image }}" = "true" ] &&
    [ "${{ inputs.publish-latest }}" = "true" ]; then
    echo "$image_repository:latest"
  fi
  echo "EOF"
} >> "$GITHUB_OUTPUT"
```

Give `docker/build-push-action@v5` the `id: build` and use:

```yaml
          push: ${{ inputs.push-image }}
          tags: ${{ steps.image.outputs.tags }}
```

After the build, create metadata only for pushed images:

```yaml
      - name: Create image metadata
        id: metadata
        if: inputs.push-image == true
        shell: bash
        run: |
```

Use this script as the `run` body:

```bash
digest="${{ steps.build.outputs.digest }}"
[[ "$digest" =~ ^sha256:[0-9a-f]{64}$ ]] || {
  echo "invalid image digest: $digest" >&2
  exit 1
}

immutable_ref="${{ steps.image.outputs.image_repository }}@$digest"
metadata_file="${RUNNER_TEMP}/${{ inputs.module-name }}.json"

jq -n -c \
  --arg service "${{ inputs.module-name }}" \
  --arg imageUri "${{ steps.image.outputs.image_uri }}" \
  --arg digest "$digest" \
  --arg immutableRef "$immutable_ref" \
  '{service:$service,imageUri:$imageUri,digest:$digest,immutableRef:$immutableRef}' \
  > "$metadata_file"

echo "immutable_ref=$immutable_ref" >> "$GITHUB_OUTPUT"
echo "metadata_file=$metadata_file" >> "$GITHUB_OUTPUT"
```

Upload it:

```yaml
      - name: Upload image metadata
        if: inputs.push-image == true
        uses: actions/upload-artifact@v4
        with:
          name: image-metadata-${{ inputs.module-name }}
          path: ${{ steps.metadata.outputs.metadata_file }}
          if-no-files-found: error
          retention-days: 1
```

- [ ] **Step 4: Update every caller without changing its policy**

In `.github/workflows/ci-main.yml`, replace all ten `ecr-repository` keys with `ghcr-repository` while
preserving these literal values:

```text
user_service_image_build       → prompthub-user-service
ai_service_image_build         → prompthub-ai-service
product_service_image_build    → prompthub-product-service
payment_service_image_build    → prompthub-payment-service
order_service_image_build      → prompthub-order-service
settlement_service_image_build → prompthub-settlement-service
admin_service_image_build      → prompthub-admin-service
apigateway_image_build         → prompthub-apigateway
config_image_build             → prompthub-config
discovery_image_build          → prompthub-discovery
```

Each job uses this shape and keeps its existing `push-image: false`:

```yaml
    with:
      module-name: user-service
      module-path: user-service
      dockerfile-path: Dockerfile
      ghcr-repository: prompthub-user-service
      push-image: false
```

In the current Kubernetes workflow, temporarily use:

```yaml
      ghcr-repository: prompthub-${{ matrix.service }}
      image-tag: ${{ github.sha }}
      push-image: true
```

This caller is removed in Task 5.

In Compose CD use:

```yaml
      ghcr-repository: prompthub-${{ matrix.service }}
      image-tag: ${{ github.sha }}
      push-image: true
      publish-latest: true
```

- [ ] **Step 5: Run Docker contract and existing Kubernetes validators**

Run:

```bash
bash scripts/validate-k8s-cd-workflow.sh
rg -n 'ecr-repository|aws-region' .github/workflows
```

Expected: validator exits 0; `rg` returns no matches. Confirm Compose is the only caller containing `publish-latest: true`.

- [ ] **Step 6: Commit the Docker publisher contract**

```bash
git add \
  .github/workflows/reusable-docker-build.yml \
  .github/workflows/ci-main.yml \
  .github/workflows/cd-selfhosted-compose.yml \
  .github/workflows/cd-selfhosted-kubernetes.yml \
  scripts/validate-k8s-cd-workflow.sh
git commit -m "feat: Docker 이미지를 GHCR digest로 발행"
```

---

### Task 3: `develop` Release CI orchestration 추가

**Files:**
- Create: `.github/workflows/release-develop.yml`
- Modify: `scripts/validate-k8s-cd-workflow.sh`

**Interfaces:**
- Consumes: planner outputs and `reusable-build.yml`
- Produces: all-tests Gate, service image artifacts, compact `release_manifest`

- [ ] **Step 1: Add failing Release workflow assertions**

Add `RELEASE_WORKFLOW` and required patterns:

```bash
RELEASE_WORKFLOW="${ROOT_DIR}/.github/workflows/release-develop.yml"

release_required_patterns=(
  '^name:[[:space:]]+Release - Develop$'
  '^[[:space:]]+push:$'
  'branches:[[:space:]]+\["develop"\]'
  'test_matrix:'
  'image_matrix:'
  'bash scripts/plan-release-targets\.sh'
  '^[[:space:]]+build-and-test:$'
  '^[[:space:]]+ci-gate:$'
  '^[[:space:]]+publish-images:$'
  '^[[:space:]]+collect-release-manifest:$'
  'actions/download-artifact@v4'
  'release_manifest='
)

for pattern in "${release_required_patterns[@]}"; do
  grep -Eq -- "$pattern" "$RELEASE_WORKFLOW" ||
    fail "missing Release workflow contract: $pattern"
done
```

- [ ] **Step 2: Run the validator and verify the missing workflow failure**

Run:

```bash
bash scripts/validate-k8s-cd-workflow.sh
```

Expected: non-zero exit reporting missing `.github/workflows/release-develop.yml`.

- [ ] **Step 3: Create planning and test jobs**

Create `.github/workflows/release-develop.yml` with:

```yaml
name: Release - Develop

on:
  push:
    branches: ["develop"]

permissions:
  contents: read

concurrency:
  group: release-develop
  cancel-in-progress: false
```

The `planning` job checks out with `fetch-depth: 0`, uses `tj-actions/changed-files@v46`, and defines:

```yaml
            user_service:
              - user-service/**
              - grpc/user/**
            ai_service:
              - ai-service/**
              - grpc/user/**
            product_service:
              - product-service/**
            payment_service:
              - payment-service/**
            order_service:
              - order-service/**
            settlement_service:
              - settlement-service/**
            admin_service:
              - admin-service/**
            apigateway:
              - apigateway/**
            config:
              - config/**
            discovery:
              - discovery/**
            shared_build:
              - common-module/**
              - Dockerfile
              - build.gradle
              - build.gradle.kts
              - gradle.properties
              - gradlew
              - gradlew.bat
              - settings.gradle
              - settings.gradle.kts
              - gradle/**
              - .github/workflows/reusable-build.yml
              - .github/workflows/reusable-docker-build.yml
              - .github/workflows/release-develop.yml
            application_manifests:
              - k8s/base/platform/**
              - k8s/base/services/**
              - k8s/base/gateway/**
              - k8s/overlays/ec2-kubeadm/applications/**
              - .github/workflows/reusable-kubernetes-deploy.yml
```

Pass these flags to the planner:

```yaml
        env:
          USER_SERVICE_CHANGED: ${{ steps.changed.outputs.user_service_any_changed }}
          AI_SERVICE_CHANGED: ${{ steps.changed.outputs.ai_service_any_changed }}
          PRODUCT_SERVICE_CHANGED: ${{ steps.changed.outputs.product_service_any_changed }}
          PAYMENT_SERVICE_CHANGED: ${{ steps.changed.outputs.payment_service_any_changed }}
          ORDER_SERVICE_CHANGED: ${{ steps.changed.outputs.order_service_any_changed }}
          SETTLEMENT_SERVICE_CHANGED: ${{ steps.changed.outputs.settlement_service_any_changed }}
          ADMIN_SERVICE_CHANGED: ${{ steps.changed.outputs.admin_service_any_changed }}
          APIGATEWAY_CHANGED: ${{ steps.changed.outputs.apigateway_any_changed }}
          CONFIG_CHANGED: ${{ steps.changed.outputs.config_any_changed }}
          DISCOVERY_CHANGED: ${{ steps.changed.outputs.discovery_any_changed }}
          SHARED_BUILD_CHANGED: ${{ steps.changed.outputs.shared_build_any_changed }}
          APPLICATION_MANIFESTS_CHANGED: ${{ steps.changed.outputs.application_manifests_any_changed }}
        run: bash scripts/plan-release-targets.sh
```

Call `reusable-build.yml` with:

```yaml
  build-and-test:
    needs: planning
    if: needs.planning.outputs.test_matrix != '[]'
    strategy:
      fail-fast: false
      matrix:
        service: ${{ fromJson(needs.planning.outputs.test_matrix) }}
    uses: ./.github/workflows/reusable-build.yml
    with:
      module-name: ${{ matrix.service }}
      module-path: ${{ matrix.service }}
```

- [ ] **Step 4: Add the aggregate Gate and publish matrix**

Use an aggregate Gate that accepts only the intentional empty-matrix skip:

```yaml
  ci-gate:
    needs:
      - planning
      - build-and-test
    if: always()
    runs-on: ubuntu-latest
    steps:
      - name: Release CI 결과 확인
        env:
          PLANNING_RESULT: ${{ needs.planning.result }}
          TEST_RESULT: ${{ needs.build-and-test.result }}
          TEST_MATRIX: ${{ needs.planning.outputs.test_matrix }}
        run: |
          set -euo pipefail
          [ "$PLANNING_RESULT" = "success" ]

          if [ "$TEST_MATRIX" = "[]" ]; then
            [ "$TEST_RESULT" = "skipped" ]
          else
            [ "$TEST_RESULT" = "success" ]
          fi
```

Publish only after the Gate:

```yaml
  publish-images:
    needs:
      - planning
      - ci-gate
    if: needs.ci-gate.result == 'success' && needs.planning.outputs.image_matrix != '[]'
    strategy:
      fail-fast: false
      matrix:
        service: ${{ fromJson(needs.planning.outputs.image_matrix) }}
    uses: ./.github/workflows/reusable-docker-build.yml
    permissions:
      contents: read
      packages: write
    with:
      module-name: ${{ matrix.service }}
      module-path: ${{ matrix.service }}
      ghcr-repository: prompthub-${{ matrix.service }}
      image-tag: ${{ github.sha }}
      push-image: true
      publish-latest: false
```

- [ ] **Step 5: Collect image artifacts into one Release manifest**

Create a job output:

```yaml
  collect-release-manifest:
    needs:
      - planning
      - ci-gate
      - publish-images
    if: |
      always() &&
      needs.ci-gate.result == 'success' &&
      (
        needs.publish-images.result == 'success' ||
        needs.publish-images.result == 'skipped'
      )
    runs-on: ubuntu-latest
    outputs:
      release_manifest: ${{ steps.collect.outputs.release_manifest }}
```

Download only when images were published and merge the per-service JSON:

```yaml
      - name: Download image metadata
        if: needs.planning.outputs.image_matrix != '[]'
        uses: actions/download-artifact@v4
        with:
          pattern: image-metadata-*
          path: image-metadata
          merge-multiple: true

      - name: Collect Release manifest
        id: collect
        env:
          IMAGE_MATRIX: ${{ needs.planning.outputs.image_matrix }}
        run: |
          set -euo pipefail

          if [ "$IMAGE_MATRIX" = "[]" ]; then
            release_manifest='{}'
          else
            expected_count="$(jq 'length' <<< "$IMAGE_MATRIX")"
            actual_count="$(find image-metadata -type f -name '*.json' | wc -l | tr -d ' ')"
            [ "$actual_count" = "$expected_count" ]

            release_manifest="$(
              jq -s -c '
                map(select(
                  (.service | type) == "string" and
                  (.imageUri | type) == "string" and
                  (.digest | test("^sha256:[0-9a-f]{64}$")) and
                  (.immutableRef | test("@sha256:[0-9a-f]{64}$"))
                ))
                | map({key:.service,value:.})
                | from_entries
              ' image-metadata/*.json
            )"
            [ "$(jq 'length' <<< "$release_manifest")" = "$expected_count" ]
          fi

          echo "release_manifest=$release_manifest" >> "$GITHUB_OUTPUT"
```

- [ ] **Step 6: Run Release workflow contract checks**

Run:

```bash
bash scripts/test-plan-release-targets.sh
bash scripts/validate-k8s-cd-workflow.sh
```

Expected: both exit 0. The Release workflow does not yet call Kubernetes CD; that connection is added in Task 4.

- [ ] **Step 7: Commit Release CI**

```bash
git add .github/workflows/release-develop.yml scripts/validate-k8s-cd-workflow.sh
git commit -m "feat: develop Release CI 파이프라인 추가"
```

---

### Task 4: 애플리케이션 배포를 reusable CD로 추출하고 digest 배포

**Files:**
- Create: `.github/workflows/reusable-kubernetes-deploy.yml`
- Modify: `.github/workflows/release-develop.yml`
- Modify: `scripts/validate-k8s-cd-workflow.sh`

**Interfaces:**
- Consumes: compact `release-manifest`, boolean `application-manifests-changed`
- Produces: Kubernetes apply/rollout result; failure restores only this run's changes

- [ ] **Step 1: Add failing reusable CD assertions**

Point deployment-order assertions at the new file and require:

```bash
APPLICATION_WORKFLOW="${ROOT_DIR}/.github/workflows/reusable-kubernetes-deploy.yml"

application_required_patterns=(
  '^name:[[:space:]]+Reusable Kubernetes Application Deploy$'
  '^[[:space:]]+workflow_call:$'
  '^[[:space:]]+release-manifest:$'
  '^[[:space:]]+application-manifests-changed:$'
  'RELEASE_MANIFEST:'
  'immutableRef'
  'validate_release_manifest'
  'current_or_base_image'
  'append_image_override'
  'snapshot_manifest_deployments'
  'rollback_deployments'
  'rollback_settlement_cronjob'
)
```

Forbid in `APPLICATION_WORKFLOW`:

```bash
application_forbidden_patterns=(
  'docker/build-push-action'
  'docker/login-action'
  'push-image:'
  'ecr-repository'
  ':latest'
  'short_sha'
)
```

- [ ] **Step 2: Run validator and verify the missing reusable CD failure**

Run:

```bash
bash scripts/validate-k8s-cd-workflow.sh
```

Expected: non-zero exit reporting missing `reusable-kubernetes-deploy.yml`.

- [ ] **Step 3: Create the reusable workflow boundary**

Create:

```yaml
name: Reusable Kubernetes Application Deploy

on:
  workflow_call:
    inputs:
      release-manifest:
        required: true
        type: string
      application-manifests-changed:
        required: true
        type: boolean

permissions:
  contents: read

env:
  NAMESPACE: prompthub
  KUBECONFIG: /home/ubuntu/.kube/config

jobs:
  deploy-applications:
    runs-on: [self-hosted, linux, deploy]
    timeout-minutes: 90
```

Move the current `deploy-applications` steps from
`.github/workflows/cd-selfhosted-kubernetes.yml:161-581` into this job. Preserve these current contracts:

```text
required Secret checks including ai-secret
initial package creation without waiting on a broken existing rollout
release_order: config → discovery → user → product → order → payment → settlement → admin → ai → apigateway
deployment_order excludes settlement
config_consumers excludes settlement and includes ai
Deployment template snapshots
new Deployment tracking
reverse rollback
settlement CronJob snapshot and rollback
legacy settlement Deployment/Service cleanup after manifest transition
```

Replace `needs.planning` expressions with:

```yaml
        env:
          RELEASE_MANIFEST: ${{ inputs.release-manifest }}
          APPLICATION_MANIFESTS_CHANGED: ${{ inputs.application-manifests-changed }}
```

- [ ] **Step 4: Validate Release manifest before touching the cluster**

Add this function before snapshot or apply:

```bash
validate_release_manifest() {
  jq -e 'type == "object"' <<< "$RELEASE_MANIFEST" >/dev/null

  local service
  local immutable_ref
  while IFS= read -r service; do
    case "$service" in
      config|discovery|user-service|product-service|order-service|payment-service|settlement-service|admin-service|ai-service|apigateway)
        ;;
      *)
        echo "허용되지 않은 Release 서비스: $service" >&2
        return 1
        ;;
    esac

    immutable_ref="$(jq -r --arg service "$service" '.[$service].immutableRef' <<< "$RELEASE_MANIFEST")"
    if [[ ! "$immutable_ref" =~ ^ghcr\.io/${owner_lc}/prompthub-${service}@sha256:[0-9a-f]{64}$ ]]; then
      echo "잘못된 immutable image reference: $service -> $immutable_ref" >&2
      return 1
    fi
  done < <(jq -r 'keys[]' <<< "$RELEASE_MANIFEST")
}
```

Call it before any `kubectl apply` or `kubectl set image`.

- [ ] **Step 5: Preserve unchanged workload images during manifest apply**

Add an explicit base manifest map:

```bash
base_manifest_for() {
  case "$1" in
    config) echo "k8s/base/platform/config/deployment.yaml" ;;
    discovery) echo "k8s/base/platform/discovery/deployment.yaml" ;;
    user-service) echo "k8s/base/services/user/deployment.yaml" ;;
    product-service) echo "k8s/base/services/product/deployment.yaml" ;;
    order-service) echo "k8s/base/services/order/deployment.yaml" ;;
    payment-service) echo "k8s/base/services/payment/deployment.yaml" ;;
    settlement-service) echo "k8s/base/services/settlement/cronjob.yaml" ;;
    admin-service) echo "k8s/base/services/admin/deployment.yaml" ;;
    ai-service) echo "k8s/base/services/ai/deployment.yaml" ;;
    apigateway) echo "k8s/base/gateway/deployment.yaml" ;;
    *) return 1 ;;
  esac
}
```

Resolve each target image:

```bash
current_or_base_image() {
  local service="$1"
  local current=""

  if jq -e --arg service "$service" 'has($service)' <<< "$RELEASE_MANIFEST" >/dev/null; then
    jq -r --arg service "$service" '.[$service].immutableRef' <<< "$RELEASE_MANIFEST"
    return
  fi

  if [ "$service" = "settlement-service" ]; then
    current="$(kubectl get cronjob settlement-weekly -n "$NAMESPACE" \
      -o jsonpath="{.spec.jobTemplate.spec.template.spec.containers[?(@.name=='settlement-service')].image}" \
      2>/dev/null || true)"
  else
    current="$(kubectl get deployment "$service" -n "$NAMESPACE" \
      -o jsonpath="{.spec.template.spec.containers[?(@.name=='$service')].image}" \
      2>/dev/null || true)"
  fi

  if [ -n "$current" ]; then
    echo "$current"
    return
  fi

  awk '$1 == "image:" { print $2; exit }' "$(base_manifest_for "$service")"
}
```

Write Kustomize overrides for both tag and digest references:

```bash
append_image_override() {
  local service="$1"
  local reference="$2"
  local base_name="ghcr.io/prgrms-be-adv-devcourse/prompthub-$service"
  local repository
  local version

  if [[ "$reference" == *@sha256:* ]]; then
    repository="${reference%@*}"
    version="${reference#*@}"
    {
      echo "  - name: $base_name"
      echo "    newName: $repository"
      echo "    digest: $version"
    } >> "$runtime_overlay/kustomization.yaml"
  else
    repository="${reference%:*}"
    version="${reference##*:}"
    {
      echo "  - name: $base_name"
      echo "    newName: $repository"
      echo "    newTag: $version"
    } >> "$runtime_overlay/kustomization.yaml"
  fi
}
```

When `APPLICATION_MANIFESTS_CHANGED=true`, initialize `images:` and call this function for all ten
services using `current_or_base_image`. This replaces the ten hard-coded short SHA overrides.

- [ ] **Step 6: Deploy only immutable references from the Release manifest**

Replace the current `short_sha` image construction:

```bash
if ! jq -e --arg service "$service" 'has($service)' <<< "$RELEASE_MANIFEST" >/dev/null; then
  continue
fi

image="$(jq -r --arg service "$service" '.[$service].immutableRef' <<< "$RELEASE_MANIFEST")"
```

Keep the current settlement CronJob and Deployment update branches, annotation, rollout, Config restart and
rollback behavior.

- [ ] **Step 7: Connect the Release workflow to reusable CD**

Add:

```yaml
  deploy-applications:
    needs:
      - planning
      - ci-gate
      - collect-release-manifest
    if: |
      always() &&
      needs.planning.outputs.deploy == 'true' &&
      needs.ci-gate.result == 'success' &&
      needs.collect-release-manifest.result == 'success'
    uses: ./.github/workflows/reusable-kubernetes-deploy.yml
    with:
      release-manifest: ${{ needs.collect-release-manifest.outputs.release_manifest }}
      application-manifests-changed: ${{ needs.planning.outputs.application_manifests_changed == 'true' }}
```

- [ ] **Step 8: Run reusable CD contract and manifest validators**

Run:

```bash
bash scripts/validate-k8s-cd-workflow.sh
bash scripts/validate-k8s-manifests.sh
bash scripts/validate-k8s-secret-contract.sh
```

Expected: all exit 0.

- [ ] **Step 9: Commit reusable application CD**

```bash
git add \
  .github/workflows/reusable-kubernetes-deploy.yml \
  .github/workflows/release-develop.yml \
  scripts/validate-k8s-cd-workflow.sh
git commit -m "feat: Release digest 기반 Kubernetes CD 연결"
```

---

### Task 5: 기존 Kubernetes CD를 수동 인프라 전용으로 축소

**Files:**
- Modify: `.github/workflows/cd-selfhosted-kubernetes.yml:3-581`
- Modify: `scripts/validate-k8s-cd-workflow.sh`

**Interfaces:**
- Preserves: `workflow_dispatch(target, confirmation)`, `deploy-infrastructure`, `deploy-ingress`
- Removes: automatic push trigger, planning, image build/push, application deploy

- [ ] **Step 1: Change validator expectations before trimming**

Require in the manual workflow:

```bash
manual_required_patterns=(
  '^name:[[:space:]]+CD - Self-hosted Kubernetes$'
  '^[[:space:]]+workflow_dispatch:$'
  '^[[:space:]]+- infrastructure$'
  '^[[:space:]]+- ingress$'
  'CONFIRMATION:'
  'kubectl apply -k k8s/base/storage'
  'kubectl apply -k k8s/base/infrastructure'
  'kubectl apply -k k8s/addons/nginx-ingress'
)
```

Forbid:

```bash
manual_forbidden_patterns=(
  '^[[:space:]]+push:$'
  '^[[:space:]]+planning:$'
  'parallel-build-and-push'
  'deploy-applications'
  'reusable-docker-build'
  'packages: write'
)
```

Move `release_order`, `deployment_order`, `config_consumers`, rollback and settlement assertions to
`APPLICATION_WORKFLOW`.

- [ ] **Step 2: Run validator and verify it fails on the current automatic jobs**

Run:

```bash
bash scripts/validate-k8s-cd-workflow.sh
```

Expected: non-zero exit reporting the active `push` or `planning` contract.

- [ ] **Step 3: Remove automatic application responsibilities**

From `.github/workflows/cd-selfhosted-kubernetes.yml` remove:

```text
on.push
permissions.packages
jobs.planning
jobs.parallel-build-and-push
jobs.deploy-applications
```

Keep:

```yaml
on:
  workflow_dispatch:
    inputs:
      target:
        description: "수동 배포 대상"
        required: true
        type: choice
        options:
          - infrastructure
          - ingress
      confirmation:
        description: "실행하려면 DEPLOY 입력"
        required: true
        type: string

permissions:
  contents: read
```

Preserve `NAMESPACE`, `KUBECONFIG`, `deploy-infrastructure` and `deploy-ingress` bodies.

- [ ] **Step 4: Run the full workflow boundary validator**

Run:

```bash
bash scripts/validate-k8s-cd-workflow.sh
```

Expected: exit 0 and print `Kubernetes CD workflow validation passed.`

Also run:

```bash
rg -n 'push-image|docker/build-push-action|docker/login-action|parallel-build-and-push' \
  .github/workflows/cd-selfhosted-kubernetes.yml \
  .github/workflows/reusable-kubernetes-deploy.yml
```

Expected: no matches.

- [ ] **Step 5: Commit the manual CD boundary**

```bash
git add .github/workflows/cd-selfhosted-kubernetes.yml scripts/validate-k8s-cd-workflow.sh
git commit -m "refactor: Kubernetes 수동 배포 경계 분리"
```

---

### Task 6: CI/CD 운영 문서를 현재 구조로 갱신

**Files:**
- Modify: `k8s/README.md:31,70-96`
- Modify: `docs/architecture/kubernetes.md:56,99,565-574,766-806`
- Modify: `settlement-service/docs/architecture/deployment-ci-cd.md:1-80`

**Interfaces:**
- Documents: PR CI, Release CI, Release manifest, reusable CD, manual infrastructure/Ingress, Compose rollback

- [ ] **Step 1: Add documentation assertions to the workflow validator**

Require these literal references across the three documents:

```bash
docs_required_patterns=(
  'release-develop\.yml'
  'reusable-kubernetes-deploy\.yml'
  '전체 Git SHA'
  'image digest'
  'CI가 발행'
  '매니페스트만'
)

docs=(
  "${ROOT_DIR}/k8s/README.md"
  "${ROOT_DIR}/docs/architecture/kubernetes.md"
  "${ROOT_DIR}/settlement-service/docs/architecture/deployment-ci-cd.md"
)

for pattern in "${docs_required_patterns[@]}"; do
  grep -Eq -- "$pattern" "${docs[@]}" ||
    fail "missing CI/CD documentation contract: $pattern"
done

if grep -Eq 'main-cd\.yml|CD — EC2에서 빌드·실행' \
  "${ROOT_DIR}/settlement-service/docs/architecture/deployment-ci-cd.md"; then
  fail "settlement deployment documentation is stale"
fi
```

- [ ] **Step 2: Run validator and verify stale documentation failure**

Run:

```bash
bash scripts/validate-k8s-cd-workflow.sh
```

Expected: non-zero exit identifying missing Release CI documentation or stale `main-cd.yml`.

- [ ] **Step 3: Update Kubernetes README**

Describe this exact flow:

```text
develop push
  → release-develop.yml
  → changed services build/test
  → Release CI Gate
  → full SHA image push and digest collection
  → reusable-kubernetes-deploy.yml
  → rollout/rollback
```

State that application-manifest-only changes preserve current images, and
`cd-selfhosted-kubernetes.yml` is manual infrastructure/Ingress only.

- [ ] **Step 4: Update the architecture contract**

Replace the single-workflow automatic CD contract in `docs/architecture/kubernetes.md` with:

```text
PR CI: ci.yml
Develop Release CI: release-develop.yml
Application CD: reusable-kubernetes-deploy.yml
Manual infrastructure/Ingress: cd-selfhosted-kubernetes.yml
Manual Compose rollback: cd-selfhosted-compose.yml
```

Replace short SHA rollout statements with full SHA traceability plus digest deployment. Keep the existing
Secret, runner, rollout order, CronJob and rollback sections.

- [ ] **Step 5: Rewrite the settlement deployment overview**

Replace the old Compose-only `main-cd.yml` description with the current Kubernetes flow. Explicitly state:

```text
PR CI does not publish images.
Release CI publishes changed service images after all affected tests pass.
CD receives immutable references and never rebuilds application images.
Infrastructure and Ingress require workflow_dispatch with DEPLOY confirmation.
```

- [ ] **Step 6: Run documentation and workflow validation**

Run:

```bash
bash scripts/validate-k8s-cd-workflow.sh
rg -n 'main-cd.yml|CD — EC2에서 빌드·실행' \
  k8s/README.md \
  docs/architecture/kubernetes.md \
  settlement-service/docs/architecture/deployment-ci-cd.md
```

Expected: validator exits 0; `rg` returns no stale matches.

- [ ] **Step 7: Commit documentation**

```bash
git add \
  k8s/README.md \
  docs/architecture/kubernetes.md \
  settlement-service/docs/architecture/deployment-ci-cd.md \
  scripts/validate-k8s-cd-workflow.sh
git commit -m "docs: Release CI와 Kubernetes CD 흐름 갱신"
```

---

### Task 7: 전체 정적 검증과 변경 범위 감사

**Files:**
- Verify only; modify a failing implementation file only when the failure directly identifies a defect from Tasks 1-6

**Interfaces:**
- Produces: evidence that workflow boundaries, release planning, manifests and secrets satisfy the approved design

- [ ] **Step 1: Run shell syntax and planner tests**

```bash
bash -n scripts/plan-release-targets.sh
bash -n scripts/test-plan-release-targets.sh
bash -n scripts/validate-k8s-cd-workflow.sh
bash scripts/test-plan-release-targets.sh
```

Expected: all exit 0.

- [ ] **Step 2: Run workflow and Kubernetes static validators**

```bash
bash scripts/validate-k8s-cd-workflow.sh
bash scripts/validate-k8s-manifests.sh
bash scripts/validate-k8s-secret-contract.sh
```

Expected: all exit 0 with their success messages.

- [ ] **Step 3: Render every deployment package**

```bash
kubectl kustomize k8s/overlays/ec2-kubeadm/applications >/dev/null
kubectl apply --dry-run=client -k k8s/overlays/ec2-kubeadm/applications >/dev/null
```

Expected: both exit 0.

- [ ] **Step 4: Verify immutable-image and permission boundaries**

```bash
rg -n 'ecr-repository|SHORT_SHA|short_sha' .github/workflows scripts
```

Expected: no matches in Release CI or reusable application CD. A remaining historical/document reference must
be reviewed and either corrected or explicitly outside executable workflow scope.

```bash
rg -n 'packages: write|docker/login-action|docker/build-push-action' \
  .github/workflows/release-develop.yml \
  .github/workflows/reusable-docker-build.yml \
  .github/workflows/reusable-kubernetes-deploy.yml \
  .github/workflows/cd-selfhosted-kubernetes.yml
```

Expected: write/login/build appear only in Release CI or reusable Docker build, never in reusable or manual
Kubernetes CD.

- [ ] **Step 5: Confirm PR CI behavior was not changed**

```bash
git diff origin/develop...HEAD -- .github/workflows/ci.yml
```

Expected: empty output.

- [ ] **Step 6: Audit committed and uncommitted scope**

```bash
git status --short
git diff --stat origin/develop...HEAD
git log --oneline origin/develop..HEAD
```

Expected: implementation commits contain only `#584 (이슈)` workflow, validator and documentation files.
The four pre-existing user changes remain unstaged and are not included in any implementation commit.

- [ ] **Step 7: Record final verification**

Do not create an empty verification commit. Include command results in the handoff and proceed to the
project verification/review workflow before PR creation.

---

## Service-Scoped Manifest Deployment Supplement

### Task 8: Release 대상 계산을 서비스별 matrix 계약으로 확장

**Files:**
- Modify: `scripts/test-plan-release-targets.sh`
- Modify: `scripts/plan-release-targets.sh`

**Interfaces:**
- Consumes: 기존 코드 변경 flag, 서비스별 `*_MANIFEST_CHANGED`, `ALL_APPLICATION_MANIFESTS_CHANGED`,
  Config profile flag, `MANUAL_RELEASE_SERVICES`, `MANUAL_MANIFEST_SERVICES`, `MANUAL_CONFIRMATION`
- Produces: `test_matrix`, `image_matrix`, `manifest_matrix`, `deploy_matrix`,
  `config_consumer_matrix`, `apply_all_manifests`, `deploy`

- [ ] **Step 1: 서비스별 매니페스트와 수동 Release 실패 테스트 작성**

`run_case`가 일곱 출력을 검증하도록 바꾸고 다음 계약을 추가한다.

```bash
run_case \
  "ai manifest only" \
  '[]' '[]' '["ai-service"]' '["ai-service"]' '[]' false true \
  AI_MANIFEST_CHANGED=true

run_case \
  "ai code and manifest" \
  '["ai-service"]' '["ai-service"]' '["ai-service"]' '["ai-service"]' '[]' false true \
  AI_SERVICE_CHANGED=true AI_MANIFEST_CHANGED=true

run_case \
  "shared application overlay" \
  '[]' '[]' "$all_services" "$all_services" '[]' true true \
  ALL_APPLICATION_MANIFESTS_CHANGED=true

run_case \
  "ai config profile" \
  '["config"]' '["config"]' '[]' '["config"]' '["ai-service"]' false true \
  CONFIG_CHANGED=true AI_CONFIG_CHANGED=true

run_case \
  "pipeline only" \
  '[]' '[]' '[]' '[]' '[]' false false \
  PIPELINE_CHANGED=true

run_case \
  "manual config and ai replay" \
  '["config","ai-service"]' \
  '["config","ai-service"]' \
  '["ai-service"]' \
  '["config","ai-service"]' \
  '[]' false true \
  MANUAL_CONFIRMATION=RELEASE \
  MANUAL_RELEASE_SERVICES=config,ai-service \
  MANUAL_MANIFEST_SERVICES=ai-service
```

잘못된 수동 입력은 별도 실패 helper로 검증한다.

```bash
run_failure_case \
  "manual confirmation" \
  MANUAL_CONFIRMATION=WRONG \
  MANUAL_RELEASE_SERVICES=config,ai-service

run_failure_case \
  "manual unknown service" \
  MANUAL_CONFIRMATION=RELEASE \
  MANUAL_RELEASE_SERVICES=config,unknown

run_failure_case \
  "manual empty item" \
  MANUAL_CONFIRMATION=RELEASE \
  MANUAL_RELEASE_SERVICES=config,,ai-service
```

- [ ] **Step 2: 테스트를 실행해 기존 단일 boolean 계약 때문에 실패하는지 확인**

Run:

```bash
bash scripts/test-plan-release-targets.sh
```

Expected: `manifest_matrix` 또는 `deploy_matrix` 누락으로 non-zero exit.

- [ ] **Step 3: Bash 3.2 호환 서비스 집합 helper 구현**

`plan-release-targets.sh`에 공백 구분 집합과 고정 release order를 사용한다. associative array,
`declare -A`, nameref는 사용하지 않는다.

```bash
release_order=(
  config
  discovery
  user-service
  product-service
  order-service
  payment-service
  settlement-service
  admin-service
  ai-service
  apigateway
)

append_service() {
  local list="$1"
  local candidate="$2"
  case " $list " in
    *" $candidate "*) printf '%s' "$list" ;;
    *) printf '%s%s%s' "$list" "${list:+ }" "$candidate" ;;
  esac
}

require_service() {
  case "$1" in
    config|discovery|user-service|product-service|order-service|payment-service|settlement-service|admin-service|ai-service|apigateway)
      ;;
    *)
      echo "허용되지 않은 Release 서비스: $1" >&2
      return 1
      ;;
  esac
}

ordered_json() {
  local selected=" $1 "
  local values=()
  local service
  for service in "${release_order[@]}"; do
    case "$selected" in
      *" $service "*) values+=("$service") ;;
    esac
  done

  if [ ${#values[@]} -eq 0 ]; then
    printf '[]'
  else
    printf '%s\n' "${values[@]}" | jq -R . | jq -s -c .
  fi
}
```

수동 CSV는 빈 항목을 먼저 거부하고, 각 항목의 모든 공백을 제거한 뒤 `require_service`로 검증한다.

```bash
parse_manual_services() {
  local raw="$1"
  local parsed=""
  local parts=()
  local part

  [ -z "$raw" ] && {
    printf ''
    return
  }
  case ",$raw," in
    *,,*)
      echo "수동 Release 서비스에 빈 항목이 있습니다: $raw" >&2
      return 1
      ;;
  esac

  IFS=',' read -r -a parts <<< "$raw"
  for part in "${parts[@]}"; do
    part="$(printf '%s' "$part" | tr -d '[:space:]')"
    require_service "$part"
    parsed="$(append_service "$parsed" "$part")"
  done
  printf '%s' "$parsed"
}
```

- [ ] **Step 4: 자동 변경 flag를 다섯 matrix로 변환**

기존 코드 변경 flag는 `test_services`와 `image_services`에 넣는다. 서비스별 manifest flag는
`manifest_services`, 공통 overlay는 전체 `manifest_services`와
`apply_all_manifests=true`를 만든다. Config profile은 장기 실행 소비자만
`config_consumer_services`에 넣는다.

```bash
add_image_target() {
  test_services="$(append_service "$test_services" "$1")"
  image_services="$(append_service "$image_services" "$1")"
}

add_manifest_target() {
  manifest_services="$(append_service "$manifest_services" "$1")"
}

add_config_consumer() {
  config_consumer_services="$(append_service "$config_consumer_services" "$1")"
}

if is_true "${SHARED_BUILD_CHANGED:-false}"; then
  for service in "${release_order[@]}"; do
    add_image_target "$service"
  done
else
  is_true "${CONFIG_CHANGED:-false}" && add_image_target config
  is_true "${DISCOVERY_CHANGED:-false}" && add_image_target discovery
  is_true "${USER_SERVICE_CHANGED:-false}" && add_image_target user-service
  is_true "${PRODUCT_SERVICE_CHANGED:-false}" && add_image_target product-service
  is_true "${ORDER_SERVICE_CHANGED:-false}" && add_image_target order-service
  is_true "${PAYMENT_SERVICE_CHANGED:-false}" && add_image_target payment-service
  is_true "${SETTLEMENT_SERVICE_CHANGED:-false}" && add_image_target settlement-service
  is_true "${ADMIN_SERVICE_CHANGED:-false}" && add_image_target admin-service
  is_true "${AI_SERVICE_CHANGED:-false}" && add_image_target ai-service
  is_true "${APIGATEWAY_CHANGED:-false}" && add_image_target apigateway
fi

is_true "${CONFIG_MANIFEST_CHANGED:-false}" && add_manifest_target config
is_true "${DISCOVERY_MANIFEST_CHANGED:-false}" && add_manifest_target discovery
is_true "${USER_MANIFEST_CHANGED:-false}" && add_manifest_target user-service
is_true "${PRODUCT_MANIFEST_CHANGED:-false}" && add_manifest_target product-service
is_true "${ORDER_MANIFEST_CHANGED:-false}" && add_manifest_target order-service
is_true "${PAYMENT_MANIFEST_CHANGED:-false}" && add_manifest_target payment-service
is_true "${SETTLEMENT_MANIFEST_CHANGED:-false}" && add_manifest_target settlement-service
is_true "${ADMIN_MANIFEST_CHANGED:-false}" && add_manifest_target admin-service
is_true "${AI_MANIFEST_CHANGED:-false}" && add_manifest_target ai-service
is_true "${APIGATEWAY_MANIFEST_CHANGED:-false}" && add_manifest_target apigateway

is_true "${USER_CONFIG_CHANGED:-false}" && add_config_consumer user-service
is_true "${PRODUCT_CONFIG_CHANGED:-false}" && add_config_consumer product-service
is_true "${ORDER_CONFIG_CHANGED:-false}" && add_config_consumer order-service
is_true "${PAYMENT_CONFIG_CHANGED:-false}" && add_config_consumer payment-service
is_true "${ADMIN_CONFIG_CHANGED:-false}" && add_config_consumer admin-service
is_true "${AI_CONFIG_CHANGED:-false}" && add_config_consumer ai-service

if is_true "${SHARED_CONFIG_CHANGED:-false}"; then
  for service in user-service product-service order-service payment-service admin-service ai-service apigateway; do
    add_config_consumer "$service"
  done
fi

if is_true "${ALL_APPLICATION_MANIFESTS_CHANGED:-false}"; then
  apply_all_manifests=true
  manifest_services="${release_order[*]}"
fi
```

수동 입력이 존재하면 자동 flag 결과 대신 아래 계약을 사용한다.

```bash
if [ -n "${MANUAL_RELEASE_SERVICES:-}${MANUAL_MANIFEST_SERVICES:-}" ]; then
  [ "${MANUAL_CONFIRMATION:-}" = "RELEASE" ] || {
    echo "수동 Release는 confirmation에 RELEASE가 필요합니다." >&2
    exit 1
  }
  manual_release="$(parse_manual_services "${MANUAL_RELEASE_SERVICES:-}")"
  manual_manifests="$(parse_manual_services "${MANUAL_MANIFEST_SERVICES:-}")"
  test_services="$manual_release"
  image_services="$manual_release"
  manifest_services="$manual_manifests"
  config_consumer_services=""
  apply_all_manifests=false
fi

deploy_services="$image_services"
for service in $manifest_services; do
  deploy_services="$(append_service "$deploy_services" "$service")"
done
```

- [ ] **Step 5: 새 출력 계약을 기록하고 테스트 통과 확인**

```bash
test_matrix="$(ordered_json "$test_services")"
image_matrix="$(ordered_json "$image_services")"
manifest_matrix="$(ordered_json "$manifest_services")"
deploy_matrix="$(ordered_json "$deploy_services")"
config_consumer_matrix="$(ordered_json "$config_consumer_services")"
deploy=false
[ "$deploy_matrix" != "[]" ] && deploy=true

{
  echo "test_matrix=$test_matrix"
  echo "image_matrix=$image_matrix"
  echo "manifest_matrix=$manifest_matrix"
  echo "deploy_matrix=$deploy_matrix"
  echo "config_consumer_matrix=$config_consumer_matrix"
  echo "apply_all_manifests=$apply_all_manifests"
  echo "deploy=$deploy"
} >> "$GITHUB_OUTPUT"
```

Run:

```bash
bash -n scripts/plan-release-targets.sh
bash -n scripts/test-plan-release-targets.sh
bash scripts/test-plan-release-targets.sh
```

Expected: all exit 0 and print `Release target planner tests passed.`

- [ ] **Step 6: Planner 보완 커밋**

```bash
git add scripts/plan-release-targets.sh scripts/test-plan-release-targets.sh
git commit -m "feat: 서비스별 Release 대상 계산 추가"
```

---

### Task 9: Release workflow에 세분화된 변경 감지와 수동 최신 develop 재배포 추가

**Files:**
- Modify: `.github/workflows/release-develop.yml`
- Modify: `scripts/validate-k8s-cd-workflow.sh`

**Interfaces:**
- Consumes: push changed-files 결과 또는 `workflow_dispatch`의 `release-services`,
  `manifest-services`, `confirmation`
- Produces: Task 8의 일곱 planning output과 immutable release manifest

- [ ] **Step 1: workflow 계약 validator를 먼저 실패하도록 확장**

`release_patterns`에서 `application_manifests_changed`를 제거하고 다음 패턴을 요구한다.

```bash
release_patterns+=(
  '^[[:space:]]+workflow_dispatch:$'
  '^[[:space:]]+release-services:$'
  '^[[:space:]]+manifest-services:$'
  '^[[:space:]]+confirmation:$'
  'manifest_matrix:'
  'deploy_matrix:'
  'config_consumer_matrix:'
  'apply_all_manifests:'
  'MANUAL_CONFIRMATION:'
  'PIPELINE_CHANGED:'
  'AI_MANIFEST_CHANGED:'
)
```

pipeline-only 변경이 shared build나 전체 manifest로 전달되지 않는 정적 검사도 추가한다.

```bash
pipeline_block="$(sed -n '/^[[:space:]]*pipeline:/,/^[[:space:]]*[a-z_]*:/p' "$RELEASE_WORKFLOW")"
grep -Fq '.github/workflows/release-develop.yml' <<< "$pipeline_block" ||
  fail "Release workflow must classify itself as pipeline-only"
```

Run:

```bash
bash scripts/validate-k8s-cd-workflow.sh
```

Expected: 새 `workflow_dispatch` 또는 matrix 계약 누락으로 non-zero exit.

- [ ] **Step 2: 수동 최신 develop 입력과 공통 concurrency 추가**

`release-develop.yml` trigger를 다음과 같이 바꾼다.

```yaml
on:
  push:
    branches: ["develop"]
  workflow_dispatch:
    inputs:
      release-services:
        description: "다시 테스트·발행할 서비스(쉼표 구분)"
        required: true
        type: string
      manifest-services:
        description: "현재 develop 매니페스트도 적용할 서비스(쉼표 구분)"
        required: false
        type: string
      confirmation:
        description: "실행하려면 RELEASE 입력"
        required: true
        type: string

concurrency:
  group: release-develop
  cancel-in-progress: false
```

과거 ref 입력은 추가하지 않는다. 저장소 기본 브랜치가 `develop`이므로 수동 실행도 최신
`develop` workflow와 SHA를 사용한다.

- [ ] **Step 3: changed-files를 코드·Config profile·서비스 manifest·공통 overlay로 분리**

기존 `application_manifests` group을 제거한다. `shared_build`에는 애플리케이션 산출물에 직접
영향을 주는 루트 Gradle, Dockerfile, wrapper와 `common-module/**`만 남긴다. workflow와 스크립트는
아래 `pipeline` group으로 옮긴다.

```yaml
            config_ai:
              - config/src/main/resources/configs/ai-service.yml
            config_user:
              - config/src/main/resources/configs/user-service.yml
            config_product:
              - config/src/main/resources/configs/product-service.yml
            config_order:
              - config/src/main/resources/configs/order-service.yml
            config_payment:
              - config/src/main/resources/configs/payment-service.yml
            config_admin:
              - config/src/main/resources/configs/admin-service.yml
            config_shared:
              - config/src/main/java/**
              - config/src/main/resources/application.yml
              - config/src/main/resources/configs/application.yml
              - config/build.gradle
            manifest_config:
              - k8s/base/platform/config/**
            manifest_discovery:
              - k8s/base/platform/discovery/**
            manifest_user:
              - k8s/base/services/user/**
            manifest_product:
              - k8s/base/services/product/**
            manifest_order:
              - k8s/base/services/order/**
            manifest_payment:
              - k8s/base/services/payment/**
            manifest_settlement:
              - k8s/base/services/settlement/**
            manifest_admin:
              - k8s/base/services/admin/**
            manifest_ai:
              - k8s/base/services/ai/**
            manifest_apigateway:
              - k8s/base/gateway/**
            manifest_all:
              - k8s/overlays/ec2-kubeadm/applications/**
            pipeline:
              - .github/workflows/ci.yml
              - .github/workflows/ci-main.yml
              - .github/workflows/reusable-build.yml
              - .github/workflows/reusable-docker-build.yml
              - .github/workflows/release-develop.yml
              - .github/workflows/reusable-kubernetes-deploy.yml
              - .github/workflows/cd-selfhosted-kubernetes.yml
              - .github/workflows/cd-selfhosted-compose.yml
              - scripts/plan-release-targets.sh
              - scripts/test-plan-release-targets.sh
              - scripts/validate-k8s-cd-workflow.sh
```

`config/**`는 계속 `CONFIG_CHANGED=true`로 config 이미지를 발행한다. profile group은 어떤 소비자를
재시작할지 추가로 계산한다. `settlement-service.yml`은 다음 CronJob 실행 때 읽으므로 장기 실행
소비자 재시작 matrix에는 넣지 않는다.

- [ ] **Step 4: planning output과 수동 env를 reusable CD까지 전달**

planning outputs:

```yaml
      manifest_matrix: ${{ steps.plan.outputs.manifest_matrix }}
      deploy_matrix: ${{ steps.plan.outputs.deploy_matrix }}
      config_consumer_matrix: ${{ steps.plan.outputs.config_consumer_matrix }}
      apply_all_manifests: ${{ steps.plan.outputs.apply_all_manifests }}
```

planner env:

```yaml
          AI_MANIFEST_CHANGED: ${{ steps.changed.outputs.manifest_ai_any_changed }}
          CONFIG_MANIFEST_CHANGED: ${{ steps.changed.outputs.manifest_config_any_changed }}
          DISCOVERY_MANIFEST_CHANGED: ${{ steps.changed.outputs.manifest_discovery_any_changed }}
          USER_MANIFEST_CHANGED: ${{ steps.changed.outputs.manifest_user_any_changed }}
          PRODUCT_MANIFEST_CHANGED: ${{ steps.changed.outputs.manifest_product_any_changed }}
          ORDER_MANIFEST_CHANGED: ${{ steps.changed.outputs.manifest_order_any_changed }}
          PAYMENT_MANIFEST_CHANGED: ${{ steps.changed.outputs.manifest_payment_any_changed }}
          SETTLEMENT_MANIFEST_CHANGED: ${{ steps.changed.outputs.manifest_settlement_any_changed }}
          ADMIN_MANIFEST_CHANGED: ${{ steps.changed.outputs.manifest_admin_any_changed }}
          APIGATEWAY_MANIFEST_CHANGED: ${{ steps.changed.outputs.manifest_apigateway_any_changed }}
          ALL_APPLICATION_MANIFESTS_CHANGED: ${{ steps.changed.outputs.manifest_all_any_changed }}
          AI_CONFIG_CHANGED: ${{ steps.changed.outputs.config_ai_any_changed }}
          USER_CONFIG_CHANGED: ${{ steps.changed.outputs.config_user_any_changed }}
          PRODUCT_CONFIG_CHANGED: ${{ steps.changed.outputs.config_product_any_changed }}
          ORDER_CONFIG_CHANGED: ${{ steps.changed.outputs.config_order_any_changed }}
          PAYMENT_CONFIG_CHANGED: ${{ steps.changed.outputs.config_payment_any_changed }}
          ADMIN_CONFIG_CHANGED: ${{ steps.changed.outputs.config_admin_any_changed }}
          SHARED_CONFIG_CHANGED: ${{ steps.changed.outputs.config_shared_any_changed }}
          PIPELINE_CHANGED: ${{ steps.changed.outputs.pipeline_any_changed }}
          MANUAL_CONFIRMATION: ${{ github.event_name == 'workflow_dispatch' && inputs.confirmation || '' }}
          MANUAL_RELEASE_SERVICES: ${{ github.event_name == 'workflow_dispatch' && inputs.release-services || '' }}
          MANUAL_MANIFEST_SERVICES: ${{ github.event_name == 'workflow_dispatch' && inputs.manifest-services || '' }}
```

planner는 `USER_CONFIG_CHANGED`, `PRODUCT_CONFIG_CHANGED`, `ORDER_CONFIG_CHANGED`,
`PAYMENT_CONFIG_CHANGED`, `ADMIN_CONFIG_CHANGED`, `AI_CONFIG_CHANGED`를 각각 같은 이름의 장기 실행
서비스에 매핑한다. `SHARED_CONFIG_CHANGED=true`이면
`user-service,product-service,order-service,payment-service,admin-service,ai-service,apigateway`
전체를 `config_consumer_matrix`에 넣는다.

deploy 호출:

```yaml
    with:
      release-manifest: ${{ needs.collect-release-manifest.outputs.release_manifest }}
      manifest-matrix: ${{ needs.planning.outputs.manifest_matrix }}
      deploy-matrix: ${{ needs.planning.outputs.deploy_matrix }}
      config-consumer-matrix: ${{ needs.planning.outputs.config_consumer_matrix }}
      apply-all-manifests: ${{ needs.planning.outputs.apply_all_manifests == 'true' }}
```

- [ ] **Step 5: workflow YAML과 planner/validator 검증**

Run:

```bash
bash scripts/test-plan-release-targets.sh
bash scripts/validate-k8s-cd-workflow.sh
ruby -e 'require "yaml"; YAML.load_file(".github/workflows/release-develop.yml", aliases: true)'
```

Expected: all exit 0.

- [ ] **Step 6: Release orchestration 보완 커밋**

```bash
git add .github/workflows/release-develop.yml scripts/validate-k8s-cd-workflow.sh
git commit -m "feat: 최신 develop 선택 Release 경로 추가"
```

---

### Task 10: reusable CD를 서비스별 runtime package 순차 배포로 변경

**Files:**
- Modify: `.github/workflows/reusable-kubernetes-deploy.yml`
- Modify: `scripts/validate-k8s-cd-workflow.sh`

**Interfaces:**
- Consumes: `release-manifest`, `manifest-matrix`, `deploy-matrix`,
  `config-consumer-matrix`, `apply-all-manifests`
- Produces: 대상 workload만 순차 apply/set-image/rollout하며 실패 시 실제 변경 대상만 rollback

- [ ] **Step 1: 전체 overlay apply를 금지하는 실패 계약 추가**

validator에서 새 input과 서비스 package helper를 요구한다.

```bash
application_patterns+=(
  '^[[:space:]]+manifest-matrix:$'
  '^[[:space:]]+deploy-matrix:$'
  '^[[:space:]]+config-consumer-matrix:$'
  '^[[:space:]]+apply-all-manifests:$'
  'package_for\(\)'
  'create_runtime_package\(\)'
  'deploy_service\(\)'
  'DEPLOY_MATRIX:'
  'MANIFEST_MATRIX:'
)
```

전체 applications overlay를 runtime resource로 사용하는 기존 계약을 금지한다.

```bash
forbid_pattern "$APPLICATION_WORKFLOW" '^[[:space:]]+- ../applications$' \
  "application CD must not apply the whole applications overlay"
```

Run:

```bash
bash scripts/validate-k8s-cd-workflow.sh
```

Expected: 새 input/helper 누락 또는 전체 overlay pattern으로 non-zero exit.

- [ ] **Step 2: workflow_call input과 matrix allowlist 검증 추가**

```yaml
      manifest-matrix:
        required: true
        type: string
      deploy-matrix:
        required: true
        type: string
      config-consumer-matrix:
        required: true
        type: string
      apply-all-manifests:
        required: true
        type: boolean
```

`Validate immutable release manifest` 단계에서 네 matrix 모두 JSON array인지, 중복이 없는지,
모든 값이 기존 `allowed_services`에 포함되는지 확인한다.

```bash
for matrix in "$MANIFEST_MATRIX" "$DEPLOY_MATRIX" "$CONFIG_CONSUMER_MATRIX"; do
  jq -e 'type == "array" and (length == (unique | length))' <<< "$matrix" >/dev/null
  while IFS= read -r service; do
    jq -e --arg service "$service" 'index($service) != null' <<< "$allowed_services" >/dev/null
  done < <(jq -r '.[]' <<< "$matrix")
done
```

- [ ] **Step 3: 서비스와 Kustomize package 매핑 구현**

```bash
package_for() {
  case "$1" in
    config) echo "../../../base/platform/config" ;;
    discovery) echo "../../../base/platform/discovery" ;;
    user-service) echo "../../../base/services/user" ;;
    product-service) echo "../../../base/services/product" ;;
    order-service) echo "../../../base/services/order" ;;
    payment-service) echo "../../../base/services/payment" ;;
    settlement-service) echo "../../../base/services/settlement" ;;
    admin-service) echo "../../../base/services/admin" ;;
    ai-service) echo "../../../base/services/ai" ;;
    apigateway) echo "../../../base/gateway" ;;
    *) echo "Unknown service package: $1" >&2; return 1 ;;
  esac
}
```

runtime directory는 `k8s/overlays/ec2-kubeadm/.runtime-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}` 하나를
재사용한다. 서비스가 바뀔 때 기존 `kustomization.yaml`을 덮어써서 동시에 여러 workload를
resources에 넣지 않는다.

- [ ] **Step 4: 현재 또는 새 immutable image를 보존하는 runtime package 생성**

```bash
create_runtime_package() {
  local service="$1"
  local image="$2"
  local package

  package="$(package_for "$service")"
  mkdir -p "$runtime_overlay"
  cat > "$runtime_overlay/kustomization.yaml" <<EOF
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
namespace: prompthub
resources:
  - $package
labels:
  - pairs:
      app.kubernetes.io/instance: ec2-kubeadm
    includeSelectors: false
    includeTemplates: true
images:
EOF
  append_image_override "$service" "$image"
}
```

`current_or_base_image`는 release manifest에 해당 서비스가 있으면 그 `immutableRef`, 없으면 현재
workload image, workload가 없으면 Git base digest 순서로 선택한다.

- [ ] **Step 5: 한 서비스 apply와 rollout을 하나의 함수로 묶기**

```bash
deploy_service() {
  local service="$1"
  local apply_manifest="$2"
  local image

  active_service="$service"
  image="$(current_or_base_image "$service")"

  if ! workload_exists "$service"; then
    apply_manifest=true
  fi

  if [ "$apply_manifest" = "true" ]; then
    create_runtime_package "$service" "$image"
    kubectl kustomize "$runtime_overlay" >/dev/null
    kubectl apply --dry-run=server -k "$runtime_overlay"
    kubectl apply -k "$runtime_overlay"
    track_manifest_deployment_changes
  elif jq -e --arg service "$service" 'has($service)' <<< "$RELEASE_MANIFEST" >/dev/null; then
    set_workload_image "$service" "$image"
  fi

  if [ "$service" = "settlement-service" ]; then
    verify_settlement_image "$image"
  else
    kubectl rollout status deployment/"$service" -n "$NAMESPACE" --timeout=10m
  fi
}
```

`workload_exists`는 settlement만 CronJob을 확인하고 나머지는 Deployment를 확인한다.

```bash
workload_exists() {
  if [ "$1" = "settlement-service" ]; then
    kubectl get cronjob "$settlement_cronjob" -n "$NAMESPACE" >/dev/null 2>&1
  else
    kubectl get deployment "$1" -n "$NAMESPACE" >/dev/null 2>&1
  fi
}
```

기존 전체 `kubectl apply -k "$runtime_overlay"`와 전체 `deployment_order` status loop를 제거한다.
대신 `release_order`를 순회하면서 `DEPLOY_MATRIX`에 포함된 서비스만 호출한다.

```bash
for service in "${release_order[@]}"; do
  jq -e --arg service "$service" 'index($service) != null' <<< "$DEPLOY_MATRIX" >/dev/null || continue
  apply_manifest=false
  if [ "$APPLY_ALL_MANIFESTS" = "true" ] ||
    jq -e --arg service "$service" 'index($service) != null' <<< "$MANIFEST_MATRIX" >/dev/null; then
    apply_manifest=true
  fi
  deploy_service "$service" "$apply_manifest"
done
```

- [ ] **Step 6: Config 소비자도 대상 matrix만 순차 재시작**

primary deploy가 끝난 뒤 `CONFIG_CONSUMER_MATRIX`만 순회한다. 이미
`updated_deployments`에 포함된 서비스는 건너뛴다.

```bash
for deployment in "${config_consumers[@]}"; do
  jq -e --arg service "$deployment" \
    'index($service) != null' <<< "$CONFIG_CONSUMER_MATRIX" >/dev/null || continue
  was_updated "$deployment" && continue
  kubectl rollout restart deployment/"$deployment" -n "$NAMESPACE"
  updated_deployments+=("$deployment")
  kubectl rollout status deployment/"$deployment" -n "$NAMESPACE" --timeout=10m
done
```

- [ ] **Step 7: 정적 검증과 렌더링 통과 확인**

Run:

```bash
bash scripts/validate-k8s-cd-workflow.sh
bash scripts/validate-k8s-manifests.sh
kubectl kustomize k8s/overlays/ec2-kubeadm/applications >/dev/null
ruby -e 'require "yaml"; YAML.load_file(".github/workflows/reusable-kubernetes-deploy.yml", aliases: true)'
```

Expected: all exit 0.

- [ ] **Step 8: 서비스별 CD 커밋**

```bash
git add .github/workflows/reusable-kubernetes-deploy.yml scripts/validate-k8s-cd-workflow.sh
git commit -m "feat: Kubernetes 서비스를 변경 단위로 순차 배포"
```

---

### Task 11: rollout 실패 진단을 rollback 전에 보존

**Files:**
- Modify: `.github/workflows/reusable-kubernetes-deploy.yml`
- Modify: `scripts/validate-k8s-cd-workflow.sh`

**Interfaces:**
- Consumes: `active_service`, namespace workload 상태
- Produces: 원래 오류를 유지하면서 Deployment, ReplicaSet, Pod, Event, node 자원 진단 로그

- [ ] **Step 1: 진단 계약 validator를 실패하도록 추가**

```bash
application_patterns+=(
  'dump_rollout_diagnostics\(\)'
  'kubectl get deployment "\$service"'
  'kubectl get rs'
  'kubectl get pods'
  'kubectl get events'
  'kubectl describe node'
)
```

Run:

```bash
bash scripts/validate-k8s-cd-workflow.sh
```

Expected: `dump_rollout_diagnostics` 누락으로 non-zero exit.

- [ ] **Step 2: best-effort 진단 함수 구현**

```bash
dump_rollout_diagnostics() {
  local service="${1:-}"
  [ -n "$service" ] || return 0

  echo "=== rollout diagnostics: $service ==="
  kubectl get deployment "$service" -n "$NAMESPACE" -o wide || true
  kubectl describe deployment "$service" -n "$NAMESPACE" || true
  kubectl get rs -n "$NAMESPACE" \
    -l "app.kubernetes.io/name=$service" -o wide || true
  kubectl get pods -n "$NAMESPACE" \
    -l "app.kubernetes.io/name=$service" -o wide || true
  kubectl get events -n "$NAMESPACE" --sort-by=.lastTimestamp || true
  while IFS= read -r node; do
    kubectl describe node "$node" | sed -n '/Allocated resources:/,/Events:/p' || true
  done < <(kubectl get nodes -o name 2>/dev/null)
}
```

- [ ] **Step 3: rollback 진입 직후 상태 변경 전에 진단 호출**

`rollback_deployments`에서 `trap - ERR`, `set +e` 직후 snapshot/undo보다 먼저 호출한다.

```bash
local failed_service="${active_service:-}"
dump_rollout_diagnostics "$failed_service"
track_manifest_deployment_changes
echo "배포 실패: 이번 실행에서 변경한 workload만 rollback 합니다."
```

진단 명령은 모두 `|| true`이므로 원래 exit code를 보존하고 rollback을 막지 않는다.

- [ ] **Step 4: validator와 YAML 파싱 확인**

```bash
bash scripts/validate-k8s-cd-workflow.sh
ruby -e 'require "yaml"; YAML.load_file(".github/workflows/reusable-kubernetes-deploy.yml", aliases: true)'
```

Expected: both exit 0.

- [ ] **Step 5: 진단 보완 커밋**

```bash
git add .github/workflows/reusable-kubernetes-deploy.yml scripts/validate-k8s-cd-workflow.sh
git commit -m "feat: Kubernetes rollout 실패 진단 추가"
```

---

### Task 12: 운영 문서와 전체 회귀 검증 갱신

**Files:**
- Modify: `k8s/README.md`
- Modify: `docs/architecture/kubernetes.md`
- Modify: `settlement-service/docs/architecture/deployment-ci-cd.md`
- Modify: `settlement-service/docs/superpowers/plans/2026-07-27-ci-cd-pipeline-responsibility-separation-implementation.md`
- Verify: `.github/workflows/release-develop.yml`
- Verify: `.github/workflows/reusable-kubernetes-deploy.yml`

**Interfaces:**
- Produces: 자동 서비스별 배포와 최신 develop 수동 재배포 운영 절차 및 최종 검증 증거

- [ ] **Step 1: 문서에 자동·수동 배포 예시 기록**

세 운영 문서에 다음 계약을 같은 용어로 기록한다.

```text
k8s/base/services/ai/** 변경
  → image build 없음
  → manifest_matrix=["ai-service"]
  → 현재 ai-service digest를 유지한 서비스별 runtime package 적용
  → ai-service rollout만 확인

최신 develop의 #590 변경 재반영
  release-services=config,ai-service
  manifest-services=ai-service
  confirmation=RELEASE
  → config와 ai-service 테스트·이미지 발행
  → config rollout 성공 후 ai-service manifest+image rollout
```

기존 PR 590 Actions의 `Re-run`은 과거 workflow를 사용하므로 이 절차가 아니라는 경고도 명시한다.

- [ ] **Step 2: planner, shell, workflow 계약 검증**

```bash
bash -n scripts/plan-release-targets.sh
bash -n scripts/test-plan-release-targets.sh
bash -n scripts/validate-k8s-cd-workflow.sh
bash scripts/test-plan-release-targets.sh
bash scripts/validate-k8s-cd-workflow.sh
```

Expected: all exit 0.

- [ ] **Step 3: Kubernetes 정적 계약과 렌더링 검증**

```bash
bash scripts/validate-k8s-manifests.sh
bash scripts/validate-k8s-secret-contract.sh
kubectl kustomize k8s/overlays/ec2-kubeadm/applications >/dev/null
kubectl apply --dry-run=client -k k8s/overlays/ec2-kubeadm/applications >/dev/null
```

Expected: all exit 0. 로컬 Kubernetes API server가 없으면 server dry-run은 실행하지 않고 PR 본문에
미검증 사실을 기록한다.

- [ ] **Step 4: workflow YAML 파싱과 금지 패턴 확인**

```bash
ruby -e '
  require "yaml"
  %w[
    .github/workflows/release-develop.yml
    .github/workflows/reusable-kubernetes-deploy.yml
  ].each { |path| YAML.load_file(path, aliases: true) }
'

rg -n 'application.manifests.changed|APPLICATION_MANIFESTS_CHANGED|../applications' \
  .github/workflows/release-develop.yml \
  .github/workflows/reusable-kubernetes-deploy.yml \
  scripts/plan-release-targets.sh \
  scripts/validate-k8s-cd-workflow.sh
```

Expected: YAML parsing exits 0; `rg` returns no executable legacy contract.

- [ ] **Step 5: 변경 범위와 기존 사용자 변경 보존 확인**

```bash
git status --short
git diff --check
git diff --stat origin/develop...HEAD
git log --oneline origin/develop..HEAD
```

Expected: 기존 사용자 변경 4개는 unstaged로 남고, 보완 커밋은 planner/test, Release workflow,
reusable CD, validator와 운영 문서만 포함한다.

- [ ] **Step 6: 문서 커밋**

```bash
git add \
  k8s/README.md \
  docs/architecture/kubernetes.md \
  settlement-service/docs/architecture/deployment-ci-cd.md \
  settlement-service/docs/superpowers/plans/2026-07-27-ci-cd-pipeline-responsibility-separation-implementation.md
git commit -m "docs: 서비스별 Kubernetes 배포 절차 보완"
```

- [ ] **Step 7: 구현 완료 후 수동 Release 입력 전달**

PR 588이 최신 `develop`을 반영한 상태로 merge되고 Release pipeline 변경-only 실행이 종료된 뒤,
아래 입력으로 `Release - Develop`을 수동 실행한다. 이 단계는 코드 구현·PR merge가 완료된 후
별도 사용자 승인을 받아 수행한다.

```text
release-services: config,ai-service
manifest-services: ai-service
confirmation: RELEASE
```
