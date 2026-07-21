package com.prompthub.order.domain.enums;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "주문 상태: CREATED(생성), COMPLETED(결제 완료), FAILED(결제 실패), REFUND_REQUESTED(환불 요청), PARTIAL_REFUNDED(부분 환불), ALL_REFUNDED(전체 환불)")
public enum OrderStatus {
    CREATED,
    COMPLETED,
    FAILED,
    REFUND_REQUESTED,
    PARTIAL_REFUNDED,
    ALL_REFUNDED;

    public static final OrderStatus PENDING = CREATED;

    public static final OrderStatus PAID = COMPLETED;

    public static final OrderStatus CANCELED = FAILED;

    public static final OrderStatus REFUNDED = ALL_REFUNDED;

    public boolean canTransitionTo(OrderStatus target) {
        return switch (this) {
            case CREATED -> target == COMPLETED || target == FAILED;
            case FAILED -> target == COMPLETED;
            case COMPLETED, PARTIAL_REFUNDED -> target == REFUND_REQUESTED;
            case REFUND_REQUESTED -> target == PARTIAL_REFUNDED || target == ALL_REFUNDED;
            case ALL_REFUNDED -> false;
        };
    }
}
