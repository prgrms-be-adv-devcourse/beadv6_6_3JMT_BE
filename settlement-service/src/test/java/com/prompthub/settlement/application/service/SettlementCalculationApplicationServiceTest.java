package com.prompthub.settlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.prompthub.settlement.application.dto.CalculateSettlementCommand;
import com.prompthub.settlement.domain.model.Settlement;
import com.prompthub.settlement.domain.model.SettlementDetail;
import com.prompthub.settlement.domain.model.SettlementPeriod;
import com.prompthub.settlement.domain.model.SettlementSourceLine;
import com.prompthub.settlement.domain.repository.SettlementRepository;
import com.prompthub.settlement.domain.repository.SettlementDeliveryRepository;
import com.prompthub.settlement.domain.repository.SettlementSourceRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class SettlementCalculationApplicationServiceTest {

    private static final SettlementPeriod PERIOD = SettlementPeriod.of(
            LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 7));

    @Mock
    private SettlementSourceRepository settlementSourceRepository;

    @Mock
    private SettlementRepository settlementRepository;

    @Mock
    private SettlementDeliveryRepository settlementDeliveryRepository;

    @InjectMocks
    private SettlementCalculationApplicationService service;

    private SettlementSourceLine paidLine(UUID sellerId, String amount) {
        SettlementSourceLine line = SettlementSourceLine.paid(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                sellerId, new BigDecimal(amount), LocalDateTime.of(2026, 6, 15, 10, 0));
        ReflectionTestUtils.setField(line, "id", UUID.randomUUID());
        return line;
    }

    private SettlementSourceLine refundLine(UUID sellerId, String amount) {
        SettlementSourceLine line = SettlementSourceLine.refunded(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                sellerId, new BigDecimal(amount), LocalDateTime.of(2026, 6, 16, 10, 0));
        ReflectionTestUtils.setField(line, "id", UUID.randomUUID());
        return line;
    }

    private void stubSaveAssigningId() {
        given(settlementRepository.save(any(Settlement.class))).willAnswer(invocation -> {
            Settlement saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", UUID.randomUUID());
            return saved;
        });
    }

    @Test
    @DisplayName("PAID와 REFUND를 분리한 요약과 signed 순액으로 정산을 생성한다")
    void calculate_paidAndRefund_separatesSummaryAndNetsAmount() {
        // given
        UUID sellerId = UUID.randomUUID();
        CalculateSettlementCommand command = new CalculateSettlementCommand(UUID.randomUUID(), sellerId, PERIOD);
        given(settlementSourceRepository.findSettleableLines(sellerId, PERIOD))
                .willReturn(List.of(paidLine(sellerId, "300.00"), refundLine(sellerId, "100.00")));
        stubSaveAssigningId();

        // when
        Settlement settlement = service.calculate(command);

        // then : PAID 300(fee 45, net 255) + REFUND 100(fee -15, net -85)
        assertThat(settlement.getProductCount()).isEqualTo(1);
        assertThat(settlement.getTotalAmount()).isEqualByComparingTo("300.00");
        assertThat(settlement.getRefundAmount()).isEqualByComparingTo("100.00");
        assertThat(settlement.getFeeTotalAmount()).isEqualByComparingTo("30.00");
        assertThat(settlement.getSettlementTotalAmount()).isEqualByComparingTo("170.00");
        assertThat(settlement.getPeriodStart()).isEqualTo(PERIOD.periodStart());
        assertThat(settlement.getPeriodEnd()).isEqualTo(PERIOD.periodEnd());
        assertThat(settlement.getDetails())
                .extracting(SettlementDetail::getSettlementSourceLineId)
                .doesNotContainNull();
        verify(settlementRepository).save(settlement);
    }

    @Test
    @DisplayName("정산에 포함된 소스 라인은 생성된 정산 ID로 연결(markSettled)된다")
    void calculate_marksSourceLinesSettled() {
        // given
        UUID sellerId = UUID.randomUUID();
        CalculateSettlementCommand command = new CalculateSettlementCommand(UUID.randomUUID(), sellerId, PERIOD);
        SettlementSourceLine line = paidLine(sellerId, "100.00");
        given(settlementSourceRepository.findSettleableLines(sellerId, PERIOD)).willReturn(List.of(line));
        stubSaveAssigningId();

        // when
        Settlement settlement = service.calculate(command);

        // then
        assertThat(line.isSettled()).isTrue();
        assertThat(line.getSettlementId()).isEqualTo(settlement.getId());
    }

    @Test
    @DisplayName("정산 대상 라인이 없으면 정산을 생성·저장하지 않는다")
    void calculate_noLines_doesNotSave() {
        // given
        UUID sellerId = UUID.randomUUID();
        CalculateSettlementCommand command = new CalculateSettlementCommand(UUID.randomUUID(), sellerId, PERIOD);
        given(settlementSourceRepository.findSettleableLines(sellerId, PERIOD)).willReturn(List.of());

        // when
        Settlement settlement = service.calculate(command);

        // then
        assertThat(settlement).isNull();
        verify(settlementRepository, never()).save(any());
        then(settlementDeliveryRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("정산 저장 후 같은 트랜잭션에서 CALCULATED Delivery를 생성한다")
    void calculate_createsCalculatedDeliveryAfterSave() {
        // given
        UUID sellerId = UUID.randomUUID();
        CalculateSettlementCommand command = new CalculateSettlementCommand(UUID.randomUUID(), sellerId, PERIOD);
        given(settlementSourceRepository.findSettleableLines(sellerId, PERIOD))
                .willReturn(List.of(paidLine(sellerId, "100.00")));
        stubSaveAssigningId();

        // when
        Settlement settlement = service.calculate(command);

        then(settlementDeliveryRepository).should().save(
                org.mockito.ArgumentMatchers.argThat(delivery ->
                        delivery.getSettlementId().equals(settlement.getId())
                                && delivery.getSettlementBatchId()
                                .equals(command.settlementBatchId())
                                && delivery.getDeliveryRequestId() != null));
    }
}
