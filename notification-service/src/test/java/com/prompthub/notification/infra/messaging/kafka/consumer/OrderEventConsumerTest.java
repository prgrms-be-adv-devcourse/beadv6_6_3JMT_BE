package com.prompthub.notification.infra.messaging.kafka.consumer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.prompthub.common.event.EventMessage;
import com.prompthub.notification.infra.messaging.kafka.router.OrderEventRouter;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class OrderEventConsumerTest {

    @Mock
    private OrderEventRouter router;
    @Mock
    private Acknowledgment acknowledgment;

    private ObjectMapper objectMapper;
    private OrderEventConsumer consumer;

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder().findAndAddModules().build();
        consumer = new OrderEventConsumer(router, objectMapper);
    }

    @Test
    void supportedEventDelegatesThenAcknowledges() throws Exception {
        EventMessage<JsonNode> message = event("ORDER_PAID");
        given(router.supports("ORDER_PAID")).willReturn(true);

        consumer.consume(objectMapper.writeValueAsString(message), acknowledgment);

        then(router).should().route(any());
        then(acknowledgment).should().acknowledge();
    }

    @Test
    void unknownEventAcknowledgesWithoutRouting() throws Exception {
        EventMessage<JsonNode> message = event("ORDER_CANCELLED");

        consumer.consume(objectMapper.writeValueAsString(message), acknowledgment);

        then(router).should().supports("ORDER_CANCELLED");
        then(router).should( org.mockito.Mockito.never()).route(any());
        then(acknowledgment).should().acknowledge();
    }

    @Test
    void unknownEventWithoutPayloadAcknowledgesWithoutRouting() throws Exception {
        EventMessage<JsonNode> message = new EventMessage<>(
            UUID.randomUUID(), "ORDER_CANCELLED", LocalDateTime.now(), "ORDER", UUID.randomUUID(), null
        );

        consumer.consume(objectMapper.writeValueAsString(message), acknowledgment);

        then(router).should().supports("ORDER_CANCELLED");
        then(router).should(org.mockito.Mockito.never()).route(any());
        then(acknowledgment).should().acknowledge();
    }

    @Test
    void malformedJsonPropagatesWithoutAcknowledging() {
        assertThatThrownBy(() -> consumer.consume("not-json", acknowledgment))
            .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(acknowledgment);
    }

    private EventMessage<JsonNode> event(String eventType) {
        return new EventMessage<>(
            UUID.randomUUID(), eventType, LocalDateTime.now(), "ORDER", UUID.randomUUID(), objectMapper.createObjectNode()
        );
    }
}
