package com.prompthub.payment.infrastructure.messaging.consumer;

import com.prompthub.payment.application.dto.command.RefundCommand;
import com.prompthub.payment.application.usecase.RefundUseCase;
import com.prompthub.payment.infrastructure.messaging.dto.OrderRefundRequestedMessage;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * order-events 구독. 공통 이벤트 규칙(EventMessage&lt;T&gt; 봉투)의 최상위 eventType으로 필터링한다.
 * ORDER_REFUND_REQUESTED만 처리하고, 그 외 eventType(ORDER_CREATED/ORDER_PAID 등)은 무시한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventConsumer {

    private static final String TOPIC_ORDER_EVENTS = "order-events";
    private static final String GROUP_ID = "payment-service-order-events";
    private static final String EVENT_TYPE_ORDER_REFUND_REQUESTED = "ORDER_REFUND_REQUESTED";

    // requestedAt(LocalDateTime, 존 없음)에 부여할 존 — payment의 KST 표기 관례와 일치
    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    private final ObjectMapper objectMapper;
    private final RefundUseCase refundUseCase;

    @KafkaListener(
        topics = TOPIC_ORDER_EVENTS,
        groupId = GROUP_ID,
        containerFactory = "orderEventKafkaListenerContainerFactory"
    )
    public void consume(String message, Acknowledgment acknowledgment) {
        try {
            JsonNode root = objectMapper.readTree(message);
            String eventType = root.path("eventType").stringValue(null);
            JsonNode payload = root.path("payload");

            if (EVENT_TYPE_ORDER_REFUND_REQUESTED.equals(eventType)) {
                handleOrderRefundRequested(payload);
            } else {
                log.debug("처리 대상이 아닌 order-events 메시지 무시 — eventType={}", eventType);
            }
            acknowledgment.acknowledge();
        } catch (Exception e) {
            log.error("order-events 메시지 처리 실패: {}", e.getMessage(), e);
            throw e; // DefaultErrorHandler → FixedBackOff 재시도 → order-events.DLT
        }
    }

    private void handleOrderRefundRequested(JsonNode payload) {
        OrderRefundRequestedMessage requested = objectMapper.treeToValue(payload, OrderRefundRequestedMessage.class);
        validateRefund(requested);

        refundUseCase.refund(new RefundCommand(
            requested.orderId(),
            requested.refundRequestId(),
            requested.refundAmount(),
            requested.requestedAt().atOffset(KST)
        ));
        log.info("환불 처리 완료 — orderId={}, refundRequestId={}", requested.orderId(), requested.refundRequestId());
    }

    private void validateRefund(OrderRefundRequestedMessage message) {
        if (message.orderId() == null || message.refundRequestId() == null || message.requestedAt() == null) {
            throw new IllegalArgumentException("ORDER_REFUND_REQUESTED 필수 필드 누락: " + message);
        }
    }
}
