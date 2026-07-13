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

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class RefundResultContextLoader {

	private final OrderRefundRepository refundRepository;
	private final OrderRepository orderRepository;

	public OrderRefund loadValidatedRefund(PaymentRefundResult result) {
		OrderRefund refund = refundRepository.findByIdForUpdate(result.refundId())
			.orElseThrow(() -> new OrderException(ErrorCode.ORDER_REFUND_REQUEST_CONFLICT));
		refund.validateResult(result.paymentId(), result.orderId(), result.totalRefundAmount());
		return refund;
	}

	public Order loadOrderForUpdate(UUID orderId) {
		return orderRepository.findByIdWithOrderProductsForUpdate(orderId)
			.orElseThrow(() -> new OrderException(ErrorCode.ORDER_NOT_FOUND));
	}
}
