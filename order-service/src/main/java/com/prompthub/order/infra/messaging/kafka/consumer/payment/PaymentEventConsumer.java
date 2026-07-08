package com.prompthub.order.infra.messaging.kafka.consumer.payment;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.infra.messaging.kafka.router.PaymentEventRouter;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
@RequiredArgsConstructor
public class PaymentEventConsumer {

    private final PaymentEventRouter paymentEventRouter;

    @KafkaListener(
            topics = "payment-events",
            groupId = "order-service"
    )
    public void consume(EventMessage<JsonNode> message) {
        paymentEventRouter.route(message);
    }
}
