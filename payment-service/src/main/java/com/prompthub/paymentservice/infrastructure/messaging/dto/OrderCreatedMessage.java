package com.prompthub.paymentservice.infrastructure.messaging.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * order-events의 ORDER_CREATED 평면 메시지 계약(§2.1 동결). envelope 미사용.
 * createdAt은 order-service에서 존 없는 LocalDateTime으로 직렬화되므로, 소비 시 KST를 부여한다.
 */
public record OrderCreatedMessage(
    String eventType,
    UUID orderId,
    UUID buyerId,
    int totalOrderAmount,
    LocalDateTime createdAt
) {}
