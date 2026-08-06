# Kubernetes CD 연쇄 실패: 롤아웃 상태, 설정 누락, 필드 매니저 충돌

## 환경

GitHub Actions / self-hosted runner / Kubernetes / Kustomize / `kubectl apply` / GHCR

대상 워크플로는 `.github/workflows/cd-selfhosted-kubernetes.yml`의
`CD - Self-hosted Kubernetes`다. `develop` 브랜치에 병합되면 변경 모듈의 이미지를 만들고,
self-hosted runner가 `prompthub` 네임스페이스에 배포한다.

## 증상

2026-07-20~21 사이 여러 PR의 이미지 빌드는 성공했지만 `Deploy Applications` job이 반복해서 실패했다.
실패 로그는 크게 세 형태였다.

```text
error: timed out waiting for the condition
error: deployment "..." exceeded its progress deadline
```

```text
Apply failed with 1 conflict: conflict with "kubectl-set" using apps/v1:
.spec.template.spec.containers[name="admin-service"].image
```

```text
Failed to bind properties under 'spring.data.redis.port' to int
Value: "${REDIS_PORT}"
```

겉으로는 모두 CD 실패지만 같은 원인이 아니었다. 애플리케이션 기동 실패, 이전 실패 상태를 검사하는
배포 전 단계, client-side와 server-side apply 혼용이 차례로 겹쳤다.

## 먼저 확인할 점: PR 번호는 장애 순서가 아니다

PR 번호와 실제 병합 시각이 일치하지 않아 `#438 (PR)부터 계속 실패했다`고 보기 쉽다.
그러나 `#438 (PR)`의 CD 실행 `29728956625`는 order-service 이미지 빌드와 실제 배포 단계까지 성공했다.

장애 흐름은 PR 번호가 아니라 Actions 실행 시각과 `headSha`로 정렬해야 한다.

| 관련 변경 | Actions 실행 | 결과 | 확인된 지점 |
| --- | ---: | --- | --- |
| `#428 (PR)` | `29722716347` | 실패 | user-service 롤아웃 10분 타임아웃 후 rollback |
| `#417 (PR)` | `29722843264` | 성공 표시 | 애플리케이션 빌드·배포가 모두 skip되어 복구 검증은 아님 |
| `#439 (PR)` | `29727563718` | 실패 | product-service 롤아웃 타임아웃 |
| `#438 (PR)` | `29728956625` | 성공 | order-service 실제 배포 성공 |
| `#449 (PR)` | `29792792243` | 실패 | admin-service 신규 Pod가 Ready가 되지 않아 타임아웃 |
| `#432 (PR)` | `29797702554` | 실패 | 매니페스트 server-side dry-run의 이미지 필드 충돌 |
| `#448 (PR)` | `29797886166` | 실패 | product-service 롤아웃 타임아웃 |
| `#455 (PR)` | `29803948248` | 성공 | settlement-service·user-service 실제 배포 성공 |
| `#453 (PR)`·`#456 (PR)`·`#459 (PR)`·`#460 (PR)` | `29806115130` 외 3건 | 실패 | 배포 전 검사에서 기존 payment-service의 `ProgressDeadlineExceeded` 감지 |
| `#465 (PR)` | `29810295288` | 취소 | config·user·product 갱신 후 admin-service 롤아웃 대기 중 취소 |
| `#470 (PR)` | `29812176658` | 실패 | admin Redis 설정 수정본 적용 전 server-side dry-run 충돌 |
| `#474 (PR)` | `29813721803` | 실패 | 필드 충돌은 제거됐지만 배포 전 검사에서 기존 admin-service 실패 상태로 종료 |

`success`만 봐서도 안 된다. 실행 `29722843264`처럼 변경 경로상 배포 대상이 없어 관련 job이 skip된 경우에는
클러스터가 복구됐다는 뜻이 아니다. `Plan Application Images`의 build matrix와 `Deploy Applications` 실행 여부를
같이 확인해야 한다.

## 원인 1: 롤아웃 타임아웃은 원인이 아니라 결과다

워크플로는 이미지를 바꾼 뒤 다음 명령으로 새 Pod가 Ready가 되기를 기다린다.

```bash
kubectl rollout status deployment/<service> -n prompthub --timeout=10m
```

신규 Pod가 기동하지 못하면 `maxUnavailable: 0`, `maxSurge: 1`인 RollingUpdate 특성상 기존 Pod는 남고
로그에는 다음 문구가 보인다.

```text
Waiting for deployment "admin-service" rollout to finish:
1 old replicas are pending termination...
```

이 문구만 보고 기존 Pod 종료 문제로 단정하면 안 된다. 새 Pod가 Ready가 되지 않아 기존 Pod를 내릴 수 없는
상태에서도 같은 문구가 나온다. 다음 순서로 신규 ReplicaSet과 Pod를 찾아야 한다.

```bash
kubectl -n prompthub describe deployment <service>
kubectl -n prompthub get pods -l app.kubernetes.io/name=<service> -o wide
kubectl -n prompthub describe pod <new-pod>
kubectl -n prompthub logs <new-pod> --previous --tail=200
```

`#428 (PR)`·`#439 (PR)`·`#448 (PR)` 실행은 Actions 로그에 롤아웃 타임아웃과 rollback까지만 남아 있다.
실패한 Pod가 이미 정리되어 애플리케이션 수준의 최초 원인은 현재 기록만으로 확정할 수 없다.
문서에서는 이 구간을 `롤아웃 실패`로만 분류하고 구체적인 예외를 추측하지 않는다.

## 원인 2: admin-service Redis 설정과 Kubernetes 주입 계약 불일치

`#449 (PR)`은 admin-service에 Redis를 사용하는 인증 기능과 Config Server 설정을 추가했다.

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST}
      port: ${REDIS_PORT}
```

하지만 당시 `k8s/base/services/admin/deployment.yaml`에는 `REDIS_HOST`, `REDIS_PORT` 주입이 없었다.
Config Server는 문자열 플레이스홀더를 그대로 내려줬고, admin-service는 `${REDIS_PORT}`를 정수로 변환하지
못해 종료했다.

2026-07-21 운영 클러스터에서 실패한 신규 Pod를 다시 확인한 결과도 같았다.

```text
STATUS: CrashLoopBackOff
Failed to bind properties under 'spring.data.redis.port' to int
Value: "${REDIS_PORT}"
Reason: NumberFormatException
```

`#470 (PR)`에서 admin Deployment가 `runtime-secret`의 두 키를 주입하도록 수정하고 정적 계약 검증을
추가했다.

```yaml
- name: REDIS_HOST
  valueFrom:
    secretKeyRef:
      name: runtime-secret
      key: REDIS_HOST
- name: REDIS_PORT
  valueFrom:
    secretKeyRef:
      name: runtime-secret
      key: REDIS_PORT
```

다만 `#470 (PR)`의 CD는 다음 원인인 dry-run 충돌로 실제 `kubectl apply`까지 도달하지 못했다.
따라서 Git의 매니페스트가 수정됐다는 사실과 클러스터에 적용됐다는 사실은 구분해야 한다.

## 원인 3: server-side dry-run과 기존 필드 매니저 충돌

`#432 (PR)`에 포함된 `4cb43628 (커밋)`은 런타임 오버레이를 적용하기 전에 다음 검증을 추가했다.

```bash
kubectl apply --server-side --dry-run=server -k "$runtime_overlay"
```

그런데 실제 배포는 client-side apply와 `kubectl set image`를 함께 사용한다.

```bash
kubectl apply -k "$runtime_overlay"
kubectl set image deployment/<service> ...
```

클러스터의 이미지 필드는 이미 `kubectl` 또는 `kubectl-set`이 관리하고 있었다. server-side apply는 같은
필드를 자신이 관리하려다 충돌을 감지했다.

```text
conflict with "kubectl" ... .spec.template.spec.containers[...].image
conflict with "kubectl-set" ... .spec.template.spec.containers[...].image
```

`dry-run=server`라도 server-side apply의 필드 소유권 검사는 수행된다. 이 실패는 API 스키마 오류도,
이미지 빌드 오류도 아니다. 실제 배포 방식과 검증 방식의 필드 관리 모델이 달랐던 것이 원인이다.

`#474 (PR)`은 검증도 client-side apply 방식으로 맞췄다.

```bash
kubectl apply --dry-run=server -k "$runtime_overlay"
```

API 서버 검증은 유지하면서 실제 적용과 동일한 apply 방식을 사용한다. `--force-conflicts`로 소유권을
강제로 빼앗는 방식은 사용하지 않았다. 검증 단계가 실제 배포보다 강한 필드 소유권 변경을 가정하면 같은
문제가 다시 생길 수 있기 때문이다.

회귀 방지를 위해 `scripts/validate-k8s-cd-workflow.sh`는 client-side server dry-run을 필수로 확인하고,
기존 server-side 명령은 금지한다.

## 원인 4: 실패한 기존 Deployment가 다음 수정 배포를 막는 복구 교착

필드 충돌을 제거한 `#474 (PR)`도 최종적으로 실패했다. 이번 오류는 다음 배포 단계가 아니라
`최초 애플리케이션 리소스 준비`였다.

```text
deployment "payment-service" successfully rolled out
error: deployment "admin-service" exceeded its progress deadline
```

현재 워크플로는 실제 매니페스트를 적용하기 전에 모든 애플리케이션 Deployment가 정상 rollout 상태인지
검사한다.

```bash
for deployment in "${application_deployments[@]}"; do
  kubectl rollout status deployment/"$deployment" -n "$NAMESPACE" --timeout=10m
done
```

이 검사는 정상 상태에서는 안전장치지만, 매니페스트 변경 자체가 장애 복구 수단일 때 교착을 만든다.

1. admin-service 신규 Pod가 Redis 환경변수 누락으로 실패한다.
2. `#470 (PR)`의 수정 매니페스트는 dry-run 충돌로 적용되지 않는다.
3. `#474 (PR)`에서 dry-run 충돌을 고친다.
4. 그러나 배포 전 단계가 기존 admin Deployment의 `ProgressDeadlineExceeded`를 보고 먼저 종료한다.
5. `REDIS_HOST`, `REDIS_PORT`가 추가된 수정 매니페스트는 여전히 적용되지 않는다.

운영 클러스터에는 기존 정상 Pod 1개와 실패한 신규 Pod 1개가 함께 남아 있었다.

```text
admin-service-749d4bd5fc-...   1/1 Running
admin-service-5b94db649-...    0/1 CrashLoopBackOff
```

반복된 payment-service 차단도 같은 구조였다. `#453 (PR)`·`#456 (PR)`·`#459 (PR)`·`#460 (PR)`은 각 변경 서비스의 실제
배포 전에 기존 payment Deployment의 `ProgressDeadlineExceeded`를 감지하고 종료했다. 당시 실패 Pod의
애플리케이션 로그는 남아 있지 않아 최초 기동 실패 원인은 확정할 수 없다. 클러스터 rollout history에는
이후 `Manual recovery to PR 465 image 3858c4e`가 남아 있다.

## 해결과 현재 상태

### 적용된 변경

- `#470 (PR)`: admin Deployment에 `REDIS_HOST`, `REDIS_PORT` Secret 참조와 정적 검증 추가
- `#474 (PR)`: 매니페스트 검증을 `kubectl apply --dry-run=server`로 통일하고 server-side apply 금지

### 아직 필요한 복구

2026-07-21 17시대 기준으로 `#474 (PR)`의 CD 실행 `29813721803`은 admin-service의 기존 실패 상태에
막혀 종료됐다. 따라서 당시 시점에는 전체 CD 복구가 완료되지 않았다.

운영 복구 시에는 다음 원칙을 따른다.

1. 실패한 신규 admin Pod 로그에서 `${REDIS_PORT}` 바인딩 오류를 확인한다.
2. 현재 이미지 태그를 보존하면서 `REDIS_HOST`, `REDIS_PORT` Secret 참조가 포함된 desired state를
   클러스터에 적용한다.
3. 신규 Pod가 Ready가 되고 Deployment의 `Progressing` 조건이 정상화됐는지 확인한다.
4. 동일 `develop` 기준 CD를 다시 실행해 이미지·매니페스트 적용 전체가 성공하는지 확인한다.

단순 `kubectl rollout restart`는 같은 잘못된 Pod template을 다시 실행하므로 해결이 아니다.
또한 기존 정상 Pod를 먼저 삭제하면 admin-service 가용성이 사라질 수 있으므로, 수정된 Pod가 Ready가 된
뒤 기존 ReplicaSet이 축소되는 RollingUpdate 흐름을 유지한다.

## 후속 개선

`최초 애플리케이션 리소스 준비`는 리소스 존재 여부와 인프라 준비 상태만 확인하고, 이미 존재하는 모든
애플리케이션 Deployment의 정상 여부를 매니페스트 적용 전에 강제하지 않도록 재검토해야 한다.

가능한 방향은 다음과 같다.

- 매니페스트 변경 배포에서는 desired state를 먼저 적용한 뒤 대상 Deployment의 rollout을 검증한다.
- 코드 전용 배포에서는 이번 build matrix에 포함된 대상과 필수 선행 서비스만 검사한다.
- 사전 상태 이상은 즉시 종료하기보다 Pod·ReplicaSet·Deployment 진단 정보를 Actions 로그에 남긴다.
- rollback과 수동 복구에는 실패 Pod의 마지막 로그와 적용 이미지, Deployment 조건을 보존한다.

## 교훈 / 재발 방지

- CD의 빨간 결과를 하나의 원인으로 묶지 않는다. 실패 job과 step, 마지막 실제 명령을 먼저 구분한다.
- PR 번호가 아니라 병합 시각, Actions run ID, `headSha`로 타임라인을 만든다.
- `success` 실행도 배포 job이 skip됐는지 확인한다.
- `old replicas are pending termination`은 기존 Pod 종료 문제만 뜻하지 않는다. 신규 Pod의 Ready 실패를
  먼저 확인한다.
- 애플리케이션 yml에 새 환경변수 플레이스홀더를 추가하면 같은 PR에서 Kubernetes 주입 계약과 정적 검증도
  함께 갱신한다.
- dry-run은 실제 apply와 같은 필드 관리 방식을 사용한다.
- 전체 서비스 사전 health gate는 무관한 장애가 모든 후속 배포를 막고, 장애 수정 배포까지 차단할 수 있다.
- Git에 수정이 병합된 것, 이미지가 빌드된 것, 클러스터에 매니페스트가 적용된 것은 서로 다른 상태다.

## 관련 기록

- `#428 (PR)`: https://github.com/prgrms-be-adv-devcourse/beadv6_6_3JMT_BE/pull/428
- `#438 (PR)`: https://github.com/prgrms-be-adv-devcourse/beadv6_6_3JMT_BE/pull/438
- `#449 (PR)`: https://github.com/prgrms-be-adv-devcourse/beadv6_6_3JMT_BE/pull/449
- `#470 (PR)`: https://github.com/prgrms-be-adv-devcourse/beadv6_6_3JMT_BE/pull/470
- `#471 (이슈)`: https://github.com/prgrms-be-adv-devcourse/beadv6_6_3JMT_BE/issues/471
- `#474 (PR)`: https://github.com/prgrms-be-adv-devcourse/beadv6_6_3JMT_BE/pull/474
- `#474 (PR) CD 실행`: https://github.com/prgrms-be-adv-devcourse/beadv6_6_3JMT_BE/actions/runs/29813721803
