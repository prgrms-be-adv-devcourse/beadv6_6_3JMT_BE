package com.prompthub.payment.infrastructure.persistence;

import com.prompthub.payment.domain.model.Refund;
import com.prompthub.payment.domain.model.RefundStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundJpaRepository extends JpaRepository<Refund, UUID> {
    List<Refund> findByPaymentIdAndStatus(UUID paymentId, RefundStatus status);
    boolean existsByRefundRequestId(UUID refundRequestId);
}
