package com.prompthub.order.domain.repository;

import com.prompthub.order.domain.enums.OrderRefundStatus;
import com.prompthub.order.domain.model.OrderRefund;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRefundRepository {

	OrderRefund save(OrderRefund orderRefund);

	Optional<OrderRefund> findByIdWithProduct(UUID refundId);

	Optional<OrderRefund> findByPaymentIdAndOrderProductId(UUID paymentId, UUID orderProductId);

	boolean existsByOrderIdAndStatus(UUID orderId, OrderRefundStatus status);

	List<OrderRefund> findDueRequestedForUpdate(LocalDateTime now, int batchSize);
}
