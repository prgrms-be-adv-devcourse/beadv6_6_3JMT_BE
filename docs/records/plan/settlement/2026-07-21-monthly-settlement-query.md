# 월별 정산 동적 집계 조회 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 주간 `seller_settlement` 행을 조회 시점에 월별로 집계하고, 판매자와 어드민에 월별 목록·주간 상세 API를 제공하면서 기존 주간 지급 흐름을 유지한다.

**Architecture:** user-service와 admin-service가 같은 `seller_settlement` 테이블을 각자 조회하되, 목요일 기준 월 분류와 취소 제외 합계 규칙을 동일하게 구현한다. 목록은 월별 그룹 페이지, 현재 페이지의 상태 건수, 어드민 판매자명 순으로 조립하고 상세는 한 월의 집계와 주간 행을 결합한다. 월별 엔티티나 저장 집계는 만들지 않는다.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring Data JPA, Spring JDBC, H2 2.4.240, PostgreSQL, JUnit 5, Mockito, AssertJ, MockMvc, Gradle

## Global Constraints

- 작업 기준은 `#462 (이슈)`와 `feat/#462-monthly-settlement-query (브랜치)`다.
- 설계 원본은 `settlement-service/docs/superpowers/specs/2026-07-21-monthly-settlement-query-design.md`다.
- `seller_settlement`의 주간 행이 운영 데이터의 단일 진실 공급원이다.
- 월 분류는 `YearMonth.from(periodStart.plusDays(3))`, SQL에서는
  `EXTRACT(YEAR FROM (period_start + 3))`와 `EXTRACT(MONTH FROM (period_start + 3))`를 사용한다.
- `CANCELLED`는 상세·`weeklySettlementCount`·`statusCounts`에는 포함하고, 판매 건수·금액·`aggregatedSettlementCount`에서는 제외한다.
- 목록의 `status`는 해당 상태가 하나 이상 있는 월별 그룹을 선택할 뿐, 합계와 상태 건수는 그 달 전체를 계산한다.
- 판매자 응답은 `sellerId`, `sellerName`을 노출하지 않고 모든 조회에 `X-User-Id`를 강제한다.
- 어드민 판매자명은 `SellerNameQueryPort`를 거쳐 `UserRepository.findAllByIds`로 한 번에 조회하며, 누락 시 `sellerName: null`과 warn 로그를 남긴다.
- 판매자 목록은 `settlementMonth DESC`, 어드민 목록은 `settlementMonth DESC, sellerId ASC`, 상세는 `periodStart ASC`로 고정한다.
- 판매자 page size 기본값은 10, 어드민은 20이고 공통 허용 범위는 1~100이다.
- 판매자 누적 summary와 기존 주간 `PATCH` 상태 전이·취소 로직은 변경하지 않는다.
- 어드민 API는 기존 `${api.init}` 값인 `/api/v2`를 유지한다. 공통 Config와 다른 어드민 API를 수정하지 않는다.
- H2와 PostgreSQL 공통 SQL만 사용하고 Testcontainers, Gradle 의존성, 마이그레이션, 테스트 리소스 SQL을 추가·수정하지 않는다.
- 쓰기 범위는 `user.sellersettlement`, `admin.settlement`, `settlement-service/docs` 안으로 제한한다.

---

## File Map

### Create

- `user-service/src/main/java/com/prompthub/user/sellersettlement/domain/repository/SellerSettlementQueryRepository.java` — 판매자 월별 집계·상태 건수·주간 상세 조회 포트와 불변 projection을 정의한다.
- `user-service/src/main/java/com/prompthub/user/sellersettlement/infrastructure/persistence/SellerSettlementQueryRepositoryAdapter.java` — H2/PostgreSQL 공통 SQL로 판매자 월별 그룹을 조회한다.
- `user-service/src/main/java/com/prompthub/user/sellersettlement/presentation/dto/response/SellerSettlementMonthlyResponse.java` — 판매자 월별 상태 건수, 주간 행, 액션 공통 응답을 정의한다.
- `user-service/src/main/java/com/prompthub/user/sellersettlement/presentation/dto/response/SellerSettlementDetailResponse.java` — 판매자 한 달의 집계와 주간 정산을 반환한다.
- `user-service/src/test/java/com/prompthub/user/sellersettlement/infrastructure/persistence/SellerSettlementQueryRepositoryAdapterTest.java` — 실제 H2 월 분류·집계·필터·페이지 쿼리를 검증한다.
- `user-service/src/test/java/com/prompthub/user/sellersettlement/presentation/controller/SellerSettlementControllerTest.java` — 판매자 월별 목록·상세와 요청 검증 계약을 고정한다.
- `admin-service/src/main/java/com/prompthub/admin/settlement/domain/repository/SettlementMonthlyQueryRepository.java` — 어드민 판매자-월 집계 조회 포트와 projection을 정의한다.
- `admin-service/src/main/java/com/prompthub/admin/settlement/infrastructure/persistence/SettlementMonthlyQueryRepositoryAdapter.java` — 어드민 월별 그룹과 주간 상세를 직접 조회한다.
- `admin-service/src/main/java/com/prompthub/admin/settlement/application/port/SellerNameQueryPort.java` — 정산 애플리케이션이 판매자명을 벌크 조회하는 포트다.
- `admin-service/src/main/java/com/prompthub/admin/settlement/infrastructure/user/SellerNameQueryAdapter.java` — 기존 admin user 저장소를 포트 뒤에 연결한다.
- `admin-service/src/main/java/com/prompthub/admin/settlement/presentation/dto/response/SettlementMonthlyResponse.java` — 어드민 월별 상태 건수, 주간 행, 액션 공통 응답을 정의한다.
- `admin-service/src/main/java/com/prompthub/admin/settlement/presentation/dto/response/SettlementDetailResponse.java` — 어드민 판매자·월 상세를 반환한다.
- `admin-service/src/test/java/com/prompthub/admin/settlement/infrastructure/user/SellerNameQueryAdapterTest.java` — 판매자명 벌크 조회와 빈 입력을 검증한다.

### Modify

- `user-service/src/main/java/com/prompthub/user/sellersettlement/application/dto/SellerSettlementListQuery.java`
- `user-service/src/main/java/com/prompthub/user/sellersettlement/application/usecase/SellerSettlementUseCase.java`
- `user-service/src/main/java/com/prompthub/user/sellersettlement/application/service/SellerSettlementApplicationService.java`
- `user-service/src/main/java/com/prompthub/user/sellersettlement/domain/repository/SellerSettlementRepository.java`
- `user-service/src/main/java/com/prompthub/user/sellersettlement/infrastructure/persistence/SellerSettlementJpaRepository.java`
- `user-service/src/main/java/com/prompthub/user/sellersettlement/infrastructure/persistence/SellerSettlementRepositoryAdapter.java`
- `user-service/src/main/java/com/prompthub/user/sellersettlement/presentation/dto/response/SellerSettlementListResponse.java`
- `user-service/src/main/java/com/prompthub/user/sellersettlement/presentation/controller/SellerSettlementController.java`
- `user-service/src/test/java/com/prompthub/user/sellersettlement/application/service/SellerSettlementQueryServiceTest.java`
- `user-service/src/test/java/com/prompthub/user/sellersettlement/infrastructure/persistence/SellerSettlementRepositoryAdapterTest.java`
- `user-service/src/test/java/com/prompthub/user/sellersettlement/presentation/dto/response/SellerSettlementListResponseTest.java`
- `admin-service/src/main/java/com/prompthub/admin/settlement/application/dto/SettlementListQuery.java`
- `admin-service/src/main/java/com/prompthub/admin/settlement/application/usecase/SettlementUseCase.java`
- `admin-service/src/main/java/com/prompthub/admin/settlement/application/service/SettlementApplicationService.java`
- `admin-service/src/main/java/com/prompthub/admin/settlement/domain/repository/SettlementQueryRepository.java`
- `admin-service/src/main/java/com/prompthub/admin/settlement/infrastructure/persistence/SettlementQueryJpaRepository.java`
- `admin-service/src/main/java/com/prompthub/admin/settlement/infrastructure/persistence/SettlementQueryRepositoryAdapter.java`
- `admin-service/src/main/java/com/prompthub/admin/settlement/presentation/dto/response/SettlementListResponse.java`
- `admin-service/src/main/java/com/prompthub/admin/settlement/presentation/controller/SettlementController.java`
- `admin-service/src/test/java/com/prompthub/admin/settlement/application/service/SettlementApplicationServiceTest.java`
- `admin-service/src/test/java/com/prompthub/admin/settlement/infrastructure/persistence/SettlementQueryRepositoryAdapterTest.java`
- `admin-service/src/test/java/com/prompthub/admin/settlement/presentation/controller/SettlementControllerTest.java`

### Do Not Modify

- `user-service/src/main/resources/db/migration/**`
- `user-service/src/test/resources/**`
- `admin-service/src/test/resources/**`
- `config/src/main/resources/configs/admin-service.yml`
- `user-service/build.gradle`, `admin-service/build.gradle`, 루트 `build.gradle`
- 기존 주간 상태 전이 Controller 경로와 도메인 전이 메서드

---

### Task 1: 판매자 월별 집계 영속성 포트와 H2 쿼리

**Files:**
- Create: `user-service/src/main/java/com/prompthub/user/sellersettlement/domain/repository/SellerSettlementQueryRepository.java`
- Create: `user-service/src/main/java/com/prompthub/user/sellersettlement/infrastructure/persistence/SellerSettlementQueryRepositoryAdapter.java`
- Create: `user-service/src/test/java/com/prompthub/user/sellersettlement/infrastructure/persistence/SellerSettlementQueryRepositoryAdapterTest.java`
- Modify: `user-service/src/main/java/com/prompthub/user/sellersettlement/infrastructure/persistence/SellerSettlementJpaRepository.java:1-39`

**Interfaces:**
- Consumes: `seller_settlement` 컬럼과 `SellerSettlement`, `SettlementDisplayStatus`
- Produces: `findMonthlyPage`, `findMonthlyAggregate`, `findStatusCounts`, `findWeeklySettlements`

- [ ] **Step 1: 목요일 월 분류와 취소 제외 합계를 고정하는 실패 테스트 작성**

새 테스트 클래스에 다음 핵심 테스트와 helper를 작성한다. 테스트 데이터는 Java에서 엔티티를 저장해 테스트 리소스 변경을 피한다.

```java
@DataJpaTest
@Import({SellerSettlementQueryRepositoryAdapter.class, JpaConfig.class})
@ActiveProfiles("test")
class SellerSettlementQueryRepositoryAdapterTest {

    @Autowired
    private SellerSettlementQueryRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void 월경계_주간을_목요일_기준으로_묶고_취소는_합계에서_제외한다() {
        UUID sellerId = UUID.randomUUID();
        persist(sellerId, LocalDate.of(2026, 6, 29), 10, "1000000", "150000", "0", "850000",
                SettlementDisplayStatus.APPROVED);
        persist(sellerId, LocalDate.of(2026, 7, 6), 12, "1200000", "180000", "100000", "920000",
                SettlementDisplayStatus.PAID);
        persist(sellerId, LocalDate.of(2026, 7, 13), 5, "500000", "75000", "0", "425000",
                SettlementDisplayStatus.CANCELLED);
        persist(UUID.randomUUID(), LocalDate.of(2026, 7, 6), 99,
                "9900000", "990000", "0", "8910000", SettlementDisplayStatus.PAID);
        entityManager.flush();

        SellerSettlementQueryRepository.MonthlyPage page =
                repository.findMonthlyPage(sellerId, null, YearMonth.of(2026, 7), 0, 10);
        SellerSettlementQueryRepository.MonthlyAggregate aggregate = page.content().getFirst();

        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(aggregate.key().settlementMonth()).isEqualTo(YearMonth.of(2026, 7));
        assertThat(aggregate.weeklySettlementCount()).isEqualTo(3);
        assertThat(aggregate.aggregatedSettlementCount()).isEqualTo(2);
        assertThat(aggregate.salesCount()).isEqualTo(22);
        assertThat(aggregate.grossAmount()).isEqualByComparingTo("2200000");
        assertThat(aggregate.feeAmount()).isEqualByComparingTo("330000");
        assertThat(aggregate.refundAmount()).isEqualByComparingTo("100000");
        assertThat(aggregate.payoutAmount()).isEqualByComparingTo("1770000");

        assertThat(repository.findStatusCounts(List.of(aggregate.key())))
                .extracting(SellerSettlementQueryRepository.MonthlyStatusCount::status,
                        SellerSettlementQueryRepository.MonthlyStatusCount::count)
                .containsExactly(
                        tuple(SettlementDisplayStatus.APPROVED, 1L),
                        tuple(SettlementDisplayStatus.PAID, 1L),
                        tuple(SettlementDisplayStatus.CANCELLED, 1L));
        assertThat(repository.findWeeklySettlements(sellerId, YearMonth.of(2026, 7)))
                .extracting(SellerSettlement::getStatus)
                .containsExactly(SettlementDisplayStatus.APPROVED,
                        SettlementDisplayStatus.PAID, SettlementDisplayStatus.CANCELLED);
    }

    @Test
    void 상태필터는_그룹만_선택하고_합계는_월전체를_유지한다() {
        UUID sellerId = UUID.randomUUID();
        persist(sellerId, LocalDate.of(2026, 6, 29), 10, "1000000", "150000", "0", "850000",
                SettlementDisplayStatus.APPROVED);
        persist(sellerId, LocalDate.of(2026, 7, 6), 12, "1200000", "180000", "100000", "920000",
                SettlementDisplayStatus.PAID);
        entityManager.flush();

        var page = repository.findMonthlyPage(
                sellerId, SettlementDisplayStatus.APPROVED, null, 0, 10);

        assertThat(page.content()).singleElement()
                .satisfies(it -> assertThat(it.grossAmount()).isEqualByComparingTo("2200000"));
    }

    @Test
    void 칠월말_주간은_칠월이고_팔월첫주부터_팔월이다() {
        UUID sellerId = UUID.randomUUID();
        persist(sellerId, LocalDate.of(2026, 7, 27), 1, "100", "15", "0", "85",
                SettlementDisplayStatus.WAITING);
        persist(sellerId, LocalDate.of(2026, 8, 3), 1, "200", "30", "0", "170",
                SettlementDisplayStatus.WAITING);
        entityManager.flush();

        var page = repository.findMonthlyPage(sellerId, null, null, 0, 10);

        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.content()).extracting(it -> it.key().settlementMonth())
                .containsExactly(YearMonth.of(2026, 8), YearMonth.of(2026, 7));
        assertThat(repository.findWeeklySettlements(sellerId, YearMonth.of(2026, 7)))
                .extracting(SellerSettlement::getPeriodStart)
                .containsExactly(LocalDate.of(2026, 7, 27));
    }

    private void persist(UUID sellerId, LocalDate periodStart, int salesCount,
            String gross, String fee, String refund, String payout, SettlementDisplayStatus status) {
        SellerSettlement settlement = SellerSettlement.seed(
                UUID.randomUUID(), sellerId, periodStart, periodStart.plusDays(6), salesCount,
                new BigDecimal(gross), new BigDecimal(payout), new BigDecimal(fee),
                new BigDecimal(refund), periodStart.plusDays(7).atStartOfDay());
        switch (status) {
            case WAITING -> { }
            case APPROVAL_ON_HOLD -> settlement.hold();
            case APPROVED -> settlement.approve();
            case PAYOUT_REQUESTED -> {
                settlement.approve();
                settlement.requestPayout();
            }
            case PAYOUT_ON_HOLD -> {
                settlement.approve();
                settlement.requestPayout();
                settlement.payoutHold();
            }
            case PAID -> {
                settlement.approve();
                settlement.requestPayout();
                settlement.payout();
            }
            case CANCELLED -> settlement.cancel();
        }
        entityManager.persist(settlement);
    }
}
```

- [ ] **Step 2: 새 테스트가 조회 포트 부재로 실패하는지 확인**

Run:

```bash
./gradlew :user-service:test --tests '*SellerSettlementQueryRepositoryAdapterTest'
```

Expected: FAIL at `compileTestJava`; `SellerSettlementQueryRepository`와 어댑터가 존재하지 않는다.

- [ ] **Step 3: 판매자 월별 조회 포트와 projection 정의**

`SellerSettlementQueryRepository.java`를 다음 내용으로 만든다.

```java
package com.prompthub.user.sellersettlement.domain.repository;

import com.prompthub.user.sellersettlement.domain.model.SellerSettlement;
import com.prompthub.user.sellersettlement.domain.model.enums.SettlementDisplayStatus;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SellerSettlementQueryRepository {

    MonthlyPage findMonthlyPage(UUID sellerId, SettlementDisplayStatus status,
            YearMonth settlementMonth, int page, int size);

    Optional<MonthlyAggregate> findMonthlyAggregate(UUID sellerId, YearMonth settlementMonth);

    List<MonthlyStatusCount> findStatusCounts(List<MonthlyKey> keys);

    List<SellerSettlement> findWeeklySettlements(UUID sellerId, YearMonth settlementMonth);

    record MonthlyKey(UUID sellerId, YearMonth settlementMonth) {
    }

    record MonthlyAggregate(
            MonthlyKey key,
            long weeklySettlementCount,
            long aggregatedSettlementCount,
            long salesCount,
            BigDecimal grossAmount,
            BigDecimal feeAmount,
            BigDecimal refundAmount,
            BigDecimal payoutAmount
    ) {
    }

    record MonthlyStatusCount(MonthlyKey key, SettlementDisplayStatus status, long count) {
    }

    record MonthlyPage(List<MonthlyAggregate> content, long totalElements) {
    }
}
```

- [ ] **Step 4: 주간 상세 조회용 JPA 메서드 추가**

`SellerSettlementJpaRepository`에 다음 메서드를 추가한다.

```java
@Query("""
        select s from SellerSettlement s
        where s.sellerId = :sellerId
          and s.periodStart >= :periodStart
          and s.periodStart < :periodEnd
        order by s.periodStart asc, s.sellerSettlementId asc
        """)
List<SellerSettlement> findWeeklySettlements(
        @Param("sellerId") UUID sellerId,
        @Param("periodStart") LocalDate periodStart,
        @Param("periodEnd") LocalDate periodEnd);
```

- [ ] **Step 5: 공통 월 범위와 집계 SQL을 가진 어댑터 구현**

`SellerSettlementQueryRepositoryAdapter`는 `NamedParameterJdbcTemplate`과
`SellerSettlementJpaRepository`를 주입받는다. 월별 select와 count에 아래 식을 그대로 사용한다.

```java
private static final String SETTLEMENT_YEAR =
        "CAST(EXTRACT(YEAR FROM (s.period_start + 3)) AS INTEGER)";
private static final String SETTLEMENT_MONTH =
        "CAST(EXTRACT(MONTH FROM (s.period_start + 3)) AS INTEGER)";

private static final String MONTHLY_SELECT = """
        SELECT s.seller_id AS seller_id,
               %s AS settlement_year,
               %s AS settlement_month,
               COUNT(*) AS weekly_settlement_count,
               SUM(CASE WHEN s.status <> 'CANCELLED' THEN 1 ELSE 0 END)
                   AS aggregated_settlement_count,
               SUM(CASE WHEN s.status <> 'CANCELLED' THEN s.product_count ELSE 0 END)
                   AS sales_count,
               SUM(CASE WHEN s.status <> 'CANCELLED' THEN s.total_amount ELSE 0 END)
                   AS gross_amount,
               SUM(CASE WHEN s.status <> 'CANCELLED' THEN s.fee_total_amount ELSE 0 END)
                   AS fee_amount,
               SUM(CASE WHEN s.status <> 'CANCELLED' THEN COALESCE(s.refund_amount, 0) ELSE 0 END)
                   AS refund_amount,
               SUM(CASE WHEN s.status <> 'CANCELLED' THEN s.settlement_total_amount ELSE 0 END)
                   AS payout_amount
        FROM seller_settlement s
        %s
        GROUP BY %s, %s
        ORDER BY settlement_year DESC, settlement_month DESC
        LIMIT :size OFFSET :offset
        """;
```

where 절은 항상 `s.seller_id = :sellerId`를 포함한다. `settlementMonth`가 있으면 아래 경계를 추가하고,
`status`가 있으면 같은 판매자·같은 계산 월에 해당 상태가 존재하는지 correlated `EXISTS`를 추가한다.

```java
private String buildWhere(SettlementDisplayStatus status, YearMonth settlementMonth) {
    StringBuilder where = new StringBuilder("WHERE s.seller_id = :sellerId");
    if (settlementMonth != null) {
        where.append(" AND s.period_start >= :periodStart AND s.period_start < :periodEnd");
    }
    if (status != null) {
        where.append("""
                 AND EXISTS (
                     SELECT 1
                     FROM seller_settlement sf
                     WHERE sf.seller_id = s.seller_id
                       AND CAST(EXTRACT(YEAR FROM (sf.period_start + 3)) AS INTEGER) = %s
                       AND CAST(EXTRACT(MONTH FROM (sf.period_start + 3)) AS INTEGER) = %s
                       AND sf.status = :status
                 )
                """.formatted(SETTLEMENT_YEAR, SETTLEMENT_MONTH));
    }
    return where.toString();
}

private MapSqlParameterSource parameters(UUID sellerId, SettlementDisplayStatus status,
        YearMonth settlementMonth, int page, int size) {
    MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("sellerId", sellerId)
            .addValue("size", size)
            .addValue("offset", Math.multiplyExact(page, size));
    if (status != null) {
        parameters.addValue("status", status.name());
    }
    if (settlementMonth != null) {
        parameters.addValue("periodStart", settlementMonth.atDay(1).minusDays(3));
        parameters.addValue("periodEnd", settlementMonth.plusMonths(1).atDay(1).minusDays(3));
    }
    return parameters;
}
```

count query는 동일한 where와 `GROUP BY`를 derived table로 감싸 `COUNT(*)`한다. 목록 매핑은 다음
factory를 사용한다.

```java
private static final String MONTHLY_COUNT = """
        SELECT COUNT(*)
        FROM (
            SELECT %s AS settlement_year, %s AS settlement_month
            FROM seller_settlement s
            %s
            GROUP BY %s, %s
        ) monthly_groups
        """;
```

```java
private MonthlyAggregate mapAggregate(ResultSet resultSet) throws SQLException {
    int year = resultSet.getInt("settlement_year");
    int month = resultSet.getInt("settlement_month");
    return new MonthlyAggregate(
            new MonthlyKey(UUID.fromString(resultSet.getString("seller_id")), YearMonth.of(year, month)),
            resultSet.getLong("weekly_settlement_count"),
            resultSet.getLong("aggregated_settlement_count"),
            resultSet.getLong("sales_count"),
            resultSet.getBigDecimal("gross_amount"),
            resultSet.getBigDecimal("fee_amount"),
            resultSet.getBigDecimal("refund_amount"),
            resultSet.getBigDecimal("payout_amount"));
}
```

판매자 select에도 `s.seller_id AS seller_id`를 포함한다. `findMonthlyAggregate`는 같은 select에서
`LIMIT/OFFSET`만 제거하고 정확한 월 경계를 강제해 0건이면 `Optional.empty()`를 반환한다.

`findStatusCounts`는 빈 key면 바로 빈 목록을 반환하고, key별 OR 절을 만든 한 쿼리로 다음 컬럼을
그룹화한다. 결과는 `status.ordinal()` 순서로 정렬한다.

```sql
SELECT s.seller_id,
       CAST(EXTRACT(YEAR FROM (s.period_start + 3)) AS INTEGER) AS settlement_year,
       CAST(EXTRACT(MONTH FROM (s.period_start + 3)) AS INTEGER) AS settlement_month,
       s.status,
       COUNT(*) AS status_count
FROM seller_settlement s
WHERE (s.seller_id = :sellerId0
       AND CAST(EXTRACT(YEAR FROM (s.period_start + 3)) AS INTEGER) = :year0
       AND CAST(EXTRACT(MONTH FROM (s.period_start + 3)) AS INTEGER) = :month0)
   OR (s.seller_id = :sellerId1
       AND CAST(EXTRACT(YEAR FROM (s.period_start + 3)) AS INTEGER) = :year1
       AND CAST(EXTRACT(MONTH FROM (s.period_start + 3)) AS INTEGER) = :month1)
GROUP BY s.seller_id, settlement_year, settlement_month, s.status
```

`findWeeklySettlements`는 아래 범위로 JPA 메서드에 위임한다.

```java
LocalDate periodStart = settlementMonth.atDay(1).minusDays(3);
LocalDate periodEnd = settlementMonth.plusMonths(1).atDay(1).minusDays(3);
return jpaRepository.findWeeklySettlements(sellerId, periodStart, periodEnd);
```

- [ ] **Step 6: 판매자 영속성 테스트 통과 확인**

Run:

```bash
./gradlew :user-service:test --tests '*SellerSettlementQueryRepositoryAdapterTest'
```

Expected: BUILD SUCCESSFUL. 월경계, 취소 제외, 상태 필터와 상세 범위 테스트가 모두 PASS.

- [ ] **Step 7: Task 1 변경만 stage하고 검증**

```bash
git add user-service/src/main/java/com/prompthub/user/sellersettlement/domain/repository/SellerSettlementQueryRepository.java
git add user-service/src/main/java/com/prompthub/user/sellersettlement/infrastructure/persistence/SellerSettlementQueryRepositoryAdapter.java
git add user-service/src/main/java/com/prompthub/user/sellersettlement/infrastructure/persistence/SellerSettlementJpaRepository.java
git add user-service/src/test/java/com/prompthub/user/sellersettlement/infrastructure/persistence/SellerSettlementQueryRepositoryAdapterTest.java
git diff --cached --check
git diff --cached --name-status
```

Expected: 생성 3개, 수정 1개만 stage.

- [ ] **Step 8: 판매자 집계 영속성 커밋**

```bash
git commit -m "feat: 판매자 월별 정산 집계 조회 추가 (#462)"
```

---

### Task 2: 판매자 월별 목록과 주간 상세 API

**Files:**
- Create: `user-service/src/main/java/com/prompthub/user/sellersettlement/presentation/dto/response/SellerSettlementMonthlyResponse.java`
- Create: `user-service/src/main/java/com/prompthub/user/sellersettlement/presentation/dto/response/SellerSettlementDetailResponse.java`
- Create: `user-service/src/test/java/com/prompthub/user/sellersettlement/presentation/controller/SellerSettlementControllerTest.java`
- Modify: `user-service/src/main/java/com/prompthub/user/sellersettlement/application/dto/SellerSettlementListQuery.java:1-13`
- Modify: `user-service/src/main/java/com/prompthub/user/sellersettlement/application/usecase/SellerSettlementUseCase.java:1-18`
- Modify: `user-service/src/main/java/com/prompthub/user/sellersettlement/application/service/SellerSettlementApplicationService.java:1-68`
- Modify: `user-service/src/main/java/com/prompthub/user/sellersettlement/presentation/dto/response/SellerSettlementListResponse.java:1-107`
- Modify: `user-service/src/main/java/com/prompthub/user/sellersettlement/presentation/controller/SellerSettlementController.java:1-103`
- Modify: `user-service/src/main/java/com/prompthub/user/sellersettlement/domain/repository/SellerSettlementRepository.java:1-31`
- Modify: `user-service/src/main/java/com/prompthub/user/sellersettlement/infrastructure/persistence/SellerSettlementRepositoryAdapter.java:1-61`
- Modify: `user-service/src/main/java/com/prompthub/user/sellersettlement/infrastructure/persistence/SellerSettlementJpaRepository.java`
- Modify: `user-service/src/test/java/com/prompthub/user/sellersettlement/application/service/SellerSettlementQueryServiceTest.java`
- Modify: `user-service/src/test/java/com/prompthub/user/sellersettlement/infrastructure/persistence/SellerSettlementRepositoryAdapterTest.java`
- Modify: `user-service/src/test/java/com/prompthub/user/sellersettlement/presentation/dto/response/SellerSettlementListResponseTest.java`

**Interfaces:**
- Consumes: Task 1의 `SellerSettlementQueryRepository`
- Produces: 월별 `SellerSettlementListResponse`, `SellerSettlementDetailResponse`, 기존 목록 경로와 신규 `/months/{settlementMonth}`

- [ ] **Step 1: 서비스의 월별 목록·상세·404 실패 테스트 작성**

`SellerSettlementQueryServiceTest`의 저장소 mock을 `SellerSettlementQueryRepository`로 바꾸고 다음을 검증한다.

```java
@Mock
private SellerSettlementRepository sellerSettlementRepository;

@Mock
private SellerSettlementQueryRepository sellerSettlementQueryRepository;

@InjectMocks
private SellerSettlementApplicationService service;

@Test
void getMySettlements_월별페이지와_상태건수를_응답으로_조립한다() {
    UUID sellerId = UUID.randomUUID();
    MonthlyKey key = new MonthlyKey(sellerId, YearMonth.of(2026, 7));
    MonthlyAggregate aggregate = new MonthlyAggregate(
            key, 3, 2, 22, bd("2200000"), bd("330000"), bd("100000"), bd("1770000"));
    given(sellerSettlementQueryRepository.findMonthlyPage(sellerId, null, null, 0, 10))
            .willReturn(new MonthlyPage(List.of(aggregate), 1));
    given(sellerSettlementQueryRepository.findStatusCounts(List.of(key)))
            .willReturn(List.of(new MonthlyStatusCount(key, SettlementDisplayStatus.APPROVED, 1)));

    SellerSettlementListResponse response = service.getMySettlements(
            new SellerSettlementListQuery(sellerId, null, null, 0, 10));

    assertThat(response.items()).singleElement().satisfies(item -> {
        assertThat(item.settlementMonth()).isEqualTo("2026-07");
        assertThat(item.payoutAmount()).isEqualByComparingTo("1770000");
        assertThat(item.statusCounts()).extracting(StatusCount::status).containsExactly("APPROVED");
    });
}

@Test
void getMySettlementMonth_주간행을_기간순으로_내려준다() {
    UUID sellerId = UUID.randomUUID();
    YearMonth month = YearMonth.of(2026, 7);
    MonthlyKey key = new MonthlyKey(sellerId, month);
    MonthlyAggregate aggregate = new MonthlyAggregate(
            key, 1, 1, 10, bd("1000000"), bd("150000"), bd("0"), bd("850000"));
    SellerSettlement weekly = approvedRow(sellerId, LocalDate.of(2026, 6, 29));
    given(sellerSettlementQueryRepository.findMonthlyAggregate(sellerId, month))
            .willReturn(Optional.of(aggregate));
    given(sellerSettlementQueryRepository.findStatusCounts(List.of(key)))
            .willReturn(List.of(new MonthlyStatusCount(key, SettlementDisplayStatus.APPROVED, 1)));
    given(sellerSettlementQueryRepository.findWeeklySettlements(sellerId, month))
            .willReturn(List.of(weekly));

    SellerSettlementDetailResponse response = service.getMySettlementMonth(sellerId, month);

    assertThat(response.weeklySettlements()).singleElement().satisfies(item -> {
        assertThat(item.settlementId()).isEqualTo(weekly.getSettlementId());
        assertThat(item.availableActions()).extracting(Action::type).containsExactly("REQUEST_PAYOUT");
    });
}

@Test
void getMySettlementMonth_본인월이_없으면_404예외다() {
    UUID sellerId = UUID.randomUUID();
    YearMonth month = YearMonth.of(2026, 7);
    given(sellerSettlementQueryRepository.findMonthlyAggregate(sellerId, month))
            .willReturn(Optional.empty());

    assertThatThrownBy(() -> service.getMySettlementMonth(sellerId, month))
            .isInstanceOf(SellerSettlementNotFoundException.class);
}

private static BigDecimal bd(String value) {
    return new BigDecimal(value);
}

private SellerSettlement approvedRow(UUID sellerId, LocalDate periodStart) {
    SellerSettlement settlement = SellerSettlement.seed(
            UUID.randomUUID(), sellerId, periodStart, periodStart.plusDays(6), 10,
            bd("1000000"), bd("850000"), bd("150000"), bd("0"),
            periodStart.plusDays(7).atStartOfDay());
    settlement.approve();
    return settlement;
}
```

- [ ] **Step 2: Controller 계약 실패 테스트 작성**

새 `SellerSettlementControllerTest`는 user-service의 기존 Controller 테스트 방식대로
`@SpringBootTest`, `@ActiveProfiles("test")`, `@AutoConfigureMockMvc`와
`@MockitoBean SellerSettlementUseCase`를 사용한다. 다음 요청을 고정한다.

```java
@Test
void 월별목록은_settlementMonth와_페이지를_전달한다() throws Exception {
    given(useCase.getMySettlements(any()))
            .willReturn(new SellerSettlementListResponse(List.of(), 0, 0, 10));

    mockMvc.perform(get("/api/v2/sellers/me/settlements")
                    .header("X-User-Id", SELLER_ID)
                    .param("settlementMonth", "2026-07")
                    .param("status", "APPROVED"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items").isEmpty())
            .andExpect(jsonPath("$.data.size").value(10));
}

@Test
void 월별상세를_조회한다() throws Exception {
    given(useCase.getMySettlementMonth(SELLER_ID, YearMonth.of(2026, 7)))
            .willReturn(emptyDetail("2026-07"));

    mockMvc.perform(get("/api/v2/sellers/me/settlements/months/2026-07")
                    .header("X-User-Id", SELLER_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.settlementMonth").value("2026-07"));
}

@ParameterizedTest
@CsvSource({"page,-1", "size,0", "size,101"})
void 잘못된_page_size는_400이다(String parameter, String value) throws Exception {
    mockMvc.perform(get("/api/v2/sellers/me/settlements")
                    .header("X-User-Id", SELLER_ID)
                    .param(parameter, value))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("V001"));
}

@Test
void 잘못된_월형식은_400이다() throws Exception {
    mockMvc.perform(get("/api/v2/sellers/me/settlements/months/2026-13")
                    .header("X-User-Id", SELLER_ID))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("V001"));
}

private static SellerSettlementDetailResponse emptyDetail(String settlementMonth) {
    return new SellerSettlementDetailResponse(
            settlementMonth, 0, 0, 0,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            List.of(), List.of());
}
```

- [ ] **Step 3: 새 서비스·Controller 테스트의 RED 확인**

Run:

```bash
./gradlew :user-service:test \
  --tests '*SellerSettlementQueryServiceTest' \
  --tests '*SellerSettlementControllerTest'
```

Expected: FAIL. 상세 use case와 응답 타입, `settlementMonth` parameter, validation이 아직 없다.

- [ ] **Step 4: query DTO와 use case를 월별 계약으로 변경**

```java
public record SellerSettlementListQuery(
        UUID sellerId,
        SettlementDisplayStatus status,
        YearMonth settlementMonth,
        int page,
        int size
) {
}
```

`SellerSettlementUseCase`에 상세를 추가한다.

```java
SellerSettlementListResponse getMySettlements(SellerSettlementListQuery query);

SellerSettlementDetailResponse getMySettlementMonth(UUID sellerId, YearMonth settlementMonth);
```

- [ ] **Step 5: 공통 월별 응답과 판매자 상세 응답 구현**

`SellerSettlementMonthlyResponse`에 아래 nested record를 둔다.

```java
public final class SellerSettlementMonthlyResponse {

    private SellerSettlementMonthlyResponse() {
    }

    public record StatusCount(String status, String statusLabel, long count) {
        public static StatusCount from(MonthlyStatusCount count) {
            return new StatusCount(count.status().name(), count.status().getLabel(), count.count());
        }
    }

    public static List<StatusCount> statusCounts(
            MonthlyKey key, List<MonthlyStatusCount> allCounts) {
        return allCounts.stream()
                .filter(count -> count.key().equals(key))
                .sorted(Comparator.comparingInt(count -> count.status().ordinal()))
                .map(StatusCount::from)
                .toList();
    }

    public record Action(String type, String label) {
        static Action requestPayout() {
            return new Action("REQUEST_PAYOUT", "지급 신청하기");
        }
    }

    public record WeeklySettlement(
            UUID settlementId,
            LocalDate periodStart,
            LocalDate periodEnd,
            int salesCount,
            BigDecimal grossAmount,
            BigDecimal feeAmount,
            BigDecimal refundAmount,
            BigDecimal payoutAmount,
            String status,
            String statusLabel,
            LocalDateTime calculatedAt,
            LocalDateTime approvedAt,
            LocalDateTime payoutRequestedAt,
            LocalDateTime paidAt,
            LocalDateTime cancelledAt,
            List<Action> availableActions
    ) {
        public static WeeklySettlement from(SellerSettlement settlement) {
            List<Action> actions = settlement.canRequestPayout()
                    ? List.of(Action.requestPayout()) : List.of();
            return new WeeklySettlement(
                    settlement.getSettlementId(), settlement.getPeriodStart(), settlement.getPeriodEnd(),
                    settlement.getProductCount(), settlement.getTotalAmount(), settlement.getFeeTotalAmount(),
                    zeroIfNull(settlement.getRefundAmount()), settlement.getSettlementTotalAmount(),
                    settlement.getStatus().name(), settlement.getStatus().getLabel(),
                    settlement.getCalculatedAt(), settlement.getApprovedAt(), settlement.getPayoutRequestedAt(),
                    settlement.getPaidAt(), settlement.getCancelledAt(), actions);
        }
    }

    static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
```

`SellerSettlementDetailResponse`는 다음 필드와 factory를 사용한다.

```java
public record SellerSettlementDetailResponse(
        String settlementMonth,
        long weeklySettlementCount,
        long aggregatedSettlementCount,
        long salesCount,
        BigDecimal grossAmount,
        BigDecimal feeAmount,
        BigDecimal refundAmount,
        BigDecimal payoutAmount,
        List<StatusCount> statusCounts,
        List<WeeklySettlement> weeklySettlements
) {
    public static SellerSettlementDetailResponse from(
            MonthlyAggregate aggregate,
            List<MonthlyStatusCount> allCounts,
            List<SellerSettlement> weeklySettlements) {
        return new SellerSettlementDetailResponse(
                aggregate.key().settlementMonth().toString(),
                aggregate.weeklySettlementCount(), aggregate.aggregatedSettlementCount(),
                aggregate.salesCount(), aggregate.grossAmount(), aggregate.feeAmount(),
                aggregate.refundAmount(), aggregate.payoutAmount(),
                SellerSettlementMonthlyResponse.statusCounts(aggregate.key(), allCounts),
                weeklySettlements.stream().map(WeeklySettlement::from).toList());
    }
}
```

- [ ] **Step 6: 기존 목록 응답을 월별 상위 객체로 교체**

`SellerSettlementListResponse.Item`을 다음 필드로 바꾼다. `settlementId`, 기간, 단일 상태와 액션은
목록에서 제거한다.

```java
public record Item(
        String settlementMonth,
        long weeklySettlementCount,
        long aggregatedSettlementCount,
        long salesCount,
        BigDecimal grossAmount,
        BigDecimal feeAmount,
        BigDecimal refundAmount,
        BigDecimal payoutAmount,
        List<StatusCount> statusCounts
) {
    static Item from(MonthlyAggregate aggregate, List<MonthlyStatusCount> allCounts) {
        return new Item(
                aggregate.key().settlementMonth().toString(), aggregate.weeklySettlementCount(),
                aggregate.aggregatedSettlementCount(), aggregate.salesCount(), aggregate.grossAmount(),
                aggregate.feeAmount(), aggregate.refundAmount(), aggregate.payoutAmount(),
                SellerSettlementMonthlyResponse.statusCounts(aggregate.key(), allCounts));
    }
}
```

상위 factory는 다음과 같다.

```java
public static SellerSettlementListResponse from(
        MonthlyPage monthlyPage, List<MonthlyStatusCount> counts, int page, int size) {
    List<Item> items = monthlyPage.content().stream()
            .map(aggregate -> Item.from(aggregate, counts))
            .toList();
    return new SellerSettlementListResponse(items, monthlyPage.totalElements(), page, size);
}
```

- [ ] **Step 7: 애플리케이션 서비스를 월별 조회 포트로 전환**

기존 write/summary 저장소와 새 query 저장소를 함께 주입한다.

```java
private final SellerSettlementRepository sellerSettlementRepository;
private final SellerSettlementQueryRepository sellerSettlementQueryRepository;
```

목록과 상세 구현은 다음과 같다.

```java
@Override
@Transactional(readOnly = true)
public SellerSettlementListResponse getMySettlements(SellerSettlementListQuery query) {
    MonthlyPage page = sellerSettlementQueryRepository.findMonthlyPage(
            query.sellerId(), query.status(), query.settlementMonth(), query.page(), query.size());
    List<MonthlyKey> keys = page.content().stream().map(MonthlyAggregate::key).toList();
    List<MonthlyStatusCount> counts = sellerSettlementQueryRepository.findStatusCounts(keys);
    return SellerSettlementListResponse.from(page, counts, query.page(), query.size());
}

@Override
@Transactional(readOnly = true)
public SellerSettlementDetailResponse getMySettlementMonth(UUID sellerId, YearMonth settlementMonth) {
    MonthlyAggregate aggregate = sellerSettlementQueryRepository
            .findMonthlyAggregate(sellerId, settlementMonth)
            .orElseThrow(SellerSettlementNotFoundException::new);
    List<MonthlyStatusCount> counts =
            sellerSettlementQueryRepository.findStatusCounts(List.of(aggregate.key()));
    List<SellerSettlement> weekly =
            sellerSettlementQueryRepository.findWeeklySettlements(sellerId, settlementMonth);
    return SellerSettlementDetailResponse.from(aggregate, counts, weekly);
}
```

- [ ] **Step 8: Controller에 월별 parameter·상세·검증·Swagger 추가**

Controller에 `@Validated`를 붙이고 page/size에 다음 검증을 적용한다.

```java
@RequestParam(defaultValue = "0") @Min(0) int page,
@RequestParam(defaultValue = "10") @Min(1) @Max(100) int size
```

기존 `period` parameter를 다음으로 바꾼다.

```java
@Parameter(description = "정산 월(YYYY-MM)", example = "2026-07")
@RequestParam(required = false)
@DateTimeFormat(pattern = "yyyy-MM") YearMonth settlementMonth
```

상세 메서드를 추가한다.

```java
@GetMapping("/months/{settlementMonth}")
@Operation(summary = "판매자 월별 정산 상세 조회",
        description = "본인의 정산 월에 포함된 주간 정산과 가능한 액션을 조회합니다. SELLER 권한이 필요합니다.")
public ApiResult<SellerSettlementDetailResponse> getMySettlementMonth(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") UUID sellerId,
        @PathVariable @DateTimeFormat(pattern = "yyyy-MM") YearMonth settlementMonth
) {
    return ApiResult.success(sellerSettlementUseCase.getMySettlementMonth(sellerId, settlementMonth));
}
```

Swagger에 200·400·403·404 응답을 기록한다.

- [ ] **Step 9: 사용하지 않는 기존 주간 목록 조회 제거**

다음 항목만 제거하고 저장·누적 summary 메서드는 유지한다.

```text
SellerSettlementRepository.findPageBySeller(...)
SellerSettlementRepository.SellerSettlementPage
SellerSettlementRepositoryAdapter.findPageBySeller(...)
SellerSettlementJpaRepository.findPageBySeller(...)
```

기존 adapter test의 월경계·정렬 mock 테스트를 제거하고 `existsBySettlementId` 위임 테스트는 유지한다.
`SellerSettlementListResponseTest`는 월별 aggregate와 상태 count를 만들어 필드명·enum 순서를 검증하게
바꾼다.

- [ ] **Step 10: 판매자 단위·Controller·회귀 테스트 실행**

Run:

```bash
./gradlew :user-service:test \
  --tests '*SellerSettlementQueryRepositoryAdapterTest' \
  --tests '*SellerSettlementQueryServiceTest' \
  --tests '*SellerSettlementListResponseTest' \
  --tests '*SellerSettlementControllerTest' \
  --tests '*SellerSettlementRequestPayoutServiceTest' \
  --tests '*SellerSettlementSummaryServiceTest'
```

Expected: BUILD SUCCESSFUL. 월별 API와 기존 지급 신청·누적 summary가 모두 PASS.

- [ ] **Step 11: Task 2 범위만 stage하고 커밋**

```bash
git add user-service/src/main/java/com/prompthub/user/sellersettlement
git add user-service/src/test/java/com/prompthub/user/sellersettlement
git diff --cached --check
git diff --cached --name-status
git commit -m "feat: 판매자 월별 정산 상세 API 추가 (#462)"
```

Expected: user `sellersettlement` main/test만 포함하고 다른 user 패키지는 포함하지 않는다.

---

### Task 3: 어드민 판매자-월 집계 영속성과 월별 요약

**Files:**
- Create: `admin-service/src/main/java/com/prompthub/admin/settlement/domain/repository/SettlementMonthlyQueryRepository.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/settlement/infrastructure/persistence/SettlementMonthlyQueryRepositoryAdapter.java`
- Modify: `admin-service/src/main/java/com/prompthub/admin/settlement/infrastructure/persistence/SettlementQueryJpaRepository.java:1-21`
- Modify: `admin-service/src/main/java/com/prompthub/admin/settlement/domain/repository/SettlementQueryRepository.java:1-16`
- Modify: `admin-service/src/main/java/com/prompthub/admin/settlement/infrastructure/persistence/SettlementQueryRepositoryAdapter.java:1-46`
- Modify: `admin-service/src/test/java/com/prompthub/admin/settlement/infrastructure/persistence/SettlementQueryRepositoryAdapterTest.java`

**Interfaces:**
- Consumes: admin-service가 재매핑한 `seller_settlement`와 기존 summary 카드 집계
- Produces: 판매자-월 그룹 page/detail/status 조회, `aggregateByStatus(YearMonth)`

- [ ] **Step 1: 기존 SQL fixture를 사용하지 않는 H2 실패 테스트로 교체**

`SettlementQueryRepositoryAdapterTest`에서 `@Sql("/sql/settlements.sql")`를 제거한다. 두 adapter를
import하고 테스트 내부 `EntityManager` native insert helper로 유효한 월요일~일요일 행을 넣는다.

```java
@DataJpaTest
@Import({SettlementQueryRepositoryAdapter.class, SettlementMonthlyQueryRepositoryAdapter.class})
@ActiveProfiles("test")
class SettlementQueryRepositoryAdapterTest {

@Autowired
private SettlementQueryRepository settlementQueryRepository;

@Autowired
private SettlementMonthlyQueryRepository monthlyQueryRepository;

@Autowired
private TestEntityManager entityManager;

private void insert(UUID sellerId, LocalDate periodStart, int salesCount,
        String gross, String fee, String refund, String payout, SettlementDisplayStatus status) {
    entityManager.getEntityManager().createNativeQuery("""
            INSERT INTO seller_settlement (
                seller_settlement_id, settlement_id, seller_id, period_start, period_end,
                product_count, total_amount, settlement_total_amount, fee_total_amount,
                refund_amount, calculated_at, status)
            VALUES (:rowId, :settlementId, :sellerId, :periodStart, :periodEnd,
                :salesCount, :gross, :payout, :fee, :refund, :calculatedAt, :status)
            """)
            .setParameter("rowId", UUID.randomUUID())
            .setParameter("settlementId", UUID.randomUUID())
            .setParameter("sellerId", sellerId)
            .setParameter("periodStart", periodStart)
            .setParameter("periodEnd", periodStart.plusDays(6))
            .setParameter("salesCount", salesCount)
            .setParameter("gross", new BigDecimal(gross))
            .setParameter("payout", new BigDecimal(payout))
            .setParameter("fee", new BigDecimal(fee))
            .setParameter("refund", new BigDecimal(refund))
            .setParameter("calculatedAt", periodStart.plusDays(7).atStartOfDay())
            .setParameter("status", status.name())
            .executeUpdate();
}

@Test
void 판매자와_월로_그룹하고_취소행은_합계에서_제외한다() {
    UUID sellerId = UUID.randomUUID();
    insert(sellerId, LocalDate.of(2026, 6, 29), 10,
            "1000000", "150000", "0", "850000", SettlementDisplayStatus.APPROVED);
    insert(sellerId, LocalDate.of(2026, 7, 6), 12,
            "1200000", "180000", "100000", "920000", SettlementDisplayStatus.PAID);
    insert(sellerId, LocalDate.of(2026, 7, 13), 5,
            "500000", "75000", "0", "425000", SettlementDisplayStatus.CANCELLED);

    MonthlyPage page = monthlyQueryRepository.findMonthlyPage(null, YearMonth.of(2026, 7), 0, 20);
    MonthlyAggregate aggregate = page.content().getFirst();

    assertThat(page.totalElements()).isEqualTo(1);
    assertThat(aggregate.key()).isEqualTo(new MonthlyKey(sellerId, YearMonth.of(2026, 7)));
    assertThat(aggregate.weeklySettlementCount()).isEqualTo(3);
    assertThat(aggregate.aggregatedSettlementCount()).isEqualTo(2);
    assertThat(aggregate.salesCount()).isEqualTo(22);
    assertThat(aggregate.grossAmount()).isEqualByComparingTo("2200000");
    assertThat(aggregate.feeAmount()).isEqualByComparingTo("330000");
    assertThat(aggregate.refundAmount()).isEqualByComparingTo("100000");
    assertThat(aggregate.payoutAmount()).isEqualByComparingTo("1770000");
    assertThat(monthlyQueryRepository.findStatusCounts(List.of(aggregate.key())))
            .extracting(MonthlyStatusCount::status)
            .containsExactly(SettlementDisplayStatus.APPROVED,
                    SettlementDisplayStatus.PAID, SettlementDisplayStatus.CANCELLED);
}

@Test
void 상태필터는_판매자월_그룹만_고르고_전체월_합계를_유지한다() {
    UUID sellerId = UUID.randomUUID();
    insert(sellerId, LocalDate.of(2026, 6, 29), 10,
            "1000000", "150000", "0", "850000", SettlementDisplayStatus.APPROVED);
    insert(sellerId, LocalDate.of(2026, 7, 6), 12,
            "1200000", "180000", "100000", "920000", SettlementDisplayStatus.PAID);

    MonthlyPage page = monthlyQueryRepository.findMonthlyPage(
            SettlementDisplayStatus.APPROVED, null, 0, 20);

    assertThat(page.content()).singleElement().satisfies(aggregate -> {
        assertThat(aggregate.key().sellerId()).isEqualTo(sellerId);
        assertThat(aggregate.grossAmount()).isEqualByComparingTo("2200000");
        assertThat(aggregate.payoutAmount()).isEqualByComparingTo("1770000");
    });
}

@Test
void 그룹페이지는_월내_sellerId_ASC이고_totalElements는_그룹수다() {
    UUID sellerA = UUID.fromString("11111111-1111-1111-1111-111111111111");
    UUID sellerB = UUID.fromString("22222222-2222-2222-2222-222222222222");
    insert(sellerB, LocalDate.of(2026, 7, 6), 1,
            "100", "15", "0", "85", SettlementDisplayStatus.WAITING);
    insert(sellerA, LocalDate.of(2026, 7, 13), 1,
            "100", "15", "0", "85", SettlementDisplayStatus.WAITING);
    insert(sellerA, LocalDate.of(2026, 8, 3), 1,
            "100", "15", "0", "85", SettlementDisplayStatus.WAITING);

    MonthlyPage page = monthlyQueryRepository.findMonthlyPage(null, null, 0, 3);

    assertThat(page.totalElements()).isEqualTo(3);
    assertThat(page.content()).extracting(MonthlyAggregate::key).containsExactly(
            new MonthlyKey(sellerA, YearMonth.of(2026, 8)),
            new MonthlyKey(sellerA, YearMonth.of(2026, 7)),
            new MonthlyKey(sellerB, YearMonth.of(2026, 7)));
}

@Test
void 상세는_판매자월_전체주간을_기간순으로_조회하고_없는조합은_비어있다() {
    UUID sellerId = UUID.randomUUID();
    insert(sellerId, LocalDate.of(2026, 7, 6), 1,
            "200", "30", "0", "170", SettlementDisplayStatus.PAID);
    insert(sellerId, LocalDate.of(2026, 6, 29), 1,
            "100", "15", "0", "85", SettlementDisplayStatus.APPROVED);

    assertThat(monthlyQueryRepository.findMonthlyAggregate(sellerId, YearMonth.of(2026, 7)))
            .isPresent();
    assertThat(monthlyQueryRepository.findWeeklySettlements(sellerId, YearMonth.of(2026, 7)))
            .extracting(Settlement::getPeriodStart)
            .containsExactly(LocalDate.of(2026, 6, 29), LocalDate.of(2026, 7, 6));
    assertThat(monthlyQueryRepository.findMonthlyAggregate(
            UUID.randomUUID(), YearMonth.of(2026, 7))).isEmpty();
}

@Test
void 월별_summary는_목요일기준_범위만_상태별집계한다() {
    UUID sellerId = UUID.randomUUID();
    insert(sellerId, LocalDate.of(2026, 6, 29), 1,
            "100", "15", "0", "85", SettlementDisplayStatus.WAITING);
    insert(sellerId, LocalDate.of(2026, 7, 27), 1,
            "200", "30", "0", "170", SettlementDisplayStatus.PAID);
    insert(sellerId, LocalDate.of(2026, 8, 3), 1,
            "300", "45", "0", "255", SettlementDisplayStatus.APPROVED);

    List<SettlementStatusAggregate> result =
            settlementQueryRepository.aggregateByStatus(YearMonth.of(2026, 7));

    assertThat(result).extracting(SettlementStatusAggregate::status)
            .containsExactlyInAnyOrder(SettlementDisplayStatus.WAITING, SettlementDisplayStatus.PAID);
    assertThat(result).extracting(SettlementStatusAggregate::sumSettlementTotal)
            .containsExactlyInAnyOrder(new BigDecimal("85"), new BigDecimal("170"));
}
}
```

- [ ] **Step 2: 어드민 월별 포트 부재와 기존 summary 시그니처로 RED 확인**

Run:

```bash
./gradlew :admin-service:test --tests '*SettlementQueryRepositoryAdapterTest'
```

Expected: FAIL at compile; 월별 포트와 `aggregateByStatus(YearMonth)`가 없다.

- [ ] **Step 3: 어드민 월별 조회 포트 정의**

`SettlementMonthlyQueryRepository`는 Task 1 포트와 같은 record 구조를 사용하되 주간 타입은
admin `Settlement`다.

```java
public interface SettlementMonthlyQueryRepository {

    MonthlyPage findMonthlyPage(SettlementDisplayStatus status,
            YearMonth settlementMonth, int page, int size);

    Optional<MonthlyAggregate> findMonthlyAggregate(UUID sellerId, YearMonth settlementMonth);

    List<MonthlyStatusCount> findStatusCounts(List<MonthlyKey> keys);

    List<Settlement> findWeeklySettlements(UUID sellerId, YearMonth settlementMonth);

    record MonthlyKey(UUID sellerId, YearMonth settlementMonth) { }

    record MonthlyAggregate(
            MonthlyKey key, long weeklySettlementCount, long aggregatedSettlementCount,
            long salesCount, BigDecimal grossAmount, BigDecimal feeAmount,
            BigDecimal refundAmount, BigDecimal payoutAmount) { }

    record MonthlyStatusCount(MonthlyKey key, SettlementDisplayStatus status, long count) { }

    record MonthlyPage(List<MonthlyAggregate> content, long totalElements) { }
}
```

- [ ] **Step 4: 어드민 집계 adapter 구현**

Task 1 SQL을 복사하지 말고 어드민 차이를 아래처럼 명확히 반영한다.

- select와 group key에 `s.seller_id` 추가
- seller 고정 where 제거
- 정렬은 `settlement_year DESC, settlement_month DESC, s.seller_id ASC`
- 상태 `EXISTS`는 `sf.seller_id = s.seller_id`와 계산 월을 모두 상관 조건으로 사용
- `findMonthlyAggregate`와 주간 상세에만 `sellerId` 고정
- current page의 seller-month key만 한 번의 OR 쿼리로 상태 count 조회
- 주간 JPA 조회는 `periodStart ASC, sellerSettlementId ASC`

`SettlementQueryJpaRepository`에 주간 상세 메서드를 추가한다.

```java
@Query("""
        select s from Settlement s
        where s.sellerId = :sellerId
          and s.periodStart >= :periodStart
          and s.periodStart < :periodEnd
        order by s.periodStart asc, s.sellerSettlementId asc
        """)
List<Settlement> findWeeklySettlements(
        @Param("sellerId") UUID sellerId,
        @Param("periodStart") LocalDate periodStart,
        @Param("periodEnd") LocalDate periodEnd);
```

월별 합계 select는 다음 컬럼을 반환한다.

```sql
SELECT s.seller_id,
       CAST(EXTRACT(YEAR FROM (s.period_start + 3)) AS INTEGER) AS settlement_year,
       CAST(EXTRACT(MONTH FROM (s.period_start + 3)) AS INTEGER) AS settlement_month,
       COUNT(*) AS weekly_settlement_count,
       SUM(CASE WHEN s.status <> 'CANCELLED' THEN 1 ELSE 0 END) AS aggregated_settlement_count,
       SUM(CASE WHEN s.status <> 'CANCELLED' THEN s.product_count ELSE 0 END) AS sales_count,
       SUM(CASE WHEN s.status <> 'CANCELLED' THEN s.total_amount ELSE 0 END) AS gross_amount,
       SUM(CASE WHEN s.status <> 'CANCELLED' THEN s.fee_total_amount ELSE 0 END) AS fee_amount,
       SUM(CASE WHEN s.status <> 'CANCELLED' THEN COALESCE(s.refund_amount, 0) ELSE 0 END) AS refund_amount,
       SUM(CASE WHEN s.status <> 'CANCELLED' THEN s.settlement_total_amount ELSE 0 END) AS payout_amount
FROM seller_settlement s
%s
GROUP BY s.seller_id, settlement_year, settlement_month
ORDER BY settlement_year DESC, settlement_month DESC, s.seller_id ASC
LIMIT :size OFFSET :offset
```

SQL 주석은 실제 Java text block에서는 제거하고 동적 where 문자열을 삽입한다.

- [ ] **Step 5: summary 포트에 선택 월을 추가**

`SettlementQueryRepository`에는 기존 전체 summary 메서드를 유지하고 월 선택 overload를 추가한다.
이렇게 해야 Task 3 커밋 시 현재 애플리케이션 서비스도 계속 컴파일된다. 기존 주간 page 계약도 Task 5가
전환할 때까지 유지한다.

```java
List<SettlementStatusAggregate> aggregateByStatus();

List<SettlementStatusAggregate> aggregateByStatus(YearMonth settlementMonth);
```

`SettlementQueryJpaRepository`에 월 범위용 JPQL을 추가한다.

```java
@Query("""
        select new com.prompthub.admin.settlement.domain.repository.SettlementStatusAggregate(
            s.status, sum(s.settlementTotalAmount), count(s))
        from Settlement s
        where s.periodStart >= :periodStart and s.periodStart < :periodEnd
        group by s.status
        """)
List<SettlementStatusAggregate> aggregateByStatusBetween(
        @Param("periodStart") LocalDate periodStart,
        @Param("periodEnd") LocalDate periodEnd);
```

adapter overload는 month가 null이면 기존 전체 집계를 호출하고, 값이 있으면 다음 경계로 새 쿼리를
호출한다.

```java
@Override
public List<SettlementStatusAggregate> aggregateByStatus(YearMonth settlementMonth) {
    if (settlementMonth == null) {
        return aggregateByStatus();
    }
    LocalDate periodStart = settlementMonth.atDay(1).minusDays(3);
    LocalDate periodEnd = settlementMonth.plusMonths(1).atDay(1).minusDays(3);
    return jpaRepository.aggregateByStatusBetween(periodStart, periodEnd);
}
```

- [ ] **Step 6: 어드민 영속성 테스트 통과 확인**

Run:

```bash
./gradlew :admin-service:test --tests '*SettlementQueryRepositoryAdapterTest'
```

Expected: BUILD SUCCESSFUL. H2에서 월경계, 취소 제외, 상태 필터, 그룹 페이지와 summary 월 범위가 PASS.

- [ ] **Step 7: Task 3 범위 stage·검증·커밋**

```bash
git add admin-service/src/main/java/com/prompthub/admin/settlement/domain/repository
git add admin-service/src/main/java/com/prompthub/admin/settlement/infrastructure/persistence
git add admin-service/src/test/java/com/prompthub/admin/settlement/infrastructure/persistence/SettlementQueryRepositoryAdapterTest.java
git diff --cached --check
git diff --cached --name-status
git commit -m "feat: 어드민 월별 정산 집계 조회 추가 (#462)"
```

---

### Task 4: 어드민 판매자명 조회 포트와 벌크 어댑터

**Files:**
- Create: `admin-service/src/main/java/com/prompthub/admin/settlement/application/port/SellerNameQueryPort.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/settlement/infrastructure/user/SellerNameQueryAdapter.java`
- Create: `admin-service/src/test/java/com/prompthub/admin/settlement/infrastructure/user/SellerNameQueryAdapterTest.java`

**Interfaces:**
- Consumes: `UserRepository.findAllByIds(List<UUID>)`, `User.getUserId()`, `User.getName()`
- Produces: `Map<UUID, String> findNamesBySellerIds(List<UUID>)`

- [ ] **Step 1: 벌크 조회와 빈 입력 실패 테스트 작성**

```java
@ExtendWith(MockitoExtension.class)
class SellerNameQueryAdapterTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private SellerNameQueryAdapter adapter;

    @Test
    void 판매자ID를_중복제거해_한번에_조회한다() {
        UUID sellerId = UUID.randomUUID();
        User user = mock(User.class);
        given(user.getUserId()).willReturn(sellerId);
        given(user.getName()).willReturn("프롬프트 상점");
        given(userRepository.findAllByIds(List.of(sellerId))).willReturn(List.of(user));

        Map<UUID, String> result = adapter.findNamesBySellerIds(List.of(sellerId, sellerId));

        assertThat(result).containsEntry(sellerId, "프롬프트 상점");
        then(userRepository).should().findAllByIds(List.of(sellerId));
    }

    @Test
    void 빈ID목록이면_사용자저장소를_호출하지_않는다() {
        assertThat(adapter.findNamesBySellerIds(List.of())).isEmpty();
        then(userRepository).shouldHaveNoInteractions();
    }
}
```

- [ ] **Step 2: 포트 부재로 RED 확인**

Run:

```bash
./gradlew :admin-service:test --tests '*SellerNameQueryAdapterTest'
```

Expected: FAIL at compile; 포트와 adapter가 없다.

- [ ] **Step 3: 포트와 어댑터 구현**

```java
package com.prompthub.admin.settlement.application.port;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface SellerNameQueryPort {
    Map<UUID, String> findNamesBySellerIds(List<UUID> sellerIds);
}
```

```java
@Component
@RequiredArgsConstructor
public class SellerNameQueryAdapter implements SellerNameQueryPort {

    private final UserRepository userRepository;

    @Override
    public Map<UUID, String> findNamesBySellerIds(List<UUID> sellerIds) {
        List<UUID> distinctIds = sellerIds.stream().distinct().toList();
        if (distinctIds.isEmpty()) {
            return Map.of();
        }
        return userRepository.findAllByIds(distinctIds).stream()
                .collect(Collectors.toUnmodifiableMap(User::getUserId, User::getName));
    }
}
```

이 어댑터만 `admin.user` 패키지를 import한다. application service와 presentation은 포트만 안다.

- [ ] **Step 4: adapter 테스트 통과 확인 후 커밋**

```bash
./gradlew :admin-service:test --tests '*SellerNameQueryAdapterTest'
git add admin-service/src/main/java/com/prompthub/admin/settlement/application/port/SellerNameQueryPort.java
git add admin-service/src/main/java/com/prompthub/admin/settlement/infrastructure/user/SellerNameQueryAdapter.java
git add admin-service/src/test/java/com/prompthub/admin/settlement/infrastructure/user/SellerNameQueryAdapterTest.java
git diff --cached --check
git commit -m "feat: 어드민 정산 판매자명 조회 포트 추가 (#462)"
```

Expected: BUILD SUCCESSFUL 후 생성 파일 3개만 커밋.

---

### Task 5: 어드민 월별 목록·상세·요약 API

**Files:**
- Create: `admin-service/src/main/java/com/prompthub/admin/settlement/presentation/dto/response/SettlementMonthlyResponse.java`
- Create: `admin-service/src/main/java/com/prompthub/admin/settlement/presentation/dto/response/SettlementDetailResponse.java`
- Modify: `admin-service/src/main/java/com/prompthub/admin/settlement/application/dto/SettlementListQuery.java:1-6`
- Modify: `admin-service/src/main/java/com/prompthub/admin/settlement/application/usecase/SettlementUseCase.java:1-28`
- Modify: `admin-service/src/main/java/com/prompthub/admin/settlement/application/service/SettlementApplicationService.java:1-148`
- Modify: `admin-service/src/main/java/com/prompthub/admin/settlement/domain/repository/SettlementQueryRepository.java`
- Modify: `admin-service/src/main/java/com/prompthub/admin/settlement/infrastructure/persistence/SettlementQueryRepositoryAdapter.java`
- Modify: `admin-service/src/main/java/com/prompthub/admin/settlement/presentation/dto/response/SettlementListResponse.java:1-75`
- Modify: `admin-service/src/main/java/com/prompthub/admin/settlement/presentation/controller/SettlementController.java:1-230`
- Modify: `admin-service/src/test/java/com/prompthub/admin/settlement/application/service/SettlementApplicationServiceTest.java`
- Modify: `admin-service/src/test/java/com/prompthub/admin/settlement/presentation/controller/SettlementControllerTest.java`

**Interfaces:**
- Consumes: Task 3의 `SettlementMonthlyQueryRepository`, Task 4의 `SellerNameQueryPort`
- Produces: `/api/v2/admin/settlements`, `/sellers/{sellerId}/months/{settlementMonth}`, 월 필터 summary

- [ ] **Step 1: 애플리케이션 서비스 실패 테스트를 월별 계약으로 변경**

수동 생성자에 `SettlementMonthlyQueryRepository`, `SellerNameQueryPort` mock을 추가한다. 다음을 검증한다.

```java
private final SettlementQueryRepository settlementQueryRepository = mock(SettlementQueryRepository.class);
private final SettlementMonthlyQueryRepository monthlyQueryRepository =
        mock(SettlementMonthlyQueryRepository.class);
private final SellerNameQueryPort sellerNameQueryPort = mock(SellerNameQueryPort.class);
private final SettlementRepository settlementRepository = mock(SettlementRepository.class);
private final SettlementSourceRepository settlementSourceRepository = mock(SettlementSourceRepository.class);
private final SettlementApplicationService service = new SettlementApplicationService(
        settlementQueryRepository, monthlyQueryRepository, sellerNameQueryPort,
        settlementRepository, settlementSourceRepository);

@Test
void 월별목록은_상태건수와_판매자명을_한번에_조립한다() {
    MonthlyKey key = new MonthlyKey(SELLER_ID, YearMonth.of(2026, 7));
    MonthlyAggregate aggregate = new MonthlyAggregate(
            key, 3, 2, 22, bd("2200000"), bd("330000"), bd("100000"), bd("1770000"));
    when(monthlyQueryRepository.findMonthlyPage(null, null, 0, 20))
            .thenReturn(new MonthlyPage(List.of(aggregate), 1));
    when(monthlyQueryRepository.findStatusCounts(List.of(key)))
            .thenReturn(List.of(new MonthlyStatusCount(key, SettlementDisplayStatus.APPROVED, 1)));
    when(sellerNameQueryPort.findNamesBySellerIds(List.of(SELLER_ID)))
            .thenReturn(Map.of(SELLER_ID, "프롬프트 상점"));

    SettlementListResponse response = service.getList(new SettlementListQuery(null, null, 0, 20));

    assertThat(response.items()).singleElement().satisfies(item -> {
        assertThat(item.sellerId()).isEqualTo(SELLER_ID);
        assertThat(item.sellerName()).isEqualTo("프롬프트 상점");
        assertThat(item.settlementMonth()).isEqualTo("2026-07");
        assertThat(item.payoutAmount()).isEqualByComparingTo("1770000");
    });
    verify(sellerNameQueryPort).findNamesBySellerIds(List.of(SELLER_ID));
}

@Test
void 판매자명이_없어도_월별그룹을_null이름으로_유지한다() {
    MonthlyKey key = new MonthlyKey(SELLER_ID, YearMonth.of(2026, 7));
    MonthlyAggregate aggregate = new MonthlyAggregate(
            key, 1, 1, 1, bd("100"), bd("15"), bd("0"), bd("85"));
    when(monthlyQueryRepository.findMonthlyPage(null, null, 0, 20))
            .thenReturn(new MonthlyPage(List.of(aggregate), 1));
    when(monthlyQueryRepository.findStatusCounts(List.of(key))).thenReturn(List.of());
    when(sellerNameQueryPort.findNamesBySellerIds(List.of(SELLER_ID))).thenReturn(Map.of());

    SettlementListResponse response = service.getList(new SettlementListQuery(null, null, 0, 20));

    assertThat(response.items()).singleElement().satisfies(item -> {
        assertThat(item.sellerId()).isEqualTo(SELLER_ID);
        assertThat(item.sellerName()).isNull();
    });
}

@Test
void 상세_판매자월이_없으면_404다() {
    YearMonth month = YearMonth.of(2026, 7);
    when(monthlyQueryRepository.findMonthlyAggregate(SELLER_ID, month)).thenReturn(Optional.empty());

    AdminException exception = catchThrowableOfType(
            AdminException.class, () -> service.getDetail(SELLER_ID, month));

    assertThat(exception.getErrorCode()).isEqualTo(AdminErrorCode.SETTLEMENT_NOT_FOUND);
}

@Test
void 요약은_선택월을_저장소에_전달하고_기존카드버킷을_유지한다() {
    YearMonth month = YearMonth.of(2026, 7);
    when(settlementQueryRepository.aggregateByStatus(month)).thenReturn(List.of(
            new SettlementStatusAggregate(SettlementDisplayStatus.APPROVAL_ON_HOLD, bd("100"), 1),
            new SettlementStatusAggregate(SettlementDisplayStatus.PAYOUT_REQUESTED, bd("200"), 2)));

    SettlementSummaryResponse response = service.getSummary(month);

    assertThat(response.cards().get(0).totalAmount()).isEqualByComparingTo("100");
    assertThat(response.cards().get(1).totalAmount()).isEqualByComparingTo("200");
}

private static BigDecimal bd(String value) {
    return new BigDecimal(value);
}
```

액션 매핑 테스트는 각 상태를 mock한 `Settlement`로 만들어 다음처럼 검증한다.

```java
@ParameterizedTest
@MethodSource("adminActions")
void 주간상태별_어드민액션을_매핑한다(
        SettlementDisplayStatus status, List<String> expectedActions) {
    Settlement settlement = mock(Settlement.class);
    when(settlement.displayStatus()).thenReturn(status);
    when(settlement.getRefundAmount()).thenReturn(BigDecimal.ZERO);

    WeeklySettlement response = WeeklySettlement.from(settlement);

    assertThat(response.availableActions()).extracting(Action::type)
            .containsExactlyElementsOf(expectedActions);
}

private static Stream<Arguments> adminActions() {
    return Stream.of(
            arguments(SettlementDisplayStatus.WAITING, List.of("APPROVE", "HOLD", "CANCEL")),
            arguments(SettlementDisplayStatus.APPROVAL_ON_HOLD, List.of("RELEASE_HOLD", "CANCEL")),
            arguments(SettlementDisplayStatus.APPROVED, List.of("CANCEL")),
            arguments(SettlementDisplayStatus.PAYOUT_REQUESTED,
                    List.of("PAYOUT", "PAYOUT_HOLD", "CANCEL")),
            arguments(SettlementDisplayStatus.PAYOUT_ON_HOLD,
                    List.of("RELEASE_PAYOUT_HOLD", "CANCEL")),
            arguments(SettlementDisplayStatus.PAID, List.of()),
            arguments(SettlementDisplayStatus.CANCELLED, List.of()));
}
```

- [ ] **Step 2: Controller 실패 테스트를 월별 목록·상세·summary 계약으로 변경**

```java
@Test
void 어드민_월별목록은_v2경로와_기본20을_유지한다() throws Exception {
    when(settlementUseCase.getList(any()))
            .thenReturn(new SettlementListResponse(List.of(), 0, 0, 20));

    mockMvc.perform(get("/api/v2/admin/settlements").param("settlementMonth", "2026-07"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.size").value(20));
}

@Test
void 어드민_판매자월_상세를_조회한다() throws Exception {
    when(settlementUseCase.getDetail(SELLER_ID, YearMonth.of(2026, 7)))
            .thenReturn(emptyDetail(SELLER_ID, "2026-07"));

    mockMvc.perform(get("/api/v2/admin/settlements/sellers/{sellerId}/months/2026-07", SELLER_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.sellerId").value(SELLER_ID.toString()))
            .andExpect(jsonPath("$.data.settlementMonth").value("2026-07"));
}

@Test
void 어드민_summary는_선택월을_받는다() throws Exception {
    when(settlementUseCase.getSummary(YearMonth.of(2026, 7)))
            .thenReturn(new SettlementSummaryResponse(List.of()));

    mockMvc.perform(get("/api/v2/admin/settlements/summary")
                    .param("settlementMonth", "2026-07"))
            .andExpect(status().isOk());
}

@ParameterizedTest
@CsvSource({"page,-1", "size,0", "size,101"})
void 잘못된_페이지요청은_400이다(String name, String value) throws Exception {
    mockMvc.perform(get("/api/v2/admin/settlements").param(name, value))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("A-001"));
}

private static SettlementDetailResponse emptyDetail(UUID sellerId, String settlementMonth) {
    return new SettlementDetailResponse(
            sellerId, null, settlementMonth, 0, 0, 0,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            List.of(), List.of());
}
```

- [ ] **Step 3: RED 확인**

Run:

```bash
./gradlew :admin-service:test \
  --tests '*SettlementApplicationServiceTest' \
  --tests '*SettlementControllerTest'
```

Expected: FAIL. 월별 응답, 상세 use case, 판매자명 포트 사용과 summary month 시그니처가 연결되지 않았다.

- [ ] **Step 4: query DTO와 use case 시그니처 변경**

```java
public record SettlementListQuery(
        SettlementDisplayStatus status,
        YearMonth settlementMonth,
        int page,
        int size
) {
}
```

```java
SettlementListResponse getList(SettlementListQuery query);

SettlementDetailResponse getDetail(UUID sellerId, YearMonth settlementMonth);

SettlementSummaryResponse getSummary(YearMonth settlementMonth);
```

기존 상태 전이 use case 시그니처는 그대로 둔다.

- [ ] **Step 5: 어드민 공통 월별 응답과 상세 응답 구현**

`SettlementMonthlyResponse`는 실제 7상태 count와 주간 행을 다음 계약으로 매핑한다.

```java
public final class SettlementMonthlyResponse {

    private SettlementMonthlyResponse() {
    }

    public record StatusCount(String status, String statusLabel, long count) {
        static StatusCount from(MonthlyStatusCount count) {
            return new StatusCount(count.status().name(), count.status().getLabel(), count.count());
        }
    }

    public static List<StatusCount> statusCounts(
            MonthlyKey key, List<MonthlyStatusCount> allCounts) {
        return allCounts.stream()
                .filter(count -> count.key().equals(key))
                .sorted(Comparator.comparingInt(count -> count.status().ordinal()))
                .map(StatusCount::from)
                .toList();
    }

    public record Action(String type, String label) {
    }

    public record WeeklySettlement(
            UUID settlementId,
            LocalDate periodStart,
            LocalDate periodEnd,
            int salesCount,
            BigDecimal grossAmount,
            BigDecimal feeAmount,
            BigDecimal refundAmount,
            BigDecimal payoutAmount,
            String status,
            String statusLabel,
            LocalDateTime calculatedAt,
            LocalDateTime approvedAt,
            LocalDateTime payoutRequestedAt,
            LocalDateTime paidAt,
            LocalDateTime cancelledAt,
            List<Action> availableActions
    ) {
        public static WeeklySettlement from(Settlement settlement) {
            SettlementDisplayStatus status = settlement.displayStatus();
            return new WeeklySettlement(
                    settlement.getSettlementId(), settlement.getPeriodStart(), settlement.getPeriodEnd(),
                    settlement.getProductCount(), settlement.getTotalAmount(), settlement.getFeeTotalAmount(),
                    zeroIfNull(settlement.getRefundAmount()), settlement.getSettlementTotalAmount(),
                    status.name(), status.getLabel(), settlement.getCalculatedAt(), settlement.getApprovedAt(),
                    settlement.getPayoutRequestedAt(), settlement.getPaidAt(), settlement.getCancelledAt(),
                    actions(status));
        }
    }

    private static Action action(String type, String label) {
        return new Action(type, label);
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static List<Action> actions(SettlementDisplayStatus status) {
        return switch (status) {
            case WAITING -> List.of(action("APPROVE", "승인"), action("HOLD", "승인 보류"),
                    action("CANCEL", "정산 취소"));
            case APPROVAL_ON_HOLD -> List.of(action("RELEASE_HOLD", "승인 보류 해제"),
                    action("CANCEL", "정산 취소"));
            case APPROVED -> List.of(action("CANCEL", "정산 취소"));
            case PAYOUT_REQUESTED -> List.of(action("PAYOUT", "지급 완료"),
                    action("PAYOUT_HOLD", "지급 보류"), action("CANCEL", "정산 취소"));
            case PAYOUT_ON_HOLD -> List.of(action("RELEASE_PAYOUT_HOLD", "지급 보류 해제"),
                    action("CANCEL", "정산 취소"));
            case PAID, CANCELLED -> List.of();
        };
    }
}
```

`SettlementListResponse.Item`과 상위 factory는 다음 필드를 사용한다.

```java
public record Item(
        UUID sellerId,
        String sellerName,
        String settlementMonth,
        long weeklySettlementCount,
        long aggregatedSettlementCount,
        long salesCount,
        BigDecimal grossAmount,
        BigDecimal feeAmount,
        BigDecimal refundAmount,
        BigDecimal payoutAmount,
        List<StatusCount> statusCounts
) {
    static Item from(MonthlyAggregate aggregate, List<MonthlyStatusCount> counts,
            Map<UUID, String> sellerNames) {
        return new Item(
                aggregate.key().sellerId(), sellerNames.get(aggregate.key().sellerId()),
                aggregate.key().settlementMonth().toString(), aggregate.weeklySettlementCount(),
                aggregate.aggregatedSettlementCount(), aggregate.salesCount(), aggregate.grossAmount(),
                aggregate.feeAmount(), aggregate.refundAmount(), aggregate.payoutAmount(),
                SettlementMonthlyResponse.statusCounts(aggregate.key(), counts));
    }
}

public static SettlementListResponse from(MonthlyPage page, List<MonthlyStatusCount> counts,
        Map<UUID, String> sellerNames, int pageNumber, int size) {
    List<Item> items = page.content().stream()
            .map(aggregate -> Item.from(aggregate, counts, sellerNames))
            .toList();
    return new SettlementListResponse(items, page.totalElements(), pageNumber, size);
}
```

`SettlementDetailResponse`는 다음과 같다.

```java
public record SettlementDetailResponse(
        UUID sellerId,
        String sellerName,
        String settlementMonth,
        long weeklySettlementCount,
        long aggregatedSettlementCount,
        long salesCount,
        BigDecimal grossAmount,
        BigDecimal feeAmount,
        BigDecimal refundAmount,
        BigDecimal payoutAmount,
        List<StatusCount> statusCounts,
        List<WeeklySettlement> weeklySettlements
) {
    public static SettlementDetailResponse from(MonthlyAggregate aggregate,
            List<MonthlyStatusCount> counts, List<Settlement> weeklySettlements, String sellerName) {
        return new SettlementDetailResponse(
                aggregate.key().sellerId(), sellerName, aggregate.key().settlementMonth().toString(),
                aggregate.weeklySettlementCount(), aggregate.aggregatedSettlementCount(),
                aggregate.salesCount(), aggregate.grossAmount(), aggregate.feeAmount(),
                aggregate.refundAmount(), aggregate.payoutAmount(),
                SettlementMonthlyResponse.statusCounts(aggregate.key(), counts),
                weeklySettlements.stream().map(WeeklySettlement::from).toList());
    }
}
```

월별 상위 응답에는 `settlementId`, 단일 status, `paidAt`을 넣지 않는다.

- [ ] **Step 6: 애플리케이션 서비스에 월별 조회와 판매자명 조립 추가**

```java
private final SettlementQueryRepository settlementQueryRepository;
private final SettlementMonthlyQueryRepository monthlyQueryRepository;
private final SellerNameQueryPort sellerNameQueryPort;
private final SettlementRepository settlementRepository;
private final SettlementSourceRepository settlementSourceRepository;
```

목록 구현:

```java
MonthlyPage page = monthlyQueryRepository.findMonthlyPage(
        query.status(), query.settlementMonth(), query.page(), query.size());
List<MonthlyKey> keys = page.content().stream().map(MonthlyAggregate::key).toList();
List<MonthlyStatusCount> counts = monthlyQueryRepository.findStatusCounts(keys);
List<UUID> sellerIds = keys.stream().map(MonthlyKey::sellerId).distinct().toList();
Map<UUID, String> sellerNames = sellerNameQueryPort.findNamesBySellerIds(sellerIds);
sellerIds.stream().filter(id -> !sellerNames.containsKey(id))
        .forEach(id -> log.warn("정산 판매자명 조회 누락 - sellerId={}", id));
return SettlementListResponse.from(page, counts, sellerNames, query.page(), query.size());
```

상세는 다음과 같이 한 판매자·월의 이름과 주간 행을 조립한다.

```java
@Override
public SettlementDetailResponse getDetail(UUID sellerId, YearMonth settlementMonth) {
    MonthlyAggregate aggregate = monthlyQueryRepository
            .findMonthlyAggregate(sellerId, settlementMonth)
            .orElseThrow(() -> new AdminException(AdminErrorCode.SETTLEMENT_NOT_FOUND));
    List<MonthlyStatusCount> counts =
            monthlyQueryRepository.findStatusCounts(List.of(aggregate.key()));
    List<Settlement> weekly = monthlyQueryRepository.findWeeklySettlements(sellerId, settlementMonth);
    Map<UUID, String> sellerNames = sellerNameQueryPort.findNamesBySellerIds(List.of(sellerId));
    String sellerName = sellerNames.get(sellerId);
    if (sellerName == null) {
        log.warn("정산 판매자명 조회 누락 - sellerId={}", sellerId);
    }
    return SettlementDetailResponse.from(aggregate, counts, weekly, sellerName);
}
```

summary 메서드의 parameter와 조회 호출을 다음처럼 바꾸고, 이후 기존 `toCard()` 초기화·병합·응답
생성 코드는 그대로 둔다.

```java
@Override
public SettlementSummaryResponse getSummary(YearMonth settlementMonth) {
    Map<SettlementDisplayStatus, BigDecimal> amountByCard = new EnumMap<>(SettlementDisplayStatus.class);
    Map<SettlementDisplayStatus, Long> countByCard = new EnumMap<>(SettlementDisplayStatus.class);
    for (SettlementDisplayStatus card : CARD_ORDER) {
        amountByCard.put(card, BigDecimal.ZERO);
        countByCard.put(card, 0L);
    }
    for (SettlementStatusAggregate aggregate
            : settlementQueryRepository.aggregateByStatus(settlementMonth)) {
        SettlementDisplayStatus card = aggregate.status().toCard();
        if (card == null) {
            continue;
        }
        amountByCard.merge(card, aggregate.sumSettlementTotal(), BigDecimal::add);
        countByCard.merge(card, aggregate.count(), Long::sum);
    }
    List<Card> cards = CARD_ORDER.stream()
            .map(card -> new Card(card.name(), amountByCard.get(card), countByCard.get(card)))
            .toList();
    return new SettlementSummaryResponse(cards);
}
```

- [ ] **Step 7: Controller의 v2 목록·상세·summary month와 validation 구현**

Controller에 `@Validated`를 붙인다. 목록에는 optional `settlementMonth`, `@Min(0) page`,
`@Min(1) @Max(100) size`를 추가한다. summary도 optional month를 받는다.

```java
@GetMapping("/summary")
public ApiResult<SettlementSummaryResponse> getSummary(
        @RequestParam(required = false)
        @DateTimeFormat(pattern = "yyyy-MM") YearMonth settlementMonth
) {
    return ApiResult.success(settlementUseCase.getSummary(settlementMonth));
}

@GetMapping
public ApiResult<SettlementListResponse> getList(
        @RequestParam(required = false) SettlementDisplayStatus status,
        @RequestParam(required = false)
        @DateTimeFormat(pattern = "yyyy-MM") YearMonth settlementMonth,
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
) {
    return ApiResult.success(settlementUseCase.getList(
            new SettlementListQuery(status, settlementMonth, page, size)));
}

@GetMapping("/sellers/{sellerId}/months/{settlementMonth}")
@Operation(summary = "판매자 월별 정산 상세 조회",
        description = "판매자와 정산 월에 포함된 주간 정산과 가능한 액션을 조회합니다. ADMIN 권한이 필요합니다.")
public ApiResult<SettlementDetailResponse> getDetail(
        @PathVariable UUID sellerId,
        @PathVariable @DateTimeFormat(pattern = "yyyy-MM") YearMonth settlementMonth
) {
    return ApiResult.success(settlementUseCase.getDetail(sellerId, settlementMonth));
}
```

Swagger 목록·상세·summary에 200·400·401·403·404를 실제 응답 타입과 함께 기록한다.

- [ ] **Step 8: 사용하지 않는 기존 주간 admin page 계약 제거**

Task 5 전환 뒤 다음을 제거한다.

```text
SettlementQueryRepository.findPage(...)
SettlementQueryRepository.SettlementPage
SettlementQueryRepositoryAdapter.findPage(...)
SettlementQueryRepositoryAdapter.statusSpec(...)
```

`SettlementQueryJpaRepository`의 `JpaSpecificationExecutor` 상속도 다른 메서드에서 사용하지 않으면
제거한다. 기존 전체 summary용 `aggregateByStatus()`는 새 `aggregateByStatus(YearMonth)` adapter가
month null일 때 사용하므로 유지한다.

- [ ] **Step 9: 어드민 월별·상태 전이 회귀 테스트 실행**

Run:

```bash
./gradlew :admin-service:test \
  --tests '*SettlementQueryRepositoryAdapterTest' \
  --tests '*SellerNameQueryAdapterTest' \
  --tests '*SettlementApplicationServiceTest' \
  --tests '*SettlementControllerTest' \
  --tests '*SettlementTransitionTest'
```

Expected: BUILD SUCCESSFUL. 월별 조회·summary와 기존 상태 전이·취소가 모두 PASS.

- [ ] **Step 10: Task 5 범위 stage·검증·커밋**

```bash
git add admin-service/src/main/java/com/prompthub/admin/settlement
git add admin-service/src/test/java/com/prompthub/admin/settlement
git diff --cached --check
git diff --cached --name-status
git commit -m "feat: 어드민 월별 정산 상세 API 추가 (#462)"
```

Expected: `admin.settlement` main/test만 포함하고 `admin.user` 파일은 수정하지 않는다.

---

### Task 6: 전체 계약·회귀·변경 범위 검증

**Files:**
- Verify only: Task 1~5에서 변경한 user/admin settlement 파일
- Verify only: `settlement-service/docs/superpowers/specs/2026-07-21-monthly-settlement-query-design.md`

**Interfaces:**
- Consumes: Task 1~5의 판매자·어드민 월별 API
- Produces: PR에 올릴 수 있는 검증 완료 브랜치

- [ ] **Step 1: 금지된 월별 저장 모델과 범위 밖 변경이 없는지 확인**

```bash
rg -n 'MonthlyPayout|monthly_payout|monthly_settlement_id|monthlyPayoutId|payoutMonth|cutoffAt' \
  user-service/src/main/java/com/prompthub/user/sellersettlement \
  admin-service/src/main/java/com/prompthub/admin/settlement
git diff --name-only origin/develop...HEAD
```

Expected: 첫 명령은 exit 1, 출력 없음. diff는 허용된 settlement main/test와
`settlement-service/docs/superpowers` 문서, 브랜치에 의도적으로 들고 온 기존 두 커밋 파일만 포함한다.

- [ ] **Step 2: API 응답 필드와 경로를 정적 확인**

```bash
rg -n 'settlementMonth|weeklySettlementCount|aggregatedSettlementCount|statusCounts|weeklySettlements' \
  user-service/src/main/java/com/prompthub/user/sellersettlement \
  admin-service/src/main/java/com/prompthub/admin/settlement
rg -n '/api/v1/admin/settlements|payoutMonth|monthlyPayoutId' \
  user-service/src/main/java/com/prompthub/user/sellersettlement \
  admin-service/src/main/java/com/prompthub/admin/settlement
```

Expected: 첫 명령은 목록·상세 DTO와 Controller에서 새 계약을 찾는다. 두 번째 명령은 exit 1, 출력 없음.

- [ ] **Step 3: 두 모듈 전체 테스트**

```bash
./gradlew :user-service:test :admin-service:test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: 컴파일·checkstyle·diff 검증**

```bash
./gradlew :user-service:compileJava :admin-service:compileJava
./gradlew :user-service:checkstyleMain :admin-service:checkstyleMain
git diff --check origin/develop...HEAD
git status --short --branch
```

Expected: 모든 Gradle 명령 BUILD SUCCESSFUL, diff 공백 오류 없음, 작업 트리 clean.

- [ ] **Step 5: 커밋 이력과 미추적 파일 확인**

```bash
git log --oneline --decorate origin/develop..HEAD
git status --short --untracked-files=all
```

Expected: Task별 커밋과 기존 `cb22a5fb (커밋)`, `6a9ea302 (커밋)`, 설계 문서 커밋이 보이고 미추적 파일이 없다.

- [ ] **Step 6: 검증 종료 시 작업 트리가 비어 있는지 최종 확인**

```bash
git status --porcelain=v1 --untracked-files=all
```

Expected: 출력 없음. 검증 중 수정이 생겼다면 이 단계에서 임의 커밋하지 않고 해당 변경을 만든 Task의
실패 테스트 단계로 돌아가 수정·테스트·범위 검증·Task 커밋을 다시 수행한 뒤 Task 6 전체를 재실행한다.
