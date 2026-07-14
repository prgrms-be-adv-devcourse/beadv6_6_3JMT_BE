package com.prompthub.settlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.prompthub.settlement.application.dto.CalculateSettlementCommand;
import com.prompthub.settlement.application.port.OutboxEventAppender;
import com.prompthub.settlement.domain.model.Settlement;
import com.prompthub.settlement.domain.model.SettlementSourceLine;
import com.prompthub.settlement.domain.repository.SettlementRepository;
import com.prompthub.settlement.domain.repository.SettlementSourceRepository;
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
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class SettlementCalculationApplicationServiceTest {

    @Mock
    private SettlementSourceRepository settlementSourceRepository;

    @Mock
    private SettlementRepository settlementRepository;

    @Mock
    private OutboxEventAppender outboxEventAppender;

    @InjectMocks
    private SettlementCalculationApplicationService service;

    private SettlementSourceLine paidLine(UUID sellerId, String amount) {
        return SettlementSourceLine.paid(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                sellerId, new BigDecimal(amount), LocalDateTime.of(2026, 6, 15, 10, 0));
    }

    private SettlementSourceLine refundLine(UUID sellerId, String amount) {
        return SettlementSourceLine.refunded(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                sellerId, new BigDecimal(amount), LocalDateTime.of(2026, 6, 16, 10, 0));
    }

    private void stubSaveAssigningId() {
        given(settlementRepository.save(any(Settlement.class))).willAnswer(invocation -> {
            Settlement saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", UUID.randomUUID());
            return saved;
        });
    }

    @Test
    @DisplayName("PAID는 더하고 REFUND는 빼서 순액으로 정산을 생성한다")
    void calculate_paidAndRefund_netsAmount() {
        // given
        UUID sellerId = UUID.randomUUID();
        YearMonth period = YearMonth.of(2026, 6);
        CalculateSettlementCommand command = new CalculateSettlementCommand(UUID.randomUUID(), sellerId, period);
        given(settlementSourceRepository.findSettleableLines(sellerId, period))
                .willReturn(List.of(paidLine(sellerId, "300.00"), refundLine(sellerId, "100.00")));
        stubSaveAssigningId();

        // when
        Settlement settlement = service.calculate(command);

        // then : PAID 300(fee 45, net 255) + REFUND 100(fee -15, net -85) => total 200, fee 30, net 170
        assertThat(settlement.getProductCount()).isEqualTo(2);
        assertThat(settlement.getTotalAmount()).isEqualByComparingTo("200.00");
        assertThat(settlement.getFeeTotalAmount()).isEqualByComparingTo("30.00");
        assertThat(settlement.getSettlementTotalAmount()).isEqualByComparingTo("170.00");
        verify(settlementRepository).save(settlement);
    }

    @Test
    @DisplayName("정산에 포함된 소스 라인은 생성된 정산 ID로 연결(markSettled)된다")
    void calculate_marksSourceLinesSettled() {
        // given
        UUID sellerId = UUID.randomUUID();
        YearMonth period = YearMonth.of(2026, 6);
        CalculateSettlementCommand command = new CalculateSettlementCommand(UUID.randomUUID(), sellerId, period);
        SettlementSourceLine line = paidLine(sellerId, "100.00");
        given(settlementSourceRepository.findSettleableLines(sellerId, period)).willReturn(List.of(line));
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
        YearMonth period = YearMonth.of(2026, 6);
        CalculateSettlementCommand command = new CalculateSettlementCommand(UUID.randomUUID(), sellerId, period);
        given(settlementSourceRepository.findSettleableLines(sellerId, period)).willReturn(List.of());

        // when
        Settlement settlement = service.calculate(command);

        // then
        assertThat(settlement).isNull();
        verify(settlementRepository, never()).save(any());
        then(outboxEventAppender).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("정산 저장 후 배치 ID와 생성된 정산 페이로드로 아웃박스를 적재한다")
    void calculate_appendsSettlementCreatedToOutboxAfterSave() {
        // given
        UUID sellerId = UUID.randomUUID();
        YearMonth period = YearMonth.of(2026, 6);
        CalculateSettlementCommand command = new CalculateSettlementCommand(UUID.randomUUID(), sellerId, period);
        given(settlementSourceRepository.findSettleableLines(sellerId, period))
                .willReturn(List.of(paidLine(sellerId, "100.00")));
        stubSaveAssigningId();

        // when
        Settlement settlement = service.calculate(command);

        // then : 저장된 정산 ID로 이벤트가 적재된다
        then(outboxEventAppender).should().appendSettlementCreated(
                eq(command.settlementBatchId()),
                org.mockito.ArgumentMatchers.argThat(
                        payload -> payload.settlementId().equals(settlement.getId())));
    }
}
