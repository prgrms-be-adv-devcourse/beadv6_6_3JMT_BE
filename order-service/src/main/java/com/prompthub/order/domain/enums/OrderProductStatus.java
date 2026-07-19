package com.prompthub.order.domain.enums;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "주문 상품 상태: PENDING(결제 대기), PAID(결제 완료), FAILED(결제 실패), REFUNDED(환불)")
public enum OrderProductStatus {
    PENDING,
    PAID,
    FAILED,
    REFUNDED;

    public boolean canTransitionTo(OrderProductStatus target) {
        return switch (this) {
            case PENDING -> target == PAID || target == FAILED;
            case FAILED -> target == PAID;
            case PAID -> target == REFUNDED;
            case REFUNDED -> false;
        };
    }
}
