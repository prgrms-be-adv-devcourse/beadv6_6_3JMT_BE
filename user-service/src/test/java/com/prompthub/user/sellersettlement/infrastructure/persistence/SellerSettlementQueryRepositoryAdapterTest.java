package com.prompthub.user.sellersettlement.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.prompthub.user.global.config.JpaConfig;
import com.prompthub.user.sellersettlement.domain.model.SellerSettlement;
import com.prompthub.user.sellersettlement.domain.model.enums.SettlementDisplayStatus;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementQueryRepository;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementQueryRepository.MonthlyAggregate;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementQueryRepository.MonthlyPage;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementQueryRepository.MonthlyStatusCount;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@Import({SellerSettlementQueryRepositoryAdapter.class, JpaConfig.class})
@ActiveProfiles("test")
@DisplayName("판매자 월별 정산 조회 저장소")
class SellerSettlementQueryRepositoryAdapterTest {

    @Autowired
    private SellerSettlementQueryRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    @DisplayName("월경계 주간은 목요일 기준으로 묶고 취소 정산은 합계에서 제외한다")
    void aggregatesByThursdayAndExcludesCancelledAmounts() {
        UUID sellerId = UUID.randomUUID();
        persist(sellerId, LocalDate.of(2026, 6, 29), 10,
                "1000000", "150000", "0", "850000", SettlementDisplayStatus.APPROVED);
        persist(sellerId, LocalDate.of(2026, 7, 6), 12,
                "1200000", "180000", "100000", "920000", SettlementDisplayStatus.PAID);
        persist(sellerId, LocalDate.of(2026, 7, 13), 5,
                "500000", "75000", "0", "425000", SettlementDisplayStatus.CANCELLED);
        persist(UUID.randomUUID(), LocalDate.of(2026, 7, 6), 99,
                "9900000", "990000", "0", "8910000", SettlementDisplayStatus.PAID);
        entityManager.flush();

        MonthlyPage page = repository.findMonthlyPage(
                sellerId, null, YearMonth.of(2026, 7), 0, 10);
        MonthlyAggregate aggregate = page.content().getFirst();

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
                .extracting(MonthlyStatusCount::status, MonthlyStatusCount::count)
                .containsExactly(
                        tuple(SettlementDisplayStatus.APPROVED, 1L),
                        tuple(SettlementDisplayStatus.PAID, 1L),
                        tuple(SettlementDisplayStatus.CANCELLED, 1L));
        assertThat(repository.findWeeklySettlements(sellerId, YearMonth.of(2026, 7)))
                .extracting(SellerSettlement::getStatus)
                .containsExactly(
                        SettlementDisplayStatus.APPROVED,
                        SettlementDisplayStatus.PAID,
                        SettlementDisplayStatus.CANCELLED);
    }

    @Test
    @DisplayName("상태 필터는 월 그룹만 고르고 합계는 월 전체를 유지한다")
    void statusFilterSelectsGroupWithoutReducingAggregate() {
        UUID sellerId = UUID.randomUUID();
        persist(sellerId, LocalDate.of(2026, 6, 29), 10,
                "1000000", "150000", "0", "850000", SettlementDisplayStatus.APPROVED);
        persist(sellerId, LocalDate.of(2026, 7, 6), 12,
                "1200000", "180000", "100000", "920000", SettlementDisplayStatus.PAID);
        entityManager.flush();

        MonthlyPage page = repository.findMonthlyPage(
                sellerId, SettlementDisplayStatus.APPROVED, null, 0, 10);

        assertThat(page.content()).singleElement().satisfies(aggregate -> {
            assertThat(aggregate.grossAmount()).isEqualByComparingTo("2200000");
            assertThat(aggregate.payoutAmount()).isEqualByComparingTo("1770000");
        });
    }

    @Test
    @DisplayName("7월 마지막 주는 7월이고 다음 주부터 8월이다")
    void assignsMonthAtEndOfMonthBoundary() {
        UUID sellerId = UUID.randomUUID();
        persist(sellerId, LocalDate.of(2026, 7, 27), 1,
                "100", "15", "0", "85", SettlementDisplayStatus.WAITING);
        persist(sellerId, LocalDate.of(2026, 8, 3), 1,
                "200", "30", "0", "170", SettlementDisplayStatus.WAITING);
        entityManager.flush();

        MonthlyPage page = repository.findMonthlyPage(sellerId, null, null, 0, 10);

        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.content()).extracting(it -> it.key().settlementMonth())
                .containsExactly(YearMonth.of(2026, 8), YearMonth.of(2026, 7));
        assertThat(repository.findMonthlyPage(sellerId, null, null, 1, 1).content())
                .extracting(it -> it.key().settlementMonth())
                .containsExactly(YearMonth.of(2026, 7));
        assertThat(repository.findWeeklySettlements(sellerId, YearMonth.of(2026, 7)))
                .extracting(SellerSettlement::getPeriodStart)
                .containsExactly(LocalDate.of(2026, 7, 27));
    }

    @Test
    @DisplayName("연도 경계 주간은 목요일의 연도로 묶고 null 환불액은 0으로 합산한다")
    void assignsMonthAtYearBoundaryAndTreatsNullRefundAsZero() {
        UUID sellerId = UUID.randomUUID();
        persist(sellerId, LocalDate.of(2025, 12, 29), 1,
                "100", "15", null, "85", SettlementDisplayStatus.WAITING);
        entityManager.flush();

        MonthlyPage page = repository.findMonthlyPage(sellerId, null, null, 0, 10);

        assertThat(page.content()).singleElement().satisfies(aggregate -> {
            assertThat(aggregate.key().settlementMonth())
                    .isEqualTo(YearMonth.of(2026, 1));
            assertThat(aggregate.refundAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        });
    }

    @Test
    @DisplayName("판매자와 월 조합이 없으면 상세 집계가 비어 있다")
    void returnsEmptyAggregateWhenSellerMonthDoesNotExist() {
        assertThat(repository.findMonthlyAggregate(
                UUID.randomUUID(), YearMonth.of(2026, 7))).isEmpty();
    }

    private void persist(UUID sellerId, LocalDate periodStart, int salesCount,
            String gross, String fee, String refund, String payout,
            SettlementDisplayStatus status) {
        SellerSettlement settlement = SellerSettlement.seed(
                UUID.randomUUID(), sellerId, periodStart, periodStart.plusDays(6), salesCount,
                new BigDecimal(gross), new BigDecimal(payout), new BigDecimal(fee),
                refund == null ? null : new BigDecimal(refund),
                periodStart.plusDays(7).atStartOfDay());
        changeStatus(settlement, status);
        entityManager.persist(settlement);
    }

    private void changeStatus(SellerSettlement settlement, SettlementDisplayStatus status) {
        switch (status) {
            case WAITING -> {
            }
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
    }
}
