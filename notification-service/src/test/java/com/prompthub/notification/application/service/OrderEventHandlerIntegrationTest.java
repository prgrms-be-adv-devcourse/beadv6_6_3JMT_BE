package com.prompthub.notification.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.common.event.EventMessage;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

@SpringBootTest
@ActiveProfiles("test")
class OrderEventHandlerIntegrationTest {

    @Autowired
    private OrderPaidEventHandler paidEventHandler;
    @Autowired
    private OrderRefundEventHandler refundEventHandler;
    @Autowired
    private NotificationQueryService queryService;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void paidEventCreatesOneIdempotentNotification() {
        UUID buyerId = UUID.randomUUID();
        EventMessage<JsonNode> event = new EventMessage<>(
            UUID.randomUUID(), "ORDER_PAID", LocalDateTime.now(), "ORDER", UUID.randomUUID(),
            objectMapper.createObjectNode()
                .put("orderId", UUID.randomUUID().toString())
                .put("buyerId", buyerId.toString())
                .put("totalOrderAmount", 15_000)
                .put("totalProductCount", 1)
                .put("paidAt", "2026-07-26T10:00:00")
        );

        paidEventHandler.handle(event);
        paidEventHandler.handle(event);

        assertThat(queryService.findPage(buyerId, 0, 20).total()).isEqualTo(1L);
    }

    @Test
    void refundEventCreatesNotificationForBuyer() {
        UUID buyerId = UUID.randomUUID();
        EventMessage<JsonNode> event = new EventMessage<>(
            UUID.randomUUID(), "ORDER_REFUND", LocalDateTime.now(), "ORDER", UUID.randomUUID(),
            objectMapper.createObjectNode()
                .put("orderId", UUID.randomUUID().toString())
                .put("buyerId", buyerId.toString())
                .put("totalOrderAmount", 15_000)
                .put("refundedAt", "2026-07-26T10:00:00")
        );

        refundEventHandler.handle(event);

        assertThat(queryService.findPage(buyerId, 0, 20).items())
            .singleElement()
            .extracting(NotificationItem::title)
            .isEqualTo("환불이 완료되었습니다.");
    }
}
