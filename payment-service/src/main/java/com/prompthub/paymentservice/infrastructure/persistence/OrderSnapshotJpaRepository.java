package com.prompthub.paymentservice.infrastructure.persistence;

import com.prompthub.paymentservice.domain.model.OrderSnapshot;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderSnapshotJpaRepository extends JpaRepository<OrderSnapshot, UUID> {
    Optional<OrderSnapshot> findByOrderId(UUID orderId);
}
