package com.prompthub.paymentservice.domain.repository;

import com.prompthub.paymentservice.domain.model.Refund;
import com.prompthub.paymentservice.domain.model.RefundStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefundRepository {
    Refund save(Refund refund);
    Optional<Refund> findById(UUID id);
    List<Refund> findByPaymentIdAndStatus(UUID paymentId, RefundStatus status);
    Optional<Refund> findByPaymentIdAndOrderProductId(UUID paymentId, UUID orderProductId);
}
