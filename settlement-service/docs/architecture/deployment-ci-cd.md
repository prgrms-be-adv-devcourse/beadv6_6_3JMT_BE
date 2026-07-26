# 배포 구조 — CI/CD 흐름

정산 서비스를 포함한 백엔드의 PR 검증부터 Kubernetes 배포까지의 책임과 연결 방식을 정리한다.

## 전체 흐름

```text
PR
 └─ CI (`ci.yml`)
     └─ 변경 모듈 빌드·테스트

develop merge/push
 └─ Release CI (`release-develop.yml`)
     ├─ 변경 모듈 계산
     ├─ 변경 모듈 빌드·테스트
     ├─ Release CI Gate
     ├─ 변경 모듈 Docker build + GHCR push
     └─ 서비스별 image digest를 release manifest로 수집
          └─ CD (`reusable-kubernetes-deploy.yml`)
              ├─ release manifest 검증
              ├─ Kubernetes 매니페스트 적용
              ├─ digest 기준 순차 rollout
              └─ 실패 시 rollback
```

PR CI와 develop Release CI는 목적이 다르므로 둘 다 실행한다. PR CI는 머지 가능성을 미리 검증하고,
Release CI는 실제로 머지된 커밋을 기준으로 배포할 산출물을 다시 검증하고 발행한다.

## PR CI 책임

`.github/workflows/ci.yml`은 `develop` 대상 PR에서 실행된다.

- 변경된 모듈만 빌드하고 테스트한다.
- `grpc/user/**` 변경은 User와 AI 모듈을 함께 검증한다.
- 모든 대상 job 결과를 `ci-gate`에서 확인한다.
- Docker 이미지를 push하거나 배포하지 않는다.

## Release CI 책임

`.github/workflows/release-develop.yml`은 `develop` push에서 실행된다.

1. 변경된 모듈과 애플리케이션 매니페스트를 구분한다.
2. 변경 모듈만 빌드·테스트한다.
3. 모든 테스트가 통과한 뒤에만 변경 모듈의 Docker 이미지를 빌드해 GHCR에 push한다.
4. 전체 Git SHA tag는 실행 추적에 사용하고, 빌드가 반환한 digest를 배포 계약으로 사용한다.
5. 서비스별 `repository@sha256:...` 값을 release manifest로 합친다.

공통 빌드 파일이나 `common-module`이 바뀌면 전체 애플리케이션을 대상으로 한다. Kubernetes
매니페스트만 바뀐 경우에는 이미지를 다시 빌드하지 않는다.

## CD 책임

`.github/workflows/reusable-kubernetes-deploy.yml`은 Release CI와 같은 GitHub Actions DAG 안에서
자동으로 이어진다. 별도의 `workflow_run`이나 새 workflow 실행을 만들지 않는다.

- CD는 Docker 이미지를 빌드하거나 registry에 push하지 않는다.
- release manifest에 포함된 모듈은 immutable digest로 배포한다.
- 매니페스트만 변경된 모듈은 현재 클러스터 image ref를 유지한다.
- Settlement는 Deployment가 아니라 `CronJob/settlement-weekly`의 Job template 이미지를 갱신한다.
- rollout 실패 시 이번 실행에서 변경된 Deployment와 Settlement CronJob을 복구한다.

`.github/workflows/cd-selfhosted-kubernetes.yml`은 자동 애플리케이션 CD가 아니다. Storage·상태 저장
인프라와 Ingress를 운영자가 `workflow_dispatch`로 승인해 적용하는 수동 workflow다.

## 이미지 태그와 digest

GHCR에는 다음 두 종류의 참조가 존재한다.

```text
추적용: ghcr.io/<owner>/prompthub-<module>:<full-git-sha>
배포용: ghcr.io/<owner>/prompthub-<module>@sha256:<digest>
```

Git SHA tag도 registry에서 다시 가리킬 수 있는 태그이므로 Kubernetes의 최종 배포 입력은 digest로
고정한다. `latest`는 Kubernetes 자동 배포에서 사용하지 않는다. 기존 Compose 수동 rollback 경로의
호환을 위해서만 함께 발행한다.

## Self-hosted runner

Kubernetes CD job은 `[self-hosted, linux, deploy]` runner에서 실행된다. runner에는 `kubectl`, `jq`,
`/home/ubuntu/.kube/config`가 준비돼 있어야 한다. 실제 Secret과 GHCR pull credential은 클러스터에서
관리하며 저장소에 커밋하지 않는다.
