package com.prompthub.order.domain.repository;

import com.prompthub.order.domain.model.OrderRefund;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.LocalDateTime;

public interface OrderRefundRepository {

    OrderRefund save(OrderRefund refund);

    Optional<OrderRefund> findByIdForUpdate(UUID refundRequestId);

    List<OrderRefund> findDueRefunds(LocalDateTime now, int batchSize);

    List<OrderRefund> findAllByOrderIdWithProducts(UUID orderId);
}
