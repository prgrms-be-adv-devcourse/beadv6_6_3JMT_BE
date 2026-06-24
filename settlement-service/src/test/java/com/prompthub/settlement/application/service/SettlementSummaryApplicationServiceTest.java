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
                        tuple(SettlementDisplayStatus.WAITING, new BigDecimal("1135500").stripTrailingZeros(), 4L),
                        tuple(SettlementDisplayStatus.APPROVED, new BigDecimal("1448500").stripTrailingZeros(), 4L),
                        tuple(SettlementDisplayStatus.PAYOUT_ON_HOLD, new BigDecimal("757000").stripTrailingZeros(), 2L),
                        tuple(SettlementDisplayStatus.PAID, new BigDecimal("1224000").stripTrailingZeros(), 4L));
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
