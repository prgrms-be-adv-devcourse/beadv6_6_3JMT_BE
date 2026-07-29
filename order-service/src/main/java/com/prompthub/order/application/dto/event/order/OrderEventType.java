package com.prompthub.order.application.dto.event.order;

import com.prompthub.common.event.EventType;

public enum OrderEventType implements EventType {
    ORDER_CREATED,
    ORDER_PAID,
    ORDER_PAYMENT_FAILED,
    ORDER_EXPIRED,
    ORDER_REFUND_REQUESTED,
    ORDER_REFUND,
    ORDER_REFUND_FAILED;

    @Override
    public String code() {
        return name();
    }
}
