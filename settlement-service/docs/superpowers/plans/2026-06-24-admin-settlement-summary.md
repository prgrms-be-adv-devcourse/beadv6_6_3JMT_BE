# 어드민 정산 요약 조회 API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `GET /admin/settlements/summary` — 기준 월의 정산을 표시 상태별로 집계해 금액 합계·건수를 반환하는 어드민 API를 구현한다.

**Architecture:** DB는 `GROUP BY (settlement_status, payout_status)`로 단순 그룹 집계만 하고, 표시 상태 파생·병합은 애플리케이션 자바 도메인 로직에서 한다. 헥사고날 계층(presentation → application → domain ← infrastructure)을 따른다.

**Tech Stack:** Java 17 + Spring Boot 3 (Spring MVC, Spring Data JPA / Hibernate 6), JUnit 5 + Mockito + AssertJ, Gradle.

## Global Constraints

- 생성물은 모두 `settlement-service/` 모듈 하위에 둔다.
- 도메인 모델은 `ErrorCode`/`HttpStatus`를 import하지 않는다. 표현·애플리케이션 예외는 `SettlementException(ErrorCode)`.
- 응답은 common `ApiResult<T>`로 감싼다. 컨트롤러는 `~Response` DTO를 반환하고 도메인 모델을 직접 노출하지 않는다.
- 빌드/테스트는 `settlement-service/`에서 `./gradlew test` 로 실행한다.
- 표시 상태 enum 값: `PENDING_APPROVAL`, `SETTLEMENT_ON_HOLD`, `APPROVED`, `PAYOUT_REQUESTED`, `PAYOUT_ON_HOLD`, `PAID`, `CANCELLED`.
- `items[].totalAmount`는 `settlementTotalAmount`(지급 순액) 합계. `period` 생략 시 직전 월(`now-1개월`).
- `WebConfig`가 이미 `/admin/**`에 `AdminAuthorizationInterceptor`를 등록하므로 ADMIN 권한은 신규 컨트롤러에 자동 적용된다. 추가 배선 불필요.

---

## File Structure

- Create `domain/model/enums/SettlementDisplayStatus.java` — 통합 표시 상태 enum + 파생 `of(...)`.
- Create `domain/repository/SettlementStatusAggregate.java` — 집계 행 record.
- Modify `domain/repository/SettlementRepository.java` — 집계 포트 메서드 추가.
- Modify `infrastructure/persistence/SettlementJpaRepository.java` — JPQL 집계 쿼리.
- Modify `infrastructure/persistence/SettlementRepositoryAdapter.java` — 집계 위임.
- Create `application/usecase/GetSettlementSummaryUseCase.java` — 인바운드 포트.
- Create `application/dto/SettlementSummaryResult.java` — 결과 DTO(중첩 Item).
- Create `application/service/SettlementSummaryApplicationService.java` — period 보정·병합.
- Create `presentation/dto/response/SettlementSummaryResponse.java` — 응답 DTO(중첩 Item, `from`).
- Create `presentation/controller/AdminSettlementController.java` — `GET /summary`.
- Tests: `SettlementDisplayStatusTest`, `SettlementSummaryApplicationServiceTest`, `SettlementSummaryResponseTest`.

**범위 밖 / YAGNI:** `Settlement.displayStatus()` 인스턴스 메서드는 집계 경로에서 쓰이지 않으므로(static `of()`만 사용) 본 작업에서 추가하지 않는다. #56 목록 조회에서 필요할 때 도입한다.

**테스트 전략 메모:** 기존 코드 관행대로 단위 테스트(도메인 파생, 애플리케이션 병합·보정, 응답 변환) 위주로 커버한다. 영속성 집계 쿼리(JpaRepository `@Query`)는 통합 테스트 인프라(@DataJpaTest용 임베디드 DB)가 갖춰져 있지 않아, JPQL 파싱·매핑은 `./gradlew test`(스프링 컨텍스트 부팅) 통과로 검증한다.

---

## Task 1: 표시 상태 파생 (SettlementDisplayStatus)

**Files:**
- Create: `src/main/java/com/prompthub/settlement/domain/model/enums/SettlementDisplayStatus.java`
- Test: `src/test/java/com/prompthub/settlement/domain/model/enums/SettlementDisplayStatusTest.java`

**Interfaces:**
- Produces: `enum SettlementDisplayStatus { PENDING_APPROVAL, SETTLEMENT_ON_HOLD, APPROVED, PAYOUT_REQUESTED, PAYOUT_ON_HOLD, PAID, CANCELLED }` + `static SettlementDisplayStatus of(SettlementStatus, PayoutStatus)`.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/prompthub/settlement/domain/model/enums/SettlementDisplayStatusTest.java`:

```java
package com.prompthub.settlement.domain.model.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class SettlementDisplayStatusTest {

    @ParameterizedTest
    @CsvSource({
            "PENDING_APPROVAL,   NOT_READY,        PENDING_APPROVAL",
            "PENDING_APPROVAL,   PAID,             PENDING_APPROVAL",
            "SETTLEMENT_ON_HOLD, NOT_READY,        SETTLEMENT_ON_HOLD",
            "CANCELLED,          NOT_READY,        CANCELLED",
            "APPROVED,           NOT_READY,        APPROVED",
            "APPROVED,           READY,            APPROVED",
            "APPROVED,           PAYOUT_REQUESTED, PAYOUT_REQUESTED",
            "APPROVED,           PAYOUT_ON_HOLD,   PAYOUT_ON_HOLD",
            "APPROVED,           PAID,             PAID"
    })
    @DisplayName("settlementStatus와 payoutStatus 조합으로 표시 상태를 파생한다")
    void of_derivesDisplayStatus(SettlementStatus settlementStatus, PayoutStatus payoutStatus,
                                 SettlementDisplayStatus expected) {
        assertThat(SettlementDisplayStatus.of(settlementStatus, payoutStatus)).isEqualTo(expected);
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd settlement-service && ./gradlew test --tests "com.prompthub.settlement.domain.model.enums.SettlementDisplayStatusTest"`
Expected: 컴파일 실패 — `SettlementDisplayStatus` 심볼 없음.

- [ ] **Step 3: 최소 구현**

`src/main/java/com/prompthub/settlement/domain/model/enums/SettlementDisplayStatus.java`:

```java
package com.prompthub.settlement.domain.model.enums;

public enum SettlementDisplayStatus {

    PENDING_APPROVAL,
    SETTLEMENT_ON_HOLD,
    APPROVED,
    PAYOUT_REQUESTED,
    PAYOUT_ON_HOLD,
    PAID,
    CANCELLED;

    public static SettlementDisplayStatus of(SettlementStatus settlementStatus, PayoutStatus payoutStatus) {
        return switch (settlementStatus) {
            case PENDING_APPROVAL -> PENDING_APPROVAL;
            case SETTLEMENT_ON_HOLD -> SETTLEMENT_ON_HOLD;
            case CANCELLED -> CANCELLED;
            case APPROVED -> fromPayout(payoutStatus);
        };
    }

    private static SettlementDisplayStatus fromPayout(PayoutStatus payoutStatus) {
        return switch (payoutStatus) {
            case NOT_READY, READY -> APPROVED;
            case PAYOUT_REQUESTED -> PAYOUT_REQUESTED;
            case PAYOUT_ON_HOLD -> PAYOUT_ON_HOLD;
            case PAID -> PAID;
        };
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd settlement-service && ./gradlew test --tests "com.prompthub.settlement.domain.model.enums.SettlementDisplayStatusTest"`
Expected: PASS (9 케이스).

- [ ] **Step 5: 커밋**

```bash
git add settlement-service/src/main/java/com/prompthub/settlement/domain/model/enums/SettlementDisplayStatus.java settlement-service/src/test/java/com/prompthub/settlement/domain/model/enums/SettlementDisplayStatusTest.java
git commit -m "feat: 정산 표시 상태(SettlementDisplayStatus) 파생 로직 추가 (#55)"
```

---

## Task 2: 영속성 집계 (SettlementStatusAggregate + Repository)

**Files:**
- Create: `src/main/java/com/prompthub/settlement/domain/repository/SettlementStatusAggregate.java`
- Modify: `src/main/java/com/prompthub/settlement/domain/repository/SettlementRepository.java`
- Modify: `src/main/java/com/prompthub/settlement/infrastructure/persistence/SettlementJpaRepository.java`
- Modify: `src/main/java/com/prompthub/settlement/infrastructure/persistence/SettlementRepositoryAdapter.java`

**Interfaces:**
- Produces: `record SettlementStatusAggregate(SettlementStatus settlementStatus, PayoutStatus payoutStatus, BigDecimal sumSettlementTotal, long count)`; `List<SettlementStatusAggregate> SettlementRepository.aggregateByPeriod(LocalDate periodStart, LocalDate periodEnd)`.
- Consumes: `SettlementDisplayStatus.of(...)` (Task 1, 다음 태스크에서 사용).

- [ ] **Step 1: 집계 행 record 생성**

`src/main/java/com/prompthub/settlement/domain/repository/SettlementStatusAggregate.java`:

```java
package com.prompthub.settlement.domain.repository;

import com.prompthub.settlement.domain.model.enums.PayoutStatus;
import com.prompthub.settlement.domain.model.enums.SettlementStatus;
import java.math.BigDecimal;

public record SettlementStatusAggregate(
        SettlementStatus settlementStatus,
        PayoutStatus payoutStatus,
        BigDecimal sumSettlementTotal,
        long count
) {
}
```

- [ ] **Step 2: 포트 메서드 추가**

`SettlementRepository.java` — import와 메서드를 추가한다(기존 메서드 유지):

```java
package com.prompthub.settlement.domain.repository;

import com.prompthub.settlement.domain.model.Settlement;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface SettlementRepository {

    Settlement save(Settlement settlement);

    List<Settlement> saveAll(List<Settlement> settlements);

    List<Settlement> findBySettlementBatchId(UUID settlementBatchId);

    List<SettlementStatusAggregate> aggregateByPeriod(LocalDate periodStart, LocalDate periodEnd);
}
```

- [ ] **Step 3: JPQL 집계 쿼리 추가**

`SettlementJpaRepository.java`:

```java
package com.prompthub.settlement.infrastructure.persistence;

import com.prompthub.settlement.domain.model.Settlement;
import com.prompthub.settlement.domain.repository.SettlementStatusAggregate;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SettlementJpaRepository extends JpaRepository<Settlement, UUID> {

    List<Settlement> findBySettlementBatchId(UUID settlementBatchId);

    @Query("""
            select new com.prompthub.settlement.domain.repository.SettlementStatusAggregate(
                s.settlementStatus, s.payoutStatus, sum(s.settlementTotalAmount), count(s))
            from Settlement s
            where s.periodStart between :start and :end
            group by s.settlementStatus, s.payoutStatus
            """)
    List<SettlementStatusAggregate> aggregateByPeriod(@Param("start") LocalDate start, @Param("end") LocalDate end);
}
```

> 주의: `count(s)`는 `Long`을 반환한다. Hibernate constructor expression이 record의 `long count`로 매핑한다. 부팅 시 매핑 오류가 나면 record의 `count` 타입을 `Long`으로 바꾼다(이 경우 Task 3의 `agg.count()`는 그대로 동작).

- [ ] **Step 4: 어댑터 위임 추가**

`SettlementRepositoryAdapter.java` — import와 메서드 추가:

```java
import com.prompthub.settlement.domain.repository.SettlementStatusAggregate;
import java.time.LocalDate;
```

클래스 본문에 추가:

```java
    @Override
    public List<SettlementStatusAggregate> aggregateByPeriod(LocalDate periodStart, LocalDate periodEnd) {
        return jpaRepository.aggregateByPeriod(periodStart, periodEnd);
    }
```

- [ ] **Step 5: 컴파일·부팅 검증**

Run: `cd settlement-service && ./gradlew test --tests "com.prompthub.settlement.SettlementApplicationTests"`
Expected: PASS — 스프링 컨텍스트가 뜨고 JPQL 쿼리가 파싱된다(쿼리 문법/매핑 오류 없음).

- [ ] **Step 6: 커밋**

```bash
git add settlement-service/src/main/java/com/prompthub/settlement/domain/repository/ settlement-service/src/main/java/com/prompthub/settlement/infrastructure/persistence/
git commit -m "feat: 정산 상태별 집계 쿼리(aggregateByPeriod) 추가 (#55)"
```

---

## Task 3: 애플리케이션 서비스 (period 보정 + 표시상태 병합)

**Files:**
- Create: `src/main/java/com/prompthub/settlement/application/usecase/GetSettlementSummaryUseCase.java`
- Create: `src/main/java/com/prompthub/settlement/application/dto/SettlementSummaryResult.java`
- Create: `src/main/java/com/prompthub/settlement/application/service/SettlementSummaryApplicationService.java`
- Test: `src/test/java/com/prompthub/settlement/application/service/SettlementSummaryApplicationServiceTest.java`

**Interfaces:**
- Consumes: `SettlementRepository.aggregateByPeriod(...)`, `SettlementStatusAggregate` (Task 2), `SettlementDisplayStatus.of(...)` (Task 1).
- Produces: `SettlementSummaryResult getSummary(YearMonth period)`; `record SettlementSummaryResult(YearMonth period, List<Item> items)` with `record Item(SettlementDisplayStatus status, BigDecimal totalAmount, long count)`.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/prompthub/settlement/application/service/SettlementSummaryApplicationServiceTest.java`:

```java
package com.prompthub.settlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.prompthub.settlement.application.dto.SettlementSummaryResult;
import com.prompthub.settlement.domain.model.enums.PayoutStatus;
import com.prompthub.settlement.domain.model.enums.SettlementDisplayStatus;
import com.prompthub.settlement.domain.model.enums.SettlementStatus;
import com.prompthub.settlement.domain.repository.SettlementRepository;
import com.prompthub.settlement.domain.repository.SettlementStatusAggregate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SettlementSummaryApplicationServiceTest {

    @Mock
    private SettlementRepository settlementRepository;

    @InjectMocks
    private SettlementSummaryApplicationService service;

    @Captor
    private ArgumentCaptor<LocalDate> startCaptor;

    @Captor
    private ArgumentCaptor<LocalDate> endCaptor;

    @Test
    @DisplayName("지정한 월의 1일~말일 범위로 집계를 조회한다")
    void getSummary_usesGivenPeriodRange() {
        given(settlementRepository.aggregateByPeriod(startCaptor.capture(), endCaptor.capture()))
                .willReturn(List.of());

        service.getSummary(YearMonth.of(2026, 5));

        assertThat(startCaptor.getValue()).isEqualTo(LocalDate.of(2026, 5, 1));
        assertThat(endCaptor.getValue()).isEqualTo(LocalDate.of(2026, 5, 31));
    }

    @Test
    @DisplayName("같은 표시 상태로 파생되는 집계 행은 금액과 건수를 합산한다")
    void getSummary_mergesByDisplayStatus() {
        given(settlementRepository.aggregateByPeriod(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31)))
                .willReturn(List.of(
                        new SettlementStatusAggregate(SettlementStatus.APPROVED, PayoutStatus.NOT_READY,
                                new BigDecimal("100"), 1L),
                        new SettlementStatusAggregate(SettlementStatus.APPROVED, PayoutStatus.READY,
                                new BigDecimal("200"), 2L)));

        SettlementSummaryResult result = service.getSummary(YearMonth.of(2026, 5));

        assertThat(result.period()).isEqualTo(YearMonth.of(2026, 5));
        assertThat(result.items()).hasSize(1);
        SettlementSummaryResult.Item item = result.items().get(0);
        assertThat(item.status()).isEqualTo(SettlementDisplayStatus.APPROVED);
        assertThat(item.totalAmount()).isEqualByComparingTo("300");
        assertThat(item.count()).isEqualTo(3L);
    }

    @Test
    @DisplayName("period가 null이면 직전 월 범위로 집계를 조회한다")
    void getSummary_nullPeriod_usesPreviousMonth() {
        given(settlementRepository.aggregateByPeriod(startCaptor.capture(), endCaptor.capture()))
                .willReturn(List.of());

        YearMonth previous = YearMonth.now().minusMonths(1);
        SettlementSummaryResult result = service.getSummary(null);

        assertThat(result.period()).isEqualTo(previous);
        assertThat(startCaptor.getValue()).isEqualTo(previous.atDay(1));
        assertThat(endCaptor.getValue()).isEqualTo(previous.atEndOfMonth());
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd settlement-service && ./gradlew test --tests "com.prompthub.settlement.application.service.SettlementSummaryApplicationServiceTest"`
Expected: 컴파일 실패 — `SettlementSummaryApplicationService`, `SettlementSummaryResult` 심볼 없음.

- [ ] **Step 3: UseCase 포트 + Result DTO 작성**

`application/usecase/GetSettlementSummaryUseCase.java`:

```java
package com.prompthub.settlement.application.usecase;

import com.prompthub.settlement.application.dto.SettlementSummaryResult;
import java.time.YearMonth;

public interface GetSettlementSummaryUseCase {

    SettlementSummaryResult getSummary(YearMonth period);
}
```

`application/dto/SettlementSummaryResult.java`:

```java
package com.prompthub.settlement.application.dto;

import com.prompthub.settlement.domain.model.enums.SettlementDisplayStatus;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

public record SettlementSummaryResult(YearMonth period, List<Item> items) {

    public record Item(SettlementDisplayStatus status, BigDecimal totalAmount, long count) {
    }
}
```

- [ ] **Step 4: 서비스 구현**

`application/service/SettlementSummaryApplicationService.java`:

```java
package com.prompthub.settlement.application.service;

import com.prompthub.settlement.application.dto.SettlementSummaryResult;
import com.prompthub.settlement.application.dto.SettlementSummaryResult.Item;
import com.prompthub.settlement.application.usecase.GetSettlementSummaryUseCase;
import com.prompthub.settlement.domain.model.enums.SettlementDisplayStatus;
import com.prompthub.settlement.domain.repository.SettlementRepository;
import com.prompthub.settlement.domain.repository.SettlementStatusAggregate;
import java.time.YearMonth;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SettlementSummaryApplicationService implements GetSettlementSummaryUseCase {

    private final SettlementRepository settlementRepository;

    @Override
    @Transactional(readOnly = true)
    public SettlementSummaryResult getSummary(YearMonth period) {
        YearMonth target = (period != null) ? period : YearMonth.now().minusMonths(1);

        List<SettlementStatusAggregate> aggregates =
                settlementRepository.aggregateByPeriod(target.atDay(1), target.atEndOfMonth());

        Map<SettlementDisplayStatus, Item> merged = new EnumMap<>(SettlementDisplayStatus.class);
        for (SettlementStatusAggregate aggregate : aggregates) {
            SettlementDisplayStatus status =
                    SettlementDisplayStatus.of(aggregate.settlementStatus(), aggregate.payoutStatus());
            merged.merge(status,
                    new Item(status, aggregate.sumSettlementTotal(), aggregate.count()),
                    (existing, incoming) -> new Item(status,
                            existing.totalAmount().add(incoming.totalAmount()),
                            existing.count() + incoming.count()));
        }

        return new SettlementSummaryResult(target, List.copyOf(merged.values()));
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `cd settlement-service && ./gradlew test --tests "com.prompthub.settlement.application.service.SettlementSummaryApplicationServiceTest"`
Expected: PASS (3 케이스).

- [ ] **Step 6: 커밋**

```bash
git add settlement-service/src/main/java/com/prompthub/settlement/application/ settlement-service/src/test/java/com/prompthub/settlement/application/service/SettlementSummaryApplicationServiceTest.java
git commit -m "feat: 정산 요약 조회 유스케이스(period 보정·표시상태 병합) 추가 (#55)"
```

---

## Task 4: 표현 계층 (Response DTO + Controller)

**Files:**
- Create: `src/main/java/com/prompthub/settlement/presentation/dto/response/SettlementSummaryResponse.java`
- Create: `src/main/java/com/prompthub/settlement/presentation/controller/AdminSettlementController.java`
- Test: `src/test/java/com/prompthub/settlement/presentation/dto/response/SettlementSummaryResponseTest.java`

**Interfaces:**
- Consumes: `GetSettlementSummaryUseCase.getSummary(YearMonth)`, `SettlementSummaryResult` (Task 3), `AuthHeaders`, common `ApiResult`.
- Produces: `SettlementSummaryResponse.from(SettlementSummaryResult)`.

- [ ] **Step 1: 실패하는 변환 테스트 작성**

`src/test/java/com/prompthub/settlement/presentation/dto/response/SettlementSummaryResponseTest.java`:

```java
package com.prompthub.settlement.presentation.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.settlement.application.dto.SettlementSummaryResult;
import com.prompthub.settlement.domain.model.enums.SettlementDisplayStatus;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SettlementSummaryResponseTest {

    @Test
    @DisplayName("Result를 응답 DTO로 변환한다 — period는 YYYY-MM 문자열, 금액은 long")
    void from_convertsResult() {
        SettlementSummaryResult result = new SettlementSummaryResult(
                YearMonth.of(2026, 5),
                List.of(new SettlementSummaryResult.Item(
                        SettlementDisplayStatus.PAID, new BigDecimal("19200000.00"), 3L)));

        SettlementSummaryResponse response = SettlementSummaryResponse.from(result);

        assertThat(response.period()).isEqualTo("2026-05");
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).status()).isEqualTo("PAID");
        assertThat(response.items().get(0).totalAmount()).isEqualTo(19200000L);
        assertThat(response.items().get(0).count()).isEqualTo(3L);
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd settlement-service && ./gradlew test --tests "com.prompthub.settlement.presentation.dto.response.SettlementSummaryResponseTest"`
Expected: 컴파일 실패 — `SettlementSummaryResponse` 심볼 없음.

- [ ] **Step 3: 응답 DTO 구현**

`presentation/dto/response/SettlementSummaryResponse.java`:

```java
package com.prompthub.settlement.presentation.dto.response;

import com.prompthub.settlement.application.dto.SettlementSummaryResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "정산 요약 조회 응답")
public record SettlementSummaryResponse(

        @Schema(description = "조회 기준 월 (YYYY-MM)", example = "2026-05")
        String period,

        @Schema(description = "표시 상태별 정산 집계 목록")
        List<Item> items
) {

    public static SettlementSummaryResponse from(SettlementSummaryResult result) {
        List<Item> items = result.items().stream()
                .map(Item::from)
                .toList();
        return new SettlementSummaryResponse(result.period().toString(), items);
    }

    @Schema(description = "표시 상태별 정산 집계 항목")
    public record Item(

            @Schema(description = "표시 상태", example = "PENDING_APPROVAL")
            String status,

            @Schema(description = "해당 상태의 지급 순액(settlementTotalAmount) 합계", example = "16200000")
            long totalAmount,

            @Schema(description = "해당 상태의 정산 건수", example = "2")
            long count
    ) {

        public static Item from(SettlementSummaryResult.Item item) {
            return new Item(item.status().name(), item.totalAmount().longValue(), item.count());
        }
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd settlement-service && ./gradlew test --tests "com.prompthub.settlement.presentation.dto.response.SettlementSummaryResponseTest"`
Expected: PASS.

- [ ] **Step 5: 컨트롤러 구현**

`presentation/controller/AdminSettlementController.java`:

```java
package com.prompthub.settlement.presentation.controller;

import com.prompthub.exception.response.ErrorResponse;
import com.prompthub.presentation.dto.ApiResult;
import com.prompthub.settlement.application.dto.SettlementSummaryResult;
import com.prompthub.settlement.application.usecase.GetSettlementSummaryUseCase;
import com.prompthub.settlement.global.web.AuthHeaders;
import com.prompthub.settlement.presentation.dto.response.SettlementSummaryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.YearMonth;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("${api.init}/admin/settlements")
@RequiredArgsConstructor
@Tag(name = "Admin Settlement", description = "어드민 정산 조회 API")
public class AdminSettlementController {

    private final GetSettlementSummaryUseCase getSettlementSummaryUseCase;

    @GetMapping("/summary")
    @Operation(summary = "정산 요약 조회",
            description = "기준 월의 정산을 표시 상태별로 집계해 금액 합계와 건수를 반환합니다. "
                    + "period 생략 시 직전 월을 집계합니다. ADMIN 권한이 필요합니다.",
            parameters = @Parameter(name = AuthHeaders.USER_ROLE, in = ParameterIn.HEADER, required = true,
                    description = "게이트웨이가 주입하는 사용자 역할 (ADMIN 필요)"))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(schema = @Schema(implementation = SettlementSummaryResponse.class))),
            @ApiResponse(responseCode = "400", description = "period 형식 오류(YYYY-MM 아님)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증 정보 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "ADMIN 권한 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ApiResult<SettlementSummaryResponse> getSummary(
            @Parameter(description = "조회 기준 월 (YYYY-MM). 생략 시 직전 월", example = "2026-05")
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth period
    ) {
        SettlementSummaryResult result = getSettlementSummaryUseCase.getSummary(period);
        return ApiResult.success(SettlementSummaryResponse.from(result));
    }
}
```

- [ ] **Step 6: 전체 테스트 + 부팅 검증**

Run: `cd settlement-service && ./gradlew test`
Expected: PASS — 신규 컨트롤러 빈이 등록되고 컨텍스트가 정상 부팅된다.

- [ ] **Step 7: 커밋**

```bash
git add settlement-service/src/main/java/com/prompthub/settlement/presentation/ settlement-service/src/test/java/com/prompthub/settlement/presentation/
git commit -m "feat: 어드민 정산 요약 조회 API(GET /admin/settlements/summary) 추가 (#55)"
```

---

## Self-Review

- **Spec coverage:** 표시상태 파생(3절)→Task1, 집계 방식·기간 필터(4절)→Task2, period 보정·병합(4·5절)→Task3, 응답·Swagger·예외코드(2·6절)→Task4. ADMIN 권한(WebConfig)은 기존 인프라로 충족(Global Constraints에 기록). 모든 스펙 항목에 대응 태스크 존재.
- **Placeholder scan:** 모든 코드 스텝에 실제 구현 포함. TODO/TBD 없음.
- **Type consistency:** `aggregateByPeriod(LocalDate, LocalDate)`, `SettlementStatusAggregate(SettlementStatus, PayoutStatus, BigDecimal, long)`, `SettlementDisplayStatus.of(SettlementStatus, PayoutStatus)`, `SettlementSummaryResult(YearMonth, List<Item>)` / `Item(SettlementDisplayStatus, BigDecimal, long)`, `SettlementSummaryResponse.from(...)` — 태스크 간 시그니처 일치 확인.
