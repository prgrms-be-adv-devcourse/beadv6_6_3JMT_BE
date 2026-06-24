package com.prompthub.paymentservice.domain.repository;

import com.prompthub.paymentservice.domain.model.Payment;
import com.prompthub.paymentservice.domain.model.PaymentStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository {
    Payment save(Payment payment);
    Payment saveAndFlush(Payment payment);
    Optional<Payment> findById(UUID id);
    Optional<Payment> findByIdempotencyKey(String idempotencyKey);
    List<Payment> findByStatusAndUpdatedAtBefore(PaymentStatus status, OffsetDateTime threshold);
}
