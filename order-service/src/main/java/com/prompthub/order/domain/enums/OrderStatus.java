package com.prompthub.order.domain.enums;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "주문 상태: PENDING(결제 대기), PAID(결제 완료), FAILED(결제 실패), CANCELED(취소), REFUND_REQUESTED(환불 요청), PARTIAL_REFUNDED(부분 환불), REFUNDED(전체 환불)")
public enum OrderStatus {
    PENDING,
    PAID,
    FAILED,
    CANCELED,
    REFUND_REQUESTED,
    PARTIAL_REFUNDED,
    REFUNDED;

    public boolean canTransitionTo(OrderStatus target) {
        return switch (this) {
            case PENDING -> target == PAID || target == FAILED || target == CANCELED;
            case FAILED -> target == PAID;
            case PAID -> target == REFUND_REQUESTED || target == REFUNDED;
            case REFUND_REQUESTED -> target == PARTIAL_REFUNDED || target == REFUNDED;
            case PARTIAL_REFUNDED -> target == REFUND_REQUESTED;
            case CANCELED, REFUNDED -> false;
        };
    }
}
