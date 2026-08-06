package com.prompthub.payment.domain.repository;

import com.prompthub.payment.domain.model.Refund;
import com.prompthub.payment.domain.model.RefundStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefundRepository {
    Refund save(Refund refund);
    Optional<Refund> findById(UUID id);
    List<Refund> findByPaymentIdAndStatus(UUID paymentId, RefundStatus status);
    boolean existsByRefundRequestId(UUID refundRequestId);
}
