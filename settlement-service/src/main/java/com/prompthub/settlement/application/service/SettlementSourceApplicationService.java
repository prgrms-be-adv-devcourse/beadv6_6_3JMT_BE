package com.prompthub.settlement.application.service;

import com.prompthub.settlement.application.event.OrderEventEnvelope;
import com.prompthub.settlement.application.event.OrderPaidEvent;
import com.prompthub.settlement.application.event.OrderPaidProduct;
import com.prompthub.settlement.application.usecase.SettlementSourceUseCase;
import com.prompthub.settlement.domain.model.SettlementSourceLine;
import com.prompthub.settlement.domain.model.enums.SettlementSourceEventType;
import com.prompthub.settlement.domain.repository.SettlementSourceRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementSourceApplicationService implements SettlementSourceUseCase {

    private final SettlementSourceRepository settlementSourceRepository;

    @Override
    @Transactional
    public void recordOrderPaid(OrderEventEnvelope<OrderPaidEvent> envelope) {
        OrderPaidEvent event = envelope.payload();
        for (OrderPaidProduct product : event.products()) {
            UUID lineEventId = lineEventId(envelope.eventId(), product.orderProductId());
            if (settlementSourceRepository.existsByEventId(lineEventId)) {
                log.debug("이미 적재된 정산 소스 라인이라 건너뜁니다. eventId={}", lineEventId);
                continue;
            }
            settlementSourceRepository.save(SettlementSourceLine.paid(
                    lineEventId,
                    event.orderId(),
                    product.orderProductId(),
                    product.sellerId(),
                    BigDecimal.valueOf(product.productAmount()),
                    envelope.occurredAt()));
        }
    }

    private UUID lineEventId(UUID orderEventId, UUID orderProductId) {
        String seed = orderEventId + "|" + orderProductId + "|" + SettlementSourceEventType.PAID;
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }
}
