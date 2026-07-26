# CI/CD 파이프라인 책임 분리 설계

- 작성일: 2026-07-26
- 상태: 설계 승인 완료, 구현 계획 작성 완료
- 연결 이슈: `#584 (이슈)`
- 대상: GitHub Actions, GHCR, Kubernetes 애플리케이션 배포

## 1. 배경

현재 `develop` 대상 PR은 `.github/workflows/ci.yml`에서 변경 모듈의 Gradle 빌드와 테스트를
검증한다. PR이 머지되어 `develop`에 push되면
`.github/workflows/cd-selfhosted-kubernetes.yml`이 별도로 실행되어 Docker 이미지를 빌드하고
GHCR에 push한 뒤 Kubernetes에 배포한다.

두 실행 사이에는 GitHub Actions 의존 관계가 없다. Kubernetes CD는 PR CI 결과를 직접 확인하지
않으며, 이미지 빌드에 사용하는 `bootJar` 작업도 테스트를 실행하지 않는다. 따라서 CI가 검증한 대상과
실제 배포 이미지가 동일하다는 점이 파이프라인 구조로 보장되지 않는다.

## 2. 목표

- `develop` 머지 커밋을 다시 빌드하고 테스트한 뒤에만 이미지를 발행한다.
- Docker 이미지 빌드와 GHCR push를 CI 책임으로 옮긴다.
- CD는 CI가 발행한 정확한 Git SHA 이미지 또는 그 digest만 배포한다.
- CI, 이미지 발행, CD를 하나의 실행 의존 그래프로 연결한다.
- 변경 영향이 있는 모듈만 테스트하고 이미지를 발행하며 배포한다.
- Kubernetes 애플리케이션 매니페스트만 바뀌면 이미지를 다시 만들지 않는다.
- 현재 순차 rollout, health 확인, Config 소비자 재시작과 rollback 동작을 유지한다.
- 상태 저장 인프라와 Ingress의 수동 배포 경계를 유지한다.

## 3. 비목표

- 기존 PR CI의 trigger, 변경 감지 기준이나 통과 조건을 바꾸지 않는다.
- `main` 대상 `ci-main.yml`의 검증 정책을 바꾸지 않는다. GHCR 입력명 변경에 따른 호출부만 수정한다.
- Compose CD의 자동 trigger를 다시 활성화하지 않는다.
- 운영 환경용 승인 배포나 release/tag 전략을 추가하지 않는다.
- Kubernetes 상태 저장 인프라, Secret, Ingress 배포 방식을 바꾸지 않는다.
- 애플리케이션 코드나 테스트 코드를 변경하지 않는다.

## 4. 선택한 구조

CI와 CD를 서로 독립된 GitHub Actions 실행으로 연결하지 않는다. `develop` push를 받는
`release-develop.yml`을 진입점으로 두고, 재사용 워크플로를 `needs`로 연결한다.

```text
develop push
  → 변경 영향 분석
  → 영향 모듈 빌드·테스트
  → Release CI Gate
  → 영향 모듈 이미지 빌드·GHCR push
  → 서비스별 image digest 수집
  → Kubernetes 애플리케이션 CD
```

이 구조는 별도 workflow run 사이의 artifact 전달과 별도 token 호출이 필요 없다. 같은 실행에서
이미지 발행 job이 만든 digest metadata를 수집해 CD에 전달한다. 테스트나 이미지 발행이 실패하면
GitHub Actions의 job 의존 관계에 따라 CD가 시작되지 않는다.

### 4.1 검토한 대안

#### 별도 워크플로를 `workflow_run`으로 연결

CI와 CD 실행이 Actions 화면에서 분리되는 장점이 있다. 반면 변경 서비스 목록과 이미지 식별자를
artifact 또는 API로 전달해야 하고, `head_sha`, 권한, 재실행과 중복 이벤트 처리가 복잡해진다.

#### CI가 API로 CD를 호출

`workflow_dispatch`나 `repository_dispatch` payload로 값을 직접 전달할 수 있다. 별도 credential,
호출 실패 복구와 중복 배포 방지가 필요해 현재 범위보다 크다.

## 5. 파일 경계

| 파일 | 변경 후 책임 |
| --- | --- |
| `.github/workflows/ci.yml` | 기존 `develop` 대상 PR 빌드·테스트. 변경하지 않는다. |
| `.github/workflows/ci-main.yml` | 기존 검증 동작 유지. Docker workflow의 GHCR 입력명만 변경 |
| `.github/workflows/reusable-build.yml` | PR CI와 Release CI가 함께 쓰는 모듈별 Gradle 빌드·테스트 |
| `.github/workflows/release-develop.yml` | 신규. 변경 감지, Release CI Gate, 이미지 발행, CD 호출 |
| `.github/workflows/reusable-docker-build.yml` | CI가 호출하는 모듈별 이미지 빌드·GHCR push |
| `.github/workflows/reusable-kubernetes-deploy.yml` | 신규. 애플리케이션 배포, rollout 확인과 rollback |
| `.github/workflows/cd-selfhosted-kubernetes.yml` | 상태 저장 인프라와 Ingress 수동 배포만 담당 |
| `.github/workflows/cd-selfhosted-compose.yml` | 기존 수동 rollback 동작 유지. GHCR 입력명과 `latest` 호환 입력만 변경 |

`cd-selfhosted-kubernetes.yml`의 자동 `develop` push trigger, 애플리케이션 변경 감지,
`parallel-build-and-push`, `deploy-applications`는 제거한다. 애플리케이션 배포 구현은
`reusable-kubernetes-deploy.yml`로 이동한다.

## 6. PR CI

`.github/workflows/ci.yml`은 현재 동작을 유지한다.

```text
develop 대상 PR
  → 변경 모듈 감지
  → 해당 모듈 Gradle build
  → CI Gate
```

PR CI는 Docker 이미지 빌드, GHCR 로그인·push, Kubernetes 배포를 수행하지 않는다. 권한도
`contents: read`를 유지한다.

## 7. Release CI

### 7.1 실행 조건

`release-develop.yml`은 `develop` push에서 실행한다. PR merge와 허용된 직접 push 모두 같은
Release CI를 거친다.

```yaml
on:
  push:
    branches: [develop]
```

동시 실행은 `release-develop` concurrency group으로 직렬화하고 `cancel-in-progress: false`를
사용한다. 앞선 배포를 중간에 취소해 부분 rollout 상태를 남기지 않는다.

### 7.2 변경 분석 출력

planning job은 다음 출력을 만든다.

| 출력 | 의미 |
| --- | --- |
| `test_matrix` | Gradle 빌드·테스트 대상 서비스 JSON 배열 |
| `image_matrix` | Docker 이미지 빌드·push 대상 서비스 JSON 배열 |
| `deploy` | 애플리케이션 CD 실행 여부 |
| `application_manifests_changed` | 자동 관리 애플리케이션 매니페스트 변경 여부 |

서비스 디렉터리가 바뀌면 해당 서비스만 `test_matrix`와 `image_matrix`에 넣는다.

```text
config
discovery
user-service
ai-service
product-service
order-service
payment-service
settlement-service
admin-service
apigateway
```

`grpc/user/**`가 바뀌면 계약 제공자인 `user-service`와 소비자인 `ai-service`를 함께 포함한다.

다음 공통 입력이 바뀌면 모든 애플리케이션을 테스트하고 이미지를 다시 만든다.

- `common-module/**`
- 루트 `Dockerfile`
- 루트 Gradle build, settings, wrapper와 `gradle/**`
- `reusable-build.yml`
- `reusable-docker-build.yml`
- `release-develop.yml`

다음 애플리케이션 매니페스트만 바뀌면 `test_matrix`와 `image_matrix`는 비우고
`application_manifests_changed=true`, `deploy=true`로 설정한다.

- `k8s/base/platform/**`
- `k8s/base/services/**`
- `k8s/base/gateway/**`
- `k8s/overlays/ec2-kubeadm/applications/**`
- `reusable-kubernetes-deploy.yml`

Storage, PostgreSQL, Redis, Kafka와 Ingress 전용 변경은 자동 애플리케이션 CD 대상으로 분류하지 않는다.
문서처럼 빌드와 배포에 영향이 없는 변경은 모든 matrix를 비우고 `deploy=false`로 설정한다.

### 7.3 테스트 Gate

`test_matrix`의 모든 모듈은 `reusable-build.yml`을 사용해 병렬로 빌드하고 테스트한다. 한 모듈이라도
실패하거나 취소되면 Release CI Gate가 실패한다.

이미지 발행 job은 개별 테스트 job이 아니라 Release CI Gate 전체에 의존한다. 일부 모듈 테스트만
통과한 상태에서 그 모듈의 이미지가 먼저 registry에 올라가지 않게 한다.

`test_matrix`가 빈 매니페스트 전용 실행에서는 test job의 `skipped`를 정상 결과로 처리한다. planning
실패, test `failure`와 `cancelled`만 Gate 실패로 판단한다.

### 7.4 이미지 발행

Release CI Gate를 통과한 뒤 `image_matrix`의 이미지를 병렬로 빌드한다. registry는 GHCR이며
이미지 tag는 전체 `github.sha`를 사용한다.

```text
ghcr.io/<owner>/prompthub-<service>:<full-git-sha>
```

Release CI는 `latest`를 발행하거나 배포 입력으로 사용하지 않는다. 전체 Git SHA tag는 source
traceability를 제공하고, 실제 불변 배포 식별자는 해당 실행의 build 결과 digest를 사용한다.

`reusable-docker-build.yml`의 실제 registry 입력 이름은 잘못된 `ecr-repository`에서
`ghcr-repository`로 바꾼다. `packages: write`와 GHCR 로그인은 Release CI의 이미지 발행 경로에만
허용한다.

수동 Compose rollback은 현재 `docker-compose.yml`의 `latest` reference를 유지한다. 재사용 Docker
workflow는 `publish-latest` 입력이 명시된 Compose 호출에서만 호환용 `latest` tag를 함께 발행한다.
Kubernetes Release CI는 이 입력을 사용하지 않는다.

### 7.5 Release manifest

각 이미지 발행 job은 다음 metadata를 서비스별 artifact로 남긴다.

```json
{
  "service": "payment-service",
  "imageUri": "ghcr.io/<owner>/prompthub-payment-service:<full-git-sha>",
  "digest": "sha256:<digest>",
  "immutableRef": "ghcr.io/<owner>/prompthub-payment-service@sha256:<digest>"
}
```

모든 이미지 발행이 성공하면 별도 job이 metadata artifact를 하나의 `release_manifest` JSON으로
합친다. CD는 tag를 다시 조합하지 않고 이 manifest의 `immutableRef`를 배포한다. 매니페스트 전용
실행의 `release_manifest`는 빈 객체다.

## 8. 애플리케이션 CD

### 8.1 입력

`reusable-kubernetes-deploy.yml`은 다음 값을 명시적으로 받는다.

| 입력 | 의미 |
| --- | --- |
| `release-manifest` | 서비스별 image URI, digest와 immutable reference |
| `application-manifests-changed` | 애플리케이션 매니페스트 적용 여부 |

CD는 저장소 변경을 다시 감지하지 않는다. Release CI가 만든 `release-manifest`를 단일 입력으로
사용해 이미지 발행 대상과 배포 대상이 달라지지 않게 한다.

### 8.2 권한과 사전 조건

배포 job은 기존 `[self-hosted, linux, deploy]` runner를 사용한다. `KUBECONFIG`, cluster context,
node Ready 상태, 상태 저장 StatefulSet과 필수 Secret을 확인한다.

CD에는 GHCR push 권한과 Docker 로그인 단계가 없다. Kubernetes image pull은 기존
`ghcr-pull-secret`이 담당한다.

### 8.3 이미지가 바뀐 배포

`release-manifest`에 포함된 서비스만 `immutableRef`로 갱신한다. 상시 서비스는 기존 release
order대로 Deployment를 순차 갱신한다. `settlement-service`는 `CronJob/settlement-weekly`의
Job template 이미지만 갱신한다.

Config 이미지가 바뀌고 애플리케이션 매니페스트가 바뀌지 않았으면, Config rollout 뒤 이미지가
바뀌지 않은 `user-service`, `product-service`, `order-service`, `payment-service`,
`admin-service`, `ai-service`, `apigateway` Deployment를 순차 재시작한다.

### 8.4 매니페스트만 바뀐 배포

`release-manifest`가 비어 있으면 새 이미지를 적용하지 않는다. runtime overlay를 만들 때 현재
클러스터의 각 Deployment와 settlement CronJob 이미지 reference를 읽어 image override로 사용한다.
클러스터에 처음 생성하는 workload는 Git에 고정된 base digest를 사용한다.

서비스 코드와 애플리케이션 매니페스트가 함께 바뀌면 `release-manifest`에 포함된 서비스는 새
`immutableRef`를 사용하고, 포함되지 않은 기존 workload는 현재 클러스터 이미지를 보존한다. 새로
생성하는 workload만 base digest를 사용한다.

이를 통해 Pod template, resource, probe나 Service 선언만 바뀐 배포에서 실행 중인 애플리케이션
이미지가 의도치 않게 바뀌지 않게 한다.

매니페스트는 `kubectl kustomize`, server-side dry-run을 통과한 뒤 적용한다. Storage,
상태 저장 인프라와 Ingress는 applications overlay에 포함하지 않는다.

## 9. 실패, rollback과 재실행

| 실패 지점 | 처리 |
| --- | --- |
| 변경 모듈 빌드·테스트 실패 | Release CI Gate 실패, 이미지 발행과 CD 미실행 |
| 이미지 빌드·GHCR push 실패 | CD 미실행 |
| 클러스터·Secret 사전 확인 실패 | workload 변경 전 종료 |
| manifest dry-run 실패 | 실제 apply 전 종료 |
| Deployment rollout 실패 | 이번 실행에서 바꾼 Deployment만 역순 rollback |
| settlement CronJob 갱신 실패 | 이전 CronJob 이미지 또는 부재 상태로 복구 |

현재 CD의 Deployment template snapshot, 신규 Deployment 추적, Config rollback 후 소비자 재시작과
settlement CronJob 복구 로직을 유지한다.

같은 GitHub Actions 실행을 재시도하면 같은 Git SHA tag로 이미지를 다시 검증·발행하고 그 실행에서
확정된 digest metadata를 사용한다. CD는 workload의 현재 immutable image reference가 목표와 같으면
해당 이미지 갱신을 건너뛴다. 이미 성공한 rollout을 불필요하게 반복하지 않는다.

## 10. 검증

### 10.1 워크플로 정적 검증

- PR `ci.yml`의 trigger, 권한과 기존 build job 호출이 유지되는지 확인한다.
- `release-develop.yml`이 `develop` push만 자동 처리하는지 확인한다.
- Release CI Gate 성공 전 이미지 발행이 실행될 수 없는지 확인한다.
- 모든 이미지 발행 성공 전 CD가 실행될 수 없는지 확인한다.
- Release manifest의 모든 서비스가 GHCR digest와 immutable reference를 갖는지 확인한다.
- `reusable-kubernetes-deploy.yml`에 Docker build, GHCR login과 push가 없는지 확인한다.
- `cd-selfhosted-kubernetes.yml`에 자동 `develop` push와 애플리케이션 배포가 남지 않았는지 확인한다.
- Compose CD의 자동 trigger가 비활성 상태인지 확인한다.
- Kubernetes Release CI와 애플리케이션 CD에 `ecr-repository`, `latest`, 짧은 SHA 입력이 남지
  않았는지 확인한다.
- 수동 Compose 호출만 `publish-latest: true`를 사용하고 자동 trigger는 비활성인지 확인한다.

기존 `scripts/validate-k8s-cd-workflow.sh`는 분리된 Release CI와 reusable CD 계약을 함께 검증하도록
수정한다.

### 10.2 Kubernetes 검증

다음 기존 검증을 유지한다.

```text
bash scripts/validate-k8s-manifests.sh
bash scripts/validate-k8s-secret-contract.sh
kubectl apply --dry-run=client
kubectl apply --dry-run=server
```

추가 fixture 또는 정적 검증으로 다음 변경 감지 결과를 확인한다.

- 단일 서비스 코드 변경은 해당 서비스만 테스트·이미지 발행·배포
- 공통 빌드 입력 변경은 전체 애플리케이션 대상
- 애플리케이션 매니페스트 전용 변경은 이미지 발행 없음
- 문서 전용 변경은 Release pipeline 종료
- 테스트 실패, 이미지 발행 실패 시 CD 차단

## 11. 문서 변경

현재 동작을 설명하는 다음 문서를 새 책임 경계에 맞게 수정한다.

- `k8s/README.md`
- `docs/architecture/kubernetes.md`
- `settlement-service/docs/architecture/deployment-ci-cd.md`
- 필요한 운영·문제 해결 문서

문서에는 PR CI, Release CI, GHCR artifact와 CD의 관계를 구분하고, CD가 이미지를 다시 만들지 않는다는
계약을 명시한다.

## 12. 완료 조건

- 기존 PR CI가 동일한 trigger와 변경 모듈 빌드·테스트 계약을 유지한다.
- `develop` 머지 커밋에서 영향 모듈 전체 테스트가 성공해야 이미지를 발행한다.
- CI가 전체 Git SHA tag로 영향 모듈 이미지를 GHCR에 push한다.
- CI가 서비스별 digest를 모은 Release manifest를 CD에 전달한다.
- 이미지 발행이 모두 성공해야 애플리케이션 CD가 자동 실행된다.
- CD는 CI가 발행한 immutable image reference만 배포하고 이미지를 빌드하지 않는다.
- 애플리케이션 매니페스트만 바뀐 경우 새 이미지 없이 현재 이미지를 보존해 적용한다.
- 상태 저장 인프라와 Ingress는 수동 배포로 남는다.
- 순차 rollout, Config 소비자 재시작과 rollback 계약이 유지된다.
- 정적 워크플로 검증과 Kubernetes manifest·Secret contract 검증이 통과한다.

## 13. 구현 계획

구현은 아래 계획을 따른다.

`settlement-service/docs/superpowers/plans/2026-07-27-ci-cd-pipeline-responsibility-separation-implementation.md`
