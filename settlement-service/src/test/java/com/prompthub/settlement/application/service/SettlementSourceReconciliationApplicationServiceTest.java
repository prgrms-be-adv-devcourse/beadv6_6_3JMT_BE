package com.prompthub.settlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

import com.prompthub.settlement.application.dto.SettleableLine;
import com.prompthub.settlement.application.dto.SettlementSourceReconciliationResult;
import com.prompthub.settlement.application.port.OrderSettlementQuery;
import com.prompthub.settlement.domain.model.SettlementPeriod;
import com.prompthub.settlement.domain.model.enums.SettlementSourceLineType;
import com.prompthub.settlement.domain.repository.SettlementSourceAggregate;
import com.prompthub.settlement.domain.repository.SettlementSourceAggregateRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SettlementSourceReconciliationApplicationServiceTest {

    private static final SettlementPeriod PERIOD = SettlementPeriod.of(
            LocalDate.of(2026, 7, 20),
            LocalDate.of(2026, 7, 26));

    private OrderSettlementQuery orderSettlementQuery;
    private SettlementSourceAggregateRepository settlementSourceAggregateRepository;
    private SettlementSourceReconciliationApplicationService service;

    @BeforeEach
    void setUp() {
        orderSettlementQuery = mock(OrderSettlementQuery.class);
        settlementSourceAggregateRepository = mock(SettlementSourceAggregateRepository.class);
        service = new SettlementSourceReconciliationApplicationService(
                orderSettlementQuery,
                settlementSourceAggregateRepository);
    }

    @Test
    @DisplayName("PAID와 REFUND 건수·금액이 모두 일치하면 대사에 성공한다")
    void reconcile_matchingAggregates_returnsMatched() {
        given(orderSettlementQuery.fetchSettleableLines(PERIOD)).willReturn(List.of(
                line(SettlementSourceLineType.PAID, "1000"),
                line(SettlementSourceLineType.PAID, "2000"),
                line(SettlementSourceLineType.REFUND, "500")));
        given(settlementSourceAggregateRepository.aggregate(PERIOD))
                .willReturn(aggregate(2, "3000.00", 1, "500.0"));

        SettlementSourceReconciliationResult result = service.reconcile(PERIOD);

        assertThat(result.matched()).isTrue();
        assertThat(result.failureReason()).isNull();
        assertThat(result.orderAggregate()).isEqualTo(aggregate(2, "3000", 1, "500"));
        assertThat(result.sourceAggregate()).isEqualTo(aggregate(2, "3000.00", 1, "500.0"));
    }

    @Test
    @DisplayName("PAID 건수가 다르면 대사에 실패하고 기대값과 실제값을 남긴다")
    void reconcile_paidCountMismatch_returnsMismatch() {
        given(orderSettlementQuery.fetchSettleableLines(PERIOD)).willReturn(List.of(
                line(SettlementSourceLineType.PAID, "1000"),
                line(SettlementSourceLineType.PAID, "2000")));
        given(settlementSourceAggregateRepository.aggregate(PERIOD))
                .willReturn(aggregate(1, "3000", 0, "0"));

        SettlementSourceReconciliationResult result = service.reconcile(PERIOD);

        assertThat(result.matched()).isFalse();
        assertThat(result.failureReason()).isEqualTo("PAID_COUNT(order=2, source=1)");
    }

    @Test
    @DisplayName("PAID 금액이 다르면 대사에 실패한다")
    void reconcile_paidAmountMismatch_returnsMismatch() {
        given(orderSettlementQuery.fetchSettleableLines(PERIOD))
                .willReturn(List.of(line(SettlementSourceLineType.PAID, "3000")));
        given(settlementSourceAggregateRepository.aggregate(PERIOD))
                .willReturn(aggregate(1, "2999", 0, "0"));

        SettlementSourceReconciliationResult result = service.reconcile(PERIOD);

        assertThat(result.matched()).isFalse();
        assertThat(result.failureReason()).isEqualTo("PAID_AMOUNT(order=3000, source=2999)");
    }

    @Test
    @DisplayName("REFUND 건수와 금액이 모두 다르면 두 불일치를 순서대로 남긴다")
    void reconcile_refundCountAndAmountMismatch_returnsBothReasons() {
        given(orderSettlementQuery.fetchSettleableLines(PERIOD)).willReturn(List.of(
                line(SettlementSourceLineType.REFUND, "100"),
                line(SettlementSourceLineType.REFUND, "200")));
        given(settlementSourceAggregateRepository.aggregate(PERIOD))
                .willReturn(aggregate(0, "0", 1, "250"));

        SettlementSourceReconciliationResult result = service.reconcile(PERIOD);

        assertThat(result.matched()).isFalse();
        assertThat(result.failureReason()).isEqualTo(
                "REFUND_COUNT(order=2, source=1); REFUND_AMOUNT(order=300, source=250)");
    }

    @Test
    @DisplayName("Order와 저장 원천이 모두 비어 있으면 0건·0원으로 대사에 성공한다")
    void reconcile_emptySources_returnsMatchedZeroAggregate() {
        given(orderSettlementQuery.fetchSettleableLines(PERIOD)).willReturn(List.of());
        given(settlementSourceAggregateRepository.aggregate(PERIOD))
                .willReturn(SettlementSourceAggregate.zero());

        SettlementSourceReconciliationResult result = service.reconcile(PERIOD);

        assertThat(result.matched()).isTrue();
        assertThat(result.orderAggregate()).isEqualTo(SettlementSourceAggregate.zero());
        assertThat(result.sourceAggregate()).isEqualTo(SettlementSourceAggregate.zero());
    }

    @Test
    @DisplayName("동일 기간을 다시 대사하면 저장 상태를 바꾸지 않고 같은 결과를 반환한다")
    void reconcile_samePeriodTwice_returnsSameResult() {
        List<SettleableLine> orderLines = List.of(
                line(SettlementSourceLineType.PAID, "1000"),
                line(SettlementSourceLineType.REFUND, "200"));
        SettlementSourceAggregate sourceAggregate = aggregate(1, "900", 1, "200");
        given(orderSettlementQuery.fetchSettleableLines(PERIOD)).willReturn(orderLines);
        given(settlementSourceAggregateRepository.aggregate(PERIOD))
                .willReturn(sourceAggregate);

        SettlementSourceReconciliationResult first = service.reconcile(PERIOD);
        SettlementSourceReconciliationResult second = service.reconcile(PERIOD);

        assertThat(second).isEqualTo(first);
        assertThat(second.failureReason())
                .isEqualTo("PAID_AMOUNT(order=1000, source=900)");
        then(orderSettlementQuery).should(times(2)).fetchSettleableLines(PERIOD);
        then(settlementSourceAggregateRepository).should(times(2)).aggregate(PERIOD);
    }

    private SettleableLine line(SettlementSourceLineType lineType, String amount) {
        return new SettleableLine(
                lineType,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal(amount),
                LocalDateTime.of(2026, 7, 22, 12, 0));
    }

    private SettlementSourceAggregate aggregate(
            long paidCount,
            String paidAmount,
            long refundCount,
            String refundAmount) {
        return new SettlementSourceAggregate(
                paidCount,
                new BigDecimal(paidAmount),
                refundCount,
                new BigDecimal(refundAmount));
    }
}
