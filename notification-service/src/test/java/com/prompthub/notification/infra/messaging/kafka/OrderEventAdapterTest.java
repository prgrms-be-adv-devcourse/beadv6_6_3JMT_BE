package com.prompthub.notification.infra.messaging.kafka;

import com.prompthub.common.event.EventMessage;
import com.prompthub.notification.domain.enums.NotificationType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderEventAdapterTest {

    private static final UUID EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID BUYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 7, 27, 10, 0);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OrderEventAdapter adapter = new OrderEventAdapter();

    @ParameterizedTest
    @EnumSource(value = NotificationType.class, names = {
        "ORDER_CREATED", "ORDER_PAID", "ORDER_PAYMENT_FAILED", "ORDER_EXPIRED",
        "ORDER_REFUND_REQUESTED", "ORDER_REFUND_FAILED"
    })
    void mapsEveryDirectOrderEvent(NotificationType type) {
        OrderEventAdapter.OrderEvent event = adapter.adapt(message(type.name(), validPayload()));

        assertThat(event.orderId()).isEqualTo(ORDER_ID);
        assertThat(event.buyerId()).isEqualTo(BUYER_ID);
        assertThat(event.orderNumber()).isEqualTo("ORD-1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"orderId", "buyerId", "orderNumber"})
    void rejectsMissingCommonPayloadField(String field) {
        assertThatThrownBy(() -> adapter.adapt(message("ORDER_PAID", payloadWithout(field))))
            .isInstanceOf(NotificationEventContractException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"orderId", "buyerId"})
    void rejectsMalformedCommonUuid(String field) {
        JsonNode payload = objectMapper.readTree("""
            {"orderId":"%s","buyerId":"%s","orderNumber":"ORD-1"}
            """.formatted(ORDER_ID, BUYER_ID));
        ((tools.jackson.databind.node.ObjectNode) payload).put(field, "not-a-uuid");

        assertThatThrownBy(() -> adapter.adapt(message("ORDER_PAID", payload)))
            .isInstanceOf(NotificationEventContractException.class);
    }

    private EventMessage<JsonNode> message(String eventType, JsonNode payload) {
        return new EventMessage<>(EVENT_ID, eventType, OCCURRED_AT, "ORDER", ORDER_ID, payload);
    }

    private JsonNode validPayload() {
        return objectMapper.readTree("""
            {"orderId":"%s","buyerId":"%s","orderNumber":"ORD-1"}
            """.formatted(ORDER_ID, BUYER_ID));
    }

    private JsonNode payloadWithout(String field) {
        JsonNode payload = validPayload();
        ((tools.jackson.databind.node.ObjectNode) payload).remove(field);
        return payload;
    }
}
