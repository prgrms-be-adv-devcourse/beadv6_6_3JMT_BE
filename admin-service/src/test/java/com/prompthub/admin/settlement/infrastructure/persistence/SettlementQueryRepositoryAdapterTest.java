package com.prompthub.admin.settlement.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.admin.settlement.domain.model.Settlement;
import com.prompthub.admin.settlement.domain.model.enums.SettlementDisplayStatus;
import com.prompthub.admin.settlement.domain.repository.SettlementMonthlyQueryRepository;
import com.prompthub.admin.settlement.domain.repository.SettlementMonthlyQueryRepository.MonthlyAggregate;
import com.prompthub.admin.settlement.domain.repository.SettlementMonthlyQueryRepository.MonthlyKey;
import com.prompthub.admin.settlement.domain.repository.SettlementMonthlyQueryRepository.MonthlyPage;
import com.prompthub.admin.settlement.domain.repository.SettlementMonthlyQueryRepository.MonthlyStatusCount;
import com.prompthub.admin.settlement.domain.repository.SettlementQueryRepository;
import com.prompthub.admin.settlement.domain.repository.SettlementStatusAggregate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@Import({SettlementQueryRepositoryAdapter.class,
        SettlementMonthlyQueryRepositoryAdapter.class})
@ActiveProfiles("test")
class SettlementQueryRepositoryAdapterTest {

    @Autowired
    private SettlementQueryRepository settlementQueryRepository;

    @Autowired
    private SettlementMonthlyQueryRepository monthlyQueryRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void 판매자와_월로_그룹하고_취소행은_합계에서_제외한다() {
        UUID sellerId = UUID.randomUUID();
        insert(sellerId, LocalDate.of(2026, 6, 29), 10,
                "1000000", "150000", "0", "850000",
                SettlementDisplayStatus.APPROVED);
        insert(sellerId, LocalDate.of(2026, 7, 6), 12,
                "1200000", "180000", "100000", "920000",
                SettlementDisplayStatus.PAID);
        insert(sellerId, LocalDate.of(2026, 7, 13), 5,
                "500000", "75000", "0", "425000",
                SettlementDisplayStatus.CANCELLED);

        MonthlyPage page = monthlyQueryRepository.findMonthlyPage(
                null, YearMonth.of(2026, 7), 0, 20);
        MonthlyAggregate aggregate = page.content().getFirst();

        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(aggregate.key())
                .isEqualTo(new MonthlyKey(sellerId, YearMonth.of(2026, 7)));
        assertThat(aggregate.weeklySettlementCount()).isEqualTo(3);
        assertThat(aggregate.aggregatedSettlementCount()).isEqualTo(2);
        assertThat(aggregate.salesCount()).isEqualTo(22);
        assertThat(aggregate.grossAmount()).isEqualByComparingTo("2200000");
        assertThat(aggregate.feeAmount()).isEqualByComparingTo("330000");
        assertThat(aggregate.refundAmount()).isEqualByComparingTo("100000");
        assertThat(aggregate.payoutAmount()).isEqualByComparingTo("1770000");
        assertThat(monthlyQueryRepository.findStatusCounts(List.of(aggregate.key())))
                .extracting(MonthlyStatusCount::status)
                .containsExactly(
                        SettlementDisplayStatus.APPROVED,
                        SettlementDisplayStatus.PAID,
                        SettlementDisplayStatus.CANCELLED);
    }

    @Test
    void 상태필터는_판매자월_그룹만_고르고_전체월_합계를_유지한다() {
        UUID sellerId = UUID.randomUUID();
        insert(sellerId, LocalDate.of(2026, 6, 29), 10,
                "1000000", "150000", "0", "850000",
                SettlementDisplayStatus.APPROVED);
        insert(sellerId, LocalDate.of(2026, 7, 6), 12,
                "1200000", "180000", "100000", "920000",
                SettlementDisplayStatus.PAID);

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
        assertThat(page.content()).extracting(MonthlyAggregate::key)
                .containsExactly(
                        new MonthlyKey(sellerA, YearMonth.of(2026, 8)),
                        new MonthlyKey(sellerA, YearMonth.of(2026, 7)),
                        new MonthlyKey(sellerB, YearMonth.of(2026, 7)));
        assertThat(monthlyQueryRepository.findMonthlyPage(null, null, 1, 2).content())
                .extracting(MonthlyAggregate::key)
                .containsExactly(new MonthlyKey(sellerB, YearMonth.of(2026, 7)));
    }

    @Test
    void 상세는_판매자월_전체주간을_기간순으로_조회하고_없는조합은_비어있다() {
        UUID sellerId = UUID.randomUUID();
        insert(sellerId, LocalDate.of(2026, 7, 6), 1,
                "200", "30", "0", "170", SettlementDisplayStatus.PAID);
        insert(sellerId, LocalDate.of(2026, 6, 29), 1,
                "100", "15", "0", "85", SettlementDisplayStatus.APPROVED);

        assertThat(monthlyQueryRepository.findMonthlyAggregate(
                sellerId, YearMonth.of(2026, 7))).isPresent();
        assertThat(monthlyQueryRepository.findWeeklySettlements(
                sellerId, YearMonth.of(2026, 7)))
                .extracting(Settlement::getPeriodStart)
                .containsExactly(
                        LocalDate.of(2026, 6, 29),
                        LocalDate.of(2026, 7, 6));
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
                .containsExactlyInAnyOrder(
                        SettlementDisplayStatus.WAITING,
                        SettlementDisplayStatus.PAID);
        assertThat(result).extracting(aggregate ->
                        aggregate.sumSettlementTotal().stripTrailingZeros().toPlainString())
                .containsExactlyInAnyOrder("85", "170");
        assertThat(settlementQueryRepository.aggregateByStatus(null))
                .extracting(SettlementStatusAggregate::status)
                .containsExactlyInAnyOrder(
                        SettlementDisplayStatus.WAITING,
                        SettlementDisplayStatus.PAID,
                        SettlementDisplayStatus.APPROVED);
    }

    @Test
    void 연도경계와_null환불액을_같은규칙으로_집계한다() {
        UUID sellerId = UUID.randomUUID();
        insert(sellerId, LocalDate.of(2025, 12, 29), 1,
                "100", "15", null, "85", SettlementDisplayStatus.WAITING);

        MonthlyPage page = monthlyQueryRepository.findMonthlyPage(null, null, 0, 20);

        assertThat(page.content()).singleElement().satisfies(aggregate -> {
            assertThat(aggregate.key())
                    .isEqualTo(new MonthlyKey(sellerId, YearMonth.of(2026, 1)));
            assertThat(aggregate.refundAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        });
    }

    private void insert(
            UUID sellerId,
            LocalDate periodStart,
            int salesCount,
            String gross,
            String fee,
            String refund,
            String payout,
            SettlementDisplayStatus status) {
        entityManager.getEntityManager().createNativeQuery("""
                INSERT INTO seller_settlement (
                    seller_settlement_id, settlement_id, seller_id,
                    period_start, period_end, product_count, total_amount,
                    settlement_total_amount, fee_total_amount, refund_amount,
                    calculated_at, status)
                VALUES (
                    :rowId, :settlementId, :sellerId,
                    :periodStart, :periodEnd, :salesCount, :gross,
                    :payout, :fee, :refund, :calculatedAt, :status)
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
                .setParameter("refund", refund == null ? null : new BigDecimal(refund))
                .setParameter("calculatedAt", periodStart.plusDays(7).atStartOfDay())
                .setParameter("status", status.name())
                .executeUpdate();
    }
}
