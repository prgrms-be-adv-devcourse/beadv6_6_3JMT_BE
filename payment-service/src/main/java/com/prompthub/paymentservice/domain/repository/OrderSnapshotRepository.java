package com.prompthub.paymentservice.domain.repository;

import com.prompthub.paymentservice.domain.model.OrderSnapshot;
import java.util.Optional;
import java.util.UUID;

public interface OrderSnapshotRepository {
    OrderSnapshot save(OrderSnapshot orderSnapshot);
    Optional<OrderSnapshot> findByOrderId(UUID orderId);
}
