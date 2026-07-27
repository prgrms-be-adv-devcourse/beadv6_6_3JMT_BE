package com.prompthub.notification.infra.messaging.kafka;

import com.prompthub.notification.application.command.CreateNotificationCommand;
import com.prompthub.notification.domain.enums.NotificationCategory;
import com.prompthub.notification.domain.enums.NotificationType;
import org.springframework.stereotype.Component;

@Component
public class OrderNotificationTemplateFactory {

    public CreateNotificationCommand create(OrderEventAdapter.OrderEvent event) {
        NotificationType type = resolveType(event);
        NotificationCategory category = switch (type) {
            case ORDER_CREATED, ORDER_EXPIRED -> NotificationCategory.ORDER;
            case ORDER_PAID, ORDER_PAYMENT_FAILED -> NotificationCategory.PAYMENT;
            default -> NotificationCategory.REFUND;
        };
        String title = switch (type) {
            case ORDER_CREATED -> "주문이 생성되었습니다.";
            case ORDER_PAID -> "결제가 완료되었습니다.";
            case ORDER_PAYMENT_FAILED -> "결제에 실패했습니다.";
            case ORDER_EXPIRED -> "주문이 만료되었습니다.";
            case ORDER_REFUND_REQUESTED -> "환불이 접수되었습니다.";
            case ORDER_PARTIALLY_REFUNDED -> "일부 상품 환불이 완료되었습니다.";
            case ORDER_REFUNDED -> "환불이 완료되었습니다.";
            case ORDER_REFUND_FAILED -> "환불 처리에 실패했습니다.";
        };
        return new CreateNotificationCommand(event.buyerId(), type, category, title,
            event.orderNumber() + " 주문 관련 안내입니다.", "/mypage?tab=payments", "ORDER", event.orderId(),
            event.occurredAt(), event.eventType() + ":" + event.eventId());
    }

    private NotificationType resolveType(OrderEventAdapter.OrderEvent event) {
        if ("ORDER_REFUND".equals(event.eventType())) {
            String orderStatus = event.payload().path("orderStatus").asText();
            return switch (orderStatus) {
                case "PARTIAL_REFUNDED" -> NotificationType.ORDER_PARTIALLY_REFUNDED;
                case "ALL_REFUNDED" -> NotificationType.ORDER_REFUNDED;
                default -> throw new NotificationEventContractException("Invalid refund order status");
            };
        }
        try {
            return NotificationType.valueOf(event.eventType());
        } catch (IllegalArgumentException exception) {
            throw new NotificationEventContractException("Unsupported notification event type");
        }
    }
}
