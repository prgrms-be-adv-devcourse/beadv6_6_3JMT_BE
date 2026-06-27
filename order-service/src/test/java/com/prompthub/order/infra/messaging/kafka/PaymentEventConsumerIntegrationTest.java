package com.prompthub.order.infra.messaging.kafka;

import com.prompthub.order.application.event.PaymentApprovedEvent;
import com.prompthub.order.application.service.event.OrderPaymentEventService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

class PaymentEventConsumerIntegrationTest extends KafkaIntegrationTest {

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @MockitoBean
    private OrderPaymentEventService orderPaymentEventService;

    @Test
    @DisplayName("결제 승인 이벤트를 수신하면 OrderPaymentEventService가 호출된다")
    void consumePaymentApprovedEvent() {
        // given
        UUID paymentId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();
        
        java.util.Map<String, Object> message = new java.util.HashMap<>();
        message.put("eventId", eventId);
        message.put("eventType", "PAYMENT_APPROVED");
        message.put("paymentId", paymentId.toString());
        message.put("orderId", orderId.toString());
        message.put("buyerId", buyerId.toString());
        message.put("approvedAmount", 30000);
        message.put("occurredAt", LocalDateTime.now().toString());

        // when
        kafkaTemplate.send("payment-events", orderId.toString(), message);

        // then
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> 
            verify(orderPaymentEventService).handlePaymentApproved(any(PaymentApprovedEvent.class))
        );
    }
}
