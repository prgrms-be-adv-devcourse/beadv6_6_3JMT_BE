package com.prompthub.settlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.prompthub.settlement.application.event.OrderEventEnvelope;
import com.prompthub.settlement.application.event.OrderPaidEvent;
import com.prompthub.settlement.application.event.OrderPaidProduct;
import com.prompthub.settlement.domain.model.SettlementSourceLine;
import com.prompthub.settlement.domain.model.enums.SettlementSourceEventType;
import com.prompthub.settlement.domain.repository.SettlementSourceRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SettlementSourceApplicationServiceTest {

    private static final LocalDateTime EVENT_TIME = LocalDateTime.of(2026, 6, 15, 10, 0);

    @Mock
    private SettlementSourceRepository settlementSourceRepository;

    @InjectMocks
    private SettlementSourceApplicationService service;

    private OrderPaidProduct product(UUID orderProductId, UUID sellerId, int amount) {
        return new OrderPaidProduct(orderProductId, UUID.randomUUID(), sellerId, "프롬프트", "PROMPT", amount);
    }

    private OrderEventEnvelope<OrderPaidEvent> envelope(UUID eventId, UUID orderId, List<OrderPaidProduct> products) {
        OrderPaidEvent event = new OrderPaidEvent(orderId, UUID.randomUUID(), 0, products.size(), EVENT_TIME, products);
        return new OrderEventEnvelope<>(eventId, "ORDER_PAID", 1, EVENT_TIME, orderId, event);
    }

    @Test
    @DisplayName("주문상품 수만큼 PAID 소스 라인을 적재한다")
    void recordOrderPaid_savesLinePerProduct() {
        UUID seller = UUID.randomUUID();
        given(settlementSourceRepository.existsByEventId(any())).willReturn(false);

        service.recordOrderPaid(envelope(UUID.randomUUID(), UUID.randomUUID(),
                List.of(product(UUID.randomUUID(), seller, 9900), product(UUID.randomUUID(), seller, 5000))));

        ArgumentCaptor<SettlementSourceLine> captor = ArgumentCaptor.forClass(SettlementSourceLine.class);
        verify(settlementSourceRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
                .allMatch(line -> line.getEventType() == SettlementSourceEventType.PAID)
                .anyMatch(line -> line.getLineAmount().compareTo(BigDecimal.valueOf(9900)) == 0);
    }

    @Test
    @DisplayName("이미 적재된 라인(eventId 중복)은 저장하지 않는다")
    void recordOrderPaid_duplicate_skips() {
        given(settlementSourceRepository.existsByEventId(any())).willReturn(true);

        service.recordOrderPaid(envelope(UUID.randomUUID(), UUID.randomUUID(),
                List.of(product(UUID.randomUUID(), UUID.randomUUID(), 9900))));

        verify(settlementSourceRepository, never()).save(any());
    }

    @Test
    @DisplayName("같은 주문상품은 항상 같은 합성 eventId로 적재된다(멱등)")
    void recordOrderPaid_deterministicEventId() {
        UUID eventId = UUID.randomUUID();
        UUID orderProductId = UUID.randomUUID();
        UUID seller = UUID.randomUUID();
        given(settlementSourceRepository.existsByEventId(any())).willReturn(false);

        service.recordOrderPaid(envelope(eventId, UUID.randomUUID(), List.of(product(orderProductId, seller, 9900))));
        service.recordOrderPaid(envelope(eventId, UUID.randomUUID(), List.of(product(orderProductId, seller, 9900))));

        ArgumentCaptor<SettlementSourceLine> captor = ArgumentCaptor.forClass(SettlementSourceLine.class);
        verify(settlementSourceRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues().get(0).getEventId())
                .isEqualTo(captor.getAllValues().get(1).getEventId());
    }
}
