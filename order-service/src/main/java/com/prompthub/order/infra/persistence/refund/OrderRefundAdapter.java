package com.prompthub.order.infra.persistence.refund;

import com.prompthub.order.domain.enums.OrderRefundStatus;
import com.prompthub.order.domain.model.OrderRefund;
import com.prompthub.order.domain.repository.OrderRefundRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class OrderRefundAdapter implements OrderRefundRepository {

	private final OrderRefundPersistence orderRefundPersistence;

	@Override
	public OrderRefund save(OrderRefund orderRefund) {
		return orderRefundPersistence.save(orderRefund);
	}

	@Override
	public Optional<OrderRefund> findByIdWithProduct(UUID refundId) {
		return orderRefundPersistence.findByIdWithProduct(refundId);
	}

	@Override
	public Optional<OrderRefund> findByPaymentIdAndOrderProductId(UUID paymentId, UUID orderProductId) {
		return orderRefundPersistence.findByPaymentIdAndOrderProductId(paymentId, orderProductId);
	}

	@Override
	public boolean existsByOrderIdAndStatus(UUID orderId, OrderRefundStatus status) {
		return orderRefundPersistence.existsByOrderIdAndStatus(orderId, status);
	}

	@Override
	public List<OrderRefund> findDueRequestedForUpdate(LocalDateTime now, int batchSize) {
		return orderRefundPersistence.findDueRequestedForUpdate(now, batchSize);
	}
}
