# CI/CD Pipeline Responsibility Separation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `develop` 머지 커밋의 영향 모듈을 다시 검증하고 GHCR digest를 발행한 뒤, 같은 GitHub Actions 실행에서 그 digest만 Kubernetes에 자동 배포한다.

**Architecture:** 기존 PR `ci.yml`은 그대로 둔다. 신규 `release-develop.yml`이 변경 분석, Release CI Gate, 이미지 발행과 release manifest 수집을 담당하고, 신규 `reusable-kubernetes-deploy.yml`을 `needs`로 호출한다. 기존 `cd-selfhosted-kubernetes.yml`은 상태 저장 인프라와 Ingress 수동 배포만 남긴다.

**Tech Stack:** GitHub Actions reusable workflows, Bash 3.2+, jq, Docker Buildx, GHCR, Kubernetes, Kustomize

## Global Constraints

- 연결 이슈는 `#584 (이슈)`이고 작업 브랜치는 `infra/#584-ci-cd-pipeline-separation`이다.
- 기존 `.github/workflows/ci.yml`의 PR trigger, 권한, 변경 감지와 CI Gate 동작을 수정하지 않는다.
- `.github/workflows/ci-main.yml`은 GHCR 입력명만 바꾸고 검증 정책은 유지한다.
- Release CI는 영향 모듈 전체 테스트가 성공한 뒤에만 이미지를 발행한다.
- Kubernetes 배포 입력은 tag를 다시 조합하지 않고 Docker build 결과의 `immutableRef`를 사용한다.
- Release CI는 `latest`를 발행하지 않는다. 수동 Compose rollback 호출만 `publish-latest: true`를 사용한다.
- `grpc/user/**` 변경은 `user-service`와 `ai-service`를 함께 처리한다.
- 애플리케이션 매니페스트만 바뀌면 새 이미지를 만들지 않고 현재 workload 이미지를 보존한다.
- `settlement-service`는 Deployment가 아니라 `CronJob/settlement-weekly` 이미지로 관리한다.
- 상태 저장 인프라와 Ingress는 `workflow_dispatch`와 확인 문자열 `DEPLOY`를 유지한다.
- 기존 미커밋 변경 4개는 각 task의 stage와 commit에서 제외한다.
- workflow와 validator는 저장소 루트의 담당 범위 밖 파일이다. 사용자가 `#584 (이슈)` 구현 범위로 변경을 승인했다.

---

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

Consumes boolean environment variables ending in `_CHANGED` and writes these compact JSON/string outputs to `$GITHUB_OUTPUT`:

```text
test_matrix=["service-a","service-b"]
image_matrix=["service-a","service-b"]
deploy=true|false
application_manifests_changed=true|false
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
application-manifests-changed:
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
