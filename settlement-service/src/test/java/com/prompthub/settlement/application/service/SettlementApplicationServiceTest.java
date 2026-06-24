package com.prompthub.settlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.when;

import com.prompthub.settlement.application.dto.SettlementListQuery;
import com.prompthub.settlement.application.dto.SettlementListResult;
import com.prompthub.settlement.application.dto.SettlementSummaryResult;
import com.prompthub.settlement.domain.model.Settlement;
import com.prompthub.settlement.domain.model.SettlementDetail;
import com.prompthub.settlement.domain.model.enums.PayoutStatus;
import com.prompthub.settlement.domain.model.enums.SettlementDisplayStatus;
import com.prompthub.settlement.domain.model.enums.SettlementStatus;
import com.prompthub.settlement.domain.repository.SettlementListQueryRepository;
import com.prompthub.settlement.domain.repository.SettlementListQueryRepository.SettlementPage;
import com.prompthub.settlement.domain.repository.SettlementStatusAggregate;
import com.prompthub.settlement.domain.repository.SettlementSummaryQueryRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SettlementApplicationServiceTest {

    @Mock
    private SettlementSummaryQueryRepository settlementSummaryQueryRepository;

    @Mock
    private SettlementListQueryRepository settlementListQueryRepository;

    @InjectMocks
    private SettlementApplicationService settlementApplicationService;

    private Settlement settlement(UUID sellerId) {
        SettlementDetail detail = SettlementDetail.sale(
                UUID.randomUUID(), new BigDecimal("100.00"), new BigDecimal("0.15"),
                LocalDateTime.of(2026, 6, 15, 10, 0));
        return Settlement.create(UUID.randomUUID(), sellerId, YearMonth.of(2026, 6), List.of(detail));
    }

    @Test
    @DisplayName("요약: 상태쌍 집계를 카드 버킷으로 접어 고정 4카드를 순서대로 반환한다")
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

        SettlementSummaryResult result = settlementApplicationService.getSummary();

        assertThat(result.cards())
                .extracting(c -> c.status(), c -> c.totalAmount().stripTrailingZeros(), c -> c.count())
                .containsExactly(
                        tuple(SettlementDisplayStatus.WAITING, new BigDecimal("1135500").stripTrailingZeros(), 4L),
                        tuple(SettlementDisplayStatus.APPROVED, new BigDecimal("1448500").stripTrailingZeros(), 4L),
                        tuple(SettlementDisplayStatus.PAYOUT_ON_HOLD, new BigDecimal("757000").stripTrailingZeros(), 2L),
                        tuple(SettlementDisplayStatus.PAID, new BigDecimal("1224000").stripTrailingZeros(), 4L));
    }

    @Test
    @DisplayName("요약: APPROVED와 PAYOUT_REQUESTED는 같은 승인 완료 카드로 합산된다")
    void getSummary_mergesApprovedAndPayoutRequestedIntoApprovedCard() {
        given(settlementSummaryQueryRepository.aggregateByStatus()).willReturn(List.of(
                new SettlementStatusAggregate(SettlementStatus.APPROVED, PayoutStatus.NOT_READY,
                        new BigDecimal("1000000"), 3L),
                new SettlementStatusAggregate(SettlementStatus.APPROVED, PayoutStatus.PAYOUT_REQUESTED,
                        new BigDecimal("250000"), 1L)));

        SettlementSummaryResult result = settlementApplicationService.getSummary();

        SettlementSummaryResult.Card approved = result.cards().stream()
                .filter(card -> card.status() == SettlementDisplayStatus.APPROVED)
                .findFirst()
                .orElseThrow();
        assertThat(approved.totalAmount()).isEqualByComparingTo("1250000");
        assertThat(approved.count()).isEqualTo(4L);
    }

    @Test
    @DisplayName("요약: 취소(CANCELLED)는 어느 카드에도 합산되지 않는다")
    void getSummary_excludesCancelled() {
        given(settlementSummaryQueryRepository.aggregateByStatus()).willReturn(List.of(
                new SettlementStatusAggregate(SettlementStatus.CANCELLED, PayoutStatus.NOT_READY,
                        new BigDecimal("178000"), 2L)));

        SettlementSummaryResult result = settlementApplicationService.getSummary();

        assertThat(result.cards()).extracting(c -> c.status())
                .containsExactly(SettlementDisplayStatus.WAITING, SettlementDisplayStatus.APPROVED,
                        SettlementDisplayStatus.PAYOUT_ON_HOLD, SettlementDisplayStatus.PAID);
        assertThat(result.cards()).allMatch(c -> c.count() == 0L);
        assertThat(result.cards()).allMatch(c -> c.totalAmount().signum() == 0);
    }

    @Test
    @DisplayName("요약: 집계가 비어도 0건 4카드를 반환한다")
    void getSummary_emptyAggregates_returnsZeroCards() {
        given(settlementSummaryQueryRepository.aggregateByStatus()).willReturn(List.of());

        SettlementSummaryResult result = settlementApplicationService.getSummary();

        assertThat(result.cards()).hasSize(4);
        assertThat(result.cards()).allMatch(c -> c.count() == 0L && c.totalAmount().signum() == 0);
    }

    @Test
    @DisplayName("목록: 조회한 정산을 응답 항목으로 매핑하고 페이징 정보를 조립한다")
    void getList_mapsSettlementsAndAssemblesPaging() {
        UUID sellerA = UUID.randomUUID();
        UUID sellerB = UUID.randomUUID();
        when(settlementListQueryRepository.findPage(SettlementDisplayStatus.WAITING, 0, 20))
                .thenReturn(new SettlementPage(List.of(settlement(sellerA), settlement(sellerB)), 5L));

        SettlementListResult result = settlementApplicationService.getList(
                new SettlementListQuery(SettlementDisplayStatus.WAITING, 0, 20));

        assertThat(result.totalElements()).isEqualTo(5L);
        assertThat(result.page()).isEqualTo(0);
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.items()).hasSize(2);

        SettlementListResult.Item first = result.items().get(0);
        assertThat(first.sellerId()).isEqualTo(sellerA);
        assertThat(first.productCount()).isEqualTo(1);
        assertThat(first.totalAmount()).isEqualByComparingTo("100.00");
        assertThat(first.feeTotalAmount()).isEqualByComparingTo("15.00");
        assertThat(first.settlementTotalAmount()).isEqualByComparingTo("85.00");
        assertThat(first.displayStatus()).isEqualTo(SettlementDisplayStatus.WAITING);
        assertThat(result.items().get(1).sellerId()).isEqualTo(sellerB);
    }
}
