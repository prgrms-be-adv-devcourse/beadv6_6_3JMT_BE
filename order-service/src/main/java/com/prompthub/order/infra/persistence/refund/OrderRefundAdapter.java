package com.prompthub.order.infra.persistence.refund;

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

	private final OrderRefundPersistence persistence;

	@Override
	public OrderRefund save(OrderRefund refund) {
		return persistence.save(refund);
	}

	@Override
	public Optional<OrderRefund> findById(UUID refundId) {
		return persistence.findByIdWithProducts(refundId);
	}

	@Override
	public Optional<OrderRefund> findByIdForUpdate(UUID refundId) {
		return persistence.findByIdForUpdate(refundId)
			.flatMap(ignored -> persistence.findByIdWithProducts(refundId));
	}

	@Override
	public List<OrderRefund> findDueRefunds(LocalDateTime now, int batchSize) {
		return persistence.findDueRefunds(now, batchSize);
	}
}
