# 주간 정산 Kubernetes CronJob 전환 설계

- 작성일: 2026-07-20
- 관련 이슈: `#364 (이슈)`, `#365 (이슈)`
- 작업 브랜치: `infra/#364-weekly-settlement-cronjob`

## 1. 목표

정산 계산을 상시 `Deployment + @Scheduled` 구조에서 매주 한 번 실행되고 종료되는
Kubernetes CronJob 구조로 전환한다. CronJob은 `Asia/Seoul` 기준 매주 월요일 00:00에
직전 월요일부터 일요일까지의 정산을 한 번 실행한다.

이번 작업은 `#365 (이슈)` 중 주간 정산 계산 기반만 선행 구현한다. 주간 범위 조회,
Spring Batch JobInstance 식별, one-shot 프로세스 종료와 Kubernetes 실행 계약을 함께
완성해 CronJob이 실제로 직전 주차를 계산할 수 있게 한다.

## 2. 범위

### 포함

- 정산 기간을 월 단위 `YearMonth`에서 월요일~일요일 주간 범위로 변경
- order-service 정산 대상 조회 gRPC 계약과 조회 구현을 주간 범위로 변경
- `periodStart`, `periodEnd` 기반 Spring Batch JobParameter와 중복 실행 방지
- CronJob 전용 one-shot 애플리케이션 실행 모드와 프로세스 종료 코드
- settlement-service의 내부 `@Scheduled`, 웹 서버와 Eureka 등록 제거
- Kubernetes Deployment를 주간 CronJob으로 교체하고 불필요한 Service 제거
- Gateway의 settlement-service 런타임·문서 라우트 제거
- Config와 CD 워크플로의 Deployment 전제 제거 및 CronJob 이미지 갱신 지원
- 정적 매니페스트, 단위·통합·gRPC·회귀 테스트

### 제외

- 완료된 과거 주차의 누락 건 보정·차액 정산
- 월간 지급 Job과 지급 대상 자동 집계
- 어드민 주간 정산·배치 조회 API
- 실패 배치 재실행 API, Kubernetes 일회성 Job 생성과 RBAC
- `JobOperator.restart()` 기반 동일 JobInstance 재시작 정책
- 승인·보류 감사 이력과 낙관적 락
- 운영 클러스터에 실제 리소스를 적용하거나 임시 정산 Job을 실행하는 작업

운영 클러스터 변경은 코드와 매니페스트 검증이 끝난 뒤 별도 승인을 받아 수행한다.

## 3. 정산 주차 계약

정산 주차는 `Asia/Seoul` 달력 기준 월요일부터 일요일까지다.

- `periodStart`: 포함 시작일인 월요일
- `periodEnd`: 포함 종료일인 일요일
- 조회 하한: `periodStart` 00:00 이상
- 조회 상한: `periodEnd + 1일` 00:00 미만

예를 들어 2026-07-20 월요일 00:00에 실행되는 CronJob의 대상은 다음과 같다.

```text
periodStart = 2026-07-13
periodEnd   = 2026-07-19
query       = [2026-07-13T00:00, 2026-07-20T00:00)
```

`SettlementPeriod` 값 객체가 시작일이 월요일인지, 종료일이 시작일로부터 6일 뒤인지 검증한다.
정산 엔티티, 배치 엔티티와 `SETTLEMENT_CREATED` 이벤트에는 포함 범위인 월요일·일요일을
그대로 기록한다. 날짜 저장 의미를 유지하므로 데이터베이스 컬럼 변경은 필요하지 않다.

CronJob은 시스템 기본 시간대에 의존하지 않는다. 주차 계산에는 `Asia/Seoul` ZoneId를 가진
`Clock`을 주입하고, 테스트에서는 고정 Clock을 사용한다.

## 4. 애플리케이션 구조

### 4.1 주간 기간 모델

settlement-service에 `SettlementPeriod`를 추가하고 다음 흐름 전체가 이 타입을 사용한다.

```text
CronJob runner / local manual request
  -> RunSettlementJobCommand
  -> Spring Batch JobParameters(periodStart, periodEnd)
  -> source load / batch create / target read / calculate
  -> SettlementBatch / Settlement / SettlementCreatedEvent
```

기존 `YearMonth` 변환은 제거한다. 로컬 전용 수동 API도 `periodStart`, `periodEnd`를 받아
운영 CronJob과 같은 주간 계약을 검증한다.

### 4.2 JobInstance 식별

Spring Batch JobParameter는 다음과 같이 구분한다.

| 파라미터 | 값 | JobInstance 식별 |
| --- | --- | --- |
| `periodStart` | `yyyy-MM-dd` | 예 |
| `periodEnd` | `yyyy-MM-dd` | 예 |
| `requestedAt` | epoch millis | 아니요 |
| `actorId` | UUID, 수동 실행에만 존재 | 아니요 |
| `triggerType` | `SCHEDULED` 또는 `MANUAL` | 아니요 |

동일 주차의 정상 완료 JobInstance는 새 `requestedAt`이나 실행 방식만 바꿔 다시 생성할 수 없다.
Kubernetes의 `concurrencyPolicy: Forbid`가 스케줄 중첩을 차단하고, Spring Batch 저장소가 같은
주차의 애플리케이션 중복 실행을 한 번 더 차단한다.

### 4.3 one-shot 실행과 종료 코드

`settlement.execution.mode=cronjob`일 때만 활성화되는 `ApplicationRunner`를 둔다.

1. 주입받은 서울 시간 Clock으로 직전 주차를 계산한다.
2. `RunSettlementBatchUseCase`를 `SCHEDULED` 방식으로 동기 호출한다.
3. 최종 BatchStatus가 `COMPLETED`면 종료 코드 0을 기록한다.
4. 상태가 `FAILED`이거나 실행 중 예외가 발생하면 원인을 로그에 남기고 종료 코드 1을 기록한다.
5. runner 실행이 끝나면 Spring Context를 닫고 해당 코드로 JVM을 종료한다.

기본 애플리케이션 모드는 로컬 수동 API와 테스트를 위해 유지한다. CronJob Pod는 다음 설정으로
기술적 상시 실행 요소를 끈다.

```text
spring.main.web-application-type=none
settlement.execution.mode=cronjob
settlement.manual-api.enabled=false
eureka.client.enabled=false
spring.cloud.discovery.enabled=false
```

`SettlementBatchScheduler`와 `SchedulingConfig`는 삭제한다. 운영 스케줄의 단일 소유자는
Kubernetes CronJob이다.

## 5. order-service 조회 계약

현재 `GetSettleableLinesRequest.period`는 `yyyy-MM` 월 문자열이다. 무중단 배포 호환성을 위해
필드 1은 deprecated 상태로 남기고 신규 필드를 추가한다.

```proto
message GetSettleableLinesRequest {
  string period = 1 [deprecated = true];
  string period_start = 2;
  string period_end = 3;
}
```

신규 settlement-service는 `period_start`, `period_end`만 전송한다. order-service는 두 신규
필드가 모두 있으면 주간 범위로 검증·조회하고, 둘 다 없으면 배포 호환성을 위해 기존 `period`를
월 범위로 변환한다. 신규 필드가 하나만 있거나 날짜 형식·범위가 잘못되면
`INVALID_ARGUMENT`를 반환한다.

order-service 애플리케이션 포트와 영속성 조회는 `startInclusive`, `endExclusive`를 사용한다.
QueryDSL/JPA 조건은 결제 완료 시각 또는 현행 정산 기준 시각에 대해 `>= startInclusive`와
`< endExclusive`를 유지한다. gRPC 서버만 외부의 포함 종료일을 다음 날 00:00 상한으로
변환한다.

## 6. Kubernetes 리소스

`k8s/base/services/settlement/deployment.yaml`과 `service.yaml`을 제거하고
`cronjob.yaml`을 추가한다.

CronJob의 고정 계약은 다음과 같다.

```yaml
apiVersion: batch/v1
kind: CronJob
metadata:
  name: settlement-weekly
spec:
  schedule: "0 0 * * 1"
  timeZone: Asia/Seoul
  concurrencyPolicy: Forbid
  startingDeadlineSeconds: 3600
  successfulJobsHistoryLimit: 3
  failedJobsHistoryLimit: 3
  jobTemplate:
    spec:
      backoffLimit: 1
      activeDeadlineSeconds: 7200
      template:
        spec:
          restartPolicy: Never
```

기존 Deployment의 다음 설정은 Job Pod template에 유지한다.

- 이미지와 `ghcr-pull-secret`
- Secret 기반 PostgreSQL·Kafka·gRPC 환경변수
- `automountServiceAccountToken: false`, `enableServiceLinks: false`
- application 노드 selector
- privilege escalation 금지와 Linux capability 전체 제거
- CPU·메모리 request/limit와 JVM 메모리 옵션
- config, PostgreSQL, Kafka와 order-service를 기다리는 initContainer

Eureka 대기, Eureka 환경변수, HTTP port, startup/readiness/liveness probe는 제거한다. 실행 완료
여부는 Pod probe가 아니라 Kubernetes Job 상태와 프로세스 종료 코드로 판단한다.

Kustomization은 `cronjob.yaml`만 참조한다. 기존 Service가 없어지므로 Gateway가
settlement-service로 요청을 전달하거나 OpenAPI 문서를 조회하지 않게 관련 코드·설정·테스트도
제거한다. 이후 정산 운영 API는 `#365 (이슈)`에서 상시 실행되는 admin-service가 소유한다.

## 7. CD 전환

현재 CD는 모든 애플리케이션을 Deployment로 가정한다. settlement-service를 다음과 같이
별도 CronJob 대상으로 관리한다.

- 최초 리소스 준비 조건을 `cronjob/settlement-weekly` 존재 여부로 판단
- Deployment rollout 순서와 config consumer 재시작 목록에서 settlement-service 제거
- settlement-service 이미지 빌드 시 CronJob jobTemplate의 `settlement-service` 컨테이너 이미지를 갱신
- 이미지 갱신 후 CronJob template에 새 이미지가 반영됐는지 조회해 검증
- 매니페스트 전환 시 CronJob 적용 성공 뒤 기존 `deployment/settlement-service`와
  `service/settlement-service`를 `--ignore-not-found`로 명시 삭제
- 실패 처리에서 CronJob의 이전 이미지를 복원할 수 있도록 변경 전 이미지를 저장

CronJob 이미지를 갱신하는 것만으로 실제 정산 Job을 실행하지 않는다. 배포 검증 과정에서
운영 정산을 임의로 발생시키지 않으며, 임시 Job 실행은 별도 운영 승인 후 수행한다.

## 8. 실패 처리와 관측

- order-service 조회 실패는 빈 정산으로 바꾸지 않고 Batch 실패로 전파한다.
- Job이 `COMPLETED`가 아니면 프로세스는 1로 종료해 Kubernetes Job을 실패로 표시한다.
- `backoffLimit: 1`에 따라 Pod 실패 시 한 번만 재시도한다.
- `activeDeadlineSeconds: 7200`을 넘긴 Job은 Kubernetes가 종료한다.
- `startingDeadlineSeconds: 3600`을 넘긴 누락 스케줄은 뒤늦게 시작하지 않는다.
- 같은 CronJob의 이전 Job이 실행 중이면 `Forbid` 정책으로 새 Job을 만들지 않는다.
- 로그에는 정산 주차, JobExecution ID와 최종 상태를 남기되 Secret과 개인정보는 남기지 않는다.

완료된 과거 주차의 누락은 이번 흐름이 자동으로 다른 주차에 섞어 처리하지 않는다. 별도의
보정 배치 정책이 생길 때까지 완료 주차는 불변으로 유지한다.

## 9. 테스트 전략

### settlement-service

- 월요일 00:00 실행 시 직전 월요일~일요일 계산
- 월·연도 경계를 넘는 주차 계산
- 월요일이 아닌 기간과 7일이 아닌 기간 거부
- JobParameter의 식별·비식별 속성 검증
- 배치 tasklet, reader, 계산과 source 조회가 같은 주간 범위를 사용
- 정산·배치·이벤트의 `periodStart`, `periodEnd` 기록
- CronJob runner의 완료 0, 실패·예외 1 종료 코드
- CronJob 모드에서 runner 활성화, 기본 모드에서 비활성화

### order-service와 gRPC

- 신규 주간 요청의 날짜 파싱, 범위 조회와 응답 매핑
- 신규 필드 일부 누락·형식 오류·잘못된 주차의 `INVALID_ARGUMENT`
- 기존 월 필드 fallback 호환
- `startInclusive <= timestamp < endExclusive` 경계 조회
- settlement-service gRPC client가 신규 필드만 전송하는지 검증

### Kubernetes와 CD

- `kubectl kustomize k8s/overlays/ec2-kubeadm`
- `bash scripts/validate-k8s-manifests.sh`
- `bash scripts/validate-k8s-cd-workflow.sh`
- CronJob schedule, timeZone, 동시 실행·deadline·history·재시도 정책 정적 검증
- Service·Deployment·Gateway·Eureka 참조 제거 검증
- 기존 Secret, initContainer, securityContext와 resource 설정 유지 검증

### 회귀

```bash
./gradlew :settlement-service:test
./gradlew :order-service:test
./gradlew :settlement-service:build :order-service:build
git diff --check origin/develop...HEAD
```

## 10. 완료 조건

- 매니페스트상 settlement-service는 상시 Deployment가 아니라 주간 CronJob이다.
- CronJob은 매주 월요일 00:00 KST에 직전 주차를 한 번 실행한다.
- 애플리케이션 내부 스케줄러, 웹 서버, Eureka 등록과 ClusterIP Service가 운영 경로에 없다.
- order-service와 settlement-service가 동일한 주간 범위 계약을 사용한다.
- 동일 주차의 중복 JobInstance와 겹치는 Kubernetes Job 실행이 차단된다.
- 성공·실패가 프로세스 종료 코드와 Kubernetes Job 상태로 드러난다.
- 정적 검증과 두 서비스의 테스트·빌드가 통과한다.
- 운영 클러스터 변경과 과거 완료 주차 보정은 별도 작업으로 남는다.
