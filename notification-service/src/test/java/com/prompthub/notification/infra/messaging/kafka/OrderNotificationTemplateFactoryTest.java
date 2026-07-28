package com.prompthub.notification.infra.messaging.kafka;

import com.prompthub.notification.application.command.CreateNotificationCommand;
import com.prompthub.notification.domain.enums.NotificationCategory;
import com.prompthub.notification.domain.enums.NotificationType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderNotificationTemplateFactoryTest {

    private static final UUID EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID BUYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final Instant OCCURRED_AT = Instant.parse("2026-07-27T01:00:00Z");
    private final OrderNotificationTemplateFactory factory = new OrderNotificationTemplateFactory();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @ParameterizedTest
    @MethodSource("eventTypes")
    void mapsEventToExpectedNotificationContract(String eventType, String status, NotificationType expectedType,
                                                   NotificationCategory expectedCategory) {
        CreateNotificationCommand command = factory.create(event(eventType, status));

        assertThat(command.recipientId()).isEqualTo(BUYER_ID);
        assertThat(command.referenceId()).isEqualTo(ORDER_ID);
        assertThat(command.type()).isEqualTo(expectedType);
        assertThat(command.category()).isEqualTo(expectedCategory);
        assertThat(command.deduplicationKey()).isEqualTo(eventType + ":" + EVENT_ID);
    }

    @ParameterizedTest
    @MethodSource("invalidRefundStatuses")
    void rejectsUnknownRefundStatus(String status) {
        assertThatThrownBy(() -> factory.create(event("ORDER_REFUND", status)))
            .isInstanceOf(NotificationEventContractException.class);
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments> eventTypes() {
        return Stream.of(
            org.junit.jupiter.params.provider.Arguments.of("ORDER_CREATED", null, NotificationType.ORDER_CREATED, NotificationCategory.ORDER),
            org.junit.jupiter.params.provider.Arguments.of("ORDER_PAID", null, NotificationType.ORDER_PAID, NotificationCategory.PAYMENT),
            org.junit.jupiter.params.provider.Arguments.of("ORDER_PAYMENT_FAILED", null, NotificationType.ORDER_PAYMENT_FAILED, NotificationCategory.PAYMENT),
            org.junit.jupiter.params.provider.Arguments.of("ORDER_EXPIRED", null, NotificationType.ORDER_EXPIRED, NotificationCategory.ORDER),
            org.junit.jupiter.params.provider.Arguments.of("ORDER_REFUND_REQUESTED", null, NotificationType.ORDER_REFUND_REQUESTED, NotificationCategory.REFUND),
            org.junit.jupiter.params.provider.Arguments.of("ORDER_REFUND", "PARTIAL_REFUNDED", NotificationType.ORDER_PARTIALLY_REFUNDED, NotificationCategory.REFUND),
            org.junit.jupiter.params.provider.Arguments.of("ORDER_REFUND", "ALL_REFUNDED", NotificationType.ORDER_REFUNDED, NotificationCategory.REFUND),
            org.junit.jupiter.params.provider.Arguments.of("ORDER_REFUND_FAILED", null, NotificationType.ORDER_REFUND_FAILED, NotificationCategory.REFUND)
        );
    }

    private static Stream<String> invalidRefundStatuses() {
        return Stream.of("", "REFUNDED", "UNKNOWN");
    }

    private OrderEventAdapter.OrderEvent event(String eventType, String orderStatus) {
        JsonNode payload = objectMapper.readTree("{} ");
        if (orderStatus != null) {
            ((tools.jackson.databind.node.ObjectNode) payload).put("orderStatus", orderStatus);
        }
        return new OrderEventAdapter.OrderEvent(EVENT_ID, eventType, ORDER_ID, BUYER_ID, "ORD-1", OCCURRED_AT, payload);
    }
}
