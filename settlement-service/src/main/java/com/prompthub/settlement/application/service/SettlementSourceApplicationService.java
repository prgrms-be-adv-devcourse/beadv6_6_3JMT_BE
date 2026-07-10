package com.prompthub.settlement.application.service;

import com.prompthub.settlement.application.dto.SettleableLine;
import com.prompthub.settlement.application.event.OrderEventEnvelope;
import com.prompthub.settlement.application.event.OrderPaidEvent;
import com.prompthub.settlement.application.event.OrderPaidProduct;
import com.prompthub.settlement.application.event.OrderRefundedEvent;
import com.prompthub.settlement.application.event.OrderRefundedProduct;
import com.prompthub.settlement.application.port.OrderSettlementQueryPort;
import com.prompthub.settlement.application.usecase.LoadSettlementSourceUseCase;
import com.prompthub.settlement.application.usecase.SettlementSourceUseCase;
import com.prompthub.settlement.domain.model.SettlementSourceLine;
import com.prompthub.settlement.domain.model.enums.SettlementSourceEventType;
import com.prompthub.settlement.domain.repository.SettlementSourceRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementSourceApplicationService implements SettlementSourceUseCase, LoadSettlementSourceUseCase {

    private final SettlementSourceRepository settlementSourceRepository;
    private final OrderSettlementQueryPort orderSettlementQueryPort;

    @Override
    @Transactional
    public void recordOrderPaid(OrderEventEnvelope<OrderPaidEvent> envelope) {
        OrderPaidEvent event = envelope.payload();
        for (OrderPaidProduct product : event.products()) {
            UUID lineEventId = lineEventId(envelope.eventId(), product.orderProductId(), SettlementSourceEventType.PAID);
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

    @Override
    @Transactional
    public void recordOrderRefunded(OrderEventEnvelope<OrderRefundedEvent> envelope) {
        OrderRefundedEvent event = envelope.payload();
        for (OrderRefundedProduct product : event.products()) {
            UUID lineEventId = lineEventId(envelope.eventId(), product.orderProductId(), SettlementSourceEventType.REFUND);
            if (settlementSourceRepository.existsByEventId(lineEventId)) {
                log.debug("이미 적재된 정산 소스 라인이라 건너뜁니다. eventId={}", lineEventId);
                continue;
            }
            settlementSourceRepository.save(SettlementSourceLine.refunded(
                    lineEventId,
                    event.orderId(),
                    product.orderProductId(),
                    product.sellerId(),
                    BigDecimal.valueOf(product.refundAmount()),
                    envelope.occurredAt()));
        }
    }

    @Override
    @Transactional
    public int load(YearMonth period) {
        List<SettleableLine> lines = orderSettlementQueryPort.fetchSettleableLines(period);
        if (lines.isEmpty()) {
            return 0;
        }
        // 멱등키(orderProductId + eventType)로 파생 — 재-pull 시에도 같은 라인은 한 번만 적재된다.
        Map<UUID, SettleableLine> byEventId = new LinkedHashMap<>();
        for (SettleableLine line : lines) {
            byEventId.put(pullLineEventId(line.orderProductId(), line.eventType()), line);
        }
        Set<UUID> existing = new HashSet<>(settlementSourceRepository.findExistingEventIds(byEventId.keySet()));
        List<SettlementSourceLine> toSave = byEventId.entrySet().stream()
                .filter(entry -> !existing.contains(entry.getKey()))
                .map(entry -> toSourceLine(entry.getKey(), entry.getValue()))
                .toList();
        settlementSourceRepository.saveAll(toSave);
        log.info("정산 대상 라인 bulk 적재 완료. period={}, 조회={}, 신규적재={}", period, lines.size(), toSave.size());
        return toSave.size();
    }

    private SettlementSourceLine toSourceLine(UUID eventId, SettleableLine line) {
        return switch (line.eventType()) {
            case PAID -> SettlementSourceLine.paid(eventId, line.orderId(), line.orderProductId(),
                    line.sellerId(), line.lineAmount(), line.occurredAt());
            case REFUND -> SettlementSourceLine.refunded(eventId, line.orderId(), line.orderProductId(),
                    line.sellerId(), line.lineAmount(), line.occurredAt());
        };
    }

    private UUID lineEventId(UUID orderEventId, UUID orderProductId, SettlementSourceEventType eventType) {
        String seed = orderEventId + "|" + orderProductId + "|" + eventType;
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }

    private UUID pullLineEventId(UUID orderProductId, SettlementSourceEventType eventType) {
        String seed = orderProductId + "|" + eventType;
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }
}
