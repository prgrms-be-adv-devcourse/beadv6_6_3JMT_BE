package com.prompthub.order.infra.persistence.order;

import com.prompthub.order.application.dto.OrderPaymentListProjection;
import com.prompthub.order.domain.model.OrderPayment;
import com.prompthub.order.domain.repository.OrderPaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class OrderPaymentAdapter implements OrderPaymentRepository {

	private final OrderPaymentPersistence orderPaymentPersistence;

	@Override
	public OrderPayment save(OrderPayment orderPayment) {
		return orderPaymentPersistence.save(orderPayment);
	}

	@Override
	public boolean existsByOrderId(UUID orderId) {
		return orderPaymentPersistence.existsByOrderId(orderId);
	}

	@Override
	public boolean existsByPaymentId(UUID paymentId) {
		return orderPaymentPersistence.existsByPaymentId(paymentId);
	}

	@Override
	public Page<OrderPaymentListProjection> searchOrderPayments(UUID buyerId, Pageable pageable) {
		return orderPaymentPersistence.searchOrderPayments(buyerId, pageable);
	}
}
