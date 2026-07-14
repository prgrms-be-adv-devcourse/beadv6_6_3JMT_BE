package com.prompthub.order.application.port;

import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderRefund;

import java.time.LocalDateTime;

public interface OrderRefundCompletedEventPort {
    void emit(OrderRefund refund, Order order, LocalDateTime refundedAt);
}
