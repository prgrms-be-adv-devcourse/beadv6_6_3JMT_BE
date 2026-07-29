package com.prompthub.order.application.service.event;

import com.prompthub.order.application.dto.event.order.OrderExpiredPayload;
import com.prompthub.order.application.dto.event.order.OrderPaymentFailedPayload;
import com.prompthub.order.application.dto.event.order.OrderRefundFailedPayload;
import com.prompthub.order.application.dto.event.order.OrderRefundRequestedPayload;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OrderEventMessageFactoryTest {

    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID BUYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final String ORDER_NUMBER = "ORD-20260727-0001";
    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 7, 27, 10, 0);

    private final OrderEventMessageFactory factory = new OrderEventMessageFactory();

    @Test
    void createsRefundRequestedMessageWithNotificationIdentityFields() {
        OrderRefundRequestedPayload payload = new OrderRefundRequestedPayload(
            ORDER_ID, BUYER_ID, ORDER_NUMBER, UUID.randomUUID(), 10_000, OCCURRED_AT
        );

        var message = factory.createOrderRefundRequestedMessage(ORDER_ID, payload);

        assertThat(message.eventType()).isEqualTo("ORDER_REFUND_REQUESTED");
        assertThat(message.payload().orderId()).isEqualTo(ORDER_ID);
        assertThat(message.payload().buyerId()).isEqualTo(BUYER_ID);
        assertThat(message.payload().orderNumber()).isEqualTo(ORDER_NUMBER);
    }

    @Test
    void createsFailureAndExpirationMessagesWithNotificationIdentityFields() {
        var paymentFailed = factory.createOrderPaymentFailedMessage(
            ORDER_ID,
            new OrderPaymentFailedPayload(ORDER_ID, BUYER_ID, ORDER_NUMBER, "DECLINED", "잔액 부족", OCCURRED_AT)
        );
        var expired = factory.createOrderExpiredMessage(
            ORDER_ID,
            new OrderExpiredPayload(ORDER_ID, BUYER_ID, ORDER_NUMBER, OCCURRED_AT)
        );
        var refundFailed = factory.createOrderRefundFailedMessage(
            ORDER_ID,
            new OrderRefundFailedPayload(ORDER_ID, BUYER_ID, ORDER_NUMBER, 10_000, OCCURRED_AT)
        );

        assertThat(paymentFailed.eventType()).isEqualTo("ORDER_PAYMENT_FAILED");
        assertThat(paymentFailed.payload().buyerId()).isEqualTo(BUYER_ID);
        assertThat(expired.eventType()).isEqualTo("ORDER_EXPIRED");
        assertThat(expired.payload().orderNumber()).isEqualTo(ORDER_NUMBER);
        assertThat(refundFailed.eventType()).isEqualTo("ORDER_REFUND_FAILED");
        assertThat(refundFailed.payload().orderId()).isEqualTo(ORDER_ID);
    }
}
