package com.prompthub.order.infra.messaging.kafka.consumer;

import com.prompthub.order.application.event.PaymentApprovedEvent;
import com.prompthub.order.application.event.PaymentCanceledEvent;
import com.prompthub.order.application.event.PaymentFailedEvent;
import com.prompthub.order.application.event.PaymentRefundedEvent;
import com.prompthub.order.application.service.OrderPaymentEventService;
import com.prompthub.order.application.service.event.ProcessedEventService;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventHandler {

    private final ProcessedEventService processedEventService;
    private final OrderPaymentEventService orderPaymentEventService;
    private final ObjectMapper objectMapper;

    @Transactional
    public void handle(String eventId, PaymentEventType eventType, String eventTypeStr, String consumerGroup, JsonNode root) {
        // 1. processed_event insert 시도
        // 2. 중복이면 return
        if (!processedEventService.processEvent(eventId, eventTypeStr, consumerGroup)) {
            log.info("중복된 이벤트이므로 무시합니다. eventId={}, eventType={}", eventId, eventTypeStr);
            return; // DataIntegrityViolationException 예외가 발생한 경우 Transaction이 rollback-only로 마킹될 수 있음
        }

        // 3. 주문 상태 변경 로직 실행
        switch (eventType) {
            case PAYMENT_APPROVED -> orderPaymentEventService.handlePaymentApproved(toEvent(root, PaymentApprovedEvent.class));
            case PAYMENT_FAILED -> orderPaymentEventService.handlePaymentFailed(toEvent(root, PaymentFailedEvent.class));
            case PAYMENT_CANCELED -> orderPaymentEventService.handlePaymentCanceled(toEvent(root, PaymentCanceledEvent.class));
            case PAYMENT_REFUNDED -> orderPaymentEventService.handlePaymentRefunded(toEvent(root, PaymentRefundedEvent.class));
            case UNKNOWN -> log.warn("지원하지 않는 결제 이벤트 타입입니다. eventType={}", eventTypeStr);
        }
    }

    private <T> T toEvent(JsonNode root, Class<T> eventTypeClass) {
        try {
            return objectMapper.treeToValue(root, eventTypeClass);
        } catch (JacksonException exception) {
            throw new OrderException(ErrorCode.INTERNAL_SERVER_ERROR, "결제 이벤트 페이로드 역직렬화에 실패했습니다.");
        }
    }
}
