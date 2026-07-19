package com.prompthub.paymentservice.infrastructure.messaging.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * order-events의 ORDER_REFUND_REQUESTED payload(EventMessage&lt;T&gt; 봉투 내부).
 * requestedAt은 order-service에서 존 없는 LocalDateTime으로 직렬화되므로, 소비 시 KST를 부여한다.
 * orderProductId는 order-service가 계속 함께 보내지만 payment-service 내부에서 쓰지 않는다(#398) —
 * 역직렬화 호환을 위해 필드는 남겨둔다. buyerId는 애초에 쓰인 적 없어 필드 자체를 제거했다(들어와도 파싱하지 않고 무시된다).
 */
public record OrderRefundRequestedMessage(
    UUID orderId,
    UUID orderProductId,
    UUID refundRequestId,
    int refundAmount,
    LocalDateTime requestedAt
) {}
