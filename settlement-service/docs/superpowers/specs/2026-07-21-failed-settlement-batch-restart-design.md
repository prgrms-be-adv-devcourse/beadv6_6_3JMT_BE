# 실패한 주간 정산 배치 재시작 설계

- 작성일: 2026-07-21
- 관련 이슈: `#365 (이슈)`
- 선행 작업: `#364 (이슈)`, `#432 (PR)`
- 작업 브랜치: `feat/#365-failed-batch-restart (브랜치)`

## 1. 목표

실패한 주간 정산을 새로운 JobInstance나 `settlement_batch`로 다시 만들지 않고, 기존
Spring Batch JobInstance와 정산 배치를 이어서 실행한다. 첫 실행에서 이미 커밋된 정산과
소스 라인 연결은 유지하고, 처리되지 않은 소스 라인만 같은 배치에 포함한다.

실패 배치에서 생성된 `SETTLEMENT_CREATED` Outbox 이벤트는 외부로 발행하지 않는다.
재시작이 최종 완료된 뒤에만 해당 배치의 대기 이벤트를 발행해 user-service가 불완전한
정산 결과를 소비하지 않게 한다.

이번 작업은 settlement-service의 재시작 코어와 실행 계약까지만 구현한다. admin-service의
조회·재시작 API, Kubernetes 일회성 Job 생성과 RBAC는 다음 작업에서 이 계약에 연결한다.

## 2. 현재 상태와 문제

`#432 (PR)`에서 정산 기간을 월요일부터 일요일까지의 주간 범위로 변경하고,
`periodStart`와 `periodEnd`만 JobInstance 식별 파라미터로 사용하도록 반영했다. 동일 주차의
JobInstance를 식별할 기반은 마련됐지만 다음 문제는 남아 있다.

- `settlement_batch`가 Spring Batch JobInstance를 직접 참조하지 않는다.
- 배치 상태에 재시작 요청 단계를 나타내는 `RETRY_REQUESTED`가 없다.
- `SettlementBatch`에 낙관적 락이 없어 동시 재시작 요청을 차단할 수 없다.
- 실행 어댑터는 `JobOperator.start()`만 사용하며 실패 실행을 지정해 재시작하지 않는다.
- 배치 생성 전에 소스 적재가 실패하면 `settlement_batch` 자체가 생성되지 않는다.
- 일반 Outbox 재시도 조회가 배치 상태를 확인하지 않아 실패 배치 이벤트를 발행할 수 있다.
- 청크 재시작 시 처리 위치를 별도로 저장하지 않지만, 소스 라인의 정산 연결 여부를 멱등성
  기준으로 사용할 수 있다.

## 3. 선택한 접근

Spring Batch의 기본 재시작과 정산 소스 라인의 처리 표시를 함께 사용한다.

1. `settlement_batch.job_instance_id`로 기존 정산 배치와 JobInstance를 연결한다.
2. 가장 최근의 `FAILED` JobExecution을 조회한다.
3. Spring Batch 6의 `JobOperator.restart(JobExecution)`을 호출한다.
4. 완료된 Step은 Spring Batch 기본 동작에 따라 건너뛴다.
5. 실패한 `settlementStep`은 다시 실행하되, `settlement_id is null`인 소스 라인만 조회한다.
6. 최종 완료 후 기존 배치에 속한 모든 대기 Outbox 이벤트를 발행한다.

Reader의 처리 위치를 별도 커서로 저장하는 방식은 사용하지 않는다. 판매자 목록은 실행 중
달라질 수 있고 UUID 커서를 별도로 관리해야 한다. 반면 정산 계산, 소스 라인 연결과 Outbox
저장은 같은 트랜잭션에서 처리되므로 `settlement_source_line.settlement_id`가 커밋 여부를
직접 나타낸다.

새 JobInstance와 새 `settlement_batch`를 만드는 방식도 사용하지 않는다. 기존 청크와 신규
청크가 서로 다른 배치에 나뉘고, 정산·소스 연결·Outbox 이벤트를 별도로 정리해야 하기 때문이다.

참고 문서:

- [Spring Batch Step 재시작 규칙](https://docs.spring.io/spring-batch/reference/step/chunk-oriented-processing/restart.html)
- [Spring Batch 6 JobOperator API](https://docs.spring.io/spring-batch/reference/api/org/springframework/batch/core/launch/JobOperator.html)

## 4. 범위

### 포함

- `settlement_batch`와 Spring Batch JobInstance 연결
- `SettlementBatch` 낙관적 락과 재시작 상태 전이
- 실패 JobExecution 조회와 `JobOperator.restart(JobExecution)` 호출
- 재시작 전용 one-shot 애플리케이션 실행 모드
- 최초 실행과 재시작에서 같은 `settlementBatchId` 사용
- 실패 배치 Outbox 이벤트 발행 차단
- 첫 청크 완료 후 실패·재시작하는 통합 테스트
- 기존 배치의 실행 이력 연결을 위한 안전한 보정

### 제외

- admin-service의 주간 정산·배치 조회 API
- admin-service의 재시작 요청 API
- Kubernetes CronJob 템플릿을 복제한 일회성 Job 생성
- ServiceAccount, Role과 RoleBinding
- Kubernetes Job 상태와 배치 상세의 결합
- 장시간 `STARTED` 상태인 JobExecution의 자동 복구
- 월간 지급 Job과 지급 데이터 모델
- 승인·보류 감사 이력과 `seller_settlement` 낙관적 락

비정상 종료로 Spring Batch 메타데이터가 `STARTED`에 남은 실행은 이번 작업에서 자동으로
`FAILED`로 바꾸지 않는다. 운영자가 실제 프로세스 종료를 확인하지 않은 상태에서 자동 복구하면
중복 실행될 수 있으므로, 해당 실행은 후속 모니터링 작업에서 실패 의심 상태로 노출한다.

## 5. 데이터 모델과 마이그레이션

### 5.1 SettlementBatch 필드

`SettlementBatch`에 다음 필드를 추가한다.

| 필드 | DB 컬럼 | 의미 |
| --- | --- | --- |
| `jobInstanceId` | `job_instance_id bigint` | 최초 실행에서 생성된 Spring Batch JobInstance ID |
| `version` | `version bigint not null default 0` | 재시작 상태 변경의 낙관적 락 버전 |

`version`에는 `@Version`을 사용한다. `job_instance_id`는 신규 배치 생성 시 필수지만, 연결할 수
없는 기존 데이터를 보존하기 위해 DB에서는 nullable로 둔다. 값이 있는 행에는 유일 제약을
적용해 하나의 JobInstance가 여러 정산 배치에 연결되지 않게 한다. Spring Batch 메타데이터의
보존 주기와 정산 도메인 데이터의 보존 주기를 결합하지 않기 위해 외래 키는 추가하지 않는다.

### 5.2 기존 데이터 보정

신규 Flyway 마이그레이션은 기존 `batch_no` 마지막 구간의 JobExecution ID와
`batch_job_execution`·`batch_job_instance`를 대조한다. 다음 조건을 모두 만족하는 행만
`job_instance_id`를 보정한다.

- `batch_no` 마지막 구간이 숫자로 해석된다.
- 같은 ID의 `batch_job_execution`이 존재한다.
- 연결된 JobInstance의 잡 이름이 `settlementJob`이다.
- 하나의 JobExecution과 정산 배치가 모호하지 않게 일대일로 대응한다.

형식이 다르거나 메타데이터가 삭제돼 연결할 수 없는 배치는 그대로 유지한다. 해당 배치는
조회할 수 있지만 재시작 요청 시 `job_instance_id` 미연결 오류로 거부한다. 마이그레이션은
기존 배치를 삭제하거나 임의의 JobInstance에 연결하지 않는다.

마이그레이션은 `settlement_batch_status_check`에 `RETRY_REQUESTED`도 추가한다.

## 6. 상태 전이

배치 상태는 다음 흐름을 사용한다.

```text
PROCESSING -> COMPLETED
PROCESSING -> FAILED
FAILED -> RETRY_REQUESTED
RETRY_REQUESTED -> PROCESSING
RETRY_REQUESTED -> FAILED
```

- `requestRetry()`: `FAILED` 배치만 `RETRY_REQUESTED`로 전환한다.
- `startRetry()`: 재시작 Job의 `beforeJob`에서 `RETRY_REQUESTED`를 `PROCESSING`으로 전환한다.
- `restoreFailed(reason)`: Kubernetes Job 생성 실패나 재시작 시작 전 오류가 발생하면
  `RETRY_REQUESTED`를 다시 `FAILED`로 전환한다.
- `complete()`: `PROCESSING`에서만 `COMPLETED`로 전환한다.
- `fail(reason)`: `PROCESSING`에서만 `FAILED`로 전환한다.

`startRetry()`는 이전 실패 사유와 종료 시각을 비운다. 재시작이 다시 실패하면 새 실패 사유와
종료 시각을 기록한다. 잘못된 상태 전이는 기존 도메인 순수 예외로 거부한다.

`@Version`은 두 요청이 같은 `FAILED` 버전을 읽고 동시에 `requestRetry()`를 호출해도 하나만
저장되게 한다. admin-service가 Kubernetes Job을 생성하기 전에 상태 저장을 flush하도록 하는
구체적인 트랜잭션 경계는 후속 API 설계에서 다룬다.

## 7. 애플리케이션 구조

재시작은 정산 엔티티 조회가 아니라 배치 잡을 다루는 관심사이므로 별도 유스케이스로 둔다.

```text
SettlementBatchRestartRunner
  -> RestartSettlementBatchUseCase
  -> SettlementBatchRestartApplicationService
  -> SettlementBatchRepository
  -> SettlementJobRestarter (application port)
  -> SettlementJobRestartAdapter (infrastructure/batch/launcher)
  -> JobRepository + JobOperator
```

애플리케이션 서비스는 다음을 검증한다.

1. `batchId`에 해당하는 배치가 존재한다.
2. 상태가 `RETRY_REQUESTED`다.
3. `jobInstanceId`가 연결돼 있다.

재시작 어댑터는 다음을 검증한다.

1. `jobInstanceId`에 해당하는 JobInstance가 존재한다.
2. JobInstance의 잡 이름이 `settlementJob`이다.
3. 가장 최근 JobExecution의 상태가 `FAILED`다.
4. JobExecutionContext의 `settlementBatchId`가 요청한 배치 ID와 같다.

검증을 통과하면 동기 `JobOperator.restart(JobExecution)`을 호출하고 기존
`SettlementJobResult` 형식으로 결과를 반환한다. 숫자 실행 ID만 받는 deprecated API는 사용하지
않는다.

## 8. 배치 실행 흐름

### 8.1 최초 실행

Job Step 순서를 다음과 같이 바꾼다.

```text
createSettlementBatchStep
  -> retryPendingOutboxStep
  -> loadSettlementSourceStep
  -> settlementStep
  -> completeSettlementBatchStep
  -> flushCurrentBatchOutboxStep
```

`createSettlementBatchStep`은 현재 JobExecution의 JobInstance ID를 함께 저장하고,
`settlementBatchId`를 JobExecutionContext에 기록한다. 배치를 소스 적재보다 먼저 생성하므로
order-service 조회나 소스 저장이 실패해도 실패 배치를 추적할 수 있다. 배치 생성 자체가 DB
오류로 실패한 경우에는 저장할 도메인 행이 없으므로 Spring Batch 메타데이터와 로그로 확인한다.

### 8.2 재시작

admin-service가 후속 작업에서 `FAILED -> RETRY_REQUESTED`를 먼저 저장한 뒤 Kubernetes
일회성 Job을 생성한다. 일회성 Job은 다음 실행 계약을 사용한다.

```text
settlement.execution.mode=restart
settlement.restart.batch-id=<UUID>
settlement.restart.actor-id=<관리자 UUID>
```

`actor-id`는 별도 재처리 이력 테이블을 만들지 않는 정책에 따라 이번 작업에서는 구조화 로그에만
남긴다. JobInstance의 식별 파라미터와 기존 `triggerType`은 변경하지 않는다. Kubernetes Job의
`trigger-type=MANUAL_RETRY` 라벨은 후속 인프라 작업에서 실행 출처를 나타낸다.

재시작 전용 runner는 배치 ID와 관리자 ID를 검증하고 유스케이스를 동기 호출한다. 최종
JobExecution이 `COMPLETED`면 종료 코드 0, 그 외 상태나 예외는 종료 코드 1을 반환한다.

Spring Batch는 이전 실행에서 완료된 Step을 건너뛴다. JobExecutionContext의
`settlementBatchId`를 이어받으므로 `createSettlementBatchStep`은 다시 실행되지 않고 같은
정산 배치를 사용한다. `beforeJob` 리스너는 기존 배치를 `RETRY_REQUESTED -> PROCESSING`으로
전환한다.

`settlementStep`이 재실행되면 Reader가 해당 주차에서 `settlement_id is null`인 판매자만 다시
조회한다. 완료된 청크의 정산·소스 라인 연결·Outbox 저장은 이미 커밋돼 조회 대상에서 빠진다.
실패가 발생한 청크는 청크 트랜잭션이 롤백되므로 전체 청크가 다시 처리된다.

통합 테스트에서 여러 청크의 실패 지점을 작게 재현할 수 있도록 청크 크기는
`settlement.batch.chunk-size` 설정으로 분리하고 운영 기본값 100을 유지한다.

## 9. Outbox 발행 제한

현재 `retryPendingOutboxStep`은 배치 상태와 무관하게 오래된 `PENDING` 이벤트를 조회한다.
이 조건을 유지하면 실패한 주간 정산의 일부 결과가 다음 주 정산 시작 시 발행될 수 있다.

일반 대기 이벤트 조회에 `settlement_batch.status = COMPLETED` 조건을 추가한다. 커서 기반 첫
조회와 후속 조회에 같은 조건을 적용한다.

- `FAILED`, `RETRY_REQUESTED`, `PROCESSING` 배치의 이벤트는 일반 재시도 대상에서 제외한다.
- `completeSettlementBatchStep`이 커밋된 뒤에만 `flushCurrentBatchOutboxStep`이 현재 배치의
  이벤트를 발행한다.
- 재시작이 완료되면 최초 실행의 완료 청크와 재시작 청크에서 생성된 대기 이벤트를 같은 배치
  ID로 함께 발행한다.
- 완료 이후 Kafka 발행이 일시적으로 실패한 이벤트는 기존 Outbox 재시도 정책을 따른다.

Outbox와 배치 사이에는 JPA 연관관계를 추가하지 않는다. 현재 UUID 연결을 유지하고 조회에서
배치 상태를 명시적으로 결합한다.

## 10. 오류 처리

다음 경우 재시작을 시작하지 않고 종료 코드 1을 반환한다.

- 배치가 존재하지 않는다.
- 배치 상태가 `RETRY_REQUESTED`가 아니다.
- 기존 배치에 `job_instance_id`가 연결되지 않았다.
- Spring Batch JobInstance나 JobExecution 메타데이터가 없다.
- 가장 최근 JobExecution이 `FAILED`가 아니다.
- JobExecutionContext의 배치 ID가 요청 배치와 다르다.
- 다른 실행이 이미 같은 JobInstance를 재시작하고 있다.

배치가 `RETRY_REQUESTED`임을 확인한 뒤 새 JobExecution이 시작되기 전에 오류가 발생하면,
배치를 다시 `FAILED`로 복원하고 원인을 기록한다. 배치 없음이나 잘못된 최초 상태처럼
재시작 요청 상태에 진입하지 않은 오류는 기존 상태를 바꾸지 않는다. 새 JobExecution이 시작된
뒤 실패하면 기존 afterJob 리스너가 `PROCESSING -> FAILED`를 처리한다. 상태 복원은 원래 예외를
삼키지 않으며, 복원 실패도 함께 로그에 남긴다.

최근 실행이 `STARTED`, `STARTING`, `STOPPING`이면 자동으로 실패 처리하거나 `recover()`를
호출하지 않는다. 실제 실행 여부가 확인되지 않은 상태에서 재시작하지 않는 것이 중복 정산보다
안전하다.

## 11. 테스트 전략

### 도메인 단위 테스트

- 신규 배치가 JobInstance ID와 `PROCESSING` 상태로 생성된다.
- `FAILED -> RETRY_REQUESTED -> PROCESSING -> COMPLETED`가 정상 전이된다.
- 재시작 실패 시 `RETRY_REQUESTED -> FAILED`로 복원된다.
- 각 메서드가 허용되지 않은 상태 전이를 거부한다.
- 재시작 시작 시 이전 실패 사유와 종료 시각이 초기화된다.

### 애플리케이션·어댑터 단위 테스트

- 배치 없음, 잘못된 상태와 미연결 레거시 배치를 거부한다.
- JobInstance 없음, 잡 이름 불일치, 최근 실행 상태 불일치와 배치 ID 불일치를 거부한다.
- 가장 최근 `FAILED` JobExecution을 `JobOperator.restart(JobExecution)`에 전달한다.
- 재시작 전 오류가 발생하면 배치 상태를 `FAILED`로 복원한다.
- restart runner가 완료 0, 실패·예외 1을 반환하고 관리자 ID를 로그 계약에 포함한다.

### 영속성·마이그레이션 테스트

- `version`의 낙관적 락으로 동시에 저장한 두 상태 변경 중 하나만 성공한다.
- 연결 가능한 기존 배치는 올바른 JobInstance ID로 보정된다.
- 형식이 다르거나 메타데이터가 없는 기존 배치는 삭제되지 않고 ID가 비어 있다.
- 같은 JobInstance를 두 배치에 연결할 수 없다.
- 상태 CHECK 제약이 `RETRY_REQUESTED`를 허용한다.

### 배치 통합 테스트

통합 테스트는 청크 크기를 작게 설정하고 첫 청크 커밋 후 다음 청크에서 의도적으로 실패시킨다.

1. 최초 실행에서 하나 이상의 청크가 완료되고 다음 청크가 실패한다.
2. 배치가 `FAILED`이며 JobInstance ID가 기록됐는지 확인한다.
3. 완료 청크의 정산, 소스 라인 연결과 Outbox가 남아 있는지 확인한다.
4. 실패 배치의 Outbox가 일반 재시도 조회와 Kafka 발행 대상에서 제외되는지 확인한다.
5. 배치를 `RETRY_REQUESTED`로 전환하고 실패한 JobExecution을 기준으로 재시작한다.
6. 같은 JobInstance 아래 새 JobExecution이 생성되고 같은 `settlementBatchId`를 사용하는지 확인한다.
7. 완료된 소스 라인은 다시 처리되지 않고 미처리 라인만 정산되는지 확인한다.
8. 최종 상태가 `COMPLETED`이며 정산·소스 연결·Outbox가 중복되지 않았는지 확인한다.
9. 최종 완료 이후에만 해당 배치의 이벤트가 발행되는지 확인한다.

추가로 소스 적재 단계에서 실패했을 때도 앞서 생성된 배치가 `FAILED`로 남고, 재시작 시 같은
배치에서 소스 적재 단계부터 이어지는지 검증한다.

### 회귀 검증

```bash
./gradlew :settlement-service:test
./gradlew :settlement-service:build
git diff --check origin/develop...HEAD
```

## 12. 완료 조건

- 모든 신규 정산 배치가 Spring Batch JobInstance와 연결된다.
- 연결할 수 없는 기존 배치는 보존되며 안전하게 재시작이 거부된다.
- 실패한 주간 정산이 같은 JobInstance와 `settlement_batch`로 재시작된다.
- 이미 커밋된 청크는 중복 정산되지 않고 미처리 소스 라인만 처리된다.
- 실패 배치 Outbox 이벤트는 발행되지 않는다.
- 최종 완료 이후 해당 배치의 대기 이벤트만 발행된다.
- 동시에 재시작 상태를 변경해도 낙관적 락으로 하나만 성공한다.
- 완료·진행 중·재시작 요청 중인 배치는 재시작할 수 없다.
- 단위·영속성·배치 통합 테스트와 전체 정산 서비스 회귀 테스트가 통과한다.
