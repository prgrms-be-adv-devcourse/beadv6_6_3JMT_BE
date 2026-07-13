package com.prompthub.order.domain.enums;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "주문 상태: PENDING(결제 대기), PAID(결제 완료), FAILED(결제 실패), CANCELED(취소), PARTIALLY_REFUNDED(부분 환불), REFUNDED(전체 환불)")
public enum OrderStatus {
    PENDING,
    PAID,
    FAILED,
    CANCELED,
    PARTIALLY_REFUNDED,
    REFUNDED;

    public boolean canTransitionTo(OrderStatus target) {
        return switch (this) {
            case PENDING -> target == PAID || target == FAILED || target == CANCELED;
            case FAILED -> target == PAID;
            case PAID -> target == PARTIALLY_REFUNDED || target == REFUNDED;
            case PARTIALLY_REFUNDED -> target == REFUNDED;
            case CANCELED, REFUNDED -> false;
        };
    }
}
