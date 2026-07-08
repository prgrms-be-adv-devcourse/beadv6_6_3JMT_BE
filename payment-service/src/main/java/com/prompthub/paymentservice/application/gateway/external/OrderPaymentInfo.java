package com.prompthub.paymentservice.application.gateway.external;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 결제에 필요한 주문 스냅샷 최소 정보. 이벤트/gRPC 어느 경로로 확보하든 동일 표현.
 */
public record OrderPaymentInfo(
    UUID orderId,
    UUID buyerId,
    int totalAmount,
    OffsetDateTime orderCreatedAt
) {}
