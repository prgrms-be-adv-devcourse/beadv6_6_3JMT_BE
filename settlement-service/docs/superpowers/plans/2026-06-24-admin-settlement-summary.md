# 어드민 정산 요약 카드 조회 API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `GET /admin/settlements/summary` — 정산 관리 화면 상단의 고정 4카드(정산 대기·승인 완료·지급 보류·지급 완료)에 상태별 지급액 합계·건수를 채우는 어드민 API의 leaf 구현.

**Architecture:** 공유 골격(frozen)이 enum·인바운드 포트·컨트롤러·응답 DTO를 이미 깔아 두었다. 이 플랜은 **요약 세션이 소유하는 leaf**만 구현한다 — 별도 집계 쿼리 포트(CQRS-lite)와 그 어댑터, 그리고 스텁 상태인 애플리케이션 서비스의 집계·카드 병합 로직. DB는 `GROUP BY (settlement_status, payout_status)`로 (상태쌍, 합계, 건수)만 뽑고, 표시 상태 파생(`SettlementDisplayStatus.from`)과 카드 버킷 병합은 자바에서 한다.

**Tech Stack:** Java 17 + Spring Boot 3 (Spring MVC, Spring Data JPA / Hibernate 6), JUnit 5 + Mockito + AssertJ, Gradle.

## Global Constraints

- 생성물은 모두 `settlement-service/` 모듈 하위에 둔다. 빌드/테스트는 `settlement-service/`에서 `./gradlew test`.
- **골격 frozen 파일은 건드리지 않는다:** `SettlementDisplayStatus`(enum + `from`), `Settlement.displayStatus()`, `GetSettlementSummaryUseCase`(무인자 `getSummary()`), `SettlementQueryController`.
- 기존 `SettlementRepository`(배치/커맨드용)도 건드리지 않는다. 조회는 **별도 쿼리 포트**로 분리한다(`clean-architecture.md` §4-1).
- 도메인은 `ErrorCode`/`HttpStatus`를 import하지 않는다. (이 플랜엔 신규 예외 없음.)
- 금액은 `BigDecimal`을 유지한다(`Card.totalAmount`가 BigDecimal). long 변환 없음.

## 골격 계약 (이미 존재 — 재확인용, 수정 금지)

```java
// domain/model/enums/SettlementDisplayStatus.java (frozen)
enum SettlementDisplayStatus { WAITING, APPROVAL_ON_HOLD, APPROVED, PAYOUT_REQUESTED, PAYOUT_ON_HOLD, PAID, CANCELLED; }
static SettlementDisplayStatus from(SettlementStatus, PayoutStatus);  // 7종 파생

// application/usecase/GetSettlementSummaryUseCase.java (frozen)
SettlementSummaryResult getSummary();   // 무인자 — period 필터 없음, 전체 기간 집계

// application/dto/SettlementSummaryResult.java (seed — 구조 그대로 사용)
record SettlementSummaryResult(List<Card> cards) {
    record Card(SettlementDisplayStatus status, BigDecimal totalAmount, long count) {}
}

// presentation/dto/response/SettlementSummaryResponse.java (seed — from() 완성됨, 수정 불필요)
// SettlementQueryController.getSummary() → SettlementSummaryResponse.from(result) 호출 (frozen)
```

## 카드 버킷 매핑 (이 플랜의 핵심 도메인 규칙)

화면 4카드는 displayStatus 7종을 4버킷으로 묶은 것이다(화면 금액으로 검증 완료).

| 카드(대표 displayStatus) | 묶이는 displayStatus | 화면 검증 |
| --- | --- | --- |
| `WAITING`(정산 대기) | `WAITING` + `APPROVAL_ON_HOLD` | 4건 / 1,135,500 |
| `APPROVED`(승인 완료) | `APPROVED` + `PAYOUT_REQUESTED` | 4건 / 1,448,500 |
| `PAYOUT_ON_HOLD`(지급 보류) | `PAYOUT_ON_HOLD` | 2건 / 757,000 |
| `PAID`(지급 완료) | `PAID` | 4건 / 1,224,000 |

- `CANCELLED`(취소)는 카드에 포함하지 않는다.
- 카드는 **항상 4개** 고정 순서(`WAITING`, `APPROVED`, `PAYOUT_ON_HOLD`, `PAID`)로 반환하며, 0건이어도 `totalAmount=0`, `count=0`으로 노출한다.

---

## File Structure

- Create `domain/repository/SettlementStatusAggregate.java` — 집계 행 record.
- Create `domain/repository/SettlementSummaryQueryRepository.java` — 집계 아웃바운드 포트(조회 전용).
- Create `infrastructure/persistence/SettlementSummaryJpaRepository.java` — Spring Data, JPQL 집계 쿼리.
- Create `infrastructure/persistence/SettlementSummaryQueryRepositoryAdapter.java` — 포트 구현(위임).
- Modify `application/service/SettlementSummaryApplicationService.java` — 스텁 → 집계·카드 병합.
- Test `src/test/.../application/service/SettlementSummaryApplicationServiceTest.java` — 병합·카드 구성 단위 테스트.

**범위 밖:** 목록 조회(#56) leaf, 판매자명 연동(설계 §7 TODO).

**테스트 전략:** 핵심 로직(카드 버킷 병합·고정 4카드·CANCELLED 제외·0건)은 서비스 단위 테스트로 커버한다. 집계 쿼리(JpaRepository `@Query`)는 통합 테스트 인프라(임베디드 DB)가 없어 `./gradlew test`(스프링 컨텍스트 부팅) 시 JPQL 파싱·매핑으로 검증한다.

---

## Task 1: 집계 쿼리 포트 + 어댑터 (영속성)

**Files:**
- Create: `src/main/java/com/prompthub/settlement/domain/repository/SettlementStatusAggregate.java`
- Create: `src/main/java/com/prompthub/settlement/domain/repository/SettlementSummaryQueryRepository.java`
- Create: `src/main/java/com/prompthub/settlement/infrastructure/persistence/SettlementSummaryJpaRepository.java`
- Create: `src/main/java/com/prompthub/settlement/infrastructure/persistence/SettlementSummaryQueryRepositoryAdapter.java`

**Interfaces:**
- Produces: `record SettlementStatusAggregate(SettlementStatus settlementStatus, PayoutStatus payoutStatus, BigDecimal sumSettlementTotal, long count)`; `List<SettlementStatusAggregate> SettlementSummaryQueryRepository.aggregateByStatus()`.

- [ ] **Step 1: 집계 행 record 생성**

`domain/repository/SettlementStatusAggregate.java`:

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

- [ ] **Step 2: 조회 전용 포트 생성**

`domain/repository/SettlementSummaryQueryRepository.java`:

```java
package com.prompthub.settlement.domain.repository;

import java.util.List;

public interface SettlementSummaryQueryRepository {

    List<SettlementStatusAggregate> aggregateByStatus();
}
```

- [ ] **Step 3: Spring Data 집계 쿼리 생성**

`infrastructure/persistence/SettlementSummaryJpaRepository.java`:

```java
package com.prompthub.settlement.infrastructure.persistence;

import com.prompthub.settlement.domain.model.Settlement;
import com.prompthub.settlement.domain.repository.SettlementStatusAggregate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SettlementSummaryJpaRepository extends JpaRepository<Settlement, UUID> {

    @Query("""
            select new com.prompthub.settlement.domain.repository.SettlementStatusAggregate(
                s.settlementStatus, s.payoutStatus, sum(s.settlementTotalAmount), count(s))
            from Settlement s
            group by s.settlementStatus, s.payoutStatus
            """)
    List<SettlementStatusAggregate> aggregateByStatus();
}
```

> 주의: `count(s)`는 `Long`을 반환한다. Hibernate constructor expression이 record의 `long count`로 매핑한다. 부팅 시 매핑 오류가 나면 record의 `count`를 `Long`으로 바꾼다(Task 2의 `agg.count()`는 그대로 동작).

- [ ] **Step 4: 포트 어댑터 생성**

`infrastructure/persistence/SettlementSummaryQueryRepositoryAdapter.java`:

```java
package com.prompthub.settlement.infrastructure.persistence;

import com.prompthub.settlement.domain.repository.SettlementStatusAggregate;
import com.prompthub.settlement.domain.repository.SettlementSummaryQueryRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SettlementSummaryQueryRepositoryAdapter implements SettlementSummaryQueryRepository {

    private final SettlementSummaryJpaRepository jpaRepository;

    @Override
    public List<SettlementStatusAggregate> aggregateByStatus() {
        return jpaRepository.aggregateByStatus();
    }
}
```

- [ ] **Step 5: 컴파일·부팅 검증**

Run: `cd settlement-service && ./gradlew test --tests "com.prompthub.settlement.SettlementApplicationTests"`
Expected: PASS — 스프링 컨텍스트가 뜨고 JPQL 쿼리가 파싱된다(문법/매핑 오류 없음).

- [ ] **Step 6: 커밋**

```bash
git add settlement-service/src/main/java/com/prompthub/settlement/domain/repository/SettlementStatusAggregate.java settlement-service/src/main/java/com/prompthub/settlement/domain/repository/SettlementSummaryQueryRepository.java settlement-service/src/main/java/com/prompthub/settlement/infrastructure/persistence/SettlementSummaryJpaRepository.java settlement-service/src/main/java/com/prompthub/settlement/infrastructure/persistence/SettlementSummaryQueryRepositoryAdapter.java
git commit -m "feat: 정산 상태별 집계 조회 포트·어댑터 추가 (#55)"
```

---

## Task 2: 요약 서비스 — 집계 + 카드 버킷 병합

**Files:**
- Modify: `src/main/java/com/prompthub/settlement/application/service/SettlementSummaryApplicationService.java`
- Test: `src/test/java/com/prompthub/settlement/application/service/SettlementSummaryApplicationServiceTest.java`

**Interfaces:**
- Consumes: `SettlementSummaryQueryRepository.aggregateByStatus()`, `SettlementStatusAggregate` (Task 1), `SettlementDisplayStatus.from(...)` (frozen).
- Produces: `SettlementSummaryResult getSummary()` 구현 — 고정 4카드(`WAITING`, `APPROVED`, `PAYOUT_ON_HOLD`, `PAID`).

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/prompthub/settlement/application/service/SettlementSummaryApplicationServiceTest.java`:

```java
package com.prompthub.settlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.BDDMockito.given;

import com.prompthub.settlement.application.dto.SettlementSummaryResult;
import com.prompthub.settlement.domain.model.enums.PayoutStatus;
import com.prompthub.settlement.domain.model.enums.SettlementDisplayStatus;
import com.prompthub.settlement.domain.model.enums.SettlementStatus;
import com.prompthub.settlement.domain.repository.SettlementStatusAggregate;
import com.prompthub.settlement.domain.repository.SettlementSummaryQueryRepository;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SettlementSummaryApplicationServiceTest {

    @Mock
    private SettlementSummaryQueryRepository settlementSummaryQueryRepository;

    @InjectMocks
    private SettlementSummaryApplicationService service;

    @Test
    @DisplayName("상태쌍 집계를 카드 버킷으로 접어 고정 4카드를 순서대로 반환한다")
    void getSummary_foldsAggregatesIntoFourCards() {
        given(settlementSummaryQueryRepository.aggregateByStatus()).willReturn(List.of(
                new SettlementStatusAggregate(SettlementStatus.PENDING_APPROVAL, PayoutStatus.NOT_READY,
                        new BigDecimal("719000"), 2L),
                new SettlementStatusAggregate(SettlementStatus.SETTLEMENT_ON_HOLD, PayoutStatus.NOT_READY,
                        new BigDecimal("416500"), 2L),
                new SettlementStatusAggregate(SettlementStatus.APPROVED, PayoutStatus.NOT_READY,
                        new BigDecimal("1448500"), 4L),
                new SettlementStatusAggregate(SettlementStatus.APPROVED, PayoutStatus.PAYOUT_ON_HOLD,
                        new BigDecimal("757000"), 2L),
                new SettlementStatusAggregate(SettlementStatus.APPROVED, PayoutStatus.PAID,
                        new BigDecimal("1224000"), 4L)));

        SettlementSummaryResult result = service.getSummary();

        assertThat(result.cards())
                .extracting(c -> c.status(), c -> c.totalAmount().stripTrailingZeros(), c -> c.count())
                .containsExactly(
                        tuple(SettlementDisplayStatus.WAITING, new BigDecimal("1135500"), 4L),
                        tuple(SettlementDisplayStatus.APPROVED, new BigDecimal("1448500"), 4L),
                        tuple(SettlementDisplayStatus.PAYOUT_ON_HOLD, new BigDecimal("757000"), 2L),
                        tuple(SettlementDisplayStatus.PAID, new BigDecimal("1224000"), 4L));
    }

    @Test
    @DisplayName("취소(CANCELLED)는 어느 카드에도 합산되지 않는다")
    void getSummary_excludesCancelled() {
        given(settlementSummaryQueryRepository.aggregateByStatus()).willReturn(List.of(
                new SettlementStatusAggregate(SettlementStatus.CANCELLED, PayoutStatus.NOT_READY,
                        new BigDecimal("178000"), 2L)));

        SettlementSummaryResult result = service.getSummary();

        assertThat(result.cards()).extracting(c -> c.status())
                .containsExactly(SettlementDisplayStatus.WAITING, SettlementDisplayStatus.APPROVED,
                        SettlementDisplayStatus.PAYOUT_ON_HOLD, SettlementDisplayStatus.PAID);
        assertThat(result.cards()).allMatch(c -> c.count() == 0L);
        assertThat(result.cards()).allMatch(c -> c.totalAmount().signum() == 0);
    }

    @Test
    @DisplayName("집계가 비어도 0건 4카드를 반환한다")
    void getSummary_emptyAggregates_returnsZeroCards() {
        given(settlementSummaryQueryRepository.aggregateByStatus()).willReturn(List.of());

        SettlementSummaryResult result = service.getSummary();

        assertThat(result.cards()).hasSize(4);
        assertThat(result.cards()).allMatch(c -> c.count() == 0L && c.totalAmount().signum() == 0);
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd settlement-service && ./gradlew test --tests "com.prompthub.settlement.application.service.SettlementSummaryApplicationServiceTest"`
Expected: 컴파일/실행 실패 — 현재 서비스는 빈 결과 스텁이라 카드가 비어 단언 실패.

- [ ] **Step 3: 서비스 구현 (스텁 교체)**

`application/service/SettlementSummaryApplicationService.java` 전체를 아래로 교체한다:

```java
package com.prompthub.settlement.application.service;

import com.prompthub.settlement.application.dto.SettlementSummaryResult;
import com.prompthub.settlement.application.dto.SettlementSummaryResult.Card;
import com.prompthub.settlement.application.usecase.GetSettlementSummaryUseCase;
import com.prompthub.settlement.domain.model.enums.SettlementDisplayStatus;
import com.prompthub.settlement.domain.repository.SettlementStatusAggregate;
import com.prompthub.settlement.domain.repository.SettlementSummaryQueryRepository;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 정산 요약 카드 조회 구현체.
 *
 * <p>상태쌍 집계를 표시 상태(SettlementDisplayStatus.from)로 파생한 뒤, 화면 고정 4카드 버킷으로
 * 접어 합산한다. 카드는 항상 WAITING/APPROVED/PAYOUT_ON_HOLD/PAID 4종을 순서대로 반환한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SettlementSummaryApplicationService implements GetSettlementSummaryUseCase {

    private static final List<SettlementDisplayStatus> CARD_ORDER = List.of(
            SettlementDisplayStatus.WAITING,
            SettlementDisplayStatus.APPROVED,
            SettlementDisplayStatus.PAYOUT_ON_HOLD,
            SettlementDisplayStatus.PAID);

    private final SettlementSummaryQueryRepository settlementSummaryQueryRepository;

    @Override
    public SettlementSummaryResult getSummary() {
        Map<SettlementDisplayStatus, BigDecimal> amountByCard = new EnumMap<>(SettlementDisplayStatus.class);
        Map<SettlementDisplayStatus, Long> countByCard = new EnumMap<>(SettlementDisplayStatus.class);
        for (SettlementDisplayStatus card : CARD_ORDER) {
            amountByCard.put(card, BigDecimal.ZERO);
            countByCard.put(card, 0L);
        }

        for (SettlementStatusAggregate aggregate : settlementSummaryQueryRepository.aggregateByStatus()) {
            SettlementDisplayStatus card = toCard(
                    SettlementDisplayStatus.from(aggregate.settlementStatus(), aggregate.payoutStatus()));
            if (card == null) {
                continue;
            }
            amountByCard.merge(card, aggregate.sumSettlementTotal(), BigDecimal::add);
            countByCard.merge(card, aggregate.count(), Long::sum);
        }

        List<Card> cards = CARD_ORDER.stream()
                .map(card -> new Card(card, amountByCard.get(card), countByCard.get(card)))
                .toList();
        return new SettlementSummaryResult(cards);
    }

    private static SettlementDisplayStatus toCard(SettlementDisplayStatus displayStatus) {
        return switch (displayStatus) {
            case WAITING, APPROVAL_ON_HOLD -> SettlementDisplayStatus.WAITING;
            case APPROVED, PAYOUT_REQUESTED -> SettlementDisplayStatus.APPROVED;
            case PAYOUT_ON_HOLD -> SettlementDisplayStatus.PAYOUT_ON_HOLD;
            case PAID -> SettlementDisplayStatus.PAID;
            case CANCELLED -> null;
        };
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd settlement-service && ./gradlew test --tests "com.prompthub.settlement.application.service.SettlementSummaryApplicationServiceTest"`
Expected: PASS (3 케이스).

- [ ] **Step 5: 전체 테스트 + 부팅 검증**

Run: `cd settlement-service && ./gradlew test`
Expected: PASS — 신규 빈(`SettlementSummaryQueryRepositoryAdapter`)이 주입되고 컨텍스트가 정상 부팅된다.

- [ ] **Step 6: 커밋**

```bash
git add settlement-service/src/main/java/com/prompthub/settlement/application/service/SettlementSummaryApplicationService.java settlement-service/src/test/java/com/prompthub/settlement/application/service/SettlementSummaryApplicationServiceTest.java
git commit -m "feat: 정산 요약 카드 집계·병합 로직 구현 (#55)"
```

---

## Self-Review

- **Spec coverage (골격 설계 + 화면):** 집계 쿼리(§6 권장안)→Task1, 카드 버킷 병합·고정 4카드·CANCELLED 제외→Task2. enum·포트·컨트롤러·응답 DTO는 골격 frozen/seed로 이미 충족(수정 금지 명시). `getSummary()` 무인자(전체 기간) 준수.
- **Placeholder scan:** 모든 코드 스텝에 실제 구현 포함. 골격 스텁의 `// TODO(요약 세션)`은 Step 3 전체 교체로 제거됨.
- **Type consistency:** `aggregateByStatus(): List<SettlementStatusAggregate>`, `SettlementStatusAggregate(SettlementStatus, PayoutStatus, BigDecimal, long)`, `SettlementDisplayStatus.from(SettlementStatus, PayoutStatus)`, `SettlementSummaryResult(List<Card>)` / `Card(SettlementDisplayStatus, BigDecimal, long)` — 골격 시그니처와 일치. 서비스는 `Card`/`from`을 바꾸지 않아 frozen 컨트롤러와 호환.
