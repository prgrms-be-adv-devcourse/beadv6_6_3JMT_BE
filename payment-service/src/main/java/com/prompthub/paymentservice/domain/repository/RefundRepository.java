package com.prompthub.paymentservice.domain.repository;

import com.prompthub.paymentservice.domain.model.Refund;
import java.util.Optional;
import java.util.UUID;

public interface RefundRepository {
    Refund save(Refund refund);
    Optional<Refund> findById(UUID id);
    Optional<Refund> findByPaymentId(UUID paymentId);
}
