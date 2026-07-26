package com.prompthub.notification.infra.messaging.kafka.router;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.prompthub.common.event.EventMessage;
import com.prompthub.notification.application.service.OrderPaidEventHandler;
import com.prompthub.notification.application.service.OrderRefundEventHandler;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class OrderEventRouterTest {

    @Mock
    private OrderPaidEventHandler paidEventHandler;
    @Mock
    private OrderRefundEventHandler refundEventHandler;
    @InjectMocks
    private OrderEventRouter router;

    @Test
    void routesPaidEventToPaidHandler() {
        EventMessage<JsonNode> message = event("ORDER_PAID");

        router.route(message);

        verify(paidEventHandler).handle(message);
        verify(refundEventHandler, never()).handle(any());
    }

    @Test
    void routesRefundEventToRefundHandler() {
        EventMessage<JsonNode> message = event("ORDER_REFUND");

        router.route(message);

        verify(refundEventHandler).handle(message);
        verify(paidEventHandler, never()).handle(any());
    }

    @Test
    void doesNotSupportOtherOrderEventTypes() {
        EventMessage<JsonNode> message = event("ORDER_REFUND_REQUESTED");

        router.route(message);

        verify(paidEventHandler, never()).handle(any());
        verify(refundEventHandler, never()).handle(any());
    }

    private EventMessage<JsonNode> event(String eventType) {
        return new EventMessage<>(
            UUID.randomUUID(), eventType, LocalDateTime.now(), "ORDER", UUID.randomUUID(), JsonMapper.builder().build().createObjectNode()
        );
    }
}
