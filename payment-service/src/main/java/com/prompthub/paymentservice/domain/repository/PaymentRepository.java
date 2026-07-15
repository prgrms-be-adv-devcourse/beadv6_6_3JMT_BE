package com.prompthub.paymentservice.domain.repository;

import com.prompthub.paymentservice.domain.model.Payment;
import com.prompthub.paymentservice.domain.model.PaymentStatus;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository {
    Payment save(Payment payment);
    Payment saveAndFlush(Payment payment);
    Optional<Payment> findById(UUID id);
    Optional<Payment> findByIdForUpdate(UUID id);
    boolean existsByOrderIdAndStatusIn(UUID orderId, Collection<PaymentStatus> statuses);
    Optional<Payment> findByOrderIdAndStatusInForUpdate(UUID orderId, Collection<PaymentStatus> statuses);
    Optional<Payment> findLatestByOrderId(UUID orderId);
}
