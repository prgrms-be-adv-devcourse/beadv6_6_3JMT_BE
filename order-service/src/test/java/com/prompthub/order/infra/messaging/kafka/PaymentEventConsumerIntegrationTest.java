package com.prompthub.order.infra.messaging.kafka;

import com.prompthub.order.application.event.payment.PaymentApprovedEvent;
import com.prompthub.order.application.event.payment.PaymentRefundedEvent;
import com.prompthub.order.application.service.event.OrderPaymentEventService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
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
        
        Map<String, Object> message = new HashMap<>();
        message.put("eventType", "payment.approved");
        message.put("paymentId", paymentId.toString());
        message.put("orderId", orderId.toString());
        message.put("userId", buyerId.toString());
        message.put("amount", 30000);
        message.put("approvedAt", OffsetDateTime.now(ZoneOffset.ofHours(9)).toString());

        // when
        kafkaTemplate.send("payment.approved", orderId.toString(), message);

        // then
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> 
            verify(orderPaymentEventService).handlePaymentApproved(any(PaymentApprovedEvent.class))
        );
    }

    @Test
    @DisplayName("결제 환불 이벤트를 수신하면 OrderPaymentEventService가 호출된다")
    void consumePaymentRefundedEvent() {
        // given
        UUID paymentId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();

        Map<String, Object> message = new HashMap<>();
        message.put("eventType", "payment.refunded");
        message.put("paymentId", paymentId.toString());
        message.put("orderId", orderId.toString());
        message.put("userId", buyerId.toString());
        message.put("amount", 30000);
        message.put("refundedAt", OffsetDateTime.now(ZoneOffset.ofHours(9)).toString());

        // when
        kafkaTemplate.send("payment.refunded", orderId.toString(), message);

        // then
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
            verify(orderPaymentEventService).handlePaymentRefunded(any(PaymentRefundedEvent.class))
        );
    }
}
