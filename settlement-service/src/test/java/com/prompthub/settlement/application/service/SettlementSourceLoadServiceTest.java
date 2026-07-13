package com.prompthub.settlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.prompthub.settlement.application.dto.SettleableLine;
import com.prompthub.settlement.application.port.OrderSettlementQueryPort;
import com.prompthub.settlement.domain.model.SettlementSourceLine;
import com.prompthub.settlement.domain.model.enums.SettlementSourceLineType;
import com.prompthub.settlement.domain.repository.SettlementSourceRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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
    private OrderSettlementQueryPort orderSettlementQueryPort;

    @InjectMocks
    private SettlementSourceApplicationService service;

    @Captor
    private ArgumentCaptor<List<SettlementSourceLine>> savedLinesCaptor;

    // 서비스와 동일한 멱등키 파생식(orderProductId + lineType). 계약을 테스트가 명시적으로 문서화한다.
    private static UUID eventId(UUID orderProductId, SettlementSourceLineType type) {
        return UUID.nameUUIDFromBytes((orderProductId + "|" + type).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("정산 대상 라인을 gRPC로 bulk 조회해 이미 적재된 멱등키는 건너뛰고 신규만 저장한다")
    void load_savesOnlyNewLines() {
        // given
        YearMonth period = YearMonth.of(2026, 6);
        UUID orderId = UUID.randomUUID();
        UUID seller = UUID.randomUUID();
        LocalDateTime occurredAt = LocalDateTime.of(2026, 6, 3, 10, 15);
        UUID existingOrderProductId = UUID.randomUUID();
        UUID newPaidOrderProductId = UUID.randomUUID();
        UUID newRefundOrderProductId = UUID.randomUUID();

        List<SettleableLine> lines = List.of(
                new SettleableLine(SettlementSourceLineType.PAID, orderId,
                        existingOrderProductId, seller, new BigDecimal("1000"), occurredAt),
                new SettleableLine(SettlementSourceLineType.PAID, orderId,
                        newPaidOrderProductId, seller, new BigDecimal("2000"), occurredAt),
                new SettleableLine(SettlementSourceLineType.REFUND, orderId,
                        newRefundOrderProductId, seller, new BigDecimal("500"), occurredAt));
        given(orderSettlementQueryPort.fetchSettleableLines(period)).willReturn(lines);
        given(settlementSourceRepository.findExistingEventIds(anyCollection()))
                .willReturn(List.of(eventId(existingOrderProductId, SettlementSourceLineType.PAID)));

        // when
        int saved = service.load(period);

        // then
        assertThat(saved).isEqualTo(2);
        verify(settlementSourceRepository).saveAll(savedLinesCaptor.capture());
        List<SettlementSourceLine> savedLines = savedLinesCaptor.getValue();
        assertThat(savedLines).extracting(SettlementSourceLine::getOrderProductId)
                .containsExactlyInAnyOrder(newPaidOrderProductId, newRefundOrderProductId);
        assertThat(savedLines).extracting(SettlementSourceLine::getEventId)
                .containsExactlyInAnyOrder(
                        eventId(newPaidOrderProductId, SettlementSourceLineType.PAID),
                        eventId(newRefundOrderProductId, SettlementSourceLineType.REFUND));
        assertThat(savedLines).extracting(SettlementSourceLine::getLineType)
                .containsExactlyInAnyOrder(SettlementSourceLineType.PAID, SettlementSourceLineType.REFUND);
    }

    @Test
    @DisplayName("조회 결과가 비면 저장을 호출하지 않고 0을 반환한다")
    void load_empty_noSave() {
        // given
        YearMonth period = YearMonth.of(2026, 6);
        given(orderSettlementQueryPort.fetchSettleableLines(period)).willReturn(List.of());

        // when
        int saved = service.load(period);

        // then
        assertThat(saved).isZero();
        verify(settlementSourceRepository, never()).saveAll(any());
    }
}
