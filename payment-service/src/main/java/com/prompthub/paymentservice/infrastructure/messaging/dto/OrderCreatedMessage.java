package com.prompthub.paymentservice.infrastructure.messaging.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * order-events의 ORDER_CREATED payload(EventMessage&lt;OrderCreatedPayload&gt; 봉투 내부).
 * order-service의 orderNumber/orderStatus 필드는 payment-service가 사용하지 않아 매핑하지 않는다.
 * createdAt은 order-service에서 존 없는 LocalDateTime으로 직렬화되므로, 소비 시 KST를 부여한다.
 */
public record OrderCreatedMessage(
    UUID orderId,
    UUID buyerId,
    int totalAmount,
    LocalDateTime createdAt
) {}
