package com.prompthub.notification.infra.messaging.kafka;

import com.prompthub.common.event.EventMessage;
import com.prompthub.notification.application.command.CreateNotificationCommand;
import com.prompthub.notification.application.service.NotificationService;
import com.prompthub.notification.domain.repository.NotificationProcessedEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class OrderEventHandlerTest {

    @Mock private OrderEventAdapter orderEventAdapter;
    @Mock private OrderNotificationTemplateFactory templateFactory;
    @Mock private NotificationService notificationService;
    @Mock private NotificationProcessedEventRepository processedEventRepository;
    @InjectMocks private OrderEventHandler handler;

    @Test
    void claimedEventCreatesNotification() {
        EventMessage<JsonNode> message = message();
        OrderEventAdapter.OrderEvent event = event(message);
        CreateNotificationCommand command = org.mockito.Mockito.mock(CreateNotificationCommand.class);
        given(processedEventRepository.claim(any(), org.mockito.ArgumentMatchers.eq(message.eventId()),
            org.mockito.ArgumentMatchers.eq("notification-service"), org.mockito.ArgumentMatchers.eq("ORDER_PAID"), any()))
            .willReturn(1);
        given(orderEventAdapter.adapt(message)).willReturn(event);
        given(templateFactory.create(event)).willReturn(command);

        handler.handle(message);

        then(notificationService).should().createNotification(command);
    }

    @Test
    void unclaimedDuplicateDoesNotCreateNotification() {
        EventMessage<JsonNode> message = message();
        given(processedEventRepository.claim(any(), org.mockito.ArgumentMatchers.eq(message.eventId()),
            org.mockito.ArgumentMatchers.eq("notification-service"), org.mockito.ArgumentMatchers.eq("ORDER_PAID"), any()))
            .willReturn(0);

        handler.handle(message);

        then(orderEventAdapter).shouldHaveNoInteractions();
        then(notificationService).shouldHaveNoInteractions();
    }

    @Test
    void claimedEvent_withSuppressedNotificationCompletesNormally() {
        EventMessage<JsonNode> message = message();
        OrderEventAdapter.OrderEvent event = event(message);
        CreateNotificationCommand command = org.mockito.Mockito.mock(CreateNotificationCommand.class);
        given(processedEventRepository.claim(any(), eq(message.eventId()), eq("notification-service"), eq("ORDER_PAID"), any()))
            .willReturn(1);
        given(orderEventAdapter.adapt(message)).willReturn(event);
        given(templateFactory.create(event)).willReturn(command);
        given(notificationService.createNotification(command)).willReturn(Optional.empty());

        assertThatCode(() -> handler.handle(message)).doesNotThrowAnyException();

        then(notificationService).should().createNotification(command);
    }

    @Test
    void claimedEvent_creationFailurePropagatesForKafkaRetry() {
        EventMessage<JsonNode> message = message();
        OrderEventAdapter.OrderEvent event = event(message);
        CreateNotificationCommand command = org.mockito.Mockito.mock(CreateNotificationCommand.class);
        given(processedEventRepository.claim(any(), eq(message.eventId()), eq("notification-service"), eq("ORDER_PAID"), any()))
            .willReturn(1);
        given(orderEventAdapter.adapt(message)).willReturn(event);
        given(templateFactory.create(event)).willReturn(command);
        given(notificationService.createNotification(command))
            .willThrow(new IllegalStateException("setting repository unavailable"));

        assertThatThrownBy(() -> handler.handle(message)).isInstanceOf(IllegalStateException.class);
    }

    private EventMessage<JsonNode> message() {
        UUID orderId = UUID.randomUUID();
        return new EventMessage<>(UUID.randomUUID(), "ORDER_PAID", LocalDateTime.now(), "ORDER", orderId,
            new ObjectMapper().readTree("{}"));
    }

    private OrderEventAdapter.OrderEvent event(EventMessage<JsonNode> message) {
        return new OrderEventAdapter.OrderEvent(message.eventId(), message.eventType(), message.aggregateId(),
            UUID.randomUUID(), "ORD-1", message.occurredAt().toInstant(java.time.ZoneOffset.UTC), message.payload());
    }
}
