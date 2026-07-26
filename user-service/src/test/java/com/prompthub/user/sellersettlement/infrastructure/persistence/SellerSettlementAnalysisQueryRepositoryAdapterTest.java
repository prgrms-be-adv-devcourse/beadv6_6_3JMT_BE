package com.prompthub.user.sellersettlement.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.prompthub.user.global.config.JpaConfig;
import com.prompthub.user.sellersettlement.domain.model.SellerSettlement;
import com.prompthub.user.sellersettlement.domain.model.SellerSettlementDetail;
import com.prompthub.user.sellersettlement.domain.model.enums.SellerSettlementLineType;
import com.prompthub.user.sellersettlement.domain.model.enums.SettlementDisplayStatus;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementAnalysisQueryRepository;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementAnalysisQueryRepository.AnalysisAggregate;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementAnalysisQueryRepository.AnalysisQueryRange;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementAnalysisQueryRepository.PayoutStatusCount;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementAnalysisQueryRepository.PayoutStatusSnapshot;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementAnalysisQueryRepository.WeeklyAnalysisAggregate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
@Import({SellerSettlementAnalysisQueryRepositoryAdapter.class, JpaConfig.class})
@ActiveProfiles("test")
@DisplayName("판매자 정산 분석 JDBC 저장소")
class SellerSettlementAnalysisQueryRepositoryAdapterTest {

    @Autowired
    private SellerSettlementAnalysisQueryRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    @DisplayName("seller를 격리하고 완료 Detail 합계와 주차 합계를 맞추며 취소 상태는 보존한다")
    void isolatesSellerAndMatchesCompletedMonthlyBreakdown() {
        UUID sellerId = UUID.randomUUID();
        persistWeek(sellerId, LocalDate.of(2026, 6, 29), SettlementDisplayStatus.APPROVED,
                List.of(
                        detail(SellerSettlementLineType.SALE, "900", "135", "765", "2026-06-30T10:00:00"),
                        detail(SellerSettlementLineType.SALE, "100", "15", "85", "2026-07-01T10:00:00"),
                        detail(SellerSettlementLineType.REFUND, "-40", "-6", "-34", "2026-07-02T10:00:00")));
        persistWeek(sellerId, LocalDate.of(2026, 7, 6), SettlementDisplayStatus.PAID,
                List.of(detail(
                        SellerSettlementLineType.SALE, "200", "30", "170", "2026-07-08T10:00:00")));
        persistWeek(sellerId, LocalDate.of(2026, 7, 13), SettlementDisplayStatus.CANCELLED,
                List.of(detail(
                        SellerSettlementLineType.SALE, "500", "75", "425", "2026-07-15T10:00:00")));
        persistWeek(sellerId, LocalDate.of(2026, 7, 20), SettlementDisplayStatus.APPROVED,
                List.of(detail(
                        SellerSettlementLineType.SALE, "1000", "150", "850", "2026-07-21T10:00:00")));
        persistWeek(UUID.randomUUID(), LocalDate.of(2026, 7, 6), SettlementDisplayStatus.PAID,
                List.of(detail(
                        SellerSettlementLineType.SALE, "9999", "999", "9000", "2026-07-08T10:00:00")));
        entityManager.flush();
        entityManager.clear();

        AnalysisQueryRange julyThroughCompletedWeek = new AnalysisQueryRange(
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 19),
                LocalDate.of(2026, 7, 19));
        AnalysisAggregate aggregate = repository.aggregate(sellerId, julyThroughCompletedWeek);
        List<WeeklyAnalysisAggregate> breakdown = repository.findWeeklyBreakdown(
                sellerId,
                YearMonth.of(2026, 7),
                LocalDate.of(2026, 7, 19),
                LocalDate.of(2026, 7, 19));

        assertAggregate(aggregate, 2, 1, "300", "40", "45", "6", "39", "221");
        assertThat(breakdown).hasSize(2);
        assertThat(breakdown.getFirst().weekStart()).isEqualTo(LocalDate.of(2026, 6, 29));
        assertThat(breakdown.getFirst().includedStart()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(breakdown.getFirst().boundaryWeek()).isTrue();
        assertThat(breakdown.stream()
                .map(WeeklyAnalysisAggregate::aggregate)
                .map(AnalysisAggregate::payoutAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(aggregate.payoutAmount());

        PayoutStatusSnapshot payout = repository.findPayoutStatuses(
                sellerId, YearMonth.of(2026, 7));
        assertThat(payout.counts())
                .extracting(PayoutStatusCount::status, PayoutStatusCount::count)
                .containsExactly(
                        tuple(SettlementDisplayStatus.APPROVED, 2L),
                        tuple(SettlementDisplayStatus.PAID, 1L),
                        tuple(SettlementDisplayStatus.CANCELLED, 1L));
        assertThat(payout.weeklySettlements())
                .extracting(row -> row.periodStart(), row -> row.status())
                .containsExactly(
                        tuple(LocalDate.of(2026, 6, 29), SettlementDisplayStatus.APPROVED),
                        tuple(LocalDate.of(2026, 7, 6), SettlementDisplayStatus.PAID),
                        tuple(LocalDate.of(2026, 7, 13), SettlementDisplayStatus.CANCELLED),
                        tuple(LocalDate.of(2026, 7, 20), SettlementDisplayStatus.APPROVED));
    }

    private void assertAggregate(
            AnalysisAggregate aggregate,
            long saleCount,
            long refundCount,
            String grossSaleAmount,
            String grossRefundAmount,
            String saleFeeAmount,
            String refundedFeeAmount,
            String netFeeAmount,
            String payoutAmount) {
        assertThat(aggregate.saleCount()).isEqualTo(saleCount);
        assertThat(aggregate.refundCount()).isEqualTo(refundCount);
        assertThat(aggregate.grossSaleAmount()).isEqualByComparingTo(grossSaleAmount);
        assertThat(aggregate.grossRefundAmount()).isEqualByComparingTo(grossRefundAmount);
        assertThat(aggregate.saleFeeAmount()).isEqualByComparingTo(saleFeeAmount);
        assertThat(aggregate.refundedFeeAmount()).isEqualByComparingTo(refundedFeeAmount);
        assertThat(aggregate.netFeeAmount()).isEqualByComparingTo(netFeeAmount);
        assertThat(aggregate.payoutAmount()).isEqualByComparingTo(payoutAmount);
    }

    private void persistWeek(
            UUID sellerId,
            LocalDate periodStart,
            SettlementDisplayStatus status,
            List<SellerSettlementDetail> details) {
        BigDecimal totalAmount = details.stream()
                .map(SellerSettlementDetail::getLineAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal feeAmount = details.stream()
                .map(SellerSettlementDetail::getFeeAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal payoutAmount = details.stream()
                .map(SellerSettlementDetail::getLineSettlementAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        SellerSettlement settlement = SellerSettlement.seedV2(
                UUID.randomUUID(), sellerId, periodStart, periodStart.plusDays(6), details.size(),
                totalAmount, payoutAmount, feeAmount, BigDecimal.ZERO,
                periodStart.plusDays(7).atStartOfDay(), details);
        changeStatus(settlement, status);
        entityManager.persist(settlement);
    }

    private SellerSettlementDetail detail(
            SellerSettlementLineType lineType,
            String lineAmount,
            String feeAmount,
            String payoutAmount,
            String occurredAt) {
        return SellerSettlementDetail.seed(
                UUID.randomUUID(),
                UUID.randomUUID(),
                lineType,
                new BigDecimal(lineAmount),
                new BigDecimal("0.1500"),
                new BigDecimal(feeAmount),
                new BigDecimal(payoutAmount),
                LocalDateTime.parse(occurredAt));
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
