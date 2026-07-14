package com.prompthub.paymentservice.infrastructure.persistence;

import com.prompthub.paymentservice.domain.model.Refund;
import com.prompthub.paymentservice.domain.model.RefundStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundJpaRepository extends JpaRepository<Refund, UUID> {
    List<Refund> findByPaymentIdAndStatus(UUID paymentId, RefundStatus status);
    Optional<Refund> findByPaymentIdAndOrderProductId(UUID paymentId, UUID orderProductId);
}
