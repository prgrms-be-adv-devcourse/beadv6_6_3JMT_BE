# Settlement Package Architecture Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 정산 서비스의 현재 동작과 외부 계약을 유지하면서 application·domain을 기능별로 응집시키고, 배치 실행 책임과 내부 gRPC Client 경계를 클린 아키텍처 규칙에 맞게 정리한다.

**Architecture:** 최상위 `presentation/application/domain/infrastructure/global` 계층은 유지한다. application과 domain 안에서 `batch/calculation/source/delivery` 기능 패키지를 사용하고, 내부 서비스 gRPC 연동은 `application/client` 포트를 `infrastructure/grpc/client` 어댑터가 구현한다. 먼저 패키지 이동과 이름 변경만 완료한 뒤, 배치 실행 서비스 분리와 gRPC 예외 격리를 적용한다.

**Tech Stack:** Java 21, Spring Boot, Spring Batch, Spring Data JPA, gRPC Java, Gradle multi-module, JUnit 5, Mockito, AssertJ, Testcontainers PostgreSQL

---

## 0. 실행 제약과 완료 조건

- 관련 설계: `settlement-service/docs/superpowers/specs/2026-08-05-settlement-package-architecture-refactor-design.md`
- 관련 이슈: `#706`
- 기준 커밋: `64de86be`
- 작업 브랜치: `refactor/#706-settlement-package-architecture`
- REST API, gRPC proto, DB 스키마, Flyway migration, 배치 Step 순서, 재시도 횟수·backoff·상태 전이는 변경하지 않는다.
- P1 동시성 문제, ArchUnit, 신규 도메인 정책 타입, persistence 재분류는 이 계획에서 제외한다.
- 저장소에 이미 존재하는 다른 문서 변경을 수정하거나 되돌리지 않는다.
- 사용자의 명시적 요청 전에는 commit, push, PR 생성을 하지 않는다.
- 각 이동은 `git mv`로 이력을 보존하고 package 선언·import·Javadoc 경로를 함께 갱신한다.
- 전체 테스트가 Docker/Testcontainers 환경 때문에 실패하면 코드 실패와 환경 실패를 분리하여 기록하고 `UNVERIFIED`로 보고한다.

완료 조건:

- `application/port`, `domain/model/enums`, `infrastructure/client`, `infrastructure/batch/listener`가 비어 제거된다.
- `SettlementBatchExecutionApplicationService`, `OrderSettlementQuery`, `SellerSettlementRegistration`, `SellerSettlementDeliveryException` 참조가 남지 않는다.
- application 소스가 `io.grpc`를 import하지 않는다.
- 관련 단위·통합 테스트와 `:settlement-service:test`가 통과한다. 환경상 실행 불가한 검증은 이유와 함께 명시한다.
- `git diff --check`가 통과하고 요청 범위 밖 파일이 새로 변경되지 않는다.

## Task 1: 기준선과 이동 목록을 고정한다

**Files:**

- Verify: `settlement-service/src/main/java/com/prompthub/settlement/**`
- Verify: `settlement-service/src/test/java/com/prompthub/settlement/**`
- Verify: `settlement-service/build.gradle`
- Preserve: `.claude/rules/clean-architecture.md`
- Preserve: `payment-service/.claude/rules/architecture.md`
- Preserve: `docs/superpowers/plans/2026-08-05-clean-architecture-client-gateway-convention.md`

- [x] **Step 1: 작업 기준을 확인한다**

Run:

```bash
git branch --show-current
git rev-parse HEAD
git status --short
```

Expected:

- branch는 `refactor/#706-settlement-package-architecture`
- HEAD는 계획 기준인 `64de86be`이거나, 이후 사용자 승인으로 생성된 이 브랜치의 커밋
- 기존 문서 변경 외 Java 변경은 없음

- [x] **Step 2: 기준 테스트를 실행한다**

Run:

```bash
./gradlew :settlement-service:test
```

Expected: `BUILD SUCCESSFUL`. Testcontainers/Docker 실패 시 실패 테스트와 root cause를 기록하고 구현은 정적 컴파일 및 비컨테이너 테스트로 계속 검증한다.

- [x] **Step 3: 기존 이름과 금지 의존성을 스냅샷으로 확인한다**

Run:

```bash
rg -n "SettlementBatchExecutionApplicationService|OrderSettlementQuery|SellerSettlementRegistration|SellerSettlementDeliveryException|infrastructure\.batch\.listener" settlement-service/src/main settlement-service/src/test
rg -n "^import io\.grpc" settlement-service/src/main/java/com/prompthub/settlement/application
```

Expected: 첫 명령은 현재 이동·교체 대상을 출력하고, 두 번째 명령은 기존 `SellerSettlementDeliveryException`의 `io.grpc.Status` import만 출력한다.

## Task 2: Domain 모델과 enum을 기능 패키지로 이동한다

**Files:**

- Move to `domain/model/batch`: `SettlementBatch.java`, `SettlementPeriod.java`, `SettlementBatchStatus.java`, `TriggerType.java`
- Move to `domain/model/calculation`: `Settlement.java`, `SettlementDetail.java`, `SettlementCalculationReconciliation.java`, `SettlementCalculationSummary.java`, `SettlementCalculationReconciliationStatus.java`, `SettlementLineType.java`
- Move to `domain/model/source`: `SettlementSourceLine.java`, `SettlementSourceLineType.java`
- Move to `domain/model/delivery`: `SettlementDelivery.java`, `SettlementDeliveryStatus.java`
- Keep: `domain/repository/*.java`, `domain/exception/*.java`
- Move matching tests to `src/test/java/com/prompthub/settlement/domain/model/{batch,calculation,source,delivery}`
- Modify imports in all `settlement-service/src/main/java/**` and `settlement-service/src/test/java/**` consumers

- [x] **Step 1: Domain 테스트 파일을 목표 패키지로 먼저 이동한다**

Move these tests and update package declarations/imports:

```text
batch/SettlementBatchTest.java
batch/SettlementPeriodTest.java
calculation/SettlementTest.java
calculation/SettlementDetailTest.java
calculation/SettlementCalculationReconciliationTest.java
source/SettlementSourceLineTest.java
delivery/SettlementDeliveryTest.java
```

- [x] **Step 2: 테스트 컴파일 실패로 아직 이동하지 않은 production 패키지를 확인한다**

Run:

```bash
./gradlew :settlement-service:compileTestJava
```

Expected: 새 domain package를 찾지 못하는 컴파일 실패. 다른 의미의 실패가 섞이면 먼저 원인을 분리한다.

- [x] **Step 3: Domain 모델과 enum을 `git mv`하고 참조를 갱신한다**

구현 규칙:

- enum은 별도 `enums` 패키지를 만들지 않고 소유 모델과 같은 기능 패키지에 둔다.
- Entity의 `@Entity`, `@Table`, 컬럼, 연관관계, 생성자, 상태 전이 메서드는 변경하지 않는다.
- repository와 persistence adapter의 generic type/import만 새 패키지에 맞춘다.
- Javadoc의 예전 package 경로도 함께 고친다.

- [x] **Step 4: Domain 및 전체 소스 컴파일을 검증한다**

Run:

```bash
./gradlew :settlement-service:compileJava :settlement-service:compileTestJava
./gradlew :settlement-service:test --tests 'com.prompthub.settlement.domain.model.*'
```

Expected: 두 명령 모두 `BUILD SUCCESSFUL`.

- [x] **Step 5: 이전 평면 package가 제거됐는지 확인한다**

Run:

```bash
find settlement-service/src/main/java/com/prompthub/settlement/domain/model -maxdepth 2 -type f | sort
rg -n "domain\.model\.enums|domain\.model\.(Settlement|SettlementBatch|SettlementPeriod|SettlementSourceLine|SettlementDelivery)" settlement-service/src/main settlement-service/src/test
```

Expected: 파일은 네 기능 하위 패키지에 있고, 두 번째 명령은 결과가 없어야 한다.

## Task 3: Application DTO·UseCase·Service를 기능 패키지로 이동한다

**Files:**

- Move DTOs to `application/dto/batch`: `CreateSettlementBatchCommand.java`, `RunSettlementBatchCommand.java`, `RestartSettlementBatchCommand.java`, `SettlementJobResult.java`, `SettlementJobStatusResult.java`
- Move DTOs to `application/dto/calculation`: `CalculateSettlementCommand.java`, `SettlementCalculationReconciliationReport.java`
- Move DTOs to `application/dto/source`: `SettleableLine.java`, `SettlementSourceReconciliationResult.java`
- Move DTOs to `application/dto/delivery`: `SellerSettlementRegistrationCommand.java`, `SellerSettlementStoredSnapshot.java`, `SettlementDeliveryAttempt.java`, `SettlementDeliveryComparison.java`, `SettlementDeliverySummary.java`
- Move use cases to `application/usecase/batch`: `SettlementBatchLifecycleUseCase.java`, `RunSettlementBatchUseCase.java`, `RestartSettlementBatchUseCase.java`, `GetSettlementJobStatusUseCase.java`
- Move use cases to `application/usecase/calculation`: `CalculateSettlementUseCase.java`, `ReconcileSettlementCalculationUseCase.java`
- Move use cases to `application/usecase/source`: `LoadSettlementSourceUseCase.java`, `ReconcileSettlementSourceUseCase.java`
- Move technical batch ports from `application/port` to `application/usecase/batch`: `SettlementJobLauncher.java`, `SettlementJobQuery.java`, `SettlementJobRestarter.java`
- Move delivery ports from `application/port` to `application/usecase/delivery`: `DeliveryRetrySleeper.java`, `RequiresNewTransactionExecutor.java`
- Move services to `application/service/batch`: `SettlementBatchLifecycleApplicationService.java`, `SettlementBatchExecutionApplicationService.java` (임시; Task 6에서 분리·삭제)
- Move services to `application/service/calculation`: `SettlementCalculationApplicationService.java`, `SettlementCalculationReconciliationApplicationService.java`
- Move services to `application/service/source`: `SettlementSourceApplicationService.java`, `SettlementSourceReconciliationApplicationService.java`
- Move services to `application/service/delivery`: `SettlementDeliveryApplicationService.java`, `SettlementDeliveryTransactionService.java`, `SettlementDeliveryReconciler.java`
- Move matching application tests to mirrored package paths
- Modify all main/test consumers, including presentation, batch config/execution/tasklet/runner, persistence, time, transaction

- [x] **Step 1: DTO를 네 기능 패키지로 이동하고 import를 갱신한다**

Run after changes:

```bash
./gradlew :settlement-service:compileJava :settlement-service:compileTestJava
```

Expected: `BUILD SUCCESSFUL`. record component, validation, factory method는 변경하지 않는다.

- [x] **Step 2: UseCase와 내부 기술 포트를 기능 패키지로 이동한다**

구현 규칙:

- 인터페이스 이름과 메서드 시그니처는 이 단계에서 유지한다.
- `SettlementJobLauncher`, `SettlementJobQuery`, `SettlementJobRestarter`는 batch 실행 경계이므로 `usecase/batch`에 둔다.
- `DeliveryRetrySleeper`, `RequiresNewTransactionExecutor`는 delivery orchestration 경계이므로 `usecase/delivery`에 둔다.
- `OrderSettlementQuery`와 `SellerSettlementRegistration`은 Task 4에서 rename할 때까지 `application/port`에 남긴다.

- [x] **Step 3: Service와 테스트를 기능 패키지로 이동한다**

테스트 매핑:

```text
service/batch/SettlementBatchLifecycleApplicationServiceTest.java
service/batch/SettlementBatchExecutionApplicationServiceTest.java
service/calculation/SettlementCalculationApplicationServiceTest.java
service/calculation/SettlementCalculationReconciliationApplicationServiceTest.java
service/source/SettlementSourceApplicationServiceTest.java
service/source/SettlementSourceReconciliationApplicationServiceTest.java
service/delivery/SettlementDeliveryApplicationServiceTest.java
service/delivery/SettlementDeliveryReconcilerTest.java
```

구현 규칙: 클래스 본문과 Spring stereotype/transaction annotation은 이동에 필요한 import 외에는 변경하지 않는다.

- [x] **Step 4: Application 관련 단위 테스트를 검증한다**

Run:

```bash
./gradlew :settlement-service:test --tests 'com.prompthub.settlement.application.*'
```

Expected: `BUILD SUCCESSFUL`.

- [x] **Step 5: 이전 평면 DTO·UseCase·Service 참조를 확인한다**

Run:

```bash
find settlement-service/src/main/java/com/prompthub/settlement/application -maxdepth 3 -type f | sort
rg -n "application\.(dto|usecase|service)\.[A-Z]" settlement-service/src/main settlement-service/src/test
```

Expected: 새 기능 패키지 구조가 출력되고, 평면 패키지에서 클래스를 import한 결과는 없어야 한다.

## Task 4: 내부 gRPC 포트와 어댑터를 Client 규칙으로 이동·개명한다

**Files:**

- Create by rename: `application/client/order/OrderSettlementClient.java`
- Create by rename: `application/client/user/SellerSettlementClient.java`
- Move/rename: `infrastructure/client/order/OrderSettlementQueryClient.java` → `infrastructure/grpc/client/order/OrderSettlementGrpcClientAdapter.java`
- Move/rename: `infrastructure/client/order/config/OrderGrpcClientConfig.java` → `infrastructure/grpc/client/order/OrderSettlementGrpcClientConfig.java`
- Move/rename: `infrastructure/client/user/SellerSettlementCommandGrpcClient.java` → `infrastructure/grpc/client/user/SellerSettlementGrpcClientAdapter.java`
- Move/rename: `infrastructure/client/user/SellerSettlementCommandGrpcMapper.java` → `infrastructure/grpc/client/user/SellerSettlementGrpcMapper.java`
- Move/rename: `infrastructure/client/user/config/SellerSettlementCommandGrpcClientConfig.java` → `infrastructure/grpc/client/user/config/SellerSettlementGrpcClientConfig.java`
- Move/rename: `infrastructure/client/user/config/SellerSettlementCommandGrpcProperties.java` → `infrastructure/grpc/client/user/config/SellerSettlementGrpcClientProperties.java`
- Move/rename tests: `OrderSettlementQueryClientTest.java` → `infrastructure/grpc/client/order/OrderSettlementGrpcClientAdapterTest.java`
- Move/rename tests: `SellerSettlementCommandGrpcContractTest.java` → `infrastructure/grpc/client/user/SellerSettlementGrpcClientContractTest.java`
- Modify: source application services, delivery service, batch integration tests and client mocks

- [x] **Step 1: Application Client 인터페이스 이름과 위치를 변경한다**

`OrderSettlementClient`의 계약:

```java
public interface OrderSettlementClient {
    List<SettleableLine> fetchSettleableLines(SettlementPeriod period);
}
```

`SellerSettlementClient`의 계약:

```java
public interface SellerSettlementClient {
    SellerSettlementStoredSnapshot register(
            SellerSettlementRegistrationCommand command);
}
```

`SettlementSourceApplicationService`, `SettlementSourceReconciliationApplicationService`, `SettlementDeliveryApplicationService`와 관련 테스트·통합 테스트의 주입 타입을 새 Client로 교체한다.

- [x] **Step 2: gRPC 어댑터·mapper·config를 목표 infrastructure 패키지로 이동한다**

구현 규칙:

- Order 어댑터는 `OrderSettlementClient`를 구현하고 기존 `SettlementException(SETTLEMENT_SOURCE_QUERY_FAILED)` 변환을 유지한다.
- Seller 어댑터는 `SellerSettlementClient`를 구현하고, 예외 타입 변경은 Task 7까지 미룬다.
- Spring bean 메서드, 설정 키, property prefix, deadline, internal token metadata를 변경하지 않는다.
- `infrastructure/client`는 이동 완료 후 제거한다.

- [x] **Step 3: Client adapter와 contract 테스트를 새 이름·패키지로 이동한다**

테스트 assertion은 기존 gRPC request mapping, response mapping, deadline/token, 실패 변환 의미를 유지한다. 클래스명·package·대상 타입만 새 이름으로 바꾼다.

- [x] **Step 4: Client 관련 테스트와 컴파일을 검증한다**

Run:

```bash
./gradlew :settlement-service:compileJava :settlement-service:compileTestJava
./gradlew :settlement-service:test --tests 'com.prompthub.settlement.infrastructure.grpc.client.*'
./gradlew :settlement-service:test --tests 'com.prompthub.settlement.application.service.source.*' --tests 'com.prompthub.settlement.application.service.delivery.*'
```

Expected: 모두 `BUILD SUCCESSFUL`.

- [x] **Step 5: 예전 Port·Client 명칭이 제거됐는지 확인한다**

Run:

```bash
rg -n "OrderSettlementQuery|SellerSettlementRegistration|OrderSettlementQueryClient|SellerSettlementCommandGrpc(Client|Mapper|Properties)|infrastructure\.client" settlement-service/src/main settlement-service/src/test
```

Expected: DTO `SellerSettlementRegistrationCommand`을 제외하면 이전 포트·어댑터·패키지 결과가 없어야 한다.

## Task 5: Chunk 파이프라인 Listener를 settlement 패키지에 응집한다

**Files:**

- Move: `infrastructure/batch/listener/SettlementBatchStateJobExecutionListener.java` → `infrastructure/batch/settlement/SettlementBatchStateJobExecutionListener.java`
- Move test: `infrastructure/batch/listener/SettlementBatchStateJobExecutionListenerTest.java` → `infrastructure/batch/settlement/SettlementBatchStateJobExecutionListenerTest.java`
- Modify: `infrastructure/batch/config/SettlementJobConfig.java`

- [x] **Step 1: Listener와 테스트를 `batch/settlement`로 이동한다**

`SettlementTarget`, `SettlementTargetReader`, `SettlementProcessor`, `SettlementWriter`와 같은 패키지에 둔다. Listener의 Job 상태 전이, exit status 해석, 실패 사유 계산은 변경하지 않는다.

- [x] **Step 2: JobConfig import와 테스트 package를 갱신한다**

Run:

```bash
./gradlew :settlement-service:test --tests 'com.prompthub.settlement.infrastructure.batch.settlement.*'
./gradlew :settlement-service:test --tests 'com.prompthub.settlement.infrastructure.batch.config.*'
```

Expected: `BUILD SUCCESSFUL`.

- [x] **Step 3: Stage 1 전체 검증을 실행한다**

Run:

```bash
./gradlew :settlement-service:test
git diff --check
```

Expected: `BUILD SUCCESSFUL`, `git diff --check` 출력 없음. Docker 환경 실패는 환경 원인과 미검증 테스트를 따로 기록한다.

## Task 6: 배치 실행 Application Service를 유스케이스별로 분리한다

**Files:**

- Create: `application/service/batch/RunSettlementBatchApplicationService.java`
- Create: `application/service/batch/RestartSettlementBatchApplicationService.java`
- Create: `application/service/batch/GetSettlementJobStatusApplicationService.java`
- Delete: `application/service/batch/SettlementBatchExecutionApplicationService.java`
- Create tests: `application/service/batch/RunSettlementBatchApplicationServiceTest.java`
- Create tests: `application/service/batch/RestartSettlementBatchApplicationServiceTest.java`
- Create tests: `application/service/batch/GetSettlementJobStatusApplicationServiceTest.java`
- Delete: `application/service/batch/SettlementBatchExecutionApplicationServiceTest.java`
- Modify if imports require it: `presentation/controller/SettlementBatchController.java`, batch runners/integration tests

- [x] **Step 1: 기존 테스트를 책임별 세 테스트 클래스로 먼저 분리한다**

테스트 이동 기준:

- `run_delegatesToLauncher` → `RunSettlementBatchApplicationServiceTest`
- `getStatus_existingExecution_returnsStatus`, `getStatus_missingExecution_throwsException` → `GetSettlementJobStatusApplicationServiceTest`
- 나머지 restart·복원·suppressed exception 테스트 → `RestartSettlementBatchApplicationServiceTest`

새 production 클래스가 아직 없으므로 이 시점의 `compileTestJava`는 세 클래스 미존재로 실패해야 한다.

- [x] **Step 2: Run 서비스를 구현한다**

```java
@Service
@RequiredArgsConstructor
public class RunSettlementBatchApplicationService
        implements RunSettlementBatchUseCase {
    private final SettlementJobLauncher settlementJobLauncher;

    @Override
    public SettlementJobResult run(RunSettlementBatchCommand command) {
        return settlementJobLauncher.launch(command);
    }
}
```

- [x] **Step 3: GetStatus 서비스를 구현한다**

```java
@Service
@RequiredArgsConstructor
public class GetSettlementJobStatusApplicationService
        implements GetSettlementJobStatusUseCase {
    private final SettlementJobQuery settlementJobQuery;

    @Override
    public SettlementJobStatusResult getStatus(Long jobExecutionId) {
        return settlementJobQuery.findByJobExecutionId(jobExecutionId)
                .orElseThrow(() -> new SettlementException(
                        SettlementErrorCode.SETTLEMENT_JOB_NOT_FOUND));
    }
}
```

- [x] **Step 4: Restart 서비스를 구현한다**

의존성은 `SettlementJobRestarter`, `SettlementBatchLifecycleUseCase` 두 개만 둔다. 기존 서비스의 아래 의미를 그대로 옮긴다.

- `requireRetryJobInstanceId` 호출 후 동일 `batchId/jobInstanceId`로 restart
- `SETTLEMENT_BATCH_JOB_INSTANCE_NOT_LINKED`일 때 FAILED 복원
- restarter 실패 시 원래 예외 재throw 및 FAILED 복원
- 복원도 실패하면 원래 예외에 suppressed exception 추가
- blank/null message면 `정산 배치 재시작 실행 실패` 사용
- 복원 실패 로그의 `batchId`, `actorId` 유지

- [x] **Step 5: 기존 통합 서비스를 삭제하고 분리 테스트를 실행한다**

Run:

```bash
./gradlew :settlement-service:test --tests 'com.prompthub.settlement.application.service.batch.RunSettlementBatchApplicationServiceTest' --tests 'com.prompthub.settlement.application.service.batch.RestartSettlementBatchApplicationServiceTest' --tests 'com.prompthub.settlement.application.service.batch.GetSettlementJobStatusApplicationServiceTest'
rg -n "SettlementBatchExecutionApplicationService" settlement-service/src/main settlement-service/src/test
```

Expected: 테스트 `BUILD SUCCESSFUL`, `rg` 결과 없음.

## Task 7: Delivery 진입점을 UseCase로 추상화한다

**Files:**

- Create: `application/usecase/delivery/SettlementDeliveryUseCase.java`
- Modify: `application/service/delivery/SettlementDeliveryApplicationService.java`
- Modify: `infrastructure/batch/tasklet/DeliverSellerSettlementsTasklet.java`
- Modify: `infrastructure/batch/runner/SettlementDeliveryRetryRunner.java`
- Modify: `infrastructure/batch/runner/SettlementDeliveryRetryRunnerTest.java`
- Verify: `infrastructure/batch/SettlementBatchRestartIntegrationTest.java`
- Verify: `infrastructure/batch/SettlementSourceReconciliationBatchIntegrationTest.java`

- [x] **Step 1: Tasklet·Runner 테스트를 구현체가 아닌 새 UseCase mock 기준으로 먼저 변경한다**

`SettlementDeliveryRetryRunnerTest`의 mock, `ApplicationContextRunner.withBean`, 생성자 인자를 `SettlementDeliveryUseCase`로 바꾼다. `DeliverSellerSettlementsTasklet`에 직접 단위 테스트가 없으므로 컴파일과 batch 통합 테스트로 wiring을 검증한다.

- [x] **Step 2: UseCase 인터페이스를 추가한다**

```java
public interface SettlementDeliveryUseCase {
    SettlementDeliverySummary deliverBatch(UUID batchId);

    SettlementDeliveryStatus retry(UUID deliveryId);
}
```

- [x] **Step 3: Application Service와 infrastructure 진입점의 의존성을 변경한다**

- `SettlementDeliveryApplicationService implements SettlementDeliveryUseCase`
- 두 공개 메서드에 `@Override`
- `DeliverSellerSettlementsTasklet`과 `SettlementDeliveryRetryRunner`는 `SettlementDeliveryUseCase`만 주입
- 재시도/집계/트랜잭션 로직은 변경하지 않음

- [x] **Step 4: Delivery wiring을 검증한다**

Run:

```bash
./gradlew :settlement-service:test --tests 'com.prompthub.settlement.infrastructure.batch.runner.SettlementDeliveryRetryRunnerTest' --tests 'com.prompthub.settlement.application.service.delivery.SettlementDeliveryApplicationServiceTest'
./gradlew :settlement-service:compileJava :settlement-service:compileTestJava
```

Expected: 모두 `BUILD SUCCESSFUL`.

## Task 8: Seller gRPC 실패를 application 기술 중립 타입으로 격리한다

**Files:**

- Create: `application/client/user/SellerSettlementClientFailure.java`
- Create: `application/client/user/SellerSettlementClientException.java`
- Create test: `application/client/user/SellerSettlementClientExceptionTest.java`
- Modify: `infrastructure/grpc/client/user/SellerSettlementGrpcClientAdapter.java`
- Modify: `application/service/delivery/SettlementDeliveryApplicationService.java`
- Modify: `application/service/delivery/SettlementDeliveryApplicationServiceTest.java`
- Modify: `infrastructure/grpc/client/user/SellerSettlementGrpcClientContractTest.java`
- Delete: `application/exception/SellerSettlementDeliveryException.java`
- Delete: `application/exception/SellerSettlementDeliveryExceptionTest.java`

- [x] **Step 1: 기술 중립 실패 모델 테스트를 먼저 작성한다**

검증 항목:

- `UNAVAILABLE`, `DEADLINE_EXCEEDED`에 해당하는 failure는 `retryable=true`를 보존
- `INVALID_ARGUMENT` 등 나머지는 `retryable=false`
- exception은 `code`, `retryable`, message, cause를 손실 없이 노출
- application 테스트는 `io.grpc.Status`를 import하지 않고 문자열 code와 boolean만 사용

- [x] **Step 2: 실패 record와 exception을 추가한다**

```java
public record SellerSettlementClientFailure(
        String code,
        boolean retryable) {
}
```

```java
public class SellerSettlementClientException extends RuntimeException {
    private final SellerSettlementClientFailure failure;

    public SellerSettlementClientException(
            SellerSettlementClientFailure failure,
            String message,
            Throwable cause) {
        super(message, cause);
        this.failure = failure;
    }

    public SellerSettlementClientFailure getFailure() {
        return failure;
    }
}
```

retryability 결정은 application 타입이 아니라 gRPC 어댑터의 private mapping 메서드에서 한다.

- [x] **Step 3: Seller gRPC adapter에서 `StatusRuntimeException`을 변환한다**

매핑 규칙:

```text
Status.Code.UNAVAILABLE          → code="UNAVAILABLE", retryable=true
Status.Code.DEADLINE_EXCEEDED    → code="DEADLINE_EXCEEDED", retryable=true
그 외 Status.Code               → code=statusCode.name(), retryable=false
```

message는 기존과 동일한 `gRPC <CODE>`, cause는 원래 `StatusRuntimeException`을 유지한다.

- [x] **Step 4: Delivery 서비스가 기술 중립 exception만 처리하도록 바꾼다**

두 catch 블록을 `SellerSettlementClientException`으로 바꾸고 다음 출력 의미를 유지한다.

- 로그 `grpcStatus`는 `exception.getFailure().code()`
- retry 판단은 `exception.getFailure().retryable()`
- DB 실패 사유는 `gRPC <CODE>: attempts=<N>`
- backoff index, 최대 시도 수, mismatch 처리, 다음 delivery 계속 처리 동작은 변경하지 않음

- [x] **Step 5: 기존 gRPC 결합 exception을 삭제하고 테스트한다**

Run:

```bash
./gradlew :settlement-service:test --tests 'com.prompthub.settlement.application.client.user.SellerSettlementClientExceptionTest' --tests 'com.prompthub.settlement.application.service.delivery.SettlementDeliveryApplicationServiceTest' --tests 'com.prompthub.settlement.infrastructure.grpc.client.user.SellerSettlementGrpcClientContractTest'
rg -n "^import io\.grpc|SellerSettlementDeliveryException" settlement-service/src/main/java/com/prompthub/settlement/application settlement-service/src/test/java/com/prompthub/settlement/application
```

Expected: 테스트 `BUILD SUCCESSFUL`, `rg` 결과 없음.

## Task 9: 최종 정적 검사와 회귀 테스트를 수행한다

**Files:**

- Verify: `settlement-service/src/main/java/com/prompthub/settlement/**`
- Verify: `settlement-service/src/test/java/com/prompthub/settlement/**`
- Verify: `settlement-service/src/main/resources/**`
- Verify: repository-wide dirty files

- [x] **Step 1: 제거 대상 이름과 패키지를 검사한다**

Run:

```bash
rg -n "SettlementBatchExecutionApplicationService|OrderSettlementQuery|SellerSettlementRegistration|SellerSettlementDeliveryException|domain\.model\.enums|infrastructure\.client|infrastructure\.batch\.listener" settlement-service/src/main settlement-service/src/test
find settlement-service/src/main/java/com/prompthub/settlement/application/port settlement-service/src/main/java/com/prompthub/settlement/domain/model/enums settlement-service/src/main/java/com/prompthub/settlement/infrastructure/client settlement-service/src/main/java/com/prompthub/settlement/infrastructure/batch/listener -type f
```

Expected:

- 첫 명령은 `SellerSettlementRegistrationCommand` DTO 이름 외 제거 대상 결과가 없어야 한다.
- 두 번째 명령은 디렉터리가 이미 제거되어 `No such file or directory`가 나오거나 파일 결과가 없어야 한다.

- [x] **Step 2: 계층 의존 방향을 정적으로 확인한다**

Run:

```bash
rg -n "^import com\.prompthub\.settlement\.infrastructure" settlement-service/src/main/java/com/prompthub/settlement/application settlement-service/src/main/java/com/prompthub/settlement/domain
rg -n "^import (org\.springframework|io\.grpc)" settlement-service/src/main/java/com/prompthub/settlement/domain
rg -n "^import jakarta\.persistence" settlement-service/src/main/java/com/prompthub/settlement/domain/repository settlement-service/src/main/java/com/prompthub/settlement/domain/exception
rg -n "^import io\.grpc" settlement-service/src/main/java/com/prompthub/settlement/application
```

Expected: 네 명령 모두 결과 없음. 프로젝트 규칙상 domain model의 JPA annotation은 허용하되 repository와 exception에는 JPA 의존성이 없어야 한다.

- [x] **Step 3: 정산 서비스 전체 테스트를 실행한다**

Run:

```bash
./gradlew :settlement-service:test
```

Expected: `BUILD SUCCESSFUL`. Docker/Testcontainers 문제면 비컨테이너 테스트와 compile 결과를 함께 제시하고 컨테이너 의존 테스트를 `UNVERIFIED`로 표시한다.

- [x] **Step 4: 외부 계약·스키마 무변경을 확인한다**

Run:

```bash
git diff --name-only -- grpc settlement-service/src/main/resources/db settlement-service/src/main/resources/application.yml settlement-service/src/main/java/com/prompthub/settlement/presentation
```

Expected: presentation 파일은 import/wiring 변경만 허용한다. `grpc`, DB migration, application 설정의 의미 변경은 없어야 한다.

- [x] **Step 5: 최종 diff 품질과 작업 범위를 확인한다**

Run:

```bash
git diff --check
git status --short
git diff --stat
```

Expected:

- `git diff --check` 출력 없음
- Settlement Java/test 이동 및 이 계획·설계 문서 외에는 구현 과정에서 새로 건드린 파일 없음
- 기존 `.claude/rules/clean-architecture.md`, `payment-service/.claude/rules/architecture.md`, 루트 convention 계획은 보존됨

- [x] **Step 6: 결과를 사용자에게 보고하고 다음 Git 작업을 확인받는다**

보고 내용:

- Stage 1 package move와 Stage 2 boundary refactor 결과
- 통과한 테스트 명령과 수치
- `UNVERIFIED` 항목 및 이유
- 외부 계약·DB·배치 동작이 변경되지 않았다는 diff 근거
- commit/push/PR은 실행하지 않았으며, 필요하면 별도 명시 요청이 필요하다는 안내
