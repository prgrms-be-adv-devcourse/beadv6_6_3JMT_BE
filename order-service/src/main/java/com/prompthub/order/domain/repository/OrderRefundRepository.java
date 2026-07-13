package com.prompthub.order.domain.repository;

import com.prompthub.order.domain.model.OrderRefund;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRefundRepository {

	OrderRefund save(OrderRefund refund);

	Optional<OrderRefund> findById(UUID refundId);

	Optional<OrderRefund> findByIdForUpdate(UUID refundId);

	List<OrderRefund> findDueRefunds(LocalDateTime now, int batchSize);
}
