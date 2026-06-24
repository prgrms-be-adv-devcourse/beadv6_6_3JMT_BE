package com.prompthub.settlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.when;

import com.prompthub.settlement.application.dto.SettlementListQuery;
import com.prompthub.settlement.domain.model.Settlement;
import com.prompthub.settlement.domain.model.SettlementDetail;
import com.prompthub.settlement.domain.model.enums.PayoutStatus;
import com.prompthub.settlement.domain.model.enums.SettlementDisplayStatus;
import com.prompthub.settlement.domain.model.enums.SettlementStatus;
import com.prompthub.settlement.domain.repository.SettlementQueryRepository;
import com.prompthub.settlement.domain.repository.SettlementQueryRepository.SettlementPage;
import com.prompthub.settlement.domain.repository.SettlementStatusAggregate;
import com.prompthub.settlement.presentation.dto.response.SettlementListResponse;
import com.prompthub.settlement.presentation.dto.response.SettlementSummaryResponse;
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
    private SettlementQueryRepository settlementQueryRepository;

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
        given(settlementQueryRepository.aggregateByStatus()).willReturn(List.of(
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

        SettlementSummaryResponse response = settlementApplicationService.getSummary();

        assertThat(response.cards())
                .extracting(c -> c.status(), c -> c.totalAmount().stripTrailingZeros(), c -> c.count())
                .containsExactly(
                        tuple("WAITING", new BigDecimal("1135500").stripTrailingZeros(), 4L),
                        tuple("APPROVED", new BigDecimal("1448500").stripTrailingZeros(), 4L),
                        tuple("PAYOUT_ON_HOLD", new BigDecimal("757000").stripTrailingZeros(), 2L),
                        tuple("PAID", new BigDecimal("1224000").stripTrailingZeros(), 4L));
    }

    @Test
    @DisplayName("요약: APPROVED와 PAYOUT_REQUESTED는 같은 승인 완료 카드로 합산된다")
    void getSummary_mergesApprovedAndPayoutRequestedIntoApprovedCard() {
        given(settlementQueryRepository.aggregateByStatus()).willReturn(List.of(
                new SettlementStatusAggregate(SettlementStatus.APPROVED, PayoutStatus.NOT_READY,
                        new BigDecimal("1000000"), 3L),
                new SettlementStatusAggregate(SettlementStatus.APPROVED, PayoutStatus.PAYOUT_REQUESTED,
                        new BigDecimal("250000"), 1L)));

        SettlementSummaryResponse response = settlementApplicationService.getSummary();

        SettlementSummaryResponse.Card approved = response.cards().stream()
                .filter(card -> card.status().equals("APPROVED"))
                .findFirst()
                .orElseThrow();
        assertThat(approved.totalAmount()).isEqualByComparingTo("1250000");
        assertThat(approved.count()).isEqualTo(4L);
    }

    @Test
    @DisplayName("요약: 취소(CANCELLED)는 어느 카드에도 합산되지 않는다")
    void getSummary_excludesCancelled() {
        given(settlementQueryRepository.aggregateByStatus()).willReturn(List.of(
                new SettlementStatusAggregate(SettlementStatus.CANCELLED, PayoutStatus.NOT_READY,
                        new BigDecimal("178000"), 2L)));

        SettlementSummaryResponse response = settlementApplicationService.getSummary();

        assertThat(response.cards()).extracting(c -> c.status())
                .containsExactly("WAITING", "APPROVED", "PAYOUT_ON_HOLD", "PAID");
        assertThat(response.cards()).allMatch(c -> c.count() == 0L);
        assertThat(response.cards()).allMatch(c -> c.totalAmount().signum() == 0);
    }

    @Test
    @DisplayName("요약: 집계가 비어도 0건 4카드를 반환한다")
    void getSummary_emptyAggregates_returnsZeroCards() {
        given(settlementQueryRepository.aggregateByStatus()).willReturn(List.of());

        SettlementSummaryResponse response = settlementApplicationService.getSummary();

        assertThat(response.cards()).hasSize(4);
        assertThat(response.cards()).allMatch(c -> c.count() == 0L && c.totalAmount().signum() == 0);
    }

    @Test
    @DisplayName("목록: 조회한 정산을 응답 항목으로 매핑하고 페이징 정보를 조립한다")
    void getList_mapsSettlementsAndAssemblesPaging() {
        UUID sellerA = UUID.randomUUID();
        UUID sellerB = UUID.randomUUID();
        when(settlementQueryRepository.findPage(SettlementDisplayStatus.WAITING, 0, 20))
                .thenReturn(new SettlementPage(List.of(settlement(sellerA), settlement(sellerB)), 5L));

        SettlementListResponse response = settlementApplicationService.getList(
                new SettlementListQuery(SettlementDisplayStatus.WAITING, 0, 20));

        assertThat(response.totalElements()).isEqualTo(5L);
        assertThat(response.page()).isEqualTo(0);
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.items()).hasSize(2);

        SettlementListResponse.Item first = response.items().get(0);
        assertThat(first.sellerId()).isEqualTo(sellerA);
        assertThat(first.sellerName()).isNull();
        assertThat(first.productCount()).isEqualTo(1);
        assertThat(first.totalAmount()).isEqualByComparingTo("100.00");
        assertThat(first.feeTotalAmount()).isEqualByComparingTo("15.00");
        assertThat(first.settlementTotalAmount()).isEqualByComparingTo("85.00");
        assertThat(first.displayStatus()).isEqualTo("WAITING");
        assertThat(response.items().get(1).sellerId()).isEqualTo(sellerB);
    }
}
