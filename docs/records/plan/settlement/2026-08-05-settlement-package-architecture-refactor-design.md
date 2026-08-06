# Settlement 패키지·아키텍처 경계 리팩토링 설계

## 문서 정보

- 기준 브랜치: `refactor/#706-settlement-package-architecture`
- 관련 이슈: `#706`
- 기준 커밋: `64de86be`
- 작성일: 2026-08-05
- 대상 모듈: `settlement-service`

## 1. 목표

Settlement의 현재 동작을 유지하면서 패키지 응집도와 클린 아키텍처 의존 방향을 정리한다.

- application·domain 모델을 `batch`, `calculation`, `source`, `delivery` 기능별로 묶는다.
- 내부 Order·User gRPC 호출을 `Client` 포트와 gRPC 어댑터로 분리한다.
- Run·Restart·GetStatus 배치 실행 서비스를 유스케이스별로 분리한다.
- Tasklet·Runner가 application 구현체가 아니라 `SettlementDeliveryUseCase`에 의존하게 한다.
- gRPC 타입이 application 계층으로 노출되지 않게 한다.
- 정산 Chunk 전용 Reader·Processor·Writer·Target·Listener를 한 패키지에 모은다.

## 2. 이번 범위에서 하지 않는 것

- P1로 분류한 Seller Settlement 이중 writer와 전달 재시도 동시성 문제를 수정하지 않는다.
- REST API, gRPC proto, DB 스키마, Flyway migration을 변경하지 않는다.
- 정산 배치 Step 순서, 재시도 횟수, backoff, 트랜잭션 전파와 상태 전이를 변경하지 않는다.
- 현재 존재하지 않는 `FeePolicy`, `SettlementTotals`, `DeliveryRetryPolicy`를 추가하지 않는다.
- infrastructure persistence 패키지는 이번 단계에서 재분류하지 않는다.
- ArchUnit 의존성과 구조 테스트를 추가하지 않는다.

## 3. 확정한 설계 원칙

### 3.1 계층 우선, 계층 안에서 기능 응집

최상위는 `presentation`, `application`, `domain`, `infrastructure`, `global`을 유지한다.
`batch`·`calculation`·`source`·`delivery`를 최상위로 올리지 않고 application과 domain 안에서
하위 패키지로 사용한다.

Domain Repository와 Domain Exception은 클래스 수와 현재 결합도를 고려해 평평하게 유지한다.

### 3.2 내부 서비스는 Client

Order·User는 시스템 내부 서비스이므로 `Gateway`를 사용하지 않는다.

```text
application/client/<target>/XxxClient
        ▲
        │ implements
infrastructure/grpc/client/<target>/XxxGrpcClientAdapter
```

제3자 API를 직접 호출하게 될 때만 `application/gateway/external`과
`infrastructure/external/<provider>`를 추가한다.

### 3.3 Chunk 파이프라인 단위 응집

정산 Chunk에서만 함께 변경되는 구성은 `infrastructure/batch/settlement`에 둔다.

```text
SettlementTarget
SettlementTargetReader
SettlementProcessor
SettlementWriter
SettlementBatchStateJobExecutionListener
```

Tasklet과 Runner는 각각 여러 구현이 존재하고 실행 역할이 구분되므로 기존 역할 패키지를 유지한다.

## 4. 목표 패키지 구조

```text
com.prompthub.settlement
├── presentation
│   ├── controller
│   └── dto/{request,response}
├── application
│   ├── usecase
│   │   ├── batch
│   │   │   ├── SettlementBatchLifecycleUseCase
│   │   │   ├── RunSettlementBatchUseCase
│   │   │   ├── RestartSettlementBatchUseCase
│   │   │   ├── GetSettlementJobStatusUseCase
│   │   │   ├── SettlementJobLauncher
│   │   │   ├── SettlementJobQuery
│   │   │   └── SettlementJobRestarter
│   │   ├── calculation
│   │   ├── source
│   │   └── delivery
│   │       ├── SettlementDeliveryUseCase
│   │       ├── DeliveryRetrySleeper
│   │       └── RequiresNewTransactionExecutor
│   ├── service
│   │   ├── batch
│   │   │   ├── SettlementBatchLifecycleApplicationService
│   │   │   ├── RunSettlementBatchApplicationService
│   │   │   ├── RestartSettlementBatchApplicationService
│   │   │   └── GetSettlementJobStatusApplicationService
│   │   ├── calculation
│   │   ├── source
│   │   └── delivery
│   ├── client
│   │   ├── order
│   │   │   └── OrderSettlementClient
│   │   └── user
│   │       ├── SellerSettlementClient
│   │       ├── SellerSettlementClientException
│   │       └── SellerSettlementClientFailure
│   └── dto
│       ├── batch
│       ├── calculation
│       ├── source
│       └── delivery
├── domain
│   ├── model
│   │   ├── batch
│   │   ├── calculation
│   │   ├── source
│   │   └── delivery
│   ├── repository
│   └── exception
├── infrastructure
│   ├── grpc/client
│   │   ├── order
│   │   └── user
│   ├── batch
│   │   ├── config
│   │   ├── execution
│   │   ├── settlement
│   │   ├── tasklet
│   │   └── runner
│   ├── persistence
│   ├── time
│   └── transaction
└── global
    ├── common
    ├── config
    ├── exception
    └── web
```

## 5. 클래스 이동 기준

### 5.1 Application UseCase와 내부 기술 포트

| 현재 | 변경 후 |
| --- | --- |
| `application/usecase/SettlementBatchLifecycleUseCase` | `application/usecase/batch` |
| `application/usecase/RunSettlementBatchUseCase` | `application/usecase/batch` |
| `application/usecase/RestartSettlementBatchUseCase` | `application/usecase/batch` |
| `application/usecase/GetSettlementJobStatusUseCase` | `application/usecase/batch` |
| `application/port/SettlementJobLauncher` | `application/usecase/batch` |
| `application/port/SettlementJobQuery` | `application/usecase/batch` |
| `application/port/SettlementJobRestarter` | `application/usecase/batch` |
| `application/usecase/CalculateSettlementUseCase` | `application/usecase/calculation` |
| `application/usecase/ReconcileSettlementCalculationUseCase` | `application/usecase/calculation` |
| `application/usecase/LoadSettlementSourceUseCase` | `application/usecase/source` |
| `application/usecase/ReconcileSettlementSourceUseCase` | `application/usecase/source` |
| `application/port/DeliveryRetrySleeper` | `application/usecase/delivery` |
| `application/port/RequiresNewTransactionExecutor` | `application/usecase/delivery` |

`application/port`는 이동 완료 후 제거한다.

### 5.2 Application Service

기존 `SettlementBatchExecutionApplicationService`는 다음 세 구현체로 분리한다.

- `RunSettlementBatchApplicationService`
- `RestartSettlementBatchApplicationService`
- `GetSettlementJobStatusApplicationService`

나머지 서비스는 클래스 책임을 바꾸지 않고 기능 패키지로 이동한다.

### 5.3 Application DTO

| 기능 | DTO |
| --- | --- |
| batch | `CreateSettlementBatchCommand`, `RunSettlementBatchCommand`, `RestartSettlementBatchCommand`, `SettlementJobResult`, `SettlementJobStatusResult` |
| calculation | `CalculateSettlementCommand`, `SettlementCalculationReconciliationReport` |
| source | `SettleableLine`, `SettlementSourceReconciliationResult` |
| delivery | `SellerSettlementRegistrationCommand`, `SellerSettlementStoredSnapshot`, `SettlementDeliveryAttempt`, `SettlementDeliveryComparison`, `SettlementDeliverySummary` |

### 5.4 Domain Model과 Enum

| 기능 | 모델·enum |
| --- | --- |
| batch | `SettlementBatch`, `SettlementPeriod`, `SettlementBatchStatus`, `TriggerType` |
| calculation | `Settlement`, `SettlementDetail`, `SettlementCalculationReconciliation`, `SettlementCalculationSummary`, `SettlementCalculationReconciliationStatus`, `SettlementLineType` |
| source | `SettlementSourceLine`, `SettlementSourceLineType` |
| delivery | `SettlementDelivery`, `SettlementDeliveryStatus` |

`domain/model/enums`는 이동 완료 후 제거한다.

### 5.5 내부 gRPC Client

| 현재 | 변경 후 |
| --- | --- |
| `application/port/OrderSettlementQuery` | `application/client/order/OrderSettlementClient` |
| `infrastructure/client/order/OrderSettlementQueryClient` | `infrastructure/grpc/client/order/OrderSettlementGrpcClientAdapter` |
| `application/port/SellerSettlementRegistration` | `application/client/user/SellerSettlementClient` |
| `infrastructure/client/user/SellerSettlementCommandGrpcClient` | `infrastructure/grpc/client/user/SellerSettlementGrpcClientAdapter` |
| `SellerSettlementCommandGrpcMapper` | `SellerSettlementGrpcMapper` |
| `SellerSettlementCommandGrpcClientConfig` | `SellerSettlementGrpcClientConfig` |
| `SellerSettlementCommandGrpcProperties` | `SellerSettlementGrpcClientProperties` |

Order 설정은 대상 패키지에 `OrderSettlementGrpcClientConfig`로 둔다. User는 설정과 properties가
둘이므로 `infrastructure/grpc/client/user/config`에 함께 둔다.

## 6. 호출 경계

### 6.1 배치 실행

```text
SettlementBatchController
├── RunSettlementBatchUseCase
│   └── RunSettlementBatchApplicationService
│       └── SettlementJobLauncher
├── RestartSettlementBatchUseCase
│   └── RestartSettlementBatchApplicationService
│       ├── SettlementBatchLifecycleUseCase
│       └── SettlementJobRestarter
└── GetSettlementJobStatusUseCase
    └── GetSettlementJobStatusApplicationService
        └── SettlementJobQuery
```

기존 restart 실패 시 batch 상태 복원과 suppressed exception 처리 흐름은 그대로 보존한다.

### 6.2 Delivery

```text
DeliverSellerSettlementsTasklet ─┐
                                 ├── SettlementDeliveryUseCase
SettlementDeliveryRetryRunner ───┘       │
                                         ▼
                        SettlementDeliveryApplicationService
                        ├── SellerSettlementClient
                        ├── DeliveryRetrySleeper
                        ├── SettlementDeliveryTransactionService
                        └── SettlementDeliveryReconciler
```

`SettlementDeliveryUseCase`는 현재 공개 동작과 같은 두 메서드를 제공한다.

```java
SettlementDeliverySummary deliverBatch(UUID batchId);
SettlementDeliveryStatus retry(UUID deliveryId);
```

## 7. gRPC 오류 격리

`SellerSettlementGrpcClientAdapter`가 `StatusRuntimeException`을 해석하고 application의 기술 중립 타입으로
변환한다.

```text
SellerSettlementClientException
└── SellerSettlementClientFailure
    ├── code: String
    └── retryable: boolean
```

- 기존 gRPC status 이름을 `code` 문자열로 보존한다.
- `UNAVAILABLE`, `DEADLINE_EXCEEDED`만 `retryable=true`로 변환한다.
- 기존 로그와 DB 실패 사유의 코드 문자열을 유지한다.
- application과 domain에서 `io.grpc` import를 제거한다.

## 8. 구현 단계

### 단계 1 — 패키지·이름 정렬

- application usecase·service·dto를 기능 패키지로 이동한다.
- domain model·enum을 기능 패키지로 이동한다.
- 내부 gRPC 포트·어댑터·설정·매퍼를 이동하고 이름을 변경한다.
- Listener를 `infrastructure/batch/settlement`로 이동한다.
- 운영 코드와 테스트 코드의 package/import를 함께 수정한다.
- 이 단계에서는 클래스 책임과 제어 흐름을 바꾸지 않는다.

### 단계 2 — 아키텍처 경계 정리

- `SettlementBatchExecutionApplicationService`를 세 구현체로 분리한다.
- `SettlementDeliveryUseCase`를 추가한다.
- Tasklet·Runner가 Delivery UseCase를 주입받게 한다.
- Client 실패 타입과 예외를 추가하고 gRPC 타입을 infrastructure에 격리한다.
- 기존 재시도 횟수·backoff·상태 변경·오류 문자열을 보존한다.

각 단계는 컴파일과 테스트가 통과한 상태로 종료한다. 한 단계의 실패를 다음 단계 변경으로 덮지 않는다.

## 9. 검증 전략

### 변경 전

```bash
./gradlew :settlement-service:test
```

기존 실패가 있으면 리팩토링 실패와 구분하고 진행 여부를 다시 확인한다.

### 단계 1 완료 조건

```bash
./gradlew :settlement-service:compileJava
./gradlew :settlement-service:compileTestJava
./gradlew :settlement-service:test
```

### 단계 2 집중 검증

- Run·Restart·GetStatus ApplicationService 테스트
- Settlement Delivery ApplicationService·Tasklet·Runner 테스트
- Seller Settlement Client 실패 변환 테스트
- Order·User gRPC Client adapter 테스트
- Settlement Batch Listener 테스트

집중 테스트 뒤 `./gradlew :settlement-service:test`를 다시 실행한다.

### 정적 확인

다음 패턴이 남아 있지 않아야 한다.

- `com.prompthub.settlement.application.port`
- `com.prompthub.settlement.infrastructure.client`
- application 아래의 `io.grpc` import
- Tasklet·Runner의 `application.service` import
- `infrastructure.batch.listener.SettlementBatchStateJobExecutionListener`

마지막으로 `git diff --check`와 변경 파일 목록을 확인하고 REST·proto·migration 무변경을 증명한다.

## 10. 완료 기준

- 목표 패키지 구조와 클래스 이름이 본 문서와 일치한다.
- 기존 Settlement 테스트가 동일하게 통과한다.
- application/domain에 infrastructure·gRPC 기술 의존이 새지 않는다.
- Tasklet·Runner가 유스케이스 포트에만 의존한다.
- 배치 순서, 정산 계산, 전달 재시도와 상태 전이 동작이 변경되지 않는다.
- P1과 신규 정책 객체는 이번 diff에 포함되지 않는다.
