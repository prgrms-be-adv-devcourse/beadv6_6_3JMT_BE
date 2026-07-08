package com.prompthub.paymentservice.infrastructure.messaging.consumer;

import com.prompthub.paymentservice.application.dto.command.RecordOrderSnapshotCommand;
import com.prompthub.paymentservice.application.usecase.RecordOrderSnapshotUseCase;
import com.prompthub.paymentservice.domain.model.OrderSnapshotSource;
import com.prompthub.paymentservice.infrastructure.messaging.dto.OrderCreatedMessage;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * order-events 구독. 평면 메시지(§2.1)의 최상위 eventType으로 필터링한다.
 * ORDER_CREATED만 처리하고, 다른 eventType(ORDER_PAID/ORDER_REFUND 등)은 무시한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventConsumer {

    private static final String TOPIC_ORDER_EVENTS = "order-events";
    private static final String GROUP_ID = "payment-service-order-events";
    private static final String EVENT_TYPE_ORDER_CREATED = "ORDER_CREATED";

    // createdAt(LocalDateTime, 존 없음)에 부여할 존 — payment의 KST 표기 관례와 일치
    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    private final ObjectMapper objectMapper;
    private final RecordOrderSnapshotUseCase recordOrderSnapshotUseCase;

    @KafkaListener(
        topics = TOPIC_ORDER_EVENTS,
        groupId = GROUP_ID,
        containerFactory = "orderEventKafkaListenerContainerFactory"
    )
    public void consume(String message, Acknowledgment acknowledgment) {
        try {
            JsonNode root = objectMapper.readTree(message);
            String eventType = root.path("eventType").stringValue(null);

            if (!EVENT_TYPE_ORDER_CREATED.equals(eventType)) {
                log.debug("처리 대상이 아닌 order-events 메시지 무시 — eventType={}", eventType);
                acknowledgment.acknowledge();
                return;
            }

            OrderCreatedMessage created = objectMapper.treeToValue(root, OrderCreatedMessage.class);
            validate(created);

            recordOrderSnapshotUseCase.record(new RecordOrderSnapshotCommand(
                created.orderId(),
                created.buyerId(),
                created.totalOrderAmount(),
                created.createdAt().atOffset(KST),
                OrderSnapshotSource.EVENT
            ));
            log.info("주문 스냅샷 저장 — orderId={}", created.orderId());
            acknowledgment.acknowledge();
        } catch (Exception e) {
            log.error("order-events 메시지 처리 실패: {}", e.getMessage(), e);
            throw e; // DefaultErrorHandler → FixedBackOff 재시도 → order-events.DLT
        }
    }

    private void validate(OrderCreatedMessage message) {
        if (message.orderId() == null || message.buyerId() == null || message.createdAt() == null) {
            throw new IllegalArgumentException("ORDER_CREATED 필수 필드 누락: " + message);
        }
    }
}
