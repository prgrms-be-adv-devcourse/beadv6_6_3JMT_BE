# Weekly Settlement Kubernetes CronJob Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** settlement-service가 매주 월요일 00:00 KST에 Kubernetes CronJob으로 직전 월요일~일요일 정산을 한 번 실행하고 종료하도록 전체 주간 계약과 배포 구조를 전환한다.

**Architecture:** `SettlementPeriod`가 포함 범위인 월요일·일요일을 검증하고 settlement-service 전체 배치 흐름과 order-service gRPC 계약이 이 범위를 공유한다. Kubernetes가 유일한 스케줄 소유자가 되며, 조건부 one-shot runner가 동기 Spring Batch 결과를 프로세스 종료 코드로 바꾼다. settlement-service는 운영 HTTP/Eureka 대상에서 빠지고 CD는 Deployment와 별도로 `CronJob/settlement-weekly`의 이미지를 갱신·복구한다.

**Tech Stack:** Java 21, Spring Boot 4, Spring Batch 6, gRPC/Protocol Buffers, PostgreSQL/JPA/QueryDSL, JUnit 5/AssertJ/Mockito, Kubernetes `batch/v1` CronJob, Kustomize, Bash, GitHub Actions

## Global Constraints

- 정산 주차는 `Asia/Seoul` 달력 기준 월요일부터 일요일까지다.
- Cron 표현식은 `"0 0 * * 1"`, `timeZone`은 `Asia/Seoul`이다.
- 외부 계약의 `periodStart`, `periodEnd`는 모두 포함 날짜이며 내부 조회만 `[periodStart 00:00, periodEnd + 1일 00:00)`로 바꾼다.
- Spring Batch 식별 파라미터는 `periodStart`, `periodEnd`뿐이다. `requestedAt`, `actorId`, `triggerType`은 비식별 파라미터다.
- 기존 gRPC 필드 `period = 1`은 deprecated 호환 필드로 유지하고 신규 필드는 `period_start = 2`, `period_end = 3`을 사용한다.
- CronJob 이름은 `settlement-weekly`이며 `concurrencyPolicy: Forbid`, `startingDeadlineSeconds: 3600`, 성공·실패 이력은 각각 3개, `backoffLimit: 1`, `activeDeadlineSeconds: 7200`, `restartPolicy: Never`를 고정한다.
- 운영 CronJob에서는 웹 서버, 수동 API, Eureka client, Spring Cloud discovery를 비활성화한다.
- 완료된 과거 주차의 누락 보정, 월간 지급, 운영 재시작 API, 일회성 Job/RBAC은 구현하지 않는다.
- 코드·매니페스트·워크플로만 변경한다. 운영 클러스터에 리소스를 적용하거나 정산 Job을 실제 실행하지 않는다.
- 데이터베이스 컬럼 의미는 이미 포함 시작일·종료일이므로 마이그레이션을 추가하지 않는다.
- 테스트는 RED를 확인한 뒤 최소 구현으로 GREEN을 만들고 각 작업 단위로 커밋한다.

---

## File Map

### 신규 파일

- `settlement-service/src/main/java/com/prompthub/settlement/domain/model/SettlementPeriod.java`: 주간 범위 불변식과 반개구간 변환을 소유한다.
- `settlement-service/src/test/java/com/prompthub/settlement/domain/model/SettlementPeriodTest.java`: 직전 주차·연도 경계·유효성 규칙을 검증한다.
- `settlement-service/src/main/java/com/prompthub/settlement/infrastructure/batch/launcher/SettlementJobParametersFactory.java`: 식별/비식별 Spring Batch 파라미터를 한곳에서 만든다.
- `settlement-service/src/test/java/com/prompthub/settlement/infrastructure/batch/launcher/SettlementJobParametersFactoryTest.java`: 파라미터 이름·값·식별 여부를 검증한다.
- `settlement-service/src/main/java/com/prompthub/settlement/infrastructure/batch/runner/SettlementCronJobRunner.java`: 직전 주차 배치를 한 번 실행하고 종료 코드를 제공한다.
- `settlement-service/src/test/java/com/prompthub/settlement/infrastructure/batch/runner/SettlementCronJobRunnerTest.java`: 성공·실패·예외 종료 코드를 검증한다.
- `settlement-service/src/test/java/com/prompthub/settlement/infrastructure/batch/runner/SettlementCronJobRunnerConditionTest.java`: 실행 모드 조건을 검증한다.
- `settlement-service/src/main/java/com/prompthub/settlement/global/config/SettlementClockConfig.java`: 서울 시간대 `Clock` 빈을 제공한다.
- `k8s/base/services/settlement/cronjob.yaml`: 주간 one-shot Job Pod 템플릿을 정의한다.

### 책임이 바뀌는 파일군

- `grpc/order/order_query.proto`, `order-service/.../SettlementOrderQueryUseCase.java`, `SettlementOrderQueryService.java`, `OrderQueryGrpcServer.java`: 신규 주간 gRPC 필드와 legacy 월 fallback을 제공한다.
- settlement-service의 command/port/service/repository/batch/client/request 파일: `YearMonth`를 `SettlementPeriod`로 바꾸고 동일 주차를 끝까지 전달한다.
- `SettlementApplication.java`, `SettlementBatchScheduler.java`, `SchedulingConfig.java`: 내부 스케줄을 제거하고 one-shot 프로세스 종료를 연결한다.
- `apigateway/...`, `config/src/main/resources/configs/...`: settlement 운영 HTTP/Eureka 라우팅과 내부 scheduler 설정을 제거한다.
- `scripts/validate-k8s-manifests.sh`, `k8s/base/services/settlement/...`: Deployment/Service를 CronJob으로 교체하고 정적 계약을 잠근다.
- `.github/workflows/cd-selfhosted-kubernetes.yml`, `scripts/validate-k8s-cd-workflow.sh`: Deployment 전제에서 settlement CronJob을 분리한다.
- settlement 및 k8s 문서: 주간 범위, gRPC, 실행·운영 계약을 현재 코드와 맞춘다.

---

### Task 1: 주간 정산 기간 값 객체

**Files:**
- Create: `settlement-service/src/main/java/com/prompthub/settlement/domain/model/SettlementPeriod.java`
- Create: `settlement-service/src/test/java/com/prompthub/settlement/domain/model/SettlementPeriodTest.java`

**Interfaces:**
- Consumes: `java.time.LocalDate`, `java.time.DayOfWeek.MONDAY`, `TemporalAdjusters.previousOrSame`
- Produces: `SettlementPeriod.of(LocalDate, LocalDate)`, `SettlementPeriod.previousWeek(LocalDate)`, `startInclusive()`, `endExclusive()`

- [ ] **Step 1: 값 객체의 실패 테스트를 작성한다**

```java
package com.prompthub.settlement.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class SettlementPeriodTest {

    @Test
    void previousWeek_returnsPreviousMondayThroughSunday() {
        SettlementPeriod period = SettlementPeriod.previousWeek(LocalDate.of(2026, 7, 20));

        assertThat(period.periodStart()).isEqualTo(LocalDate.of(2026, 7, 13));
        assertThat(period.periodEnd()).isEqualTo(LocalDate.of(2026, 7, 19));
        assertThat(period.startInclusive()).isEqualTo(
                LocalDateTime.of(2026, 7, 13, 0, 0));
        assertThat(period.endExclusive()).isEqualTo(
                LocalDateTime.of(2026, 7, 20, 0, 0));
    }

    @Test
    void previousWeek_handlesYearBoundary() {
        SettlementPeriod period = SettlementPeriod.previousWeek(LocalDate.of(2027, 1, 4));

        assertThat(period.periodStart()).isEqualTo(LocalDate.of(2026, 12, 28));
        assertThat(period.periodEnd()).isEqualTo(LocalDate.of(2027, 1, 3));
    }

    @Test
    void of_rejectsNonMondayStart() {
        assertThatThrownBy(() -> SettlementPeriod.of(
                LocalDate.of(2026, 7, 14), LocalDate.of(2026, 7, 20)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("월요일");
    }

    @Test
    void of_rejectsRangeOtherThanSevenDays() {
        assertThatThrownBy(() -> SettlementPeriod.of(
                LocalDate.of(2026, 7, 13), LocalDate.of(2026, 7, 18)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("일요일");
    }
}
```

- [ ] **Step 2: 테스트가 타입 부재로 실패하는지 확인한다**

Run: `./gradlew :settlement-service:test --tests com.prompthub.settlement.domain.model.SettlementPeriodTest`

Expected: `cannot find symbol: class SettlementPeriod`로 FAIL

- [ ] **Step 3: 값 객체를 최소 구현한다**

```java
package com.prompthub.settlement.domain.model;

import static java.time.DayOfWeek.MONDAY;
import static java.time.temporal.TemporalAdjusters.previousOrSame;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

public record SettlementPeriod(LocalDate periodStart, LocalDate periodEnd) {

    public SettlementPeriod {
        Objects.requireNonNull(periodStart, "정산 시작일은 필수입니다.");
        Objects.requireNonNull(periodEnd, "정산 종료일은 필수입니다.");
        if (periodStart.getDayOfWeek() != MONDAY) {
            throw new IllegalArgumentException("정산 시작일은 월요일이어야 합니다.");
        }
        if (!periodEnd.equals(periodStart.plusDays(6))) {
            throw new IllegalArgumentException("정산 종료일은 시작일 다음 일요일이어야 합니다.");
        }
    }

    public static SettlementPeriod of(LocalDate periodStart, LocalDate periodEnd) {
        return new SettlementPeriod(periodStart, periodEnd);
    }

    public static SettlementPeriod previousWeek(LocalDate executionDate) {
        Objects.requireNonNull(executionDate, "실행일은 필수입니다.");
        LocalDate currentWeekStart = executionDate.with(previousOrSame(MONDAY));
        return of(currentWeekStart.minusWeeks(1), currentWeekStart.minusDays(1));
    }

    public LocalDateTime startInclusive() {
        return periodStart.atStartOfDay();
    }

    public LocalDateTime endExclusive() {
        return periodEnd.plusDays(1).atStartOfDay();
    }
}
```

- [ ] **Step 4: 기간 테스트를 통과시킨다**

Run: `./gradlew :settlement-service:test --tests com.prompthub.settlement.domain.model.SettlementPeriodTest`

Expected: `BUILD SUCCESSFUL`, 4 tests passed

- [ ] **Step 5: 값 객체를 커밋한다**

```bash
git add settlement-service/src/main/java/com/prompthub/settlement/domain/model/SettlementPeriod.java settlement-service/src/test/java/com/prompthub/settlement/domain/model/SettlementPeriodTest.java
git commit -m "feat: 주간 정산 기간 모델 추가"
```

---

### Task 2: order-service 주간 gRPC 조회 계약

**Files:**
- Modify: `grpc/order/order_query.proto`
- Modify: `order-service/src/main/java/com/prompthub/order/application/usecase/SettlementOrderQueryUseCase.java`
- Modify: `order-service/src/main/java/com/prompthub/order/application/service/order/SettlementOrderQueryService.java`
- Modify: `order-service/src/main/java/com/prompthub/order/infra/grpc/server/OrderQueryGrpcServer.java`
- Modify: `order-service/src/test/java/com/prompthub/order/application/service/order/SettlementOrderQueryServiceTest.java`
- Modify: `order-service/src/test/java/com/prompthub/order/infra/grpc/server/OrderQueryGrpcServerTest.java`
- Verify: `order-service/src/main/java/com/prompthub/order/domain/repository/SettlementOrderQueryRepository.java`
- Verify: `order-service/src/main/java/com/prompthub/order/infra/persistence/order/SettlementOrderQueryRepositoryImpl.java`
- Verify: `order-service/src/test/java/com/prompthub/order/infra/persistence/order/SettlementOrderQueryRepositoryImplTest.java`

**Interfaces:**
- Consumes: gRPC 포함 날짜 문자열 `period_start`, `period_end`; legacy `period` 월 문자열
- Produces: `List<SettleableLineResult> getSettleableLines(LocalDate periodStart, LocalDate periodEnd)`와 저장소 반개구간 호출

- [ ] **Step 1: 서비스와 gRPC 주간 계약의 실패 테스트를 작성한다**

`SettlementOrderQueryServiceTest`의 월 변환 테스트를 다음 계약으로 교체한다.

```java
@Test
void getSettleableLines_convertsInclusiveDatesToHalfOpenRange() {
    LocalDate periodStart = LocalDate.of(2026, 7, 13);
    LocalDate periodEnd = LocalDate.of(2026, 7, 19);
    given(repository.findSettleableLines(
            LocalDateTime.of(2026, 7, 13, 0, 0),
            LocalDateTime.of(2026, 7, 20, 0, 0)))
            .willReturn(List.of());

    service.getSettleableLines(periodStart, periodEnd);

    then(repository).should().findSettleableLines(
            LocalDateTime.of(2026, 7, 13, 0, 0),
            LocalDateTime.of(2026, 7, 20, 0, 0));
}
```

`OrderQueryGrpcServerTest`에는 신규 요청, partial 요청, 잘못된 주차, legacy fallback을 각각 고정한다.

```java
@Test
void getSettleableLines_weeklyPeriod_returnsMappedLines() {
    given(settlementOrderQueryUseCase.getSettleableLines(
            LocalDate.of(2026, 7, 13), LocalDate.of(2026, 7, 19)))
            .willReturn(List.of(settleableResult()));

    GetSettleableLinesResponse response = blockingStub.getSettleableLines(
            GetSettleableLinesRequest.newBuilder()
                    .setPeriodStart("2026-07-13")
                    .setPeriodEnd("2026-07-19")
                    .build());

    assertThat(response.getLinesCount()).isEqualTo(1);
    then(settlementOrderQueryUseCase).should().getSettleableLines(
            LocalDate.of(2026, 7, 13), LocalDate.of(2026, 7, 19));
}

@ParameterizedTest
@MethodSource("invalidWeeklyRequests")
void getSettleableLines_invalidWeeklyRequest_returnsInvalidArgument(GetSettleableLinesRequest request) {
    assertThatThrownBy(() -> blockingStub.getSettleableLines(request))
            .isInstanceOfSatisfying(StatusRuntimeException.class,
                    exception -> assertThat(exception.getStatus().getCode())
                            .isEqualTo(Status.Code.INVALID_ARGUMENT));
    then(settlementOrderQueryUseCase).shouldHaveNoInteractions();
}

static Stream<GetSettleableLinesRequest> invalidWeeklyRequests() {
    return Stream.of(
            GetSettleableLinesRequest.newBuilder().setPeriodStart("2026-07-13").build(),
            GetSettleableLinesRequest.newBuilder().setPeriodEnd("2026-07-19").build(),
            GetSettleableLinesRequest.newBuilder()
                    .setPeriodStart("2026/07/13").setPeriodEnd("2026-07-19").build(),
            GetSettleableLinesRequest.newBuilder()
                    .setPeriodStart("2026-07-14").setPeriodEnd("2026-07-20").build(),
            GetSettleableLinesRequest.newBuilder()
                    .setPeriodStart("2026-07-13").setPeriodEnd("2026-07-18").build());
}

@Test
void getSettleableLines_legacyMonth_mapsToInclusiveMonthDates() {
    given(settlementOrderQueryUseCase.getSettleableLines(
            LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)))
            .willReturn(List.of());

    blockingStub.getSettleableLines(
            GetSettleableLinesRequest.newBuilder().setPeriod("2026-07").build());

    then(settlementOrderQueryUseCase).should().getSettleableLines(
            LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));
}

private SettleableLineResult settleableResult() {
    return new SettleableLineResult(
            SettlementLineType.REFUND,
            UUID.fromString("00000000-0000-0000-0000-000000000201"),
            UUID.fromString("00000000-0000-0000-0000-000000000202"),
            UUID.fromString("00000000-0000-0000-0000-000000000203"),
            15_000,
            LocalDateTime.of(2026, 7, 15, 12, 30));
}
```

기존 empty/예상 밖 실패/wire method 테스트는 유지하되 use case mock을 두 `LocalDate` 인자로 바꾸고, 무호출 검증은 `getSettleableLines(Mockito.any(), Mockito.any())`를 사용한다. 기존 월 형식 오류 테스트는 legacy fallback 유효성 테스트로 유지한다.

- [ ] **Step 2: 신규 proto 접근자와 use case 시그니처가 없어 실패하는지 확인한다**

Run: `./gradlew :order-service:test --tests com.prompthub.order.application.service.order.SettlementOrderQueryServiceTest --tests com.prompthub.order.infra.grpc.server.OrderQueryGrpcServerTest`

Expected: `setPeriodStart`, `setPeriodEnd` 또는 새 `getSettleableLines(LocalDate, LocalDate)`가 없어 컴파일 FAIL

- [ ] **Step 3: wire 호환 필드를 추가한다**

`GetSettleableLinesRequest`를 정확히 다음과 같이 바꾼다.

```proto
message GetSettleableLinesRequest {
  string period = 1 [deprecated = true];
  string period_start = 2;
  string period_end = 3;
}
```

- [ ] **Step 4: 애플리케이션 포트와 반개구간 변환을 구현한다**

```java
public interface SettlementOrderQueryUseCase {
    List<SettleableLineResult> getSettleableLines(LocalDate periodStart, LocalDate periodEnd);
}
```

```java
@Override
public List<SettleableLineResult> getSettleableLines(LocalDate periodStart, LocalDate periodEnd) {
    Objects.requireNonNull(periodStart, "정산 시작일은 필수입니다.");
    Objects.requireNonNull(periodEnd, "정산 종료일은 필수입니다.");
    if (periodEnd.isBefore(periodStart)) {
        throw new IllegalArgumentException("정산 종료일은 시작일보다 빠를 수 없습니다.");
    }
    return repository.findSettleableLines(
            periodStart.atStartOfDay(),
            periodEnd.plusDays(1).atStartOfDay());
}
```

저장소 인터페이스와 QueryDSL 구현은 기존 `startInclusive`, `endExclusive` 시그니처 및 `goe`/`lt` 조건을 그대로 둔다.

- [ ] **Step 5: gRPC 서버에서 신규 주간 계약을 우선 파싱하고 legacy 월을 fallback한다**

`getSettleableLines`는 `SettlementQueryPeriod queryPeriod = parseSettlementQueryPeriod(request)`를 먼저 만들고 use case에 두 날짜를 전달한다. 파싱 코드는 다음과 같다.

```java
private SettlementQueryPeriod parseSettlementQueryPeriod(GetSettleableLinesRequest request) {
    boolean hasStart = !request.getPeriodStart().isBlank();
    boolean hasEnd = !request.getPeriodEnd().isBlank();
    if (hasStart != hasEnd) {
        throw new IllegalArgumentException("정산 시작일과 종료일을 모두 입력해야 합니다.");
    }

    if (hasStart) {
        LocalDate start = LocalDate.parse(request.getPeriodStart());
        LocalDate end = LocalDate.parse(request.getPeriodEnd());
        if (start.getDayOfWeek() != DayOfWeek.MONDAY || !end.equals(start.plusDays(6))) {
            throw new IllegalArgumentException("정산 기간은 월요일부터 일요일까지여야 합니다.");
        }
        return new SettlementQueryPeriod(start, end);
    }

    YearMonth legacyPeriod = parseLegacyPeriod(request.getPeriod());
    return new SettlementQueryPeriod(legacyPeriod.atDay(1), legacyPeriod.atEndOfMonth());
}

private YearMonth parseLegacyPeriod(String period) {
    if (!period.matches("\\d{4}-\\d{2}")) {
        throw new DateTimeParseException("Invalid settlement period", period, 0);
    }
    return YearMonth.parse(period);
}

private record SettlementQueryPeriod(LocalDate periodStart, LocalDate periodEnd) {
}
```

파싱 전용 try/catch에서 `DateTimeException | IllegalArgumentException`은 `INVALID_ARGUMENT`와 일반 설명으로 반환하고, 그 다음 try/catch의 use case 예외는 기존처럼 `INTERNAL`로 반환한다. 로그에는 legacy period와 신규 시작·종료일만 기록한다.

- [ ] **Step 6: order-service 계약 테스트와 저장소 경계 테스트를 통과시킨다**

Run: `./gradlew :order-service:test --tests com.prompthub.order.application.service.order.SettlementOrderQueryServiceTest --tests com.prompthub.order.infra.grpc.server.OrderQueryGrpcServerTest --tests com.prompthub.order.infra.persistence.order.SettlementOrderQueryRepositoryImplTest`

Expected: `BUILD SUCCESSFUL`; 신규 주간·legacy·경계 테스트 PASS

- [ ] **Step 7: order 계약 변경을 커밋한다**

```bash
git add grpc/order/order_query.proto order-service/src/main/java/com/prompthub/order/application/usecase/SettlementOrderQueryUseCase.java order-service/src/main/java/com/prompthub/order/application/service/order/SettlementOrderQueryService.java order-service/src/main/java/com/prompthub/order/infra/grpc/server/OrderQueryGrpcServer.java order-service/src/test/java/com/prompthub/order/application/service/order/SettlementOrderQueryServiceTest.java order-service/src/test/java/com/prompthub/order/infra/grpc/server/OrderQueryGrpcServerTest.java order-service/src/test/java/com/prompthub/order/infra/persistence/order/SettlementOrderQueryRepositoryImplTest.java
git commit -m "feat: 주문 정산 조회를 주간 범위로 확장"
```

---

### Task 3: settlement-service 전체 주간 배치 흐름

**Files:**
- Create: `settlement-service/src/main/java/com/prompthub/settlement/infrastructure/batch/launcher/SettlementJobParametersFactory.java`
- Create: `settlement-service/src/test/java/com/prompthub/settlement/infrastructure/batch/launcher/SettlementJobParametersFactoryTest.java`
- Modify: `settlement-service/src/main/java/com/prompthub/settlement/application/dto/RunSettlementJobCommand.java`
- Modify: `settlement-service/src/main/java/com/prompthub/settlement/application/dto/CalculateSettlementCommand.java`
- Modify: `settlement-service/src/main/java/com/prompthub/settlement/application/port/OrderSettlementQueryPort.java`
- Modify: `settlement-service/src/main/java/com/prompthub/settlement/application/usecase/LoadSettlementSourceUseCase.java`
- Modify: `settlement-service/src/main/java/com/prompthub/settlement/application/service/SettlementSourceApplicationService.java`
- Modify: `settlement-service/src/main/java/com/prompthub/settlement/application/service/SettlementCalculationApplicationService.java`
- Modify: `settlement-service/src/main/java/com/prompthub/settlement/domain/model/Settlement.java`
- Modify: `settlement-service/src/main/java/com/prompthub/settlement/domain/repository/SettlementSourceRepository.java`
- Modify: `settlement-service/src/main/java/com/prompthub/settlement/infrastructure/persistence/SettlementSourceRepositoryAdapter.java`
- Modify: `settlement-service/src/main/java/com/prompthub/settlement/infrastructure/client/order/OrderSettlementQueryClient.java`
- Modify: `settlement-service/src/main/java/com/prompthub/settlement/infrastructure/batch/launcher/SettlementJobLauncherAdapter.java`
- Modify: `settlement-service/src/main/java/com/prompthub/settlement/infrastructure/batch/tasklet/LoadSettlementSourceTasklet.java`
- Modify: `settlement-service/src/main/java/com/prompthub/settlement/infrastructure/batch/tasklet/CreateSettlementBatchTasklet.java`
- Modify: `settlement-service/src/main/java/com/prompthub/settlement/infrastructure/batch/reader/SettlementTargetReader.java`
- Modify: `settlement-service/src/main/java/com/prompthub/settlement/infrastructure/batch/model/SettlementItem.java`
- Modify: `settlement-service/src/main/java/com/prompthub/settlement/infrastructure/batch/processor/SettlementProcessor.java`
- Modify: `settlement-service/src/main/java/com/prompthub/settlement/presentation/dto/request/RunSettlementJobRequest.java`
- Modify: `settlement-service/src/main/java/com/prompthub/settlement/presentation/controller/SettlementBatchController.java`
- Delete: `settlement-service/src/main/java/com/prompthub/settlement/infrastructure/batch/scheduler/SettlementBatchScheduler.java`
- Modify: `settlement-service/src/test/java/com/prompthub/settlement/application/event/SettlementCreatedEventTest.java`
- Modify: `settlement-service/src/test/java/com/prompthub/settlement/application/service/SettlementCalculationApplicationServiceTest.java`
- Modify: `settlement-service/src/test/java/com/prompthub/settlement/application/service/SettlementOutboxCommitIntegrationTest.java`
- Modify: `settlement-service/src/test/java/com/prompthub/settlement/application/service/SettlementOutboxTransactionIntegrationTest.java`
- Modify: `settlement-service/src/test/java/com/prompthub/settlement/application/service/SettlementSourceLoadServiceTest.java`
- Modify: `settlement-service/src/test/java/com/prompthub/settlement/domain/model/SettlementTest.java`
- Modify: `settlement-service/src/test/java/com/prompthub/settlement/infrastructure/client/order/OrderSettlementQueryClientTest.java`
- Modify: `settlement-service/src/test/java/com/prompthub/settlement/presentation/controller/SettlementManualApiIntegrationTest.java`
- Create: `settlement-service/src/test/java/com/prompthub/settlement/infrastructure/batch/tasklet/CreateSettlementBatchTaskletTest.java`
- Create: `settlement-service/src/test/java/com/prompthub/settlement/infrastructure/batch/tasklet/LoadSettlementSourceTaskletTest.java`
- Create: `settlement-service/src/test/java/com/prompthub/settlement/infrastructure/batch/reader/SettlementTargetReaderTest.java`

**Interfaces:**
- Consumes: Task 1 `SettlementPeriod`; Task 2 proto accessors `setPeriodStart`, `setPeriodEnd`
- Produces: `RunSettlementJobCommand(SettlementPeriod, UUID, TriggerType)`, 주간 source 조회·정산·이벤트, 동일 주차 JobInstance 식별

- [ ] **Step 1: 파라미터 식별과 주간 전달 실패 테스트를 먼저 작성한다**

`SettlementJobParametersFactoryTest`는 다음 값을 고정한다.

```java
private static final UUID ACTOR_ID =
        UUID.fromString("00000000-0000-0000-0000-000000000364");
private static final SettlementPeriod PERIOD = SettlementPeriod.of(
        LocalDate.of(2026, 7, 13), LocalDate.of(2026, 7, 19));

private final SettlementJobParametersFactory factory =
        new SettlementJobParametersFactory();

@Test
void create_usesOnlyPeriodDatesAsIdentifyingParameters() {
    JobParameters parameters = factory.create(
            RunSettlementJobCommand.manual(PERIOD, ACTOR_ID));

    assertThat(parameters.getString("periodStart")).isEqualTo("2026-07-13");
    assertThat(parameters.getString("periodEnd")).isEqualTo("2026-07-19");
    assertThat(parameters.getParameter("periodStart").identifying()).isTrue();
    assertThat(parameters.getParameter("periodEnd").identifying()).isTrue();
    assertThat(parameters.getParameter("requestedAt").identifying()).isFalse();
    assertThat(parameters.getParameter("actorId").identifying()).isFalse();
    assertThat(parameters.getParameter("triggerType").identifying()).isFalse();
}

@Test
void create_omitsActorIdForScheduledExecution() {
    JobParameters parameters = factory.create(RunSettlementJobCommand.scheduled(PERIOD));

    assertThat(parameters.getParameter("actorId")).isNull();
    assertThat(parameters.getString("triggerType")).isEqualTo("SCHEDULED");
}
```

각 계층 테스트의 기존 `YearMonth.of(2026, 7)` fixture를 `SettlementPeriod.of(2026-07-13, 2026-07-19)`로 바꾸고 다음 핵심 단언을 추가한다.

```java
then(orderSettlementQueryPort).should().fetchSettleableLines(PERIOD);
then(settlementSourceRepository).should().findSettleableSellerIds(PERIOD);
then(settlementSourceRepository).should().findSettleableLines(SELLER_ID, PERIOD);
assertThat(settlement.getPeriodStart()).isEqualTo(PERIOD.periodStart());
assertThat(settlement.getPeriodEnd()).isEqualTo(PERIOD.periodEnd());
assertThat(createdEvent.periodStart()).isEqualTo(PERIOD.periodStart());
assertThat(createdEvent.periodEnd()).isEqualTo(PERIOD.periodEnd());
```

새 batch 구성요소 테스트는 `ReflectionTestUtils`로 두 step-scoped 날짜 필드를 주입하고 다음 호출을 검증한다.

```java
@Test
void execute_loadsTheInclusiveWeeklyPeriod() throws Exception {
    LoadSettlementSourceUseCase useCase = mock(LoadSettlementSourceUseCase.class);
    LoadSettlementSourceTasklet tasklet = new LoadSettlementSourceTasklet(useCase);
    ReflectionTestUtils.setField(tasklet, "periodStartParam", "2026-07-13");
    ReflectionTestUtils.setField(tasklet, "periodEndParam", "2026-07-19");

    RepeatStatus result = tasklet.execute(null, null);

    then(useCase).should().load(PERIOD);
    assertThat(result).isEqualTo(RepeatStatus.FINISHED);
}
```

```java
@Test
void read_queriesSellersAndCreatesItemWithTheSameWeeklyPeriod() {
    SettlementSourceRepository repository = mock(SettlementSourceRepository.class);
    given(repository.findSettleableSellerIds(PERIOD)).willReturn(List.of(SELLER_ID));
    SettlementTargetReader reader = new SettlementTargetReader(repository);
    ReflectionTestUtils.setField(reader, "periodStartParam", "2026-07-13");
    ReflectionTestUtils.setField(reader, "periodEndParam", "2026-07-19");
    ReflectionTestUtils.setField(reader, "settlementBatchIdParam", BATCH_ID.toString());

    SettlementItem first = reader.read();
    SettlementItem exhausted = reader.read();

    assertThat(first).isEqualTo(new SettlementItem(SELLER_ID, PERIOD, BATCH_ID));
    assertThat(exhausted).isNull();
    then(repository).should().findSettleableSellerIds(PERIOD);
}
```

`CreateSettlementBatchTaskletTest`는 `ChunkContext -> StepContext -> StepExecution -> JobExecution` mock을 연결해 job execution ID `42L`과 실제 `ExecutionContext`를 반환하게 하고 다음 captor 단언을 사용한다.

```java
SettlementBatchRepository repository = mock(SettlementBatchRepository.class);
SettlementBatch saved = mock(SettlementBatch.class);
given(saved.getId()).willReturn(BATCH_ID);
given(repository.save(any(SettlementBatch.class))).willReturn(saved);

JobExecution jobExecution = mock(JobExecution.class);
ExecutionContext executionContext = new ExecutionContext();
given(jobExecution.getExecutionContext()).willReturn(executionContext);
StepExecution stepExecution = mock(StepExecution.class);
given(stepExecution.getJobExecutionId()).willReturn(42L);
given(stepExecution.getJobExecution()).willReturn(jobExecution);
StepContext stepContext = mock(StepContext.class);
given(stepContext.getStepExecution()).willReturn(stepExecution);
ChunkContext chunkContext = mock(ChunkContext.class);
given(chunkContext.getStepContext()).willReturn(stepContext);

CreateSettlementBatchTasklet tasklet = new CreateSettlementBatchTasklet(repository);
ReflectionTestUtils.setField(tasklet, "periodStartParam", "2026-07-13");
ReflectionTestUtils.setField(tasklet, "periodEndParam", "2026-07-19");
ReflectionTestUtils.setField(tasklet, "triggerTypeParam", "SCHEDULED");

tasklet.execute(null, chunkContext);

ArgumentCaptor<SettlementBatch> batchCaptor = ArgumentCaptor.forClass(SettlementBatch.class);
then(repository).should().save(batchCaptor.capture());
SettlementBatch batch = batchCaptor.getValue();
assertThat(batch.getBatchNo()).isEqualTo(
        "SETTLE-20260713-20260719-SCHEDULED-42");
assertThat(batch.getPeriodStart()).isEqualTo(PERIOD.periodStart());
assertThat(batch.getPeriodEnd()).isEqualTo(PERIOD.periodEnd());
assertThat(batch.getTriggerType()).isEqualTo(TriggerType.SCHEDULED);
assertThat(executionContext.getString("settlementBatchId"))
        .isEqualTo(BATCH_ID.toString());
```

`OrderSettlementQueryClientTest`에서는 request captor로 legacy 필드가 비고 신규 필드만 채워지는지 확인한다.

```java
then(stub).should().getSettleableLines(requestCaptor.capture());
GetSettleableLinesRequest request = requestCaptor.getValue();
assertThat(request.getPeriod()).isEmpty();
assertThat(request.getPeriodStart()).isEqualTo("2026-07-13");
assertThat(request.getPeriodEnd()).isEqualTo("2026-07-19");
```

수동 API 통합 테스트 body와 command 단언은 다음 계약을 쓴다.

```java
.content("""
    {"periodStart":"2026-07-13","periodEnd":"2026-07-19"}
    """))

then(runSettlementBatchUseCase).should().run(argThat(command ->
        command.period().equals(PERIOD)
                && command.actorId().equals(ACTOR_ID)
                && command.triggerType() == TriggerType.MANUAL));
```

월요일이 아닌 시작일과 6일 뒤가 아닌 종료일 요청도 각각 `400`, error code `S-003`이고 use case 무호출인지 추가한다.

- [ ] **Step 2: 월 타입과 기존 `period` 파라미터 때문에 실패하는지 확인한다**

Run: `./gradlew :settlement-service:test --tests com.prompthub.settlement.infrastructure.batch.launcher.SettlementJobParametersFactoryTest --tests com.prompthub.settlement.infrastructure.client.order.OrderSettlementQueryClientTest --tests com.prompthub.settlement.presentation.controller.SettlementManualApiIntegrationTest`

Expected: factory 부재 또는 `SettlementPeriod` 시그니처 불일치로 컴파일 FAIL

- [ ] **Step 3: command·port·repository·도메인 시그니처를 주간 타입으로 바꾼다**

아래 시그니처를 단일 기준으로 사용한다.

```java
public record RunSettlementJobCommand(
        SettlementPeriod period,
        UUID actorId,
        TriggerType triggerType
) {
    public static RunSettlementJobCommand manual(SettlementPeriod period, UUID actorId) {
        return new RunSettlementJobCommand(period, actorId, TriggerType.MANUAL);
    }

    public static RunSettlementJobCommand scheduled(SettlementPeriod period) {
        return new RunSettlementJobCommand(period, null, TriggerType.SCHEDULED);
    }
}

public record CalculateSettlementCommand(
        UUID settlementBatchId,
        UUID sellerId,
        SettlementPeriod period
) {
}

public record SettlementItem(
        UUID sellerId,
        SettlementPeriod period,
        UUID settlementBatchId
) {
}
```

```java
List<SettleableLine> fetchSettleableLines(SettlementPeriod period);
int load(SettlementPeriod period);
List<UUID> findSettleableSellerIds(SettlementPeriod period);
List<SettlementSourceLine> findSettleableLines(UUID sellerId, SettlementPeriod period);
```

`SettlementSourceRepositoryAdapter`는 `period.startInclusive()`와 `period.endExclusive()`를 JPA 저장소에 그대로 전달한다. `Settlement.create`와 private 생성자는 `SettlementPeriod`를 받고 다음처럼 저장한다.

```java
this.periodStart = period.periodStart();
this.periodEnd = period.periodEnd();
```

- [ ] **Step 4: source load·reader·tasklet이 같은 두 JobParameter를 사용하게 구현한다**

각 step-scoped 구성요소는 아래 두 필드를 받고 동일한 팩토리 호출로 파싱한다.

```java
@Value("#{jobParameters['periodStart']}")
private String periodStartParam;

@Value("#{jobParameters['periodEnd']}")
private String periodEndParam;

private SettlementPeriod period() {
    return SettlementPeriod.of(
            LocalDate.parse(periodStartParam),
            LocalDate.parse(periodEndParam));
}
```

`LoadSettlementSourceTasklet.execute`는 `loadSettlementSourceUseCase.load(period())`를 호출한다. `SettlementTargetReader`는 최초 read에서 `findSettleableSellerIds(period())`를 호출하고 `new SettlementItem(nextSellerId, period(), batchId)`를 반환한다. `CreateSettlementBatchTasklet`은 다음 batch number와 포함 날짜를 사용한다.

```java
String batchNo = "SETTLE-%s-%s-%s-%d".formatted(
        period.periodStart().format(DateTimeFormatter.BASIC_ISO_DATE),
        period.periodEnd().format(DateTimeFormatter.BASIC_ISO_DATE),
        triggerType.name(),
        jobExecutionId);
SettlementBatch.start(
        batchNo, period.periodStart(), period.periodEnd(), triggerType);
```

`SettlementBatchScheduler.java`는 월 타입을 가진 내부 운영 트리거이므로 이 단계에서 삭제한다. Task 4가 같은 use case에 주간 one-shot runner를 연결한다.

- [ ] **Step 5: JobParameter factory와 launcher를 구현한다**

```java
package com.prompthub.settlement.infrastructure.batch.launcher;

import com.prompthub.settlement.application.dto.RunSettlementJobCommand;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.stereotype.Component;

@Component
public class SettlementJobParametersFactory {

    public JobParameters create(RunSettlementJobCommand command) {
        JobParametersBuilder builder = new JobParametersBuilder()
                .addString("periodStart", command.period().periodStart().toString(), true)
                .addString("periodEnd", command.period().periodEnd().toString(), true)
                .addLong("requestedAt", System.currentTimeMillis(), false)
                .addString("triggerType", command.triggerType().name(), false);
        if (command.actorId() != null) {
            builder.addString("actorId", command.actorId().toString(), false);
        }
        return builder.toJobParameters();
    }
}
```

`SettlementJobLauncherAdapter` 생성자에 factory를 주입하고 기존 inline builder를 다음 한 줄로 교체한다.

```java
JobParameters parameters = settlementJobParametersFactory.create(command);
```

수동 실행은 기존 async operator, scheduled 실행은 기존 sync operator를 유지한다.

- [ ] **Step 6: gRPC client와 로컬 수동 요청을 신규 주간 계약으로 바꾼다**

```java
GetSettleableLinesRequest request = GetSettleableLinesRequest.newBuilder()
        .setPeriodStart(period.periodStart().toString())
        .setPeriodEnd(period.periodEnd().toString())
        .build();
GetSettleableLinesResponse response = orderSettlementQueryStub.getSettleableLines(request);
```

`RunSettlementJobRequest`는 다음 두 날짜와 bean validation을 사용한다.

```java
public record RunSettlementJobRequest(
        @NotNull LocalDate periodStart,
        @NotNull LocalDate periodEnd
) {
    @JsonIgnore
    @AssertTrue(message = "정산 기간은 월요일부터 일요일까지여야 합니다.")
    public boolean isWeeklyPeriod() {
        if (periodStart == null || periodEnd == null) {
            return true;
        }
        return periodStart.getDayOfWeek() == DayOfWeek.MONDAY
                && periodEnd.equals(periodStart.plusDays(6));
    }

    public RunSettlementJobCommand toCommand(UUID actorId) {
        return RunSettlementJobCommand.manual(
                SettlementPeriod.of(periodStart, periodEnd), actorId);
    }
}
```

컨트롤러 OpenAPI 설명은 “정산 대상 월”을 “월요일부터 일요일까지의 정산 주차”로 교체한다.

- [ ] **Step 7: 전체 settlement-service 테스트를 실행해 누락된 월 fixture까지 정리한다**

Run: `./gradlew :settlement-service:test`

Expected: `BUILD SUCCESSFUL`

Run: `rg -n "YearMonth|jobParameters\['period'\]" settlement-service/src/main/java/com/prompthub/settlement`

Expected: 출력 없음

- [ ] **Step 8: 주간 배치 흐름을 커밋한다**

```bash
git add settlement-service/src/main settlement-service/src/test
git commit -m "feat: 정산 배치를 주간 범위로 전환"
```

---

### Task 4: CronJob one-shot 실행기와 프로세스 종료

**Files:**
- Create: `settlement-service/src/main/java/com/prompthub/settlement/global/config/SettlementClockConfig.java`
- Create: `settlement-service/src/main/java/com/prompthub/settlement/infrastructure/batch/runner/SettlementCronJobRunner.java`
- Create: `settlement-service/src/test/java/com/prompthub/settlement/infrastructure/batch/runner/SettlementCronJobRunnerTest.java`
- Create: `settlement-service/src/test/java/com/prompthub/settlement/infrastructure/batch/runner/SettlementCronJobRunnerConditionTest.java`
- Modify: `settlement-service/src/main/java/com/prompthub/settlement/SettlementApplication.java`
- Delete: `settlement-service/src/main/java/com/prompthub/settlement/global/config/SchedulingConfig.java`
- Modify: `settlement-service/src/main/resources/application.yml`
- Modify: `settlement-service/src/main/resources/application-local.yml`
- Modify: `config/src/main/resources/configs/settlement-service.yml`

**Interfaces:**
- Consumes: `RunSettlementBatchUseCase`, Task 1 `SettlementPeriod.previousWeek`, `SettlementJobResult.status()`
- Produces: property `settlement.execution.mode=cronjob`, `ExitCodeGenerator` 0/1, 서울 `Clock`

- [ ] **Step 1: fixed Clock 기반 runner 실패 테스트를 작성한다**

```java
private static final LocalDateTime START_TIME =
        LocalDateTime.of(2026, 7, 20, 0, 0);

private RunSettlementBatchUseCase useCase;
private SettlementCronJobRunner runner;

@BeforeEach
void setUp() {
    useCase = mock(RunSettlementBatchUseCase.class);
    Clock clock = Clock.fixed(
            Instant.parse("2026-07-19T15:00:00Z"), ZoneId.of("Asia/Seoul"));
    runner = new SettlementCronJobRunner(useCase, clock);
}

@Test
void run_onMonday_executesPreviousWeekAndReturnsZeroWhenCompleted() throws Exception {
    given(useCase.run(any())).willReturn(
            new SettlementJobResult(10L, "settlementJob", "COMPLETED", START_TIME));

    runner.run(mock(ApplicationArguments.class));

    then(useCase).should().run(argThat(command ->
            command.triggerType() == TriggerType.SCHEDULED
                    && command.period().equals(SettlementPeriod.of(
                            LocalDate.of(2026, 7, 13), LocalDate.of(2026, 7, 19)))));
    assertThat(runner.getExitCode()).isZero();
}

@Test
void run_returnsOneWhenBatchDoesNotComplete() throws Exception {
    given(useCase.run(any())).willReturn(
            new SettlementJobResult(11L, "settlementJob", "FAILED", START_TIME));

    runner.run(mock(ApplicationArguments.class));

    assertThat(runner.getExitCode()).isEqualTo(1);
}

@Test
void run_returnsOneWhenUseCaseThrows() throws Exception {
    given(useCase.run(any())).willThrow(new IllegalStateException("order unavailable"));

    runner.run(mock(ApplicationArguments.class));

    assertThat(runner.getExitCode()).isEqualTo(1);
}
```

조건 테스트는 다음 context 구성을 사용해 property가 `cronjob`이면 runner 1개, property가 없으면 0개임을 단언한다.

```java
private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withBean(RunSettlementBatchUseCase.class,
                () -> mock(RunSettlementBatchUseCase.class))
        .withBean(Clock.class, () -> Clock.fixed(
                Instant.parse("2026-07-19T15:00:00Z"),
                ZoneId.of("Asia/Seoul")))
        .withUserConfiguration(SettlementCronJobRunner.class);

@Test
void cronjobMode_createsRunner() {
    contextRunner
            .withPropertyValues("settlement.execution.mode=cronjob")
            .run(context -> assertThat(context)
                    .hasSingleBean(SettlementCronJobRunner.class));
}

@Test
void defaultMode_doesNotCreateRunner() {
    contextRunner.run(context -> assertThat(context)
            .doesNotHaveBean(SettlementCronJobRunner.class));
}
```

- [ ] **Step 2: runner가 없어 테스트가 실패하는지 확인한다**

Run: `./gradlew :settlement-service:test --tests 'com.prompthub.settlement.infrastructure.batch.runner.*'`

Expected: `SettlementCronJobRunner` 타입 부재로 컴파일 FAIL

- [ ] **Step 3: 서울 Clock과 조건부 one-shot runner를 구현한다**

```java
@Configuration
public class SettlementClockConfig {
    public static final ZoneId SETTLEMENT_ZONE = ZoneId.of("Asia/Seoul");

    @Bean
    Clock settlementClock() {
        return Clock.system(SETTLEMENT_ZONE);
    }
}
```

```java
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "settlement.execution.mode", havingValue = "cronjob")
public class SettlementCronJobRunner implements ApplicationRunner, ExitCodeGenerator {

    private final RunSettlementBatchUseCase runSettlementBatchUseCase;
    private final Clock clock;
    private int exitCode = 1;

    @Override
    public void run(ApplicationArguments args) {
        SettlementPeriod period = SettlementPeriod.previousWeek(LocalDate.now(clock));
        try {
            SettlementJobResult result = runSettlementBatchUseCase.run(
                    RunSettlementJobCommand.scheduled(period));
            exitCode = "COMPLETED".equals(result.status()) ? 0 : 1;
            log.info("주간 정산 CronJob 종료. periodStart={}, periodEnd={}, jobExecutionId={}, status={}",
                    period.periodStart(), period.periodEnd(), result.jobExecutionId(), result.status());
        } catch (Exception exception) {
            exitCode = 1;
            log.error("주간 정산 CronJob 실패. periodStart={}, periodEnd={}",
                    period.periodStart(), period.periodEnd(), exception);
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
```

- [ ] **Step 4: cronjob 모드에서 runner 완료 후 JVM 종료 코드를 연결한다**

```java
public static void main(String[] args) {
    ConfigurableApplicationContext context =
            SpringApplication.run(SettlementApplication.class, args);
    if ("cronjob".equalsIgnoreCase(
            context.getEnvironment().getProperty("settlement.execution.mode"))) {
        System.exit(SpringApplication.exit(context));
    }
}
```

SpringApplication은 `ApplicationRunner`가 끝난 뒤 context를 반환하고, `SpringApplication.exit`가 runner의 `ExitCodeGenerator` 값을 수집한다.

- [ ] **Step 5: 내부 scheduler와 scheduler 설정을 제거한다**

Task 3에서 `SettlementBatchScheduler.java`를 이미 삭제했다. 남아 있는 `SchedulingConfig.java`를 삭제하고 다음 설정 결과만 남긴다.

```yaml
# settlement-service/src/main/resources/application.yml
settlement:
  execution:
    mode: service
  manual-api:
    enabled: false
```

```yaml
# settlement-service/src/main/resources/application-local.yml
settlement:
  execution:
    mode: service
  manual-api:
    enabled: true
```

`config/src/main/resources/configs/settlement-service.yml`에서 `settlement.scheduler.cron` 블록만 제거하고 Kafka 설정은 유지한다.

- [ ] **Step 6: runner·조건·회귀 테스트를 통과시킨다**

Run: `./gradlew :settlement-service:test --tests 'com.prompthub.settlement.infrastructure.batch.runner.*' --tests com.prompthub.settlement.SettlementApplicationTests`

Expected: `BUILD SUCCESSFUL`; 성공 0, 실패/예외 1, 조건부 bean 테스트 PASS

Run: `rg -n "@Scheduled|EnableScheduling|settlement\.scheduler" settlement-service/src/main config/src/main/resources/configs/settlement-service.yml`

Expected: 출력 없음

- [ ] **Step 7: one-shot 실행 전환을 커밋한다**

```bash
git add settlement-service/src/main settlement-service/src/test config/src/main/resources/configs/settlement-service.yml
git commit -m "feat: 정산 CronJob 일회성 실행 모드 추가"
```

---

### Task 5: Gateway와 Config에서 settlement 상시 서비스 제거

**Files:**
- Modify: `apigateway/src/main/java/com/prompthub/apigateway/route/VersionedServiceRoute.java`
- Modify: `apigateway/src/test/java/com/prompthub/apigateway/route/VersionedRouteDefinitionLocatorTest.java`
- Modify: `apigateway/src/main/resources/application.yml`
- Modify: `config/src/main/resources/configs/application.yml`

**Interfaces:**
- Consumes: 운영 settlement-service에는 HTTP Service와 Eureka 등록이 없다는 Task 4 계약
- Produces: Gateway 동적 라우트 5개, settlement docs/swagger/api-version 참조 0개

- [ ] **Step 1: settlement 라우트가 없어야 한다는 실패 테스트를 작성한다**

기존 settlement/admin 우선순위 테스트를 삭제하고 다음 회귀 테스트를 추가한다.

```java
@Test
void 전체_서비스_기본_설정에_settlement_service_라우트가_없다() {
    Map<String, List<String>> config = new LinkedHashMap<>();
    for (VersionedServiceRoute spec : VersionedServiceRoute.ALL) {
        config.put(spec.id(), List.of("v1"));
    }
    config.put("settlement-service", List.of("v1", "v2"));

    List<RouteDefinition> definitions =
            VersionedRouteDefinitionLocator.buildRouteDefinitions(propertiesOf(config));

    assertThat(definitions).hasSize(5);
    assertThat(definitions).extracting(RouteDefinition::getId)
            .doesNotContain("settlement-service");
}
```

- [ ] **Step 2: 기존 route가 남아 실패하는지 확인한다**

Run: `./gradlew :apigateway:test --tests com.prompthub.apigateway.route.VersionedRouteDefinitionLocatorTest`

Expected: route 수 또는 settlement route 단언 FAIL

- [ ] **Step 3: route spec과 설명에서 settlement를 제거한다**

`VersionedServiceRoute.ALL`의 첫 항목을 `admin-service`, order `0`으로 시작하고 이후 order/product/payment/user 순서를 각각 `1`~`4`로 둔다. 클래스 Javadoc의 settlement 우선순위 설명을 제거하고 “낮은 order가 먼저 매칭된다”만 남긴다.

```java
new VersionedServiceRoute(
        "admin-service",
        "lb://ADMIN-SERVICE",
        List.of("/admin/settlements/**"),
        0
)
```

- [ ] **Step 4: Gateway YAML 두 소스에서 settlement 항목을 제거한다**

`apigateway/src/main/resources/application.yml`에서 아래 세 종류를 모두 삭제한다.

```text
id: settlement-service-docs
gateway.api-versions.settlement-service
springdoc.swagger-ui.urls[name=settlement-service]
```

`config/src/main/resources/configs/application.yml`에서는 `gateway.api-versions.settlement-service`를 삭제한다. 나머지 서비스 버전과 route policy는 유지한다.

- [ ] **Step 5: Gateway 테스트와 정적 검색을 통과시킨다**

Run: `./gradlew :apigateway:test --tests com.prompthub.apigateway.route.VersionedRouteDefinitionLocatorTest`

Expected: `BUILD SUCCESSFUL`, route 5개

Run: `rg -n "settlement-service" apigateway/src/main config/src/main/resources/configs/application.yml`

Expected: 출력 없음

- [ ] **Step 6: 상시 라우트 제거를 커밋한다**

```bash
git add apigateway/src/main apigateway/src/test config/src/main/resources/configs/application.yml
git commit -m "refactor: 게이트웨이 정산 서비스 라우트 제거"
```

---

### Task 6: Kubernetes Deployment를 주간 CronJob으로 교체

**Files:**
- Create: `k8s/base/services/settlement/cronjob.yaml`
- Modify: `k8s/base/services/settlement/kustomization.yaml`
- Delete: `k8s/base/services/settlement/deployment.yaml`
- Delete: `k8s/base/services/settlement/service.yaml`
- Modify: `scripts/validate-k8s-manifests.sh`

**Interfaces:**
- Consumes: Task 4 args `settlement.execution.mode=cronjob`; order-service ClusterIP gRPC port `9083`
- Produces: `batch/v1 CronJob/settlement-weekly`, 8 Deployments + 8 Services + 1 CronJob application overlay

- [ ] **Step 1: 매니페스트 검증기를 먼저 CronJob 기대값으로 바꾼다**

settlement package 전용 검사 블록을 추가한다.

```bash
if [[ "${package}" == "k8s/base/services/settlement" ]]; then
  required_patterns=(
    '^kind:[[:space:]]+CronJob$'
    '^[[:space:]]+name:[[:space:]]+settlement-weekly$'
    '^[[:space:]]+schedule:[[:space:]]+"0 0 \* \* 1"$'
    '^[[:space:]]+timeZone:[[:space:]]+Asia/Seoul$'
    '^[[:space:]]+concurrencyPolicy:[[:space:]]+Forbid$'
    '^[[:space:]]+startingDeadlineSeconds:[[:space:]]+3600$'
    '^[[:space:]]+successfulJobsHistoryLimit:[[:space:]]+3$'
    '^[[:space:]]+failedJobsHistoryLimit:[[:space:]]+3$'
    '^[[:space:]]+backoffLimit:[[:space:]]+1$'
    '^[[:space:]]+activeDeadlineSeconds:[[:space:]]+7200$'
    '^[[:space:]]+restartPolicy:[[:space:]]+Never$'
    'settlement.execution.mode=cronjob'
    'spring.main.web-application-type=none'
    'eureka.client.enabled=false'
    'spring.cloud.discovery.enabled=false'
    'until nc -z order-service 9083'
  )
  for pattern in "${required_patterns[@]}"; do
    grep -Eq -- "${pattern}" "${rendered}" || {
      echo "missing settlement CronJob contract: ${pattern}" >&2
      exit 1
    }
  done
  if grep -Eq '^kind:[[:space:]]+(Deployment|Service)$' "${rendered}"; then
    echo "settlement package must only expose a CronJob" >&2
    exit 1
  fi
  if grep -Eq 'EUREKA_CLIENT|startupProbe:|readinessProbe:|livenessProbe:|containerPort:' "${rendered}"; then
    echo "settlement CronJob contains an always-on service contract" >&2
    exit 1
  fi
fi
```

applications overlay의 count와 allowed kind 검사를 다음 값으로 바꾼다.

```bash
cronjob_count="$(awk '$1 == "kind:" && $2 == "CronJob" { count++ } END { print count + 0 }' "${rendered}")"
unexpected_kinds="$(awk '$1 == "kind:" && $2 != "Deployment" && $2 != "Service" && $2 != "CronJob" { print $2 }' "${rendered}" | sort -u)"
if [[ "${deployment_count}" -ne 8 || "${service_count}" -ne 8 || "${cronjob_count}" -ne 1 ]]; then
  echo "application CD package must render 8 Deployments, 8 Services, and 1 CronJob" >&2
  exit 1
fi
```

- [ ] **Step 2: validator가 기존 Deployment/Service 때문에 실패하는지 확인한다**

Run: `bash scripts/validate-k8s-manifests.sh`

Expected: `missing settlement CronJob contract` 또는 resource count로 FAIL

- [ ] **Step 3: 고정 운영 계약의 CronJob 매니페스트를 작성한다**

`cronjob.yaml`의 바깥 구조는 다음 값으로 작성한다.

```yaml
apiVersion: batch/v1
kind: CronJob
metadata:
  name: settlement-weekly
  labels:
    app.kubernetes.io/name: settlement-service
    app.kubernetes.io/part-of: prompthub
    app.kubernetes.io/component: batch
    app.kubernetes.io/managed-by: kustomize
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
        metadata:
          labels:
            app.kubernetes.io/name: settlement-service
            app.kubernetes.io/part-of: prompthub
            app.kubernetes.io/component: batch
        spec:
          automountServiceAccountToken: false
          enableServiceLinks: false
          restartPolicy: Never
          nodeSelector:
            prompthub.io/node-pool: application
          imagePullSecrets:
            - name: ghcr-pull-secret
```

기존 Deployment에서 initContainer의 busybox 이미지·resource, main container 이미지·imagePullPolicy·securityContext·resource·`JAVA_TOOL_OPTIONS`, PostgreSQL/Kafka/order gRPC Secret env를 그대로 옮긴다. init command는 정확히 다음 네 의존성만 기다린다.

```sh
until wget -q -O /dev/null http://config:8888/actuator/health; do sleep 2; done
until nc -z postgres 5432; do sleep 2; done
until nc -z kafka 9092; do sleep 2; done
until nc -z order-service 9083; do sleep 2; done
```

main container args는 다음 여섯 개다.

```yaml
args:
  - --spring.main.web-application-type=none
  - --settlement.execution.mode=cronjob
  - --settlement.manual-api.enabled=false
  - --eureka.client.enabled=false
  - --spring.cloud.discovery.enabled=false
  - --spring.grpc.client.channel.order-service.target=static://order-service:$(ORDER_GRPC_SERVER_PORT)
```

Eureka env, management probe env, HTTP port와 세 probe는 옮기지 않는다.

- [ ] **Step 4: Kustomization에서 old resource를 제거한다**

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
namespace: prompthub
resources:
  - cronjob.yaml
```

`deployment.yaml`과 `service.yaml`은 삭제한다.

- [ ] **Step 5: render와 전체 매니페스트 검증을 통과시킨다**

Run: `kubectl kustomize k8s/base/services/settlement`

Expected: `CronJob/settlement-weekly` 1개, Deployment/Service 0개

Run: `kubectl kustomize k8s/overlays/ec2-kubeadm/applications | awk '$1 == "kind:" {print $2}' | sort | uniq -c`

Expected: `1 CronJob`, `8 Deployment`, `8 Service`

Run: `bash scripts/validate-k8s-manifests.sh`

Expected: `Kubernetes manifest validation passed.`

- [ ] **Step 6: CronJob 매니페스트를 커밋한다**

```bash
git add k8s/base/services/settlement scripts/validate-k8s-manifests.sh
git commit -m "infra: 정산 서비스를 주간 CronJob으로 전환"
```

---

### Task 7: Kubernetes CD의 CronJob 이미지 갱신과 rollback

**Files:**
- Modify: `.github/workflows/cd-selfhosted-kubernetes.yml`
- Modify: `scripts/validate-k8s-cd-workflow.sh`

**Interfaces:**
- Consumes: `CronJob/settlement-weekly`, main container `settlement-service`
- Produces: CronJob 존재 보장, image update/verification/rollback, old Deployment/Service 안전한 전환 삭제, 자동 Job 미생성

- [ ] **Step 1: CD validator에 CronJob 계약과 실행 금지 규칙을 추가한다**

`required_patterns`에 다음 패턴을 추가하고 Deployment 전용 기존 패턴은 다른 서비스 검증을 위해 유지한다.

```bash
'ensure_settlement_cronjob'
'settlement_cronjob="settlement-weekly"'
'snapshot_settlement_cronjob'
'rollback_settlement_cronjob'
'kubectl set image cronjob/'
'kubectl get cronjob "\$settlement_cronjob"'
'kubectl delete deployment/settlement-service'
'kubectl delete service/settlement-service'
```

`forbidden_patterns`에 실제 정산 실행을 막는 다음 문자열을 추가한다.

```bash
'kubectl create job'
'--from=cronjob/settlement-weekly'
```

- [ ] **Step 2: workflow가 아직 Deployment 전용이라 validator가 실패하는지 확인한다**

Run: `bash scripts/validate-k8s-cd-workflow.sh`

Expected: `missing contract: ensure_settlement_cronjob`로 FAIL

- [ ] **Step 3: 최초 리소스 준비에서 CronJob을 분리한다**

기존 `ensure_package settlement-service ...`를 삭제하고 다음 함수를 호출한다.

```bash
ensure_settlement_cronjob() {
  if ! kubectl get cronjob settlement-weekly -n "$NAMESPACE" >/dev/null 2>&1; then
    kubectl apply -k k8s/base/services/settlement
  fi
}

ensure_settlement_cronjob
kubectl get cronjob settlement-weekly -n "$NAMESPACE" >/dev/null
```

`application_deployments`, `deployment_order`, `config_consumers` 배열에서 `settlement-service`를 제거한다. build matrix에는 settlement-service를 그대로 유지하고, 이미지 배포 순서는 별도 배열로 정의한다.

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
  apigateway
)
deployment_order=(
  config
  discovery
  user-service
  product-service
  order-service
  payment-service
  admin-service
  apigateway
)
```

- [ ] **Step 4: CronJob 스냅샷과 rollback 상태를 추가한다**

배포 스크립트 상단에 다음 상태를 둔다.

```bash
settlement_cronjob="settlement-weekly"
settlement_cronjob_previous_image=""
settlement_cronjob_existed="false"
settlement_cronjob_updated="false"
settlement_cronjob_snapshot_taken="false"
```

함수는 다음 동작을 정확히 수행한다.

```bash
snapshot_settlement_cronjob() {
  if [ "$settlement_cronjob_snapshot_taken" = "true" ]; then
    return
  fi
  if settlement_cronjob_previous_image="$(kubectl get cronjob "$settlement_cronjob" -n "$NAMESPACE" \
    -o jsonpath="{.spec.jobTemplate.spec.template.spec.containers[?(@.name=='settlement-service')].image}" 2>/dev/null)"; then
    settlement_cronjob_existed="true"
  else
    settlement_cronjob_previous_image=""
    settlement_cronjob_existed="false"
  fi
  settlement_cronjob_snapshot_taken="true"
}

rollback_settlement_cronjob() {
  if [ "$settlement_cronjob_updated" != "true" ]; then
    return
  fi
  if [ "$settlement_cronjob_existed" = "true" ]; then
    kubectl set image cronjob/"$settlement_cronjob" \
      settlement-service="$settlement_cronjob_previous_image" -n "$NAMESPACE"
  else
    kubectl delete cronjob/"$settlement_cronjob" -n "$NAMESPACE" --ignore-not-found
  fi
}
```

`rollback_deployments`의 Deployment undo/delete 전에 `rollback_settlement_cronjob`을 호출한다.

- [ ] **Step 5: manifest apply 경로에서 CronJob 생성·변경을 추적한다**

`snapshot_manifest_deployments` 직전에 `snapshot_settlement_cronjob`을 호출한다. apply 뒤 다음 순서로 검증한다.

```bash
kubectl apply -k "$runtime_overlay"
track_manifest_deployment_changes
kubectl get cronjob "$settlement_cronjob" -n "$NAMESPACE" >/dev/null

current_settlement_image="$(kubectl get cronjob "$settlement_cronjob" -n "$NAMESPACE" \
  -o jsonpath="{.spec.jobTemplate.spec.template.spec.containers[?(@.name=='settlement-service')].image}")"
if [ "$settlement_cronjob_existed" != "true" ] || \
  [ "$current_settlement_image" != "$settlement_cronjob_previous_image" ]; then
  settlement_cronjob_updated="true"
fi
```

Deployment rollout가 모두 성공하고 CronJob 이미지 검증까지 끝난 뒤 stale 리소스를 지운다.

```bash
kubectl delete deployment/settlement-service -n "$NAMESPACE" --ignore-not-found
kubectl delete service/settlement-service -n "$NAMESPACE" --ignore-not-found
```

- [ ] **Step 6: settlement-service build를 CronJob image update로 분기한다**

기존 `for service in "${deployment_order[@]}"`를 `for service in "${release_order[@]}"`로 바꾸고, Deployment 처리 전에 아래 분기를 넣어 `continue`한다. 이 분기 뒤의 기존 코드는 release_order 중 나머지 8개 Deployment만 처리한다.

```bash
if [ "$service" = "settlement-service" ]; then
  image="ghcr.io/$owner_lc/prompthub-settlement-service:$short_sha"
  snapshot_settlement_cronjob
  current_image="$(kubectl get cronjob "$settlement_cronjob" -n "$NAMESPACE" \
    -o jsonpath="{.spec.jobTemplate.spec.template.spec.containers[?(@.name=='settlement-service')].image}")"
  if [ "$current_image" = "$image" ]; then
    echo "이미 배포된 이미지이므로 건너뜁니다: settlement-service -> $image"
    continue
  fi
  kubectl set image cronjob/"$settlement_cronjob" settlement-service="$image" -n "$NAMESPACE"
  kubectl annotate cronjob/"$settlement_cronjob" \
    kubernetes.io/change-cause="GitHub Actions $GITHUB_SHA" -n "$NAMESPACE" --overwrite
  deployed_image="$(kubectl get cronjob "$settlement_cronjob" -n "$NAMESPACE" \
    -o jsonpath="{.spec.jobTemplate.spec.template.spec.containers[?(@.name=='settlement-service')].image}")"
  test "$deployed_image" = "$image"
  settlement_cronjob_updated="true"
  continue
fi
```

이 경로에는 `kubectl create job`이나 `kubectl rollout status cronjob`을 넣지 않는다.

- [ ] **Step 7: CD와 전체 매니페스트 정적 검증을 통과시킨다**

Run: `bash scripts/validate-k8s-cd-workflow.sh`

Expected: `Kubernetes CD workflow validation passed.`

Run: `bash scripts/validate-k8s-manifests.sh`

Expected: `Kubernetes manifest validation passed.`

Run: `rg -n "settlement-service" .github/workflows/cd-selfhosted-kubernetes.yml`

Expected: build matrix, `release_order`, CronJob container/image 처리, stale Deployment/Service 삭제에만 존재하며 `deployment_order`와 `config_consumers` 배열에는 없음

- [ ] **Step 8: CD 전환을 커밋한다**

```bash
git add .github/workflows/cd-selfhosted-kubernetes.yml scripts/validate-k8s-cd-workflow.sh
git commit -m "ci: 정산 CronJob 이미지 배포 지원"
```

---

### Task 8: 문서 동기화와 전체 검증

**Files:**
- Modify: `settlement-service/.claude/rules/clean-architecture.md`
- Modify: `settlement-service/docs/settlement-api-for-frontend.md`
- Modify: `settlement-service/docs/final-roadmap.md`
- Modify: `settlement-service/docs/architecture/integration-catalog.md`
- Modify: `settlement-service/docs/architecture/settlement-internal-comm-topology.md`
- Modify: `settlement-service/docs/trade-offs/order-data-sourcing.md`
- Modify: `k8s/README.md`

**Interfaces:**
- Consumes: Tasks 1~7의 최종 코드·wire·운영 계약
- Produces: 현재 구현과 일치하는 로컬 API, gRPC, CronJob, CD 운영 문서와 최종 검증 증거

- [ ] **Step 1: 문서에서 stale 월간·scheduler·Deployment 계약을 검색한다**

Run: `rg -n "YearMonth|yyyy-MM|월간|월별|@Scheduled|SettlementBatchScheduler|GetSettleableLines\(period\)|Deployment|settlement-service-docs" settlement-service/docs settlement-service/.claude/rules/clean-architecture.md k8s/README.md`

Expected: 수정 대상 위치가 출력됨

- [ ] **Step 2: 로컬 API와 아키텍처 문서를 주간 계약으로 고친다**

`settlement-api-for-frontend.md`의 요청 예시와 TypeScript 타입은 정확히 다음 계약을 쓴다.

```json
{
  "periodStart": "2026-07-13",
  "periodEnd": "2026-07-19"
}
```

```typescript
interface RunSettlementJobRequest {
  periodStart: string; // 포함 시작일, 월요일 yyyy-MM-dd
  periodEnd: string;   // 포함 종료일, 일요일 yyyy-MM-dd
}
```

오류 표에는 시작/종료 누락, 날짜 형식 오류, 월요일~일요일 규칙 위반이 모두 `400 / S-003`임을 적는다. 운영 Gateway에서는 이 로컬 API가 노출되지 않으며 settlement-service 직접 실행에서만 확인한다고 명시한다.

`clean-architecture.md`, `final-roadmap.md`, `integration-catalog.md`, `settlement-internal-comm-topology.md`, `order-data-sourcing.md`에는 다음 사실을 동일하게 기록한다.

```text
Kubernetes CronJob settlement-weekly -> SettlementCronJobRunner -> settlementJob
schedule: 매주 월요일 00:00 Asia/Seoul
request: period_start/period_end 포함 날짜
query: [periodStart 00:00, periodEnd + 1일 00:00)
legacy: period(yyyy-MM)는 order-service 배포 호환 fallback만 제공
```

서버 handler가 미구현이라는 기존 문구와 월 단일 `period`가 현행 계약이라는 문구는 제거한다.

- [ ] **Step 3: Kubernetes 운영 문서를 현재 리소스와 맞춘다**

`k8s/README.md`에 다음 확인 명령과 정책을 추가한다.

```bash
kubectl get cronjob settlement-weekly -n prompthub
kubectl get jobs -n prompthub -l app.kubernetes.io/name=settlement-service
kubectl logs -n prompthub job/settlement-weekly-29123456 -c settlement-service
```

마지막 명령의 Job 이름은 승인 후 생성된 실제 이름을 조회해 바꾸는 예시라고 설명한다. 문서에는 CD가 CronJob template 이미지만 갱신하고 정산 Job을 생성하지 않는다는 점, 수동 `kubectl create job --from=cronjob/settlement-weekly`는 별도 운영 승인 대상이라는 점, 완료된 과거 주차 보정은 이 CronJob 범위 밖이라는 점을 명시한다.

- [ ] **Step 4: 서비스 테스트와 빌드를 실행한다**

Run: `./gradlew :settlement-service:test :order-service:test :apigateway:test`

Expected: `BUILD SUCCESSFUL`. baseline에서 관찰된 order-service Kafka shutdown 임시 디렉터리 경고가 다시 보여도 Gradle exit code 0과 테스트 결과를 기준으로 판정한다.

Run: `./gradlew :settlement-service:build :order-service:build :apigateway:build`

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Kubernetes와 CD 검증을 다시 실행한다**

Run: `kubectl kustomize k8s/overlays/ec2-kubeadm/applications >/dev/null`

Expected: exit code 0

Run: `bash scripts/validate-k8s-manifests.sh`

Expected: `Kubernetes manifest validation passed.`

Run: `bash scripts/validate-k8s-cd-workflow.sh`

Expected: `Kubernetes CD workflow validation passed.`

Run: `bash scripts/validate-k8s-secret-contract.sh`

Expected: secret contract validator의 `passed.` 출력

- [ ] **Step 6: 범위·계약·diff 품질을 점검한다**

Run: `rg -n "@Scheduled|EnableScheduling|settlement\.scheduler|jobParameters\['period'\]|setPeriod\(" settlement-service/src/main config/src/main/resources/configs/settlement-service.yml`

Expected: 출력 없음

Run: `rg -n "settlement-service" apigateway/src/main config/src/main/resources/configs/application.yml`

Expected: 출력 없음

Run: `git diff --check origin/develop...HEAD`

Expected: 출력 없음, exit code 0

Run: `git status --short`

Expected: 문서 변경만 아직 커밋 전으로 표시됨

- [ ] **Step 7: 문서와 최종 검증 상태를 커밋한다**

```bash
git add settlement-service/.claude/rules/clean-architecture.md settlement-service/docs/settlement-api-for-frontend.md settlement-service/docs/final-roadmap.md settlement-service/docs/architecture/integration-catalog.md settlement-service/docs/architecture/settlement-internal-comm-topology.md settlement-service/docs/trade-offs/order-data-sourcing.md k8s/README.md
git commit -m "docs: 주간 정산 CronJob 운영 계약 반영"
```

- [ ] **Step 8: 최종 커밋 범위와 clean status를 확인한다**

Run: `git log --oneline origin/develop..HEAD`

Expected: 설계·계획 커밋과 Task 1~8의 논리 커밋이 순서대로 출력됨

Run: `git status --short`

Expected: 출력 없음

최종 보고에는 운영 클러스터 미적용, 과거 완료 주차 누락 보정 미구현, order gRPC legacy fallback 유지, 매주 월요일 00:00 KST 실행을 함께 명시한다.
