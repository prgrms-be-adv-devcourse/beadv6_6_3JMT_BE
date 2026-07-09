package com.prompthub.settlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.prompthub.settlement.application.dto.SettleableLine;
import com.prompthub.settlement.application.port.SettleableLineQueryPort;
import com.prompthub.settlement.domain.model.SettlementSourceLine;
import com.prompthub.settlement.domain.model.enums.SettlementSourceEventType;
import com.prompthub.settlement.domain.repository.SettlementSourceRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SettlementSourceLoadServiceTest {

    @Mock
    private SettlementSourceRepository settlementSourceRepository;

    @Mock
    private SettleableLineQueryPort settleableLineQueryPort;

    @InjectMocks
    private SettlementSourceApplicationService service;

    @Captor
    private ArgumentCaptor<List<SettlementSourceLine>> savedLinesCaptor;

    @Test
    @DisplayName("정산 대상 라인을 gRPC로 bulk 조회해 이미 적재된 eventId는 건너뛰고 신규만 저장한다")
    void load_savesOnlyNewLines() {
        // given
        YearMonth period = YearMonth.of(2026, 6);
        UUID orderId = UUID.randomUUID();
        UUID seller = UUID.randomUUID();
        LocalDateTime occurredAt = LocalDateTime.of(2026, 6, 3, 10, 15);
        UUID existingEventId = UUID.randomUUID();
        UUID newPaidEventId = UUID.randomUUID();
        UUID newRefundEventId = UUID.randomUUID();

        List<SettleableLine> lines = List.of(
                new SettleableLine(existingEventId, SettlementSourceEventType.PAID, orderId,
                        UUID.randomUUID(), seller, new BigDecimal("1000"), occurredAt),
                new SettleableLine(newPaidEventId, SettlementSourceEventType.PAID, orderId,
                        UUID.randomUUID(), seller, new BigDecimal("2000"), occurredAt),
                new SettleableLine(newRefundEventId, SettlementSourceEventType.REFUND, orderId,
                        UUID.randomUUID(), seller, new BigDecimal("500"), occurredAt));
        given(settleableLineQueryPort.fetchSettleableLines(period)).willReturn(lines);
        given(settlementSourceRepository.findExistingEventIds(anyCollection()))
                .willReturn(List.of(existingEventId));

        // when
        int saved = service.load(period);

        // then
        assertThat(saved).isEqualTo(2);
        verify(settlementSourceRepository).saveAll(savedLinesCaptor.capture());
        List<SettlementSourceLine> savedLines = savedLinesCaptor.getValue();
        assertThat(savedLines).extracting(SettlementSourceLine::getEventId)
                .containsExactlyInAnyOrder(newPaidEventId, newRefundEventId);
        assertThat(savedLines).extracting(SettlementSourceLine::getEventType)
                .containsExactlyInAnyOrder(SettlementSourceEventType.PAID, SettlementSourceEventType.REFUND);
    }

    @Test
    @DisplayName("조회 결과가 비면 저장을 호출하지 않고 0을 반환한다")
    void load_empty_noSave() {
        // given
        YearMonth period = YearMonth.of(2026, 6);
        given(settleableLineQueryPort.fetchSettleableLines(period)).willReturn(List.of());

        // when
        int saved = service.load(period);

        // then
        assertThat(saved).isZero();
        verify(settlementSourceRepository, never()).saveAll(any());
    }
}
