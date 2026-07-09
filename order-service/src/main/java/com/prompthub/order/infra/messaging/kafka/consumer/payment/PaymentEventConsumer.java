package com.prompthub.order.infra.messaging.kafka.consumer.payment;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.infra.messaging.kafka.router.PaymentEventRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.type.TypeReference;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventConsumer {

    private final PaymentEventRouter paymentEventRouter;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "payment-events",
            groupId = "order-service",
            containerFactory = "paymentEventKafkaListenerContainerFactory"
    )
    public void consume(String message, Acknowledgment acknowledgment) {
        try {
            EventMessage<JsonNode> eventMessage = objectMapper.readValue(message, new TypeReference<EventMessage<JsonNode>>() {});
            if (eventMessage.payload() == null || eventMessage.payload().isNull() || eventMessage.payload().isMissingNode()) {
                throw new IllegalArgumentException("Payload is missing");
            }
            paymentEventRouter.route(eventMessage);
            acknowledgment.acknowledge();
        } catch (Exception e) {
            log.error("결제 메시지 처리 중 에러 발생: {}", e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }
}
