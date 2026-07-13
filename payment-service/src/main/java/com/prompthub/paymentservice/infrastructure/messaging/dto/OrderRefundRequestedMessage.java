package com.prompthub.paymentservice.infrastructure.messaging.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * order-events의 ORDER_REFUND_REQUESTED payload(EventMessage&lt;T&gt; 봉투 내부).
 * requestedAt은 order-service에서 존 없는 LocalDateTime으로 직렬화되므로, 소비 시 KST를 부여한다.
 */
public record OrderRefundRequestedMessage(
    UUID orderId,
    UUID orderProductId,
    UUID buyerId,
    int refundAmount,
    LocalDateTime requestedAt
) {}
