package com.prompthub.notification.application.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prompthub.common.event.EventMessage;
import com.prompthub.notification.infra.sse.SseNotificationPublisher;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class OrderPaidEventHandlerTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    @Mock
    private NotificationCommandService notificationCommandService;
    @Mock
    private SseNotificationPublisher sseNotificationPublisher;

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void publishesOnlyAfterTransactionCommit() {
        UUID buyerId = UUID.randomUUID();
        when(notificationCommandService.createIfAbsent(any()))
            .thenReturn(new StoredNotification(UUID.randomUUID(), 1L, true));
        OrderPaidEventHandler handler = new OrderPaidEventHandler(
            objectMapper, notificationCommandService, sseNotificationPublisher
        );
        TransactionSynchronizationManager.initSynchronization();

        handler.handle(event(buyerId));

        verify(sseNotificationPublisher, never()).publish(any(), any());
        TransactionSynchronizationManager.getSynchronizations().forEach(synchronization -> synchronization.afterCommit());
        verify(sseNotificationPublisher).publish(any(), any());
    }

    @Test
    void duplicateEventDoesNotRegisterSsePublication() {
        when(notificationCommandService.createIfAbsent(any()))
            .thenReturn(new StoredNotification(UUID.randomUUID(), 1L, false));
        OrderPaidEventHandler handler = new OrderPaidEventHandler(
            objectMapper, notificationCommandService, sseNotificationPublisher
        );
        TransactionSynchronizationManager.initSynchronization();

        handler.handle(event(UUID.randomUUID()));
        TransactionSynchronizationManager.getSynchronizations().forEach(synchronization -> synchronization.afterCommit());

        verify(sseNotificationPublisher, never()).publish(any(), any());
    }

    private EventMessage<JsonNode> event(UUID buyerId) {
        return new EventMessage<>(
            UUID.randomUUID(), "ORDER_PAID", LocalDateTime.now(), "ORDER", UUID.randomUUID(),
            objectMapper.createObjectNode()
                .put("orderId", UUID.randomUUID().toString())
                .put("buyerId", buyerId.toString())
                .put("totalOrderAmount", 15_000)
                .put("totalProductCount", 1)
                .put("paidAt", "2026-07-26T10:00:00")
        );
    }
}
