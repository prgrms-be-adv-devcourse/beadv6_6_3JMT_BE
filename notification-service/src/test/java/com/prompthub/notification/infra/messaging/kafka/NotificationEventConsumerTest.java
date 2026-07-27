package com.prompthub.notification.infra.messaging.kafka;

import com.prompthub.common.event.EventMessage;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.mock;
import static org.mockito.BDDMockito.then;

class NotificationEventConsumerTest {

    private static final String ORDER_PAID_RELAY_VALUE = "{\"eventType\":\"ORDER_PAID\"}";
    private static final String UNKNOWN_RELAY_VALUE = "{\"eventType\":\"ORDER_SHIPPED\"}";

    private final NotificationEventMessageParser parser = mock(NotificationEventMessageParser.class);
    private final OrderEventHandler orderEventHandler = mock(OrderEventHandler.class);
    private final Acknowledgment acknowledgment = mock(Acknowledgment.class);
    private final NotificationEventConsumer consumer =
        new NotificationEventConsumer(parser, orderEventHandler);

    @Test
    void handlesSupportedRawEventThenAcknowledgesIt() {
        EventMessage<JsonNode> event = event("ORDER_PAID");
        given(parser.parse(ORDER_PAID_RELAY_VALUE)).willReturn(event);

        consumer.consume(ORDER_PAID_RELAY_VALUE, acknowledgment);

        then(orderEventHandler).should().handle(event);
        then(acknowledgment).should().acknowledge();
    }

    @Test
    void acknowledgesUnsupportedRawEventWithoutHandlingIt() {
        given(parser.parse(UNKNOWN_RELAY_VALUE)).willReturn(event("ORDER_SHIPPED"));

        consumer.consume(UNKNOWN_RELAY_VALUE, acknowledgment);

        then(orderEventHandler).shouldHaveNoInteractions();
        then(acknowledgment).should().acknowledge();
    }

    @Test
    void doesNotAcknowledgeMalformedRawEvent() {
        given(parser.parse("{")).willThrow(new NotificationEventDeserializeException(new RuntimeException()));

        try {
            consumer.consume("{", acknowledgment);
        } catch (NotificationEventDeserializeException ignored) {
        }

        then(orderEventHandler).shouldHaveNoInteractions();
        then(acknowledgment).shouldHaveNoInteractions();
    }

    private EventMessage<JsonNode> event(String eventType) {
        JsonNode payload = new ObjectMapper().readTree("{\"orderId\":\"00000000-0000-0000-0000-000000000201\"}");
        return new EventMessage<>(
            UUID.fromString("00000000-0000-0000-0000-000000000101"),
            eventType,
            LocalDateTime.of(2026, 7, 27, 10, 0),
            "ORDER",
            UUID.fromString("00000000-0000-0000-0000-000000000201"),
            payload
        );
    }
}
