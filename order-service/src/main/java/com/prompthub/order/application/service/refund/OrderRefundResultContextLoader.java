package com.prompthub.order.application.service.refund;

import com.prompthub.order.application.dto.PaymentRefundResult;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderRefund;
import com.prompthub.order.domain.repository.OrderRefundRepository;
import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OrderRefundResultContextLoader {

    private final OrderRefundRepository orderRefundRepository;
    private final OrderRepository orderRepository;

    public Context loadForUpdate(PaymentRefundResult result) {
        OrderRefund refund = orderRefundRepository.findByIdForUpdate(result.refundRequestId())
            .orElseThrow(() -> new OrderException(ErrorCode.ORDER_NOT_FOUND));
        refund.requireMatches(result.paymentId(), result.orderId(), result.totalRefundAmount());
        Order order = orderRepository.findByIdWithOrderProductsForUpdate(result.orderId())
            .orElseThrow(() -> new OrderException(ErrorCode.ORDER_NOT_FOUND));
        return new Context(refund, order);
    }

    public record Context(OrderRefund refund, Order order) {
    }
}
